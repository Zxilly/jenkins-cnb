package dev.zxilly.jenkins.cnb.webhook

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Implements the body-only signature emitted by the official `cnbcool/webhook` image. */
internal object CnbHmac {
    private val signaturePattern = Regex("sha256=[0-9a-f]{64}")

    fun verify(
        rawBody: ByteArray,
        signatureHeader: String?,
        secrets: Collection<CharArray>,
    ): Boolean {
        val supplied = signatureHeader?.takeIf { signaturePattern.matches(it) } ?: return false
        if (secrets.isEmpty()) return false
        val suppliedBytes = supplied.toByteArray(StandardCharsets.US_ASCII)
        var matched = false
        for (secret in secrets) {
            val keyBytes = String(secret).toByteArray(StandardCharsets.UTF_8)
            val computed =
                try {
                    val mac = Mac.getInstance(HMAC_ALGORITHM)
                    mac.init(SecretKeySpec(keyBytes, HMAC_ALGORITHM))
                    "sha256=${mac.doFinal(rawBody).toHex()}".toByteArray(StandardCharsets.US_ASCII)
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
