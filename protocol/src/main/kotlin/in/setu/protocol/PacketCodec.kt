package `in`.setu.protocol

import `in`.setu.protocol.Protocol.Key

class PacketException(message: String) : Exception(message)

/** Encodes, signs, decodes and validates packets as defined in docs/PROTOCOL.md. */
object PacketCodec {

    /** Canonical CBOR of the body map: ascending integer keys, optional keys omitted when absent. */
    fun encodeBody(b: PacketBody): ByteArray {
        var n = 11
        if (b.ref != null) n++
        if (!b.note.isNullOrEmpty()) n++
        val w = CborWriter().map(n)
        w.uint(Key.V.toLong()).uint(Protocol.VERSION.toLong())
        w.uint(Key.ORIGIN.toLong()).bytes(b.origin)
        w.uint(Key.TYPE.toLong()).uint(b.type.code.toLong())
        w.uint(Key.TS.toLong()).uint(b.ts)
        w.uint(Key.LAT.toLong()).int(b.latE5.toLong())
        w.uint(Key.LON.toLong()).int(b.lonE5.toLong())
        w.uint(Key.ACC.toLong()).uint(b.accuracyM.toLong())
        w.uint(Key.HEAD.toLong()).uint(b.headcount.toLong())
        w.uint(Key.NEEDS.toLong()).uint(b.needs.toLong())
        w.uint(Key.BATT.toLong()).uint(b.battery.toLong())
        w.uint(Key.TTL.toLong()).uint(b.ttl)
        if (b.ref != null) w.uint(Key.REF.toLong()).bytes(b.ref.bytes)
        if (!b.note.isNullOrEmpty()) w.uint(Key.NOTE.toLong()).text(b.note)
        return w.bytes()
    }

    fun decodeBody(bytes: ByteArray): PacketBody {
        try {
            val r = CborReader(bytes)
            val n = r.map()
            if (n < 11 || n > 13) throw PacketException("body has $n keys")
            var v = -1L
            var origin: ByteArray? = null
            var type = -1L
            var ts = -1L
            var lat: Long? = null
            var lon: Long? = null
            var acc = -1L
            var head = -1L
            var needs = -1L
            var batt = -1L
            var ttl = -1L
            var ref: ByteArray? = null
            var note: String? = null
            var lastKey = -1L
            repeat(n) {
                val k = r.uint()
                if (k <= lastKey) throw PacketException("keys not strictly ascending")
                lastKey = k
                when (k.toInt()) {
                    Key.V -> v = r.uint()
                    Key.ORIGIN -> origin = r.bytes()
                    Key.TYPE -> type = r.uint()
                    Key.TS -> ts = r.uint()
                    Key.LAT -> lat = r.int()
                    Key.LON -> lon = r.int()
                    Key.ACC -> acc = r.uint()
                    Key.HEAD -> head = r.uint()
                    Key.NEEDS -> needs = r.uint()
                    Key.BATT -> batt = r.uint()
                    Key.TTL -> ttl = r.uint()
                    Key.REF -> ref = r.bytes()
                    Key.NOTE -> note = r.text()
                    else -> throw PacketException("unknown key $k")
                }
            }
            r.end()
            if (v != Protocol.VERSION.toLong()) throw PacketException("unsupported version $v")
            val o = origin ?: throw PacketException("missing origin")
            if (o.size != Protocol.ORIGIN_BYTES) throw PacketException("origin must be 32 bytes")
            val pt = PacketType.fromCode(type.toInt()) ?: throw PacketException("unknown type $type")
            val la = lat ?: throw PacketException("missing lat")
            val lo = lon ?: throw PacketException("missing lon")
            if (ts < 0 || acc < 0 || head < 0 || needs < 0 || batt < 0 || ttl < 0) {
                throw PacketException("missing required field")
            }
            val rf = ref
            if (rf != null && rf.size != Protocol.ID_BYTES) throw PacketException("ref must be 16 bytes")
            if (la !in -90_00000L..90_00000L || lo !in -180_00000L..180_00000L) throw PacketException("bad coordinates")
            if (acc > Int.MAX_VALUE || head > Int.MAX_VALUE || needs > Int.MAX_VALUE || batt > Int.MAX_VALUE) {
                throw PacketException("field too large")
            }
            return PacketBody(
                origin = o, type = pt, ts = ts, latE5 = la.toInt(), lonE5 = lo.toInt(),
                accuracyM = acc.toInt(), headcount = head.toInt(), needs = needs.toInt(), battery = batt.toInt(),
                ttl = ttl, ref = rf?.let(::PacketId), note = note,
            )
        } catch (e: CborException) {
            throw PacketException("cbor: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw PacketException(e.message ?: "invalid body")
        }
    }

    /** Builds and signs a fresh packet with zero hops. */
    fun create(body: PacketBody, identity: Identity): Packet {
        require(identity.publicKey.contentEquals(body.origin)) { "origin must be the signer's public key" }
        val bytes = encodeBody(body)
        val packet = Packet(body, bytes, identity.sign(bytes), hops = 0)
        val size = encode(packet).size
        if (size > Protocol.MAX_PACKET_BYTES) {
            throw PacketException("packet is $size bytes, limit ${Protocol.MAX_PACKET_BYTES}")
        }
        return packet
    }

    /** Outer array: [body, sig, hops]. */
    fun encode(p: Packet): ByteArray =
        CborWriter().array(3).bytes(p.bodyBytes).bytes(p.signature).uint(p.hops.toLong()).bytes()

    /**
     * Parses and fully validates a packet received from an untrusted peer.
     * Throws [PacketException] on any rule violation; callers drop silently.
     */
    fun decode(bytes: ByteArray, now: Long, verifySignature: Boolean = true): Packet {
        if (bytes.size > Protocol.MAX_PACKET_BYTES) throw PacketException("packet too large: ${bytes.size}")
        val bodyBytes: ByteArray
        val sig: ByteArray
        val hops: Long
        try {
            val r = CborReader(bytes)
            if (r.array() != 3) throw PacketException("outer array must have 3 items")
            bodyBytes = r.bytes()
            sig = r.bytes()
            hops = r.uint()
            r.end()
        } catch (e: CborException) {
            throw PacketException("cbor: ${e.message}")
        }
        if (sig.size != Protocol.SIG_BYTES) throw PacketException("sig must be 64 bytes")
        if (hops >= Protocol.MAX_HOPS) throw PacketException("too many hops: $hops")
        val body = decodeBody(bodyBytes)
        if (body.ttl > Protocol.MAX_TTL_SECONDS) throw PacketException("ttl too long")
        if (body.ts > now + Protocol.MAX_CLOCK_SKEW_SECONDS) throw PacketException("ts in the future")
        if (body.expiresAt <= now) throw PacketException("expired")
        if (verifySignature && !Identity.verify(body.origin, bodyBytes, sig)) throw PacketException("bad signature")
        return Packet(body, bodyBytes, sig, hops.toInt())
    }

    /** Like [decode] but returns null instead of throwing. */
    fun tryDecode(bytes: ByteArray, now: Long): Packet? = try {
        decode(bytes, now)
    } catch (_: PacketException) {
        null
    }
}
