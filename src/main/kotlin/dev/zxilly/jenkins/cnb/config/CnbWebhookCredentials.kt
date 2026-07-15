package dev.zxilly.jenkins.cnb.config

import com.cloudbees.plugins.credentials.common.StandardListBoxModel
import hudson.Extension
import hudson.model.Describable
import hudson.model.Descriptor
import hudson.util.FormValidation
import hudson.util.ListBoxModel
import jenkins.model.Jenkins
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import java.io.Serializable

/** Repository-bound webhook keys prevent one CNB repository from impersonating another. */
class CnbWebhookCredentials
    @DataBoundConstructor
    constructor(
        repositoryPath: String,
        secretCredentialsId: String,
    ) : Describable<CnbWebhookCredentials>,
        Serializable {
        var repositoryPath: String = normalizeRepository(repositoryPath)
            private set
        var secretCredentialsId: String = secretCredentialsId.trim()
            private set
        var previousSecretCredentialsId: String? = null
            private set

        init {
            require(isValidRepository(this.repositoryPath)) {
                "CNB webhook repository must be a full repository path"
            }
            require(this.secretCredentialsId.isNotEmpty()) { "CNB webhook secret credential is required" }
        }

        @DataBoundSetter
        fun setPreviousSecretCredentialsId(value: String?) {
            previousSecretCredentialsId = value?.trim()?.takeIf(String::isNotEmpty)
        }

        private fun readResolve(): Any {
            repositoryPath = normalizeRepository(repositoryPath)
            secretCredentialsId = secretCredentialsId.trim()
            previousSecretCredentialsId = previousSecretCredentialsId?.trim()?.takeIf(String::isNotEmpty)
            require(isValidRepository(repositoryPath)) { "Invalid CNB webhook repository path" }
            require(secretCredentialsId.isNotEmpty()) { "CNB webhook secret credential is required" }
            return this
        }

        @Extension
        @Symbol("cnbWebhookCredentials")
        class DescriptorImpl : Descriptor<CnbWebhookCredentials>() {
            override fun getDisplayName(): String = "CNB repository webhook secret"

            fun doFillSecretCredentialsIdItems(
                @QueryParameter secretCredentialsId: String?,
            ): ListBoxModel = secretItems(secretCredentialsId)

            fun doFillPreviousSecretCredentialsIdItems(
                @QueryParameter previousSecretCredentialsId: String?,
            ): ListBoxModel = secretItems(previousSecretCredentialsId)

            @POST
            fun doCheckRepositoryPath(
                @QueryParameter value: String?,
            ): FormValidation {
                Jenkins.get().checkPermission(Jenkins.MANAGE)
                return if (value != null && isValidRepository(normalizeRepository(value))) {
                    FormValidation.ok()
                } else {
                    FormValidation.error("Use a full CNB repository path such as namespace/project")
                }
            }

            @POST
            fun doCheckSecretCredentialsId(
                @QueryParameter value: String?,
            ): FormValidation {
                Jenkins.get().checkPermission(Jenkins.MANAGE)
                return if (value.isNullOrBlank()) {
                    FormValidation.error("Select a repository-specific Secret Text credential")
                } else {
                    FormValidation.ok()
                }
            }

            private fun secretItems(currentValue: String?): ListBoxModel {
                if (!Jenkins.get().hasPermission(Jenkins.MANAGE)) {
                    return StandardListBoxModel().includeCurrentValue(currentValue.orEmpty())
                }
                return StandardListBoxModel()
                    .includeEmptyValue()
                    .includeAs(hudson.security.ACL.SYSTEM2, Jenkins.get(), StringCredentials::class.java)
                    .includeCurrentValue(currentValue.orEmpty())
            }
        }

        companion object {
            private const val serialVersionUID = 1L

            private fun normalizeRepository(value: String): String = value.trim().trim('/')

            private fun isValidRepository(value: String): Boolean {
                if (value.length !in 3..512 || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) {
                    return false
                }
                val segments = value.split('/')
                return segments.size >= 2 && segments.all { it.isNotEmpty() && it != "." && it != ".." }
            }
        }
    }
