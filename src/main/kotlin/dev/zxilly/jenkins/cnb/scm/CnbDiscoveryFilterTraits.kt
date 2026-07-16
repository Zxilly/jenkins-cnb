package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import hudson.Extension
import hudson.util.ListBoxModel
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.SCMSource
import jenkins.scm.api.trait.SCMHeadAuthorityDescriptor
import jenkins.scm.api.trait.SCMHeadFilter
import jenkins.scm.api.trait.SCMSourceContext
import jenkins.scm.api.trait.SCMSourceTrait
import jenkins.scm.api.trait.SCMSourceTraitDescriptor
import org.jenkinsci.Symbol
import org.kohsuke.stapler.DataBoundConstructor

/** Filters discovered CNB branches by their protected and locked properties. */
class CnbBranchPropertyFilterTrait
    @DataBoundConstructor
    constructor(
        val protectedStrategyId: Int,
        val lockedStrategyId: Int,
    ) : SCMSourceTrait() {
        init {
            requirePropertyStrategy(protectedStrategyId)
            requirePropertyStrategy(lockedStrategyId)
        }

        override fun decorateContext(context: SCMSourceContext<*, *>) {
            (context as CnbSCMSourceContext).withFilter(Filter(protectedStrategyId, lockedStrategyId))
        }

        @Extension
        @Symbol("cnbBranchPropertyFilter")
        class DescriptorImpl : SCMSourceTraitDescriptor() {
            override fun getDisplayName(): String = "Filter protected or locked branches"

            override fun getContextClass(): Class<out SCMSourceContext<*, *>> = CnbSCMSourceContext::class.java

            override fun getSourceClass(): Class<out SCMSource> = CnbSCMSource::class.java

            fun doFillProtectedStrategyIdItems(): ListBoxModel = propertyStrategyItems("protected")

            fun doFillLockedStrategyIdItems(): ListBoxModel = propertyStrategyItems("locked")
        }

        private class Filter(
            private val protectedStrategyId: Int,
            private val lockedStrategyId: Int,
        ) : SCMHeadFilter() {
            override fun isExcluded(
                request: jenkins.scm.api.trait.SCMSourceRequest,
                head: SCMHead,
            ): Boolean {
                if (request !is CnbSCMSourceRequest || head !is CnbBranchSCMHead) return false
                val branch = request.branches.firstOrNull { it.name == head.name } ?: return true
                return !propertyMatches(protectedStrategyId, branch.protected) ||
                    !propertyMatches(lockedStrategyId, branch.locked)
            }
        }
    }

/** Filters PR discovery without trusting values from a webhook payload. */
class CnbPullRequestFilterTrait
    @DataBoundConstructor
    constructor(
        val includeDrafts: Boolean,
        sourceBranchFilter: String,
        targetBranchFilter: String,
        requiredLabels: String,
        excludedLabels: String,
    ) : SCMSourceTrait() {
        val sourceBranchFilter: String = sourceBranchFilter.trim().ifEmpty { DEFAULT_GLOB }
        val targetBranchFilter: String = targetBranchFilter.trim().ifEmpty { DEFAULT_GLOB }
        val requiredLabels: String = normalizeLabels(requiredLabels)
        val excludedLabels: String = normalizeLabels(excludedLabels)

        init {
            CnbDiscoveryGlob.compile(this.sourceBranchFilter)
            CnbDiscoveryGlob.compile(this.targetBranchFilter)
            require(labelSet(this.requiredLabels).intersect(labelSet(this.excludedLabels)).isEmpty()) {
                "Required and excluded CNB PR labels must not overlap"
            }
        }

        override fun decorateContext(context: SCMSourceContext<*, *>) {
            val cnb = context as CnbSCMSourceContext
            if (requiredLabels.isNotEmpty() || excludedLabels.isNotEmpty()) {
                cnb.wantPullRequestLabels(true)
            }
            cnb.withFilter(
                Filter(
                    includeDrafts,
                    sourceBranchFilter,
                    targetBranchFilter,
                    labelSet(requiredLabels),
                    labelSet(excludedLabels),
                ),
            )
        }

        @Extension
        @Symbol("cnbPullRequestFilter")
        class DescriptorImpl : SCMSourceTraitDescriptor() {
            override fun getDisplayName(): String = "Filter CNB pull requests"

            override fun getContextClass(): Class<out SCMSourceContext<*, *>> = CnbSCMSourceContext::class.java

            override fun getSourceClass(): Class<out SCMSource> = CnbSCMSource::class.java
        }

        private class Filter(
            private val includeDrafts: Boolean,
            private val sourceBranchFilter: String,
            private val targetBranchFilter: String,
            private val requiredLabels: Set<String>,
            private val excludedLabels: Set<String>,
        ) : SCMHeadFilter() {
            override fun isExcluded(
                request: jenkins.scm.api.trait.SCMSourceRequest,
                head: SCMHead,
            ): Boolean {
                if (request !is CnbSCMSourceRequest || head !is CnbPullRequestSCMHead) return false
                val pullRequest = request.pullRequests.firstOrNull { it.number == head.number } ?: return true
                val labels = request.pullRequestLabels[pullRequest.number].orEmpty()
                return !includes(pullRequest, labels)
            }

            private fun includes(
                pullRequest: CnbPullRequest,
                labels: Set<String>,
            ): Boolean =
                (includeDrafts || !pullRequest.draft) &&
                    CnbDiscoveryGlob.matches(sourceBranchFilter, pullRequest.sourceBranch) &&
                    CnbDiscoveryGlob.matches(targetBranchFilter, pullRequest.targetBranch) &&
                    labels.containsAll(requiredLabels) &&
                    labels.none(excludedLabels::contains)
        }

        companion object {
            private const val DEFAULT_GLOB = "**"
        }
    }

/** Trusts fork Jenkinsfiles only when the PR author has the configured target-repository role. */
class TrustMembers
    @DataBoundConstructor
    constructor(
        minimumRole: String,
    ) : CnbForkTrustPolicy() {
        val minimumRole: String = CnbRepositoryRole.parseMinimum(minimumRole).wireName

        override fun decorateContext(context: CnbSCMSourceContext) {
            context.wantPullRequestAuthorAccess(true)
        }

        override fun checkTrusted(
            request: CnbSCMSourceRequest,
            head: CnbPullRequestSCMHead,
        ): Boolean {
            val required = CnbRepositoryRole.parse(minimumRole)
            for (accessLevel in request.pullRequestAuthorAccess[head.author].orEmpty()) {
                if (CnbRepositoryRole.parse(accessLevel).rank >= required.rank) return true
            }
            return false
        }

        @Extension
        @Symbol("cnbTrustMembers")
        class DescriptorImpl : SCMHeadAuthorityDescriptor() {
            override fun getDisplayName(): String = "Target repository members"

            override fun isApplicableToOrigin(originClass: Class<out SCMHeadOrigin>): Boolean =
                SCMHeadOrigin.Fork::class.java.isAssignableFrom(originClass)

            fun doFillMinimumRoleItems(): ListBoxModel =
                ListBoxModel().apply {
                    for (role in CnbRepositoryRole.entries) {
                        if (role.rank >= CnbRepositoryRole.REPORTER.rank) add(role.displayName, role.wireName)
                    }
                }
        }
    }

internal enum class CnbRepositoryRole(
    val wireName: String,
    val displayName: String,
    val rank: Int,
) {
    UNKNOWN("Unknown", "Unknown", 0),
    GUEST("Guest", "Guest", 1),
    REPORTER("Reporter", "Reporter", 2),
    DEVELOPER("Developer", "Developer", 3),
    MASTER("Master", "Master", 4),
    OWNER("Owner", "Owner", 5),
    ;

    companion object {
        fun parse(value: String): CnbRepositoryRole = entries.firstOrNull { it.wireName.equals(value.trim(), ignoreCase = true) } ?: UNKNOWN

        fun parseMinimum(value: String): CnbRepositoryRole =
            parse(value).also {
                require(it.rank >= REPORTER.rank) { "CNB minimum trusted member role must be Reporter or higher" }
            }
    }
}

internal object CnbDiscoveryGlob {
    fun matches(
        pattern: String,
        value: String,
    ): Boolean = compile(pattern).matches(value)

    fun compile(pattern: String): Regex {
        require(pattern.isNotEmpty()) { "CNB branch filter must not be empty" }
        require(pattern.length <= 512 && pattern.none { it.code < 0x20 || it.code == 0x7f }) {
            "CNB branch filter is invalid"
        }
        val expression = StringBuilder("^")
        var index = 0
        while (index < pattern.length) {
            val character = pattern[index]
            when {
                character == '*' && index + 1 < pattern.length && pattern[index + 1] == '*' -> {
                    expression.append(".*")
                    index += 2
                }

                character == '*' -> {
                    expression.append("[^/]*")
                    index++
                }

                character == '?' -> {
                    expression.append("[^/]")
                    index++
                }

                else -> {
                    if (character in "\\.[]{}()+-^$|") expression.append('\\')
                    expression.append(character)
                    index++
                }
            }
        }
        return Regex(expression.append('$').toString())
    }
}

private fun requirePropertyStrategy(strategyId: Int) {
    require(strategyId in PROPERTY_ANY..PROPERTY_EXCLUDE) { "CNB branch property strategy ID must be between 0 and 2" }
}

private fun propertyMatches(
    strategyId: Int,
    value: Boolean,
): Boolean =
    when (strategyId) {
        PROPERTY_ANY -> true
        PROPERTY_ONLY -> value
        PROPERTY_EXCLUDE -> !value
        else -> false
    }

private fun propertyStrategyItems(property: String): ListBoxModel =
    ListBoxModel().apply {
        add("Include all branches", PROPERTY_ANY.toString())
        add("Only $property branches", PROPERTY_ONLY.toString())
        add("Exclude $property branches", PROPERTY_EXCLUDE.toString())
    }

private fun normalizeLabels(value: String): String {
    val labels = labelSet(value)
    require(labels.size <= 50) { "At most 50 CNB PR labels may be configured" }
    require(labels.all { it.length <= 100 && it.none { character -> character.isISOControl() } }) {
        "CNB PR labels are invalid"
    }
    return labels.joinToString(",")
}

private fun labelSet(value: String): Set<String> {
    val labels = linkedSetOf<String>()
    for (candidate in value.split(',', '\n')) {
        val label = candidate.trim()
        if (label.isNotEmpty()) labels += label
    }
    return labels
}

private const val PROPERTY_ANY = 0
private const val PROPERTY_ONLY = 1
private const val PROPERTY_EXCLUDE = 2
