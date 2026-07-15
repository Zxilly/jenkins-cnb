package dev.zxilly.jenkins.cnb.scm

import hudson.Extension
import hudson.util.ListBoxModel
import jenkins.plugins.git.GitTagSCMRevision
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMHeadCategory
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.SCMRevision
import jenkins.scm.api.SCMSource
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import jenkins.scm.api.mixin.ChangeRequestSCMHead2
import jenkins.scm.api.trait.SCMHeadAuthority
import jenkins.scm.api.trait.SCMHeadAuthorityDescriptor
import jenkins.scm.api.trait.SCMHeadFilter
import jenkins.scm.api.trait.SCMSourceContext
import jenkins.scm.api.trait.SCMSourceRequest
import jenkins.scm.api.trait.SCMSourceTrait
import jenkins.scm.api.trait.SCMSourceTraitDescriptor
import jenkins.scm.impl.ChangeRequestSCMHeadCategory
import jenkins.scm.impl.TagSCMHeadCategory
import jenkins.scm.impl.trait.Discovery
import org.jenkinsci.Symbol
import org.kohsuke.stapler.DataBoundConstructor
import java.util.EnumSet

/** Discovers branches, optionally filtering branches that also have an origin pull request. */
class CnbBranchDiscoveryTrait
    @DataBoundConstructor
    constructor(
        val strategyId: Int,
    ) : SCMSourceTrait() {
        init {
            requireDiscoveryStrategy(strategyId, "branch")
        }

        val buildBranchesWithoutPullRequests: Boolean
            get() = strategyId and 1 != 0

        val buildBranchesWithPullRequests: Boolean
            get() = strategyId and 2 != 0

        override fun decorateContext(context: SCMSourceContext<*, *>) {
            val cnb = context as CnbSCMSourceContext
            cnb.wantBranches(true).withAuthority(BranchAuthority())
            when (strategyId) {
                1 -> cnb.wantOriginPullRequests(true).withFilter(ExcludePullRequestBranches())
                2 -> cnb.wantOriginPullRequests(true).withFilter(OnlyPullRequestBranches())
                3 -> Unit
                else -> error("Unsupported CNB branch discovery strategy ID: $strategyId")
            }
        }

        override fun includeCategory(category: SCMHeadCategory): Boolean = category.isUncategorized

        @Extension
        @Discovery
        @Symbol("cnbBranchDiscovery")
        class DescriptorImpl : SCMSourceTraitDescriptor() {
            override fun getDisplayName(): String = "Discover branches"

            override fun getContextClass(): Class<out SCMSourceContext<*, *>> = CnbSCMSourceContext::class.java

            override fun getSourceClass(): Class<out SCMSource> = CnbSCMSource::class.java

            fun doFillStrategyIdItems(): ListBoxModel =
                ListBoxModel().apply {
                    add("Exclude branches that are also pull requests", "1")
                    add("Only branches that are also pull requests", "2")
                    add("All branches", "3")
                }
        }

        class BranchAuthority : SCMHeadAuthority<SCMSourceRequest, CnbBranchSCMHead, SCMRevision>() {
            override fun checkTrusted(
                request: SCMSourceRequest,
                head: CnbBranchSCMHead,
            ): Boolean = true

            @Extension
            @Symbol("cnbBranchAuthority")
            class DescriptorImpl : SCMHeadAuthorityDescriptor() {
                override fun getDisplayName(): String = "Trust CNB branches"

                override fun isApplicableToOrigin(originClass: Class<out SCMHeadOrigin>): Boolean =
                    SCMHeadOrigin.Default::class.java.isAssignableFrom(originClass)
            }
        }

        private class ExcludePullRequestBranches : SCMHeadFilter() {
            override fun isExcluded(
                request: SCMSourceRequest,
                head: SCMHead,
            ): Boolean =
                head is CnbBranchSCMHead &&
                    request is CnbSCMSourceRequest &&
                    request.pullRequests.any { !it.fromFork && it.sourceBranch == head.name }
        }

        private class OnlyPullRequestBranches : SCMHeadFilter() {
            override fun isExcluded(
                request: SCMSourceRequest,
                head: SCMHead,
            ): Boolean =
                head is CnbBranchSCMHead &&
                    request is CnbSCMSourceRequest &&
                    request.pullRequests.none { !it.fromFork && it.sourceBranch == head.name }
        }
    }

/** Discovers repository tags. */
class CnbTagDiscoveryTrait
    @DataBoundConstructor
    constructor() : SCMSourceTrait() {
        override fun decorateContext(context: SCMSourceContext<*, *>) {
            (context as CnbSCMSourceContext).wantTags(true).withAuthority(TagAuthority())
        }

        override fun includeCategory(category: SCMHeadCategory): Boolean = category is TagSCMHeadCategory

        @Extension
        @Discovery
        @Symbol("cnbTagDiscovery")
        class DescriptorImpl : SCMSourceTraitDescriptor() {
            override fun getDisplayName(): String = "Discover tags"

            override fun getContextClass(): Class<out SCMSourceContext<*, *>> = CnbSCMSourceContext::class.java

            override fun getSourceClass(): Class<out SCMSource> = CnbSCMSource::class.java
        }

        class TagAuthority : SCMHeadAuthority<SCMSourceRequest, CnbTagSCMHead, GitTagSCMRevision>() {
            override fun checkTrusted(
                request: SCMSourceRequest,
                head: CnbTagSCMHead,
            ): Boolean = true

            @Extension
            @Symbol("cnbTagAuthority")
            class DescriptorImpl : SCMHeadAuthorityDescriptor() {
                override fun getDisplayName(): String = "Trust CNB tags"

                override fun isApplicableToOrigin(originClass: Class<out SCMHeadOrigin>): Boolean =
                    SCMHeadOrigin.Default::class.java.isAssignableFrom(originClass)
            }
        }
    }

/** Discovers pull requests opened from branches in the repository itself. */
class CnbOriginPullRequestDiscoveryTrait
    @DataBoundConstructor
    constructor(
        val strategyId: Int,
    ) : SCMSourceTrait() {
        init {
            requireDiscoveryStrategy(strategyId, "origin pull request")
        }

        val strategies: Set<ChangeRequestCheckoutStrategy>
            get() = strategies(strategyId)

        override fun decorateContext(context: SCMSourceContext<*, *>) {
            (context as CnbSCMSourceContext)
                .wantOriginPullRequests(true)
                .withOriginPullRequestStrategies(strategies)
                .withAuthority(OriginPullRequestAuthority())
        }

        override fun includeCategory(category: SCMHeadCategory): Boolean = category is ChangeRequestSCMHeadCategory

        @Extension
        @Discovery
        @Symbol("cnbPullRequestDiscovery")
        class DescriptorImpl : SCMSourceTraitDescriptor() {
            override fun getDisplayName(): String = "Discover pull requests from origin"

            override fun getContextClass(): Class<out SCMSourceContext<*, *>> = CnbSCMSourceContext::class.java

            override fun getSourceClass(): Class<out SCMSource> = CnbSCMSource::class.java

            fun doFillStrategyIdItems(): ListBoxModel = strategyItems()
        }

        class OriginPullRequestAuthority : SCMHeadAuthority<SCMSourceRequest, ChangeRequestSCMHead2, SCMRevision>() {
            override fun checkTrusted(
                request: SCMSourceRequest,
                head: ChangeRequestSCMHead2,
            ): Boolean = head.origin == SCMHeadOrigin.DEFAULT

            @Extension
            class DescriptorImpl : SCMHeadAuthorityDescriptor() {
                override fun getDisplayName(): String = "Trust origin pull requests"

                override fun isApplicableToOrigin(originClass: Class<out SCMHeadOrigin>): Boolean =
                    SCMHeadOrigin.Default::class.java.isAssignableFrom(originClass)
            }
        }

        companion object {
            const val NONE = 0
            const val MERGE = 1
            const val HEAD = 2
            const val HEAD_AND_MERGE = 3
        }
    }

/** Discovers pull requests opened from forks, with an explicit trust policy. */
class CnbForkPullRequestDiscoveryTrait
    @DataBoundConstructor
    constructor(
        val strategyId: Int,
        val trust: CnbForkTrustPolicy,
    ) : SCMSourceTrait() {
        init {
            requireDiscoveryStrategy(strategyId, "fork pull request")
        }

        val strategies: Set<ChangeRequestCheckoutStrategy>
            get() = strategies(strategyId)

        override fun decorateContext(context: SCMSourceContext<*, *>) {
            (context as CnbSCMSourceContext)
                .wantForkPullRequests(true)
                .withForkPullRequestStrategies(strategies)
                .withAuthority(trust)
        }

        override fun includeCategory(category: SCMHeadCategory): Boolean = category is ChangeRequestSCMHeadCategory

        @Extension
        @Discovery
        @Symbol("cnbForkPullRequestDiscovery")
        class DescriptorImpl : SCMSourceTraitDescriptor() {
            override fun getDisplayName(): String = "Discover pull requests from forks"

            override fun getContextClass(): Class<out SCMSourceContext<*, *>> = CnbSCMSourceContext::class.java

            override fun getSourceClass(): Class<out SCMSource> = CnbSCMSource::class.java

            fun doFillStrategyIdItems(): ListBoxModel = strategyItems()

            fun getTrustDescriptors(): List<SCMHeadAuthorityDescriptor> =
                SCMHeadAuthority._for(
                    CnbSCMSourceRequest::class.java,
                    CnbPullRequestSCMHead::class.java,
                    CnbPullRequestSCMRevision::class.java,
                    SCMHeadOrigin.Fork::class.java,
                )

            fun getDefaultTrust(): CnbForkTrustPolicy = TrustNobody()
        }

        companion object {
            const val NONE = 0
            const val MERGE = 1
            const val HEAD = 2
            const val HEAD_AND_MERGE = 3
        }
    }

/** Data-bound base type for CNB fork trust policies. */
abstract class CnbForkTrustPolicy : SCMHeadAuthority<CnbSCMSourceRequest, CnbPullRequestSCMHead, CnbPullRequestSCMRevision>()

/** Secure default: Jenkinsfiles from forks are never trusted. */
class TrustNobody
    @DataBoundConstructor
    constructor() : CnbForkTrustPolicy() {
        override fun checkTrusted(
            request: CnbSCMSourceRequest,
            head: CnbPullRequestSCMHead,
        ): Boolean = false

        @Extension
        @Symbol("cnbTrustNobody")
        class DescriptorImpl : SCMHeadAuthorityDescriptor() {
            override fun getDisplayName(): String = "Nobody"

            override fun isApplicableToOrigin(originClass: Class<out SCMHeadOrigin>): Boolean =
                SCMHeadOrigin.Fork::class.java.isAssignableFrom(originClass)
        }
    }

/** Trust forks in the same CNB namespace as the target repository. */
class TrustSameNamespace
    @DataBoundConstructor
    constructor() : CnbForkTrustPolicy() {
        override fun checkTrusted(
            request: CnbSCMSourceRequest,
            head: CnbPullRequestSCMHead,
        ): Boolean {
            val target = request.sourceRepositoryPath ?: return false
            return namespaceOf(target) == namespaceOf(head.sourceRepository)
        }

        @Extension
        @Symbol("cnbTrustSameNamespace")
        class DescriptorImpl : SCMHeadAuthorityDescriptor() {
            override fun getDisplayName(): String = "Forks in the same namespace"

            override fun isApplicableToOrigin(originClass: Class<out SCMHeadOrigin>): Boolean =
                SCMHeadOrigin.Fork::class.java.isAssignableFrom(originClass)
        }
    }

/** Explicit opt-in for installations that accept Jenkinsfiles from every fork. */
class TrustEveryone
    @DataBoundConstructor
    constructor() : CnbForkTrustPolicy() {
        override fun checkTrusted(
            request: CnbSCMSourceRequest,
            head: CnbPullRequestSCMHead,
        ): Boolean = true

        @Extension
        @Symbol("cnbTrustEveryone")
        class DescriptorImpl : SCMHeadAuthorityDescriptor() {
            override fun getDisplayName(): String = "Everyone"

            override fun isApplicableToOrigin(originClass: Class<out SCMHeadOrigin>): Boolean =
                SCMHeadOrigin.Fork::class.java.isAssignableFrom(originClass)
        }
    }

private fun strategies(strategyId: Int): Set<ChangeRequestCheckoutStrategy> =
    when (strategyId) {
        1 -> EnumSet.of(ChangeRequestCheckoutStrategy.MERGE)
        2 -> EnumSet.of(ChangeRequestCheckoutStrategy.HEAD)
        3 -> EnumSet.of(ChangeRequestCheckoutStrategy.HEAD, ChangeRequestCheckoutStrategy.MERGE)
        else -> EnumSet.noneOf(ChangeRequestCheckoutStrategy::class.java)
    }

private fun requireDiscoveryStrategy(
    strategyId: Int,
    subject: String,
) {
    require(strategyId in 1..3) { "CNB $subject discovery strategy ID must be between 1 and 3" }
}

private fun strategyItems(): ListBoxModel =
    ListBoxModel().apply {
        add("Merging the pull request with the current target branch revision", "1")
        add("The current pull request revision", "2")
        add("Both the current pull request revision and the merged result", "3")
    }

private fun namespaceOf(repository: String): String = repository.substringBeforeLast('/', missingDelimiterValue = "")
