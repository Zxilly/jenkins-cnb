package dev.zxilly.jenkins.cnb.scm

import hudson.MarkupText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CnbChangeLogAnnotatorTest {
    @Test
    fun `links an explicit pull request and the current commit to CNB`() {
        val text = MarkupText("Fix PR #42")

        CnbChangeLogAnnotator().annotate(
            CnbRepositoryBrowser("https://cnb.cool/example/project"),
            COMMIT_SHA,
            text,
        )

        assertEquals(
            "Fix PR <a href='https://cnb.cool/example/project/-/pulls/42'>#42</a>" +
                " (<a href='https://cnb.cool/example/project/-/commit/$COMMIT_SHA'>commit: ${COMMIT_SHA.take(12)}</a>)",
            text.toString(true),
        )
    }

    @Test
    fun `links fork commits to the source repository and pull requests to the target repository`() {
        val text = MarkupText("Review pull request #42")

        CnbChangeLogAnnotator().annotate(
            CnbRepositoryBrowser(
                "https://cnb.cool/contributor/project",
                "https://cnb.cool/upstream/project",
            ),
            COMMIT_SHA,
            text,
        )

        assertEquals(
            "Review pull request <a href='https://cnb.cool/upstream/project/-/pulls/42'>#42</a>" +
                " (<a href='https://cnb.cool/contributor/project/-/commit/$COMMIT_SHA'>" +
                "commit: ${COMMIT_SHA.take(12)}</a>)",
            text.toString(true),
        )
    }

    @Test
    fun `does not add links for an unsafe repository URL`() {
        listOf(
            "file:///tmp/example/project",
            "https://user:secret@cnb.cool/example/project",
            "https://cnb.cool/example/project?view=unsafe",
            "https://cnb.cool/example/project#fragment",
        ).forEach { repositoryUrl ->
            val text = MarkupText("Fix PR #42")

            CnbChangeLogAnnotator().annotate(
                CnbRepositoryBrowser(repositoryUrl),
                COMMIT_SHA,
                text,
            )

            assertEquals("Fix PR #42", text.toString(true), repositoryUrl)
        }
    }

    @Test
    fun `leaves ambiguous and malformed pull request references as plain text`() {
        val original =
            "Issue #42; repo#43; C#44; https://cnb.cool/path/PR#45; " +
                "PR #0; PR #123456789012345678901"
        val text = MarkupText(original)

        CnbChangeLogAnnotator().annotate(
            CnbRepositoryBrowser("https://cnb.cool/example/project"),
            null,
            text,
        )

        assertEquals(original, text.toString(true))
    }

    @Test
    fun `escapes untrusted messages and canonicalizes SHA 256 commit links`() {
        val commit = "A".repeat(64)
        val text = MarkupText("<script>alert(1)</script> PR#7")

        CnbChangeLogAnnotator().annotate(
            CnbRepositoryBrowser("https://cnb.cool/example/project"),
            commit,
            text,
        )

        assertEquals(
            "&lt;script&gt;alert(1)&lt;/script&gt; PR" +
                "<a href='https://cnb.cool/example/project/-/pulls/7'>#7</a>" +
                " (<a href='https://cnb.cool/example/project/-/commit/${commit.lowercase()}'>" +
                "commit: ${commit.lowercase().take(12)}</a>)",
            text.toString(true),
        )
    }

    @Test
    fun `escapes repository URLs before inserting markup`() {
        val text = MarkupText("PR#7")

        CnbChangeLogAnnotator().annotate(
            CnbRepositoryBrowser("https://cnb.cool/example/pro'ject"),
            COMMIT_SHA,
            text,
        )

        assertEquals(
            "PR<a href='https://cnb.cool/example/pro&#39;ject/-/pulls/7'>#7</a>" +
                " (<a href='https://cnb.cool/example/pro&#39;ject/-/commit/$COMMIT_SHA'>" +
                "commit: ${COMMIT_SHA.take(12)}</a>)",
            text.toString(true),
        )
    }

    @Test
    fun `does not link invalid or absent commit IDs`() {
        listOf(null, "a".repeat(39), "g".repeat(40), "0".repeat(40)).forEach { commitId ->
            val text = MarkupText("No reference")

            CnbChangeLogAnnotator().annotate(
                CnbRepositoryBrowser("https://cnb.cool/example/project"),
                commitId,
                text,
            )

            assertEquals("No reference", text.toString(true), commitId)
        }
    }

    companion object {
        private const val COMMIT_SHA = "98b0c3b8d29219fb51bad8a4deafce9e04ea21e5"
    }
}
