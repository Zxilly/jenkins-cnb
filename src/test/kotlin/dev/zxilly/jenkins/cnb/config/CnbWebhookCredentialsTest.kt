package dev.zxilly.jenkins.cnb.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CnbWebhookCredentialsTest {
    @Test
    fun `normalizes and binds a secret to one canonical repository`() {
        val credentials = CnbWebhookCredentials(" /team/sub/project/ ", " webhook-secret ")

        assertEquals("team/sub/project", credentials.repositoryPath)
        assertEquals("webhook-secret", credentials.secretCredentialsId)
    }

    @Test
    fun `rejects traversal whitespace and non-repository paths`() {
        listOf("team/../project", "team/project name", "team\\project", "project").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                CnbWebhookCredentials(path, "secret")
            }
        }
    }
}
