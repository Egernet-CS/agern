package dk.egernet.factory.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for user secrets. Stored format: 12-byte random nonce || ciphertext+tag.
 * The username is bound as associated data so a ciphertext cannot be moved to another user's row.
 */
class SecretBox(masterKey: ByteArray) {
    init {
        require(masterKey.size == 32) { "master key must be 32 bytes" }
    }

    private val key = SecretKeySpec(masterKey.copyOf(), "AES")
    private val random = SecureRandom()

    fun encrypt(plaintext: String, associatedData: String): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(blob: ByteArray, associatedData: String): String {
        require(blob.size > NONCE_LEN) { "ciphertext too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, NONCE_LEN))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(blob, NONCE_LEN, blob.size - NONCE_LEN), Charsets.UTF_8)
    }

    companion object {
        private const val NONCE_LEN = 12
        private const val TAG_BITS = 128
    }
}

object Tokens {
    private val random = SecureRandom()

    fun random(bytes: Int = 32): String {
        val b = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
