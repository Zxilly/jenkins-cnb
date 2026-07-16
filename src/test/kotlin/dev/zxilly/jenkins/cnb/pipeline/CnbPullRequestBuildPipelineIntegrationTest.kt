package dev.zxilly.jenkins.cnb.pipeline

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

@WithJenkins
class CnbPullRequestBuildPipelineIntegrationTest {
    @Test
    fun `Pipeline exercises complete typed PR surface and safe runner download`(jenkins: JenkinsRule) {
        val sha = "a".repeat(40)
        val requests = CopyOnWriteArrayList<Request>()
        val runnerLog = "runner log body".toByteArray(StandardCharsets.UTF_8)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val request =
                Request(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestURI.rawQuery.orEmpty(),
                    exchange.requestHeaders.getFirst("Authorization"),
                    if (exchange.requestMethod == "HEAD") "" else exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8),
                )
            requests += request
            when (request.path) {
                "/team/project/-/git/branches/feature" -> {
                    respond(exchange, 200, """{"name":"feature","sha":"$sha"}""")
                }

                "/team/project/-/pulls" -> {
                    if (request.method == "POST") {
                        respond(exchange, 201, pullJson(sha))
                    } else {
                        respond(exchange, 200, if (request.query.contains("page=1")) "[${pullJson(sha)}]" else "[]")
                    }
                }

                "/team/project/-/pulls/42" -> {
                    respond(exchange, 200, pullJson(sha))
                }

                "/team/project/-/pulls/42/assignees" -> {
                    if (request.method == "GET") {
                        respond(
                            exchange,
                            200,
                            if (request.query.contains("page=1")) """[{"username":"alice","avatar":"https://secret/avatar"}]""" else "[]",
                        )
                    } else {
                        respond(exchange, 200, pullJson(sha))
                    }
                }

                "/team/project/-/pulls/42/reviewers" -> {
                    respond(exchange, 200, pullJson(sha))
                }

                "/team/project/-/pulls/42/comments" -> {
                    if (request.method == "POST") {
                        respond(exchange, 201, """{"id":"comment-2","body":"created","user":{"username":"alice"}}""")
                    } else {
                        respond(
                            exchange,
                            200,
                            if (request.query.contains(
                                    "page=1",
                                )
                            ) {
                                """[{"id":"comment-1","body":"hello","user":{"username":"alice"}}]"""
                            } else {
                                "[]"
                            },
                        )
                    }
                }

                "/team/project/-/pulls/42/comments/comment-1" -> {
                    val body = if (request.method == "PATCH") "updated" else "hello"
                    respond(exchange, 200, """{"id":"comment-1","body":"$body","user":{"username":"alice"}}""")
                }

                "/team/project/-/pulls/42/labels" -> {
                    when (request.method) {
                        "GET" -> {
                            respond(
                                exchange,
                                200,
                                if (request.query.contains("page=1")) """[{"id":"label-1","name":"ready"}]""" else "[]",
                            )
                        }

                        "DELETE" -> {
                            respond(exchange, 204, "")
                        }

                        else -> {
                            respond(exchange, 200, """{"id":"label-1","name":"ready"}""")
                        }
                    }
                }

                "/team/project/-/pulls/42/reviews" -> {
                    if (request.method == "POST") {
                        respond(exchange, 201, "{}")
                    } else {
                        respond(
                            exchange,
                            200,
                            if (request.query.contains("page=1")) """[{"id":"review-1","state":"approved","body":"ok"}]""" else "[]",
                        )
                    }
                }

                "/team/project/-/pulls/42/reviews/review-1/comments" -> {
                    respond(
                        exchange,
                        200,
                        if (request.query.contains("page=1")) "[${reviewCommentJson(sha, "comment-1", "")}]" else "[]",
                    )
                }

                "/team/project/-/pulls/42/reviews/review-1/replies" -> {
                    respond(exchange, 201, reviewCommentJson(sha, "comment-2", "comment-1"))
                }

                "/team/project/-/build/logs/stage/build-1/pipeline-1/stage-1" -> {
                    respond(
                        exchange,
                        200,
                        """{"id":"stage-1","name":"tests","status":"success","duration":7,"startTime":1,"endTime":8,"content":["line"]}""",
                    )
                }

                "/team/project/-/build/runner/download/log/pipeline-1" -> {
                    exchange.responseHeaders.add(
                        "Location",
                        "http://127.0.0.1:${server.address.port}/signed/runner?signature=secret",
                    )
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }

                "/signed/runner" -> {
                    exchange.responseHeaders.add("Content-Type", "text/plain")
                    exchange.responseHeaders.add("ETag", "runner-etag")
                    exchange.sendResponseHeaders(200, runnerLog.size.toLong())
                    exchange.responseBody.use { it.write(runnerLog) }
                }

                else -> {
                    respond(exchange, 404, """{"errcode":404,"errmsg":"missing"}""")
                }
            }
        }
        server.start()
        try {
            configureServer(server)
            val job = jenkins.createProject(WorkflowJob::class.java, "cnb-pr-build-surface")
            job.definition =
                CpsFlowDefinition(
                    """
                    node {
                      def prs = cnbPullRequests(serverId: 'local-cnb', repository: 'team/project', state: 'all')
                      def pr = cnbPullRequest(serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42')
                      if (prs[0].number != '42' || pr.state != 'open') { error('PR read failed') }
                      if (pr.assignees[0].avatarUrl != null) { error('avatar URL leaked') }
                      def created = cnbCreatePullRequest(
                        serverId: 'local-cnb', repository: 'team/project', sha: '$sha',
                        targetBranch: 'master', sourceBranch: 'feature', title: 'Created', body: 'body'
                      )
                      def updated = cnbUpdatePullRequest(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', sha: '$sha',
                        title: 'Updated', state: 'closed', confirm: '42'
                      )
                      if (created.number != '42' || updated.number != '42') { error('PR write failed') }
                      def assignees = cnbPullRequestAssignees(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42'
                      )
                      cnbAddPullRequestAssignees(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', sha: '$sha', usernames: ['alice']
                      )
                      cnbRemovePullRequestAssignees(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', sha: '$sha',
                        usernames: ['alice'], confirm: '42'
                      )
                      cnbAddPullRequestReviewers(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', sha: '$sha', usernames: ['bob']
                      )
                      cnbRemovePullRequestReviewers(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', sha: '$sha',
                        usernames: ['bob'], confirm: '42'
                      )
                      if (assignees[0].username != 'alice') { error('assignee read failed') }
                      def comments = cnbPullRequestComments(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42'
                      )
                      def oneComment = cnbPullRequestCommentById(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', commentId: 'comment-1'
                      )
                      def createdComment = cnbPullRequestComment(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', sha: '$sha', comment: 'created'
                      )
                      def updatedComment = cnbUpdatePullRequestComment(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', sha: '$sha',
                        commentId: 'comment-1', comment: 'updated'
                      )
                      if (comments[0].id != oneComment.id || createdComment.id != 'comment-2' || updatedComment.body != 'updated') {
                        error('comment surface failed')
                      }
                      def labels = cnbPullRequestLabels(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', mode: 'list', labels: []
                      )
                      def cleared = cnbPullRequestLabels(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', sha: '$sha',
                        mode: 'clear', labels: [], confirm: '42'
                      )
                      if (labels[0].name != 'ready' || cleared.operation != 'labels-cleared') { error('label surface failed') }
                      def reviews = cnbPullRequestReviews(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42'
                      )
                      cnbReviewPullRequest(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', sha: '$sha',
                        action: 'comment', body: 'review', comments: [[body: 'inline', path: 'README.md']]
                      )
                      def reviewComments = cnbPullRequestReviewComments(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', reviewId: 'review-1'
                      )
                      def reply = cnbReplyPullRequestReviewComment(
                        serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', sha: '$sha',
                        reviewId: 'review-1', commentId: 'comment-1', body: 'thanks'
                      )
                      if (reviews[0].state != 'approved' || reviewComments[0].id != 'comment-1' || reply.replyToCommentId != 'comment-1') {
                        error('review surface failed')
                      }
                      def stage = cnbBuildStage(
                        serverId: 'local-cnb', repository: 'team/project', sn: 'build-1', pipelineId: 'pipeline-1', stageId: 'stage-1'
                      )
                      def downloaded = cnbDownloadBuildRunnerLog(
                        serverId: 'local-cnb', repository: 'team/project', pipelineId: 'pipeline-1',
                        path: 'logs/runner.log', overwrite: false, maxBytes: 1024
                      )
                      if (stage.status != 'success' || downloaded.content != null || readFile('logs/runner.log') != 'runner log body') {
                        error('build detail surface failed')
                      }
                    }
                    """.trimIndent(),
                    true,
                )

            jenkins.buildAndAssertSuccess(job)

            assertTrue(requests.any { it.method == "POST" && it.path == "/team/project/-/pulls" })
            assertTrue(requests.any { it.method == "PATCH" && it.path.endsWith("/pulls/42") })
            assertTrue(requests.any { it.method == "DELETE" && it.path.endsWith("/labels") })
            val signed = requests.single { it.path == "/signed/runner" }
            assertNull(signed.authorization)
            assertEquals("runner log body", runnerLog.toString(StandardCharsets.UTF_8))
        } finally {
            server.stop(0)
        }
    }

    private fun configureServer(server: HttpServer) {
        val local =
            CnbServer(
                "local-cnb",
                "Local CNB",
                "http://127.0.0.1:${server.address.port}",
                "http://127.0.0.1:${server.address.port}",
            )
        local.setAllowInsecureHttp(true)
        local.setAllowPrivateNetwork(true)
        CnbGlobalConfiguration.get().setServers(listOf(local))
    }

    private fun pullJson(sha: String): String =
        """
        {
          "number":"42","title":"Typed PR","state":"open","body":"body",
          "head":{"ref":"feature","sha":"$sha","repo":{"path":"team/project"}},
          "base":{"ref":"master","sha":"${"b".repeat(40)}","repo":{"path":"team/project"}},
          "author":{"username":"alice","avatar":"https://secret/avatar"},
          "assignees":[{"username":"alice","avatar":"https://secret/avatar"}],
          "reviewers":[{"review_state":"approved","user":{"username":"bob"}}]
        }
        """.trimIndent()

    private fun reviewCommentJson(
        sha: String,
        id: String,
        replyTo: String,
    ): String =
        """
        {
          "id":"$id","review_id":"review-1","body":"comment","author":{"username":"alice"},
          "commit_hash":"$sha","path":"README.md","review_state":"commented",
          "reply_to_comment_id":"$replyTo","subject_type":"file"
        }
        """.trimIndent()

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
        if (status == 204) exchange.close() else exchange.responseBody.use { it.write(bytes) }
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: String,
        val authorization: String?,
        val body: String,
    )
}
