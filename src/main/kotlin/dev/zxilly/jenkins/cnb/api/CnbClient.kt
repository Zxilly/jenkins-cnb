package dev.zxilly.jenkins.cnb.api

import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbAuthenticatedUser
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbCommitAnnotation
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatus
import dev.zxilly.jenkins.cnb.api.model.CnbContent
import dev.zxilly.jenkins.cnb.api.model.CnbPullComment
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEvent
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import java.io.Closeable
import java.time.ZonedDateTime

/** Hard upper bound shared with current-hour persistent deduplication. */
internal const val MAX_REPOSITORY_EVENTS_PER_HOUR = 10_000

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

    fun listPullRequests(
        repo: String,
        state: String = "open",
    ): List<CnbPullRequest>

    fun getPullRequest(
        repo: String,
        number: String,
    ): CnbPullRequest

    fun getContent(
        repo: String,
        path: String,
        ref: String,
    ): CnbContent?

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

    fun listPullComments(
        repo: String,
        number: String,
    ): List<CnbPullComment>

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

    fun listRepositoryEvents(
        repo: String,
        hour: ZonedDateTime,
    ): List<CnbRepositoryEvent>

    override fun close() = Unit
}
