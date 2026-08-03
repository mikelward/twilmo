package app.twilmo

import app.twilmo.domain.config.SetupError
import app.twilmo.domain.config.SetupField
import app.twilmo.domain.config.SetupValidation
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupValidationTest {

    private val goodEndpoint = "https://example.twil.io/token"

    @Test
    fun aCompleteFormPasses() {
        assertEquals(
            emptyMap<SetupField, SetupError>(),
            SetupValidation.validate(goodEndpoint, "twilmo", "a-secret"),
        )
    }

    @Test
    fun everyBlankFieldIsReportedTogether() {
        assertEquals(
            mapOf(
                SetupField.ENDPOINT to SetupError.MISSING,
                SetupField.IDENTITY to SetupError.MISSING,
                SetupField.SECRET to SetupError.MISSING,
            ),
            SetupValidation.validate("  ", "", ""),
        )
    }

    @Test
    fun httpEndpointIsRefused() {
        assertEquals(
            mapOf(SetupField.ENDPOINT to SetupError.ENDPOINT_NOT_HTTPS),
            SetupValidation.validate("http://example.twil.io/token", "twilmo", "s"),
        )
    }

    @Test
    fun schemelessEndpointIsNotAUrl() {
        assertEquals(
            mapOf(SetupField.ENDPOINT to SetupError.ENDPOINT_NOT_A_URL),
            SetupValidation.validate("example.twil.io/token", "twilmo", "s"),
        )
    }

    @Test
    fun schemeOnlyEndpointIsNotAUrl() {
        assertEquals(
            mapOf(SetupField.ENDPOINT to SetupError.ENDPOINT_NOT_A_URL),
            SetupValidation.validate("https://", "twilmo", "s"),
        )
    }

    @Test
    fun hostlessEndpointIsNotAUrl() {
        assertEquals(
            mapOf(SetupField.ENDPOINT to SetupError.ENDPOINT_NOT_A_URL),
            SetupValidation.validate("https:///token", "twilmo", "s"),
        )
    }

    @Test
    fun queryOnlyAuthorityIsNotAUrl() {
        assertEquals(
            mapOf(SetupField.ENDPOINT to SetupError.ENDPOINT_NOT_A_URL),
            SetupValidation.validate("https://?token", "twilmo", "s"),
        )
    }

    @Test
    fun fragmentOnlyAuthorityIsNotAUrl() {
        assertEquals(
            mapOf(SetupField.ENDPOINT to SetupError.ENDPOINT_NOT_A_URL),
            SetupValidation.validate("https://#fragment", "twilmo", "s"),
        )
    }

    @Test
    fun illegalCharactersInTheHostAreNotAUrl() {
        assertEquals(
            mapOf(SetupField.ENDPOINT to SetupError.ENDPOINT_NOT_A_URL),
            SetupValidation.validate("https://host name/token", "twilmo", "s"),
        )
    }

    @Test
    fun portOutsideTheTcpRangeIsNotAUrl() {
        assertEquals(
            mapOf(SetupField.ENDPOINT to SetupError.ENDPOINT_NOT_A_URL),
            SetupValidation.validate("https://example.twil.io:99999/token", "twilmo", "s"),
        )
        assertEquals(
            mapOf(SetupField.ENDPOINT to SetupError.ENDPOINT_NOT_A_URL),
            SetupValidation.validate("https://example.twil.io:0/token", "twilmo", "s"),
        )
    }

    @Test
    fun explicitValidPortIsAccepted() {
        assertEquals(
            emptyMap<SetupField, SetupError>(),
            SetupValidation.validate("https://example.twil.io:8443/token", "twilmo", "s"),
        )
    }

    @Test
    fun credentialBearingEndpointIsRefused() {
        // user:password@ would put a credential into the backup-included
        // config store; the client secret field is the credential's home.
        assertEquals(
            mapOf(SetupField.ENDPOINT to SetupError.ENDPOINT_HAS_USERINFO),
            SetupValidation.validate("https://client:password@example.twil.io/token", "twilmo", "s"),
        )
    }

    @Test
    fun usernameOnlyEndpointIsRefusedToo() {
        assertEquals(
            mapOf(SetupField.ENDPOINT to SetupError.ENDPOINT_HAS_USERINFO),
            SetupValidation.validate("https://client@example.twil.io/token", "twilmo", "s"),
        )
    }

    @Test
    fun endpointSchemeIsCaseInsensitive() {
        assertEquals(
            emptyMap<SetupField, SetupError>(),
            SetupValidation.validate("HTTPS://example.twil.io/token", "twilmo", "s"),
        )
    }

    @Test
    fun endpointIsTrimmedBeforeValidation() {
        assertEquals(
            emptyMap<SetupField, SetupError>(),
            SetupValidation.validate("  $goodEndpoint  ", "twilmo", "s"),
        )
    }

    @Test
    fun secretOnlyModeChecksJustTheSecret() {
        assertEquals(
            mapOf(SetupField.SECRET to SetupError.MISSING),
            SetupValidation.validateSecretOnly(" "),
        )
        assertEquals(
            emptyMap<SetupField, SetupError>(),
            SetupValidation.validateSecretOnly("a-secret"),
        )
    }
}
