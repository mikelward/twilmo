package app.twilmo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.twilmo.domain.config.SetupConfig
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the corruption recovery path: a corrupt preferences file blocks
 * reads *and* writes (edit() re-reads before applying), so without the
 * corruption handler both Retry and Save would stay wedged forever. The
 * handler resets the unreadable file so setup can run again.
 *
 * One test method on purpose: the DataStore delegate is a process-wide
 * singleton, so the corrupt file must be planted before its first access.
 */
@RunWith(RobolectricTestRunner::class)
class ConfigStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun corruptFileResetsToNotConfiguredAndSavesWorkAgain() = runTest {
        val file = File(context.filesDir, "datastore/setup.preferences_pb")
        file.parentFile?.mkdirs()
        file.writeBytes("not a preferences pb".encodeToByteArray())

        val store = DataStoreConfigStore(context)
        // The handler replaces the unreadable file: the honest
        // not-configured state, not a permanent wedge — and the reset is
        // marked so the home screen can say what happened.
        assertNull(store.config.first())
        assertEquals(true, store.settingsReset.first())

        // And the store is writable again, so setup actually works; a
        // completed save supersedes the reset notice.
        val config = SetupConfig(
            endpointUrl = "https://example.twil.io/token",
            identity = "twilmo",
        )
        store.save(config)
        assertEquals(config, store.config.first())
        assertEquals(false, store.settingsReset.first())
    }
}
