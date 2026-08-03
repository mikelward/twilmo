package app.twilmo.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import app.twilmo.domain.config.StoredSecret
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The secret half of the configuration (SPEC "Persistence"): the client
 * secret alone. Interface so the view model tests run against an in-memory
 * fake.
 */
interface SecretStore {
    /**
     * Null when absent — after first run, a platform restore, or a cleared
     * unreadable value. Carries the authority fingerprint it was written
     * with, so callers can detect a secret that no longer matches the
     * stored config (see [app.twilmo.domain.config.configState]).
     */
    suspend fun readSecret(): StoredSecret?

    suspend fun writeSecret(secret: StoredSecret)
}

/**
 * Stores the client secret encrypted with an Android Keystore AES-GCM key,
 * in a SharedPreferences file the backup rules exclude
 * (`res/xml/data_extraction_rules.xml`). Two layers, one reason each: the
 * exclusion keeps the secret out of backups; the Keystore encryption keeps
 * the on-device file unreadable without this device's keystore, so a copied
 * file is ciphertext. After a restore the Keystore key doesn't exist on the
 * new device — and the file wasn't backed up anyway — so [readSecret] is
 * null and setup asks for exactly this one field.
 *
 * Not exercised by unit tests: Robolectric has no real AndroidKeyStore. The
 * view model is tested against the interface; this implementation is small,
 * verified by inspection, and owed a device check (recorded in the PR).
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    private val preferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun readSecret(): StoredSecret? = withContext(Dispatchers.IO) {
        // A wrong-typed stored value (getString throws ClassCastException)
        // is corrupt stored data like malformed Base64 below: irrecoverable
        // input, cleared so it can't wedge every later read — and with it
        // the save path's rollback snapshot — behind the same exception.
        val (ciphertext, iv, authority) = try {
            Triple(
                preferences.getString(KEY_CIPHERTEXT, null),
                preferences.getString(KEY_IV, null),
                preferences.getString(KEY_AUTHORITY, null),
            )
        } catch (e: ClassCastException) {
            clearUnreadableSecret(e)
            return@withContext null
        }
        if (ciphertext == null || iv == null || authority == null) return@withContext null
        // Decoded before any cipher work: malformed Base64 is corrupt
        // *stored* data — no retry fixes it — so it takes the same
        // irrecoverable path as a failed auth tag rather than wedging
        // every later read behind the same exception.
        val (ciphertextBytes, ivBytes) = try {
            Base64.decode(ciphertext, Base64.NO_WRAP) to Base64.decode(iv, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            clearUnreadableSecret(e)
            return@withContext null
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                obtainKey(),
                GCMParameterSpec(GCM_TAG_BITS, ivBytes),
            )
            StoredSecret(
                secret = String(cipher.doFinal(ciphertextBytes)),
                authorityKey = authority,
            )
        } catch (e: javax.crypto.AEADBadTagException) {
            // Provably irrecoverable: the ciphertext does not match this
            // device's key (cleared keystore, restored prefs without the
            // key, corrupt file). Same situation as no secret — setup asks
            // again with the reason stated — and the file is cleared so
            // the two read paths agree.
            clearUnreadableSecret(e)
            null
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            // Also irrecoverable, by the platform's own word — and the key
            // itself is dead, not just this value: delete the alias too, or
            // obtainKey() would keep returning the same invalidated key and
            // every later write would fail at Cipher.init, leaving no way
            // to save a replacement secret short of clearing app data.
            deleteKeyAlias()
            clearUnreadableSecret(e)
            null
        } catch (e: java.security.InvalidAlgorithmParameterException) {
            // A stored IV the cipher rejects (wrong length after
            // corruption) is bad stored data, not a provider hiccup:
            // deterministic on these inputs, so keeping the value would
            // wedge every later read behind the same failure.
            clearUnreadableSecret(e)
            null
        } catch (e: java.security.GeneralSecurityException) {
            // Possibly transient (keystore provider hiccup, cipher init
            // failure): deleting the ciphertext here could destroy a
            // credential that decrypts fine after the hiccup passes
            // (principle 2). Keep the file, log, and propagate — callers
            // already surface store failures visibly.
            Log.w(
                TAG,
                "reading client secret failed, keeping ciphertext" +
                    " (${e.javaClass.simpleName})",
            )
            throw e
        }
    }

    private fun clearUnreadableSecret(e: Exception) {
        // Logged so a credential-loss event is distinguishable from an
        // ordinary restore; the exception class carries no secret material.
        Log.w(
            TAG,
            "stored client secret no longer decrypts" +
                " (${e.javaClass.simpleName}); clearing so setup asks again",
        )
        if (!preferences.edit().clear().commit()) {
            Log.w(TAG, "clearing the unreadable secret did not reach disk")
        }
    }

    override suspend fun writeSecret(secret: StoredSecret): Unit = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            // A dead key must not wedge saving a replacement secret: the
            // old ciphertext was unreadable under it anyway, so nothing is
            // lost by regenerating and encrypting with a fresh key.
            Log.w(TAG, "encryption key permanently invalidated; regenerating")
            deleteKeyAlias()
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        }
        val ciphertext = cipher.doFinal(secret.secret.encodeToByteArray())
        // commit(), not apply(): already on the IO dispatcher, and a write
        // that quietly missed the disk would report setup as saved while a
        // restart loses the credential (principle 2). A false return
        // becomes a visible save failure upstream. The authority fingerprint
        // rides the same commit, so secret and fingerprint can never tear
        // apart — the fingerprint is a one-way hash, safe to store as-is.
        val written = preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_AUTHORITY, secret.authorityKey)
            .commit()
        if (!written) {
            throw java.io.IOException("client secret write did not reach disk")
        }
    }

    private fun deleteKeyAlias() {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (e: java.security.GeneralSecurityException) {
            // Best-effort: if even the deletion fails, the next write's
            // init failure stays visible through the save-failure path.
            Log.w(TAG, "deleting the invalidated key failed (${e.javaClass.simpleName})")
        } catch (e: java.io.IOException) {
            Log.w(TAG, "deleting the invalidated key failed (${e.javaClass.simpleName})")
        }
    }

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /** Named in the backup exclusion rules — keep the two in sync. */
        const val PREFS_NAME = "twilmo_secret"

        private const val TAG = "TwilmoSecretStore"

        // Internal so the corruption-recovery test can plant bad values.
        internal const val KEY_CIPHERTEXT = "secret_ciphertext"
        internal const val KEY_IV = "secret_iv"
        internal const val KEY_AUTHORITY = "secret_authority"
        private const val KEY_ALIAS = "twilmo_secret_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
