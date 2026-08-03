package app.twilmo.domain.config

import java.net.URI
import java.net.URISyntaxException

/**
 * Field-level problems the setup screen must explain before saving. One
 * error per field at a time — the first thing wrong with it.
 */
enum class SetupField { ENDPOINT, IDENTITY, SECRET }

enum class SetupError {
    /** The field is empty or whitespace. */
    MISSING,

    /** The endpoint isn't an https URL — the secret must never ride plaintext. */
    ENDPOINT_NOT_HTTPS,

    /** The endpoint isn't parseable as a URL at all. */
    ENDPOINT_NOT_A_URL,

    /**
     * The endpoint embeds a username/password. The endpoint URL rides
     * platform backup (SPEC "Persistence"), so an embedded credential
     * would transit cloud backup — exactly what the split-store design
     * exists to prevent; the client secret field is the credential's home.
     */
    ENDPOINT_HAS_USERINFO,
}

/**
 * Pure validation for the setup form (SPEC "Outbound": misconfiguration is
 * surfaced at save time, not discovered as a failed call later). Returns an
 * empty map when the form is saveable.
 */
object SetupValidation {

    fun validate(
        endpointUrl: String,
        identity: String,
        secret: String,
    ): Map<SetupField, SetupError> = buildMap {
        validateEndpoint(endpointUrl)?.let { put(SetupField.ENDPOINT, it) }
        if (identity.isBlank()) put(SetupField.IDENTITY, SetupError.MISSING)
        if (secret.isBlank()) put(SetupField.SECRET, SetupError.MISSING)
    }

    /** Validation for the restore path, where only the secret is asked for. */
    fun validateSecretOnly(secret: String): Map<SetupField, SetupError> = buildMap {
        if (secret.isBlank()) put(SetupField.SECRET, SetupError.MISSING)
    }

    private fun validateEndpoint(endpointUrl: String): SetupError? {
        val trimmed = endpointUrl.trim()
        if (trimmed.isEmpty()) return SetupError.MISSING
        // java.net.URI, not URL: URI parses without touching the network
        // (URL's equals() resolves DNS) and rejects illegal characters, so
        // "https://host name/x" fails here instead of at the first mint.
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return SetupError.ENDPOINT_NOT_A_URL
        }
        val scheme = uri.scheme ?: return SetupError.ENDPOINT_NOT_A_URL
        if (!scheme.equals("https", ignoreCase = true)) return SetupError.ENDPOINT_NOT_HTTPS
        // A scheme with no usable host ("https://?token", "https://#f",
        // "https:///path") would only fail later, at dial time.
        if (uri.host.isNullOrBlank()) return SetupError.ENDPOINT_NOT_A_URL
        // URI doesn't range-check the port, but no TCP destination exists
        // outside 1..65535 — refuse it here instead of at the first mint.
        if (uri.port != -1 && uri.port !in 1..65535) return SetupError.ENDPOINT_NOT_A_URL
        // A user:password@ authority would put a credential into the
        // backup-included config store; see ENDPOINT_HAS_USERINFO.
        if (uri.rawUserInfo != null) return SetupError.ENDPOINT_HAS_USERINFO
        return null
    }
}
