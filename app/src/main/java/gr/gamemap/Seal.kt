package gr.gamemap

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for what we post to the public ntfy.sh relay (convoy and live ETA).
 * Messages are AES-256-GCM; the key never leaves the people who have the code or link
 * (it lives in the part of the link after "#", which browsers never send to a server).
 * Message format: "e1." + base64url(12-byte IV + ciphertext with tag). The web pages
 * (docs/convoy, docs/live) do the same with WebCrypto.
 */
object Seal {
    private const val PREFIX = "e1."
    private const val CONVOY_SALT = "gameroute-convoy-v1"
    private const val ITERATIONS = 60_000
    private val rnd = SecureRandom()

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun randomKey(): ByteArray = ByteArray(16).also { rnd.nextBytes(it) }

    /** Convoy key from the 6-character code; slow on purpose so codes can't be tried quickly. */
    @Volatile private var cached: Pair<String, ByteArray>? = null
    fun convoyKey(code: String): ByteArray {
        cached?.let { if (it.first == code) return it.second }
        val spec = PBEKeySpec(code.toCharArray(), CONVOY_SALT.toByteArray(), ITERATIONS, 256)
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        cached = code to k
        return k
    }

    /** The relay topic for a convoy: a hash of the code, so the topic name doesn't reveal it. */
    fun convoyTopic(code: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest("gameroute-topic-v1:$code".toByteArray())
        return "gmc" + h.take(12).joinToString("") { "%02x".format(it) }
    }

    fun seal(key: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return PREFIX + b64(iv + c.doFinal(plain.toByteArray()))
    }

    /** Decrypts a message, or null if it isn't ours (wrong key, old plain message, tampered). */
    fun open(key: ByteArray, msg: String): String? {
        if (!msg.startsWith(PREFIX)) return null
        return try {
            val b = unb64(msg.substring(PREFIX.length))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b, 0, 12))
            String(c.doFinal(b, 12, b.size - 12))
        } catch (e: Exception) { null }
    }
}
