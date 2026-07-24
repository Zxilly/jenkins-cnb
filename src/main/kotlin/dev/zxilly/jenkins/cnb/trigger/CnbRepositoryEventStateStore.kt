package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.MAX_REPOSITORY_EVENTS_PER_HOUR
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.HexFormat
import java.util.LinkedHashMap
import java.util.PriorityQueue
import java.util.Properties

internal data class CnbRefLifecycleTransition(
    val qualifiedRef: String,
    val present: Boolean,
    val occurredAt: Instant,
    val stableEventId: String,
) {
    init {
        require(qualifiedRef.startsWith("refs/heads/") || qualifiedRef.startsWith("refs/tags/")) {
            "ref lifecycle transition must identify a branch or tag"
        }
        require(isValidLifecycleEventId(stableEventId)) { "ref lifecycle event ID is invalid" }
    }
}

internal data class CnbScopedRefLifecycleTransition(
    val scope: CnbRepositoryEventStateScope,
    val transition: CnbRefLifecycleTransition,
)

internal data class CnbRefLifecycleResult(
    val generation: Long,
    val present: Boolean,
    /** True for a newly applied marker or an exact replay of the current marker. */
    val current: Boolean,
)

/** Stable, hash-only persistence scope for one authorized repository-event consumer. */
internal data class CnbRepositoryEventStateScope(
    val serverId: String,
    val repositoryPath: String,
    val authorizationScope: String,
    val consumerScope: String,
) {
    init {
        require(serverId.isNotBlank()) { "repository-event server scope must not be blank" }
        require(repositoryPath.isNotBlank()) { "repository-event repository scope must not be blank" }
        require(authorizationScope.isNotBlank()) { "repository-event authorization scope must not be blank" }
        require(consumerScope.isNotBlank()) { "repository-event consumer scope must not be blank" }
    }
}

/**
 * Durable incarnation state for classic-job refs.
 *
 * A deletion has no Run to carry a receipt, so revision history alone cannot distinguish a ref
 * recreated at the same object ID. The generation advances only for deleted-to-present
 * transitions. Ordering by the validated event timestamp and stable event key makes archive
 * replay independent of API response order and makes webhook/poll convergence idempotent.
 */
internal class CnbRefLifecycleStore(
    private val path: Path,
    private val capacity: Int = DEFAULT_REF_LIFECYCLE_CAPACITY,
    maxJournalBytes: Long = DEFAULT_JOURNAL_BYTES,
    compactionThreshold: Int = DEFAULT_COMPACTION_THRESHOLD,
    beforePersistence: () -> Unit = {},
    forceNonAtomicMove: Boolean = false,
) {
    private data class LifecycleEntry(
        val present: Boolean,
        val generation: Long,
        val occurredAt: Instant,
        val stableEventId: String,
    )

    private data class LifecycleRecord(
        val operation: Operation,
        val refScopeHash: String,
        val present: Boolean,
        val generation: Long,
        val epochSecond: Long,
        val nano: Int,
        val stableEventId: String,
    )

    private enum class Operation(
        val code: String,
    ) {
        SET("S"),
        DELETE("D"),
        FLOOR("F"),
    }

    private val entries = LinkedHashMap<String, LifecycleEntry>()
    private var generationFloor = 0L
    private val journal =
        AppendOnlyStateJournal(
            path = path,
            magic = REF_LIFECYCLE_MAGIC,
            maxJournalBytes = maxJournalBytes,
            compactionThreshold = compactionThreshold,
            encode = ::encode,
            decode = ::decode,
            snapshot = ::snapshot,
            beforePersistence = beforePersistence,
            forceNonAtomicMove = forceNonAtomicMove,
        )

    init {
        require(capacity > 0) { "ref lifecycle capacity must be positive" }
        val loaded = journal.load(loadLegacy = { false }, apply = ::applyLoaded)
        val adjusted = enforceCapacity()
        if (loaded.legacy || loaded.capacityAdjusted || loaded.needsCompaction || adjusted) journal.compact()
    }

    /** Applies a delivery batch atomically and returns the resulting state for each input transition. */
    @Synchronized
    fun apply(transitions: List<CnbScopedRefLifecycleTransition>): List<CnbRefLifecycleResult> {
        if (transitions.isEmpty()) return emptyList()
        val originals = LinkedHashMap<String, LifecycleEntry?>()
        val originalGenerationFloor = generationFloor
        val records = ArrayList<LifecycleRecord>(transitions.size)
        val results = ArrayList<CnbRefLifecycleResult>(transitions.size)

        fun remember(key: String) {
            if (!originals.containsKey(key)) originals[key] = entries[key]
        }

        try {
            for ((scope, transition) in transitions) {
                val key = refScopeHash(scope, transition.qualifiedRef)
                val previous = entries[key]
                val order = previous?.let { compareTransition(transition, it) }
                if (previous != null && requireNotNull(order) <= 0) {
                    results +=
                        CnbRefLifecycleResult(
                            previous.generation,
                            previous.present,
                            current = order == 0,
                        )
                    continue
                }
                val generation =
                    if (transition.present && previous?.present == false) {
                        check(previous.generation < Long.MAX_VALUE) { "ref lifecycle generation overflow" }
                        previous.generation + 1L
                    } else {
                        previous?.generation ?: generationFloor
                    }
                val updated =
                    LifecycleEntry(
                        present = transition.present,
                        generation = generation,
                        occurredAt = transition.occurredAt,
                        stableEventId = transition.stableEventId,
                    )
                remember(key)
                entries[key] = updated
                records += setRecord(key, updated)
                results += CnbRefLifecycleResult(generation, transition.present, current = true)
            }

            if (entries.size > capacity) {
                advanceGenerationFloor()
                records += floorRecord()
                while (entries.size > capacity) {
                    val victim = oldestEntry() ?: break
                    remember(victim.key)
                    entries.remove(victim.key)
                    records += deleteRecord(victim.key)
                }
            }
            journal.persist(records)
            return results
        } catch (failure: Exception) {
            for ((key, original) in originals) {
                if (original == null) entries.remove(key) else entries[key] = original
            }
            generationFloor = originalGenerationFloor
            throw failure
        }
    }

    internal fun journalCompactionCount(): Int = journal.compactionCount

    private fun compareTransition(
        transition: CnbRefLifecycleTransition,
        previous: LifecycleEntry,
    ): Int {
        val time = transition.occurredAt.compareTo(previous.occurredAt)
        return if (time != 0) time else compareLifecycleEventIds(transition.stableEventId, previous.stableEventId)
    }

    private fun oldestEntry(): Map.Entry<String, LifecycleEntry>? =
        entries.entries.minWithOrNull(
            Comparator { left, right ->
                val time = left.value.occurredAt.compareTo(right.value.occurredAt)
                if (time != 0) {
                    time
                } else {
                    val eventId = compareLifecycleEventIds(left.value.stableEventId, right.value.stableEventId)
                    if (eventId != 0) eventId else left.key.compareTo(right.key)
                }
            },
        )

    private fun enforceCapacity(): Boolean {
        if (entries.size <= capacity) return false
        advanceGenerationFloor()
        var adjusted = false
        while (entries.size > capacity) {
            oldestEntry()?.let { entries.remove(it.key) } ?: break
            adjusted = true
        }
        return adjusted
    }

    private fun applyLoaded(record: LifecycleRecord): Boolean {
        when (record.operation) {
            Operation.DELETE -> entries.remove(record.refScopeHash)
            Operation.FLOOR -> generationFloor = maxOf(generationFloor, record.generation)
            Operation.SET -> {
                val occurredAt = instantOfEpochSecondAndNano(record.epochSecond, record.nano) ?: return false
                entries[record.refScopeHash] =
                    LifecycleEntry(record.present, record.generation, occurredAt, record.stableEventId)
            }
        }
        return enforceCapacity()
    }

    private fun snapshot(): Sequence<LifecycleRecord> =
        sequence {
            if (generationFloor > 0L) yield(floorRecord())
            for ((key, entry) in entries) yield(setRecord(key, entry))
        }

    private fun advanceGenerationFloor() {
        val maximum = entries.values.maxOfOrNull { it.generation }?.let { maxOf(it, generationFloor) } ?: generationFloor
        check(maximum < Long.MAX_VALUE) { "ref lifecycle generation floor overflow" }
        generationFloor = maximum + 1L
    }

    private fun setRecord(
        key: String,
        entry: LifecycleEntry,
    ): LifecycleRecord =
        LifecycleRecord(
            Operation.SET,
            key,
            entry.present,
            entry.generation,
            entry.occurredAt.epochSecond,
            entry.occurredAt.nano,
            entry.stableEventId,
        )

    private fun deleteRecord(key: String): LifecycleRecord =
        LifecycleRecord(Operation.DELETE, key, false, 0L, 0L, 0, ZERO_ID)

    private fun floorRecord(): LifecycleRecord =
        LifecycleRecord(Operation.FLOOR, FLOOR_KEY, false, generationFloor, 0L, 0, ZERO_ID)

    private fun encode(record: LifecycleRecord): ByteArray =
        encodeJournalRecord(
            REF_LIFECYCLE_MAGIC,
            listOf(
                record.operation.code,
                record.refScopeHash,
                if (record.present) "1" else "0",
                record.generation.toString(),
                record.epochSecond.toString(),
                record.nano.toString(),
                encodeLifecycleEventId(record.stableEventId),
            ),
        )

    private fun decode(line: String): LifecycleRecord? {
        val fields = verifiedJournalFields(line, REF_LIFECYCLE_MAGIC, 7) ?: return null
        val operation = Operation.entries.firstOrNull { it.code == fields[0] } ?: return null
        val key = fields[1].takeIf(STATE_KEY_PATTERN::matches) ?: return null
        val present = when (fields[2]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val generation = fields[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val epochSecond = fields[4].toLongOrNull() ?: return null
        val nano = fields[5].toIntOrNull()?.takeIf { it in 0..999_999_999 } ?: return null
        val eventId = decodeLifecycleEventId(fields[6]) ?: return null
        when (operation) {
            Operation.SET -> Unit
            Operation.DELETE -> {
                if (present || generation != 0L || epochSecond != 0L || nano != 0 || eventId != ZERO_ID) return null
            }

            Operation.FLOOR -> {
                if (key != FLOOR_KEY || present || epochSecond != 0L || nano != 0 || eventId != ZERO_ID) return null
            }
        }
        return LifecycleRecord(operation, key, present, generation, epochSecond, nano, eventId)
    }

    private fun refScopeHash(
        scope: CnbRepositoryEventStateScope,
        qualifiedRef: String,
    ): String = sha256("${scopeHash(scope)}\u0000$qualifiedRef")

    private companion object {
        private const val REF_LIFECYCLE_MAGIC = "CNB_REF_LIFECYCLE_V1"
        private const val DEFAULT_REF_LIFECYCLE_CAPACITY = 100_000
        private const val ZERO_ID = "0000000000000000000000000000000000000000000000000000000000000000"
        private const val FLOOR_KEY = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    }
}

/** Jenkins-home-backed cursor for the last completely processed UTC hour per authorization scope. */
internal class CnbRepositoryEventCursorStore(
    private val path: Path,
    private val capacity: Int = DEFAULT_CURSOR_CAPACITY,
    maxJournalBytes: Long = DEFAULT_JOURNAL_BYTES,
    compactionThreshold: Int = DEFAULT_COMPACTION_THRESHOLD,
    forceNonAtomicMove: Boolean = false,
) {
    private data class CursorRecord(
        val operation: Operation,
        val scopeHash: String,
        val epochSecond: Long,
    )

    private enum class Operation(
        val code: String,
    ) {
        SET("S"),
        DELETE("D"),
    }

    private val entries = LinkedHashMap<String, Instant>()
    private val journal =
        AppendOnlyStateJournal(
            path = path,
            magic = CURSOR_MAGIC,
            maxJournalBytes = maxJournalBytes,
            compactionThreshold = compactionThreshold,
            encode = ::encode,
            decode = ::decode,
            snapshot = ::snapshot,
            forceNonAtomicMove = forceNonAtomicMove,
        )

    init {
        require(capacity > 0) { "cursor capacity must be positive" }
        val loaded = journal.load(::loadLegacy, ::applyLoaded)
        if (loaded.legacy || loaded.capacityAdjusted || loaded.needsCompaction) {
            journal.compact()
        }
    }

    /**
     * Returns the last completed hour. A future value is discarded so a backwards clock adjustment
     * cannot prevent this repository from being polled indefinitely.
     */
    @Synchronized
    fun get(
        scope: CnbRepositoryEventStateScope,
        latestCompletedHour: Instant,
    ): Instant? = get(scopeHash(scope), latestCompletedHour)

    @Synchronized
    fun get(
        serverId: String,
        repositoryPath: String,
        latestCompletedHour: Instant,
    ): Instant? = get(repositoryScopeKey(serverId, repositoryPath), latestCompletedHour)

    private fun get(
        key: String,
        latestCompletedHour: Instant,
    ): Instant? {
        requireHour(latestCompletedHour)
        val value = entries[key] ?: return null
        if (!value.isAfter(latestCompletedHour)) return value

        entries.remove(key)
        try {
            journal.persist(listOf(CursorRecord(Operation.DELETE, key, 0)))
        } catch (failure: Exception) {
            entries[key] = value
            throw failure
        }
        return null
    }

    /** Advances monotonically after an hour has been fetched and all of its events were handled. */
    @Synchronized
    fun advance(
        scope: CnbRepositoryEventStateScope,
        completedHour: Instant,
    ) = advance(scopeHash(scope), completedHour)

    @Synchronized
    fun advance(
        serverId: String,
        repositoryPath: String,
        completedHour: Instant,
    ) = advance(repositoryScopeKey(serverId, repositoryPath), completedHour)

    private fun advance(
        key: String,
        completedHour: Instant,
    ) {
        requireHour(completedHour)
        val previous = entries[key]
        if (previous != null && !completedHour.isAfter(previous)) return

        val evicted =
            if (previous == null && entries.size >= capacity) {
                entries.entries.minWithOrNull(compareBy<Map.Entry<String, Instant>> { it.value }.thenBy { it.key })
            } else {
                null
            }
        evicted?.let { entries.remove(it.key) }
        entries[key] = completedHour
        val records =
            buildList {
                evicted?.let { add(CursorRecord(Operation.DELETE, it.key, 0)) }
                add(CursorRecord(Operation.SET, key, completedHour.epochSecond))
            }
        try {
            journal.persist(records)
        } catch (failure: Exception) {
            if (previous == null) entries.remove(key) else entries[key] = previous
            evicted?.let { entries[it.key] = it.value }
            throw failure
        }
    }

    internal fun journalCompactionCount(): Int = journal.compactionCount

    private fun loadLegacy(file: Path): Boolean {
        val properties = loadLegacyProperties(file, journal.maxJournalBytes)
        var adjusted = false
        for (key in properties.stringPropertyNames().sorted()) {
            if (!STATE_KEY_PATTERN.matches(key)) continue
            val epochSecond = properties.getProperty(key).toLongOrNull() ?: continue
            val value = instantOfEpochSecond(epochSecond) ?: continue
            if (value != value.truncatedTo(ChronoUnit.HOURS)) continue
            val previous = entries[key]
            if (previous == null || value.isAfter(previous)) entries[key] = value
            if (entries.size > capacity) {
                evictOldestCursor()
                adjusted = true
            }
        }
        return adjusted
    }

    private fun applyLoaded(record: CursorRecord): Boolean {
        when (record.operation) {
            Operation.DELETE -> {
                entries.remove(record.scopeHash)
            }

            Operation.SET -> {
                val value = instantOfEpochSecond(record.epochSecond) ?: return false
                if (value != value.truncatedTo(ChronoUnit.HOURS)) return false
                val previous = entries[record.scopeHash]
                if (previous == null || value.isAfter(previous)) entries[record.scopeHash] = value
            }
        }
        if (entries.size <= capacity) return false
        evictOldestCursor()
        return true
    }

    private fun evictOldestCursor() {
        entries.entries
            .minWithOrNull(compareBy<Map.Entry<String, Instant>> { it.value }.thenBy { it.key })
            ?.let { entries.remove(it.key) }
    }

    private fun snapshot(): Sequence<CursorRecord> =
        entries.asSequence().map { (scopeHash, hour) ->
            CursorRecord(Operation.SET, scopeHash, hour.epochSecond)
        }

    private fun encode(record: CursorRecord): ByteArray =
        encodeJournalRecord(
            CURSOR_MAGIC,
            listOf(record.operation.code, record.scopeHash, record.epochSecond.toString()),
        )

    private fun decode(line: String): CursorRecord? {
        val fields = verifiedJournalFields(line, CURSOR_MAGIC, 3) ?: return null
        val operation = Operation.entries.firstOrNull { it.code == fields[0] } ?: return null
        val scopeHash = fields[1].takeIf(STATE_KEY_PATTERN::matches) ?: return null
        val epochSecond = fields[2].toLongOrNull() ?: return null
        if (operation == Operation.DELETE && epochSecond != 0L) return null
        return CursorRecord(operation, scopeHash, epochSecond)
    }

    private fun requireHour(value: Instant) {
        require(value == value.truncatedTo(ChronoUnit.HOURS)) { "cursor must be an exact UTC hour" }
    }

    companion object {
        private const val CURSOR_MAGIC = "CNB_EVENT_CURSOR_V1"
        private const val DEFAULT_CURSOR_CAPACITY = 100_000
    }
}

/**
 * Jenkins-home-backed, TTL-bounded event deduplication containing hashes only.
 *
 * State is partitioned by a SHA-256 authorization/consumer scope. A scope cannot consume more than
 * its quota, and global pressure evicts the oldest entry from the currently largest scope so a
 * noisy consumer cannot continuously evict every quiet consumer.
 */
internal class CnbRepositoryEventDedupStore(
    private val path: Path,
    private val ttl: Duration = Duration.ofDays(7),
    private val capacity: Int = DEFAULT_DEDUP_CAPACITY,
    private val perRepositoryCapacity: Int = minOf(DEFAULT_REPOSITORY_CAPACITY, capacity),
    maxJournalBytes: Long = DEFAULT_JOURNAL_BYTES,
    compactionThreshold: Int = DEFAULT_COMPACTION_THRESHOLD,
    private val clock: Clock = Clock.systemUTC(),
    beforePersistence: () -> Unit = {},
    forceNonAtomicMove: Boolean = false,
) {
    private data class DedupRecord(
        val operation: Operation,
        val scopeHash: String,
        val keyHash: String,
        val epochMillis: Long,
    )

    private enum class Operation(
        val code: String,
    ) {
        PUT("P"),
        DELETE("D"),
    }

    private data class ScopePressure(
        val scopeHash: String,
        val size: Int,
        val oldestKey: String,
        val oldestAt: Instant,
    )

    private val entries = LinkedHashMap<String, LinkedHashMap<String, Instant>>()
    private var totalEntries = 0
    private var lastPrunedAt: Instant? = null
    private var prunePasses = 0
    private val journal =
        AppendOnlyStateJournal(
            path = path,
            magic = DEDUP_MAGIC,
            maxJournalBytes = maxJournalBytes,
            compactionThreshold = compactionThreshold,
            encode = ::encode,
            decode = ::decode,
            snapshot = ::snapshot,
            beforePersistence = beforePersistence,
            forceNonAtomicMove = forceNonAtomicMove,
        )

    init {
        require(!ttl.isNegative && !ttl.isZero) { "deduplication TTL must be positive" }
        require(capacity > 0) { "capacity must be positive" }
        require(perRepositoryCapacity in 1..capacity) {
            "per-repository capacity must be positive and no larger than global capacity"
        }
        val loaded = journal.load(::loadLegacy, ::applyLoaded)
        val initializationTime = clock.instant()
        val pruned = prune(initializationTime)
        lastPrunedAt = initializationTime
        val capacityAdjusted = enforceLoadedCapacity()
        if (loaded.legacy || loaded.capacityAdjusted || capacityAdjusted || pruned || loaded.needsCompaction) {
            journal.compact()
        }
    }

    @Synchronized
    fun contains(
        scope: CnbRepositoryEventStateScope,
        key: String,
        now: Instant,
    ): Boolean = contains(scopeHash(scope), key, now, includeLegacy = false)

    @Synchronized
    fun contains(
        serverId: String,
        repositoryPath: String,
        key: String,
        now: Instant,
    ): Boolean = contains(repositoryScopeKey(serverId, repositoryPath), key, now, includeLegacy = true)

    private fun contains(
        scopeHash: String,
        key: String,
        now: Instant,
        includeLegacy: Boolean,
    ): Boolean {
        requireEventKey(key)
        pruneOnce(now)
        return isSeen(scopeHash, key, now) || (includeLegacy && isSeen(LEGACY_SCOPE_HASH, key, now))
    }

    /** Filters one fetched hour with a single global expiry pass instead of one pass per event. */
    @Synchronized
    fun unseenKeys(
        scope: CnbRepositoryEventStateScope,
        keys: Collection<String>,
        now: Instant,
    ): Set<String> = unseenKeys(scopeHash(scope), keys, now, includeLegacy = false)

    @Synchronized
    fun unseenKeys(
        serverId: String,
        repositoryPath: String,
        keys: Collection<String>,
        now: Instant,
    ): Set<String> = unseenKeys(repositoryScopeKey(serverId, repositoryPath), keys, now, includeLegacy = true)

    private fun unseenKeys(
        scopeHash: String,
        keys: Collection<String>,
        now: Instant,
        includeLegacy: Boolean,
    ): Set<String> {
        if (keys.isEmpty()) return emptySet()
        keys.forEach(::requireEventKey)
        pruneOnce(now)
        return keys
            .asSequence()
            .filterNot { key -> isSeen(scopeHash, key, now) || (includeLegacy && isSeen(LEGACY_SCOPE_HASH, key, now)) }
            .toCollection(LinkedHashSet())
    }

    /**
     * Persists a completed dispatch batch with one append and one fsync. If persistence fails, every
     * non-expiry mutation is rolled back and the caller leaves its completed-hour cursor unchanged.
     */
    @Synchronized
    fun mark(
        scope: CnbRepositoryEventStateScope,
        keys: Collection<String>,
        now: Instant,
    ) = mark(scopeHash(scope), keys, now)

    @Synchronized
    fun mark(
        serverId: String,
        repositoryPath: String,
        keys: Collection<String>,
        now: Instant,
    ) = mark(repositoryScopeKey(serverId, repositoryPath), keys, now)

    private fun mark(
        scopeHash: String,
        keys: Collection<String>,
        now: Instant,
    ) {
        if (keys.isEmpty()) return
        val uniqueKeys = LinkedHashSet(keys)
        uniqueKeys.forEach(::requireEventKey)
        pruneOnce(now)

        val originalScopes = LinkedHashMap<String, LinkedHashMap<String, Instant>?>()
        val originalTotalEntries = totalEntries
        val records = ArrayList<DedupRecord>(uniqueKeys.size * 2)
        var pressureQueue: PriorityQueue<ScopePressure>? = null

        fun remember(scope: String) {
            if (!originalScopes.containsKey(scope)) {
                originalScopes[scope] = entries[scope]?.let(::LinkedHashMap)
            }
        }

        try {
            for (key in uniqueKeys) {
                remember(scopeHash)
                var scopedEntries = entries.getOrPut(scopeHash, ::LinkedHashMap)
                if (scopedEntries.remove(key) != null) {
                    scopedEntries[key] = now
                    records += DedupRecord(Operation.PUT, scopeHash, key, now.toEpochMilli())
                    pressureQueue?.let { addPressure(it, scopeHash) }
                    continue
                }

                while (scopedEntries.size >= quotaFor(scopeHash)) {
                    val victim = scopedEntries.entries.firstOrNull() ?: break
                    records += remove(scopeHash, victim.key, originalScopes)
                    pressureQueue?.let { addPressure(it, scopeHash) }
                    scopedEntries = entries.getOrPut(scopeHash, ::LinkedHashMap)
                }
                while (totalEntries >= capacity) {
                    val queue = pressureQueue ?: newPressureQueue().also { pressureQueue = it }
                    val victim = fairVictim(queue) ?: break
                    records += remove(victim.first, victim.second, originalScopes)
                    addPressure(queue, victim.first)
                }

                entries.getOrPut(scopeHash, ::LinkedHashMap)[key] = now
                totalEntries++
                records += DedupRecord(Operation.PUT, scopeHash, key, now.toEpochMilli())
                pressureQueue?.let { addPressure(it, scopeHash) }
            }
            journal.persist(records)
        } catch (failure: Exception) {
            restoreScopes(originalScopes)
            totalEntries = originalTotalEntries
            throw failure
        }
    }

    internal fun journalCompactionCount(): Int = journal.compactionCount

    internal fun prunePassCount(): Int = prunePasses

    internal fun sizeByRepository(
        serverId: String,
        repositoryPath: String,
    ): Int = entries[repositoryScopeKey(serverId, repositoryPath)]?.size ?: 0

    private fun remove(
        scopeHash: String,
        keyHash: String,
        originalScopes: MutableMap<String, LinkedHashMap<String, Instant>?>,
    ): DedupRecord {
        if (!originalScopes.containsKey(scopeHash)) {
            originalScopes[scopeHash] = entries[scopeHash]?.let(::LinkedHashMap)
        }
        val scopedEntries = entries[scopeHash]
        if (scopedEntries?.remove(keyHash) != null) totalEntries--
        if (scopedEntries?.isEmpty() == true) entries.remove(scopeHash)
        return DedupRecord(Operation.DELETE, scopeHash, keyHash, 0)
    }

    private fun restoreScopes(originalScopes: Map<String, LinkedHashMap<String, Instant>?>) {
        for ((scopeHash, original) in originalScopes) {
            if (original == null) entries.remove(scopeHash) else entries[scopeHash] = original
        }
    }

    /** Selects the oldest entry from the largest scope, with lazy stale-candidate removal. */
    private fun fairVictim(queue: PriorityQueue<ScopePressure>): Pair<String, String>? {
        while (true) {
            val candidate = queue.poll() ?: return null
            val scopedEntries = entries[candidate.scopeHash] ?: continue
            val oldest = scopedEntries.entries.firstOrNull() ?: continue
            if (
                candidate.size == scopedEntries.size &&
                candidate.oldestKey == oldest.key &&
                candidate.oldestAt == oldest.value
            ) {
                return candidate.scopeHash to candidate.oldestKey
            }
        }
    }

    private fun newPressureQueue(): PriorityQueue<ScopePressure> =
        PriorityQueue(
            compareByDescending<ScopePressure> { it.size }
                .thenBy { it.oldestAt }
                .thenBy { it.scopeHash },
        ).also { queue -> entries.keys.forEach { addPressure(queue, it) } }

    private fun addPressure(
        queue: PriorityQueue<ScopePressure>,
        scopeHash: String,
    ) {
        val scopedEntries = entries[scopeHash] ?: return
        val oldest = scopedEntries.entries.firstOrNull() ?: return
        queue += ScopePressure(scopeHash, scopedEntries.size, oldest.key, oldest.value)
    }

    private fun loadLegacy(file: Path): Boolean {
        val properties = loadLegacyProperties(file, journal.maxJournalBytes)
        val newest =
            properties
                .stringPropertyNames()
                .asSequence()
                .filter(STATE_KEY_PATTERN::matches)
                .mapNotNull { key ->
                    properties
                        .getProperty(key)
                        .toLongOrNull()
                        ?.let(::instantOfEpochMilli)
                        ?.let { key to it }
                }.sortedWith(compareByDescending<Pair<String, Instant>> { it.second }.thenBy { it.first })
                .take(capacity)
                .toList()
                .asReversed()
        if (newest.isNotEmpty()) {
            entries[LEGACY_SCOPE_HASH] = LinkedHashMap<String, Instant>().apply { putAll(newest) }
            totalEntries = newest.size
        }
        return properties.size > newest.size
    }

    private fun applyLoaded(record: DedupRecord): Boolean {
        when (record.operation) {
            Operation.DELETE -> {
                val scopedEntries = entries[record.scopeHash] ?: return false
                if (scopedEntries.remove(record.keyHash) != null) totalEntries--
                if (scopedEntries.isEmpty()) entries.remove(record.scopeHash)
            }

            Operation.PUT -> {
                val seenAt = instantOfEpochMilli(record.epochMillis) ?: return false
                val scopedEntries = entries.getOrPut(record.scopeHash, ::LinkedHashMap)
                if (scopedEntries.remove(record.keyHash) == null) totalEntries++
                scopedEntries[record.keyHash] = seenAt
            }
        }
        return false
    }

    private fun enforceLoadedCapacity(): Boolean {
        var adjusted = false
        val scopes = entries.entries.iterator()
        while (scopes.hasNext()) {
            val (scopeHash, scopedEntries) = scopes.next()
            while (scopedEntries.size > quotaFor(scopeHash)) {
                scopedEntries.entries.firstOrNull()?.let {
                    scopedEntries.remove(it.key)
                    totalEntries--
                    adjusted = true
                }
            }
            if (scopedEntries.isEmpty()) scopes.remove()
        }
        val queue = newPressureQueue()
        while (totalEntries > capacity) {
            val victim = fairVictim(queue) ?: break
            val victimEntries = entries[victim.first] ?: break
            if (victimEntries.remove(victim.second) != null) totalEntries--
            if (victimEntries.isEmpty()) entries.remove(victim.first)
            addPressure(queue, victim.first)
            adjusted = true
        }
        return adjusted
    }

    private fun pruneOnce(now: Instant): Boolean {
        if (lastPrunedAt == now) return false
        val changed = prune(now)
        lastPrunedAt = now
        return changed
    }

    private fun isSeen(
        scopeHash: String,
        keyHash: String,
        now: Instant,
    ): Boolean {
        val scopedEntries = entries[scopeHash] ?: return false
        val seenAt = scopedEntries[keyHash] ?: return false
        val oldest = now.minus(ttl)
        val newest = now.plusSeconds(MAX_FUTURE_SKEW_SECONDS)
        if (!seenAt.isBefore(oldest) && !seenAt.isAfter(newest)) return true
        if (scopedEntries.remove(keyHash) != null) totalEntries--
        if (scopedEntries.isEmpty()) entries.remove(scopeHash)
        return false
    }

    private fun prune(now: Instant): Boolean {
        prunePasses++
        val oldest = now.minus(ttl)
        val newest = now.plusSeconds(MAX_FUTURE_SKEW_SECONDS)
        var changed = false
        val scopes = entries.entries.iterator()
        while (scopes.hasNext()) {
            val scopedEntries = scopes.next().value
            val before = scopedEntries.size
            scopedEntries.entries.removeIf { (_, seenAt) -> seenAt.isBefore(oldest) || seenAt.isAfter(newest) }
            totalEntries -= before - scopedEntries.size
            changed = changed || before != scopedEntries.size
            if (scopedEntries.isEmpty()) scopes.remove()
        }
        return changed
    }

    private fun quotaFor(scopeHash: String): Int = if (scopeHash == LEGACY_SCOPE_HASH) capacity else perRepositoryCapacity

    private fun snapshot(): Sequence<DedupRecord> =
        sequence {
            for ((scopeHash, scopedEntries) in entries) {
                for ((keyHash, seenAt) in scopedEntries) {
                    yield(DedupRecord(Operation.PUT, scopeHash, keyHash, seenAt.toEpochMilli()))
                }
            }
        }

    private fun encode(record: DedupRecord): ByteArray =
        encodeJournalRecord(
            DEDUP_MAGIC,
            listOf(
                record.operation.code,
                record.scopeHash,
                record.keyHash,
                record.epochMillis.toString(),
            ),
        )

    private fun decode(line: String): DedupRecord? {
        val fields = verifiedJournalFields(line, DEDUP_MAGIC, 4) ?: return null
        val operation = Operation.entries.firstOrNull { it.code == fields[0] } ?: return null
        val scopeHash = fields[1].takeIf(STATE_KEY_PATTERN::matches) ?: return null
        val keyHash = fields[2].takeIf(STATE_KEY_PATTERN::matches) ?: return null
        val epochMillis = fields[3].toLongOrNull() ?: return null
        if (operation == Operation.DELETE && epochMillis != 0L) return null
        return DedupRecord(operation, scopeHash, keyHash, epochMillis)
    }

    private fun requireEventKey(key: String) {
        require(STATE_KEY_PATTERN.matches(key)) { "repository event key must be a SHA-256 hash" }
    }

    companion object {
        private const val DEDUP_MAGIC = "CNB_EVENT_DEDUP_V1"
        private const val DEFAULT_DEDUP_CAPACITY = 100_000
        private const val DEFAULT_REPOSITORY_CAPACITY = MAX_REPOSITORY_EVENTS_PER_HOUR
        private const val MAX_FUTURE_SKEW_SECONDS = 60L
        private val LEGACY_SCOPE_HASH = sha256("cnb-repository-event-legacy-v1")
    }
}

/** Append-only, checksummed journal with bounded reads and atomic snapshot compaction. */
private class AppendOnlyStateJournal<Record>(
    private val path: Path,
    private val magic: String,
    val maxJournalBytes: Long,
    private val compactionThreshold: Int,
    private val encode: (Record) -> ByteArray,
    private val decode: (String) -> Record?,
    private val snapshot: () -> Sequence<Record>,
    private val beforePersistence: () -> Unit = {},
    private val forceNonAtomicMove: Boolean = false,
) {
    data class LoadResult(
        val legacy: Boolean = false,
        val capacityAdjusted: Boolean = false,
        val needsCompaction: Boolean = false,
    )

    private var journalBytes = 0L
    private var mutationRecords = 0
    var compactionCount: Int = 0
        private set

    init {
        require(maxJournalBytes >= MIN_JOURNAL_BYTES) { "state journal size limit is too small" }
        require(compactionThreshold > 0) { "state journal compaction threshold must be positive" }
    }

    fun load(
        loadLegacy: (Path) -> Boolean,
        apply: (Record) -> Boolean,
    ): LoadResult {
        val file = path.toAbsolutePath().normalize()
        recoverInterruptedReplacement(file)
        if (!Files.isRegularFile(file)) return LoadResult()
        if (!startsWithMagic(file)) {
            val adjusted = loadLegacy(file)
            journalBytes = Files.size(file)
            return LoadResult(legacy = true, capacityAdjusted = adjusted)
        }

        val size = Files.size(file)
        var bytesRead = 0L
        var lastGoodOffset = 0L
        var validRecords = 0
        var capacityAdjusted = false
        var corruptTail = false
        Files.newInputStream(file).buffered().use { input ->
            val line = ByteArrayOutputStream(MAX_RECORD_BYTES)
            var lineTooLong = false
            while (bytesRead < minOf(size, maxJournalBytes)) {
                val next = input.read()
                if (next < 0) break
                bytesRead++
                if (next == '\n'.code) {
                    if (lineTooLong) {
                        corruptTail = true
                        break
                    }
                    val encodedLine = line.toString(StandardCharsets.US_ASCII).trimEnd('\r')
                    if (!isSnapshotCheckpoint(encodedLine)) {
                        val record = decode(encodedLine)
                        if (record == null) {
                            corruptTail = true
                            break
                        }
                        capacityAdjusted = apply(record) || capacityAdjusted
                        validRecords++
                    }
                    lastGoodOffset = bytesRead
                    line.reset()
                } else if (!lineTooLong) {
                    if (line.size() >= MAX_RECORD_BYTES) {
                        lineTooLong = true
                    } else {
                        line.write(next)
                    }
                }
            }
            if (!corruptTail && bytesRead == size && line.size() > 0) corruptTail = true
        }

        if (corruptTail) {
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                channel.truncate(lastGoodOffset)
                channel.force(true)
            }
            journalBytes = lastGoodOffset
        } else {
            journalBytes = minOf(bytesRead, size)
        }
        mutationRecords = validRecords.coerceAtMost(compactionThreshold)
        return LoadResult(
            capacityAdjusted = capacityAdjusted,
            needsCompaction = size > maxJournalBytes,
        )
    }

    fun persist(records: List<Record>) {
        if (records.isEmpty()) return
        val encoded = ByteArrayOutputStream()
        records.forEach { record ->
            val bytes = encode(record)
            require(bytes.size <= MAX_RECORD_BYTES) { "state journal record is too large" }
            encoded.write(bytes)
        }
        if (
            mutationRecords + records.size >= compactionThreshold ||
            journalBytes + encoded.size() > maxJournalBytes
        ) {
            compact()
            return
        }
        append(encoded.toByteArray(), records.size)
    }

    fun compact() {
        beforePersistence()
        val file = path.toAbsolutePath().normalize()
        val directory = requireNotNull(file.parent) { "State journal file must have a parent directory" }
        val fileName = requireNotNull(file.fileName) { "State journal file must have a file name" }
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, fileName.toString(), ".compact")
        var size = 0L
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                for (record in snapshot()) {
                    val bytes = encode(record)
                    require(bytes.size <= MAX_RECORD_BYTES) { "state journal record is too large" }
                    if (size + bytes.size > maxJournalBytes) {
                        throw IOException("State journal cannot fit its bounded live state")
                    }
                    writeFully(channel, ByteBuffer.wrap(bytes))
                    size += bytes.size
                }
                val checkpoint = snapshotCheckpoint()
                if (size + checkpoint.size > maxJournalBytes) {
                    throw IOException("State journal cannot fit its bounded live state")
                }
                writeFully(channel, ByteBuffer.wrap(checkpoint))
                size += checkpoint.size
                channel.force(true)
            }
            installSnapshot(temporary, file, directory)
            journalBytes = size
            mutationRecords = 0
            compactionCount++
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun installSnapshot(
        temporary: Path,
        file: Path,
        directory: Path,
    ) {
        try {
            if (forceNonAtomicMove) {
                throw AtomicMoveNotSupportedException(temporary.toString(), file.toString(), "test fallback")
            }
            Files.move(
                temporary,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            forceDirectory(directory)
        } catch (_: AtomicMoveNotSupportedException) {
            installSnapshotWithBackup(temporary, file, directory)
        }
    }

    /**
     * Same-directory backup protocol for providers without atomic replacement. At every crash
     * point either the previous journal remains in [backupPath] or a checksummed snapshot with a
     * commit checkpoint is present at [file]. Startup resolves that pair before parsing state.
     */
    private fun installSnapshotWithBackup(
        temporary: Path,
        file: Path,
        directory: Path,
    ) {
        recoverInterruptedReplacement(file)
        val backup = backupPath(file)
        val hadOriginal = Files.exists(file)
        if (hadOriginal) {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING)
            forceDirectory(directory)
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            forceDirectory(directory)
            if (!isCommittedSnapshot(file)) {
                throw IOException("Replacement state journal did not contain a valid snapshot checkpoint")
            }
        } catch (failure: Exception) {
            try {
                Files.deleteIfExists(file)
                if (hadOriginal && Files.exists(backup)) {
                    Files.move(backup, file, StandardCopyOption.REPLACE_EXISTING)
                }
                forceDirectory(directory)
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }

        if (hadOriginal) {
            try {
                Files.deleteIfExists(backup)
                forceDirectory(directory)
            } catch (_: IOException) {
                // The committed target is authoritative. Startup validates it before discarding a
                // leftover backup, so cleanup failure cannot make subsequent appends unsafe.
            }
        }
    }

    private fun recoverInterruptedReplacement(file: Path) {
        val backup = backupPath(file)
        if (!Files.exists(backup)) return
        if (!Files.isRegularFile(backup)) throw IOException("State journal backup is not a regular file")
        val directory = requireNotNull(file.parent) { "State journal file must have a parent directory" }
        if (Files.isRegularFile(file) && isCommittedSnapshot(file)) {
            try {
                Files.deleteIfExists(backup)
                forceDirectory(directory)
            } catch (_: IOException) {
                // A later load repeats safe cleanup; the valid committed target wins.
            }
            return
        }

        Files.deleteIfExists(file)
        Files.move(backup, file, StandardCopyOption.REPLACE_EXISTING)
        forceDirectory(directory)
    }

    private fun isCommittedSnapshot(file: Path): Boolean {
        if (!Files.isRegularFile(file)) return false
        val size = Files.size(file)
        if (size <= 0 || size > maxJournalBytes) return false
        var bytesRead = 0L
        var sawCheckpoint = false
        Files.newInputStream(file).buffered().use { input ->
            val line = ByteArrayOutputStream(MAX_RECORD_BYTES)
            while (bytesRead < size) {
                val next = input.read()
                if (next < 0) return false
                bytesRead++
                if (next == '\n'.code) {
                    val encodedLine = line.toString(StandardCharsets.US_ASCII).trimEnd('\r')
                    if (isSnapshotCheckpoint(encodedLine)) {
                        sawCheckpoint = true
                    } else if (decode(encodedLine) == null) {
                        return false
                    }
                    line.reset()
                } else {
                    if (line.size() >= MAX_RECORD_BYTES) return false
                    line.write(next)
                }
            }
            if (line.size() != 0) return false
        }
        return sawCheckpoint
    }

    private fun backupPath(file: Path): Path = file.resolveSibling("${file.fileName}.backup")

    private fun snapshotCheckpoint(): ByteArray = encodeJournalRecord(magic, listOf(SNAPSHOT_CHECKPOINT_RECORD))

    private fun isSnapshotCheckpoint(line: String): Boolean =
        verifiedJournalFields(line, magic, 1)?.singleOrNull() == SNAPSHOT_CHECKPOINT_RECORD

    private fun append(
        encoded: ByteArray,
        recordCount: Int,
    ) {
        beforePersistence()
        val file = path.toAbsolutePath().normalize()
        val directory = requireNotNull(file.parent) { "State journal file must have a parent directory" }
        Files.createDirectories(directory)
        val existed = Files.exists(file)
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val originalSize = channel.size()
            try {
                channel.position(originalSize)
                writeFully(channel, ByteBuffer.wrap(encoded))
                channel.force(true)
            } catch (failure: Exception) {
                try {
                    channel.truncate(originalSize)
                    channel.force(true)
                } catch (rollbackFailure: Exception) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
        }
        if (!existed) forceDirectory(directory)
        journalBytes += encoded.size
        mutationRecords += recordCount
    }

    private fun startsWithMagic(file: Path): Boolean {
        if (Files.size(file) == 0L) return true
        val expected = magic.toByteArray(StandardCharsets.US_ASCII)
        val prefix = Files.newInputStream(file).use { it.readNBytes(expected.size) }
        return prefix.contentEquals(expected)
    }
}

private fun repositoryScopeKey(
    serverId: String,
    repositoryPath: String,
): String = sha256("$serverId\u0000$repositoryPath")

private fun scopeHash(scope: CnbRepositoryEventStateScope): String =
    sha256(
        "${scope.serverId}\u0000${scope.repositoryPath}\u0000" +
            "${scope.authorizationScope}\u0000${scope.consumerScope}",
    )

private fun loadLegacyProperties(
    path: Path,
    maximumBytes: Long,
): Properties {
    if (Files.size(path) > maximumBytes) throw IOException("Legacy repository event state exceeds its migration limit")
    return Properties().also { properties ->
        try {
            Files.newInputStream(path).use(properties::load)
        } catch (failure: IllegalArgumentException) {
            throw IOException("Legacy repository event state is malformed", failure)
        }
    }
}

private fun encodeJournalRecord(
    magic: String,
    fields: List<String>,
): ByteArray {
    val prefix = (listOf(magic) + fields).joinToString("\t")
    return "$prefix\t${sha256(prefix)}\n".toByteArray(StandardCharsets.US_ASCII)
}

private fun verifiedJournalFields(
    line: String,
    magic: String,
    fieldCount: Int,
): List<String>? {
    val parts = line.split('\t')
    if (parts.size != fieldCount + 2 || parts.first() != magic) return null
    val prefix = parts.dropLast(1).joinToString("\t")
    val expected = sha256(prefix).toByteArray(StandardCharsets.US_ASCII)
    val supplied = parts.last().toByteArray(StandardCharsets.US_ASCII)
    if (!MessageDigest.isEqual(expected, supplied)) return null
    return parts.subList(1, parts.lastIndex)
}

private fun instantOfEpochSecond(value: Long): Instant? =
    try {
        Instant.ofEpochSecond(value)
    } catch (_: DateTimeException) {
        null
    }

private fun instantOfEpochSecondAndNano(
    epochSecond: Long,
    nano: Int,
): Instant? =
    try {
        Instant.ofEpochSecond(epochSecond, nano.toLong())
    } catch (_: DateTimeException) {
        null
    }

private fun instantOfEpochMilli(value: Long): Instant? =
    try {
        Instant.ofEpochMilli(value)
    } catch (_: DateTimeException) {
        null
    }

internal fun stableLifecycleEventId(
    value: String,
    fallback: String,
): String = value.takeIf(::isValidLifecycleEventId) ?: fallback.also { require(isValidLifecycleEventId(it)) }

internal fun compareLifecycleEventIds(
    left: String,
    right: String,
): Int {
    val leftDecimal = normalizedDecimalEventId(left)
    val rightDecimal = normalizedDecimalEventId(right)
    if (leftDecimal != null && rightDecimal != null) {
        val length = leftDecimal.length.compareTo(rightDecimal.length)
        if (length != 0) return length
        val digits = leftDecimal.compareTo(rightDecimal)
        if (digits != 0) return digits
    }
    return left.compareTo(right)
}

private fun normalizedDecimalEventId(value: String): String? {
    if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
    return value.trimStart('0').ifEmpty { "0" }
}

private fun isValidLifecycleEventId(value: String): Boolean =
    value.length in 1..MAX_LIFECYCLE_EVENT_ID_LENGTH && value.all { it.code in 0x20..0x7e }

private fun encodeLifecycleEventId(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.US_ASCII))

private fun decodeLifecycleEventId(value: String): String? =
    try {
        Base64
            .getUrlDecoder()
            .decode(value)
            .toString(StandardCharsets.US_ASCII)
            .takeIf(::isValidLifecycleEventId)
    } catch (_: IllegalArgumentException) {
        null
    }

private fun sha256(value: String): String =
    HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

private fun writeFully(
    channel: FileChannel,
    buffer: ByteBuffer,
) {
    while (buffer.hasRemaining()) channel.write(buffer)
}

private fun forceDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        // Directory fsync is unavailable on some supported filesystems; the state file is forced.
    } catch (_: UnsupportedOperationException) {
        // Directory fsync is unavailable on some supported filesystems; the state file is forced.
    }
}

private val STATE_KEY_PATTERN = Regex("[0-9a-f]{64}")
private const val DEFAULT_JOURNAL_BYTES = 64L * 1024L * 1024L
private const val DEFAULT_COMPACTION_THRESHOLD = 4_096
private const val MIN_JOURNAL_BYTES = 1_024L
private const val MAX_RECORD_BYTES = 512
private const val MAX_LIFECYCLE_EVENT_ID_LENGTH = 200
private const val SNAPSHOT_CHECKPOINT_RECORD = "C"
