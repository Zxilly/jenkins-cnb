package dev.zxilly.jenkins.cnb.api.model

import java.io.Serializable

data class CnbRepository(
    val path: String,
    val name: String,
    val webUrl: String,
    val cloneUrl: String,
    val defaultBranch: String,
    val status: CnbRepositoryStatus,
    val visibility: CnbRepositoryVisibility,
    val id: String = path,
) : Serializable {
    val archived: Boolean
        get() = status == CnbRepositoryStatus.ARCHIVED

    val cloneable: Boolean
        get() =
            (status == CnbRepositoryStatus.OK || status == CnbRepositoryStatus.ARCHIVED) &&
                (visibility == CnbRepositoryVisibility.PRIVATE || visibility == CnbRepositoryVisibility.PUBLIC)
}

enum class CnbRepositoryStatus(
    val wireValue: Int,
) {
    OK(0),
    ARCHIVED(1),
    FORKING(2),
    UNKNOWN(-1),
}

enum class CnbRepositoryVisibility(
    val wireValue: String,
) {
    PRIVATE("Private"),
    PUBLIC("Public"),
    SECRET("Secret"),
    UNKNOWN("Unknown"),
}

data class CnbBranch(
    val name: String,
    val sha: String,
    val protected: Boolean = false,
    val locked: Boolean = false,
) : Serializable

data class CnbTag(
    val name: String,
    val sha: String,
    val timestamp: Long = 0,
) : Serializable

data class CnbCommitQuery(
    val sha: String? = null,
    val author: String? = null,
    val committer: String? = null,
    val since: String? = null,
    val until: String? = null,
) : Serializable

data class CnbCommitPerson(
    val username: String = "",
    val nickname: String = "",
    val name: String = "",
    val email: String = "",
    val date: String = "",
) : Serializable

data class CnbCommit(
    val sha: String,
    val message: String = "",
    val author: CnbCommitPerson = CnbCommitPerson(),
    val committer: CnbCommitPerson = CnbCommitPerson(),
    val parentShas: List<String> = arrayListOf(),
) : Serializable

data class CnbCommitDiffFile(
    val path: String,
    val name: String = path.substringAfterLast('/'),
    val previousFilename: String = "",
    val status: CnbCommitDiffStatus,
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String = "",
    val mode: CnbGitFileMode? = null,
    val previousMode: CnbGitFileMode? = null,
) : Serializable

enum class CnbCommitDiffStatus(
    val wireValue: String,
) {
    ADDED("added"),
    MODIFIED("modified"),
    DELETED("deleted"),
    RENAMED("renamed"),
    COPIED("copied"),
}

enum class CnbGitFileMode(
    val wireValue: String,
) {
    TREE("040000"),
    REGULAR("100644"),
    EXECUTABLE("100755"),
    SYMLINK("120000"),
    GITLINK("160000"),
}

data class CnbCommitComparison(
    val baseCommit: CnbCommit? = null,
    val headCommit: CnbCommit? = null,
    val mergeBaseCommit: CnbCommit? = null,
    val commits: List<CnbCommit> = arrayListOf(),
    val files: List<CnbCommitDiffFile> = arrayListOf(),
    val totalCommits: Int = commits.size,
) : Serializable

data class CnbPullFile(
    val filename: String,
    val status: CnbPullFileStatus,
    val sha: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String = "",
    val blobUrl: String = "",
    val rawUrl: String = "",
    val contentsUrl: String = "",
) : Serializable

enum class CnbPullFileStatus(
    val wireValue: String,
) {
    ADD("add"),
    MODIFY("modify"),
    DELETE("delete"),
    RENAME("rename"),
    COPY("copy"),
}

data class CnbPullRequest(
    val number: String,
    val title: String,
    val state: CnbPullRequestState,
    val sourceRepo: String,
    val sourceBranch: String,
    val sourceSha: String,
    val targetRepo: String,
    val targetBranch: String,
    val targetSha: String,
    val mergeSha: String? = null,
    val author: String = "",
    val fromFork: Boolean = sourceRepo != targetRepo,
    val draft: Boolean = false,
    val updatedAt: Long = 0,
    val body: String = "",
    val blockedOn: CnbPullBlockedReason? = null,
    val mergeableState: CnbPullMergeableState? = null,
    val labels: List<CnbLabel> = arrayListOf(),
    val assignees: List<CnbUser> = arrayListOf(),
    val reviewers: List<CnbPullReviewer> = arrayListOf(),
    val authorInfo: CnbUser? = null,
    val mergedBy: CnbUser? = null,
    val createdAt: String = "",
) : Serializable

data class CnbUser(
    val username: String,
    val nickname: String = "",
    val email: String = "",
    val avatarUrl: String = "",
    val frozen: Boolean = false,
    val npc: Boolean = false,
) : Serializable

data class CnbPullReviewer(
    val user: CnbUser,
    val reviewState: CnbPullReviewState? = null,
) : Serializable

enum class CnbPullRequestState(
    val wireValue: String,
) {
    OPEN("open"),
    CLOSED("closed"),
    MERGED("merged"),
}

enum class CnbPullRequestListState(
    val wireValue: String,
) {
    OPEN("open"),
    CLOSED("closed"),
    ALL("all"),
}

enum class CnbPullBlockedReason(
    val wireValue: String,
) {
    NO_MERGE_BASE("no_merge_base"),
    INTERNAL_ERROR("internal_error"),
    CODE_CONFLICT("code_conflict"),
    STATUS_CHECK("status_check"),
    WAITING_REVIEW("waiting_review"),
}

enum class CnbPullMergeableState(
    val wireValue: String,
) {
    CHECKING("checking"),
    MERGEABLE("mergeable"),
    MERGING("merging"),
    MERGED("merged"),
    CONFLICT("conflict"),
    NO_MERGE_BASE("no-merge-base"),
}

enum class CnbPullReviewState(
    val wireValue: String,
) {
    PENDING("pending"),
    COMMENTED("commented"),
    APPROVED("approved"),
    CHANGES_REQUESTED("changes_requested"),
    DISMISSED("dismissed"),
}

data class CnbCreatePullRequestRequest(
    val targetBranch: String,
    val sourceBranch: String,
    val title: String,
    val body: String = "",
    val sourceRepository: String? = null,
) : Serializable

data class CnbUpdatePullRequestRequest(
    val title: String? = null,
    val body: String? = null,
    val state: CnbPullRequestState? = null,
) : Serializable

data class CnbLabel(
    val id: String,
    val name: String,
    val color: String = "",
    val description: String = "",
) : Serializable

data class CnbPullReview(
    val id: String,
    val body: String = "",
    val state: CnbPullReviewState,
    val author: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
) : Serializable

data class CnbPullReviewComment(
    val body: String,
    val path: String,
    val subjectType: CnbPullReviewSubjectType = CnbPullReviewSubjectType.FILE,
    val startLine: Int? = null,
    val startSide: CnbPullReviewSide? = null,
    val endLine: Int? = null,
    val endSide: CnbPullReviewSide? = null,
) : Serializable

data class CnbPullReviewRequest(
    val event: CnbPullReviewEvent,
    val body: String = "",
    val comments: List<CnbPullReviewComment> = arrayListOf(),
) : Serializable

enum class CnbPullReviewEvent(
    val wireValue: String,
) {
    APPROVE("approve"),
    COMMENT("comment"),
    REQUEST_CHANGES("request_changes"),
    PENDING("pending"),
}

data class CnbPullReviewDiffLine(
    val content: String,
    val type: CnbPullReviewDiffLineType,
    val prefix: String = "",
    val leftLineNumber: Int? = null,
    val rightLineNumber: Int? = null,
) : Serializable

enum class CnbPullReviewDiffLineType(
    val wireValue: String,
) {
    CONTEXT("context"),
    ADDITION("addition"),
    DELETION("deletion"),
    CONTEXT_EOFNL("context_eofnl"),
    ADD_EOFNL("add_eofnl"),
    DELETE_EOFNL("del_eofnl"),
    FILE_HEADER("file_header"),
    HUNK_HEADER("hunk_header"),
    BINARY("binary"),
}

enum class CnbPullReviewSubjectType(
    val wireValue: String,
) {
    LINE("line"),
    FILE("file"),
}

enum class CnbPullReviewSide(
    val wireValue: String,
) {
    LEFT("left"),
    RIGHT("right"),
}

data class CnbReactionSummary(
    val reaction: String,
    val count: Int,
    val reactedByCurrentUser: Boolean = false,
    val topUsers: List<CnbUser> = arrayListOf(),
) : Serializable

data class CnbPullReviewCommentInfo(
    val id: String,
    val reviewId: String,
    val body: String,
    val author: CnbUser? = null,
    val commitSha: String = "",
    val path: String = "",
    val reviewState: CnbPullReviewState? = null,
    val replyToCommentId: String = "",
    val subjectType: CnbPullReviewSubjectType? = null,
    val startLine: Int? = null,
    val startSide: CnbPullReviewSide? = null,
    val endLine: Int? = null,
    val endSide: CnbPullReviewSide? = null,
    val diffHunk: List<CnbPullReviewDiffLine> = arrayListOf(),
    val reactions: List<CnbReactionSummary> = arrayListOf(),
    val createdAt: String = "",
    val updatedAt: String = "",
) : Serializable

data class CnbPullReviewReplyRequest(
    val body: String,
    val replyToCommentId: String,
) : Serializable

data class CnbMergePullRequest(
    val mergeStyle: CnbPullMergeStyle = CnbPullMergeStyle.MERGE,
    val commitTitle: String = "",
    val commitMessage: String = "",
) : Serializable

enum class CnbPullMergeStyle(
    val wireValue: String,
) {
    MERGE("merge"),
    SQUASH("squash"),
    REBASE("rebase"),
}

data class CnbMergePullResult(
    val merged: Boolean,
    val message: String = "",
    val sha: String = "",
) : Serializable

data class CnbMemberAccess(
    val path: String,
    val accessLevel: CnbMemberAccessLevel,
) : Serializable

enum class CnbMemberAccessLevel(
    val wireValue: String,
) {
    GUEST("Guest"),
    REPORTER("Reporter"),
    DEVELOPER("Developer"),
    MASTER("Master"),
    OWNER("Owner"),
}

data class CnbContent(
    val path: String,
    val sha: String,
    val type: CnbContentType,
    val size: Long,
    val content: String? = null,
    val encoding: CnbContentEncoding? = null,
    val entries: List<CnbContentEntry> = arrayListOf(),
) : Serializable

data class CnbContentEntry(
    val name: String,
    val path: String,
    val sha: String,
    val type: CnbContentType,
    val size: Long = 0,
) : Serializable

enum class CnbContentType(
    val wireValue: String,
) {
    TREE("tree"),
    BLOB("blob"),
    LFS("lfs"),
    EMPTY("empty"),
    LINK("link"),
    SUBMODULE("submodule"),
}

enum class CnbContentEncoding(
    val wireValue: String,
) {
    BASE64("base64"),
}

data class CnbRawContent(
    val bytes: ByteArray,
    val contentType: String,
) : Serializable {
    val size: Int
        get() = bytes.size
}

data class CnbAuthenticatedUser(
    val username: String,
    val nickname: String = "",
    val email: String = "",
) : Serializable

data class CnbCommitStatus(
    val context: String,
    val state: CnbCommitStatusState,
    val description: String = "",
    val targetUrl: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
) : Serializable

data class CnbCommitStatuses(
    val sha: String,
    val state: CnbCommitStatusState,
    val statuses: List<CnbCommitStatus> = arrayListOf(),
) : Serializable

enum class CnbCommitStatusState(
    val wireValue: String,
) {
    PENDING("pending"),
    SUCCESS("success"),
    FAILURE("failure"),
    ERROR("error"),
    SKIP("skip"),
}

data class CnbCommitAnnotation(
    val key: String,
    val value: String,
) : Serializable

data class CnbTagAnnotation(
    val key: String,
    val value: String,
    val meta: CnbTagAnnotationMetadata = CnbTagAnnotationMetadata(),
) : Serializable

data class CnbTagAnnotationMetadata(
    val operator: String = "",
    val updatedAt: String = "",
    val platform: String = "",
) : Serializable

data class CnbBuildNpc(
    val name: CnbBuildNpcName,
    val workMode: Boolean = false,
) : Serializable

enum class CnbBuildNpcName(
    val wireValue: String,
) {
    CODE_BUDDY("CodeBuddy"),
}

data class CnbBuildRequest(
    val event: CnbBuildTriggerEvent = CnbBuildTriggerEvent.API_TRIGGER,
    val branch: String? = null,
    val tag: String? = null,
    val sha: String? = null,
    val title: String? = null,
    val config: String? = null,
    val sync: Boolean = false,
    val env: Map<String, String> = linkedMapOf(),
    val npc: CnbBuildNpc? = null,
) : Serializable

data class CnbBuildTriggerEvent(
    val wireValue: String,
) : Serializable {
    init {
        require(
            wireValue.length in 1..128 &&
                (wireValue == "api_trigger" || wireValue.startsWith("api_trigger_")) &&
                wireValue.none(Char::isWhitespace) &&
                wireValue.none { it.code < 0x20 || it.code == 0x7f },
        ) { "Invalid CNB build trigger event" }
    }

    companion object {
        val API_TRIGGER = CnbBuildTriggerEvent("api_trigger")
    }
}

data class CnbBuildEventName(
    val wireValue: String,
) : Serializable {
    init {
        require(
            wireValue.length in 1..128 &&
                wireValue.none(Char::isWhitespace) &&
                wireValue.none { it.code < 0x20 || it.code == 0x7f },
        ) { "Invalid CNB build event" }
    }
}

data class CnbBuildResult(
    val sn: String,
    val buildLogUrl: String = "",
    val message: String = "",
    val success: Boolean = false,
) : Serializable

data class CnbPipelineLabel(
    val key: String,
    val values: List<String> = arrayListOf(),
) : Serializable

data class CnbBuildStage(
    val id: String,
    val name: String = "",
    val status: CnbBuildStageStatus,
    val duration: Long = 0,
    val startTime: Long = 0,
    val endTime: Long = 0,
    val error: String = "",
    val content: List<String> = arrayListOf(),
) : Serializable

enum class CnbBuildStageStatus(
    val wireValue: String,
) {
    PENDING("pending"),
    START("start"),
    SUCCESS("success"),
    ERROR("error"),
    CANCEL("cancel"),
    SKIPPED("skipped"),
}

data class CnbBuildRunnerLogDownload(
    val contentLength: Long,
    val contentType: String = "",
    val etag: String = "",
) : Serializable

data class CnbPipelineStatus(
    val id: String,
    val name: String = "",
    val status: CnbBuildState,
    val duration: Long = 0,
    val metricCoreHours: Double = 0.0,
    val metricDuration: Double = 0.0,
    val labels: List<CnbPipelineLabel> = arrayListOf(),
    val stages: List<CnbBuildStage> = arrayListOf(),
) : Serializable

data class CnbBuildStatus(
    val status: CnbBuildState,
    val pipelinesStatus: Map<String, CnbPipelineStatus> = linkedMapOf(),
) : Serializable

data class CnbBuildHistoryQuery(
    val createTime: String? = null,
    val endTime: String? = null,
    val event: CnbBuildEventName? = null,
    val sha: String? = null,
    val sn: String? = null,
    val sourceRef: String? = null,
    val status: CnbBuildState? = null,
    val targetRef: String? = null,
    val userId: String? = null,
    val userName: String? = null,
) : Serializable

data class CnbBuildPipeline(
    val id: String,
    val status: CnbBuildState,
    val createTime: String = "",
    val duration: Long = 0,
    val labels: String = "",
) : Serializable

data class CnbBuildInfo(
    val sn: String,
    val sha: String = "",
    val slug: String = "",
    val status: CnbBuildState,
    val event: CnbBuildEventName? = null,
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
    val pipelines: List<CnbBuildPipeline> = arrayListOf(),
) : Serializable

enum class CnbBuildState(
    val wireValue: String,
) {
    PENDING("pending"),
    START("start"),
    RUNNING("running"),
    SUCCESS("success"),
    ERROR("error"),
    CANCEL("cancel"),
    SKIPPED("skipped"),
}

data class CnbBuildHistory(
    val total: Long,
    val timestamp: Long,
    val builds: List<CnbBuildInfo> = arrayListOf(),
) : Serializable

data class CnbPullComment(
    val id: String,
    val body: String,
    val author: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
) : Serializable

data class CnbReleaseUser(
    val username: String,
    val nickname: String = "",
    val email: String = "",
    val avatarUrl: String = "",
    val frozen: Boolean = false,
    val npc: Boolean = false,
) : Serializable

data class CnbReleaseAsset(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val contentType: String = "",
    val downloadCount: Long = 0,
    val hashAlgorithm: String = "",
    val hashValue: String = "",
    val browserDownloadUrl: String = "",
    val apiUrl: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val uploader: CnbReleaseUser? = null,
) : Serializable

data class CnbRelease(
    val id: String,
    val tagName: String,
    val name: String = "",
    val body: String = "",
    val tagCommitish: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val latest: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val publishedAt: String? = null,
    val author: CnbReleaseUser? = null,
    val assets: List<CnbReleaseAsset> = arrayListOf(),
) : Serializable

enum class CnbReleaseMakeLatest(
    val wireValue: String,
) {
    TRUE("true"),
    FALSE("false"),
    LEGACY("legacy"),
}

data class CnbCreateReleaseRequest(
    val tagName: String,
    val targetCommitish: String,
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val makeLatest: CnbReleaseMakeLatest = CnbReleaseMakeLatest.TRUE,
) : Serializable

data class CnbUpdateReleaseRequest(
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean? = null,
    val prerelease: Boolean? = null,
    val makeLatest: CnbReleaseMakeLatest? = null,
) : Serializable

data class CnbReleaseAssetUploadRequest(
    val assetName: String,
    val size: Long,
    val overwrite: Boolean = false,
    val ttlDays: Int = 0,
) : Serializable

data class CnbReleaseAssetDownload(
    val contentLength: Long,
    val contentType: String,
    val etag: String = "",
) : Serializable

data class CnbReleaseAssetHead(
    val exists: Boolean,
    val contentLength: Long? = null,
    val contentType: String = "",
    val etag: String = "",
    val lastModified: String = "",
) : Serializable

data class CnbRepositoryEvent(
    val id: String,
    val type: CnbRepositoryEventType,
    val repositoryPath: String,
    val createdAt: String,
    val payload: CnbRepositoryEventPayload = CnbRepositoryEventPayload(),
) : Serializable

data class CnbRepositoryEventPayload(
    val ref: String = "",
    val head: String = "",
    val refType: CnbRepositoryRefType? = null,
) : Serializable

data class CnbRepositoryEventType(
    val wireValue: String,
) : Serializable {
    init {
        require(
            wireValue.length in 1..128 &&
                wireValue.none(Char::isWhitespace) &&
                wireValue.none { it.code < 0x20 || it.code == 0x7f },
        ) { "Invalid CNB repository event type" }
    }
}

enum class CnbRepositoryRefType(
    val wireValue: String,
) {
    BRANCH("branch"),
    TAG("tag"),
    REPOSITORY("repository"),
}

data class CnbApiCapabilities(
    val supportsRepositoryEvents: Boolean = true,
    val supportsCommitAnnotations: Boolean = true,
    val supportsTagAnnotations: Boolean = true,
    val supportsPullComments: Boolean = true,
    val supportsCommitStatusWrite: Boolean = false,
    val supportsWebhookManagement: Boolean = false,
) : Serializable
