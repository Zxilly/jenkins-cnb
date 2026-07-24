package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbCommit
import dev.zxilly.jenkins.cnb.api.model.CnbLabel
import dev.zxilly.jenkins.cnb.api.model.CnbMemberAccess
import dev.zxilly.jenkins.cnb.api.model.CnbMemberAccessLevel
import dev.zxilly.jenkins.cnb.api.model.CnbPullComment
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestState
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookActor
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookInstance
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPayload
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPullRequest
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRef
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRepository
import hudson.util.FormValidation
import jenkins.model.Jenkins
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class CnbPushTriggerTest {
    @Test
    @WithJenkins
    fun `label descriptor autocompletes and warns without blocking configuration`(jenkins: JenkinsRule) {
        val calls = AtomicInteger()
        var catalog: CnbRepositoryLabelCatalogResult =
            CnbRepositoryLabelCatalogResult.Available(listOf("ready", "release", "security-reviewed"), complete = true)
        val descriptor =
            CnbPushTrigger.DescriptorImpl(
                CnbRepositoryLabelLookup { _ ->
                    calls.incrementAndGet()
                    catalog
                },
            )
        val project = jenkins.createFreeStyleProject("labels")

        assertEquals(
            listOf("ready", "release"),
            descriptor
                .doAutoCompleteRequiredPullRequestLabels(project, "primary", "team/project", "ready, re")
                .values,
        )
        assertEquals(
            FormValidation.Kind.OK,
            descriptor
                .doCheckRequiredPullRequestLabels(project, "ready", "skip", "primary", "team/project")
                .kind,
        )
        assertEquals(
            FormValidation.Kind.WARNING,
            descriptor
                .doCheckRequiredPullRequestLabels(project, "ready,missing", "skip", "primary", "team/project")
                .kind,
        )

        catalog = CnbRepositoryLabelCatalogResult.Available(listOf("ready"), complete = false)
        assertEquals(
            FormValidation.Kind.WARNING,
            descriptor
                .doCheckExcludedPullRequestLabels(project, "skip", "ready", "primary", "team/project")
                .kind,
        )
        catalog = CnbRepositoryLabelCatalogResult.Unavailable
        assertEquals(
            FormValidation.Kind.WARNING,
            descriptor
                .doCheckRequiredPullRequestLabels(project, "ready", "", "primary", "team/project")
                .kind,
        )

        val callsBeforeLocalFailure = calls.get()
        assertEquals(
            FormValidation.Kind.ERROR,
            descriptor
                .doCheckRequiredPullRequestLabels(project, "ready", "ready", "primary", "team/project")
                .kind,
        )
        assertEquals(callsBeforeLocalFailure, calls.get())
        assertEquals(
            emptyList<String>(),
            descriptor
                .doAutoCompleteRequiredPullRequestLabels(null, "primary", "team/project", "re")
                .values,
        )
        assertEquals(callsBeforeLocalFailure, calls.get())
    }

    @Test
    fun `matches live pushes but never schedules a deleted ref`() {
        val trigger = CnbPushTrigger("cnb-cool", "team/project", "release/**")

        assertTrue(trigger.matches(delivery("release/1", "a".repeat(40))))
        assertFalse(trigger.matches(delivery("release/1", "0".repeat(40))))
        assertFalse(trigger.matches(delivery("main", "a".repeat(40))))
    }

    @Test
    fun `rejects non-canonical repository configuration`() {
        assertThrows(IllegalArgumentException::class.java) {
            CnbPushTrigger("cnb-cool", "team/../victim", "**")
        }
    }

    @Test
    fun `default events remain push and tag push only`() {
        val trigger = CnbPushTrigger("cnb-cool", "team/project", "**")

        assertTrue(trigger.matches(delivery("main", SHA_A)))
        assertTrue(trigger.matches(delivery("v1", SHA_A, CnbWebhookEvent.TAG_PUSH, tag = true)))
        assertFalse(trigger.matches(delivery("main", SHA_A, CnbWebhookEvent.COMMIT_ADD)))
        assertFalse(trigger.matches(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_TARGET)))
        assertEquals("push,tag_push", trigger.getEventFilter())
        assertFalse(trigger.isIncludeDraftPullRequests())
        assertFalse(trigger.isCancelPendingBuildsOnUpdate())
        assertFalse(trigger.isCancelRunningBuildsOnUpdate())
        assertTrue(trigger.isCiSkip())
        assertTrue(trigger.isSetBuildDescription())
        assertFalse(trigger.isTriggerOnlyIfNewCommitsPushed())
        assertEquals("never", trigger.getTriggerOpenPullRequestOnPush())
        assertEquals("", trigger.getRequiredPullRequestLabels())
        assertEquals("", trigger.getExcludedPullRequestLabels())
        assertEquals("", trigger.getCommentPattern())
        assertEquals("Developer", trigger.getCommentMinimumRole())
        assertFalse(trigger.matches(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_COMMENT)))
    }

    @Test
    fun `open pull request push mode accepts stable wire values`() {
        val trigger = CnbPushTrigger("cnb-cool", "team/project", "**")

        for (mode in listOf("never", "source", "both")) {
            trigger.setTriggerOpenPullRequestOnPush(mode)
            assertEquals(mode, trigger.getTriggerOpenPullRequestOnPush())
        }

        assertThrows(IllegalArgumentException::class.java) {
            trigger.setTriggerOpenPullRequestOnPush("all")
        }
    }

    @Test
    fun `source and both modes enable authoritative pull request target events`() {
        val trigger = CnbPushTrigger("cnb-cool", "team/project", "**")
        val delivery = pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_TARGET)

        assertFalse(trigger.matches(delivery))
        trigger.setTriggerOpenPullRequestOnPush("source")
        assertTrue(trigger.matches(delivery))
        trigger.setTriggerOpenPullRequestOnPush("both")
        assertTrue(trigger.matches(delivery))
    }

    @Test
    fun `configured pull request events apply ref source and target filters before API verification`() {
        val trigger = CnbPushTrigger("cnb-cool", "team/project", "main")
        trigger.setEventFilter("pull_request.comment pull_request.update")
        trigger.setSourceBranchFilter("feature/**")
        trigger.setTargetBranchFilter("main")
        trigger.setCommentPattern("rebuild(?:\\s+please)?")
        trigger.setCommentMinimumRole("Developer")

        val matching = pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_COMMENT)
        assertTrue(trigger.matches(matching))
        assertFalse(trigger.matches(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_TARGET)))
        assertFalse(trigger.matches(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_COMMENT, sourceBranch = "fix/nope")))
        assertFalse(trigger.matches(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_COMMENT, targetBranch = "release")))
        assertTrue(trigger.matches(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_COMMENT, wip = true)))
        assertEquals("pull_request.update,pull_request.comment", trigger.getEventFilter())

        val queueIdentity = requireNotNull(CnbQueueIdentity.from(matching))
        assertEquals("team/project", queueIdentity.repositoryPath)
        assertEquals("refs/pull/7/head", queueIdentity.ref)
        assertEquals(SHA_A, queueIdentity.sha)
    }

    @Test
    fun `comment delivery requires an explicit RE2 policy`() {
        val trigger = CnbPushTrigger("cnb-cool", "team/project", "main")
        trigger.setEventFilter("pull_request.comment")

        assertFalse(trigger.matches(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_COMMENT)))

        trigger.setCommentPattern("rebuild")
        assertTrue(trigger.matches(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_COMMENT)))
    }

    @Test
    fun `live pull request snapshot drives labels comment identity and actor role`() {
        val delivery =
            pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_COMMENT).let { value ->
                value.copy(
                    payload =
                        value.payload.copy(
                            pullRequest =
                                value.payload.pullRequest?.copy(
                                    commentId = "comment-9",
                                    commentBody = "rebuild please",
                                ),
                        ),
                )
            }
        val snapshot =
            CnbLiveDeliveryResolver.resolve(
                delivery = delivery,
                requirements = CnbLiveDeliveryRequirements(labels = true, comment = true, commitMessage = true),
                getBranch = { _, _ -> error("Branch lookup must not be used") },
                getTag = { _, _ -> error("Tag lookup must not be used") },
                getPullRequest = { _, number -> livePullRequest(number) },
                listLabels = { _, _ -> listOf(CnbLabel("1", "ci"), CnbLabel("2", "ready")) },
                getComment = { _, _, id -> CnbPullComment(id, "rebuild please", "alice") },
                listMemberAccess = { repository, actor ->
                    assertEquals("team/project", repository)
                    assertEquals("alice", actor)
                    listOf(CnbMemberAccess(repository, CnbMemberAccessLevel.DEVELOPER))
                },
                getCommit = { repository, sha ->
                    assertEquals("team/project", repository)
                    assertEquals(SHA_A, sha)
                    CnbCommit(sha, "build this change")
                },
            )
        val accepted = CnbPushTrigger("cnb-cool", "team/project", "main")
        accepted.setEventFilter("pull_request.comment")
        accepted.setRequiredPullRequestLabels("ci,ready")
        accepted.setExcludedPullRequestLabels("skip")
        accepted.setCommentPattern("rebuild\\s+please")
        accepted.setCommentMinimumRole("Developer")

        assertTrue(snapshot.revisionMatches)
        assertTrue(accepted.matchesLive(delivery, snapshot))
        val draftSnapshot = snapshot.copy(pullRequest = snapshot.pullRequest?.copy(draft = true))
        assertFalse(accepted.matchesLive(delivery, draftSnapshot))
        accepted.setIncludeDraftPullRequests(true)
        assertTrue(accepted.matchesLive(delivery, draftSnapshot))

        val rejected = CnbPushTrigger("cnb-cool", "team/project", "main")
        rejected.setEventFilter("pull_request.comment")
        rejected.setRequiredPullRequestLabels("security")
        rejected.setCommentPattern("rebuild\\s+please")
        assertFalse(rejected.matchesLive(delivery, snapshot))
    }

    @Test
    fun `CI skip markers are case insensitive and use the live commit message`() {
        val trigger = CnbPushTrigger("cnb-cool", "team/project", "**")

        listOf("docs [ci-skip]", "docs [CI SKIP]", "docs [Skip CI]").forEach { message ->
            val snapshot =
                CnbLiveDeliveryResolver.resolve(
                    delivery = delivery("main", SHA_A),
                    requirements = CnbLiveDeliveryRequirements(commitMessage = true),
                    getBranch = { _, name -> CnbBranch(name, SHA_A) },
                    getTag = { _, _ -> error("unused") },
                    getPullRequest = { _, _ -> error("unused") },
                    listLabels = { _, _ -> error("unused") },
                    getComment = { _, _, _ -> error("unused") },
                    listMemberAccess = { _, _ -> error("unused") },
                    getCommit = { repository, sha ->
                        assertEquals("team/project", repository)
                        CnbCommit(sha, message)
                    },
                )
            assertFalse(trigger.matchesLive(delivery("main", SHA_A), snapshot), message)
        }

        assertFalse(CnbCiSkip.matches("docs [ci_skip]"))
        trigger.setCiSkip(false)
        assertTrue(trigger.matchesLive(delivery("main", SHA_A), CnbLiveDeliverySnapshot(true, commitMessage = "[ci skip]")))
    }

    @Test
    fun `live comment verification fails closed for edits spoofed actors and unavailable access`() {
        val delivery =
            pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_COMMENT).let { value ->
                value.copy(
                    payload =
                        value.payload.copy(
                            pullRequest = value.payload.pullRequest?.copy(commentId = "comment-9", commentBody = "rebuild"),
                        ),
                )
            }

        fun snapshot(
            body: String = "rebuild",
            author: String = "alice",
            commentFailure: Int? = null,
            accessFailure: Int? = null,
        ): CnbLiveDeliverySnapshot =
            CnbLiveDeliveryResolver.resolve(
                delivery,
                CnbLiveDeliveryRequirements(comment = true),
                getBranch = { _, _ -> error("unused") },
                getTag = { _, _ -> error("unused") },
                getPullRequest = { _, number -> livePullRequest(number) },
                listLabels = { _, _ -> error("unused") },
                getComment = { _, _, id ->
                    commentFailure?.let { throw CnbApiException("comment unavailable", it) }
                    CnbPullComment(id, body, author)
                },
                listMemberAccess = { repository, _ ->
                    accessFailure?.let { throw CnbApiException("unavailable", it) }
                    listOf(CnbMemberAccess(repository, CnbMemberAccessLevel.REPORTER))
                },
            )

        assertTrue(snapshot().commentVerified)
        assertFalse(snapshot(body = "edited").commentVerified)
        assertFalse(snapshot(author = "mallory").commentVerified)
        assertFalse(snapshot(commentFailure = 404).commentVerified)
        assertEquals(403, assertThrows(CnbApiException::class.java) { snapshot(commentFailure = 403) }.statusCode)
        assertNull(snapshot(accessFailure = 403).actorAccessLevels)
        assertThrows(CnbApiException::class.java) { snapshot(accessFailure = 503) }
    }

    @Test
    fun `event filter rejects unsupported names and ambiguous wildcard combinations`() {
        assertThrows(IllegalArgumentException::class.java) { CnbEventFilter.normalize("issue.comment") }
        assertThrows(IllegalArgumentException::class.java) { CnbEventFilter.normalize("*,push") }
        assertEquals("*", CnbEventFilter.normalize("*"))
    }

    @Test
    fun `running cancellation is opt in and limited to pull request revision updates`() {
        val trigger = CnbPushTrigger("cnb-cool", "team/project", "**")

        assertFalse(trigger.shouldCancelRunningBuildsFor(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_UPDATE)))
        trigger.setCancelRunningBuildsOnUpdate(true)

        assertTrue(trigger.shouldCancelRunningBuildsFor(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_UPDATE)))
        assertTrue(trigger.shouldCancelRunningBuildsFor(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_TARGET)))
        assertTrue(
            trigger.shouldCancelRunningBuildsFor(
                pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST, action = "update"),
            ),
        )
        assertTrue(
            trigger.shouldCancelRunningBuildsFor(
                pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST, action = "synchronize"),
            ),
        )
        assertFalse(trigger.shouldCancelRunningBuildsFor(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST)))
        assertFalse(trigger.shouldCancelRunningBuildsFor(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_COMMENT)))
        assertFalse(trigger.shouldCancelRunningBuildsFor(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_APPROVED)))
        assertFalse(trigger.shouldCancelRunningBuildsFor(delivery("main", SHA_A)))
    }

    @Test
    @WithJenkins
    fun `advanced trigger options survive Jenkins configuration persistence`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("configured-trigger")
        val trigger = CnbPushTrigger("cnb-cool", "team/project", "release/**")
        trigger.setEventFilter("commit.add,pull_request.target")
        trigger.setSourceBranchFilter("feature/**")
        trigger.setTargetBranchFilter("release/**")
        trigger.setIncludeDraftPullRequests(true)
        trigger.setCancelPendingBuildsOnUpdate(true)
        trigger.setCancelRunningBuildsOnUpdate(true)
        trigger.setCiSkip(false)
        trigger.setSetBuildDescription(false)
        trigger.setTriggerOnlyIfNewCommitsPushed(true)
        trigger.setTriggerOpenPullRequestOnPush("both")
        trigger.setRequiredPullRequestLabels("ci, ready")
        trigger.setExcludedPullRequestLabels("skip")
        trigger.setCommentPattern("rebuild(?:\\s+please)?")
        trigger.setCommentMinimumRole("Developer")
        project.addTrigger(trigger)
        project.save()

        jenkins.jenkins.reload()

        val loaded = requireNotNull(jenkins.jenkins.getItemByFullName("configured-trigger", hudson.model.FreeStyleProject::class.java))
        val restored = requireNotNull(loaded.getTrigger(CnbPushTrigger::class.java))
        assertEquals("commit.add,pull_request.target", restored.getEventFilter())
        assertEquals("feature/**", restored.getSourceBranchFilter())
        assertEquals("release/**", restored.getTargetBranchFilter())
        assertTrue(restored.isIncludeDraftPullRequests())
        assertTrue(restored.isCancelPendingBuildsOnUpdate())
        assertTrue(restored.isCancelRunningBuildsOnUpdate())
        assertFalse(restored.isCiSkip())
        assertFalse(restored.isSetBuildDescription())
        assertTrue(restored.isTriggerOnlyIfNewCommitsPushed())
        assertEquals("both", restored.getTriggerOpenPullRequestOnPush())
        assertEquals("ci,ready", restored.getRequiredPullRequestLabels())
        assertEquals("skip", restored.getExcludedPullRequestLabels())
        assertEquals("rebuild(?:\\s+please)?", restored.getCommentPattern())
        assertEquals("Developer", restored.getCommentMinimumRole())
    }

    @Test
    @WithJenkins
    fun `XStream restoration disables invalid policy fields and preserves legacy defaults`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val unsafe = CnbPushTrigger("cnb-cool", "team/project", "**")
        unsafe.setEventFilter("pull_request.update,pull_request.comment")
        setSerializedField(unsafe, "configuredRequiredPullRequestLabels", "ready")
        setSerializedField(unsafe, "configuredExcludedPullRequestLabels", "ready")
        setSerializedField(unsafe, "configuredCommentPattern", "(?=rebuild)")
        setSerializedField(unsafe, "configuredCommentMinimumRole", "Guest")
        setSerializedField(unsafe, "configuredTriggerOpenPullRequestOnPush", "all")

        val restored = Jenkins.XSTREAM2.fromXML(Jenkins.XSTREAM2.toXML(unsafe)) as CnbPushTrigger

        assertEquals("", restored.getRequiredPullRequestLabels())
        assertEquals("", restored.getExcludedPullRequestLabels())
        assertEquals("", restored.getCommentPattern())
        assertEquals("Developer", restored.getCommentMinimumRole())
        assertEquals("never", restored.getTriggerOpenPullRequestOnPush())
        assertFalse(restored.matches(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_UPDATE)))
        assertFalse(restored.matches(pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_COMMENT)))

        val legacy = CnbPushTrigger("cnb-cool", "team/project", "**")
        setSerializedField(legacy, "configuredRequiredPullRequestLabels", null)
        setSerializedField(legacy, "configuredExcludedPullRequestLabels", null)
        setSerializedField(legacy, "configuredCommentPattern", null)
        setSerializedField(legacy, "configuredCommentMinimumRole", null)
        setSerializedField(legacy, "configuredCiSkip", null)
        setSerializedField(legacy, "configuredSetBuildDescription", null)
        setSerializedField(legacy, "configuredTriggerOpenPullRequestOnPush", null)
        val legacyRestored = Jenkins.XSTREAM2.fromXML(Jenkins.XSTREAM2.toXML(legacy)) as CnbPushTrigger
        assertEquals("", legacyRestored.getRequiredPullRequestLabels())
        assertEquals("", legacyRestored.getExcludedPullRequestLabels())
        assertEquals("", legacyRestored.getCommentPattern())
        assertEquals("Developer", legacyRestored.getCommentMinimumRole())
        assertTrue(legacyRestored.isCiSkip())
        assertTrue(legacyRestored.isSetBuildDescription())
        assertEquals("never", legacyRestored.getTriggerOpenPullRequestOnPush())
        assertTrue(legacyRestored.matches(delivery("main", SHA_A)))
    }

    @Test
    fun `accepts a branch only when CNB reports the signed revision as current`() {
        val expected = "a".repeat(40)
        val delivery = delivery("release/1", expected)

        assertTrue(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { repository, ref ->
                    assertTrue(repository == "team/project")
                    CnbBranch(ref, expected)
                },
                listTags = { error("Tag lookup must not be used for a branch push") },
            ),
        )
    }

    @Test
    fun `rejects a branch when its current CNB revision differs from the payload`() {
        val delivery = delivery("release/1", "a".repeat(40))

        assertFalse(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, ref -> CnbBranch(ref, "b".repeat(40)) },
                listTags = { error("Tag lookup must not be used for a branch push") },
            ),
        )
    }

    @Test
    fun `treats a missing branch as stale without scheduling it`() {
        val delivery = delivery("release/1", "a".repeat(40))

        assertFalse(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, _ -> throw CnbApiException("missing", statusCode = 404) },
                listTags = { error("Tag lookup must not be used for a branch push") },
            ),
        )
    }

    @Test
    fun `propagates a CNB API failure so the webhook can be retried`() {
        val delivery = delivery("release/1", "a".repeat(40))

        assertThrows(CnbApiException::class.java) {
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, _ -> throw CnbApiException("unavailable", statusCode = 503, retryable = true) },
                listTags = { error("Tag lookup must not be used for a branch push") },
            )
        }
    }

    @Test
    fun `accepts a tag only when the exact tag and revision exist in CNB`() {
        val expected = "c".repeat(40)
        val delivery = delivery("v1.0.0", expected, CnbWebhookEvent.TAG_PUSH, tag = true)

        assertTrue(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, _ -> error("Branch lookup must not be used for a tag push") },
                listTags = { listOf(CnbTag("v0.9.0", "b".repeat(40)), CnbTag("v1.0.0", expected)) },
            ),
        )
        assertFalse(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, _ -> error("Branch lookup must not be used for a tag push") },
                listTags = { listOf(CnbTag("v1.0.0", "d".repeat(40))) },
            ),
        )
    }

    @Test
    fun `classic preflight covers branch creation commit addition and confirmed deletions`() {
        for (event in listOf(CnbWebhookEvent.BRANCH_CREATE, CnbWebhookEvent.COMMIT_ADD)) {
            assertTrue(
                CnbPushTrigger.revisionMatches(
                    delivery("feature", SHA_A, event),
                    getBranch = { _, name -> CnbBranch(name, SHA_A) },
                    getTag = { _, _ -> error("Tag lookup must not be used") },
                    getPullRequest = { _, _ -> error("Pull request lookup must not be used") },
                ),
            )
        }

        assertTrue(
            CnbPushTrigger.revisionMatches(
                delivery("deleted", ZERO_SHA, CnbWebhookEvent.BRANCH_DELETE),
                getBranch = { _, _ -> throw CnbApiException("missing", statusCode = 404) },
                getTag = { _, _ -> error("Tag lookup must not be used") },
                getPullRequest = { _, _ -> error("Pull request lookup must not be used") },
            ),
        )
        assertFalse(
            CnbPushTrigger.revisionMatches(
                delivery("deleted", ZERO_SHA, CnbWebhookEvent.BRANCH_DELETE),
                getBranch = { _, name -> CnbBranch(name, SHA_A) },
                getTag = { _, _ -> error("Tag lookup must not be used") },
                getPullRequest = { _, _ -> error("Pull request lookup must not be used") },
            ),
        )

        assertTrue(
            CnbPushTrigger.revisionMatches(
                delivery("v-old", ZERO_SHA, CnbWebhookEvent.TAG_PUSH, tag = true),
                getBranch = { _, _ -> error("Branch lookup must not be used") },
                getTag = { _, _ -> throw CnbApiException("missing", statusCode = 404) },
                getPullRequest = { _, _ -> error("Pull request lookup must not be used") },
            ),
        )
    }

    @Test
    fun `classic preflight validates the live pull request revision`() {
        val delivery = pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_UPDATE)

        assertTrue(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, _ -> error("Branch lookup must not be used") },
                getTag = { _, _ -> error("Tag lookup must not be used") },
                getPullRequest = { _, number -> livePullRequest(number) },
            ),
        )
        assertFalse(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, _ -> error("Branch lookup must not be used") },
                getTag = { _, _ -> error("Tag lookup must not be used") },
                getPullRequest = { _, number -> livePullRequest(number).copy(sourceSha = SHA_B) },
            ),
        )
        assertTrue(
            CnbPushTrigger.revisionMatches(
                pullRequestDelivery(CnbWebhookEvent.PULL_REQUEST_MERGED),
                getBranch = { _, _ -> error("Branch lookup must not be used") },
                getTag = { _, _ -> error("Tag lookup must not be used") },
                getPullRequest = { _, number -> livePullRequest(number).copy(state = CnbPullRequestState.MERGED) },
            ),
        )
        assertTrue(
            CnbPushTrigger.revisionMatches(
                delivery,
                getBranch = { _, _ -> error("Branch lookup must not be used") },
                getTag = { _, _ -> error("Tag lookup must not be used") },
                getPullRequest = { _, number -> livePullRequest(number).copy(draft = true) },
            ),
        )
    }

    private fun delivery(
        ref: String,
        current: String,
        event: CnbWebhookEvent = CnbWebhookEvent.PUSH,
        tag: Boolean = false,
    ): CnbWebhookDelivery {
        val payload =
            CnbWebhookPayload(
                "delivery-1",
                "build-1",
                Instant.parse("2026-07-15T10:00:00Z"),
                event,
                "https://cnb.cool/team/project",
                false,
                CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
                CnbWebhookRepository("repo-1", "team/project", "https://cnb.cool/team/project"),
                CnbWebhookActor("user-1", "alice", "Alice", ""),
                CnbWebhookRef(ref, current, "b".repeat(40), current, tag),
                null,
            )
        return CnbWebhookDelivery("cnb-cool", payload, "test")
    }

    private fun pullRequestDelivery(
        event: CnbWebhookEvent,
        sourceBranch: String = "feature/change",
        targetBranch: String = "main",
        wip: Boolean = false,
        action: String = if (event == CnbWebhookEvent.PULL_REQUEST) "open" else "synchronize",
    ): CnbWebhookDelivery {
        val pullRequest =
            CnbWebhookPullRequest(
                id = "pr-7",
                number = "7",
                title = "Change",
                description = "",
                proposer = "alice",
                sourceRepository = "team/project",
                sourceBranch = sourceBranch,
                sourceSha = SHA_A,
                targetBranch = targetBranch,
                targetSha = SHA_C,
                mergeSha = SHA_D,
                action = action,
                wip = wip,
            )
        val payload =
            CnbWebhookPayload(
                "delivery-pr-7",
                "build-pr-7",
                Instant.parse("2026-07-15T10:00:00Z"),
                event,
                "https://cnb.cool/team/project/-/pulls/7",
                false,
                CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
                CnbWebhookRepository("repo-1", "team/project", "https://cnb.cool/team/project"),
                CnbWebhookActor("user-1", "alice", "Alice", ""),
                CnbWebhookRef(targetBranch, SHA_C, SHA_B, SHA_C, false),
                pullRequest,
            )
        return CnbWebhookDelivery("cnb-cool", payload, "test")
    }

    private fun livePullRequest(number: String): CnbPullRequest =
        CnbPullRequest(
            number = number,
            title = "Change",
            state = CnbPullRequestState.OPEN,
            sourceRepo = "team/project",
            sourceBranch = "feature/change",
            sourceSha = SHA_A,
            targetRepo = "team/project",
            targetBranch = "main",
            targetSha = SHA_C,
            mergeSha = SHA_D,
        )

    companion object {
        private val ZERO_SHA = "0".repeat(40)
        private val SHA_A = "a".repeat(40)
        private val SHA_B = "b".repeat(40)
        private val SHA_C = "c".repeat(40)
        private val SHA_D = "d".repeat(40)

        private fun setSerializedField(
            trigger: CnbPushTrigger,
            name: String,
            value: Any?,
        ) {
            CnbPushTrigger::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .set(trigger, value)
        }
    }
}
