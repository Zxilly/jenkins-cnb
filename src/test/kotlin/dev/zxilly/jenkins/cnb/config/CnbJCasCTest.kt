package dev.zxilly.jenkins.cnb.config

import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import dev.zxilly.jenkins.cnb.credentials.CnbTokenCredentials
import io.jenkins.plugins.casc.ConfigurationContext
import io.jenkins.plugins.casc.Configurator
import io.jenkins.plugins.casc.ConfiguratorRegistry
import io.jenkins.plugins.casc.misc.ConfiguredWithCode
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule
import io.jenkins.plugins.casc.misc.Util
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@WithJenkinsConfiguredWithCode
class CnbJCasCTest {
    @Test
    @ConfiguredWithCode("cnb-jcasc.yml")
    fun `imports and exports server profiles`(rule: JenkinsConfiguredWithCodeRule) {
        assertNotNull(rule.jenkins)
        val configured = CnbGlobalConfiguration.get()
        assertTrue(configured.organizationReconciliationEnabled)
        assertEquals(7200, configured.organizationReconciliationIntervalSeconds)
        val servers = configured.getServers()
        assertEquals(1, servers.size)
        val server = servers.single()
        assertEquals("enterprise", server.id)
        assertEquals("https://cnb.example.com", server.webUrl)
        assertEquals("https://api.cnb.example.com", server.apiUrl)
        assertEquals("scan", server.credentialsId)
        assertEquals("report", server.reportingCredentialsId)
        assertEquals("acme/application", server.getWebhookCredentials().single().repositoryPath)
        assertEquals("webhook", server.getWebhookCredentials().single().secretCredentialsId)
        assertEquals(CnbStatusReportingMode.PULL_REQUEST_COMMENT, server.statusReportingMode)
        assertFalse(server.allowInsecureHttp)
        assertTrue(server.allowPrivateNetwork)
        assertTrue(server.eventPollingEnabled)
        val loadedSecret =
            SystemCredentialsProvider
                .getInstance()
                .credentials
                .filterIsInstance<StringCredentials>()
                .single { it.id == "webhook" }
        assertEquals("secret-value", loadedSecret.secret.plainText)
        val loadedScanToken =
            SystemCredentialsProvider
                .getInstance()
                .credentials
                .filterIsInstance<CnbTokenCredentials>()
                .single { it.id == "scan" }
        assertEquals("cnb", loadedScanToken.username)
        assertEquals("scan-token-value", loadedScanToken.password.plainText)

        val context = ConfigurationContext(ConfiguratorRegistry.get())
        val configurator: Configurator<CnbGlobalConfiguration> =
            context.lookupOrFail(CnbGlobalConfiguration::class.java)
        val exported = configurator.describe(configured, context)
        assertNotNull(exported)
        val yaml = Util.toYamlString(exported)
        assertTrue(yaml.contains("enterprise"))
        assertTrue(yaml.contains("https://api.cnb.example.com"))
        assertFalse(yaml.contains("secret-value"))
    }
}
