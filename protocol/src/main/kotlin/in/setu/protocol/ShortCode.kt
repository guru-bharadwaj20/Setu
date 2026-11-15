package `in`.setu.protocol

/**
 * Six-character Crockford base32 code derived from an origin key, shown as
 * `ABC-DEF`. Families use it to look up a sender on the relay.
 */
object ShortCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 6

    fun of(origin: ByteArray): String {
        val digest = sha256(origin)
        val sb = StringBuilder(LENGTH)
        var bits = 0
        var acc = 0
        var i = 0
        while (sb.length < LENGTH) {
            acc = (acc shl 8) or (digest[i++].toInt() and 0xff)
            bits += 8
            while (bits >= 5 && sb.length < LENGTH) {
                bits -= 5
                sb.append(ALPHABET[(acc ushr bits) and 0x1f])
            }
        }
        return sb.toString()
    }

    fun display(code: String): String = code.substring(0, 3) + "-" + code.substring(3)

    /** Accepts user input in any case, with or without the dash, with common confusions mapped. */
    fun normalise(input: String): String? {
        val cleaned = input.uppercase().filter { it != '-' && it != ' ' }
            .map {
                when (it) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    'U' -> 'V'
                    else -> it
                }
            }.joinToString("")
        if (cleaned.length != LENGTH || cleaned.any { it !in ALPHABET }) return null
        return cleaned
    }
}
