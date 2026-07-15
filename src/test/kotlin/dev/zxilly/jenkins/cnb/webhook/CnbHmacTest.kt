package dev.zxilly.jenkins.cnb.webhook

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CnbHmacTest {
    @Test
    fun `accepts the CNB webhook HMAC over the exact raw body`() {
        val body = "{\"event\":\"push\"}".toByteArray()

        assertTrue(
            CnbHmac.verify(
                body,
                "sha256=4a73af2e548d77ce1b343cd10dbbba9bb8f7995a28a583fd8600ec28d4ae2e0b",
                listOf("secret".toCharArray()),
            ),
        )
    }

    @Test
    fun `rejects malformed signatures and modified bodies`() {
        val valid = "sha256=4a73af2e548d77ce1b343cd10dbbba9bb8f7995a28a583fd8600ec28d4ae2e0b"

        assertFalse(CnbHmac.verify("{\"event\":\"tag_push\"}".toByteArray(), valid, listOf("secret".toCharArray())))
        assertFalse(CnbHmac.verify("{\"event\":\"push\"}".toByteArray(), "sha256=xyz", listOf("secret".toCharArray())))
        assertFalse(CnbHmac.verify("{\"event\":\"push\"}".toByteArray(), valid, emptyList()))
    }
}
