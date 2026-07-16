package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbCommit
import dev.zxilly.jenkins.cnb.api.model.CnbLabel
import dev.zxilly.jenkins.cnb.api.model.CnbMemberAccess
import dev.zxilly.jenkins.cnb.api.model.CnbPullComment
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import dev.zxilly.jenkins.cnb.scm.CnbRepositoryRole
import dev.zxilly.jenkins.cnb.security.CnbGitObjectId
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent

internal data class CnbLiveDeliveryRequirements(
    val labels: Boolean = false,
    val comment: Boolean = false,
    val commitMessage: Boolean = false,
)

/** One immutable CNB API snapshot shared by every Classic policy for a delivery. */
internal data class CnbLiveDeliverySnapshot(
    val revisionMatches: Boolean,
    val pullRequest: CnbPullRequest? = null,
    val labels: Set<String>? = null,
    val commentVerified: Boolean = false,
    val commentBody: String? = null,
    val actorAccessLevels: Set<CnbRepositoryRole>? = null,
    val commitMessage: String? = null,
)

internal object CnbLiveDeliveryResolver {
    fun resolve(
        delivery: CnbWebhookDelivery,
        requirements: CnbLiveDeliveryRequirements,
        getBranch: (String, String) -> CnbBranch,
        getTag: (String, String) -> CnbTag,
        getPullRequest: (String, String) -> CnbPullRequest,
        listLabels: (String, String) -> List<CnbLabel>,
        getComment: (String, String, String) -> CnbPullComment,
        listMemberAccess: (String, String) -> List<CnbMemberAccess>,
        getCommit: (String, String) -> CnbCommit = { _, _ ->
            throw CnbApiException("CNB commit is unavailable", statusCode = 404)
        },
    ): CnbLiveDeliverySnapshot {
        var livePullRequest: CnbPullRequest? = null
        val revisionMatches =
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch,
                getTag,
                getPullRequest = { repository, number ->
                    livePullRequest ?: getPullRequest(repository, number).also { livePullRequest = it }
                },
            )
        if (!revisionMatches) return CnbLiveDeliverySnapshot(false, livePullRequest)
        val advertised = delivery.payload.pullRequest
        val current = livePullRequest
        val commitMessage =
            if (requirements.commitMessage) {
                resolveCommitMessage(delivery, current, getCommit)
            } else {
                null
            }
        if (advertised == null || current == null) {
            return CnbLiveDeliverySnapshot(true, commitMessage = commitMessage)
        }

        val labels =
            if (requirements.labels) {
                failClosedLookup {
                    val names = linkedSetOf<String>()
                    for (label in listLabels(delivery.payload.repository.slug, advertised.number)) names += label.name
                    names
                }
            } else {
                null
            }
        if (!requirements.comment || delivery.payload.event != CnbWebhookEvent.PULL_REQUEST_COMMENT) {
            return CnbLiveDeliverySnapshot(true, current, labels, commitMessage = commitMessage)
        }

        val actor = delivery.payload.actor.username
        val commentId = advertised.commentId
        if (actor.isBlank() || commentId.isBlank()) {
            return CnbLiveDeliverySnapshot(true, current, labels, commitMessage = commitMessage)
        }
        val comment =
            failClosedLookup {
                getComment(delivery.payload.repository.slug, advertised.number, commentId)
            } ?: return CnbLiveDeliverySnapshot(true, current, labels, commitMessage = commitMessage)
        val commentVerified =
            comment.id == commentId &&
                comment.body == advertised.commentBody &&
                comment.author.isNotBlank() &&
                comment.author == actor
        if (!commentVerified) return CnbLiveDeliverySnapshot(true, current, labels, commitMessage = commitMessage)

        val accessLevels =
            failClosedLookup {
                val roles = linkedSetOf<CnbRepositoryRole>()
                for (membership in listMemberAccess(delivery.payload.repository.slug, actor)) {
                    roles += CnbRepositoryRole.parse(membership.accessLevel.wireValue)
                }
                roles
            }
        return CnbLiveDeliverySnapshot(
            revisionMatches = true,
            pullRequest = current,
            labels = labels,
            commentVerified = true,
            commentBody = comment.body,
            actorAccessLevels = accessLevels,
            commitMessage = commitMessage,
        )
    }

    private fun resolveCommitMessage(
        delivery: CnbWebhookDelivery,
        pullRequest: CnbPullRequest?,
        getCommit: (String, String) -> CnbCommit,
    ): String? {
        val target =
            if (delivery.payload.event.pullRequestEvent) {
                pullRequest?.let { it.sourceRepo to it.sourceSha }
            } else {
                delivery.payload.ref.commit
                    .ifEmpty { delivery.payload.ref.sha }
                    .takeIf(CnbGitObjectId::isPresent)
                    ?.let { delivery.payload.repository.slug to it }
            } ?: return ""
        return failClosedLookup {
            getCommit(target.first, target.second)
                .takeIf { commit -> commit.sha.equals(target.second, ignoreCase = true) }
                ?.message
        }
    }

    private inline fun <T> failClosedLookup(block: () -> T): T? =
        try {
            block()
        } catch (failure: CnbApiException) {
            if (failure.statusCode !in missingOrUnauthorizedStatusCodes) throw failure
            null
        }

    private val missingOrUnauthorizedStatusCodes = setOf(401, 403, 404)
}
