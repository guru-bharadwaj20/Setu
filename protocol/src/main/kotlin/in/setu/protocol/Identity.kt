package `in`.setu.protocol

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * An Ed25519 device identity. The 32-byte public key is the packet `origin`.
 * The seed is the only secret; it is 32 bytes and never leaves the device.
 */
class Identity private constructor(private val priv: EdDSAPrivateKey, val publicKey: ByteArray) {

    val seed: ByteArray get() = priv.seed.copyOf()

    fun sign(message: ByteArray): ByteArray {
        val engine = EdDSAEngine(MessageDigest.getInstance(SPEC.hashAlgorithm))
        engine.initSign(priv)
        engine.update(message)
        return engine.sign()
    }

    companion object {
        private val SPEC = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

        fun generate(random: SecureRandom = SecureRandom()): Identity =
            fromSeed(ByteArray(32).also(random::nextBytes))

        fun fromSeed(seed: ByteArray): Identity {
            require(seed.size == 32) { "seed must be 32 bytes" }
            val priv = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, SPEC))
            return Identity(priv, priv.abyte.copyOf())
        }

        fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            if (publicKey.size != Protocol.ORIGIN_BYTES || signature.size != Protocol.SIG_BYTES) return false
            return try {
                val pub = EdDSAPublicKey(EdDSAPublicKeySpec(publicKey, SPEC))
                val engine = EdDSAEngine(MessageDigest.getInstance(SPEC.hashAlgorithm))
                engine.initVerify(pub)
                engine.update(message)
                engine.verify(signature)
            } catch (_: Exception) {
                false
            }
        }
    }
}
