package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbCommit
import dev.zxilly.jenkins.cnb.api.model.CnbLabel
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestState
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryStatus
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryVisibility
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookActor
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookInstance
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPayload
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRef
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Instant

class CnbOpenPullRequestTargetPushResolverTest {
    @Test
    fun `resolves origin and fork pull requests from an authoritative target push snapshot`() {
        val origin = pullRequest("7", "team/project", "feature/origin", SHA_A)
        val fork = pullRequest("8", "alice/project", "feature/fork", SHA_B)
        val otherTarget = pullRequest("9", "team/project", "feature/release", SHA_D, targetBranch = "release")
        val calls = mutableListOf<String>()
        val client =
            client(
                calls = calls,
                listed = listOf(origin, fork, otherTarget),
                details = mapOf("7" to origin, "8" to fork),
                branches =
                    mapOf(
                        "team/project:main" to CnbBranch("main", SHA_C),
                        "team/project:feature/origin" to CnbBranch("feature/origin", SHA_A),
                        "alice/project:feature/fork" to CnbBranch("feature/fork", SHA_B),
                    ),
                labels = mapOf("7" to listOf(CnbLabel("ready", "ready"))),
                commits =
                    mapOf(
                        "team/project:$SHA_A" to CnbCommit(SHA_A, "origin change"),
                        "alice/project:$SHA_B" to CnbCommit(SHA_B, "fork change"),
                    ),
            )

        val resolved =
            CnbOpenPullRequestTargetPushResolver.resolve(
                targetPushDelivery(),
                CnbLiveDeliveryRequirements(labels = true, commitMessage = true),
                client,
            )

        assertEquals(
            listOf("7", "8"),
            resolved.map {
                it.delivery.payload.pullRequest
                    ?.number
            },
        )
        assertEquals(
            listOf(SHA_A, SHA_B),
            resolved.map {
                it.delivery.payload.pullRequest
                    ?.sourceSha
            },
        )
        assertEquals(
            listOf(SHA_C, SHA_C),
            resolved.map {
                it.delivery.payload.pullRequest
                    ?.targetSha
            },
        )
        assertEquals(listOf("https://cnb.cool/team/project.git", "https://cnb.cool/alice/project.git"), resolved.map { it.sourceCloneUrl })
        assertEquals(setOf("ready"), resolved.first().snapshot.labels)
        assertEquals(emptySet<String>(), resolved.last().snapshot.labels)
        assertEquals(listOf("origin change", "fork change"), resolved.map { it.snapshot.commitMessage })
        assertEquals(
            listOf(CnbWebhookEvent.PULL_REQUEST_TARGET, CnbWebhookEvent.PULL_REQUEST_TARGET),
            resolved.map { it.delivery.payload.event },
        )
        assertEquals(1, calls.count { it == "listPullRequests:team/project" })
        assertEquals(0, calls.count { it == "getPullRequest:9" })
    }

    @Test
    fun `skips pull requests that close or retarget during verification`() {
        val listedClosed = pullRequest("7", "team/project", "feature/closed", SHA_A)
        val listedRetargeted = pullRequest("8", "team/project", "feature/retargeted", SHA_B)
        val client =
            client(
                listed = listOf(listedClosed, listedRetargeted),
                details =
                    mapOf(
                        "7" to listedClosed.copy(state = CnbPullRequestState.CLOSED),
                        "8" to listedRetargeted.copy(targetBranch = "release"),
                    ),
                branches = mapOf("team/project:main" to CnbBranch("main", SHA_C)),
            )

        val resolved =
            CnbOpenPullRequestTargetPushResolver.resolve(
                targetPushDelivery(),
                CnbLiveDeliveryRequirements(),
                client,
            )

        assertEquals(emptyList<CnbVerifiedOpenPullRequestPush>(), resolved)
    }

    @Test
    fun `rejects non branch pushes and stale target revisions before listing pull requests`() {
        val calls = mutableListOf<String>()
        val stale = client(calls = calls, branches = mapOf("team/project:main" to CnbBranch("main", SHA_D)))
        val tag =
            targetPushDelivery().let { delivery ->
                delivery.copy(payload = delivery.payload.copy(ref = delivery.payload.ref.copy(tag = true)))
            }

        assertThrows(CnbApiException::class.java) {
            CnbOpenPullRequestTargetPushResolver.resolve(
                targetPushDelivery(),
                CnbLiveDeliveryRequirements(),
                stale,
            )
        }
        assertEquals(
            emptyList<CnbVerifiedOpenPullRequestPush>(),
            CnbOpenPullRequestTargetPushResolver.resolve(tag, CnbLiveDeliveryRequirements(), stale),
        )
        assertEquals(0, calls.count { it.startsWith("listPullRequests:") })
    }

    @Test
    fun `skips missing pull request details but propagates retryable API failures`() {
        val first = pullRequest("7", "team/project", "feature/missing", SHA_A)
        val second = pullRequest("8", "team/project", "feature/unavailable", SHA_B)
        val missing = CnbApiException("missing", statusCode = 404)
        val unavailable = CnbApiException("unavailable", statusCode = 503, retryable = true)
        val delegate =
            client(
                listed = listOf(first, second),
                branches = mapOf("team/project:main" to CnbBranch("main", SHA_C)),
            )
        val client =
            object : CnbClient by delegate {
                override fun getPullRequest(
                    repo: String,
                    number: String,
                ): CnbPullRequest = if (number == "7") throw missing else throw unavailable
            }

        val thrown =
            assertThrows(CnbApiException::class.java) {
                CnbOpenPullRequestTargetPushResolver.resolve(
                    targetPushDelivery(),
                    CnbLiveDeliveryRequirements(),
                    client,
                )
            }

        assertEquals(503, thrown.statusCode)
        assertEquals(true, thrown.retryable)
    }

    @Test
    fun `rejects a source clone URL outside the signed CNB web origin`() {
        val pullRequest = pullRequest("7", "alice/project", "feature/fork", SHA_A)
        val delegate =
            client(
                listed = listOf(pullRequest),
                details = mapOf("7" to pullRequest),
                branches =
                    mapOf(
                        "team/project:main" to CnbBranch("main", SHA_C),
                        "alice/project:feature/fork" to CnbBranch("feature/fork", SHA_A),
                    ),
            )
        val client =
            object : CnbClient by delegate {
                override fun getRepository(path: String): CnbRepository =
                    repository(path).copy(cloneUrl = "https://attacker.invalid/alice/project.git")
            }

        val failure =
            assertThrows(CnbApiException::class.java) {
                CnbOpenPullRequestTargetPushResolver.resolve(
                    targetPushDelivery(),
                    CnbLiveDeliveryRequirements(),
                    client,
                )
            }

        assertEquals(false, failure.retryable)
    }

    private fun client(
        calls: MutableList<String> = mutableListOf(),
        listed: List<CnbPullRequest> = emptyList(),
        details: Map<String, Any> = emptyMap(),
        branches: Map<String, CnbBranch> = emptyMap(),
        labels: Map<String, List<CnbLabel>> = emptyMap(),
        commits: Map<String, CnbCommit> = emptyMap(),
    ): CnbClient =
        Proxy.newProxyInstance(
            CnbClient::class.java.classLoader,
            arrayOf(CnbClient::class.java),
        ) { _, method, arguments ->
            val args = arguments.orEmpty()
            when (method.name) {
                "getCapabilities" -> {
                    CnbApiCapabilities()
                }

                "getBranch" -> {
                    val key = "${args[0]}:${args[1]}"
                    calls += "getBranch:$key"
                    branches[key] ?: throw CnbApiException("branch missing", statusCode = 404)
                }

                "listPullRequests" -> {
                    calls += "listPullRequests:${args[0]}"
                    listed
                }

                "getPullRequest" -> {
                    val number = args[1].toString()
                    calls += "getPullRequest:$number"
                    when (val value = details[number]) {
                        is CnbPullRequest -> value
                        is Throwable -> throw value
                        else -> throw CnbApiException("pull request missing", statusCode = 404)
                    }
                }

                "listPullLabels" -> {
                    val number = args[1].toString()
                    calls += "listPullLabels:$number"
                    labels[number].orEmpty()
                }

                "getCommit" -> {
                    val key = "${args[0]}:${args[1]}"
                    calls += "getCommit:$key"
                    commits[key] ?: throw CnbApiException("commit missing", statusCode = 404)
                }

                "getRepository" -> {
                    val path = args[0].toString()
                    calls += "getRepository:$path"
                    repository(path)
                }

                "close" -> {
                    Unit
                }

                "toString" -> {
                    "CnbOpenPullRequestTargetPushResolverTestClient"
                }

                else -> {
                    throw UnsupportedOperationException(method.name)
                }
            }
        } as CnbClient

    private fun pullRequest(
        number: String,
        sourceRepo: String,
        sourceBranch: String,
        sourceSha: String,
        targetBranch: String = "main",
    ): CnbPullRequest =
        CnbPullRequest(
            number = number,
            title = "Change $number",
            state = CnbPullRequestState.OPEN,
            sourceRepo = sourceRepo,
            sourceBranch = sourceBranch,
            sourceSha = sourceSha,
            targetRepo = "team/project",
            targetBranch = targetBranch,
            targetSha = SHA_C,
            author = "alice",
            body = "Description $number",
        )

    private fun repository(path: String): CnbRepository =
        CnbRepository(
            path = path,
            name = path.substringAfterLast('/'),
            webUrl = "https://cnb.cool/$path",
            cloneUrl = "https://cnb.cool/$path.git",
            defaultBranch = "main",
            status = CnbRepositoryStatus.OK,
            visibility = CnbRepositoryVisibility.PUBLIC,
        )

    private fun targetPushDelivery(): CnbWebhookDelivery =
        CnbWebhookDelivery(
            "primary",
            CnbWebhookPayload(
                schema = CnbWebhookPayload.SCHEMA_V1,
                installationId = "primary",
                deliveryId = "delivery-target-push",
                buildId = "build-target-push",
                occurredAt = Instant.EPOCH,
                event = CnbWebhookEvent.PUSH,
                eventUrl = "",
                retry = false,
                instance = CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
                repository = CnbWebhookRepository("repo-1", "team/project", "https://cnb.cool/team/project"),
                actor = CnbWebhookActor("user-1", "alice", "Alice", ""),
                ref = CnbWebhookRef("main", SHA_C, SHA_D, SHA_C, false),
                pullRequest = null,
            ),
            "test",
        )

    private companion object {
        val SHA_A = "a".repeat(40)
        val SHA_B = "b".repeat(40)
        val SHA_C = "c".repeat(40)
        val SHA_D = "d".repeat(40)
    }
}
