package dev.zxilly.jenkins.cnb.scm

import hudson.plugins.git.GitChangeSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CnbRepositoryBrowserTest {
    @Test
    fun `change set links to CNB commit page`() {
        val changeSet = GitChangeSet(listOf("commit $COMMIT_SHA"), true)
        val browser = CnbRepositoryBrowser("https://cnb.cool/example/project/")

        assertEquals(
            "https://cnb.cool/example/project/-/commit/$COMMIT_SHA",
            browser.getChangeSetLink(changeSet).toExternalForm(),
        )
    }

    companion object {
        private const val COMMIT_SHA = "98b0c3b8d29219fb51bad8a4deafce9e04ea21e5"
    }
}
