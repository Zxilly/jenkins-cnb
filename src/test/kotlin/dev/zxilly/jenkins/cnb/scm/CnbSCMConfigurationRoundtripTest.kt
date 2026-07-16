package dev.zxilly.jenkins.cnb.scm

import com.cloudbees.plugins.credentials.Credentials
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import hudson.util.Secret
import jenkins.branch.BranchSource
import jenkins.branch.OrganizationFolder
import jenkins.model.Jenkins
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbSCMConfigurationRoundtripTest {
    @Test
    fun `legacy SCM objects without a traits field restore secure discovery defaults`(jenkins: JenkinsRule) {
        configureServerAndCredentials()
        val sourceXml = removeTraitsField(Jenkins.XSTREAM2.toXML(CnbSCMSource(SERVER_ID, "team/repository")))
        val navigatorXml = removeTraitsField(Jenkins.XSTREAM2.toXML(CnbSCMNavigator(SERVER_ID, "team")))

        val source = Jenkins.XSTREAM2.fromXML(sourceXml) as CnbSCMSource
        val navigator = Jenkins.XSTREAM2.fromXML(navigatorXml) as CnbSCMNavigator

        assertEquals(3, source.traits.size)
        assertEquals(3, navigator.traits.size)
        assertTrue(source.traits.any { it is CnbBranchDiscoveryTrait })
        assertTrue(navigator.traits.any { it is CnbBranchDiscoveryTrait })
        assertTrue(jenkins.jenkins.isQuietingDown.not())
    }

    @Test
    fun `SCM source preserves destinations credentials and traits through configuration roundtrip`(jenkins: JenkinsRule) {
        configureServerAndCredentials()
        val project =
            jenkins.jenkins.createProject(
                WorkflowMultiBranchProject::class.java,
                "source-complete-roundtrip",
            )
        val source = CnbSCMSource(SERVER_ID, "team/repository")
        source.setApiCredentialsId(API_CREDENTIALS_ID)
        source.setCheckoutCredentialsId(CHECKOUT_CREDENTIALS_ID)
        source.setTraits(sourceTraits())
        project.sourcesList.add(BranchSource(source))

        val restoredProject = jenkins.configRoundtrip(project)

        val restored = restoredProject.sourcesList.single().source as CnbSCMSource
        assertRestoredSource(restored)

        jenkins.waitUntilNoActivity()
        jenkins.jenkins.reload()
        val restartedProject =
            requireNotNull(
                jenkins.jenkins.getItemByFullName(
                    "source-complete-roundtrip",
                    WorkflowMultiBranchProject::class.java,
                ),
            )
        assertRestoredSource(restartedProject.sourcesList.single().source as CnbSCMSource)
    }

    @Test
    fun `SCM navigator preserves every discovery option through configuration roundtrip`(jenkins: JenkinsRule) {
        configureServerAndCredentials()
        val folder = jenkins.jenkins.createProject(OrganizationFolder::class.java, "navigator-complete-roundtrip")
        val navigator = CnbSCMNavigator(SERVER_ID, "team/subgroup")
        navigator.setCredentialsId(API_CREDENTIALS_ID)
        navigator.setCheckoutCredentialsId(CHECKOUT_CREDENTIALS_ID)
        navigator.setIncludeDescendants(false)
        navigator.setIncludeArchivedRepositories(true)
        navigator.setDiscoverAllRepositories(true)
        navigator.setTraits(sourceTraits())
        folder.navigators.add(navigator)

        val restoredFolder = jenkins.configRoundtrip(folder)

        val restored = restoredFolder.navigators.single() as CnbSCMNavigator
        assertRestoredNavigator(restored)

        jenkins.waitUntilNoActivity()
        jenkins.jenkins.reload()
        val restartedFolder =
            requireNotNull(
                jenkins.jenkins.getItemByFullName(
                    "navigator-complete-roundtrip",
                    OrganizationFolder::class.java,
                ),
            )
        assertRestoredNavigator(restartedFolder.navigators.single() as CnbSCMNavigator)
    }

    private fun configureServerAndCredentials() {
        val credentials: List<Credentials> =
            listOf(
                StringCredentialsImpl(
                    CredentialsScope.GLOBAL,
                    API_CREDENTIALS_ID,
                    "CNB API token",
                    Secret.fromString("api-token"),
                ),
                UsernamePasswordCredentialsImpl(
                    CredentialsScope.GLOBAL,
                    CHECKOUT_CREDENTIALS_ID,
                    "CNB Git token",
                    "cnb",
                    "git-token",
                ),
            )
        SystemCredentialsProvider.getInstance().setDomainCredentialsMap(mapOf(Domain.global() to credentials))
        val server = CnbServer(SERVER_ID, "CNB", "http://127.0.0.1:9", "http://127.0.0.1:9")
        server.setAllowInsecureHttp(true)
        server.setAllowPrivateNetwork(true)
        CnbGlobalConfiguration.get().apply {
            setOrganizationReconciliationEnabled(false)
            setServers(listOf(server))
        }
    }

    private fun sourceTraits() =
        listOf(
            CnbBranchDiscoveryTrait(2),
            CnbTagDiscoveryTrait(),
            CnbPullRequestFilterTrait(
                includeDrafts = true,
                sourceBranchFilter = "feature/**",
                targetBranchFilter = "master",
                requiredLabels = "ready,ci",
                excludedLabels = "skip",
            ),
            CnbReportingContextTrait("ci/roundtrip"),
            CnbSkipReportingTrait(),
        )

    private fun assertRestoredSource(source: CnbSCMSource) {
        assertEquals(SERVER_ID, source.serverId)
        assertEquals("team/repository", source.repositoryPath)
        assertEquals(API_CREDENTIALS_ID, source.getApiCredentialsId())
        assertEquals(CHECKOUT_CREDENTIALS_ID, source.getCheckoutCredentialsId())
        assertEquals(CHECKOUT_CREDENTIALS_ID, source.credentialsId)
        assertRestoredTraits(source.traits)
    }

    private fun assertRestoredNavigator(navigator: CnbSCMNavigator) {
        assertEquals(SERVER_ID, navigator.serverId)
        assertEquals("team/subgroup", navigator.namespace)
        assertEquals(API_CREDENTIALS_ID, navigator.credentialsId)
        assertEquals(CHECKOUT_CREDENTIALS_ID, navigator.checkoutCredentialsId)
        assertFalse(navigator.isIncludeDescendants())
        assertTrue(navigator.isIncludeArchivedRepositories())
        assertTrue(navigator.isDiscoverAllRepositories())
        assertRestoredTraits(navigator.traits)
    }

    private fun assertRestoredTraits(traits: List<*>) {
        assertEquals(5, traits.size)
        assertEquals(2, traits.filterIsInstance<CnbBranchDiscoveryTrait>().single().strategyId)
        assertEquals(1, traits.filterIsInstance<CnbTagDiscoveryTrait>().size)
        val filter = traits.filterIsInstance<CnbPullRequestFilterTrait>().single()
        assertTrue(filter.includeDrafts)
        assertEquals("feature/**", filter.sourceBranchFilter)
        assertEquals("master", filter.targetBranchFilter)
        assertEquals("ready,ci", filter.requiredLabels)
        assertEquals("skip", filter.excludedLabels)
        assertEquals("ci/roundtrip", traits.filterIsInstance<CnbReportingContextTrait>().single().context)
        assertEquals(1, traits.filterIsInstance<CnbSkipReportingTrait>().size)
    }

    private fun removeTraitsField(xml: String): String =
        xml.replace(Regex("<configuredTraits(?: [^>]*)?>.*?</configuredTraits>", setOf(RegexOption.DOT_MATCHES_ALL)), "")

    companion object {
        private const val SERVER_ID = "cnb-cool"
        private const val API_CREDENTIALS_ID = "cnb-api"
        private const val CHECKOUT_CREDENTIALS_ID = "cnb-checkout"
    }
}
