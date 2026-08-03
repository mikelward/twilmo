package app.twilmo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.twilmo.R
import app.twilmo.domain.config.SetupError
import app.twilmo.domain.config.SetupField

/** Stateful wrapper; [SetupContent] carries the layout for screenshots. */
@Composable
fun SetupScreen(viewModel: SetupViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }
    // Back is swallowed while a save runs: leaving would hide the only
    // surface where a save or rollback failure is reported (principle 1),
    // and the writes are local and brief, so the wait is too. Any other
    // time, Back closes the visit. Read the *live* state, not the
    // compositional snapshot: a Save and a Back dispatched in the same
    // frame would otherwise see the pre-save value and close anyway.
    BackHandler {
        if (!viewModel.state.value.saving) onDone()
    }
    SetupContent(
        state = state,
        onEndpointChanged = viewModel::onEndpointChanged,
        onIdentityChanged = viewModel::onIdentityChanged,
        onSecretChanged = viewModel::onSecretChanged,
        onSave = viewModel::onSave,
        onRetryLoad = viewModel::onRetryLoad,
    )
}

@Composable
fun SetupContent(
    state: SetupViewModel.UiState,
    onEndpointChanged: (String) -> Unit,
    onIdentityChanged: (String) -> Unit,
    onSecretChanged: (String) -> Unit,
    onSave: () -> Unit,
    onRetryLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            if (state.loadFailed) {
                // Loading failed, so settings may exist that this form
                // isn't showing: say so instead of posing as a first run,
                // and state the overwrite risk since Save stays enabled
                // (a store that never recovers must not strand re-setup).
                Text(
                    text = stringResource(R.string.setup_error_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = onRetryLoad,
                    modifier = Modifier.fillMaxWidth(),
                    // Same gate as every other control: disabled while a
                    // save runs (a reload would race the write transaction)
                    // and while a reload is already in flight (!loaded), so
                    // two reloads can't finish out of order.
                    enabled = state.loaded && !state.saving,
                ) {
                    Text(stringResource(R.string.setup_retry))
                }
            }
            if (state.secretOnly) {
                // Missing-secret mode (a restore, a torn save, a cleared
                // corrupt secret): say why only one field is asked for
                // (principle 2 — the rest of their work survived). The copy
                // stays cause-neutral because the causes aren't reliably
                // distinguishable from the stores alone.
                Text(
                    text = stringResource(R.string.setup_secret_missing_reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SetupField(
                    value = state.endpointUrl,
                    onValueChange = onEndpointChanged,
                    label = R.string.setup_endpoint_label,
                    error = state.errors[SetupField.ENDPOINT],
                    enabled = state.loaded && !state.saving,
                )
                SetupField(
                    value = state.identity,
                    onValueChange = onIdentityChanged,
                    label = R.string.setup_identity_label,
                    error = state.errors[SetupField.IDENTITY],
                    enabled = state.loaded && !state.saving,
                )
            }
            SetupField(
                value = state.secret,
                onValueChange = onSecretChanged,
                label = R.string.setup_secret_label,
                error = state.errors[SetupField.SECRET],
                enabled = state.loaded && !state.saving,
                secret = true,
            )
            if (state.saveFailed) {
                Text(
                    text = stringResource(R.string.setup_error_save_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.loaded && !state.saving,
            ) {
                Text(stringResource(R.string.setup_save))
            }
        }
    }
}

@Composable
private fun SetupField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    error: SetupError?,
    enabled: Boolean = true,
    secret: Boolean = false,
) {
    // Disabled until loading settles and while a save runs: a load replaces
    // the field values when the stored ones arrive, and onSave captured the
    // values at submission — either way, text typed in that window would
    // silently vanish.
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it.messageRes())) } },
        singleLine = true,
        // Both halves matter for the secret: the transformation masks the
        // display, and the password keyboard type keeps the IME from
        // learning or suggesting the credential.
        keyboardOptions = if (secret) {
            KeyboardOptions(keyboardType = KeyboardType.Password)
        } else {
            KeyboardOptions.Default
        },
        visualTransformation = if (secret) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
    )
}

private fun SetupError.messageRes(): Int = when (this) {
    SetupError.MISSING -> R.string.setup_error_missing
    SetupError.ENDPOINT_NOT_HTTPS -> R.string.setup_error_not_https
    SetupError.ENDPOINT_NOT_A_URL -> R.string.setup_error_not_a_url
    SetupError.ENDPOINT_HAS_USERINFO -> R.string.setup_error_endpoint_userinfo
}
