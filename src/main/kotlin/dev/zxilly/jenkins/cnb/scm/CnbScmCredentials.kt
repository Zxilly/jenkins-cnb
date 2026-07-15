package dev.zxilly.jenkins.cnb.scm

import com.cloudbees.plugins.credentials.CredentialsMatchers
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.common.StandardListBoxModel
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.credentials.CnbTokenCredentials
import hudson.model.Item
import hudson.model.Queue
import hudson.security.ACL
import hudson.util.FormValidation

/** Keeps API authentication independent from Git transport authentication. */
internal object CnbScmCredentials {
    fun checkoutCredentialsId(
        serverId: String,
        apiCredentialsId: String?,
        checkoutCredentialsId: String?,
        context: Item?,
    ): String? {
        val server = CnbGlobalConfiguration.get().findServer(serverId)
        checkoutCredentialsId?.takeIf { it.isNotBlank() }?.let { id ->
            val credential =
                findCheckoutCredential(id, server.webUrl, context)
                    ?: throw IllegalArgumentException(
                        "CNB checkout credential '$id' was not found or is not usable in this item context",
                    )
            require(CNB_GIT_CREDENTIALS_MATCHER.matches(credential)) {
                "CNB checkout credential '$id' must be username/password credentials with username 'cnb'"
            }
            return credential.id
        }
        val apiId = apiCredentialsId?.takeIf { it.isNotBlank() } ?: server.credentialsId ?: return null
        val reusable = findCheckoutCredential(apiId, server.webUrl, context)
        return reusable?.takeIf(CNB_GIT_CREDENTIALS_MATCHER::matches)?.id
    }

    fun checkoutCredentialItems(
        context: Item?,
        currentValue: String?,
        serverId: String?,
    ): StandardListBoxModel {
        val result = StandardListBoxModel()
        val requirements =
            serverId
                ?.let { id -> CnbGlobalConfiguration.get().getServers().firstOrNull { it.id == id } }
                ?.webUrl
                ?.let { URIRequirementBuilder.fromUri(it).build() }
                .orEmpty()
        result.includeEmptyValue()
        result.includeMatchingAs(
            (context as? Queue.Task)?.defaultAuthentication2 ?: ACL.SYSTEM2,
            context,
            StandardUsernamePasswordCredentials::class.java,
            requirements,
            CNB_GIT_CREDENTIALS_MATCHER,
        )
        result.includeCurrentValue(currentValue.orEmpty())
        return result
    }

    fun currentCheckoutCredential(currentValue: String?): StandardListBoxModel =
        StandardListBoxModel().also { it.includeCurrentValue(currentValue.orEmpty()) }

    fun checkCheckoutCredentials(
        serverId: String?,
        apiCredentialsId: String?,
        checkoutCredentialsId: String?,
        context: Item?,
    ): FormValidation {
        if (serverId.isNullOrBlank()) return FormValidation.ok()
        val server =
            CnbGlobalConfiguration.get().getServers().firstOrNull { it.id == serverId }
                ?: return FormValidation.ok()
        checkoutCredentialsId?.takeIf { it.isNotBlank() }?.let { id ->
            val explicit =
                findCheckoutCredential(id, server.webUrl, context)
                    ?: return FormValidation.error("The checkout credential was not found or is not usable here")
            return if (CNB_GIT_CREDENTIALS_MATCHER.matches(explicit)) {
                FormValidation.ok()
            } else {
                FormValidation.error("The checkout credential cannot authenticate HTTPS Git")
            }
        }
        val apiId =
            apiCredentialsId?.takeIf { it.isNotBlank() } ?: server.credentialsId
                ?: return FormValidation.ok("Git checkout will be anonymous")
        val reusable = findCheckoutCredential(apiId, server.webUrl, context)
        return if (reusable != null && CNB_GIT_CREDENTIALS_MATCHER.matches(reusable)) {
            FormValidation.ok("The API credential will also be used for Git checkout")
        } else {
            FormValidation.warning("The API credential cannot authenticate Git; checkout will be anonymous")
        }
    }

    private fun findCheckoutCredential(
        credentialsId: String,
        webUrl: String,
        context: Item?,
    ): StandardUsernamePasswordCredentials? =
        CredentialsProvider.findCredentialByIdInItem(
            credentialsId,
            StandardUsernamePasswordCredentials::class.java,
            context,
            (context as? Queue.Task)?.defaultAuthentication2 ?: ACL.SYSTEM2,
            URIRequirementBuilder.fromUri(webUrl).build(),
        )

    private val CNB_GIT_CREDENTIALS_MATCHER =
        CredentialsMatchers.allOf(
            CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials::class.java),
            CredentialsMatchers.withUsername(CnbTokenCredentials.CNB_USERNAME),
        )
}
