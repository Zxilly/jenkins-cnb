package dev.zxilly.jenkins.cnb.security

/** Canonical CNB repository-path policy shared by trusted configuration and resolved metadata. */
internal object CnbRepositoryPath {
    const val MAX_LENGTH = 1024

    /**
     * Validates an already-canonical value. This function deliberately does not trim, decode,
     * normalize Unicode, remove `.git`, or extract a path from a URL.
     */
    fun isValid(value: String?): Boolean = isValidSegmentedPath(value, minimumSegments = 2)
}

/** A CNB group, organization, user, or repository path returned by inherited-access APIs. */
internal object CnbResourcePath {
    fun isValid(value: String?): Boolean = isValidSegmentedPath(value, minimumSegments = 1)
}

private fun isValidSegmentedPath(
    value: String?,
    minimumSegments: Int,
): Boolean {
    if (value == null || value.length !in 1..CnbRepositoryPath.MAX_LENGTH || containsUnsafeCharacter(value)) return false
    val segments = value.split('/')
    return segments.size >= minimumSegments &&
        segments.all { segment -> segment.isNotEmpty() && segment != "." && segment != ".." }
}

private fun containsUnsafeCharacter(value: String): Boolean {
    var offset = 0
    while (offset < value.length) {
        val current = value[offset]
        if (current.isHighSurrogate()) {
            if (offset + 1 >= value.length || !value[offset + 1].isLowSurrogate()) return true
        } else if (current.isLowSurrogate()) {
            return true
        }

        val codePoint = value.codePointAt(offset)
        if (
            codePoint == '\\'.code ||
            Character.isISOControl(codePoint) ||
            Character.isWhitespace(codePoint) ||
            Character.isSpaceChar(codePoint)
        ) {
            return true
        }
        offset += Character.charCount(codePoint)
    }
    return false
}
