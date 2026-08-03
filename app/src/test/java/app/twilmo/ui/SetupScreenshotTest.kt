package app.twilmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.twilmo.ui.theme.TwilmoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the setup screen's two states (SPEC "Persistence"): the fresh
 * full form, and the restore path asking for only the secret. Same pattern
 * as HomeScreenshotTest: assertions always run; PNGs are captured only
 * under the record/verify flags CI sets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun setup_freshForm() {
        composeRule.setContent {
            TwilmoTheme(darkTheme = false) {
                SetupContent(
                    state = SetupViewModel.UiState(loaded = true),
                    onEndpointChanged = {},
                    onIdentityChanged = {},
                    onSecretChanged = {},
                    onSave = {},
                    onRetryLoad = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Endpoint URL").assertExists()
        composeRule.onNodeWithText("Identity").assertExists()
        composeRule.onNodeWithText("Client secret").assertExists()
        composeRule.onNodeWithText("Save").assertExists()

        captureSnapshot("setup_fresh_form.png")
    }

    @Test
    fun setup_secretOnlyAfterRestore() {
        composeRule.setContent {
            TwilmoTheme(darkTheme = false) {
                SetupContent(
                    state = SetupViewModel.UiState(loaded = true, secretOnly = true),
                    onEndpointChanged = {},
                    onIdentityChanged = {},
                    onSecretChanged = {},
                    onSave = {},
                    onRetryLoad = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            "The client secret is missing or does not match this setup. Enter it again.",
        ).assertExists()
        composeRule.onNodeWithText("Client secret").assertExists()

        captureSnapshot("setup_secret_only.png")
    }

    @Test
    fun setup_savingInFlight() {
        composeRule.setContent {
            TwilmoTheme(darkTheme = false) {
                SetupContent(
                    state = SetupViewModel.UiState(
                        loaded = true,
                        endpointUrl = "https://example.twil.io/token",
                        identity = "twilmo",
                        secret = "a-secret",
                        saving = true,
                    ),
                    onEndpointChanged = {},
                    onIdentityChanged = {},
                    onSecretChanged = {},
                    onSave = {},
                    onRetryLoad = {},
                )
            }
        }
        composeRule.waitForIdle()

        // Fields and Save are disabled while the captured save runs, so
        // edits typed mid-save can't silently vanish when the screen closes.
        composeRule.onNodeWithText("https://example.twil.io/token").assertIsNotEnabled()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()

        captureSnapshot("setup_saving.png")
    }

    @Test
    fun setup_loadFailed() {
        composeRule.setContent {
            TwilmoTheme(darkTheme = false) {
                SetupContent(
                    state = SetupViewModel.UiState(loaded = true, loadFailed = true),
                    onEndpointChanged = {},
                    onIdentityChanged = {},
                    onSecretChanged = {},
                    onSave = {},
                    onRetryLoad = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            "Could not load your saved settings. Saving now will overwrite them.",
        ).assertExists()
        composeRule.onNodeWithText("Retry").assertExists()

        captureSnapshot("setup_load_failed.png")
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
