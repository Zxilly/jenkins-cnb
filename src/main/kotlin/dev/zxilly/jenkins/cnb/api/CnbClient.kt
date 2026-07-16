package dev.zxilly.jenkins.cnb.api

import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbAuthenticatedUser
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbBuildHistory
import dev.zxilly.jenkins.cnb.api.model.CnbBuildHistoryQuery
import dev.zxilly.jenkins.cnb.api.model.CnbBuildRequest
import dev.zxilly.jenkins.cnb.api.model.CnbBuildResult
import dev.zxilly.jenkins.cnb.api.model.CnbBuildRunnerLogDownload
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStage
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStatus
import dev.zxilly.jenkins.cnb.api.model.CnbCommit
import dev.zxilly.jenkins.cnb.api.model.CnbCommitAnnotation
import dev.zxilly.jenkins.cnb.api.model.CnbCommitComparison
import dev.zxilly.jenkins.cnb.api.model.CnbCommitQuery
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatus
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatuses
import dev.zxilly.jenkins.cnb.api.model.CnbContent
import dev.zxilly.jenkins.cnb.api.model.CnbCreatePullRequestRequest
import dev.zxilly.jenkins.cnb.api.model.CnbCreateReleaseRequest
import dev.zxilly.jenkins.cnb.api.model.CnbLabel
import dev.zxilly.jenkins.cnb.api.model.CnbMemberAccess
import dev.zxilly.jenkins.cnb.api.model.CnbMergePullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbMergePullResult
import dev.zxilly.jenkins.cnb.api.model.CnbPullComment
import dev.zxilly.jenkins.cnb.api.model.CnbPullFile
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestListState
import dev.zxilly.jenkins.cnb.api.model.CnbPullReview
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewCommentInfo
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewReplyRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewRequest
import dev.zxilly.jenkins.cnb.api.model.CnbRawContent
import dev.zxilly.jenkins.cnb.api.model.CnbRelease
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAsset
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAssetDownload
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAssetHead
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAssetUploadRequest
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEvent
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import dev.zxilly.jenkins.cnb.api.model.CnbTagAnnotation
import dev.zxilly.jenkins.cnb.api.model.CnbUpdatePullRequestRequest
import dev.zxilly.jenkins.cnb.api.model.CnbUpdateReleaseRequest
import dev.zxilly.jenkins.cnb.api.model.CnbUser
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.time.ZonedDateTime

/** Hard upper bound shared with current-hour persistent deduplication. */
internal const val MAX_REPOSITORY_EVENTS_PER_HOUR = 10_000

/** Opens a fresh stream for each transport attempt. The caller retains ownership of source data. */
fun interface CnbRepeatableInput {
    fun openStream(): InputStream
}

/**
 * Opens a fresh, empty sink for each transport attempt.
 *
 * A failed or length-mismatched download may leave a partial sink. Implementations should write to
 * a temporary target and publish it atomically only after the corresponding client download method
 * returns successfully.
 */
fun interface CnbDownloadTarget {
    fun openStream(): OutputStream
}

interface CnbClient : Closeable {
    val capabilities: CnbApiCapabilities

    fun testConnection(): CnbAuthenticatedUser

    fun getRepository(path: String): CnbRepository

    fun listRepositories(
        namespace: String,
        includeDescendants: Boolean = true,
    ): List<CnbRepository>

    fun listUserRepositories(): List<CnbRepository>

    fun listBranches(repo: String): List<CnbBranch>

    fun getBranch(
        repo: String,
        name: String,
    ): CnbBranch

    fun listTags(repo: String): List<CnbTag>

    fun getTag(
        repo: String,
        name: String,
    ): CnbTag

    fun getCommit(
        repo: String,
        ref: String,
    ): CnbCommit

    fun listCommits(
        repo: String,
        query: CnbCommitQuery = CnbCommitQuery(),
    ): List<CnbCommit>

    fun compareCommits(
        repo: String,
        base: String,
        head: String,
    ): CnbCommitComparison

    fun listPullRequests(
        repo: String,
        state: CnbPullRequestListState = CnbPullRequestListState.OPEN,
    ): List<CnbPullRequest>

    fun getPullRequest(
        repo: String,
        number: String,
    ): CnbPullRequest

    fun listPullRequestsByNumbers(
        repo: String,
        numbers: List<String>,
    ): List<CnbPullRequest>

    fun createPullRequest(
        repo: String,
        request: CnbCreatePullRequestRequest,
    ): CnbPullRequest

    fun updatePullRequest(
        repo: String,
        number: String,
        request: CnbUpdatePullRequestRequest,
    ): CnbPullRequest

    fun listPullAssignees(
        repo: String,
        number: String,
    ): List<CnbUser>

    fun addPullAssignees(
        repo: String,
        number: String,
        assignees: List<String>,
    ): CnbPullRequest

    fun removePullAssignees(
        repo: String,
        number: String,
        assignees: List<String>,
    ): CnbPullRequest

    fun addPullReviewers(
        repo: String,
        number: String,
        reviewers: List<String>,
    ): CnbPullRequest

    fun removePullReviewers(
        repo: String,
        number: String,
        reviewers: List<String>,
    ): CnbPullRequest

    fun listPullLabels(
        repo: String,
        number: String,
    ): List<CnbLabel>

    fun addPullLabel(
        repo: String,
        number: String,
        label: String,
    ): CnbLabel

    fun replacePullLabels(
        repo: String,
        number: String,
        labels: List<String>,
    ): CnbLabel

    fun removePullLabel(
        repo: String,
        number: String,
        label: String,
    ): CnbLabel

    fun clearPullLabels(
        repo: String,
        number: String,
    )

    fun listPullCommits(
        repo: String,
        number: String,
    ): List<CnbCommit>

    fun listPullFiles(
        repo: String,
        number: String,
    ): List<CnbPullFile>

    fun listPullCommitStatuses(
        repo: String,
        number: String,
    ): CnbCommitStatuses

    fun listPullReviews(
        repo: String,
        number: String,
    ): List<CnbPullReview>

    fun createPullReview(
        repo: String,
        number: String,
        request: CnbPullReviewRequest,
    )

    fun listPullReviewComments(
        repo: String,
        number: String,
        reviewId: String,
    ): List<CnbPullReviewCommentInfo>

    fun replyToPullReviewComment(
        repo: String,
        number: String,
        reviewId: String,
        request: CnbPullReviewReplyRequest,
    ): CnbPullReviewCommentInfo

    fun mergePullRequest(
        repo: String,
        number: String,
        request: CnbMergePullRequest,
    ): CnbMergePullResult

    fun startBuild(
        repo: String,
        request: CnbBuildRequest,
    ): CnbBuildResult

    fun getBuildStatus(
        repo: String,
        sn: String,
    ): CnbBuildStatus

    fun getBuildStage(
        repo: String,
        sn: String,
        pipelineId: String,
        stageId: String,
    ): CnbBuildStage

    fun downloadBuildRunnerLog(
        repo: String,
        pipelineId: String,
        target: CnbDownloadTarget,
        maxBytes: Long = 512L * 1024 * 1024,
    ): CnbBuildRunnerLogDownload

    fun stopBuild(
        repo: String,
        sn: String,
    ): CnbBuildResult

    fun listBuildHistory(
        repo: String,
        query: CnbBuildHistoryQuery = CnbBuildHistoryQuery(),
    ): CnbBuildHistory

    fun listMemberAccessLevels(
        repo: String,
        username: String,
    ): List<CnbMemberAccess>

    fun getContent(
        repo: String,
        path: String,
        ref: String,
    ): CnbContent?

    fun getRawContent(
        repo: String,
        ref: String,
        path: String,
        maxBytes: Int = 1024 * 1024,
    ): CnbRawContent?

    fun listCommitStatuses(
        repo: String,
        commitish: String,
    ): List<CnbCommitStatus>

    fun getCommitAnnotations(
        repo: String,
        sha: String,
    ): List<CnbCommitAnnotation>

    fun putCommitAnnotations(
        repo: String,
        sha: String,
        annotations: List<CnbCommitAnnotation>,
    )

    fun deleteCommitAnnotation(
        repo: String,
        sha: String,
        key: String,
    )

    fun getTagAnnotations(
        repo: String,
        tag: String,
    ): List<CnbTagAnnotation>

    fun putTagAnnotations(
        repo: String,
        tag: String,
        annotations: List<CnbTagAnnotation>,
    )

    fun deleteTagAnnotation(
        repo: String,
        tag: String,
        key: String,
    )

    fun listPullComments(
        repo: String,
        number: String,
    ): List<CnbPullComment>

    fun getPullComment(
        repo: String,
        number: String,
        commentId: String,
    ): CnbPullComment

    fun createPullComment(
        repo: String,
        number: String,
        body: String,
    ): CnbPullComment

    fun updatePullComment(
        repo: String,
        number: String,
        commentId: String,
        body: String,
    ): CnbPullComment

    fun listReleases(repo: String): List<CnbRelease>

    fun getLatestRelease(repo: String): CnbRelease?

    fun getRelease(
        repo: String,
        releaseId: String,
    ): CnbRelease

    fun getReleaseByTag(
        repo: String,
        tag: String,
    ): CnbRelease

    fun createRelease(
        repo: String,
        request: CnbCreateReleaseRequest,
    ): CnbRelease

    fun updateRelease(
        repo: String,
        releaseId: String,
        request: CnbUpdateReleaseRequest,
    )

    fun deleteRelease(
        repo: String,
        releaseId: String,
    )

    fun getReleaseAsset(
        repo: String,
        releaseId: String,
        assetId: String,
    ): CnbReleaseAsset

    fun downloadReleaseAsset(
        repo: String,
        tag: String,
        filename: String,
        target: CnbDownloadTarget,
        share: Boolean = false,
        maxBytes: Long = 512L * 1024 * 1024,
    ): CnbReleaseAssetDownload?

    fun headReleaseAsset(
        repo: String,
        tag: String,
        filename: String,
    ): CnbReleaseAssetHead

    fun deleteReleaseAsset(
        repo: String,
        releaseId: String,
        assetId: String,
    )

    /** Requests a CNB-signed URL, uploads without Bearer credentials, then confirms on CNB. */
    fun uploadReleaseAsset(
        repo: String,
        releaseId: String,
        request: CnbReleaseAssetUploadRequest,
        source: CnbRepeatableInput,
    )

    fun listRepositoryEvents(
        repo: String,
        hour: ZonedDateTime,
    ): List<CnbRepositoryEvent>

    override fun close() = Unit
}
