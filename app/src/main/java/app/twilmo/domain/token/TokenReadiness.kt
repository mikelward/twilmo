package app.twilmo.domain.token

/**
 * A minted Voice access token with its expiry and the credential set that
 * minted it. The token string is a credential: [toString] deliberately omits
 * it so the value can never ride a log line, an exception message, or a
 * debugger dump into an artifact (`AGENTS.md` Privacy). [authorityKey] is an
 * opaque fingerprint (never the raw endpoint or identity) of the
 * configuration that minted the token; it too stays out of [toString] since
 * its derivation is the client's business.
 */
data class CachedToken(
    val token: String,
    val expiresAtMillis: Long,
    val authorityKey: String,
) {
    override fun toString(): String = "CachedToken(expiresAtMillis=$expiresAtMillis)"
}

/** What to do about the access token when the user dials. */
sealed interface TokenDecision {
    /** The cache is comfortably valid — connect with it now. */
    data class UseCached(val token: CachedToken) : TokenDecision

    /**
     * The cache is valid but aging — connect with it now and refresh in the
     * background, so this call is fast and the next one finds a fresh cache.
     */
    data class UseCachedAndRefresh(val token: CachedToken) : TokenDecision

    /**
     * No comfortably valid token — mint now; the dial waits on the mint
     * (SPEC "Outbound"). [lastResort] is a thin-but-unexpired token for the
     * same authority: if the mint fails (endpoint down, no network), a call
     * on a thin token beats no call — see
     * [TokenReadiness.lastResortAfterMintFailure]. A token from a different
     * credential set is never a last resort.
     */
    data class MintNow(val lastResort: CachedToken? = null) : TokenDecision
}

/**
 * Pure policy for keeping a token ready ahead of dialing (SPEC "Outbound":
 * cache plus opportunistic refresh, mint on demand as the fallback). Callers
 * pass the clock, so every decision is testable and deterministic.
 *
 * A cached token counts only while its [CachedToken.authorityKey] matches
 * the active credential set: after the user changes identity, account, or
 * endpoint, the old cache is treated as absent everywhere — a dial mints
 * fresh, a warm-up refreshes, and the stale-authority token is never a
 * fallback. That keeps a credentials change from placing the next call on
 * the previous configuration.
 *
 * The battery model shapes the refresh triggers: refreshes happen at moments
 * the app is already awake for its own reasons (a dial, the app opening,
 * setup being saved) — never on a schedule of their own.
 */
object TokenReadiness {
    /**
     * Below this remaining validity a cached token is not *preferred* for a
     * connect: it only has to outlive call *setup* (the connect is
     * authenticated once; an established call survives token expiry), but a
     * token this close to its end risks expiring mid-handshake on a slow
     * network. It stays available as the last resort should the mint fail.
     */
    const val MIN_USABLE_REMAINING_MILLIS: Long = 60_000L

    /**
     * Below this remaining validity a token still dials — no reason to make
     * the user wait — but a background refresh starts so the *next* dial
     * finds a fresh cache (principle 3: do the work ahead of time).
     */
    const val REFRESH_BELOW_REMAINING_MILLIS: Long = 10 * 60_000L

    /** The decision at the moment the user dials. */
    fun atDial(
        cached: CachedToken?,
        activeAuthorityKey: String,
        nowMillis: Long,
    ): TokenDecision {
        val usable = cached?.takeIf { it.authorityKey == activeAuthorityKey }
            ?: return TokenDecision.MintNow()
        val remaining = usable.expiresAtMillis - nowMillis
        return when {
            remaining <= 0 -> TokenDecision.MintNow()
            remaining < MIN_USABLE_REMAINING_MILLIS -> TokenDecision.MintNow(lastResort = usable)
            remaining < REFRESH_BELOW_REMAINING_MILLIS -> TokenDecision.UseCachedAndRefresh(usable)
            else -> TokenDecision.UseCached(usable)
        }
    }

    /**
     * After a failed mint, the one acceptable degradation: connect on the
     * [TokenDecision.MintNow.lastResort] token if it is *still* unexpired at
     * this moment — a call on a thin token beats a failed call (principle
     * 1). Returns null when there is nothing valid left, and the caller
     * surfaces the mint failure's reason at dial time instead.
     */
    fun lastResortAfterMintFailure(lastResort: CachedToken?, nowMillis: Long): CachedToken? =
        lastResort?.takeIf { it.expiresAtMillis > nowMillis }

    /**
     * Whether a warm-up moment (app opened, credentials saved) should spend
     * a network call on refreshing the cache. A comfortably fresh cache for
     * the active authority skips it — the refresh would buy nothing. A
     * credentials change makes the old cache a mismatch, so saving new
     * credentials always refreshes.
     */
    fun shouldRefreshOpportunistically(
        cached: CachedToken?,
        activeAuthorityKey: String,
        nowMillis: Long,
    ): Boolean {
        val usable = cached?.takeIf { it.authorityKey == activeAuthorityKey }
        return usable == null ||
            usable.expiresAtMillis - nowMillis < REFRESH_BELOW_REMAINING_MILLIS
    }
}
