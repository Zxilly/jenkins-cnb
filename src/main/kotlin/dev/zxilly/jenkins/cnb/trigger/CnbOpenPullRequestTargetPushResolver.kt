package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestListState
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestState
import dev.zxilly.jenkins.cnb.security.CnbGitObjectId
import dev.zxilly.jenkins.cnb.security.CnbRepositoryPath
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPullRequest
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal data class CnbVerifiedOpenPullRequestPush(
    val delivery: CnbWebhookDelivery,
    val snapshot: CnbLiveDeliverySnapshot,
    val sourceCloneUrl: String,
)

/**
 * Converts one verified target branch push into immutable, live open-PR snapshots.
 *
 * All CNB reads finish before any caller may enqueue a build. List responses are only hints: each
 * matching pull request and both branch tips are read back independently to close update races.
 */
internal object CnbOpenPullRequestTargetPushResolver {
    fun resolve(
        delivery: CnbWebhookDelivery,
        requirements: CnbLiveDeliveryRequirements,
        client: CnbClient,
    ): List<CnbVerifiedOpenPullRequestPush> {
        val payload = delivery.payload
        if (payload.event !in TARGET_PUSH_EVENTS || payload.ref.tag || payload.pullRequest != null) return emptyList()
        val targetRepository = payload.repository.slug
        val targetBranch = payload.ref.name
        val advertisedTargetSha = payload.ref.commit.ifBlank { payload.ref.sha }
        if (!CnbRepositoryPath.isValid(targetRepository) || targetBranch.isBlank() || !CnbGitObjectId.isPresent(advertisedTargetSha)) {
            return emptyList()
        }

        val targetSha = CnbGitObjectId.canonical(advertisedTargetSha)
        val liveTarget = client.getBranch(targetRepository, targetBranch)
        if (liveTarget.name != targetBranch || !sameObjectId(liveTarget.sha, targetSha)) {
            throw CnbApiException(
                "CNB target branch changed during open pull request verification",
                statusCode = 409,
                retryable = true,
            )
        }

        val result = ArrayList<CnbVerifiedOpenPullRequestPush>()
        for (listed in client.listPullRequests(targetRepository, CnbPullRequestListState.OPEN)) {
            if (!isListCandidate(listed, targetRepository, targetBranch)) continue
            val current = missingOrUnauthorized { client.getPullRequest(targetRepository, listed.number) } ?: continue
            if (!isCurrentCandidate(current, listed.number, targetRepository, targetBranch, targetSha)) continue

            val sourceBranch =
                missingOrUnauthorized { client.getBranch(current.sourceRepo, current.sourceBranch) } ?: continue
            if (sourceBranch.name != current.sourceBranch || !sameObjectId(sourceBranch.sha, current.sourceSha)) {
                throw CnbApiException(
                    "CNB pull request source branch changed during verification",
                    statusCode = 409,
                    retryable = true,
                )
            }
            val sourceSha = CnbGitObjectId.canonical(current.sourceSha)
            val normalized = current.copy(sourceSha = sourceSha, targetSha = targetSha, mergeSha = null)
            val sourceRepository = missingOrUnauthorized { client.getRepository(normalized.sourceRepo) } ?: continue
            if (sourceRepository.path != normalized.sourceRepo || !sourceRepository.cloneable || sourceRepository.cloneUrl.isBlank()) {
                continue
            }
            val sourceCloneUrl = secureCloneUrl(sourceRepository.cloneUrl, normalized.sourceRepo, payload.instance.webUrl)

            val labels =
                if (requirements.labels) {
                    missingOrUnauthorized {
                        client
                            .listPullLabels(targetRepository, normalized.number)
                            .mapTo(linkedSetOf()) { label -> label.name }
                    }
                } else {
                    null
                }
            val commitMessage =
                if (requirements.commitMessage) {
                    missingOrUnauthorized {
                        client
                            .getCommit(normalized.sourceRepo, sourceSha)
                            .takeIf { commit -> sameObjectId(commit.sha, sourceSha) }
                            ?.message
                    }
                } else {
                    null
                }

            val derived =
                delivery.copy(
                    payload =
                        payload.copy(
                            event = CnbWebhookEvent.PULL_REQUEST_TARGET,
                            pullRequest = normalized.toWebhookPullRequest(),
                        ),
                )
            result +=
                CnbVerifiedOpenPullRequestPush(
                    delivery = derived,
                    snapshot =
                        CnbLiveDeliverySnapshot(
                            revisionMatches = true,
                            pullRequest = normalized,
                            labels = labels,
                            commitMessage = commitMessage,
                        ),
                    sourceCloneUrl = sourceCloneUrl,
                )
        }
        return result
    }

    private fun isListCandidate(
        pullRequest: CnbPullRequest,
        targetRepository: String,
        targetBranch: String,
    ): Boolean =
        pullRequest.state == CnbPullRequestState.OPEN &&
            PULL_REQUEST_NUMBER.matches(pullRequest.number) &&
            pullRequest.targetRepo == targetRepository &&
            pullRequest.targetBranch == targetBranch

    private fun isCurrentCandidate(
        pullRequest: CnbPullRequest,
        expectedNumber: String,
        targetRepository: String,
        targetBranch: String,
        targetSha: String,
    ): Boolean =
        pullRequest.number == expectedNumber &&
            pullRequest.state == CnbPullRequestState.OPEN &&
            pullRequest.targetRepo == targetRepository &&
            pullRequest.targetBranch == targetBranch &&
            CnbRepositoryPath.isValid(pullRequest.sourceRepo) &&
            pullRequest.sourceBranch.isNotBlank() &&
            CnbGitObjectId.isPresent(pullRequest.sourceSha) &&
            sameObjectId(pullRequest.targetSha, targetSha)

    private fun CnbPullRequest.toWebhookPullRequest(): CnbWebhookPullRequest =
        CnbWebhookPullRequest(
            id = "pull-$number",
            number = number,
            title = title,
            description = body,
            proposer = author,
            sourceRepository = sourceRepo,
            sourceBranch = sourceBranch,
            sourceSha = sourceSha,
            targetBranch = targetBranch,
            targetSha = targetSha,
            mergeSha = null,
            action = TARGET_PUSH_ACTION,
            wip = draft,
        )

    private inline fun <T> missingOrUnauthorized(block: () -> T): T? =
        try {
            block()
        } catch (failure: CnbApiException) {
            if (failure.statusCode !in MISSING_OR_UNAUTHORIZED_STATUS_CODES) throw failure
            null
        }

    private fun sameObjectId(
        actual: String,
        expected: String,
    ): Boolean =
        CnbGitObjectId.isPresent(actual) &&
            CnbGitObjectId.canonical(actual) == CnbGitObjectId.canonical(expected)

    private fun secureCloneUrl(
        value: String,
        repositoryPath: String,
        webOrigin: String,
    ): String {
        try {
            val clone = URI(value).normalize()
            val origin = URI(webOrigin).normalize()
            require(clone.scheme.equals("https", ignoreCase = true))
            require(clone.userInfo == null && clone.host != null)
            require(clone.rawQuery == null && clone.rawFragment == null)
            require(origin.scheme.equals("https", ignoreCase = true) || origin.scheme.equals("http", ignoreCase = true))
            require(origin.userInfo == null && origin.host != null)
            require(clone.host.lowercase(Locale.ROOT) == origin.host.lowercase(Locale.ROOT))
            val expectedPort = if (origin.scheme.equals("https", ignoreCase = true)) effectivePort(origin) else 443
            require(effectivePort(clone) == expectedPort)
            val basePath = origin.path.orEmpty().trimEnd('/')
            val expectedPath = "$basePath/${repositoryPath.trim('/')}"
            require(clone.path == expectedPath || clone.path == "$expectedPath.git")
            return clone.toASCIIString()
        } catch (failure: IllegalArgumentException) {
            throw CnbApiException("CNB response contained an unsafe repository clone URL", cause = failure)
        } catch (failure: URISyntaxException) {
            throw CnbApiException("CNB response contained an unsafe repository clone URL", cause = failure)
        }
    }

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }

    private val TARGET_PUSH_EVENTS = setOf(CnbWebhookEvent.PUSH, CnbWebhookEvent.COMMIT_ADD)
    private val MISSING_OR_UNAUTHORIZED_STATUS_CODES = setOf(401, 403, 404)
    private val PULL_REQUEST_NUMBER = Regex("[1-9][0-9]{0,19}")
    private const val TARGET_PUSH_ACTION = "target_push"
}
