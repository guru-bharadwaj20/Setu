package `in`.setu.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Checks docs/vectors/packets-v1.txt, which the relay's TypeScript tests
 * generate. If this fails, the phone and the relay disagree about the wire
 * format and one of them is wrong.
 */
class SharedVectorsTest {
    private val now = 1_800_000_000L

    private fun vectors(): List<Map<String, String>> {
        val dir = System.getProperty("setu.vectors") ?: error("setu.vectors not set by Gradle")
        return File(dir, "packets-v1.txt").readText(Charsets.UTF_8).replace("\r\n", "\n")
            .split("\n\n").drop(1).map { block ->
                block.trim().lines().associate { it.substringBefore(": ") to it.substringAfter(": ") }
            }
    }

    @Test
    fun `kotlin produces the same bytes, ids and codes as the relay`() {
        val all = vectors()
        assertEquals(5, all.size)
        for (v in all) {
            val name = v.getValue("name")
            val identity = Identity.fromSeed(v.getValue("seed").hexToBytes())
            assertEquals(v["origin"], identity.publicKey.toHex(), name)
            val body = PacketBody(
                origin = identity.publicKey,
                type = PacketType.fromCode(v.getValue("type").toInt())!!,
                ts = v.getValue("ts").toLong(),
                latE5 = v.getValue("latE5").toInt(),
                lonE5 = v.getValue("lonE5").toInt(),
                accuracyM = v.getValue("accuracyM").toInt(),
                headcount = v.getValue("headcount").toInt(),
                needs = v.getValue("needs").toInt(),
                battery = v.getValue("battery").toInt(),
                ttl = v.getValue("ttl").toLong(),
                ref = v["ref"]?.let { PacketId.fromHex(it) },
                note = v["note"],
            )
            val created = PacketCodec.create(body, identity)
            val p = Packet(created.body, created.bodyBytes, created.signature, v.getValue("hops").toInt())
            assertEquals(v["body"], p.bodyBytes.toHex(), "$name body")
            assertEquals(v["wire"], PacketCodec.encode(p).toHex(), "$name wire")
            assertEquals(v["id"], p.id.hex, "$name id")
            assertEquals(v["code"], ShortCode.of(identity.publicKey), "$name code")

            val back = PacketCodec.decode(v.getValue("wire").hexToBytes(), now)
            assertEquals(body, back.body, "$name decode")
            assertEquals(p.hops, back.hops, "$name hops")
        }
    }
}
