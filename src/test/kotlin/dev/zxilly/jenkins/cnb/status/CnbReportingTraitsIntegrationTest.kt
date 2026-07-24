package dev.zxilly.jenkins.cnb.status

import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.config.CnbStatusReportingMode
import dev.zxilly.jenkins.cnb.scm.CnbBranchSCMHead
import dev.zxilly.jenkins.cnb.scm.CnbBranchSCMRevision
import dev.zxilly.jenkins.cnb.scm.CnbReportingContextTrait
import dev.zxilly.jenkins.cnb.scm.CnbSCMSource
import dev.zxilly.jenkins.cnb.scm.CnbSkipReportingTrait
import hudson.EnvVars
import hudson.model.Queue
import hudson.model.TaskListener
import hudson.scm.NullSCM
import jenkins.branch.Branch
import jenkins.branch.BranchSource
import jenkins.model.JenkinsLocationConfiguration
import jenkins.scm.api.SCMRevisionAction
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbReportingTraitsIntegrationTest {
    @Test
    fun `reporting context trait supplies the automatic context while an explicit Pipeline context wins`(jenkins: JenkinsRule) {
        configureDisabledReporting(jenkins)
        val fixture =
            branchJob(
                jenkins,
                "context-policy",
                listOf(CnbReportingContextTrait("automatic/context")),
                """
                cnbBuildMetadata(
                  serverId: 'cnb-cool',
                  repository: 'team/repository',
                  sha: '${"a".repeat(40)}',
                  context: 'explicit/context',
                  state: 'running'
                )
                """.trimIndent(),
            )

        val run = buildWithRevision(jenkins, fixture, "a".repeat(40))
        val actions = run.getActions(CnbBuildMetadataAction::class.java).associateBy(CnbBuildMetadataAction::contextKey)

        assertEquals(setOf("automatic/context", "explicit/context"), actions.keys)
        assertEquals("automatic/context", actions.getValue("automatic/context").target()?.context)
        assertEquals("explicit/context", actions.getValue("explicit/context").target()?.context)
        assertEquals(CnbBuildMetadataState.SUCCESS, actions.getValue("automatic/context").state())
        assertEquals(CnbBuildMetadataState.SUCCESS, actions.getValue("explicit/context").state())
    }

    @Test
    fun `skip reporting trait blocks listener states but not an explicit Pipeline report`(jenkins: JenkinsRule) {
        configureDisabledReporting(jenkins)
        val fixture =
            branchJob(
                jenkins,
                "skip-pipeline",
                listOf(CnbReportingContextTrait("automatic/context"), CnbSkipReportingTrait()),
                """
                cnbBuildMetadata(
                  serverId: 'cnb-cool',
                  repository: 'team/repository',
                  sha: '${"b".repeat(40)}',
                  context: 'pipeline/context',
                  state: 'running'
                )
                """.trimIndent(),
            )

        val run = buildWithRevision(jenkins, fixture, "b".repeat(40))
        val actions = run.getActions(CnbBuildMetadataAction::class.java)

        assertEquals(1, actions.size)
        assertEquals("pipeline/context", actions.single().target()?.context)
        // If the final lifecycle callback were not skipped this explicit running state would have
        // been replaced with success.
        assertEquals(CnbBuildMetadataState.RUNNING, actions.single().state())
        assertFalse(actions.any { it.contextKey() == "automatic/context" })
    }

    @Test
    fun `skip reporting trait blocks a completely automatic run but not an explicit Publisher report`(jenkins: JenkinsRule) {
        configureDisabledReporting(jenkins)
        val fixture =
            branchJob(
                jenkins,
                "skip-publisher",
                listOf(CnbReportingContextTrait("automatic/context"), CnbSkipReportingTrait()),
                "echo 'no explicit Pipeline report'",
            )

        val run = buildWithRevision(jenkins, fixture, "c".repeat(40))
        assertNull(run.getAction(CnbBuildMetadataAction::class.java))

        val publisher = CnbBuildMetadataPublisher()
        publisher.setServerId("cnb-cool")
        publisher.setRepository("team/repository")
        publisher.setSha("c".repeat(40))
        publisher.setContext("publisher/context")
        publisher.perform(run, EnvVars(), TaskListener.NULL)

        val action = requireNotNull(run.getAction(CnbBuildMetadataAction::class.java))
        assertEquals("publisher/context", action.target()?.context)
        assertEquals(CnbBuildMetadataState.SUCCESS, action.state())
    }

    @Test
    fun `lifecycle keeps the persisted target while an explicit report can retarget the run`(jenkins: JenkinsRule) {
        configureDisabledReporting(jenkins)
        val fixture =
            branchJob(
                jenkins,
                "stable-target",
                emptyList(),
                "echo 'capture the original target'",
            )
        val run = buildWithRevision(jenkins, fixture, "a".repeat(40))
        val action = requireNotNull(run.getAction(CnbBuildMetadataAction::class.java))
        assertEquals("team/repository", action.target()?.repository)

        val replacement = CnbSCMSource("cnb-cool", "team/replacement")
        replacement.withId(fixture.source.id)
        fixture.project.sourcesList.clear()
        fixture.project.sourcesList.add(BranchSource(replacement))
        fixture.project.save()

        assertTrue(CnbBuildMetadataService.reportRunLifecycle(run, CnbBuildMetadataState.SUCCESS))
        assertEquals("team/repository", action.target()?.repository)
        assertEquals("a".repeat(40), action.target()?.sha)

        assertTrue(
            CnbBuildMetadataService.reportRun(
                run,
                CnbBuildMetadataState.SUCCESS,
                suppliedConfiguration =
                    CnbBuildMetadataConfiguration(
                        serverId = "cnb-cool",
                        repository = "team/replacement",
                        sha = "b".repeat(40),
                    ),
            ),
        )
        assertEquals("team/replacement", action.target()?.repository)
        assertEquals("b".repeat(40), action.target()?.sha)
    }

    @Test
    fun `queued multibranch revision reports queued and cancelled states before a run exists`(jenkins: JenkinsRule) {
        configureDisabledReporting(jenkins)
        val fixture =
            branchJob(
                jenkins,
                "queued-revision",
                emptyList(),
                "echo 'must not start'",
            )
        val revision = CnbBranchSCMRevision(fixture.head, "c".repeat(40))

        @Suppress("DEPRECATION")
        val item =
            requireNotNull(
                Queue.getInstance().schedule(
                    fixture.job,
                    3_600,
                    listOf(SCMRevisionAction(fixture.source, revision)),
                ),
            )
        try {
            val action = requireNotNull(item.getAction(CnbBuildMetadataAction::class.java))
            assertEquals(CnbBuildMetadataState.QUEUED, action.state())
            assertEquals("team/repository", action.target()?.repository)
            assertEquals("c".repeat(40), action.target()?.sha)

            assertTrue(Queue.getInstance().cancel(item))
            assertEquals(CnbBuildMetadataState.ABORTED, action.state())
        } finally {
            Queue.getInstance().cancel(item)
        }
    }

    private fun branchJob(
        jenkins: JenkinsRule,
        name: String,
        traits: List<jenkins.scm.api.trait.SCMSourceTrait>,
        script: String,
    ): BranchFixture {
        val project =
            jenkins.jenkins.createProject(
                WorkflowMultiBranchProject::class.java,
                name,
            )
        val source = CnbSCMSource("cnb-cool", "team/repository")
        source.withId("$name-source")
        source.setTraits(traits)
        project.sourcesList.add(BranchSource(source))
        val head = CnbBranchSCMHead("master")
        val branch = Branch(source.id, head, NullSCM(), emptyList())
        val job: WorkflowJob = project.projectFactory.newInstance(branch)
        val pipelineScript = "node {\n${script.prependIndent("  ")}\n}"
        job.definition = CpsFlowDefinition(pipelineScript, true)
        job.save()

        val policy = CnbBranchSourceReportingPolicy.forItem(job)
        assertEquals(traits.none { it is CnbSkipReportingTrait }, policy.automaticReportingEnabled)
        assertEquals(traits.filterIsInstance<CnbReportingContextTrait>().lastOrNull()?.context, policy.defaultContext)
        return BranchFixture(project, job, source, head)
    }

    private fun buildWithRevision(
        jenkins: JenkinsRule,
        fixture: BranchFixture,
        sha: String,
    ): org.jenkinsci.plugins.workflow.job.WorkflowRun {
        val revision = CnbBranchSCMRevision(fixture.head, sha)
        val future = requireNotNull(fixture.job.scheduleBuild2(0, SCMRevisionAction(fixture.source, revision)))
        val run = future.get()
        assertTrue(run.isBuilding.not())
        return jenkins.assertBuildStatusSuccess(run)
    }

    private fun configureDisabledReporting(jenkins: JenkinsRule) {
        val server = CnbServer.defaultServer()
        server.setStatusReportingMode(CnbStatusReportingMode.DISABLED)
        CnbGlobalConfiguration.get().setServers(listOf(server))
        JenkinsLocationConfiguration.get().url = jenkins.url.toExternalForm()
    }

    private data class BranchFixture(
        val project: WorkflowMultiBranchProject,
        val job: WorkflowJob,
        val source: CnbSCMSource,
        val head: CnbBranchSCMHead,
    )
}
