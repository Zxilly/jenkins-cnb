package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.trigger.CnbPushCause
import dev.zxilly.jenkins.cnb.trigger.CnbSCMHeadEvent
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookActor
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookInstance
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPayload
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPullRequest
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRef
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookRepository
import hudson.model.Item
import hudson.model.TaskListener
import hudson.scm.NullSCM
import jenkins.scm.api.SCMEvent
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.SCMSource
import jenkins.scm.api.SCMSourceObserver
import jenkins.scm.api.SCMSourceOwner
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant

class CnbSCMHeadEventTest {
    @Test
    fun `navigator project names are stable across namespace depth`() {
        val event = CnbSCMHeadEvent(delivery(payload(CnbWebhookEvent.PUSH, "acme/team/repo")))
        val recursive = CnbSCMNavigator("cnb-cool", "acme")
        val directOnly = CnbSCMNavigator("cnb-cool", "acme").also { it.setIncludeDescendants(false) }
        val nested = CnbSCMNavigator("cnb-cool", "acme/team").also { it.setIncludeDescendants(false) }

        assertEquals(CnbSCMNavigator.projectNameFor("acme/team/repo"), event.sourceName)
        assertEquals(20, event.sourceName.substringAfterLast('-').length)
        assertTrue(event.isMatch(recursive))
        assertFalse(event.isMatch(directOnly))
        assertTrue(event.isMatch(nested))
        assertNotEquals(
            CnbSCMNavigator.projectNameFor("another/team/repo"),
            CnbSCMNavigator.projectNameFor("acme/team/repo"),
        )
    }

    @Test
    fun `tag push type distinguishes create update and removal`() {
        val zero = "0".repeat(40)
        val old = "a".repeat(40)
        val current = "b".repeat(40)

        assertEquals(
            SCMEvent.Type.CREATED,
            CnbSCMHeadEvent.typeOf(payload(CnbWebhookEvent.TAG_PUSH, before = zero, current = current)),
        )
        assertEquals(
            SCMEvent.Type.UPDATED,
            CnbSCMHeadEvent.typeOf(payload(CnbWebhookEvent.TAG_PUSH, before = old, current = current)),
        )
        assertEquals(
            SCMEvent.Type.REMOVED,
            CnbSCMHeadEvent.typeOf(payload(CnbWebhookEvent.TAG_PUSH, before = old, current = zero)),
        )
    }

    @Test
    fun `event matches only the exact CNB source`() {
        val event = CnbSCMHeadEvent(delivery(payload(CnbWebhookEvent.PUSH, "acme/repo")))

        assertTrue(event.isMatch(CnbSCMSource("cnb-cool", "acme/repo")))
        assertFalse(event.isMatch(CnbSCMSource("another-server", "acme/repo")))
        assertFalse(event.isMatch(CnbSCMSource("cnb-cool", "acme/other")))
        assertFalse(event.isMatch(NullSCM()))
    }

    @Test
    @WithJenkins
    fun `branch head hints cover create update removal and trait filtering`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source =
            CnbSCMSource("cnb-cool", "acme/repo").also {
                it.setTraits(listOf(CnbBranchDiscoveryTrait(3)))
            }
        val created =
            CnbSCMHeadEvent(
                delivery(
                    payload(
                        CnbWebhookEvent.BRANCH_CREATE,
                        refName = "main",
                        before = ZERO_SHA,
                        current = CURRENT_SHA,
                    ),
                ),
            )
        val updated =
            CnbSCMHeadEvent(
                delivery(payload(CnbWebhookEvent.PUSH, refName = "main", before = OLD_SHA, current = CURRENT_SHA)),
            )
        val removed =
            CnbSCMHeadEvent(
                delivery(
                    payload(
                        CnbWebhookEvent.BRANCH_DELETE,
                        refName = "main",
                        before = CURRENT_SHA,
                        current = ZERO_SHA,
                    ),
                ),
            )

        assertEquals(SCMEvent.Type.CREATED, created.type)
        assertEquals(SCMEvent.Type.UPDATED, updated.type)
        assertEquals(SCMEvent.Type.REMOVED, removed.type)
        for (event in listOf(created, updated)) {
            val (head, revision) = event.heads(source).entries.single()
            assertEquals("main", (head as CnbBranchSCMHead).name)
            assertEquals(CURRENT_SHA, (revision as CnbBranchSCMRevision).hash)
        }
        val removedHeads = removed.heads(source) as Map<*, *>
        assertEquals("main", (removedHeads.keys.single() as CnbBranchSCMHead).name)
        assertNull(removedHeads.values.single())

        val tagOnly = CnbSCMSource("cnb-cool", "acme/repo").also { it.setTraits(listOf(CnbTagDiscoveryTrait())) }
        assertTrue(updated.heads(tagOnly).isEmpty())
        assertTrue(updated.description().contains("acme/repo"))
        assertTrue(updated.descriptionFor(source).contains("main"))
        val cause = updated.asCauses().single() as CnbPushCause
        assertEquals("main", cause.ref)
        assertEquals("acme/repo", cause.repositoryPath)
    }

    @Test
    @WithJenkins
    fun `tag head hints cover create update removal and trait filtering`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source = CnbSCMSource("cnb-cool", "acme/repo").also { it.setTraits(listOf(CnbTagDiscoveryTrait())) }
        val created =
            CnbSCMHeadEvent(
                delivery(
                    payload(
                        CnbWebhookEvent.TAG_PUSH,
                        refName = "v2.0.0",
                        before = ZERO_SHA,
                        current = CURRENT_SHA,
                    ),
                ),
            )
        val updated =
            CnbSCMHeadEvent(
                delivery(
                    payload(
                        CnbWebhookEvent.TAG_PUSH,
                        refName = "v2.0.0",
                        before = OLD_SHA,
                        current = CURRENT_SHA,
                    ),
                ),
            )
        val removed =
            CnbSCMHeadEvent(
                delivery(
                    payload(
                        CnbWebhookEvent.TAG_PUSH,
                        refName = "v2.0.0",
                        before = CURRENT_SHA,
                        current = ZERO_SHA,
                    ),
                ),
            )

        for (event in listOf(created, updated)) {
            val (head, revision) = event.heads(source).entries.single()
            assertEquals("v2.0.0", (head as CnbTagSCMHead).name)
            assertEquals(Instant.EPOCH.toEpochMilli(), head.timestamp)
            assertEquals(CURRENT_SHA, (revision as CnbTagSCMRevision).hash)
        }
        val removedHeads = removed.heads(source) as Map<*, *>
        assertEquals("v2.0.0", (removedHeads.keys.single() as CnbTagSCMHead).name)
        assertNull(removedHeads.values.single())

        val branchOnly = CnbSCMSource("cnb-cool", "acme/repo").also { it.setTraits(listOf(CnbBranchDiscoveryTrait(3))) }
        assertTrue(updated.heads(branchOnly).isEmpty())
    }

    @Test
    @WithJenkins
    fun `origin pull request hints expose HEAD and MERGE revisions and removal`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val source =
            CnbSCMSource("cnb-cool", "acme/repo").also {
                it.setTraits(
                    listOf(
                        CnbOriginPullRequestDiscoveryTrait(CnbOriginPullRequestDiscoveryTrait.HEAD_AND_MERGE),
                    ),
                )
            }
        val pullRequest = pullRequest(sourceRepository = "acme/repo", action = "opened")
        val opened =
            CnbSCMHeadEvent(
                delivery(payload(CnbWebhookEvent.PULL_REQUEST_TARGET, refName = "feature", pullRequest = pullRequest)),
            )

        assertEquals(SCMEvent.Type.CREATED, opened.type)
        val entries = opened.heads(source).entries.sortedBy { it.key.name }
        assertEquals(listOf("PR-42-head", "PR-42-merge"), entries.map { it.key.name })
        for ((headValue, revisionValue) in entries) {
            val head = headValue as CnbPullRequestSCMHead
            val revision = revisionValue as CnbPullRequestSCMRevision
            assertEquals(SCMHeadOrigin.DEFAULT, head.origin)
            assertEquals("feature", head.sourceBranch)
            assertEquals(TARGET_SHA, revision.baseHash)
            assertEquals(SOURCE_SHA, revision.headHash)
            assertEquals(MERGE_SHA, revision.mergeHash)
            assertEquals(head.name.endsWith("-merge"), head.checkoutStrategy == ChangeRequestCheckoutStrategy.MERGE)
        }

        val merged =
            CnbSCMHeadEvent(
                delivery(
                    payload(
                        CnbWebhookEvent.PULL_REQUEST_MERGED,
                        refName = "feature",
                        pullRequest = pullRequest.copy(action = "merged"),
                    ),
                ),
            )
        assertEquals(SCMEvent.Type.REMOVED, merged.type)
        val removedHeads = merged.heads(source) as Map<*, *>
        assertEquals(setOf("PR-42-head", "PR-42-merge"), removedHeads.keys.map { (it as CnbPullRequestSCMHead).name }.toSet())
        assertTrue(removedHeads.values.all { it == null })

        val branchesOnly = CnbSCMSource("cnb-cool", "acme/repo").also { it.setTraits(listOf(CnbBranchDiscoveryTrait(3))) }
        assertTrue(opened.heads(branchesOnly).isEmpty())
        assertTrue(
            CnbSCMHeadEvent(
                delivery(payload(CnbWebhookEvent.PULL_REQUEST_TARGET, refName = "feature", pullRequest = null)),
            ).heads(source).isEmpty(),
        )
    }

    @Test
    @WithJenkins
    fun `fork pull request hints use fork discovery strategies only`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val forkSource =
            CnbSCMSource("cnb-cool", "acme/repo").also {
                it.setTraits(
                    listOf(
                        CnbForkPullRequestDiscoveryTrait(
                            CnbForkPullRequestDiscoveryTrait.HEAD_AND_MERGE,
                            TrustNobody(),
                        ),
                    ),
                )
            }
        val originOnly =
            CnbSCMSource("cnb-cool", "acme/repo").also {
                it.setTraits(
                    listOf(CnbOriginPullRequestDiscoveryTrait(CnbOriginPullRequestDiscoveryTrait.HEAD_AND_MERGE)),
                )
            }
        val event =
            CnbSCMHeadEvent(
                delivery(
                    payload(
                        CnbWebhookEvent.PULL_REQUEST_MERGEABLE,
                        refName = "feature",
                        pullRequest = pullRequest(sourceRepository = "contributor/repo", action = "synchronize"),
                    ),
                ),
            )

        assertEquals(SCMEvent.Type.UPDATED, event.type)
        val heads = event.heads(forkSource).keys.map { it as CnbPullRequestSCMHead }
        assertEquals(setOf("PR-42-head", "PR-42-merge"), heads.map { it.name }.toSet())
        assertTrue(heads.all { it.origin is SCMHeadOrigin.Fork })
        assertTrue(heads.all { it.sourceRepository == "contributor/repo" })
        assertTrue(event.heads(originOnly).isEmpty())
    }

    @Test
    fun `navigator webhook visit fetches only the event repository`() {
        val requested = mutableListOf<String>()
        val client = targetedNavigatorClient(requested)
        val navigator = TestNavigator(client, "acme")
        val observer = RecordingObserver()
        val event = CnbSCMHeadEvent(delivery(payload(CnbWebhookEvent.PUSH, "acme/team/repo")))

        navigator.visitSources(observer, event)

        assertEquals(listOf("acme/team/repo"), requested)
        assertEquals(listOf("acme/team/repo"), observer.sources.map { (it as CnbSCMSource).repositoryPath })
    }

    @Test
    fun `navigator webhook visit ignores only a missing repository`() {
        val event = CnbSCMHeadEvent(delivery(payload(CnbWebhookEvent.PUSH, "acme/repo")))
        val observer = RecordingObserver()

        TestNavigator(failingNavigatorClient(404), "acme").visitSources(observer, event)

        assertTrue(observer.sources.isEmpty())
        val failure =
            assertThrows(CnbApiException::class.java) {
                TestNavigator(failingNavigatorClient(503), "acme").visitSources(RecordingObserver(), event)
            }
        assertEquals(503, failure.statusCode)
    }

    @Test
    fun `navigator webhook visit rejects an API repository path mismatch`() {
        val event = CnbSCMHeadEvent(delivery(payload(CnbWebhookEvent.PUSH, "acme/repo")))

        assertThrows(IOException::class.java) {
            TestNavigator(targetedNavigatorClient(mutableListOf()) { "acme/other" }, "acme")
                .visitSources(RecordingObserver(), event)
        }
    }

    private fun delivery(payload: CnbWebhookPayload): CnbWebhookDelivery = CnbWebhookDelivery("cnb-cool", payload, "test")

    private class TestNavigator(
        private val api: CnbClient,
        namespace: String,
    ) : CnbSCMNavigator("cnb-cool", namespace) {
        override fun client(context: Item?): CnbClient = api
    }

    private class RecordingObserver : SCMSourceObserver() {
        val sources = mutableListOf<SCMSource>()
        private val owner =
            Proxy.newProxyInstance(
                SCMSourceOwner::class.java.classLoader,
                arrayOf(SCMSourceOwner::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getSCMSources" -> emptyList<SCMSource>()
                    "toString" -> "RecordingSCMSourceOwner"
                    else -> null
                }
            } as SCMSourceOwner

        override fun getContext(): SCMSourceOwner = owner

        override fun getListener(): TaskListener = TaskListener.NULL

        override fun observe(projectName: String): ProjectObserver =
            object : ProjectObserver() {
                override fun addSource(source: SCMSource) {
                    sources += source
                }

                override fun addAttribute(
                    key: String,
                    value: Any?,
                ) = Unit

                override fun complete() = Unit
            }

        override fun addAttribute(
            key: String,
            value: Any?,
        ) = Unit
    }

    private fun targetedNavigatorClient(
        requested: MutableList<String>,
        returnedPath: (String) -> String = { it },
    ): CnbClient =
        Proxy.newProxyInstance(
            CnbClient::class.java.classLoader,
            arrayOf(CnbClient::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getRepository" -> {
                    val requestedPath = arguments?.single() as String
                    requested += requestedPath
                    val path = returnedPath(requestedPath)
                    CnbRepository(
                        path = path,
                        name = path.substringAfterLast('/'),
                        webUrl = "https://cnb.cool/$path",
                        cloneUrl = "https://cnb.cool/$path",
                        defaultBranch = "main",
                        archived = false,
                        visibility = "Public",
                    )
                }

                "listRepositories" -> {
                    throw AssertionError("Webhook targeting must not enumerate a namespace")
                }

                "close" -> {
                    Unit
                }

                "toString" -> {
                    "TargetedNavigatorClient"
                }

                else -> {
                    throw UnsupportedOperationException(method.name)
                }
            }
        } as CnbClient

    private fun failingNavigatorClient(status: Int): CnbClient =
        object : CnbClient by targetedNavigatorClient(mutableListOf()) {
            override fun getRepository(path: String): CnbRepository = throw CnbApiException("failure", status)
        }

    private fun payload(
        event: CnbWebhookEvent,
        repository: String = "acme/repo",
        before: String = OLD_SHA,
        current: String = CURRENT_SHA,
        refName: String = "v1",
        pullRequest: CnbWebhookPullRequest? = null,
    ): CnbWebhookPayload =
        CnbWebhookPayload(
            schema = CnbWebhookPayload.SCHEMA_V1,
            installationId = "cnb-cool",
            deliveryId = "delivery-1",
            buildId = "build-1",
            occurredAt = Instant.EPOCH,
            event = event,
            eventUrl = "",
            retry = false,
            instance = CnbWebhookInstance("https://cnb.cool", "https://api.cnb.cool"),
            repository = CnbWebhookRepository("repo-id", repository, "https://cnb.cool/$repository"),
            actor = CnbWebhookActor("user-id", "user", "User", ""),
            ref = CnbWebhookRef(refName, current, before, current, event == CnbWebhookEvent.TAG_PUSH),
            pullRequest = pullRequest,
        )

    private fun pullRequest(
        sourceRepository: String,
        action: String,
    ): CnbWebhookPullRequest =
        CnbWebhookPullRequest(
            id = "pull-42",
            number = "42",
            title = "Improve CNB integration",
            description = "",
            proposer = "contributor",
            sourceRepository = sourceRepository,
            sourceBranch = "feature",
            sourceSha = SOURCE_SHA,
            targetBranch = "main",
            targetSha = TARGET_SHA,
            mergeSha = MERGE_SHA,
            action = action,
            wip = false,
        )

    companion object {
        private val ZERO_SHA = "0".repeat(40)
        private val OLD_SHA = "a".repeat(40)
        private val CURRENT_SHA = "b".repeat(40)
        private val SOURCE_SHA = "c".repeat(40)
        private val TARGET_SHA = "d".repeat(40)
        private val MERGE_SHA = "e".repeat(40)
    }
}
