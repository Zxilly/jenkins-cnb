package dev.zxilly.jenkins.cnb.status

import hudson.model.Actionable
import hudson.model.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CnbBuildMetadataActionTest {
    @Test
    fun `maps every Jenkins terminal result without collapsing unstable or not built`() {
        assertEquals(CnbBuildMetadataState.SUCCESS, CnbBuildMetadataState.fromResult(Result.SUCCESS))
        assertEquals(CnbBuildMetadataState.UNSTABLE, CnbBuildMetadataState.fromResult(Result.UNSTABLE))
        assertEquals(CnbBuildMetadataState.FAILURE, CnbBuildMetadataState.fromResult(Result.FAILURE))
        assertEquals(CnbBuildMetadataState.ABORTED, CnbBuildMetadataState.fromResult(Result.ABORTED))
        assertEquals(CnbBuildMetadataState.NOT_BUILT, CnbBuildMetadataState.fromResult(Result.NOT_BUILT))
        assertEquals(CnbBuildMetadataState.NOT_BUILT, CnbBuildMetadataState.fromResult(null))
        assertEquals(CnbBuildMetadataState.UNSTABLE, CnbBuildMetadataState.fromWireName(" UnStAbLe "))
        assertNull(CnbBuildMetadataState.fromWireName("unknown"))
    }

    @Test
    fun `durable action does not regress lifecycle and becomes idempotently synchronized`() {
        val action = CnbBuildMetadataAction("queue:101:folder/job")
        val target = target()

        assertTrue(action.advance(target, CnbBuildMetadataState.QUEUED, "job (queued)", "queue/item/101/"))
        assertTrue(action.advance(target, CnbBuildMetadataState.RUNNING, "job #1", "job/1/"))
        action.advance(target, CnbBuildMetadataState.QUEUED, "job #1", "job/1/")
        assertEquals(CnbBuildMetadataState.RUNNING, action.snapshot()?.state)

        action.advance(target, CnbBuildMetadataState.ABORTED, "job #1", "job/1/")
        action.advance(target, CnbBuildMetadataState.SUCCESS, "job #1", "job/1/")
        action.advance(target, CnbBuildMetadataState.RUNNING, "job #1", "job/1/")
        val terminal = requireNotNull(action.snapshot())
        assertEquals(CnbBuildMetadataState.ABORTED, terminal.state)
        assertEquals(target.context, action.contextKey())
        action.markReported(terminal.version, "comment-7")

        assertFalse(action.isPending())
        assertNull(action.snapshot())
    }

    @Test
    fun `comment marker is unpredictable even for the same build identity`() {
        val first = CnbBuildMetadataAction("run:folder/job#1")
        val second = CnbBuildMetadataAction("run:folder/job#1")
        first.advance(target(), CnbBuildMetadataState.RUNNING, "job #1", "job/1/")
        second.advance(target(), CnbBuildMetadataState.RUNNING, "job #1", "job/1/")

        assertNotEquals(first.snapshot()?.markerToken, second.snapshot()?.markerToken)
    }

    @Test
    fun `explicit configuration overlays defaults and validates reporting identifiers`() {
        val defaults = CnbBuildMetadataConfiguration(serverId = "cnb-cool", context = "job", credentialsId = "server-creds")
        val explicit =
            CnbBuildMetadataConfiguration(
                repository = "Acme/repo",
                sha = "abcdef123456",
                tag = "v1.2.3",
                credentialsId = "build-creds",
            )
        val merged = explicit.overlay(defaults)

        assertEquals("cnb-cool", merged.serverId)
        assertEquals("Acme/repo", merged.repository)
        assertEquals("build-creds", merged.credentialsId)
        assertEquals("v1.2.3", merged.tag)
        assertTrue(CnbBuildMetadataResolver.isRepository(merged.repository))
        assertTrue(CnbBuildMetadataResolver.isSha(merged.sha))
        assertTrue(CnbBuildMetadataResolver.isPullRequestNumber("42"))
        assertFalse(CnbBuildMetadataResolver.isPullRequestNumber("0"))
        assertTrue(CnbBuildMetadataResolver.isTag("release/v1.2.3"))
        assertFalse(CnbBuildMetadataResolver.isTag("release..v1"))
        assertFalse(CnbBuildMetadataResolver.isTag("release v1"))
        assertFalse(CnbBuildMetadataResolver.isTag("release.lock"))
    }

    @Test
    fun `fork pull request keeps comment and commit repositories separate`() {
        val resolution =
            CnbBuildMetadataResolver.resolve(
                actionable = actionable(),
                item = null,
                causes = emptyList(),
                explicit =
                    CnbBuildMetadataConfiguration(
                        serverId = "cnb-cool",
                        repository = "team/project",
                        commitRepository = "alice/project",
                        sha = "a".repeat(40),
                        pullRequestNumber = "42",
                        tag = "v1.2.3",
                        context = "folder/job",
                    ),
                previous = null,
            )

        assertEquals("team/project", resolution.target?.repository)
        assertEquals("alice/project", resolution.target?.commitRepository)
        assertEquals("v1.2.3", resolution.target?.tag)
    }

    @Test
    fun `invalid explicit target cannot fall back to a previous valid target`() {
        val resolution =
            CnbBuildMetadataResolver.resolve(
                actionable = actionable(),
                item = null,
                causes = emptyList(),
                explicit = CnbBuildMetadataConfiguration(sha = "not-a-sha"),
                previous = target(),
            )

        assertTrue(resolution.relevant)
        assertNull(resolution.target)
    }

    private fun target() =
        CnbBuildMetadataTarget(
            serverId = "cnb-cool",
            repository = "Acme/repo",
            sha = "0123456789abcdef0123456789abcdef01234567",
            pullRequestNumber = "42",
            context = "folder/job",
            credentialsId = null,
        )

    private fun actionable(): Actionable =
        object : Actionable() {
            override fun getDisplayName(): String = "test"

            override fun getSearchUrl(): String = "test"
        }
}
