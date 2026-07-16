package dev.zxilly.jenkins.cnb.status

import dev.zxilly.jenkins.cnb.security.CnbRepositoryPath
import hudson.model.Actionable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CnbBuildMetadataRepositoryValidationTest {
    @Test
    fun `metadata resolver accepts canonical Unicode repositories`() {
        val resolution = resolve("团队/项目")

        assertTrue(resolution.relevant)
        assertNotNull(resolution.target)
        assertEquals("团队/项目", resolution.target?.repository)
        assertEquals("团队/项目", resolution.target?.commitRepository)
        assertTrue(CnbBuildMetadataResolver.isRepository("https://cnb.cool/团队/项目.git"))
    }

    @Test
    fun `metadata resolver rejects noncanonical and unsafe repository paths`() {
        val invalid =
            listOf(
                "team/../repository",
                "team/./repository",
                "team//repository",
                "team\\repository/project",
                "team/repo sitory",
                "team/repository\u0000",
                "team/${"r".repeat(CnbRepositoryPath.MAX_LENGTH)}",
            )

        invalid.forEach { repository ->
            assertFalse(CnbBuildMetadataResolver.isRepository(repository), repository)
            val resolution = resolve(repository)
            assertTrue(resolution.relevant, repository)
            assertNull(resolution.target, repository)
        }
    }

    private fun resolve(repository: String): CnbBuildMetadataResolution =
        CnbBuildMetadataResolver.resolve(
            actionable =
                object : Actionable() {
                    override fun getDisplayName(): String = "repository-validation"

                    override fun getSearchUrl(): String = "repository-validation"
                },
            item = null,
            causes = emptyList(),
            explicit =
                CnbBuildMetadataConfiguration(
                    serverId = "primary",
                    repository = repository,
                    sha = "a".repeat(40),
                    context = "repository-validation",
                ),
            previous = null,
        )
}
