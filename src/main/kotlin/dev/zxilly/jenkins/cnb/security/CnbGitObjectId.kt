package dev.zxilly.jenkins.cnb.security

/** Canonical full Git object IDs accepted from CNB response and webhook boundaries. */
internal object CnbGitObjectId {
    private val FULL_OBJECT_ID = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")

    fun isValid(value: String?): Boolean = value != null && FULL_OBJECT_ID.matches(value)

    /** A concrete ref target; Git's all-zero deletion sentinel is intentionally excluded. */
    fun isPresent(value: String?): Boolean = isValid(value) && requireNotNull(value).any { it != '0' }

    /** Returns a lowercase canonical object ID, rejecting abbreviations and option-like values. */
    fun canonical(value: String): String {
        require(isValid(value)) { "Git object ID must contain exactly 40 or 64 hexadecimal characters" }
        return value.lowercase()
    }
}
