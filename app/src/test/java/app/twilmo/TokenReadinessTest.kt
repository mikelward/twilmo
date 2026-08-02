package app.twilmo

import app.twilmo.domain.token.CachedToken
import app.twilmo.domain.token.TokenDecision
import app.twilmo.domain.token.TokenReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenReadinessTest {

    private val now = 1_000_000_000L
    private val authority = "authority-fingerprint-1"
    private val otherAuthority = "authority-fingerprint-2"

    private fun tokenExpiringIn(remainingMillis: Long, authorityKey: String = authority) =
        CachedToken(
            token = "an-access-token",
            expiresAtMillis = now + remainingMillis,
            authorityKey = authorityKey,
        )

    // --- atDial ---

    @Test
    fun noCacheMintsNowWithNoFallback() {
        assertEquals(TokenDecision.MintNow(), TokenReadiness.atDial(null, authority, now))
    }

    @Test
    fun expiredCacheMintsNowWithNoFallback() {
        assertEquals(
            TokenDecision.MintNow(),
            TokenReadiness.atDial(tokenExpiringIn(0L), authority, now),
        )
    }

    @Test
    fun thinCacheMintsNowButStaysAvailableAsLastResort() {
        val cached = tokenExpiringIn(TokenReadiness.MIN_USABLE_REMAINING_MILLIS - 1)
        assertEquals(
            TokenDecision.MintNow(lastResort = cached),
            TokenReadiness.atDial(cached, authority, now),
        )
    }

    @Test
    fun agingCacheDialsAndRefreshes() {
        val cached = tokenExpiringIn(TokenReadiness.MIN_USABLE_REMAINING_MILLIS)
        assertEquals(
            TokenDecision.UseCachedAndRefresh(cached),
            TokenReadiness.atDial(cached, authority, now),
        )
    }

    @Test
    fun agingCacheJustUnderRefreshThresholdDialsAndRefreshes() {
        val cached = tokenExpiringIn(TokenReadiness.REFRESH_BELOW_REMAINING_MILLIS - 1)
        assertEquals(
            TokenDecision.UseCachedAndRefresh(cached),
            TokenReadiness.atDial(cached, authority, now),
        )
    }

    @Test
    fun freshCacheDialsWithoutRefresh() {
        val cached = tokenExpiringIn(TokenReadiness.REFRESH_BELOW_REMAINING_MILLIS)
        assertEquals(
            TokenDecision.UseCached(cached),
            TokenReadiness.atDial(cached, authority, now),
        )
    }

    // --- Authority binding: a credentials change orphans the old cache ---

    @Test
    fun freshCacheForAnotherAuthorityMintsNowWithNoFallback() {
        val cached = tokenExpiringIn(
            TokenReadiness.REFRESH_BELOW_REMAINING_MILLIS,
            authorityKey = otherAuthority,
        )
        assertEquals(TokenDecision.MintNow(), TokenReadiness.atDial(cached, authority, now))
    }

    @Test
    fun thinCacheForAnotherAuthorityIsNeverALastResort() {
        val cached = tokenExpiringIn(
            TokenReadiness.MIN_USABLE_REMAINING_MILLIS - 1,
            authorityKey = otherAuthority,
        )
        assertEquals(TokenDecision.MintNow(), TokenReadiness.atDial(cached, authority, now))
    }

    @Test
    fun savingNewCredentialsAlwaysRefreshesDespiteAFreshOldCache() {
        val cached = tokenExpiringIn(
            TokenReadiness.REFRESH_BELOW_REMAINING_MILLIS,
            authorityKey = otherAuthority,
        )
        assertTrue(TokenReadiness.shouldRefreshOpportunistically(cached, authority, now))
    }

    // --- Mint-failure fallback ---

    @Test
    fun lastResortIsUsedWhileStillValid() {
        val cached = tokenExpiringIn(30_000L)
        assertEquals(
            cached,
            TokenReadiness.lastResortAfterMintFailure(cached, now + 29_999L),
        )
    }

    @Test
    fun lastResortIsDroppedOnceExpired() {
        val cached = tokenExpiringIn(30_000L)
        assertNull(TokenReadiness.lastResortAfterMintFailure(cached, now + 30_000L))
    }

    @Test
    fun lastResortIsNullWhenThereWasNothingToFallBackOn() {
        assertNull(TokenReadiness.lastResortAfterMintFailure(null, now))
    }

    // --- Opportunistic warm-up refresh ---

    @Test
    fun warmUpRefreshesWhenThereIsNoCache() {
        assertTrue(TokenReadiness.shouldRefreshOpportunistically(null, authority, now))
    }

    @Test
    fun warmUpRefreshesAThinCache() {
        val cached = tokenExpiringIn(TokenReadiness.REFRESH_BELOW_REMAINING_MILLIS - 1)
        assertTrue(TokenReadiness.shouldRefreshOpportunistically(cached, authority, now))
    }

    @Test
    fun warmUpSkipsAFreshCache() {
        val cached = tokenExpiringIn(TokenReadiness.REFRESH_BELOW_REMAINING_MILLIS)
        assertFalse(TokenReadiness.shouldRefreshOpportunistically(cached, authority, now))
    }

    // --- Privacy ---

    @Test
    fun cachedTokenNeverPrintsTheCredentialOrAuthority() {
        val cached = tokenExpiringIn(60_000L)
        assertFalse(cached.toString().contains("an-access-token"))
        assertFalse(cached.toString().contains(authority))
        assertTrue(cached.toString().contains(cached.expiresAtMillis.toString()))
    }
}
