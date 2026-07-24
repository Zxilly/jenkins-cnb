package dev.zxilly.jenkins.cnb.trigger

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEvent
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEventPayload
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEventType
import dev.zxilly.jenkins.cnb.api.model.CnbCommit
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.health.CnbHealthComponent
import dev.zxilly.jenkins.cnb.health.CnbOperationalHealth
import dev.zxilly.jenkins.cnb.scm.CnbSCMSource
import hudson.model.Action
import hudson.model.Queue
import hudson.model.TaskListener
import hudson.plugins.git.GitSCM
import hudson.plugins.git.RevisionParameterAction
import hudson.util.StreamTaskListener
import jenkins.branch.BranchSource
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@WithJenkins
class CnbRepositoryEventPollingWorkIntegrationTest {
    @Test
    fun `polling converges with the latest webhook revision but permits A to B to A`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("cross-channel-history")
        val trigger = CnbPushTrigger("primary", "team/project", "main").apply { setCiSkip(false) }
        val shaA = "a".repeat(40)
        val shaB = "b".repeat(40)
        fun identity(sha: String) = CnbQueueIdentity("primary", "team/project", "refs/heads/main", sha)
        fun event(id: String, sha: String) =
            repositoryEvent("team/project", id).copy(payload = CnbRepositoryEventPayload(ref = "refs/heads/main", head = sha))

        jenkins.assertBuildStatusSuccess(
            project.scheduleBuild2(0, CnbQueueAction(identity(shaA), "webhook-a")),
        )
        CnbPushTriggerRecovery.recover("primary", "team/project", listOf(event("poll-a-duplicate", shaA)), project, trigger)
        jenkins.waitUntilNoActivity()
        assertEquals(1, project.builds.count())

        jenkins.assertBuildStatusSuccess(
            project.scheduleBuild2(0, CnbQueueAction(identity(shaB), "webhook-b")),
        )
        CnbPushTriggerRecovery.recover("primary", "team/project", listOf(event("poll-a-return", shaA)), project, trigger)
        jenkins.waitUntilNoActivity()

        assertEquals(3, project.builds.count())
        assertEquals(shaA, requireNotNull(requireNotNull(project.lastBuild).getAction(CnbQueueAction::class.java)).sha)
    }

    @Test
    fun `classic polling recovery honors CI skip and pins a matching GitSCM revision`(jenkins: JenkinsRule) {
        jenkins.jenkins.numExecutors = 0
        val project = jenkins.createFreeStyleProject("classic-policy-recovery")
        project.scm = GitSCM("https://cnb.cool/team/project.git")
        val trigger = CnbPushTrigger("primary", "team/project", "main")
        val event = repositoryEvent("team/project", "policy-event")

        CnbPushTriggerRecovery.recover(
            "primary",
            "team/project",
            listOf(event),
            project,
            trigger,
            getCommit = { _, sha -> CnbCommit(sha, "do not recover [skip ci]") },
            loadSourceCloneUrl = { "https://cnb.cool/team/project" },
        )
        assertTrue(Queue.getInstance().items.isEmpty())

        CnbPushTriggerRecovery.recover(
            "primary",
            "team/project",
            listOf(event),
            project,
            trigger,
            getCommit = { _, sha -> CnbCommit(sha, "recover this revision") },
            loadSourceCloneUrl = { "https://cnb.cool/team/other" },
        )
        assertTrue(Queue.getInstance().items.isEmpty(), "A GitSCM job must not run without a trusted revision action")

        CnbPushTriggerRecovery.recover(
            "primary",
            "team/project",
            listOf(event),
            project,
            trigger,
            getCommit = { _, sha -> CnbCommit(sha, "recover this revision") },
            loadSourceCloneUrl = {
                val executor = Executors.newSingleThreadExecutor()
                try {
                    executor
                        .submit { Queue.withLock(Runnable {}) }
                        .get(5, TimeUnit.SECONDS)
                    "https://cnb.cool/team/project"
                } finally {
                    executor.shutdownNow()
                }
            },
        )

        val queued = Queue.getInstance().items.single()
        assertEquals(COMMIT, requireNotNull(queued.getAction(RevisionParameterAction::class.java)).commit)
        assertEquals(COMMIT, requireNotNull(queued.getAction(CnbQueueAction::class.java)).sha)
        Queue.getInstance().cancel(queued)
    }

    @Test
    fun `queue refusal leaves a recovered event available for retry`(jenkins: JenkinsRule) {
        val api = startApi { exchange -> respond(exchange, 200, pushEvents("team/project", "retry-event")) }
        val veto =
            object : Queue.QueueDecisionHandler() {
                override fun shouldSchedule(
                    task: Queue.Task,
                    actions: MutableList<Action>,
                ): Boolean = false
            }
        try {
            configureServer(api)
            val project = jenkins.createFreeStyleProject("classic-retry")
            project.addTrigger(CnbPushTrigger("primary", "team/project", "main").apply { setCiSkip(false) })
            Queue.QueueDecisionHandler.all().add(veto)

            CnbRepositoryEventPollingWork(CnbOperationalHealth()).runOnce(TaskListener.NULL)
            jenkins.waitUntilNoActivity()
            assertNull(project.lastBuild)

            Queue.QueueDecisionHandler.all().remove(veto)
            CnbRepositoryEventPollingWork(CnbOperationalHealth()).runOnce(TaskListener.NULL)
            jenkins.waitUntilNoActivity()

            val build = requireNotNull(project.lastBuild)
            assertEquals("retry-event", requireNotNull(build.getCause(CnbRepositoryEventCause::class.java)).eventId)
        } finally {
            Queue.QueueDecisionHandler.all().remove(veto)
            api.stop(0)
        }
    }

    @Test
    fun `production polling recovers a classic job and persists cursor and dedup state`(jenkins: JenkinsRule) {
        val requests = CopyOnWriteArrayList<String>()
        val api =
            startApi { exchange ->
                if (exchange.requestURI.path.startsWith("/events/team/project/")) {
                    requests += exchange.requestURI.path
                }
                respond(exchange, 200, pushEvents("team/project", "archive-event-1"))
            }
        try {
            configureServer(api)
            val project = jenkins.createFreeStyleProject("classic-polling")
            project.addTrigger(CnbPushTrigger("primary", "team/project", "main").apply { setCiSkip(false) })
            val work = CnbRepositoryEventPollingWork(CnbOperationalHealth())

            assertEquals(60_000L, work.getRecurrencePeriod())
            assertEquals(60_000L, work.getInitialDelay())
            work.runOnce(TaskListener.NULL)
            val requestsAfterFirstPoll = requests.size
            work.runOnce(TaskListener.NULL)
            jenkins.waitUntilNoActivity()

            val archiveHours = requests.take(requestsAfterFirstPoll).map(::hourFromRequest).toSet()
            assertEquals(2, archiveHours.size, "The first poll reads the completed and current UTC hours")
            assertEquals(requestsAfterFirstPoll, requests.size, "A second tick before nextDue must not scan or poll")
            val build = requireNotNull(project.lastBuild)
            val cause = requireNotNull(build.getCause(CnbRepositoryEventCause::class.java))
            assertEquals("archive-event-1", cause.eventId)
            assertEquals("main", cause.ref)
            assertEquals(COMMIT, cause.commit)

            val stateDirectory =
                jenkins.jenkins.rootDir
                    .toPath()
                    .resolve("cnb")
            val cursorPath = stateDirectory.resolve("repository-event-cursors.properties")
            val dedupPath = stateDirectory.resolve("repository-event-dedup.properties")
            assertTrue(Files.exists(cursorPath))
            assertTrue(Files.exists(dedupPath))
            assertFalse(Files.readString(cursorPath).contains("team/project"))
            assertFalse(Files.readString(dedupPath).contains("team/project"))

            val completedHour = archiveHours.min()
            val consumer = CnbRepositoryEventConsumer("1\u0000classic-polling", "classic-polling") {}
            val repository =
                CnbWatchedRepository(
                    "primary",
                    "team/project",
                    consumers = listOf(consumer),
                    authorizationScope = SERVER_AUTHORIZATION_SCOPE,
                )
            assertEquals(
                completedHour,
                CnbRepositoryEventCursorStore(cursorPath).get(repository.cursorScope(), completedHour),
            )
            val key =
                CnbRepositoryEventPollingWork.eventKey(
                    "primary",
                    "team/project",
                    repositoryEvent("team/project", "archive-event-1"),
                )
            assertTrue(
                CnbRepositoryEventDedupStore(dedupPath).contains(repository.stateScope(consumer), key, Instant.now()),
            )
        } finally {
            api.stop(0)
        }
    }

    @Test
    fun `one repository API failure is logged without blocking a healthy repository`(jenkins: JenkinsRule) {
        val health = CnbOperationalHealth()
        val api =
            startApi { exchange ->
                when {
                    exchange.requestURI.path.startsWith("/events/team/broken/") -> {
                        respond(
                            exchange,
                            400,
                            "{\"errcode\":\"INVALID_ARGUMENT\",\"errmsg\":\"token=polling-secret-must-not-leak\"}",
                        )
                    }

                    exchange.requestURI.path.startsWith("/events/team/healthy/") -> {
                        respond(exchange, 200, pushEvents("team/healthy", "healthy-event"))
                    }

                    else -> {
                        respond(exchange, 404, "{}")
                    }
                }
            }
        try {
            configureServer(api)
            val broken = jenkins.createFreeStyleProject("broken-polling")
            broken.addTrigger(CnbPushTrigger("primary", "team/broken", "main").apply { setCiSkip(false) })
            val healthy = jenkins.createFreeStyleProject("healthy-polling")
            healthy.addTrigger(CnbPushTrigger("primary", "team/healthy", "main").apply { setCiSkip(false) })
            val output = ByteArrayOutputStream()
            val listener = StreamTaskListener(output, StandardCharsets.UTF_8)

            CnbRepositoryEventPollingWork(health).runOnce(listener)
            jenkins.waitUntilNoActivity()
            listener.logger.flush()

            assertNull(broken.lastBuild)
            val build = requireNotNull(healthy.lastBuild)
            assertEquals("healthy-event", requireNotNull(build.getCause(CnbRepositoryEventCause::class.java)).eventId)
            assertTrue(
                output.toString(StandardCharsets.UTF_8).contains(
                    "CNB event polling failed for primary/team/broken: CnbApiException",
                ),
            )
            val entries = health.snapshot().entries.associateBy { it.repository.substringBefore(" [authorization context ") }
            val brokenEntry = requireNotNull(entries["team/broken"])
            val healthyEntry = requireNotNull(entries["team/healthy"])
            assertEquals(CnbHealthComponent.POLLING, brokenEntry.component)
            assertEquals("class=Cnb.Api.Exception", brokenEntry.summary)
            assertTrue(brokenEntry.lastFailureAt != null)
            assertNull(brokenEntry.lastSuccessAt)
            assertEquals("processed=1", healthyEntry.summary)
            assertTrue(healthyEntry.lastSuccessAt != null)
            assertNull(healthyEntry.lastFailureAt)
            assertFalse(health.snapshot().entries.any { "polling-secret" in it.summary })
        } finally {
            api.stop(0)
        }
    }

    @Test
    fun `SCM source discovery never falls back from a missing item credential`(jenkins: JenkinsRule) {
        val requests = AtomicInteger()
        val api =
            startApi { exchange ->
                if (exchange.requestURI.path.startsWith("/events/team/source/")) {
                    requests.incrementAndGet()
                }
                respond(exchange, 200, "[]")
            }
        try {
            configureServer(api)
            val project =
                jenkins.jenkins.createProject(
                    WorkflowMultiBranchProject::class.java,
                    "cnb-multibranch",
                )
            val source = CnbSCMSource("primary", "team/source")
            source.setApiCredentialsId("missing-item-credential")
            project.sourcesList.add(BranchSource(source))
            val health = CnbOperationalHealth()
            val work = CnbRepositoryEventPollingWork(health)

            work.runOnce(TaskListener.NULL)
            val requestsAfterFirstPoll = requests.get()
            work.runOnce(TaskListener.NULL)

            assertEquals(0, requestsAfterFirstPoll)
            assertEquals(requestsAfterFirstPoll, requests.get())
            assertTrue(project.items.isEmpty())
            assertTrue(
                health
                    .snapshot()
                    .entries
                    .single()
                    .lastFailureAt != null,
            )
        } finally {
            api.stop(0)
        }
    }

    @Test
    fun `SCM source without explicit credential still retains its owner authorization context`(jenkins: JenkinsRule) {
        val project =
            jenkins.jenkins.createProject(
                WorkflowMultiBranchProject::class.java,
                "owner-scoped-source",
            )

        val candidate = CnbRepositoryEventCredentialCandidate.item(null, project, "source:${project.fullName}")

        assertNull(candidate.credentialsId)
        assertSame(project, candidate.context)
        assertTrue(candidate.itemScoped)
    }

    private fun configureServer(api: HttpServer) {
        val endpoint = "http://127.0.0.1:${api.address.port}"
        val server = CnbServer("primary", "Primary", endpoint, endpoint)
        server.setAllowInsecureHttp(true)
        server.setAllowPrivateNetwork(true)
        server.setEventPollingEnabled(true)
        server.setEventPollingIntervalSeconds(60)
        CnbGlobalConfiguration.get().setServers(listOf(server))
    }

    private fun startApi(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }
            start()
        }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun pushEvents(
        repository: String,
        id: String,
    ): String =
        """
        [
          {
            "id": "$id",
            "type": "PushEvent",
            "created_at": "2026-07-15T10:15:00Z",
            "repo": {"path": "$repository"},
            "payload": {"ref": "refs/heads/main", "head": "$COMMIT"}
          }
        ]
        """.trimIndent()

    private fun repositoryEvent(
        repository: String,
        id: String,
    ): CnbRepositoryEvent =
        CnbRepositoryEvent(
            id = id,
            type = CnbRepositoryEventType("PushEvent"),
            repositoryPath = repository,
            createdAt = "2026-07-15T10:15:00Z",
            payload = CnbRepositoryEventPayload(ref = "refs/heads/main", head = COMMIT),
        )

    private fun hourFromRequest(path: String): Instant {
        val parts = path.substringAfterLast('/').split('-')
        require(parts.size == 4) { "Unexpected CNB event archive path: $path" }
        return LocalDate
            .of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            .atTime(parts[3].toInt(), 0)
            .toInstant(ZoneOffset.UTC)
    }

    companion object {
        private val COMMIT = "b".repeat(40)
    }
}
