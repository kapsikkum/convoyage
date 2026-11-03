package app.convoyage.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.convoyage.api.TokenStore
import app.convoyage.api.Tokens
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Access + refresh tokens, encrypted with an AES-256-GCM key that lives in the Android Keystore (it never
 * leaves secure hardware where there is any) and stored in private SharedPreferences. No extra dependency.
 *
 * If the key is lost or unreadable (restored backup, keystore reset, new lock screen policy) the stored
 * tokens are dropped and the person simply signs in again.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("convoyage.secure", Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile
    private var cached: Tokens? = null

    @Volatile
    private var loaded = false

    override var tokens: Tokens?
        get() {
            if (!loaded) synchronized(lock) { if (!loaded) { cached = readFromDisk(); loaded = true } }
            return cached
        }
        set(value) {
            synchronized(lock) {
                cached = value
                loaded = true
                val edit = prefs.edit()
                if (value == null) {
                    edit.remove(KEY_ACCESS).remove(KEY_REFRESH)
                } else {
                    val a = encrypt(value.access)
                    val r = encrypt(value.refresh)
                    if (a == null || r == null) edit.remove(KEY_ACCESS).remove(KEY_REFRESH)
                    else edit.putString(KEY_ACCESS, a).putString(KEY_REFRESH, r)
                }
                edit.commit() // the rotated refresh token must hit the disk before we hand out the new access token
            }
        }

    private fun readFromDisk(): Tokens? {
        val a = prefs.getString(KEY_ACCESS, null)?.let(::decrypt)
        val r = prefs.getString(KEY_REFRESH, null)?.let(::decrypt)
        if (a == null || r == null) {
            if (prefs.contains(KEY_ACCESS) || prefs.contains(KEY_REFRESH)) prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).apply()
            return null
        }
        return Tokens(a, r)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** `base64(iv):base64(ciphertext)`, or null if the keystore is unusable. */
    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    private fun decrypt(stored: String): String? = try {
        val (iv, ct) = stored.split(':', limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "convoyage.tokens.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
    }
}
