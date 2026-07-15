package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.config.CnbStatusReportingMode
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataAction
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataPublisher
import hudson.EnvVars
import hudson.model.TaskListener
import jenkins.model.JenkinsLocationConfiguration
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbBuildMetadataStepIntegrationTest {
    @Test
    fun `Pipeline step expands trusted environment and persists a durable build target`(jenkins: JenkinsRule) {
        configureDisabledReporting(jenkins)
        val job = jenkins.createProject(WorkflowJob::class.java, "pipeline-metadata")
        job.definition =
            CpsFlowDefinition(
                """
                node {
                  withEnv(['CNB_REPOSITORY=team/pipeline', 'CNB_CONTEXT=release/pipeline']) {
                    cnbBuildMetadata(
                      serverId: ' cnb-cool ',
                      repository: '${'$'}{CNB_REPOSITORY}',
                      commitRepository: 'contributor/pipeline',
                      sha: '${"a".repeat(40)}',
                      pullRequestNumber: '42',
                      context: '${'$'}{CNB_CONTEXT}',
                      credentialsId: ' reporting-token '
                    )
                  }
                }
                """.trimIndent(),
                true,
            )

        val run = jenkins.buildAndAssertSuccess(job)
        val action = requireNotNull(run.getAction(CnbBuildMetadataAction::class.java))
        val target = requireNotNull(action.target())

        assertEquals("cnb-cool", target.serverId)
        assertEquals("team/pipeline", target.repository)
        assertEquals("contributor/pipeline", target.commitRepository)
        assertEquals("a".repeat(40), target.sha)
        assertEquals("42", target.pullRequestNumber)
        assertEquals("release/pipeline", target.context)
        assertEquals("reporting-token", target.credentialsId)
        assertEquals("team/pipeline", action.configuration().repository)
        jenkins.assertLogContains("[CNB] Scheduled running build metadata reconciliation", run)
    }

    @Test
    fun `Freestyle publisher expands build environment without changing the Jenkins result`(jenkins: JenkinsRule) {
        configureDisabledReporting(jenkins)
        val project = jenkins.createFreeStyleProject("freestyle-metadata")
        val publisher = CnbBuildMetadataPublisher()
        publisher.setServerId("cnb-cool")
        publisher.setRepository("\$CNB_REPOSITORY")
        publisher.setSha("\$CNB_SHA")
        publisher.setContext(" freestyle/context ")
        project.publishersList.add(publisher)
        project.addProperty(
            hudson.model.ParametersDefinitionProperty(
                hudson.model.StringParameterDefinition("CNB_REPOSITORY", "team/freestyle"),
                hudson.model.StringParameterDefinition("CNB_SHA", "b".repeat(40)),
            ),
        )

        val parameters =
            hudson.model.ParametersAction(
                hudson.model.StringParameterValue("CNB_REPOSITORY", "team/freestyle"),
                hudson.model.StringParameterValue("CNB_SHA", "b".repeat(40)),
            )
        val run =
            jenkins.assertBuildStatusSuccess(
                project.scheduleBuild2(0, hudson.model.Cause.UserIdCause(), parameters).get(),
            )
        val action = requireNotNull(run.getAction(CnbBuildMetadataAction::class.java))

        assertEquals("team/freestyle", action.target()?.repository)
        assertEquals("b".repeat(40), action.target()?.sha)
        assertEquals("freestyle/context", action.target()?.context)
        jenkins.assertLogContains("[CNB] Scheduled success build metadata reconciliation", run)
    }

    @Test
    fun `step interface normalizes optional values and declares its Jenkins context`() {
        val step = CnbBuildMetadataStep()
        step.setServerId("  cnb-cool  ")
        step.setRepository("  team/repo  ")
        step.setCommitRepository("   ")
        step.setSha("  abcdef1  ")
        step.setPullRequestNumber("  7  ")
        step.setContext(" context ")
        step.setCredentialsId("")

        assertEquals("cnb-cool", step.serverId)
        assertEquals("team/repo", step.repository)
        assertNull(step.commitRepository)
        assertEquals("abcdef1", step.sha)
        assertEquals("7", step.pullRequestNumber)
        assertEquals("context", step.context)
        assertNull(step.credentialsId)

        val descriptor = CnbBuildMetadataStep.DescriptorImpl()
        assertEquals("cnbBuildMetadata", descriptor.functionName)
        assertEquals("Report build metadata to CNB", descriptor.displayName)
        assertEquals(setOf(hudson.model.Run::class.java, TaskListener::class.java, EnvVars::class.java), descriptor.requiredContext)
        assertFalse(descriptor.takesImplicitBlockArgument())
        assertTrue(CnbBuildMetadataPublisher().requiresWorkspace().not())
    }

    private fun configureDisabledReporting(jenkins: JenkinsRule) {
        val server = CnbServer.defaultServer()
        server.setStatusReportingMode(CnbStatusReportingMode.DISABLED)
        CnbGlobalConfiguration.get().setServers(listOf(server))
        JenkinsLocationConfiguration.get().url = jenkins.url.toExternalForm()
    }
}
