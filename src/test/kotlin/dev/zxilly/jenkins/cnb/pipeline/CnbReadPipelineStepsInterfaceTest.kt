package dev.zxilly.jenkins.cnb.pipeline

import hudson.EnvVars
import hudson.model.Result
import hudson.model.Run
import hudson.model.TaskListener
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.steps.StepDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbReadPipelineStepsInterfaceTest {
    @Test
    fun `Jenkins loads every read-only Pipeline symbol with the required context`(jenkins: JenkinsRule) {
        val names =
            setOf(
                "cnbCommit",
                "cnbCommits",
                "cnbCompareCommits",
                "cnbCommitAnnotations",
                "cnbPullRequestCommits",
                "cnbPullRequestFiles",
                "cnbPullRequestStatuses",
                "cnbPullRequestReviews",
                "cnbBuildHistory",
            )
        val descriptors =
            jenkins.jenkins.getExtensionList(StepDescriptor::class.java).filter { it.functionName in names }

        assertEquals(names, descriptors.map { it.functionName }.toSet())
        assertTrue(
            descriptors.all {
                it.requiredContext == setOf(Run::class.java, TaskListener::class.java, EnvVars::class.java) &&
                    !it.takesImplicitBlockArgument()
            },
        )
        val helpRoot = "dev/zxilly/jenkins/cnb/pipeline/CnbCommitAnnotationsStep"
        assertTrue(javaClass.classLoader.getResource("$helpRoot/help-commitHashes.html") != null)
        assertTrue(javaClass.classLoader.getResource("$helpRoot/help-keys.html") != null)
    }

    @Test
    fun `Pipeline resolves cnbCommit DSL and reports missing CNB context clearly`(jenkins: JenkinsRule) {
        val job = jenkins.createProject(WorkflowJob::class.java, "read-interface")
        job.definition = CpsFlowDefinition("cnbCommit(ref: 'master')", true)

        val scheduled = requireNotNull(job.scheduleBuild2(0))
        val run = jenkins.assertBuildStatus(Result.FAILURE, scheduled.get())

        jenkins.assertLogContains("CNB repository could not be resolved", run)
    }
}
