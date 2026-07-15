package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEvent
import hudson.EnvVars
import hudson.model.TaskListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
                type = "PushEvent\u0000" + "y".repeat(100),
                repositoryPath = "team/project",
                createdAt = "2".repeat(1000),
                payload = emptyMap(),
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
    }

    private fun push(
        id: String,
        ref: String,
        head: String,
    ): CnbRepositoryEvent =
        CnbRepositoryEvent(
            id = id,
            type = "PushEvent",
            repositoryPath = "team/project",
            createdAt = "2026-07-15T10:15:00Z",
            payload = mapOf("ref" to ref, "head" to head),
        )
}
