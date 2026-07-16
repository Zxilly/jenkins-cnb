package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbBuildEventName
import dev.zxilly.jenkins.cnb.api.model.CnbBuildHistory
import dev.zxilly.jenkins.cnb.api.model.CnbBuildHistoryQuery
import dev.zxilly.jenkins.cnb.api.model.CnbBuildInfo
import dev.zxilly.jenkins.cnb.api.model.CnbBuildPipeline
import dev.zxilly.jenkins.cnb.api.model.CnbBuildState
import dev.zxilly.jenkins.cnb.api.model.CnbCommit
import dev.zxilly.jenkins.cnb.api.model.CnbCommitComparison
import dev.zxilly.jenkins.cnb.api.model.CnbCommitDiffFile
import dev.zxilly.jenkins.cnb.api.model.CnbCommitDiffStatus
import dev.zxilly.jenkins.cnb.api.model.CnbCommitPerson
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatus
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatusState
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatuses
import dev.zxilly.jenkins.cnb.api.model.CnbPullFile
import dev.zxilly.jenkins.cnb.api.model.CnbPullFileStatus
import dev.zxilly.jenkins.cnb.api.model.CnbPullReview
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewState
import hudson.AbortException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.LinkedHashMap

class CnbReadPipelineStepsTest {
    private val context = CnbRunContext("cnb-cool", "team/project", "42", "a".repeat(40), null)
    private val author = CnbCommitPerson("alice", "Alice", "Alice Doe", "alice@example.test", "2026-07-16")
    private val commit = CnbCommit("a".repeat(40), "subject", author, author, listOf("b".repeat(40)))

    @Test
    fun `commit list and comparison results contain only CPS safe values`() {
        var query: dev.zxilly.jenkins.cnb.api.model.CnbCommitQuery? = null
        val client =
            client(
                mapOf(
                    "getCommit" to { commit },
                    "listCommits" to { args ->
                        query = args?.get(1) as dev.zxilly.jenkins.cnb.api.model.CnbCommitQuery
                        listOf(commit)
                    },
                    "compareCommits" to {
                        CnbCommitComparison(
                            baseCommit = commit,
                            headCommit = commit.copy(sha = "c".repeat(40)),
                            commits = listOf(commit),
                            files =
                                listOf(
                                    CnbCommitDiffFile(
                                        "src/App.kt",
                                        status = CnbCommitDiffStatus.MODIFIED,
                                        additions = 2,
                                    ),
                                ),
                            totalCommits = 1,
                        )
                    },
                ),
            )

        val one = CnbStepDispatcher.execute(CnbStepRequest.Commit("master"), context, client)
        val many =
            CnbStepDispatcher.execute(
                CnbStepRequest.Commits("abc1234", "alice", "bot", "2026-01-01", "2026-07-16"),
                context,
                client,
            )
        val comparison =
            CnbStepDispatcher.execute(CnbStepRequest.CompareCommits("master", "feature"), context, client)

        assertCpsSafe(one)
        assertCpsSafe(many)
        assertCpsSafe(comparison)
        assertEquals("alice", ((one as Map<*, *>)["author"] as Map<*, *>)["username"])
        assertEquals("abc1234", query?.sha)
        assertEquals("src/App.kt", (((comparison as Map<*, *>)["files"] as List<*>).single() as Map<*, *>)["path"])
    }

    @Test
    fun `pull request read operations accept empty lists and require PR context`() {
        val client =
            client(
                mapOf(
                    "listPullCommits" to { emptyList<CnbCommit>() },
                    "listPullFiles" to { emptyList<CnbPullFile>() },
                    "listPullCommitStatuses" to {
                        CnbCommitStatuses("a".repeat(40), CnbCommitStatusState.PENDING, emptyList())
                    },
                    "listPullReviews" to { emptyList<CnbPullReview>() },
                ),
            )

        val values =
            listOf(
                CnbStepDispatcher.execute(CnbStepRequest.PullRequestCommits, context, client),
                CnbStepDispatcher.execute(CnbStepRequest.PullRequestFiles, context, client),
                CnbStepDispatcher.execute(CnbStepRequest.PullRequestStatuses, context, client),
                CnbStepDispatcher.execute(CnbStepRequest.PullRequestReviews, context, client),
            )
        values.forEach(::assertCpsSafe)
        assertTrue((values[0] as List<*>).isEmpty())
        assertTrue((values[1] as List<*>).isEmpty())
        assertTrue(((values[2] as Map<*, *>)["statuses"] as List<*>).isEmpty())
        assertTrue((values[3] as List<*>).isEmpty())

        assertThrows(AbortException::class.java) {
            CnbStepDispatcher.execute(CnbStepRequest.PullRequestFiles, context.copy(pullRequestNumber = null), client)
        }
    }

    @Test
    fun `pull request files statuses and reviews preserve every documented field`() {
        val client =
            client(
                mapOf(
                    "listPullFiles" to {
                        listOf(
                            CnbPullFile(
                                "src/App.kt",
                                CnbPullFileStatus.MODIFY,
                                "a".repeat(40),
                                4,
                                1,
                                "patch",
                                "blob",
                                "raw",
                                "contents",
                            ),
                        )
                    },
                    "listPullCommitStatuses" to {
                        CnbCommitStatuses(
                            "a".repeat(40),
                            CnbCommitStatusState.SUCCESS,
                            listOf(
                                CnbCommitStatus(
                                    "ci",
                                    CnbCommitStatusState.SUCCESS,
                                    "green",
                                    "target",
                                    "created",
                                    "updated",
                                ),
                            ),
                        )
                    },
                    "listPullReviews" to {
                        listOf(
                            CnbPullReview(
                                "7",
                                "looks good",
                                CnbPullReviewState.APPROVED,
                                "alice",
                                "created",
                                "updated",
                            ),
                        )
                    },
                ),
            )

        val file =
            (CnbStepDispatcher.execute(CnbStepRequest.PullRequestFiles, context, client) as List<*>).single() as Map<*, *>
        val statuses = CnbStepDispatcher.execute(CnbStepRequest.PullRequestStatuses, context, client) as Map<*, *>
        val review =
            (CnbStepDispatcher.execute(CnbStepRequest.PullRequestReviews, context, client) as List<*>).single() as Map<*, *>

        assertEquals("contents", file["contentsUrl"])
        assertEquals("success", statuses["state"])
        assertEquals("approved", review["state"])
        assertCpsSafe(file)
        assertCpsSafe(statuses)
        assertCpsSafe(review)
    }

    @Test
    fun `build history maps every query and result field to CPS values`() {
        var query: CnbBuildHistoryQuery? = null
        val history =
            CnbBuildHistory(
                1,
                1234,
                listOf(
                    CnbBuildInfo(
                        sn = "cnb-1",
                        sha = "a".repeat(40),
                        slug = "team/project",
                        status = CnbBuildState.SUCCESS,
                        event = CnbBuildEventName("api_trigger_jenkins"),
                        sourceRef = "feature",
                        sourceSlug = "fork/project",
                        targetRef = "master",
                        title = "Build",
                        commitTitle = "Commit",
                        buildLogUrl = "build-url",
                        eventUrl = "event-url",
                        createTime = "created",
                        duration = 10,
                        labels = "linux",
                        groupName = "team",
                        userName = "alice",
                        nickName = "Alice",
                        freeze = true,
                        pipelineFailCount = 0,
                        pipelineSuccessCount = 1,
                        pipelineTotalCount = 1,
                        pipelines = listOf(CnbBuildPipeline("p1", CnbBuildState.SUCCESS, "created", 9, "linux")),
                    ),
                ),
            )
        val client =
            client(
                mapOf(
                    "listBuildHistory" to { args ->
                        query = args?.get(1) as CnbBuildHistoryQuery
                        history
                    },
                ),
            )
        val request =
            CnbStepRequest.BuildHistory(
                "2026-07-01",
                "2026-07-16",
                CnbBuildEventName("api_trigger_jenkins"),
                "sha filter",
                "cnb-1",
                "feature",
                "success",
                "master",
                "42",
                "alice",
            )

        val result = CnbStepDispatcher.execute(request, context, client) as Map<*, *>

        assertEquals("2026-07-01", query?.createTime)
        assertEquals("alice", query?.userName)
        val build = (result["builds"] as List<*>).single() as Map<*, *>
        assertEquals("fork/project", build["sourceSlug"])
        assertEquals(1, build["pipelineTotalCount"])
        assertEquals("p1", ((build["pipelines"] as List<*>).single() as Map<*, *>)["id"])
        assertCpsSafe(result)
    }

    @Test
    fun `read step inputs and descriptors are validated`() {
        assertThrows(IllegalArgumentException::class.java) { CnbCommitStep(" ") }
        assertThrows(IllegalArgumentException::class.java) { CnbCompareCommitsStep("master", "\u0001") }
        assertThrows(IllegalArgumentException::class.java) { CnbCommitStep("x".repeat(1_025)) }
        val commits = CnbCommitsStep()
        assertThrows(IllegalArgumentException::class.java) { commits.setAuthor("x".repeat(1_025)) }
        val history = CnbBuildHistoryStep()
        assertThrows(IllegalArgumentException::class.java) { history.setCreateTime("07/16/2026") }

        val descriptors =
            listOf(
                CnbCommitStep.DescriptorImpl(),
                CnbCommitsStep.DescriptorImpl(),
                CnbCompareCommitsStep.DescriptorImpl(),
                CnbPullRequestCommitsStep.DescriptorImpl(),
                CnbPullRequestFilesStep.DescriptorImpl(),
                CnbPullRequestStatusesStep.DescriptorImpl(),
                CnbPullRequestReviewsStep.DescriptorImpl(),
                CnbBuildHistoryStep.DescriptorImpl(),
            )
        assertEquals(
            listOf(
                "cnbCommit",
                "cnbCommits",
                "cnbCompareCommits",
                "cnbPullRequestCommits",
                "cnbPullRequestFiles",
                "cnbPullRequestStatuses",
                "cnbPullRequestReviews",
                "cnbBuildHistory",
            ),
            descriptors.map { it.functionName },
        )
    }

    private fun assertCpsSafe(value: Any?) =
        when (value) {
            null,
            is String,
            is Number,
            is Boolean,
            -> {
                Unit
            }

            is LinkedHashMap<*, *> -> {
                assertTrue(value.keys.all { it is String })
                value.values.forEach(::assertCpsSafe)
            }

            is ArrayList<*> -> {
                value.forEach(::assertCpsSafe)
            }

            else -> {
                fail("Pipeline result contains non-CPS-safe ${value.javaClass.name}")
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun client(handlers: Map<String, (Array<out Any?>?) -> Any?>): CnbClient =
        Proxy.newProxyInstance(CnbClient::class.java.classLoader, arrayOf(CnbClient::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCapabilities" -> CnbApiCapabilities()
                "close" -> Unit
                "toString" -> "ReadPipelineTestCnbClient"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> handlers[method.name]?.invoke(args) ?: throw UnsupportedOperationException(method.name)
            }
        } as CnbClient
}
