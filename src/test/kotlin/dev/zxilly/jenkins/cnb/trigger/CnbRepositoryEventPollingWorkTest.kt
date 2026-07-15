package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEvent
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
    fun `aggregates every consumer and orders credential candidates deterministically`() {
        val noOp: (List<CnbRepositoryEvent>) -> Unit = {}
        val repository =
            aggregateWatchedRepositories(
                listOf(
                    CnbRepositoryEventWatch(
                        "primary",
                        "team/project",
                        CnbRepositoryEventConsumer("1:job-z", "job z", noOp),
                        emptyList(),
                    ),
                    CnbRepositoryEventWatch(
                        "primary",
                        "team/project",
                        CnbRepositoryEventConsumer("0:source-z", "source z", noOp),
                        listOf(
                            CnbRepositoryEventCredentialCandidate("z-token", null, "source:z", itemScoped = true),
                        ),
                    ),
                    CnbRepositoryEventWatch(
                        "primary",
                        "team/project",
                        CnbRepositoryEventConsumer("0:source-a", "source a", noOp),
                        listOf(
                            CnbRepositoryEventCredentialCandidate("a-token", null, "source:a", itemScoped = true),
                        ),
                    ),
                ),
            ).single()

        assertEquals(listOf("0:source-a", "0:source-z", "1:job-z"), repository.consumers.map { it.stableKey })
        assertEquals(
            listOf(
                "source:a/a-token",
                "source:z/z-token",
                "server/<server>",
            ),
            repository.credentialCandidates.map { "${it.origin}/${it.credentialsId ?: "<server>"}" },
        )
    }

    @Test
    fun `credential selector falls back in stable order and closes every opened session`() {
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val expected = listOf(event("PushEvent"))
        val selector =
            CnbRepositoryEventClientSelector(
                serverId = "primary",
                candidates =
                    listOf(
                        CnbRepositoryEventCredentialCandidate("c-token", null, "source:c", itemScoped = true),
                        CnbRepositoryEventCredentialCandidate.server(),
                        CnbRepositoryEventCredentialCandidate("a-token", null, "source:a", itemScoped = true),
                        CnbRepositoryEventCredentialCandidate("b-token", null, "source:b", itemScoped = true),
                    ),
                openSession = { _, candidate ->
                    opened += candidate.origin
                    when (candidate.origin) {
                        "source:a" -> {
                            throw IllegalArgumentException("credential is no longer visible")
                        }

                        "source:b" -> {
                            testSession(closed, candidate.origin) {
                                throw CnbApiException("unauthorized", statusCode = 401)
                            }
                        }

                        else -> {
                            testSession(closed, candidate.origin) { expected }
                        }
                    }
                },
            )

        val actual =
            selector.use {
                it.listRepositoryEvents("team/project", java.time.ZonedDateTime.parse("2026-07-15T10:00:00Z"))
            }

        assertEquals(expected, actual)
        assertEquals(listOf("source:a", "source:b", "source:c"), opened)
        assertEquals(listOf("source:b", "source:c"), closed)
    }

    @Test
    fun `credential selector falls back for forbidden and not found responses`() {
        for (status in listOf(403, 404)) {
            val opened = mutableListOf<String>()
            val closed = mutableListOf<String>()
            val selector =
                CnbRepositoryEventClientSelector(
                    serverId = "primary",
                    candidates =
                        listOf(
                            CnbRepositoryEventCredentialCandidate("item-token", null, "source:item", itemScoped = true),
                            CnbRepositoryEventCredentialCandidate.server(),
                        ),
                    openSession = { _, candidate ->
                        opened += candidate.origin
                        testSession(closed, candidate.origin) {
                            if (candidate.itemScoped) throw CnbApiException("rejected", statusCode = status)
                            emptyList()
                        }
                    },
                )

            selector.use {
                it.listRepositoryEvents("team/project", java.time.ZonedDateTime.parse("2026-07-15T10:00:00Z"))
            }

            assertEquals(listOf("source:item", "server"), opened)
            assertEquals(listOf("source:item", "server"), closed)
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
                        CnbRepositoryEventCredentialCandidate.server(),
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
        assertTrue(CnbRepositoryEventClassifier.isRelevant(event("CreateEvent", mapOf("ref_type" to "branch"))))
        assertTrue(CnbRepositoryEventClassifier.isRelevant(event("DeleteEvent", mapOf("ref_type" to "tag"))))
        assertFalse(CnbRepositoryEventClassifier.isRelevant(event("CreateEvent", mapOf("ref_type" to "repository"))))
        assertFalse(CnbRepositoryEventClassifier.isRelevant(event("WatchEvent")))
        assertFalse(CnbRepositoryEventClassifier.isRelevant(event("IssueCommentEvent")))
    }

    @Test
    fun `failed dispatch is not deduplicated and is retried`() {
        val now = Instant.parse("2026-07-15T10:30:00Z")
        val event = event("PushEvent", mapOf("ref" to "refs/heads/main"))
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
    fun `one failed consumer does not block other consumers or durable deduplication`() {
        val now = Instant.parse("2026-07-15T10:30:00Z")
        val event = event("PushEvent", mapOf("ref" to "refs/heads/main"))
        val store = CnbRepositoryEventDedupStore(directory.resolve("isolated-consumer.properties"))
        var successfulDispatches = 0
        val repository =
            CnbWatchedRepository(
                serverId = "primary",
                repositoryPath = "team/project",
                consumers =
                    listOf(
                        CnbRepositoryEventConsumer("0:broken", "broken source") {
                            throw IllegalStateException("source refresh failed")
                        },
                        CnbRepositoryEventConsumer("1:healthy", "healthy job") { successfulDispatches++ },
                    ),
            )

        val handled =
            CnbRepositoryEventHourProcessor.process(
                "primary",
                "team/project",
                listOf(event),
                now,
                store,
            ) { events -> CnbRepositoryEventDispatcher.dispatch(repository, events) }

        assertEquals(1, handled)
        assertEquals(1, successfulDispatches)
        assertTrue(
            store.contains(
                "primary",
                "team/project",
                CnbRepositoryEventPollingWork.eventKey("primary", "team/project", event),
                now,
            ),
        )
    }

    @Test
    fun `processor ignores noisy events and coalesces duplicate event IDs`() {
        val now = Instant.parse("2026-07-15T10:30:00Z")
        val push = event("PushEvent", mapOf("ref" to "main"))
        val dispatched = mutableListOf<CnbRepositoryEvent>()

        val handled =
            CnbRepositoryEventHourProcessor.process(
                "primary",
                "team/project",
                listOf(
                    event("WatchEvent"),
                    push,
                    push.copy(payload = mapOf("ref" to "other")),
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
                event("PushEvent", mapOf("ref" to "refs/heads/feature/recovery")),
            ),
        )
        assertEquals(
            "v1.0.0",
            CnbRepositoryEventClassifier.pushRef(event("PushEvent", mapOf("ref" to "refs/tags/v1.0.0"))),
        )
        assertNull(CnbRepositoryEventClassifier.pushRef(event("PullRequestEvent", mapOf("ref" to "main"))))

        val commit = "a".repeat(40)
        assertEquals(
            commit,
            CnbRepositoryEventClassifier.pushCommit(event("PushEvent", mapOf("head" to commit))),
        )
        assertNull(CnbRepositoryEventClassifier.pushCommit(event("PushEvent", mapOf("head" to ""))))
        assertNull(
            CnbRepositoryEventClassifier.pushCommit(
                event("PushEvent", mapOf("head" to "0".repeat(40))),
            ),
        )
    }

    private fun event(
        type: String,
        payload: Map<String, Any?> = emptyMap(),
        repositoryPath: String = "team/project",
    ): CnbRepositoryEvent =
        CnbRepositoryEvent(
            id = "event-1",
            type = type,
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
