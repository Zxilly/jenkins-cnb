package dev.zxilly.jenkins.cnb.api

import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbAuthenticatedUser
import dev.zxilly.jenkins.cnb.api.model.CnbBadge
import dev.zxilly.jenkins.cnb.api.model.CnbBadgeGroup
import dev.zxilly.jenkins.cnb.api.model.CnbBadgeSummary
import dev.zxilly.jenkins.cnb.api.model.CnbBadgeUploadRequest
import dev.zxilly.jenkins.cnb.api.model.CnbBadgeUploadResult
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbBuildEventName
import dev.zxilly.jenkins.cnb.api.model.CnbBuildHistory
import dev.zxilly.jenkins.cnb.api.model.CnbBuildHistoryQuery
import dev.zxilly.jenkins.cnb.api.model.CnbBuildInfo
import dev.zxilly.jenkins.cnb.api.model.CnbBuildPipeline
import dev.zxilly.jenkins.cnb.api.model.CnbBuildRequest
import dev.zxilly.jenkins.cnb.api.model.CnbBuildResult
import dev.zxilly.jenkins.cnb.api.model.CnbBuildRunnerLogDownload
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStage
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStageStatus
import dev.zxilly.jenkins.cnb.api.model.CnbBuildState
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStatus
import dev.zxilly.jenkins.cnb.api.model.CnbCommit
import dev.zxilly.jenkins.cnb.api.model.CnbCommitAnnotation
import dev.zxilly.jenkins.cnb.api.model.CnbCommitAnnotations
import dev.zxilly.jenkins.cnb.api.model.CnbCommitComparison
import dev.zxilly.jenkins.cnb.api.model.CnbCommitDiffFile
import dev.zxilly.jenkins.cnb.api.model.CnbCommitDiffStatus
import dev.zxilly.jenkins.cnb.api.model.CnbCommitPerson
import dev.zxilly.jenkins.cnb.api.model.CnbCommitQuery
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatus
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatusState
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatuses
import dev.zxilly.jenkins.cnb.api.model.CnbContent
import dev.zxilly.jenkins.cnb.api.model.CnbContentEncoding
import dev.zxilly.jenkins.cnb.api.model.CnbContentEntry
import dev.zxilly.jenkins.cnb.api.model.CnbContentType
import dev.zxilly.jenkins.cnb.api.model.CnbCreatePullRequestRequest
import dev.zxilly.jenkins.cnb.api.model.CnbCreateReleaseRequest
import dev.zxilly.jenkins.cnb.api.model.CnbGitFileMode
import dev.zxilly.jenkins.cnb.api.model.CnbLabel
import dev.zxilly.jenkins.cnb.api.model.CnbMemberAccess
import dev.zxilly.jenkins.cnb.api.model.CnbMemberAccessLevel
import dev.zxilly.jenkins.cnb.api.model.CnbMergePullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbMergePullResult
import dev.zxilly.jenkins.cnb.api.model.CnbPipelineLabel
import dev.zxilly.jenkins.cnb.api.model.CnbPipelineStatus
import dev.zxilly.jenkins.cnb.api.model.CnbPullBlockedReason
import dev.zxilly.jenkins.cnb.api.model.CnbPullComment
import dev.zxilly.jenkins.cnb.api.model.CnbPullFile
import dev.zxilly.jenkins.cnb.api.model.CnbPullFileStatus
import dev.zxilly.jenkins.cnb.api.model.CnbPullMergeableState
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestListState
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestState
import dev.zxilly.jenkins.cnb.api.model.CnbPullReview
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewCommentInfo
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewDiffLine
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewDiffLineType
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewReplyRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewSide
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewState
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewSubjectType
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewer
import dev.zxilly.jenkins.cnb.api.model.CnbRawContent
import dev.zxilly.jenkins.cnb.api.model.CnbReactionSummary
import dev.zxilly.jenkins.cnb.api.model.CnbRelease
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAsset
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAssetDownload
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAssetHead
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAssetUploadRequest
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseUser
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEvent
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEventType
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryRefType
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryStatus
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryVisibility
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import dev.zxilly.jenkins.cnb.api.model.CnbTagAnnotation
import dev.zxilly.jenkins.cnb.api.model.CnbTagAnnotationMetadata
import dev.zxilly.jenkins.cnb.api.model.CnbUpdatePullRequestRequest
import dev.zxilly.jenkins.cnb.api.model.CnbUpdateReleaseRequest
import dev.zxilly.jenkins.cnb.api.model.CnbUser
import dev.zxilly.jenkins.cnb.api.wire.CnbAnnotationMutationWire
import dev.zxilly.jenkins.cnb.api.wire.CnbAnnotationWire
import dev.zxilly.jenkins.cnb.api.wire.CnbAnnotationsRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbAuthenticatedUserWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBadgeGroupWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBadgeListWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBadgeSummaryWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBadgeUploadRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBadgeUploadResultWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBadgeWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBranchWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBuildHistoryWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBuildInfoWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBuildNpcRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBuildPipelineWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBuildRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBuildResultWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBuildStageWire
import dev.zxilly.jenkins.cnb.api.wire.CnbBuildStatusWire
import dev.zxilly.jenkins.cnb.api.wire.CnbCommentRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbCommitAnnotationsBatchEntryWire
import dev.zxilly.jenkins.cnb.api.wire.CnbCommitAnnotationsBatchRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbCommitComparisonWire
import dev.zxilly.jenkins.cnb.api.wire.CnbCommitDiffFileWire
import dev.zxilly.jenkins.cnb.api.wire.CnbCommitStatusWire
import dev.zxilly.jenkins.cnb.api.wire.CnbCommitStatusesWire
import dev.zxilly.jenkins.cnb.api.wire.CnbCommitWire
import dev.zxilly.jenkins.cnb.api.wire.CnbContentWire
import dev.zxilly.jenkins.cnb.api.wire.CnbCreatePullRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbCreateReleaseRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbCreatedPullRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbErrorWire
import dev.zxilly.jenkins.cnb.api.wire.CnbGetBadgeRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbHeadWire
import dev.zxilly.jenkins.cnb.api.wire.CnbItemsEnvelopeWire
import dev.zxilly.jenkins.cnb.api.wire.CnbJsonCodec
import dev.zxilly.jenkins.cnb.api.wire.CnbLabelWire
import dev.zxilly.jenkins.cnb.api.wire.CnbMemberAccessWire
import dev.zxilly.jenkins.cnb.api.wire.CnbMergePullRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbMergePullResultWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPipelineStatusWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullAssigneesRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullCommentWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullFileWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullLabelsRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullReviewCommentRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullReviewCommentWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullReviewDiffLineWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullReviewReplyRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullReviewRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullReviewWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullReviewerWire
import dev.zxilly.jenkins.cnb.api.wire.CnbPullReviewersRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbReactionWire
import dev.zxilly.jenkins.cnb.api.wire.CnbReleaseAssetUploadRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbReleaseAssetUploadTicketWire
import dev.zxilly.jenkins.cnb.api.wire.CnbReleaseAssetWire
import dev.zxilly.jenkins.cnb.api.wire.CnbReleaseUserWire
import dev.zxilly.jenkins.cnb.api.wire.CnbReleaseWire
import dev.zxilly.jenkins.cnb.api.wire.CnbRepositoryEventWire
import dev.zxilly.jenkins.cnb.api.wire.CnbRepositoryEventsEnvelopeWire
import dev.zxilly.jenkins.cnb.api.wire.CnbRepositoryWire
import dev.zxilly.jenkins.cnb.api.wire.CnbTagAnnotationWire
import dev.zxilly.jenkins.cnb.api.wire.CnbTagWire
import dev.zxilly.jenkins.cnb.api.wire.CnbUpdatePullRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbUpdateReleaseRequestWire
import dev.zxilly.jenkins.cnb.api.wire.CnbUserInfoWire
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.security.CnbEndpointPolicy
import dev.zxilly.jenkins.cnb.security.CnbGitObjectId
import dev.zxilly.jenkins.cnb.security.CnbRepositoryPath
import dev.zxilly.jenkins.cnb.security.CnbResourcePath
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import hudson.util.Secret
import io.ktor.util.reflect.typeInfo
import jenkins.model.Jenkins
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import org.eclipse.jgit.lib.Repository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Collections
import java.util.HexFormat
import java.util.IdentityHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

internal class CnbEncodedJsonBody(
    val bytes: ByteArray,
) {
    fun destroy() {
        bytes.fill(0)
    }
}

private sealed interface ApiResponseBody<out T> {
    data class Success<T>(
        val value: T,
    ) : ApiResponseBody<T>

    data class Error(
        val bytes: ByteArray,
    ) : ApiResponseBody<Nothing>
}

internal class HttpCnbClient(
    private val server: CnbServer,
    private val token: Secret?,
) : CnbClient {
    private val baseUri: URI = server.normalizedApiUri()
    private val permits = PERMITS.computeIfAbsent(server.id) { Semaphore(MAX_CONCURRENT_REQUESTS, true) }
    private val circuit = CIRCUITS.computeIfAbsent(server.id) { CircuitBreaker() }
    private val httpClient = CnbHttpTransportFactory.create(server)

    override val capabilities: CnbApiCapabilities = CnbApiCapabilities()

    override fun close() {
        httpClient.close()
    }

    override fun testConnection(): CnbAuthenticatedUser {
        val wire =
            requestJson("GET", "/user", CnbAuthenticatedUserWire.serializer())
                ?: throw CnbApiException("CNB returned an empty user response")
        return CnbAuthenticatedUser(
            username = boundedRequiredWireText(wire.username, "authenticated username", MAX_USERNAME_LENGTH),
            nickname = boundedWireText(wire.nickname, "authenticated user nickname", MAX_DISPLAY_NAME_LENGTH),
            email = safeWireToken(wire.email, "authenticated user email", MAX_EMAIL_LENGTH),
        )
    }

    override fun getRepository(path: String): CnbRepository {
        val safeRepo = encodeRepository(path)
        val wire =
            requestJson("GET", "/$safeRepo", CnbRepositoryWire.serializer())
                ?: throw CnbApiException("CNB repository response was empty")
        val head = requestJson("GET", "/$safeRepo/-/git/head", CnbHeadWire.serializer(), acceptNotFound = true)
        return parseRepository(wire, optionalBranchName(head?.name, "default branch"))
    }

    override fun listRepositories(
        namespace: String,
        includeDescendants: Boolean,
    ): List<CnbRepository> {
        val slug = encodeNamespace(namespace)
        val query = mapOf("descendant" to if (includeDescendants) "all" else "sub")
        return try {
            paginate(
                "/$slug/-/repos",
                query,
                CnbRepositoryWire.serializer(),
                stableIdentity = ::repositoryIdentity,
            ) { parseRepository(it) }
        } catch (failure: CnbApiException) {
            if (failure.statusCode != 404 || namespace.contains('/')) throw failure
            paginate(
                "/users/${encodeSegment(namespace)}/repos",
                serializer = CnbRepositoryWire.serializer(),
                stableIdentity = ::repositoryIdentity,
            ) { parseRepository(it) }
        }
    }

    override fun listUserRepositories(): List<CnbRepository> =
        paginate(
            "/user/repos",
            mapOf("role" to "Guest"),
            CnbRepositoryWire.serializer(),
            stableIdentity = ::repositoryIdentity,
        ) { parseRepository(it) }

    override fun listRepositoryLabels(repo: String): List<CnbLabel> =
        paginate(
            "/${encodeRepository(repo)}/-/labels",
            serializer = CnbLabelWire.serializer(),
            stableIdentity = { "label:${it.id}" },
            transform = ::parseLabel,
        )

    override fun listBadges(repo: String): List<CnbBadgeSummary> {
        val path = "/${encodeRepository(repo)}/-/badge/list"
        val bytes =
            requestValue(
                method = "GET",
                path = path,
                query = emptyMap(),
                body = null,
                idempotent = true,
                acceptNotFound = false,
            ) { response -> response.readBoundedBytes(MAX_BADGE_RESPONSE_BYTES) }
                ?: return emptyList()
        if (bytes.isEmpty()) return emptyList()
        val wires =
            CnbJsonCodec.decodeArrayOrEnvelope(
                elementSerializer = CnbBadgeSummaryWire.serializer(),
                envelopeDeserializer = CnbBadgeListWire.serializer(),
                extract = CnbBadgeListWire::badges,
                bytes = bytes,
                context = "badge list response",
            )
        if (wires.size > MAX_BADGES) throw CnbApiException("CNB badge list exceeded $MAX_BADGES entries")
        return wires.map { parseBadgeSummary(repo, it) }
    }

    override fun getBadge(
        repo: String,
        badge: String,
        revision: String,
        branch: String?,
    ): CnbBadge? {
        val safeBadge = normalizeBadgeName(badge)
        val safeRevision = validateBadgeRevision(revision)
        val safeBranch = branch?.let(::validateBadgeBranch)
        val body =
            safeBranch?.let {
                encodeRequest(CnbGetBadgeRequestWire.serializer(), CnbGetBadgeRequestWire(it))
            }
        val wire =
            requestBoundedJson(
                method = "GET",
                path =
                    "/${encodeRepository(repo)}/-/badge/git/${encodeSegment(safeRevision)}/" +
                        "${encodeRelativePath(safeBadge)}.json",
                serializer = CnbBadgeWire.serializer(),
                maxResponseBytes = MAX_BADGE_JSON_BYTES,
                body = body,
                idempotent = true,
                acceptNotFound = true,
            ) ?: return null
        if (wire.links.size > MAX_BADGE_LINKS) throw CnbApiException("CNB badge response contained too many links")
        return CnbBadge(
            color = boundedRequiredWireText(wire.color, "badge color", MAX_BADGE_COLOR_LENGTH),
            label = boundedRequiredWireText(wire.label, "badge label", MAX_BADGE_TEXT_LENGTH),
            message = boundedRequiredWireText(wire.message, "badge message", MAX_BADGE_TEXT_LENGTH),
            link = validateExternalUrl(wire.link, "badge link"),
            links = wire.links.map { validateExternalUrl(it, "badge link") },
        )
    }

    override fun uploadBadge(
        repo: String,
        request: CnbBadgeUploadRequest,
    ): CnbBadgeUploadResult {
        validateBadgeName(request.key)
        val sha = CnbGitObjectId.canonical(request.sha)
        require(request.message != null || request.value != null) { "CNB badge upload requires message or value" }
        request.message?.let {
            require(it.isNotBlank() && it.length <= MAX_BADGE_TEXT_LENGTH) { "Invalid CNB badge message" }
            require(it.none { character -> character.code < 0x20 || character.code == 0x7f }) { "Invalid CNB badge message" }
        }
        val link = validateBadgeLink(request.link)
        val body =
            CnbBadgeUploadRequestWire(
                key = request.key,
                latest = request.latest,
                link = link,
                message = request.message,
                sha = sha,
                value = request.value,
            )
        val wire =
            requestBoundedJson(
                method = "POST",
                path = "/${encodeRepository(repo)}/-/badge/upload",
                serializer = CnbBadgeUploadResultWire.serializer(),
                maxResponseBytes = MAX_BADGE_JSON_BYTES,
                body = encodeRequest(CnbBadgeUploadRequestWire.serializer(), body),
                idempotent = false,
            ) ?: throw CnbApiException("CNB badge upload response was empty")
        if (request.latest && wire.latestUrl.isEmpty()) {
            throw CnbApiException("CNB badge upload response omitted its latest URL")
        }
        if (!request.latest && wire.latestUrl.isNotEmpty()) {
            throw CnbApiException("CNB badge upload response unexpectedly included a latest URL")
        }
        return CnbBadgeUploadResult(
            url = normalizeBadgeUrl(wire.url, repo, "badge upload URL"),
            latestUrl =
                wire.latestUrl
                    .takeIf(String::isNotEmpty)
                    ?.let {
                        normalizeBadgeUrl(it, repo, "latest badge upload URL")
                    }.orEmpty(),
        )
    }

    override fun listBranches(repo: String): List<CnbBranch> {
        val safeRepo = encodeRepository(repo)
        return paginate(
            "/$safeRepo/-/git/branches",
            serializer = CnbBranchWire.serializer(),
            stableIdentity = { "branch:${stripHeadRef(it.name)}" },
            transform = ::parseBranch,
        )
    }

    override fun getBranch(
        repo: String,
        name: String,
    ): CnbBranch {
        val wire =
            requestJson("GET", "/${encodeRepository(repo)}/-/git/branches/${encodeSegment(name)}", CnbBranchWire.serializer())
                ?: throw CnbApiException("CNB branch response was empty")
        return parseBranch(wire)
    }

    override fun listTags(repo: String): List<CnbTag> {
        val safeRepo = encodeRepository(repo)
        return paginate(
            "/$safeRepo/-/git/tags",
            serializer = CnbTagWire.serializer(),
            stableIdentity = { "tag:${it.name}" },
            transform = ::parseTag,
        )
    }

    override fun getTag(
        repo: String,
        name: String,
    ): CnbTag {
        val wire =
            requestJson("GET", "/${encodeRepository(repo)}/-/git/tags/${encodeSegment(name)}", CnbTagWire.serializer())
                ?: throw CnbApiException("CNB tag response was empty")
        return parseTag(wire)
    }

    override fun getCommit(
        repo: String,
        ref: String,
    ): CnbCommit {
        val wire =
            requestJson("GET", "/${encodeRepository(repo)}/-/git/commits/${encodeSegment(ref)}", CnbCommitWire.serializer())
                ?: throw CnbApiException("CNB commit response was empty")
        return parseCommit(wire)
    }

    override fun listCommits(
        repo: String,
        query: CnbCommitQuery,
    ): List<CnbCommit> {
        val parameters = LinkedHashMap<String, String>()
        query.sha?.let { parameters["sha"] = it }
        query.author?.let { parameters["author"] = it }
        query.committer?.let { parameters["committer"] = it }
        query.since?.let { parameters["since"] = it }
        query.until?.let { parameters["until"] = it }
        return paginate(
            "/${encodeRepository(repo)}/-/git/commits",
            parameters,
            CnbCommitWire.serializer(),
            stableIdentity = { "commit:${it.sha}" },
            transform = ::parseCommit,
        )
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    override fun compareCommits(
        repo: String,
        base: String,
        head: String,
    ): CnbCommitComparison {
        val wire =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/git/compare/${encodeSegment("$base...$head")}",
                CnbCommitComparisonWire.serializer(),
            ) ?: throw CnbApiException("CNB commit comparison response was empty")
        return CnbCommitComparison(
            baseCommit = wire.baseCommit?.let(::parseCommit),
            headCommit = wire.headCommit?.let(::parseCommit),
            mergeBaseCommit = wire.mergeBaseCommit?.let(::parseCommit),
            commits = wire.commits.map(::parseCommit),
            files = wire.files.map(::parseCommitDiffFile),
            totalCommits =
                wire.totalCommits.also { total ->
                    if (total < wire.commits.size) throw CnbApiException("CNB commit comparison contained an invalid total")
                },
        )
    }

    override fun listPullRequests(
        repo: String,
        state: CnbPullRequestListState,
    ): List<CnbPullRequest> {
        val safeRepo = encodeRepository(repo)
        return paginate(
            "/$safeRepo/-/pulls",
            mapOf("state" to state.wireValue, "order_by" to "-updated_at"),
            CnbPullRequestWire.serializer(),
            stableIdentity = { "pull:${it.number}" },
        ) { parsePullRequest(it, repo) }
    }

    override fun getPullRequest(
        repo: String,
        number: String,
    ): CnbPullRequest {
        val wire =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}",
                CnbPullRequestWire.serializer(),
            )
                ?: throw CnbApiException("CNB pull request response was empty")
        return parsePullRequest(wire, repo)
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    override fun listPullRequestsByNumbers(
        repo: String,
        numbers: List<String>,
    ): List<CnbPullRequest> {
        validatePullNumbers(numbers)
        val path =
            buildRepeatedQueryPath(
                "/${encodeRepository(repo)}/-/pull-in-batch",
                numbers.map { "n" to it },
            )
        return requestJsonArrayOrItems(
            "GET",
            path,
            CnbPullRequestWire.serializer(),
        ).orEmpty().map { parsePullRequest(it, repo) }
    }

    override fun createPullRequest(
        repo: String,
        request: CnbCreatePullRequestRequest,
    ): CnbPullRequest {
        validateCreatePullRequest(request)
        val acknowledgement =
            requestJson(
                "POST",
                "/${encodeRepository(repo)}/-/pulls",
                CnbCreatedPullRequestWire.serializer(),
                body =
                    encodeRequest(
                        CnbCreatePullRequestWire.serializer(),
                        CnbCreatePullRequestWire(
                            base = request.targetBranch,
                            body = request.body,
                            head = request.sourceBranch,
                            headRepo = request.sourceRepository,
                            title = request.title,
                        ),
                    ),
                idempotent = false,
            ) ?: throw CnbApiException("CNB create pull request response was empty")
        return getPullRequest(repo, requireWirePullRequestNumber(acknowledgement.number))
    }

    override fun updatePullRequest(
        repo: String,
        number: String,
        request: CnbUpdatePullRequestRequest,
    ): CnbPullRequest {
        validatePullRequestNumber(number)
        validateUpdatePullRequest(request)
        val wire =
            requestJson(
                "PATCH",
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}",
                CnbPullRequestWire.serializer(),
                body =
                    encodeRequest(
                        CnbUpdatePullRequestWire.serializer(),
                        CnbUpdatePullRequestWire(request.body, request.state?.wireValue, request.title),
                    ),
                idempotent = false,
            ) ?: throw CnbApiException("CNB update pull request response was empty")
        return parsePullRequest(wire, repo)
    }

    override fun listPullAssignees(
        repo: String,
        number: String,
    ): List<CnbUser> {
        validatePullRequestNumber(number)
        return paginate(
            "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/assignees",
            serializer = CnbUserInfoWire.serializer(),
            stableIdentity = { "user:${it.username}" },
            transform = { parseUser(it, "pull request assignee") },
        )
    }

    override fun addPullAssignees(
        repo: String,
        number: String,
        assignees: List<String>,
    ): CnbPullRequest = mutatePullAssignees("POST", repo, number, assignees)

    override fun removePullAssignees(
        repo: String,
        number: String,
        assignees: List<String>,
    ): CnbPullRequest = mutatePullAssignees("DELETE", repo, number, assignees)

    override fun addPullReviewers(
        repo: String,
        number: String,
        reviewers: List<String>,
    ): CnbPullRequest = mutatePullReviewers("POST", repo, number, reviewers)

    override fun removePullReviewers(
        repo: String,
        number: String,
        reviewers: List<String>,
    ): CnbPullRequest = mutatePullReviewers("DELETE", repo, number, reviewers)

    override fun listPullLabels(
        repo: String,
        number: String,
    ): List<CnbLabel> =
        paginate(
            "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/labels",
            serializer = CnbLabelWire.serializer(),
            stableIdentity = { "label:${it.id}" },
            transform = ::parseLabel,
        )

    override fun addPullLabel(
        repo: String,
        number: String,
        label: String,
    ): CnbLabel {
        validatePullLabels(listOf(label))
        val wire =
            requestJson(
                "POST",
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/labels",
                CnbLabelWire.serializer(),
                body = encodeRequest(CnbPullLabelsRequestWire.serializer(), CnbPullLabelsRequestWire(listOf(label))),
                idempotent = false,
            ) ?: throw CnbApiException("CNB add pull request label response was empty")
        return parseLabel(wire)
    }

    override fun replacePullLabels(
        repo: String,
        number: String,
        labels: List<String>,
    ): CnbLabel {
        validatePullLabels(labels)
        val wire =
            requestJson(
                "PUT",
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/labels",
                CnbLabelWire.serializer(),
                body = encodeRequest(CnbPullLabelsRequestWire.serializer(), CnbPullLabelsRequestWire(labels)),
                idempotent = true,
            ) ?: throw CnbApiException("CNB replace pull request labels response was empty")
        return parseLabel(wire)
    }

    override fun removePullLabel(
        repo: String,
        number: String,
        label: String,
    ): CnbLabel {
        validatePullLabels(listOf(label))
        val wire =
            requestJson(
                "DELETE",
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/labels/${encodeSegment(label)}",
                CnbLabelWire.serializer(),
                idempotent = true,
            ) ?: throw CnbApiException("CNB remove pull request label response was empty")
        return parseLabel(wire)
    }

    override fun clearPullLabels(
        repo: String,
        number: String,
    ) {
        validatePullRequestNumber(number)
        requestNoContent(
            "DELETE",
            "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/labels",
            idempotent = true,
        )
    }

    override fun listPullCommits(
        repo: String,
        number: String,
    ): List<CnbCommit> =
        paginate(
            "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/commits",
            serializer = CnbCommitWire.serializer(),
            stableIdentity = { "commit:${it.sha}" },
            transform = ::parseCommit,
        )

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    override fun listPullFiles(
        repo: String,
        number: String,
    ): List<CnbPullFile> {
        val wires =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/files",
                ListSerializer(CnbPullFileWire.serializer()),
            )
                ?: return emptyList()
        return wires.map {
            if (it.additions < 0 || it.deletions < 0) {
                throw CnbApiException("CNB pull request file response contained negative line counts")
            }
            CnbPullFile(
                filename = boundedRequiredWireText(it.filename, "pull request file path", MAX_REVIEW_PATH_LENGTH),
                status =
                    requireWireEnumValue(
                        it.status,
                        "pull request file status",
                        CnbPullFileStatus.entries,
                    ) { state -> state.wireValue },
                sha = optionalWireObjectId(it.sha, "pull request file object id").orEmpty(),
                additions = it.additions,
                deletions = it.deletions,
                patch = boundedWireText(it.patch, "pull request file patch", MAX_DIFF_PATCH_LENGTH, allowLineBreaks = true),
                blobUrl = validateExternalUrl(it.blobUrl, "pull request blob URL"),
                rawUrl = validateExternalUrl(it.rawUrl, "pull request raw URL"),
                contentsUrl = validateExternalUrl(it.contentsUrl, "pull request contents URL"),
            )
        }
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    override fun listPullCommitStatuses(
        repo: String,
        number: String,
    ): CnbCommitStatuses {
        val wire =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/commit-statuses",
                CnbCommitStatusesWire.serializer(),
            )
                ?: throw CnbApiException("CNB pull request commit statuses response was empty")
        return CnbCommitStatuses(
            sha = requireWireObjectId(wire.sha, "pull request commit status sha"),
            state = requireWireEnumValue(wire.state, "aggregate commit status", CnbCommitStatusState.entries) { it.wireValue },
            statuses = wire.statuses.map(::parseCommitStatus),
        )
    }

    override fun listPullReviews(
        repo: String,
        number: String,
    ): List<CnbPullReview> =
        paginate(
            "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/reviews",
            serializer = CnbPullReviewWire.serializer(),
            stableIdentity = { "review:${it.id}" },
        ) {
            CnbPullReview(
                id = requireWireResourceId(it.id, "pull review id"),
                body = boundedWireText(it.body, "pull review body", MAX_COMMENT_LENGTH, allowLineBreaks = true),
                state = requireWireEnumValue(it.state, "pull review state", CnbPullReviewState.entries) { state -> state.wireValue },
                author =
                    it.author
                        ?.username
                        ?.let { username ->
                            boundedRequiredWireText(username, "pull review author", MAX_USERNAME_LENGTH)
                        }.orEmpty(),
                createdAt = requireWireTimestamp(it.createdAt, "pull review created time"),
                updatedAt = requireWireTimestamp(it.updatedAt, "pull review updated time"),
            )
        }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    override fun createPullReview(
        repo: String,
        number: String,
        request: CnbPullReviewRequest,
    ) {
        validatePullReview(request)
        val wire =
            CnbPullReviewRequestWire(
                event = request.event.wireValue,
                body = request.body,
                comments =
                    request.comments.map { comment ->
                        CnbPullReviewCommentRequestWire(
                            body = comment.body,
                            path = comment.path,
                            subjectType = comment.subjectType.wireValue,
                            startLine = comment.startLine,
                            startSide = comment.startSide?.wireValue,
                            endLine = comment.endLine,
                            endSide = comment.endSide?.wireValue,
                        )
                    },
            )
        requestNoContent(
            "POST",
            "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/reviews",
            body = encodeRequest(CnbPullReviewRequestWire.serializer(), wire),
            idempotent = false,
        )
    }

    override fun listPullReviewComments(
        repo: String,
        number: String,
        reviewId: String,
    ): List<CnbPullReviewCommentInfo> {
        validatePullRequestNumber(number)
        validateResourceId(reviewId, "pull review id")
        return paginate(
            "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/reviews/${encodeSegment(reviewId)}/comments",
            serializer = CnbPullReviewCommentWire.serializer(),
            stableIdentity = { "review-comment:${it.id}" },
            transform = { parsePullReviewComment(it, reviewId) },
        )
    }

    override fun replyToPullReviewComment(
        repo: String,
        number: String,
        reviewId: String,
        request: CnbPullReviewReplyRequest,
    ): CnbPullReviewCommentInfo {
        validatePullRequestNumber(number)
        validateResourceId(reviewId, "pull review id")
        validateResourceId(request.replyToCommentId, "parent pull review comment id")
        validateComment(request.body)
        val wire =
            requestJson(
                "POST",
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/reviews/${encodeSegment(reviewId)}/replies",
                CnbPullReviewCommentWire.serializer(),
                body =
                    encodeRequest(
                        CnbPullReviewReplyRequestWire.serializer(),
                        CnbPullReviewReplyRequestWire(request.body, request.replyToCommentId),
                    ),
                idempotent = false,
            ) ?: throw CnbApiException("CNB pull review reply response was empty")
        return parsePullReviewComment(wire, reviewId)
    }

    override fun mergePullRequest(
        repo: String,
        number: String,
        request: CnbMergePullRequest,
    ): CnbMergePullResult {
        require(request.commitTitle.length <= MAX_COMMIT_TITLE_LENGTH) { "Merge commit title is too long" }
        require(request.commitMessage.length <= MAX_COMMIT_MESSAGE_LENGTH) { "Merge commit message is too long" }
        val body =
            CnbMergePullRequestWire(
                mergeStyle = request.mergeStyle.wireValue,
                commitTitle = request.commitTitle.takeIf(String::isNotEmpty),
                commitMessage = request.commitMessage.takeIf(String::isNotEmpty),
            )
        val wire =
            requestJson(
                "PUT",
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/merge",
                CnbMergePullResultWire.serializer(),
                body = encodeRequest(CnbMergePullRequestWire.serializer(), body),
                idempotent = false,
            ) ?: throw CnbApiException("CNB merge pull request response was empty")
        return CnbMergePullResult(
            merged = wire.merged,
            message = boundedWireText(wire.message, "merge result message", MAX_BUILD_MESSAGE_LENGTH, allowLineBreaks = true),
            sha = optionalWireObjectId(wire.sha, "merge result sha").orEmpty(),
        )
    }

    override fun startBuild(
        repo: String,
        request: CnbBuildRequest,
    ): CnbBuildResult {
        validateBuildRequest(request)
        val body =
            CnbBuildRequestWire(
                event = request.event.wireValue,
                branch = request.branch,
                tag = request.tag,
                sha = request.sha,
                title = request.title,
                config = request.config,
                sync = request.sync.toString(),
                env = request.env,
                npc = request.npc?.let { CnbBuildNpcRequestWire(it.name.wireValue, it.workMode) },
            )
        val wire =
            requestJson(
                "POST",
                "/${encodeRepository(repo)}/-/build/start",
                CnbBuildResultWire.serializer(),
                body = encodeRequest(CnbBuildRequestWire.serializer(), body),
                idempotent = false,
            ) ?: throw CnbApiException("CNB start build response was empty")
        return parseBuildResult(wire)
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    override fun getBuildStatus(
        repo: String,
        sn: String,
    ): CnbBuildStatus {
        val wire =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/build/status/${encodeSegment(sn)}",
                CnbBuildStatusWire.serializer(),
            )
                ?: throw CnbApiException("CNB build status response was empty")
        if (wire.pipelinesStatus.size > MAX_BUILD_PIPELINES) {
            throw CnbApiException("CNB build status response exceeded the pipeline limit")
        }
        val pipelines =
            wire.pipelinesStatus
                .map { (key, pipeline) ->
                    val pipelineKey = requireWireResourceId(key, "pipeline status key")
                    pipelineKey to parsePipelineStatus(pipeline)
                }.toMap(linkedMapOf())
        return CnbBuildStatus(
            status = requireWireEnumValue(wire.status, "build status", CnbBuildState.entries) { it.wireValue },
            pipelinesStatus = pipelines,
        )
    }

    override fun getBuildStage(
        repo: String,
        sn: String,
        pipelineId: String,
        stageId: String,
    ): CnbBuildStage {
        validateResourceId(sn, "build serial number")
        validateResourceId(pipelineId, "pipeline id")
        validateResourceId(stageId, "build stage id")
        val wire =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/build/logs/stage/${encodeSegment(sn)}/${encodeSegment(pipelineId)}/${encodeSegment(stageId)}",
                CnbBuildStageWire.serializer(),
            ) ?: throw CnbApiException("CNB build stage response was empty")
        return parseBuildStage(wire)
    }

    override fun downloadBuildRunnerLog(
        repo: String,
        pipelineId: String,
        target: CnbDownloadTarget,
        maxBytes: Long,
    ): CnbBuildRunnerLogDownload {
        validateResourceId(pipelineId, "pipeline id")
        require(maxBytes in 1..MAX_BUILD_RUNNER_LOG_BYTES) { "Invalid CNB build runner log download limit" }
        val download =
            downloadPresignedResource(
                "/${encodeRepository(repo)}/-/build/runner/download/log/${encodeSegment(pipelineId)}",
                emptyMap(),
                maxBytes,
                target,
            ) ?: throw CnbApiException("CNB build runner log was not found", 404)
        return CnbBuildRunnerLogDownload(download.contentLength, download.contentType, download.etag)
    }

    override fun stopBuild(
        repo: String,
        sn: String,
    ): CnbBuildResult {
        val wire =
            requestJson(
                "POST",
                "/${encodeRepository(repo)}/-/build/stop/${encodeSegment(sn)}",
                CnbBuildResultWire.serializer(),
                idempotent = false,
            ) ?: throw CnbApiException("CNB stop build response was empty")
        return parseBuildResult(wire)
    }

    override fun listBuildHistory(
        repo: String,
        query: CnbBuildHistoryQuery,
    ): CnbBuildHistory =
        paginateBuildHistory(
            "/${encodeRepository(repo)}/-/build/logs",
            buildHistoryParameters(query),
        )

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    override fun listMemberAccessLevels(
        repo: String,
        username: String,
    ): List<CnbMemberAccess> {
        val wires =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/members/${encodeSegment(username)}/access-level",
                ListSerializer(CnbMemberAccessWire.serializer()),
            ) ?: return emptyList()
        return wires.map {
            CnbMemberAccess(
                path = requireWireResourcePath(it.path, "member access resource"),
                accessLevel =
                    requireWireEnumValue(it.accessLevel, "member access level", CnbMemberAccessLevel.entries) { level ->
                        level.wireValue
                    },
            )
        }
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
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
                "/${encodeRepository(repo)}/-/git/contents/${encodeRelativePath(normalizedPath)}"
            }
        val wire =
            requestJson(
                "GET",
                contentPath,
                CnbContentWire.serializer(),
                mapOf("ref" to ref),
                acceptNotFound = true,
            ) ?: return null
        if (wire.size < 0 || wire.entries.size > MAX_CONTENT_ENTRIES) {
            throw CnbApiException("CNB content response contained invalid size metadata")
        }
        return CnbContent(
            path = boundedWireText(wire.path, "content path", MAX_REVIEW_PATH_LENGTH),
            sha = requireWireObjectId(wire.sha, "content object id"),
            type = requireWireEnumValue(wire.type, "content type", CONTENT_TYPES) { it.wireValue },
            size = wire.size,
            content = wire.content?.let { boundedWireText(it, "content body", MAX_CONTENT_BODY_LENGTH, allowLineBreaks = true) },
            encoding =
                wire.encoding?.takeIf(String::isNotEmpty)?.let {
                    requireWireEnumValue(it, "content encoding", CnbContentEncoding.entries) { encoding -> encoding.wireValue }
                },
            entries =
                wire.entries.map {
                    if (it.size < 0) throw CnbApiException("CNB content entry contained a negative size")
                    CnbContentEntry(
                        name = boundedRequiredWireText(it.name, "content entry name", MAX_REPOSITORY_NAME_LENGTH),
                        path = boundedRequiredWireText(it.path, "content entry path", MAX_REVIEW_PATH_LENGTH),
                        sha = requireWireObjectId(it.sha, "content entry object id"),
                        type = requireWireEnumValue(it.type, "content entry type", CONTENT_ENTRY_TYPES) { type -> type.wireValue },
                        size = it.size,
                    )
                },
        )
    }

    override fun getRawContent(
        repo: String,
        ref: String,
        path: String,
        maxBytes: Int,
    ): CnbRawContent? {
        require(maxBytes in 1..MAX_RAW_RESPONSE_BYTES) {
            "CNB raw content limit must be between 1 and $MAX_RAW_RESPONSE_BYTES bytes"
        }
        require(ref.isNotBlank() && ref.length <= MAX_RAW_REFERENCE_LENGTH) { "Invalid CNB raw content ref" }
        require(ref.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid CNB raw content ref" }
        val normalizedPath = path.replace('\\', '/').trim('/')
        require(normalizedPath.isNotEmpty()) { "CNB raw content path must not be empty" }
        encodeRelativePath(normalizedPath)
        return requestRaw(
            "/${encodeRepository(repo)}/-/git/raw/${encodeSegment("$ref/$normalizedPath")}",
            mapOf("max_in_byte" to maxBytes.toString()),
            maxBytes,
        )
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    override fun listCommitStatuses(
        repo: String,
        commitish: String,
    ): List<CnbCommitStatus> {
        val wires =
            requestJsonArrayOrItems(
                "GET",
                "/${encodeRepository(repo)}/-/git/commit-statuses/${encodeSegment(commitish)}",
                CnbCommitStatusWire.serializer(),
            ) ?: return emptyList()
        return wires.map(::parseCommitStatus)
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    override fun getCommitAnnotations(
        repo: String,
        sha: String,
    ): List<CnbCommitAnnotation> {
        val wires =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/git/commit-annotations/${encodeSegment(sha)}",
                ListSerializer(CnbAnnotationWire.serializer()),
                acceptNotFound = true,
            ) ?: return emptyList()
        return wires.map {
            CnbCommitAnnotation(
                boundedRequiredWireText(it.key, "commit annotation key", MAX_ANNOTATION_KEY_LENGTH),
                boundedWireText(it.value, "commit annotation value", MAX_ANNOTATION_VALUE_LENGTH, allowLineBreaks = true),
            )
        }
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates concrete ArrayLists that SpotBugs loses in SMAP bytecode.",
    )
    override fun getCommitAnnotationsInBatch(
        repo: String,
        commitHashes: List<String>,
        keys: List<String>,
    ): List<CnbCommitAnnotations> {
        require(commitHashes.size in 1..MAX_COMMIT_ANNOTATION_BATCH_HASHES) {
            "CNB commit annotation batch must contain between 1 and $MAX_COMMIT_ANNOTATION_BATCH_HASHES hashes"
        }
        require(commitHashes.all(COMMIT_ANNOTATION_BATCH_HASH::matches)) {
            "CNB commit annotation batch hashes must contain exactly 40 hexadecimal characters"
        }
        require(keys.size <= MAX_COMMIT_ANNOTATION_BATCH_KEYS) {
            "CNB commit annotation batch must contain at most $MAX_COMMIT_ANNOTATION_BATCH_KEYS keys"
        }
        val request = CnbCommitAnnotationsBatchRequestWire(ArrayList(commitHashes), ArrayList(keys))
        val wires =
            requestBoundedJson(
                "POST",
                "/${encodeRepository(repo)}/-/git/commit-annotations-in-batch",
                ListSerializer(CnbCommitAnnotationsBatchEntryWire.serializer()),
                MAX_COMMIT_ANNOTATION_BATCH_RESPONSE_BYTES,
                body = encodeRequest(CnbCommitAnnotationsBatchRequestWire.serializer(), request),
                idempotent = true,
            ) ?: throw CnbApiException("CNB commit annotations batch response was empty")
        val remainingHashes =
            commitHashes
                .map(CnbGitObjectId::canonical)
                .groupingBy { it }
                .eachCount()
                .toMutableMap()
        return wires.map { entry ->
            val commitHash = requireWireObjectId(entry.commitHash, "commit annotation batch hash")
            val remaining = remainingHashes[commitHash] ?: 0
            if (remaining < 1) {
                throw CnbApiException("CNB commit annotations batch returned an unrequested commit")
            }
            if (remaining == 1) remainingHashes.remove(commitHash) else remainingHashes[commitHash] = remaining - 1
            CnbCommitAnnotations(
                commitHash = commitHash,
                annotations =
                    entry.annotations.map { annotation ->
                        CnbCommitAnnotation(
                            boundedRequiredWireText(
                                annotation.key,
                                "commit annotation key",
                                MAX_ANNOTATION_KEY_LENGTH,
                            ),
                            boundedWireText(
                                annotation.value,
                                "commit annotation value",
                                MAX_ANNOTATION_VALUE_LENGTH,
                                allowLineBreaks = true,
                            ),
                        )
                    },
            )
        }
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    override fun putCommitAnnotations(
        repo: String,
        sha: String,
        annotations: List<CnbCommitAnnotation>,
    ) {
        require(annotations.size <= MAX_ANNOTATIONS) { "At most $MAX_ANNOTATIONS annotations may be written at once" }
        annotations.forEach { annotation ->
            requireValidAnnotationKey(annotation.key)
            require(annotation.value.length <= MAX_ANNOTATION_VALUE_LENGTH) { "Annotation value is too long" }
        }
        val body =
            CnbAnnotationsRequestWire(
                annotations.map { CnbAnnotationMutationWire(it.key, it.value) },
            )
        requestNoContent(
            "PUT",
            "/${encodeRepository(repo)}/-/git/commit-annotations/${encodeSegment(sha)}",
            body = encodeRequest(CnbAnnotationsRequestWire.serializer(), body),
            idempotent = true,
        )
    }

    override fun deleteCommitAnnotation(
        repo: String,
        sha: String,
        key: String,
    ) {
        requireValidAnnotationKey(key)
        requestNoContent(
            "DELETE",
            "/${encodeRepository(repo)}/-/git/commit-annotations/${encodeSegment(sha)}/${encodeSegment(key)}",
            idempotent = true,
        )
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    override fun getTagAnnotations(
        repo: String,
        tag: String,
    ): List<CnbTagAnnotation> {
        val wires =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/git/tag-annotations/${encodeSegment(tag)}",
                ListSerializer(CnbTagAnnotationWire.serializer()),
                acceptNotFound = true,
            ) ?: return emptyList()
        return wires.map { item ->
            CnbTagAnnotation(
                key = boundedRequiredWireText(item.key, "tag annotation key", MAX_ANNOTATION_KEY_LENGTH),
                value = boundedWireText(item.value, "tag annotation value", MAX_ANNOTATION_VALUE_LENGTH, allowLineBreaks = true),
                meta =
                    item.meta?.let {
                        CnbTagAnnotationMetadata(
                            operator = boundedWireText(it.operator, "tag annotation operator", MAX_USERNAME_LENGTH),
                            updatedAt =
                                it.updatedAt.let { timestamp ->
                                    if (timestamp.isEmpty()) "" else requireWireTimestamp(timestamp, "tag annotation updated time")
                                },
                            platform = boundedWireText(it.platform, "tag annotation platform", MAX_DISPLAY_NAME_LENGTH),
                        )
                    } ?: CnbTagAnnotationMetadata(),
            )
        }
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    override fun putTagAnnotations(
        repo: String,
        tag: String,
        annotations: List<CnbTagAnnotation>,
    ) {
        require(annotations.size <= MAX_ANNOTATIONS) { "At most $MAX_ANNOTATIONS annotations may be written at once" }
        annotations.forEach { annotation ->
            requireValidAnnotationKey(annotation.key)
            require(annotation.value.length <= MAX_ANNOTATION_VALUE_LENGTH) { "Annotation value is too long" }
        }
        val body = CnbAnnotationsRequestWire(annotations.map { CnbAnnotationMutationWire(it.key, it.value) })
        requestNoContent(
            "PUT",
            "/${encodeRepository(repo)}/-/git/tag-annotations/${encodeSegment(tag)}",
            body = encodeRequest(CnbAnnotationsRequestWire.serializer(), body),
            idempotent = true,
        )
    }

    override fun deleteTagAnnotation(
        repo: String,
        tag: String,
        key: String,
    ) {
        requireValidAnnotationKey(key)
        requestNoContent(
            "DELETE",
            "/${encodeRepository(repo)}/-/git/tag-annotations/${encodeSegment("$tag/$key")}",
            idempotent = true,
        )
    }

    override fun listPullComments(
        repo: String,
        number: String,
    ): List<CnbPullComment> {
        val safeRepo = encodeRepository(repo)
        return paginate(
            "/$safeRepo/-/pulls/${encodePullRequestNumber(number)}/comments",
            serializer = CnbPullCommentWire.serializer(),
            stableIdentity = { "comment:${it.id}" },
            transform = ::parsePullComment,
        )
    }

    override fun getPullComment(
        repo: String,
        number: String,
        commentId: String,
    ): CnbPullComment {
        val wire =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/comments/${encodeSegment(commentId)}",
                CnbPullCommentWire.serializer(),
            ) ?: throw CnbApiException("CNB pull request comment response was empty")
        return parsePullComment(wire)
    }

    override fun createPullComment(
        repo: String,
        number: String,
        body: String,
    ): CnbPullComment {
        validateComment(body)
        val wire =
            requestJson(
                "POST",
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/comments",
                CnbPullCommentWire.serializer(),
                body = encodeRequest(CnbCommentRequestWire.serializer(), CnbCommentRequestWire(body)),
                idempotent = false,
            ) ?: throw CnbApiException("CNB create comment response was empty")
        return parsePullComment(wire)
    }

    override fun updatePullComment(
        repo: String,
        number: String,
        commentId: String,
        body: String,
    ): CnbPullComment {
        validateComment(body)
        val wire =
            requestJson(
                "PATCH",
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/comments/${encodeSegment(commentId)}",
                CnbPullCommentWire.serializer(),
                body = encodeRequest(CnbCommentRequestWire.serializer(), CnbCommentRequestWire(body)),
                idempotent = true,
            ) ?: throw CnbApiException("CNB update comment response was empty")
        return parsePullComment(wire)
    }

    override fun listReleases(repo: String): List<CnbRelease> =
        paginate(
            "/${encodeRepository(repo)}/-/releases",
            serializer = CnbReleaseWire.serializer(),
            stableIdentity = { "release:${it.id}" },
            transform = { parseRelease(repo, it) },
        )

    override fun getLatestRelease(repo: String): CnbRelease? =
        requestJson(
            "GET",
            "/${encodeRepository(repo)}/-/releases/latest",
            CnbReleaseWire.serializer(),
            acceptNotFound = true,
        )?.let { parseRelease(repo, it) }

    override fun getRelease(
        repo: String,
        releaseId: String,
    ): CnbRelease {
        validateResourceId(releaseId, "release id")
        val wire =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/releases/${encodeSegment(releaseId)}",
                CnbReleaseWire.serializer(),
            ) ?: throw CnbApiException("CNB release response was empty")
        return parseRelease(repo, wire)
    }

    override fun getReleaseByTag(
        repo: String,
        tag: String,
    ): CnbRelease {
        validateTag(tag)
        val wire =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/releases/tags/${encodeSegment(tag)}",
                CnbReleaseWire.serializer(),
            ) ?: throw CnbApiException("CNB release response was empty")
        return parseRelease(repo, wire)
    }

    override fun createRelease(
        repo: String,
        request: CnbCreateReleaseRequest,
    ): CnbRelease {
        validateCreateRelease(request)
        val body =
            CnbCreateReleaseRequestWire(
                tagName = request.tagName,
                targetCommitish = request.targetCommitish,
                name = request.name,
                body = request.body,
                draft = request.draft,
                prerelease = request.prerelease,
                makeLatest = request.makeLatest.wireValue,
            )
        val wire =
            requestJson(
                "POST",
                "/${encodeRepository(repo)}/-/releases",
                CnbReleaseWire.serializer(),
                body = encodeRequest(CnbCreateReleaseRequestWire.serializer(), body),
                idempotent = false,
            ) ?: throw CnbApiException("CNB create release response was empty")
        return parseRelease(repo, wire)
    }

    override fun updateRelease(
        repo: String,
        releaseId: String,
        request: CnbUpdateReleaseRequest,
    ) {
        validateResourceId(releaseId, "release id")
        validateUpdateRelease(request)
        requestNoContent(
            "PATCH",
            "/${encodeRepository(repo)}/-/releases/${encodeSegment(releaseId)}",
            body =
                encodeRequest(
                    CnbUpdateReleaseRequestWire.serializer(),
                    CnbUpdateReleaseRequestWire(
                        name = request.name,
                        body = request.body,
                        draft = request.draft,
                        prerelease = request.prerelease,
                        makeLatest = request.makeLatest?.wireValue,
                    ),
                ),
            idempotent = true,
        )
    }

    override fun deleteRelease(
        repo: String,
        releaseId: String,
    ) {
        validateResourceId(releaseId, "release id")
        requestNoContent(
            "DELETE",
            "/${encodeRepository(repo)}/-/releases/${encodeSegment(releaseId)}",
            idempotent = true,
        )
    }

    override fun getReleaseAsset(
        repo: String,
        releaseId: String,
        assetId: String,
    ): CnbReleaseAsset {
        validateResourceId(releaseId, "release id")
        validateResourceId(assetId, "release asset id")
        val wire =
            requestJson(
                "GET",
                "/${encodeRepository(repo)}/-/releases/${encodeSegment(releaseId)}/assets/${encodeSegment(assetId)}",
                CnbReleaseAssetWire.serializer(),
            ) ?: throw CnbApiException("CNB release asset response was empty")
        return parseReleaseAsset(wire, repo)
    }

    override fun downloadReleaseAsset(
        repo: String,
        tag: String,
        filename: String,
        target: CnbDownloadTarget,
        share: Boolean,
        maxBytes: Long,
    ): CnbReleaseAssetDownload? {
        validateTag(tag)
        validateAssetName(filename)
        require(maxBytes in 1..MAX_RELEASE_ASSET_TRANSFER_BYTES) { "Invalid CNB release asset download limit" }
        return downloadPresignedResource(
            "/${encodeRepository(repo)}/-/releases/download/${encodeSegment(tag)}/${encodeSegment(filename)}",
            mapOf("share" to share.toString()),
            maxBytes,
            target,
        )
    }

    override fun headReleaseAsset(
        repo: String,
        tag: String,
        filename: String,
    ): CnbReleaseAssetHead {
        validateTag(tag)
        validateAssetName(filename)
        return headPresignedResource(
            "/${encodeRepository(repo)}/-/releases/download/${encodeSegment(tag)}/${encodeSegment(filename)}",
        )
    }

    override fun deleteReleaseAsset(
        repo: String,
        releaseId: String,
        assetId: String,
    ) {
        validateResourceId(releaseId, "release id")
        validateResourceId(assetId, "release asset id")
        requestNoContent(
            "DELETE",
            "/${encodeRepository(repo)}/-/releases/${encodeSegment(releaseId)}/assets/${encodeSegment(assetId)}",
            idempotent = true,
        )
    }

    override fun uploadReleaseAsset(
        repo: String,
        releaseId: String,
        request: CnbReleaseAssetUploadRequest,
        source: CnbRepeatableInput,
    ) {
        validateResourceId(releaseId, "release id")
        validateAssetUpload(request)
        val safeRepo = encodeRepository(repo)
        val ticket =
            requestJson(
                "POST",
                "/$safeRepo/-/releases/${encodeSegment(releaseId)}/asset-upload-url",
                CnbReleaseAssetUploadTicketWire.serializer(),
                body =
                    encodeRequest(
                        CnbReleaseAssetUploadRequestWire.serializer(),
                        CnbReleaseAssetUploadRequestWire(
                            assetName = request.assetName,
                            overwrite = request.overwrite,
                            size = request.size,
                            ttl = request.ttlDays,
                        ),
                    ),
                idempotent = false,
            ) ?: throw CnbApiException("CNB release asset upload ticket response was empty")
        uploadSignedReleaseAsset(repo, safeRepo, releaseId, request, source, ticket)
    }

    override fun listRepositoryEvents(
        repo: String,
        hour: ZonedDateTime,
    ): List<CnbRepositoryEvent> {
        val date = hour.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-H"))
        val bytes = requestPresignedBytes("/events/${encodeRepository(repo)}/-/$date") ?: return emptyList()
        val events = decodeRepositoryEvents(bytes)
        val parsedEvents = ArrayList<CnbRepositoryEvent>()
        for (event in events) {
            if (parsedEvents.size >= MAX_REPOSITORY_EVENTS_PER_HOUR) {
                throw CnbApiException("CNB repository event item limit exceeded")
            }
            parsedEvents.add(
                CnbRepositoryEvent(
                    id = requireWireResourceId(event.id, "repository event id"),
                    type = CnbRepositoryEventType(requireWireToken(event.type, "repository event type", MAX_EVENT_TYPE_LENGTH)),
                    repositoryPath = requireWireRepositoryPath(event.repo?.path ?: repo, "repository event path"),
                    createdAt = event.createdAt.let { if (it.isEmpty()) "" else requireWireTimestamp(it, "repository event created time") },
                    payload =
                        dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEventPayload(
                            ref = optionalWireGitRef(event.payload.ref, "repository event ref"),
                            head = optionalWireObjectId(event.payload.head, "repository event head").orEmpty(),
                            refType =
                                event.payload.refType.takeIf(String::isNotEmpty)?.let {
                                    requireWireEnumValue(it, "repository event ref type", CnbRepositoryRefType.entries) { type ->
                                        type.wireValue
                                    }
                                },
                        ),
                ),
            )
        }
        return parsedEvents
    }

    private fun decodeRepositoryEvents(bytes: ByteArray): List<CnbRepositoryEventWire> =
        CnbJsonCodec.decodeArrayOrEnvelope(
            elementSerializer = CnbRepositoryEventWire.serializer(),
            envelopeDeserializer = CnbRepositoryEventsEnvelopeWire.serializer(),
            extract = CnbRepositoryEventsEnvelopeWire::events,
            bytes = bytes,
            context = "repository events response",
        )

    private fun <Wire, Domain> paginate(
        path: String,
        query: Map<String, String> = emptyMap(),
        serializer: KSerializer<Wire>,
        stableIdentity: (Wire) -> String?,
        transform: (Wire) -> Domain,
    ): List<Domain> {
        val output = ArrayList<Domain>()
        val pageFingerprints = LinkedHashSet<String>()
        val resourceFingerprints = LinkedHashSet<String>()
        var aggregateBytes = 0L
        var aggregateItems = 0L
        for (page in 1..MAX_PAGES + 1) {
            val bytes =
                requestBytes("GET", path, query + mapOf("page" to page.toString(), "page_size" to PAGE_SIZE.toString()))
                    ?: return output
            if (bytes.isEmpty()) return output
            aggregateBytes += bytes.size
            if (aggregateBytes > MAX_PAGINATED_BYTES) {
                throw CnbApiException("CNB pagination byte limit exceeded for $path")
            }
            val values =
                CnbJsonCodec.decodeArrayOrEnvelope(
                    elementSerializer = serializer,
                    envelopeDeserializer = CnbItemsEnvelopeWire.serializer(serializer),
                    extract = { envelope -> envelope.items },
                    bytes = bytes,
                    context = "pagination response for $path",
                )
            if (values.isEmpty()) return output
            if (page > MAX_PAGES) {
                throw CnbApiException("CNB pagination page limit exceeded for $path")
            }
            aggregateItems += values.size
            if (aggregateItems > MAX_PAGINATED_ITEMS) {
                throw CnbApiException("CNB pagination item limit exceeded for $path")
            }
            val fingerprint = pageFingerprint(values, serializer, stableIdentity)
            if (!pageFingerprints.add(fingerprint)) {
                throw CnbApiException("CNB returned a repeated pagination page for $path")
            }
            values.forEach { wire ->
                if (resourceFingerprints.add(resourceFingerprint(wire, serializer, stableIdentity))) {
                    output.add(transform(wire))
                }
            }
        }
        return output
    }

    private fun paginateBuildHistory(
        path: String,
        query: Map<String, String>,
    ): CnbBuildHistory {
        val output = ArrayList<CnbBuildInfo>()
        val pageFingerprints = LinkedHashSet<String>()
        val resourceFingerprints = LinkedHashSet<String>()
        val stableIdentity: (CnbBuildInfoWire) -> String = { "build:${it.sn}" }
        var aggregateBytes = 0L
        var aggregateItems = 0L
        var total = 0L
        var timestamp = 0L
        for (page in 1..MAX_PAGES + 1) {
            val bytes =
                requestBytes("GET", path, query + mapOf("page" to page.toString(), "page_size" to PAGE_SIZE.toString()))
                    ?: return CnbBuildHistory(total, timestamp, output)
            if (bytes.isEmpty()) return CnbBuildHistory(total, timestamp, output)
            aggregateBytes += bytes.size
            if (aggregateBytes > MAX_PAGINATED_BYTES) {
                throw CnbApiException("CNB pagination byte limit exceeded for $path")
            }
            val wire = CnbJsonCodec.decode(CnbBuildHistoryWire.serializer(), bytes, "build history response")
            val values = wire.data
            if (wire.total < 0 || wire.timestamp < 0) {
                throw CnbApiException("CNB build history response contained negative metadata")
            }
            total = maxOf(total, wire.total)
            timestamp = maxOf(timestamp, wire.timestamp)
            if (values.isEmpty()) return CnbBuildHistory(total, timestamp, output)
            if (page > MAX_PAGES) {
                throw CnbApiException("CNB pagination page limit exceeded for $path")
            }
            aggregateItems += values.size
            if (aggregateItems > MAX_PAGINATED_ITEMS) {
                throw CnbApiException("CNB pagination item limit exceeded for $path")
            }
            if (!pageFingerprints.add(pageFingerprint(values, CnbBuildInfoWire.serializer(), stableIdentity))) {
                throw CnbApiException("CNB returned a repeated pagination page for $path")
            }
            values.forEach { build ->
                if (resourceFingerprints.add(resourceFingerprint(build, CnbBuildInfoWire.serializer(), stableIdentity))) {
                    output.add(parseBuildInfo(build))
                }
            }
        }
        return CnbBuildHistory(total, timestamp, output)
    }

    private fun <T> pageFingerprint(
        values: List<T>,
        serializer: SerializationStrategy<T>,
        stableIdentity: (T) -> String?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            digest.update(resourceFingerprint(value, serializer, stableIdentity).toByteArray(StandardCharsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(canonicalFingerprint(value, serializer).toByteArray(StandardCharsets.US_ASCII))
            digest.update(1.toByte())
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun <T> resourceFingerprint(
        value: T,
        serializer: SerializationStrategy<T>,
        stableIdentity: (T) -> String?,
    ): String =
        stableIdentity(value)
            ?.takeIf(String::isNotBlank)
            ?.let { sha256(it.toByteArray(StandardCharsets.UTF_8)) }
            ?: canonicalFingerprint(value, serializer)

    private fun <T> canonicalFingerprint(
        value: T,
        serializer: SerializationStrategy<T>,
    ): String = sha256(CnbJsonCodec.canonicalBytes(serializer, value))

    private fun requireValidAnnotationKey(key: String) {
        require(key.length in 1..MAX_ANNOTATION_KEY_LENGTH) { "Invalid annotation key length" }
        require(ANNOTATION_KEY_PATTERN.matches(key)) {
            "Annotation key may contain only ASCII letters, digits, underscores, and hyphens"
        }
    }

    private fun sha256(value: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))

    @Suppress("UNUSED_PARAMETER") // Anchors T to the explicit wire schema; Ktor resolves it from TypeInfo.
    private inline fun <reified T> requestJson(
        method: String,
        path: String,
        serializer: DeserializationStrategy<T>,
        query: Map<String, String> = emptyMap(),
        body: CnbEncodedJsonBody? = null,
        idempotent: Boolean = method == "GET",
        acceptNotFound: Boolean = false,
    ): T? =
        requestValue(
            method = method,
            path = path,
            query = query,
            body = body,
            idempotent = idempotent,
            acceptNotFound = acceptNotFound,
        ) { response ->
            if (response.statusCode == 204 || response.statusCode == 205 || response.headers.allValues("Content-Length") == listOf("0")) {
                null
            } else {
                response.readJson<T>(typeInfo<T>())
            }
        }

    private fun <T> requestBoundedJson(
        method: String,
        path: String,
        serializer: DeserializationStrategy<T>,
        maxResponseBytes: Int,
        query: Map<String, String> = emptyMap(),
        body: CnbEncodedJsonBody? = null,
        idempotent: Boolean = method == "GET",
        acceptNotFound: Boolean = false,
    ): T? {
        val bytes =
            requestValue(
                method = method,
                path = path,
                query = query,
                body = body,
                idempotent = idempotent,
                acceptNotFound = acceptNotFound,
            ) { response ->
                if (
                    response.statusCode == 204 || response.statusCode == 205 ||
                    response.headers.allValues("Content-Length") == listOf("0")
                ) {
                    ByteArray(0)
                } else {
                    response.readBoundedBytes(maxResponseBytes)
                }
            } ?: return null
        if (bytes.isEmpty()) return null
        return CnbJsonCodec.decode(serializer, bytes, "response for $path")
    }

    private fun <T> requestJsonArrayOrItems(
        method: String,
        path: String,
        serializer: KSerializer<T>,
        query: Map<String, String> = emptyMap(),
        acceptNotFound: Boolean = false,
    ): List<T>? {
        val bytes = requestBytes(method, path, query, acceptNotFound = acceptNotFound) ?: return null
        if (bytes.isEmpty()) return emptyList()
        return CnbJsonCodec.decodeArrayOrEnvelope(
            elementSerializer = serializer,
            envelopeDeserializer = CnbItemsEnvelopeWire.serializer(serializer),
            extract = { envelope -> envelope.items },
            bytes = bytes,
            context = "response for $path",
        )
    }

    private fun requestNoContent(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: CnbEncodedJsonBody? = null,
        idempotent: Boolean = method == "GET",
        acceptNotFound: Boolean = false,
    ) {
        requestBytes(method, path, query, body, idempotent, acceptNotFound)
    }

    private fun <T> encodeRequest(
        serializer: SerializationStrategy<T>,
        value: T,
    ): CnbEncodedJsonBody = CnbEncodedJsonBody(CnbJsonCodec.encode(serializer, value))

    private fun requestBytes(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: CnbEncodedJsonBody? = null,
        idempotent: Boolean = method == "GET",
        acceptNotFound: Boolean = false,
    ): ByteArray? = requestValue(method, path, query, body, idempotent, acceptNotFound) { response -> response.readBytes() }

    private fun <T> requestValue(
        method: String,
        path: String,
        query: Map<String, String>,
        body: CnbEncodedJsonBody?,
        idempotent: Boolean,
        acceptNotFound: Boolean,
        successReader: CnbHttpResponseReader<T>,
    ): T? {
        try {
            require(path.startsWith('/') && !path.startsWith("//")) { "CNB API path must be relative to the server" }
            circuit.beforeRequest()
            val uri = buildUri(path, query)
            var lastFailure: Throwable? = null
            val attempts = if (idempotent) MAX_ATTEMPTS else 1
            for (attempt in 1..attempts) {
                try {
                    val response =
                        executeWithReader(method, uri, body) { context ->
                            if (context.statusCode in 200..299) {
                                ApiResponseBody.Success(successReader.read(context))
                            } else {
                                ApiResponseBody.Error(context.readBoundedBytes(MAX_ERROR_RESPONSE_BYTES))
                            }
                        }
                    if (response.statusCode == 404 && acceptNotFound) {
                        circuit.success()
                        return null
                    }
                    if (response.statusCode in 200..299) {
                        circuit.success()
                        return (response.body as ApiResponseBody.Success<T>).value
                    }

                    val retryable = response.statusCode == 429 || response.statusCode in RETRYABLE_STATUS_CODES
                    val errorBytes = (response.body as ApiResponseBody.Error).bytes
                    val error = errorFromResponse(response.statusCode, errorBytes, retryable)
                    if (!retryable) {
                        circuit.success()
                        throw error
                    }
                    if (attempt == attempts) {
                        circuit.failure()
                        throw error
                    }
                    lastFailure = error
                    sleepBeforeRetry(attempt, response.headers.firstValue("Retry-After"))
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
        } finally {
            body?.destroy()
        }
    }

    private fun requestRaw(
        path: String,
        query: Map<String, String>,
        maxBytes: Int,
    ): CnbRawContent? {
        circuit.beforeRequest()
        val uri = buildUri(path, query)
        var lastFailure: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val response = execute("GET", uri, null, maxResponseBytes = maxBytes)
                if (response.statusCode == 404) {
                    circuit.success()
                    return null
                }
                if (response.statusCode in 200..299) {
                    circuit.success()
                    val contentType = validateRawContentType(response.headers.firstValue("Content-Type").orEmpty())
                    return CnbRawContent(response.body, contentType)
                }

                val retryable = response.statusCode == 429 || response.statusCode in RETRYABLE_STATUS_CODES
                val error = errorFromResponse(response.statusCode, response.body, retryable)
                if (!retryable) {
                    circuit.success()
                    throw error
                }
                if (attempt == MAX_ATTEMPTS) {
                    circuit.failure()
                    throw error
                }
                lastFailure = error
                sleepBeforeRetry(attempt, response.headers.firstValue("Retry-After"))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CnbApiException("Interrupted while calling CNB", retryable = true, cause = e)
            } catch (e: CnbApiException) {
                throw e
            } catch (e: IOException) {
                lastFailure = e
                if (attempt == MAX_ATTEMPTS) {
                    circuit.failure()
                    throw CnbApiException("CNB raw content request failed: ${safeMessage(e)}", retryable = true, cause = e)
                }
                sleepBeforeRetry(attempt, null)
            }
        }
        throw CnbApiException("CNB raw content request failed", retryable = true, cause = lastFailure)
    }

    private fun validateRawContentType(value: String): String {
        val contentType = value.trim()
        val mediaType = contentType.substringBefore(';').trim()
        if (
            contentType.isEmpty() ||
            contentType.length > MAX_CONTENT_TYPE_LENGTH ||
            contentType.any { it.code < 0x20 || it.code == 0x7f } ||
            !RAW_MEDIA_TYPE.matches(mediaType)
        ) {
            throw CnbApiException("CNB raw content response had an invalid Content-Type")
        }
        return contentType
    }

    /**
     * The repository-events API returns a short-lived object-storage URL. Redirect handling is
     * deliberately separate so the CNB bearer token can never cross the API origin boundary.
     */
    private fun requestPresignedBytes(path: String): ByteArray? {
        circuit.beforeRequest()
        val initialUri = buildUri(path, emptyMap())
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val result = requestPresignedBytesOnce(initialUri)
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

    private fun requestPresignedBytesOnce(initialUri: URI): ByteArray? {
        val first = execute("GET", initialUri, null, includeAuthorization = true)
        if (first.statusCode in 200..299) {
            return first.body.takeIf { it.isNotEmpty() }
        }
        if (first.statusCode !in REDIRECT_STATUS_CODES) {
            val retryable = first.statusCode in RETRYABLE_STATUS_CODES || first.statusCode == 429
            throw errorFromResponse(first.statusCode, first.body, retryable)
        }

        var target =
            first.headers.firstValue("Location")
                ?: throw CnbApiException("CNB repository-events redirect did not include Location")
        var targetUri = initialUri.resolve(target)
        for (redirect in 1..MAX_PRESIGNED_REDIRECTS) {
            validatePresignedUri(targetUri)
            val response = execute("GET", targetUri, null, includeAuthorization = false)
            if (response.statusCode in 200..299) {
                return response.body.takeIf { it.isNotEmpty() }
            }
            if (response.statusCode !in REDIRECT_STATUS_CODES) {
                val retryable = response.statusCode in RETRYABLE_STATUS_CODES || response.statusCode == 429
                throw errorFromResponse(response.statusCode, response.body, retryable)
            }
            if (redirect == MAX_PRESIGNED_REDIRECTS) {
                throw CnbApiException("CNB repository-events redirect limit exceeded")
            }
            target =
                response.headers.firstValue("Location")
                    ?: throw CnbApiException("CNB object-storage redirect did not include Location")
            targetUri = targetUri.resolve(target)
        }
        throw CnbApiException("CNB repository-events redirect limit exceeded")
    }

    private fun downloadPresignedResource(
        path: String,
        query: Map<String, String>,
        maxBytes: Long,
        target: CnbDownloadTarget,
    ): CnbReleaseAssetDownload? {
        circuit.beforeRequest()
        val initialUri = buildUri(path, query)
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val result = downloadPresignedResourceOnce(initialUri, maxBytes, target)
                circuit.success()
                return result
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CnbApiException("Interrupted while downloading a CNB release asset", retryable = true, cause = failure)
            } catch (failure: CnbApiException) {
                if (!failure.retryable || attempt == MAX_ATTEMPTS) {
                    if (failure.retryable) circuit.failure() else circuit.success()
                    throw failure
                }
                sleepBeforeRetry(attempt, null)
            } catch (_: IOException) {
                if (attempt == MAX_ATTEMPTS) {
                    circuit.failure()
                    throw CnbApiException("CNB release asset download failed", retryable = true)
                }
                sleepBeforeRetry(attempt, null)
            }
        }
        circuit.failure()
        throw CnbApiException("CNB release asset download failed", retryable = true)
    }

    private fun downloadPresignedResourceOnce(
        initialUri: URI,
        maxBytes: Long,
        target: CnbDownloadTarget,
    ): CnbReleaseAssetDownload? {
        var current = initialUri
        var includeAuthorization = true
        for (redirect in 0..MAX_PRESIGNED_REDIRECTS) {
            val response = executeDownload(current, includeAuthorization, maxBytes, target)
            when {
                response.statusCode == 404 -> {
                    return null
                }

                response.statusCode in 200..299 -> {
                    val streamed =
                        response.body as? TransferBody.Streamed
                            ?: throw CnbApiException("CNB release asset response was not streamed")
                    return CnbReleaseAssetDownload(
                        contentLength = streamed.length,
                        contentType = optionalResponseMediaType(response.headers),
                        etag = safeResponseHeader(response.headers, "ETag", MAX_ETAG_LENGTH),
                    )
                }

                response.statusCode !in REDIRECT_STATUS_CODES -> {
                    val bytes = (response.body as? TransferBody.Buffered)?.bytes ?: ByteArray(0)
                    val retryable = response.statusCode == 429 || response.statusCode in RETRYABLE_STATUS_CODES
                    throw errorFromResponse(response.statusCode, bytes, retryable)
                }

                redirect == MAX_PRESIGNED_REDIRECTS -> {
                    throw CnbApiException("CNB release asset redirect limit exceeded")
                }

                else -> {
                    current = resolveSignedRedirect(current, response.headers, "release asset")
                    validateSignedTransferUri(current)
                    includeAuthorization = false
                }
            }
        }
        throw CnbApiException("CNB release asset redirect limit exceeded")
    }

    private fun executeDownload(
        uri: URI,
        includeAuthorization: Boolean,
        maxBytes: Long,
        target: CnbDownloadTarget,
    ): CnbHttpResponse<TransferBody> {
        require(uri.isAbsolute && !uri.host.isNullOrBlank()) { "CNB release asset URI must have a host" }
        if (!server.allowPrivateNetwork) CnbEndpointPolicy.validatePublicAddress(uri.host)
        val headers = ArrayList<Pair<String, String>>()
        headers += "User-Agent" to userAgent()
        if (includeAuthorization) {
            headers += "Accept" to CNB_MEDIA_TYPE
            token?.let { headers += "Authorization" to "Bearer ${it.plainText}" }
        }
        val request =
            CnbHttpRequest(
                method = "GET",
                uri = uri,
                headers = CnbHttpHeaders.of(*headers.toTypedArray()),
                negotiateCnbJson = includeAuthorization,
            )
        return sendRequest(request) { response ->
            if (response.statusCode in 200..299) {
                val declaredLength =
                    response.headers.firstValue("Content-Length")?.let { value ->
                        value.toLongOrNull()
                            ?: throw CnbApiException(
                                "CNB release asset response contained an invalid Content-Length header",
                            )
                    }
                val length = response.streamTo(maxBytes, declaredLength, target::openStream)
                TransferBody.Streamed(length)
            } else {
                TransferBody.Buffered(response.readBoundedBytes(MAX_ERROR_RESPONSE_BYTES))
            }
        }
    }

    private fun headPresignedResource(path: String): CnbReleaseAssetHead {
        circuit.beforeRequest()
        val initialUri = buildUri(path, emptyMap())
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val result = headPresignedResourceOnce(initialUri)
                circuit.success()
                return result
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CnbApiException("Interrupted while checking a CNB release asset", retryable = true, cause = failure)
            } catch (failure: CnbApiException) {
                if (!failure.retryable || attempt == MAX_ATTEMPTS) {
                    if (failure.retryable) circuit.failure() else circuit.success()
                    throw failure
                }
                sleepBeforeRetry(attempt, null)
            } catch (_: IOException) {
                if (attempt == MAX_ATTEMPTS) {
                    circuit.failure()
                    throw CnbApiException("CNB release asset check failed", retryable = true)
                }
                sleepBeforeRetry(attempt, null)
            }
        }
        circuit.failure()
        throw CnbApiException("CNB release asset check failed", retryable = true)
    }

    private fun headPresignedResourceOnce(initialUri: URI): CnbReleaseAssetHead {
        var current = initialUri
        var includeAuthorization = true
        for (redirect in 0..MAX_PRESIGNED_REDIRECTS) {
            val response = execute("HEAD", current, null, includeAuthorization = includeAuthorization)
            when {
                response.statusCode == 404 -> {
                    return CnbReleaseAssetHead(exists = false)
                }

                response.statusCode in 200..299 -> {
                    return releaseAssetHead(response.headers)
                }

                response.statusCode !in REDIRECT_STATUS_CODES -> {
                    val retryable = response.statusCode == 429 || response.statusCode in RETRYABLE_STATUS_CODES
                    throw errorFromResponse(response.statusCode, response.body, retryable)
                }

                redirect == MAX_PRESIGNED_REDIRECTS -> {
                    throw CnbApiException("CNB release asset redirect limit exceeded")
                }

                else -> {
                    current = resolveSignedRedirect(current, response.headers, "release asset")
                    validateSignedTransferUri(current)
                    includeAuthorization = false
                }
            }
        }
        throw CnbApiException("CNB release asset redirect limit exceeded")
    }

    private fun releaseAssetHead(headers: CnbHttpHeaders): CnbReleaseAssetHead {
        val length = optionalContentLength(headers, MAX_RELEASE_ASSET_METADATA_BYTES)
        return CnbReleaseAssetHead(
            exists = true,
            contentLength = length,
            contentType = optionalResponseMediaType(headers),
            etag = safeResponseHeader(headers, "ETag", MAX_ETAG_LENGTH),
            lastModified = safeResponseHeader(headers, "Last-Modified", MAX_TIMESTAMP_LENGTH),
        )
    }

    private fun uploadSignedReleaseAsset(
        repo: String,
        safeRepo: String,
        releaseId: String,
        request: CnbReleaseAssetUploadRequest,
        source: CnbRepeatableInput,
        ticket: CnbReleaseAssetUploadTicketWire,
    ) {
        if (ticket.expiresInSeconds !in 1..MAX_UPLOAD_TICKET_LIFETIME_SECONDS) {
            throw CnbApiException("CNB release asset upload ticket had an invalid lifetime")
        }
        val uploadUri = parseSignedUri(ticket.uploadUrl, "upload")
        validateSignedTransferUri(uploadUri)
        val verificationUri =
            validateReleaseVerificationUri(
                repo,
                safeRepo,
                releaseId,
                request.assetName,
                request.ttlDays,
                ticket.verifyUrl,
            )
        putSignedReleaseAsset(uploadUri, request.size, source)

        val response = execute("POST", verificationUri, null, includeAuthorization = true)
        if (response.statusCode !in 200..299) {
            val retryable = response.statusCode == 429 || response.statusCode in RETRYABLE_STATUS_CODES
            throw errorFromResponse(response.statusCode, response.body, retryable)
        }
    }

    private fun putSignedReleaseAsset(
        initialUri: URI,
        size: Long,
        source: CnbRepeatableInput,
    ) {
        var current = initialUri
        for (redirect in 0..MAX_PRESIGNED_REDIRECTS) {
            validateSignedTransferUri(current)
            val request =
                CnbHttpRequest(
                    method = "PUT",
                    uri = current,
                    body = CnbHttpRequestBody.RepeatableStream(size, source::openStream),
                    negotiateCnbJson = false,
                )
            val response =
                sendRequest(request) { context ->
                    if (context.statusCode in SIGNED_UPLOAD_REDIRECT_STATUS_CODES) {
                        context.discard()
                        ByteArray(0)
                    } else {
                        context.readBoundedBytes(MAX_UPLOAD_RESPONSE_BYTES)
                    }
                }
            when {
                response.statusCode in 200..299 -> {
                    return
                }

                response.statusCode !in SIGNED_UPLOAD_REDIRECT_STATUS_CODES -> {
                    throw errorFromResponse(response.statusCode, response.body, retryable = false)
                }

                redirect == MAX_PRESIGNED_REDIRECTS -> {
                    throw CnbApiException("CNB release asset upload redirect limit exceeded")
                }

                else -> {
                    current = resolveSignedRedirect(current, response.headers, "release asset upload")
                }
            }
        }
        throw CnbApiException("CNB release asset upload redirect limit exceeded")
    }

    private fun validateReleaseVerificationUri(
        repo: String,
        safeRepo: String,
        releaseId: String,
        assetName: String,
        ttlDays: Int,
        value: String,
    ): URI {
        val uri = parseSignedUri(value, "verification")
        if (!sameOrigin(uri, baseUri) || uri.rawUserInfo != null || uri.rawFragment != null) {
            throw CnbApiException("CNB release asset verification URL was outside the configured API origin")
        }
        val confirmationUri =
            when (uri.rawQuery) {
                null -> URI.create("${uri.toASCIIString()}?ttl=$ttlDays")
                "ttl=$ttlDays" -> uri
                else -> throw CnbApiException("CNB release asset verification URL had invalid parameters")
            }
        val expectedPrefix =
            "${baseUri.rawPath.orEmpty().trimEnd('/')}/$safeRepo/-/releases/${encodeSegment(releaseId)}/asset-upload-confirmation/"
        if (!uri.rawPath.startsWith(expectedPrefix) || uri.rawPath.length <= expectedPrefix.length) {
            throw CnbApiException("CNB release asset verification URL had an invalid path")
        }
        val rawSuffix = uri.rawPath.removePrefix(expectedPrefix)
        val rawSlash = rawSuffix.indexOf('/')
        if (rawSlash !in 1 until rawSuffix.lastIndex || rawSuffix.length > MAX_VERIFICATION_SUFFIX_LENGTH) {
            throw CnbApiException("CNB release asset verification URL had an invalid path")
        }
        val rawUploadToken = rawSuffix.substring(0, rawSlash)
        val rawAssetPath = rawSuffix.substring(rawSlash + 1)
        if (!UPLOAD_TOKEN.matches(rawUploadToken) || rawAssetPath.contains('/')) {
            throw CnbApiException("CNB release asset verification URL had an invalid path")
        }
        val assetPath = decodeVerificationPathSegment(rawAssetPath)
        val expectedAssetPrefix = "/$repo/-/releases/download/"
        val expectedAssetSuffix = "/$assetName"
        if (
            assetPath.length > MAX_VERIFICATION_SUFFIX_LENGTH || !assetPath.startsWith(expectedAssetPrefix) ||
            !assetPath.endsWith(expectedAssetSuffix)
        ) {
            throw CnbApiException("CNB release asset verification URL had an invalid asset path")
        }
        val tag = assetPath.removePrefix(expectedAssetPrefix).removeSuffix(expectedAssetSuffix)
        try {
            validateTag(tag)
        } catch (_: IllegalArgumentException) {
            throw CnbApiException("CNB release asset verification URL had an invalid asset path")
        }
        return confirmationUri
    }

    private fun decodeVerificationPathSegment(value: String): String {
        val bytes = ByteArrayOutputStream(value.length)
        var offset = 0
        while (offset < value.length) {
            when (val current = value[offset]) {
                '%' -> {
                    if (offset + 2 >= value.length) {
                        throw CnbApiException("CNB release asset verification URL had an invalid asset path")
                    }
                    val high = Character.digit(value[offset + 1], 16)
                    val low = Character.digit(value[offset + 2], 16)
                    if (high < 0 || low < 0) {
                        throw CnbApiException("CNB release asset verification URL had an invalid asset path")
                    }
                    bytes.write((high shl 4) or low)
                    offset += 3
                }

                '/' -> {
                    throw CnbApiException("CNB release asset verification URL had an invalid asset path")
                }

                else -> {
                    val width =
                        when {
                            current.isHighSurrogate() && offset + 1 < value.length && value[offset + 1].isLowSurrogate() -> {
                                2
                            }

                            current.isSurrogate() -> {
                                throw CnbApiException("CNB release asset verification URL had an invalid asset path")
                            }

                            else -> {
                                1
                            }
                        }
                    bytes.write(value.substring(offset, offset + width).toByteArray(StandardCharsets.UTF_8))
                    offset += width
                }
            }
        }
        val decoded =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw CnbApiException("CNB release asset verification URL had an invalid asset path")
            }
        if (decoded.any { it.code < 0x20 || it.code == 0x7f }) {
            throw CnbApiException("CNB release asset verification URL had an invalid asset path")
        }
        return decoded
    }

    private fun parseSignedUri(
        value: String,
        purpose: String,
    ): URI {
        if (value.length !in 1..MAX_EXTERNAL_URL_LENGTH || value.any { it.code < 0x20 || it.code == 0x7f }) {
            throw CnbApiException("CNB release asset $purpose URL was invalid")
        }
        return try {
            URI(value)
        } catch (_: URISyntaxException) {
            throw CnbApiException("CNB release asset $purpose URL was invalid")
        } catch (_: IllegalArgumentException) {
            throw CnbApiException("CNB release asset $purpose URL was invalid")
        }
    }

    private fun validateSignedTransferUri(uri: URI) {
        val secure = uri.scheme.equals("https", true)
        val explicitlyInsecure = server.allowInsecureHttp && server.allowPrivateNetwork && uri.scheme.equals("http", true)
        if (
            !uri.isAbsolute || uri.host.isNullOrBlank() || (!secure && !explicitlyInsecure) ||
            uri.rawUserInfo != null || uri.rawFragment != null || uri.toASCIIString().length > MAX_EXTERNAL_URL_LENGTH
        ) {
            throw CnbApiException("CNB signed transfer URL was invalid")
        }
        if (!server.allowPrivateNetwork) CnbEndpointPolicy.validatePublicAddress(uri.host)
    }

    private fun resolveSignedRedirect(
        current: URI,
        headers: CnbHttpHeaders,
        purpose: String,
    ): URI {
        val location = safeResponseHeader(headers, "Location", MAX_EXTERNAL_URL_LENGTH)
        if (location.isEmpty()) throw CnbApiException("CNB $purpose redirect did not include Location")
        return try {
            current.resolve(location)
        } catch (_: IllegalArgumentException) {
            throw CnbApiException("CNB $purpose redirect contained an invalid Location")
        }
    }

    private fun sameOrigin(
        left: URI,
        right: URI,
    ): Boolean =
        left.scheme.equals(right.scheme, true) &&
            left.host.equals(right.host, true) &&
            effectivePort(left) == effectivePort(right)

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", true) -> 443
            uri.scheme.equals("http", true) -> 80
            else -> -1
        }

    private fun safeResponseHeader(
        headers: CnbHttpHeaders,
        name: String,
        maxLength: Int,
    ): String {
        val values = headers.allValues(name)
        if (values.isEmpty()) return ""
        if (values.size != 1) throw CnbApiException("CNB response contained duplicate $name headers")
        val value = values.single()
        if (value.length > maxLength || value.any { it.code < 0x20 || it.code == 0x7f }) {
            throw CnbApiException("CNB response contained an invalid $name header")
        }
        return value
    }

    private fun optionalContentLength(
        headers: CnbHttpHeaders,
        maxBytes: Long,
    ): Long? {
        val value = safeResponseHeader(headers, "Content-Length", 32)
        if (value.isEmpty()) return null
        val parsed = value.toLongOrNull()
        if (parsed == null || parsed !in 0..maxBytes) {
            throw CnbApiException("CNB response contained an invalid Content-Length header")
        }
        return parsed
    }

    private fun optionalResponseMediaType(headers: CnbHttpHeaders): String {
        val value = safeResponseHeader(headers, "Content-Type", MAX_CONTENT_TYPE_LENGTH)
        return value.takeIf(String::isNotEmpty)?.let(::validateReleaseMediaType).orEmpty()
    }

    private sealed interface TransferBody {
        data class Buffered(
            val bytes: ByteArray,
        ) : TransferBody

        data class Streamed(
            val length: Long,
        ) : TransferBody
    }

    private fun execute(
        method: String,
        uri: URI,
        body: CnbEncodedJsonBody?,
        includeAuthorization: Boolean = true,
        maxResponseBytes: Int? = null,
    ): CnbHttpResponse<ByteArray> =
        executeWithReader(method, uri, body, includeAuthorization) { response ->
            if (maxResponseBytes == null) {
                response.readBytes()
            } else {
                response.readBoundedBytes(maxResponseBytes)
            }
        }

    private fun <T> executeWithReader(
        method: String,
        uri: URI,
        body: CnbEncodedJsonBody?,
        includeAuthorization: Boolean = true,
        reader: CnbHttpResponseReader<T>,
    ): CnbHttpResponse<T> {
        require(uri.isAbsolute && !uri.host.isNullOrBlank()) { "CNB request URI must have a host" }
        if (!server.allowPrivateNetwork) CnbEndpointPolicy.validatePublicAddress(uri.host)
        val requestBody = body?.bytes ?: ByteArray(0)
        val transportBody =
            if (requestBody.isEmpty() && method in setOf("GET", "DELETE")) {
                CnbHttpRequestBody.Empty
            } else {
                CnbHttpRequestBody.Bytes(requestBody)
            }
        val headers = ArrayList<Pair<String, String>>()
        headers += "User-Agent" to userAgent()
        if (body != null) headers += "Content-Type" to "application/json; charset=utf-8"
        if (includeAuthorization) {
            headers += "Accept" to CNB_MEDIA_TYPE
            token?.let { headers += "Authorization" to "Bearer ${it.plainText}" }
        }

        val request =
            CnbHttpRequest(
                method = method,
                uri = uri,
                headers = CnbHttpHeaders.of(*headers.toTypedArray()),
                body = transportBody,
                negotiateCnbJson = includeAuthorization,
            )
        return sendRequest(request, reader)
    }

    private fun <T> sendRequest(
        request: CnbHttpRequest,
        reader: CnbHttpResponseReader<T>,
    ): CnbHttpResponse<T> {
        val acquired = permits.tryAcquire(PERMIT_WAIT_SECONDS, TimeUnit.SECONDS)
        if (!acquired) {
            throw CnbApiException(
                "Timed out waiting for CNB request capacity",
                retryable = true,
            )
        }
        return try {
            try {
                httpClient.execute(request, reader)
            } catch (failure: InterruptedException) {
                throw failure
            } catch (failure: Exception) {
                findApiFailure(failure)?.let { throw it }
                // Transport implementations may embed the full request URI in exception messages.
                // Signed URIs carry credentials in their query string, so no untrusted cause is
                // allowed to cross this diagnostic boundary.
                throw IOException("CNB HTTP transport failed")
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

    private fun buildRepeatedQueryPath(
        path: String,
        query: List<Pair<String, String>>,
    ): String {
        require(path.startsWith('/') && '?' !in path && '#' !in path) { "Invalid CNB API path" }
        require(query.isNotEmpty()) { "CNB repeated query must not be empty" }
        return path +
            query.joinToString(prefix = "?", separator = "&") { (key, value) ->
                require(key.isNotEmpty() && value.isNotEmpty()) { "CNB repeated query values must not be empty" }
                "${encodeQuery(key)}=${encodeQuery(value)}"
            }
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
        if (bytes.isNotEmpty()) {
            try {
                val error = CnbJsonCodec.decode(CnbErrorWire.serializer(), bytes, "error response")
                errorCode = error.errcode?.takeIf(SAFE_ERROR_CODE::matches)
            } catch (_: CnbApiException) {
                // Never copy an arbitrary HTML error page into Jenkins logs.
            }
        }
        return CnbApiException("CNB API returned HTTP $status", status, errorCode, retryable)
    }

    private fun repositoryIdentity(wire: CnbRepositoryWire): String =
        wire.id?.takeIf(String::isNotBlank)?.let { "repository-id:$it" } ?: "repository-path:${wire.path}"

    private fun parseRepository(
        wire: CnbRepositoryWire,
        defaultBranch: String = "",
    ): CnbRepository {
        val path = requireWireRepositoryPath(wire.path, "repository path")
        val encodedPath =
            try {
                encodeRepository(path)
            } catch (failure: IllegalArgumentException) {
                throw CnbApiException("CNB repository response contained an invalid path", cause = failure)
            }
        val webUrl = server.normalizedWebUri().toString().removeSuffix("/") + "/$encodedPath"
        return CnbRepository(
            path = path,
            name =
                wire.name
                    ?.takeIf(String::isNotBlank)
                    ?.let { boundedRequiredWireText(it, "repository name", MAX_REPOSITORY_NAME_LENGTH) }
                    ?: path.substringAfterLast('/'),
            webUrl = webUrl,
            cloneUrl = webUrl,
            defaultBranch = defaultBranch,
            status = parseRepositoryStatus(wire.status, wire.archived),
            visibility = parseRepositoryVisibility(wire.visibilityLevel ?: wire.visibility),
            id = wire.id?.let { requireWireId(it, "repository id") } ?: path,
        )
    }

    private fun parseRepositoryStatus(
        value: String?,
        legacyArchived: Boolean,
    ): CnbRepositoryStatus {
        val parsed =
            when (value?.takeIf(String::isNotEmpty)) {
                null -> if (legacyArchived) CnbRepositoryStatus.ARCHIVED else CnbRepositoryStatus.UNKNOWN
                "0", "active" -> CnbRepositoryStatus.OK
                "1", "archived" -> CnbRepositoryStatus.ARCHIVED
                "2", "forking" -> CnbRepositoryStatus.FORKING
                else -> throw CnbApiException("CNB repository response contained an unsupported status")
            }
        if (legacyArchived && parsed != CnbRepositoryStatus.ARCHIVED) {
            throw CnbApiException("CNB repository response contained conflicting status fields")
        }
        return parsed
    }

    private fun parseRepositoryVisibility(value: String?): CnbRepositoryVisibility =
        when (value?.takeIf(String::isNotEmpty)) {
            null -> CnbRepositoryVisibility.UNKNOWN
            CnbRepositoryVisibility.PRIVATE.wireValue -> CnbRepositoryVisibility.PRIVATE
            CnbRepositoryVisibility.PUBLIC.wireValue -> CnbRepositoryVisibility.PUBLIC
            CnbRepositoryVisibility.SECRET.wireValue -> CnbRepositoryVisibility.SECRET
            else -> throw CnbApiException("CNB repository response contained an unsupported visibility")
        }

    private fun parseBranch(wire: CnbBranchWire): CnbBranch =
        CnbBranch(
            name = requireWireBranchName(wire.name, "branch name"),
            sha = requireWireObjectId(wire.commit?.sha ?: wire.sha.orEmpty(), "branch commit sha"),
            protected = wire.protected,
            locked = wire.locked,
        )

    private fun parseTag(wire: CnbTagWire): CnbTag {
        val timestampText =
            wire.commit
                ?.commit
                ?.committer
                ?.date
                ?.takeIf(String::isNotBlank)
                ?: wire.commit
                    ?.commit
                    ?.author
                    ?.date
                    ?.takeIf(String::isNotBlank)
        val timestamp =
            timestampText?.let {
                requireWireTimestamp(it, "tag commit time")
                Instant.parse(it).toEpochMilli()
            } ?: 0
        return CnbTag(
            name = requireWireTagName(wire.name, "tag name"),
            sha = requireWireObjectId(wire.commit?.sha ?: wire.target.orEmpty(), "tag commit sha"),
            timestamp = timestamp,
        )
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    private fun parseCommit(wire: CnbCommitWire): CnbCommit {
        val details = wire.commit
        return CnbCommit(
            sha = requireWireObjectId(wire.sha, "commit sha"),
            message = boundedWireText(details?.message.orEmpty(), "commit message", MAX_COMMIT_MESSAGE_LENGTH, allowLineBreaks = true),
            author = parseCommitPerson(wire.author, details?.author),
            committer = parseCommitPerson(wire.committer, details?.committer),
            parentShas = wire.parents.map { requireWireObjectId(it.sha, "parent commit sha") },
        )
    }

    private fun parseCommitPerson(
        user: dev.zxilly.jenkins.cnb.api.wire.CnbUserInfoWire?,
        signature: dev.zxilly.jenkins.cnb.api.wire.CnbSignatureWire?,
    ): CnbCommitPerson =
        CnbCommitPerson(
            username = boundedWireText(user?.username.orEmpty(), "commit username", MAX_USERNAME_LENGTH),
            nickname = boundedWireText(user?.nickname.orEmpty(), "commit nickname", MAX_DISPLAY_NAME_LENGTH),
            name =
                boundedWireText(
                    signature?.name?.takeIf(String::isNotBlank) ?: user?.nickname?.takeIf(String::isNotBlank) ?: user?.username.orEmpty(),
                    "commit signature name",
                    MAX_DISPLAY_NAME_LENGTH,
                ),
            email = safeWireToken(signature?.email?.takeIf(String::isNotBlank) ?: user?.email.orEmpty(), "commit email", MAX_EMAIL_LENGTH),
            date = signature?.date.orEmpty().let { if (it.isEmpty()) "" else requireWireTimestamp(it, "commit signature time") },
        )

    private fun parseCommitDiffFile(wire: CnbCommitDiffFileWire): CnbCommitDiffFile {
        val path = wire.path?.takeIf(String::isNotBlank) ?: wire.name?.takeIf(String::isNotBlank)
        if (path == null) throw CnbApiException("CNB commit diff file did not include a path")
        if (wire.additions < 0 || wire.deletions < 0) {
            throw CnbApiException("CNB commit diff file contained negative line counts")
        }
        return CnbCommitDiffFile(
            path = boundedRequiredWireText(path, "commit diff path", MAX_REVIEW_PATH_LENGTH),
            name =
                boundedWireText(
                    wire.name?.takeIf(String::isNotBlank) ?: path.substringAfterLast('/'),
                    "commit diff name",
                    MAX_REPOSITORY_NAME_LENGTH,
                ),
            previousFilename = boundedWireText(wire.previousFilename, "commit diff previous path", MAX_REVIEW_PATH_LENGTH),
            status = requireWireEnumValue(wire.status, "commit diff status", CnbCommitDiffStatus.entries) { it.wireValue },
            additions = wire.additions,
            deletions = wire.deletions,
            patch = boundedWireText(wire.patch, "commit diff patch", MAX_DIFF_PATCH_LENGTH, allowLineBreaks = true),
            mode = optionalWireEnumValue(wire.mode, "commit diff mode", CnbGitFileMode.entries) { it.wireValue },
            previousMode = optionalWireEnumValue(wire.previousMode, "commit diff previous mode", CnbGitFileMode.entries) { it.wireValue },
        )
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    private fun parsePullRequest(
        wire: CnbPullRequestWire,
        fallbackRepo: String,
    ): CnbPullRequest {
        if (wire.labels.size > MAX_PULL_LABELS) throw CnbApiException("CNB pull request response exceeded the label limit")
        if (wire.assignees.size > MAX_PULL_PARTICIPANTS_RESPONSE) {
            throw CnbApiException("CNB pull request response exceeded the assignee limit")
        }
        if (wire.reviewers.size > MAX_PULL_PARTICIPANTS_RESPONSE) {
            throw CnbApiException("CNB pull request response exceeded the reviewer limit")
        }
        val sourceRepo = requireWireRepositoryPath(wire.head.repo?.path ?: fallbackRepo, "pull request source repository")
        val targetRepo = requireWireRepositoryPath(wire.base.repo?.path ?: fallbackRepo, "pull request target repository")
        val updated = wire.updatedAt ?: wire.lastActedAt
        val authorWire = wire.author ?: wire.user
        val authorInfo = authorWire?.let { parseUser(it, "pull request author") }
        return CnbPullRequest(
            number = requireWirePullRequestNumber(wire.number),
            title = boundedRequiredWireText(wire.title, "pull request title", MAX_PULL_TITLE_LENGTH),
            state = requireWireEnumValue(wire.state, "pull request state", CnbPullRequestState.entries) { it.wireValue },
            sourceRepo = sourceRepo,
            sourceBranch = requireWireBranchName(wire.head.ref, "pull request source ref"),
            sourceSha = requireWireObjectId(wire.head.sha, "pull request source sha"),
            targetRepo = targetRepo,
            targetBranch = requireWireBranchName(wire.base.ref, "pull request target ref"),
            targetSha = requireWireObjectId(wire.base.sha, "pull request target sha"),
            mergeSha = optionalWireObjectId(wire.mergeSha ?: wire.mergeCommitSha, "pull request merge sha"),
            author = authorInfo?.username.orEmpty(),
            fromFork = sourceRepo != targetRepo,
            draft = wire.isWip,
            updatedAt = optionalWireTimestampEpoch(updated, "pull request updated time"),
            body = boundedWireText(wire.body, "pull request body", MAX_PULL_BODY_LENGTH, allowLineBreaks = true),
            blockedOn = parsePullBlockedReason(wire.blockedOn),
            mergeableState =
                optionalWireEnumValue(wire.mergeableState, "pull request mergeable state", CnbPullMergeableState.entries) {
                    it.wireValue
                },
            labels = wire.labels.map(::parseLabel),
            assignees = wire.assignees.map { parseUser(it, "pull request assignee") },
            reviewers = wire.reviewers.map(::parsePullReviewer),
            authorInfo = authorInfo,
            mergedBy = wire.mergedBy?.takeIf { it.username.isNotBlank() }?.let { parseUser(it, "pull request merger") },
            createdAt = wire.createdAt.let { if (it.isEmpty()) "" else requireWireTimestamp(it, "pull request created time") },
        )
    }

    private fun parsePullReviewer(wire: CnbPullReviewerWire): CnbPullReviewer {
        val user =
            wire.user
                ?: CnbUserInfoWire(
                    username = wire.username,
                    nickname = wire.nickname,
                    email = wire.email,
                    avatar = wire.avatar,
                    freeze = wire.freeze,
                    isNpc = wire.isNpc,
                )
        return CnbPullReviewer(
            user = parseUser(user, "pull request reviewer"),
            reviewState = optionalWireEnumValue(wire.reviewState, "pull request review state", CnbPullReviewState.entries) { it.wireValue },
        )
    }

    private fun parsePullBlockedReason(value: String): CnbPullBlockedReason? =
        when (value) {
            "", "unblocked" -> null
            else -> requireWireEnumValue(value, "pull request block reason", CnbPullBlockedReason.entries) { it.wireValue }
        }

    private fun parseUser(
        wire: CnbUserInfoWire,
        field: String,
    ): CnbUser =
        CnbUser(
            username = boundedRequiredWireText(wire.username, "$field username", MAX_USERNAME_LENGTH),
            nickname = boundedWireText(wire.nickname, "$field nickname", MAX_DISPLAY_NAME_LENGTH),
            email = safeWireToken(wire.email, "$field email", MAX_EMAIL_LENGTH),
            avatarUrl = validateExternalUrl(wire.avatar, "$field avatar URL"),
            frozen = wire.freeze,
            npc = wire.isNpc,
        )

    private fun parseLabel(wire: CnbLabelWire): CnbLabel =
        CnbLabel(
            id = requireWireResourceId(wire.id, "label id"),
            name = boundedRequiredWireText(wire.name, "label name", MAX_PULL_LABEL_LENGTH),
            color = validateWireLabelColor(wire.color),
            description = boundedWireText(wire.description, "label description", MAX_LABEL_DESCRIPTION_LENGTH, allowLineBreaks = true),
        )

    private fun parsePullComment(wire: CnbPullCommentWire): CnbPullComment =
        CnbPullComment(
            id = requireWireResourceId(wire.id, "pull comment id"),
            body = boundedWireText(wire.body, "pull comment body", MAX_COMMENT_LENGTH, allowLineBreaks = true),
            author =
                (wire.user?.username?.takeIf(String::isNotBlank) ?: wire.author?.username.orEmpty()).let {
                    boundedWireText(it, "pull comment author", MAX_USERNAME_LENGTH)
                },
            createdAt = wire.createdAt.let { if (it.isEmpty()) "" else requireWireTimestamp(it, "pull comment created time") },
            updatedAt = wire.updatedAt.let { if (it.isEmpty()) "" else requireWireTimestamp(it, "pull comment updated time") },
        )

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    private fun parsePullReviewComment(
        wire: CnbPullReviewCommentWire,
        fallbackReviewId: String,
    ): CnbPullReviewCommentInfo {
        if (wire.diffHunk.size > MAX_REVIEW_DIFF_LINES) {
            throw CnbApiException("CNB pull review comment exceeded the diff-line limit")
        }
        if (wire.reactions.size > MAX_REACTIONS) {
            throw CnbApiException("CNB pull review comment exceeded the reaction limit")
        }
        val reviewId = wire.reviewId?.takeIf(String::isNotBlank) ?: fallbackReviewId
        return CnbPullReviewCommentInfo(
            id = requireWireResourceId(wire.id, "pull review comment id"),
            reviewId = requireWireResourceId(reviewId, "pull review id"),
            body = boundedWireText(wire.body, "pull review comment body", MAX_COMMENT_LENGTH, allowLineBreaks = true),
            author = wire.author?.let { parseUser(it, "pull review comment author") },
            commitSha = optionalWireObjectId(wire.commitHash, "pull review comment commit sha").orEmpty(),
            path = boundedWireText(wire.path, "pull review comment path", MAX_REVIEW_PATH_LENGTH),
            reviewState = optionalWireEnumValue(wire.reviewState, "pull review comment state", CnbPullReviewState.entries) { it.wireValue },
            replyToCommentId =
                wire.replyToCommentId
                    ?.takeIf(String::isNotBlank)
                    ?.let { requireWireResourceId(it, "parent pull review comment id") }
                    .orEmpty(),
            subjectType =
                optionalWireEnumValue(wire.subjectType, "pull review comment subject type", CnbPullReviewSubjectType.entries) {
                    it.wireValue
                },
            startLine = optionalWireLineNumber(wire.startLine, "pull review comment start line"),
            startSide = optionalWireEnumValue(wire.startSide, "pull review comment start side", CnbPullReviewSide.entries) { it.wireValue },
            endLine = optionalWireLineNumber(wire.endLine, "pull review comment end line"),
            endSide = optionalWireEnumValue(wire.endSide, "pull review comment end side", CnbPullReviewSide.entries) { it.wireValue },
            diffHunk = wire.diffHunk.map(::parsePullReviewDiffLine),
            reactions = wire.reactions.map(::parseReaction),
            createdAt = requireWireTimestamp(wire.createdAt, "pull review comment created time"),
            updatedAt = requireWireTimestamp(wire.updatedAt, "pull review comment updated time"),
        )
    }

    private fun parsePullReviewDiffLine(wire: CnbPullReviewDiffLineWire): CnbPullReviewDiffLine =
        CnbPullReviewDiffLine(
            content = boundedWireText(wire.content, "pull review diff content", MAX_REVIEW_DIFF_LINE_LENGTH, allowLineBreaks = true),
            type = requireWireEnumValue(wire.type, "pull review diff type", CnbPullReviewDiffLineType.entries) { it.wireValue },
            prefix = boundedWireText(wire.prefix, "pull review diff prefix", MAX_REVIEW_DIFF_PREFIX_LENGTH),
            leftLineNumber = optionalWireLineNumber(wire.leftLineNumber, "pull review diff left line"),
            rightLineNumber = optionalWireLineNumber(wire.rightLineNumber, "pull review diff right line"),
        )

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    private fun parseReaction(wire: CnbReactionWire): CnbReactionSummary {
        if (wire.count < 0 || wire.topUsers.size > MAX_REACTION_TOP_USERS) {
            throw CnbApiException("CNB pull review comment contained an invalid reaction")
        }
        return CnbReactionSummary(
            reaction = boundedRequiredWireText(wire.reaction, "pull review reaction", MAX_REACTION_NAME_LENGTH),
            count = wire.count,
            reactedByCurrentUser = wire.hasReacted,
            topUsers = wire.topUsers.map { parseUser(it, "pull review reaction user") },
        )
    }

    private fun mutatePullAssignees(
        method: String,
        repo: String,
        number: String,
        assignees: List<String>,
    ): CnbPullRequest {
        validatePullRequestNumber(number)
        validatePullParticipants(assignees, "assignee")
        val wire =
            requestJson(
                method,
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/assignees",
                CnbPullRequestWire.serializer(),
                body =
                    encodeRequest(
                        CnbPullAssigneesRequestWire.serializer(),
                        CnbPullAssigneesRequestWire(assignees),
                    ),
                idempotent = false,
            ) ?: throw CnbApiException("CNB pull request assignee response was empty")
        return parsePullRequest(wire, repo)
    }

    private fun mutatePullReviewers(
        method: String,
        repo: String,
        number: String,
        reviewers: List<String>,
    ): CnbPullRequest {
        validatePullRequestNumber(number)
        validatePullParticipants(reviewers, "reviewer")
        val wire =
            requestJson(
                method,
                "/${encodeRepository(repo)}/-/pulls/${encodePullRequestNumber(number)}/reviewers",
                CnbPullRequestWire.serializer(),
                body =
                    encodeRequest(
                        CnbPullReviewersRequestWire.serializer(),
                        CnbPullReviewersRequestWire(reviewers),
                    ),
                idempotent = false,
            ) ?: throw CnbApiException("CNB pull request reviewer response was empty")
        return parsePullRequest(wire, repo)
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    private fun parseRelease(
        repo: String,
        wire: CnbReleaseWire,
    ): CnbRelease {
        val assets = wire.assets.orEmpty()
        if (assets.size > MAX_RELEASE_ASSETS) throw CnbApiException("CNB release response exceeded the asset limit")
        val tagName = requireWireTagName(wire.tagName, "release tag name")
        return CnbRelease(
            id = requireWireId(wire.id, "release id"),
            tagName = tagName,
            name = boundedWireText(wire.name, "release name", MAX_RELEASE_NAME_LENGTH),
            body = boundedWireText(wire.body, "release body", MAX_RELEASE_BODY_LENGTH, allowLineBreaks = true),
            tagCommitish = wire.tagCommitish?.takeIf(String::isNotEmpty)?.let(::requireWireReleaseCommitish),
            draft = wire.draft,
            prerelease = wire.prerelease,
            latest = wire.isLatest,
            createdAt = requireWireTimestamp(wire.createdAt, "release created time"),
            updatedAt = requireWireTimestamp(wire.updatedAt, "release updated time"),
            publishedAt = wire.publishedAt?.takeIf(String::isNotEmpty)?.let { requireWireTimestamp(it, "release published time") },
            author = wire.author?.let(::parseReleaseUser),
            assets = assets.map { parseReleaseAsset(it, repo, tagName) },
        )
    }

    private fun parseReleaseAsset(
        wire: CnbReleaseAssetWire,
        repo: String,
        expectedTag: String? = null,
    ): CnbReleaseAsset {
        if (wire.size !in 0..MAX_RELEASE_ASSET_METADATA_BYTES) {
            throw CnbApiException("CNB release asset response contained an invalid size")
        }
        if (wire.downloadCount < 0) throw CnbApiException("CNB release asset response contained an invalid download count")
        val contentType =
            wire.contentType
                .takeIf(String::isNotBlank)
                ?.let(::validateReleaseMediaType)
                .orEmpty()
        val name = requireWireAssetName(wire.name)
        return CnbReleaseAsset(
            id = requireWireId(wire.id, "release asset id"),
            name = name,
            path = requireWireAssetPath(wire.path, repo, name, expectedTag),
            size = wire.size,
            contentType = contentType,
            downloadCount = wire.downloadCount,
            hashAlgorithm = safeWireToken(wire.hashAlgorithm, "release asset hash algorithm", MAX_HASH_ALGORITHM_LENGTH),
            hashValue = safeWireToken(wire.hashValue, "release asset hash", MAX_HASH_VALUE_LENGTH),
            browserDownloadUrl =
                validateExternalUrl(
                    wire.browserDownloadUrl.ifBlank { wire.legacyBrowserDownloadUrl },
                    "release asset browser URL",
                ),
            apiUrl = validateExternalUrl(wire.url, "release asset API URL"),
            createdAt = requireWireTimestamp(wire.createdAt, "release asset created time"),
            updatedAt = requireWireTimestamp(wire.updatedAt, "release asset updated time"),
            uploader = wire.uploader?.let(::parseReleaseUser),
        )
    }

    private fun parseReleaseUser(wire: CnbReleaseUserWire): CnbReleaseUser =
        CnbReleaseUser(
            username = boundedRequiredWireText(wire.username, "release user name", MAX_USERNAME_LENGTH),
            nickname = boundedWireText(wire.nickname, "release user nickname", MAX_DISPLAY_NAME_LENGTH),
            email = safeWireToken(wire.email, "release user email", MAX_EMAIL_LENGTH),
            avatarUrl = validateExternalUrl(wire.avatar, "release user avatar URL"),
            frozen = wire.freeze,
            npc = wire.isNpc,
        )

    private fun parseCommitStatus(wire: CnbCommitStatusWire): CnbCommitStatus =
        CnbCommitStatus(
            context = boundedRequiredWireText(wire.context, "commit status context", MAX_STATUS_CONTEXT_LENGTH),
            state = requireWireEnumValue(wire.state, "commit status state", CnbCommitStatusState.entries) { it.wireValue },
            description = boundedWireText(wire.description, "commit status description", MAX_STATUS_DESCRIPTION_LENGTH),
            targetUrl = validateExternalUrl(wire.targetUrl, "commit status target URL"),
            createdAt = requireWireTimestamp(wire.createdAt, "commit status created time"),
            updatedAt = requireWireTimestamp(wire.updatedAt, "commit status updated time"),
        )

    private fun parseBadgeSummary(
        repo: String,
        wire: CnbBadgeSummaryWire,
    ): CnbBadgeSummary {
        val group = wire.group ?: CnbBadgeGroupWire()
        return CnbBadgeSummary(
            name = requireWireBadgeName(wire.name),
            description = boundedWireText(wire.desc, "badge description", MAX_STATUS_DESCRIPTION_LENGTH),
            type = boundedWireText(wire.type, "badge type", MAX_BADGE_TYPE_LENGTH),
            group =
                CnbBadgeGroup(
                    status = boundedWireText(group.status, "badge group status", MAX_BADGE_GROUP_LENGTH),
                    type = boundedWireText(group.type, "badge group type", MAX_BADGE_GROUP_LENGTH),
                    englishType = boundedWireText(group.typeEn, "badge group English type", MAX_BADGE_GROUP_LENGTH),
                ),
            url = normalizeBadgeUrl(wire.url, repo, "badge URL"),
            link = validateExternalUrl(wire.link, "badge link"),
        )
    }

    private fun parseBuildResult(wire: CnbBuildResultWire): CnbBuildResult =
        CnbBuildResult(
            sn = requireWireResourceId(wire.sn, "build serial number"),
            buildLogUrl = validateExternalUrl(wire.buildLogUrl, "build log URL"),
            message = boundedWireText(wire.message, "build result message", MAX_BUILD_MESSAGE_LENGTH, allowLineBreaks = true),
            success = wire.success,
        )

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    private fun parsePipelineStatus(wire: CnbPipelineStatusWire): CnbPipelineStatus =
        CnbPipelineStatus(
            id = requireWireResourceId(wire.id, "pipeline id"),
            name = boundedWireText(wire.name, "pipeline name", MAX_BUILD_STAGE_NAME_LENGTH),
            status = requireWireEnumValue(wire.status, "pipeline status", CnbBuildState.entries) { it.wireValue },
            duration = nonNegativeWireLong(wire.duration, "pipeline duration"),
            metricCoreHours = nonNegativeFiniteWireDouble(wire.metricCoreHours, "pipeline core-hours metric"),
            metricDuration = nonNegativeFiniteWireDouble(wire.metricDuration, "pipeline duration metric"),
            labels =
                wire.labels.map { label ->
                    CnbPipelineLabel(
                        key = boundedRequiredWireText(label.key, "pipeline label key", MAX_BUILD_LABEL_LENGTH),
                        values = label.value.map { boundedWireText(it, "pipeline label value", MAX_BUILD_LABEL_LENGTH) },
                    )
                },
            stages =
                wire.stages.map(::parseBuildStage),
        )

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    private fun parseBuildStage(wire: CnbBuildStageWire): CnbBuildStage {
        if (wire.duration < 0 || wire.startTime < 0 || wire.endTime < 0) {
            throw CnbApiException("CNB build stage response contained invalid timing data")
        }
        if (wire.content.size > MAX_BUILD_STAGE_LOG_LINES) {
            throw CnbApiException("CNB build stage response exceeded the log-line limit")
        }
        return CnbBuildStage(
            id = requireWireResourceId(wire.id, "build stage id"),
            name = boundedWireText(wire.name, "build stage name", MAX_BUILD_STAGE_NAME_LENGTH),
            status = requireWireEnumValue(wire.status, "build stage status", CnbBuildStageStatus.entries) { it.wireValue },
            duration = wire.duration,
            startTime = wire.startTime,
            endTime = wire.endTime,
            error = boundedWireText(wire.error, "build stage error", MAX_COMMENT_LENGTH, allowLineBreaks = true),
            content =
                wire.content.map { line ->
                    boundedWireText(line, "build stage log line", MAX_BUILD_STAGE_LOG_LINE_LENGTH, allowLineBreaks = false)
                },
        )
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    private fun parseBuildInfo(wire: CnbBuildInfoWire): CnbBuildInfo {
        if (wire.pipelineFailCount < 0 || wire.pipelineSuccessCount < 0 || wire.pipelineTotalCount < 0) {
            throw CnbApiException("CNB build history response contained negative pipeline counts")
        }
        if (wire.pipelines.size > MAX_BUILD_PIPELINES) {
            throw CnbApiException("CNB build history response exceeded the pipeline limit")
        }
        return CnbBuildInfo(
            sn = requireWireResourceId(wire.sn, "build history serial number"),
            sha = optionalWireObjectId(wire.sha, "build history sha").orEmpty(),
            slug = optionalWireRepositoryPath(wire.slug, "build history repository"),
            status = requireWireEnumValue(wire.status, "build history status", CnbBuildState.entries) { it.wireValue },
            event =
                wire.event.takeIf(String::isNotEmpty)?.let {
                    CnbBuildEventName(requireWireToken(it, "build history event", MAX_BUILD_EVENT_LENGTH))
                },
            sourceRef = optionalWireGitRef(wire.sourceRef, "build history source ref"),
            sourceSlug = optionalWireRepositoryPath(wire.sourceSlug, "build history source repository"),
            targetRef = optionalWireGitRef(wire.targetRef, "build history target ref"),
            title = boundedWireText(wire.title, "build history title", MAX_BUILD_TITLE_LENGTH),
            commitTitle = boundedWireText(wire.commitTitle, "build history commit title", MAX_COMMIT_TITLE_LENGTH),
            buildLogUrl = validateExternalUrl(wire.buildLogUrl, "build history log URL"),
            eventUrl = validateExternalUrl(wire.eventUrl, "build history event URL"),
            createTime = wire.createTime.let { if (it.isEmpty()) "" else requireWireTimestamp(it, "build history create time") },
            duration = nonNegativeWireLong(wire.duration, "build history duration"),
            labels = boundedWireText(wire.labels, "build history labels", MAX_BUILD_LABELS_LENGTH),
            groupName = boundedWireText(wire.groupName, "build history group name", MAX_DISPLAY_NAME_LENGTH),
            userName = boundedWireText(wire.userName, "build history user name", MAX_USERNAME_LENGTH),
            nickName = boundedWireText(wire.nickName, "build history nickname", MAX_DISPLAY_NAME_LENGTH),
            freeze = wire.freeze,
            pipelineFailCount = wire.pipelineFailCount,
            pipelineSuccessCount = wire.pipelineSuccessCount,
            pipelineTotalCount = wire.pipelineTotalCount,
            pipelines = wire.pipelines.map(::parseBuildPipeline),
        )
    }

    private fun parseBuildPipeline(wire: CnbBuildPipelineWire): CnbBuildPipeline =
        CnbBuildPipeline(
            id = requireWireResourceId(wire.id, "build history pipeline id"),
            status = requireWireEnumValue(wire.status, "build history pipeline status", CnbBuildState.entries) { it.wireValue },
            createTime = wire.createTime.let { if (it.isEmpty()) "" else requireWireTimestamp(it, "build history pipeline create time") },
            duration = nonNegativeWireLong(wire.duration, "build history pipeline duration"),
            labels = boundedWireText(wire.labels, "build history pipeline labels", MAX_BUILD_LABELS_LENGTH),
        )

    private fun requireWireText(
        value: String,
        field: String,
    ): String {
        if (value.isBlank()) throw CnbApiException("CNB response contained an empty $field")
        return value
    }

    private fun boundedRequiredWireText(
        value: String,
        field: String,
        maxLength: Int,
    ): String {
        if (value.isBlank()) throw CnbApiException("CNB response contained an empty $field")
        return boundedWireText(value, field, maxLength)
    }

    private fun boundedWireText(
        value: String,
        field: String,
        maxLength: Int,
        allowLineBreaks: Boolean = false,
    ): String {
        if (value.length > maxLength || value.any { unsafeTextCharacter(it, allowLineBreaks) }) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        return value
    }

    private fun <T> optionalWireEnumValue(
        value: String,
        field: String,
        values: Iterable<T>,
        wireValue: (T) -> String,
    ): T? {
        if (value.isEmpty()) return null
        return values.firstOrNull { wireValue(it) == value }
            ?: throw CnbApiException("CNB response contained an unsupported $field")
    }

    private fun <T> requireWireEnumValue(
        value: String,
        field: String,
        values: Iterable<T>,
        wireValue: (T) -> String,
    ): T =
        optionalWireEnumValue(value, field, values, wireValue)
            ?: throw CnbApiException("CNB response contained an empty $field")

    private fun optionalWireLineNumber(
        value: Int,
        field: String,
    ): Int? {
        if (value < 0) throw CnbApiException("CNB response contained an invalid $field")
        return value.takeIf { it > 0 }
    }

    private fun nonNegativeWireLong(
        value: Long,
        field: String,
    ): Long {
        if (value < 0) throw CnbApiException("CNB response contained an invalid $field")
        return value
    }

    private fun nonNegativeFiniteWireDouble(
        value: Double,
        field: String,
    ): Double {
        if (!value.isFinite() || value < 0) throw CnbApiException("CNB response contained an invalid $field")
        return value
    }

    private fun safeWireToken(
        value: String,
        field: String,
        maxLength: Int,
    ): String {
        if (value.isEmpty()) return ""
        if (value.length > maxLength || value.any { it.code < 0x21 || it.code == 0x7f }) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        return value
    }

    private fun requireWireToken(
        value: String,
        field: String,
        maxLength: Int,
    ): String =
        safeWireToken(value, field, maxLength).takeIf(String::isNotEmpty)
            ?: throw CnbApiException("CNB response contained an empty $field")

    private fun requireWireBadgeName(value: String): String =
        try {
            validateBadgeName(value)
            value
        } catch (_: IllegalArgumentException) {
            throw CnbApiException("CNB response contained an invalid badge name")
        }

    private fun normalizeBadgeName(value: String): String {
        val normalized = value.removeSuffix(".json")
        validateBadgeName(normalized)
        return normalized
    }

    private fun validateBadgeRevision(value: String): String {
        require(value == "latest" || BADGE_REVISION.matches(value)) {
            "CNB badge revision must be latest or an 8-character hexadecimal commit hash"
        }
        return value.lowercase()
    }

    private fun validateBadgeBranch(value: String): String {
        require(value == value.trim() && value.length in 1..MAX_BUILD_REFERENCE_LENGTH) { "Invalid CNB badge branch" }
        require(Repository.isValidRefName("refs/heads/$value")) { "Invalid CNB badge branch" }
        return value
    }

    private fun validateBadgeLink(value: String): String {
        if (value.isEmpty()) return ""
        require(value.length <= MAX_EXTERNAL_URL_LENGTH && value.none { it.code < 0x20 || it.code == 0x7f }) {
            "Invalid CNB badge link"
        }
        val uri = runCatching { URI(value) }.getOrNull()
        require(
            uri != null && uri.isAbsolute && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
                (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)),
        ) { "Invalid CNB badge link" }
        return uri.toASCIIString()
    }

    private fun normalizeBadgeUrl(
        value: String,
        repo: String,
        field: String,
    ): String {
        if (value.isEmpty() || value.length > MAX_EXTERNAL_URL_LENGTH || value.any { it.code < 0x20 || it.code == 0x7f }) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        val candidate =
            try {
                URI(value)
            } catch (_: IllegalArgumentException) {
                throw CnbApiException("CNB response contained an invalid $field")
            }
        if (candidate.rawUserInfo != null || candidate.rawQuery != null || candidate.rawFragment != null) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        val webRoot = server.normalizedWebUri()
        val resolved =
            if (candidate.isAbsolute) {
                candidate
            } else {
                if (!value.startsWith('/') || candidate.rawAuthority != null) {
                    throw CnbApiException("CNB response contained an invalid $field")
                }
                URI(webRoot.scheme, null, webRoot.host, webRoot.port, "/", null, null).resolve(candidate)
            }
        val expectedPrefix = "/${encodeRepository(repo)}/-/badge/"
        if (!sameOrigin(webRoot, resolved) || !resolved.rawPath.startsWith(expectedPrefix)) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        return resolved.toASCIIString()
    }

    private fun validateExternalUrl(
        value: String,
        field: String,
    ): String {
        if (value.isEmpty()) return ""
        if (value.length > MAX_EXTERNAL_URL_LENGTH || value.any { it.code < 0x20 || it.code == 0x7f }) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        val uri =
            try {
                URI(value)
            } catch (_: IllegalArgumentException) {
                throw CnbApiException("CNB response contained an invalid $field")
            }
        val allowedScheme = uri.scheme.equals("https", true) || (server.allowInsecureHttp && uri.scheme.equals("http", true))
        if (!uri.isAbsolute || uri.host.isNullOrBlank() || !allowedScheme || uri.rawUserInfo != null || uri.rawFragment != null) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        return uri.toASCIIString()
    }

    private fun requireWireAssetName(value: String): String =
        try {
            validateAssetName(value)
            value
        } catch (_: IllegalArgumentException) {
            throw CnbApiException("CNB response contained an invalid release asset name")
        }

    private fun requireWireAssetPath(
        value: String,
        repo: String,
        assetName: String,
        expectedTag: String?,
    ): String {
        if (
            value.isEmpty() || value.length > MAX_RELEASE_ASSET_PATH_LENGTH || value.endsWith('/') ||
            value.any { it == '\\' || it.code < 0x20 || it.code == 0x7f }
        ) {
            throw CnbApiException("CNB response contained an invalid release asset path")
        }
        if (value.startsWith('/')) {
            val prefix = "/$repo/-/releases/download/"
            if (!value.startsWith(prefix)) {
                throw CnbApiException("CNB response contained an invalid release asset path")
            }
            val suffix = value.removePrefix(prefix)
            val separator = suffix.lastIndexOf('/')
            if (separator !in 1 until suffix.lastIndex || suffix.substring(separator + 1) != assetName) {
                throw CnbApiException("CNB response contained an invalid release asset path")
            }
            val tag = suffix.substring(0, separator)
            try {
                validateTag(tag)
            } catch (_: IllegalArgumentException) {
                throw CnbApiException("CNB response contained an invalid release asset path")
            }
            if (expectedTag != null && tag != expectedTag) {
                throw CnbApiException("CNB response contained an invalid release asset path")
            }
            return value
        }
        if (value.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw CnbApiException("CNB response contained an invalid release asset path")
        }
        return value
    }

    private fun requireWireReleaseCommitish(value: String): String {
        if (value.isEmpty()) return ""
        try {
            validateReleaseCommitish(value)
        } catch (_: IllegalArgumentException) {
            throw CnbApiException("CNB response contained an invalid release tag commitish")
        }
        return if (CnbGitObjectId.isValid(value)) CnbGitObjectId.canonical(value) else value
    }

    private fun requireWireTimestamp(
        value: String,
        field: String,
    ): String {
        if (value.isEmpty()) return ""
        if (value.length > MAX_TIMESTAMP_LENGTH || value.any { it.code < 0x20 || it.code == 0x7f }) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        try {
            Instant.parse(value)
        } catch (_: RuntimeException) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        return value
    }

    private fun optionalWireTimestampEpoch(
        value: String?,
        field: String,
    ): Long {
        val timestamp = value?.takeIf(String::isNotEmpty) ?: return 0
        requireWireTimestamp(timestamp, field)
        return Instant.parse(timestamp).toEpochMilli()
    }

    private fun requireWireRepositoryPath(
        value: String,
        field: String,
    ): String {
        if (!CnbRepositoryPath.isValid(value)) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        return value
    }

    private fun requireWireResourcePath(
        value: String,
        field: String,
    ): String {
        if (!CnbResourcePath.isValid(value)) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        return value
    }

    private fun requireWireObjectId(
        value: String,
        field: String,
    ): String =
        try {
            CnbGitObjectId.canonical(value)
        } catch (_: IllegalArgumentException) {
            throw CnbApiException("CNB response contained an invalid $field")
        }

    private fun optionalWireObjectId(
        value: String?,
        field: String,
    ): String? = value?.takeIf(String::isNotBlank)?.let { requireWireObjectId(it, field) }

    private fun requireWireBranchName(
        value: String,
        field: String,
    ): String {
        val name = stripHeadRef(requireWireText(value, field))
        if (!Repository.isValidRefName("refs/heads/$name")) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        return name
    }

    private fun optionalBranchName(
        value: String?,
        field: String,
    ): String = value?.takeIf(String::isNotBlank)?.let { requireWireBranchName(it, field) }.orEmpty()

    private fun requireWireTagName(
        value: String,
        field: String,
    ): String {
        val name = requireWireText(value, field)
        if (!Repository.isValidRefName("refs/tags/$name")) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        return name
    }

    private fun requireWireId(
        value: String,
        field: String,
    ): String {
        val id = requireWireText(value, field)
        if (!RESOURCE_ID.matches(id)) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        return id
    }

    private fun requireWirePullRequestNumber(value: String): String {
        val number = requireWireText(value, "pull request number")
        if (!PULL_REQUEST_NUMBER.matches(number)) {
            throw CnbApiException("CNB response contained an invalid pull request number")
        }
        return number
    }

    private fun requireWireResourceId(
        value: String,
        field: String,
    ): String {
        val id = requireWireId(value, field)
        if (!RESOURCE_ID.matches(id)) throw CnbApiException("CNB response contained an invalid $field")
        return id
    }

    private fun optionalWireRepositoryPath(
        value: String,
        field: String,
    ): String = if (value.isEmpty()) "" else requireWireRepositoryPath(value, field)

    private fun optionalWireGitRef(
        value: String,
        field: String,
    ): String {
        if (value.isEmpty()) return ""
        val candidate = if (value.startsWith("refs/")) value else "refs/heads/$value"
        if (value.length > MAX_BUILD_REFERENCE_LENGTH || !Repository.isValidRefName(candidate)) {
            throw CnbApiException("CNB response contained an invalid $field")
        }
        return value
    }

    private fun validateComment(body: String) {
        require(body.isNotBlank()) { "Comment body must not be empty" }
        require(body.length <= MAX_COMMENT_LENGTH) { "Comment body is too long" }
    }

    private fun validateBadgeName(value: String) {
        require(value.length in 1..MAX_BADGE_NAME_LENGTH) { "Invalid CNB badge name length" }
        require(value.none { it == '\\' || it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
            "Invalid CNB badge name"
        }
        encodeRelativePath(value)
    }

    private fun validatePullNumbers(numbers: List<String>) {
        require(numbers.size in 1..MAX_BATCH_PULL_REQUESTS) { "Invalid CNB pull request batch size" }
        require(numbers.distinct().size == numbers.size) { "CNB pull request batch contains duplicate numbers" }
        numbers.forEach(::validatePullRequestNumber)
    }

    private fun validatePullRequestNumber(value: String) {
        require(PULL_REQUEST_NUMBER.matches(value)) { "Invalid CNB pull request number" }
    }

    private fun encodePullRequestNumber(value: String): String {
        validatePullRequestNumber(value)
        return encodeSegment(value)
    }

    private fun validateCreatePullRequest(request: CnbCreatePullRequestRequest) {
        validatePullBranch(request.targetBranch, "target")
        validatePullBranch(request.sourceBranch, "source")
        validatePullTitle(request.title)
        validatePullBody(request.body)
        request.sourceRepository?.let { source ->
            require(CnbRepositoryPath.isValid(source)) { "Invalid CNB source repository path" }
        }
    }

    private fun validateUpdatePullRequest(request: CnbUpdatePullRequestRequest) {
        require(request.title != null || request.body != null || request.state != null) {
            "CNB pull request update must change at least one field"
        }
        request.title?.let(::validatePullTitle)
        request.body?.let(::validatePullBody)
        require(request.state != CnbPullRequestState.MERGED) { "CNB pull request state cannot be updated to merged" }
    }

    private fun validatePullTitle(value: String) {
        require(value.isNotBlank() && value.length <= MAX_PULL_TITLE_LENGTH && value.none { unsafeTextCharacter(it, false) }) {
            "Invalid CNB pull request title"
        }
    }

    private fun validatePullBody(value: String) {
        require(value.length <= MAX_PULL_BODY_LENGTH && value.none { unsafeTextCharacter(it, true) }) {
            "Invalid CNB pull request body"
        }
    }

    private fun validatePullBranch(
        value: String,
        field: String,
    ) {
        require(
            value.length in 1..MAX_PULL_REF_LENGTH && !value.startsWith("refs/") &&
                Repository.isValidRefName("refs/heads/$value"),
        ) { "Invalid CNB pull request $field branch" }
    }

    private fun validatePullParticipants(
        values: List<String>,
        field: String,
    ) {
        require(values.size in 1..MAX_PULL_PARTICIPANT_MUTATION) { "Invalid CNB pull request $field count" }
        require(values.distinct().size == values.size) { "Duplicate CNB pull request ${field}s" }
        values.forEach { value ->
            require(
                value.length in 1..MAX_USERNAME_LENGTH && value == value.trim() &&
                    value.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f },
            ) { "Invalid CNB pull request $field" }
        }
    }

    private fun validatePullLabels(labels: List<String>) {
        require(labels.size <= MAX_PULL_LABELS) { "Too many CNB pull request labels" }
        labels.forEach { label ->
            require(label.isNotBlank() && label.length <= MAX_PULL_LABEL_LENGTH) { "Invalid CNB pull request label" }
            require(label.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid CNB pull request label" }
        }
    }

    private fun validateWireLabelColor(value: String): String {
        if (value.isEmpty()) return ""
        if (!LABEL_COLOR.matches(value)) throw CnbApiException("CNB response contained an invalid label color")
        return value
    }

    private fun validatePullReview(review: CnbPullReviewRequest) {
        require(review.body.length <= MAX_COMMENT_LENGTH) { "Pull review body is too long" }
        require(review.comments.size <= MAX_REVIEW_COMMENTS) { "Too many pull review comments" }
        review.comments.forEach { comment ->
            require(comment.body.isNotBlank() && comment.body.length <= MAX_COMMENT_LENGTH) {
                "Invalid pull review comment body"
            }
            require(comment.path.isNotBlank() && comment.path.length <= MAX_REVIEW_PATH_LENGTH) {
                "Invalid pull review comment path"
            }
            if (comment.subjectType == CnbPullReviewSubjectType.LINE) {
                val endLine = comment.endLine
                val endSide = comment.endSide
                require(endLine != null && endLine > 0 && endSide != null) {
                    "Line review comments require a valid end line and side"
                }
                val startLine = comment.startLine
                val startSide = comment.startSide
                if (startLine != null || startSide != null) {
                    require(startLine != null && startLine > 0 && startSide != null) {
                        "Line review comments require a valid start line and side"
                    }
                }
            }
        }
    }

    private fun validateBuildRequest(request: CnbBuildRequest) {
        validateBuildReference("branch", request.branch)
        validateBuildReference("tag", request.tag)
        validateBuildReference("sha", request.sha)
        request.title?.let { require(it.length <= MAX_BUILD_TITLE_LENGTH) { "CNB build title is too long" } }
        request.config?.let { require(it.length <= MAX_BUILD_CONFIG_LENGTH) { "CNB build config is too long" } }
        require(request.env.size <= MAX_BUILD_ENVIRONMENT_ENTRIES) { "Too many CNB build environment variables" }
        request.env.forEach { (key, value) ->
            require(key.isNotBlank() && key.length <= MAX_BUILD_ENVIRONMENT_KEY_LENGTH) {
                "Invalid CNB build environment variable name"
            }
            require(key.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid CNB build environment variable name" }
            require(value.length <= MAX_BUILD_ENVIRONMENT_VALUE_LENGTH) { "CNB build environment variable is too long" }
        }
    }

    private fun buildHistoryParameters(query: CnbBuildHistoryQuery): Map<String, String> {
        validateBuildHistoryDate("createTime", query.createTime)
        validateBuildHistoryDate("endTime", query.endTime)
        val parameters = LinkedHashMap<String, String>()
        addBuildHistoryParameter(parameters, "createTime", query.createTime)
        addBuildHistoryParameter(parameters, "endTime", query.endTime)
        addBuildHistoryParameter(parameters, "event", query.event?.wireValue)
        addBuildHistoryParameter(parameters, "sha", query.sha)
        addBuildHistoryParameter(parameters, "sn", query.sn)
        addBuildHistoryParameter(parameters, "sourceRef", query.sourceRef)
        addBuildHistoryParameter(parameters, "status", query.status?.wireValue)
        addBuildHistoryParameter(parameters, "targetRef", query.targetRef)
        addBuildHistoryParameter(parameters, "userId", query.userId)
        addBuildHistoryParameter(parameters, "userName", query.userName)
        return parameters
    }

    private fun validateBuildHistoryDate(
        name: String,
        value: String?,
    ) {
        if (value.isNullOrEmpty()) return
        require(runCatching { LocalDate.parse(value) }.isSuccess) { "Invalid CNB build history $name date" }
    }

    private fun addBuildHistoryParameter(
        parameters: MutableMap<String, String>,
        name: String,
        value: String?,
    ) {
        if (value.isNullOrEmpty()) return
        require(value.isNotBlank() && value.length <= MAX_BUILD_HISTORY_FILTER_LENGTH) {
            "Invalid CNB build history $name filter"
        }
        require(value.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid CNB build history $name filter" }
        parameters[name] = value
    }

    private fun validateBuildReference(
        name: String,
        value: String?,
    ) {
        if (value == null) return
        if (name == "sha") {
            require(CnbGitObjectId.isValid(value)) { "Invalid CNB build sha" }
            return
        }
        require(value.isNotBlank() && value.length <= MAX_BUILD_REFERENCE_LENGTH) { "Invalid CNB build $name" }
        require(value.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid CNB build $name" }
    }

    private fun validateCreateRelease(request: CnbCreateReleaseRequest) {
        validateTag(request.tagName)
        validateReleaseCommitish(request.targetCommitish)
        validateReleaseName(request.name)
        validateReleaseBody(request.body)
    }

    private fun validateUpdateRelease(request: CnbUpdateReleaseRequest) {
        require(
            request.name != null || request.body != null || request.draft != null ||
                request.prerelease != null || request.makeLatest != null,
        ) { "CNB release update must change at least one field" }
        request.name?.let(::validateReleaseName)
        request.body?.let(::validateReleaseBody)
    }

    private fun validateReleaseName(value: String) {
        require(value.length <= MAX_RELEASE_NAME_LENGTH && value.none { unsafeTextCharacter(it, false) }) {
            "Invalid CNB release name"
        }
    }

    private fun validateReleaseBody(value: String) {
        require(value.length <= MAX_RELEASE_BODY_LENGTH && value.none { unsafeTextCharacter(it, true) }) {
            "Invalid CNB release body"
        }
    }

    private fun validateReleaseCommitish(value: String) {
        require(value.length in 1..MAX_RELEASE_REF_LENGTH) { "Invalid CNB release target commitish" }
        val validRef =
            Repository.isValidRefName("refs/heads/$value") ||
                Repository.isValidRefName("refs/tags/$value") ||
                CnbGitObjectId.isValid(value)
        require(validRef) { "Invalid CNB release target commitish" }
    }

    private fun validateTag(value: String) {
        require(value.length <= MAX_RELEASE_REF_LENGTH && Repository.isValidRefName("refs/tags/$value")) {
            "Invalid CNB release tag"
        }
    }

    private fun validateResourceId(
        value: String,
        field: String,
    ) {
        require(RESOURCE_ID.matches(value)) { "Invalid CNB $field" }
    }

    private fun validateAssetName(value: String) {
        require(
            value.length in 1..MAX_RELEASE_ASSET_NAME_LENGTH && value != "." && value != ".." &&
                value.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f },
        ) { "Invalid CNB release asset name" }
    }

    private fun validateAssetUpload(request: CnbReleaseAssetUploadRequest) {
        validateAssetName(request.assetName)
        require(request.size in 0..MAX_RELEASE_ASSET_TRANSFER_BYTES) { "Invalid CNB release asset size" }
        require(request.ttlDays in 0..MAX_RELEASE_ASSET_TTL_DAYS) { "Invalid CNB release asset ttl" }
    }

    private fun validateReleaseMediaType(value: String): String {
        if (value.length > MAX_CONTENT_TYPE_LENGTH || !RAW_MEDIA_TYPE.matches(value.substringBefore(';').trim())) {
            throw CnbApiException("CNB release asset response contained an invalid Content-Type")
        }
        if (value.any { it.code < 0x20 || it.code == 0x7f }) {
            throw CnbApiException("CNB release asset response contained an invalid Content-Type")
        }
        return value
    }

    private fun unsafeTextCharacter(
        value: Char,
        allowLineBreaks: Boolean,
    ): Boolean = value.code == 0x7f || (value.code < 0x20 && !(allowLineBreaks && value in setOf('\r', '\n', '\t')))

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
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 100
        private const val MAX_PAGINATED_ITEMS = 10_000
        private const val MAX_PAGINATED_BYTES = 16L * 1024 * 1024
        private const val MAX_CONCURRENT_REQUESTS = 8
        private const val PERMIT_WAIT_SECONDS = 5L
        private const val MAX_ATTEMPTS = 4
        private const val MAX_RAW_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_ERROR_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_RAW_REFERENCE_LENGTH = 1_024
        private const val MAX_CONTENT_TYPE_LENGTH = 256
        private const val MAX_COMMENT_LENGTH = 60_000
        private const val MAX_REPOSITORY_NAME_LENGTH = 1_024
        private const val MAX_DIFF_PATCH_LENGTH = 4 * 1024 * 1024
        private const val MAX_STATUS_CONTEXT_LENGTH = 1_024
        private const val MAX_STATUS_DESCRIPTION_LENGTH = 4_096
        private const val MAX_BADGES = 1_000
        private const val MAX_BADGE_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_BADGE_NAME_LENGTH = 1_024
        private const val MAX_BADGE_TYPE_LENGTH = 256
        private const val MAX_BADGE_GROUP_LENGTH = 1_024
        private const val MAX_BADGE_JSON_BYTES = 64 * 1024
        private const val MAX_BADGE_LINKS = 20
        private const val MAX_BADGE_COLOR_LENGTH = 128
        private const val MAX_BADGE_TEXT_LENGTH = 4_096
        private const val MAX_PULL_TITLE_LENGTH = 1_000
        private const val MAX_PULL_BODY_LENGTH = 1024 * 1024
        private const val MAX_PULL_REF_LENGTH = 1_024
        private const val MAX_BATCH_PULL_REQUESTS = 100
        private const val MAX_PULL_PARTICIPANT_MUTATION = 8
        private const val MAX_PULL_PARTICIPANTS_RESPONSE = 100
        private const val MAX_PULL_LABELS = 100
        private const val MAX_PULL_LABEL_LENGTH = 256
        private const val MAX_LABEL_DESCRIPTION_LENGTH = 4_096
        private const val MAX_REVIEW_COMMENTS = 100
        private const val MAX_REVIEW_PATH_LENGTH = 4_096
        private const val MAX_REVIEW_DIFF_LINES = 10_000
        private const val MAX_REVIEW_DIFF_LINE_LENGTH = 256 * 1024
        private const val MAX_REVIEW_DIFF_PREFIX_LENGTH = 16
        private const val MAX_REACTIONS = 100
        private const val MAX_REACTION_TOP_USERS = 100
        private const val MAX_REACTION_NAME_LENGTH = 128
        private const val MAX_COMMIT_TITLE_LENGTH = 1_000
        private const val MAX_COMMIT_MESSAGE_LENGTH = 60_000
        private const val MAX_BUILD_EVENT_LENGTH = 128
        private const val MAX_BUILD_REFERENCE_LENGTH = 1_024
        private const val MAX_BUILD_TITLE_LENGTH = 1_000
        private const val MAX_BUILD_MESSAGE_LENGTH = 60_000
        private const val MAX_BUILD_LABEL_LENGTH = 1_024
        private const val MAX_BUILD_LABELS_LENGTH = 16_384
        private const val MAX_BUILD_CONFIG_LENGTH = 1024 * 1024
        private const val MAX_BUILD_ENVIRONMENT_ENTRIES = 200
        private const val MAX_BUILD_ENVIRONMENT_KEY_LENGTH = 256
        private const val MAX_BUILD_ENVIRONMENT_VALUE_LENGTH = 16_384
        private const val MAX_BUILD_HISTORY_FILTER_LENGTH = 1_024
        private const val MAX_BUILD_STAGE_NAME_LENGTH = 1_000
        private const val MAX_BUILD_STAGE_LOG_LINES = 100_000
        private const val MAX_BUILD_STAGE_LOG_LINE_LENGTH = 256 * 1024
        private const val MAX_BUILD_RUNNER_LOG_BYTES = 512L * 1024 * 1024
        private const val MAX_BUILD_PIPELINES = 10_000
        private const val MAX_CONTENT_ENTRIES = 10_000
        private const val MAX_CONTENT_BODY_LENGTH = 4 * 1024 * 1024
        private const val MAX_EVENT_TYPE_LENGTH = 128
        private const val MAX_RELEASE_NAME_LENGTH = 1_000
        private const val MAX_RELEASE_BODY_LENGTH = 1024 * 1024
        private const val MAX_RELEASE_REF_LENGTH = 1_024
        private const val MAX_RELEASE_ASSETS = 1_000
        private const val MAX_RELEASE_ASSET_NAME_LENGTH = 255
        private const val MAX_RELEASE_ASSET_PATH_LENGTH = 4_096
        private const val MAX_RELEASE_ASSET_TTL_DAYS = 180
        private const val MAX_RELEASE_ASSET_TRANSFER_BYTES = 512L * 1024 * 1024
        private const val MAX_RELEASE_ASSET_METADATA_BYTES = 10L * 1024 * 1024 * 1024
        private const val MAX_UPLOAD_TICKET_LIFETIME_SECONDS = 7L * 24 * 60 * 60
        private const val MAX_EXTERNAL_URL_LENGTH = 8_192
        private const val MAX_TIMESTAMP_LENGTH = 128
        private const val MAX_USERNAME_LENGTH = 256
        private const val MAX_DISPLAY_NAME_LENGTH = 1_024
        private const val MAX_EMAIL_LENGTH = 512
        private const val MAX_HASH_ALGORITHM_LENGTH = 64
        private const val MAX_HASH_VALUE_LENGTH = 512
        private const val MAX_ETAG_LENGTH = 512
        private const val MAX_UPLOAD_RESPONSE_BYTES = 64 * 1024
        private const val MAX_VERIFICATION_SUFFIX_LENGTH = 4_096
        private const val MAX_ANNOTATIONS = 100
        private const val MAX_ANNOTATION_KEY_LENGTH = 256
        private const val MAX_ANNOTATION_VALUE_LENGTH = 16_384
        private const val MAX_COMMIT_ANNOTATION_BATCH_HASHES = 20
        private const val MAX_COMMIT_ANNOTATION_BATCH_KEYS = 5
        private const val MAX_COMMIT_ANNOTATION_BATCH_RESPONSE_BYTES = 4 * 1024 * 1024
        private val ANNOTATION_KEY_PATTERN = Regex("[A-Za-z0-9_-]+")
        private val COMMIT_ANNOTATION_BATCH_HASH = Regex("[0-9A-Fa-f]{40}")
        private const val BASE_BACKOFF_MILLIS = 500L
        private const val MAX_BACKOFF_MILLIS = 10_000L
        private const val MAX_RETRY_AFTER_SECONDS = 60L
        private val RETRYABLE_STATUS_CODES = setOf(500, 502, 503, 504)
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val SIGNED_UPLOAD_REDIRECT_STATUS_CODES = setOf(307, 308)
        private val CONTENT_TYPES =
            listOf(CnbContentType.TREE, CnbContentType.BLOB, CnbContentType.LFS, CnbContentType.EMPTY)
        private val CONTENT_ENTRY_TYPES =
            listOf(CnbContentType.TREE, CnbContentType.BLOB, CnbContentType.LINK, CnbContentType.SUBMODULE)
        private val LABEL_COLOR = Regex("#?[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")
        private val PULL_REQUEST_NUMBER = Regex("[1-9][0-9]{0,19}")
        private val SAFE_ERROR_CODE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val RESOURCE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val UPLOAD_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,511}")
        private val RAW_MEDIA_TYPE = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+/[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        private const val MAX_PRESIGNED_REDIRECTS = 3
        private const val CIRCUIT_FAILURE_THRESHOLD = 5
        private const val CIRCUIT_OPEN_MILLIS = 30_000L
        private const val MAX_FAILURE_GRAPH_NODES = 32
        private val BADGE_REVISION = Regex("(?i)[0-9a-f]{8}")
        private val PERMITS = ConcurrentHashMap<String, Semaphore>()
        private val CIRCUITS = ConcurrentHashMap<String, CircuitBreaker>()

        private fun findApiFailure(failure: Throwable): CnbApiException? {
            val pending = ArrayDeque<Throwable>()
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            pending.add(failure)
            repeat(MAX_FAILURE_GRAPH_NODES) {
                val current = pending.removeFirstOrNull() ?: return null
                if (!seen.add(current)) return@repeat
                if (current is CnbApiException) return current
                current.cause?.let(pending::addLast)
                current.suppressed.forEach(pending::addLast)
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
            require(CnbRepositoryPath.isValid(value)) { "Invalid canonical CNB repository path" }
            return value.split('/').joinToString("/") { encodeSegment(it) }
        }

        private fun encodeNamespace(value: String): String {
            require(value.isNotEmpty() && value == value.trim() && !value.startsWith('/') && !value.endsWith('/')) {
                "Invalid canonical CNB namespace"
            }
            val segments = value.split('/')
            require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid canonical CNB namespace" }
            require(value.none { it == '\\' || it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
                "Invalid canonical CNB namespace"
            }
            return segments.joinToString("/") { encodeSegment(it) }
        }

        private fun encodeRelativePath(value: String): String {
            require(value.isNotEmpty() && !value.startsWith('/') && !value.endsWith('/')) { "Invalid relative path" }
            val segments = value.split('/')
            require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid relative path" }
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
    }
}
