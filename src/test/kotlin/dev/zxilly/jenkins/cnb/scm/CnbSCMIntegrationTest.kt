package dev.zxilly.jenkins.cnb.scm

import com.cloudbees.plugins.credentials.Credentials
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbContent
import dev.zxilly.jenkins.cnb.api.model.CnbContentType
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestState
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryStatus
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryVisibility
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import hudson.model.TaskListener
import hudson.plugins.git.GitChangeSet
import hudson.plugins.git.GitSCM
import jenkins.plugins.git.MergeWithGitSCMExtension
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.io.IOException
import java.lang.reflect.Proxy

@WithJenkins
class CnbSCMIntegrationTest {
    @Test
    fun `API and Git credentials remain separate`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source = CnbSCMSource("cnb-cool", "acme/repo")

        source.setApiCredentialsId("api-token")
        source.setCheckoutCredentialsId("git-token")

        assertEquals("api-token", source.getApiCredentialsId())
        assertEquals("git-token", source.credentialsId)
        assertEquals("git-token", source.getCheckoutCredentialsId())
    }

    @Test
    fun `named revision APIs preserve CNB revisions and discovery traits`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source = TestSource(fakeBranchClient())
        source.setTraits(listOf(CnbBranchDiscoveryTrait(3)))

        val revision = source.fetch("main", TaskListener.NULL, null)
        val names = source.fetchRevisions(TaskListener.NULL, null)

        assertInstanceOf(CnbBranchSCMRevision::class.java, revision)
        assertEquals("a".repeat(40), (revision as CnbBranchSCMRevision).hash)
        assertEquals(setOf("main"), names)
    }

    @Test
    fun `default traits discover branches`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source = TestSource(fakeBranchClient())

        assertEquals(setOf("main"), source.fetchRevisions(TaskListener.NULL, null))
    }

    @Test
    fun `trusted fork revision checks out the target repository`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source = CnbSCMSource("cnb-cool", "acme/repo")
        val head = pullRequestHead(ChangeRequestCheckoutStrategy.HEAD)
        val trusted = CnbBranchSCMRevision(head.target, "b".repeat(40))

        val scm = source.build(head, trusted) as GitSCM

        assertEquals(listOf("https://cnb.cool/acme/repo"), scm.userRemoteConfigs.map { it.url })
        assertEquals(listOf("main"), scm.branches.map { it.name })
        assertTrue(
            scm.userRemoteConfigs
                .single()
                .refspec
                .contains("refs/heads/main"),
        )
        assertTrue(scm.extensions.none { it is MergeWithGitSCMExtension })
    }

    @Test
    fun `merge strategy without a fixed revision still installs local merge`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source = CnbSCMSource("cnb-cool", "acme/repo")
        val head = pullRequestHead(ChangeRequestCheckoutStrategy.MERGE)

        val scm = source.build(head, null) as GitSCM
        val merge = scm.extensions.filterIsInstance<MergeWithGitSCMExtension>().single()

        assertEquals(listOf("origin", "upstream"), scm.userRemoteConfigs.map { it.name })
        assertEquals("remotes/upstream/main", merge.baseName)
        assertNull(merge.baseHash)
    }

    @Test
    fun `merge revision without server merge hash checks out source and locally merges fixed base`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source = CnbSCMSource("cnb-cool", "acme/repo")
        val head = pullRequestHead(ChangeRequestCheckoutStrategy.MERGE)
        val revision = CnbPullRequestSCMRevision(head, "b".repeat(40), "a".repeat(40), null)

        val scm = source.build(head, revision) as GitSCM
        val merge = scm.extensions.filterIsInstance<MergeWithGitSCMExtension>().single()
        val browser = assertInstanceOf(CnbRepositoryBrowser::class.java, scm.browser)

        assertEquals(
            listOf("https://cnb.cool/contributor/repo", "https://cnb.cool/acme/repo"),
            scm.userRemoteConfigs.map { it.url },
        )
        assertEquals("remotes/upstream/main", merge.baseName)
        assertEquals("b".repeat(40), merge.baseHash)
        assertEquals("PR-42", scm.branches.single().name)
        assertEquals(
            "https://cnb.cool/contributor/repo/-/commit/${"a".repeat(40)}",
            browser.getChangeSetLink(GitChangeSet(listOf("commit ${"a".repeat(40)}"), true)).toExternalForm(),
        )
        assertEquals(
            "https://cnb.cool/acme/repo/-/pulls/42",
            browser.pullRequestLink("42").toExternalForm(),
        )
    }

    @Test
    fun `targeted lookup only converts HTTP 404 to a missing head`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source = CnbSCMSource("cnb-cool", "acme/repo")

        assertTrue(source.branchesFor(failingBranchClient(404), "acme/repo", setOf("main")).isEmpty())
        val failure =
            assertThrows(CnbApiException::class.java) {
                source.branchesFor(failingBranchClient(503), "acme/repo", setOf("main"))
            }
        assertEquals(503, failure.statusCode)
    }

    @Test
    fun `missing explicit checkout credential fails before Git checkout`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source = CnbSCMSource("cnb-cool", "acme/repo")
        source.setCheckoutCredentialsId("missing")

        assertThrows(IllegalArgumentException::class.java) {
            source.build(CnbBranchSCMHead("main"), null)
        }
    }

    @Test
    fun `checkout credential must use the CNB Git username`(jenkins: JenkinsRule) {
        val credential: Credentials =
            UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL,
                "wrong-user",
                "",
                "someone-else",
                "token",
            )
        SystemCredentialsProvider.getInstance().setDomainCredentialsMap(
            mapOf(Domain.global() to listOf(credential)),
        )
        val source = CnbSCMSource("cnb-cool", "acme/repo")
        source.setCheckoutCredentialsId("wrong-user")

        assertThrows(IllegalArgumentException::class.java) {
            source.build(CnbBranchSCMHead("main"), null)
        }
    }

    @Test
    fun `API credential with a non-CNB username is not reused for Git checkout`(jenkins: JenkinsRule) {
        val credential: Credentials =
            UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL,
                "wrong-api-user",
                "",
                "someone-else",
                "token",
            )
        SystemCredentialsProvider.getInstance().setDomainCredentialsMap(
            mapOf(Domain.global() to listOf(credential)),
        )
        val source = CnbSCMSource("cnb-cool", "acme/repo")
        source.setApiCredentialsId("wrong-api-user")

        val scm = source.build(CnbBranchSCMHead("main"), null) as GitSCM

        assertNull(scm.userRemoteConfigs.single().credentialsId)
    }

    @Test
    fun `repository clone URL cannot redirect checkout credentials to another repository`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source = CnbSCMSource("cnb-cool", "Acme/repo")
        source.remember(publicRepository("Acme/repo", "https://cnb.cool/Acme/other.git"))

        assertThrows(IllegalArgumentException::class.java) { source.targetCloneUrl() }
    }

    @Test
    fun `repository clone URL accepts percent encoded path and optional git suffix`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source = CnbSCMSource("cnb-cool", "Acme/项目")
        source.remember(publicRepository("Acme/项目", "https://cnb.cool/Acme/%E9%A1%B9%E7%9B%AE.git"))

        assertEquals("https://cnb.cool/Acme/%E9%A1%B9%E7%9B%AE.git", source.targetCloneUrl())
    }

    @Test
    fun `targeted pull request fetch rejects retargeted cached heads`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val original = pullRequest()
        val changes =
            listOf(
                original.copy(number = "43"),
                original.copy(targetRepo = "acme/other"),
                original.copy(targetBranch = "release"),
                original.copy(sourceRepo = "other/repo"),
                original.copy(sourceBranch = "other-feature"),
            )

        changes.forEach { changed ->
            val source = TestSource(fakePullRequestClient(changed))
            assertThrows(IOException::class.java) {
                source.fetchHead(pullRequestHead(ChangeRequestCheckoutStrategy.HEAD))
            }
        }
    }

    @Test
    fun `targeted pull request fetch treats closed head as missing`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source = TestSource(fakePullRequestClient(pullRequest().copy(state = CnbPullRequestState.MERGED)))

        assertNull(source.fetchHead(pullRequestHead(ChangeRequestCheckoutStrategy.HEAD)))
    }

    @Test
    fun `HTTP web origin only permits default HTTPS clone port`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        configureServer("http-web", "http://cnb.cool:8080", allowInsecureHttp = true)
        val source = CnbSCMSource("http-web", "Acme/repo")
        source.remember(publicRepository("Acme/repo", "https://cnb.cool:8443/Acme/repo.git"))

        assertThrows(IllegalArgumentException::class.java) { source.targetCloneUrl() }

        source.remember(publicRepository("Acme/repo", ""))
        assertEquals("https://cnb.cool/Acme/repo", source.targetCloneUrl())
    }

    @Test
    fun `HTTPS web origin permits only its effective clone port`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        configureServer("https-web", "https://cnb.cool:8443")
        val source = CnbSCMSource("https-web", "Acme/repo")
        source.remember(publicRepository("Acme/repo", "https://cnb.cool:8443/Acme/repo.git"))

        assertEquals("https://cnb.cool:8443/Acme/repo.git", source.targetCloneUrl())

        source.remember(publicRepository("Acme/repo", "https://cnb.cool/Acme/repo.git"))
        assertThrows(IllegalArgumentException::class.java) { source.targetCloneUrl() }
    }

    @Test
    fun `untrusted fork criteria never reads the source Jenkinsfile`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val calls = mutableListOf<Triple<String, String, String>>()
        val head = pullRequestHead(ChangeRequestCheckoutStrategy.MERGE)
        val revision = CnbPullRequestSCMRevision(head, "b".repeat(40), "a".repeat(40), null)
        val source = ProbeSource(criteriaClient(calls, targetHasJenkinsfile = true, sourceHasJenkinsfile = true))

        source.openProbe(head, revision).use { probe ->
            assertTrue(probe.stat("Jenkinsfile").exists())
        }

        assertEquals(listOf(Triple("acme/repo", "Jenkinsfile", "b".repeat(40))), calls)
    }

    @Test
    fun `explicitly trusted fork merge uses target fallback`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val calls = mutableListOf<Triple<String, String, String>>()
        val head = pullRequestHead(ChangeRequestCheckoutStrategy.MERGE)
        val revision = CnbPullRequestSCMRevision(head, "b".repeat(40), "a".repeat(40), null)
        val source = ProbeSource(criteriaClient(calls, targetHasJenkinsfile = true, sourceHasJenkinsfile = false))
        source.setTraits(listOf(CnbForkPullRequestDiscoveryTrait(1, TrustEveryone())))

        source.openProbe(head, revision).use { probe ->
            assertTrue(probe.stat("Jenkinsfile").exists())
        }

        assertEquals(
            listOf(
                Triple("contributor/repo", "Jenkinsfile", "a".repeat(40)),
                Triple("acme/repo", "Jenkinsfile", "b".repeat(40)),
            ),
            calls,
        )
    }

    private fun pullRequestHead(strategy: ChangeRequestCheckoutStrategy): CnbPullRequestSCMHead =
        CnbPullRequestSCMHead(
            "PR-42",
            "42",
            CnbBranchSCMHead("main"),
            strategy,
            SCMHeadOrigin.Fork("contributor/repo"),
            "contributor/repo",
            "feature",
            "contributor",
            "Change",
        )

    private class TestSource(
        private val api: CnbClient,
    ) : CnbSCMSource("cnb-cool", "acme/repo") {
        override fun client(): CnbClient = api

        fun fetchHead(head: CnbPullRequestSCMHead) = retrieve(head, TaskListener.NULL)
    }

    private class ProbeSource(
        private val api: CnbClient,
    ) : CnbSCMSource("cnb-cool", "acme/repo") {
        override fun client(): CnbClient = api

        fun openProbe(
            head: CnbPullRequestSCMHead,
            revision: CnbPullRequestSCMRevision,
        ): CnbSCMProbe = createProbe(head, revision)
    }

    private fun configureServer(
        id: String,
        webUrl: String,
        allowInsecureHttp: Boolean = false,
    ) {
        val server = CnbServer(id, id, webUrl, "https://api.cnb.cool")
        server.setAllowInsecureHttp(allowInsecureHttp)
        CnbGlobalConfiguration.get().setServers(listOf(server))
    }

    private fun fakeBranchClient(): CnbClient {
        val repository =
            CnbRepository(
                path = "acme/repo",
                name = "repo",
                webUrl = "https://cnb.cool/acme/repo",
                cloneUrl = "https://cnb.cool/acme/repo",
                defaultBranch = "main",
                status = CnbRepositoryStatus.OK,
                visibility = CnbRepositoryVisibility.PUBLIC,
            )
        val branch = CnbBranch("main", "a".repeat(40))
        return Proxy.newProxyInstance(
            CnbClient::class.java.classLoader,
            arrayOf(CnbClient::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getCapabilities" -> CnbApiCapabilities()
                "getRepository" -> repository
                "getBranch" -> branch.takeIf { arguments?.get(1) == branch.name }
                "listBranches" -> listOf(branch)
                "listPullRequests" -> emptyList<Any>()
                "close" -> Unit
                "toString" -> "FakeCnbClient"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as CnbClient
    }

    private fun failingBranchClient(status: Int): CnbClient =
        object : CnbClient by fakeBranchClient() {
            override fun getBranch(
                repo: String,
                name: String,
            ): CnbBranch = throw CnbApiException("failure", status)
        }

    private fun fakePullRequestClient(pullRequest: CnbPullRequest): CnbClient {
        val repository = publicRepository("acme/repo", "https://cnb.cool/acme/repo")
        return Proxy.newProxyInstance(
            CnbClient::class.java.classLoader,
            arrayOf(CnbClient::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCapabilities" -> CnbApiCapabilities()
                "getRepository" -> repository
                "getPullRequest" -> pullRequest
                "getBranch" -> CnbBranch("main", pullRequest.targetSha)
                "close" -> Unit
                "toString" -> "PullRequestClient"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as CnbClient
    }

    private fun criteriaClient(
        calls: MutableList<Triple<String, String, String>>,
        targetHasJenkinsfile: Boolean,
        sourceHasJenkinsfile: Boolean,
    ): CnbClient {
        val repository = publicRepository("acme/repo", "https://cnb.cool/acme/repo")
        return Proxy.newProxyInstance(
            CnbClient::class.java.classLoader,
            arrayOf(CnbClient::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getCapabilities" -> {
                    CnbApiCapabilities()
                }

                "getRepository" -> {
                    repository
                }

                "getContent" -> {
                    val repo = arguments!![0] as String
                    val path = arguments[1] as String
                    val ref = arguments[2] as String
                    calls.add(Triple(repo, path, ref))
                    val exists =
                        when {
                            ref == "b".repeat(40) -> targetHasJenkinsfile
                            repo == "contributor/repo" && ref == "a".repeat(40) -> sourceHasJenkinsfile
                            else -> false
                        }
                    if (exists && path == "Jenkinsfile") {
                        CnbContent(path, "blob-sha", CnbContentType.BLOB, 1, "x")
                    } else {
                        null
                    }
                }

                "close" -> {
                    Unit
                }

                "toString" -> {
                    "CriteriaClient"
                }

                else -> {
                    throw UnsupportedOperationException(method.name)
                }
            }
        } as CnbClient
    }

    private fun pullRequest(): CnbPullRequest =
        CnbPullRequest(
            number = "42",
            title = "Change",
            state = CnbPullRequestState.OPEN,
            sourceRepo = "contributor/repo",
            sourceBranch = "feature",
            sourceSha = "a".repeat(40),
            targetRepo = "acme/repo",
            targetBranch = "main",
            targetSha = "b".repeat(40),
            author = "contributor",
        )

    private fun publicRepository(
        path: String,
        cloneUrl: String,
    ) = CnbRepository(
        path = path,
        name = path.substringAfterLast('/'),
        webUrl = "https://cnb.cool/$path",
        cloneUrl = cloneUrl,
        defaultBranch = "main",
        status = CnbRepositoryStatus.OK,
        visibility = CnbRepositoryVisibility.PUBLIC,
    )
}
