package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.scm.CnbRepositoryRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CnbPullRequestEventPolicyTest {
    @Test
    fun `label policy requires an authoritative snapshot and applies both sets`() {
        val policy = CnbPullRequestLabelPolicy(" ci, ready\n", "skip, blocked")

        assertEquals("ci,ready", policy.requiredConfiguration)
        assertEquals("skip,blocked", policy.excludedConfiguration)
        assertFalse(policy.matches(null), "An unavailable label API must fail closed")
        assertTrue(policy.matches(setOf("ci", "ready")))
        assertFalse(policy.matches(setOf("ci")))
        assertFalse(policy.matches(setOf("ci", "ready", "skip")))

        assertTrue(CnbPullRequestLabelPolicy("", "").matches(null))
        assertThrows(IllegalArgumentException::class.java) {
            CnbPullRequestLabelPolicy("ci", "ci")
        }
    }

    @Test
    fun `comment policy is explicit full RE2 match with a minimum live role`() {
        val policy = CnbPullRequestCommentPolicy("rebuild(?:\\s+please)?", "Developer")

        assertEquals("Developer", policy.minimumRole)
        assertTrue(policy.matches("REBUILD\nPLEASE", setOf(CnbRepositoryRole.DEVELOPER)))
        assertTrue(policy.matches("rebuild", setOf(CnbRepositoryRole.OWNER)))
        assertFalse(
            policy.matches("please rebuild", setOf(CnbRepositoryRole.OWNER)),
            "Comment matching must cover the complete body",
        )
        assertFalse(policy.matches("rebuild", setOf(CnbRepositoryRole.REPORTER)))
        assertFalse(policy.matches("rebuild", setOf(CnbRepositoryRole.UNKNOWN)))
        assertFalse(policy.matches("rebuild", null), "An unavailable member API must fail closed")
        assertFalse(policy.matches(null, setOf(CnbRepositoryRole.OWNER)), "An unavailable live comment must fail closed")
    }

    @Test
    fun `comment policy rejects disabled invalid or non-linear syntax`() {
        assertNull(CnbPullRequestCommentPolicy.optional("", "Reporter"))
        assertThrows(IllegalArgumentException::class.java) {
            CnbPullRequestCommentPolicy("(?=rebuild)rebuild", "Reporter")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CnbPullRequestCommentPolicy("(rebuild)\\1", "Reporter")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CnbPullRequestCommentPolicy("rebuild", "Guest")
        }
    }

    @Test
    fun `nested repetition remains bounded by RE2 rather than backtracking`() {
        val policy = CnbPullRequestCommentPolicy("(a+)+b", "Reporter")
        val longNonMatch = "a".repeat(100_000)

        assertFalse(policy.matches(longNonMatch, setOf(CnbRepositoryRole.REPORTER)))
    }
}
