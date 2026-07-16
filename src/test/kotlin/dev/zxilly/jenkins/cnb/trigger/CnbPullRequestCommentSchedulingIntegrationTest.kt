package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbContent
import dev.zxilly.jenkins.cnb.api.model.CnbContentEncoding
import dev.zxilly.jenkins.cnb.api.model.CnbContentType
import dev.zxilly.jenkins.cnb.api.model.CnbLabel
import dev.zxilly.jenkins.cnb.api.model.CnbMemberAccess
import dev.zxilly.jenkins.cnb.api.model.CnbMemberAccessLevel
import dev.zxilly.jenkins.cnb.api.model.CnbPullComment
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestState
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryStatus
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryVisibility
import dev.zxilly.jenkins.cnb.scm.CnbBranchSCMHead
import dev.zxilly.jenkins.cnb.scm.CnbOriginPullRequestDiscoveryTrait
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestCommentTriggerTrait
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestFilterTrait
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestSCMHead
import dev.zxilly.jenkins.cnb.scm.CnbSCMSource
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookActor
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookInstance
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPayload
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPullRequest
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRef
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRepository
import hudson.model.CauseAction
import hudson.model.Queue
import hudson.model.TaskListener
import hudson.scm.NullSCM
import jenkins.branch.Branch
import jenkins.branch.BranchSource
import jenkins.scm.api.SCMHeadObserver
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.SCMRevisionAction
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.lang.reflect.Proxy
import java.time.Instant

@WithJenkins
class CnbPullRequestCommentSchedulingIntegrationTest {
    @Test
    fun `live authorized comment schedules only the existing PR child that still passes source policy`(jenkins: JenkinsRule) {
        jenkins.jenkins.numExecutors = 0
        var readyLabel = false
        val project =
            jenkins.jenkins.createProject(
                WorkflowMultiBranchProject::class.java,
                "comments",
            )
        val source =
            TestCnbSCMSource {
                sourceClient(if (readyLabel) listOf(CnbLabel("label-1", "ready")) else emptyList())
            }
        source.withId("cnb-source")
        source.setTraits(
            listOf(
                CnbOriginPullRequestDiscoveryTrait(2),
                CnbPullRequestFilterTrait(false, "**", "**", "ready", ""),
                CnbPullRequestCommentTriggerTrait("rebuild").apply { setMinimumRole("Reporter") },
            ),
        )
        project.sourcesList.add(BranchSource(source))
        source.setOwner(project)

        val head =
            CnbPullRequestSCMHead(
                "PR-7",
                "7",
                CnbBranchSCMHead("main"),
                ChangeRequestCheckoutStrategy.HEAD,
                SCMHeadOrigin.DEFAULT,
                "team/project",
                "feature/change",
                "alice",
                "Change",
            )
        val branch = Branch(source.id, head, NullSCM(), emptyList())
        val job: WorkflowJob = project.projectFactory.newInstance(branch)
        job.definition = CpsFlowDefinition("echo 'comment build'", true)
        job.save()
        // BranchProjectFactory.newInstance only constructs a child with this project as its
        // parent. Register the persisted child exactly as an AbstractFolder reload would so the
        // planner can find a real, existing multibranch item rather than an unattached fixture.
        project.addLoadedChild(job, job.name)
        assertEquals(job, project.getItem(job.name))

        val blocked =
            CnbVerifiedWebhookPlanner.pullRequestComment(commentDelivery()) { _, _ ->
                liveCommentClient()
            }
        assertTrue(blocked.isEmpty())

        readyLabel = true
        val discovered = SCMHeadObserver.collect()
        source.fetch(discovered, CnbSCMHeadEvent(commentDelivery()), TaskListener.NULL)
        assertEquals(listOf("PR-7"), discovered.result().keys.map { it.name })
        assertEquals(source.id, project.projectFactory.getBranch(job).sourceId)
        val planned =
            CnbVerifiedWebhookPlanner.pullRequestComment(commentDelivery()) { _, _ ->
                liveCommentClient()
            }
        assertEquals(listOf(job.fullName), planned.map { it.job.fullName })
        // Hold the queue lock across scheduling and inspection so a flyweight Pipeline cannot
        // start between schedule() returning and the assertions below.
        Queue.withLock {
            assertEquals(1, CnbAtomicQueueScheduler.schedule(planned))

            val queued = Queue.getInstance().items.single()
            assertEquals(job, queued.task)
            assertEquals("refs/pull/7/head", requireNotNull(queued.getAction(CnbQueueAction::class.java)).ref)
            val queuedRevision = requireNotNull(queued.getAction(SCMRevisionAction::class.java)).revision
            assertEquals(SOURCE_SHA, (queuedRevision as dev.zxilly.jenkins.cnb.scm.CnbPullRequestSCMRevision).headHash)
            val cause = requireNotNull(queued.getAction(CauseAction::class.java)).causes.single()
            assertFalse(cause.shortDescription.contains(COMMENT_BODY))
            assertFalse(cause.toString().contains(COMMENT_BODY))
            Queue.getInstance().cancel(queued)
        }
    }

    private fun sourceClient(labels: List<CnbLabel>): CnbClient =
        clientProxy { method ->
            when (method) {
                "getRepository" -> repository()
                "getPullRequest" -> livePullRequest()
                "getBranch" -> CnbBranch("main", TARGET_SHA)
                "listPullLabels" -> labels
                "getContent" -> CnbContent("Jenkinsfile", SOURCE_SHA, CnbContentType.BLOB, 16, "ZWNobyAnb2sn", CnbContentEncoding.BASE64)
                else -> unsupported(method)
            }
        }

    private fun liveCommentClient(): CnbClient =
        clientProxy { method ->
            when (method) {
                "getPullRequest" -> livePullRequest()
                "getPullComment" -> CnbPullComment("comment-9", COMMENT_BODY, "alice")
                "listMemberAccessLevels" -> listOf(CnbMemberAccess("team/project", CnbMemberAccessLevel.DEVELOPER))
                else -> unsupported(method)
            }
        }

    private fun clientProxy(result: (String) -> Any?): CnbClient =
        Proxy.newProxyInstance(
            CnbClient::class.java.classLoader,
            arrayOf(CnbClient::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCapabilities" -> CnbApiCapabilities()
                "close" -> Unit
                "toString" -> "CnbPullRequestCommentSchedulingIntegrationTestClient"
                else -> result(method.name)
            }
        } as CnbClient

    private fun unsupported(method: String): Nothing = throw UnsupportedOperationException(method)

    private fun repository(): CnbRepository =
        CnbRepository(
            path = "team/project",
            name = "project",
            webUrl = "https://cnb.cool/team/project",
            cloneUrl = "https://cnb.cool/team/project.git",
            defaultBranch = "main",
            status = CnbRepositoryStatus.OK,
            visibility = CnbRepositoryVisibility.PUBLIC,
        )

    private fun livePullRequest(): CnbPullRequest =
        CnbPullRequest(
            number = "7",
            title = "Change",
            state = CnbPullRequestState.OPEN,
            sourceRepo = "team/project",
            sourceBranch = "feature/change",
            sourceSha = SOURCE_SHA,
            targetRepo = "team/project",
            targetBranch = "main",
            targetSha = TARGET_SHA,
            author = "alice",
        )

    private fun commentDelivery(): CnbWebhookDelivery =
        CnbWebhookDelivery(
            "primary",
            CnbWebhookPayload(
                schema = CnbWebhookPayload.SCHEMA_V1,
                installationId = "primary",
                deliveryId = "comment-delivery-9",
                buildId = "build-9",
                occurredAt = Instant.EPOCH,
                event = CnbWebhookEvent.PULL_REQUEST_COMMENT,
                eventUrl = "https://cnb.cool/team/project/-/pulls/7#comment-9",
                retry = false,
                instance = CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
                repository = CnbWebhookRepository("repo-1", "team/project", "https://cnb.cool/team/project"),
                actor = CnbWebhookActor("user-1", "alice", "Alice", ""),
                ref = CnbWebhookRef("main", TARGET_SHA, TARGET_SHA, TARGET_SHA, false),
                pullRequest =
                    CnbWebhookPullRequest(
                        id = "pr-7",
                        number = "7",
                        title = "Change",
                        description = "",
                        proposer = "alice",
                        sourceRepository = "team/project",
                        sourceBranch = "feature/change",
                        sourceSha = SOURCE_SHA,
                        targetBranch = "main",
                        targetSha = TARGET_SHA,
                        mergeSha = null,
                        action = "comment",
                        wip = false,
                        commentId = "comment-9",
                        commentBody = COMMENT_BODY,
                        commentType = "note",
                    ),
            ),
            "test",
        )

    private class TestCnbSCMSource(
        private val openClient: () -> CnbClient,
    ) : CnbSCMSource("primary", "team/project") {
        override fun client(): CnbClient = openClient()
    }

    private companion object {
        const val COMMENT_BODY = "rebuild"
        val SOURCE_SHA = "a".repeat(40)
        val TARGET_SHA = "b".repeat(40)
    }
}
