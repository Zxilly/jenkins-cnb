package dev.zxilly.jenkins.cnb.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.zxilly.jenkins.cnb.api.model.CnbCommitAnnotation
import dev.zxilly.jenkins.cnb.config.CnbServer
import hudson.util.Secret
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class HttpCnbClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: HttpCnbClient
    private val requests = CopyOnWriteArrayList<Request>()
    private val handlers = ConcurrentHashMap<String, (HttpExchange) -> Unit>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests +=
                Request(
                    exchange.requestMethod,
                    exchange.requestURI.rawPath,
                    exchange.requestURI.rawQuery.orEmpty(),
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8),
                )
            val handler = handlers[exchange.requestURI.path]
            if (handler == null) respond(exchange, 404, "{\"errcode\":404,\"errmsg\":\"missing\"}") else handler(exchange)
        }
        server.start()
        client = newClient()
    }

    private fun newClient(
        requestTimeoutSeconds: Int = 30,
        token: Secret? = Secret.fromString("top-secret"),
    ): HttpCnbClient {
        val configured =
            CnbServer(
                "test",
                "Test",
                "http://127.0.0.1:${server.address.port}",
                "http://127.0.0.1:${server.address.port}",
            )
        configured.setAllowInsecureHttp(true)
        configured.setAllowPrivateNetwork(true)
        configured.setRequestTimeoutSeconds(requestTimeoutSeconds)
        return HttpCnbClient(configured, token)
    }

    @AfterEach
    fun tearDown() {
        client.close()
        server.stop(0)
    }

    @Test
    fun `maps the authenticated user response`() {
        handlers["/user"] = { exchange ->
            respond(
                exchange,
                200,
                """{"username":"alice","nickname":"Alice CNB","email":"alice@example.com"}""",
            )
        }

        val user = client.testConnection()

        assertEquals("alice", user.username)
        assertEquals("Alice CNB", user.nickname)
        assertEquals("alice@example.com", user.email)
        assertEquals("GET", requests.single().method)
        assertEquals("/user", requests.single().rawPath)
    }

    @Test
    fun `retrieves repository and default branch with encoded path segments`() {
        handlers["/group/sub repo/project"] = { exchange ->
            respond(
                exchange,
                200,
                """{"id":"42","path":"group/sub repo/project","name":"project","web_url":"https://cnb.cool/group/sub%20repo/project","visibility_level":"Private","status":"active"}""",
            )
        }
        handlers["/group/sub repo/project/-/git/head"] = { exchange ->
            respond(exchange, 200, """{"name":"main","protected":true}""")
        }

        val repository = client.getRepository("group/sub repo/project")

        assertEquals("42", repository.id)
        assertEquals("main", repository.defaultBranch)
        assertEquals("https://cnb.cool/group/sub%20repo/project", repository.cloneUrl)
        assertTrue(requests.all { it.authorization == "Bearer top-secret" })
        assertTrue(requests.first().rawPath.contains("sub%20repo"))
    }

    @Test
    fun `maps branch listings and an encoded branch lookup`() {
        handlers["/org/repo/-/git/branches"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """[{"name":"feature/space name","commit":{"sha":"branch-sha"},"protected":true,"locked":true}]"""
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }
        handlers["/org/repo/-/git/branches/feature/space name"] = { exchange ->
            respond(exchange, 200, """{"name":"feature/space name","sha":"direct-sha"}""")
        }

        val branches = client.listBranches("org/repo")
        val branch = client.getBranch("org/repo", "feature/space name")

        assertEquals(listOf("feature/space name"), branches.map { it.name })
        assertEquals("branch-sha", branches.single().sha)
        assertTrue(branches.single().protected)
        assertTrue(branches.single().locked)
        assertEquals("direct-sha", branch.sha)
        assertTrue(requests.last().rawPath.endsWith("/feature%2Fspace%20name"))
    }

    @Test
    fun `maps tags with committer and author timestamps`() {
        handlers["/org/repo/-/git/tags"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """
                    [
                        {"name":"v1.0","commit":{"sha":"tag-one","commit":{"committer":{"date":"2026-07-15T08:30:00Z"}}}},
                        {"name":"v2.0","commit":{"sha":"tag-two","commit":{"author":{"date":"not-an-instant"}}}}
                    ]
                    """.trimIndent()
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val tags = client.listTags("org/repo")

        assertEquals(listOf("v1.0", "v2.0"), tags.map { it.name })
        assertEquals("tag-one", tags.first().sha)
        assertEquals(Instant.parse("2026-07-15T08:30:00Z").toEpochMilli(), tags.first().timestamp)
        assertEquals(0, tags.last().timestamp)
    }

    @Test
    fun `maps pull request listings including fork and draft metadata`() {
        handlers["/org/repo/-/pulls"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """
                    [{
                        "number":"7","title":"Improve build","state":"closed",
                        "head":{"ref":"refs/heads/feature","sha":"head-sha","repo":{"path":"fork/repo"}},
                        "base":{"ref":"refs/heads/main","sha":"base-sha","repo":{"path":"org/repo"}},
                        "merge_sha":"merge-sha","author":{"username":"alice"},"is_wip":true,
                        "updated_at":"2026-07-15T09:15:00Z"
                    }]
                    """.trimIndent()
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val pullRequest = client.listPullRequests("org/repo", "closed").single()

        assertEquals("7", pullRequest.number)
        assertEquals("feature", pullRequest.sourceBranch)
        assertEquals("main", pullRequest.targetBranch)
        assertEquals("fork/repo", pullRequest.sourceRepo)
        assertEquals("org/repo", pullRequest.targetRepo)
        assertEquals("merge-sha", pullRequest.mergeSha)
        assertEquals("alice", pullRequest.author)
        assertTrue(pullRequest.fromFork)
        assertTrue(pullRequest.draft)
        assertEquals(Instant.parse("2026-07-15T09:15:00Z").toEpochMilli(), pullRequest.updatedAt)
        assertTrue(requests.first().query.contains("state=closed"))
        assertTrue(requests.first().query.contains("order_by=-updated_at"))
    }

    @Test
    fun `maps pull request lookup fallbacks and rejects unsupported states`() {
        handlers["/org/repo/-/pulls/8"] = { exchange ->
            respond(
                exchange,
                200,
                """
                {
                    "number":"8","title":"Fallback fields","state":"open",
                    "head":{"ref":"topic","sha":"head"},"base":{"ref":"main","sha":"base"},
                    "merge_commit_sha":"fallback-merge","user":{"username":"bob"},
                    "last_acted_at":"invalid"
                }
                """.trimIndent(),
            )
        }

        val pullRequest = client.getPullRequest("org/repo", "8")

        assertEquals("org/repo", pullRequest.sourceRepo)
        assertEquals("org/repo", pullRequest.targetRepo)
        assertEquals("fallback-merge", pullRequest.mergeSha)
        assertEquals("bob", pullRequest.author)
        assertFalse(pullRequest.fromFork)
        assertEquals(0, pullRequest.updatedAt)
        assertThrows(IllegalArgumentException::class.java) {
            client.listPullRequests("org/repo", "merged")
        }
    }

    @Test
    fun `paginates until an empty page and requests Guest role`() {
        handlers["/user/repos"] = { exchange ->
            val page = query(exchange.requestURI.rawQuery)["page"]
            val body =
                if (page == "1") {
                    """[{"id":"1","path":"org/one","name":"one","web_url":"https://cnb.cool/org/one"}]"""
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val repositories = client.listUserRepositories()

        assertEquals(listOf("org/one"), repositories.map { it.path })
        assertEquals(2, requests.size)
        assertTrue(requests.first().query.contains("role=Guest"))
        assertTrue(requests.first().query.contains("page_size=100"))
    }

    @Test
    fun `falls back to user repositories and parses numeric archived status`() {
        handlers["/users/alice/repos"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """[{"id":"9","path":"alice/old-project","name":"old-project","status":1,"web_url":"https://cnb.cool/alice/old-project"}]"""
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val repositories = client.listRepositories("alice", includeDescendants = true)

        assertEquals(listOf("alice/old-project"), repositories.map { it.path })
        assertTrue(repositories.single().archived)
        assertEquals("/alice/-/repos", requests.first().rawPath)
        assertEquals("/users/alice/repos", requests[1].rawPath)
    }

    @Test
    fun `deduplicates resources repeated across pagination boundaries`() {
        handlers["/user/repos"] = { exchange ->
            val body =
                when (query(exchange.requestURI.rawQuery)["page"]) {
                    "1" -> """[{"id":"1","path":"org/one"},{"id":"2","path":"org/two"}]"""
                    "2" -> """[{"id":"2","path":"org/two"},{"id":"3","path":"org/three"}]"""
                    else -> "[]"
                }
            respond(exchange, 200, body)
        }

        val repositories = client.listUserRepositories()

        assertEquals(listOf("org/one", "org/two", "org/three"), repositories.map { it.path })
    }

    @Test
    fun `rejects a repeated pagination page without looping forever`() {
        handlers["/user/repos"] = { exchange ->
            respond(exchange, 200, """[{"id":"same-page","path":"org/repeated"}]""")
        }

        val failure = assertThrows(CnbApiException::class.java) { client.listUserRepositories() }

        assertTrue(failure.message.orEmpty().contains("repeated pagination page"))
        assertEquals(2, requests.size)
    }

    @Test
    fun `reports malformed remote repository paths as API failures`() {
        handlers["/user/repos"] = { exchange ->
            respond(exchange, 200, """[{"id":"malformed","path":"org/../repo"}]""")
        }

        val failure = assertThrows(CnbApiException::class.java) { client.listUserRepositories() }

        assertTrue(failure.message.orEmpty().contains("invalid path"))
        assertTrue(failure.cause is IllegalArgumentException)
        assertEquals(1, requests.size)
    }

    @Test
    fun `reports a missing remote repository path as an API failure`() {
        handlers["/user/repos"] = { exchange ->
            respond(exchange, 200, """[{"id":"missing-path"}]""")
        }

        val failure = assertThrows(CnbApiException::class.java) { client.listUserRepositories() }

        assertTrue(failure.message.orEmpty().contains("did not include a path"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `accepts an empty no-content pagination response`() {
        handlers["/user/repos"] = { exchange -> respond(exchange, 204, "") }

        assertTrue(client.listUserRepositories().isEmpty())
        assertEquals(1, requests.size)
    }

    @Test
    fun `rejects an oversized single API response`() {
        val padding = "x".repeat(4 * 1024 * 1024)
        handlers["/user/repos"] = { exchange ->
            respond(exchange, 200, """[{"id":"1","path":"org/one","padding":"$padding"}]""")
        }

        val error = assertThrows(CnbApiException::class.java) { client.listUserRepositories() }

        assertTrue(error.message.orEmpty().contains("response exceeded"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `rejects pagination whose aggregate response budget is exceeded`() {
        val padding = "x".repeat(3_500_000)
        handlers["/user/repos"] = { exchange ->
            val page = query(exchange.requestURI.rawQuery)["page"].orEmpty()
            respond(exchange, 200, """[{"id":"$page","path":"org/repo-$page","padding":"$padding"}]""")
        }

        val error = assertThrows(CnbApiException::class.java) { client.listUserRepositories() }

        assertTrue(error.message.orEmpty().contains("pagination byte limit"))
        assertEquals(5, requests.size)
    }

    @Test
    fun `allows the maximum number of data pages followed by an empty sentinel page`() {
        handlers["/user/repos"] = { exchange ->
            val page = query(exchange.requestURI.rawQuery)["page"]!!.toInt()
            val body =
                if (page <= 100) {
                    buildString {
                        append('[')
                        repeat(100) { offset ->
                            if (offset > 0) append(',')
                            val id = (page - 1) * 100 + offset
                            append("""{"id":"$id","path":"org/repo-$id"}""")
                        }
                        append(']')
                    }
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val repositories = client.listUserRepositories()

        assertEquals(10_000, repositories.size)
        assertEquals("org/repo-0", repositories.first().path)
        assertEquals("org/repo-9999", repositories.last().path)
        assertEquals(101, requests.size)
    }

    @Test
    fun `request timeout covers a slow response body and releases capacity`() {
        client.close()
        client = newClient(requestTimeoutSeconds = 1)
        handlers["/org/repo/-/pulls/7/comments"] = { exchange ->
            val bytes = """{"id":"1","body":"ok"}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            try {
                exchange.responseBody.use { output ->
                    output.write(bytes, 0, 1)
                    output.flush()
                    Thread.sleep(1_500)
                    output.write(bytes, 1, bytes.size - 1)
                }
            } catch (_: Exception) {
                exchange.close()
            }
        }

        val started = System.nanoTime()
        val error =
            assertThrows(CnbApiException::class.java) {
                client.createPullComment("org/repo", "7", "hello")
            }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(error.retryable)
        assertTrue(elapsedMillis < 5_000, "slow body exceeded the configured request deadline: ${elapsedMillis}ms")
        handlers["/org/repo/-/pulls/7/comments"] = { exchange ->
            respond(exchange, 200, """{"id":"2","body":"recovered"}""")
        }
        val recovered = client.createPullComment("org/repo", "7", "hello again")
        assertEquals("2", recovered.id)
        assertEquals(2, requests.size)
    }

    @Test
    fun `retries an idempotent throttled request using Retry-After`() {
        var attempt = 0
        handlers["/user"] = { exchange ->
            attempt++
            if (attempt == 1) {
                exchange.responseHeaders.add("Retry-After", "0")
                respond(exchange, 429, """{"errcode":"RATE_LIMIT","errmsg":"slow down"}""")
            } else {
                respond(exchange, 200, """{"username":"recovered"}""")
            }
        }

        val user = client.testConnection()

        assertEquals("recovered", user.username)
        assertEquals(2, requests.size)
    }

    @Test
    fun `reports structured non-retryable errors without leaking credentials`() {
        handlers["/user"] = { exchange ->
            respond(
                exchange,
                401,
                """{"errcode":"AUTH_DENIED","errmsg":"Bearer top-secret denied\\nAuthorization: top-secret"}""",
            )
        }

        val failure = assertThrows(CnbApiException::class.java) { client.testConnection() }

        assertEquals(401, failure.statusCode)
        assertEquals("AUTH_DENIED", failure.errorCode)
        assertFalse(failure.retryable)
        assertFalse(failure.message.orEmpty().contains("top-secret"))
        assertTrue(failure.message.orEmpty().contains("[redacted]"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `does not copy an arbitrary HTML error body into diagnostics`() {
        handlers["/org/repo/-/git/commit-annotations/sha/key"] = { exchange ->
            respond(exchange, 400, "<html><body>proxy secret details</body></html>")
        }

        val failure =
            assertThrows(CnbApiException::class.java) {
                client.deleteCommitAnnotation("org/repo", "sha", "key")
            }

        assertEquals(400, failure.statusCode)
        assertEquals("CNB API returned HTTP 400", failure.message)
        assertFalse(failure.retryable)
        assertEquals(1, requests.size)
    }

    @Test
    fun `rejects an empty authenticated-user response`() {
        handlers["/user"] = { exchange -> respond(exchange, 204, "") }

        val failure = assertThrows(CnbApiException::class.java) { client.testConnection() }

        assertTrue(failure.message.orEmpty().contains("empty user response"))
    }

    @Test
    fun `omits authorization when the client has no token`() {
        client.close()
        client = newClient(token = null)
        handlers["/user"] = { exchange -> respond(exchange, 200, """{"username":"anonymous"}""") }

        assertEquals("anonymous", client.testConnection().username)
        assertNull(requests.single().authorization)
    }

    @Test
    fun `returns directory entries for complete SCM file traversal`() {
        handlers["/org/repo/-/git/contents"] = { exchange ->
            respond(
                exchange,
                200,
                """{"path":"","sha":"tree-sha","type":"tree","size":0,"entries":[{"name":"src","path":"src","sha":"src-sha","type":"tree","size":0},{"name":"Jenkinsfile","path":"Jenkinsfile","sha":"file-sha","type":"blob","size":42}]}""",
            )
        }

        val content = requireNotNull(client.getContent("org/repo", "", "main"))

        assertEquals("tree", content.type)
        assertEquals(listOf("src", "Jenkinsfile"), content.entries.map { it.name })
        assertEquals("ref=main", requests.single().query)
    }

    @Test
    fun `returns null for missing normalized content paths`() {
        handlers["/org/repo/-/git/contents/docs/missing file"] = { exchange ->
            respond(exchange, 404, """{"errcode":404,"errmsg":"not found"}""")
        }

        val content = client.getContent("org/repo", "docs\\missing file", "feature/x")

        assertNull(content)
        assertTrue(requests.single().rawPath.endsWith("/docs/missing%20file"))
        assertEquals("ref=feature%2Fx", requests.single().query)
    }

    @Test
    fun `maps commit statuses from an items envelope`() {
        handlers["/org/repo/-/git/commit-statuses/main"] = { exchange ->
            respond(
                exchange,
                200,
                """
                {"items":[{
                    "context":"jenkins/build","state":"success","description":"Build passed",
                    "target_url":"https://jenkins.example/job/1","created_at":"created","updated_at":"updated"
                }]}
                """.trimIndent(),
            )
        }

        val status = client.listCommitStatuses("org/repo", "main").single()

        assertEquals("jenkins/build", status.context)
        assertEquals("success", status.state)
        assertEquals("Build passed", status.description)
        assertEquals("https://jenkins.example/job/1", status.targetUrl)
        assertEquals("created", status.createdAt)
        assertEquals("updated", status.updatedAt)
    }

    @Test
    fun `maps valid commit annotations and ignores entries without a key`() {
        handlers["/org/repo/-/git/commit-annotations/abc123"] = { exchange ->
            respond(
                exchange,
                200,
                """[{"key":"jenkins/result","value":"SUCCESS"},{"value":"ignored"}]""",
            )
        }

        val annotations = client.getCommitAnnotations("org/repo", "abc123")

        assertEquals(listOf(CnbCommitAnnotation("jenkins/result", "SUCCESS")), annotations)
    }

    @Test
    fun `treats missing annotations as empty and deletes an encoded annotation key`() {
        handlers["/org/repo/-/git/commit-annotations/missing"] = { exchange ->
            respond(exchange, 404, """{"errcode":404}""")
        }
        handlers["/org/repo/-/git/commit-annotations/abc123/jenkins/job result"] = { exchange ->
            respond(exchange, 204, "")
        }

        assertTrue(client.getCommitAnnotations("org/repo", "missing").isEmpty())
        client.deleteCommitAnnotation("org/repo", "abc123", "jenkins/job result")

        val delete = requests.last()
        assertEquals("DELETE", delete.method)
        assertTrue(delete.rawPath.endsWith("/jenkins%2Fjob%20result"))
    }

    @Test
    fun `validates annotation batch key and value bounds before sending`() {
        assertThrows(IllegalArgumentException::class.java) {
            client.putCommitAnnotations("org/repo", "sha", List(101) { CnbCommitAnnotation("key-$it", "value") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.putCommitAnnotations("org/repo", "sha", listOf(CnbCommitAnnotation("", "value")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.putCommitAnnotations("org/repo", "sha", listOf(CnbCommitAnnotation("key", "x".repeat(16_385))))
        }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `does not retry a non-idempotent comment creation`() {
        handlers["/org/repo/-/pulls/7/comments"] = { exchange ->
            respond(exchange, 503, """{"errcode":503,"errmsg":"temporary"}""")
        }

        val error =
            assertThrows(CnbApiException::class.java) {
                client.createPullComment("org/repo", "7", "hello")
            }

        assertEquals(503, error.statusCode)
        assertEquals(1, requests.size)
    }

    @Test
    fun `maps paginated pull comments`() {
        handlers["/org/repo/-/pulls/7/comments"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """[{"id":"11","body":"Looks good","user":{"username":"alice"},"created_at":"created","updated_at":"updated"}]"""
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val comment = client.listPullComments("org/repo", "7").single()

        assertEquals("11", comment.id)
        assertEquals("Looks good", comment.body)
        assertEquals("alice", comment.author)
        assertEquals("created", comment.createdAt)
        assertEquals("updated", comment.updatedAt)
    }

    @Test
    fun `maps created and updated pull comments with JSON request bodies`() {
        handlers["/org/repo/-/pulls/7/comments"] = { exchange ->
            respond(
                exchange,
                201,
                """{"id":"12","body":"Created","author":{"username":"creator"}}""",
            )
        }
        handlers["/org/repo/-/pulls/7/comments/12"] = { exchange ->
            respond(
                exchange,
                200,
                """{"id":"12","body":"Updated","user":{"username":"editor"}}""",
            )
        }

        val created = client.createPullComment("org/repo", "7", "Created")
        val updated = client.updatePullComment("org/repo", "7", "12", "Updated")

        assertEquals("creator", created.author)
        assertEquals("Updated", updated.body)
        assertEquals("editor", updated.author)
        assertEquals("POST", requests.first().method)
        assertTrue(requests.first().body.contains("Created"))
        assertEquals("PATCH", requests.last().method)
        assertTrue(requests.last().body.contains("Updated"))
    }

    @Test
    fun `validates pull comment content before sending`() {
        assertThrows(IllegalArgumentException::class.java) {
            client.createPullComment("org/repo", "7", "  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.updatePullComment("org/repo", "7", "12", "x".repeat(60_001))
        }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `wraps malformed non-idempotent JSON responses without retrying`() {
        handlers["/org/repo/-/pulls/7/comments"] = { exchange ->
            respond(exchange, 201, "{")
        }

        val failure =
            assertThrows(CnbApiException::class.java) {
                client.createPullComment("org/repo", "7", "hello")
            }

        assertTrue(failure.retryable)
        assertEquals(1, requests.size)
    }

    @Test
    fun `writes annotations through an idempotent PUT`() {
        handlers["/org/repo/-/git/commit-annotations/abc123"] = { exchange ->
            respond(exchange, 204, "")
        }

        client.putCommitAnnotations(
            "org/repo",
            "abc123",
            listOf(CnbCommitAnnotation("jenkins/job/result", "SUCCESS")),
        )

        val request = requests.single()
        assertEquals("PUT", request.method)
        assertTrue(request.body.contains("jenkins/job/result"))
        assertFalse(request.body.contains("top-secret"))
    }

    @Test
    fun `rejects an insecure repository event redirect before credentials can cross hosts`() {
        handlers["/events/org/repo/-/2026-07-15-10"] = { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/object/events.json")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }

        assertThrows(IllegalArgumentException::class.java) {
            client.listRepositoryEvents("org/repo", ZonedDateTime.parse("2026-07-15T10:00:00Z"))
        }

        assertEquals(1, requests.size)
        assertEquals("Bearer top-secret", requests.single().authorization)
    }

    @Test
    fun `maps repository events from an events envelope and payload objects`() {
        handlers["/events/org/repo/-/2026-07-15-10"] = { exchange ->
            respond(
                exchange,
                200,
                """
                {"events":[
                    {"id":"event-1","type":"PushEvent","created_at":"2026-07-15T10:01:00Z","repo":{"path":"actual/repo"},"payload":{"ref":"refs/heads/main","count":2}},
                    {"id":"event-2","type":"IssueEvent","created_at":"2026-07-15T10:02:00Z","payload":"not-an-object"}
                ]}
                """.trimIndent(),
            )
        }

        val events = client.listRepositoryEvents("org/repo", ZonedDateTime.parse("2026-07-15T10:00:00Z"))

        assertEquals(2, events.size)
        assertEquals("actual/repo", events.first().repositoryPath)
        assertEquals("refs/heads/main", events.first().payload["ref"])
        assertEquals(2, events.first().payload["count"])
        assertEquals("org/repo", events.last().repositoryPath)
        assertTrue(events.last().payload.isEmpty())
        assertEquals("Bearer top-secret", requests.single().authorization)
    }

    @Test
    fun `treats an unexpected repository-events document as empty`() {
        handlers["/events/org/repo/-/2026-07-15-10"] = { exchange -> respond(exchange, 200, "{}") }

        val events = client.listRepositoryEvents("org/repo", ZonedDateTime.parse("2026-07-15T10:00:00Z"))

        assertTrue(events.isEmpty())
        assertEquals(1, requests.size)
    }

    @Test
    fun `retries a transient repository-events response`() {
        var attempt = 0
        handlers["/events/org/repo/-/2026-07-15-10"] = { exchange ->
            attempt++
            if (attempt == 1) {
                respond(exchange, 503, """{"errcode":503,"errmsg":"temporary"}""")
            } else {
                respond(exchange, 200, "[]")
            }
        }

        val events = client.listRepositoryEvents("org/repo", ZonedDateTime.parse("2026-07-15T10:00:00Z"))

        assertTrue(events.isEmpty())
        assertEquals(2, requests.size)
    }

    @Test
    fun `rejects a repository-events redirect without a location`() {
        handlers["/events/org/repo/-/2026-07-15-10"] = { exchange ->
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }

        val failure =
            assertThrows(CnbApiException::class.java) {
                client.listRepositoryEvents("org/repo", ZonedDateTime.parse("2026-07-15T10:00:00Z"))
            }

        assertTrue(failure.message.orEmpty().contains("did not include Location"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `rejects an event hour larger than the persistent deduplication bound`() {
        handlers["/events/org/repo/-/2026-07-15-10"] = { exchange ->
            val events =
                (0..MAX_REPOSITORY_EVENTS_PER_HOUR).joinToString(prefix = "[", postfix = "]") { index ->
                    """{"id":"$index","type":"PushEvent"}"""
                }
            respond(exchange, 200, events)
        }

        assertThrows(CnbApiException::class.java) {
            client.listRepositoryEvents("org/repo", ZonedDateTime.parse("2026-07-15T10:00:00Z"))
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
        if (status != 204) exchange.responseBody.use { it.write(bytes) } else exchange.close()
    }

    private fun query(rawQuery: String): Map<String, String> =
        rawQuery.split('&').filter { it.isNotBlank() }.associate {
            val parts = it.split('=', limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }

    private data class Request(
        val method: String,
        val rawPath: String,
        val query: String,
        val authorization: String?,
        val body: String,
    )
}
