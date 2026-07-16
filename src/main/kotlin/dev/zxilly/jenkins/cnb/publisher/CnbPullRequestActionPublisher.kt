package dev.zxilly.jenkins.cnb.publisher

import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.common.StandardCredentials
import com.cloudbees.plugins.credentials.common.StandardListBoxModel
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import dev.zxilly.jenkins.cnb.api.model.CnbPullMergeStyle
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewEvent
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.pipeline.CnbRunContextInput
import dev.zxilly.jenkins.cnb.pipeline.CnbRunContextResolver
import dev.zxilly.jenkins.cnb.pipeline.CnbStepDispatcher
import dev.zxilly.jenkins.cnb.pipeline.CnbStepRequest
import hudson.AbortException
import hudson.EnvVars
import hudson.Extension
import hudson.model.AbstractProject
import hudson.model.Item
import hudson.model.Result
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
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import org.kohsuke.stapler.AncestorInPath
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import java.util.Locale

/** Freestyle post-build action for a CNB pull request. Remote and validation failures fail the build. */
class CnbPullRequestActionPublisher
    @DataBoundConstructor
    constructor(
        action: String,
    ) : Recorder(),
        SimpleBuildStep {
        val action: String = action.trim().lowercase(Locale.ROOT)
        var serverId: String? = null
            private set
        var repository: String? = null
            private set
        var pullRequestNumber: String? = null
            private set
        var sha: String? = null
            private set
        var credentialsId: String? = null
            private set
        var body: String? = null
            private set
        var reviewAction: String = "comment"
            private set
        var mergeMethod: String = "merge"
            private set
        var commitTitle: String? = null
            private set
        var commitMessage: String? = null
            private set
        var confirmDangerousAction: Boolean = false
            private set
        var onlyOnSuccess: Boolean = true
            private set

        init {
            require(this.action in ACTIONS) { "CNB pull request Publisher action must be comment, review, or merge" }
        }

        @DataBoundSetter fun setServerId(value: String?) {
            serverId = clean(value)
        }

        @DataBoundSetter fun setRepository(value: String?) {
            repository = clean(value)
        }

        @DataBoundSetter fun setPullRequestNumber(value: String?) {
            pullRequestNumber = clean(value)
        }

        @DataBoundSetter fun setSha(value: String?) {
            sha = clean(value)
        }

        @DataBoundSetter fun setCredentialsId(value: String?) {
            credentialsId = clean(value)
        }

        @DataBoundSetter fun setBody(value: String?) {
            body = value?.takeIf(String::isNotBlank)
        }

        @DataBoundSetter fun setReviewAction(value: String?) {
            reviewAction = clean(value)?.lowercase(Locale.ROOT) ?: "comment"
            require(reviewAction in REVIEW_ACTIONS) { "CNB review action must be approve, comment, or request_changes" }
        }

        @DataBoundSetter fun setMergeMethod(value: String?) {
            mergeMethod = clean(value)?.lowercase(Locale.ROOT) ?: "merge"
            require(mergeMethod in MERGE_METHODS) { "CNB merge method must be merge, squash, or rebase" }
        }

        @DataBoundSetter fun setCommitTitle(value: String?) {
            commitTitle = clean(value)
        }

        @DataBoundSetter fun setCommitMessage(value: String?) {
            commitMessage = value?.takeIf(String::isNotBlank)
        }

        @DataBoundSetter fun setConfirmDangerousAction(value: Boolean) {
            confirmDangerousAction = value
        }

        @DataBoundSetter fun setOnlyOnSuccess(value: Boolean) {
            onlyOnSuccess = value
        }

        override fun perform(
            run: Run<*, *>,
            environment: EnvVars,
            listener: TaskListener,
        ) {
            if (onlyOnSuccess && run.result != null && run.result != Result.SUCCESS) {
                listener.logger.println("[CNB] Skipped pull request action because the build was not successful")
                return
            }
            val dangerous = action == "merge" || (action == "review" && reviewAction != "comment")
            if (dangerous && !confirmDangerousAction) {
                throw AbortException(
                    "CNB $action action requires the explicit 'Confirm dangerous action' option",
                )
            }
            val resolved =
                CnbRunContextResolver.resolve(
                    run,
                    listener,
                    CnbRunContextInput(serverId, repository, pullRequestNumber, sha, credentialsId),
                    environment,
                )
            val request =
                when (action) {
                    "comment" -> {
                        val expanded =
                            body?.let(environment::expand)?.takeIf(String::isNotBlank)
                                ?: throw AbortException("CNB pull request comment body must not be blank")
                        CnbStepRequest.PullRequestComment(expanded)
                    }

                    "review" -> {
                        CnbStepRequest.ReviewPullRequest(
                            CnbPullReviewEvent.entries.first { it.wireValue == reviewAction },
                            body?.let(environment::expand).orEmpty(),
                        )
                    }

                    "merge" -> {
                        CnbStepRequest.MergePullRequest(
                            CnbPullMergeStyle.entries.first { it.wireValue == mergeMethod },
                            commitTitle?.let(environment::expand).orEmpty(),
                            commitMessage?.let(environment::expand).orEmpty(),
                        )
                    }

                    else -> {
                        throw AbortException("Unsupported CNB pull request action")
                    }
                }
            resolved.client(run).use { client -> CnbStepDispatcher.execute(request, resolved, client) }
            listener.logger.println(
                "[CNB] Completed $action action for ${resolved.repository} pull request ${resolved.requirePullRequestNumber()}",
            )
        }

        override fun requiresWorkspace(): Boolean = false

        private fun clean(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)

        @Extension
        @Symbol("cnbPullRequestAction")
        class DescriptorImpl : BuildStepDescriptor<Publisher>() {
            override fun getDisplayName(): String = "Perform a CNB pull request action"

            override fun isApplicable(jobType: Class<out AbstractProject<*, *>>): Boolean = true

            fun doFillActionItems(): ListBoxModel =
                ListBoxModel().apply {
                    add("Comment", "comment")
                    add("Review", "review")
                    add("Merge", "merge")
                }

            fun doFillReviewActionItems(): ListBoxModel =
                ListBoxModel().apply {
                    add("Comment", "comment")
                    add("Approve", "approve")
                    add("Request changes", "request_changes")
                }

            fun doFillMergeMethodItems(): ListBoxModel =
                ListBoxModel().apply {
                    add("Merge commit", "merge")
                    add("Squash", "squash")
                    add("Rebase", "rebase")
                }

            fun doFillServerIdItems(
                @AncestorInPath item: Item?,
                @QueryParameter serverId: String?,
            ): ListBoxModel {
                if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                    return ListBoxModel().apply { serverId?.takeIf(String::isNotBlank)?.let { add(it, it) } }
                }
                return ListBoxModel().apply {
                    add("Use resolved/default server", "")
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
                            CredentialsMatchers.instanceOf(StringCredentials::class.java),
                            CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials::class.java),
                        ),
                    ).includeCurrentValue(credentialsId.orEmpty())
            }

            @POST
            fun doCheckBody(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation {
                if (item == null || !item.hasPermission(Item.CONFIGURE) || value.isNullOrEmpty()) return FormValidation.ok()
                return if (value.length <= MAX_BODY_LENGTH) FormValidation.ok() else FormValidation.error("Body is too long")
            }

            @POST
            fun doCheckPullRequestNumber(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation {
                if (item == null || !item.hasPermission(Item.CONFIGURE) || value.isNullOrBlank()) return FormValidation.ok()
                return if (value.trim().matches(Regex("[1-9][0-9]{0,18}"))) {
                    FormValidation.ok()
                } else {
                    FormValidation.error("Use a positive pull request number")
                }
            }

            @POST
            fun doCheckSha(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation {
                if (item == null || !item.hasPermission(Item.CONFIGURE) || value.isNullOrBlank()) return FormValidation.ok()
                return if (FULL_SHA.matches(value.trim())) {
                    FormValidation.ok()
                } else {
                    FormValidation.error("Use a full 40- or 64-character hexadecimal pull request source SHA")
                }
            }
        }

        companion object {
            private val ACTIONS = setOf("comment", "review", "merge")
            private val REVIEW_ACTIONS = setOf("approve", "comment", "request_changes")
            private val MERGE_METHODS = setOf("merge", "squash", "rebase")
            private val FULL_SHA = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")
            private const val MAX_BODY_LENGTH = 60_000
        }
    }
