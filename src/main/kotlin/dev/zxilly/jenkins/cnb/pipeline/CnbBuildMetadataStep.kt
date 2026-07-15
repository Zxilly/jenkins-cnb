package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataConfiguration
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataService
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataState
import hudson.EnvVars
import hudson.Extension
import hudson.model.Run
import hudson.model.TaskListener
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.workflow.steps.Step
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepDescriptor
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter

/** Pipeline step that attaches/updates durable CNB build metadata for the current Run. */
class CnbBuildMetadataStep
    @DataBoundConstructor
    constructor() : Step() {
        var serverId: String? = null
            private set
        var repository: String? = null
            private set
        var commitRepository: String? = null
            private set
        var sha: String? = null
            private set
        var pullRequestNumber: String? = null
            private set
        var context: String? = null
            private set
        var credentialsId: String? = null
            private set

        @DataBoundSetter fun setServerId(value: String?) {
            serverId = clean(value)
        }

        @DataBoundSetter fun setRepository(value: String?) {
            repository = clean(value)
        }

        @DataBoundSetter fun setCommitRepository(value: String?) {
            commitRepository = clean(value)
        }

        @DataBoundSetter fun setSha(value: String?) {
            sha = clean(value)
        }

        @DataBoundSetter fun setPullRequestNumber(value: String?) {
            pullRequestNumber = clean(value)
        }

        @DataBoundSetter fun setContext(value: String?) {
            context = clean(value)
        }

        @DataBoundSetter fun setCredentialsId(value: String?) {
            credentialsId = clean(value)
        }

        override fun start(context: StepContext): StepExecution = Execution(configuration(), context)

        private fun configuration(): CnbBuildMetadataConfiguration =
            CnbBuildMetadataConfiguration(
                serverId = serverId,
                repository = repository,
                commitRepository = commitRepository,
                sha = sha,
                pullRequestNumber = pullRequestNumber,
                context = context,
                credentialsId = credentialsId,
            )

        private fun clean(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)

        private class Execution(
            private val suppliedConfiguration: CnbBuildMetadataConfiguration,
            context: StepContext,
        ) : SynchronousNonBlockingStepExecution<Void?>(context) {
            override fun run(): Void? {
                val run = context.get(Run::class.java)
                val listener = context.get(TaskListener::class.java)
                val environment = context.get(EnvVars::class.java)
                val state = run.result?.let(CnbBuildMetadataState::fromResult) ?: CnbBuildMetadataState.RUNNING
                CnbBuildMetadataService.reportRun(
                    run = run,
                    state = state,
                    suppliedConfiguration = suppliedConfiguration,
                    environment = environment,
                    listener = listener,
                    announce = true,
                )
                return null
            }

            companion object {
                private const val serialVersionUID = 1L
            }
        }

        @Extension
        @Symbol("cnbBuildMetadata")
        class DescriptorImpl : StepDescriptor() {
            override fun getFunctionName(): String = "cnbBuildMetadata"

            override fun getDisplayName(): String = "Report build metadata to CNB"

            override fun getRequiredContext(): Set<Class<*>> = setOf(Run::class.java, TaskListener::class.java, EnvVars::class.java)

            override fun takesImplicitBlockArgument(): Boolean = false
        }
    }
