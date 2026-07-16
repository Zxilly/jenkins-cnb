package dev.zxilly.jenkins.cnb.publisher

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import hudson.model.Result
import hudson.util.FormValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.FailureBuilder
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@WithJenkins
class CnbPullRequestActionPublisherTest {
    @Test
    fun `confirmed Publisher review preflights the configured source SHA before writing`(jenkins: JenkinsRule) {
        val calls = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls += "${exchange.requestMethod} ${exchange.requestURI.path}"
            when (exchange.requestURI.path) {
                "/team/project/-/pulls/42" -> {
                    respond(
                        exchange,
                        200,
                        """{"number":42,"title":"Change","state":"open","head":{"ref":"feature","sha":"${"a".repeat(
                            40,
                        )}"},"base":{"ref":"master","sha":"${"c".repeat(40)}"}}""",
                    )
                }

                "/team/project/-/pulls/42/reviews" -> {
                    assertEquals("POST", exchange.requestMethod)
                    exchange.requestBody.readAllBytes()
                    respond(exchange, 201, "{}")
                }

                else -> {
                    respond(exchange, 404, """{"errcode":404,"errmsg":"missing"}""")
                }
            }
        }
        server.start()
        try {
            configureServer(server)
            val project = jenkins.createFreeStyleProject("publisher-review-preflight")
            val publisher = CnbPullRequestActionPublisher("review")
            publisher.setServerId("local-cnb")
            publisher.setRepository("team/project")
            publisher.setPullRequestNumber("42")
            publisher.setSha("A".repeat(40))
            publisher.setReviewAction("approve")
            publisher.setConfirmDangerousAction(true)
            project.publishersList.add(publisher)

            jenkins.buildAndAssertSuccess(project)

            assertEquals(
                listOf(
                    "GET /team/project/-/pulls/42",
                    "POST /team/project/-/pulls/42/reviews",
                ),
                calls,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `confirmed Publisher merge preflights SHA and sends the configured merge request`(jenkins: JenkinsRule) {
        val calls = CopyOnWriteArrayList<String>()
        val requestBody = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls += "${exchange.requestMethod} ${exchange.requestURI.path}"
            when (exchange.requestURI.path) {
                "/team/project/-/pulls/42" -> {
                    respond(
                        exchange,
                        200,
                        """{"number":42,"title":"Change","state":"open","head":{"ref":"feature","sha":"${"a".repeat(
                            40,
                        )}"},"base":{"ref":"master","sha":"${"c".repeat(40)}"}}""",
                    )
                }

                "/team/project/-/pulls/42/merge" -> {
                    assertEquals("PUT", exchange.requestMethod)
                    requestBody.set(exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8))
                    respond(
                        exchange,
                        200,
                        """{"merged":true,"message":"merged","sha":"${"d".repeat(40)}"}""",
                    )
                }

                else -> {
                    respond(exchange, 404, """{"errcode":404,"errmsg":"missing"}""")
                }
            }
        }
        server.start()
        try {
            configureServer(server)
            val project = jenkins.createFreeStyleProject("publisher-merge-preflight")
            val publisher = CnbPullRequestActionPublisher("merge")
            publisher.setServerId("local-cnb")
            publisher.setRepository("team/project")
            publisher.setPullRequestNumber("42")
            publisher.setSha("A".repeat(40))
            publisher.setMergeMethod("squash")
            publisher.setCommitTitle("Squashed title")
            publisher.setCommitMessage("Squashed body")
            publisher.setConfirmDangerousAction(true)
            project.publishersList.add(publisher)

            val run = jenkins.buildAndAssertSuccess(project)

            assertEquals(
                listOf(
                    "GET /team/project/-/pulls/42",
                    "PUT /team/project/-/pulls/42/merge",
                ),
                calls,
            )
            assertTrue(requestBody.get().contains("\"merge_style\":\"squash\""))
            assertTrue(requestBody.get().contains("\"commit_title\":\"Squashed title\""))
            assertTrue(requestBody.get().contains("\"commit_message\":\"Squashed body\""))
            jenkins.assertLogContains("[CNB] Completed merge action for team/project pull request 42", run)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Freestyle Publisher posts a comment and survives configuration roundtrip`(jenkins: JenkinsRule) {
        val requestBody = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/team/project/-/pulls/42") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            respond(
                exchange,
                200,
                """{"number":42,"title":"Change","state":"open","head":{"ref":"feature","sha":"${"a".repeat(
                    40,
                )}"},"base":{"ref":"master","sha":"${"b".repeat(40)}"}}""",
            )
        }
        server.createContext("/team/project/-/pulls/42/comments") { exchange ->
            requestBody.set(exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8))
            respond(exchange, 201, """{"id":"1","body":"build finished"}""")
        }
        server.start()
        try {
            configureServer(server)
            val project = jenkins.createFreeStyleProject("publisher-comment")
            val publisher = CnbPullRequestActionPublisher("comment")
            publisher.setServerId("local-cnb")
            publisher.setRepository("team/project")
            publisher.setPullRequestNumber("42")
            publisher.setSha("A".repeat(40))
            publisher.setBody("build finished")
            project.publishersList.add(publisher)
            jenkins.configRoundtrip(project)

            val run = jenkins.buildAndAssertSuccess(project)

            assertEquals("{\"body\":\"build finished\"}", requestBody.get())
            jenkins.assertLogContains("[CNB] Completed comment action for team/project pull request 42", run)
            val restored = project.publishersList.get(CnbPullRequestActionPublisher::class.java)
            assertEquals("comment", restored.action)
            assertEquals("team/project", restored.repository)
            assertEquals("A".repeat(40), restored.sha)
            assertFalse(restored.confirmDangerousAction)
            assertTrue(restored.onlyOnSuccess)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `onlyOnSuccess skips failed builds by default and can be explicitly disabled`(jenkins: JenkinsRule) {
        val calls = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls += "${exchange.requestMethod} ${exchange.requestURI.path}"
            when (exchange.requestURI.path) {
                "/team/project/-/pulls/42" -> {
                    respond(
                        exchange,
                        200,
                        """{"number":42,"title":"Change","state":"open","head":{"ref":"feature","sha":"${"a".repeat(
                            40,
                        )}"},"base":{"ref":"master","sha":"${"b".repeat(40)}"}}""",
                    )
                }

                "/team/project/-/pulls/42/comments" -> {
                    assertEquals("POST", exchange.requestMethod)
                    exchange.requestBody.readAllBytes()
                    respond(exchange, 201, """{"id":"1","body":"build finished"}""")
                }

                else -> {
                    respond(exchange, 404, """{"errcode":404,"errmsg":"missing"}""")
                }
            }
        }
        server.start()
        try {
            configureServer(server)
            val skippedProject = jenkins.createFreeStyleProject("publisher-success-only")
            skippedProject.buildersList.add(FailureBuilder())
            skippedProject.publishersList.add(commentPublisher(onlyOnSuccess = true))

            val skippedRun = requireNotNull(skippedProject.scheduleBuild2(0)).get()
            jenkins.assertBuildStatus(Result.FAILURE, skippedRun)
            assertTrue(calls.isEmpty())
            jenkins.assertLogContains("[CNB] Skipped pull request action because the build was not successful", skippedRun)

            val forcedProject = jenkins.createFreeStyleProject("publisher-after-failure")
            forcedProject.buildersList.add(FailureBuilder())
            forcedProject.publishersList.add(commentPublisher(onlyOnSuccess = false))

            val forcedRun = requireNotNull(forcedProject.scheduleBuild2(0)).get()
            jenkins.assertBuildStatus(Result.FAILURE, forcedRun)
            assertEquals(
                listOf(
                    "GET /team/project/-/pulls/42",
                    "POST /team/project/-/pulls/42/comments",
                ),
                calls,
            )
            jenkins.assertLogContains("[CNB] Completed comment action for team/project pull request 42", forcedRun)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `dangerous Publisher action fails build unless explicitly confirmed`(jenkins: JenkinsRule) {
        CnbGlobalConfiguration.get().setServers(listOf(CnbServer.defaultServer()))
        val project = jenkins.createFreeStyleProject("publisher-danger")
        val publisher = CnbPullRequestActionPublisher("merge")
        publisher.setRepository("team/project")
        publisher.setPullRequestNumber("42")
        project.publishersList.add(publisher)

        val run = jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0).get())

        jenkins.assertLogContains("requires the explicit 'Confirm dangerous action' option", run)
    }

    @Test
    fun `confirmed dangerous Publisher actions fail closed without a resolvable full SHA`(jenkins: JenkinsRule) {
        CnbGlobalConfiguration.get().setServers(listOf(CnbServer.defaultServer()))
        listOf("approve", "request_changes", "merge").forEach { operation ->
            val project = jenkins.createFreeStyleProject("publisher-missing-sha-$operation")
            val publisher =
                if (operation == "merge") {
                    CnbPullRequestActionPublisher("merge")
                } else {
                    CnbPullRequestActionPublisher("review").apply { setReviewAction(operation) }
                }
            publisher.setRepository("team/project")
            publisher.setPullRequestNumber("42")
            publisher.setConfirmDangerousAction(true)
            project.publishersList.add(publisher)

            val scheduled = requireNotNull(project.scheduleBuild2(0))
            val run = jenkins.assertBuildStatus(Result.FAILURE, scheduled.get())

            jenkins.assertLogContains("CNB commit SHA could not be resolved", run)
        }
    }

    @Test
    fun `Publisher validates supported operations and exposes safe descriptor options`() {
        assertThrows(IllegalArgumentException::class.java) { CnbPullRequestActionPublisher("delete") }
        val publisher = CnbPullRequestActionPublisher("review")
        assertThrows(IllegalArgumentException::class.java) { publisher.setReviewAction("pending") }
        assertThrows(IllegalArgumentException::class.java) { publisher.setMergeMethod("fast-forward") }
        val descriptor = CnbPullRequestActionPublisher.DescriptorImpl()
        assertEquals(listOf("comment", "review", "merge"), descriptor.doFillActionItems().map { it.value })
        assertEquals(listOf("merge", "squash", "rebase"), descriptor.doFillMergeMethodItems().map { it.value })
        assertFalse(publisher.requiresWorkspace())
    }

    @Test
    fun `Publisher validates optional source SHA as a full commit identity`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("publisher-sha-validation")
        val descriptor = CnbPullRequestActionPublisher.DescriptorImpl()

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckSha(project, null).kind)
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckSha(project, "a".repeat(40)).kind)
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckSha(project, "A".repeat(64)).kind)
        listOf("a".repeat(7), "a".repeat(39), "a".repeat(41), "g".repeat(40)).forEach { invalid ->
            assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckSha(project, invalid).kind)
        }
    }

    private fun configureServer(server: HttpServer) {
        val configured =
            CnbServer(
                "local-cnb",
                "Local CNB",
                "http://127.0.0.1:${server.address.port}",
                "http://127.0.0.1:${server.address.port}",
            )
        configured.setAllowInsecureHttp(true)
        configured.setAllowPrivateNetwork(true)
        CnbGlobalConfiguration.get().setServers(listOf(configured))
    }

    private fun commentPublisher(onlyOnSuccess: Boolean): CnbPullRequestActionPublisher =
        CnbPullRequestActionPublisher("comment").apply {
            setServerId("local-cnb")
            setRepository("team/project")
            setPullRequestNumber("42")
            setSha("A".repeat(40))
            setBody("build finished")
            setOnlyOnSuccess(onlyOnSuccess)
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
