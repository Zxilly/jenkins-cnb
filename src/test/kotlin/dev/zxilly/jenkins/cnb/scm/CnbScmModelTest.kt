package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbContent
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import hudson.model.TaskListener
import jenkins.scm.api.SCMHeadObserver
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.Base64

class CnbScmModelTest {
    @Test
    fun `pull request revision retains both immutable sides`() {
        val head = pullRequestHead("Acme/fork", ChangeRequestCheckoutStrategy.MERGE)
        val revision = CnbPullRequestSCMRevision(head, "base-sha", "head-sha", null)
        val sameHead = CnbPullRequestSCMRevision(head, "new-base", "head-sha", "merge-sha")
        val newCommit = CnbPullRequestSCMRevision(head, "base-sha", "new-head", null)

        assertEquals("base-sha", (revision.target as CnbBranchSCMRevision).hash)
        assertTrue(revision.equivalent(sameHead))
        assertFalse(revision.equivalent(newCommit))
        assertEquals("head-sha+base-sha (LOCAL_MERGE)", revision.toString())
    }

    @Test
    fun `same namespace fork trust is exact and case sensitive`() {
        val source = CnbSCMSource("cnb-cool", "/Acme/repo/")
        val request = CnbSCMSourceContext(null, SCMHeadObserver.none()).newRequest(source, TaskListener.NULL)
        request.use {
            assertTrue(checkTrust(TrustSameNamespace(), request, pullRequestHead("Acme/fork")))
            assertFalse(checkTrust(TrustSameNamespace(), request, pullRequestHead("acme/fork")))
            assertFalse(checkTrust(TrustNobody(), request, pullRequestHead("Acme/fork")))
        }
        assertEquals("Acme/repo", source.repositoryPath)
    }

    @Test
    fun `SCM file decodes fixed revision base64 content`() {
        val payload = Base64.getEncoder().encodeToString("pipeline { agent any }".toByteArray())
        val client =
            fakeClient(
                CnbContent("Jenkinsfile", "blob-sha", "file", payload.length.toLong(), payload, "base64"),
            )
        val file = CnbSCMFile.root(client, "Acme/repo", "commit-sha").child("Jenkinsfile")

        assertTrue(file.isFile)
        assertEquals("pipeline { agent any }", file.contentAsString())
    }

    @Test
    fun `secret repositories are rejected by every source consumer`() {
        val source = CnbSCMSource("cnb-cool", "Acme/repo")
        val secret =
            CnbRepository(
                path = "Acme/repo",
                name = "repo",
                webUrl = "https://cnb.cool/Acme/repo",
                cloneUrl = "https://cnb.cool/Acme/repo",
                defaultBranch = "main",
                archived = false,
                visibility = "Secret",
            )

        assertThrows(IOException::class.java) { source.remember(secret) }
    }

    @Test
    fun `discovery traits reject invalid strategy IDs`() {
        listOf(0, 4).forEach { strategyId ->
            assertThrows(IllegalArgumentException::class.java) { CnbBranchDiscoveryTrait(strategyId) }
            assertThrows(IllegalArgumentException::class.java) { CnbOriginPullRequestDiscoveryTrait(strategyId) }
            assertThrows(IllegalArgumentException::class.java) {
                CnbForkPullRequestDiscoveryTrait(strategyId, TrustNobody())
            }
        }
    }

    @Test
    fun `navigator rejects an empty namespace at the data bound constructor`() {
        assertThrows(IllegalArgumentException::class.java) { CnbSCMNavigator("cnb-cool", " / ") }
    }

    @Test
    fun `SCM file system resolves current pull request HEAD through the API`() {
        val pullRequest = pullRequest(mergeSha = null)
        val fileSystem =
            TestFileSystemBuilder(fileSystemClient(pullRequest))
                .build(
                    CnbSCMSource("cnb-cool", "Acme/repo"),
                    pullRequestHead("Acme/fork", ChangeRequestCheckoutStrategy.HEAD),
                    null,
                )

        assertNotNull(fileSystem)
        val revision = fileSystem!!.revision as CnbPullRequestSCMRevision
        assertEquals(pullRequest.sourceSha, revision.headHash)
        assertEquals(pullRequest.targetSha, revision.baseHash)
        fileSystem.close()
    }

    @Test
    fun `SCM file system uses merge revision only when CNB provides one`() {
        val merged = pullRequest(mergeSha = "c".repeat(40))
        val mergedFileSystem =
            TestFileSystemBuilder(fileSystemClient(merged))
                .build(
                    CnbSCMSource("cnb-cool", "Acme/repo"),
                    pullRequestHead("Acme/fork", ChangeRequestCheckoutStrategy.MERGE),
                    null,
                )

        assertNotNull(mergedFileSystem)
        assertEquals(merged.mergeSha, (mergedFileSystem!!.revision as CnbPullRequestSCMRevision).mergeHash)
        mergedFileSystem.close()

        val closeCount = intArrayOf(0)
        val unavailable = pullRequest(mergeSha = null)
        val unavailableFileSystem =
            TestFileSystemBuilder(fileSystemClient(unavailable, closeCount))
                .build(
                    CnbSCMSource("cnb-cool", "Acme/repo"),
                    pullRequestHead("Acme/fork", ChangeRequestCheckoutStrategy.MERGE),
                    null,
                )

        assertNull(unavailableFileSystem)
        assertEquals(1, closeCount.single())
    }

    @Test
    fun `targeted pull request identity rejects every cached-head identity change`() {
        val head = pullRequestHead("Acme/fork")
        val original = pullRequest(mergeSha = null)
        val changes =
            listOf(
                original.copy(number = "43"),
                original.copy(targetRepo = "Acme/other"),
                original.copy(targetBranch = "release"),
                original.copy(sourceRepo = "Acme/other-fork"),
                original.copy(sourceBranch = "other-feature"),
            )

        changes.forEach { changed ->
            assertThrows(IOException::class.java) {
                CnbPullRequestIdentity.requireMatches(changed, "Acme/repo", head)
            }
        }
    }

    @Test
    fun `trusted merge criteria falls back from source HEAD to immutable target base`() {
        val calls = mutableListOf<Triple<String, String, String>>()
        val head =
            CnbPullRequestSCMHead(
                "PR-42",
                "42",
                CnbBranchSCMHead("main"),
                ChangeRequestCheckoutStrategy.MERGE,
                SCMHeadOrigin.DEFAULT,
                "Acme/repo",
                "feature",
                "contributor",
                "Change",
            )
        val revision = CnbPullRequestSCMRevision(head, "base-sha", "head-sha", null)
        val source = ProbeSource(criteriaClient(calls, targetHasJenkinsfile = true, sourceHasJenkinsfile = false))

        source.openProbe(head, revision).use { probe ->
            assertTrue(probe.stat("Jenkinsfile").exists())
        }

        assertEquals(
            listOf(
                Triple("Acme/repo", "Jenkinsfile", "head-sha"),
                Triple("Acme/repo", "Jenkinsfile", "base-sha"),
            ),
            calls,
        )
    }

    private fun pullRequestHead(
        sourceRepository: String,
        strategy: ChangeRequestCheckoutStrategy = ChangeRequestCheckoutStrategy.HEAD,
    ) = CnbPullRequestSCMHead(
        "PR-42-${strategy.name.lowercase()}",
        "42",
        CnbBranchSCMHead("main"),
        strategy,
        SCMHeadOrigin.Fork(sourceRepository),
        sourceRepository,
        "feature",
        "contributor",
        "Change",
    )

    private fun checkTrust(
        policy: CnbForkTrustPolicy,
        request: CnbSCMSourceRequest,
        head: CnbPullRequestSCMHead,
    ): Boolean {
        val method =
            policy.javaClass.getDeclaredMethod(
                "checkTrusted",
                CnbSCMSourceRequest::class.java,
                CnbPullRequestSCMHead::class.java,
            )
        method.isAccessible = true
        return method.invoke(policy, request, head) as Boolean
    }

    private fun fakeClient(content: CnbContent): CnbClient =
        Proxy.newProxyInstance(
            CnbClient::class.java.classLoader,
            arrayOf(CnbClient::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getCapabilities" -> CnbApiCapabilities()
                "getContent" -> if (arguments?.get(1) == content.path) content else null
                "close" -> Unit
                "toString" -> "FakeCnbClient"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as CnbClient

    private class TestFileSystemBuilder(
        private val api: CnbClient,
    ) : CnbSCMFileSystem.BuilderImpl() {
        override fun createClient(source: CnbSCMSource): CnbClient = api
    }

    private class ProbeSource(
        private val api: CnbClient,
    ) : CnbSCMSource("cnb-cool", "Acme/repo") {
        override fun client(): CnbClient = api

        fun openProbe(
            head: CnbPullRequestSCMHead,
            revision: CnbPullRequestSCMRevision,
        ): CnbSCMProbe = createProbe(head, revision)
    }

    private fun criteriaClient(
        calls: MutableList<Triple<String, String, String>>,
        targetHasJenkinsfile: Boolean,
        sourceHasJenkinsfile: Boolean,
    ): CnbClient {
        val repository = publicRepository("Acme/repo", "https://cnb.cool/Acme/repo")
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
                            ref == "base-sha" -> targetHasJenkinsfile
                            repo == "Acme/fork" && ref == "head-sha" -> sourceHasJenkinsfile
                            repo == "Acme/repo" && ref == "head-sha" -> sourceHasJenkinsfile
                            else -> false
                        }
                    if (exists && path == "Jenkinsfile") {
                        CnbContent(path, "blob-sha", "file", 1, "x", "text")
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

    private fun fileSystemClient(
        pullRequest: CnbPullRequest,
        closeCount: IntArray = intArrayOf(0),
    ): CnbClient {
        val repository = publicRepository("Acme/repo", "https://cnb.cool/Acme/repo")
        return Proxy.newProxyInstance(
            CnbClient::class.java.classLoader,
            arrayOf(CnbClient::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getRepository" -> {
                    repository
                }

                "getPullRequest" -> {
                    pullRequest
                }

                "close" -> {
                    closeCount[0]++
                    Unit
                }

                "toString" -> {
                    "FileSystemClient"
                }

                else -> {
                    throw UnsupportedOperationException(method.name)
                }
            }
        } as CnbClient
    }

    private fun pullRequest(mergeSha: String?): CnbPullRequest =
        CnbPullRequest(
            number = "42",
            title = "Change",
            state = "open",
            sourceRepo = "Acme/fork",
            sourceBranch = "feature",
            sourceSha = "a".repeat(40),
            targetRepo = "Acme/repo",
            targetBranch = "main",
            targetSha = "b".repeat(40),
            mergeSha = mergeSha,
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
        archived = false,
        visibility = "Public",
    )
}
