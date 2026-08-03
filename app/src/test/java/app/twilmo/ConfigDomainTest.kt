package app.twilmo

import app.twilmo.domain.config.AuthorityKeys
import app.twilmo.domain.config.ConfigState
import app.twilmo.domain.config.SetupConfig
import app.twilmo.domain.config.StoredSecret
import app.twilmo.domain.config.configState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDomainTest {

    private val config = SetupConfig(
        endpointUrl = "https://example.twil.io/token",
        identity = "twilmo",
    )

    private val matchingSecret = StoredSecret(
        secret = "a-secret",
        authorityKey = AuthorityKeys.authorityKey(config.endpointUrl, config.identity),
    )

    // --- configState ---

    @Test
    fun nothingSavedIsNotConfigured() {
        assertEquals(ConfigState.NotConfigured, configState(null, storedSecret = null))
    }

    @Test
    fun aBlankFieldNeverLooksRestored() {
        assertEquals(
            ConfigState.NotConfigured,
            configState(SetupConfig("", "twilmo"), storedSecret = null),
        )
        assertEquals(
            ConfigState.NotConfigured,
            configState(SetupConfig("https://example.twil.io/token", " "), storedSecret = null),
        )
    }

    @Test
    fun configWithoutSecretIsTheRestoreSignature() {
        assertEquals(ConfigState.SecretMissing(config), configState(config, storedSecret = null))
    }

    @Test
    fun configWithSecretIsConfigured() {
        assertEquals(ConfigState.Configured(config), configState(config, matchingSecret))
    }

    @Test
    fun aStraySecretAloneIsStillNotConfigured() {
        assertEquals(ConfigState.NotConfigured, configState(null, matchingSecret))
    }

    @Test
    fun aSecretBoundToADifferentAuthorityReadsAsMissing() {
        // The signature of a save torn by process death between the secret
        // and config commits: the surviving config doesn't match the
        // secret's fingerprint, and reporting Configured would be a
        // working-looking account whose mints all fail.
        val torn = StoredSecret(
            secret = "a-secret",
            authorityKey = AuthorityKeys.authorityKey("https://other.twil.io/token", "twilmo"),
        )
        assertEquals(ConfigState.SecretMissing(config), configState(config, torn))
    }

    @Test
    fun storedSecretNeverPrintsItsSecret() {
        assertFalse(matchingSecret.toString().contains("a-secret"))
    }

    // --- AuthorityKeys ---

    @Test
    fun sameInputsSameKey() {
        assertEquals(
            AuthorityKeys.authorityKey(config.endpointUrl, config.identity),
            AuthorityKeys.authorityKey(config.endpointUrl, config.identity),
        )
    }

    @Test
    fun endpointOrIdentityChangeChangesTheKey() {
        val key = AuthorityKeys.authorityKey(config.endpointUrl, config.identity)
        assertNotEquals(
            key,
            AuthorityKeys.authorityKey("https://other.twil.io/token", config.identity),
        )
        assertNotEquals(
            key,
            AuthorityKeys.authorityKey(config.endpointUrl, "someone-else"),
        )
    }

    @Test
    fun fieldBoundariesCannotCollide() {
        // Length prefixing: ("ab","c") must not hash like ("a","bc").
        assertNotEquals(
            AuthorityKeys.authorityKey("ab", "c"),
            AuthorityKeys.authorityKey("a", "bc"),
        )
    }

    @Test
    fun theKeyIsOpaqueHexNotTheInputs() {
        val key = AuthorityKeys.authorityKey(config.endpointUrl, config.identity)
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(key.contains("twil"))
    }

    // --- Privacy ---

    @Test
    fun setupConfigNeverPrintsItsFields() {
        assertFalse(config.toString().contains("twil"))
    }
}
