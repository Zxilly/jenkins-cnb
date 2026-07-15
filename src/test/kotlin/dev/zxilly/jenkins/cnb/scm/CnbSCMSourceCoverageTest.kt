package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbContent
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbRepository
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import hudson.model.TaskListener
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMHeadObserver
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.SCMSourceCriteria
import jenkins.scm.api.metadata.ContributorMetadataAction
import jenkins.scm.api.metadata.ObjectMetadataAction
import jenkins.scm.api.metadata.PrimaryInstanceMetadataAction
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.io.IOException
import java.lang.reflect.Proxy

@WithJenkins
class CnbSCMSourceCoverageTest {
    @Test
    fun `complete discovery publishes branches tags and both pull request strategies`(jenkins: JenkinsRule) {
        assertFalse(jenkins.jenkins.isUseSecurity)
        val fixture = Fixture()
        val source = TestSource { fixture.client() }
        source.setTraits(
            listOf(
                CnbBranchDiscoveryTrait(3),
                CnbTagDiscoveryTrait(),
                CnbOriginPullRequestDiscoveryTrait(3),
                CnbForkPullRequestDiscoveryTrait(3, TrustEveryone()),
            ),
        )
        val collector = SCMHeadObserver.collect()
        val criteria = SCMSourceCriteria { probe, _ -> probe.stat("Jenkinsfile").exists() }

        source.fetch(criteria, collector, TaskListener.NULL)

        val revisions = collector.result()
        assertEquals(
            setOf("main", "feature", "PR-1-head", "PR-1-merge", "PR-2-head", "PR-2-merge", "v1"),
            revisions.keys.mapTo(linkedSetOf()) { it.name },
        )
        assertInstanceOf(CnbTagSCMRevision::class.java, revisions.entries.single { it.key.name == "v1" }.value)
        val forkMerge = revisions.entries.single { it.key.name == "PR-2-merge" }.value as CnbPullRequestSCMRevision
        assertEquals("current-release", forkMerge.baseHash)
        assertEquals(1, fixture.closeCount)
        assertTrue(fixture.contentCalls.any { it.first == "fork/repo" && it.third == "fork-head" })
    }

    @Test
    fun `branch discovery strategies filter branches that have origin pull requests`(jenkins: JenkinsRule) {
        assertFalse(jenkins.jenkins.isUseSecurity)
        val fixture = Fixture()
        val withoutPullRequests = TestSource { fixture.client() }
        withoutPullRequests.setTraits(listOf(CnbBranchDiscoveryTrait(1)))
        val onlyPullRequests = TestSource { fixture.client() }
        onlyPullRequests.setTraits(listOf(CnbBranchDiscoveryTrait(2)))

        assertEquals(setOf("main"), withoutPullRequests.fetch(TaskListener.NULL).mapTo(linkedSetOf()) { it.name })
        assertEquals(setOf("feature"), onlyPullRequests.fetch(TaskListener.NULL).mapTo(linkedSetOf()) { it.name })
    }

    @Test
    fun `source request derives immutable targeted API selections and strategy routing`() {
        val source = TestSource { Fixture().client() }
        val originHead = pullRequestHead("1", SCMHeadOrigin.DEFAULT, ChangeRequestCheckoutStrategy.HEAD, "team/repo")
        val forkHead = pullRequestHead("2", SCMHeadOrigin.Fork("fork/repo"), ChangeRequestCheckoutStrategy.MERGE, "fork/repo")
        val collector = SCMHeadObserver.collect()
        val observer =
            SCMHeadObserver.filter(
                collector,
                CnbBranchSCMHead("main"),
                CnbTagSCMHead("v1", 99),
                originHead,
                forkHead,
            )
        val context =
            CnbSCMSourceContext(null, observer)
                .wantBranches(true)
                .wantTags(true)
                .wantOriginPullRequests(true)
                .wantForkPullRequests(true)
                .withOriginPullRequestStrategies(setOf(ChangeRequestCheckoutStrategy.HEAD))
                .withForkPullRequestStrategies(setOf(ChangeRequestCheckoutStrategy.MERGE))

        context.newRequest(source, TaskListener.NULL).use { request ->
            assertEquals("team/repo", request.sourceRepositoryPath)
            assertEquals(setOf("1", "2"), request.requestedPullRequestNumbers)
            assertEquals(setOf("main", "feature"), request.requestedBranchNames)
            assertEquals(setOf("v1"), request.requestedTagNames)
            assertEquals(setOf(ChangeRequestCheckoutStrategy.HEAD), request.strategiesFor(false))
            assertEquals(setOf(ChangeRequestCheckoutStrategy.MERGE), request.strategiesFor(true))
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (request.requestedBranchNames as MutableSet<String>).add("other")
            }
        }

        val disabled = CnbSCMSourceContext(null, SCMHeadObserver.none()).newRequest(source, null)
        disabled.use {
            assertTrue(it.strategiesFor(false).isEmpty())
            assertTrue(it.strategiesFor(true).isEmpty())
        }
    }

    @Test
    fun `direct head lookup preserves CNB revision types and current pull request base`() {
        val fixture = Fixture()
        val source = TestSource { fixture.client() }

        val branch = source.fetch(CnbBranchSCMHead("main"), TaskListener.NULL)
        val tag = source.fetch(CnbTagSCMHead("v1", 99), TaskListener.NULL)
        val missingTag = source.fetch(CnbTagSCMHead("missing", 0), TaskListener.NULL)
        val pullRequest =
            source.fetch(
                pullRequestHead("2", SCMHeadOrigin.Fork("fork/repo"), ChangeRequestCheckoutStrategy.HEAD, "fork/repo"),
                TaskListener.NULL,
            )

        assertEquals("main-sha", (branch as CnbBranchSCMRevision).hash)
        assertEquals("tag-sha", (tag as CnbTagSCMRevision).hash)
        assertNull(missingTag)
        assertEquals("current-release", (pullRequest as CnbPullRequestSCMRevision).baseHash)
        assertNull(source.fetch(SCMHead("other"), TaskListener.NULL))
    }

    @Test
    fun `source actions expose repository branch pull request and tag metadata`() {
        val fixture = Fixture()
        val source = TestSource { fixture.client() }
        val repositoryAction = source.sourceActions().single() as ObjectMetadataAction
        val defaultBranch = source.headActions(CnbBranchSCMHead("main"))
        val featureBranch = source.headActions(CnbBranchSCMHead("feature/a b"))
        val pullRequest =
            source.headActions(
                pullRequestHead("2", SCMHeadOrigin.Fork("fork/repo"), ChangeRequestCheckoutStrategy.HEAD, "fork/repo"),
            )
        val tag = source.headActions(CnbTagSCMHead("v1", 99)).single() as ObjectMetadataAction

        assertEquals("repo", repositoryAction.objectDisplayName)
        assertEquals("https://cnb.cool/team/repo", repositoryAction.objectUrl)
        assertTrue(defaultBranch.any { it is PrimaryInstanceMetadataAction })
        assertEquals(
            "https://cnb.cool/team/repo/-/tree/feature%2Fa%20b",
            (featureBranch.single() as ObjectMetadataAction).objectUrl,
        )
        assertEquals("Fork change", pullRequest.filterIsInstance<ObjectMetadataAction>().single().objectDisplayName)
        assertEquals("contributor", pullRequest.filterIsInstance<ContributorMetadataAction>().single().contributor)
        assertEquals("https://cnb.cool/team/repo", tag.objectUrl)
        assertTrue(source.headActions(SCMHead("other")).isEmpty())
    }

    @Test
    fun `trusted revision policy keeps origin and protects fork Jenkinsfiles`(jenkins: JenkinsRule) {
        assertFalse(jenkins.jenkins.isUseSecurity)
        val fixture = Fixture()
        val source = TestSource { fixture.client() }
        source.setTraits(listOf(CnbForkPullRequestDiscoveryTrait(1, TrustNobody())))
        val forkHead = pullRequestHead("2", SCMHeadOrigin.Fork("fork/repo"), ChangeRequestCheckoutStrategy.HEAD, "fork/repo")
        val forkRevision = CnbPullRequestSCMRevision(forkHead, "base", "head", null)
        val trustedFork = source.getTrustedRevision(forkRevision, TaskListener.NULL)
        val originHead = pullRequestHead("1", SCMHeadOrigin.DEFAULT, ChangeRequestCheckoutStrategy.HEAD, "team/repo")
        val originRevision = CnbPullRequestSCMRevision(originHead, "base", "head", null)
        val branchRevision = CnbBranchSCMRevision(CnbBranchSCMHead("main"), "sha")

        assertInstanceOf(CnbBranchSCMRevision::class.java, trustedFork)
        assertSame(originRevision, source.getTrustedRevision(originRevision, TaskListener.NULL))
        assertSame(branchRevision, source.getTrustedRevision(branchRevision, TaskListener.NULL))
    }

    @Test
    fun `targeted lookup handles closed missing mismatched and server failures`() {
        val fixture = Fixture()
        val source = TestSource { fixture.client() }

        assertEquals(listOf("1"), source.pullRequestsFor(fixture.client(), "team/repo", setOf("1", "closed")).map { it.number })
        val missingClient =
            object : CnbClient by fixture.client() {
                override fun getPullRequest(
                    repo: String,
                    number: String,
                ): CnbPullRequest = throw CnbApiException("missing", 404)
            }
        assertTrue(source.pullRequestsFor(missingClient, "team/repo", setOf("404")).isEmpty())
        assertThrows(IOException::class.java) {
            source.pullRequestsFor(fixture.client(), "team/repo", setOf("mismatch"))
        }
        val unavailableClient =
            object : CnbClient by fixture.client() {
                override fun getPullRequest(
                    repo: String,
                    number: String,
                ): CnbPullRequest = throw CnbApiException("unavailable", 503)
            }
        val failure =
            assertThrows(CnbApiException::class.java) {
                source.pullRequestsFor(unavailableClient, "team/repo", setOf("503"))
            }
        assertEquals(503, failure.statusCode)
        assertEquals(2, source.pullRequestsFor(fixture.client(), "team/repo", null).size)
    }

    @Test
    fun `source configuration is defensive and rejects API repository identity changes`() {
        assertThrows(IllegalArgumentException::class.java) { CnbSCMSource(" ", "team/repo") }
        assertThrows(IllegalArgumentException::class.java) { CnbSCMSource("cnb", "repo") }
        assertThrows(IllegalArgumentException::class.java) { CnbSCMSource("cnb", "team/../repo") }
        val source = TestSource { Fixture().client() }
        source.setCredentialsId(" checkout ")
        source.setApiCredentialsId(" api ")
        source.setCheckoutCredentialsId(" explicit ")
        source.setTraits(listOf(CnbTagDiscoveryTrait()))
        val copy = source.traits

        assertEquals("explicit", source.credentialsId)
        assertEquals("api", source.getApiCredentialsId())
        assertEquals(1, copy.size)
        assertNotSame(copy, source.traits)
        assertThrows(IOException::class.java) {
            source.remember(Fixture.repository.copy(path = "other/repo"))
        }
    }

    private class TestSource(
        private val clientFactory: () -> CnbClient,
    ) : CnbSCMSource("cnb-cool", "team/repo") {
        override fun client(): CnbClient = clientFactory()

        fun sourceActions() = retrieveActions(null, TaskListener.NULL)

        fun headActions(head: SCMHead) = retrieveActions(head, null, TaskListener.NULL)
    }

    private class Fixture {
        var closeCount = 0
        val contentCalls = mutableListOf<Triple<String, String, String>>()

        fun client(): CnbClient =
            Proxy.newProxyInstance(
                CnbClient::class.java.classLoader,
                arrayOf(CnbClient::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getCapabilities" -> {
                        CnbApiCapabilities()
                    }

                    "getRepository" -> {
                        repository
                    }

                    "listBranches" -> {
                        branches
                    }

                    "getBranch" -> {
                        val name = arguments!![1] as String
                        branches.firstOrNull { it.name == name } ?: CnbBranch(name, "current-$name")
                    }

                    "listTags" -> {
                        tags
                    }

                    "listPullRequests" -> {
                        pullRequests
                    }

                    "getPullRequest" -> {
                        pullRequest(arguments!![1] as String)
                    }

                    "getContent" -> {
                        val call = Triple(arguments!![0] as String, arguments[1] as String, arguments[2] as String)
                        contentCalls += call
                        CnbContent(call.second, "blob", "file", 1, "x", "text")
                    }

                    "close" -> {
                        closeCount++
                        Unit
                    }

                    "toString" -> {
                        "Source fixture client"
                    }

                    "hashCode" -> {
                        System.identityHashCode(proxy)
                    }

                    "equals" -> {
                        proxy === arguments?.firstOrNull()
                    }

                    else -> {
                        throw UnsupportedOperationException(method.name)
                    }
                }
            } as CnbClient

        private fun pullRequest(number: String): CnbPullRequest =
            when (number) {
                "1" -> pullRequests[0]
                "2" -> pullRequests[1]
                "closed" -> pullRequests[0].copy(number = number, state = "merged")
                "404" -> throw CnbApiException("missing", 404)
                "503" -> throw CnbApiException("unavailable", 503)
                "mismatch" -> pullRequests[0].copy(number = "different")
                else -> throw CnbApiException("missing", 404)
            }

        companion object {
            val repository =
                CnbRepository(
                    "team/repo",
                    "repo",
                    "https://cnb.cool/team/repo",
                    "https://cnb.cool/team/repo",
                    "main",
                    false,
                    "Public",
                )
            val branches = listOf(CnbBranch("main", "main-sha"), CnbBranch("feature", "feature-sha"))
            val tags = listOf(CnbTag("v1", "tag-sha", 99))
            val pullRequests =
                listOf(
                    CnbPullRequest(
                        "1",
                        "Origin change",
                        "open",
                        "team/repo",
                        "feature",
                        "origin-head",
                        "team/repo",
                        "main",
                        "old-main",
                        "origin-merge",
                        "owner",
                        false,
                    ),
                    CnbPullRequest(
                        "2",
                        "Fork change",
                        "open",
                        "fork/repo",
                        "topic",
                        "fork-head",
                        "team/repo",
                        "release",
                        "old-release",
                        "fork-merge",
                        "contributor",
                        true,
                    ),
                )
        }
    }

    companion object {
        private fun pullRequestHead(
            number: String,
            origin: SCMHeadOrigin,
            strategy: ChangeRequestCheckoutStrategy,
            sourceRepository: String,
        ): CnbPullRequestSCMHead =
            CnbPullRequestSCMHead(
                "PR-$number",
                number,
                CnbBranchSCMHead(if (number == "2") "release" else "main"),
                strategy,
                origin,
                sourceRepository,
                if (number == "2") "topic" else "feature",
                if (number == "2") "contributor" else "owner",
                "Change",
            )
    }
}
