package dev.zxilly.jenkins.cnb.status

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.CnbClientFactory
import dev.zxilly.jenkins.cnb.api.model.CnbCommitAnnotation
import dev.zxilly.jenkins.cnb.api.model.CnbPullComment
import dev.zxilly.jenkins.cnb.api.model.CnbTagAnnotation
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbStatusReportingMode
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import hudson.model.Item
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class CnbBuildMetadataReportResult(
    val commentId: String?,
)

/** Writes CNB commit annotations and one durable PR comment. Native commit statuses are not used. */
internal object CnbBuildMetadataReporter {
    private const val CONTEXT_SLUG_LENGTH = 72
    private val commentLocks = Array(64) { ReentrantLock() }

    fun report(
        snapshot: CnbBuildMetadataSnapshot,
        item: Item?,
    ): CnbBuildMetadataReportResult {
        val target = snapshot.target
        val server = CnbGlobalConfiguration.get().findServer(target.serverId)
        val mode = server.statusReportingMode ?: CnbStatusReportingMode.BOTH
        if (mode == CnbStatusReportingMode.DISABLED) {
            return CnbBuildMetadataReportResult(snapshot.knownCommentId)
        }
        val credentialsId =
            target.credentialsId?.takeIf(String::isNotBlank)
                ?: server.reportingCredentialsId?.takeIf(String::isNotBlank)
                ?: server.credentialsId?.takeIf(String::isNotBlank)

        CnbClientFactory.create(target.serverId, credentialsId, item).use { client ->
            return reportWithClient(snapshot, client, mode)
        }
    }

    /**
     * Executes one idempotent reporting attempt against an already-scoped client. Keeping the
     * transport lifetime in [report] and the remote reconciliation here makes every capability
     * and partial-failure combination directly testable without replacing Jenkins global state.
     */
    internal fun reportWithClient(
        snapshot: CnbBuildMetadataSnapshot,
        client: CnbClient,
        mode: CnbStatusReportingMode,
    ): CnbBuildMetadataReportResult {
        var commentId = snapshot.knownCommentId
        val failures = mutableListOf<Exception>()
        val supportsTargetAnnotations =
            if (snapshot.target.tag == null) {
                client.capabilities.supportsCommitAnnotations
            } else {
                client.capabilities.supportsTagAnnotations
            }
        if (mode.reportsAnnotations() && supportsTargetAnnotations) {
            try {
                updateAnnotations(client, snapshot)
            } catch (failure: Exception) {
                failures += failure
            }
        }
        if (
            mode.reportsPullComments() &&
            client.capabilities.supportsPullComments &&
            !snapshot.target.pullRequestNumber.isNullOrBlank()
        ) {
            try {
                commentId = updatePullRequestComment(client, snapshot)
            } catch (failure: Exception) {
                failures += failure
            }
        }
        failures
            .firstOrNull { it is dev.zxilly.jenkins.cnb.api.CnbApiException && it.retryable }
            ?.let { throw it }
        failures.firstOrNull()?.let { throw it }
        return CnbBuildMetadataReportResult(commentId)
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
    )
    private fun updateAnnotations(
        client: CnbClient,
        snapshot: CnbBuildMetadataSnapshot,
    ) {
        val target = snapshot.target
        val tag = target.tag
        val prefix = annotationPrefix(target.context)
        // CNB's PUT contract merges by key. Sending only our namespaced keys avoids a
        // read-modify-write race and cannot overwrite annotations owned by another producer.
        val entries = annotationEntries(prefix, snapshot)
        if (tag == null) {
            client.putCommitAnnotations(
                target.commitRepository,
                target.sha,
                entries.map { (key, value) -> CnbCommitAnnotation(key, value) },
            )
        } else {
            client.putTagAnnotations(
                target.repository,
                tag,
                entries.map { (key, value) -> CnbTagAnnotation(key, value) },
            )
        }
    }

    private fun annotationEntries(
        prefix: String,
        snapshot: CnbBuildMetadataSnapshot,
    ): List<Pair<String, String>> =
        listOf(
            "${prefix}state" to snapshot.state.wireName,
            "${prefix}url" to snapshot.buildUrl.take(2_000),
            "${prefix}build" to snapshot.buildDisplayName.take(500),
            "${prefix}context" to snapshot.target.context.take(500),
            "${prefix}updated_at" to snapshot.stateChangedAt,
            "${prefix}kind" to "jenkins_build_metadata",
        )

    private fun updatePullRequestComment(
        client: CnbClient,
        snapshot: CnbBuildMetadataSnapshot,
    ): String {
        val target = snapshot.target
        val pullRequestNumber = requireNotNull(target.pullRequestNumber)
        val marker = "<!-- jenkins-cnb:v1:${snapshot.markerToken} -->"
        val body = pullRequestComment(marker, snapshot)
        return lockFor(commentLocks, "${target.serverId}:${target.repository}:$pullRequestNumber").withLock {
            // HttpCnbClient paginates this call. Listing before every write makes retries and
            // controller restarts converge even if a previous POST succeeded but its response was lost.
            val comments = client.listPullComments(target.repository, pullRequestNumber)
            val selected = selectPullComment(comments, marker, snapshot.knownCommentId)
            when {
                selected == null -> {
                    client.createPullComment(target.repository, pullRequestNumber, body).id
                }

                selected.body == body -> {
                    selected.id
                }

                else -> {
                    client.updatePullComment(target.repository, pullRequestNumber, selected.id, body).id
                }
            }
        }
    }

    internal fun selectPullComment(
        comments: List<CnbPullComment>,
        marker: String,
        knownCommentId: String?,
    ): CnbPullComment? {
        var earliest: CnbPullComment? = null
        for (comment in comments) {
            if (!comment.body.contains(marker)) continue
            if (comment.id == knownCommentId) return comment
            if (earliest == null || comparePullComments(comment, earliest) < 0) {
                earliest = comment
            }
        }
        return earliest
    }

    private fun comparePullComments(
        left: CnbPullComment,
        right: CnbPullComment,
    ): Int {
        val createdAt = left.createdAt.ifBlank { "\uffff" }.compareTo(right.createdAt.ifBlank { "\uffff" })
        if (createdAt != 0) return createdAt
        val numericId =
            (left.id.toLongOrNull() ?: Long.MAX_VALUE).compareTo(right.id.toLongOrNull() ?: Long.MAX_VALUE)
        return if (numericId != 0) numericId else left.id.compareTo(right.id)
    }

    private fun pullRequestComment(
        marker: String,
        snapshot: CnbBuildMetadataSnapshot,
    ): String {
        val state = snapshot.state.wireName.replace('_', ' ')
        val build = markdown(snapshot.buildDisplayName)
        val context = markdown(snapshot.target.context)
        val sha = markdown(snapshot.target.sha.take(12))
        val updated = markdown(snapshot.stateChangedAt)
        val buildReference =
            if (snapshot.buildUrl.startsWith("https://") || snapshot.buildUrl.startsWith("http://")) {
                "[$build](${markdownUrl(snapshot.buildUrl)})"
            } else {
                build
            }
        return """
            $marker
            ### Jenkins build metadata

            > This is Jenkins lifecycle metadata backed by CNB commit annotations; it is **not** a native CNB commit status.

            | Context | State | Build | Commit | Updated |
            | --- | --- | --- | --- | --- |
            | `$context` | **${markdown(state)}** | $buildReference | `$sha` | `$updated` |
            """.trimIndent()
    }

    private fun annotationPrefix(context: String): String {
        val normalized =
            context
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9_-]+"), "-")
                .trim('-', '_')
                .take(CONTEXT_SLUG_LENGTH)
                .ifBlank { "default" }
        return "jenkins_$normalized-${sha256(context).take(12)}_"
    }

    private fun lockFor(
        locks: Array<ReentrantLock>,
        key: String,
    ): ReentrantLock = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]

    private fun markdown(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\\", "\\\\")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("|", "\\|")
            .replace("`", "&#96;")
            .replace("\r", " ")
            .replace("\n", " ")

    private fun markdownUrl(value: String): String =
        value
            .replace("\\", "%5C")
            .replace("(", "%28")
            .replace(")", "%29")
            .replace(" ", "%20")
            .replace("\r", "%0D")
            .replace("\n", "%0A")
            .replace("<", "%3C")
            .replace(">", "%3E")
            .replace("\"", "%22")

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun CnbStatusReportingMode.reportsAnnotations(): Boolean =
        this == CnbStatusReportingMode.COMMIT_ANNOTATION || this == CnbStatusReportingMode.BOTH

    private fun CnbStatusReportingMode.reportsPullComments(): Boolean =
        this == CnbStatusReportingMode.PULL_REQUEST_COMMENT || this == CnbStatusReportingMode.BOTH
}
