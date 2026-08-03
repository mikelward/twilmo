package app.twilmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.twilmo.domain.config.ConfigState
import app.twilmo.ui.theme.TwilmoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the home/status screen's unconfigured state (SPEC "UI
 * architecture"). Mirrors the sibling repos' screenshot pattern: assertions
 * always run; the PNG is captured only under the record/verify flags CI sets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun home_notSetUp() {
        composeRule.setContent {
            // The production theme, pinned to light so the snapshot is
            // deterministic; a theme regression should change this image.
            TwilmoTheme(darkTheme = false) {
                HomeScreen(
                    versionName = "0.1.1+0000000",
                    configState = ConfigState.NotConfigured,
                    onSetUp = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Twilmo").assertExists()
        composeRule.onNodeWithText("Not set up").assertExists()
        composeRule.onNodeWithText("0.1.1+0000000").assertExists()

        captureSnapshot("home_not_set_up.png")
    }

    @Test
    fun home_statusUnavailable() {
        composeRule.setContent {
            TwilmoTheme(darkTheme = false) {
                HomeScreen(
                    versionName = "0.1.1+0000000",
                    configState = ConfigState.Unknown,
                    onSetUp = {},
                )
            }
        }
        composeRule.waitForIdle()

        // A failed status refresh shows its own honest state — never a
        // stale "Ready" — with setup still reachable.
        composeRule.onNodeWithText("Status unavailable").assertExists()
        composeRule.onNodeWithText("Set up").assertExists()

        captureSnapshot("home_status_unavailable.png")
    }

    @Test
    fun home_settingsReset() {
        composeRule.setContent {
            TwilmoTheme(darkTheme = false) {
                HomeScreen(
                    versionName = "0.1.1+0000000",
                    configState = ConfigState.NotConfigured,
                    settingsReset = true,
                    onSetUp = {},
                )
            }
        }
        composeRule.waitForIdle()

        // A corruption reset must not pose as an ordinary first run.
        composeRule.onNodeWithText(
            "Saved settings could not be read and were reset. Set up again.",
        ).assertExists()
        composeRule.onNodeWithText("Set up").assertExists()

        captureSnapshot("home_settings_reset.png")
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
