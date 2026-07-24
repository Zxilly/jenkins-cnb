package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEvent
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEventPayload
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEventType
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryRefType
import dev.zxilly.jenkins.cnb.health.CnbOperationalHealth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.Properties

class CnbRepositoryEventPollingWorkTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `does not enumerate watched repositories when no server is due`() {
        val now = Instant.parse("2026-07-15T10:30:00Z")
        val cycle =
            CnbRepositoryEventPollCycle(
                mutableMapOf("primary" to now.plusSeconds(30)),
            )
        var enumerations = 0

        cycle.run(
            now = now,
            servers = listOf(CnbRepositoryEventPollingServer("primary", 120)),
            discover = {
                enumerations++
                emptyList()
            },
            poll = { error("No repository should be polled") },
        )

        assertEquals(0, enumerations)
    }

    @Test
    fun `discovers once and reuses the snapshot for every due server`() {
        val now = Instant.parse("2026-07-15T10:30:00Z")
        val cycle = CnbRepositoryEventPollCycle(mutableMapOf())
        var enumerations = 0
        val discoveredServerSets = mutableListOf<Set<String>>()
        val polled = mutableListOf<String>()

        cycle.run(
            now = now,
            servers =
                listOf(
                    CnbRepositoryEventPollingServer("secondary", 120),
                    CnbRepositoryEventPollingServer("primary", 60),
                ),
            discover = { serverIds ->
                enumerations++
                discoveredServerSets += serverIds
                listOf(
                    CnbWatchedRepository("secondary", "team/two"),
                    CnbWatchedRepository("primary", "team/one"),
                )
            },
            poll = { repository -> polled += "${repository.serverId}/${repository.repositoryPath}" },
        )

        assertEquals(1, enumerations)
        assertEquals(listOf(setOf("primary", "secondary")), discoveredServerSets)
        assertEquals(listOf("primary/team/one", "secondary/team/two"), polled)
    }

    @Test
    fun `repository watches remain partitioned by Jenkins authorization scope`() {
        val noOp: (List<CnbRepositoryEvent>) -> Unit = {}
        val repositories =
            aggregateWatchedRepositories(
                listOf(
                    CnbRepositoryEventWatch(
                        "primary",
                        "team/project",
                        CnbRepositoryEventConsumer("1:job-z", "job z", noOp),
                        listOf(CnbRepositoryEventCredentialCandidate.server()),
                        authorizationScope = "server",
                    ),
                    CnbRepositoryEventWatch(
                        "primary",
                        "team/project",
                        CnbRepositoryEventConsumer("0:source-z", "source z", noOp),
                        listOf(
                            CnbRepositoryEventCredentialCandidate("z-token", null, "source:z", itemScoped = true),
                        ),
                        authorizationScope = "source:z",
                    ),
                    CnbRepositoryEventWatch(
                        "primary",
                        "team/project",
                        CnbRepositoryEventConsumer("0:source-a", "source a", noOp),
                        listOf(
                            CnbRepositoryEventCredentialCandidate("a-token", null, "source:a", itemScoped = true),
                        ),
                        authorizationScope = "source:a",
                    ),
                ),
            ).associateBy { it.authorizationScope }

        assertEquals(setOf("server", "source:a", "source:z"), repositories.keys)
        assertEquals(listOf("1:job-z"), requireNotNull(repositories["server"]).consumers.map { it.stableKey })
        assertEquals(
            listOf("server/<server>"),
            requireNotNull(repositories["server"])
                .credentialCandidates
                .map { "${it.origin}/${it.credentialsId ?: "<server>"}" },
        )
        assertEquals(listOf("0:source-a"), requireNotNull(repositories["source:a"]).consumers.map { it.stableKey })
        assertEquals(
            listOf("source:a/a-token"),
            requireNotNull(repositories["source:a"])
                .credentialCandidates
                .map { "${it.origin}/${it.credentialsId ?: "<server>"}" },
        )
        assertEquals(listOf("0:source-z"), requireNotNull(repositories["source:z"]).consumers.map { it.stableKey })
        assertEquals(
            listOf("source:z/z-token"),
            requireNotNull(repositories["source:z"])
                .credentialCandidates
                .map { "${it.origin}/${it.credentialsId ?: "<server>"}" },
        )
    }

    @Test
    fun `private Folder credential never dispatches another Folder or Classic consumer`() {
        val deliveries = linkedMapOf("folder-a" to 0, "folder-b" to 0, "classic" to 0)

        fun consumer(name: String) = CnbRepositoryEventConsumer(name, name) { deliveries.computeIfPresent(name) { _, count -> count + 1 } }
        val targets =
            aggregateWatchedRepositories(
                listOf(
                    CnbRepositoryEventWatch(
                        "primary",
                        "private/project",
                        consumer("folder-a"),
                        listOf(CnbRepositoryEventCredentialCandidate("private-a", null, "folder-a", itemScoped = true)),
                        authorizationScope = "item:folder-a",
                    ),
                    CnbRepositoryEventWatch(
                        "primary",
                        "private/project",
                        consumer("folder-b"),
                        listOf(CnbRepositoryEventCredentialCandidate("private-b", null, "folder-b", itemScoped = true)),
                        authorizationScope = "item:folder-b",
                    ),
                    CnbRepositoryEventWatch(
                        "primary",
                        "private/project",
                        consumer("classic"),
                        listOf(CnbRepositoryEventCredentialCandidate.server()),
                        authorizationScope = SERVER_AUTHORIZATION_SCOPE,
                    ),
                ),
            )
        val folderA = requireNotNull(targets.singleOrNull { it.authorizationScope == "item:folder-a" })
        val archivedEvent =
            event(
                "PushEvent",
                CnbRepositoryEventPayload(ref = "refs/heads/main"),
                repositoryPath = "private/project",
            )

        val fetched =
            CnbRepositoryEventClientSelector(
                "primary",
                folderA.credentialCandidates,
            ) { _, candidate ->
                check(candidate.origin == "folder-a") { "Folder A must not borrow another authorization context" }
                testSession(mutableListOf(), candidate.origin) { listOf(archivedEvent) }
            }.use { selector ->
                selector.listRepositoryEvents(
                    "private/project",
                    java.time.ZonedDateTime.parse("2026-07-15T10:00:00Z"),
                )
            }
        CnbRepositoryEventDispatcher.dispatch(
            folderA,
            fetched,
            Instant.parse("2026-07-15T10:30:00Z"),
            CnbRepositoryEventDedupStore(directory.resolve("folder-isolation.journal")),
        )

        assertEquals(mapOf("folder-a" to 1, "folder-b" to 0, "classic" to 0), deliveries)
        assertTrue(folderA.credentialCandidates.none { it.origin == "folder-b" })
    }

    @Test
    fun `polling health keeps success and failure separate without exposing authorization identifiers`() {
        val health = CnbOperationalHealth()
        val successful =
            CnbWatchedRepository(
                "primary",
                "private/project",
                authorizationScope = "item:folder-a:credential-secret-a",
            )
        val failed =
            CnbWatchedRepository(
                "primary",
                "private/project",
                authorizationScope = "item:folder-b:credential-secret-b",
            )

        health.recordPolling("primary", successful.healthRepository(), successful = true, summary = "processed=1")
        health.recordPolling("primary", failed.healthRepository(), successful = false, summary = "class=Forbidden")

        val entries = health.snapshot().entries
        assertEquals(2, entries.size)
        assertEquals(setOf(true, false), entries.map { it.lastSuccessAt != null }.toSet())
        assertEquals(2, entries.map { it.repository }.toSet().size)
        assertTrue(entries.all { it.repository.startsWith("private/project [authorization context ") })
        assertTrue(entries.none { "folder-" in it.repository || "credential-secret" in it.repository })
    }

    @Test
    fun `credential selector uses and closes exactly one authorized context`() {
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val expected = listOf(event("PushEvent"))
        val selector =
            CnbRepositoryEventClientSelector(
                serverId = "primary",
                candidates =
                    listOf(
                        CnbRepositoryEventCredentialCandidate("a-token", null, "source:a", itemScoped = true),
                    ),
                openSession = { _, candidate ->
                    opened += candidate.origin
                    testSession(closed, candidate.origin) { expected }
                },
            )

        val actual =
            selector.use {
                it.listRepositoryEvents("team/project", java.time.ZonedDateTime.parse("2026-07-15T10:00:00Z"))
            }

        assertEquals(expected, actual)
        assertEquals(listOf("source:a"), opened)
        assertEquals(listOf("source:a"), closed)
    }

    @Test
    fun `credential selector never falls back after an item credential is rejected`() {
        for (status in listOf(401, 403, 404)) {
            val opened = mutableListOf<String>()
            val closed = mutableListOf<String>()
            val selector =
                CnbRepositoryEventClientSelector(
                    serverId = "primary",
                    candidates =
                        listOf(
                            CnbRepositoryEventCredentialCandidate("item-token", null, "source:item", itemScoped = true),
                        ),
                    openSession = { _, candidate ->
                        opened += candidate.origin
                        testSession(closed, candidate.origin) {
                            throw CnbApiException("rejected", statusCode = status)
                        }
                    },
                )

            assertThrows(CnbApiException::class.java) {
                selector.use {
                    it.listRepositoryEvents("team/project", java.time.ZonedDateTime.parse("2026-07-15T10:00:00Z"))
                }
            }

            assertEquals(listOf("source:item"), opened)
            assertEquals(listOf("source:item"), closed)
        }
    }

    @Test
    fun `credential selector does not fan out retryable server failures`() {
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val selector =
            CnbRepositoryEventClientSelector(
                serverId = "primary",
                candidates =
                    listOf(
                        CnbRepositoryEventCredentialCandidate("item-token", null, "source:item", itemScoped = true),
                    ),
                openSession = { _, candidate ->
                    opened += candidate.origin
                    testSession(closed, candidate.origin) {
                        throw CnbApiException("server unavailable", statusCode = 503, retryable = true)
                    }
                },
            )

        assertThrows(CnbApiException::class.java) {
            selector.use {
                it.listRepositoryEvents("team/project", java.time.ZonedDateTime.parse("2026-07-15T10:00:00Z"))
            }
        }

        assertEquals(listOf("source:item"), opened)
        assertEquals(listOf("source:item"), closed)
    }

    @Test
    fun `credential selector reuses the successful session across hours`() {
        var opened = 0
        var fetched = 0
        val closed = mutableListOf<String>()
        val selector =
            CnbRepositoryEventClientSelector(
                serverId = "primary",
                candidates = listOf(CnbRepositoryEventCredentialCandidate.server()),
                openSession = { _, candidate ->
                    opened++
                    testSession(closed, candidate.origin) {
                        fetched++
                        emptyList()
                    }
                },
            )

        selector.use {
            it.listRepositoryEvents("team/project", java.time.ZonedDateTime.parse("2026-07-15T09:00:00Z"))
            it.listRepositoryEvents("team/project", java.time.ZonedDateTime.parse("2026-07-15T10:00:00Z"))
        }

        assertEquals(1, opened)
        assertEquals(2, fetched)
        assertEquals(listOf("server"), closed)
    }

    @Test
    fun `plans every missing completed hour up to the per-run limit`() {
        val now = Instant.parse("2026-07-15T15:42:00Z")

        val plan =
            repositoryEventPollPlan(
                cursor = Instant.parse("2026-07-15T10:00:00Z"),
                now = now,
                maxCompletedHours = 2,
            )

        assertEquals(
            listOf(
                Instant.parse("2026-07-15T11:00:00Z"),
                Instant.parse("2026-07-15T12:00:00Z"),
            ),
            plan.completedHours,
        )
        assertNull(plan.currentHour)
    }

    @Test
    fun `first run handles the previous hour and re-reads the open hour`() {
        val plan = repositoryEventPollPlan(null, Instant.parse("2026-07-15T15:42:00Z"))

        assertEquals(listOf(Instant.parse("2026-07-15T14:00:00Z")), plan.completedHours)
        assertEquals(Instant.parse("2026-07-15T15:00:00Z"), plan.currentHour)
    }

    @Test
    fun `cursor is persisted independently per repository and advances monotonically`() {
        val path = directory.resolve("cursors.properties")
        val ten = Instant.parse("2026-07-15T10:00:00Z")
        val eleven = Instant.parse("2026-07-15T11:00:00Z")
        val latest = Instant.parse("2026-07-15T12:00:00Z")
        val store = CnbRepositoryEventCursorStore(path)

        store.advance("primary", "team/one", ten)
        store.advance("primary", "team/one", eleven)
        store.advance("primary", "team/one", ten)
        store.advance("primary", "team/two", ten)

        val reloaded = CnbRepositoryEventCursorStore(path)
        assertEquals(eleven, reloaded.get("primary", "team/one", latest))
        assertEquals(ten, reloaded.get("primary", "team/two", latest))
        assertFalse(path.toFile().readText().contains("team/one"))
    }

    @Test
    fun `cursor remains isolated between authorization scopes for the same repository`() {
        val path = directory.resolve("authorization-cursors.journal")
        val hour = Instant.parse("2026-07-15T10:00:00Z")
        val latest = hour.plusSeconds(3600)
        val folderA =
            CnbRepositoryEventStateScope("primary", "team/project", "item:folder-a", "@cursor")
        val folderB =
            CnbRepositoryEventStateScope("primary", "team/project", "item:folder-b", "@cursor")
        val store = CnbRepositoryEventCursorStore(path)

        store.advance(folderA, hour)

        val restarted = CnbRepositoryEventCursorStore(path)
        assertEquals(hour, restarted.get(folderA, latest))
        assertNull(restarted.get(folderB, latest))
        assertFalse(Files.readString(path).contains("folder-a"))
    }

    @Test
    fun `future cursor is discarded instead of blocking polling`() {
        val path = directory.resolve("future-cursor.properties")
        val store = CnbRepositoryEventCursorStore(path)
        store.advance("primary", "team/project", Instant.parse("2026-07-15T15:00:00Z"))

        assertNull(
            store.get(
                "primary",
                "team/project",
                Instant.parse("2026-07-15T14:00:00Z"),
            ),
        )

        val reloaded = CnbRepositoryEventCursorStore(path)
        assertNull(reloaded.get("primary", "team/project", Instant.parse("2026-07-15T14:00:00Z")))
    }

    @Test
    fun `cursor migrates legacy properties and recovers a corrupt journal tail`() {
        val path = directory.resolve("legacy-cursor.properties")
        val hour = Instant.parse("2026-07-15T10:00:00Z")
        val scope =
            java.util.HexFormat.of().formatHex(
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest("primary\u0000team/project".toByteArray()),
            )
        Properties()
            .apply { setProperty(scope, hour.epochSecond.toString()) }
            .also { properties -> Files.newOutputStream(path).use { properties.store(it, "legacy cursor") } }

        val migrated = CnbRepositoryEventCursorStore(path)
        assertEquals(hour, migrated.get("primary", "team/project", hour.plusSeconds(3600)))
        assertTrue(Files.readString(path).startsWith("CNB_EVENT_CURSOR_V1"))

        Files.writeString(path, "CNB_EVENT_CURSOR_V1\tS\tbroken", StandardOpenOption.APPEND)
        val recovered = CnbRepositoryEventCursorStore(path)
        assertEquals(hour, recovered.get("primary", "team/project", hour.plusSeconds(3600)))
        assertFalse(Files.readString(path).contains("broken"))
    }

    @Test
    fun `only code and pull request archive events are relevant`() {
        assertTrue(CnbRepositoryEventClassifier.isRelevant(event("PushEvent")))
        assertTrue(CnbRepositoryEventClassifier.isRelevant(event("PullRequestEvent")))
        assertTrue(
            CnbRepositoryEventClassifier.isRelevant(
                event("CreateEvent", CnbRepositoryEventPayload(refType = CnbRepositoryRefType.BRANCH)),
            ),
        )
        assertTrue(
            CnbRepositoryEventClassifier.isRelevant(
                event("DeleteEvent", CnbRepositoryEventPayload(refType = CnbRepositoryRefType.TAG)),
            ),
        )
        assertFalse(
            CnbRepositoryEventClassifier.isRelevant(
                event("CreateEvent", CnbRepositoryEventPayload(refType = CnbRepositoryRefType.REPOSITORY)),
            ),
        )
        assertFalse(CnbRepositoryEventClassifier.isRelevant(event("WatchEvent")))
        assertFalse(CnbRepositoryEventClassifier.isRelevant(event("IssueCommentEvent")))
    }

    @Test
    fun `failed dispatch is not deduplicated and is retried`() {
        val now = Instant.parse("2026-07-15T10:30:00Z")
        val event = event("PushEvent", CnbRepositoryEventPayload(ref = "refs/heads/main"))
        val store =
            CnbRepositoryEventDedupStore(
                directory.resolve("failed-dispatch.properties"),
                Duration.ofDays(1),
                10,
            )
        val key = CnbRepositoryEventPollingWork.eventKey("primary", "team/project", event)

        assertThrows(IllegalStateException::class.java) {
            CnbRepositoryEventHourProcessor.process(
                "primary",
                "team/project",
                listOf(event),
                now,
                store,
            ) {
                throw IllegalStateException("refresh failed")
            }
        }

        assertFalse(store.contains("primary", "team/project", key, now))
        var retries = 0
        assertEquals(
            1,
            CnbRepositoryEventHourProcessor.process(
                "primary",
                "team/project",
                listOf(event),
                now,
                store,
            ) {
                retries++
            },
        )
        assertEquals(1, retries)
        assertTrue(store.contains("primary", "team/project", key, now))
    }

    @Test
    fun `processor durably acknowledges successful event keys from a partial batch`() {
        val now = Instant.parse("2026-07-15T10:30:00Z")
        val first = event("PushEvent", CnbRepositoryEventPayload(ref = "refs/heads/main")).copy(id = "event-1")
        val second = event("PushEvent", CnbRepositoryEventPayload(ref = "refs/heads/release")).copy(id = "event-2")
        val store = CnbRepositoryEventDedupStore(directory.resolve("partial-batch.properties"))
        var dispatched = emptyList<String>()
        val secondKey = CnbRepositoryEventPollingWork.eventKey("primary", "team/project", second)

        assertThrows(CnbPartialRepositoryEventDispatchException::class.java) {
            CnbRepositoryEventHourProcessor.process(
                "primary",
                "team/project",
                listOf(first, second),
                now,
                store,
            ) { events ->
                dispatched = events.map { it.id }
                throw CnbPartialRepositoryEventDispatchException(
                    setOf(secondKey),
                    IllegalStateException("queue refused second ref"),
                )
            }
        }

        assertEquals(listOf("event-1", "event-2"), dispatched)
        assertTrue(store.contains("primary", "team/project", CnbRepositoryEventPollingWork.eventKey("primary", "team/project", first), now))
        assertFalse(store.contains("primary", "team/project", secondKey, now))
    }

    @Test
    fun `failed consumer is retried without repeating a successful consumer`() {
        val now = Instant.parse("2026-07-15T10:30:00Z")
        val event = event("PushEvent", CnbRepositoryEventPayload(ref = "refs/heads/main"))
        val store = CnbRepositoryEventDedupStore(directory.resolve("isolated-consumer.properties"))
        var failedConsumerAttempts = 0
        var successfulDispatches = 0
        val failedConsumer =
            CnbRepositoryEventConsumer("0:broken", "broken source") {
                failedConsumerAttempts++
                if (failedConsumerAttempts == 1) throw IllegalStateException("source refresh failed")
            }
        val successfulConsumer =
            CnbRepositoryEventConsumer("1:healthy", "healthy job") { successfulDispatches++ }
        val repository =
            CnbWatchedRepository(
                serverId = "primary",
                repositoryPath = "team/project",
                consumers = listOf(failedConsumer, successfulConsumer),
                authorizationScope = "item:folder-a",
            )
        val key = CnbRepositoryEventPollingWork.eventKey("primary", "team/project", event)

        assertThrows(IllegalStateException::class.java) {
            CnbRepositoryEventDispatcher.dispatch(repository, listOf(event), now, store)
        }

        assertEquals(1, failedConsumerAttempts)
        assertEquals(1, successfulDispatches)
        assertFalse(store.contains(repository.stateScope(failedConsumer), key, now))
        assertTrue(store.contains(repository.stateScope(successfulConsumer), key, now))

        assertEquals(1, CnbRepositoryEventDispatcher.dispatch(repository, listOf(event), now, store))

        assertEquals(2, failedConsumerAttempts)
        assertEquals(1, successfulDispatches)
        assertTrue(store.contains(repository.stateScope(failedConsumer), key, now))
        assertTrue(store.contains(repository.stateScope(successfulConsumer), key, now))
    }

    @Test
    fun `processor ignores noisy events and coalesces duplicate event IDs`() {
        val now = Instant.parse("2026-07-15T10:30:00Z")
        val push = event("PushEvent", CnbRepositoryEventPayload(ref = "main"))
        val dispatched = mutableListOf<CnbRepositoryEvent>()

        val handled =
            CnbRepositoryEventHourProcessor.process(
                "primary",
                "team/project",
                listOf(
                    event("WatchEvent"),
                    push,
                    push.copy(payload = CnbRepositoryEventPayload(ref = "other")),
                    event("PushEvent", repositoryPath = "other/project"),
                ),
                now,
                CnbRepositoryEventDedupStore(directory.resolve("filtered.properties")),
            ) { dispatched += it }

        assertEquals(1, handled)
        assertEquals(listOf(push), dispatched)
    }

    @Test
    fun `push refs are normalized for classic trigger branch filters`() {
        assertEquals(
            "feature/recovery",
            CnbRepositoryEventClassifier.pushRef(
                event("PushEvent", CnbRepositoryEventPayload(ref = "refs/heads/feature/recovery")),
            ),
        )
        assertEquals(
            "v1.0.0",
            CnbRepositoryEventClassifier.pushRef(
                event("PushEvent", CnbRepositoryEventPayload(ref = "refs/tags/v1.0.0")),
            ),
        )
        assertNull(
            CnbRepositoryEventClassifier.pushRef(
                event("PullRequestEvent", CnbRepositoryEventPayload(ref = "main")),
            ),
        )

        val commit = "a".repeat(40)
        assertEquals(
            commit,
            CnbRepositoryEventClassifier.pushCommit(event("PushEvent", CnbRepositoryEventPayload(head = commit))),
        )
        assertNull(CnbRepositoryEventClassifier.pushCommit(event("PushEvent")))
        assertNull(
            CnbRepositoryEventClassifier.pushCommit(
                event("PushEvent", CnbRepositoryEventPayload(head = "0".repeat(40))),
            ),
        )
    }

    private fun event(
        type: String,
        payload: CnbRepositoryEventPayload = CnbRepositoryEventPayload(),
        repositoryPath: String = "team/project",
    ): CnbRepositoryEvent =
        CnbRepositoryEvent(
            id = "event-1",
            type = CnbRepositoryEventType(type),
            repositoryPath = repositoryPath,
            createdAt = "2026-07-15T10:15:00Z",
            payload = payload,
        )

    private fun testSession(
        closed: MutableList<String>,
        origin: String,
        fetch: () -> List<CnbRepositoryEvent>,
    ): CnbRepositoryEventSession =
        object : CnbRepositoryEventSession {
            override fun listRepositoryEvents(
                repositoryPath: String,
                hour: java.time.ZonedDateTime,
            ): List<CnbRepositoryEvent> = fetch()

            override fun close() {
                closed += origin
            }
        }
}
