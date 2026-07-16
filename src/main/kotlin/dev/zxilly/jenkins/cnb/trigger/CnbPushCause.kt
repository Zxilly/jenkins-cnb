package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import hudson.model.Cause
import org.kohsuke.stapler.export.Exported
import org.kohsuke.stapler.export.ExportedBean
import java.util.LinkedHashMap

@ExportedBean
class CnbPushCause private constructor(
    @get:Exported val serverId: String,
    @get:Exported val deliveryId: String,
    @get:Exported val event: String,
    @get:Exported val repositoryPath: String,
    @get:Exported val ref: String,
    @get:Exported val actor: String,
    private val variables: Map<String, String>,
) : Cause() {
    override fun getShortDescription(): String =
        buildString {
            append("CNB ").append(event).append(" event for ").append(repositoryPath)
            if (ref.isNotEmpty()) append(" at ").append(ref)
            if (actor.isNotEmpty()) append(" by ").append(actor)
        }

    fun buildVariables(): Map<String, String> = LinkedHashMap(variables)

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_ENVIRONMENT_VALUE_LENGTH = 32 * 1024
        private val PULL_REQUEST_BUILD_EVENTS = setOf("pull_request", "pull_request.update", "pull_request.target")

        fun from(delivery: CnbWebhookDelivery): CnbPushCause {
            val payload = delivery.payload
            val pullRequest = payload.pullRequest
            val variables =
                linkedMapOf(
                    "CNB_SERVER_ID" to delivery.serverId,
                    "CNB_JENKINS_DELIVERY_ID" to payload.deliveryId,
                    "CNB_EVENT" to payload.event.wireName,
                    "CNB_EVENT_URL" to payload.eventUrl,
                    "CNB_WEB_ENDPOINT" to payload.instance.webUrl,
                    "CNB_API_ENDPOINT" to payload.instance.apiUrl,
                    "CNB_REPO_ID" to payload.repository.id,
                    "CNB_REPO_SLUG" to payload.repository.slug,
                    "CNB_REPO_SLUG_LOWERCASE" to payload.repository.slug.lowercase(),
                    "CNB_REPOSITORY" to payload.repository.slug,
                    "CNB_REPO_NAME" to payload.repository.name,
                    "CNB_REPO_NAME_LOWERCASE" to payload.repository.name.lowercase(),
                    "CNB_REPO_URL_HTTPS" to payload.repository.url,
                    "CNB_BRANCH" to payload.ref.name,
                    "CNB_BRANCH_SHA" to payload.ref.sha,
                    "CNB_BEFORE_SHA" to payload.ref.before,
                    "CNB_COMMIT" to payload.ref.commit,
                    "CNB_COMMIT_SHORT" to payload.ref.commit.take(8),
                    "CNB_NEW_COMMITS_COUNT" to payload.ref.newCommitsCount,
                    "CNB_IS_TAG" to payload.ref.tag.toString(),
                    "CNB_IS_NEW_BRANCH" to (payload.event.wireName == "branch.create").toString(),
                    "CNB_BUILD_ID" to payload.buildId,
                    "CNB_BUILD_USER_ID" to payload.actor.id,
                    "CNB_BUILD_USER" to payload.actor.username,
                    "CNB_BUILD_USER_NICKNAME" to payload.actor.nickname,
                    "CNB_BUILD_USER_EMAIL" to payload.actor.email,
                    "CNB_IS_RETRY" to payload.retry.toString(),
                    "CNB_PULL_REQUEST_LIKE" to (pullRequest != null).toString(),
                    "CNB_PULL_REQUEST" to (payload.event.wireName in PULL_REQUEST_BUILD_EVENTS).toString(),
                    "CNB_PULL_REQUEST_ID" to pullRequest?.id.orEmpty(),
                    "CNB_PULL_REQUEST_IID" to pullRequest?.number.orEmpty(),
                    "CNB_PULL_REQUEST_TITLE" to pullRequest?.title.orEmpty(),
                    "CNB_PULL_REQUEST_DESCRIPTION" to pullRequest?.description.orEmpty(),
                    "CNB_PULL_REQUEST_PROPOSER" to pullRequest?.proposer.orEmpty(),
                    "CNB_PULL_REQUEST_SLUG" to pullRequest?.sourceRepository.orEmpty(),
                    "CNB_PULL_REQUEST_SOURCE_REPOSITORY" to pullRequest?.sourceRepository.orEmpty(),
                    "CNB_PULL_REQUEST_BRANCH" to pullRequest?.sourceBranch.orEmpty(),
                    "CNB_PULL_REQUEST_SOURCE_BRANCH" to pullRequest?.sourceBranch.orEmpty(),
                    "CNB_PULL_REQUEST_SHA" to pullRequest?.sourceSha.orEmpty(),
                    "CNB_PULL_REQUEST_SOURCE_SHA" to pullRequest?.sourceSha.orEmpty(),
                    "CNB_PULL_REQUEST_TARGET_SHA" to pullRequest?.targetSha.orEmpty(),
                    "CNB_PULL_REQUEST_TARGET_BRANCH" to pullRequest?.targetBranch.orEmpty(),
                    "CNB_PULL_REQUEST_MERGE_SHA" to pullRequest?.mergeSha.orEmpty(),
                    "CNB_PULL_REQUEST_ACTION" to pullRequest?.action.orEmpty(),
                    "CNB_PULL_REQUEST_IS_WIP" to pullRequest?.wip?.toString().orEmpty(),
                    "CNB_PULL_REQUEST_REVIEWERS" to pullRequest?.reviewers.orEmpty(),
                    "CNB_PULL_REQUEST_REVIEW_STATE" to pullRequest?.reviewState.orEmpty(),
                    "CNB_REVIEW_REVIEWED_BY" to pullRequest?.reviewedBy.orEmpty(),
                    "CNB_REVIEW_LAST_REVIEWED_BY" to pullRequest?.lastReviewedBy.orEmpty(),
                    "CNB_COMMENT_ID" to pullRequest?.commentId.orEmpty(),
                    "CNB_COMMENT_BODY" to pullRequest?.commentBody.orEmpty(),
                    "CNB_COMMENT_TYPE" to pullRequest?.commentType.orEmpty(),
                    "CNB_COMMENT_FILE_PATH" to pullRequest?.commentFilePath.orEmpty(),
                    "CNB_COMMENT_RANGE" to pullRequest?.commentRange.orEmpty(),
                    "CNB_REVIEW_ID" to pullRequest?.reviewId.orEmpty(),
                    "CNB_REVIEW_DESCRIPTION" to pullRequest?.reviewDescription.orEmpty(),
                ).mapValues { (_, value) -> value.take(MAX_ENVIRONMENT_VALUE_LENGTH) }
            return CnbPushCause(
                serverId = delivery.serverId,
                deliveryId = payload.deliveryId,
                event = payload.event.wireName,
                repositoryPath = payload.repository.slug,
                ref = payload.ref.name,
                actor = payload.actor.username,
                variables = variables,
            )
        }
    }
}
