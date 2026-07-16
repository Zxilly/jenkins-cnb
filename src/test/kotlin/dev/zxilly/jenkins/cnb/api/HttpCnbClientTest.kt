package dev.zxilly.jenkins.cnb.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.zxilly.jenkins.cnb.api.model.CnbBuildEventName
import dev.zxilly.jenkins.cnb.api.model.CnbBuildNpcName
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStageStatus
import dev.zxilly.jenkins.cnb.api.model.CnbBuildState
import dev.zxilly.jenkins.cnb.api.model.CnbBuildTriggerEvent
import dev.zxilly.jenkins.cnb.api.model.CnbCommitAnnotation
import dev.zxilly.jenkins.cnb.api.model.CnbCommitDiffStatus
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatusState
import dev.zxilly.jenkins.cnb.api.model.CnbContentType
import dev.zxilly.jenkins.cnb.api.model.CnbCreatePullRequestRequest
import dev.zxilly.jenkins.cnb.api.model.CnbCreateReleaseRequest
import dev.zxilly.jenkins.cnb.api.model.CnbGitFileMode
import dev.zxilly.jenkins.cnb.api.model.CnbPullBlockedReason
import dev.zxilly.jenkins.cnb.api.model.CnbPullFileStatus
import dev.zxilly.jenkins.cnb.api.model.CnbPullMergeStyle
import dev.zxilly.jenkins.cnb.api.model.CnbPullMergeableState
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestListState
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestState
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewDiffLineType
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewEvent
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewReplyRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewSide
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewState
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewSubjectType
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAssetUploadRequest
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseMakeLatest
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryRefType
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryStatus
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryVisibility
import dev.zxilly.jenkins.cnb.api.model.CnbTagAnnotation
import dev.zxilly.jenkins.cnb.api.model.CnbUpdatePullRequestRequest
import dev.zxilly.jenkins.cnb.api.model.CnbUpdateReleaseRequest
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HttpCnbClientTest {
    private lateinit var server: HttpServer
    private lateinit var serverExecutor: ExecutorService
    private lateinit var client: HttpCnbClient
    private val requests = CopyOnWriteArrayList<Request>()
    private val handlers = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
    private val shaA = "a".repeat(40)
    private val shaB = "b".repeat(40)
    private val shaC = "c".repeat(40)
    private val shaD = "d".repeat(40)
    private val shaE = "e".repeat(40)
    private val shaF = "f".repeat(40)
    private val sha256 = "1".repeat(64)

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverExecutor = Executors.newFixedThreadPool(2)
        server.executor = serverExecutor
        server.createContext("/") { exchange ->
            requests +=
                Request(
                    exchange.requestMethod,
                    exchange.requestURI.rawPath,
                    exchange.requestURI.rawQuery.orEmpty(),
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestHeaders["Accept"].orEmpty(),
                    exchange.requestHeaders.getFirst("Content-Length"),
                    exchange.requestHeaders.getFirst("Transfer-Encoding"),
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
        try {
            client.close()
        } finally {
            server.stop(0)
            serverExecutor.shutdownNow()
            assertTrue(serverExecutor.awaitTermination(5, TimeUnit.SECONDS), "test HTTP executor did not stop")
        }
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
        assertEquals(listOf(CNB_MEDIA_TYPE), requests.single().accept)
    }

    @Test
    fun `retries a retryable status before interpreting a malformed error content type`() {
        val attempts = AtomicInteger()
        handlers["/user"] = { exchange ->
            if (attempts.incrementAndGet() == 1) {
                exchange.responseHeaders.add("Content-Type", "not a media type")
                val body = "temporary".toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(503, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                respond(exchange, 200, """{"username":"alice","nickname":"","email":""}""")
            }
        }

        val user = client.testConnection()

        assertEquals("alice", user.username)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `retrieves repository and default branch with encoded path segments`() {
        handlers["/group/子组/project"] = { exchange ->
            respond(
                exchange,
                200,
                """{"id":"42","path":"group/子组/project","name":"project","web_url":"https://cnb.cool/group/%E5%AD%90%E7%BB%84/project","visibility_level":"Private","status":"active"}""",
            )
        }
        handlers["/group/子组/project/-/git/head"] = { exchange ->
            respond(exchange, 200, """{"name":"refs/heads/main","protected":true}""")
        }

        val repository = client.getRepository("group/子组/project")
        val configuredWebRoot = "http://127.0.0.1:${server.address.port}"

        assertEquals("42", repository.id)
        assertEquals("main", repository.defaultBranch)
        assertEquals("$configuredWebRoot/group/%E5%AD%90%E7%BB%84/project", repository.webUrl)
        assertEquals("$configuredWebRoot/group/%E5%AD%90%E7%BB%84/project", repository.cloneUrl)
        assertTrue(requests.all { it.authorization == "Bearer top-secret" })
        assertTrue(requests.first().rawPath.contains("%E5%AD%90%E7%BB%84"))
    }

    @Test
    fun `derives repository links from configured origin and ignores untrusted response targets`() {
        val configuredWebRoot = "http://127.0.0.1:${server.address.port}"
        handlers["/user/repos"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """
                    [
                      {"id":"1","path":"org/cross-origin","web_url":"https://evil.example/org/cross-origin"},
                      {"id":"2","path":"org/userinfo","web_url":"http://attacker:secret@127.0.0.1:${server.address.port}/org/userinfo"},
                      {"id":"3","path":"org/query-fragment","web_url":"$configuredWebRoot/org/query-fragment?token=secret#redirect"},
                      {"id":"4","path":"org/right-repo","web_url":"$configuredWebRoot/org/wrong-repo"}
                    ]
                    """.trimIndent()
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val repositories = client.listUserRepositories()
        val expected =
            listOf(
                "$configuredWebRoot/org/cross-origin",
                "$configuredWebRoot/org/userinfo",
                "$configuredWebRoot/org/query-fragment",
                "$configuredWebRoot/org/right-repo",
            )

        assertEquals(expected, repositories.map { it.webUrl })
        assertEquals(expected, repositories.map { it.cloneUrl })
        assertTrue(repositories.all { it.status == CnbRepositoryStatus.UNKNOWN })
        assertTrue(repositories.all { it.visibility == CnbRepositoryVisibility.UNKNOWN })
        assertTrue(repositories.none { it.cloneable })
        assertTrue(
            repositories.none { repository ->
                listOf("evil.example", "@", "?", "#", "/org/wrong-repo").any { it in repository.webUrl }
            },
        )
    }

    @Test
    fun `maps branch listings and an encoded branch lookup`() {
        handlers["/org/repo/-/git/branches"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """[{"name":"feature/release-2026","commit":{"sha":"$shaA"},"protected":true,"locked":true}]"""
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }
        handlers["/org/repo/-/git/branches/feature/release-2026"] = { exchange ->
            respond(exchange, 200, """{"name":"refs/heads/feature/release-2026","sha":"$sha256"}""")
        }

        val branches = client.listBranches("org/repo")
        val branch = client.getBranch("org/repo", "feature/release-2026")

        assertEquals(listOf("feature/release-2026"), branches.map { it.name })
        assertEquals(shaA, branches.single().sha)
        assertTrue(branches.single().protected)
        assertTrue(branches.single().locked)
        assertEquals("feature/release-2026", branch.name)
        assertEquals(sha256, branch.sha)
        assertTrue(requests.last().rawPath.endsWith("/feature%2Frelease-2026"))
    }

    @Test
    fun `maps tags with committer and author timestamps`() {
        handlers["/org/repo/-/git/tags"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """
                    [
                        {"name":"v1.0","commit":{"sha":"$shaA","commit":{"committer":{"date":"2026-07-15T08:30:00Z"}}}},
                        {"name":"v2.0","commit":{"sha":"$shaB","commit":{"author":{"date":"2026-07-15T09:30:00Z"}}}}
                    ]
                    """.trimIndent()
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val tags = client.listTags("org/repo")

        assertEquals(listOf("v1.0", "v2.0"), tags.map { it.name })
        assertEquals(shaA, tags.first().sha)
        assertEquals(Instant.parse("2026-07-15T08:30:00Z").toEpochMilli(), tags.first().timestamp)
        assertEquals(Instant.parse("2026-07-15T09:30:00Z").toEpochMilli(), tags.last().timestamp)
    }

    @Test
    fun `rejects invalid nonempty tag timestamps`() {
        handlers["/org/repo/-/git/tags/v1.0"] = { exchange ->
            respond(
                exchange,
                200,
                """{"name":"v1.0","commit":{"sha":"$shaA","commit":{"author":{"date":"not-an-instant"}}}}""",
            )
        }

        assertThrows(CnbApiException::class.java) { client.getTag("org/repo", "v1.0") }
    }

    @Test
    fun `gets an encoded tag and ignores unknown response fields`() {
        handlers["/org/repo/-/git/tags/release/2026-07"] = { exchange ->
            respond(
                exchange,
                200,
                """{"name":"release/2026-07","commit":{"sha":"$shaA"},"future_field":{"ignored":true}}""",
            )
        }

        val tag = client.getTag("org/repo", "release/2026-07")

        assertEquals("release/2026-07", tag.name)
        assertEquals(shaA, tag.sha)
        assertTrue(requests.single().rawPath.endsWith("/release%2F2026-07"))
    }

    @Test
    fun `maps pull request listings including fork and draft metadata`() {
        handlers["/org/repo/-/pulls"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """
                    [{
                        "number":"7","title":"Improve build","state":"closed",
                        "head":{"ref":"refs/heads/feature","sha":"$shaA","repo":{"path":"fork/repo"}},
                        "base":{"ref":"refs/heads/main","sha":"$shaB","repo":{"path":"org/repo"}},
                        "merge_sha":"$shaC","author":{"username":"alice"},"is_wip":true,
                        "updated_at":"2026-07-15T09:15:00Z"
                    }]
                    """.trimIndent()
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val pullRequest = client.listPullRequests("org/repo", CnbPullRequestListState.CLOSED).single()

        assertEquals("7", pullRequest.number)
        assertEquals("feature", pullRequest.sourceBranch)
        assertEquals("main", pullRequest.targetBranch)
        assertEquals("fork/repo", pullRequest.sourceRepo)
        assertEquals("org/repo", pullRequest.targetRepo)
        assertEquals(shaC, pullRequest.mergeSha)
        assertEquals("alice", pullRequest.author)
        assertTrue(pullRequest.fromFork)
        assertTrue(pullRequest.draft)
        assertEquals(Instant.parse("2026-07-15T09:15:00Z").toEpochMilli(), pullRequest.updatedAt)
        assertTrue(requests.first().query.contains("state=closed"))
        assertTrue(requests.first().query.contains("order_by=-updated_at"))
    }

    @Test
    fun `maps pull request lookup fallbacks`() {
        handlers["/org/repo/-/pulls/8"] = { exchange ->
            respond(
                exchange,
                200,
                """
                {
                    "number":"8","title":"Fallback fields","state":"open",
                    "head":{"ref":"topic","sha":"$shaA"},"base":{"ref":"main","sha":"$shaB"},
                    "merge_commit_sha":"$shaC","user":{"username":"bob"},
                    "last_acted_at":"2026-07-15T09:20:00Z"
                }
                """.trimIndent(),
            )
        }

        val pullRequest = client.getPullRequest("org/repo", "8")

        assertEquals("org/repo", pullRequest.sourceRepo)
        assertEquals("org/repo", pullRequest.targetRepo)
        assertEquals(shaC, pullRequest.mergeSha)
        assertEquals("bob", pullRequest.author)
        assertFalse(pullRequest.fromFork)
        assertEquals(Instant.parse("2026-07-15T09:20:00Z").toEpochMilli(), pullRequest.updatedAt)
    }

    @Test
    fun `rejects hostile fork repository paths invalid refs and nonfull response object ids`() {
        var payload =
            """{"number":"9","title":"Hostile","state":"open","head":{"ref":"feature","sha":"$shaA","repo":{"path":"fork/../victim"}},"base":{"ref":"main","sha":"$shaB","repo":{"path":"org/repo"}}}"""
        handlers["/org/repo/-/pulls/9"] = { exchange -> respond(exchange, 200, payload) }

        assertThrows(CnbApiException::class.java) { client.getPullRequest("org/repo", "9") }

        payload =
            """{"number":"9","title":"Bad ref","state":"open","head":{"ref":"bad..ref","sha":"$shaA","repo":{"path":"fork/repo"}},"base":{"ref":"main","sha":"$shaB","repo":{"path":"org/repo"}}}"""
        assertThrows(CnbApiException::class.java) { client.getPullRequest("org/repo", "9") }

        payload =
            """{"number":"9","title":"Bad SHA","state":"open","head":{"ref":"feature","sha":"--upload-pack=evil","repo":{"path":"fork/repo"}},"base":{"ref":"main","sha":"$shaB","repo":{"path":"org/repo"}}}"""
        assertThrows(CnbApiException::class.java) { client.getPullRequest("org/repo", "9") }

        payload =
            """{"number":"9","title":"Bad time","state":"open","head":{"ref":"feature","sha":"$shaA","repo":{"path":"fork/repo"}},"base":{"ref":"main","sha":"$shaB","repo":{"path":"org/repo"}},"updated_at":"yesterday"}"""
        assertThrows(CnbApiException::class.java) { client.getPullRequest("org/repo", "9") }

        payload =
            """{"number":"9","title":"Bad state","state":"future","head":{"ref":"feature","sha":"$shaA","repo":{"path":"fork/repo"}},"base":{"ref":"main","sha":"$shaB","repo":{"path":"org/repo"}}}"""
        assertThrows(CnbApiException::class.java) { client.getPullRequest("org/repo", "9") }

        payload =
            """{"number":"not-a-number","title":"Bad number","state":"open","head":{"ref":"feature","sha":"$shaA","repo":{"path":"fork/repo"}},"base":{"ref":"main","sha":"$shaB","repo":{"path":"org/repo"}}}"""
        assertThrows(CnbApiException::class.java) { client.getPullRequest("org/repo", "9") }
        assertThrows(IllegalArgumentException::class.java) { client.getPullRequest("org/repo", "not-a-number") }

        assertEquals(6, requests.size)
    }

    @Test
    fun `accepts and canonicalizes a complete sha256 response object id`() {
        handlers["/org/repo/-/git/branches/main"] = { exchange ->
            respond(exchange, 200, """{"name":"main","sha":"${sha256.uppercase()}"}""")
        }

        val branch = client.getBranch("org/repo", "main")

        assertEquals(sha256, branch.sha)
    }

    @Test
    fun `creates updates and batch loads pull requests with typed bounded contracts`() {
        handlers["/org/repo/-/pulls"] = { exchange ->
            respond(
                exchange,
                201,
                """{"number":"7","state":"open","title":"Typed PR","body":"Production body","author":{"is_npc":false},"reviewers":null,"labels":null,"base":null,"head":null,"blocked_on":"","mergeable_state":"","is_wip":false}""",
            )
        }
        handlers["/org/repo/-/pulls/7"] = { exchange ->
            val body =
                if (exchange.requestMethod == "GET") {
                    richPullJson("7")
                        .replace("\"blocked_on\":\"status_check\"", "\"blocked_on\":\"unblocked\"")
                        .replace("\"is_wip\":false,", "\"is_wip\":false,\"merged_by\":{\"is_npc\":false},")
                        .replace("\"created_at\":\"2026-07-15T09:00:00Z\",", "")
                } else {
                    richPullJson("7")
                }
            respond(exchange, 200, body)
        }
        handlers["/org/repo/-/pull-in-batch"] = { exchange ->
            respond(exchange, 200, "[${richPullJson("7")},${richPullJson("8")}]")
        }

        val created =
            client.createPullRequest(
                "org/repo",
                CnbCreatePullRequestRequest(
                    targetBranch = "main",
                    sourceBranch = "feature/api",
                    title = "Typed PR",
                    body = "Production body",
                    sourceRepository = "fork/repo",
                ),
            )
        val updated =
            client.updatePullRequest(
                "org/repo",
                "7",
                CnbUpdatePullRequestRequest(title = "Updated PR", state = CnbPullRequestState.CLOSED),
            )
        val batch = client.listPullRequestsByNumbers("org/repo", listOf("7", "8"))

        assertEquals("7", created.number)
        assertNull(created.blockedOn)
        assertNull(created.mergedBy)
        assertEquals("", created.createdAt)
        assertEquals(CnbPullMergeableState.MERGEABLE, created.mergeableState)
        assertEquals("alice", created.assignees.single().username)
        assertEquals(CnbPullReviewState.APPROVED, created.reviewers.single().reviewState)
        assertEquals("7", updated.number)
        assertEquals(listOf("7", "8"), batch.map { it.number })
        assertEquals("POST", requests[0].method)
        assertTrue(requests[0].body.contains("\"head_repo\":\"fork/repo\""))
        assertTrue(requests[0].body.contains("\"base\":\"main\""))
        assertEquals("GET", requests[1].method)
        assertEquals("PATCH", requests[2].method)
        assertTrue(requests[2].body.contains("\"state\":\"closed\""))
        assertEquals("n=7&n=8", requests[3].query)
    }

    @Test
    fun `rejects empty successful pull request mutation responses`() {
        handlers["/org/repo/-/pulls"] = { exchange -> respond(exchange, 201, "") }
        handlers["/org/repo/-/pulls/7"] = { exchange -> respond(exchange, 204, "") }

        assertThrows(CnbApiException::class.java) {
            client.createPullRequest("org/repo", CnbCreatePullRequestRequest("main", "feature", "Title"))
        }
        assertThrows(CnbApiException::class.java) {
            client.updatePullRequest("org/repo", "7", CnbUpdatePullRequestRequest(title = "Updated"))
        }
    }

    @Test
    fun `manages pull assignees reviewers and clear labels without retrying mutations`() {
        handlers["/org/repo/-/pulls/7/assignees"] = { exchange ->
            if (exchange.requestMethod == "GET") {
                val body =
                    if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                        """[{"username":"alice","nickname":"Alice","avatar":"http://127.0.0.1:${server.address.port}/avatar"}]"""
                    } else {
                        "[]"
                    }
                respond(exchange, 200, body)
            } else {
                respond(exchange, 200, richPullJson("7"))
            }
        }
        handlers["/org/repo/-/pulls/7/reviewers"] = { exchange -> respond(exchange, 200, richPullJson("7")) }
        handlers["/org/repo/-/pulls/7/labels"] = { exchange -> respond(exchange, 204, "") }

        val assignee = client.listPullAssignees("org/repo", "7").single()
        client.addPullAssignees("org/repo", "7", listOf("alice"))
        client.removePullAssignees("org/repo", "7", listOf("alice"))
        client.addPullReviewers("org/repo", "7", listOf("bob"))
        client.removePullReviewers("org/repo", "7", listOf("bob"))
        client.clearPullLabels("org/repo", "7")

        assertEquals("alice", assignee.username)
        assertTrue(assignee.avatarUrl.startsWith("http://127.0.0.1:"))
        val mutations = requests.filter { it.method != "GET" }
        assertEquals(listOf("POST", "DELETE", "POST", "DELETE", "DELETE"), mutations.map { it.method })
        assertTrue(mutations[0].body.contains("\"assignees\":[\"alice\"]"))
        assertTrue(mutations[2].body.contains("\"reviewers\":[\"bob\"]"))

        val before = requests.size
        assertThrows(IllegalArgumentException::class.java) {
            client.addPullAssignees("org/repo", "7", (1..9).map { "user-$it" })
        }
        assertEquals(before, requests.size)
    }

    @Test
    fun `lists and replies to strongly typed pull review comments`() {
        var response = reviewCommentJson("comment-1")
        handlers["/org/repo/-/pulls/7/reviews/review-1/comments"] = { exchange ->
            val body = if (query(exchange.requestURI.rawQuery)["page"] == "1") "[$response]" else "[]"
            respond(exchange, 200, body)
        }
        handlers["/org/repo/-/pulls/7/reviews/review-1/replies"] = { exchange ->
            respond(exchange, 201, reviewCommentJson("comment-2", "comment-1"))
        }

        val comment = client.listPullReviewComments("org/repo", "7", "review-1").single()
        val reply =
            client.replyToPullReviewComment(
                "org/repo",
                "7",
                "review-1",
                CnbPullReviewReplyRequest("Thanks", "comment-1"),
            )

        assertEquals(shaA, comment.commitSha)
        assertEquals(CnbPullReviewState.APPROVED, comment.reviewState)
        assertEquals(CnbPullReviewSubjectType.LINE, comment.subjectType)
        assertEquals(CnbPullReviewSide.RIGHT, comment.endSide)
        assertEquals(CnbPullReviewDiffLineType.ADDITION, comment.diffHunk.single().type)
        assertEquals("thumbs_up", comment.reactions.single().reaction)
        assertEquals("comment-1", reply.replyToCommentId)
        assertTrue(requests.last().body.contains("\"reply_to_comment_id\":\"comment-1\""))

        response = response.replace("\"review_state\":\"approved\"", "\"review_state\":\"future\"")
        assertThrows(CnbApiException::class.java) {
            client.listPullReviewComments("org/repo", "7", "review-1")
        }
    }

    @Test
    fun `maps build stage detail and streams runner log without leaking authorization`() {
        handlers["/org/repo/-/build/logs/stage/build-1/pipeline-1/stage-1"] = { exchange ->
            respond(
                exchange,
                200,
                """{"id":"stage-1","name":"test","status":"success","duration":42,"startTime":10,"endTime":52,"error":"","content":["line one","line two"]}""",
            )
        }
        handlers["/org/repo/-/build/runner/download/log/pipeline-1"] = { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/object/runner-log?signature=signed")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val log = "runner output".toByteArray(StandardCharsets.UTF_8)
        handlers["/object/runner-log"] = { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.responseHeaders.add("ETag", "runner-etag")
            exchange.sendResponseHeaders(200, log.size.toLong())
            exchange.responseBody.use { it.write(log) }
        }
        val sink = ByteArrayOutputStream()

        val stage = client.getBuildStage("org/repo", "build-1", "pipeline-1", "stage-1")
        val download = client.downloadBuildRunnerLog("org/repo", "pipeline-1", CnbDownloadTarget { sink }, 1024)

        assertEquals(CnbBuildStageStatus.SUCCESS, stage.status)
        assertEquals(listOf("line one", "line two"), stage.content)
        assertEquals("runner output", sink.toString(StandardCharsets.UTF_8))
        assertEquals("runner-etag", download.etag)
        assertEquals("Bearer top-secret", requests.single { it.rawPath.contains("runner/download") }.authorization)
        val external = requests.single { it.rawPath == "/object/runner-log" }
        assertNull(external.authorization)
        assertNoJsonNegotiation(external)
    }

    @Test
    fun `lists repository labels from the repository catalog endpoint`() {
        handlers["/org/repo/-/labels"] = { exchange ->
            val body =
                when (query(exchange.requestURI.rawQuery)["page"]) {
                    "1" -> {
                        """[{"id":"label-1","name":"trusted","color":"#00ff00","description":"Trusted contributor","future":true}]"""
                    }

                    else -> {
                        "[]"
                    }
                }
            respond(exchange, 200, body)
        }

        val label = client.listRepositoryLabels("org/repo").single()

        assertEquals("label-1", label.id)
        assertEquals("trusted", label.name)
        assertEquals("#00ff00", label.color)
        assertEquals("Trusted contributor", label.description)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.method == "GET" && it.rawPath == "/org/repo/-/labels" })
        assertTrue(requests.first().query.contains("page=1"))
        assertTrue(requests.first().query.contains("page_size=100"))
    }

    @Test
    fun `lists repository labels from an items envelope`() {
        handlers["/org/repo/-/labels"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """{"items":[{"id":"label-1","name":"ready"}],"future":true}"""
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val labels = client.listRepositoryLabels("org/repo")

        assertEquals(listOf("ready"), labels.map { it.name })
        assertEquals(2, requests.size)
    }

    @Test
    fun `deduplicates repository labels repeated across pagination boundaries`() {
        handlers["/org/repo/-/labels"] = { exchange ->
            val body =
                when (query(exchange.requestURI.rawQuery)["page"]) {
                    "1" -> """[{"id":"label-1","name":"ready"},{"id":"label-2","name":"security"}]"""
                    "2" -> """[{"id":"label-2","name":"security-renamed"},{"id":"label-3","name":"release"}]"""
                    else -> "[]"
                }
            respond(exchange, 200, body)
        }

        val labels = client.listRepositoryLabels("org/repo")

        assertEquals(listOf("label-1", "label-2", "label-3"), labels.map { it.id })
        assertEquals(listOf("ready", "security", "release"), labels.map { it.name })
        assertEquals(3, requests.size)
    }

    @Test
    fun `rejects malformed repository label catalog responses`() {
        val malformedResponses =
            listOf(
                """[{"id":"","name":"ready"}]""" to "label id",
                """[{"id":"label-1","name":""}]""" to "label name",
                """[{"id":"label-1","name":"ready","color":"red"}]""" to "label color",
                """{"items":"not-an-array"}""" to "invalid JSON",
            )
        var response = ""
        handlers["/org/repo/-/labels"] = { exchange ->
            respond(
                exchange,
                200,
                if (query(exchange.requestURI.rawQuery)["page"] == "1") response else "[]",
            )
        }

        malformedResponses.forEach { (body, expectedMessage) ->
            response = body
            val failure = assertThrows(CnbApiException::class.java) { client.listRepositoryLabels("org/repo") }

            assertTrue(failure.message.orEmpty().contains(expectedMessage))
            requests.clear()
        }
    }

    @Test
    fun `enforces the aggregate response budget for repository labels`() {
        val padding = "x".repeat(8_500_000)
        handlers["/org/repo/-/labels"] = { exchange ->
            val page = query(exchange.requestURI.rawQuery)["page"].orEmpty()
            respond(exchange, 200, """[{"id":"label-$page","name":"label-$page","padding":"$padding"}]""")
        }

        val failure = assertThrows(CnbApiException::class.java) { client.listRepositoryLabels("org/repo") }

        assertTrue(failure.message.orEmpty().contains("pagination byte limit"))
        assertEquals(2, requests.size)
    }

    @Test
    fun `paginates pull labels and maps unknown fields compatibly`() {
        handlers["/org/repo/-/pulls/7/labels"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """[{"id":"label-1","name":"trusted","color":"#00ff00","description":"Trusted contributor","future":true}]"""
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val label = client.listPullLabels("org/repo", "7").single()

        assertEquals("label-1", label.id)
        assertEquals("trusted", label.name)
        assertEquals("#00ff00", label.color)
        assertEquals("Trusted contributor", label.description)
        assertTrue(requests.first().query.contains("page_size=100"))
    }

    @Test
    fun `rejects empty and negative identifiers after wire decoding`() {
        var id = "-1"
        handlers["/org/repo/-/pulls/7/labels"] = { exchange ->
            respond(exchange, 200, """{"id":$id,"name":"trusted"}""")
        }

        assertThrows(CnbApiException::class.java) {
            client.addPullLabel("org/repo", "7", "trusted")
        }

        id = "\"\""
        assertThrows(CnbApiException::class.java) {
            client.addPullLabel("org/repo", "7", "trusted")
        }

        assertEquals(2, requests.size)
    }

    @Test
    fun `adds replaces and removes pull labels with bounded JSON bodies`() {
        handlers["/org/repo/-/pulls/7/labels"] = { exchange ->
            val name = if (exchange.requestMethod == "POST") "added" else "replacement"
            respond(exchange, 200, """{"id":"label-$name","name":"$name","future":true}""")
        }
        handlers["/org/repo/-/pulls/7/labels/needs review"] = { exchange ->
            respond(exchange, 200, """{"id":"label-remove","name":"needs review"}""")
        }

        val added = client.addPullLabel("org/repo", "7", "added")
        val replaced = client.replacePullLabels("org/repo", "7", listOf("replacement", "security"))
        val removed = client.removePullLabel("org/repo", "7", "needs review")

        assertEquals("added", added.name)
        assertEquals("replacement", replaced.name)
        assertEquals("needs review", removed.name)
        assertEquals("POST", requests[0].method)
        assertTrue(requests[0].body.contains("\"labels\":[\"added\"]"))
        assertEquals("PUT", requests[1].method)
        assertTrue(requests[1].body.contains("\"replacement\""))
        assertTrue(requests[1].body.contains("\"security\""))
        assertTrue(requests.last().rawPath.endsWith("/needs%20review"))
    }

    @Test
    fun `rejects invalid pull label mutations before sending`() {
        assertThrows(IllegalArgumentException::class.java) {
            client.addPullLabel("org/repo", "7", " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.replacePullLabels("org/repo", "7", List(101) { "label-$it" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.removePullLabel("org/repo", "7", "x".repeat(257))
        }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `lists and creates pull reviews then merges with bounded JSON bodies`() {
        handlers["/org/repo/-/pulls/7/reviews"] = { exchange ->
            if (exchange.requestMethod == "GET") {
                val body =
                    if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                        """[{"id":"review-1","body":"Looks good","state":"approved","author":{"username":"alice"},"created_at":"2026-07-15T10:00:00Z","updated_at":"2026-07-15T10:01:00Z","future":true}]"""
                    } else {
                        "[]"
                    }
                respond(exchange, 200, body)
            } else {
                respond(exchange, 201, "")
            }
        }
        handlers["/org/repo/-/pulls/7/merge"] = { exchange ->
            respond(exchange, 200, """{"merged":true,"message":"merged","sha":"$shaC","future":true}""")
        }

        val review = client.listPullReviews("org/repo", "7").single()
        client.createPullReview(
            "org/repo",
            "7",
            dev.zxilly.jenkins.cnb.api.model.CnbPullReviewRequest(
                event = CnbPullReviewEvent.APPROVE,
                body = "Looks good",
                comments =
                    listOf(
                        dev.zxilly.jenkins.cnb.api.model.CnbPullReviewComment(
                            body = "Inline note",
                            path = "src/App.kt",
                            subjectType = CnbPullReviewSubjectType.LINE,
                            startLine = 10,
                            startSide = CnbPullReviewSide.RIGHT,
                            endLine = 11,
                            endSide = CnbPullReviewSide.RIGHT,
                        ),
                    ),
            ),
        )
        val merged =
            client.mergePullRequest(
                "org/repo",
                "7",
                dev.zxilly.jenkins.cnb.api.model.CnbMergePullRequest(
                    mergeStyle = CnbPullMergeStyle.SQUASH,
                    commitTitle = "Squashed title",
                    commitMessage = "Squashed body",
                ),
            )

        assertEquals("alice", review.author)
        assertEquals(CnbPullReviewState.APPROVED, review.state)
        assertTrue(requests[2].body.contains("\"subject_type\":\"line\""))
        assertTrue(requests[2].body.contains("\"start_line\":10"))
        assertFalse(requests[2].body.contains("top-secret"))
        assertTrue(merged.merged)
        assertEquals(shaC, merged.sha)
        assertTrue(requests.last().body.contains("\"merge_style\":\"squash\""))
    }

    @Test
    fun `rejects incomplete pull review lines and oversized merge titles before sending`() {
        assertThrows(IllegalArgumentException::class.java) {
            client.createPullReview(
                "org/repo",
                "7",
                dev.zxilly.jenkins.cnb.api.model.CnbPullReviewRequest(
                    event = CnbPullReviewEvent.COMMENT,
                    comments =
                        listOf(
                            dev.zxilly.jenkins.cnb.api.model.CnbPullReviewComment(
                                body = "missing location",
                                path = "src/App.kt",
                                subjectType = CnbPullReviewSubjectType.LINE,
                            ),
                        ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.mergePullRequest(
                "org/repo",
                "7",
                dev.zxilly.jenkins.cnb.api.model
                    .CnbMergePullRequest(commitTitle = "x".repeat(1_001)),
            )
        }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `lists member access levels with encoded username`() {
        handlers["/org/repo/-/members/alice bot/access-level"] = { exchange ->
            respond(
                exchange,
                200,
                """[{"path":"org","access_level":"Developer","future_permission":{"ignored":true}},{"path":"org/repo","access_level":"Master"}]""",
            )
        }

        val access = client.listMemberAccessLevels("org/repo", "alice bot")

        assertEquals(listOf("org", "org/repo"), access.map { it.path })
        assertEquals(listOf("Developer", "Master"), access.map { it.accessLevel.wireValue })
        assertTrue(requests.single().rawPath.endsWith("/alice%20bot/access-level"))
        assertTrue(requests.single().query.isEmpty())
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
                    """[{"id":"9","path":"alice/old-project","name":"old-project","status":1,"visibility_level":"Private","web_url":"https://cnb.cool/alice/old-project"}]"""
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val repositories = client.listRepositories("alice", includeDescendants = true)

        assertEquals(listOf("alice/old-project"), repositories.map { it.path })
        assertTrue(repositories.single().archived)
        assertTrue(repositories.single().cloneable)
        assertEquals("/alice/-/repos", requests.first().rawPath)
        assertEquals("/users/alice/repos", requests[1].rawPath)
    }

    @Test
    fun `fails closed for forking repositories and rejects unknown lifecycle values`() {
        var body =
            """[{"id":"1","path":"org/forking","status":2,"visibility_level":"Public","web_url":"https://cnb.cool/org/forking"}]"""
        handlers["/user/repos"] = { exchange ->
            respond(exchange, 200, if (query(exchange.requestURI.rawQuery)["page"] == "1") body else "[]")
        }

        val forking = client.listUserRepositories().single()

        assertEquals(CnbRepositoryStatus.FORKING, forking.status)
        assertFalse(forking.cloneable)

        requests.clear()
        body = """[{"id":"2","path":"org/bad","status":99,"visibility_level":"Public"}]"""
        assertThrows(CnbApiException::class.java) { client.listUserRepositories() }

        requests.clear()
        body = """[{"id":"3","path":"org/bad","status":0,"visibility_level":"Internal"}]"""
        assertThrows(CnbApiException::class.java) { client.listUserRepositories() }
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
    fun `deduplicates a moved resource by stable identifier when fields change between pages`() {
        handlers["/user/repos"] = { exchange ->
            val body =
                when (query(exchange.requestURI.rawQuery)["page"]) {
                    "1" -> """[{"id":"2","path":"org/two","name":"before"}]"""
                    "2" -> """[{"id":"2","path":"org/two-renamed","name":"after"}]"""
                    else -> "[]"
                }
            respond(exchange, 200, body)
        }

        val repositories = client.listUserRepositories()

        assertEquals(listOf("org/two"), repositories.map { it.path })
        assertEquals(listOf("before"), repositories.map { it.name })
        assertEquals(3, requests.size)
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

        assertTrue(failure.message.orEmpty().contains("invalid repository path"))
        assertNull(failure.cause)
        assertEquals(1, requests.size)
    }

    @Test
    fun `reports a missing remote repository path as an API failure`() {
        handlers["/user/repos"] = { exchange ->
            respond(exchange, 200, """[{"id":"missing-path"}]""")
        }

        val failure = assertThrows(CnbApiException::class.java) { client.listUserRepositories() }

        assertTrue(failure.message.orEmpty().contains("invalid JSON"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `accepts an empty no-content pagination response`() {
        handlers["/user/repos"] = { exchange -> respond(exchange, 204, "") }

        assertTrue(client.listUserRepositories().isEmpty())
        assertEquals(1, requests.size)
    }

    @Test
    fun `accepts a single API response beyond the former transport limit`() {
        val padding = "x".repeat(4 * 1024 * 1024)
        handlers["/user/repos"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """[{"id":"1","path":"org/one","padding":"$padding"}]"""
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val repositories = client.listUserRepositories()

        assertEquals(listOf("org/one"), repositories.map { it.path })
        assertEquals(2, requests.size)
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
                """{"errcode":"AUTH_DENIED","errmsg":"Bearer top-secret denied\\nJSON input: secret-response-fragment"}""",
            )
        }

        val failure = assertThrows(CnbApiException::class.java) { client.testConnection() }
        val exceptionGraph = throwableGraph(failure)

        assertEquals(401, failure.statusCode)
        assertEquals("AUTH_DENIED", failure.errorCode)
        assertFalse(failure.retryable)
        assertEquals("CNB API returned HTTP 401", failure.message)
        assertNull(failure.cause)
        assertTrue(exceptionGraph.none { "top-secret" in it.toString() })
        assertTrue(exceptionGraph.none { "secret-response-fragment" in it.toString() })
        assertTrue(exceptionGraph.none { "JSON input:" in it.toString() })
        assertTrue(exceptionGraph.none { it.suppressed.isNotEmpty() })
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
    fun `treats reset-content as an empty authenticated-user response`() {
        handlers["/user"] = { exchange ->
            exchange.sendResponseHeaders(205, -1)
            exchange.close()
        }

        val failure = assertThrows(CnbApiException::class.java) { client.testConnection() }

        assertTrue(failure.message.orEmpty().contains("empty user response"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `fails closed on a chunked empty JSON object response without retrying`() {
        handlers["/user"] = { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }

        val failure = assertThrows(CnbApiException::class.java) { client.testConnection() }

        assertFalse(failure.retryable)
        assertEquals(1, requests.size)
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
                """{"path":"","sha":"$shaA","type":"tree","size":0,"entries":[{"name":"src","path":"src","sha":"$shaB","type":"tree","size":0},{"name":"Jenkinsfile","path":"Jenkinsfile","sha":"$shaC","type":"blob","size":42}]}""",
            )
        }

        val content = requireNotNull(client.getContent("org/repo", "", "main"))

        assertEquals(CnbContentType.TREE, content.type)
        assertEquals(listOf("src", "Jenkinsfile"), content.entries.map { it.name })
        assertEquals("ref=main", requests.single().query)
    }

    @Test
    fun `rejects unknown content types encodings and negative sizes`() {
        var body = """{"path":"Jenkinsfile","sha":"$shaA","type":"file","size":1}"""
        handlers["/org/repo/-/git/contents/Jenkinsfile"] = { exchange -> respond(exchange, 200, body) }

        assertThrows(CnbApiException::class.java) { client.getContent("org/repo", "Jenkinsfile", "main") }

        body = """{"path":"Jenkinsfile","sha":"$shaA","type":"blob","size":1,"content":"eA==","encoding":"utf8"}"""
        assertThrows(CnbApiException::class.java) { client.getContent("org/repo", "Jenkinsfile", "main") }

        body = """{"path":"Jenkinsfile","sha":"$shaA","type":"blob","size":-1}"""
        assertThrows(CnbApiException::class.java) { client.getContent("org/repo", "Jenkinsfile", "main") }
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
    fun `gets bounded raw content with encoded ref-path and validated content type`() {
        handlers["/org/repo/-/git/raw/feature/x/docs/file name.txt"] = { exchange ->
            val bytes = "raw content".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val content = requireNotNull(client.getRawContent("org/repo", "feature/x", "docs/file name.txt", 64))

        assertEquals("raw content", content.bytes.toString(StandardCharsets.UTF_8))
        assertEquals("text/plain; charset=UTF-8", content.contentType)
        assertEquals(11, content.size)
        assertTrue(requests.single().rawPath.endsWith("/feature%2Fx%2Fdocs%2Ffile%20name.txt"))
        assertEquals("max_in_byte=64", requests.single().query)
    }

    @Test
    fun `returns null for missing raw content and rejects invalid content types`() {
        handlers["/org/repo/-/git/raw/main/missing.txt"] = { exchange ->
            respond(exchange, 404, """{"errcode":404}""")
        }
        handlers["/org/repo/-/git/raw/main/invalid.txt"] = { exchange ->
            val bytes = "invalid".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "not a media type")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        assertNull(client.getRawContent("org/repo", "main", "missing.txt"))
        val failure =
            assertThrows(CnbApiException::class.java) {
                client.getRawContent("org/repo", "main", "invalid.txt")
            }

        assertTrue(failure.message.orEmpty().contains("Content-Type"))
    }

    @Test
    fun `enforces caller and client raw content byte limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            client.getRawContent("org/repo", "main", "file.txt", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.getRawContent("org/repo", "main", "file.txt", 4 * 1024 * 1024 + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.getRawContent("org/repo", "main", "../secret.txt", 64)
        }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `gets lists and compares commits through encoded public interfaces`() {
        handlers["/org/repo/-/git/commits/feature/x"] = { exchange ->
            respond(
                exchange,
                200,
                """{"sha":"$shaA","author":{"username":"alice","nickname":"Alice"},"commit":{"message":"Direct commit","author":{"name":"Alice A","email":"alice@example.com","date":"2026-07-15T10:00:00Z"}},"parents":[{"sha":"$shaB"}],"unknown":"ignored"}""",
            )
        }
        handlers["/org/repo/-/git/commits"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """[{"sha":"$shaC","commit":{"message":"Listed commit"}}]"""
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }
        handlers["/org/repo/-/git/compare/base ref...head/ref"] = { exchange ->
            respond(
                exchange,
                200,
                """{"base_commit":{"sha":"$shaA"},"head_commit":{"sha":"$shaB"},"merge_base_commit":{"sha":"$shaD"},"total_commits":1,"commits":[{"sha":"$shaC"}],"files":[{"path":"src/App.kt","previous_filename":"src/Old.kt","status":"renamed","additions":3,"deletions":1,"patch":"@@","mode":"100755","previous_mode":"100644"}],"future":true}""",
            )
        }

        val direct = client.getCommit("org/repo", "feature/x")
        val listed =
            client.listCommits(
                "org/repo",
                dev.zxilly.jenkins.cnb.api.model.CnbCommitQuery(
                    sha = "main branch",
                    author = "Alice+Bot",
                    since = "2026-07-01T00:00:00Z",
                ),
            )
        val comparison = client.compareCommits("org/repo", "base ref", "head/ref")

        assertEquals("Direct commit", direct.message)
        assertEquals("alice", direct.author.username)
        assertEquals("Alice A", direct.author.name)
        assertEquals(listOf(shaB), direct.parentShas)
        assertEquals(listOf(shaC), listed.map { it.sha })
        assertTrue(requests[1].query.contains("sha=main%20branch"))
        assertTrue(requests[1].query.contains("author=Alice%2BBot"))
        assertEquals(shaD, comparison.mergeBaseCommit?.sha)
        assertEquals("src/Old.kt", comparison.files.single().previousFilename)
        assertEquals(CnbCommitDiffStatus.RENAMED, comparison.files.single().status)
        assertEquals(CnbGitFileMode.EXECUTABLE, comparison.files.single().mode)
        assertEquals(CnbGitFileMode.REGULAR, comparison.files.single().previousMode)
        assertTrue(requests.last().rawPath.endsWith("/base%20ref...head%2Fref"))
    }

    @Test
    fun `maps pull commits files and aggregate commit statuses`() {
        handlers["/org/repo/-/pulls/7/commits"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """[{"sha":"$shaA","commit":{"message":"Pull commit"}}]"""
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }
        handlers["/org/repo/-/pulls/7/files"] = { exchange ->
            respond(
                exchange,
                200,
                """[{"filename":"src/New.kt","status":"rename","sha":"$shaB","additions":4,"deletions":2,"patch":"@@","blob_url":"https://example/blob","raw_url":"https://example/raw","contents_url":"https://example/content","future":1}]""",
            )
        }
        handlers["/org/repo/-/pulls/7/commit-statuses"] = { exchange ->
            respond(
                exchange,
                200,
                """{"sha":"$shaC","state":"success","statuses":[{"context":"jenkins/test","state":"success","target_url":"https://jenkins/job/1"}],"future":true}""",
            )
        }

        val commits = client.listPullCommits("org/repo", "7")
        val files = client.listPullFiles("org/repo", "7")
        val statuses = client.listPullCommitStatuses("org/repo", "7")

        assertEquals(shaA, commits.single().sha)
        assertEquals("src/New.kt", files.single().filename)
        assertEquals(CnbPullFileStatus.RENAME, files.single().status)
        assertEquals("https://example/content", files.single().contentsUrl)
        assertEquals(shaC, statuses.sha)
        assertEquals(CnbCommitStatusState.SUCCESS, statuses.state)
        assertEquals("jenkins/test", statuses.statuses.single().context)
    }

    @Test
    fun `starts reads and stops builds with the documented wire contract`() {
        handlers["/org/repo/-/build/start"] = { exchange ->
            respond(
                exchange,
                200,
                """{"sn":"cnb-start","buildLogUrl":"https://cnb.cool/org/repo/-/build/logs/cnb-start","message":"queued","success":true,"future":true}""",
            )
        }
        handlers["/org/repo/-/build/status/cnb-start"] = { exchange ->
            respond(
                exchange,
                200,
                """{"status":"running","pipelinesStatus":{"pipeline-1":{"id":"pipeline-1","name":"Jenkins smoke","status":"prepare","duration":12,"metricCoreHours":1.25,"metricDuration":4500.5,"labels":[{"key":"runner","value":["linux",8,16]}],"stages":[{"id":"stage-1","name":"test","status":"success","duration":10}],"future":true}},"future":true}""",
            )
        }
        handlers["/org/repo/-/build/stop/cnb-start"] = { exchange ->
            respond(exchange, 200, """{"sn":"cnb-start","message":"stopping","success":true}""")
        }

        val started =
            client.startBuild(
                "org/repo",
                dev.zxilly.jenkins.cnb.api.model.CnbBuildRequest(
                    event = CnbBuildTriggerEvent("api_trigger_jenkins"),
                    branch = "feature/x",
                    tag = "v1.0",
                    sha = shaA,
                    title = "Jenkins verification",
                    config = "main: {}",
                    sync = true,
                    env = linkedMapOf("JENKINS_URL" to "https://jenkins.example/"),
                    npc =
                        dev.zxilly.jenkins.cnb.api.model
                            .CnbBuildNpc(CnbBuildNpcName.CODE_BUDDY, workMode = true),
                ),
            )
        val status = client.getBuildStatus("org/repo", started.sn)
        val stopped = client.stopBuild("org/repo", started.sn)

        assertTrue(started.success)
        assertEquals("https://cnb.cool/org/repo/-/build/logs/cnb-start", started.buildLogUrl)
        assertTrue(requests.first().body.contains("\"event\":\"api_trigger_jenkins\""))
        assertTrue(requests.first().body.contains("\"sync\":\"true\""))
        assertTrue(requests.first().body.contains("\"workMode\":true"))
        assertEquals(CnbBuildState.RUNNING, status.status)
        assertEquals(CnbBuildState.PREPARE, status.pipelinesStatus.getValue("pipeline-1").status)
        assertEquals(
            listOf("linux", "8", "16"),
            status.pipelinesStatus
                .getValue("pipeline-1")
                .labels
                .single()
                .values,
        )
        assertEquals(
            "test",
            status.pipelinesStatus
                .getValue("pipeline-1")
                .stages
                .single()
                .name,
        )
        assertTrue(stopped.success)
        assertEquals("POST", requests.last().method)
    }

    @Test
    fun `rejects unknown and invalid build status fields`() {
        var body = """{"status":"future","pipelinesStatus":{}}"""
        handlers["/org/repo/-/build/status/build-1"] = { exchange -> respond(exchange, 200, body) }

        assertThrows(CnbApiException::class.java) { client.getBuildStatus("org/repo", "build-1") }

        body = """{"status":"running","pipelinesStatus":{"invalid key":{"id":"pipeline-2","status":"running"}}}"""
        assertThrows(CnbApiException::class.java) { client.getBuildStatus("org/repo", "build-1") }

        body = """{"status":"running","pipelinesStatus":{"pipeline-1":{"id":"pipeline-1","status":"running","duration":-1}}}"""
        assertThrows(CnbApiException::class.java) { client.getBuildStatus("org/repo", "build-1") }

        body =
            """{"status":"running","pipelinesStatus":{"pipeline-1":{"id":"pipeline-1","status":"running","labels":[{"key":"cpus","value":[true]}]}}}"""
        assertThrows(CnbApiException::class.java) { client.getBuildStatus("org/repo", "build-1") }
    }

    @Test
    fun `rejects non API trigger build events and oversized request input`() {
        listOf("push", "web_trigger", "api trigger").forEach { event ->
            assertThrows(IllegalArgumentException::class.java) { CnbBuildTriggerEvent(event) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.startBuild(
                "org/repo",
                dev.zxilly.jenkins.cnb.api.model.CnbBuildRequest(
                    event = CnbBuildTriggerEvent.API_TRIGGER,
                    env = (0..200).associate { "KEY_$it" to "value" },
                ),
            )
        }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `paginates and filters build history without collapsing builds sharing a commit`() {
        handlers["/org/repo/-/build/logs"] = { exchange ->
            val body =
                if (query(exchange.requestURI.rawQuery)["page"] == "1") {
                    """{"total":2,"timestamp":1721000000000,"data":[{"sn":"cnb-one","sha":"$shaA","slug":"org/repo","status":"success","event":"push","sourceRef":"feature","targetRef":"master","title":"First","commitTitle":"Commit one","buildLogUrl":"https://cnb.cool/build/one","createTime":"2026-07-15T10:00:00Z","duration":1000,"userName":"alice","pipelines":[{"id":"pipeline-1","status":"success","createTime":"2026-07-15T10:00:00Z","duration":900,"labels":"linux"}],"future":true},{"sn":"cnb-two","sha":"$shaA","slug":"org/repo","status":"error","event":"api_trigger_jenkins","pipelineFailCount":1,"pipelineSuccessCount":0,"pipelineTotalCount":1,"future":true}],"future":true}"""
                } else {
                    """{"total":2,"timestamp":1721000000001,"data":[]}"""
                }
            respond(exchange, 200, body)
        }

        val history =
            client.listBuildHistory(
                "org/repo",
                dev.zxilly.jenkins.cnb.api.model.CnbBuildHistoryQuery(
                    createTime = "2026-07-01",
                    endTime = "2026-07-15",
                    event = CnbBuildEventName("api_trigger_jenkins"),
                    sha = "shared sha",
                    sn = "cnb-one",
                    sourceRef = "feature/x",
                    status = CnbBuildState.SUCCESS,
                    targetRef = "master",
                    userId = "42",
                    userName = "Alice+Bot",
                ),
            )

        assertEquals(2L, history.total)
        assertEquals(1721000000001L, history.timestamp)
        assertEquals(listOf("cnb-one", "cnb-two"), history.builds.map { it.sn })
        assertEquals(
            "pipeline-1",
            history.builds
                .first()
                .pipelines
                .single()
                .id,
        )
        assertEquals("Commit one", history.builds.first().commitTitle)
        assertEquals(2, requests.size)
        assertTrue(requests.first().query.contains("createTime=2026-07-01"))
        assertTrue(requests.first().query.contains("sourceRef=feature%2Fx"))
        assertTrue(requests.first().query.contains("userName=Alice%2BBot"))
        assertTrue(requests.first().query.contains("page_size=100"))
    }

    @Test
    fun `deduplicates moved build history entries by serial number when fields change`() {
        handlers["/org/repo/-/build/logs"] = { exchange ->
            val body =
                when (query(exchange.requestURI.rawQuery)["page"]) {
                    "1" -> """{"total":1,"timestamp":1,"data":[{"sn":"build-1","status":"running"}]}"""
                    "2" -> """{"total":1,"timestamp":2,"data":[{"sn":"build-1","status":"success"}]}"""
                    else -> """{"total":1,"timestamp":3,"data":[]}"""
                }
            respond(exchange, 200, body)
        }

        val history =
            client.listBuildHistory(
                "org/repo",
                dev.zxilly.jenkins.cnb.api.model
                    .CnbBuildHistoryQuery(),
            )

        assertEquals(listOf("build-1"), history.builds.map { it.sn })
        assertEquals(listOf(CnbBuildState.RUNNING), history.builds.map { it.status })
        assertEquals(3L, history.timestamp)
        assertEquals(3, requests.size)
    }

    @Test
    fun `rejects malformed build history dates before sending`() {
        assertThrows(IllegalArgumentException::class.java) {
            client.listBuildHistory(
                "org/repo",
                dev.zxilly.jenkins.cnb.api.model
                    .CnbBuildHistoryQuery(createTime = "07/15/2026"),
            )
        }

        assertTrue(requests.isEmpty())
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
                    "target_url":"https://jenkins.example/job/1","created_at":"2026-07-15T10:00:00Z","updated_at":"2026-07-15T10:01:00Z"
                }]}
                """.trimIndent(),
            )
        }

        val status = client.listCommitStatuses("org/repo", "main").single()

        assertEquals("jenkins/build", status.context)
        assertEquals(CnbCommitStatusState.SUCCESS, status.state)
        assertEquals("Build passed", status.description)
        assertEquals("https://jenkins.example/job/1", status.targetUrl)
        assertEquals("2026-07-15T10:00:00Z", status.createdAt)
        assertEquals("2026-07-15T10:01:00Z", status.updatedAt)
    }

    @Test
    fun `maps valid commit annotations and ignores unknown fields`() {
        handlers["/org/repo/-/git/commit-annotations/abc123"] = { exchange ->
            respond(
                exchange,
                200,
                """[{"key":"jenkins/result","value":"SUCCESS","future":true}]""",
            )
        }

        val annotations = client.getCommitAnnotations("org/repo", "abc123")

        assertEquals(listOf(CnbCommitAnnotation("jenkins/result", "SUCCESS")), annotations)
    }

    @Test
    fun `reads commit annotations in one strongly typed batch`() {
        handlers["/org/repo/-/git/commit-annotations-in-batch"] = { exchange ->
            respond(
                exchange,
                200,
                """[{"commit_hash":"$shaA","annotations":[{"key":"jenkins_state","value":"success","meta":{"future":true},"future":true}]},{"commit_hash":"$shaB","annotations":[],"future":true}]""",
            )
        }

        val batches =
            client.getCommitAnnotationsInBatch(
                "org/repo",
                listOf(shaA, shaB),
                listOf("jenkins_state"),
            )

        assertEquals(listOf(shaA, shaB), batches.map { it.commitHash })
        assertEquals(listOf(CnbCommitAnnotation("jenkins_state", "success")), batches.first().annotations)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals(
            """{"commit_hashes":["$shaA","$shaB"],"keys":["jenkins_state"]}""",
            request.body,
        )
    }

    @Test
    fun `preserves duplicate hashes and permissive batch filter keys`() {
        handlers["/org/repo/-/git/commit-annotations-in-batch"] = { exchange ->
            respond(
                exchange,
                200,
                """[{"commit_hash":"$shaA","annotations":[]},{"commit_hash":"$shaA","annotations":[]}]""",
            )
        }

        val batches =
            client.getCommitAnnotationsInBatch(
                "org/repo",
                listOf(shaA, shaA),
                listOf("", "unknown"),
            )

        assertEquals(2, batches.size)
        assertEquals(
            """{"commit_hashes":["$shaA","$shaA"],"keys":["","unknown"]}""",
            requests.single().body,
        )
    }

    @Test
    fun `retries the read only commit annotation batch POST`() {
        val calls = AtomicInteger()
        handlers["/org/repo/-/git/commit-annotations-in-batch"] = { exchange ->
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 503, """{"errcode":503,"errmsg":"temporary"}""")
            } else {
                respond(exchange, 200, "[]")
            }
        }

        assertTrue(client.getCommitAnnotationsInBatch("org/repo", listOf(shaA)).isEmpty())
        assertEquals(2, calls.get())
    }

    @Test
    fun `validates commit annotation batch limits before sending`() {
        assertThrows(IllegalArgumentException::class.java) {
            client.getCommitAnnotationsInBatch("org/repo", emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.getCommitAnnotationsInBatch("org/repo", List(21) { shaA })
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.getCommitAnnotationsInBatch("org/repo", listOf("abc123"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.getCommitAnnotationsInBatch("org/repo", listOf(shaA), List(6) { "key-$it" })
        }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `rejects an unrequested commit from an annotation batch response`() {
        handlers["/org/repo/-/git/commit-annotations-in-batch"] = { exchange ->
            respond(
                exchange,
                200,
                """[{"commit_hash":"$shaB","annotations":[]}]""",
            )
        }

        assertThrows(CnbApiException::class.java) {
            client.getCommitAnnotationsInBatch("org/repo", listOf(shaA))
        }
    }

    @Test
    fun `allows an annotation batch response to omit an unknown commit`() {
        handlers["/org/repo/-/git/commit-annotations-in-batch"] = { exchange ->
            respond(
                exchange,
                200,
                """[{"commit_hash":"$shaA","annotations":[]}]""",
            )
        }

        val batches = client.getCommitAnnotationsInBatch("org/repo", listOf(shaA, shaB))

        assertEquals(listOf(shaA), batches.map { it.commitHash })
    }

    @Test
    fun `bounds a commit annotation batch before JSON materialization`() {
        handlers["/org/repo/-/git/commit-annotations-in-batch"] = { exchange ->
            respond(
                exchange,
                200,
                """[{"commit_hash":"$shaA","annotations":[{"key":"large","value":"${"x".repeat(4 * 1024 * 1024)}"}]}]""",
            )
        }

        val failure =
            assertThrows(CnbApiException::class.java) {
                client.getCommitAnnotationsInBatch("org/repo", listOf(shaA))
            }

        assertTrue(failure.message.orEmpty().contains("response exceeded"))
    }

    @Test
    fun `bounds a commit annotation batch error before retry policy`() {
        handlers["/org/repo/-/git/commit-annotations-in-batch"] = { exchange ->
            respond(exchange, 503, "x".repeat(4 * 1024 * 1024 + 1))
        }

        val failure =
            assertThrows(CnbApiException::class.java) {
                client.getCommitAnnotationsInBatch("org/repo", listOf(shaA))
            }

        assertTrue(failure.message.orEmpty().contains("response exceeded"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `rejects commit annotations missing required wire fields`() {
        handlers["/org/repo/-/git/commit-annotations/abc123"] = { exchange ->
            respond(exchange, 200, """[{"value":"missing-key"}]""")
        }

        assertThrows(CnbApiException::class.java) {
            client.getCommitAnnotations("org/repo", "abc123")
        }
    }

    @Test
    fun `treats missing annotations as empty and deletes a valid annotation key`() {
        handlers["/org/repo/-/git/commit-annotations/missing"] = { exchange ->
            respond(exchange, 404, """{"errcode":404}""")
        }
        handlers["/org/repo/-/git/commit-annotations/abc123/jenkins_job-result"] = { exchange ->
            respond(exchange, 204, "")
        }

        assertTrue(client.getCommitAnnotations("org/repo", "missing").isEmpty())
        client.deleteCommitAnnotation("org/repo", "abc123", "jenkins_job-result")

        val delete = requests.last()
        assertEquals("DELETE", delete.method)
        assertTrue(delete.rawPath.endsWith("/jenkins_job-result"))
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
            client.putCommitAnnotations("org/repo", "sha", listOf(CnbCommitAnnotation("jenkins/result", "value")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.putTagAnnotations(
                "org/repo",
                "v1.0.0",
                listOf(CnbTagAnnotation("jenkins.result", "value")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.deleteCommitAnnotation("org/repo", "sha", "jenkins/result")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.deleteTagAnnotation("org/repo", "release/2026", "jenkins/result")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.putCommitAnnotations("org/repo", "sha", listOf(CnbCommitAnnotation("key", "x".repeat(16_385))))
        }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `gets puts and deletes tag annotations using encoded tag-with-key segments`() {
        handlers["/org/repo/-/git/tag-annotations/release/2026"] = { exchange ->
            if (exchange.requestMethod == "GET") {
                respond(
                    exchange,
                    200,
                    """[{"key":"jenkins_result","value":"SUCCESS","meta":{"operator":"alice","future":true},"unknown":1}]""",
                )
            } else {
                respond(exchange, 200, "")
            }
        }
        handlers["/org/repo/-/git/tag-annotations/release/2026/jenkins_result"] = { exchange ->
            respond(exchange, 200, "")
        }

        val annotation = client.getTagAnnotations("org/repo", "release/2026").single()
        client.putTagAnnotations(
            "org/repo",
            "release/2026",
            listOf(CnbTagAnnotation("jenkins_result", "SUCCESS")),
        )
        client.deleteTagAnnotation("org/repo", "release/2026", "jenkins_result")

        assertEquals("jenkins_result", annotation.key)
        assertEquals("alice", annotation.meta.operator)
        assertTrue(requests[1].body.contains("\"annotations\""))
        assertTrue(requests[1].rawPath.endsWith("/release%2F2026"))
        assertTrue(requests.last().rawPath.endsWith("/release%2F2026%2Fjenkins_result"))
    }

    @Test
    fun `treats a missing tag annotation set as empty`() {
        handlers["/org/repo/-/git/tag-annotations/missing"] = { exchange ->
            respond(exchange, 404, """{"errcode":404}""")
        }

        assertTrue(client.getTagAnnotations("org/repo", "missing").isEmpty())
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
                    """[{"id":"11","body":"Looks good","user":{"username":"alice"},"created_at":"2026-07-15T10:00:00Z","updated_at":"2026-07-15T10:01:00Z"}]"""
                } else {
                    "[]"
                }
            respond(exchange, 200, body)
        }

        val comment = client.listPullComments("org/repo", "7").single()

        assertEquals("11", comment.id)
        assertEquals("Looks good", comment.body)
        assertEquals("alice", comment.author)
        assertEquals("2026-07-15T10:00:00Z", comment.createdAt)
        assertEquals("2026-07-15T10:01:00Z", comment.updatedAt)
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
    fun `gets one pull request comment for live event verification`() {
        handlers["/org/repo/-/pulls/7/comments/12"] = { exchange ->
            respond(exchange, 200, """{"id":"12","body":"retest this","user":{"username":"alice"}}""")
        }

        val comment = client.getPullComment("org/repo", "7", "12")

        assertEquals("12", comment.id)
        assertEquals("retest this", comment.body)
        assertEquals("alice", comment.author)
        assertEquals("GET", requests.single().method)
    }

    @Test
    fun `maps release metadata and prefers the correctly spelled browser URL`() {
        handlers["/org/repo/-/releases"] = { exchange ->
            val body = if (query(exchange.requestURI.rawQuery)["page"] == "1") "[${releaseJson()}]" else "[]"
            respond(exchange, 200, body)
        }

        val release = client.listReleases("org/repo").single()

        assertEquals("release-1", release.id)
        assertEquals("v1.0.0", release.tagName)
        assertEquals(shaA, release.tagCommitish)
        assertEquals("alice", release.author?.username)
        assertEquals(1, release.assets.size)
        assertEquals("http://127.0.0.1:${server.address.port}/download/right", release.assets.single().browserDownloadUrl)
        assertFalse(
            release.assets
                .single()
                .browserDownloadUrl
                .contains("legacy"),
        )
    }

    @Test
    fun `rejects absolute release asset paths outside the release scope`() {
        val validPath = "/org/repo/-/releases/download/v1.0.0/plugin.hpi"
        val invalidPaths =
            listOf(
                "/other/repo/-/releases/download/v1.0.0/plugin.hpi",
                "/org/repo/-/releases/download/v2.0.0/plugin.hpi",
                "/org/repo/-/releases/download/v1.0.0/other.hpi",
                "/org/repo/-/releases/download/release/../v1.0.0/plugin.hpi",
                "/org/repo/-/packages/download/v1.0.0/plugin.hpi",
            )

        invalidPaths.forEach { path ->
            handlers["/org/repo/-/releases/latest"] = { exchange ->
                respond(exchange, 200, releaseJson().replace(validPath, path))
            }
            assertThrows(CnbApiException::class.java) { client.getLatestRelease("org/repo") }
        }
    }

    @Test
    fun `accepts a legacy segmented release asset path`() {
        handlers["/org/repo/-/releases/latest"] = { exchange ->
            respond(
                exchange,
                200,
                releaseJson().replace(
                    "/org/repo/-/releases/download/v1.0.0/plugin.hpi",
                    "releases/plugin.hpi",
                ),
            )
        }

        assertEquals("releases/plugin.hpi", requireNotNull(client.getLatestRelease("org/repo")).assets.single().path)
    }

    @Test
    fun `accepts absent publication metadata on draft releases`() {
        handlers["/org/repo/-/releases/latest"] = { exchange ->
            respond(
                exchange,
                200,
                """{"id":"draft-1","tag_name":"v0.1.0","name":"Draft","draft":true,"created_at":"2026-07-15T10:00:00Z","updated_at":"2026-07-15T10:00:00Z","tag_commitish":null,"published_at":null}""",
            )
        }

        val release = requireNotNull(client.getLatestRelease("org/repo"))

        assertTrue(release.draft)
        assertNull(release.tagCommitish)
        assertNull(release.publishedAt)
    }

    @Test
    fun `uses explicit typed release create update lookup and delete operations`() {
        handlers["/org/repo/-/releases"] = { exchange ->
            if (exchange.requestMethod ==
                "POST"
            ) {
                respond(
                    exchange,
                    201,
                    """{"id":"release-2","tag_name":"v2.0.0","tag_commitish":"$shaB","name":"Version 2","body":"","draft":true,"prerelease":false,"is_latest":false,"created_at":"2026-07-15T10:00:00Z","updated_at":"2026-07-15T10:00:00Z","published_at":"2026-07-15T10:00:00Z","author":null,"assets":null}""",
                )
            } else {
                respond(exchange, 200, "[]")
            }
        }
        handlers["/org/repo/-/releases/latest"] = { exchange -> respond(exchange, 200, releaseJson()) }
        handlers["/org/repo/-/releases/tags/v1.0.0"] = { exchange -> respond(exchange, 200, releaseJson()) }
        handlers["/org/repo/-/releases/release-1"] = { exchange ->
            if (exchange.requestMethod == "GET") respond(exchange, 200, releaseJson()) else respond(exchange, 204, "")
        }
        handlers["/org/repo/-/releases/release-1/assets/asset-1"] = { exchange ->
            if (exchange.requestMethod == "GET") respond(exchange, 200, releaseAssetJson()) else respond(exchange, 204, "")
        }

        assertEquals("release-1", client.getLatestRelease("org/repo")?.id)
        assertEquals("release-1", client.getRelease("org/repo", "release-1").id)
        assertEquals("release-1", client.getReleaseByTag("org/repo", "v1.0.0").id)
        assertEquals("asset-1", client.getReleaseAsset("org/repo", "release-1", "asset-1").id)
        val created =
            client.createRelease(
                "org/repo",
                CnbCreateReleaseRequest(
                    tagName = "v2.0.0",
                    targetCommitish = shaB,
                    name = "Version 2",
                    makeLatest = CnbReleaseMakeLatest.LEGACY,
                ),
            )
        client.updateRelease(
            "org/repo",
            "release-1",
            CnbUpdateReleaseRequest(name = "Renamed", draft = true, makeLatest = CnbReleaseMakeLatest.FALSE),
        )
        client.deleteReleaseAsset("org/repo", "release-1", "asset-1")
        client.deleteRelease("org/repo", "release-1")

        assertEquals("release-2", created.id)
        assertNull(created.author)
        assertTrue(created.assets.isEmpty())
        val create = requests.single { it.method == "POST" && it.rawPath == "/org/repo/-/releases" }
        assertTrue(create.body.contains("\"make_latest\":\"legacy\""))
        val update = requests.single { it.method == "PATCH" }
        assertTrue(update.body.contains("\"draft\":true"))
        assertTrue(update.body.contains("\"make_latest\":\"false\""))
        assertEquals(2, requests.count { it.method == "DELETE" })
    }

    @Test
    fun `streams release downloads without forwarding authorization to signed storage`() {
        val artifact = "release-binary".toByteArray(StandardCharsets.UTF_8)
        handlers["/org/repo/-/releases/download/v1.0.0/plugin.hpi"] = { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/object/release?signature=signed")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        handlers["/object/release"] = { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("ETag", "release-etag")
            exchange.sendResponseHeaders(200, artifact.size.toLong())
            exchange.responseBody.use { it.write(artifact) }
        }
        val sinks = CopyOnWriteArrayList<ByteArrayOutputStream>()

        val download =
            requireNotNull(
                client.downloadReleaseAsset(
                    "org/repo",
                    "v1.0.0",
                    "plugin.hpi",
                    CnbDownloadTarget { ByteArrayOutputStream().also(sinks::add) },
                    share = true,
                    maxBytes = 1024,
                ),
            )

        assertEquals(artifact.size.toLong(), download.contentLength)
        assertEquals("application/octet-stream", download.contentType)
        assertEquals("release-etag", download.etag)
        assertTrue(artifact.contentEquals(sinks.single().toByteArray()))
        assertEquals("Bearer top-secret", requests.first().authorization)
        assertNull(requests.last().authorization)
        assertNoJsonNegotiation(requests.last())
        assertTrue(requests.first().query.contains("share=true"))
    }

    @Test
    fun `checks release asset metadata without forwarding authorization to signed storage`() {
        handlers["/org/repo/-/releases/download/v1.0.0/plugin.hpi"] = { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/object/release-head?signature=signed")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        handlers["/object/release-head"] = { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("Content-Length", "8")
            exchange.responseHeaders.add("ETag", "head-etag")
            exchange.responseHeaders.add("Last-Modified", "Wed, 15 Jul 2026 10:00:00 GMT")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        val head = client.headReleaseAsset("org/repo", "v1.0.0", "plugin.hpi")

        assertTrue(head.exists)
        assertEquals(8, head.contentLength)
        assertEquals("head-etag", head.etag)
        assertEquals("Bearer top-secret", requests.first().authorization)
        assertNull(requests.last().authorization)
        assertNoJsonNegotiation(requests.last())
    }

    @Test
    fun `rejects release downloads whose declared size exceeds the caller bound before opening a sink`() {
        handlers["/org/repo/-/releases/download/v1.0.0/plugin.hpi"] = { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, 32)
            exchange.responseBody.use { it.write(ByteArray(32)) }
        }
        var opened = 0

        assertThrows(CnbApiException::class.java) {
            client.downloadReleaseAsset(
                "org/repo",
                "v1.0.0",
                "plugin.hpi",
                CnbDownloadTarget {
                    opened++
                    ByteArrayOutputStream()
                },
                maxBytes = 8,
            )
        }

        assertEquals(0, opened)
    }

    @Test
    fun `rejects malformed release commitish and timestamp response fields`() {
        var body = releaseJson().replace("\"tag_commitish\":\"$shaA\"", "\"tag_commitish\":\"bad..ref\"")
        handlers["/org/repo/-/releases/latest"] = { exchange -> respond(exchange, 200, body) }

        assertThrows(CnbApiException::class.java) { client.getLatestRelease("org/repo") }

        body = releaseJson().replace("2026-07-15T10:00:00Z", "yesterday")
        assertThrows(CnbApiException::class.java) { client.getLatestRelease("org/repo") }
    }

    @Test
    fun `uploads exact release asset streams without forwarding authorization then confirms on CNB`() {
        val artifact = "artifact".toByteArray(StandardCharsets.UTF_8)
        configureReleaseUploadTicket()
        handlers["/object/upload"] = { exchange -> respond(exchange, 200, "") }
        handlers[
            "/org/repo/-/releases/release-1/asset-upload-confirmation/token//org/repo/-/releases/download/v1.0.0/plugin.hpi",
        ] = { exchange ->
            respond(exchange, 204, "")
        }

        client.uploadReleaseAsset(
            "org/repo",
            "release-1",
            CnbReleaseAssetUploadRequest("plugin.hpi", artifact.size.toLong(), overwrite = true, ttlDays = 30),
            CnbRepeatableInput { ByteArrayInputStream(artifact) },
        )

        val upload = requests.single { it.rawPath == "/object/upload" }
        assertEquals("PUT", upload.method)
        assertEquals("artifact", upload.body)
        assertNull(upload.authorization)
        assertNoJsonNegotiation(upload)
        val confirmation = requests.single { it.rawPath.contains("asset-upload-confirmation") }
        assertEquals("POST", confirmation.method)
        assertEquals("Bearer top-secret", confirmation.authorization)
        assertEquals("ttl=30", confirmation.query)
    }

    @Test
    fun `reopens an exact release asset stream across a 307 signed redirect`() {
        assertSignedUploadRedirect(307)
    }

    @Test
    fun `reopens an exact release asset stream across a 308 signed redirect`() {
        assertSignedUploadRedirect(308)
    }

    @Test
    fun `supplies the optional release confirmation ttl when the ticket omits it`() {
        val artifact = byteArrayOf(1)
        configureReleaseUploadTicket(
            verifyUrl =
                "http://127.0.0.1:${server.address.port}/org/repo/-/releases/release-1/" +
                    "asset-upload-confirmation/token/" +
                    "%2Forg%2Frepo%2F-%2Freleases%2Fdownload%2Fv1.0.0%2Fplugin.hpi",
        )
        handlers["/object/upload"] = { exchange -> respond(exchange, 200, "") }
        handlers[
            "/org/repo/-/releases/release-1/asset-upload-confirmation/token//org/repo/-/releases/download/v1.0.0/plugin.hpi",
        ] = { exchange -> respond(exchange, 204, "") }

        client.uploadReleaseAsset(
            "org/repo",
            "release-1",
            CnbReleaseAssetUploadRequest("plugin.hpi", artifact.size.toLong(), ttlDays = 30),
            CnbRepeatableInput { ByteArrayInputStream(artifact) },
        )

        assertEquals("ttl=30", requests.single { it.rawPath.contains("asset-upload-confirmation") }.query)
    }

    @Test
    fun `accepts strict path encoding independently of the server encoder`() {
        val assetName = "插件+meta~@.hpi"
        val artifact = byteArrayOf(1)
        configureReleaseUploadTicket(
            verifyUrl =
                "http://127.0.0.1:${server.address.port}/org/repo/-/releases/release-1/" +
                    "asset-upload-confirmation/token/" +
                    "%2Forg%2Frepo%2F-%2Freleases%2Fdownload%2Frelease%2Fv1.0.0%2F" +
                    "%E6%8F%92%E4%BB%B6+meta~@.hpi?ttl=30",
        )
        handlers["/object/upload"] = { exchange -> respond(exchange, 200, "") }
        handlers[
            "/org/repo/-/releases/release-1/asset-upload-confirmation/token//org/repo/-/releases/" +
                "download/release/v1.0.0/$assetName",
        ] = { exchange -> respond(exchange, 204, "") }

        client.uploadReleaseAsset(
            "org/repo",
            "release-1",
            CnbReleaseAssetUploadRequest(assetName, artifact.size.toLong(), ttlDays = 30),
            CnbRepeatableInput { ByteArrayInputStream(artifact) },
        )

        assertEquals("ttl=30", requests.single { it.rawPath.contains("asset-upload-confirmation") }.query)
    }

    @Test
    fun `rejects short and long upload sources without confirming the asset`() {
        configureReleaseUploadTicket(ttlDays = 0)
        handlers["/object/upload"] = { exchange -> respond(exchange, 200, "") }
        handlers["/org/repo/-/releases/release-1/asset-upload-confirmation/token/releases/plugin.hpi"] = { exchange ->
            respond(exchange, 204, "")
        }
        val declared = 8L

        assertThrows(IOException::class.java) {
            client.uploadReleaseAsset(
                "org/repo",
                "release-1",
                CnbReleaseAssetUploadRequest("plugin.hpi", declared),
                CnbRepeatableInput { ByteArrayInputStream("short".toByteArray()) },
            )
        }
        assertThrows(IOException::class.java) {
            client.uploadReleaseAsset(
                "org/repo",
                "release-1",
                CnbReleaseAssetUploadRequest("plugin.hpi", declared),
                CnbRepeatableInput { ByteArrayInputStream("too-long-source".toByteArray()) },
            )
        }

        assertTrue(requests.none { it.rawPath.contains("asset-upload-confirmation") })
    }

    @Test
    fun `rejects a cross-origin upload verification URL before touching storage`() {
        configureReleaseUploadTicket(verifyUrl = "https://evil.example/confirm/token/path")

        assertThrows(CnbApiException::class.java) {
            client.uploadReleaseAsset(
                "org/repo",
                "release-1",
                CnbReleaseAssetUploadRequest("plugin.hpi", 1),
                CnbRepeatableInput { ByteArrayInputStream(byteArrayOf(1)) },
            )
        }

        assertEquals(1, requests.size)
        assertTrue(requests.none { it.rawPath == "/object/upload" })
    }

    @Test
    fun `rejects mutated release verification parameters and asset paths before touching storage`() {
        val endpoint =
            "http://127.0.0.1:${server.address.port}/org/repo/-/releases/release-1/" +
                "asset-upload-confirmation/token/"
        val validAssetPath = "%2Forg%2Frepo%2F-%2Freleases%2Fdownload%2Fv1.0.0%2Fplugin.hpi"
        val invalidUrls =
            listOf(
                "$endpoint$validAssetPath?ttl=29",
                "$endpoint$validAssetPath?ttl=30&redirect=evil",
                "$endpoint$validAssetPath?ttl=%33%30",
                "$endpoint$validAssetPath?",
                "$endpoint$validAssetPath?ttl=30#fragment",
                "http://127.0.0.1:${server.address.port}/org/repo/-/releases/release-2/" +
                    "asset-upload-confirmation/token/$validAssetPath?ttl=30",
                "http://127.0.0.1:${server.address.port}/org/repo/-/releases/release-1/" +
                    "asset-upload-confirmation/%74oken/$validAssetPath?ttl=30",
                "$endpoint%2Fother%2Frepo%2F-%2Freleases%2Fdownload%2Fv1.0.0%2Fplugin.hpi?ttl=30",
                "$endpoint%2Forg%2Frepo%2F-%2Freleases%2Fdownload%2Fv1.0.0%2Fother.hpi?ttl=30",
                "$endpoint%2Forg%2Frepo%2F-%2Freleases%2Fdownload%2Fbad..tag%2Fplugin.hpi?ttl=30",
                "$endpoint%2Forg%2Frepo%2F-%2Freleases%2Fdownload%2Frelease%2F..%2Fother%2Fplugin.hpi?ttl=30",
                "$endpoint%2Forg%2Frepo%2F-%2Fpackages%2Fdownload%2Fv1.0.0%2Fplugin.hpi?ttl=30",
                "$endpoint%252Forg%252Frepo%252F-%252Freleases%252Fdownload%252Fv1.0.0%252Fplugin.hpi?ttl=30",
                "$endpoint%FF?ttl=30",
                "$endpoint%ZZ?ttl=30",
                "$endpoint/org/repo/-/releases/download/v1.0.0/plugin.hpi?ttl=30",
            )

        invalidUrls.forEach { verifyUrl ->
            configureReleaseUploadTicket(verifyUrl = verifyUrl)
            assertThrows(CnbApiException::class.java) {
                client.uploadReleaseAsset(
                    "org/repo",
                    "release-1",
                    CnbReleaseAssetUploadRequest("plugin.hpi", 1, ttlDays = 30),
                    CnbRepeatableInput { ByteArrayInputStream(byteArrayOf(1)) },
                )
            }
        }

        assertTrue(requests.none { it.rawPath == "/object/upload" })
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

        assertFalse(failure.retryable)
        assertEquals(1, requests.size)
    }

    @Test
    fun `destroys encoded request bytes after success and preflight failure`() {
        handlers["/wipe"] = { exchange -> respond(exchange, 204, "") }
        val requestBytes =
            HttpCnbClient::class.java.declaredMethods.single {
                it.name == "requestBytes" && it.parameterCount == 6
            }
        requestBytes.isAccessible = true
        val successfulBytes = """{"secret":"success-value"}""".toByteArray(StandardCharsets.UTF_8)
        val successfulBody = CnbEncodedJsonBody(successfulBytes)

        requestBytes.invoke(client, "POST", "/wipe", emptyMap<String, String>(), successfulBody, false, false)

        assertTrue(successfulBytes.all { it == 0.toByte() })

        val rejectedBytes = """{"secret":"rejected-value"}""".toByteArray(StandardCharsets.UTF_8)
        val rejectedBody = CnbEncodedJsonBody(rejectedBytes)
        assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            requestBytes.invoke(client, "POST", "//invalid", emptyMap<String, String>(), rejectedBody, false, false)
        }
        assertTrue(rejectedBytes.all { it == 0.toByte() })
    }

    @Test
    fun `writes annotations through an idempotent PUT`() {
        handlers["/org/repo/-/git/commit-annotations/abc123"] = { exchange ->
            respond(exchange, 204, "")
        }

        client.putCommitAnnotations(
            "org/repo",
            "abc123",
            listOf(CnbCommitAnnotation("jenkins_job_result", "SUCCESS")),
        )

        val request = requests.single()
        assertEquals("PUT", request.method)
        assertEquals(
            """{"annotations":[{"key":"jenkins_job_result","value":"SUCCESS"}]}""",
            request.body,
        )
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
                    {"id":"event-1","type":"PushEvent","created_at":"2026-07-15T10:01:00Z","repo":{"path":"actual/repo"},"payload":{"ref":"refs/heads/main","head":"$shaA","ref_type":"branch","future":2}},
                    {"id":"event-2","type":"IssueEvent","created_at":"2026-07-15T10:02:00Z","payload":{}}
                ]}
                """.trimIndent(),
            )
        }

        val events = client.listRepositoryEvents("org/repo", ZonedDateTime.parse("2026-07-15T10:00:00Z"))

        assertEquals(2, events.size)
        assertEquals("actual/repo", events.first().repositoryPath)
        assertEquals("refs/heads/main", events.first().payload.ref)
        assertEquals(shaA, events.first().payload.head)
        assertEquals(CnbRepositoryRefType.BRANCH, events.first().payload.refType)
        assertEquals("org/repo", events.last().repositoryPath)
        assertEquals("", events.last().payload.ref)
        assertEquals("Bearer top-secret", requests.single().authorization)
    }

    @Test
    fun `rejects repository-events envelopes missing their required array`() {
        handlers["/events/org/repo/-/2026-07-15-10"] = { exchange -> respond(exchange, 200, "{}") }

        assertThrows(CnbApiException::class.java) {
            client.listRepositoryEvents("org/repo", ZonedDateTime.parse("2026-07-15T10:00:00Z"))
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `rejects repository event payloads with incompatible types`() {
        handlers["/events/org/repo/-/2026-07-15-10"] = { exchange ->
            respond(
                exchange,
                200,
                """[{"id":"event-1","type":"PushEvent","payload":"not-an-object"}]""",
            )
        }

        assertThrows(CnbApiException::class.java) {
            client.listRepositoryEvents("org/repo", ZonedDateTime.parse("2026-07-15T10:00:00Z"))
        }
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

    private fun releaseJson(
        id: String = "release-1",
        tag: String = "v1.0.0",
    ): String =
        """
        {
          "id":"$id","tag_name":"$tag","name":"Version $tag","body":"Production release",
          "tag_commitish":"$shaA","draft":false,"prerelease":false,"is_latest":true,
          "created_at":"2026-07-15T10:00:00Z","updated_at":"2026-07-15T10:01:00Z","published_at":"2026-07-15T10:02:00Z",
          "author":{"username":"alice","nickname":"Alice","email":"alice@example.com","avatar":"http://127.0.0.1:${server.address.port}/avatars/alice","freeze":false,"is_npc":false},
          "assets":[${releaseAssetJson()}]
        }
        """.trimIndent()

    private fun richPullJson(number: String): String =
        """
        {
          "number":"$number","title":"Typed pull","state":"open","body":"Production body",
          "head":{"ref":"refs/heads/feature/api","sha":"$shaA","repo":{"path":"fork/repo"}},
          "base":{"ref":"refs/heads/main","sha":"$shaB","repo":{"path":"org/repo"}},
          "merge_sha":"$shaC","blocked_on":"status_check","mergeable_state":"mergeable","is_wip":false,
          "author":{"username":"author","nickname":"Author"},
          "assignees":[{"username":"alice","nickname":"Alice"}],
          "reviewers":[{"review_state":"approved","user":{"username":"bob","nickname":"Bob"}}],
          "labels":[{"id":"label-1","name":"ready","color":"00ff00"}],
          "created_at":"2026-07-15T09:00:00Z","updated_at":"2026-07-15T09:15:00Z"
        }
        """.trimIndent()

    private fun reviewCommentJson(
        id: String,
        replyTo: String? = null,
    ): String =
        """
        {
          "id":"$id","review_id":"review-1","body":"Review comment","author":{"username":"alice"},
          "commit_hash":"$shaA","path":"src/Main.kt","review_state":"approved",
          ${if (replyTo == null) "" else "\"reply_to_comment_id\":\"$replyTo\","}
          "subject_type":"line","start_line":10,"start_side":"right","end_line":10,"end_side":"right",
          "diff_hunk":[{"content":"+value","left_line_number":0,"right_line_number":10,"prefix":"+","type":"addition"}],
          "reactions":[{"count":1,"has_reacted":true,"reaction":"thumbs_up","top_users":[{"username":"bob"}]}],
          "created_at":"2026-07-15T09:00:00Z","updated_at":"2026-07-15T09:01:00Z"
        }
        """.trimIndent()

    private fun releaseAssetJson(): String =
        """
        {
          "id":"asset-1","name":"plugin.hpi","path":"/org/repo/-/releases/download/v1.0.0/plugin.hpi","size":8,
          "content_type":"application/octet-stream","download_count":3,"hash_algo":"sha256","hash_value":"${"ab".repeat(32)}",
          "browser_download_url":"http://127.0.0.1:${server.address.port}/download/right",
          "brower_download_url":"http://127.0.0.1:${server.address.port}/download/legacy",
          "url":"http://127.0.0.1:${server.address.port}/api/assets/asset-1",
          "created_at":"2026-07-15T10:00:00Z","updated_at":"2026-07-15T10:01:00Z",
          "uploader":{"username":"alice","avatar":"http://127.0.0.1:${server.address.port}/avatars/alice"}
        }
        """.trimIndent()

    private fun configureReleaseUploadTicket(
        ttlDays: Int = 30,
        uploadUrl: String = "http://127.0.0.1:${server.address.port}/object/upload?signature=signed",
        verifyUrl: String =
            "http://127.0.0.1:${server.address.port}/org/repo/-/releases/release-1/asset-upload-confirmation/token/" +
                "%2Forg%2Frepo%2F-%2Freleases%2Fdownload%2Fv1.0.0%2Fplugin.hpi?ttl=$ttlDays",
    ) {
        handlers["/org/repo/-/releases/release-1/asset-upload-url"] = { exchange ->
            respond(
                exchange,
                200,
                """{"expires_in_sec":300,"upload_url":"$uploadUrl","verify_url":"$verifyUrl"}""",
            )
        }
    }

    private fun assertSignedUploadRedirect(status: Int) {
        val artifact = "redirected-artifact".toByteArray(StandardCharsets.UTF_8)
        val opened = AtomicInteger()
        configureReleaseUploadTicket(
            uploadUrl = "http://127.0.0.1:${server.address.port}/object/upload-start?signature=first",
        )
        handlers["/object/upload-start"] = { exchange ->
            exchange.responseHeaders.add(
                "Location",
                "http://127.0.0.1:${server.address.port}/object/upload-final?signature=second",
            )
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        handlers["/object/upload-final"] = { exchange -> respond(exchange, 200, "") }
        handlers[
            "/org/repo/-/releases/release-1/asset-upload-confirmation/token//org/repo/-/releases/download/v1.0.0/plugin.hpi",
        ] = { exchange -> respond(exchange, 204, "") }

        client.uploadReleaseAsset(
            "org/repo",
            "release-1",
            CnbReleaseAssetUploadRequest("plugin.hpi", artifact.size.toLong(), ttlDays = 30),
            CnbRepeatableInput {
                opened.incrementAndGet()
                ByteArrayInputStream(artifact)
            },
        )

        val uploads = requests.filter { it.rawPath == "/object/upload-start" || it.rawPath == "/object/upload-final" }
        assertEquals(2, uploads.size)
        assertEquals(2, opened.get())
        assertEquals("signature=first", uploads.single { it.rawPath == "/object/upload-start" }.query)
        assertEquals("signature=second", uploads.single { it.rawPath == "/object/upload-final" }.query)
        uploads.forEach { upload ->
            assertEquals("PUT", upload.method)
            assertEquals("redirected-artifact", upload.body)
            assertEquals(artifact.size.toString(), upload.contentLength)
            assertNull(upload.transferEncoding)
            assertNull(upload.authorization)
            assertNoJsonNegotiation(upload)
        }
        assertEquals(1, requests.count { it.rawPath.contains("asset-upload-confirmation") })
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

    private fun throwableGraph(root: Throwable): List<Throwable> {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        val output = ArrayList<Throwable>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            output += current
            current.cause?.let(pending::addLast)
            current.suppressed.forEach(pending::addLast)
        }
        return output
    }

    private fun assertNoJsonNegotiation(request: Request) {
        val advertised = request.accept.joinToString(",").lowercase()
        assertFalse(advertised.contains(CNB_MEDIA_TYPE))
        assertFalse(advertised.contains("application/json"))
    }

    private data class Request(
        val method: String,
        val rawPath: String,
        val query: String,
        val authorization: String?,
        val accept: List<String>,
        val contentLength: String?,
        val transferEncoding: String?,
        val body: String,
    )
}
