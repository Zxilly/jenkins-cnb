package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import hudson.model.TaskListener
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.SCMSource
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import jenkins.scm.api.trait.SCMSourceRequest
import java.util.Collections
import java.util.EnumSet

/** Immutable request settings plus scan-scoped API results used by filters and authorities. */
class CnbSCMSourceRequest(
    source: SCMSource,
    context: CnbSCMSourceContext,
    listener: TaskListener?,
) : SCMSourceRequest(source, context, listener) {
    val sourceRepositoryPath: String? = (source as? CnbSCMSource)?.repositoryPath

    val fetchBranches: Boolean = context.wantsBranches
    val fetchTags: Boolean = context.wantsTags
    val fetchOriginPullRequests: Boolean = context.wantsOriginPullRequests
    val fetchForkPullRequests: Boolean = context.wantsForkPullRequests

    val originPullRequestStrategies: Set<ChangeRequestCheckoutStrategy> =
        immutableStrategies(fetchOriginPullRequests, context.originPullRequestStrategies())
    val forkPullRequestStrategies: Set<ChangeRequestCheckoutStrategy> =
        immutableStrategies(fetchForkPullRequests, context.forkPullRequestStrategies())

    val requestedPullRequestNumbers: Set<String>?
    val requestedBranchNames: Set<String>?
    val requestedTagNames: Set<String>?

    var branches: Iterable<CnbBranch> = emptyList()
    var tags: Iterable<CnbTag> = emptyList()
    var pullRequests: Iterable<CnbPullRequest> = emptyList()

    init {
        val includes = context.observer().includes
        if (includes == null) {
            requestedPullRequestNumbers = null
            requestedBranchNames = null
            requestedTagNames = null
        } else {
            val pullRequests = linkedSetOf<String>()
            val branches = linkedSetOf<String>()
            val tags = linkedSetOf<String>()
            for (head: SCMHead in includes) {
                when (head) {
                    is CnbBranchSCMHead -> {
                        branches.add(head.name)
                    }

                    is CnbTagSCMHead -> {
                        tags.add(head.name)
                    }

                    is CnbPullRequestSCMHead -> {
                        pullRequests.add(head.number)
                        if (head.origin == SCMHeadOrigin.DEFAULT) {
                            branches.add(head.sourceBranch)
                        }
                    }
                }
            }
            requestedPullRequestNumbers = Collections.unmodifiableSet(pullRequests)
            requestedBranchNames = Collections.unmodifiableSet(branches)
            requestedTagNames = Collections.unmodifiableSet(tags)
        }
    }

    fun strategiesFor(fork: Boolean): Set<ChangeRequestCheckoutStrategy> =
        if (fork && fetchForkPullRequests) {
            forkPullRequestStrategies
        } else if (!fork && fetchOriginPullRequests) {
            originPullRequestStrategies
        } else {
            emptySet()
        }

    companion object {
        private fun immutableStrategies(
            enabled: Boolean,
            strategies: Set<ChangeRequestCheckoutStrategy>,
        ): Set<ChangeRequestCheckoutStrategy> =
            if (enabled && strategies.isNotEmpty()) {
                Collections.unmodifiableSet(EnumSet.copyOf(strategies))
            } else {
                emptySet()
            }
    }
}
