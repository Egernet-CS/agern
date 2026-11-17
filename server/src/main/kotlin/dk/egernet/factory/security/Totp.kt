package dk.egernet.factory.security

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** TOTP per RFC 6238 (HMAC-SHA1, 30 s steps), HOTP per RFC 4226. */
object Totp {
    const val STEP_SECONDS = 30L
    private val random = SecureRandom()

    fun newSecret(bytes: Int = 20): ByteArray = ByteArray(bytes).also { random.nextBytes(it) } // 160 bits

    fun step(epochSeconds: Long): Long = Math.floorDiv(epochSeconds, STEP_SECONDS)

    fun hotp(secret: ByteArray, counter: Long, digits: Int = 6, algorithm: String = "HmacSHA1"): String {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(secret, algorithm))
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val o = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or
            ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        var mod = 1L
        repeat(digits) { mod *= 10 }
        return (bin % mod).toString().padStart(digits, '0')
    }

    fun at(secret: ByteArray, epochSeconds: Long, digits: Int = 6, algorithm: String = "HmacSHA1") =
        hotp(secret, step(epochSeconds), digits, algorithm)

    /**
     * Checks [code] against steps now-1..now+1 (constant-time comparison for each) and returns the matching
     * step, or null. Steps <= [lastAcceptedStep] are rejected (replay protection).
     */
    fun verify(secret: ByteArray, code: String, epochSeconds: Long, lastAcceptedStep: Long?): Long? {
        val c = code.filter { !it.isWhitespace() }
        if (c.length != 6 || !c.all { it.isDigit() }) return null
        val now = step(epochSeconds)
        var match: Long? = null
        for (s in (now - 1)..(now + 1)) {
            if (MessageDigest.isEqual(hotp(secret, s).toByteArray(), c.toByteArray()) && match == null) match = s
        }
        if (match != null && lastAcceptedStep != null && match <= lastAcceptedStep) return null
        return match
    }

    fun otpauthUri(issuer: String, account: String, secretBase32: String): String {
        fun e(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${e(issuer)}:${e(account)}?secret=$secretBase32&issuer=${e(issuer)}&algorithm=SHA1&digits=6&period=30"
    }
}

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val clean = s.uppercase().filter { it != '=' && !it.isWhitespace() }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            require(v >= 0) { "invalid base32" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}

object RecoveryCodes {
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789" // no look-alikes
    private val random = SecureRandom()

    /** `xxxxx-xxxxx`, ~49 bits each. */
    fun generate(n: Int = 10): List<String> = List(n) {
        val chars = CharArray(10) { ALPHABET[random.nextInt(ALPHABET.length)] }
        String(chars, 0, 5) + "-" + String(chars, 5, 5)
    }

    fun normalise(code: String) = code.lowercase().filter { it.isLetterOrDigit() }

    fun hash(code: String) = Tokens.sha256Hex("agern-recovery:" + normalise(code))

    fun looksLikeRecoveryCode(code: String) = normalise(code).length == 10
}
