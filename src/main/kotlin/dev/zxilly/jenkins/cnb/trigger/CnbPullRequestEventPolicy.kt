package dev.zxilly.jenkins.cnb.trigger

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import dev.zxilly.jenkins.cnb.scm.CnbRepositoryRole

/** Immutable Classic/SCM event policies evaluated only against live CNB API data. */
internal class CnbPullRequestLabelPolicy(
    requiredLabels: String,
    excludedLabels: String,
) {
    private val required = normalizeLabels(requiredLabels)
    private val excluded = normalizeLabels(excludedLabels)

    val requiredConfiguration: String = required.joinToString(",")
    val excludedConfiguration: String = excluded.joinToString(",")
    val configured: Boolean = required.isNotEmpty() || excluded.isNotEmpty()

    init {
        require(required.intersect(excluded).isEmpty()) {
            "Required and excluded CNB pull request labels must not overlap"
        }
    }

    fun matches(liveLabels: Set<String>?): Boolean {
        if (!configured) return true
        val labels = liveLabels ?: return false
        return labels.containsAll(required) && labels.none(excluded::contains)
    }

    private fun normalizeLabels(value: String): Set<String> {
        val labels = linkedSetOf<String>()
        for (candidate in value.split(',', '\n')) {
            val label = candidate.trim()
            if (label.isNotEmpty()) labels += label
        }
        require(labels.size <= MAX_LABELS) { "At most $MAX_LABELS CNB pull request labels may be configured" }
        require(labels.all { it.length <= MAX_LABEL_LENGTH && it.none(Char::isISOControl) }) {
            "CNB pull request labels are invalid"
        }
        return labels
    }

    private companion object {
        const val MAX_LABELS = 50
        const val MAX_LABEL_LENGTH = 100
    }
}

/** Explicit, linear-time comment phrase and target-repository membership policy. */
internal class CnbPullRequestCommentPolicy(
    pattern: String,
    minimumRole: String,
) {
    val pattern: String = pattern.trim()
    val minimumRole: String = CnbRepositoryRole.parseMinimum(minimumRole).wireName
    private val requiredRole = CnbRepositoryRole.parseMinimum(this.minimumRole)
    private val compiled: Pattern

    init {
        require(this.pattern.isNotEmpty()) { "CNB pull request comment pattern must not be empty" }
        require(this.pattern.length <= MAX_PATTERN_LENGTH && this.pattern.none(Char::isISOControl)) {
            "CNB pull request comment pattern is invalid"
        }
        compiled = compilePattern(this.pattern)
    }

    fun matches(
        liveCommentBody: String?,
        liveAccessLevels: Set<CnbRepositoryRole>?,
    ): Boolean {
        val body = liveCommentBody ?: return false
        val access = liveAccessLevels ?: return false
        if (access.none { it.rank >= requiredRole.rank }) return false
        return compiled.matcher(body).matches()
    }

    companion object {
        private const val MAX_PATTERN_LENGTH = 2 * 1024

        private fun compilePattern(pattern: String): Pattern =
            try {
                Pattern.compile(pattern, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            } catch (_: PatternSyntaxException) {
                throw IllegalArgumentException("CNB pull request comment pattern is not valid RE2 syntax")
            }

        fun optional(
            pattern: String?,
            minimumRole: String?,
        ): CnbPullRequestCommentPolicy? =
            pattern
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { CnbPullRequestCommentPolicy(it, minimumRole.orEmpty().ifBlank { DEFAULT_MINIMUM_ROLE }) }

        internal const val DEFAULT_MINIMUM_ROLE = "Developer"
    }
}
