package dev.zxilly.jenkins.cnb.webhook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CnbWebhookPayloadTest {
    @Test
    fun `parses the versioned trusted pull request contract`() {
        val payload = CnbWebhookPayloadParser.parse(TRUSTED_PULL_REQUEST.toByteArray())

        assertEquals(CnbWebhookEvent.PULL_REQUEST_TARGET, payload.event)
        assertEquals("cnb-cool", payload.installationId)
        assertEquals("team/project", payload.repository.slug)
        assertEquals("42", payload.pullRequest?.number)
        assertEquals("feature/safe", payload.pullRequest?.sourceBranch)
        assertEquals("main", payload.pullRequest?.targetBranch)
    }

    @Test
    fun `rejects duplicate JSON fields`() {
        val duplicate =
            TRUSTED_PULL_REQUEST.replaceFirst(
                "\"delivery_id\": \"pipeline-1\"",
                "\"delivery_id\": \"one\", \"delivery_id\": \"two\"",
            )

        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(duplicate.toByteArray())
        }
    }

    @Test
    fun `rejects untrusted pull request event names`() {
        val untrusted = TRUSTED_PULL_REQUEST.replace("pull_request.target", "pull_request")

        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(untrusted.toByteArray())
        }
    }

    companion object {
        private val SHA_A = "a".repeat(40)
        private val SHA_B = "b".repeat(40)
        private val SHA_C = "c".repeat(40)

        private val TRUSTED_PULL_REQUEST =
            """
            {
              "schema": "dev.zxilly.jenkins.cnb.webhook.v1",
              "installation_id": "cnb-cool",
              "delivery_id": "pipeline-1",
              "build_id": "build-1",
              "occurred_at": "2026-07-15T10:00:00Z",
              "event": "pull_request.target",
              "event_url": "https://cnb.cool/team/project/-/pulls/42",
              "is_retry": false,
              "instance": {"web_url": "https://cnb.cool", "api_url": "https://api.cnb.cool"},
              "repository": {"id": "repo-1", "slug": "team/project", "url": "https://cnb.cool/team/project"},
              "actor": {"id": "user-1", "username": "alice", "nickname": "Alice", "email": "alice@example.test"},
              "ref": {"name": "main", "sha": "$SHA_B", "before": "$SHA_A", "commit": "$SHA_B", "is_tag": false},
              "pull_request": {
                "id": "pr-global-42",
                "number": "42",
                "title": "Safe change",
                "description": "Description",
                "proposer": "alice",
                "source_repo": "alice/project",
                "source_branch": "feature/safe",
                "source_sha": "$SHA_A",
                "target_branch": "main",
                "target_sha": "$SHA_B",
                "merge_sha": "$SHA_C",
                "action": "synchronize",
                "wip": false
              }
            }
            """.trimIndent()
    }
}
