package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import hudson.model.Queue
import hudson.model.TaskListener
import jenkins.branch.OrganizationFolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbOrganizationReconciliationWorkTest {
    @Test
    fun `queues only due CNB organization folders`(jenkins: JenkinsRule) {
        val cnb = jenkins.createProject(OrganizationFolder::class.java, "cnb-org")
        val unrelated = jenkins.createProject(OrganizationFolder::class.java, "other-org")
        jenkins.waitUntilNoActivity()
        Queue.withLock {
            cnb.navigators.add(CnbSCMNavigator("cnb-cool", "acme"))
            cnb.save()
            Queue.getInstance().cancel(cnb)
        }

        assertTrue(cnb.isBuildable, "CNB folder must be buildable")
        assertTrue(cnb.getSCMNavigators().any { it is CnbSCMNavigator }, "CNB navigator must be installed")
        assertFalse(cnb.computation.isBuilding, "CNB folder computation must be idle")
        assertFalse(Queue.getInstance().contains(cnb), "CNB folder must not already be queued")

        assertEquals(
            1,
            CnbOrganizationReconciliationWork().reconcile(
                TaskListener.NULL,
                System.currentTimeMillis() + 7 * 60 * 60 * 1000L,
            ),
        )

        assertFalse(Queue.getInstance().contains(unrelated))
        Queue.getInstance().cancel(cnb)
    }

    @Test
    fun `global setting disables organization reconciliation`(jenkins: JenkinsRule) {
        val cnb = jenkins.createProject(OrganizationFolder::class.java, "cnb-org")
        cnb.navigators.add(CnbSCMNavigator("cnb-cool", "acme"))
        cnb.save()
        Queue.getInstance().cancel(cnb)
        CnbGlobalConfiguration.get().setOrganizationReconciliationEnabled(false)

        assertEquals(
            0,
            CnbOrganizationReconciliationWork().reconcile(
                TaskListener.NULL,
                System.currentTimeMillis() + 7 * 60 * 60 * 1000L,
            ),
        )

        assertFalse(Queue.getInstance().contains(cnb))
    }
}
