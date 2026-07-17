package dev.zxilly.jenkins.cnb.webhook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CnbWebhookPayloadTest {
    @Test
    fun `parses the cnbcool webhook v1_0_2 default flat payload`() {
        val payload = CnbWebhookPayloadParser.parse(DEFAULT_PULL_REQUEST_COMMENT.toByteArray())

        assertEquals("pipeline-1", payload.deliveryId)
        assertEquals("build-1", payload.buildId)
        assertEquals(CnbWebhookEvent.PULL_REQUEST_COMMENT, payload.event)
        assertEquals("team/project", payload.repository.slug)
        assertEquals("main", payload.ref.name)
        assertEquals(SHA_B, payload.ref.commit)
        assertEquals(false, payload.ref.tag)
        assertEquals("alice", payload.actor.username)
        assertEquals("42", payload.pullRequest?.number)
        assertEquals("alice/project", payload.pullRequest?.sourceRepository)
        assertEquals("feature/safe", payload.pullRequest?.sourceBranch)
        assertEquals("comment-7", payload.pullRequest?.commentId)
        assertEquals("Looks good", payload.pullRequest?.commentBody)
        assertEquals("", payload.pullRequest?.description)
        assertNull(payload.pullRequest?.wip)
    }

    @Test
    fun `parses optional flat metadata when a newer webhook version sends it`() {
        val extended =
            DEFAULT_PULL_REQUEST_COMMENT
                .replace(
                    "\"CNB_PULL_REQUEST_TITLE\": \"Safe change\"",
                    """
                    "CNB_PULL_REQUEST_TITLE": "Safe change",
                    "CNB_PULL_REQUEST_DESCRIPTION": "Description",
                    "CNB_PULL_REQUEST_IS_WIP": "true",
                    "CNB_COMMENT_TYPE": "diff_note",
                    "CNB_COMMENT_FILE_PATH": "src/App.kt",
                    "CNB_COMMENT_RANGE": "L12-L16",
                    "CNB_REVIEW_ID": "review-7",
                    "CNB_REVIEW_DESCRIPTION": "Approved"
                    """.trimIndent(),
                )

        val pullRequest = CnbWebhookPayloadParser.parse(extended.toByteArray()).pullRequest

        assertEquals("Description", pullRequest?.description)
        assertEquals(true, pullRequest?.wip)
        assertEquals("diff_note", pullRequest?.commentType)
        assertEquals("src/App.kt", pullRequest?.commentFilePath)
        assertEquals("L12-L16", pullRequest?.commentRange)
        assertEquals("review-7", pullRequest?.reviewId)
        assertEquals("Approved", pullRequest?.reviewDescription)
    }

    @Test
    fun `ignores unknown flat fields for forward compatibility`() {
        val future = DEFAULT_PULL_REQUEST_COMMENT.replaceFirst("{", "{\"CNB_FUTURE_FIELD\":{\"enabled\":true},")

        val payload = CnbWebhookPayloadParser.parse(future.toByteArray())

        assertEquals("team/project", payload.repository.slug)
        assertEquals("42", payload.pullRequest?.number)
    }

    @Test
    fun `rejects the removed nested bridge format`() {
        val legacy =
            """
            {
              "schema": "dev.zxilly.jenkins.cnb.webhook.v1",
              "installation_id": "cnb-cool",
              "delivery_id": "pipeline-1",
              "event": "push",
              "repository": {"slug": "team/project"}
            }
            """.trimIndent()

        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(legacy.toByteArray())
        }
    }

    @Test
    fun `rejects missing and wrongly typed required flat fields`() {
        val missing = DEFAULT_PULL_REQUEST_COMMENT.replace("  \"CNB_PIPELINE_ID\": \"pipeline-1\",\n", "")
        val wrongType = DEFAULT_PULL_REQUEST_COMMENT.replace("\"CNB_PIPELINE_ID\": \"pipeline-1\"", "\"CNB_PIPELINE_ID\": 7")

        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(missing.toByteArray())
        }
        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(wrongType.toByteArray())
        }
    }

    @Test
    fun `accepts only native string booleans`() {
        val invalid = DEFAULT_PULL_REQUEST_COMMENT.replace("\"CNB_IS_RETRY\": \"false\"", "\"CNB_IS_RETRY\": \"yes\"")
        val wrongType = DEFAULT_PULL_REQUEST_COMMENT.replace("\"CNB_IS_TAG\": \"false\"", "\"CNB_IS_TAG\": false")

        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(invalid.toByteArray())
        }
        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(wrongType.toByteArray())
        }
    }

    @Test
    fun `preserves complete SHA-256 object IDs`() {
        val source = "d".repeat(64)
        val target = "e".repeat(64)
        val merge = "f".repeat(64)
        val encoded =
            DEFAULT_PULL_REQUEST_COMMENT
                .replace(SHA_A, source)
                .replace(SHA_B, target)
                .replace(SHA_C, merge)

        val payload = CnbWebhookPayloadParser.parse(encoded.toByteArray())

        assertEquals(source, payload.pullRequest?.sourceSha)
        assertEquals(target, payload.pullRequest?.targetSha)
        assertEquals(merge, payload.pullRequest?.mergeSha)
        assertEquals(target, payload.ref.commit)
    }

    @Test
    fun `requires API addressable pull request details for pull request events`() {
        val missingNumber = DEFAULT_PULL_REQUEST_COMMENT.replace("  \"CNB_PULL_REQUEST_IID\": \"42\",\n", "")

        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(missingNumber.toByteArray())
        }
    }

    @Test
    fun `recognizes every supported CNB code and pull request event`() {
        val expected =
            setOf(
                "push",
                "commit.add",
                "branch.create",
                "branch.delete",
                "tag_push",
                "pull_request",
                "pull_request.update",
                "pull_request.approved",
                "pull_request.changes_requested",
                "pull_request.comment",
                "pull_request.target",
                "pull_request.mergeable",
                "pull_request.merged",
            )

        assertEquals(expected, CnbWebhookEvent.entries.map { it.wireName }.toSet())
        assertEquals(
            expected.filter { it.startsWith("pull_request") }.toSet(),
            CnbWebhookEvent.entries
                .filter { it.pullRequestEvent }
                .map { it.wireName }
                .toSet(),
        )
    }

    private companion object {
        val SHA_A = "a".repeat(40)
        val SHA_B = "b".repeat(40)
        val SHA_C = "c".repeat(40)

        val DEFAULT_PULL_REQUEST_COMMENT =
            """
            {
              "CNB_WEB_ENDPOINT": "https://cnb.cool",
              "CNB_API_ENDPOINT": "https://api.cnb.cool",
              "CNB_EVENT": "pull_request.comment",
              "CNB_EVENT_URL": "https://cnb.cool/team/project/-/pulls/42",
              "CNB_BRANCH": "main",
              "CNB_BRANCH_SHA": "$SHA_B",
              "CNB_BEFORE_SHA": "$SHA_A",
              "CNB_COMMIT": "$SHA_B",
              "CNB_IS_TAG": "false",
              "CNB_REPO_SLUG": "team/project",
              "CNB_REPO_ID": "repo-1",
              "CNB_REPO_URL_HTTPS": "https://cnb.cool/team/project",
              "CNB_BUILD_ID": "build-1",
              "CNB_BUILD_START_TIME": "2026-07-15T10:00:00Z",
              "CNB_BUILD_USER": "alice",
              "CNB_BUILD_USER_ID": "user-1",
              "CNB_PIPELINE_ID": "pipeline-1",
              "CNB_IS_RETRY": "false",
              "CNB_PULL_REQUEST_ID": "pr-global-42",
              "CNB_PULL_REQUEST_IID": "42",
              "CNB_PULL_REQUEST_TITLE": "Safe change",
              "CNB_PULL_REQUEST_PROPOSER": "alice",
              "CNB_PULL_REQUEST_SLUG": "alice/project",
              "CNB_PULL_REQUEST_BRANCH": "feature/safe",
              "CNB_PULL_REQUEST_SHA": "$SHA_A",
              "CNB_PULL_REQUEST_TARGET_SHA": "$SHA_B",
              "CNB_PULL_REQUEST_MERGE_SHA": "$SHA_C",
              "CNB_PULL_REQUEST_ACTION": "submitted",
              "CNB_COMMENT_ID": "comment-7",
              "CNB_COMMENT_BODY": "Looks good"
            }
            """.trimIndent()
    }
}
