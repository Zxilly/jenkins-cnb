package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.webhook.CnbWebhookActor
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookInstance
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPayload
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPullRequest
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRef
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRepository
import hudson.model.Action
import hudson.model.FreeStyleBuild
import hudson.model.FreeStyleProject
import hudson.model.Result
import jenkins.model.InterruptedBuildAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.SleepBuilder
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.time.Instant
import java.util.concurrent.TimeUnit

class CnbQueueActionTest {
    @Test
    fun `queue identity merges only the exact server repository ref and revision`() {
        val queued = CnbQueueAction("primary", "team/project", "main", SHA_A)

        assertFalse(CnbQueueAction("primary", "team/project", "main", SHA_A).shouldSchedule(listOf(queued)))
        assertTrue(CnbQueueAction("secondary", "team/project", "main", SHA_A).shouldSchedule(listOf(queued)))
        assertTrue(CnbQueueAction("primary", "team/other", "main", SHA_A).shouldSchedule(listOf(queued)))
        assertTrue(CnbQueueAction("primary", "team/project", "release", SHA_A).shouldSchedule(listOf(queued)))
        assertTrue(CnbQueueAction("primary", "team/project", "main", SHA_B).shouldSchedule(listOf(queued)))
        assertTrue(queued.shouldSchedule(emptyList<Action>()))
    }

    @Test
    fun `queue identity preserves a complete SHA-256 revision`() {
        val sha256 = "d".repeat(64)

        val identity = requireNotNull(CnbQueueIdentity.from(delivery("main", sha256)))

        assertEquals(sha256, identity.sha)
    }

    @Test
    fun `branch and tag with the same name retain independent queue and supersession scopes`() {
        val branch = requireNotNull(CnbQueueIdentity.from(delivery("v1", SHA_A)))
        val tagDelivery =
            delivery("v1", SHA_B).let { delivery ->
                delivery.copy(
                    payload =
                        delivery.payload.copy(
                            event = CnbWebhookEvent.TAG_PUSH,
                            ref = delivery.payload.ref.copy(tag = true),
                        ),
                )
            }
        val tag = requireNotNull(CnbQueueIdentity.from(tagDelivery))

        assertEquals("refs/heads/v1", branch.ref)
        assertEquals("refs/tags/v1", tag.ref)
        assertTrue(CnbQueueAction(tag).shouldSchedule(listOf(CnbQueueAction(branch))))
        assertFalse(CnbQueueAction(branch).isSupersededBy(tag))
        assertFalse(CnbQueueAction(tag).isSupersededBy(branch))
    }

    @Test
    fun `superseded scope ignores other queue action types`() {
        val identity = CnbQueueIdentity("primary", "team/project", "main", SHA_B)
        val unrelated =
            object : Action {
                override fun getIconFileName(): String? = null

                override fun getDisplayName(): String? = null

                override fun getUrlName(): String? = null
            }

        assertTrue(CnbQueueAction(identity).shouldSchedule(listOf(unrelated)))
        assertTrue(CnbQueueAction("primary", "team/project", "release", SHA_A).isSupersededBy(identity).not())
        assertTrue(CnbQueueAction("primary", "team/project", "main", SHA_A).isSupersededBy(identity))
        assertTrue(CnbQueueAction("primary", "team/project", "main", SHA_B).isSupersededBy(identity).not())
    }

    @Test
    fun `running supersession requires the exact pull request identity and a new revision`() {
        val incoming = CnbQueueIdentity("primary", "team/project", "refs/pull/7/head", SHA_B)

        assertTrue(
            CnbRunningBuilds.isSupersededPullRequestRevision(
                CnbQueueIdentity("primary", "team/project", "refs/pull/7/head", SHA_A),
                incoming,
            ),
        )
        assertFalse(CnbRunningBuilds.isSupersededPullRequestRevision(incoming, incoming))
        assertFalse(
            CnbRunningBuilds.isSupersededPullRequestRevision(
                CnbQueueIdentity("primary", "team/project", "refs/pull/8/head", SHA_A),
                incoming,
            ),
        )
        assertFalse(
            CnbRunningBuilds.isSupersededPullRequestRevision(
                CnbQueueIdentity("secondary", "team/project", "refs/pull/7/head", SHA_A),
                incoming,
            ),
        )
        assertFalse(
            CnbRunningBuilds.isSupersededPullRequestRevision(
                CnbQueueIdentity("primary", "team/other", "refs/pull/7/head", SHA_A),
                incoming,
            ),
        )
        assertFalse(
            CnbRunningBuilds.isSupersededPullRequestRevision(
                CnbQueueIdentity("primary", "team/project", "main", SHA_A),
                CnbQueueIdentity("primary", "team/project", "main", SHA_B),
            ),
        )

        val description = CnbSupersededByPullRequestUpdate(incoming, SHA_A).shortDescription
        assertTrue(description.contains("team/project"))
        assertTrue(description.contains("refs/pull/7/head"))
        assertTrue(description.contains(SHA_A.take(12)))
        assertTrue(description.contains(SHA_B.take(12)))
    }

    @Test
    @WithJenkins
    fun `optional pending cancellation replaces only an older revision of the same ref`(jenkins: JenkinsRule) {
        jenkins.jenkins.numExecutors = 0
        val project = jenkins.createFreeStyleProject("classic-queue")
        val trigger = CnbPushTrigger("primary", "team/project", "**")
        trigger.setCancelPendingBuildsOnUpdate(true)
        project.addTrigger(trigger)
        trigger.start(project, true)

        assertTrue(trigger.scheduleVerified(delivery("main", SHA_A)))
        assertTrue(trigger.scheduleVerified(delivery("release", SHA_A)))
        assertTrue(trigger.scheduleVerified(delivery("main", SHA_B)))

        val actions =
            jenkins.jenkins.queue.items
                .mapNotNull { it.getAction(CnbQueueAction::class.java) }
                .sortedBy { it.ref }
        assertEquals(2, actions.size)
        assertEquals(listOf("refs/heads/main", "refs/heads/release"), actions.map { it.ref })
        assertEquals(SHA_B, actions.first { it.ref == "refs/heads/main" }.sha)
        assertEquals(SHA_A, actions.first { it.ref == "refs/heads/release" }.sha)
        assertEquals(
            0,
            project.builds
                .iterator()
                .asSequence()
                .count(),
        )
        jenkins.jenkins.queue.items
            .forEach(jenkins.jenkins.queue::cancel)
    }

    @Test
    @WithJenkins
    fun `verified pull request update interrupts only the superseded run in its own job`(jenkins: JenkinsRule) {
        jenkins.jenkins.numExecutors = 2
        val project = jenkins.createFreeStyleProject("classic-running")
        val unrelated = jenkins.createFreeStyleProject("unrelated-running")
        project.buildersList.add(SleepBuilder(TimeUnit.SECONDS.toMillis(30)))
        unrelated.buildersList.add(SleepBuilder(TimeUnit.SECONDS.toMillis(30)))
        val trigger = CnbPushTrigger("primary", "team/project", "**")
        trigger.setEventFilter("pull_request,pull_request.update,pull_request.target,pull_request.comment")
        trigger.setCancelRunningBuildsOnUpdate(true)
        project.addTrigger(trigger)
        trigger.start(project, true)

        try {
            unrelated.scheduleBuild2(0)
            val unrelatedBuild = waitForBuild(unrelated, 1)
            jenkins.waitForMessage("Sleeping 30000ms", unrelatedBuild)

            assertTrue(trigger.scheduleVerified(pullRequestDelivery(SHA_A)))
            val superseded = waitForBuild(project, 1)
            jenkins.waitForMessage("Sleeping 30000ms", superseded)
            // The in-flight build already owns its SleepBuilder invocation. Keep the replacement
            // build short so teardown tests cancellation behavior rather than another long sleep.
            project.buildersList.clear()

            assertTrue(trigger.scheduleVerified(pullRequestDelivery(SHA_B)))
            val completed = jenkins.waitForCompletion(superseded)

            assertEquals(Result.ABORTED, completed.result)
            assertTrue(unrelatedBuild.isBuilding)
            val interruption = requireNotNull(completed.getAction(InterruptedBuildAction::class.java))
            assertTrue(interruption.causes.any { it is CnbSupersededByPullRequestUpdate })
        } finally {
            project.isDisabled = true
            unrelated.isDisabled = true
            jenkins.jenkins.queue.items
                .forEach(jenkins.jenkins.queue::cancel)
            listOf(project, unrelated).flatMap { it.builds }.filter { it.isBuilding }.forEach { run ->
                run.executor?.interrupt(Result.ABORTED)
            }
            jenkins.waitUntilNoActivityUpTo(10_000)
        }
    }

    private fun delivery(
        ref: String,
        sha: String,
    ): CnbWebhookDelivery =
        CnbWebhookDelivery(
            "primary",
            CnbWebhookPayload(
                CnbWebhookPayload.SCHEMA_V1,
                "primary",
                "delivery-$ref-${sha.first()}",
                "build-1",
                Instant.EPOCH,
                CnbWebhookEvent.PUSH,
                "",
                false,
                CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
                CnbWebhookRepository("repo-1", "team/project", "https://cnb.cool/team/project"),
                CnbWebhookActor("user-1", "alice", "Alice", ""),
                CnbWebhookRef(ref, sha, SHA_A, sha, false),
                null,
            ),
            "test",
        )

    private fun pullRequestDelivery(sourceSha: String): CnbWebhookDelivery =
        CnbWebhookDelivery(
            "primary",
            CnbWebhookPayload(
                CnbWebhookPayload.SCHEMA_V1,
                "primary",
                "delivery-pr-${sourceSha.first()}",
                "build-1",
                Instant.EPOCH,
                CnbWebhookEvent.PULL_REQUEST_UPDATE,
                "",
                false,
                CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
                CnbWebhookRepository("repo-1", "team/project", "https://cnb.cool/team/project"),
                CnbWebhookActor("user-1", "alice", "Alice", ""),
                CnbWebhookRef("main", SHA_C, SHA_A, SHA_C, false),
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

    private fun waitForBuild(
        project: FreeStyleProject,
        number: Int,
    ): FreeStyleBuild {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            project.getBuildByNumber(number)?.let { return it }
            Thread.sleep(25)
        }
        error("Build #$number for ${project.fullName} did not start")
    }

    companion object {
        private val SHA_A = "a".repeat(40)
        private val SHA_B = "b".repeat(40)
        private val SHA_C = "c".repeat(40)
    }
}
