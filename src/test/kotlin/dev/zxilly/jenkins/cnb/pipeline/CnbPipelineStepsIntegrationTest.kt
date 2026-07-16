package dev.zxilly.jenkins.cnb.pipeline

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import hudson.model.Result
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@WithJenkins
class CnbPipelineStepsIntegrationTest {
    @Test
    fun `Pipeline refuses a stale pull request before issuing a write`(jenkins: JenkinsRule) {
        val writes = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            when (exchange.requestURI.path) {
                "/team/project/-/pulls/42" -> {
                    respond(
                        exchange,
                        200,
                        """{"number":42,"title":"Advanced","state":"open","head":{"ref":"feature","sha":"${"b".repeat(
                            40,
                        )}"},"base":{"ref":"master","sha":"${"c".repeat(40)}"}}""",
                    )
                }

                "/team/project/-/pulls/42/labels" -> {
                    writes.incrementAndGet()
                    respond(exchange, 201, """{"id":"1","name":"unsafe"}""")
                }

                else -> {
                    respond(exchange, 404, """{"errcode":404,"errmsg":"missing"}""")
                }
            }
        }
        server.start()
        try {
            configureServer(server)
            val job = jenkins.createProject(WorkflowJob::class.java, "cnb-stale-pull-request")
            job.definition =
                CpsFlowDefinition(
                    """
                    cnbPullRequestLabels(
                      serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42',
                      sha: '${"a".repeat(40)}', mode: 'add', labels: ['unsafe']
                    )
                    """.trimIndent(),
                    true,
                )

            val scheduled = requireNotNull(job.scheduleBuild2(0))
            val run = jenkins.assertBuildStatus(Result.FAILURE, scheduled.get())

            assertEquals(0, writes.get())
            jenkins.assertLogContains("source SHA no longer matches this build", run)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Pipeline can comment inspect labels and read statuses through the configured CNB server`(jenkins: JenkinsRule) {
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += "${exchange.requestMethod} $path"
            when (path) {
                "/team/project/-/pulls/42/comments" -> {
                    assertEquals("POST", exchange.requestMethod)
                    assertEquals("{\"body\":\"ready for review\"}", exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8))
                    respond(exchange, 201, """{"id":"91","body":"ready for review","user":{"username":"jenkins"}}""")
                }

                "/team/project/-/pulls/42" -> {
                    assertEquals("GET", exchange.requestMethod)
                    respond(
                        exchange,
                        200,
                        """{"number":42,"title":"Change","state":"open","head":{"ref":"feature","sha":"${"a".repeat(
                            40,
                        )}"},"base":{"ref":"master","sha":"${"c".repeat(40)}"}}""",
                    )
                }

                "/team/project/-/pulls/42/labels" -> {
                    if (exchange.requestMethod == "POST") {
                        respond(exchange, 201, """{"id":"2","name":"verified"}""")
                    } else {
                        val firstPage =
                            exchange.requestURI.rawQuery
                                .orEmpty()
                                .contains("page=1")
                        respond(exchange, 200, if (firstPage) """[{"id":"1","name":"security"}]""" else "[]")
                    }
                }

                "/team/project/-/pulls/42/reviews" -> {
                    assertEquals("POST", exchange.requestMethod)
                    val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                    org.junit.jupiter.api.Assertions
                        .assertTrue(body.contains("\"path\":\"README.md\""))
                    respond(exchange, 201, "{}")
                }

                "/team/project/-/pulls/42/merge" -> {
                    assertEquals("PUT", exchange.requestMethod)
                    respond(exchange, 200, """{"merged":true,"message":"merged","sha":"${"b".repeat(40)}"}""")
                }

                "/team/project/-/git/commit-statuses/${"a".repeat(40)}" -> {
                    respond(exchange, 200, """[{"context":"ci/jenkins","state":"success"}]""")
                }

                "/team/project/-/build/start" -> {
                    assertEquals("POST", exchange.requestMethod)
                    respond(exchange, 201, """{"sn":"cnb-123","buildLogUrl":"https://cnb.cool/build/123","success":true}""")
                }

                "/team/project/-/build/status/cnb-123" -> {
                    respond(
                        exchange,
                        200,
                        """{"status":"running","pipelinesStatus":{"test":{"id":"p1","name":"test","status":"running","labels":[],"stages":[]}}}""",
                    )
                }

                "/team/project/-/build/stop/cnb-123" -> {
                    assertEquals("POST", exchange.requestMethod)
                    respond(exchange, 200, """{"sn":"cnb-123","message":"stopped","success":true}""")
                }

                else -> {
                    respond(exchange, 404, """{"errcode":404,"errmsg":"missing"}""")
                }
            }
        }
        server.start()
        try {
            configureServer(server)

            val job = jenkins.createProject(WorkflowJob::class.java, "cnb-api-pipeline")
            job.definition =
                CpsFlowDefinition(
                    """
                    def comment = cnbPullRequestComment(
                      serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42',
                      sha: '${"a".repeat(40)}',
                      comment: 'ready for review'
                    )
                    if (comment.id != '91') { error('unexpected comment result') }
                    if (!cnbPullRequestLabelExists(
                      serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42', label: 'security'
                    )) { error('missing label') }
                    def labels = cnbPullRequestLabels(
                      serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42',
                      sha: '${"a".repeat(40)}', mode: 'add', labels: ['verified']
                    )
                    if (labels[0].name != 'verified') { error('label was not added') }
                    def statuses = cnbCommitStatuses(
                      serverId: 'local-cnb', repository: 'team/project', sha: '${"a".repeat(40)}'
                    )
                    if (statuses.size() != 1 || statuses[0].context != 'ci/jenkins') { error('unexpected statuses') }
                    if (!cnbReviewPullRequest(
                      serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42',
                      sha: '${"a".repeat(40)}', action: 'comment', body: 'pipeline review',
                      comments: [[body: 'inline review', path: 'README.md']]
                    )) { error('review was not accepted') }
                    def merge = cnbMergePullRequest(
                      serverId: 'local-cnb', repository: 'team/project', pullRequestNumber: '42',
                      sha: '${"a".repeat(40)}', method: 'squash', commitTitle: 'Merge test'
                    )
                    if (!merge.merged) { error('merge was not accepted') }
                    def started = cnbStartBuild(
                      serverId: 'local-cnb', repository: 'team/project', event: 'api_trigger_jenkins',
                      branch: 'master', env: [SOURCE: 'jenkins']
                    )
                    if (started.sn != 'cnb-123') { error('build did not start') }
                    def build = cnbBuildStatus(serverId: 'local-cnb', repository: 'team/project', sn: started.sn)
                    if (build.status != 'running') { error('unexpected build status') }
                    def stopped = cnbStopBuild(serverId: 'local-cnb', repository: 'team/project', sn: started.sn)
                    if (!stopped.success) { error('build did not stop') }
                    """.trimIndent(),
                    true,
                )

            jenkins.buildAndAssertSuccess(job)
            assertEquals(14, requests.size)
            assertEquals(4, requests.count { it == "GET /team/project/-/pulls/42" })
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

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
