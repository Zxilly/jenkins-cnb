package dev.zxilly.jenkins.cnb.ui

import dev.zxilly.jenkins.cnb.health.CnbOperationalHealth
import dev.zxilly.jenkins.cnb.health.CnbOperationalHealthMonitor
import hudson.ExtensionList
import jenkins.model.Jenkins
import org.htmlunit.HttpMethod
import org.htmlunit.Page
import org.htmlunit.WebRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.MockAuthorizationStrategy
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbOperationalHealthManagementLinkTest {
    @AfterEach
    fun clearGlobalHealth() {
        CnbOperationalHealth.get().clear()
    }

    @Test
    fun `health page requires administer and is absent from system read management view`(jenkins: JenkinsRule) {
        configureSecurity(jenkins)
        val health = CnbOperationalHealth.get()
        health.recordPolling("production", "team/repository", false, "poll failed")

        jenkins.createWebClient().use { reader ->
            reader.options.isThrowExceptionOnFailingStatusCode = false
            reader.login("reader", "reader")
            val manage = reader.goTo("manage")
            assertFalse(manage.webResponse.contentAsString.contains(CnbOperationalHealthManagementLink.DISPLAY_NAME))
            assertFalse(manage.webResponse.contentAsString.contains(CnbOperationalHealthManagementLink.MONITOR_MESSAGE))

            val forbidden = reader.goTo("manage/cnb-health")
            assertEquals(403, forbidden.webResponse.statusCode)
        }

        jenkins.createWebClient().use { admin ->
            admin.login("admin", "admin")
            val manage = admin.goTo("manage")
            assertTrue(manage.webResponse.contentAsString.contains(CnbOperationalHealthManagementLink.DISPLAY_NAME))
            assertTrue(manage.webResponse.contentAsString.contains(CnbOperationalHealthManagementLink.MONITOR_MESSAGE))
        }
    }

    @Test
    fun `administrator page is no-store and escapes every recorded field`(jenkins: JenkinsRule) {
        configureSecurity(jenkins)
        val health = CnbOperationalHealth.get()
        val serverXss = "production<script id='cnb-server-xss'>serverAttack()</script>"
        val repositoryXss = "team/<img src=x onerror=repositoryAttack()>"
        val summaryXss =
            "<script id=\"cnb-summary-xss\">summaryAttack()</script> " +
                "request https://api.cnb.cool/team/repository?token=url-secret&trace=private failed"
        health.recordWebhook(serverXss, repositoryXss, false, summaryXss)

        jenkins.createWebClient().use { admin ->
            admin.login("admin", "admin")
            val page = admin.goTo("manage/cnb-health")
            val response = page.webResponse
            val html = response.contentAsString

            assertEquals(200, response.statusCode)
            assertTrue(response.getResponseHeaderValue("Cache-Control").contains("no-store"))
            assertEquals("no-cache", response.getResponseHeaderValue("Pragma"))
            assertTrue(html.contains("webhook"))
            assertTrue(html.contains("production"))
            assertTrue(html.contains("team/"))
            assertTrue(html.contains("&lt;script"))
            assertTrue(html.contains("&lt;img"))
            assertFalse(html.contains("<script id=\"cnb-summary-xss\""))
            assertFalse(html.contains("<img src=x onerror=repositoryAttack()"))
            assertFalse(html.contains("url-secret"))
            assertFalse(html.contains("trace=private"))
        }
    }

    @Test
    fun `management link exposes only a read index web method`() {
        val webMethods =
            CnbOperationalHealthManagementLink::class.java.declaredMethods
                .map { it.name }
                .filter { it.startsWith("do") }
                .sorted()

        assertEquals(listOf("doIndex"), webMethods)
        assertEquals(Jenkins.ADMINISTER, CnbOperationalHealthManagementLink().requiredPermission)

        val view =
            requireNotNull(
                CnbOperationalHealthManagementLink::class.java.getResourceAsStream(
                    "/dev/zxilly/jenkins/cnb/ui/CnbOperationalHealthManagementLink/index.jelly",
                ),
            ).bufferedReader().use { it.readText() }
        assertTrue(view.contains("settings-subpage permission=\"\${app.ADMINISTER}\""))
        assertTrue(view.contains("title=\"\${%title}\""))
        assertTrue(view.contains("description=\"\${%description}\""))
        assertFalse(view.contains("settings-subpage permissions="))
        assertFalse(view.contains("includeBreadcrumb=\"true\""))
        assertFalse(view.contains("<h1>"))
    }

    @Test
    fun `administrative monitor rejects its inherited dismissal route`(jenkins: JenkinsRule) {
        configureSecurity(jenkins)
        CnbOperationalHealth.get().recordReporting("production", "team/repository", false, "reporting failed")
        val monitor = ExtensionList.lookupSingleton(CnbOperationalHealthMonitor::class.java)

        jenkins.createWebClient().use { admin ->
            admin.options.isThrowExceptionOnFailingStatusCode = false
            admin.login("admin", "admin")
            val endpoint =
                jenkins.url
                    .toURI()
                    .resolve("${monitor.url}/disable")
                    .toURL()
            val request = WebRequest(endpoint, HttpMethod.POST)
            val response = admin.getPage<Page>(admin.addCrumb(request)).webResponse

            assertEquals(405, response.statusCode)
            assertTrue(monitor.isActivated)
            assertTrue(monitor.isEnabled)
        }
    }

    private fun configureSecurity(jenkins: JenkinsRule) {
        jenkins.jenkins.securityRealm = jenkins.createDummySecurityRealm()
        jenkins.jenkins.authorizationStrategy =
            MockAuthorizationStrategy()
                .grant(Jenkins.READ, Jenkins.SYSTEM_READ)
                .everywhere()
                .to("reader")
                .grant(Jenkins.READ, Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
    }
}
