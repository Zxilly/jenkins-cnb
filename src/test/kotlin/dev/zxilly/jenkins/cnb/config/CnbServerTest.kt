package dev.zxilly.jenkins.cnb.config

import com.cloudbees.plugins.credentials.Credentials
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import com.sun.net.httpserver.HttpServer
import hudson.util.FormValidation
import hudson.util.Secret
import io.jenkins.plugins.casc.ConfigurationAsCode
import io.jenkins.plugins.casc.ConfiguratorException
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

@WithJenkins
class CnbServerTest {
    @Test
    fun `constructor rejects structurally unsafe profile fields`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        listOf("", "-invalid", "a".repeat(65), "line\nbreak").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                CnbServer(id, "CNB", "https://cnb.cool", "https://api.cnb.cool")
            }
        }
        listOf("name\u0000suffix", "n".repeat(129)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                CnbServer("valid", name, "https://cnb.cool", "https://api.cnb.cool")
            }
        }
        listOf("", "https://cnb.cool\nredirect", "https://" + "a".repeat(2_049)).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                CnbServer("valid", "CNB", endpoint, "https://api.cnb.cool")
            }
            assertThrows(IllegalArgumentException::class.java) {
                CnbServer("valid", "CNB", "https://cnb.cool", endpoint)
            }
        }
    }

    @Test
    fun `read resolve revalidates fields restored outside the data bound constructor`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val server = CnbServer.defaultServer()
        val id = CnbServer::class.java.getDeclaredField("id")
        id.isAccessible = true
        id.set(server, "../restored")

        assertThrows(IllegalArgumentException::class.java) { server.readResolve() }
    }

    @Test
    fun `JCasC rejects endpoints that violate the configured transport policy`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val resource = requireNotNull(CnbServerTest::class.java.getResource("cnb-invalid-jcasc.yml"))

        assertThrows(ConfiguratorException::class.java) {
            ConfigurationAsCode.get().configure(resource.toString())
        }
    }

    @Test
    fun `server normalizes persisted settings clamps limits and binds unique repository keys`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val server = CnbServer(" enterprise ", "  ", "https://cnb.example/", "https://api.cnb.example/")
        server.setCredentialsId(" api-token ")
        server.setReportingCredentialsId("  ")
        server.setAllowInsecureHttp(true)
        server.setAllowPrivateNetwork(true)
        server.setEventPollingEnabled(false)
        server.setEventPollingIntervalSeconds(1)
        server.setMaxWebhookAgeSeconds(Int.MAX_VALUE)
        server.setConnectTimeoutSeconds(0)
        server.setRequestTimeoutSeconds(Int.MAX_VALUE)
        server.setStatusReportingMode(null)
        server.setAutomaticBuildBadgeEnabled(true)
        server.setAutomaticBuildBadgeKey(" security/tca ")
        val current = CnbWebhookCredentials("/team/project/", "current")
        current.setPreviousSecretCredentialsId(" previous ")
        server.setWebhookCredentials(listOf(current))

        assertEquals("enterprise", server.id)
        assertEquals("enterprise", server.name)
        assertEquals("https://cnb.example", server.webUrl)
        assertEquals("https://api.cnb.example", server.apiUrl)
        assertEquals("api-token", server.credentialsId)
        assertNull(server.reportingCredentialsId)
        assertTrue(server.allowInsecureHttp)
        assertTrue(server.allowPrivateNetwork)
        assertTrue(server.eventPollingEnabled.not())
        assertEquals(60, server.eventPollingIntervalSeconds)
        assertEquals(3600, server.maxWebhookAgeSeconds)
        assertEquals(1, server.connectTimeoutSeconds)
        assertEquals(600, server.requestTimeoutSeconds)
        assertEquals(CnbStatusReportingMode.BOTH, server.statusReportingMode)
        assertTrue(server.automaticBuildBadgeEnabled)
        assertEquals("security/tca", server.automaticBuildBadgeKey)
        assertEquals("previous", current.previousSecretCredentialsId)
        assertSame(current, server.webhookCredentialsFor(" /team/project/ "))
        assertEquals(listOf(current), server.getWebhookCredentials())
        assertSame(server, server.readResolve())

        assertThrows(IllegalArgumentException::class.java) {
            server.setWebhookCredentials(listOf(current, CnbWebhookCredentials("team/project", "other")))
        }

        val local = CnbServer("local", "Local", "http://127.0.0.1:8080", "http://127.0.0.1:8081")
        local.setAllowInsecureHttp(true)
        local.setAllowPrivateNetwork(true)
        assertEquals("http://127.0.0.1:8080", local.normalizedWebUri().toString())
        assertEquals("http://127.0.0.1:8081", local.normalizedApiUri().toString())
        assertEquals("cnb-cool", CnbServer.defaultServer().id)
        assertFalse(CnbServer.defaultServer().automaticBuildBadgeEnabled)
        assertEquals("security/tca", CnbServer.defaultServer().automaticBuildBadgeKey)
    }

    @Test
    fun `descriptor validates endpoints identifiers modes and configured credential types`(jenkins: JenkinsRule) {
        val credentials: List<Credentials> =
            listOf(
                StringCredentialsImpl(CredentialsScope.GLOBAL, "secret-token", "", Secret.fromString("secret")),
                UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "user-token", "", "cnb", "token"),
            )
        SystemCredentialsProvider.getInstance().setDomainCredentialsMap(mapOf(Domain.global() to credentials))
        val descriptor = requireNotNull(jenkins.jenkins.getDescriptor(CnbServer::class.java)) as CnbServer.DescriptorImpl

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckId("server_1.example").kind)
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckId("-invalid").kind)
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckName("CNB").kind)
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckName(" ").kind)
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckWebUrl("https://8.8.8.8", false, false).kind)
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckApiUrl("http://8.8.8.8", false, false).kind)
        assertEquals(FormValidation.Kind.OK, descriptor.doValidateConfiguration("http://8.8.8.8", true, false).kind)
        assertEquals(FormValidation.Kind.ERROR, descriptor.doValidateConfiguration("not a URI", true, true).kind)

        val values = descriptor.doFillCredentialsIdItems("https://api.cnb.cool", null).map { it.value }.toSet()
        assertTrue(values.containsAll(setOf("secret-token", "user-token")))
        val reporting = descriptor.doFillReportingCredentialsIdItems("https://api.cnb.cool", "secret-token")
        assertTrue(reporting.map { it.value }.contains("secret-token"))
        assertEquals(CnbStatusReportingMode.entries.size, descriptor.doFillStatusReportingModeItems().size)
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckAutomaticBuildBadgeKey("security/tca").kind)
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckAutomaticBuildBadgeKey("../status").kind)

        val webhookDescriptor =
            requireNotNull(jenkins.jenkins.getDescriptor(CnbWebhookCredentials::class.java)) as CnbWebhookCredentials.DescriptorImpl
        assertEquals(FormValidation.Kind.OK, webhookDescriptor.doCheckRepositoryPath("team/project").kind)
        assertEquals(FormValidation.Kind.ERROR, webhookDescriptor.doCheckRepositoryPath("project").kind)
        assertEquals(FormValidation.Kind.OK, webhookDescriptor.doCheckSecretCredentialsId("secret-token").kind)
        assertEquals(FormValidation.Kind.ERROR, webhookDescriptor.doCheckSecretCredentialsId(" ").kind)
        assertTrue(webhookDescriptor.doFillSecretCredentialsIdItems("secret-token").map { it.value }.contains("secret-token"))
        assertTrue(webhookDescriptor.doFillPreviousSecretCredentialsIdItems(null).map { it.value }.contains("secret-token"))
    }

    @Test
    fun `connection validation performs a real bounded request against the configured endpoint`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/user") { exchange ->
            val body = """{"username":"integration-user","nickname":"Integration"}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        try {
            val descriptor = requireNotNull(jenkins.jenkins.getDescriptor(CnbServer::class.java)) as CnbServer.DescriptorImpl
            val base = "http://127.0.0.1:${http.address.port}"

            val accepted = descriptor.doTestConnection(base, base, null, true, true, 2, 2)
            val rejected = descriptor.doTestConnection(base, base, "missing", true, true, 2, 2)

            assertEquals(FormValidation.Kind.OK, accepted.kind)
            assertTrue(accepted.message.orEmpty().contains("integration-user"))
            assertEquals(FormValidation.Kind.ERROR, rejected.kind)
            assertTrue(rejected.message.orEmpty().contains("configured CNB credential"))
            assertFalse(rejected.message.orEmpty().contains("missing"))
        } finally {
            http.stop(0)
        }
    }
}
