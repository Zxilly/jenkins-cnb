package dev.zxilly.jenkins.cnb.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbAuthenticatedUser
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbCommitAnnotation
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatus
import dev.zxilly.jenkins.cnb.api.model.CnbContent
import dev.zxilly.jenkins.cnb.api.model.CnbContentEntry
import dev.zxilly.jenkins.cnb.api.model.CnbPullComment
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEvent
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.security.CnbEndpointPolicy
import hudson.ProxyConfiguration
import hudson.util.Secret
import jenkins.model.Jenkins
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.HexFormat
import java.util.LinkedHashSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

internal class HttpCnbClient(
    private val server: CnbServer,
    private val token: Secret?,
    private val mapper: ObjectMapper = ObjectMapper(),
) : CnbClient {
    private val baseUri: URI = server.normalizedApiUri()
    private val permits = PERMITS.computeIfAbsent(server.id) { Semaphore(MAX_CONCURRENT_REQUESTS, true) }
    private val circuit = CIRCUITS.computeIfAbsent(server.id) { CircuitBreaker() }
    private val httpClient =
        ProxyConfiguration
            .newHttpClientBuilder()
            .connectTimeout(Duration.ofSeconds(server.connectTimeoutSeconds.toLong()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_2)
            .build()

    override val capabilities: CnbApiCapabilities = CnbApiCapabilities()

    override fun close() {
        httpClient.close()
    }

    override fun testConnection(): CnbAuthenticatedUser {
        val node = request("GET", "/user") ?: throw CnbApiException("CNB returned an empty user response")
        return CnbAuthenticatedUser(
            username = node.text("username"),
            nickname = node.text("nickname"),
            email = node.text("email"),
        )
    }

    override fun getRepository(path: String): CnbRepository {
        val safeRepo = encodeRepository(path)
        val node = request("GET", "/$safeRepo") ?: throw CnbApiException("CNB repository response was empty")
        val head = request("GET", "/$safeRepo/-/git/head", acceptNotFound = true)
        return parseRepository(node, head?.text("name").orEmpty())
    }

    override fun listRepositories(
        namespace: String,
        includeDescendants: Boolean,
    ): List<CnbRepository> {
        val slug = encodeRepository(namespace)
        val query = mapOf("descendant" to if (includeDescendants) "all" else "sub")
        return try {
            paginate("/$slug/-/repos", query) { parseRepository(it) }
        } catch (failure: CnbApiException) {
            if (failure.statusCode != 404 || namespace.contains('/')) throw failure
            paginate("/users/${encodeSegment(namespace)}/repos") { parseRepository(it) }
        }
    }

    override fun listUserRepositories(): List<CnbRepository> = paginate("/user/repos", mapOf("role" to "Guest")) { parseRepository(it) }

    override fun listBranches(repo: String): List<CnbBranch> {
        val safeRepo = encodeRepository(repo)
        return paginate("/$safeRepo/-/git/branches") { parseBranch(it) }
    }

    override fun getBranch(
        repo: String,
        name: String,
    ): CnbBranch {
        val node =
            request("GET", "/${encodeRepository(repo)}/-/git/branches/${encodeSegment(name)}")
                ?: throw CnbApiException("CNB branch response was empty")
        return parseBranch(node)
    }

    override fun listTags(repo: String): List<CnbTag> {
        val safeRepo = encodeRepository(repo)
        return paginate("/$safeRepo/-/git/tags") { parseTag(it) }
    }

    override fun listPullRequests(
        repo: String,
        state: String,
    ): List<CnbPullRequest> {
        require(state in setOf("open", "closed", "all")) { "Unsupported pull request state: $state" }
        val safeRepo = encodeRepository(repo)
        return paginate("/$safeRepo/-/pulls", mapOf("state" to state, "order_by" to "-updated_at")) {
            parsePullRequest(it, repo)
        }
    }

    override fun getPullRequest(
        repo: String,
        number: String,
    ): CnbPullRequest {
        val node =
            request("GET", "/${encodeRepository(repo)}/-/pulls/${encodeSegment(number)}")
                ?: throw CnbApiException("CNB pull request response was empty")
        return parsePullRequest(node, repo)
    }

    override fun getContent(
        repo: String,
        path: String,
        ref: String,
    ): CnbContent? {
        val normalizedPath = path.replace('\\', '/').trim('/')
        val contentPath =
            if (normalizedPath.isEmpty()) {
                "/${encodeRepository(repo)}/-/git/contents"
            } else {
                "/${encodeRepository(repo)}/-/git/contents/${encodeRepository(normalizedPath)}"
            }
        val node =
            request(
                "GET",
                contentPath,
                mapOf("ref" to ref),
                acceptNotFound = true,
            ) ?: return null
        val entries = ArrayList<CnbContentEntry>()
        val entriesNode = node.path("entries")
        if (entriesNode.isArray) {
            for (entry in entriesNode) {
                entries.add(
                    CnbContentEntry(
                        name = entry.text("name", entry.text("path").substringAfterLast('/')),
                        path = entry.text("path"),
                        sha = entry.text("sha"),
                        type = entry.text("type"),
                        size = entry.long("size"),
                    ),
                )
            }
        }
        return CnbContent(
            path = node.text("path", path),
            sha = node.text("sha"),
            type = node.text("type"),
            size = node.long("size"),
            content = node.optionalText("content"),
            encoding = node.optionalText("encoding"),
            entries = entries,
        )
    }

    override fun listCommitStatuses(
        repo: String,
        commitish: String,
    ): List<CnbCommitStatus> {
        val node =
            request(
                "GET",
                "/${encodeRepository(repo)}/-/git/commit-statuses/${encodeSegment(commitish)}",
            ) ?: return emptyList()
        val statuses = ArrayList<CnbCommitStatus>()
        for (item in node.arrayElements()) {
            statuses.add(
                CnbCommitStatus(
                    context = item.text("context"),
                    state = item.text("state"),
                    description = item.text("description"),
                    targetUrl = item.text("target_url"),
                    createdAt = item.text("created_at"),
                    updatedAt = item.text("updated_at"),
                ),
            )
        }
        return statuses
    }

    override fun getCommitAnnotations(
        repo: String,
        sha: String,
    ): List<CnbCommitAnnotation> {
        val node =
            request(
                "GET",
                "/${encodeRepository(repo)}/-/git/commit-annotations/${encodeSegment(sha)}",
                acceptNotFound = true,
            ) ?: return emptyList()
        val annotations = ArrayList<CnbCommitAnnotation>()
        for (item in node.arrayElements()) {
            val key = item.optionalText("key") ?: continue
            annotations.add(CnbCommitAnnotation(key, item.text("value")))
        }
        return annotations
    }

    override fun putCommitAnnotations(
        repo: String,
        sha: String,
        annotations: List<CnbCommitAnnotation>,
    ) {
        require(annotations.size <= MAX_ANNOTATIONS) { "At most $MAX_ANNOTATIONS annotations may be written at once" }
        val body = mapper.createObjectNode()
        val values = body.putArray("annotations")
        annotations.forEach { annotation ->
            require(annotation.key.length in 1..MAX_ANNOTATION_KEY_LENGTH) { "Invalid annotation key length" }
            require(annotation.value.length <= MAX_ANNOTATION_VALUE_LENGTH) { "Annotation value is too long" }
            values.addObject().put("key", annotation.key).put("value", annotation.value)
        }
        request(
            "PUT",
            "/${encodeRepository(repo)}/-/git/commit-annotations/${encodeSegment(sha)}",
            body = body,
            idempotent = true,
        )
    }

    override fun deleteCommitAnnotation(
        repo: String,
        sha: String,
        key: String,
    ) {
        request(
            "DELETE",
            "/${encodeRepository(repo)}/-/git/commit-annotations/${encodeSegment(sha)}/${encodeSegment(key)}",
            idempotent = true,
        )
    }

    override fun listPullComments(
        repo: String,
        number: String,
    ): List<CnbPullComment> {
        val safeRepo = encodeRepository(repo)
        return paginate("/$safeRepo/-/pulls/${encodeSegment(number)}/comments") { parsePullComment(it) }
    }

    override fun createPullComment(
        repo: String,
        number: String,
        body: String,
    ): CnbPullComment {
        validateComment(body)
        val payload = mapper.createObjectNode().put("body", body)
        val node =
            request(
                "POST",
                "/${encodeRepository(repo)}/-/pulls/${encodeSegment(number)}/comments",
                body = payload,
                idempotent = false,
            ) ?: throw CnbApiException("CNB create comment response was empty")
        return parsePullComment(node)
    }

    override fun updatePullComment(
        repo: String,
        number: String,
        commentId: String,
        body: String,
    ): CnbPullComment {
        validateComment(body)
        val payload = mapper.createObjectNode().put("body", body)
        val node =
            request(
                "PATCH",
                "/${encodeRepository(repo)}/-/pulls/${encodeSegment(number)}/comments/${encodeSegment(commentId)}",
                body = payload,
                idempotent = true,
            ) ?: throw CnbApiException("CNB update comment response was empty")
        return parsePullComment(node)
    }

    override fun listRepositoryEvents(
        repo: String,
        hour: ZonedDateTime,
    ): List<CnbRepositoryEvent> {
        val date = hour.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-H"))
        val node = requestPresignedJson("/events/${encodeRepository(repo)}/-/$date") ?: return emptyList()
        val events =
            when {
                node.isArray -> node
                node.path("events").isArray -> node.path("events")
                else -> return emptyList()
            }
        val parsedEvents = ArrayList<CnbRepositoryEvent>()
        for (event in events) {
            if (parsedEvents.size >= MAX_REPOSITORY_EVENTS_PER_HOUR) {
                throw CnbApiException("CNB repository event item limit exceeded")
            }
            val payloadNode = event.path("payload")

            @Suppress("UNCHECKED_CAST")
            val payload =
                if (payloadNode.isObject) {
                    mapper.convertValue(payloadNode, Map::class.java) as Map<String, Any?>
                } else {
                    emptyMap()
                }
            parsedEvents.add(
                CnbRepositoryEvent(
                    id = event.text("id"),
                    type = event.text("type"),
                    repositoryPath = event.path("repo").text("path", repo),
                    createdAt = event.text("created_at"),
                    payload = payload,
                ),
            )
        }
        return parsedEvents
    }

    private fun <T> paginate(
        path: String,
        query: Map<String, String> = emptyMap(),
        transform: (JsonNode) -> T,
    ): List<T> {
        val output = ArrayList<T>()
        val pageFingerprints = LinkedHashSet<String>()
        val resourceFingerprints = LinkedHashSet<String>()
        var aggregateBytes = 0L
        for (page in 1..MAX_PAGES + 1) {
            val pageNode =
                request("GET", path, query + mapOf("page" to page.toString(), "page_size" to PAGE_SIZE.toString()))
                    ?: return output
            aggregateBytes += mapper.writeValueAsBytes(pageNode).size
            if (aggregateBytes > MAX_PAGINATED_BYTES) {
                throw CnbApiException("CNB pagination byte limit exceeded for $path")
            }
            val values = pageNode.arrayElements()
            if (values.isEmpty()) return output
            if (page > MAX_PAGES) {
                throw CnbApiException("CNB pagination page limit exceeded for $path")
            }
            val fingerprint = pageFingerprint(values)
            if (!pageFingerprints.add(fingerprint)) {
                throw CnbApiException("CNB returned a repeated pagination page for $path")
            }
            values.forEach {
                if (resourceFingerprints.add(resourceFingerprint(it))) {
                    if (resourceFingerprints.size > MAX_PAGINATED_ITEMS) {
                        throw CnbApiException("CNB pagination item limit exceeded for $path")
                    }
                    output.add(transform(it))
                }
            }
        }
        return output
    }

    private fun pageFingerprint(values: List<JsonNode>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { node ->
            digest.update(resourceFingerprint(node).toByteArray(StandardCharsets.US_ASCII))
            digest.update(0.toByte())
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun resourceFingerprint(node: JsonNode): String {
        for (field in listOf("id", "path", "number", "iid", "name", "sha")) {
            val value = node.path(field)
            if (value.isValueNode && !value.asText().isNullOrBlank()) {
                return sha256("$field\u0000${value.asText()}".toByteArray(StandardCharsets.UTF_8))
            }
        }
        return sha256(mapper.writeValueAsBytes(node))
    }

    private fun sha256(value: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))

    private fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonNode? = null,
        idempotent: Boolean = method == "GET",
        acceptNotFound: Boolean = false,
    ): JsonNode? {
        require(path.startsWith('/') && !path.startsWith("//")) { "CNB API path must be relative to the server" }
        circuit.beforeRequest()
        val uri = buildUri(path, query)
        var lastFailure: Throwable? = null
        val attempts = if (idempotent) MAX_ATTEMPTS else 1
        for (attempt in 1..attempts) {
            try {
                val response = execute(method, uri, body)
                if (response.statusCode() == 404 && acceptNotFound) {
                    circuit.success()
                    return null
                }
                if (response.statusCode() in 200..299) {
                    circuit.success()
                    if (response.body().isEmpty()) return null
                    return mapper.readTree(response.body())
                }

                val retryable = response.statusCode() == 429 || response.statusCode() in RETRYABLE_STATUS_CODES
                val error = errorFromResponse(response.statusCode(), response.body(), retryable)
                if (!retryable) {
                    circuit.success()
                    throw error
                }
                if (attempt == attempts) {
                    circuit.failure()
                    throw error
                }
                lastFailure = error
                sleepBeforeRetry(attempt, response.headers().firstValue("Retry-After").orElse(null))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CnbApiException("Interrupted while calling CNB", retryable = true, cause = e)
            } catch (e: CnbApiException) {
                throw e
            } catch (e: IOException) {
                lastFailure = e
                if (!idempotent || attempt == attempts) {
                    circuit.failure()
                    throw CnbApiException("CNB request failed: ${safeMessage(e)}", retryable = true, cause = e)
                }
                sleepBeforeRetry(attempt, null)
            }
        }
        throw CnbApiException("CNB request failed", retryable = true, cause = lastFailure)
    }

    /**
     * The repository-events API returns a short-lived object-storage URL. Redirect handling is
     * deliberately separate so the CNB bearer token can never cross the API origin boundary.
     */
    private fun requestPresignedJson(path: String): JsonNode? {
        circuit.beforeRequest()
        val initialUri = buildUri(path, emptyMap())
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val result = requestPresignedJsonOnce(initialUri)
                circuit.success()
                return result
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CnbApiException("Interrupted while calling CNB", retryable = true, cause = e)
            } catch (e: CnbApiException) {
                if (!e.retryable || attempt == MAX_ATTEMPTS) {
                    if (e.retryable) circuit.failure() else circuit.success()
                    throw e
                }
                sleepBeforeRetry(attempt, null)
            } catch (e: IOException) {
                if (attempt == MAX_ATTEMPTS) {
                    circuit.failure()
                    throw CnbApiException(
                        "CNB repository-events request failed (${e.javaClass.simpleName})",
                        retryable = true,
                    )
                }
                sleepBeforeRetry(attempt, null)
            }
        }
        circuit.failure()
        throw CnbApiException("CNB repository-events request failed", retryable = true)
    }

    private fun requestPresignedJsonOnce(initialUri: URI): JsonNode? {
        val first = execute("GET", initialUri, null, includeAuthorization = true)
        if (first.statusCode() in 200..299) {
            return first.body().takeIf { it.isNotEmpty() }?.let(mapper::readTree)
        }
        if (first.statusCode() !in REDIRECT_STATUS_CODES) {
            val retryable = first.statusCode() in RETRYABLE_STATUS_CODES || first.statusCode() == 429
            throw errorFromResponse(first.statusCode(), first.body(), retryable)
        }

        var target =
            first.headers().firstValue("Location").orElseThrow {
                CnbApiException("CNB repository-events redirect did not include Location")
            }
        var targetUri = initialUri.resolve(target)
        for (redirect in 1..MAX_PRESIGNED_REDIRECTS) {
            validatePresignedUri(targetUri)
            val response = execute("GET", targetUri, null, includeAuthorization = false)
            if (response.statusCode() in 200..299) {
                return response.body().takeIf { it.isNotEmpty() }?.let(mapper::readTree)
            }
            if (response.statusCode() !in REDIRECT_STATUS_CODES) {
                val retryable = response.statusCode() in RETRYABLE_STATUS_CODES || response.statusCode() == 429
                throw errorFromResponse(response.statusCode(), response.body(), retryable)
            }
            if (redirect == MAX_PRESIGNED_REDIRECTS) {
                throw CnbApiException("CNB repository-events redirect limit exceeded")
            }
            target =
                response.headers().firstValue("Location").orElseThrow {
                    CnbApiException("CNB object-storage redirect did not include Location")
                }
            targetUri = targetUri.resolve(target)
        }
        throw CnbApiException("CNB repository-events redirect limit exceeded")
    }

    private fun execute(
        method: String,
        uri: URI,
        body: JsonNode?,
        includeAuthorization: Boolean = true,
    ): HttpResponse<ByteArray> {
        require(uri.isAbsolute && !uri.host.isNullOrBlank()) { "CNB request URI must have a host" }
        if (!server.allowPrivateNetwork) CnbEndpointPolicy.validatePublicAddress(uri.host)
        val requestBody = body?.let { mapper.writeValueAsBytes(it) } ?: ByteArray(0)
        val publisher =
            if (requestBody.isEmpty() && method in setOf("GET", "DELETE")) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofByteArray(requestBody)
            }
        val requestBuilder =
            HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofSeconds(server.requestTimeoutSeconds.toLong()))
                .header("Accept", CNB_MEDIA_TYPE)
                .header("User-Agent", userAgent())
                .method(method, publisher)
        if (body != null) requestBuilder.header("Content-Type", "application/json; charset=utf-8")
        if (includeAuthorization) {
            token?.let { requestBuilder.header("Authorization", "Bearer ${it.plainText}") }
        }

        val acquired = permits.tryAcquire(PERMIT_WAIT_SECONDS, TimeUnit.SECONDS)
        if (!acquired) {
            throw CnbApiException(
                "Timed out waiting for CNB request capacity",
                retryable = true,
            )
        }
        return try {
            val response =
                httpClient.sendAsync(requestBuilder.build()) {
                    BoundedByteArraySubscriber(MAX_RESPONSE_BYTES)
                }
            try {
                response.get(server.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                response.cancel(true)
                throw HttpTimeoutException("CNB response exceeded the configured request deadline")
            } catch (failure: InterruptedException) {
                response.cancel(true)
                throw failure
            } catch (failure: ExecutionException) {
                val cause = failure.cause ?: failure
                findApiFailure(cause)?.let { throw it }
                if (cause is IOException) throw cause
                throw IOException("CNB HTTP transport failed", cause)
            }
        } finally {
            permits.release()
        }
    }

    private fun buildUri(
        path: String,
        query: Map<String, String>,
    ): URI {
        val queryString = StringBuilder()
        for ((key, value) in query) {
            if (value.isEmpty()) continue
            if (queryString.isNotEmpty()) queryString.append('&')
            queryString.append(encodeQuery(key)).append('=').append(encodeQuery(value))
        }
        return URI.create(
            baseUri.toASCIIString().removeSuffix("/") +
                path +
                if (queryString.isEmpty()) "" else "?$queryString",
        )
    }

    private fun validatePresignedUri(uri: URI) {
        require(uri.isAbsolute && !uri.host.isNullOrBlank()) { "Invalid CNB repository-events redirect" }
        require(uri.scheme.equals("https", ignoreCase = true)) { "CNB repository-events redirect must use HTTPS" }
        require(uri.rawUserInfo == null && uri.rawFragment == null) { "Unsafe CNB repository-events redirect" }
        if (!server.allowPrivateNetwork) CnbEndpointPolicy.validatePublicAddress(uri.host)
    }

    private fun errorFromResponse(
        status: Int,
        bytes: ByteArray,
        retryable: Boolean,
    ): CnbApiException {
        var errorCode: String? = null
        var message = "CNB API returned HTTP $status"
        if (bytes.isNotEmpty()) {
            try {
                val node = mapper.readTree(bytes)
                errorCode = node.path("errcode").takeUnless { it.isMissingNode || it.isNull }?.asText()
                val apiMessage = node.optionalText("errmsg")
                if (!apiMessage.isNullOrBlank()) {
                    message += ": ${sanitize(apiMessage).take(MAX_ERROR_MESSAGE_LENGTH)}"
                }
            } catch (_: Exception) {
                // Never copy an arbitrary HTML error page into Jenkins logs.
            }
        }
        return CnbApiException(message, status, errorCode, retryable)
    }

    private fun parseRepository(
        node: JsonNode,
        defaultBranch: String = "",
    ): CnbRepository {
        val path =
            node.optionalText("path")
                ?: throw CnbApiException("CNB repository response did not include a path")
        val encodedPath =
            try {
                encodeRepository(path)
            } catch (failure: IllegalArgumentException) {
                throw CnbApiException("CNB repository response contained an invalid path", cause = failure)
            }
        val webUrl =
            node.optionalText("web_url")
                ?: server.normalizedWebUri().toString().removeSuffix("/") + "/$encodedPath"
        val status = node.path("status")
        return CnbRepository(
            path = path,
            name = node.text("name", path.substringAfterLast('/')),
            webUrl = webUrl,
            cloneUrl = webUrl,
            defaultBranch = defaultBranch,
            archived =
                status.asText().equals("archived", true) ||
                    (status.isIntegralNumber && status.asInt() == 1) ||
                    node.path("archived").asBoolean(false),
            visibility = node.text("visibility_level", node.text("visibility", "Private")),
            id = node.text("id", path),
        )
    }

    private fun parseBranch(node: JsonNode): CnbBranch =
        CnbBranch(
            name = node.text("name"),
            sha = node.path("commit").text("sha", node.text("sha")),
            protected = node.path("protected").asBoolean(false),
            locked = node.path("locked").asBoolean(false),
        )

    private fun parseTag(node: JsonNode): CnbTag {
        val commit = node.path("commit")
        val timestampText =
            commit.path("commit").path("committer").optionalText("date")
                ?: commit.path("commit").path("author").optionalText("date")
        val timestamp = timestampText?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0) } ?: 0
        return CnbTag(
            name = node.text("name"),
            sha = commit.text("sha", node.text("target")),
            timestamp = timestamp,
        )
    }

    private fun parsePullRequest(
        node: JsonNode,
        fallbackRepo: String,
    ): CnbPullRequest {
        val source = node.path("head")
        val target = node.path("base")
        val sourceRepo = source.path("repo").text("path", fallbackRepo)
        val targetRepo = target.path("repo").text("path", fallbackRepo)
        val updated = node.optionalText("updated_at") ?: node.optionalText("last_acted_at")
        return CnbPullRequest(
            number = node.text("number"),
            title = node.text("title"),
            state = node.text("state"),
            sourceRepo = sourceRepo,
            sourceBranch = stripHeadRef(source.text("ref")),
            sourceSha = source.text("sha"),
            targetRepo = targetRepo,
            targetBranch = stripHeadRef(target.text("ref")),
            targetSha = target.text("sha"),
            mergeSha = node.optionalText("merge_sha") ?: node.optionalText("merge_commit_sha"),
            author = node.path("author").text("username", node.path("user").text("username")),
            fromFork = sourceRepo != targetRepo,
            draft = node.path("is_wip").asBoolean(false),
            updatedAt = updated?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0) } ?: 0,
        )
    }

    private fun parsePullComment(node: JsonNode): CnbPullComment =
        CnbPullComment(
            id = node.text("id"),
            body = node.text("body"),
            author = node.path("user").text("username", node.path("author").text("username")),
            createdAt = node.text("created_at"),
            updatedAt = node.text("updated_at"),
        )

    private fun validateComment(body: String) {
        require(body.isNotBlank()) { "Comment body must not be empty" }
        require(body.length <= MAX_COMMENT_LENGTH) { "Comment body is too long" }
    }

    private fun sleepBeforeRetry(
        attempt: Int,
        retryAfter: String?,
    ) {
        val fromHeader = retryAfter?.toLongOrNull()?.coerceIn(0, MAX_RETRY_AFTER_SECONDS)
        val exponential = min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS * (1L shl (attempt - 1)))
        val millis = fromHeader?.times(1000) ?: exponential + Random.nextLong(0, 250)
        Thread.sleep(millis)
    }

    companion object {
        private const val CNB_MEDIA_TYPE = "application/vnd.cnb.api+json"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 100
        private const val MAX_PAGINATED_ITEMS = 10_000
        private const val MAX_PAGINATED_BYTES = 16L * 1024 * 1024
        private const val MAX_CONCURRENT_REQUESTS = 8
        private const val PERMIT_WAIT_SECONDS = 5L
        private const val MAX_ATTEMPTS = 4
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_ERROR_MESSAGE_LENGTH = 500
        private const val MAX_COMMENT_LENGTH = 60_000
        private const val MAX_ANNOTATIONS = 100
        private const val MAX_ANNOTATION_KEY_LENGTH = 256
        private const val MAX_ANNOTATION_VALUE_LENGTH = 16_384
        private const val BASE_BACKOFF_MILLIS = 500L
        private const val MAX_BACKOFF_MILLIS = 10_000L
        private const val MAX_RETRY_AFTER_SECONDS = 60L
        private val RETRYABLE_STATUS_CODES = setOf(500, 502, 503, 504)
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_PRESIGNED_REDIRECTS = 3
        private const val CIRCUIT_FAILURE_THRESHOLD = 5
        private const val CIRCUIT_OPEN_MILLIS = 30_000L
        private const val MAX_CAUSE_DEPTH = 16
        private val PERMITS = ConcurrentHashMap<String, Semaphore>()
        private val CIRCUITS = ConcurrentHashMap<String, CircuitBreaker>()

        private class BoundedByteArraySubscriber(
            maxBytes: Int,
        ) : HttpResponse.BodySubscriber<ByteArray> {
            private val body = CompletableFuture<ByteArray>()
            private val output = ByteArrayOutputStream(min(maxBytes, 8192))
            private val limit = maxBytes.toLong()
            private var subscription: Flow.Subscription? = null
            private var received = 0L

            override fun getBody(): CompletionStage<ByteArray> = body

            override fun onSubscribe(value: Flow.Subscription) {
                if (subscription != null) {
                    value.cancel()
                    return
                }
                subscription = value
                value.request(1)
            }

            override fun onNext(buffers: List<ByteBuffer>) {
                if (body.isDone) return
                for (buffer in buffers) {
                    val length = buffer.remaining()
                    if (received + length > limit) {
                        subscription?.cancel()
                        output.reset()
                        body.completeExceptionally(CnbApiException("CNB response exceeded $limit bytes"))
                        return
                    }
                    val bytes = ByteArray(length)
                    buffer.get(bytes)
                    output.write(bytes)
                    received += length
                }
                subscription?.request(1)
            }

            override fun onError(failure: Throwable) {
                output.reset()
                body.completeExceptionally(failure)
            }

            override fun onComplete() {
                body.complete(output.toByteArray())
            }
        }

        private fun findApiFailure(failure: Throwable): CnbApiException? {
            var current: Throwable? = failure
            repeat(MAX_CAUSE_DEPTH) {
                if (current is CnbApiException) return current
                current = current?.cause ?: return null
            }
            return null
        }

        private class CircuitBreaker {
            private val failures = AtomicInteger()
            private val openUntil = AtomicLong()

            fun beforeRequest() {
                val until = openUntil.get()
                if (until > System.currentTimeMillis()) {
                    throw CnbApiException("CNB server circuit is temporarily open", retryable = true)
                }
                if (until != 0L) openUntil.compareAndSet(until, 0L)
            }

            fun success() {
                failures.set(0)
                openUntil.set(0)
            }

            fun failure() {
                if (failures.incrementAndGet() >= CIRCUIT_FAILURE_THRESHOLD) {
                    openUntil.set(System.currentTimeMillis() + CIRCUIT_OPEN_MILLIS)
                    failures.set(0)
                }
            }
        }

        private fun encodeRepository(value: String): String {
            val trimmed = value.trim().trim('/')
            require(trimmed.isNotEmpty()) { "Repository path must not be empty" }
            val segments = trimmed.split('/')
            require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid repository path" }
            return segments.joinToString("/") { encodeSegment(it) }
        }

        private fun encodeSegment(value: String): String {
            require(value.isNotEmpty() && value != "." && value != "..") { "Invalid path segment" }
            require(value.none { it.code < 0x20 || it.code == 0x7f }) { "Path segment contains control characters" }
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        }

        private fun encodeQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        private fun stripHeadRef(value: String): String = value.removePrefix("refs/heads/")

        private fun safeMessage(error: Throwable): String = sanitize(error.message.orEmpty()).take(300)

        private fun userAgent(): String {
            val jenkins = Jenkins.getInstanceOrNull()
            val pluginVersion = jenkins?.pluginManager?.getPlugin("cnb")?.version ?: "development"
            val jenkinsVersion = Jenkins.getVersion()?.toString() ?: "unknown"
            return "jenkins-cnb/$pluginVersion (Jenkins/$jenkinsVersion)"
        }

        private fun sanitize(value: String): String =
            value
                .replace(Regex("(?i)(bearer|token|authorization)[=: ]+[^ ,;\\r\\n]+"), "$1=[redacted]")
                .replace(Regex("[\\p{Cntrl}]"), "?")

        private fun JsonNode.text(
            field: String,
            fallback: String = "",
        ): String {
            val child = path(field)
            return if (child.isMissingNode || child.isNull) fallback else child.asText(fallback)
        }

        private fun JsonNode.optionalText(field: String): String? {
            val value = path(field)
            return if (value.isMissingNode || value.isNull || value.asText().isBlank()) null else value.asText()
        }

        private fun JsonNode.long(field: String): Long = path(field).asLong(0)

        private fun JsonNode.arrayElements(): List<JsonNode> =
            when {
                isArray -> toList()
                isObject && path("items").isArray -> path("items").toList()
                else -> emptyList()
            }
    }
}
