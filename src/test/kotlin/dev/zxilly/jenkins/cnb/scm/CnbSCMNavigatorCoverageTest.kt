package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryStatus
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryVisibility
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import hudson.model.TaskListener
import hudson.util.FormValidation
import jenkins.branch.OrganizationFolder
import jenkins.scm.api.SCMSource
import jenkins.scm.api.SCMSourceObserver
import jenkins.scm.api.SCMSourceOwner
import jenkins.scm.api.metadata.ObjectMetadataAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.lang.reflect.Proxy

@WithJenkins
class CnbSCMNavigatorCoverageTest {
    @Test
    fun `namespace scan filters unsafe repositories and builds configured sources`(jenkins: JenkinsRule) {
        val folder = jenkins.jenkins.createProject(OrganizationFolder::class.java, "team")
        val fixture = NavigatorFixture()
        val navigator = TestNavigator { fixture.client() }
        navigator.setCredentialsId(" api ")
        navigator.setCheckoutCredentialsId(" checkout ")
        navigator.setTraits(listOf(CnbTagDiscoveryTrait()))
        val observer = RecordingObserver(folder)

        navigator.visitSources(observer)

        assertEquals(listOf("team" to true), fixture.listCalls)
        assertEquals(1, fixture.closeCount)
        assertEquals(2, observer.projects.size)
        val sources =
            observer.projects.values
                .flatten()
                .map { it as CnbSCMSource }
        assertEquals(setOf("team/repo", "team/sub/nested"), sources.mapTo(linkedSetOf()) { it.repositoryPath })
        assertTrue(sources.all { it.getApiCredentialsId() == "api" && it.getCheckoutCredentialsId() == "checkout" })
        assertTrue(sources.all { it.traits.single() is CnbTagDiscoveryTrait })
        assertEquals(sources.size, sources.mapTo(hashSetOf()) { it.id }.size)
        assertTrue(sources.all { it.id.endsWith("::${it.repositoryPath}") })

        navigator.setIncludeArchivedRepositories(true)
        navigator.setIncludeDescendants(false)
        val second = RecordingObserver(folder)
        navigator.visitSources(second)
        assertEquals("team" to false, fixture.listCalls.last())
        assertTrue(
            second.projects.values
                .flatten()
                .map { (it as CnbSCMSource).repositoryPath }
                .contains("team/archived"),
        )
        assertFalse(
            second.projects.values
                .flatten()
                .map { (it as CnbSCMSource).repositoryPath }
                .contains("team/secret"),
        )
    }

    @Test
    fun `navigator identity and project names are normalized collision resistant and defensive`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val navigator = TestNavigator { NavigatorFixture().client() }
        navigator.setCredentialsId("  ")
        navigator.setCheckoutCredentialsId(" git ")
        navigator.setTraits(null)

        assertEquals("cnb-cool::team", navigator.navigatorId())
        assertNullSafe(navigator.credentialsId)
        assertEquals("git", navigator.checkoutCredentialsId)
        assertTrue(navigator.traits.isEmpty())
        assertNotEquals(CnbSCMNavigator.projectNameFor("one/repo"), CnbSCMNavigator.projectNameFor("two/repo"))
        assertEquals(CnbSCMNavigator.projectNameFor(" team/repo/ "), CnbSCMNavigator.projectNameFor("team/repo"))
        assertThrows(IllegalArgumentException::class.java) { CnbSCMNavigator(" ", "team") }
        assertThrows(IllegalArgumentException::class.java) { CnbSCMNavigator("cnb", "team/../repo") }
    }

    @Test
    fun `navigator can discover every repository visible to its credentials`(jenkins: JenkinsRule) {
        CnbGlobalConfiguration.get().setServers(
            listOf(CnbServer("cnb-cool", "CNB", "https://cnb.cool", "https://api.cnb.cool")),
        )
        val folder = jenkins.jenkins.createProject(OrganizationFolder::class.java, "all-repositories")
        val fixture = NavigatorFixture()
        val navigator = TestNavigator("", fixture::client)
        navigator.setDiscoverAllRepositories(true)
        val observer = RecordingObserver(folder)

        navigator.visitSources(observer)

        assertEquals(1, fixture.userRepositoryCalls)
        assertTrue(fixture.listCalls.isEmpty())
        assertEquals("cnb-cool::*", navigator.navigatorId())
        assertEquals(2, observer.projects.size)
        val action = navigator.fetchActions(folder, null, TaskListener.NULL).single() as ObjectMetadataAction
        assertEquals("Accessible repositories", action.objectDisplayName)
        assertEquals("https://cnb.cool", action.objectUrl)
    }

    @Test
    fun `navigator descriptor and metadata actions expose configured namespace`(jenkins: JenkinsRule) {
        val server = CnbServer("primary", "Primary", "https://cnb.cool", "https://api.cnb.cool")
        CnbGlobalConfiguration.get().setServers(listOf(server))
        val descriptor = CnbSCMNavigator.DescriptorImpl()
        val navigator = descriptor.newInstance("team") as CnbSCMNavigator
        val folder = jenkins.jenkins.createProject(OrganizationFolder::class.java, "owner")
        val action = navigator.fetchActions(folder, null, TaskListener.NULL).single() as ObjectMetadataAction

        assertEquals("CNB namespace", descriptor.displayName)
        assertEquals("Namespace", descriptor.pronoun)
        assertEquals("Scans a CNB namespace for repositories.", descriptor.description)
        assertEquals("symbol-organization plugin-ionicons-api", descriptor.iconClassName)
        assertEquals("primary", navigator.serverId)
        assertEquals("team", action.objectDisplayName)
        assertEquals("https://cnb.cool/team", action.objectUrl)
        assertEquals(listOf("primary", "legacy"), descriptor.doFillServerIdItems("legacy").map { it.value })
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckServerId(null, "primary").kind)
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckServerId(null, "missing").kind)
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckNamespace(null, "team/sub", false).kind)
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckNamespace(null, "team//sub", false).kind)
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckNamespace(null, "", true).kind)
        assertTrue(descriptor.traitsDefaults.isNotEmpty())
        assertEquals(descriptor.traitDescriptors, descriptor.traitsDescriptors)
        assertTrue(descriptor.doFillCredentialsIdItems(null, "primary", null).any { it.value.isEmpty() })
        assertTrue(descriptor.doFillCheckoutCredentialsIdItems(null, "primary", null).any { it.value.isEmpty() })
    }

    private class TestNavigator(
        namespace: String = "team",
        private val clientFactory: () -> CnbClient,
    ) : CnbSCMNavigator("cnb-cool", namespace) {
        override fun client(context: hudson.model.Item?): CnbClient = clientFactory()

        fun navigatorId(): String = id()
    }

    private class NavigatorFixture {
        var closeCount = 0
        var userRepositoryCalls = 0
        val listCalls = mutableListOf<Pair<String, Boolean>>()

        fun client(): CnbClient =
            Proxy.newProxyInstance(
                CnbClient::class.java.classLoader,
                arrayOf(CnbClient::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "listRepositories" -> {
                        listCalls += (arguments!![0] as String) to (arguments[1] as Boolean)
                        repositories
                    }

                    "listUserRepositories" -> {
                        userRepositoryCalls++
                        repositories
                    }

                    "close" -> {
                        closeCount++
                        Unit
                    }

                    "toString" -> {
                        "Navigator fixture client"
                    }

                    "hashCode" -> {
                        System.identityHashCode(proxy)
                    }

                    "equals" -> {
                        proxy === arguments?.firstOrNull()
                    }

                    else -> {
                        throw UnsupportedOperationException(method.name)
                    }
                }
            } as CnbClient

        companion object {
            private val repositories =
                listOf(
                    repository("team/repo"),
                    repository("team/sub/nested"),
                    repository("team/archived", archived = true),
                    repository("team/secret", visibility = "Secret"),
                )

            private fun repository(
                path: String,
                archived: Boolean = false,
                visibility: String = "Public",
            ) = CnbRepository(
                path,
                path.substringAfterLast('/'),
                "https://cnb.cool/$path",
                "https://cnb.cool/$path",
                "main",
                if (archived) CnbRepositoryStatus.ARCHIVED else CnbRepositoryStatus.OK,
                CnbRepositoryVisibility.entries.first { it.wireValue == visibility },
            )
        }
    }

    private class RecordingObserver(
        private val owner: SCMSourceOwner,
    ) : SCMSourceObserver() {
        val projects = linkedMapOf<String, List<SCMSource>>()

        override fun getContext(): SCMSourceOwner = owner

        override fun getListener(): TaskListener = TaskListener.NULL

        override fun observe(projectName: String): ProjectObserver =
            object : ProjectObserver() {
                private val sources = arrayListOf<SCMSource>()

                override fun addSource(source: SCMSource) {
                    sources += source
                }

                override fun addAttribute(
                    key: String,
                    value: Any?,
                ) = Unit

                override fun complete() {
                    projects[projectName] = sources.toList()
                }
            }

        override fun addAttribute(
            key: String,
            value: Any?,
        ) = Unit
    }

    companion object {
        private fun assertNullSafe(value: Any?) = assertTrue(value == null)
    }
}
