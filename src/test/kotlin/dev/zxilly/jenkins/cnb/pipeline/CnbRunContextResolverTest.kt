package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.scm.CnbBranchSCMHead
import dev.zxilly.jenkins.cnb.scm.CnbSCMSource
import dev.zxilly.jenkins.cnb.security.CnbRepositoryPath
import hudson.AbortException
import hudson.EnvVars
import hudson.model.Cause
import hudson.model.ParametersAction
import hudson.model.ParametersDefinitionProperty
import hudson.model.StringParameterDefinition
import hudson.model.StringParameterValue
import hudson.model.TaskListener
import hudson.scm.NullSCM
import jenkins.branch.Branch
import jenkins.branch.BranchSource
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbRunContextResolverTest {
    @Test
    fun `credential identifiers are never expanded from build environment`(jenkins: JenkinsRule) {
        CnbGlobalConfiguration.get().setServers(listOf(CnbServer.defaultServer()))
        val run = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject("credential-id-boundary"))

        val context =
            CnbRunContextResolver.resolve(
                run,
                TaskListener.NULL,
                CnbRunContextInput(
                    serverId = "cnb-cool",
                    repository = "team/project",
                    credentialsId = " \$CNB_SECRET_VALUE ",
                ),
                EnvVars("CNB_SECRET_VALUE", "plain-text-token"),
            )

        assertEquals("\$CNB_SECRET_VALUE", context.credentialsId)
    }

    @Test
    fun `explicit values win over CNB environment and values are normalized`(jenkins: JenkinsRule) {
        CnbGlobalConfiguration.get().setServers(listOf(CnbServer.defaultServer()))
        val project = jenkins.createFreeStyleProject("context")
        project.addProperty(
            ParametersDefinitionProperty(
                StringParameterDefinition("CNB_SERVER_ID", "environment-server"),
                StringParameterDefinition("CNB_REPOSITORY", "environment/repo"),
                StringParameterDefinition("CNB_PULL_REQUEST_IID", "17"),
                StringParameterDefinition("CNB_COMMIT", "a".repeat(40)),
            ),
        )
        val parameters =
            ParametersAction(
                StringParameterValue("CNB_SERVER_ID", "environment-server"),
                StringParameterValue("CNB_REPOSITORY", "environment/repo"),
                StringParameterValue("CNB_PULL_REQUEST_IID", "17"),
                StringParameterValue("CNB_COMMIT", "a".repeat(40)),
            )
        val run = project.scheduleBuild2(0, Cause.UserIdCause(), parameters).get()

        val context =
            CnbRunContextResolver.resolve(
                run,
                TaskListener.NULL,
                CnbRunContextInput(
                    serverId = " cnb-cool ",
                    repository = " /explicit/repo.git ",
                    pullRequestNumber = " 42 ",
                    sha = "${"B".repeat(40)}",
                    credentialsId = " token-id ",
                ),
            )

        assertEquals("cnb-cool", context.serverId)
        assertEquals("explicit/repo", context.repository)
        assertEquals("42", context.pullRequestNumber)
        assertEquals("b".repeat(40), context.sha)
        assertEquals("token-id", context.credentialsId)
    }

    @Test
    fun `manual build uses configured default server and fails clearly for missing repository`(jenkins: JenkinsRule) {
        CnbGlobalConfiguration.get().setServers(listOf(CnbServer.defaultServer()))
        val run = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject("missing-context"))

        val failure =
            assertThrows(AbortException::class.java) {
                CnbRunContextResolver.resolve(run, TaskListener.NULL, CnbRunContextInput())
            }

        assertEquals("CNB repository could not be resolved; set repository explicitly or run from a CNB-triggered/SCM job", failure.message)
        assertNull(run.getCause(dev.zxilly.jenkins.cnb.trigger.CnbPushCause::class.java))
    }

    @Test
    fun `run context applies the shared canonical repository path policy`(jenkins: JenkinsRule) {
        CnbGlobalConfiguration.get().setServers(listOf(CnbServer.defaultServer()))
        val run = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject("repository-policy"))

        val unicode =
            CnbRunContextResolver.resolve(
                run,
                TaskListener.NULL,
                CnbRunContextInput(serverId = "cnb-cool", repository = "团队/项目"),
            )
        assertEquals("团队/项目", unicode.repository)

        val invalid =
            listOf(
                "team/../repository",
                "team/./repository",
                "team//repository",
                "team\\repository/project",
                "team/repo sitory",
                "team/repository\u0000",
                "team/${"r".repeat(CnbRepositoryPath.MAX_LENGTH)}",
            )
        invalid.forEach { repository ->
            assertThrows(AbortException::class.java) {
                CnbRunContextResolver.resolve(
                    run,
                    TaskListener.NULL,
                    CnbRunContextInput(serverId = "cnb-cool", repository = repository),
                )
            }
        }
    }

    @Test
    fun `explicit server override never inherits credentials from a different SCM server`(jenkins: JenkinsRule) {
        val serverA = localServer("server-a", 18_080)
        val serverB = localServer("server-b", 18_081)
        CnbGlobalConfiguration.get().setServers(listOf(serverA, serverB))
        val project =
            jenkins.jenkins.createProject(
                WorkflowMultiBranchProject::class.java,
                "credential-source-pairing",
            )
        val source = CnbSCMSource("server-a", "team/project").also {
            it.withId("server-a-source")
            it.setApiCredentialsId("server-a-token")
        }
        project.sourcesList.add(BranchSource(source))
        val branch = Branch(source.id, CnbBranchSCMHead("master"), NullSCM(), emptyList())
        val job = project.projectFactory.newInstance(branch)
        job.definition = CpsFlowDefinition("echo 'context'", true)
        val run = jenkins.buildAndAssertSuccess(job)

        val overridden =
            CnbRunContextResolver.resolve(
                run,
                TaskListener.NULL,
                CnbRunContextInput(serverId = "server-b", repository = "team/project"),
            )
        val sameServer =
            CnbRunContextResolver.resolve(
                run,
                TaskListener.NULL,
                CnbRunContextInput(serverId = "server-a", repository = "team/project"),
            )
        val explicit =
            CnbRunContextResolver.resolve(
                run,
                TaskListener.NULL,
                CnbRunContextInput(
                    serverId = "server-b",
                    repository = "team/project",
                    credentialsId = "server-b-token",
                ),
            )

        assertNull(overridden.credentialsId)
        assertEquals("server-a-token", sameServer.credentialsId)
        assertEquals("server-b-token", explicit.credentialsId)
    }

    private fun localServer(
        id: String,
        port: Int,
    ): CnbServer =
        CnbServer(id, id, "http://127.0.0.1:$port", "http://127.0.0.1:$port").also {
            it.setAllowInsecureHttp(true)
            it.setAllowPrivateNetwork(true)
        }
}
