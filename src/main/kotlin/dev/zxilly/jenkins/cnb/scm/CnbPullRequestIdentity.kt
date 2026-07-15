package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import java.io.IOException

/** Resolves a cached pull-request head without silently retargeting it to a different change. */
internal object CnbPullRequestIdentity {
    fun fetchOpen(
        client: CnbClient,
        targetRepository: String,
        head: CnbPullRequestSCMHead,
    ): CnbPullRequest? {
        val pullRequest =
            try {
                client.getPullRequest(targetRepository, head.number)
            } catch (failure: CnbApiException) {
                if (failure.statusCode == 404) return null
                throw failure
            }
        if (!pullRequest.state.equals("open", ignoreCase = true)) {
            return null
        }
        requireMatches(pullRequest, targetRepository, head)
        return pullRequest
    }

    fun requireLookupMatches(
        pullRequest: CnbPullRequest,
        targetRepository: String,
        expectedNumber: String,
    ) {
        if (pullRequest.number != expectedNumber) {
            throw IOException(
                "CNB pull request lookup for $expectedNumber returned pull request ${pullRequest.number}",
            )
        }
        if (pullRequest.targetRepo != targetRepository) {
            throw IOException(
                "CNB pull request $expectedNumber belongs to '${pullRequest.targetRepo}', not '$targetRepository'",
            )
        }
    }

    fun requireMatches(
        pullRequest: CnbPullRequest,
        targetRepository: String,
        head: CnbPullRequestSCMHead,
    ) {
        requireLookupMatches(pullRequest, targetRepository, head.number)
        val mismatch =
            when {
                pullRequest.targetBranch != head.target.name -> "target branch"
                pullRequest.sourceRepo != head.sourceRepository -> "source repository"
                pullRequest.sourceBranch != head.sourceBranch -> "source branch"
                else -> null
            }
        if (mismatch != null) {
            throw IOException(
                "CNB pull request ${head.number} no longer matches the cached SCM head ($mismatch changed)",
            )
        }
    }
}
