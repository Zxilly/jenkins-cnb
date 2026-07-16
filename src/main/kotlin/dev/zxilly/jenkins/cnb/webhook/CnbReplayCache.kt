package dev.zxilly.jenkins.cnb.webhook

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.zip.CRC32

internal class CnbReplayCapacityException(
    message: String = "Webhook replay cache is full",
) : IllegalStateException(message)

internal class CnbReplayOwnershipException : IllegalStateException("Webhook replay claim is no longer owned")

internal sealed interface CnbReplayClaimResult {
    class Claimed internal constructor(
        internal val token: CnbReplayClaimToken,
    ) : CnbReplayClaimResult

    data object InFlight : CnbReplayClaimResult

    data object Completed : CnbReplayClaimResult
}

internal class CnbReplayClaimToken internal constructor(
    internal val scopeHash: String,
    internal val keyHash: String,
    internal val leaseId: String,
)

/**
 * Durable, bounded replay state for authenticated webhook deliveries.
 *
 * Repository scopes have independent quotas. The journal contains only SHA-256 hashes of scopes
 * and delivery keys, and every transition is forced to stable storage before it is made visible in
 * memory. An in-flight lease deliberately expires so a controller crash cannot suppress a delivery
 * forever.
 */
internal class CnbReplayCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val path: Path? = null,
    private val perScopeCapacity: Int = minOf(DEFAULT_SCOPE_CAPACITY, capacity),
    private val maxScopes: Int = DEFAULT_MAX_SCOPES,
    private val maxJournalBytes: Long = DEFAULT_MAX_JOURNAL_BYTES,
    private val compactionThreshold: Int = DEFAULT_COMPACTION_THRESHOLD,
    private val clock: Clock = Clock.systemUTC(),
) {
    private enum class State {
        IN_FLIGHT,
        COMPLETED,
    }

    private data class Entry(
        val state: State,
        val expiresAt: Instant,
        val leaseId: String?,
        val locallyOwned: Boolean,
    )

    private enum class Operation(
        val code: String,
    ) {
        IN_FLIGHT("I"),
        COMPLETED("C"),
        RELEASE("R"),
        LEGACY_COMPLETED("L"),
    }

    private data class JournalRecord(
        val operation: Operation,
        val scopeHash: String,
        val keyHash: String,
        val expiresAtEpochMillis: Long,
        val leaseId: String,
    )

    private val entries = LinkedHashMap<String, LinkedHashMap<String, Entry>>()
    private val legacyCompleted = LinkedHashMap<String, Instant>()
    private val secureRandom = SecureRandom()
    private var journalBytes = 0L
    private var mutationRecordsSinceCompaction = 0

    init {
        require(capacity > 0) { "Replay cache capacity must be positive" }
        require(perScopeCapacity in 1..capacity) {
            "Replay cache repository quota must be positive and no larger than the global capacity"
        }
        require(maxScopes > 0) { "Replay cache scope limit must be positive" }
        require(maxJournalBytes >= MIN_JOURNAL_BYTES) { "Replay journal size limit is too small" }
        require(compactionThreshold > 0) { "Replay journal compaction threshold must be positive" }
        load(clock.instant())
    }

    @Synchronized
    fun claim(
        scope: String,
        key: String,
        now: Instant,
        leaseTtl: Duration = DEFAULT_IN_FLIGHT_LEASE,
    ): CnbReplayClaimResult {
        require(scope.isNotBlank()) { "Replay cache scope must not be blank" }
        require(key.isNotBlank()) { "Replay cache key must not be blank" }
        requirePositive(leaseTtl, "Replay cache lease TTL")
        purgeExpired(now)

        // Version 1 stored SHA-256(server:repository:delivery) without a separate scope. Preserve
        // completed replay protection while transparently migrating on the next compaction.
        val legacyKeyHash = hash("$scope:$key")
        if (legacyCompleted[legacyKeyHash]?.isAfter(now) == true) return CnbReplayClaimResult.Completed

        val scopeHash = hash(scope)
        val keyHash = hash(key)
        val scopedEntries = entries[scopeHash]
        scopedEntries?.get(keyHash)?.let { existing ->
            return when (existing.state) {
                State.IN_FLIGHT -> CnbReplayClaimResult.InFlight
                State.COMPLETED -> CnbReplayClaimResult.Completed
            }
        }

        if (scopedEntries == null && entries.size >= maxScopes) {
            throw CnbReplayCapacityException("Webhook replay cache scope limit is exhausted")
        }
        if (entryCount() >= capacity) {
            throw CnbReplayCapacityException("Webhook replay cache global capacity is exhausted")
        }
        if (scopedEntries != null && scopedEntries.size >= perScopeCapacity) {
            throw CnbReplayCapacityException("Webhook replay cache repository quota is exhausted")
        }

        val leaseId = randomLeaseId()
        val expiresAt = now.plus(leaseTtl)
        append(
            JournalRecord(
                Operation.IN_FLIGHT,
                scopeHash,
                keyHash,
                expiresAt.toEpochMilli(),
                leaseId,
            ),
        )
        entries.getOrPut(scopeHash) { LinkedHashMap() }[keyHash] =
            Entry(State.IN_FLIGHT, expiresAt, leaseId, locallyOwned = true)
        return CnbReplayClaimResult.Claimed(CnbReplayClaimToken(scopeHash, keyHash, leaseId))
    }

    @Synchronized
    fun complete(
        token: CnbReplayClaimToken,
        now: Instant,
        ttl: Duration,
    ) {
        requirePositive(ttl, "Replay cache completed TTL")
        val existing =
            entries[token.scopeHash]?.get(token.keyHash)
                ?: throw CnbReplayOwnershipException()
        if (existing.state != State.IN_FLIGHT || existing.leaseId != token.leaseId || !existing.locallyOwned) {
            throw CnbReplayOwnershipException()
        }
        val expiresAt = now.plus(ttl)

        append(
            JournalRecord(
                Operation.COMPLETED,
                token.scopeHash,
                token.keyHash,
                expiresAt.toEpochMilli(),
                NO_LEASE,
            ),
        )
        entries.getOrPut(token.scopeHash) { LinkedHashMap() }[token.keyHash] =
            Entry(State.COMPLETED, expiresAt, null, locallyOwned = false)
    }

    @Synchronized
    fun release(token: CnbReplayClaimToken) {
        val scopedEntries = entries[token.scopeHash] ?: return
        val existing = scopedEntries[token.keyHash] ?: return
        if (existing.state != State.IN_FLIGHT || existing.leaseId != token.leaseId || !existing.locallyOwned) return

        append(
            JournalRecord(
                Operation.RELEASE,
                token.scopeHash,
                token.keyHash,
                0L,
                token.leaseId,
            ),
        )
        scopedEntries.remove(token.keyHash)
        if (scopedEntries.isEmpty()) entries.remove(token.scopeHash)
    }

    /**
     * Drops only the process-local ownership fence. The durable lease remains in the journal and
     * suppresses immediate retries until it expires, after which another request can recover it.
     */
    @Synchronized
    fun abandon(token: CnbReplayClaimToken) {
        val scopedEntries = entries[token.scopeHash] ?: return
        val existing = scopedEntries[token.keyHash] ?: return
        if (existing.state == State.IN_FLIGHT && existing.leaseId == token.leaseId && existing.locallyOwned) {
            scopedEntries[token.keyHash] = existing.copy(locallyOwned = false)
        }
    }

    @Synchronized
    fun size(): Int = entryCount()

    private fun entryCount(): Int = entries.values.sumOf { it.size } + legacyCompleted.size

    private fun purgeExpired(now: Instant) {
        val scopeIterator = entries.entries.iterator()
        while (scopeIterator.hasNext()) {
            val scopedEntries = scopeIterator.next().value
            scopedEntries.entries.removeIf {
                !it.value.expiresAt.isAfter(now) &&
                    !(it.value.state == State.IN_FLIGHT && it.value.locallyOwned)
            }
            if (scopedEntries.isEmpty()) scopeIterator.remove()
        }
        legacyCompleted.entries.removeIf { !it.value.isAfter(now) }
    }

    private fun load(loadedAt: Instant) {
        val file = path?.toAbsolutePath()?.normalize() ?: return
        if (!isSafeRegularFile(file)) return
        var validJournalRecords = 0
        var sawLegacyRecord = false
        try {
            journalBytes =
                Files
                    .readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    .size()
                    .coerceAtMost(maxJournalBytes + 1)
            readBoundedLines(file) { line ->
                val record = parseRecord(line)
                if (record != null) {
                    validJournalRecords++
                    applyLoaded(record, loadedAt)
                } else {
                    val legacy = LEGACY_PROPERTY_PATTERN.matchEntire(line.trim())
                    val expiry =
                        legacy
                            ?.groupValues
                            ?.get(2)
                            ?.toLongOrNull()
                            ?.let(::instantOrNull)
                    if (legacy != null) {
                        sawLegacyRecord = true
                        val keyHash = legacy.groupValues[1]
                        if (expiry?.isAfter(loadedAt) == true) {
                            if (keyHash in legacyCompleted || entryCount() < capacity) {
                                legacyCompleted[keyHash] = expiry
                            }
                        } else {
                            legacyCompleted.remove(keyHash)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            entries.clear()
            legacyCompleted.clear()
            journalBytes = 0L
            mutationRecordsSinceCompaction = 0
            return
        }
        mutationRecordsSinceCompaction =
            if (sawLegacyRecord) {
                compactionThreshold
            } else {
                validJournalRecords.coerceAtMost(compactionThreshold)
            }
    }

    private fun readBoundedLines(
        file: Path,
        consumer: (String) -> Unit,
    ) {
        Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).buffered().use { input ->
            val line = ByteArrayOutputStream(MAX_RECORD_BYTES)
            var bytesRead = 0L
            var lineTooLong = false
            while (bytesRead < maxJournalBytes) {
                val next = input.read()
                if (next < 0) {
                    if (!lineTooLong && line.size() > 0) consumer(line.toString(StandardCharsets.US_ASCII))
                    break
                }
                bytesRead++
                if (next == '\n'.code) {
                    if (!lineTooLong) consumer(line.toString(StandardCharsets.US_ASCII).trimEnd('\r'))
                    line.reset()
                    lineTooLong = false
                } else if (!lineTooLong) {
                    if (line.size() >= MAX_RECORD_BYTES) {
                        line.reset()
                        lineTooLong = true
                    } else {
                        line.write(next)
                    }
                }
            }
        }
    }

    private fun applyLoaded(
        record: JournalRecord,
        loadedAt: Instant,
    ) {
        when (record.operation) {
            Operation.LEGACY_COMPLETED -> {
                if (record.scopeHash != LEGACY_SCOPE_HASH) return
                val expiresAt = instantOrNull(record.expiresAtEpochMillis) ?: return
                if (!expiresAt.isAfter(loadedAt)) {
                    legacyCompleted.remove(record.keyHash)
                } else if (record.keyHash in legacyCompleted || entryCount() < capacity) {
                    legacyCompleted[record.keyHash] = expiresAt
                }
            }

            Operation.RELEASE -> {
                val scopedEntries = entries[record.scopeHash] ?: return
                val existing = scopedEntries[record.keyHash] ?: return
                if (existing.state == State.IN_FLIGHT && existing.leaseId == record.leaseId) {
                    scopedEntries.remove(record.keyHash)
                    if (scopedEntries.isEmpty()) entries.remove(record.scopeHash)
                }
            }

            Operation.IN_FLIGHT,
            Operation.COMPLETED,
            -> {
                val expiresAt = instantOrNull(record.expiresAtEpochMillis) ?: return
                if (!expiresAt.isAfter(loadedAt)) {
                    entries[record.scopeHash]?.let { scopedEntries ->
                        scopedEntries.remove(record.keyHash)
                        if (scopedEntries.isEmpty()) entries.remove(record.scopeHash)
                    }
                    return
                }
                val scopedEntries = entries[record.scopeHash]
                if (scopedEntries == null && entries.size >= maxScopes) return
                if (scopedEntries != null && record.keyHash !in scopedEntries && scopedEntries.size >= perScopeCapacity) {
                    return
                }
                val isNewEntry = scopedEntries?.containsKey(record.keyHash) != true
                if (isNewEntry && entryCount() >= capacity) return
                entries.getOrPut(record.scopeHash) { LinkedHashMap() }[record.keyHash] =
                    if (record.operation == Operation.IN_FLIGHT) {
                        Entry(State.IN_FLIGHT, expiresAt, record.leaseId, locallyOwned = false)
                    } else {
                        Entry(State.COMPLETED, expiresAt, null, locallyOwned = false)
                    }
            }
        }
    }

    private fun append(record: JournalRecord) {
        val file = path?.toAbsolutePath()?.normalize() ?: return
        val encoded = encode(record)
        val directory = requireNotNull(file.parent) { "Replay journal file must have a parent directory" }
        val fileName = requireNotNull(file.fileName) { "Replay journal file must have a file name" }
        prepareJournalDirectory(directory)
        requireSafeJournalTarget(file)

        if (
            mutationRecordsSinceCompaction >= compactionThreshold ||
            journalBytes + encoded.size > maxJournalBytes
        ) {
            compact(file, directory, fileName)
        }
        if (journalBytes + encoded.size > maxJournalBytes) {
            throw CnbReplayCapacityException("Webhook replay journal cannot fit the live replay state")
        }

        val existed = Files.exists(file, LinkOption.NOFOLLOW_LINKS)
        FileChannel
            .open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            .use { channel ->
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
        mutationRecordsSinceCompaction++
    }

    private fun compact(
        file: Path,
        directory: Path,
        fileName: Path,
    ) {
        val snapshot = ByteArrayOutputStream()
        entries.forEach { (scopeHash, scopedEntries) ->
            scopedEntries.forEach { (keyHash, entry) ->
                val record =
                    JournalRecord(
                        if (entry.state == State.IN_FLIGHT) Operation.IN_FLIGHT else Operation.COMPLETED,
                        scopeHash,
                        keyHash,
                        entry.expiresAt.toEpochMilli(),
                        entry.leaseId ?: NO_LEASE,
                    )
                appendSnapshotRecord(snapshot, record)
            }
        }
        legacyCompleted.forEach { (keyHash, expiry) ->
            appendSnapshotRecord(
                snapshot,
                JournalRecord(
                    Operation.LEGACY_COMPLETED,
                    LEGACY_SCOPE_HASH,
                    keyHash,
                    expiry.toEpochMilli(),
                    NO_LEASE,
                ),
            )
        }

        prepareJournalDirectory(directory)
        requireSafeJournalTarget(file)
        val temporary = Files.createTempFile(directory, fileName.toString(), ".compact")
        try {
            FileChannel
                .open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel ->
                    writeFully(channel, ByteBuffer.wrap(snapshot.toByteArray()))
                    channel.force(true)
                }
            Files.move(
                temporary,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            forceDirectory(directory)
            journalBytes = snapshot.size().toLong()
            mutationRecordsSinceCompaction = 0
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun appendSnapshotRecord(
        snapshot: ByteArrayOutputStream,
        record: JournalRecord,
    ) {
        val encoded = encode(record)
        if (snapshot.size().toLong() + encoded.size > maxJournalBytes) {
            throw CnbReplayCapacityException("Webhook replay journal cannot fit the live replay state")
        }
        snapshot.write(encoded)
    }

    private fun encode(record: JournalRecord): ByteArray {
        val prefix =
            listOf(
                JOURNAL_VERSION,
                record.operation.code,
                record.scopeHash,
                record.keyHash,
                record.expiresAtEpochMillis.toString(),
                record.leaseId,
            ).joinToString("\t")
        return "$prefix\t${checksum(prefix)}\n".toByteArray(StandardCharsets.US_ASCII)
    }

    private fun parseRecord(line: String): JournalRecord? {
        val fields = line.split('\t')
        if (fields.size != JOURNAL_FIELD_COUNT || fields[0] != JOURNAL_VERSION) return null
        val prefix = fields.take(JOURNAL_FIELD_COUNT - 1).joinToString("\t")
        if (!MessageDigest.isEqual(
                checksum(prefix).toByteArray(StandardCharsets.US_ASCII),
                fields.last().toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            return null
        }
        val operation = Operation.entries.firstOrNull { it.code == fields[1] } ?: return null
        val scopeHash = fields[2].takeIf(HASH_PATTERN::matches) ?: return null
        val keyHash = fields[3].takeIf(HASH_PATTERN::matches) ?: return null
        val expiry = fields[4].toLongOrNull() ?: return null
        val leaseId = fields[5]
        when (operation) {
            Operation.IN_FLIGHT,
            Operation.RELEASE,
            -> if (!LEASE_PATTERN.matches(leaseId)) return null

            Operation.COMPLETED,
            Operation.LEGACY_COMPLETED,
            -> if (leaseId != NO_LEASE) return null
        }
        if (operation == Operation.LEGACY_COMPLETED && scopeHash != LEGACY_SCOPE_HASH) return null
        return JournalRecord(operation, scopeHash, keyHash, expiry, leaseId)
    }

    private fun randomLeaseId(): String {
        val bytes = ByteArray(LEASE_ID_BYTES)
        secureRandom.nextBytes(bytes)
        try {
            return bytes.toHex()
        } finally {
            bytes.fill(0)
        }
    }

    private fun requirePositive(
        duration: Duration,
        label: String,
    ) {
        require(!duration.isNegative && !duration.isZero) { "$label must be positive" }
    }

    private fun instantOrNull(epochMillis: Long): Instant? =
        try {
            Instant.ofEpochMilli(epochMillis)
        } catch (_: Exception) {
            null
        }

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
            // Directory handles are not readable on every supported filesystem.
        } catch (_: UnsupportedOperationException) {
            // Directory fsync is not supported by every Jenkins host filesystem. The journal file
            // itself has already been forced, and atomic replacement remains mandatory.
        }
    }

    private fun prepareJournalDirectory(directory: Path) {
        Files.createDirectories(directory)
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Webhook replay journal directory must be a real directory")
        }
    }

    private fun requireSafeJournalTarget(file: Path) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return
        if (!isSafeRegularFile(file)) {
            throw IOException("Webhook replay journal must be a regular file and must not be a symbolic link")
        }
    }

    private fun isSafeRegularFile(file: Path): Boolean = !Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)

    private fun hash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .toHex()

    private fun checksum(value: String): String =
        CRC32()
            .apply { update(value.toByteArray(StandardCharsets.US_ASCII)) }
            .value
            .toString(16)
            .padStart(CHECKSUM_HEX_LENGTH, '0')

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_CAPACITY = 10_000
        private const val DEFAULT_SCOPE_CAPACITY = 512
        private const val DEFAULT_MAX_SCOPES = 10_000
        private const val DEFAULT_MAX_JOURNAL_BYTES = 64L * 1024L * 1024L
        private const val DEFAULT_COMPACTION_THRESHOLD = 2_048
        private const val MIN_JOURNAL_BYTES = 1_024L
        private const val MAX_RECORD_BYTES = 512
        private const val JOURNAL_VERSION = "v2"
        private const val JOURNAL_FIELD_COUNT = 7
        private const val CHECKSUM_HEX_LENGTH = 8
        private const val LEASE_ID_BYTES = 16
        private const val NO_LEASE = "-"
        private val DEFAULT_IN_FLIGHT_LEASE = Duration.ofSeconds(60)
        private val HASH_PATTERN = Regex("[0-9a-f]{64}")
        private val LEASE_PATTERN = Regex("[0-9a-f]{32}")
        private val LEGACY_PROPERTY_PATTERN = Regex("([0-9a-f]{64})\\s*[=:]\\s*([0-9]+)")
        private val LEGACY_SCOPE_HASH =
            MessageDigest
                .getInstance("SHA-256")
                .digest("cnb-replay-legacy-v1".toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
