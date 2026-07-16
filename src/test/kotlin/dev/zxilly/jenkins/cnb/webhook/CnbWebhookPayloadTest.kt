package dev.zxilly.jenkins.cnb.webhook

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        assertEquals("2", payload.ref.newCommitsCount)
        assertEquals("approve", payload.pullRequest?.reviewState)
        assertEquals("comment-7", payload.pullRequest?.commentId)
    }

    @Test
    fun `wraps malformed JSON as a webhook format failure`() {
        val malformed = TRUSTED_PULL_REQUEST.dropLast(1)

        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(malformed.toByteArray())
        }
    }

    @Test
    fun `ignores unknown fields at every typed webhook level`() {
        val futurePayload =
            TRUSTED_PULL_REQUEST
                .replace(
                    "\"schema\": \"dev.zxilly.jenkins.cnb.webhook.v1\"",
                    "\"schema\": \"dev.zxilly.jenkins.cnb.webhook.v1\", \"future_root\": {\"enabled\": true}",
                ).replace(
                    "\"slug\": \"team/project\"",
                    "\"slug\": \"team/project\", \"future_repository_field\": [1, 2, 3]",
                ).replace(
                    "\"source_branch\": \"feature/safe\"",
                    "\"source_branch\": \"feature/safe\", \"future_pull_field\": {\"value\": \"kept-compatible\"}",
                )

        val payload = CnbWebhookPayloadParser.parse(futurePayload.toByteArray())

        assertEquals("team/project", payload.repository.slug)
        assertEquals("feature/safe", payload.pullRequest?.sourceBranch)
    }

    @Test
    fun `rejects missing and wrongly typed required fields`() {
        val missing = TRUSTED_PULL_REQUEST.replace("  \"installation_id\": \"cnb-cool\",\n", "")
        val wrongType = TRUSTED_PULL_REQUEST.replace("\"installation_id\": \"cnb-cool\"", "\"installation_id\": 7")

        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(missing.toByteArray())
        }
        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(wrongType.toByteArray())
        }
    }

    @Test
    fun `accepts only the documented boolean and commit count compatibility scalars`() {
        val compatible =
            TRUSTED_PULL_REQUEST
                .replace("\"is_retry\": false", "\"is_retry\": \"true\"")
                .replace("\"is_tag\": false", "\"is_tag\": \"false\"")
                .replace("\"new_commits_count\": 2", "\"new_commits_count\": \"2\"")

        val payload = CnbWebhookPayloadParser.parse(compatible.toByteArray())

        assertEquals(true, payload.retry)
        assertEquals(false, payload.ref.tag)
        assertEquals("2", payload.ref.newCommitsCount)

        val coercedNumber = TRUSTED_PULL_REQUEST.replace("\"is_retry\": false", "\"is_retry\": 1")
        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(coercedNumber.toByteArray())
        }
    }

    @Test
    fun `typed parser preserves complete SHA-256 object IDs`() {
        val source = "d".repeat(64)
        val target = "e".repeat(64)
        val merge = "f".repeat(64)
        val encoded =
            TRUSTED_PULL_REQUEST
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
    fun `accepts untrusted pull request events only with pull request details`() {
        val untrusted = TRUSTED_PULL_REQUEST.replace("pull_request.target", "pull_request")

        val payload = CnbWebhookPayloadParser.parse(untrusted.toByteArray())

        assertEquals(CnbWebhookEvent.PULL_REQUEST, payload.event)
        assertEquals("42", payload.pullRequest?.number)

        val missingDetails = untrusted.substringBefore("\n  \"pull_request\"").removeSuffix(",") + "\n}"
        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(missingDetails.toByteArray())
        }
    }

    @Test
    fun `decodes the cnbcool webhook v1_0_2 JSON fragment encoding exactly once`() {
        val refValue = "feature/a&b<c>d\"e'f雪"
        val textValue = "amp & < > \" ' \\ literal-\\n actual\nnewline Unicode 雪"
        val encoded =
            TRUSTED_PULL_REQUEST
                .replace(
                    "\"schema\": \"dev.zxilly.jenkins.cnb.webhook.v1\"",
                    "\"schema\": \"dev.zxilly.jenkins.cnb.webhook.v1\", " +
                        "\"encoding\": \"${CnbWebhookPayload.CNBCOOL_WEBHOOK_V1_0_2_JSON_FRAGMENT}\"",
                ).replace("\"name\": \"main\"", "\"name\": ${upstreamV102OuterString(refValue)}")
                .replace("\"title\": \"Safe change\"", "\"title\": ${upstreamV102OuterString(textValue)}")
                .replace("\"description\": \"Description\"", "\"description\": ${upstreamV102OuterString(textValue)}")
                .replace("\"comment_body\": \"Looks good\"", "\"comment_body\": ${upstreamV102OuterString(textValue)}")

        val payload = CnbWebhookPayloadParser.parse(encoded.toByteArray())

        assertEquals(CnbWebhookPayload.CNBCOOL_WEBHOOK_V1_0_2_JSON_FRAGMENT, payload.encoding)
        assertEquals(refValue, payload.ref.name)
        assertEquals(textValue, payload.pullRequest?.title)
        assertEquals(textValue, payload.pullRequest?.description)
        assertEquals(textValue, payload.pullRequest?.commentBody)
    }

    @Test
    fun `rejects an invalid cnbcool webhook JSON fragment`() {
        val malformedFragment = "not-json\\q"
        val encoded =
            TRUSTED_PULL_REQUEST
                .replace(
                    "\"schema\": \"dev.zxilly.jenkins.cnb.webhook.v1\"",
                    "\"schema\": \"dev.zxilly.jenkins.cnb.webhook.v1\", " +
                        "\"encoding\": \"${CnbWebhookPayload.CNBCOOL_WEBHOOK_V1_0_2_JSON_FRAGMENT}\"",
                ).replace(
                    "\"title\": \"Safe change\"",
                    "\"title\": ${TEST_JSON.encodeToString(malformedFragment)}",
                )

        assertThrows(CnbWebhookFormatException::class.java) {
            CnbWebhookPayloadParser.parse(encoded.toByteArray())
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
        assertEquals(
            setOf(
                "pull_request",
                "pull_request.update",
                "pull_request.approved",
                "pull_request.changes_requested",
                "pull_request.comment",
            ),
            CnbWebhookEvent.entries
                .filter { it.untrusted }
                .map { it.wireName }
                .toSet(),
        )
    }

    companion object {
        private val TEST_JSON = Json { isLenient = false }
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
              "ref": {"name": "main", "sha": "$SHA_B", "before": "$SHA_A", "commit": "$SHA_B", "is_tag": false, "new_commits_count": 2},
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
                "wip": false,
                "reviewers": "bob,carol",
                "review_state": "approve",
                "reviewed_by": "bob",
                "last_reviewed_by": "bob",
                "comment_id": "comment-7",
                "comment_body": "Looks good",
                "comment_type": "note",
                "comment_file_path": "src/App.kt",
                "comment_range": "L12-L16",
                "review_id": "review-7",
                "review_description": "Approved"
              }
            }
            """.trimIndent()

        private fun upstreamV102OuterString(value: String): String {
            val encodedValue = TEST_JSON.encodeToString(value)
            val jsonFragment = encodedValue.substring(1, encodedValue.lastIndex)
            return TEST_JSON.encodeToString(jsonFragment)
        }
    }
}
