package app.twilmo.domain.token

import org.json.JSONException
import org.json.JSONObject

/**
 * What the wire gave back, before any interpretation. The real transport is
 * a thin HTTPS call to the token endpoint (docs/twilio-setup.md contract);
 * tests use a fake. [NetworkFailure.detail] must arrive sanitized — an
 * exception class or short cause, never a URL, header, or body.
 */
sealed interface TransportResult {
    data class Http(val statusCode: Int, val body: String) : TransportResult
    data class NetworkFailure(val detail: String) : TransportResult
}

/** The one call the minter makes; the driver owns endpoint, secret, and I/O. */
fun interface TokenTransport {
    suspend fun requestToken(): TransportResult
}

/**
 * Why a mint produced no token. [debugReason] is the one sanitized line the
 * debug log records — it never contains the response body (which could
 * carry a token), a URL, or a secret.
 */
sealed interface MintFailure {
    /** The endpoint rejected the shared secret — a setup problem, not a transient. */
    data object BadSecret : MintFailure

    /** The endpoint answered, but not with a token (5xx, unexpected status). */
    data class EndpointError(val statusCode: Int) : MintFailure

    /** The endpoint was never reached. */
    data class NetworkUnavailable(val detail: String) : MintFailure

    /** A 200 whose body wasn't the pinned contract shape. */
    data object MalformedResponse : MintFailure

    /**
     * The endpoint minted for a different identity than the app is
     * configured with — a configuration mismatch that would register the
     * device under the wrong identity and silently stop incoming calls.
     */
    data object IdentityMismatch : MintFailure

    val debugReason: String
        get() = when (this) {
            BadSecret -> "token endpoint rejected the shared secret"
            is EndpointError -> "token endpoint returned HTTP $statusCode"
            is NetworkUnavailable -> "no route to token endpoint: $detail"
            MalformedResponse -> "token endpoint response was not the expected shape"
            IdentityMismatch -> "token endpoint minted for a different identity than configured"
        }
}

sealed interface MintResult {
    data class Minted(val token: CachedToken) : MintResult
    data class Failed(val failure: MintFailure) : MintResult
}

/**
 * Maps the token endpoint's responses (docs/twilio-setup.md: `{token,
 * expiresInSeconds, identity}`, 401 for a bad secret) onto [MintResult].
 * Pure except for the injected transport; the clock is a parameter, so
 * expiry math is deterministic under test. The minted token is stamped with
 * the active authority key, binding it to the credential set that minted it
 * (see [TokenReadiness]).
 */
class TokenMinter(private val transport: TokenTransport) {

    suspend fun mint(
        activeAuthorityKey: String,
        expectedIdentity: String,
        nowMillis: Long,
    ): MintResult =
        when (val result = transport.requestToken()) {
            is TransportResult.NetworkFailure ->
                MintResult.Failed(MintFailure.NetworkUnavailable(result.detail))
            is TransportResult.Http -> when (result.statusCode) {
                200 -> parse(result.body, activeAuthorityKey, expectedIdentity, nowMillis)
                401 -> MintResult.Failed(MintFailure.BadSecret)
                else -> MintResult.Failed(MintFailure.EndpointError(result.statusCode))
            }
        }

    private fun parse(
        body: String,
        authorityKey: String,
        expectedIdentity: String,
        nowMillis: Long,
    ): MintResult {
        val parsed = try {
            val json = JSONObject(body)
            Triple(
                json.getString("token"),
                json.getLong("expiresInSeconds"),
                json.getString("identity"),
            )
        } catch (e: JSONException) {
            // The body is deliberately not logged — a near-miss response
            // could still contain a token. The shape mismatch is the whole
            // diagnosable fact.
            null
        }
        val (token, expiresInSeconds, identity) = parsed
            ?: return MintResult.Failed(MintFailure.MalformedResponse)
        if (token.isEmpty() || expiresInSeconds <= 0) {
            return MintResult.Failed(MintFailure.MalformedResponse)
        }
        if (identity != expectedIdentity) {
            // A token for someone else's identity would still authenticate —
            // and register the device under the wrong line. Refuse it here,
            // loudly, instead of discovering it as a phone that never rings.
            return MintResult.Failed(MintFailure.IdentityMismatch)
        }
        return MintResult.Minted(
            CachedToken(
                token = token,
                expiresAtMillis = nowMillis + expiresInSeconds * 1000,
                authorityKey = authorityKey,
            ),
        )
    }
}
