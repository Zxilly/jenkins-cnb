package dev.zxilly.jenkins.cnb.webhook

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CnbWebhookCrumbExclusionTest {
    @Test
    fun `excludes only POSTs to one exact server webhook path`() {
        assertTrue(CnbWebhookCrumbExclusion.matches("POST", "/cnb-webhook/cnb-cool"))
        assertTrue(CnbWebhookCrumbExclusion.matches("POST", "/cnb-webhook/cnb-cool/"))

        assertFalse(CnbWebhookCrumbExclusion.matches("GET", "/cnb-webhook/cnb-cool"))
        assertFalse(CnbWebhookCrumbExclusion.matches("POST", "/cnb-webhook"))
        assertFalse(CnbWebhookCrumbExclusion.matches("POST", "/cnb-webhook/cnb-cool/extra"))
        assertFalse(CnbWebhookCrumbExclusion.matches("POST", "/cnb-webhook/a/b"))
    }
}
