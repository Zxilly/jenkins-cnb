package dev.zxilly.jenkins.cnb.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CnbGitObjectIdTest {
    @Test
    fun `accepts complete sha1 and sha256 object ids`() {
        assertTrue(CnbGitObjectId.isValid("a".repeat(40)))
        assertTrue(CnbGitObjectId.isValid("B".repeat(64)))
        assertTrue(CnbGitObjectId.isPresent("a".repeat(40)))
        assertFalse(CnbGitObjectId.isPresent("0".repeat(40)))
        assertEquals("b".repeat(64), CnbGitObjectId.canonical("B".repeat(64)))
    }

    @Test
    fun `rejects abbreviated nonhex zero-length and option-like object ids`() {
        listOf(null, "", "a".repeat(7), "g".repeat(40), "--upload-pack=evil", "a".repeat(41)).forEach {
            assertFalse(CnbGitObjectId.isValid(it))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CnbGitObjectId.canonical("--upload-pack=evil")
        }
    }
}
