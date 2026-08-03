package app.twilmo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.twilmo.R
import app.twilmo.domain.config.ConfigState

/**
 * The home/status screen (SPEC "UI architecture"): what state the line is
 * in, and the way into setup when something is missing. The registration
 * health readout replaces the status line as Phase 4 lands.
 */
@Composable
fun HomeScreen(
    versionName: String,
    configState: ConfigState,
    onSetUp: () -> Unit,
    modifier: Modifier = Modifier,
    /** True when a corrupt settings file was reset; the screen says so. */
    settingsReset: Boolean = false,
) {
    // The Surface paints the theme background; without it the content sits on
    // the bare window, whose platform theme need not match the Compose scheme.
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(
                    when (configState) {
                        is ConfigState.Configured -> R.string.home_status_ready
                        is ConfigState.SecretMissing -> R.string.home_status_secret_needed
                        ConfigState.NotConfigured -> R.string.home_not_set_up
                        ConfigState.Unknown -> R.string.home_status_unavailable
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            when (configState) {
                // After a corruption reset the frame must not pose as an
                // ordinary first run: say what happened to the settings.
                ConfigState.NotConfigured -> Text(
                    text = stringResource(
                        if (settingsReset) {
                            R.string.home_settings_reset
                        } else {
                            R.string.home_setup_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                is ConfigState.SecretMissing -> Text(
                    text = stringResource(R.string.setup_secret_missing_reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                is ConfigState.Configured -> Unit
                // Unknown says only what is true: the status could not be
                // read; the debug log carries the sanitized reason.
                ConfigState.Unknown -> Unit
            }
            // Always reachable: setup only validates locally, so a saved
            // secret can still be wrong, and endpoints change — hiding the
            // way back in would strand a misconfigured account.
            Button(
                onClick = onSetUp,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(stringResource(R.string.home_set_up))
            }
            Text(
                text = versionName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}
