package `in`.setu.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentityTest {
    @Test
    fun `RFC 8032 test vector 1`() {
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes()
        val id = Identity.fromSeed(seed)
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", id.publicKey.toHex())
        val sig = id.sign(ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            sig.toHex(),
        )
        assertTrue(Identity.verify(id.publicKey, ByteArray(0), sig))
    }

    @Test
    fun `RFC 8032 test vector 2`() {
        val seed = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb".hexToBytes()
        val id = Identity.fromSeed(seed)
        assertEquals("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", id.publicKey.toHex())
        val sig = id.sign(byteArrayOf(0x72))
        assertEquals(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            sig.toHex(),
        )
    }

    @Test
    fun `seed round trips and tampering fails`() {
        val a = Identity.generate()
        val b = Identity.fromSeed(a.seed)
        assertArrayEquals(a.publicKey, b.publicKey)
        val msg = "hello".toByteArray()
        val sig = a.sign(msg)
        assertTrue(Identity.verify(a.publicKey, msg, sig))
        assertFalse(Identity.verify(a.publicKey, "hellp".toByteArray(), sig))
        sig[3] = (sig[3].toInt() xor 1).toByte()
        assertFalse(Identity.verify(a.publicKey, msg, sig))
        assertFalse(Identity.verify(ByteArray(31), msg, sig))
        assertFalse(Identity.verify(Identity.generate().publicKey, msg, a.sign(msg)))
    }
}
