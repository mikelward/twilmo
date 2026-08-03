package app.twilmo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the corruption-recovery path that never touches the keystore:
 * malformed Base64 is rejected while decoding, before any cipher work, so
 * it is exercisable under Robolectric. The cipher/key failure branches
 * (bad tag, invalidated key, transient provider errors) need a real
 * AndroidKeyStore and stay owed to the device check recorded in the PR.
 */
@RunWith(RobolectricTestRunner::class)
class SecretStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun preferences() =
        context.getSharedPreferences(KeystoreSecretStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun malformedStoredEncodingReadsAsAbsentAndClears() = runTest {
        preferences().edit()
            .putString(KeystoreSecretStore.KEY_CIPHERTEXT, "not base64 !!!")
            .putString(KeystoreSecretStore.KEY_IV, "also not base64 !!!")
            .putString(KeystoreSecretStore.KEY_AUTHORITY, "whatever")
            .commit()
        val store = KeystoreSecretStore(context)

        // Corrupt stored bytes are irrecoverable input: read as absent so
        // setup asks again, instead of wedging every later read (and the
        // save path's snapshot) behind the same exception.
        assertNull(store.readSecret())
        assertFalse(preferences().contains(KeystoreSecretStore.KEY_CIPHERTEXT))
        assertNull(store.readSecret())
    }

    @Test
    fun absentValuesReadAsAbsentWithoutTouchingTheKeystore() = runTest {
        val store = KeystoreSecretStore(context)
        assertNull(store.readSecret())
    }

    @Test
    fun wrongTypedStoredValuesReadAsAbsentAndClear() = runTest {
        preferences().edit()
            .putInt(KeystoreSecretStore.KEY_CIPHERTEXT, 42)
            .putString(KeystoreSecretStore.KEY_IV, "whatever")
            .putString(KeystoreSecretStore.KEY_AUTHORITY, "whatever")
            .commit()
        val store = KeystoreSecretStore(context)

        // getString throws ClassCastException on a wrong-typed value —
        // corrupt stored data, cleared like malformed Base64 so it can't
        // wedge later reads and the save path's rollback snapshot.
        assertNull(store.readSecret())
        assertFalse(preferences().contains(KeystoreSecretStore.KEY_CIPHERTEXT))
    }

}
