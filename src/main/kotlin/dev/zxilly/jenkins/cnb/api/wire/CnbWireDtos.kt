package dev.zxilly.jenkins.cnb.api.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal object CnbStringOrNumberSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CnbStringOrNumber", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder ?: throw SerializationException("CNB identifiers can only be decoded from JSON")
        return decodeStringOrNumber(input.decodeJsonElement())
    }

    override fun serialize(
        encoder: Encoder,
        value: String,
    ) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("CNB identifiers can only be encoded as JSON")
        output.encodeJsonElement(JsonPrimitive(value))
    }
}

internal object CnbNullableStringOrNumberSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CnbNullableStringOrNumber", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val input = decoder as? JsonDecoder ?: throw SerializationException("CNB identifiers can only be decoded from JSON")
        val element = input.decodeJsonElement()
        return if (element === JsonNull) null else decodeStringOrNumber(element)
    }

    override fun serialize(
        encoder: Encoder,
        value: String?,
    ) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("CNB identifiers can only be encoded as JSON")
        output.encodeJsonElement(value?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal object CnbStringOrNumberListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(CnbStringOrNumberSerializer)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<String> = delegate.deserialize(decoder)

    override fun serialize(
        encoder: Encoder,
        value: List<String>,
    ) = delegate.serialize(encoder, value)
}

private fun decodeStringOrNumber(element: kotlinx.serialization.json.JsonElement): String {
    val primitive = element as? JsonPrimitive ?: throw SerializationException("CNB identifier must be a string or number")
    if (primitive === JsonNull || (!primitive.isString && primitive.booleanOrNull != null)) {
        throw SerializationException("CNB identifier must be a string or number")
    }
    if (!primitive.isString && !JSON_INTEGER.matches(primitive.content)) {
        throw SerializationException("CNB identifier must be a string or number")
    }
    return primitive.content
}

private val JSON_INTEGER = Regex("-?(?:0|[1-9][0-9]*)")

@Serializable
internal data class CnbAuthenticatedUserWire(
    val username: String,
    val nickname: String = "",
    val email: String = "",
)

@Serializable
internal data class CnbRepositoryWire(
    val path: String,
    @Serializable(with = CnbNullableStringOrNumberSerializer::class)
    val id: String? = null,
    val name: String? = null,
    @SerialName("web_url")
    val webUrl: String? = null,
    @SerialName("visibility_level")
    val visibilityLevel: String? = null,
    val visibility: String? = null,
    @Serializable(with = CnbNullableStringOrNumberSerializer::class)
    val status: String? = null,
    val archived: Boolean = false,
)

@Serializable
internal data class CnbHeadWire(
    val name: String,
)

@Serializable
internal data class CnbCommitPointerWire(
    val sha: String,
)

@Serializable
internal data class CnbBranchWire(
    val name: String,
    val commit: CnbCommitPointerWire? = null,
    val sha: String? = null,
    val protected: Boolean = false,
    val locked: Boolean = false,
)

@Serializable
internal data class CnbUserInfoWire(
    val username: String = "",
    val nickname: String = "",
    val email: String = "",
    val avatar: String = "",
    val freeze: Boolean = false,
    @SerialName("is_npc")
    val isNpc: Boolean = false,
)

@Serializable
internal data class CnbSignatureWire(
    val name: String = "",
    val email: String = "",
    val date: String = "",
)

@Serializable
internal data class CnbCommitObjectWire(
    val message: String = "",
    val author: CnbSignatureWire? = null,
    val committer: CnbSignatureWire? = null,
)

@Serializable
internal data class CnbCommitParentWire(
    val sha: String,
)

@Serializable
internal data class CnbCommitWire(
    val sha: String,
    val author: CnbUserInfoWire? = null,
    val committer: CnbUserInfoWire? = null,
    val commit: CnbCommitObjectWire? = null,
    val parents: List<CnbCommitParentWire> = emptyList(),
)

@Serializable
internal data class CnbTagWire(
    val name: String,
    val commit: CnbCommitWire? = null,
    val target: String? = null,
)

@Serializable
internal data class CnbCommitDiffFileWire(
    val path: String? = null,
    val name: String? = null,
    @SerialName("previous_filename")
    val previousFilename: String = "",
    val status: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String = "",
    val mode: String = "",
    @SerialName("previous_mode")
    val previousMode: String = "",
)

@Serializable
internal data class CnbCommitComparisonWire(
    @SerialName("base_commit")
    val baseCommit: CnbCommitWire? = null,
    @SerialName("head_commit")
    val headCommit: CnbCommitWire? = null,
    @SerialName("merge_base_commit")
    val mergeBaseCommit: CnbCommitWire? = null,
    val commits: List<CnbCommitWire> = emptyList(),
    val files: List<CnbCommitDiffFileWire> = emptyList(),
    @SerialName("total_commits")
    val totalCommits: Int = 0,
)

@Serializable
internal data class CnbRepositoryPathWire(
    val path: String,
)

@Serializable
internal data class CnbPullRefWire(
    val ref: String,
    val sha: String,
    val repo: CnbRepositoryPathWire? = null,
)

@Serializable
internal data class CnbPullReviewerWire(
    @SerialName("review_state")
    val reviewState: String = "",
    val user: CnbUserInfoWire? = null,
    val username: String = "",
    val nickname: String = "",
    val email: String = "",
    val avatar: String = "",
    val freeze: Boolean = false,
    @SerialName("is_npc")
    val isNpc: Boolean = false,
)

@Serializable
internal data class CnbPullRequestWire(
    @Serializable(with = CnbStringOrNumberSerializer::class)
    val number: String,
    val title: String,
    val state: String,
    val head: CnbPullRefWire,
    val base: CnbPullRefWire,
    @SerialName("merge_sha")
    val mergeSha: String? = null,
    @SerialName("merge_commit_sha")
    val mergeCommitSha: String? = null,
    val author: CnbUserInfoWire? = null,
    val user: CnbUserInfoWire? = null,
    @SerialName("is_wip")
    val isWip: Boolean = false,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("last_acted_at")
    val lastActedAt: String? = null,
    val body: String = "",
    @SerialName("blocked_on")
    val blockedOn: String = "",
    @SerialName("mergeable_state")
    val mergeableState: String = "",
    val labels: List<CnbLabelWire> = emptyList(),
    val assignees: List<CnbUserInfoWire> = emptyList(),
    val reviewers: List<CnbPullReviewerWire> = emptyList(),
    @SerialName("merged_by")
    val mergedBy: CnbUserInfoWire? = null,
    @SerialName("created_at")
    val createdAt: String = "",
)

@Serializable
internal data class CnbLabelWire(
    @Serializable(with = CnbStringOrNumberSerializer::class)
    val id: String,
    val name: String,
    val color: String = "",
    val description: String = "",
)

@Serializable
internal data class CnbPullFileWire(
    val filename: String,
    val status: String = "",
    val sha: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String = "",
    @SerialName("blob_url")
    val blobUrl: String = "",
    @SerialName("raw_url")
    val rawUrl: String = "",
    @SerialName("contents_url")
    val contentsUrl: String = "",
)

@Serializable
internal data class CnbCommitStatusWire(
    val context: String,
    val state: String,
    val description: String = "",
    @SerialName("target_url")
    val targetUrl: String = "",
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
)

@Serializable
internal data class CnbCommitStatusesWire(
    val sha: String,
    val state: String,
    val statuses: List<CnbCommitStatusWire>,
)

@Serializable
internal data class CnbPullReviewWire(
    @Serializable(with = CnbStringOrNumberSerializer::class)
    val id: String,
    val body: String = "",
    val state: String = "",
    val author: CnbUserInfoWire? = null,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
)

@Serializable
internal data class CnbPullReviewDiffLineWire(
    val content: String = "",
    @SerialName("left_line_number")
    val leftLineNumber: Int = 0,
    val prefix: String = "",
    @SerialName("right_line_number")
    val rightLineNumber: Int = 0,
    val type: String = "",
)

@Serializable
internal data class CnbReactionWire(
    val count: Int = 0,
    @SerialName("has_reacted")
    val hasReacted: Boolean = false,
    val reaction: String = "",
    @SerialName("top_users")
    val topUsers: List<CnbUserInfoWire> = emptyList(),
)

@Serializable
internal data class CnbPullReviewCommentWire(
    @Serializable(with = CnbStringOrNumberSerializer::class)
    val id: String,
    @SerialName("review_id")
    @Serializable(with = CnbNullableStringOrNumberSerializer::class)
    val reviewId: String? = null,
    val body: String = "",
    val author: CnbUserInfoWire? = null,
    @SerialName("commit_hash")
    val commitHash: String = "",
    val path: String = "",
    @SerialName("review_state")
    val reviewState: String = "",
    @SerialName("reply_to_comment_id")
    @Serializable(with = CnbNullableStringOrNumberSerializer::class)
    val replyToCommentId: String? = null,
    @SerialName("subject_type")
    val subjectType: String = "",
    @SerialName("start_line")
    val startLine: Int = 0,
    @SerialName("start_side")
    val startSide: String = "",
    @SerialName("end_line")
    val endLine: Int = 0,
    @SerialName("end_side")
    val endSide: String = "",
    @SerialName("diff_hunk")
    val diffHunk: List<CnbPullReviewDiffLineWire> = emptyList(),
    val reactions: List<CnbReactionWire> = emptyList(),
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
)

@Serializable
internal data class CnbMergePullResultWire(
    val merged: Boolean,
    val message: String = "",
    val sha: String = "",
)

@Serializable
internal data class CnbMemberAccessWire(
    val path: String,
    @SerialName("access_level")
    val accessLevel: String,
)

@Serializable
internal data class CnbContentEntryWire(
    val name: String,
    val path: String,
    val sha: String,
    val type: String,
    val size: Long = 0,
)

@Serializable
internal data class CnbContentWire(
    val path: String,
    val sha: String,
    val type: String,
    val size: Long = 0,
    val content: String? = null,
    val encoding: String? = null,
    val entries: List<CnbContentEntryWire> = emptyList(),
)

@Serializable
internal data class CnbAnnotationWire(
    val key: String,
    val value: String,
)

@Serializable
internal data class CnbTagAnnotationMetadataWire(
    val operator: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
    val platform: String = "",
)

@Serializable
internal data class CnbTagAnnotationWire(
    val key: String,
    val value: String,
    val meta: CnbTagAnnotationMetadataWire? = null,
)

@Serializable
internal data class CnbPullCommentWire(
    @Serializable(with = CnbStringOrNumberSerializer::class)
    val id: String,
    val body: String,
    val user: CnbUserInfoWire? = null,
    val author: CnbUserInfoWire? = null,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
)

@Serializable
internal data class CnbBuildResultWire(
    val sn: String,
    val success: Boolean,
    val buildLogUrl: String = "",
    val message: String = "",
)

@Serializable
internal data class CnbPipelineLabelWire(
    val key: String,
    @Serializable(with = CnbStringOrNumberListSerializer::class)
    val value: List<String> = emptyList(),
)

@Serializable
internal data class CnbBuildStageWire(
    val id: String,
    val name: String = "",
    val status: String = "",
    val duration: Long = 0,
    val startTime: Long = 0,
    val endTime: Long = 0,
    val error: String = "",
    val content: List<String> = emptyList(),
)

@Serializable
internal data class CnbPipelineStatusWire(
    val id: String,
    val name: String = "",
    val status: String = "",
    val duration: Long = 0,
    val metricCoreHours: Double = 0.0,
    val metricDuration: Double = 0.0,
    val labels: List<CnbPipelineLabelWire> = emptyList(),
    val stages: List<CnbBuildStageWire> = emptyList(),
)

@Serializable
internal data class CnbBuildStatusWire(
    val status: String,
    val pipelinesStatus: Map<String, CnbPipelineStatusWire> = emptyMap(),
)

@Serializable
internal data class CnbBuildPipelineWire(
    val id: String,
    val status: String = "",
    val createTime: String = "",
    val duration: Long = 0,
    val labels: String = "",
)

@Serializable
internal data class CnbBuildInfoWire(
    val sn: String,
    val sha: String = "",
    val slug: String = "",
    val status: String = "",
    val event: String = "",
    val sourceRef: String = "",
    val sourceSlug: String = "",
    val targetRef: String = "",
    val title: String = "",
    val commitTitle: String = "",
    val buildLogUrl: String = "",
    val eventUrl: String = "",
    val createTime: String = "",
    val duration: Long = 0,
    val labels: String = "",
    val groupName: String = "",
    val userName: String = "",
    val nickName: String = "",
    val freeze: Boolean = false,
    val pipelineFailCount: Int = 0,
    val pipelineSuccessCount: Int = 0,
    val pipelineTotalCount: Int = 0,
    val pipelines: List<CnbBuildPipelineWire> = emptyList(),
)

@Serializable
internal data class CnbBuildHistoryWire(
    val total: Long,
    val timestamp: Long,
    val data: List<CnbBuildInfoWire>,
)

@Serializable
internal data class CnbRepositoryEventRepositoryWire(
    val path: String,
)

@Serializable
internal data class CnbRepositoryEventPayloadWire(
    val ref: String = "",
    val head: String = "",
    @SerialName("ref_type")
    val refType: String = "",
)

@Serializable
internal data class CnbRepositoryEventWire(
    @Serializable(with = CnbStringOrNumberSerializer::class)
    val id: String,
    val type: String,
    @SerialName("created_at")
    val createdAt: String = "",
    val repo: CnbRepositoryEventRepositoryWire? = null,
    val payload: CnbRepositoryEventPayloadWire = CnbRepositoryEventPayloadWire(),
)

@Serializable
internal data class CnbRepositoryEventsEnvelopeWire(
    val events: List<CnbRepositoryEventWire>,
)

@Serializable
internal data class CnbItemsEnvelopeWire<T>(
    val items: List<T>,
)

@Serializable
internal data class CnbErrorWire(
    @Serializable(with = CnbNullableStringOrNumberSerializer::class)
    val errcode: String? = null,
    val errmsg: String? = null,
)

@Serializable
internal data class CnbPullLabelsRequestWire(
    val labels: List<String>,
)

@Serializable
internal data class CnbCreatePullRequestWire(
    val base: String,
    val body: String,
    val head: String,
    @SerialName("head_repo")
    val headRepo: String? = null,
    val title: String,
)

/** CNB returns only an acknowledgement shape from POST /pulls; the full PR must be fetched by number. */
@Serializable
internal data class CnbCreatedPullRequestWire(
    @Serializable(with = CnbStringOrNumberSerializer::class)
    val number: String,
)

@Serializable
internal data class CnbUpdatePullRequestWire(
    val body: String? = null,
    val state: String? = null,
    val title: String? = null,
)

@Serializable
internal data class CnbPullAssigneesRequestWire(
    val assignees: List<String>,
)

@Serializable
internal data class CnbPullReviewersRequestWire(
    val reviewers: List<String>,
)

@Serializable
internal data class CnbPullReviewReplyRequestWire(
    val body: String,
    @SerialName("reply_to_comment_id")
    val replyToCommentId: String,
)

@Serializable
internal data class CnbPullReviewCommentRequestWire(
    val body: String,
    val path: String,
    @SerialName("subject_type")
    val subjectType: String,
    @SerialName("start_line")
    val startLine: Int? = null,
    @SerialName("start_side")
    val startSide: String? = null,
    @SerialName("end_line")
    val endLine: Int? = null,
    @SerialName("end_side")
    val endSide: String? = null,
)

@Serializable
internal data class CnbPullReviewRequestWire(
    val event: String,
    val body: String = "",
    val comments: List<CnbPullReviewCommentRequestWire> = emptyList(),
)

@Serializable
internal data class CnbMergePullRequestWire(
    @SerialName("merge_style")
    val mergeStyle: String,
    @SerialName("commit_title")
    val commitTitle: String? = null,
    @SerialName("commit_message")
    val commitMessage: String? = null,
)

@Serializable
internal data class CnbBuildNpcRequestWire(
    val name: String,
    val workMode: Boolean = false,
)

@Serializable
internal data class CnbBuildRequestWire(
    val event: String,
    val branch: String? = null,
    val tag: String? = null,
    val sha: String? = null,
    val title: String? = null,
    val config: String? = null,
    val sync: String,
    val env: Map<String, String> = emptyMap(),
    val npc: CnbBuildNpcRequestWire? = null,
)

@Serializable
internal data class CnbAnnotationMutationWire(
    val key: String,
    val value: String,
)

@Serializable
internal data class CnbAnnotationsRequestWire(
    val annotations: List<CnbAnnotationMutationWire>,
)

@Serializable
internal data class CnbCommentRequestWire(
    val body: String,
)

@Serializable
internal data class CnbReleaseUserWire(
    val username: String = "",
    val nickname: String = "",
    val email: String = "",
    val avatar: String = "",
    val freeze: Boolean = false,
    @SerialName("is_npc")
    val isNpc: Boolean = false,
)

@Serializable
internal data class CnbReleaseAssetWire(
    @Serializable(with = CnbStringOrNumberSerializer::class)
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    @SerialName("content_type")
    val contentType: String = "",
    @SerialName("download_count")
    val downloadCount: Long = 0,
    @SerialName("hash_algo")
    val hashAlgorithm: String = "",
    @SerialName("hash_value")
    val hashValue: String = "",
    @SerialName("browser_download_url")
    val browserDownloadUrl: String = "",
    @SerialName("brower_download_url")
    val legacyBrowserDownloadUrl: String = "",
    val url: String = "",
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
    val uploader: CnbReleaseUserWire? = null,
)

@Serializable
internal data class CnbReleaseWire(
    @Serializable(with = CnbStringOrNumberSerializer::class)
    val id: String,
    @SerialName("tag_name")
    val tagName: String,
    val name: String = "",
    val body: String = "",
    @SerialName("tag_commitish")
    val tagCommitish: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("is_latest")
    val isLatest: Boolean = false,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
    @SerialName("published_at")
    val publishedAt: String? = null,
    val author: CnbReleaseUserWire? = null,
    val assets: List<CnbReleaseAssetWire>? = emptyList(),
)

@Serializable
internal data class CnbCreateReleaseRequestWire(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("target_commitish")
    val targetCommitish: String,
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("make_latest")
    val makeLatest: String,
)

@Serializable
internal data class CnbUpdateReleaseRequestWire(
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean? = null,
    val prerelease: Boolean? = null,
    @SerialName("make_latest")
    val makeLatest: String? = null,
)

@Serializable
internal data class CnbReleaseAssetUploadRequestWire(
    @SerialName("asset_name")
    val assetName: String,
    val overwrite: Boolean,
    val size: Long,
    val ttl: Int,
)

@Serializable
internal data class CnbReleaseAssetUploadTicketWire(
    @SerialName("expires_in_sec")
    val expiresInSeconds: Long,
    @SerialName("upload_url")
    val uploadUrl: String,
    @SerialName("verify_url")
    val verifyUrl: String,
)
