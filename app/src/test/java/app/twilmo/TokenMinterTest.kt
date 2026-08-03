package app.twilmo

import app.twilmo.domain.token.CachedToken
import app.twilmo.domain.token.MintFailure
import app.twilmo.domain.token.MintResult
import app.twilmo.domain.token.TokenMinter
import app.twilmo.domain.token.TransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TokenMinterTest {

    private val now = 1_000_000_000L
    private val authority = "authority-fingerprint-1"

    private fun minterReturning(result: TransportResult) = TokenMinter { result }

    @Test
    fun aContractShapedResponseMints() = runTest {
        val body = """{"token":"a-jwt","expiresInSeconds":3600,"identity":"twilmo"}"""
        val result = minterReturning(TransportResult.Http(200, body)).mint(authority, "twilmo", now)
        assertEquals(
            MintResult.Minted(
                CachedToken(
                    token = "a-jwt",
                    expiresAtMillis = now + 3_600_000L,
                    authorityKey = authority,
                ),
            ),
            result,
        )
    }

    @Test
    fun a401IsABadSecret() = runTest {
        val result = minterReturning(TransportResult.Http(401, """{"error":"unauthorized"}"""))
            .mint(authority, "twilmo", now)
        assertEquals(MintResult.Failed(MintFailure.BadSecret), result)
    }

    @Test
    fun otherStatusesAreEndpointErrorsCarryingTheCode() = runTest {
        val result = minterReturning(TransportResult.Http(503, "unavailable"))
            .mint(authority, "twilmo", now)
        assertEquals(MintResult.Failed(MintFailure.EndpointError(503)), result)
    }

    @Test
    fun aNetworkFailurePropagatesItsSanitizedDetail() = runTest {
        val result = minterReturning(TransportResult.NetworkFailure("UnknownHostException"))
            .mint(authority, "twilmo", now)
        assertEquals(
            MintResult.Failed(MintFailure.NetworkUnavailable("UnknownHostException")),
            result,
        )
    }

    @Test
    fun nonJsonBodyIsMalformed() = runTest {
        val result = minterReturning(TransportResult.Http(200, "<html>proxy error</html>"))
            .mint(authority, "twilmo", now)
        assertEquals(MintResult.Failed(MintFailure.MalformedResponse), result)
    }

    @Test
    fun missingFieldIsMalformed() = runTest {
        val result = minterReturning(TransportResult.Http(200, """{"token":"a-jwt"}"""))
            .mint(authority, "twilmo", now)
        assertEquals(MintResult.Failed(MintFailure.MalformedResponse), result)
    }

    @Test
    fun emptyTokenIsMalformed() = runTest {
        val body = """{"token":"","expiresInSeconds":3600}"""
        val result = minterReturning(TransportResult.Http(200, body)).mint(authority, "twilmo", now)
        assertEquals(MintResult.Failed(MintFailure.MalformedResponse), result)
    }

    @Test
    fun nonPositiveExpiryIsMalformed() = runTest {
        val body = """{"token":"a-jwt","expiresInSeconds":0}"""
        val result = minterReturning(TransportResult.Http(200, body)).mint(authority, "twilmo", now)
        assertEquals(MintResult.Failed(MintFailure.MalformedResponse), result)
    }

    @Test
    fun missingIdentityIsMalformed() = runTest {
        val body = """{"token":"a-jwt","expiresInSeconds":3600}"""
        val result = minterReturning(TransportResult.Http(200, body)).mint(authority, "twilmo", now)
        assertEquals(MintResult.Failed(MintFailure.MalformedResponse), result)
    }

    @Test
    fun mismatchedIdentityIsRefusedAsAConfigurationFailure() = runTest {
        val body = """{"token":"a-jwt","expiresInSeconds":3600,"identity":"someone-else"}"""
        val result = minterReturning(TransportResult.Http(200, body)).mint(authority, "twilmo", now)
        assertEquals(MintResult.Failed(MintFailure.IdentityMismatch), result)
    }

    @Test
    fun debugReasonsNeverCarryTheBodyOrToken() = runTest {
        // A malformed 200 whose body embeds a token-looking value: the
        // failure's loggable reason must not contain any of it.
        val body = """{"token":"secret-jwt-value","expiresInSeconds":"not-a-number-at-all"}"""
        val result = minterReturning(TransportResult.Http(200, body)).mint(authority, "twilmo", now)
        val failure = (result as MintResult.Failed).failure
        assertFalse(failure.debugReason.contains("secret-jwt-value"))
        assertFalse(failure.debugReason.contains("not-a-number-at-all"))
    }

    @Test
    fun everyFailureHasAOneLineDebugReason() {
        val failures = listOf(
            MintFailure.BadSecret,
            MintFailure.EndpointError(500),
            MintFailure.NetworkUnavailable("SocketTimeoutException"),
            MintFailure.MalformedResponse,
            MintFailure.IdentityMismatch,
        )
        for (failure in failures) {
            val reason = failure.debugReason
            assertFalse("$failure has a blank reason", reason.isBlank())
            assertFalse("$failure reason spans lines", reason.contains('\n'))
        }
    }
}
