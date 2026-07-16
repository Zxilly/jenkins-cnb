package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbCommit
import dev.zxilly.jenkins.cnb.api.model.CnbLabel
import dev.zxilly.jenkins.cnb.api.model.CnbMemberAccess
import dev.zxilly.jenkins.cnb.api.model.CnbMemberAccessLevel
import dev.zxilly.jenkins.cnb.api.model.CnbPullComment
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
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPullRequest
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRef
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRepository
import hudson.model.Queue
import hudson.plugins.git.RevisionParameterAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class CnbVerifiedWebhookSchedulingTest {
    @Test
    @WithJenkins
    fun `classic target push plans every verified open pull request with its source checkout`(jenkins: JenkinsRule) {
        val enabledJob = jenkins.createFreeStyleProject("target-push-prs")
        val disabledJob = jenkins.createFreeStyleProject("target-push-disabled")
        val enabled =
            CnbPushTrigger("primary", "team/project", "**").apply {
                setEventFilter("tag_push")
                setTriggerOpenPullRequestOnPush("both")
                setCiSkip(false)
            }
        val disabled =
            CnbPushTrigger("primary", "team/project", "**").apply {
                setEventFilter("tag_push")
                setTriggerOpenPullRequestOnPush("source")
                setCiSkip(false)
            }

        val planned =
            CnbVerifiedWebhookPlanner.classic(
                pushDelivery(),
                listOf(
                    CnbClassicTriggerCandidate(enabledJob, enabled),
                    CnbClassicTriggerCandidate(disabledJob, disabled),
                ),
                openClient = ::targetPushClient,
            )

        val candidate = planned.single()
        assertEquals(enabledJob.fullName, candidate.job.fullName)
        assertEquals(CnbWebhookEvent.PULL_REQUEST_TARGET, candidate.delivery.payload.event)
        assertEquals(SHA_A, candidate.identity.sha)
        assertEquals(SHA_C, candidate.identity.targetSha)
        assertEquals(false, candidate.onlyIfNewPullRequestCommits)
        val checkout = requireNotNull(candidate.checkoutAction)
        assertEquals(SHA_A, checkout.commit)
    }

    @Test
    @WithJenkins
    fun `classic jobs evaluate independent policies against one live snapshot`(jenkins: JenkinsRule) {
        val readyJob = jenkins.createFreeStyleProject("ready-policy")
        val securityJob = jenkins.createFreeStyleProject("security-policy")
        val ready = pullRequestTrigger(requiredLabels = "ready")
        val security = pullRequestTrigger(requiredLabels = "security-reviewed")
        val pullRequestCalls = AtomicInteger()
        val labelCalls = AtomicInteger()

        val planned =
            CnbVerifiedWebhookPlanner.classic(
                pullRequestDelivery(),
                listOf(
                    CnbClassicTriggerCandidate(readyJob, ready),
                    CnbClassicTriggerCandidate(securityJob, security),
                ),
                openClient = {
                    pullRequestClient(
                        pullRequestCalls,
                        labelCalls,
                        labels = listOf(CnbLabel("label-1", "ready")),
                    )
                },
            )

        assertEquals(listOf(readyJob.fullName), planned.map { it.job.fullName })
        assertEquals(1, pullRequestCalls.get())
        assertEquals(1, labelCalls.get())
    }

    @Test
    @WithJenkins
    fun `classic CI skip policy uses the authoritative source commit message`(jenkins: JenkinsRule) {
        val job = jenkins.createFreeStyleProject("skip-policy")
        val trigger = pullRequestTrigger()
        trigger.setTriggerOnlyIfNewCommitsPushed(true)

        for (message in listOf("change [ci-skip]", "change [CI SKIP]", "change [Skip CI]")) {
            val planned =
                CnbVerifiedWebhookPlanner.classic(
                    pullRequestDelivery(),
                    listOf(CnbClassicTriggerCandidate(job, trigger)),
                    openClient = {
                        pullRequestClient(AtomicInteger(), AtomicInteger(), commitMessage = message)
                    },
                )
            assertEquals(emptyList<CnbVerifiedQueueCandidate>(), planned, message)
        }
        val skippedByDescription =
            CnbVerifiedWebhookPlanner.classic(
                pullRequestDelivery(),
                listOf(CnbClassicTriggerCandidate(job, trigger)),
                openClient = {
                    pullRequestClient(
                        AtomicInteger(),
                        AtomicInteger(),
                        pullRequestBody = "Please do not build [skip ci]",
                    )
                },
            )
        assertEquals(emptyList<CnbVerifiedQueueCandidate>(), skippedByDescription)

        val planned =
            CnbVerifiedWebhookPlanner.classic(
                pullRequestDelivery(),
                listOf(CnbClassicTriggerCandidate(job, trigger)),
                openClient = {
                    pullRequestClient(AtomicInteger(), AtomicInteger(), commitMessage = "build this")
                },
            )
        assertEquals(listOf(job.fullName), planned.map { it.job.fullName })
        assertEquals(true, planned.single().onlyIfNewPullRequestCommits)
    }

    @Test
    @WithJenkins
    fun `unavailable optional policy data fails only the dependent classic job closed`(jenkins: JenkinsRule) {
        val labelledJob = jenkins.createFreeStyleProject("labelled")
        val unlabelledJob = jenkins.createFreeStyleProject("unlabelled")
        val labelled = pullRequestTrigger(requiredLabels = "ready")
        val unlabelled = pullRequestTrigger()

        val denied =
            CnbVerifiedWebhookPlanner.classic(
                pullRequestDelivery(),
                listOf(
                    CnbClassicTriggerCandidate(labelledJob, labelled),
                    CnbClassicTriggerCandidate(unlabelledJob, unlabelled),
                ),
                openClient = {
                    pullRequestClient(
                        AtomicInteger(),
                        AtomicInteger(),
                        labelFailure = CnbApiException("denied", statusCode = 403),
                    )
                },
            )

        assertEquals(listOf(unlabelledJob.fullName), denied.map { candidate -> candidate.job.fullName })
        assertThrows(CnbApiException::class.java) {
            CnbVerifiedWebhookPlanner.classic(
                pullRequestDelivery(),
                listOf(CnbClassicTriggerCandidate(labelledJob, labelled)),
                openClient = {
                    pullRequestClient(
                        AtomicInteger(),
                        AtomicInteger(),
                        labelFailure = CnbApiException("unavailable", statusCode = 503, retryable = true),
                    )
                },
            )
        }
    }

    @Test
    @WithJenkins
    fun `classic comment policy uses the live comment author body and target role`(jenkins: JenkinsRule) {
        val job = jenkins.createFreeStyleProject("classic-comment")
        val trigger =
            CnbPushTrigger("primary", "team/project", "**").apply {
                setEventFilter("pull_request.comment")
                setCommentPattern("rebuild")
            }

        val accepted =
            CnbVerifiedWebhookPlanner.classic(
                commentDelivery(),
                listOf(CnbClassicTriggerCandidate(job, trigger)),
                openClient = { commentClient(author = "alice", role = CnbMemberAccessLevel.DEVELOPER) },
            )
        val spoofed =
            CnbVerifiedWebhookPlanner.classic(
                commentDelivery(),
                listOf(CnbClassicTriggerCandidate(job, trigger)),
                openClient = { commentClient(author = "mallory", role = CnbMemberAccessLevel.OWNER) },
            )
        val unknownRole =
            CnbVerifiedWebhookPlanner.classic(
                commentDelivery(),
                listOf(CnbClassicTriggerCandidate(job, trigger)),
                openClient = { commentClient(author = "alice", role = null) },
            )

        assertEquals(listOf(job.fullName), accepted.map { it.job.fullName })
        assertEquals(emptyList<CnbVerifiedQueueCandidate>(), spoofed)
        assertEquals(emptyList<CnbVerifiedQueueCandidate>(), unknownRole)
    }

    @Test
    fun `staging failure rolls back only newly-created effects in reverse order`() {
        val effects = mutableListOf<String>()
        val rollbacks = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            stageAtomically(
                listOf("existing", "first", "second", "failure"),
                stage = { input ->
                    if (input == "failure") error("boom")
                    effects += input
                    CnbStagedEffect(input, created = input != "existing")
                },
                rollback = { effect ->
                    rollbacks += effect
                    effects.remove(effect)
                },
            )
        }

        assertEquals(listOf("existing"), effects)
        assertEquals(listOf("second", "first"), rollbacks)
    }

    @Test
    @WithJenkins
    fun `atomic scheduler protects each job revision from duplicate queue entries`(jenkins: JenkinsRule) {
        jenkins.jenkins.numExecutors = 0
        val first = jenkins.createFreeStyleProject("first")
        val second = jenkins.createFreeStyleProject("second")
        val delivery = pushDelivery()
        val identity = requireNotNull(CnbQueueIdentity.from(delivery))
        val candidates =
            listOf(
                CnbVerifiedQueueCandidate(first, delivery, identity),
                CnbVerifiedQueueCandidate(second, delivery, identity),
            )

        assertEquals(2, CnbAtomicQueueScheduler.schedule(candidates))
        assertEquals(0, CnbAtomicQueueScheduler.schedule(candidates))
        assertEquals(2, Queue.getInstance().items.size)
        assertEquals(
            listOf(first.fullName, second.fullName),
            Queue
                .getInstance()
                .items
                .map { it.task.fullDisplayName }
                .sorted(),
        )
        Queue.getInstance().items.forEach(Queue.getInstance()::cancel)
    }

    @Test
    @WithJenkins
    fun `atomic scheduler cancels only an older queued revision after commit`(jenkins: JenkinsRule) {
        jenkins.jenkins.numExecutors = 0
        val job = jenkins.createFreeStyleProject("superseded")
        val oldDelivery = pushDelivery(SHA_A)
        val newDelivery = pushDelivery(SHA_B)

        assertEquals(
            1,
            CnbAtomicQueueScheduler.schedule(
                listOf(CnbVerifiedQueueCandidate(job, oldDelivery, requireNotNull(CnbQueueIdentity.from(oldDelivery)))),
            ),
        )
        assertEquals(
            1,
            CnbAtomicQueueScheduler.schedule(
                listOf(
                    CnbVerifiedQueueCandidate(
                        job,
                        newDelivery,
                        requireNotNull(CnbQueueIdentity.from(newDelivery)),
                        cancelPending = true,
                    ),
                ),
            ),
        )

        val queued = Queue.getInstance().items.single()
        assertEquals(SHA_B, requireNotNull(queued.getAction(CnbQueueAction::class.java)).sha)
        Queue.getInstance().cancel(queued)
    }

    @Test
    @WithJenkins
    fun `new commit policy compares the source SHA with the last durable pull request delivery`(jenkins: JenkinsRule) {
        var job = jenkins.createFreeStyleProject("new-pr-revision")
        job.quietPeriod = 0

        fun candidate(
            sha: String,
            onlyNew: Boolean = true,
        ): CnbVerifiedQueueCandidate {
            val delivery = pullRequestDelivery(sha)
            return CnbVerifiedQueueCandidate(
                job = job,
                delivery = delivery,
                identity = requireNotNull(CnbQueueIdentity.from(delivery)),
                onlyIfNewPullRequestCommits = onlyNew,
            )
        }

        assertEquals(1, CnbAtomicQueueScheduler.schedule(listOf(candidate(SHA_A))))
        jenkins.waitUntilNoActivity()
        jenkins.jenkins.reload()
        job = requireNotNull(jenkins.jenkins.getItemByFullName("new-pr-revision", hudson.model.FreeStyleProject::class.java))

        assertEquals(0, CnbAtomicQueueScheduler.schedule(listOf(candidate(SHA_A))))
        assertEquals(1, CnbAtomicQueueScheduler.schedule(listOf(candidate(SHA_A, onlyNew = false))))
        jenkins.waitUntilNoActivity()
        assertEquals(1, CnbAtomicQueueScheduler.schedule(listOf(candidate(SHA_B))))
        jenkins.waitUntilNoActivity()
        assertEquals(1, CnbAtomicQueueScheduler.schedule(listOf(candidate(SHA_A))))
        jenkins.waitUntilNoActivity()
        assertEquals(4, job.builds.count())
    }

    private fun pullRequestTrigger(requiredLabels: String = ""): CnbPushTrigger =
        CnbPushTrigger("primary", "team/project", "**").apply {
            setEventFilter("pull_request.update")
            setRequiredPullRequestLabels(requiredLabels)
        }

    private fun pullRequestClient(
        pullRequestCalls: AtomicInteger,
        labelCalls: AtomicInteger,
        labels: List<CnbLabel> = emptyList(),
        labelFailure: CnbApiException? = null,
        commitMessage: String = "build this change",
        pullRequestBody: String = "",
    ): CnbClient {
        val delegate =
            Proxy.newProxyInstance(
                CnbClient::class.java.classLoader,
                arrayOf(CnbClient::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getCapabilities" -> {
                        CnbApiCapabilities()
                    }

                    "getPullRequest" -> {
                        livePullRequest().copy(body = pullRequestBody).also { pullRequestCalls.incrementAndGet() }
                    }

                    "getCommit" -> {
                        CnbCommit(SHA_A, commitMessage)
                    }

                    "listPullLabels" -> {
                        labelCalls.incrementAndGet()
                        labels
                    }

                    "close" -> {
                        Unit
                    }

                    "toString" -> {
                        "CnbVerifiedWebhookSchedulingTestClient"
                    }

                    else -> {
                        throw UnsupportedOperationException(method.name)
                    }
                }
            } as CnbClient
        return if (labelFailure == null) {
            delegate
        } else {
            object : CnbClient by delegate {
                override fun listPullLabels(
                    repo: String,
                    number: String,
                ): List<CnbLabel> {
                    labelCalls.incrementAndGet()
                    throw labelFailure
                }
            }
        }
    }

    private fun targetPushClient(): CnbClient =
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
                    when ("${args[0]}:${args[1]}") {
                        "team/project:main" -> CnbBranch("main", SHA_C)
                        "alice/project:feature/change" -> CnbBranch("feature/change", SHA_A)
                        else -> throw UnsupportedOperationException("unexpected branch ${args.toList()}")
                    }
                }

                "listPullRequests" -> {
                    listOf(livePullRequest().copy(sourceRepo = "alice/project"))
                }

                "getPullRequest" -> {
                    livePullRequest().copy(sourceRepo = "alice/project")
                }

                "getRepository" -> {
                    CnbRepository(
                        path = "alice/project",
                        name = "project",
                        webUrl = "https://cnb.cool/alice/project",
                        cloneUrl = "https://cnb.cool/alice/project.git",
                        defaultBranch = "main",
                        status = CnbRepositoryStatus.OK,
                        visibility = CnbRepositoryVisibility.PUBLIC,
                    )
                }

                "close" -> {
                    Unit
                }

                "toString" -> {
                    "CnbVerifiedWebhookSchedulingTargetPushClient"
                }

                else -> {
                    throw UnsupportedOperationException(method.name)
                }
            }
        } as CnbClient

    private fun commentClient(
        author: String,
        role: CnbMemberAccessLevel?,
    ): CnbClient =
        Proxy.newProxyInstance(
            CnbClient::class.java.classLoader,
            arrayOf(CnbClient::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCapabilities" -> CnbApiCapabilities()
                "getPullRequest" -> livePullRequest()
                "getCommit" -> CnbCommit(SHA_A, "build this change")
                "getPullComment" -> CnbPullComment("comment-9", "rebuild", author)
                "listMemberAccessLevels" -> role?.let { listOf(CnbMemberAccess("team/project", it)) }.orEmpty()
                "close" -> Unit
                "toString" -> "CnbVerifiedWebhookSchedulingTestCommentClient"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as CnbClient

    private fun livePullRequest(): CnbPullRequest =
        CnbPullRequest(
            number = "7",
            title = "Change",
            state = CnbPullRequestState.OPEN,
            sourceRepo = "team/project",
            sourceBranch = "feature/change",
            sourceSha = SHA_A,
            targetRepo = "team/project",
            targetBranch = "main",
            targetSha = SHA_C,
            author = "alice",
        )

    private fun pullRequestDelivery(sourceSha: String = SHA_A): CnbWebhookDelivery =
        CnbWebhookDelivery(
            "primary",
            basePayload(CnbWebhookEvent.PULL_REQUEST_UPDATE).copy(
                pullRequest =
                    CnbWebhookPullRequest(
                        id = "pr-7",
                        number = "7",
                        title = "Change",
                        description = "",
                        proposer = "alice",
                        sourceRepository = "team/project",
                        sourceBranch = "feature/change",
                        sourceSha = sourceSha,
                        targetBranch = "main",
                        targetSha = SHA_C,
                        mergeSha = null,
                        action = "synchronize",
                        wip = false,
                    ),
            ),
            "test",
        )

    private fun commentDelivery(): CnbWebhookDelivery =
        pullRequestDelivery().let { delivery ->
            delivery.copy(
                payload =
                    delivery.payload.copy(
                        event = CnbWebhookEvent.PULL_REQUEST_COMMENT,
                        pullRequest =
                            requireNotNull(delivery.payload.pullRequest).copy(
                                action = "comment",
                                commentId = "comment-9",
                                commentBody = "rebuild",
                                commentType = "note",
                            ),
                    ),
            )
        }

    private fun pushDelivery(sha: String = SHA_C): CnbWebhookDelivery =
        CnbWebhookDelivery(
            "primary",
            basePayload(CnbWebhookEvent.PUSH).let { payload ->
                payload.copy(ref = payload.ref.copy(sha = sha, commit = sha))
            },
            "test",
        )

    private fun basePayload(event: CnbWebhookEvent): CnbWebhookPayload =
        CnbWebhookPayload(
            schema = CnbWebhookPayload.SCHEMA_V1,
            installationId = "primary",
            deliveryId = "delivery-1",
            buildId = "build-1",
            occurredAt = Instant.EPOCH,
            event = event,
            eventUrl = "",
            retry = false,
            instance = CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
            repository = CnbWebhookRepository("repo-1", "team/project", "https://cnb.cool/team/project"),
            actor = CnbWebhookActor("user-1", "alice", "Alice", ""),
            ref = CnbWebhookRef("main", SHA_C, SHA_B, SHA_C, false),
            pullRequest = null,
        )

    private companion object {
        val SHA_A = "a".repeat(40)
        val SHA_B = "b".repeat(40)
        val SHA_C = "c".repeat(40)
    }
}
