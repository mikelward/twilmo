package app.twilmo

import app.twilmo.domain.token.CachedToken
import app.twilmo.domain.token.DialTokenFlow
import app.twilmo.domain.token.DialTokenOutcome
import app.twilmo.domain.token.MintFailure
import app.twilmo.domain.token.TokenMinter
import app.twilmo.domain.token.TokenReadiness
import app.twilmo.domain.token.TransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DialTokenFlowTest {

    private val start = 1_000_000_000L
    private val authority = "authority-fingerprint-1"

    private val goodBody = """{"token":"fresh-jwt","expiresInSeconds":3600,"identity":"twilmo"}"""
    private val mintOk = TokenMinter { TransportResult.Http(200, goodBody) }
    private val mintDown = TokenMinter { TransportResult.NetworkFailure("UnknownHostException") }

    private fun tokenExpiringIn(remainingMillis: Long) =
        CachedToken("cached-jwt", start + remainingMillis, authority)

    private fun fixedClock() = { start }

    @Test
    fun freshCacheDialsWithoutMintOrRefresh() = runTest {
        val cached = tokenExpiringIn(TokenReadiness.REFRESH_BELOW_REMAINING_MILLIS)
        // A minter that would fail proves it is never called on this path.
        val outcome = DialTokenFlow.tokenForDial(cached, authority, "twilmo", mintDown, fixedClock())
        assertEquals(
            DialTokenOutcome.Ready(cached, startBackgroundRefresh = false),
            outcome,
        )
    }

    @Test
    fun agingCacheDialsAndAsksForBackgroundRefresh() = runTest {
        val cached = tokenExpiringIn(TokenReadiness.REFRESH_BELOW_REMAINING_MILLIS - 1)
        val outcome = DialTokenFlow.tokenForDial(cached, authority, "twilmo", mintDown, fixedClock())
        assertEquals(
            DialTokenOutcome.Ready(cached, startBackgroundRefresh = true),
            outcome,
        )
    }

    @Test
    fun noCacheMintsAndMarksTheTokenFresh() = runTest {
        val outcome = DialTokenFlow.tokenForDial(null, authority, "twilmo", mintOk, fixedClock())
        assertEquals(
            DialTokenOutcome.Ready(
                CachedToken("fresh-jwt", start + 3_600_000L, authority),
                startBackgroundRefresh = false,
                freshlyMinted = true,
            ),
            outcome,
        )
    }

    @Test
    fun noCacheAndFailedMintFailsTheDialWithTheReason() = runTest {
        val outcome = DialTokenFlow.tokenForDial(null, authority, "twilmo", mintDown, fixedClock())
        assertEquals(
            DialTokenOutcome.NoToken(MintFailure.NetworkUnavailable("UnknownHostException")),
            outcome,
        )
    }

    @Test
    fun thinCacheSavesTheCallWhenTheMintFailsAndTheFailureStaysVisible() = runTest {
        val cached = tokenExpiringIn(30_000L)
        val outcome = DialTokenFlow.tokenForDial(cached, authority, "twilmo", mintDown, fixedClock())
        assertEquals(
            DialTokenOutcome.Ready(
                cached,
                startBackgroundRefresh = false,
                degradedBy = MintFailure.NetworkUnavailable("UnknownHostException"),
            ),
            outcome,
        )
    }

    @Test
    fun lastResortThatExpiresDuringTheMintFailsTheDial() = runTest {
        val cached = tokenExpiringIn(30_000L)
        // The clock advances past the token's expiry between the decision
        // and the post-mint re-check — a slow, failing mint.
        var reads = 0
        val clock = {
            reads++
            if (reads == 1) start else start + 30_001L
        }
        val outcome = DialTokenFlow.tokenForDial(cached, authority, "twilmo", mintDown, clock)
        assertEquals(
            DialTokenOutcome.NoToken(MintFailure.NetworkUnavailable("UnknownHostException")),
            outcome,
        )
    }

    @Test
    fun wrongAuthorityCacheMintsFreshInsteadOfFallingBack() = runTest {
        val cached = CachedToken("old-jwt", start + 30_000L, "authority-fingerprint-2")
        val outcome = DialTokenFlow.tokenForDial(cached, authority, "twilmo", mintDown, fixedClock())
        // No last resort: a stale-authority token is never used.
        assertEquals(
            DialTokenOutcome.NoToken(MintFailure.NetworkUnavailable("UnknownHostException")),
            outcome,
        )
    }
}
