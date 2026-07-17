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
    fun `rejects a mismatched server route or CNB API origin`() {
        assertThrows(CnbWebhookValidationException::class.java) {
            CnbWebhookValidator.validate("other", server, payload(), now)
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

    @Test
    fun `requires pull request details exactly for pull request events`() {
        assertThrows(CnbWebhookValidationException::class.java) {
            CnbWebhookValidator.validate("cnb-cool", server, payload().copy(event = CnbWebhookEvent.PULL_REQUEST), now)
        }
        val pullRequestPayload =
            payload().copy(
                event = CnbWebhookEvent.PULL_REQUEST_COMMENT,
                pullRequest = pullRequest(commentBody = "Please update this line"),
            )
        assertDoesNotThrow { CnbWebhookValidator.validate("cnb-cool", server, pullRequestPayload, now) }
        assertThrows(CnbWebhookValidationException::class.java) {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                pullRequestPayload.copy(event = CnbWebhookEvent.PUSH),
                now,
            )
        }
    }

    @Test
    fun `validates new commit count and comment metadata`() {
        assertDoesNotThrow {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                payload().copy(ref = payload().ref.copy(newCommitsCount = "12")),
                now,
            )
        }
        assertThrows(CnbWebhookValidationException::class.java) {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                payload().copy(ref = payload().ref.copy(newCommitsCount = "-1")),
                now,
            )
        }
        assertThrows(CnbWebhookValidationException::class.java) {
            val invalid = pullRequest(commentBody = "unsafe\u0000comment")
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                payload().copy(event = CnbWebhookEvent.PULL_REQUEST_COMMENT, pullRequest = invalid),
                now,
            )
        }
    }

    @Test
    fun `comment events require an API-addressable comment and actor`() {
        val delivery =
            payload().copy(
                event = CnbWebhookEvent.PULL_REQUEST_COMMENT,
                pullRequest = pullRequest(commentBody = "rebuild"),
            )

        assertThrows(CnbWebhookValidationException::class.java) {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                delivery.copy(pullRequest = requireNotNull(delivery.pullRequest).copy(commentId = "")),
                now,
            )
        }
        assertThrows(CnbWebhookValidationException::class.java) {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                delivery.copy(actor = delivery.actor.copy(username = "")),
                now,
            )
        }
    }

    @Test
    fun `accepts complete SHA-1 and SHA-256 object IDs but rejects abbreviations and required zero IDs`() {
        val sha256 = "d".repeat(64)
        assertDoesNotThrow {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                payload().copy(ref = payload().ref.copy(sha = sha256, before = "e".repeat(64), commit = sha256)),
                now,
            )
        }
        assertDoesNotThrow {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                payload().copy(
                    event = CnbWebhookEvent.PULL_REQUEST_TARGET,
                    ref = payload().ref.copy(sha = sha256, before = "e".repeat(64), commit = sha256),
                    pullRequest =
                        pullRequest(commentBody = "").copy(
                            sourceSha = "a".repeat(64),
                            targetSha = "b".repeat(64),
                            mergeSha = "c".repeat(64),
                        ),
                ),
                now,
            )
        }
        assertThrows(CnbWebhookValidationException::class.java) {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                payload().copy(ref = payload().ref.copy(sha = "a".repeat(12), commit = "a".repeat(12))),
                now,
            )
        }
        assertThrows(CnbWebhookValidationException::class.java) {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                payload().copy(ref = payload().ref.copy(sha = "0".repeat(64), commit = "0".repeat(64))),
                now,
            )
        }
    }

    @Test
    fun `uses canonical Git branch and tag ref validation`() {
        for (branch in listOf("@", "feature/多级/雪", "release/v1.2.3")) {
            assertDoesNotThrow {
                CnbWebhookValidator.validate(
                    "cnb-cool",
                    server,
                    payload().copy(ref = payload().ref.copy(name = branch)),
                    now,
                )
            }
        }
        assertDoesNotThrow {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                payload().copy(
                    event = CnbWebhookEvent.TAG_PUSH,
                    ref = payload().ref.copy(name = "版本/一", tag = true),
                ),
                now,
            )
        }
        for (invalid in listOf("feature/build.lock", "feature/with space", "feature/a..b")) {
            assertThrows(CnbWebhookValidationException::class.java) {
                CnbWebhookValidator.validate(
                    "cnb-cool",
                    server,
                    payload().copy(ref = payload().ref.copy(name = invalid)),
                    now,
                )
            }
        }
    }

    @Test
    fun `repository paths share the canonical Unicode-safe policy`() {
        val unicode = payload().withRepository("团队/项目-🚀")
        assertDoesNotThrow {
            CnbWebhookValidator.validate("cnb-cool", server, unicode, now)
        }

        for (
        invalid in
        listOf(
            "team/project/",
            "team//project",
            "team/./project",
            "team/../project",
            "team\\project/repository",
            "team/project name",
            "team/project\u00a0",
            "team/project\u0000",
        )
        ) {
            assertThrows(CnbWebhookValidationException::class.java) {
                CnbWebhookValidator.validate("cnb-cool", server, payload().withRepository(invalid), now)
            }
        }
    }

    @Test
    fun `pull request source repositories use the same canonical path policy`() {
        val base =
            payload().copy(
                event = CnbWebhookEvent.PULL_REQUEST_UPDATE,
                pullRequest = pullRequest("").copy(sourceRepository = "贡献者/项目"),
            )
        assertDoesNotThrow { CnbWebhookValidator.validate("cnb-cool", server, base, now) }

        assertThrows(CnbWebhookValidationException::class.java) {
            CnbWebhookValidator.validate(
                "cnb-cool",
                server,
                base.copy(pullRequest = base.pullRequest?.copy(sourceRepository = "contributor\\repo/project")),
                now,
            )
        }
    }

    private fun pullRequest(commentBody: String): CnbWebhookPullRequest =
        CnbWebhookPullRequest(
            id = "pull-7",
            number = "7",
            title = "Change",
            description = "",
            proposer = "alice",
            sourceRepository = "team/project",
            sourceBranch = "feature/change",
            sourceSha = "c".repeat(40),
            targetBranch = "main",
            targetSha = "b".repeat(40),
            mergeSha = "d".repeat(40),
            action = "submitted",
            wip = false,
            commentId = "comment-1",
            commentBody = commentBody,
            commentType = "note",
        )

    private fun payload() =
        CnbWebhookPayload(
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

    private fun CnbWebhookPayload.withRepository(path: String): CnbWebhookPayload =
        copy(repository = repository.copy(slug = path, url = "https://cnb.cool/$path"))
}
