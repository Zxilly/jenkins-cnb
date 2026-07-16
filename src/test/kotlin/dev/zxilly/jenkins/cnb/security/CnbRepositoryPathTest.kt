package dev.zxilly.jenkins.cnb.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CnbRepositoryPathTest {
    @Test
    fun `canonical paths preserve valid Unicode and enforce segment boundaries`() {
        assertTrue(CnbRepositoryPath.isValid("a/b"))
        assertTrue(CnbRepositoryPath.isValid("团队/项目"))
        assertTrue(CnbRepositoryPath.isValid("team/repository-🚀"))
        assertTrue(CnbRepositoryPath.isValid("a/${"b".repeat(CnbRepositoryPath.MAX_LENGTH - 2)}"))

        assertFalse(CnbRepositoryPath.isValid(null))
        assertFalse(CnbRepositoryPath.isValid("repository"))
        assertFalse(CnbRepositoryPath.isValid("/team/repository"))
        assertFalse(CnbRepositoryPath.isValid("team/repository/"))
        assertFalse(CnbRepositoryPath.isValid("team//repository"))
        assertFalse(CnbRepositoryPath.isValid("team/./repository"))
        assertFalse(CnbRepositoryPath.isValid("team/../repository"))
        assertFalse(CnbRepositoryPath.isValid("team\\repository/project"))
        assertFalse(CnbRepositoryPath.isValid("team/repo sitory"))
        assertFalse(CnbRepositoryPath.isValid("team/repository\u00a0"))
        assertFalse(CnbRepositoryPath.isValid("team/repository\u0000"))
        assertFalse(CnbRepositoryPath.isValid("team/${"r".repeat(CnbRepositoryPath.MAX_LENGTH)}"))
        assertFalse(CnbRepositoryPath.isValid("team/${String(charArrayOf('\uD800'))}"))
    }

    @Test
    fun `resource paths allow inherited single namespaces without weakening repository paths`() {
        assertTrue(CnbResourcePath.isValid("org"))
        assertTrue(CnbResourcePath.isValid("org/repository"))
        assertFalse(CnbResourcePath.isValid("."))
        assertFalse(CnbResourcePath.isValid("../repository"))
        assertFalse(CnbResourcePath.isValid("org\\repository"))
        assertFalse(CnbResourcePath.isValid("org repository"))
        assertFalse(CnbRepositoryPath.isValid("org"))
    }
}
