package dev.zxilly.jenkins.cnb.webhook

import dev.zxilly.jenkins.cnb.config.CnbServer
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class CnbWebhookValidatorTest {
    private val server = CnbServer("cnb-cool", "CNB", "https://cnb.cool", "https://api.cnb.cool")
    private val now = Instant.parse("2026-07-15T10:05:00Z")

    @Test
    fun `accepts a fresh delivery bound to the configured installation and origins`() {
        assertDoesNotThrow {
            CnbWebhookValidator.validate("cnb-cool", server, payload(), now)
        }
    }

    @Test
    fun `rejects stale deliveries`() {
        assertThrows(CnbWebhookValidationException::class.java) {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                payload().copy(occurredAt = Instant.parse("2026-07-15T09:00:00Z")),
                now,
            )
        }
    }

    @Test
    fun `rejects payloads for a different installation or CNB API origin`() {
        assertThrows(CnbWebhookValidationException::class.java) {
            CnbWebhookValidator.validate("cnb-cool", server, payload().copy(installationId = "other"), now)
        }
        assertThrows(CnbWebhookValidationException::class.java) {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                payload().copy(instance = CnbWebhookInstance("https://cnb.cool", "https://attacker.example")),
                now,
            )
        }
    }

    private fun payload() =
        CnbWebhookPayload(
            schema = CnbWebhookPayload.SCHEMA_V1,
            installationId = "cnb-cool",
            deliveryId = "pipeline-1",
            buildId = "build-1",
            occurredAt = Instant.parse("2026-07-15T10:00:00Z"),
            event = CnbWebhookEvent.PUSH,
            eventUrl = "https://cnb.cool/team/project/-/commit/${"b".repeat(40)}",
            retry = false,
            instance = CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
            repository = CnbWebhookRepository("repo-1", "team/project", "https://cnb.cool/team/project"),
            actor = CnbWebhookActor("user-1", "alice", "Alice", "alice@example.test"),
            ref = CnbWebhookRef("main", "b".repeat(40), "a".repeat(40), "b".repeat(40), false),
            pullRequest = null,
        )
}
