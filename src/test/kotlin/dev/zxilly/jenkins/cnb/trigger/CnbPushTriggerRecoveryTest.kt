package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEvent
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEventPayload
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEventType
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryRefType
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataConfiguration
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataResolver
import hudson.EnvVars
import hudson.model.Action
import hudson.model.Queue
import hudson.model.TaskListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbPushTriggerRecoveryTest {
    @Test
    fun `archive cause bounds externally supplied metadata`() {
        val event =
            CnbRepositoryEvent(
                id = "x".repeat(1000) + "\r\n",
                type = CnbRepositoryEventType("PushEvent" + "y".repeat(119)),
                repositoryPath = "team/project",
                createdAt = "2".repeat(1000),
            )

        val cause = CnbRepositoryEventCause("primary", "team/project", event, "main", "a".repeat(40))

        assertEquals(256, cause.eventId.length)
        assertEquals(64, cause.eventType.length)
        assertEquals(128, cause.createdAt.length)
        assertFalse(cause.eventId.any(Char::isISOControl))
        assertFalse(cause.eventType.any(Char::isISOControl))
    }

    @Test
    fun `archive push recovers a matching classic job without an SCM source`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("classic-cnb")
        val trigger = CnbPushTrigger("primary", "team/project", "release/**")
        trigger.setCiSkip(false)
        project.addTrigger(trigger)

        CnbPushTriggerRecovery.recover(
            "primary",
            "team/project",
            listOf(
                push("event-main", "refs/heads/main", "a".repeat(40)),
                push("event-deleted", "refs/heads/release/deleted", "0".repeat(40)),
                push("event-release", "refs/heads/release/2026.07", "b".repeat(40)),
            ),
            project,
            trigger,
        )
        jenkins.waitUntilNoActivity()

        assertEquals(
            1,
            project.builds
                .iterator()
                .asSequence()
                .count(),
        )
        val build = requireNotNull(project.lastBuild)
        val cause = requireNotNull(build.getCause(CnbRepositoryEventCause::class.java))
        assertEquals("event-release", cause.eventId)
        assertEquals("release/2026.07", cause.ref)
        assertEquals("b".repeat(40), cause.commit)

        val environment = EnvVars()
        CnbEnvironmentContributor().buildEnvironmentFor(build, environment, TaskListener.NULL)
        assertEquals("primary", environment["CNB_SERVER_ID"])
        assertEquals("push", environment["CNB_EVENT"])
        assertEquals("team/project", environment["CNB_REPO_SLUG"])
        assertEquals("release/2026.07", environment["CNB_BRANCH"])
        assertEquals("b".repeat(40), environment["CNB_COMMIT"])
        assertEquals("false", environment["CNB_IS_TAG"])
    }

    @Test
    fun `archive tag push recovers only a tag trigger and resolves tag metadata`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("classic-tag-cnb")
        val trigger = CnbPushTrigger("primary", "team/project", "v1")
        trigger.setEventFilter("tag_push")
        trigger.setCiSkip(false)
        project.addTrigger(trigger)

        CnbPushTriggerRecovery.recover(
            "primary",
            "team/project",
            listOf(
                push("event-branch", "refs/heads/v1", "a".repeat(40)),
                push("event-tag", "refs/tags/v1", "b".repeat(40)),
            ),
            project,
            trigger,
        )
        jenkins.waitUntilNoActivity()

        assertEquals(
            1,
            project.builds
                .iterator()
                .asSequence()
                .count(),
        )
        val build = requireNotNull(project.lastBuild)
        val cause = requireNotNull(build.getCause(CnbRepositoryEventCause::class.java))
        assertEquals("event-tag", cause.eventId)
        assertEquals("v1", cause.ref)
        assertEquals(true, cause.tag)
        assertEquals("refs/tags/v1", requireNotNull(build.getAction(CnbQueueAction::class.java)).ref)

        val environment = EnvVars()
        CnbEnvironmentContributor().buildEnvironmentFor(build, environment, TaskListener.NULL)
        assertEquals("tag_push", environment["CNB_EVENT"])
        assertEquals("true", environment["CNB_IS_TAG"])
        val metadata =
            CnbBuildMetadataResolver.resolve(
                actionable = build,
                item = project,
                causes = build.causes,
                explicit = CnbBuildMetadataConfiguration(),
                previous = null,
            )
        assertEquals("v1", metadata.target?.tag)
    }

    @Test
    fun `push followed by deletion does not recover the obsolete revision`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("deleted-ref-recovery")
        val trigger = CnbPushTrigger("primary", "team/project", "main").apply { setCiSkip(false) }

        CnbPushTriggerRecovery.recover(
            "primary",
            "team/project",
            listOf(
                push("event-main-a", "refs/heads/main", "a".repeat(40)),
                push("event-main-delete", "refs/heads/main", "0".repeat(40), "2026-07-15T10:16:00Z"),
            ),
            project,
            trigger,
        )
        jenkins.waitUntilNoActivity()

        assertEquals(0, project.builds.count())
        assertEquals(0, Queue.getInstance().items.count { it.task == project })
    }

    @Test
    fun `push after deletion recovers the recreated ref at its final revision`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("recreated-ref-recovery")
        val trigger = CnbPushTrigger("primary", "team/project", "main").apply { setCiSkip(false) }
        val finalSha = "b".repeat(40)

        CnbPushTriggerRecovery.recover(
            "primary",
            "team/project",
            listOf(
                push("event-main-a", "refs/heads/main", "a".repeat(40)),
                push("event-main-delete", "refs/heads/main", "0".repeat(40), "2026-07-15T10:16:00Z"),
                push("event-main-b", "refs/heads/main", finalSha, "2026-07-15T10:17:00Z"),
            ),
            project,
            trigger,
        )
        jenkins.waitUntilNoActivity()

        assertEquals(1, project.builds.count())
        val build = requireNotNull(project.lastBuild)
        assertEquals(finalSha, requireNotNull(build.getAction(CnbQueueAction::class.java)).sha)
        assertEquals("event-main-b", requireNotNull(build.getCause(CnbRepositoryEventCause::class.java)).eventId)
    }

    @Test
    fun `chronological and reversed push delete push batches select the recreated ref`(jenkins: JenkinsRule) {
        val sha = "a".repeat(40)
        val transitions =
            listOf(
                push("1", "refs/heads/main", sha, "2026-07-15T10:15:00Z"),
                delete("2", "main", "2026-07-15T10:16:00Z"),
                push("3", "refs/heads/main", sha, "2026-07-15T10:17:00Z"),
            )

        for ((name, events) in listOf("chronological" to transitions, "reversed" to transitions.reversed())) {
            val project = jenkins.createFreeStyleProject("$name-ref-recovery")
            val trigger = CnbPushTrigger("primary", "team/project", "main").apply { setCiSkip(false) }
            CnbPushTriggerRecovery.recover("primary", "team/project", events, project, trigger)
            jenkins.waitUntilNoActivity()

            assertEquals(1, project.builds.count(), name)
            val action = requireNotNull(requireNotNull(project.lastBuild).getAction(CnbQueueAction::class.java))
            assertEquals(sha, action.sha)
            assertEquals(1L, action.identity.refGeneration)
        }
    }

    @Test
    fun `actual delete event lets polling rebuild the same SHA in a new lifecycle`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("same-sha-recreated-ref")
        val trigger = CnbPushTrigger("primary", "team/project", "main").apply { setCiSkip(false) }
        val sha = "a".repeat(40)

        CnbPushTriggerRecovery.recover(
            "primary",
            "team/project",
            listOf(push("1", "refs/heads/main", sha, "2026-07-15T10:15:00Z")),
            project,
            trigger,
        )
        jenkins.waitUntilNoActivity()
        CnbPushTriggerRecovery.recover(
            "primary",
            "team/project",
            listOf(delete("2", "main", "2026-07-15T10:16:00Z")),
            project,
            trigger,
        )
        jenkins.waitUntilNoActivity()
        assertEquals(1, project.builds.count(), "a deletion tombstone must not schedule a push build")

        CnbPushTriggerRecovery.recover(
            "primary",
            "team/project",
            listOf(push("3", "refs/heads/main", sha, "2026-07-15T10:17:00Z")),
            project,
            trigger,
        )
        jenkins.waitUntilNoActivity()

        assertEquals(2, project.builds.count())
        val recreated = requireNotNull(project.lastBuild)
        assertEquals(1L, requireNotNull(recreated.getAction(CnbQueueAction::class.java)).identity.refGeneration)
    }

    @Test
    fun `same timestamp numeric IDs use numeric order in a reversed archive response`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("numeric-event-order")
        val trigger = CnbPushTrigger("primary", "team/project", "main").apply { setCiSkip(false) }
        val at = "2026-07-15T10:15:00Z"
        val sha = "a".repeat(40)

        CnbPushTriggerRecovery.recover(
            "primary",
            "team/project",
            listOf(
                push("10", "refs/heads/main", sha, at),
                delete("9", "main", at),
            ),
            project,
            trigger,
        )
        jenkins.waitUntilNoActivity()

        assertEquals(1, project.builds.count())
        val build = requireNotNull(project.lastBuild)
        assertEquals(sha, requireNotNull(build.getAction(CnbQueueAction::class.java)).sha)
    }

    @Test
    fun `archive recovery reports only the refs whose queue effects failed`(jenkins: JenkinsRule) {
        jenkins.jenkins.numExecutors = 0
        val project = jenkins.createFreeStyleProject("classic-partial-recovery")
        val trigger = CnbPushTrigger("primary", "team/project", "**").apply { setCiSkip(false) }
        val main = push("event-main", "refs/heads/main", "a".repeat(40))
        val release = push("event-release", "refs/heads/release", "b".repeat(40))
        val veto =
            object : Queue.QueueDecisionHandler() {
                override fun shouldSchedule(
                    task: Queue.Task,
                    actions: MutableList<Action>,
                ): Boolean = actions.filterIsInstance<CnbQueueAction>().none { it.ref == "refs/heads/release" }
            }
        Queue.QueueDecisionHandler.all().add(veto)

        try {
            val failure =
                assertThrows(CnbPartialRepositoryEventDispatchException::class.java) {
                    CnbPushTriggerRecovery.recover(
                        "primary",
                        "team/project",
                        listOf(main, release),
                        project,
                        trigger,
                    )
                }

            assertEquals(
                setOf(CnbRepositoryEventPollingWork.eventKey("primary", "team/project", release)),
                failure.retryKeys,
            )
            assertEquals(
                "refs/heads/main",
                requireNotNull(
                    Queue
                        .getInstance()
                        .items
                        .single()
                        .getAction(CnbQueueAction::class.java),
                ).ref,
            )
        } finally {
            Queue.QueueDecisionHandler.all().remove(veto)
            Queue.getInstance().items.forEach(Queue.getInstance()::cancel)
        }
    }

    private fun push(
        id: String,
        ref: String,
        head: String,
        createdAt: String = "2026-07-15T10:15:00Z",
    ): CnbRepositoryEvent =
        CnbRepositoryEvent(
            id = id,
            type = CnbRepositoryEventType("PushEvent"),
            repositoryPath = "team/project",
            createdAt = createdAt,
            payload = CnbRepositoryEventPayload(ref = ref, head = head),
        )

    private fun delete(
        id: String,
        ref: String,
        createdAt: String,
    ): CnbRepositoryEvent =
        CnbRepositoryEvent(
            id = id,
            type = CnbRepositoryEventType("DeleteEvent"),
            repositoryPath = "team/project",
            createdAt = createdAt,
            payload = CnbRepositoryEventPayload(ref = ref, refType = CnbRepositoryRefType.BRANCH),
        )
}
