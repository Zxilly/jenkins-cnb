package dev.zxilly.jenkins.cnb.api

import com.cloudbees.plugins.credentials.Credentials
import com.cloudbees.plugins.credentials.CredentialsDescriptor
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import hudson.util.Secret
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbClientFactoryTest {
    @Test
    fun `resolves secret text and username-password credentials in item context`(jenkins: JenkinsRule) {
        installCredentials(
            StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                "secret-text",
                "",
                Secret.fromString("secret-text-token"),
            ),
            UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL,
                "username-password",
                "",
                "cnb",
                "password-token",
            ),
        )
        val project = jenkins.createFreeStyleProject("credential-context")

        val secretText = CnbClientFactory.resolveToken("secret-text", "https://api.cnb.cool", project)
        val usernamePassword = CnbClientFactory.resolveToken("username-password", "https://api.cnb.cool", project)

        assertEquals("secret-text-token", secretText.plainText)
        assertEquals("password-token", usernamePassword.plainText)
    }

    @Test
    fun `rejects missing and unsupported credential kinds`(jenkins: JenkinsRule) {
        installCredentials(UnsupportedStandardCredentials("unsupported"))
        val project = jenkins.createFreeStyleProject("unsupported-credential-context")

        val missing =
            assertThrows(IllegalArgumentException::class.java) {
                CnbClientFactory.resolveToken("missing", "https://api.cnb.cool", null)
            }
        val unsupported =
            assertThrows(IllegalArgumentException::class.java) {
                CnbClientFactory.resolveToken("unsupported", "https://api.cnb.cool", project)
            }

        assertTrue(missing.message.orEmpty().contains("was not found"))
        assertTrue(unsupported.message.orEmpty().contains("must be secret text or username/password"))
    }

    @Test
    fun `creates clients with explicit server-default and anonymous credential selection`(jenkins: JenkinsRule) {
        installCredentials(
            StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                "explicit",
                "",
                Secret.fromString("explicit-token"),
            ),
            StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                "server-default",
                "",
                Secret.fromString("default-token"),
            ),
        )
        val server =
            CnbServer(
                "factory",
                "Factory",
                "https://cnb.cool",
                "https://api.cnb.cool",
            )
        server.setCredentialsId("missing-default")
        jenkins.jenkins
            .getExtensionList(CnbGlobalConfiguration::class.java)
            .single()
            .setServers(listOf(server))

        CnbClientFactory.create("factory", "explicit", null).use { client ->
            assertTrue(client is HttpCnbClient)
        }
        val missingDefault =
            assertThrows(IllegalArgumentException::class.java) {
                CnbClientFactory.create("factory", "   ", null)
            }
        assertTrue(missingDefault.message.orEmpty().contains("missing-default"))

        server.setCredentialsId("server-default")
        CnbClientFactory.create("factory", null, null).close()

        server.setCredentialsId(null)
        CnbClientFactory.create("factory", null, null).close()

        assertThrows(IllegalArgumentException::class.java) {
            CnbClientFactory.create("unknown", null, null)
        }
    }

    private fun installCredentials(vararg credentials: Credentials) {
        SystemCredentialsProvider.getInstance().setDomainCredentialsMap(
            mapOf(Domain.global() to credentials.toList()),
        )
    }

    private class UnsupportedStandardCredentials(
        id: String,
    ) : BaseStandardCredentials(CredentialsScope.GLOBAL, id, "") {
        override fun getDescriptor(): CredentialsDescriptor = DESCRIPTOR

        companion object {
            private val DESCRIPTOR =
                object : CredentialsDescriptor(UnsupportedStandardCredentials::class.java) {
                    override fun getDisplayName(): String = "Unsupported test credential"
                }
        }
    }
}
