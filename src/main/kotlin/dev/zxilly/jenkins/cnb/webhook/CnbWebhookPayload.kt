package dev.zxilly.jenkins.cnb.webhook

import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable as KotlinSerializable

enum class CnbWebhookEvent(
    val wireName: String,
    val pullRequestEvent: Boolean = false,
    val untrusted: Boolean = false,
) {
    PUSH("push"),
    COMMIT_ADD("commit.add"),
    BRANCH_CREATE("branch.create"),
    BRANCH_DELETE("branch.delete"),
    TAG_PUSH("tag_push"),
    PULL_REQUEST("pull_request", pullRequestEvent = true, untrusted = true),
    PULL_REQUEST_UPDATE("pull_request.update", pullRequestEvent = true, untrusted = true),
    PULL_REQUEST_APPROVED("pull_request.approved", pullRequestEvent = true, untrusted = true),
    PULL_REQUEST_CHANGES_REQUESTED("pull_request.changes_requested", pullRequestEvent = true, untrusted = true),
    PULL_REQUEST_COMMENT("pull_request.comment", pullRequestEvent = true, untrusted = true),
    PULL_REQUEST_TARGET("pull_request.target", pullRequestEvent = true),
    PULL_REQUEST_MERGEABLE("pull_request.mergeable", pullRequestEvent = true),
    PULL_REQUEST_MERGED("pull_request.merged", pullRequestEvent = true),
    ;

    companion object {
        fun fromWireName(value: String): CnbWebhookEvent? = entries.firstOrNull { it.wireName == value }
    }
}

data class CnbWebhookPayload(
    val deliveryId: String,
    val buildId: String,
    val occurredAt: Instant,
    val event: CnbWebhookEvent,
    val eventUrl: String,
    val retry: Boolean,
    val instance: CnbWebhookInstance,
    val repository: CnbWebhookRepository,
    val actor: CnbWebhookActor,
    val ref: CnbWebhookRef,
    val pullRequest: CnbWebhookPullRequest?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class CnbWebhookInstance(
    val webUrl: String,
    val apiUrl: String,
) : Serializable

data class CnbWebhookRepository(
    val id: String,
    val slug: String,
    val url: String,
) : Serializable {
    val name: String
        get() = slug.substringAfterLast('/')
}

data class CnbWebhookActor(
    val id: String,
    val username: String,
    val nickname: String,
    val email: String,
) : Serializable

data class CnbWebhookRef(
    val name: String,
    val sha: String,
    val before: String,
    val commit: String,
    val tag: Boolean,
    val newCommitsCount: String = "",
) : Serializable

data class CnbWebhookPullRequest(
    val id: String,
    val number: String,
    val title: String,
    val description: String,
    val proposer: String,
    val sourceRepository: String,
    val sourceBranch: String,
    val sourceSha: String,
    val targetBranch: String,
    val targetSha: String,
    val mergeSha: String?,
    val action: String,
    val wip: Boolean?,
    val reviewers: String = "",
    val reviewState: String = "",
    val reviewedBy: String = "",
    val lastReviewedBy: String = "",
    val commentId: String = "",
    val commentBody: String = "",
    val commentType: String = "",
    val commentFilePath: String = "",
    val commentRange: String = "",
    val reviewId: String = "",
    val reviewDescription: String = "",
) : Serializable

internal class CnbWebhookFormatException(
    message: String,
) : IllegalArgumentException(message)

internal object CnbWebhookPayloadParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
            coerceInputValues = false
            allowSpecialFloatingPointValues = false
        }

    fun parse(rawBody: ByteArray): CnbWebhookPayload =
        try {
            json
                .decodeFromString(
                    CnbWebhookPayloadWire.serializer(),
                    rawBody.toString(StandardCharsets.UTF_8),
                ).toDomain()
        } catch (failure: CnbWebhookFormatException) {
            throw failure
        } catch (_: Exception) {
            throw CnbWebhookFormatException("Malformed CNB webhook JSON payload")
        }

    fun parseOccurredAt(value: String): Instant =
        runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .getOrElse { throw CnbWebhookFormatException("CNB_BUILD_START_TIME must be an ISO-8601 timestamp") }
}

@KotlinSerializable
private data class CnbWebhookPayloadWire(
    @SerialName("CNB_WEB_ENDPOINT") val webUrl: String? = null,
    @SerialName("CNB_API_ENDPOINT") val apiUrl: String? = null,
    @SerialName("CNB_EVENT") val event: String? = null,
    @SerialName("CNB_EVENT_URL") val eventUrl: String? = null,
    @SerialName("CNB_BRANCH") val branch: String? = null,
    @SerialName("CNB_BRANCH_SHA") val branchSha: String? = null,
    @SerialName("CNB_BEFORE_SHA") val beforeSha: String? = null,
    @SerialName("CNB_COMMIT") val commit: String? = null,
    @SerialName("CNB_NEW_COMMITS_COUNT") val newCommitsCount: String? = null,
    @SerialName("CNB_IS_TAG") val isTag: String? = null,
    @SerialName("CNB_REPO_SLUG") val repositorySlug: String? = null,
    @SerialName("CNB_REPO_ID") val repositoryId: String? = null,
    @SerialName("CNB_REPO_URL_HTTPS") val repositoryUrl: String? = null,
    @SerialName("CNB_BUILD_ID") val buildId: String? = null,
    @SerialName("CNB_BUILD_START_TIME") val buildStartTime: String? = null,
    @SerialName("CNB_BUILD_USER") val buildUser: String? = null,
    @SerialName("CNB_BUILD_USER_ID") val buildUserId: String? = null,
    @SerialName("CNB_BUILD_USER_NICKNAME") val buildUserNickname: String? = null,
    @SerialName("CNB_BUILD_USER_EMAIL") val buildUserEmail: String? = null,
    @SerialName("CNB_PIPELINE_ID") val pipelineId: String? = null,
    @SerialName("CNB_IS_RETRY") val isRetry: String? = null,
    @SerialName("CNB_PULL_REQUEST_ID") val pullRequestId: String? = null,
    @SerialName("CNB_PULL_REQUEST_IID") val pullRequestNumber: String? = null,
    @SerialName("CNB_PULL_REQUEST_TITLE") val pullRequestTitle: String? = null,
    @SerialName("CNB_PULL_REQUEST_DESCRIPTION") val pullRequestDescription: String? = null,
    @SerialName("CNB_PULL_REQUEST_PROPOSER") val pullRequestProposer: String? = null,
    @SerialName("CNB_PULL_REQUEST_SLUG") val pullRequestSlug: String? = null,
    @SerialName("CNB_PULL_REQUEST_BRANCH") val pullRequestBranch: String? = null,
    @SerialName("CNB_PULL_REQUEST_SHA") val pullRequestSha: String? = null,
    @SerialName("CNB_PULL_REQUEST_TARGET_SHA") val pullRequestTargetSha: String? = null,
    @SerialName("CNB_PULL_REQUEST_MERGE_SHA") val pullRequestMergeSha: String? = null,
    @SerialName("CNB_PULL_REQUEST_ACTION") val pullRequestAction: String? = null,
    @SerialName("CNB_PULL_REQUEST_IS_WIP") val pullRequestIsWip: String? = null,
    @SerialName("CNB_PULL_REQUEST_REVIEWERS") val pullRequestReviewers: String? = null,
    @SerialName("CNB_PULL_REQUEST_REVIEW_STATE") val pullRequestReviewState: String? = null,
    @SerialName("CNB_REVIEW_REVIEWED_BY") val reviewedBy: String? = null,
    @SerialName("CNB_REVIEW_LAST_REVIEWED_BY") val lastReviewedBy: String? = null,
    @SerialName("CNB_COMMENT_ID") val commentId: String? = null,
    @SerialName("CNB_COMMENT_BODY") val commentBody: String? = null,
    @SerialName("CNB_COMMENT_TYPE") val commentType: String? = null,
    @SerialName("CNB_COMMENT_FILE_PATH") val commentFilePath: String? = null,
    @SerialName("CNB_COMMENT_RANGE") val commentRange: String? = null,
    @SerialName("CNB_REVIEW_ID") val reviewId: String? = null,
    @SerialName("CNB_REVIEW_DESCRIPTION") val reviewDescription: String? = null,
) {
    fun toDomain(): CnbWebhookPayload {
        val domainEvent =
            CnbWebhookEvent.fromWireName(event.required("CNB_EVENT"))
                ?: throw CnbWebhookFormatException("Unsupported or untrusted CNB event")
        val targetBranch = branch.required("CNB_BRANCH")
        val pullRequest =
            if (domainEvent.pullRequestEvent) {
                CnbWebhookPullRequest(
                    id = pullRequestId.required("CNB_PULL_REQUEST_ID"),
                    number = pullRequestNumber.required("CNB_PULL_REQUEST_IID"),
                    title = pullRequestTitle.orEmpty(),
                    description = pullRequestDescription.orEmpty(),
                    proposer = pullRequestProposer.orEmpty(),
                    sourceRepository = pullRequestSlug.required("CNB_PULL_REQUEST_SLUG"),
                    sourceBranch = pullRequestBranch.required("CNB_PULL_REQUEST_BRANCH"),
                    sourceSha = pullRequestSha.required("CNB_PULL_REQUEST_SHA"),
                    targetBranch = targetBranch,
                    targetSha = pullRequestTargetSha.required("CNB_PULL_REQUEST_TARGET_SHA"),
                    mergeSha = pullRequestMergeSha?.takeIf(String::isNotBlank),
                    action = pullRequestAction.orEmpty(),
                    wip = pullRequestIsWip.optionalBoolean("CNB_PULL_REQUEST_IS_WIP"),
                    reviewers = pullRequestReviewers.orEmpty(),
                    reviewState = pullRequestReviewState.orEmpty(),
                    reviewedBy = reviewedBy.orEmpty(),
                    lastReviewedBy = lastReviewedBy.orEmpty(),
                    commentId = commentId.orEmpty(),
                    commentBody = commentBody.orEmpty(),
                    commentType = commentType.orEmpty(),
                    commentFilePath = commentFilePath.orEmpty(),
                    commentRange = commentRange.orEmpty(),
                    reviewId = reviewId.orEmpty(),
                    reviewDescription = reviewDescription.orEmpty(),
                )
            } else {
                null
            }
        return CnbWebhookPayload(
            deliveryId = pipelineId.required("CNB_PIPELINE_ID"),
            buildId = buildId.orEmpty(),
            occurredAt = CnbWebhookPayloadParser.parseOccurredAt(buildStartTime.required("CNB_BUILD_START_TIME")),
            event = domainEvent,
            eventUrl = eventUrl.orEmpty(),
            retry = isRetry.booleanOrDefault("CNB_IS_RETRY", false),
            instance = CnbWebhookInstance(webUrl.required("CNB_WEB_ENDPOINT"), apiUrl.required("CNB_API_ENDPOINT")),
            repository =
                CnbWebhookRepository(
                    repositoryId.required("CNB_REPO_ID"),
                    repositorySlug.required("CNB_REPO_SLUG"),
                    repositoryUrl.required("CNB_REPO_URL_HTTPS"),
                ),
            actor = CnbWebhookActor(buildUserId.orEmpty(), buildUser.orEmpty(), buildUserNickname.orEmpty(), buildUserEmail.orEmpty()),
            ref =
                CnbWebhookRef(
                    name = targetBranch,
                    sha = branchSha.orEmpty(),
                    before = beforeSha.orEmpty(),
                    commit = commit.orEmpty(),
                    tag = isTag.booleanOrDefault("CNB_IS_TAG", false),
                    newCommitsCount = newCommitsCount.orEmpty(),
                ),
            pullRequest = pullRequest,
        )
    }
}

private fun String?.required(field: String): String =
    this?.takeIf(String::isNotBlank) ?: throw CnbWebhookFormatException("$field is required")

private fun String?.optionalBoolean(field: String): Boolean? =
    when (this) {
        null, "" -> null
        "true" -> true
        "false" -> false
        else -> throw CnbWebhookFormatException("$field must be true or false")
    }

private fun String?.booleanOrDefault(
    field: String,
    default: Boolean,
): Boolean = optionalBoolean(field) ?: default
