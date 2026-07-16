package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.webhook.CnbWebhookActor
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookInstance
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPayload
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPullRequest
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRef
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant

class CnbPushCauseTest {
    @Test
    fun `exports documented CNB variables without credentials`() {
        val payload =
            CnbWebhookPayload(
                CnbWebhookPayload.SCHEMA_V1,
                "cnb-cool",
                "pipeline-1",
                "build-1",
                Instant.parse("2026-07-15T10:00:00Z"),
                CnbWebhookEvent.PUSH,
                "https://cnb.cool/team/project/-/commit/${"b".repeat(40)}",
                false,
                CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
                CnbWebhookRepository("repo-1", "team/project", "https://cnb.cool/team/project"),
                CnbWebhookActor("user-1", "alice", "Alice", "alice@example.test"),
                CnbWebhookRef("main", "b".repeat(40), "a".repeat(40), "b".repeat(40), false),
                null,
            )

        val variables = CnbPushCause.from(CnbWebhookDelivery("cnb-cool", payload, "test")).buildVariables()

        assertEquals("push", variables["CNB_EVENT"])
        assertEquals("team/project", variables["CNB_REPO_SLUG"])
        assertEquals("team/project", variables["CNB_REPOSITORY"])
        assertEquals("b".repeat(40), variables["CNB_COMMIT"])
        assertEquals(null, variables["CNB_TOKEN"])
    }

    @Test
    fun `exports stable pull request source aliases while retaining CNB names`() {
        val sourceSha = "c".repeat(40)
        val pullRequest =
            CnbWebhookPullRequest(
                id = "pr-7",
                number = "7",
                title = "Change",
                description = "",
                proposer = "alice",
                sourceRepository = "alice/project",
                sourceBranch = "feature/change",
                sourceSha = sourceSha,
                targetBranch = "main",
                targetSha = "b".repeat(40),
                mergeSha = null,
                action = "opened",
                wip = false,
                reviewers = "bob,carol",
                reviewState = "approve",
                reviewedBy = "bob",
                lastReviewedBy = "bob",
                commentId = "comment-1",
                commentBody = "Looks good",
                commentType = "note",
                reviewId = "review-1",
                reviewDescription = "Approved",
            )
        val payload =
            CnbWebhookPayload(
                CnbWebhookPayload.SCHEMA_V1,
                "cnb-cool",
                "pipeline-pr-7",
                "build-pr-7",
                Instant.parse("2026-07-15T10:00:00Z"),
                CnbWebhookEvent.PULL_REQUEST_TARGET,
                "https://cnb.cool/team/project/-/pulls/7",
                false,
                CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
                CnbWebhookRepository("repo-1", "team/project", "https://cnb.cool/team/project"),
                CnbWebhookActor("user-1", "alice", "Alice", "alice@example.test"),
                CnbWebhookRef("main", "b".repeat(40), "a".repeat(40), "b".repeat(40), false),
                pullRequest,
            )

        val cause = CnbPushCause.from(CnbWebhookDelivery("cnb-cool", payload, "test"))
        val variables = cause.buildVariables()

        assertEquals("feature/change", variables["CNB_PULL_REQUEST_BRANCH"])
        assertEquals("feature/change", variables["CNB_PULL_REQUEST_SOURCE_BRANCH"])
        assertEquals(sourceSha, variables["CNB_PULL_REQUEST_SHA"])
        assertEquals(sourceSha, variables["CNB_PULL_REQUEST_SOURCE_SHA"])
        assertEquals("main", variables["CNB_PULL_REQUEST_TARGET_BRANCH"])
        assertEquals("bob,carol", variables["CNB_PULL_REQUEST_REVIEWERS"])
        assertEquals("approve", variables["CNB_PULL_REQUEST_REVIEW_STATE"])
        assertEquals("comment-1", variables["CNB_COMMENT_ID"])
        assertEquals("Looks good", variables["CNB_COMMENT_BODY"])
        assertEquals("review-1", variables["CNB_REVIEW_ID"])
        assertEquals("true", variables["CNB_PULL_REQUEST"])
        assertEquals("true", variables["CNB_PULL_REQUEST_LIKE"])
        assertFalse(cause.shortDescription.contains("Looks good"))
        assertFalse(cause.toString().contains("Looks good"))

        val reviewVariables =
            CnbPushCause
                .from(CnbWebhookDelivery("cnb-cool", payload.copy(event = CnbWebhookEvent.PULL_REQUEST_APPROVED), "test"))
                .buildVariables()
        assertEquals("false", reviewVariables["CNB_PULL_REQUEST"])
        assertEquals("true", reviewVariables["CNB_PULL_REQUEST_LIKE"])
    }
}
