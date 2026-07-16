package dev.zxilly.jenkins.cnb.api

import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.common.StandardCredentials
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import hudson.model.Item
import hudson.model.Queue
import hudson.security.ACL
import hudson.util.Secret
import org.jenkinsci.plugins.plaincredentials.StringCredentials

object CnbClientFactory {
    @JvmStatic
    fun create(
        serverId: String,
        credentialsId: String? = null,
        context: Item? = null,
    ): CnbClient {
        val server = CnbGlobalConfiguration.get().findServer(serverId)
        val selectedCredentialsId = credentialsId?.takeIf { it.isNotBlank() } ?: server.credentialsId
        val token = selectedCredentialsId?.let { resolveToken(it, server.apiUrl, context) }
        return HttpCnbClient(server, token)
    }

    internal fun resolveToken(
        credentialsId: String,
        apiUrl: String,
        context: Item?,
    ): Secret {
        val authentication = (context as? Queue.Task)?.defaultAuthentication2 ?: ACL.SYSTEM2
        val credential =
            CredentialsProvider.findCredentialByIdInItem(
                credentialsId,
                StandardCredentials::class.java,
                context,
                authentication,
                URIRequirementBuilder.fromUri(apiUrl).build(),
            ) ?: throw IllegalArgumentException("The configured CNB credential was not found in this item context")

        return when (credential) {
            is StringCredentials -> {
                credential.secret
            }

            is StandardUsernamePasswordCredentials -> {
                credential.password
            }

            else -> {
                throw IllegalArgumentException(
                    "The configured CNB credential must be secret text or username/password",
                )
            }
        }
    }
}
