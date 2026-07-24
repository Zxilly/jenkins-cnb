package dev.zxilly.jenkins.cnb.status

import com.cloudbees.hudson.plugins.folder.Folder
import hudson.model.FreeStyleProject
import hudson.model.Job
import hudson.model.Queue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.io.IOException
import java.nio.file.Files

class CnbBuildMetadataRecoveryGateTest {
    @Test
    fun `no recovery request does not enumerate queue or jobs`() {
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        var queueEnumerations = 0
        var jobEnumerations = 0

        val stats =
            work.recoverOnce(
                recoveryRequests = requests,
                queueItems = {
                    queueEnumerations++
                    emptyList<Queue.Item>().iterator()
                },
                jobs = {
                    jobEnumerations++
                    emptyList<Job<*, *>>().iterator()
                },
                scheduleQueue = { _, _ -> error("nothing should be scheduled") },
                scheduleRun = { _, _ -> error("nothing should be scheduled") },
            )

        assertEquals(0, stats.generation)
        assertEquals(0, queueEnumerations)
        assertEquals(0, jobEnumerations)
        assertNull(requests.pendingGeneration())
    }

    @Test
    fun `new generation cannot be cleared by an older scan`() {
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val scanning = requests.request()
        val newer = requests.request()

        assertFalse(requests.completeIfUnchanged(scanning))
        assertEquals(newer, requests.pendingGeneration())
        assertTrue(requests.completeIfUnchanged(newer))
        assertNull(requests.pendingGeneration())
    }
}

@WithJenkins
class CnbBuildMetadataReconciliationWorkTest {
    @Test
    fun `one request advances in bounded ticks then becomes idle`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-history")
        val builds = (1..3).map { jenkins.buildAndAssertSuccess(project) }
        val pending = pendingAction("oldest")
        builds.first().addAction(pending)
        val scheduledBuilds = mutableListOf<Int>()
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        var jobEnumerations = 0
        requests.request()

        var ticks = 0
        while (requests.pendingGeneration() != null && ticks < 10) {
            work.recoverOnce(
                recoveryRequests = requests,
                queueItems = { emptyList<Queue.Item>().iterator() },
                jobs = {
                    jobEnumerations++
                    listOf<Job<*, *>>(project).iterator()
                },
                maximumJobs = 1,
                maximumRuns = 1,
                scheduleQueue = { _, _ -> error("queue must be empty") },
                scheduleRun = { run, _ ->
                    scheduledBuilds += run.number
                    true
                },
            )
            ticks++
        }

        assertNull(requests.pendingGeneration())
        assertTrue(ticks in 2..10)
        assertEquals(1, jobEnumerations, "the lazy job iterator must be retained for the round")
        assertEquals(listOf(builds.first().number), scheduledBuilds)
        assertTrue(pending.isPending())

        work.recoverOnce(
            recoveryRequests = requests,
            queueItems = { error("idle recovery must not inspect the queue") },
            jobs = { error("idle recovery must not inspect jobs") },
            scheduleQueue = { _, _ -> error("idle recovery must not schedule") },
            scheduleRun = { _, _ -> error("idle recovery must not schedule") },
        )
    }

    @Test
    fun `overflow requested during a scan survives into a second round`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-generation")
        val build = jenkins.buildAndAssertSuccess(project)
        build.addAction(pendingAction("generation"))
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        val firstGeneration = requests.request()
        var requestedDuringScan = false
        var jobEnumerations = 0

        val firstTick =
            work.recoverOnce(
                recoveryRequests = requests,
                queueItems = { emptyList<Queue.Item>().iterator() },
                jobs = {
                    jobEnumerations++
                    listOf<Job<*, *>>(project).iterator()
                },
                maximumJobs = 2,
                maximumRuns = 2,
                scheduleQueue = { _, _ -> error("queue must be empty") },
                scheduleRun = { _, _ ->
                    if (!requestedDuringScan) {
                        requestedDuringScan = true
                        requests.request()
                    }
                    true
                },
            )

        assertEquals(firstGeneration, firstTick.generation)
        assertTrue(firstTick.roundCompleted)
        assertFalse(firstTick.requestCleared)
        assertNotNull(requests.pendingGeneration())

        val secondTick =
            work.recoverOnce(
                recoveryRequests = requests,
                queueItems = { emptyList<Queue.Item>().iterator() },
                jobs = {
                    jobEnumerations++
                    listOf<Job<*, *>>(project).iterator()
                },
                maximumJobs = 2,
                maximumRuns = 2,
                scheduleQueue = { _, _ -> error("queue must be empty") },
                scheduleRun = { _, _ -> true },
            )

        assertTrue(secondTick.roundCompleted)
        assertTrue(secondTick.requestCleared)
        assertNull(requests.pendingGeneration())
        assertEquals(2, jobEnumerations)
    }

    @Test
    fun `large queue supplier is retained and consumed only to the per tick limit`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-large-queue")

        @Suppress("DEPRECATION")
        val item = requireNotNull(Queue.getInstance().schedule(project, 3_600, emptyList()))
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        var supplierCalls = 0
        var itemAccesses = 0
        var remaining = 10_000
        val iterator =
            object : Iterator<Queue.Item> {
                override fun hasNext(): Boolean = remaining > 0

                override fun next(): Queue.Item {
                    if (!hasNext()) throw NoSuchElementException()
                    remaining--
                    itemAccesses++
                    return item
                }
            }
        requests.request()

        try {
            repeat(2) {
                val stats =
                    work.recoverOnce(
                        recoveryRequests = requests,
                        queueItems = {
                            supplierCalls++
                            iterator
                        },
                        jobs = { emptyList<Job<*, *>>().iterator() },
                        maximumQueueItems = 17,
                        maximumJobs = 1,
                        maximumRuns = 1,
                        scheduleQueue = { _, _ -> error("synthetic item has no CNB Action") },
                        scheduleRun = { _, _ -> error("jobs must be empty") },
                    )
                assertEquals(17, stats.queueItemsInspected)
                assertFalse(stats.roundCompleted)
            }

            assertEquals(1, supplierCalls)
            assertEquals(34, itemAccesses)
            assertNotNull(requests.pendingGeneration())
        } finally {
            Queue.getInstance().cancel(item)
        }
    }

    @Test
    fun `queue index generation keeps initial items and defers newer IDs`(jenkins: JenkinsRule) {
        val firstProject = jenkins.createFreeStyleProject("metadata-queue-first")
        val secondProject = jenkins.createFreeStyleProject("metadata-queue-second")

        @Suppress("DEPRECATION")
        val first = requireNotNull(Queue.getInstance().schedule(firstProject, 3_600, emptyList()))
        val index = CnbBuildMetadataQueueRecoveryIndex()
        first.addAction(pendingAction("queue-first"))
        index.observe(first)
        val firstGeneration = index.snapshotIterator()

        @Suppress("DEPRECATION")
        val second = requireNotNull(Queue.getInstance().schedule(secondProject, 3_600, emptyList()))
        second.addAction(pendingAction("queue-second"))
        index.observe(second)

        try {
            assertEquals(listOf(first.id), firstGeneration.asSequence().map(Queue.Item::getId).toList())
            assertEquals(
                listOf(first.id, second.id),
                index
                    .snapshotIterator()
                    .asSequence()
                    .map(Queue.Item::getId)
                    .toList(),
            )
        } finally {
            index.remove(first)
            index.remove(second)
            Queue.getInstance().cancel(first)
            Queue.getInstance().cancel(second)
        }
    }

    @Test
    fun `queue index retains an item when a later metadata context is pending`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-queue-contexts")

        @Suppress("DEPRECATION")
        val item = requireNotNull(Queue.getInstance().schedule(project, 3_600, emptyList()))
        val reported = pendingAction("queue-reported")
        val pending = pendingAction("queue-pending")
        val reportedSnapshot = requireNotNull(reported.snapshot())
        reported.markReported(reportedSnapshot.version, null)
        item.addAction(reported)
        item.addAction(pending)
        val index = CnbBuildMetadataQueueRecoveryIndex()

        try {
            index.observe(item)

            assertEquals(1, index.size())
            assertEquals(
                listOf(item.id),
                index
                    .snapshotIterator()
                    .asSequence()
                    .map(Queue.Item::getId)
                    .toList(),
            )

            val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
            val scheduled = mutableListOf<CnbBuildMetadataAction>()
            requests.request()
            val stats =
                CnbBuildMetadataReconciliationWork().recoverOnce(
                    recoveryRequests = requests,
                    queueItems = index::snapshotIterator,
                    jobs = { emptyList<Job<*, *>>().iterator() },
                    scheduleQueue = { _, action ->
                        scheduled += action
                        true
                    },
                    scheduleRun = { _, _ -> error("runs must be empty") },
                )

            assertEquals(listOf(pending), scheduled)
            assertEquals(1, stats.queueItemsInspected)
            assertEquals(1, stats.accepted)
        } finally {
            index.remove(item)
            Queue.getInstance().cancel(item)
        }
    }

    @Test
    fun `run recovery schedules a later pending metadata context`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-run-contexts")
        val run = jenkins.buildAndAssertSuccess(project)
        val reported = pendingAction("run-reported")
        val pending = pendingAction("run-pending")
        val reportedSnapshot = requireNotNull(reported.snapshot())
        reported.markReported(reportedSnapshot.version, null)
        run.addAction(reported)
        run.addAction(pending)
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val scheduled = mutableListOf<CnbBuildMetadataAction>()
        requests.request()

        val stats =
            CnbBuildMetadataReconciliationWork().recoverOnce(
                recoveryRequests = requests,
                queueItems = { emptyList<Queue.Item>().iterator() },
                jobs = { listOf<Job<*, *>>(project).iterator() },
                scheduleQueue = { _, _ -> error("queue must be empty") },
                scheduleRun = { scheduledRun, action ->
                    assertEquals(run, scheduledRun)
                    scheduled += action
                    true
                },
            )

        assertEquals(listOf(pending), scheduled)
        assertEquals(1, stats.runsInspected)
        assertEquals(1, stats.accepted)
    }

    @Test
    fun `cancelled item action reloads and is scheduled by reconciliation`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-cancel-reload")
        val action = pendingAction("cancel-reload", CnbBuildMetadataState.ABORTED)
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = true)
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("cancel-reload.xml")
        CnbCancelledBuildMetadataStore(statePath, requestRecovery = {}).retain(project, action)

        val reloaded = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        val restoredRecord = reloaded.snapshotIterator().asSequence().single()
        val restored = restoredRecord.action
        assertNotSame(action, restored)
        assertEquals(CnbBuildMetadataState.ABORTED, restored.state())
        assertTrue(restored.isPending())
        val scheduled = mutableListOf<CnbBuildMetadataAction>()

        val stats =
            CnbBuildMetadataReconciliationWork().recoverOnce(
                recoveryRequests = requests,
                queueItems = { emptyList<Queue.Item>().iterator() },
                cancelledRecords = reloaded::snapshotIterator,
                jobs = { listOf<Job<*, *>>(project).iterator() },
                scheduleQueue = { _, _ -> error("queue must be empty") },
                scheduleCancelled = { record ->
                    assertEquals(project.fullName, record.itemFullName)
                    scheduled += record.action
                    true
                },
                scheduleRun = { _, _ -> error("runs must be empty") },
            )

        assertEquals(listOf(restored), scheduled)
        assertEquals(1, stats.cancelledRecordsInspected)
        assertEquals(1, stats.jobsInspected)
        assertEquals(1, stats.actionsInspected)
        assertEquals(1, stats.accepted)
        assertNull(requests.pendingGeneration())
    }

    @Test
    fun `cancelled metadata locator survives job and folder renames without stale work reverting it`(jenkins: JenkinsRule) {
        val folder = jenkins.jenkins.createProject(Folder::class.java, "metadata-scope")
        val project = folder.createProject(FreeStyleProject::class.java, "original-job")
        val store = CnbCancelledBuildMetadataStores.current(jenkins.jenkins)
        val retention = store.retain(project, pendingAction("rename", CnbBuildMetadataState.ABORTED))
        val record = retention.record
        val staleWorkRecord = record.copy()

        assertSame(project, retention.credentialContext)
        assertTrue(retention.credentialContext is Queue.Task)
        assertEquals(CnbCancelledBuildMetadataCredentialContext.ITEM, record.credentialContextKind)
        assertEquals(project.fullName, record.credentialContextFullName)
        assertNotNull(record.credentialContextIdentity)

        project.renameTo("renamed-job")
        store.persist(staleWorkRecord)
        folder.renameTo("renamed-scope")

        val reloaded =
            CnbCancelledBuildMetadataStore(
                jenkins.jenkins.rootDir
                    .toPath()
                    .resolve("cnb")
                    .resolve("cancelled-build-metadata.xml"),
                requestRecovery = {},
            )
        val restored = reloaded.snapshotIterator().asSequence().single()
        assertEquals("renamed-scope/renamed-job", restored.itemFullName)
        assertEquals(CnbCancelledBuildMetadataCredentialContext.ITEM, restored.credentialContextKind)
        assertEquals("renamed-scope/renamed-job", restored.credentialContextFullName)
        assertNotNull(restored.credentialContextIdentity)

        store.remove(store.snapshotIterator().asSequence().single())
    }

    @Test
    fun `deleted jobs never bind cancelled metadata to a same-name replacement`(jenkins: JenkinsRule) {
        val folder = jenkins.jenkins.createProject(Folder::class.java, "deleted-job-scope")
        val project = folder.createProject(FreeStyleProject::class.java, "job")
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("deleted-job-context.xml")
        val store = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        store.retain(project, pendingAction("deleted-job", CnbBuildMetadataState.ABORTED))
        project.delete()
        val replacement = folder.createProject(FreeStyleProject::class.java, "job")

        val reloaded = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        val record = reloaded.snapshotIterator().asSequence().single()
        var resolvedContext: hudson.model.Item? = replacement
        assertFalse(
            CnbBuildMetadataReconciliationWork().scheduleCancelledRecord(
                jenkins.jenkins,
                reloaded,
                record,
            ) { context, _, _ ->
                resolvedContext = context
                true
            },
        )
        assertSame(replacement, resolvedContext)
        assertEquals(CnbCancelledBuildMetadataCredentialContext.DELETED, record.credentialContextKind)
        assertEquals(0, reloaded.size())
    }

    @Test
    fun `legacy cancelled metadata derives and rewrites its credential context`(jenkins: JenkinsRule) {
        val folder = jenkins.jenkins.createProject(Folder::class.java, "legacy-metadata-scope")
        val project = folder.createProject(FreeStyleProject::class.java, "job")
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("legacy-context.xml")
        val store = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        val record = store.retain(project, pendingAction("legacy-context", CnbBuildMetadataState.ABORTED)).record
        record.credentialContextKind = null
        record.credentialContextFullName = null
        record.credentialContextIdentity = null
        store.persist(record)

        val migrated = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        val restored = migrated.snapshotIterator().asSequence().single()
        assertEquals(CnbCancelledBuildMetadataCredentialContext.ITEM, restored.credentialContextKind)
        assertEquals(project.fullName, restored.credentialContextFullName)
        assertNotNull(restored.credentialContextIdentity)

        val rewritten = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        assertEquals(CnbCancelledBuildMetadataCredentialContext.ITEM, rewritten.snapshotIterator().next().credentialContextKind)
    }

    @Test
    fun `missed rename callbacks recover the original credential context by durable identity`(jenkins: JenkinsRule) {
        val folder = jenkins.jenkins.createProject(Folder::class.java, "context-before-rename")
        val project = folder.createProject(FreeStyleProject::class.java, "job")
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("rename-race.xml")
        val store = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        val record = store.retain(project, pendingAction("rename-race", CnbBuildMetadataState.ABORTED)).record
        val work = CnbBuildMetadataReconciliationWork()

        folder.renameTo("context-after-rename")

        var resolvedContext: hudson.model.Item? = null
        assertTrue(
            work.scheduleCancelledRecord(jenkins.jenkins, store, record) { context, _, _ ->
                resolvedContext = context
                true
            },
        )
        assertEquals(1, store.size())
        assertSame(project, resolvedContext)
        assertEquals("context-after-rename/job", record.itemFullName)
        assertEquals("context-after-rename/job", record.credentialContextFullName)
        assertNull(record.credentialContextMissingSinceMillis)
    }

    @Test
    fun `missing marker clears when the same credential context is visible again`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("same-context-recovery")
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("same-context-recovery.xml")
        val store = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        val record = store.retain(project, pendingAction("same-context-recovery", CnbBuildMetadataState.ABORTED)).record
        record.credentialContextMissingSinceMillis = 1_000L
        store.persist(record)
        var resolvedContext: hudson.model.Item? = null

        assertTrue(
            CnbBuildMetadataReconciliationWork().scheduleCancelledRecord(
                jenkins.jenkins,
                store,
                record,
            ) { context, _, _ ->
                resolvedContext = context
                true
            },
        )
        assertSame(project, resolvedContext)
        assertNull(record.credentialContextMissingSinceMillis)
    }

    @Test
    fun `missing credential context expires and never later binds a replacement`(jenkins: JenkinsRule) {
        val folder = jenkins.jenkins.createProject(Folder::class.java, "missing-context")
        val project = folder.createProject(FreeStyleProject::class.java, "job")
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("missing-context.xml")
        var now = 1_000L
        val store =
            CnbCancelledBuildMetadataStore(
                statePath,
                requestRecovery = {},
                clockMillis = { now },
                missingContextGraceMillis = 5_000L,
            )
        val record = store.retain(project, pendingAction("missing-context", CnbBuildMetadataState.ABORTED)).record
        val work = CnbBuildMetadataReconciliationWork()

        project.delete()
        assertFalse(
            work.scheduleCancelledRecord(jenkins.jenkins, store, record) { _, _, _ ->
                error("a missing context must not report")
            },
        )
        assertEquals(now, record.credentialContextMissingSinceMillis)
        assertEquals(1, store.size())

        now += 4_999L
        assertFalse(
            work.scheduleCancelledRecord(jenkins.jenkins, store, record) { _, _, _ ->
                error("an absent context must not report during the grace period")
            },
        )
        assertEquals(1, store.size())

        now++
        assertFalse(
            work.scheduleCancelledRecord(jenkins.jenkins, store, record) { _, _, _ ->
                error("an expired replacement must never report")
            },
        )
        assertEquals(CnbCancelledBuildMetadataCredentialContext.DELETED, record.credentialContextKind)
        assertEquals(0, store.size())

        folder.createProject(FreeStyleProject::class.java, "job")
        assertFalse(
            work.scheduleCancelledRecord(jenkins.jenkins, store, record) { _, _, _ ->
                error("an expired record must never bind a replacement")
            },
        )
    }

    @Test
    fun `authoritative relocation refreshes a copied item identity`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("identity-before-rename")
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("identity-refresh.xml")
        val store = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        val record = store.retain(project, pendingAction("identity-refresh", CnbBuildMetadataState.ABORTED)).record
        record.credentialContextIdentity = CnbCancelledBuildMetadataItemIdentity("stale-copy", -1L)
        store.persist(record)

        project.renameTo("identity-after-rename")
        store.relocate("identity-before-rename", "identity-after-rename", project)

        assertEquals(project.fullName, record.credentialContextFullName)
        assertTrue(record.credentialContextIdentity?.creationTimeMillis != -1L)
        var resolvedContext: hudson.model.Item? = null
        assertTrue(
            CnbBuildMetadataReconciliationWork().scheduleCancelledRecord(
                jenkins.jenkins,
                store,
                record,
            ) { context, _, _ ->
                resolvedContext = context
                true
            },
        )
        assertSame(project, resolvedContext)
    }

    @Test
    fun `relocation journal survives an immediate restart after the main state write fails`(jenkins: JenkinsRule) {
        val folder = jenkins.jenkins.createProject(Folder::class.java, "journal-before-rename")
        val project = folder.createProject(FreeStyleProject::class.java, "job")
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("relocation-journal.xml")
        var writes = 0
        val store =
            CnbCancelledBuildMetadataStore(
                statePath,
                beforePersistence = {
                    writes++
                    if (writes == 2) throw IOException("injected relocation state failure")
                },
                requestRecovery = {},
            )
        store.retain(project, pendingAction("relocation-journal", CnbBuildMetadataState.ABORTED))

        folder.renameTo("journal-after-rename")
        assertThrows(IOException::class.java) {
            store.relocate("journal-before-rename", "journal-after-rename", folder)
        }

        val reloaded = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        val restored = reloaded.snapshotIterator().asSequence().single()
        assertEquals("journal-after-rename/job", restored.itemFullName)
        assertEquals("journal-after-rename/job", restored.credentialContextFullName)
        assertFalse(Files.exists(statePath.resolveSibling("${statePath.fileName}.relocations")))
        var resolvedContext: hudson.model.Item? = null
        assertTrue(
            CnbBuildMetadataReconciliationWork().scheduleCancelledRecord(
                jenkins.jenkins,
                reloaded,
                restored,
            ) { context, _, _ ->
                resolvedContext = context
                true
            },
        )
        assertSame(project, resolvedContext)
    }

    @Test
    fun `relocation journal replays consecutive renames in order after restart`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("journal-rename-a")
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("consecutive-relocation-journal.xml")
        var writes = 0
        val store =
            CnbCancelledBuildMetadataStore(
                statePath,
                beforePersistence = {
                    writes++
                    if (writes == 2 || writes == 3) {
                        throw IOException("injected consecutive relocation state failure")
                    }
                },
                requestRecovery = {},
            )
        store.retain(project, pendingAction("consecutive-relocation", CnbBuildMetadataState.ABORTED))

        project.renameTo("journal-rename-b")
        assertThrows(IOException::class.java) {
            store.relocate("journal-rename-a", "journal-rename-b", project)
        }
        project.renameTo("journal-rename-c")
        assertThrows(IOException::class.java) {
            store.relocate("journal-rename-b", "journal-rename-c", project)
        }

        val reloaded = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        val restored = reloaded.snapshotIterator().asSequence().single()
        assertEquals("journal-rename-c", restored.itemFullName)
        assertEquals("journal-rename-c", restored.credentialContextFullName)
        assertFalse(Files.exists(statePath.resolveSibling("${statePath.fileName}.relocations")))
        var resolvedContext: hudson.model.Item? = null
        assertTrue(
            CnbBuildMetadataReconciliationWork().scheduleCancelledRecord(
                jenkins.jenkins,
                reloaded,
                restored,
            ) { context, _, _ ->
                resolvedContext = context
                true
            },
        )
        assertSame(project, resolvedContext)
    }

    @Test
    fun `later journal retains a prior relocation whose journal and state writes both failed`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("double-failure-rename-a")
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("double-failure-relocation-journal.xml")
        var stateWrites = 0
        var journalWrites = 0
        val store =
            CnbCancelledBuildMetadataStore(
                statePath,
                beforePersistence = {
                    stateWrites++
                    if (stateWrites == 3 || stateWrites == 4) {
                        throw IOException("injected relocation state failure")
                    }
                },
                beforeRelocationPersistence = {
                    journalWrites++
                    if (journalWrites == 1) {
                        throw IOException("injected first relocation journal failure")
                    }
                },
                requestRecovery = {},
            )
        val record = store.retain(project, pendingAction("double-failure-relocation", CnbBuildMetadataState.ABORTED)).record
        record.credentialContextIdentity = CnbCancelledBuildMetadataItemIdentity("copy-origin", -1L)
        store.persist(record)

        project.renameTo("double-failure-rename-b")
        assertThrows(IOException::class.java) {
            store.relocate("double-failure-rename-a", "double-failure-rename-b", project)
        }
        project.renameTo("double-failure-rename-c")
        assertThrows(IOException::class.java) {
            store.relocate("double-failure-rename-b", "double-failure-rename-c", project)
        }

        val reloaded = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        val restored = reloaded.snapshotIterator().asSequence().single()
        assertEquals("double-failure-rename-c", restored.itemFullName)
        assertEquals("double-failure-rename-c", restored.credentialContextFullName)
        assertTrue(restored.credentialContextIdentity?.creationTimeMillis != -1L)
        assertFalse(Files.exists(statePath.resolveSibling("${statePath.fileName}.relocations")))
        var resolvedContext: hudson.model.Item? = null
        assertTrue(
            CnbBuildMetadataReconciliationWork().scheduleCancelledRecord(
                jenkins.jenkins,
                reloaded,
                restored,
            ) { context, _, _ ->
                resolvedContext = context
                true
            },
        )
        assertSame(project, resolvedContext)
    }

    @Test
    fun `stale relocation journal never rewrites a later same-name job`(jenkins: JenkinsRule) {
        val original = jenkins.createFreeStyleProject("stale-journal-source")
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("stale-relocation-journal.xml")
        val store =
            CnbCancelledBuildMetadataStore(
                statePath,
                beforeRelocationAcknowledgement = {
                    throw IOException("injected relocation acknowledgement failure")
                },
                requestRecovery = {},
            )
        store.retain(original, pendingAction("stale-journal-original", CnbBuildMetadataState.ABORTED))

        original.renameTo("stale-journal-target")
        store.relocate("stale-journal-source", "stale-journal-target", original)
        assertTrue(Files.exists(statePath.resolveSibling("${statePath.fileName}.relocations")))

        val replacement = jenkins.createFreeStyleProject("stale-journal-source")
        val replacementRecord =
            store
                .retain(
                    replacement,
                    pendingAction("stale-journal-replacement", CnbBuildMetadataState.ABORTED),
                ).record
        val replacementKey = replacementRecord.action.dispatchKey()

        val reloaded = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        val restoredReplacement =
            reloaded.snapshotIterator().asSequence().single { record ->
                record.action.dispatchKey() == replacementKey
            }
        assertEquals("stale-journal-source", restoredReplacement.itemFullName)
        assertEquals("stale-journal-source", restoredReplacement.credentialContextFullName)
        assertFalse(Files.exists(statePath.resolveSibling("${statePath.fileName}.relocations")))
        var resolvedContext: hudson.model.Item? = null
        assertTrue(
            CnbBuildMetadataReconciliationWork().scheduleCancelledRecord(
                jenkins.jenkins,
                reloaded,
                restoredReplacement,
            ) { context, _, _ ->
                resolvedContext = context
                true
            },
        )
        assertSame(replacement, resolvedContext)
    }

    @Test
    fun `main state commits relocation when journal persistence fails`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("failed-journal-source")
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("failed-relocation-journal.xml")
        val store =
            CnbCancelledBuildMetadataStore(
                statePath,
                beforeRelocationPersistence = {
                    throw IOException("injected relocation journal failure")
                },
                requestRecovery = {},
            )
        val record = store.retain(project, pendingAction("failed-journal", CnbBuildMetadataState.ABORTED)).record
        record.credentialContextIdentity = CnbCancelledBuildMetadataItemIdentity("copied-directory", -1L)
        store.persist(record)

        project.renameTo("failed-journal-target")
        assertEquals(1, store.relocate("failed-journal-source", "failed-journal-target", project))
        assertEquals(project.fullName, record.credentialContextFullName)
        assertTrue(record.credentialContextIdentity?.creationTimeMillis != -1L)
        assertFalse(Files.exists(statePath.resolveSibling("${statePath.fileName}.relocations")))

        val reloaded = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {})
        val restored = reloaded.snapshotIterator().asSequence().single()
        assertEquals(project.fullName, restored.itemFullName)
        assertEquals(project.fullName, restored.credentialContextFullName)
        var resolvedContext: hudson.model.Item? = null
        assertTrue(
            CnbBuildMetadataReconciliationWork().scheduleCancelledRecord(
                jenkins.jenkins,
                reloaded,
                restored,
            ) { context, _, _ ->
                resolvedContext = context
                true
            },
        )
        assertSame(project, resolvedContext)
    }

    @Test
    fun `failed locator writes are retried from dirty memory before recovery snapshots`(jenkins: JenkinsRule) {
        val folder = jenkins.jenkins.createProject(Folder::class.java, "dirty-locator")
        val project = folder.createProject(FreeStyleProject::class.java, "job")
        val statePath =
            jenkins.jenkins.rootDir
                .toPath()
                .resolve("cnb-tests")
                .resolve("dirty-locator.xml")
        var writes = 0
        val store =
            CnbCancelledBuildMetadataStore(
                statePath,
                beforePersistence = {
                    writes++
                    if (writes == 2) throw IOException("injected locator write failure")
                },
                requestRecovery = {},
            )
        val record = store.retain(project, pendingAction("dirty-locator", CnbBuildMetadataState.ABORTED)).record

        assertThrows(IOException::class.java) {
            store.relocate("dirty-locator", "dirty-locator-renamed")
        }
        assertEquals("dirty-locator-renamed/job", record.itemFullName)
        assertEquals("dirty-locator-renamed/job", record.credentialContextFullName)

        store.snapshotIterator()
        val restored = CnbCancelledBuildMetadataStore(statePath, requestRecovery = {}).snapshotIterator().next()
        assertEquals("dirty-locator-renamed/job", restored.itemFullName)
        assertEquals("dirty-locator-renamed/job", restored.credentialContextFullName)
        assertEquals(3, writes)
    }

    @Test
    fun `cancelled records and runs share the bounded action cursor`(jenkins: JenkinsRule) {
        val firstProject = jenkins.createFreeStyleProject("metadata-budget-first")
        val secondProject = jenkins.createFreeStyleProject("metadata-budget-second")
        val firstRun = jenkins.buildAndAssertSuccess(firstProject)
        val secondRun = jenkins.buildAndAssertSuccess(secondProject)
        val firstItemAction = pendingAction("budget-first-item")
        val firstRunAction = pendingAction("budget-first-run")
        val secondItemAction = pendingAction("budget-second-item")
        val secondRunAction = pendingAction("budget-second-run")
        firstRun.addAction(firstRunAction)
        firstRun.save()
        secondRun.addAction(secondRunAction)
        secondRun.save()
        val cancelledRecords =
            listOf(
                CnbCancelledBuildMetadataRecord(firstProject.fullName, firstItemAction),
                CnbCancelledBuildMetadataRecord(secondProject.fullName, secondItemAction),
            )
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        val scheduled = mutableListOf<CnbBuildMetadataAction>()
        requests.request()

        var ticks = 0
        while (requests.pendingGeneration() != null && ticks < 12) {
            val stats =
                work.recoverOnce(
                    recoveryRequests = requests,
                    queueItems = { emptyList<Queue.Item>().iterator() },
                    cancelledRecords = { cancelledRecords.iterator() },
                    jobs = { listOf<Job<*, *>>(firstProject, secondProject).iterator() },
                    maximumJobs = 1,
                    maximumRuns = 1,
                    maximumActions = 1,
                    scheduleQueue = { _, _ -> error("queue must be empty") },
                    scheduleCancelled = { record ->
                        scheduled += record.action
                        true
                    },
                    scheduleRun = { _, action ->
                        scheduled += action
                        true
                    },
                )
            assertTrue(stats.jobsInspected <= 1)
            assertTrue(stats.runsInspected <= 1)
            assertTrue(stats.actionsInspected <= 1)
            ticks++
        }

        assertNull(requests.pendingGeneration())
        assertEquals(
            listOf(firstItemAction, secondItemAction, firstRunAction, secondRunAction),
            scheduled,
        )
    }

    @Test
    fun `queue action budget resumes remaining contexts on later ticks`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-queue-budget")

        @Suppress("DEPRECATION")
        val item = requireNotNull(Queue.getInstance().schedule(project, 3_600, emptyList()))
        val actions = (1..3).map { pendingAction("queue-budget-$it") }
        actions.forEach(item::addAction)
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        val scheduled = mutableListOf<CnbBuildMetadataAction>()
        requests.request()

        try {
            var ticks = 0
            while (requests.pendingGeneration() != null && ticks < 10) {
                val stats =
                    work.recoverOnce(
                        recoveryRequests = requests,
                        queueItems = { listOf<Queue.Item>(item).iterator() },
                        jobs = { emptyList<Job<*, *>>().iterator() },
                        maximumQueueItems = 1,
                        maximumActions = 1,
                        scheduleQueue = { _, action ->
                            scheduled += action
                            true
                        },
                        scheduleRun = { _, _ -> error("runs must be empty") },
                    )
                assertTrue(stats.actionsInspected <= 1)
                ticks++
            }

            assertNull(requests.pendingGeneration())
            assertEquals(actions, scheduled)
        } finally {
            Queue.getInstance().cancel(item)
        }
    }

    @Test
    fun `run action budget resumes remaining contexts on later ticks`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject("metadata-run-budget")
        val run = jenkins.buildAndAssertSuccess(project)
        val actions = (1..3).map { pendingAction("run-budget-$it") }
        actions.forEach(run::addAction)
        val requests = CnbBuildMetadataRecoveryGate(startupRecovery = false)
        val work = CnbBuildMetadataReconciliationWork()
        val scheduled = mutableListOf<CnbBuildMetadataAction>()
        requests.request()

        var ticks = 0
        while (requests.pendingGeneration() != null && ticks < 10) {
            val stats =
                work.recoverOnce(
                    recoveryRequests = requests,
                    queueItems = { emptyList<Queue.Item>().iterator() },
                    jobs = { listOf<Job<*, *>>(project).iterator() },
                    maximumJobs = 1,
                    maximumRuns = 1,
                    maximumActions = 1,
                    scheduleQueue = { _, _ -> error("queue must be empty") },
                    scheduleRun = { scheduledRun, action ->
                        assertEquals(run, scheduledRun)
                        scheduled += action
                        true
                    },
                )
            assertTrue(stats.actionsInspected <= 1)
            ticks++
        }

        assertNull(requests.pendingGeneration())
        assertEquals(actions, scheduled)
    }

    private fun pendingAction(
        identity: String,
        state: CnbBuildMetadataState = CnbBuildMetadataState.SUCCESS,
    ): CnbBuildMetadataAction {
        val action = CnbBuildMetadataAction("run:metadata-history#$identity")
        action.advance(
            CnbBuildMetadataTarget(
                serverId = "cnb-cool",
                repository = "team/project",
                sha = "0123456789abcdef0123456789abcdef01234567",
                pullRequestNumber = null,
                context = "metadata-history",
                credentialsId = null,
            ),
            state,
            "metadata-history #$identity",
            "job/metadata-history/$identity/",
        )
        return action
    }
}
