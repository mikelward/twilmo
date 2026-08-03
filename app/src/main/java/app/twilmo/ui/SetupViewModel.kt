package app.twilmo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.twilmo.data.ConfigStore
import app.twilmo.data.SecretStore
import app.twilmo.domain.config.AuthorityKeys
import app.twilmo.domain.config.ConfigState
import app.twilmo.domain.config.SetupConfig
import app.twilmo.domain.config.SetupError
import app.twilmo.domain.config.SetupField
import app.twilmo.domain.config.SetupValidation
import app.twilmo.domain.config.StoredSecret
import app.twilmo.domain.config.configState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the setup form (SPEC "Persistence"). Two modes: full setup, and
 * the restore path where endpoint + identity survived a backup but the
 * secret did not — then only the secret is asked for, with the reason
 * stated ([UiState.secretOnly]).
 */
class SetupViewModel(
    private val configStore: ConfigStore,
    private val secretStore: SecretStore,
    /** Sanitized diagnostics only; injectable so JVM tests avoid android.util.Log. */
    private val logLine: (String) -> Unit = { android.util.Log.e("TwilmoSetup", it) },
) : ViewModel() {

    data class UiState(
        /** False until the stores have been read; the form shows a blank
         * frame immediately and fills in (principle 5). */
        val loaded: Boolean = false,
        val endpointUrl: String = "",
        val identity: String = "",
        val secret: String = "",
        /** The restore path: config present, secret absent. */
        val secretOnly: Boolean = false,
        val errors: Map<SetupField, SetupError> = emptyMap(),
        /** Set once a save lands; the host closes the screen. */
        val saved: Boolean = false,
        /** A persistence failure: the form stays open and says so. */
        val saveFailed: Boolean = false,
        /**
         * The stores could not be read: existing settings may exist that the
         * form isn't showing, so the screen says so and offers a retry. Save
         * stays enabled — a store that never recovers must not strand the
         * user out of re-setup — but the overwrite risk is stated on screen.
         */
        val loadFailed: Boolean = false,
        /**
         * A save is in flight: further submissions are refused and the
         * button disabled, so two slow saves can't interleave into one
         * account's config paired with another save's secret.
         */
        val saving: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    /** Explicit user retry after a load failure; replaces the field values. */
    fun onRetryLoad() {
        // Refused while a save runs (the button is disabled too): a reload
        // would race the write transaction and repopulate the fields from
        // the old configuration, discarding the values the user entered.
        // Also refused while a load is already in flight — loaded doubles
        // as that signal, since every load path ends by setting it — so two
        // reloads can't finish out of order and overwrite typed text.
        val current = _state.value
        if (current.saving || !current.loaded) return
        load()
    }

    private fun load() {
        // Not-loaded while a read runs — initially and on retry — so the
        // fields sit disabled and text typed mid-load can't be silently
        // replaced when the stored values arrive.
        _state.value = _state.value.copy(loaded = false)
        viewModelScope.launch {
            try {
                val config = configStore.config.first()
                val stored = secretStore.readSecret()
                // Secret-only mode covers both a restore (no secret) and a
                // torn save (secret bound to a different authority) — the
                // derivation is shared with the home screen (configState).
                _state.value = _state.value.copy(
                    loaded = true,
                    loadFailed = false,
                    endpointUrl = config?.endpointUrl.orEmpty(),
                    identity = config?.identity.orEmpty(),
                    secretOnly = configState(config, stored) is ConfigState.SecretMissing,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unreadable store must not leave a permanently disabled
                // blank form, but a bare first-run form would misrepresent
                // the state and invite overwriting settings that still
                // exist: the form stays usable, says loading failed, and
                // offers a retry (see UiState.loadFailed).
                logLine("loading setup state failed (${e.javaClass.simpleName})")
                _state.value = _state.value.copy(loaded = true, loadFailed = true)
            }
        }
    }

    // Every field handler carries the same live guard as Save and Retry:
    // the fields disable on saving/!loaded, but a text-input callback
    // already queued before recomposition can still arrive — accepting it
    // would let a save persist older values and close over the late edit,
    // or let a reload overwrite it, silently discarding typed text.
    fun onEndpointChanged(value: String) {
        val current = _state.value
        if (current.saving || !current.loaded) return
        _state.value = current.copy(
            endpointUrl = value,
            errors = current.errors - SetupField.ENDPOINT,
        )
    }

    fun onIdentityChanged(value: String) {
        val current = _state.value
        if (current.saving || !current.loaded) return
        _state.value = current.copy(
            identity = value,
            errors = current.errors - SetupField.IDENTITY,
        )
    }

    fun onSecretChanged(value: String) {
        val current = _state.value
        if (current.saving || !current.loaded) return
        _state.value = current.copy(
            secret = value,
            errors = current.errors - SetupField.SECRET,
        )
    }

    fun onSave() {
        val current = _state.value
        // One save at a time (see UiState.saving) — and no save while a
        // load is in flight (!loaded), the mirror of onRetryLoad's guard:
        // a same-frame Save after Retry would otherwise race the reload,
        // which can replace the fields the save was meant to persist.
        if (current.saving || !current.loaded) return
        val errors = if (current.secretOnly) {
            SetupValidation.validateSecretOnly(current.secret)
        } else {
            SetupValidation.validate(current.endpointUrl, current.identity, current.secret)
        }
        if (errors.isNotEmpty()) {
            _state.value = current.copy(errors = errors)
            return
        }
        _state.value = current.copy(saving = true)
        viewModelScope.launch {
            // Captured before anything is written, so a failed edit of a
            // working account can put the old configuration back and keep
            // the account working. If the snapshot itself can't be read,
            // the save aborts before mutating anything — proceeding without
            // a rollback prerequisite is how a working account gets
            // half-overwritten.
            val previousConfig = try {
                if (current.secretOnly) null else configStore.config.first()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current.loadFailed) {
                    // The load-failed form promised that saving overwrites
                    // the unreadable settings; honoring that means
                    // proceeding without the rollback snapshot the same
                    // failure blocks — otherwise a persistently unreadable
                    // (but still writable) store wedges the user forever.
                    // There is no readable account to preserve here.
                    logLine(
                        "previous configuration unreadable; overwriting as stated" +
                            " (${e.javaClass.simpleName})",
                    )
                    null
                } else {
                    // No overwrite was promised and a working account may
                    // exist: without the rollback prerequisite the save
                    // must not mutate anything.
                    logLine("reading the previous configuration failed (${e.javaClass.simpleName})")
                    _state.value = _state.value.copy(saveFailed = true, saving = false)
                    return@launch
                }
            }
            // Once mutation starts, the transaction runs to completion:
            // cancellation delivered between or after durable writes cannot
            // tell which halves already landed (a commit can be on disk
            // before its suspension resumes), so guessing a rollback could
            // itself break the pairing. Both writes — and the rollback if
            // one fails — run under NonCancellable; cancellation takes
            // effect only before this point, where nothing has been written.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                var configWritten = false
                try {
                    // Config first, then the secret bound to it. Any
                    // interruption — a write failure, or process death
                    // between the commits — leaves whichever config landed
                    // paired with a secret whose authority doesn't match,
                    // which configState reads as "secret needed": never a
                    // false Configured (the secret is written last and
                    // carries its config's authority), and the tear
                    // preserves the configuration the user just typed
                    // (principle 2) — only the one secret field needs
                    // re-entering.
                    if (!current.secretOnly) {
                        configStore.save(
                            SetupConfig(
                                endpointUrl = current.endpointUrl.trim(),
                                identity = current.identity.trim(),
                            ),
                        )
                        configWritten = true
                    }
                    secretStore.writeSecret(
                        StoredSecret(
                            secret = current.secret,
                            authorityKey = AuthorityKeys.authorityKey(
                                current.endpointUrl.trim(),
                                current.identity.trim(),
                            ),
                        ),
                    )
                    _state.value =
                        _state.value.copy(saved = true, saveFailed = false, saving = false)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Not a cancellation signal in here (the job is
                    // NonCancellable) — a store threw it on its own; treat
                    // it like the failure it is after restoring the pair.
                    rollBackConfig(previousConfig, configWritten)
                    throw e
                } catch (e: Exception) {
                    // The form stays open and says so; the reason is logged
                    // sanitized (exception class only — never a field value).
                    logLine("saving setup failed (${e.javaClass.simpleName})")
                    rollBackConfig(previousConfig, configWritten)
                    _state.value = _state.value.copy(saveFailed = true, saving = false)
                }
            }
        }
    }

    /**
     * Best-effort undo of the config-first write when the secret write
     * failed, so a failed edit leaves the old, working account. A fresh
     * setup has nothing to restore — keeping the new config with the
     * detected "secret needed" state preserves the user's work best. If
     * even the rollback fails, the leftover pair is new config + a secret
     * bound to the old authority, which reads as the honest "secret
     * needed" — never a false Ready — so no further repair is needed.
     */
    private suspend fun rollBackConfig(previousConfig: SetupConfig?, configWritten: Boolean) {
        if (!configWritten || previousConfig == null) return
        try {
            configStore.save(previousConfig)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logLine("restoring previous configuration failed (${e.javaClass.simpleName})")
        }
    }

    companion object {
        fun factory(configStore: ConfigStore, secretStore: SecretStore) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SetupViewModel(configStore, secretStore) as T
            }
    }
}
