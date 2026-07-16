package dev.zxilly.jenkins.cnb.status

import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.common.StandardCredentials
import com.cloudbees.plugins.credentials.common.StandardListBoxModel
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import hudson.EnvVars
import hudson.Extension
import hudson.model.AbstractProject
import hudson.model.Item
import hudson.model.Run
import hudson.model.TaskListener
import hudson.security.ACL
import hudson.tasks.BuildStepDescriptor
import hudson.tasks.Publisher
import hudson.tasks.Recorder
import hudson.util.FormValidation
import hudson.util.ListBoxModel
import jenkins.tasks.SimpleBuildStep
import org.jenkinsci.Symbol
import org.kohsuke.stapler.AncestorInPath
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST

/** Freestyle post-build action that configures CNB lifecycle metadata reporting. */
class CnbBuildMetadataPublisher
    @DataBoundConstructor
    constructor() :
    Recorder(),
        SimpleBuildStep {
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
        var tag: String? = null
            private set
        var context: String? = null
            private set
        var credentialsId: String? = null
            private set

        @DataBoundSetter
        fun setServerId(value: String?) {
            serverId = clean(value)
        }

        @DataBoundSetter
        fun setRepository(value: String?) {
            repository = clean(value)
        }

        @DataBoundSetter
        fun setCommitRepository(value: String?) {
            commitRepository = clean(value)
        }

        @DataBoundSetter
        fun setSha(value: String?) {
            sha = clean(value)
        }

        @DataBoundSetter
        fun setPullRequestNumber(value: String?) {
            pullRequestNumber = clean(value)
        }

        @DataBoundSetter
        fun setTag(value: String?) {
            tag = clean(value)
        }

        @DataBoundSetter
        fun setContext(value: String?) {
            context = clean(value)
        }

        @DataBoundSetter
        fun setCredentialsId(value: String?) {
            credentialsId = clean(value)
        }

        override fun perform(
            run: Run<*, *>,
            environment: EnvVars,
            listener: TaskListener,
        ) {
            val state = run.result?.let(CnbBuildMetadataState::fromResult) ?: CnbBuildMetadataState.RUNNING
            CnbBuildMetadataService.reportRun(
                run = run,
                state = state,
                suppliedConfiguration = CnbBuildMetadataService.configurationOf(this),
                environment = environment,
                listener = listener,
                announce = true,
            )
        }

        override fun requiresWorkspace(): Boolean = false

        private fun clean(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)

        @Extension
        @Symbol("cnbBuildMetadataPublisher")
        class DescriptorImpl : BuildStepDescriptor<Publisher>() {
            override fun getDisplayName(): String = "Report build metadata to CNB"

            override fun isApplicable(jobType: Class<out AbstractProject<*, *>>): Boolean = true

            fun doFillServerIdItems(
                @AncestorInPath item: Item?,
                @QueryParameter serverId: String?,
            ): ListBoxModel {
                if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                    return ListBoxModel().apply { serverId?.takeIf(String::isNotBlank)?.let { add(it, it) } }
                }
                return ListBoxModel().apply {
                    add("Use configured default", "")
                    CnbGlobalConfiguration.get().getServers().forEach { add(it.name, it.id) }
                }
            }

            fun doFillCredentialsIdItems(
                @AncestorInPath item: Item?,
                @QueryParameter serverId: String?,
                @QueryParameter credentialsId: String?,
            ): ListBoxModel {
                if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                    return StandardListBoxModel().includeCurrentValue(credentialsId.orEmpty())
                }
                val server = serverId?.let { id -> CnbGlobalConfiguration.get().getServers().firstOrNull { it.id == id } }
                return StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                        ACL.SYSTEM2,
                        item,
                        StandardCredentials::class.java,
                        server?.apiUrl?.let { URIRequirementBuilder.fromUri(it).build() }.orEmpty(),
                        CredentialsMatchers.anyOf(
                            CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials::class.java),
                            CredentialsMatchers.instanceOf(
                                org.jenkinsci.plugins.plaincredentials.StringCredentials::class.java,
                            ),
                        ),
                    ).includeCurrentValue(credentialsId.orEmpty())
            }

            @POST
            fun doCheckServerId(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation {
                if (item == null || !item.hasPermission(Item.CONFIGURE) || value.isNullOrBlank()) return FormValidation.ok()
                return runCatching { CnbGlobalConfiguration.get().findServer(value.trim()) }
                    .fold({ FormValidation.ok() }, { FormValidation.error("Unknown CNB server profile") })
            }

            @POST
            fun doCheckRepository(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation =
                validate(item, value, "Use a CNB repository path such as owner/repository") {
                    CnbBuildMetadataResolver.isRepository(it)
                }

            @POST
            fun doCheckSha(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation =
                validate(item, value, "Use a 7-64 character hexadecimal commit SHA") {
                    CnbBuildMetadataResolver.isSha(it)
                }

            @POST
            fun doCheckCommitRepository(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation =
                validate(item, value, "Use a CNB repository path such as owner/repository") {
                    CnbBuildMetadataResolver.isRepository(it)
                }

            @POST
            fun doCheckPullRequestNumber(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation =
                validate(item, value, "Use a positive pull request number") {
                    CnbBuildMetadataResolver.isPullRequestNumber(it)
                }

            @POST
            fun doCheckTag(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation =
                validate(item, value, "Use a valid CNB tag name") {
                    CnbBuildMetadataResolver.isTag(it)
                }

            @POST
            fun doCheckContext(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation {
                if (item == null || !item.hasPermission(Item.CONFIGURE) || value.isNullOrBlank()) return FormValidation.ok()
                return if (value.length <= 200) FormValidation.ok() else FormValidation.error("Context must be at most 200 characters")
            }

            private fun validate(
                item: Item?,
                value: String?,
                error: String,
                predicate: (String?) -> Boolean,
            ): FormValidation {
                if (item == null || !item.hasPermission(Item.CONFIGURE) || value.isNullOrBlank()) return FormValidation.ok()
                return if (predicate(value)) FormValidation.ok() else FormValidation.error(error)
            }
        }
    }
