package dev.zxilly.jenkins.cnb.status

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.config.CnbStatusReportingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

@WithJenkins
class CnbBuildMetadataReporterIntegrationTest {
    @Test
    fun `reporter reconciles annotations and a pull comment through the real CNB HTTP adapter`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            requests += exchange.requestMethod to exchange.requestURI.path
            when {
                exchange.requestMethod == "PUT" && exchange.requestURI.path.contains("commit-annotations") -> {
                    exchange.requestBody.readAllBytes()
                    respond(exchange, 200, "{}")
                }

                exchange.requestMethod == "GET" && exchange.requestURI.path.endsWith("/comments") -> {
                    respond(exchange, 200, "[]")
                }

                exchange.requestMethod == "POST" && exchange.requestURI.path.endsWith("/comments") -> {
                    exchange.requestBody.readAllBytes()
                    respond(exchange, 201, """{"id":"comment-9","body":"created"}""")
                }

                else -> {
                    respond(exchange, 404, """{"errmsg":"missing"}""")
                }
            }
        }
        http.start()
        try {
            configureServer(http, CnbStatusReportingMode.BOTH)

            val result = CnbBuildMetadataReporter.report(snapshot(), item = null)

            assertEquals("comment-9", result.commentId)
            assertEquals(
                listOf(
                    "PUT" to "/contributor/repo/-/git/commit-annotations/${"a".repeat(40)}",
                    "GET" to "/team/project/-/pulls/42/comments",
                    "POST" to "/team/project/-/pulls/42/comments",
                ),
                requests.toList(),
            )
        } finally {
            http.stop(0)
        }
    }

    @Test
    fun `disabled server preserves a known comment without constructing a transport`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val server = CnbServer.defaultServer()
        server.setStatusReportingMode(CnbStatusReportingMode.DISABLED)
        CnbGlobalConfiguration.get().setServers(listOf(server))

        val original = snapshot()
        val disabledSnapshot =
            original.copy(
                target = original.target.copy(serverId = "cnb-cool"),
                knownCommentId = "known-comment",
            )
        val result = CnbBuildMetadataReporter.report(disabledSnapshot, null)

        assertEquals("known-comment", result.commentId)
    }

    @Test
    fun `automatic badge reports through the real CNB HTTP adapter when metadata destinations are disabled`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val bodies = CopyOnWriteArrayList<String>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            requests += exchange.requestMethod to exchange.requestURI.path
            bodies += exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val base = "http://127.0.0.1:${http.address.port}/contributor/repo/-/badge"
            respond(
                exchange,
                200,
                """{"url":"$base/git/aaaaaaaa/security/tca","latest":"$base/git/latest/security/tca"}""",
            )
        }
        http.start()
        try {
            configureServer(http, CnbStatusReportingMode.DISABLED, automaticBadge = true)

            CnbBuildMetadataReporter.report(snapshot(), item = null)

            assertEquals(listOf("POST" to "/contributor/repo/-/badge/upload"), requests.toList())
            val body = bodies.single()
            assertTrue(body.contains("\"key\":\"security/tca\""))
            assertTrue(body.contains("\"message\":\"success\""))
            assertTrue(body.contains("\"latest\":true"))
            assertTrue(body.contains("\"sha\":\"${"a".repeat(40)}\""))
        } finally {
            http.stop(0)
        }
    }

    private fun configureServer(
        http: HttpServer,
        mode: CnbStatusReportingMode,
        automaticBadge: Boolean = false,
    ) {
        val base = "http://127.0.0.1:${http.address.port}"
        val server = CnbServer("local", "Local CNB", base, base)
        server.setAllowInsecureHttp(true)
        server.setAllowPrivateNetwork(true)
        server.setStatusReportingMode(mode)
        server.setAutomaticBuildBadgeEnabled(automaticBadge)
        server.setAutomaticBuildBadgeKey("security/tca")
        CnbGlobalConfiguration.get().setServers(listOf(server))
    }

    private fun snapshot() =
        CnbBuildMetadataSnapshot(
            version = 1,
            markerToken = "reporter-integration",
            target =
                CnbBuildMetadataTarget(
                    serverId = "local",
                    repository = "team/project",
                    commitRepository = "contributor/repo",
                    sha = "a".repeat(40),
                    pullRequestNumber = "42",
                    context = "integration/job",
                    credentialsId = null,
                ),
            state = CnbBuildMetadataState.SUCCESS,
            stateChangedAt = "2026-07-15T10:00:00Z",
            buildDisplayName = "integration #1",
            buildUrl = "https://jenkins.example/job/integration/1/",
            knownCommentId = null,
        )

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
