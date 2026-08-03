package app.twilmo.domain.token

/**
 * How a dial ends up with (or without) a token, composing [TokenReadiness]
 * with one mint attempt. Every degraded path stays visible: a call saved by
 * the last-resort token still carries the mint failure so it reaches the
 * debug log (a degraded decision is reported, not swallowed).
 */
sealed interface DialTokenOutcome {
    data class Ready(
        val token: CachedToken,
        /** True when the policy asked for a background refresh alongside the dial. */
        val startBackgroundRefresh: Boolean,
        /** True when this token is fresh from the mint and should replace the cache. */
        val freshlyMinted: Boolean = false,
        /**
         * Set when the mint failed and a thin cached token saved the call.
         * The failure is still reported — the user's next dial may not be
         * this lucky, and the reason must not vanish just because this one
         * connected.
         */
        val degradedBy: MintFailure? = null,
    ) : DialTokenOutcome

    /** No token and no valid fallback: the dial fails with this reason, shown to the user. */
    data class NoToken(val failure: MintFailure) : DialTokenOutcome
}

object DialTokenFlow {
    /**
     * The dial-time sequence: consult the policy; mint when asked; on a
     * failed mint, fall back to the still-unexpired last resort — re-checked
     * against the clock *after* the mint, since minting takes real time.
     */
    suspend fun tokenForDial(
        cached: CachedToken?,
        activeAuthorityKey: String,
        expectedIdentity: String,
        minter: TokenMinter,
        nowMillis: () -> Long,
    ): DialTokenOutcome =
        when (val decision = TokenReadiness.atDial(cached, activeAuthorityKey, nowMillis())) {
            is TokenDecision.UseCached ->
                DialTokenOutcome.Ready(decision.token, startBackgroundRefresh = false)
            is TokenDecision.UseCachedAndRefresh ->
                DialTokenOutcome.Ready(decision.token, startBackgroundRefresh = true)
            is TokenDecision.MintNow ->
                when (
                    val minted =
                        minter.mint(activeAuthorityKey, expectedIdentity, nowMillis())
                ) {
                    is MintResult.Minted ->
                        DialTokenOutcome.Ready(
                            minted.token,
                            startBackgroundRefresh = false,
                            freshlyMinted = true,
                        )
                    is MintResult.Failed -> {
                        val lastResort = TokenReadiness.lastResortAfterMintFailure(
                            decision.lastResort,
                            nowMillis(),
                        )
                        if (lastResort != null) {
                            DialTokenOutcome.Ready(
                                lastResort,
                                startBackgroundRefresh = false,
                                degradedBy = minted.failure,
                            )
                        } else {
                            DialTokenOutcome.NoToken(minted.failure)
                        }
                    }
                }
        }
}
