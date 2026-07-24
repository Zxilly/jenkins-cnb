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
import hudson.plugins.git.GitSCM
import hudson.plugins.git.RevisionParameterAction
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CnbVerifiedWebhookSchedulingTest {
    @Test
    @WithJenkins
    fun `classic direct push is pinned to the verified commit for a matching GitSCM`(jenkins: JenkinsRule) {
        val job = jenkins.createFreeStyleProject("direct-push-pin")
        job.scm = GitSCM("https://cnb.cool/team/project.git")
        val mismatched = jenkins.createFreeStyleProject("direct-push-wrong-remote")
        mismatched.scm = GitSCM("https://cnb.cool/team/other.git")
        val trigger = CnbPushTrigger("primary", "team/project", "main").apply { setCiSkip(false) }

        val candidates =
            CnbVerifiedWebhookPlanner
                .classic(
                    pushDelivery(),
                    listOf(
                        CnbClassicTriggerCandidate(job, trigger),
                        CnbClassicTriggerCandidate(mismatched, trigger),
                    ),
                    openClient = ::targetPushClient,
                )

        val candidate = candidates.single()
        assertEquals(job, candidate.job)
        val checkout = requireNotNull(candidate.checkoutAction)
        assertEquals(SHA_C, checkout.commit)
        assertTrue(checkout.canOriginateFrom((job.scm as GitSCM).repositories))
    }

    @Test
    @WithJenkins
    fun `classic direct fork pull request is pinned through its verified source repository`(jenkins: JenkinsRule) {
        val job = jenkins.createFreeStyleProject("direct-fork-pin")
        job.scm = GitSCM("https://cnb.cool/alice/project.git")
        val trigger = pullRequestTrigger().apply { setCiSkip(false) }
        val delivery =
            pullRequestDelivery().let { value ->
                value.copy(
                    payload =
                        value.payload.copy(
                            pullRequest = requireNotNull(value.payload.pullRequest).copy(sourceRepository = "alice/project"),
                        ),
                )
            }

        val candidate =
            CnbVerifiedWebhookPlanner
                .classic(
                    delivery,
                    listOf(CnbClassicTriggerCandidate(job, trigger)),
                    openClient = ::targetPushClient,
                ).single()

        val checkout = requireNotNull(candidate.checkoutAction)
        assertEquals(SHA_A, checkout.commit)
        assertTrue(checkout.canOriginateFrom((job.scm as GitSCM).repositories))
    }

    @Test
    @WithJenkins
    fun `classic target push plans every verified open pull request with its source checkout`(jenkins: JenkinsRule) {
        val enabledJob = jenkins.createFreeStyleProject("target-push-prs")
        enabledJob.scm = GitSCM("https://cnb.cool/alice/project")
        val mismatchedJob = jenkins.createFreeStyleProject("target-push-wrong-remote")
        mismatchedJob.scm = GitSCM("https://cnb.cool/team/project.git")
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
        val mismatched =
            CnbPushTrigger("primary", "team/project", "**").apply {
                setEventFilter("tag_push")
                setTriggerOpenPullRequestOnPush("both")
                setCiSkip(false)
            }

        val planned =
            CnbVerifiedWebhookPlanner.classic(
                pushDelivery(),
                listOf(
                    CnbClassicTriggerCandidate(enabledJob, enabled),
                    CnbClassicTriggerCandidate(disabledJob, disabled),
                    CnbClassicTriggerCandidate(mismatchedJob, mismatched),
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
        assertTrue(checkout.canOriginateFrom((enabledJob.scm as GitSCM).repositories))
    }

    @Test
    @WithJenkins
    fun `classic revision action checks out the requested commit through the configured remote`(jenkins: JenkinsRule) {
        val repository = Files.createTempDirectory(jenkins.jenkins.rootDir.toPath(), "cnb-source-")
        val firstCommit =
            Git.init().setDirectory(repository.toFile()).call().use { git ->
                Files.writeString(repository.resolve("revision.txt"), "first")
                git.add().addFilepattern("revision.txt").call()
                val first =
                    git
                        .commit()
                        .setMessage("first")
                        .setAuthor("CNB Test", "cnb@example.invalid")
                        .setCommitter("CNB Test", "cnb@example.invalid")
                        .call()
                        .id
                        .name
                Files.writeString(repository.resolve("revision.txt"), "second")
                git.add().addFilepattern("revision.txt").call()
                git
                    .commit()
                    .setMessage("second")
                    .setAuthor("CNB Test", "cnb@example.invalid")
                    .setCommitter("CNB Test", "cnb@example.invalid")
                    .call()
                first
            }
        val repositoryUrl = repository.toUri().toString()
        val job = jenkins.createFreeStyleProject("exact-cnb-revision")
        job.scm = GitSCM(repositoryUrl)
        val action = requireNotNull(CnbClassicGitRevisionAction.create(job, firstCommit, repositoryUrl))
        val previous = GitSCM.ALLOW_LOCAL_CHECKOUT

        try {
            GitSCM.ALLOW_LOCAL_CHECKOUT = true
            val build = jenkins.assertBuildStatusSuccess(job.scheduleBuild2(0, action))
            assertEquals("first", requireNotNull(build.workspace).child("revision.txt").readToString())
        } finally {
            GitSCM.ALLOW_LOCAL_CHECKOUT = previous
        }
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
    fun `nonmatching Git candidate does not add a clone lookup dependency`(jenkins: JenkinsRule) {
        val matching = jenkins.createFreeStyleProject("matching-non-git")
        val nonmatchingGit = jenkins.createFreeStyleProject("nonmatching-git")
        nonmatchingGit.scm = GitSCM("https://cnb.cool/team/project.git")
        val repositoryCalls = AtomicInteger()
        val delegate =
            pullRequestClient(
                AtomicInteger(),
                AtomicInteger(),
                labels = listOf(CnbLabel("label-ready", "ready")),
            )
        val client =
            object : CnbClient by delegate {
                override fun getRepository(path: String): CnbRepository {
                    repositoryCalls.incrementAndGet()
                    throw CnbApiException("clone lookup must not run", statusCode = 503, retryable = true)
                }
            }

        val planned =
            CnbVerifiedWebhookPlanner.classic(
                pullRequestDelivery(),
                listOf(
                    CnbClassicTriggerCandidate(matching, pullRequestTrigger(requiredLabels = "ready")),
                    CnbClassicTriggerCandidate(nonmatchingGit, pullRequestTrigger(requiredLabels = "security")),
                ),
                openClient = { client },
            )

        assertEquals(listOf(matching.fullName), planned.map { it.job.fullName })
        assertEquals(0, repositoryCalls.get())
        assertEquals(null, planned.single().checkoutAction)
    }

    @Test
    @WithJenkins
    fun `non Git target push candidate does not depend on clone metadata`(jenkins: JenkinsRule) {
        val job = jenkins.createFreeStyleProject("target-push-non-git")
        val trigger =
            CnbPushTrigger("primary", "team/project", "**").apply {
                setEventFilter("tag_push")
                setTriggerOpenPullRequestOnPush("both")
                setCiSkip(false)
            }
        val repositoryCalls = AtomicInteger()
        val delegate = targetPushClient()
        val client =
            object : CnbClient by delegate {
                override fun getRepository(path: String): CnbRepository {
                    repositoryCalls.incrementAndGet()
                    throw CnbApiException("clone lookup must not run", statusCode = 503, retryable = true)
                }
            }

        val candidate =
            CnbVerifiedWebhookPlanner
                .classic(
                    pushDelivery(),
                    listOf(CnbClassicTriggerCandidate(job, trigger)),
                    openClient = { client },
                ).single()

        assertEquals(job, candidate.job)
        assertEquals(CnbWebhookEvent.PULL_REQUEST_TARGET, candidate.delivery.payload.event)
        assertEquals(null, candidate.checkoutAction)
        assertEquals(0, repositoryCalls.get())
    }

    @Test
    @WithJenkins
    fun `one target push queues every pull request receipt once`(jenkins: JenkinsRule) {
        jenkins.jenkins.numExecutors = 0
        val job = jenkins.createFreeStyleProject("target-push-fanout")
        val trigger =
            CnbPushTrigger("primary", "team/project", "**").apply {
                setEventFilter("tag_push")
                setTriggerOpenPullRequestOnPush("both")
                setCiSkip(false)
            }
        val pullRequests =
            listOf(
                livePullRequest().copy(
                    number = "7",
                    sourceRepo = "team/project",
                    sourceBranch = "feature/first",
                    sourceSha = SHA_A,
                ),
                livePullRequest().copy(
                    number = "8",
                    sourceRepo = "team/project",
                    sourceBranch = "feature/second",
                    sourceSha = SHA_B,
                ),
            )
        val candidates =
            CnbVerifiedWebhookPlanner.classic(
                pushDelivery(deliveryId = "target-fanout-delivery"),
                listOf(CnbClassicTriggerCandidate(job, trigger)),
                openClient = { targetPushClient(pullRequests) },
            )

        assertEquals(2, candidates.size)
        assertEquals(2, candidates.map { it.deliveryScope }.distinct().size)
        assertTrue(candidates.all { it.checkoutAction == null })
        assertEquals(2, CnbAtomicQueueScheduler.schedule(candidates))
        assertEquals(0, CnbAtomicQueueScheduler.schedule(candidates))
        assertEquals(2, Queue.getInstance().items.count { it.task == job })
        Queue.getInstance().items.forEach(Queue.getInstance()::cancel)
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
    fun `run history is loaded without holding the Jenkins queue lock`(jenkins: JenkinsRule) {
        jenkins.jenkins.numExecutors = 0
        val job = jenkins.createFreeStyleProject("history-outside-queue-lock")
        val delivery = pushDelivery()
        val candidate = CnbVerifiedQueueCandidate(job, delivery, requireNotNull(CnbQueueIdentity.from(delivery)))
        val executor = Executors.newSingleThreadExecutor()

        try {
            assertEquals(
                1,
                CnbAtomicQueueScheduler.schedule(listOf(candidate)) {
                    executor
                        .submit { Queue.withLock(Runnable {}) }
                        .get(5, TimeUnit.SECONDS)
                    CnbDeliveryHistory.RunSummary.EMPTY
                },
            )
        } finally {
            executor.shutdownNow()
            Queue.getInstance().items.forEach(Queue.getInstance()::cancel)
        }
    }

    @Test
    @WithJenkins
    fun `queue to run transition is retried and its receipt closes the run visibility gap`(jenkins: JenkinsRule) {
        jenkins.jenkins.numExecutors = 0
        val job = jenkins.createFreeStyleProject("queue-run-transition")
        job.quietPeriod = 0
        val original = pushDelivery(SHA_A, "transition-delivery")
        val originalIdentity = requireNotNull(CnbQueueIdentity.from(original))
        requireNotNull(
            job.scheduleBuild2(
                0,
                CnbQueueAction(originalIdentity, original.payload.deliveryId, CnbDeliveryScope.DIRECT),
            ),
        )
        val queuedId = Queue.getInstance().items.single().id
        val scans = AtomicInteger()

        val replay = pushDelivery(SHA_B, original.payload.deliveryId)
        val replayCandidate =
            CnbVerifiedQueueCandidate(job, replay, requireNotNull(CnbQueueIdentity.from(replay)))
        val scheduled =
            CnbAtomicQueueScheduler.schedule(listOf(replayCandidate)) {
                if (scans.incrementAndGet() == 1) {
                    jenkins.jenkins.numExecutors = 1
                    assertEquals(false, waitForLeftItem(queuedId).isCancelled)
                }
                // Model the narrow interval where onLeft completed but Job.builds has not
                // exposed the corresponding Run to this history scan yet.
                CnbDeliveryHistory.RunSummary.EMPTY
            }

        assertEquals(0, scheduled)
        assertEquals(2, scans.get(), "The queue-to-run watermark change must force a fresh history scan")
        jenkins.waitUntilNoActivity()
        assertEquals(1, job.builds.count())
    }

    @Test
    @WithJenkins
    fun `continuous queue transitions fail after a bounded number of history scans`(jenkins: JenkinsRule) {
        jenkins.jenkins.numExecutors = 0
        val job = jenkins.createFreeStyleProject("unstable-queue-history")
        val delivery = pushDelivery()
        val candidate = CnbVerifiedQueueCandidate(job, delivery, requireNotNull(CnbQueueIdentity.from(delivery)))
        val scans = AtomicInteger()

        val failure =
            assertThrows(CnbDeliveryHistoryUnstableException::class.java) {
                CnbAtomicQueueScheduler.schedule(listOf(candidate)) {
                    scans.incrementAndGet()
                    CnbQueueTransitionWatermark.advance(job)
                    CnbDeliveryHistory.RunSummary.EMPTY
                }
            }

        assertEquals(8, failure.attempts)
        assertEquals(8, scans.get())
        assertEquals(0, Queue.getInstance().items.count { it.task == job })
    }

    @Test
    @WithJenkins
    fun `different comment deliveries may queue the same pull request revision`(jenkins: JenkinsRule) {
        jenkins.jenkins.numExecutors = 0
        val job = jenkins.createFreeStyleProject("separate-comment-deliveries")

        fun candidate(deliveryId: String): CnbVerifiedQueueCandidate {
            val delivery = commentDelivery(deliveryId)
            return CnbVerifiedQueueCandidate(job, delivery, requireNotNull(CnbQueueIdentity.from(delivery)))
        }

        assertEquals(1, CnbAtomicQueueScheduler.schedule(listOf(candidate("comment-delivery-1"))))
        assertEquals(1, CnbAtomicQueueScheduler.schedule(listOf(candidate("comment-delivery-2"))))
        assertEquals(2, Queue.getInstance().items.size)
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
    fun `each job accepts a verified revision only once across queue history and reload`(jenkins: JenkinsRule) {
        var job = jenkins.createFreeStyleProject("new-pr-revision")
        job.quietPeriod = 0

        fun candidate(
            sha: String,
            deliveryId: String = "delivery-${sha.first()}",
            onlyNew: Boolean = true,
        ): CnbVerifiedQueueCandidate {
            val delivery = pullRequestDelivery(sha, deliveryId)
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

        assertEquals(0, CnbAtomicQueueScheduler.schedule(listOf(candidate(SHA_B, "delivery-a", onlyNew = false))))
        assertEquals(0, CnbAtomicQueueScheduler.schedule(listOf(candidate(SHA_A))))
        assertEquals(1, CnbAtomicQueueScheduler.schedule(listOf(candidate(SHA_A, "delivery-a-2", onlyNew = false))))
        jenkins.waitUntilNoActivity()
        assertEquals(1, CnbAtomicQueueScheduler.schedule(listOf(candidate(SHA_B))))
        jenkins.waitUntilNoActivity()
        assertEquals(1, CnbAtomicQueueScheduler.schedule(listOf(candidate(SHA_A, "delivery-a-return"))))
        jenkins.waitUntilNoActivity()
        assertEquals(4, job.builds.count())
    }

    @Test
    @WithJenkins
    fun `push-only webhook observer rebuilds same SHA after verified deletion and store restart`(jenkins: JenkinsRule) {
        val job = jenkins.createFreeStyleProject("webhook-ref-recreation")
        val trigger = CnbPushTrigger("primary", "team/project", "main").apply { setCiSkip(false) }
        val observer = CnbClassicTriggerCandidate(job, trigger)
        val path = Files.createTempDirectory("cnb-ref-lifecycle").resolve("state.journal")
        var store = CnbRefLifecycleStore(path)
        val first = pushDelivery(SHA_A, "1").let { delivery ->
            delivery.copy(payload = delivery.payload.copy(occurredAt = Instant.EPOCH.plusSeconds(1)))
        }
        val deletion =
            CnbWebhookDelivery(
                "primary",
                basePayload(CnbWebhookEvent.BRANCH_DELETE).copy(
                    deliveryId = "2",
                    occurredAt = Instant.EPOCH.plusSeconds(2),
                    ref = CnbWebhookRef("main", "", SHA_A, "", false),
                ),
                "test",
            )
        val recreated = pushDelivery(SHA_A, "3").let { delivery ->
            delivery.copy(payload = delivery.payload.copy(occurredAt = Instant.EPOCH.plusSeconds(3)))
        }

        fun candidate(delivery: CnbWebhookDelivery) =
            CnbVerifiedQueueCandidate(job, delivery, requireNotNull(CnbQueueIdentity.from(delivery)))

        val firstCandidate = CnbWebhookRefLifecycle.applyVerified(first, listOf(observer), listOf(candidate(first)), store).single()
        assertEquals(0L, firstCandidate.identity.refGeneration)
        assertEquals(1, CnbAtomicQueueScheduler.schedule(listOf(firstCandidate)))
        jenkins.waitUntilNoActivity()

        assertFalse(trigger.matches(deletion), "branch deletion is not a default build event")
        assertTrue(trigger.observesRefLifecycle(deletion))
        assertTrue(CnbWebhookRefLifecycle.applyVerified(deletion, listOf(observer), emptyList(), store).isEmpty())
        assertEquals(1, job.builds.count(), "observing a deletion must not schedule it")

        store = CnbRefLifecycleStore(path)
        val recreatedCandidate =
            CnbWebhookRefLifecycle.applyVerified(recreated, listOf(observer), listOf(candidate(recreated)), store).single()
        assertEquals(1L, recreatedCandidate.identity.refGeneration)
        assertEquals(1, CnbAtomicQueueScheduler.schedule(listOf(recreatedCandidate)))
        jenkins.waitUntilNoActivity()
        assertEquals(2, job.builds.count())

        val replay = CnbWebhookRefLifecycle.applyVerified(recreated, listOf(observer), listOf(candidate(recreated)), store).single()
        assertEquals(0, CnbAtomicQueueScheduler.schedule(listOf(replay)))
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

    private fun targetPushClient(
        pullRequests: List<CnbPullRequest> = listOf(livePullRequest().copy(sourceRepo = "alice/project")),
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
                    val repository = args[0].toString()
                    val branch = args[1].toString()
                    if (repository == "team/project" && branch == "main") {
                        CnbBranch("main", SHA_C)
                    } else {
                        pullRequests
                            .firstOrNull { pullRequest ->
                                pullRequest.sourceRepo == repository && pullRequest.sourceBranch == branch
                            }?.let { pullRequest -> CnbBranch(branch, pullRequest.sourceSha) }
                            ?: throw UnsupportedOperationException("unexpected branch ${args.toList()}")
                    }
                }

                "listPullRequests" -> {
                    pullRequests
                }

                "getPullRequest" -> {
                    val number = args[1].toString()
                    pullRequests.firstOrNull { it.number == number }
                        ?: throw CnbApiException("pull request missing", statusCode = 404)
                }

                "getRepository" -> {
                    val path = args[0] as String
                    CnbRepository(
                        path = path,
                        name = path.substringAfterLast('/'),
                        webUrl = "https://cnb.cool/$path",
                        cloneUrl = "https://cnb.cool/$path.git",
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

    private fun pullRequestDelivery(
        sourceSha: String = SHA_A,
        deliveryId: String = "delivery-1",
    ): CnbWebhookDelivery =
        CnbWebhookDelivery(
            "primary",
            basePayload(CnbWebhookEvent.PULL_REQUEST_UPDATE).copy(
                deliveryId = deliveryId,
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

    private fun commentDelivery(deliveryId: String = "comment-delivery-9"): CnbWebhookDelivery =
        pullRequestDelivery().let { delivery ->
            delivery.copy(
                payload =
                    delivery.payload.copy(
                        deliveryId = deliveryId,
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

    private fun pushDelivery(
        sha: String = SHA_C,
        deliveryId: String = "delivery-${sha.first()}",
    ): CnbWebhookDelivery =
        CnbWebhookDelivery(
            "primary",
            basePayload(CnbWebhookEvent.PUSH).let { payload ->
                payload.copy(
                    deliveryId = deliveryId,
                    ref = payload.ref.copy(sha = sha, commit = sha),
                )
            },
            "test",
        )

    private fun waitForLeftItem(queueId: Long): Queue.LeftItem {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            Queue.getInstance().leftItems.firstOrNull { it.id == queueId }?.let { return it }
            Thread.sleep(25)
        }
        error("Queue item $queueId did not transition to a Run")
    }

    private fun basePayload(event: CnbWebhookEvent): CnbWebhookPayload =
        CnbWebhookPayload(
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
