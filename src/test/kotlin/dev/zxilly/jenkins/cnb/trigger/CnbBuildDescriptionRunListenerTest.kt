package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.webhook.CnbWebhookActor
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookInstance
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPayload
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRef
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRepository
import hudson.model.CauseAction
import hudson.model.TaskListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.time.Instant

@WithJenkins
class CnbBuildDescriptionRunListenerTest {
    @Test
    fun `sets descriptions for enabled CNB classic builds and leaves ordinary builds alone`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("descriptions")
        val trigger = CnbPushTrigger("primary", "team/project", "**")
        project.addTrigger(trigger)

        val cnbBuild = jenkins.assertBuildStatusSuccess(project.scheduleBuild2(0, CnbPushCause.from(delivery())))
        val ordinaryBuild = jenkins.assertBuildStatusSuccess(project.scheduleBuild2(0))

        assertEquals("CNB push event for team/project at main by alice", cnbBuild.description)
        assertNull(ordinaryBuild.description)
    }

    @Test
    fun `respects the disabled build description option`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("descriptions-disabled")
        val trigger = CnbPushTrigger("primary", "team/project", "**")
        trigger.setSetBuildDescription(false)
        project.addTrigger(trigger)

        val build = jenkins.assertBuildStatusSuccess(project.scheduleBuild2(0, CnbPushCause.from(delivery())))

        assertNull(build.description)
    }

    @Test
    fun `never overwrites an existing build description`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("descriptions-existing")
        project.addTrigger(CnbPushTrigger("primary", "team/project", "**"))
        val build = jenkins.assertBuildStatusSuccess(project.scheduleBuild2(0, CnbPushCause.from(delivery())))
        build.description = "owned by pipeline"

        CnbBuildDescriptionRunListener().onStarted(build, TaskListener.NULL)

        assertEquals("owned by pipeline", build.description)
        assertEquals(1, build.getActions(CauseAction::class.java).size)
    }

    private fun delivery(): CnbWebhookDelivery =
        CnbWebhookDelivery(
            "primary",
            CnbWebhookPayload(
                schema = CnbWebhookPayload.SCHEMA_V1,
                installationId = "primary",
                deliveryId = "description-delivery",
                buildId = "build-1",
                occurredAt = Instant.EPOCH,
                event = CnbWebhookEvent.PUSH,
                eventUrl = "",
                retry = false,
                instance = CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
                repository = CnbWebhookRepository("repo-1", "team/project", "https://cnb.cool/team/project"),
                actor = CnbWebhookActor("user-1", "alice", "Alice", ""),
                ref = CnbWebhookRef("main", SHA, "b".repeat(40), SHA, false),
                pullRequest = null,
            ),
            "test",
        )

    private companion object {
        val SHA = "a".repeat(40)
    }
}
