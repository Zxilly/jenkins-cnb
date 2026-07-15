package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookActor
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookInstance
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPayload
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRef
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class CnbPushTriggerTest {
    @Test
    fun `matches live pushes but never schedules a deleted ref`() {
        val trigger = CnbPushTrigger("cnb-cool", "team/project", "release/**")

        assertTrue(trigger.matches(delivery("release/1", "a".repeat(40))))
        assertFalse(trigger.matches(delivery("release/1", "0".repeat(40))))
        assertFalse(trigger.matches(delivery("main", "a".repeat(40))))
    }

    @Test
    fun `rejects non-canonical repository configuration`() {
        assertThrows(IllegalArgumentException::class.java) {
            CnbPushTrigger("cnb-cool", "team/../victim", "**")
        }
    }

    @Test
    fun `accepts a branch only when CNB reports the signed revision as current`() {
        val expected = "a".repeat(40)
        val delivery = delivery("release/1", expected)

        assertTrue(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { repository, ref ->
                    assertTrue(repository == "team/project")
                    CnbBranch(ref, expected)
                },
                listTags = { error("Tag lookup must not be used for a branch push") },
            ),
        )
    }

    @Test
    fun `rejects a branch when its current CNB revision differs from the payload`() {
        val delivery = delivery("release/1", "a".repeat(40))

        assertFalse(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, ref -> CnbBranch(ref, "b".repeat(40)) },
                listTags = { error("Tag lookup must not be used for a branch push") },
            ),
        )
    }

    @Test
    fun `treats a missing branch as stale without scheduling it`() {
        val delivery = delivery("release/1", "a".repeat(40))

        assertFalse(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, _ -> throw CnbApiException("missing", statusCode = 404) },
                listTags = { error("Tag lookup must not be used for a branch push") },
            ),
        )
    }

    @Test
    fun `propagates a CNB API failure so the webhook can be retried`() {
        val delivery = delivery("release/1", "a".repeat(40))

        assertThrows(CnbApiException::class.java) {
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, _ -> throw CnbApiException("unavailable", statusCode = 503, retryable = true) },
                listTags = { error("Tag lookup must not be used for a branch push") },
            )
        }
    }

    @Test
    fun `accepts a tag only when the exact tag and revision exist in CNB`() {
        val expected = "c".repeat(40)
        val delivery = delivery("v1.0.0", expected, CnbWebhookEvent.TAG_PUSH, tag = true)

        assertTrue(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, _ -> error("Branch lookup must not be used for a tag push") },
                listTags = { listOf(CnbTag("v0.9.0", "b".repeat(40)), CnbTag("v1.0.0", expected)) },
            ),
        )
        assertFalse(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, _ -> error("Branch lookup must not be used for a tag push") },
                listTags = { listOf(CnbTag("v1.0.0", "d".repeat(40))) },
            ),
        )
    }

    @Test
    fun `classic preflight never blocks SCM hints and creates no queue effects when unverified`() {
        val sideEffects = mutableListOf<String>()
        val schedules = listOf<() -> Unit>({ sideEffects += "job-1" }, { sideEffects += "job-2" })

        assertThrows(CnbApiException::class.java) {
            dispatchAfterRefPreflight(
                requiresVerification = true,
                verify = { throw CnbApiException("unavailable", statusCode = 503, retryable = true) },
                fireScmEvent = { sideEffects += "scm-event" },
                scheduleActions = schedules,
            )
        }
        assertEquals(listOf("scm-event"), sideEffects)
        sideEffects.clear()

        assertFalse(
            dispatchAfterRefPreflight(
                requiresVerification = true,
                verify = { false },
                fireScmEvent = { sideEffects += "scm-event" },
                scheduleActions = schedules,
            ),
        )
        assertEquals(listOf("scm-event"), sideEffects)
        sideEffects.clear()

        var successfulVerificationCalls = 0
        assertTrue(
            dispatchAfterRefPreflight(
                requiresVerification = true,
                verify = {
                    successfulVerificationCalls++
                    true
                },
                fireScmEvent = { sideEffects += "scm-event" },
                scheduleActions = schedules,
            ),
        )
        assertEquals(1, successfulVerificationCalls)
        assertEquals(listOf("scm-event", "job-1", "job-2"), sideEffects)
    }

    private fun delivery(
        ref: String,
        current: String,
        event: CnbWebhookEvent = CnbWebhookEvent.PUSH,
        tag: Boolean = false,
    ): CnbWebhookDelivery {
        val payload =
            CnbWebhookPayload(
                CnbWebhookPayload.SCHEMA_V1,
                "cnb-cool",
                "delivery-1",
                "build-1",
                Instant.parse("2026-07-15T10:00:00Z"),
                event,
                "https://cnb.cool/team/project",
                false,
                CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
                CnbWebhookRepository("repo-1", "team/project", "https://cnb.cool/team/project"),
                CnbWebhookActor("user-1", "alice", "Alice", ""),
                CnbWebhookRef(ref, current, "b".repeat(40), current, tag),
                null,
            )
        return CnbWebhookDelivery("cnb-cool", payload, "test")
    }
}
