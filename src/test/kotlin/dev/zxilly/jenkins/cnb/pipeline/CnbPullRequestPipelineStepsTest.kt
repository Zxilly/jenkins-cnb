package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbCreatePullRequestRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullComment
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestListState
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestState
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewCommentInfo
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewReplyRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewSide
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewState
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewSubjectType
import dev.zxilly.jenkins.cnb.api.model.CnbUpdatePullRequestRequest
import dev.zxilly.jenkins.cnb.api.model.CnbUser
import hudson.AbortException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.LinkedHashMap

class CnbPullRequestPipelineStepsTest {
    private val sha = "a".repeat(40)
    private val context = CnbRunContext("cnb-cool", "team/project", "42", sha, null)
    private val user = CnbUser("alice", "Alice", "alice@example.test", "https://secret/avatar")
    private val pull =
        CnbPullRequest(
            number = "42",
            title = "Typed PR",
            state = CnbPullRequestState.OPEN,
            sourceRepo = "team/project",
            sourceBranch = "feature",
            sourceSha = sha,
            targetRepo = "team/project",
            targetBranch = "master",
            targetSha = "b".repeat(40),
            assignees = listOf(user),
            authorInfo = user,
        )
    private val reviewComment =
        CnbPullReviewCommentInfo(
            id = "comment-1",
            reviewId = "review-1",
            body = "inline",
            author = user,
            commitSha = sha,
            path = "src/App.kt",
            reviewState = CnbPullReviewState.COMMENTED,
            subjectType = CnbPullReviewSubjectType.LINE,
            endLine = 7,
            endSide = CnbPullReviewSide.RIGHT,
        )

    @Test
    fun `PR list get assignee comment and review-comment reads are CPS safe and omit avatar URLs`() {
        val client =
            client(
                mapOf(
                    "listPullRequests" to { listOf(pull) },
                    "getPullRequest" to { pull },
                    "listPullAssignees" to { listOf(user) },
                    "listPullComments" to { listOf(CnbPullComment("comment-1", "hello", "alice")) },
                    "getPullComment" to { CnbPullComment("comment-1", "hello", "alice") },
                    "listPullReviewComments" to { listOf(reviewComment) },
                ),
            )
        val values =
            listOf(
                CnbPullRequestStepDispatcher.execute(
                    CnbPullRequestStepRequest.List(CnbPullRequestListState.ALL),
                    context,
                    client,
                ),
                CnbPullRequestStepDispatcher.execute(CnbPullRequestStepRequest.Get, context, client),
                CnbPullRequestStepDispatcher.execute(CnbPullRequestStepRequest.ListAssignees, context, client),
                CnbPullRequestStepDispatcher.execute(CnbPullRequestStepRequest.ListComments, context, client),
                CnbPullRequestStepDispatcher.execute(CnbPullRequestStepRequest.GetComment("comment-1"), context, client),
                CnbPullRequestStepDispatcher.execute(
                    CnbPullRequestStepRequest.ListReviewComments("review-1"),
                    context,
                    client,
                ),
            )

        values.forEach(::assertCpsSafe)
        val mappedPull = (values[0] as List<*>).single() as Map<*, *>
        val mappedAssignee = (mappedPull["assignees"] as List<*>).single() as Map<*, *>
        assertEquals("open", mappedPull["state"])
        assertFalse(mappedAssignee.containsKey("avatarUrl"))
        val mappedReview = (values.last() as List<*>).single() as Map<*, *>
        assertEquals("line", mappedReview["subjectType"])
        assertEquals("right", mappedReview["endSide"])
    }

    @Test
    fun `create and update require current source identities and typed nonempty responses`() {
        var createRequest: CnbCreatePullRequestRequest? = null
        var updateRequest: CnbUpdatePullRequestRequest? = null
        val client =
            client(
                mapOf(
                    "getBranch" to { CnbBranch("feature", sha) },
                    "createPullRequest" to { args ->
                        createRequest = requireNotNull(args)[1] as CnbCreatePullRequestRequest
                        pull
                    },
                    "getPullRequest" to { pull },
                    "updatePullRequest" to { args ->
                        updateRequest = requireNotNull(args)[2] as CnbUpdatePullRequestRequest
                        pull.copy(state = CnbPullRequestState.CLOSED)
                    },
                ),
            )

        val created =
            CnbPullRequestStepDispatcher.execute(
                CnbPullRequestStepRequest.Create(
                    CnbCreatePullRequestRequest("master", "feature", "Typed PR", "body"),
                ),
                context,
                client,
            )
        val updated =
            CnbPullRequestStepDispatcher.execute(
                CnbPullRequestStepRequest.Update(
                    CnbUpdatePullRequestRequest(state = CnbPullRequestState.CLOSED),
                    "42",
                ),
                context,
                client,
            )

        assertEquals("feature", createRequest?.sourceBranch)
        assertEquals(CnbPullRequestState.CLOSED, updateRequest?.state)
        assertCpsSafe(created)
        assertCpsSafe(updated)
    }

    @Test
    fun `participant comment and reply mutations all preflight and removals require confirmation`() {
        val calls = ArrayList<String>()
        val client =
            client(
                mapOf(
                    "getPullRequest" to {
                        calls += "preflight"
                        pull
                    },
                    "addPullAssignees" to {
                        calls += "add-assignee"
                        pull
                    },
                    "removePullAssignees" to {
                        calls += "remove-assignee"
                        pull
                    },
                    "addPullReviewers" to {
                        calls += "add-reviewer"
                        pull
                    },
                    "removePullReviewers" to {
                        calls += "remove-reviewer"
                        pull
                    },
                    "updatePullComment" to {
                        calls += "update-comment"
                        CnbPullComment("comment-1", "updated")
                    },
                    "replyToPullReviewComment" to { args ->
                        val request = requireNotNull(args)[3] as CnbPullReviewReplyRequest
                        calls += "reply:${request.replyToCommentId}"
                        reviewComment.copy(body = request.body)
                    },
                ),
            )
        val mutations =
            listOf(
                CnbPullRequestStepRequest.AddAssignees(listOf("alice")),
                CnbPullRequestStepRequest.RemoveAssignees(listOf("alice"), "42"),
                CnbPullRequestStepRequest.AddReviewers(listOf("bob")),
                CnbPullRequestStepRequest.RemoveReviewers(listOf("bob"), "42"),
                CnbPullRequestStepRequest.UpdateComment("comment-1", "updated"),
                CnbPullRequestStepRequest.ReplyReviewComment(
                    "review-1",
                    CnbPullReviewReplyRequest("reply", "comment-1"),
                ),
            )

        mutations.forEach { request -> assertCpsSafe(CnbPullRequestStepDispatcher.execute(request, context, client)) }
        assertEquals(6, calls.count { it == "preflight" })
        assertTrue(calls.contains("reply:comment-1"))

        assertThrows(AbortException::class.java) {
            CnbPullRequestStepDispatcher.execute(
                CnbPullRequestStepRequest.RemoveReviewers(listOf("bob"), "41"),
                context,
                client,
            )
        }
        assertFalse(calls.contains("remove-reviewer-after-invalid-confirm"))
    }

    @Test
    fun `stale PR and source branch guards prevent every downstream write`() {
        var writes = 0
        val stalePullClient =
            client(
                mapOf(
                    "getPullRequest" to { pull.copy(sourceSha = "c".repeat(40)) },
                    "addPullAssignees" to {
                        writes++
                        pull
                    },
                ),
            )
        assertThrows(AbortException::class.java) {
            CnbPullRequestStepDispatcher.execute(
                CnbPullRequestStepRequest.AddAssignees(listOf("alice")),
                context,
                stalePullClient,
            )
        }
        val staleBranchClient =
            client(
                mapOf(
                    "getBranch" to { CnbBranch("feature", "d".repeat(40)) },
                    "createPullRequest" to {
                        writes++
                        pull
                    },
                ),
            )
        assertThrows(AbortException::class.java) {
            CnbPullRequestStepDispatcher.execute(
                CnbPullRequestStepRequest.Create(CnbCreatePullRequestRequest("master", "feature", "title")),
                context,
                staleBranchClient,
            )
        }
        assertEquals(0, writes)
    }

    @Test
    fun `PR inputs and descriptors expose only declared typed options`() {
        assertEquals(CnbPullRequestListState.ALL, CnbPullRequestInput.listState("ALL"))
        assertEquals(CnbPullRequestState.CLOSED, CnbPullRequestInput.updateState("closed"))
        assertThrows(IllegalArgumentException::class.java) { CnbPullRequestInput.listState("merged") }
        assertThrows(IllegalArgumentException::class.java) { CnbPullRequestInput.updateState("merged") }
        assertThrows(IllegalArgumentException::class.java) { CnbPullRequestInput.branch("refs/heads/main", "source") }
        assertThrows(IllegalArgumentException::class.java) { CnbPullRequestInput.participants(listOf("bad user"), "reviewer") }
        assertThrows(IllegalArgumentException::class.java) { CnbPullRequestInput.resourceId("bad/id", "comment ID") }

        assertEquals(
            listOf(
                "cnbPullRequests",
                "cnbPullRequest",
                "cnbCreatePullRequest",
                "cnbUpdatePullRequest",
                "cnbPullRequestAssignees",
                "cnbAddPullRequestAssignees",
                "cnbRemovePullRequestAssignees",
                "cnbAddPullRequestReviewers",
                "cnbRemovePullRequestReviewers",
                "cnbPullRequestComments",
                "cnbPullRequestCommentById",
                "cnbUpdatePullRequestComment",
                "cnbPullRequestReviewComments",
                "cnbReplyPullRequestReviewComment",
            ),
            listOf(
                CnbPullRequestsStep.DescriptorImpl(),
                CnbPullRequestStep.DescriptorImpl(),
                CnbCreatePullRequestStep.DescriptorImpl(),
                CnbUpdatePullRequestStep.DescriptorImpl(),
                CnbPullRequestAssigneesStep.DescriptorImpl(),
                CnbAddPullRequestAssigneesStep.DescriptorImpl(),
                CnbRemovePullRequestAssigneesStep.DescriptorImpl(),
                CnbAddPullRequestReviewersStep.DescriptorImpl(),
                CnbRemovePullRequestReviewersStep.DescriptorImpl(),
                CnbPullRequestCommentsStep.DescriptorImpl(),
                CnbPullRequestCommentByIdStep.DescriptorImpl(),
                CnbUpdatePullRequestCommentStep.DescriptorImpl(),
                CnbPullRequestReviewCommentsStep.DescriptorImpl(),
                CnbReplyPullRequestReviewCommentStep.DescriptorImpl(),
            ).map { it.functionName },
        )
    }

    private fun assertCpsSafe(value: Any?) {
        when (value) {
            null,
            is String,
            is Number,
            is Boolean,
            -> Unit

            is LinkedHashMap<*, *> -> value.values.forEach(::assertCpsSafe)

            is ArrayList<*> -> value.forEach(::assertCpsSafe)

            else -> fail("Pipeline result contains non-CPS-safe ${value.javaClass.name}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun client(handlers: Map<String, (Array<out Any?>?) -> Any?>): CnbClient =
        Proxy.newProxyInstance(CnbClient::class.java.classLoader, arrayOf(CnbClient::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCapabilities" -> CnbApiCapabilities()
                "close" -> Unit
                "toString" -> "PullRequestPipelineTestCnbClient"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> handlers[method.name]?.invoke(args) ?: throw UnsupportedOperationException(method.name)
            }
        } as CnbClient
}
