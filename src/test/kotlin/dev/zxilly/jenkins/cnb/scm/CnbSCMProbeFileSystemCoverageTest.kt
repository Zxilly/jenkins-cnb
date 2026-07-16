package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbContent
import dev.zxilly.jenkins.cnb.api.model.CnbContentEncoding
import dev.zxilly.jenkins.cnb.api.model.CnbContentEntry
import dev.zxilly.jenkins.cnb.api.model.CnbContentType
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestState
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryStatus
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryVisibility
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import hudson.scm.NullSCM
import jenkins.scm.api.SCMFile
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.Base64

class CnbSCMProbeFileSystemCoverageTest {
    @Test
    fun `owned fallback probe exposes metadata and closes its client exactly once`() {
        val calls = mutableListOf<Triple<String, String, String>>()
        var closeCount = 0
        val client =
            client { method, arguments ->
                when (method) {
                    "getContent" -> {
                        val repository = arguments[0] as String
                        val path = arguments[1] as String
                        val ref = arguments[2] as String
                        calls += Triple(repository, path, ref)
                        if (repository == "target/repo" && path == "Jenkinsfile") {
                            CnbContent(path, "blob", CnbContentType.BLOB, 4, "echo")
                        } else {
                            null
                        }
                    }

                    "close" -> {
                        closeCount++
                        Unit
                    }

                    else -> {
                        unsupported(method)
                    }
                }
            }
        val revision = CnbBranchSCMRevision(CnbBranchSCMHead("main"), "b".repeat(40))
        val probe =
            CnbSCMProbe.ownedWithFallback(
                client,
                "fork/repo",
                "head",
                "target/repo",
                "base",
                "PR-7",
                revision,
                1234,
            )

        assertEquals("PR-7", probe.name())
        assertEquals(1234, probe.lastModified())
        assertEquals(SCMFile.Type.REGULAR_FILE, probe.stat("/Jenkinsfile/").type)
        assertNotNull(probe.root)
        assertEquals(
            listOf(
                Triple("fork/repo", "Jenkinsfile", "head"),
                Triple("target/repo", "Jenkinsfile", "base"),
            ),
            calls,
        )

        probe.close()
        probe.close()

        assertEquals(1, closeCount)
        assertNull(probe.root)
        assertThrows(IOException::class.java) { probe.stat("Jenkinsfile") }
    }

    @Test
    fun `shared probe normalizes Windows paths without owning its scan client`() {
        var closeCount = 0
        val client =
            client { method, arguments ->
                when (method) {
                    "getContent" -> {
                        assertEquals("dir/file", arguments[1])
                        CnbContent("dir/file", "link", CnbContentType.LINK, 0, "target")
                    }

                    "close" -> {
                        closeCount++
                        Unit
                    }

                    else -> {
                        unsupported(method)
                    }
                }
            }
        val probe = CnbSCMProbe.shared(client, "team/repo", "sha", "main", null)

        assertEquals(SCMFile.Type.LINK, probe.stat("\\dir\\file\\").type)
        probe.close()

        assertEquals(0, closeCount)
    }

    @Test
    fun `SCM file overlays directory entries and prefers primary file content`() {
        val primaryRoot =
            CnbContent(
                "",
                "primary-tree",
                CnbContentType.TREE,
                0,
                entries =
                    listOf(
                        CnbContentEntry("", "primary.txt", "p", CnbContentType.BLOB, 7),
                        CnbContentEntry("shared.txt", "shared.txt", "p2", CnbContentType.BLOB, 7),
                    ),
            )
        val fallbackRoot =
            CnbContent(
                "",
                "fallback-tree",
                CnbContentType.TREE,
                0,
                entries =
                    listOf(
                        CnbContentEntry("fallback.txt", "fallback.txt", "f", CnbContentType.BLOB, 8),
                        CnbContentEntry("shared.txt", "shared.txt", "f2", CnbContentType.BLOB, 8),
                    ),
            )
        val client =
            client { method, arguments ->
                if (method != "getContent") unsupported(method)
                val repository = arguments[0] as String
                val path = arguments[1] as String
                when (repository to path) {
                    "primary/repo" to "" -> primaryRoot
                    "fallback/repo" to "" -> fallbackRoot
                    "primary/repo" to "shared.txt" -> CnbContent(path, "p2", CnbContentType.BLOB, 7, "primary")
                    "fallback/repo" to "fallback.txt" -> CnbContent(path, "f", CnbContentType.BLOB, 8, "fallback")
                    else -> null
                }
            }
        val root = CnbSCMFile.root(client, "primary/repo", "head", 99, "fallback/repo", "base")

        val children = root.children().associateBy { it.name }

        assertEquals(setOf("fallback.txt", "primary.txt", "shared.txt"), children.keys)
        assertEquals("primary", children.getValue("shared.txt").contentAsString())
        assertEquals("fallback", children.getValue("fallback.txt").contentAsString())
        assertEquals(SCMFile.Type.REGULAR_FILE, children.getValue("primary.txt").type)
        assertEquals(99, root.lastModified())
        assertEquals("nested", root.child("nested").name)
    }

    @Test
    fun `SCM file reports missing and malformed content precisely`() {
        val values =
            mapOf(
                "plain" to CnbContent("plain", "1", CnbContentType.BLOB, 4, "text"),
                "base64" to
                    CnbContent(
                        "base64",
                        "2",
                        CnbContentType.BLOB,
                        4,
                        Base64.getEncoder().encodeToString("data".toByteArray()),
                        CnbContentEncoding.BASE64,
                    ),
                "bad64" to CnbContent("bad64", "3", CnbContentType.BLOB, 1, "%%%", CnbContentEncoding.BASE64),
                "empty" to CnbContent("empty", "5", CnbContentType.BLOB, 0),
                "dir" to CnbContent("dir", "6", CnbContentType.TREE, 0),
                "other" to CnbContent("other", "7", CnbContentType.SUBMODULE, 0),
            )
        val client =
            client { method, arguments ->
                if (method == "getContent") values[arguments[1] as String] else unsupported(method)
            }
        val root = CnbSCMFile.root(client, "team/repo", "sha")

        assertEquals("text", root.child("plain").contentAsString())
        assertEquals("data", root.child("base64").contentAsString())
        assertEquals(SCMFile.Type.OTHER, root.child("other").type)
        assertThrows(IOException::class.java) { root.child("bad64").content() }
        assertThrows(IOException::class.java) { root.child("empty").content() }
        assertThrows(IOException::class.java) { root.child("dir").content() }
        assertThrows(IOException::class.java) { root.child("missing").content() }
        assertThrows(IOException::class.java) { root.child("missing").children() }
        assertTrue(root.child("plain").children().none())
    }

    @Test
    fun `file system builder resolves branch and tag revisions and enforces lifecycle`() {
        var closeCount = 0
        val client =
            repositoryClient(
                onCall = { method, arguments ->
                    when (method) {
                        "getBranch" -> {
                            CnbBranch(arguments[1] as String, "a".repeat(40))
                        }

                        "getTag" -> {
                            CnbTag(arguments[1] as String, "b".repeat(40), 456)
                        }

                        "getContent" -> {
                            CnbContent("", "tree", CnbContentType.TREE, 0)
                        }

                        "close" -> {
                            closeCount++
                            Unit
                        }

                        else -> {
                            unsupported(method)
                        }
                    }
                },
            )
        val builder = TestBuilder { client }
        val source = CnbSCMSource("cnb-cool", "team/repo")

        assertTrue(builder.supports(source))
        assertFalse(builder.supports(NullSCM()))
        val branch = builder.build(source, CnbBranchSCMHead("main"), null)
        assertNotNull(branch)
        assertEquals("a".repeat(40), (branch!!.revision as CnbBranchSCMRevision).hash)
        assertEquals(SCMFile.Type.DIRECTORY, branch.root.type)
        branch.close()
        branch.close()
        assertThrows(IllegalStateException::class.java) { branch.root }

        val tagClient =
            repositoryClient(onCall = { method, arguments ->
                when (method) {
                    "getTag" -> CnbTag(arguments[1] as String, "b".repeat(40), 456)
                    "close" -> Unit
                    else -> unsupported(method)
                }
            })
        val tag = TestBuilder { tagClient }.build(source, CnbTagSCMHead("v1", 456), null)
        assertNotNull(tag)
        assertEquals(456, tag!!.lastModified())
        assertInstanceOf(CnbTagSCMRevision::class.java, tag.revision)
        tag.close()

        assertEquals(1, closeCount)
    }

    @Test
    fun `file system builder rejects mismatched and unavailable revisions and closes clients`() {
        var closeCount = 0
        val pullRequest = pullRequest(mergeSha = null)
        val client =
            repositoryClient(
                onCall = { method, _ ->
                    when (method) {
                        "getPullRequest" -> {
                            pullRequest
                        }

                        "getTag" -> {
                            throw CnbApiException("missing", 404)
                        }

                        "close" -> {
                            closeCount++
                            Unit
                        }

                        else -> {
                            unsupported(method)
                        }
                    }
                },
            )
        val source = CnbSCMSource("cnb-cool", "team/repo")
        val builder = TestBuilder { client }
        val head = pullRequestHead(ChangeRequestCheckoutStrategy.HEAD)
        val otherHead = pullRequestHead(ChangeRequestCheckoutStrategy.HEAD, number = "8")

        assertThrows(IOException::class.java) {
            builder.build(source, head, CnbPullRequestSCMRevision(otherHead, "b".repeat(40), "a".repeat(40), null))
        }
        assertEquals(1, closeCount)

        val missingTagDelegate =
            repositoryClient(
                onCall = { method, _ ->
                    when (method) {
                        "close" -> {
                            closeCount++
                            Unit
                        }

                        else -> {
                            unsupported(method)
                        }
                    }
                },
            )
        val missingTagClient =
            object : CnbClient by missingTagDelegate {
                override fun getTag(
                    repo: String,
                    name: String,
                ): CnbTag = throw CnbApiException("missing", 404)
            }
        assertNull(TestBuilder { missingTagClient }.build(source, CnbTagSCMHead("missing", 0), null))
        assertEquals(2, closeCount)

        val unsupportedClient =
            repositoryClient(
                onCall = { method, _ ->
                    if (method == "close") {
                        closeCount++
                        Unit
                    } else {
                        unsupported(method)
                    }
                },
            )
        assertNull(TestBuilder { unsupportedClient }.build(source, SCMHead("other"), null))
        assertEquals(3, closeCount)
    }

    @Test
    fun `file system builder resolves trusted target and pull request locations`() {
        val source = CnbSCMSource("cnb-cool", "team/repo")
        val mergeHead = pullRequestHead(ChangeRequestCheckoutStrategy.MERGE)
        val headHead = pullRequestHead(ChangeRequestCheckoutStrategy.HEAD)

        val trusted = CnbBranchSCMRevision(mergeHead.target, "e".repeat(40))
        val trustedFs = TestBuilder { repositoryClient() }.build(source, mergeHead, trusted)
        assertSame(trusted, trustedFs!!.revision)
        trustedFs.close()

        val mergeRevision = CnbPullRequestSCMRevision(mergeHead, "b".repeat(40), "a".repeat(40), "d".repeat(40))
        val mergeFs = TestBuilder { repositoryClient() }.build(source, mergeHead, mergeRevision)
        assertSame(mergeRevision, mergeFs!!.revision)
        mergeFs.close()

        val headRevision = CnbPullRequestSCMRevision(headHead, "b".repeat(40), "a".repeat(40), null)
        val headFs = TestBuilder { repositoryClient() }.build(source, headHead, headRevision)
        assertSame(headRevision, headFs!!.revision)
        headFs.close()

        val wrongTarget = CnbBranchSCMRevision(CnbBranchSCMHead("release"), "f".repeat(40))
        assertThrows(IOException::class.java) {
            TestBuilder { repositoryClient() }.build(source, mergeHead, wrongTarget)
        }
    }

    private class TestBuilder(
        private val supplier: () -> CnbClient,
    ) : CnbSCMFileSystem.BuilderImpl() {
        override fun createClient(source: CnbSCMSource): CnbClient = supplier()
    }

    private fun repositoryClient(
        repository: CnbRepository = publicRepository(),
        onCall: (String, Array<out Any?>) -> Any? = { method, _ ->
            if (method == "close") Unit else unsupported(method)
        },
    ): CnbClient =
        client { method, arguments ->
            when (method) {
                "getRepository" -> repository
                else -> onCall(method, arguments)
            }
        }

    private fun client(handler: (String, Array<out Any?>) -> Any?): CnbClient =
        Proxy.newProxyInstance(
            CnbClient::class.java.classLoader,
            arrayOf(CnbClient::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "SCM coverage client"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> handler(method.name, arguments.orEmpty())
            }
        } as CnbClient

    private fun pullRequest(mergeSha: String?): CnbPullRequest =
        CnbPullRequest(
            number = "7",
            title = "Change",
            state = CnbPullRequestState.OPEN,
            sourceRepo = "fork/repo",
            sourceBranch = "feature",
            sourceSha = "a".repeat(40),
            targetRepo = "team/repo",
            targetBranch = "main",
            targetSha = "b".repeat(40),
            mergeSha = mergeSha,
            author = "contributor",
        )

    private fun pullRequestHead(
        strategy: ChangeRequestCheckoutStrategy,
        number: String = "7",
    ): CnbPullRequestSCMHead =
        CnbPullRequestSCMHead(
            "PR-$number",
            number,
            CnbBranchSCMHead("main"),
            strategy,
            SCMHeadOrigin.Fork("fork/repo"),
            "fork/repo",
            "feature",
            "contributor",
            "Change",
        )

    private fun publicRepository(): CnbRepository =
        CnbRepository(
            path = "team/repo",
            name = "repo",
            webUrl = "https://cnb.cool/team/repo",
            cloneUrl = "https://cnb.cool/team/repo",
            defaultBranch = "main",
            status = CnbRepositoryStatus.OK,
            visibility = CnbRepositoryVisibility.PUBLIC,
        )

    private fun unsupported(method: String): Nothing = throw UnsupportedOperationException(method)
}
