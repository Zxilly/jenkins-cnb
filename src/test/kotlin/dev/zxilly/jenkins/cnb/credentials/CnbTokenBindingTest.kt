package dev.zxilly.jenkins.cnb.credentials

import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.domains.Domain
import hudson.util.Secret
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbTokenBindingTest {
    @Test
    fun `withCredentials exposes token through configured variable and masks it`(jenkins: JenkinsRule) {
        val token = "cnb-secret-value-never-log"
        val credential =
            CnbTokenCredentials(
                CredentialsScope.GLOBAL,
                "cnb-pipeline-token",
                "CNB pipeline token",
                Secret.fromString(token),
            )
        CredentialsProvider
            .lookupStores(jenkins.jenkins)
            .iterator()
            .next()
            .addCredentials(Domain.global(), credential)

        val job = jenkins.createProject(WorkflowJob::class.java, "cnb-token-binding")
        job.definition =
            CpsFlowDefinition(
                """
                node {
                  withCredentials([cnbToken(credentialsId: 'cnb-pipeline-token', variable: 'CNB_ACCESS_TOKEN')]) {
                    if (env.CNB_ACCESS_TOKEN != '$token') { error('token was not bound') }
                    echo "masked=${'$'}{env.CNB_ACCESS_TOKEN}"
                  }
                }
                """.trimIndent(),
                true,
            )

        val run = jenkins.buildAndAssertSuccess(job)
        val log = JenkinsRule.getLog(run)
        assertFalse(log.contains(token))
        assertFalse(log.contains("masked=$token"))
        val symbols = CnbTokenBinding.DescriptorImpl::class.java.getAnnotation(org.jenkinsci.Symbol::class.java).value
        assertEquals(listOf("cnbToken"), symbols.toList())
        assertFalse(CnbTokenBinding.DescriptorImpl().requiresWorkspace())
    }

    @Test
    fun `binding rejects unsafe environment variable names`() {
        assertThrows(IllegalArgumentException::class.java) {
            CnbTokenBinding("cnb-pipeline-token", "TOKEN-WITH-DASH")
        }
    }
}
