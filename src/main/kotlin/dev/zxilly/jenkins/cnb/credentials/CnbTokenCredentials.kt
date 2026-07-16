package dev.zxilly.jenkins.cnb.credentials

import com.cloudbees.plugins.credentials.CredentialsDescriptor
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials.BaseStandardCredentialsDescriptor
import dev.zxilly.jenkins.cnb.Messages
import hudson.Extension
import hudson.model.ModelObject
import hudson.util.FormValidation
import hudson.util.Secret
import org.jenkinsci.Symbol
import org.kohsuke.stapler.AncestorInPath
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST

class CnbTokenCredentials
    @DataBoundConstructor
    constructor(
        scope: CredentialsScope?,
        id: String?,
        description: String?,
        private val token: Secret,
    ) : BaseStandardCredentials(scope, id, description),
        StandardUsernamePasswordCredentials {
        fun getToken(): Secret = token

        override fun getUsername(): String = CNB_USERNAME

        override fun getPassword(): Secret = token

        override fun isUsernameSecret(): Boolean = false

        @Extension
        @Symbol("cnbToken")
        class DescriptorImpl : CredentialsDescriptor(CnbTokenCredentials::class.java) {
            override fun getDisplayName(): String = Messages.CnbTokenCredentials_DisplayName()

            @POST
            fun doCheckId(
                @AncestorInPath context: ModelObject,
                @QueryParameter value: String,
            ): FormValidation = ID_VALIDATOR.doCheckId(context, value)

            companion object {
                private val ID_VALIDATOR = IdValidator()
            }
        }

        /** Retains the credentials plugin's canonical ID and duplicate validation without exposing its protected type. */
        private class IdValidator : BaseStandardCredentialsDescriptor(CnbTokenCredentials::class.java)

        companion object {
            private const val serialVersionUID = 1L
            const val CNB_USERNAME = "cnb"
        }
    }
