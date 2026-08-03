package app.twilmo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.twilmo.data.DataStoreConfigStore
import app.twilmo.data.KeystoreSecretStore
import app.twilmo.domain.config.ConfigState
import app.twilmo.domain.config.configState
import app.twilmo.ui.HomeScreen
import app.twilmo.ui.SetupScreen
import app.twilmo.ui.SetupViewModel
import app.twilmo.ui.SetupVisitOwner
import app.twilmo.ui.theme.TwilmoTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val configStore = DataStoreConfigStore(applicationContext)
        val secretStore = KeystoreSecretStore(applicationContext)
        setContent {
            TwilmoTheme {
                var showSetup by rememberSaveable { mutableStateOf(false) }
                // The screen appears immediately with the not-configured
                // frame and corrects itself when the stores answer
                // (principle 5); reads are off the main thread.
                val state by produceState<ConfigState>(
                    initialValue = ConfigState.NotConfigured,
                    key1 = showSetup,
                ) {
                    try {
                        configStore.config.collectLatest { config ->
                            value = configState(config, secretStore.readSecret())
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // An unreadable store must not crash startup, and
                        // the last computed state must not stand in either —
                        // it could be Configured, and a stale "Ready" after
                        // a failed refresh is a silent lie (principle 1).
                        // Show the honest Unknown state, setup reachable,
                        // reason logged sanitized.
                        Log.w(
                            "TwilmoHome",
                            "reading configuration state failed (${e.javaClass.simpleName})",
                        )
                        value = ConfigState.Unknown
                    }
                }
                // Whether the settings file was reset after corruption: the
                // reset must be visible, not a silent first-run frame. A
                // read failure keeps false — the Unknown state above already
                // covers unreadable stores.
                val settingsReset by produceState(
                    initialValue = false,
                    key1 = showSetup,
                ) {
                    try {
                        configStore.settingsReset.collectLatest { value = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(
                            "TwilmoHome",
                            "reading the reset marker failed (${e.javaClass.simpleName})",
                        )
                    }
                }
                if (showSetup) {
                    // The visit owner scopes the setup view model to one
                    // visit: it survives rotation while the visit lasts, and
                    // ending the visit clears it — no accumulation of keyed
                    // instances (each holding plaintext secret text) in the
                    // activity's store, and no stale saved=true closing a
                    // reopened form on arrival.
                    val visitOwner: SetupVisitOwner = viewModel()
                    val viewModel: SetupViewModel = viewModel(
                        viewModelStoreOwner = visitOwner,
                        factory = SetupViewModel.factory(configStore, secretStore),
                    )
                    SetupScreen(
                        viewModel = viewModel,
                        onDone = {
                            visitOwner.endVisit()
                            showSetup = false
                        },
                    )
                } else {
                    HomeScreen(
                        versionName = BuildConfig.VERSION_NAME,
                        configState = state,
                        settingsReset = settingsReset,
                        onSetUp = { showSetup = true },
                    )
                }
            }
        }
    }
}
