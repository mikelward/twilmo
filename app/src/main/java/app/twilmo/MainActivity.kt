package app.twilmo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.twilmo.ui.HomeScreen
import app.twilmo.ui.theme.TwilmoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TwilmoTheme {
                HomeScreen(versionName = BuildConfig.VERSION_NAME)
            }
        }
    }
}
