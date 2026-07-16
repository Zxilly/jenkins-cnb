package dev.zxilly.jenkins.cnb.webhook

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Implements the body-only signature emitted by the official `cnbcool/webhook` image. */
internal object CnbHmac {
    private val signaturePattern = Regex("sha256=[0-9a-f]{64}")

    fun isValidSignatureHeader(value: String?): Boolean = value != null && signaturePattern.matches(value)

    fun verify(
        rawBody: ByteArray,
        signatureHeader: String?,
        secrets: Collection<CharArray>,
    ): Boolean {
        val supplied = signatureHeader?.takeIf(::isValidSignatureHeader) ?: return false
        if (secrets.isEmpty()) return false
        val suppliedBytes = supplied.toByteArray(StandardCharsets.US_ASCII)
        var matched = false
        for (secret in secrets) {
            val keyBytes = encodeSecret(secret) ?: continue
            val computed =
                try {
                    val mac = Mac.getInstance(HMAC_ALGORITHM)
                    mac.init(SecretKeySpec(keyBytes, HMAC_ALGORITHM))
                    val digest = mac.doFinal(rawBody)
                    try {
                        "sha256=${digest.toHex()}".toByteArray(StandardCharsets.US_ASCII)
                    } finally {
                        digest.fill(0)
                    }
                } finally {
                    keyBytes.fill(0)
                }
            // Do not short-circuit: during a rotation both configured secrets are always checked.
            matched = MessageDigest.isEqual(computed, suppliedBytes) or matched
            computed.fill(0)
        }
        suppliedBytes.fill(0)
        return matched
    }

    private fun encodeSecret(secret: CharArray): ByteArray? {
        val encoder = StandardCharsets.UTF_8.newEncoder()
        val capacity = maxOf(1, kotlin.math.ceil(secret.size * encoder.maxBytesPerChar().toDouble()).toInt())
        val encoded = ByteBuffer.allocate(capacity)
        return try {
            val encodeResult = encoder.encode(CharBuffer.wrap(secret), encoded, true)
            if (encodeResult.isError) encodeResult.throwException()
            val flushResult = encoder.flush(encoded)
            if (flushResult.isError) flushResult.throwException()
            encoded.flip()
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: Exception) {
            // A malformed surrogate sequence is an invalid secret; never copy it into diagnostics.
            null
        } finally {
            encoded.array().fill(0)
        }
    }

    private fun ByteArray.toHex(): String {
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = HEX[value ushr 4]
            output[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(output)
    }

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HEX = "0123456789abcdef"
}
