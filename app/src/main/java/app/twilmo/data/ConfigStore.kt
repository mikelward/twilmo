package app.twilmo.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.twilmo.domain.config.SetupConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The non-secret half of the configuration (SPEC "Persistence"). Interface
 * so the view model tests run against an in-memory fake.
 */
interface ConfigStore {
    /** Null until both fields have been saved. */
    val config: Flow<SetupConfig?>

    /**
     * True after a corrupt settings file was replaced (see the corruption
     * handler below): the reset must be visible, not a silent first-run
     * frame that hides what happened to the user's settings (principle 1).
     * Cleared by the next successful [save].
     */
    val settingsReset: Flow<Boolean>
        get() = kotlinx.coroutines.flow.flowOf(false)

    suspend fun save(config: SetupConfig)
}

/**
 * DataStore-backed store for endpoint URL + identity. This file is
 * deliberately *included* in platform backup/transfer — losing it on a new
 * phone would be losing the user's work (principle 2) — while the secret
 * lives in [SecretStore], encrypted and backup-excluded.
 */
class DataStoreConfigStore(private val context: Context) : ConfigStore {

    override val config: Flow<SetupConfig?> =
        context.setupDataStore.data.map { preferences ->
            val endpoint = preferences[KEY_ENDPOINT_URL]
            val identity = preferences[KEY_IDENTITY]
            if (endpoint.isNullOrBlank() || identity.isNullOrBlank()) {
                null
            } else {
                SetupConfig(endpointUrl = endpoint, identity = identity)
            }
        }

    override val settingsReset: Flow<Boolean> =
        context.setupDataStore.data.map { preferences ->
            preferences[KEY_SETTINGS_RESET] == true
        }

    override suspend fun save(config: SetupConfig) {
        context.setupDataStore.edit { preferences ->
            preferences[KEY_ENDPOINT_URL] = config.endpointUrl
            preferences[KEY_IDENTITY] = config.identity
            // A completed save supersedes the reset notice.
            preferences.remove(KEY_SETTINGS_RESET)
        }
    }

    internal companion object {
        val KEY_ENDPOINT_URL = stringPreferencesKey("endpoint_url")
        val KEY_IDENTITY = stringPreferencesKey("identity")

        /** Planted by the corruption handler so the reset is visible. */
        val KEY_SETTINGS_RESET = booleanPreferencesKey("settings_reset")
    }
}

// One DataStore per process, named so the backup rules can refer to it by
// file (datastore/setup.preferences_pb) — it is on the *included* side.
// The corruption handler is the recovery path for an unparseable file: a
// corrupt store blocks *reads and writes* (edit() re-reads before applying),
// so without it Retry and Save would both stay wedged forever and the user
// could never reconfigure. The file is already unreadable by anything at
// that point — resetting loses nothing recoverable and there is no
// alternative to offer — but the reset is not silent: the replacement
// carries a marker the home screen surfaces (principle 1), and the
// sanitized reason is logged.
private val Context.setupDataStore by preferencesDataStore(
    name = "setup",
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Log.w("TwilmoConfigStore", "setup preferences corrupt (${e.javaClass.simpleName}); resetting")
        preferencesOf(DataStoreConfigStore.KEY_SETTINGS_RESET to true)
    },
)
