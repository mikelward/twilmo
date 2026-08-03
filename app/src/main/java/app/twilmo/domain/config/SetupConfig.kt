package app.twilmo.domain.config

/**
 * The non-secret half of the account configuration (SPEC "Persistence"):
 * the token endpoint URL and the client identity. These ride platform
 * backup/transfer so a restored device keeps them; the client secret lives
 * apart (encrypted, backup-excluded) and is re-entered after a restore.
 *
 * The endpoint URL and identity are the user's own configuration —
 * displayable in the app, but never in a development artifact. [toString]
 * omits both so a logged state object can't carry them into a bug report.
 */
data class SetupConfig(
    val endpointUrl: String,
    val identity: String,
) {
    override fun toString(): String = "SetupConfig(…)"
}

/**
 * What the app knows about its configuration at a glance. Derived, never
 * stored — see [configState].
 */
sealed interface ConfigState {
    /** Nothing saved yet: first-run setup. */
    data object NotConfigured : ConfigState

    /**
     * Endpoint and identity are present but the secret is not — the
     * signature of a platform restore, since the secret deliberately does
     * not ride backups. Setup asks for exactly the one missing field, with
     * the reason stated (principle 2: the user's work survived; say what
     * didn't and why).
     */
    data class SecretMissing(val config: SetupConfig) : ConfigState

    data class Configured(val config: SetupConfig) : ConfigState

    /**
     * The stores could not be read, so readiness is unverifiable right now.
     * Shown as its own state rather than keeping whatever was last known —
     * a stale "Ready" after a failed refresh is a silent lie (principle 1).
     */
    data object Unknown : ConfigState
}

/**
 * Derives the state from what the two stores hold. A config with a blank
 * field counts as not configured — a half-written config must never look
 * restored. A secret whose stored authority fingerprint doesn't match the
 * config is treated as missing: that mismatch is the signature of a save
 * torn by process death between the two stores' commits, and reporting it
 * as configured would be a working-looking account whose mints all fail.
 */
fun configState(config: SetupConfig?, storedSecret: StoredSecret?): ConfigState = when {
    config == null || config.endpointUrl.isBlank() || config.identity.isBlank() ->
        ConfigState.NotConfigured
    storedSecret == null -> ConfigState.SecretMissing(config)
    storedSecret.authorityKey !=
        AuthorityKeys.authorityKey(config.endpointUrl, config.identity) ->
        ConfigState.SecretMissing(config)
    else -> ConfigState.Configured(config)
}
