package `in`.setu.app.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import `in`.setu.protocol.Identity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Creates the device identity on first run and keeps its 32-byte seed in
 * app-private preferences, wrapped with an AES-GCM key that lives in the
 * Android Keystore. A rooted phone can still read it; see docs/THREAT_MODEL.md.
 */
class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("setu_identity", Context.MODE_PRIVATE)

    val identity: Identity by lazy { load() ?: create() }

    private fun load(): Identity? {
        val wrapped = prefs.getString(KEY_SEED, null) ?: return null
        return try {
            Identity.fromSeed(unwrap(Base64.decode(wrapped, Base64.NO_WRAP)))
        } catch (_: Exception) {
            null
        }
    }

    private fun create(): Identity {
        val id = Identity.generate()
        prefs.edit().putString(KEY_SEED, Base64.encodeToString(wrap(id.seed), Base64.NO_WRAP)).apply()
        return id
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(seed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(seed)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(blob.copyOfRange(1 + ivLen, blob.size))
    }

    private companion object {
        const val KEY_SEED = "seed"
        const val KEY_ALIAS = "setu-seed-wrap"
    }
}
