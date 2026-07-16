package dev.zxilly.jenkins.cnb.credentials

import hudson.Extension
import hudson.FilePath
import hudson.Launcher
import hudson.model.Run
import hudson.model.TaskListener
import hudson.util.Secret
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.credentialsbinding.BindingDescriptor
import org.jenkinsci.plugins.credentialsbinding.MultiBinding
import org.kohsuke.stapler.DataBoundConstructor

/** Makes a CNB access token available to a scoped `withCredentials` block. */
class CnbTokenBinding
    @DataBoundConstructor
    constructor(
        credentialsId: String,
        variable: String,
    ) : MultiBinding<CnbTokenCredentials>(credentialsId) {
        val variable: String = variable.trim()

        init {
            require(this.variable.matches(VARIABLE_NAME)) {
                "CNB token variable must be a valid environment variable name"
            }
        }

        override fun type(): Class<CnbTokenCredentials> = CnbTokenCredentials::class.java

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun variables(): Set<String> = setOf(variable)

        override fun bind(
            build: Run<*, *>,
            workspace: FilePath?,
            launcher: Launcher?,
            listener: TaskListener,
        ): MultiEnvironment {
            val credential = getCredentials(build)
            return MultiEnvironment(mapOf(variable to Secret.toString(credential.getToken())))
        }

        @Extension
        @Symbol("cnbToken")
        class DescriptorImpl : BindingDescriptor<CnbTokenCredentials>() {
            override fun type(): Class<CnbTokenCredentials> = CnbTokenCredentials::class.java

            override fun getDisplayName(): String = "CNB access token"

            override fun requiresWorkspace(): Boolean = false
        }

        companion object {
            private val VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        }
    }
