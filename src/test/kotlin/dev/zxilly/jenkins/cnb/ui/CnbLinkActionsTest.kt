package dev.zxilly.jenkins.cnb.ui

import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.security.CnbRepositoryPath
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataAction
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataState
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataTarget
import dev.zxilly.jenkins.cnb.trigger.CnbPushTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbLinkActionsTest {
    @Test
    fun `classic jobs and resolved builds expose encoded CNB links`(jenkins: JenkinsRule) {
        CnbGlobalConfiguration.get().setServers(
            listOf(CnbServer("primary", "Primary", "https://cnb.cool", "https://api.cnb.cool")),
        )
        val project = jenkins.createFreeStyleProject("classic")
        project.addTrigger(CnbPushTrigger("primary", "team/repo", "**"))

        val jobAction = CnbJobLinkActionFactory().createFor(project).single() as CnbExternalLinkAction
        assertEquals("CNB repository", jobAction.displayName)
        assertEquals("https://cnb.cool/team/repo", jobAction.urlName)

        val pullRequest =
            CnbLinkResolver.fromResolved(
                "primary",
                "team/repo",
                "fork/repo",
                "a".repeat(40),
                "42",
                "release/v1",
            )
        val pullRequestAction = requireNotNull(pullRequest).mostSpecificAction()
        assertEquals("CNB pull request #42", pullRequestAction.displayName)
        assertTrue(pullRequestAction.urlName.endsWith("/team/repo/-/pulls/42"))

        val tag =
            CnbLinkResolver.fromResolved(
                "primary",
                "team/repo",
                sha = "c".repeat(40),
                tag = "release/v1.0",
            )
        val tagAction = requireNotNull(tag).mostSpecificAction()
        assertEquals("CNB tag release/v1.0", tagAction.displayName)
        assertTrue(tagAction.urlName.endsWith("/team/repo/-/releases/tag/release%2Fv1.0"))

        val commit = CnbLinkResolver.fromResolved("primary", "team/repo", "fork/repo", "b".repeat(40))
        val commitAction = requireNotNull(commit).mostSpecificAction()
        assertEquals("CNB commit bbbbbbbbbbbb", commitAction.displayName)
        assertTrue(commitAction.urlName.endsWith("/fork/repo/-/commit/${"b".repeat(40)}"))
    }

    @Test
    fun `link resolver rejects untrusted identifiers`(jenkins: JenkinsRule) {
        CnbGlobalConfiguration.get().setServers(
            listOf(CnbServer("primary", "Primary", "https://cnb.cool", "https://api.cnb.cool")),
        )
        assertNull(CnbLinkResolver.fromResolved("primary", "../repo"))
        listOf(
            "team/../repository",
            "team/./repository",
            "team//repository",
            "team\\repository/project",
            "team/repo sitory",
            "team/repository\u0000",
            "team/${"r".repeat(CnbRepositoryPath.MAX_LENGTH)}",
        ).forEach { repository ->
            assertNull(CnbLinkResolver.fromResolved("primary", repository), repository)
        }
        assertNull(CnbLinkResolver.fromResolved("primary", "team/repository", commitRepository = "fork/../repository"))
        assertNull(CnbLinkResolver.fromResolved("primary", "team/repo", sha = "not-a-sha"))
        assertNull(CnbLinkResolver.fromResolved("primary", "team/repo", tag = "release\nspoof"))
        assertNull(CnbLinkResolver.fromResolved("missing", "team/repo"))

        val xssTag = "release/&lt;script&gt;alert(1)&lt;/script&gt;"
        val action = requireNotNull(CnbLinkResolver.fromResolved("primary", "team/repo", tag = xssTag)).mostSpecificAction()
        assertTrue("&" !in action.urlName)
        assertTrue(
            action.urlName.endsWith(
                "release%2F%26lt%3Bscript%26gt%3Balert%281%29%26lt%3B%2Fscript%26gt%3B",
            ),
        )

        val unicode = requireNotNull(CnbLinkResolver.fromResolved("primary", "团队/项目")).repositoryAction()
        assertEquals("https://cnb.cool/%E5%9B%A2%E9%98%9F/%E9%A1%B9%E7%9B%AE", unicode.urlName)
    }

    @Test
    fun `run link factory passes persisted tag metadata without recursively enumerating transient actions`(jenkins: JenkinsRule) {
        CnbGlobalConfiguration.get().setServers(
            listOf(CnbServer("primary", "Primary", "https://cnb.cool", "https://api.cnb.cool")),
        )
        val project = jenkins.createFreeStyleProject("run-link")
        val run = jenkins.buildAndAssertSuccess(project)
        val tag = "release/&lt;script&gt;alert(1)&lt;/script&gt;"
        val metadata = CnbBuildMetadataAction("run-link-test")
        metadata.advance(
            CnbBuildMetadataTarget(
                serverId = "primary",
                repository = "team/repo",
                sha = "a".repeat(40),
                pullRequestNumber = null,
                context = "run-link",
                credentialsId = null,
                tag = tag,
            ),
            CnbBuildMetadataState.SUCCESS,
            run.fullDisplayName,
            run.url,
        )
        run.addAction(metadata)

        val links = run.getActions(CnbExternalLinkAction::class.java)

        assertEquals(1, links.size)
        assertEquals("CNB tag $tag", links.single().displayName)
        assertTrue(
            links
                .single()
                .urlName
                .endsWith(
                    "/team/repo/-/releases/tag/" +
                        "release%2F%26lt%3Bscript%26gt%3Balert%281%29%26lt%3B%2Fscript%26gt%3B",
                ),
        )

        val page = jenkins.createWebClient().goTo(run.url)
        val html = page.webResponse.contentAsString
        assertFalse(html.contains("CNB tag $tag"))
        assertTrue(html.contains("CNB tag release/&amp;lt;script&amp;gt;alert(1)&amp;lt;/script&amp;gt;"))
        assertFalse(page.getElementsByTagName("script").any { it.textContent.contains("alert(1)") })
    }
}
