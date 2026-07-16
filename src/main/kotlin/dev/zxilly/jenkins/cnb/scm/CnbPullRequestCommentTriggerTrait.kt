package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.trigger.CnbPullRequestCommentPolicy
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import hudson.Extension
import hudson.model.Item
import hudson.util.FormValidation
import hudson.util.ListBoxModel
import jenkins.model.Jenkins
import jenkins.scm.api.SCMSource
import jenkins.scm.api.SCMSourceOwner
import jenkins.scm.api.trait.SCMSourceContext
import jenkins.scm.api.trait.SCMSourceTrait
import jenkins.scm.api.trait.SCMSourceTraitDescriptor
import org.jenkinsci.Symbol
import org.kohsuke.stapler.AncestorInPath
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST

/** Explicitly permits an authorized CNB pull-request comment to rebuild its existing child job. */
class CnbPullRequestCommentTriggerTrait
    @DataBoundConstructor
    constructor(
        commentPattern: String,
    ) : SCMSourceTrait() {
        private var commentPattern: String =
            CnbPullRequestCommentPolicy(
                commentPattern,
                CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE,
            ).pattern
        private var minimumRole: String = CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE
        private var invalidConfiguration: Boolean = false

        fun getCommentPattern(): String = commentPattern

        fun getMinimumRole(): String = minimumRole

        @DataBoundSetter
        fun setMinimumRole(value: String?) {
            val candidate = value.orEmpty().ifBlank { CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE }
            minimumRole = CnbRepositoryRole.parseMinimum(candidate).wireName
        }

        override fun decorateContext(context: SCMSourceContext<*, *>) {
            if (invalidConfiguration) return
            (context as CnbSCMSourceContext).withPullRequestCommentPolicy(
                CnbPullRequestCommentPolicy(commentPattern, minimumRole),
            )
        }

        /**
         * XStream bypasses the data-bound constructor. Invalid legacy state must therefore disable
         * comment-trigger scheduling instead of weakening authorization or breaking source scans.
         */
        @Suppress("unused")
        @SuppressFBWarnings(
            value = ["RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE"],
            justification = "XStream may restore null into Kotlin non-null fields while bypassing the constructor.",
        )
        private fun readResolve(): Any {
            try {
                val policy =
                    CnbPullRequestCommentPolicy(
                        commentPattern.orEmpty(),
                        minimumRole.orEmpty().ifBlank { CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE },
                    )
                commentPattern = policy.pattern
                minimumRole = policy.minimumRole
                invalidConfiguration = false
            } catch (_: IllegalArgumentException) {
                commentPattern = ""
                minimumRole = CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE
                invalidConfiguration = true
            }
            return this
        }

        @Extension
        @Symbol("cnbPullRequestCommentTrigger")
        class DescriptorImpl : SCMSourceTraitDescriptor() {
            override fun getDisplayName(): String = "Build pull requests from authorized CNB comments"

            override fun getContextClass(): Class<out SCMSourceContext<*, *>> = CnbSCMSourceContext::class.java

            override fun getSourceClass(): Class<out SCMSource> = CnbSCMSource::class.java

            fun doFillMinimumRoleItems(): ListBoxModel =
                ListBoxModel().apply {
                    defaultRoleOrder.forEach { role -> add(role.displayName, role.wireName) }
                }

            @POST
            fun doCheckCommentPattern(
                @AncestorInPath context: SCMSourceOwner?,
                @QueryParameter value: String?,
            ): FormValidation =
                validate(context) {
                    CnbPullRequestCommentPolicy(value.orEmpty(), CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE)
                }

            @POST
            fun doCheckMinimumRole(
                @AncestorInPath context: SCMSourceOwner?,
                @QueryParameter value: String?,
            ): FormValidation =
                validate(context) {
                    CnbRepositoryRole.parseMinimum(value.orEmpty())
                }

            private fun validate(
                context: SCMSourceOwner?,
                validation: () -> Unit,
            ): FormValidation {
                if (context == null) {
                    Jenkins.get().checkPermission(Jenkins.MANAGE)
                } else {
                    context.checkPermission(Item.CONFIGURE)
                }
                return try {
                    validation()
                    FormValidation.ok()
                } catch (failure: IllegalArgumentException) {
                    FormValidation.error(failure.message)
                }
            }

            private val defaultRoleOrder =
                listOf(
                    CnbRepositoryRole.DEVELOPER,
                    CnbRepositoryRole.REPORTER,
                    CnbRepositoryRole.MASTER,
                    CnbRepositoryRole.OWNER,
                )
        }
    }
