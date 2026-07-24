package dev.zxilly.jenkins.cnb.status

import hudson.XmlFile
import hudson.model.Item
import hudson.model.Items
import jenkins.model.Jenkins
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.LinkedHashMap
import java.util.WeakHashMap

/**
 * Controller-local durable parking for metadata that outlives a cancelled queue item.
 *
 * The complete state is replaced atomically by [XmlFile]. Callers mutate records only through
 * this module so a failed acknowledgement write can restore the previous durable membership.
 * Item relocations use compare-and-set journal entries so stale recovery cannot rewrite a new Job.
 */
internal class CnbCancelledBuildMetadataStore(
    path: Path,
    private val beforePersistence: () -> Unit = {},
    private val beforeRelocationPersistence: () -> Unit = {},
    private val beforeRelocationAcknowledgement: () -> Unit = {},
    private val requestRecovery: () -> Unit = { CNB_BUILD_METADATA_RECOVERY_REQUESTS.request() },
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val missingContextGraceMillis: Long = DEFAULT_MISSING_CONTEXT_GRACE_MILLIS,
) {
    private val path = path.toAbsolutePath().normalize()
    private val file = XmlFile(Jenkins.XSTREAM2, this.path.toFile())
    private val relocationPath = this.path.resolveSibling("${this.path.fileName}.relocations")
    private val relocationFile = XmlFile(Jenkins.XSTREAM2, relocationPath.toFile())
    private val records = LinkedHashMap<String, CnbCancelledBuildMetadataRecord>()
    private val pendingRelocations = ArrayList<CnbCancelledBuildMetadataRelocation>()
    private var persistenceDirty = false

    init {
        require(missingContextGraceMillis >= 0) { "missingContextGraceMillis must not be negative" }
        load()
        recoverRelocations()
    }

    /** Retains [action] before its queue item disappears and reports whether the barrier committed. */
    @Synchronized
    fun retain(
        item: Item,
        action: CnbBuildMetadataAction,
    ): CnbCancelledBuildMetadataRetention {
        val key = action.dispatchKey()
        val credentialContextIdentity =
            itemIdentity(item) ?: throw IOException("Unable to identify the cancelled CNB metadata item context")
        val record =
            records[key]
                ?: CnbCancelledBuildMetadataRecord(
                    itemFullName = item.fullName,
                    action = action,
                ).also { records[key] = it }
        record.itemFullName = item.fullName
        record.credentialContextKind = CnbCancelledBuildMetadataCredentialContext.ITEM
        record.credentialContextFullName = item.fullName
        record.credentialContextIdentity = credentialContextIdentity
        record.credentialContextMissingSinceMillis = null
        record.action.requireItemPersistence()
        val persisted =
            try {
                writeState()
                record.action.releaseItemPersistence()
                true
            } catch (_: Exception) {
                false
            }
        requestRecovery()
        return CnbCancelledBuildMetadataRetention(record, persisted, item)
    }

    /** Atomically persists the record's latest desired and acknowledged versions. */
    @Synchronized
    @Throws(IOException::class)
    fun persist(record: CnbCancelledBuildMetadataRecord) {
        val previous = LinkedHashMap(records)
        val previousDirty = persistenceDirty
        records[record.action.dispatchKey()]?.let { canonical ->
            if (canonical !== record) record.copyLocatorFrom(canonical)
        }
        records[record.action.dispatchKey()] = record
        try {
            writeState()
        } catch (failure: Exception) {
            restore(previous)
            persistenceDirty = previousDirty
            requestRecovery()
            throw asIOException("Unable to persist cancelled CNB build metadata", failure)
        }
    }

    /** Atomically removes a remotely acknowledged record, restoring membership on write failure. */
    @Synchronized
    @Throws(IOException::class)
    fun remove(record: CnbCancelledBuildMetadataRecord) {
        val key = record.action.dispatchKey()
        if (records[key] == null) return
        val previous = LinkedHashMap(records)
        val previousDirty = persistenceDirty
        records.remove(key)
        try {
            writeState()
        } catch (failure: Exception) {
            restore(previous)
            persistenceDirty = previousDirty
            requestRecovery()
            throw asIOException("Unable to remove acknowledged CNB build metadata", failure)
        }
    }

    /** Rewrites source and credential-context paths after an Item move or rename. */
    @Synchronized
    @Throws(IOException::class)
    fun relocate(
        oldFullName: String,
        newFullName: String,
        movedItem: Item? = null,
    ): Int {
        if (oldFullName == newFullName) return 0
        val relocation = prepareRelocation(oldFullName, newFullName, movedItem)
        if (relocation.mutations.isEmpty()) return 0
        try {
            appendRelocation(relocation)
        } catch (journalFailure: Exception) {
            val affected = applyRelocation(relocation)
            check(affected == relocation.mutations.size) {
                "Unjournaled CNB metadata relocation no longer matched its records"
            }
            try {
                writeState()
            } catch (stateFailure: Exception) {
                requestRecovery()
                val failure = asIOException("Unable to relocate cancelled CNB build metadata", stateFailure)
                failure.addSuppressed(journalFailure)
                throw failure
            }
            requestRecovery()
            return affected
        }
        val affected = applyRelocation(relocation)
        check(affected == relocation.mutations.size) {
            "Journalled CNB metadata relocation no longer matched its records"
        }
        try {
            writeState()
        } catch (failure: Exception) {
            requestRecovery()
            throw asIOException("Unable to relocate cancelled CNB build metadata", failure)
        }
        requestRecovery()
        return affected
    }

    private fun prepareRelocation(
        oldFullName: String,
        newFullName: String,
        movedItem: Item? = null,
    ): CnbCancelledBuildMetadataRelocation {
        val mutations = ArrayList<CnbCancelledBuildMetadataRelocationMutation>()
        for ((dispatchKey, record) in records) {
            if (!record.wouldRelocate(oldFullName)) continue
            val before = record.locator()
            var itemFullName = record.itemFullName
            var credentialContextFullName = record.credentialContextFullName
            var credentialContextIdentity = record.credentialContextIdentity
            var credentialContextMissingSinceMillis = record.credentialContextMissingSinceMillis
            relocatePath(record.itemFullName, oldFullName, newFullName)?.let { relocated ->
                itemFullName = relocated
            }
            if (record.credentialContextKind == CnbCancelledBuildMetadataCredentialContext.ITEM) {
                relocatePath(record.credentialContextFullName, oldFullName, newFullName)?.let { relocated ->
                    credentialContextFullName = relocated
                    credentialContextMissingSinceMillis = null
                    val relocatedItem =
                        movedItem?.takeIf { it.fullName == relocated }
                            ?: Jenkins.get().getItemByFullName(relocated)
                    relocatedItem?.let(::itemIdentity)?.let { identity ->
                        credentialContextIdentity = identity
                    }
                }
            }
            val after =
                CnbCancelledBuildMetadataLocator(
                    itemFullName = itemFullName,
                    credentialContextKind = record.credentialContextKind,
                    credentialContextFullName = credentialContextFullName,
                    credentialContextIdentity = credentialContextIdentity,
                    credentialContextMissingSinceMillis = credentialContextMissingSinceMillis,
                )
            check(before != after) { "CNB metadata relocation did not change its matched record" }
            mutations += CnbCancelledBuildMetadataRelocationMutation(dispatchKey, before, after)
        }
        return CnbCancelledBuildMetadataRelocation(mutations)
    }

    private fun applyRelocation(relocation: CnbCancelledBuildMetadataRelocation): Int {
        var affected = 0
        for (mutation in relocation.mutations) {
            val record = records[mutation.dispatchKey] ?: continue
            if (record.locator() != mutation.before) continue
            record.applyLocator(mutation.after)
            affected++
        }
        return affected
    }

    /** Tombstones records whose original credential owner is being deleted. */
    @Synchronized
    @Throws(IOException::class)
    fun tombstoneCredentialContext(itemFullName: String): Int {
        var affected = 0
        for (record in records.values) {
            if (record.credentialContextKind != CnbCancelledBuildMetadataCredentialContext.ITEM) continue
            val context = record.credentialContextFullName ?: continue
            if (!samePathOrDescendant(context, itemFullName)) continue
            tombstone(record)
            affected++
        }
        if (affected == 0) return 0
        try {
            writeState()
        } catch (failure: Exception) {
            requestRecovery()
            throw asIOException("Unable to tombstone deleted CNB credential context", failure)
        }
        requestRecovery()
        return affected
    }

    @Synchronized
    fun snapshotIterator(): Iterator<CnbCancelledBuildMetadataRecord> {
        if (persistenceDirty) {
            try {
                writeState()
            } catch (_: Exception) {
                requestRecovery()
            }
        }
        return ArrayList(records.values).iterator()
    }

    /** Resolves the canonical credential owner without ever falling back to another scope. */
    @Synchronized
    fun resolveCredentialContext(
        record: CnbCancelledBuildMetadataRecord,
        jenkins: Jenkins = Jenkins.get(),
    ): CnbCancelledBuildMetadataCredentialResolution {
        val canonical = records[record.action.dispatchKey()] ?: return CnbCancelledBuildMetadataCredentialResolution.deleted()
        if (canonical !== record) record.copyLocatorFrom(canonical)
        return when (canonical.credentialContextKind) {
            CnbCancelledBuildMetadataCredentialContext.ROOT -> {
                CnbCancelledBuildMetadataCredentialResolution.available(null)
            }

            CnbCancelledBuildMetadataCredentialContext.ITEM -> {
                val fullName = canonical.credentialContextFullName.orEmpty()
                val missingSince = canonical.credentialContextMissingSinceMillis
                val expectedIdentity = canonical.credentialContextIdentity
                val item = jenkins.getItemByFullName(fullName)
                val actualIdentity = item?.let(::itemIdentity)
                if (expectedIdentity != null) {
                    val matchingItem =
                        item?.takeIf { actualIdentity == expectedIdentity }
                            ?: findItemByIdentity(jenkins, expectedIdentity)
                    if (matchingItem != null) {
                        recoverCredentialContext(canonical, matchingItem, expectedIdentity)
                        return CnbCancelledBuildMetadataCredentialResolution.available(matchingItem)
                    }
                }
                if (item != null && actualIdentity != null && expectedIdentity != null) {
                    tombstone(canonical)
                    persistLocatorBestEffort()
                    return CnbCancelledBuildMetadataCredentialResolution.deleted()
                }
                if (item != null && actualIdentity != null && expectedIdentity == null && missingSince == null) {
                    canonical.credentialContextIdentity = actualIdentity
                    persistLocatorBestEffort()
                    return CnbCancelledBuildMetadataCredentialResolution.available(item)
                }
                if (missingSince != null && missingContextExpired(missingSince)) {
                    tombstone(canonical)
                    persistLocatorBestEffort()
                    return CnbCancelledBuildMetadataCredentialResolution.deleted()
                }
                markMissing(canonical)
                CnbCancelledBuildMetadataCredentialResolution.missing()
            }

            CnbCancelledBuildMetadataCredentialContext.DELETED,
            null,
            -> {
                CnbCancelledBuildMetadataCredentialResolution.deleted()
            }
        }
    }

    @Synchronized
    internal fun size(): Int = records.size

    private fun load() {
        if (!file.file.isFile) return
        val state =
            try {
                file.read() as? State ?: throw IOException("Cancelled CNB metadata state has an unexpected root type")
            } catch (failure: IOException) {
                throw failure
            } catch (failure: Exception) {
                throw IOException("Unable to load cancelled CNB build metadata", failure)
            }
        val restoredRecords: MutableList<CnbCancelledBuildMetadataRecord>? = state.records
        var migrated = false
        for (entry in restoredRecords ?: throw IOException("Cancelled CNB metadata records are missing")) {
            val record: CnbCancelledBuildMetadataRecord? = entry
            val restored = record ?: throw IOException("Cancelled CNB metadata state contains an empty record")
            val itemFullName: String? = restored.itemFullName
            if (itemFullName.isNullOrBlank()) throw IOException("Cancelled CNB metadata item name is blank")
            if (restored.credentialContextKind == null) {
                migrateCredentialContext(restored)
                migrated = true
            }
            validateCredentialContext(restored)
            val action: CnbBuildMetadataAction? = restored.action
            val key = action?.dispatchKey() ?: throw IOException("Cancelled CNB metadata action is missing")
            if (records.putIfAbsent(key, restored) != null) {
                throw IOException("Cancelled CNB metadata state contains a duplicate dispatch key")
            }
            action.releaseItemPersistence()
        }
        if (migrated) writeState()
    }

    private fun recoverRelocations() {
        if (!relocationFile.file.isFile) return
        val state =
            try {
                relocationFile.read() as? RelocationState
                    ?: throw IOException("Cancelled CNB metadata relocation state has an unexpected root type")
            } catch (failure: IOException) {
                throw failure
            } catch (failure: Exception) {
                throw IOException("Unable to load cancelled CNB metadata relocations", failure)
            }
        val relocations: MutableList<CnbCancelledBuildMetadataRelocation>? = state.relocations
        for (entry in relocations ?: throw IOException("Cancelled CNB metadata relocations are missing")) {
            val relocation: CnbCancelledBuildMetadataRelocation? = entry
            val restored = relocation ?: throw IOException("Cancelled CNB metadata relocation is empty")
            val mutations: MutableList<CnbCancelledBuildMetadataRelocationMutation>? = restored.mutations
            if (mutations.isNullOrEmpty()) {
                throw IOException("Cancelled CNB metadata relocation mutations are missing")
            }
            for (mutationEntry in mutations) {
                val mutation: CnbCancelledBuildMetadataRelocationMutation? = mutationEntry
                val restoredMutation = mutation ?: throw IOException("Cancelled CNB metadata relocation mutation is empty")
                if (restoredMutation.dispatchKey.isBlank()) {
                    throw IOException("Cancelled CNB metadata relocation dispatch key is blank")
                }
                validateLocator(restoredMutation.before)
                validateLocator(restoredMutation.after)
                if (restoredMutation.before == restoredMutation.after) {
                    throw IOException("Cancelled CNB metadata relocation does not change its locator")
                }
            }
            pendingRelocations += restored
            applyRelocation(restored)
        }
        if (pendingRelocations.isNotEmpty()) {
            writeState()
            requestRecovery()
        }
    }

    private fun appendRelocation(relocation: CnbCancelledBuildMetadataRelocation) {
        pendingRelocations += relocation
        try {
            beforeRelocationPersistence()
            Files.createDirectories(requireNotNull(relocationPath.parent) { "Relocation state file must have a parent directory" })
            relocationFile.write(RelocationState(ArrayList(pendingRelocations)))
        } catch (failure: Exception) {
            // A later journal attempt must include this mutation if the fallback state write also fails.
            throw asIOException("Unable to journal cancelled CNB metadata relocation", failure)
        }
    }

    private fun writeState() {
        persistenceDirty = true
        beforePersistence()
        Files.createDirectories(requireNotNull(path.parent) { "Cancellation state file must have a parent directory" })
        file.write(State(ArrayList(records.values)))
        persistenceDirty = false
        acknowledgeRelocationsBestEffort()
    }

    private fun acknowledgeRelocationsBestEffort() {
        if (pendingRelocations.isEmpty()) return
        try {
            beforeRelocationAcknowledgement()
            Files.deleteIfExists(relocationPath)
            pendingRelocations.clear()
        } catch (_: Exception) {
            persistenceDirty = true
            requestRecovery()
        }
    }

    private fun restore(previous: LinkedHashMap<String, CnbCancelledBuildMetadataRecord>) {
        records.clear()
        records.putAll(previous)
    }

    private fun migrateCredentialContext(record: CnbCancelledBuildMetadataRecord) {
        val item = Jenkins.get().getItemByFullName(record.itemFullName)
        record.credentialContextKind = CnbCancelledBuildMetadataCredentialContext.ITEM
        record.credentialContextFullName = record.itemFullName
        record.credentialContextIdentity = item?.let(::itemIdentity)
        record.credentialContextMissingSinceMillis = if (item == null) clockMillis() else null
    }

    private fun validateCredentialContext(record: CnbCancelledBuildMetadataRecord) {
        when (record.credentialContextKind) {
            CnbCancelledBuildMetadataCredentialContext.ROOT,
            CnbCancelledBuildMetadataCredentialContext.DELETED,
            -> {
                if (record.credentialContextFullName != null ||
                    record.credentialContextIdentity != null ||
                    record.credentialContextMissingSinceMillis != null
                ) {
                    throw IOException("Cancelled CNB metadata root or deleted credential context has item state")
                }
            }

            CnbCancelledBuildMetadataCredentialContext.ITEM -> {
                if (record.credentialContextFullName.isNullOrBlank()) {
                    throw IOException("Cancelled CNB metadata item credential context is blank")
                }
                if ((record.credentialContextMissingSinceMillis ?: 0L) < 0L) {
                    throw IOException("Cancelled CNB metadata missing-context time is invalid")
                }
            }

            null -> {
                throw IOException("Cancelled CNB metadata credential context is missing")
            }
        }
    }

    private fun validateLocator(locator: CnbCancelledBuildMetadataLocator) {
        if (locator.itemFullName.isBlank()) {
            throw IOException("Cancelled CNB metadata relocation item name is blank")
        }
        when (locator.credentialContextKind) {
            CnbCancelledBuildMetadataCredentialContext.ROOT,
            CnbCancelledBuildMetadataCredentialContext.DELETED,
            -> {
                if (locator.credentialContextFullName != null ||
                    locator.credentialContextIdentity != null ||
                    locator.credentialContextMissingSinceMillis != null
                ) {
                    throw IOException("Cancelled CNB metadata relocation root or deleted context has item state")
                }
            }

            CnbCancelledBuildMetadataCredentialContext.ITEM -> {
                if (locator.credentialContextFullName.isNullOrBlank()) {
                    throw IOException("Cancelled CNB metadata relocation item context is blank")
                }
                if ((locator.credentialContextMissingSinceMillis ?: 0L) < 0L) {
                    throw IOException("Cancelled CNB metadata relocation missing-context time is invalid")
                }
            }

            null -> {
                throw IOException("Cancelled CNB metadata relocation credential context is missing")
            }
        }
    }

    private class State(
        val records: MutableList<CnbCancelledBuildMetadataRecord> = ArrayList(),
    )

    private class RelocationState(
        val relocations: MutableList<CnbCancelledBuildMetadataRelocation> = ArrayList(),
    )

    private fun recoverCredentialContext(
        record: CnbCancelledBuildMetadataRecord,
        item: Item,
        identity: CnbCancelledBuildMetadataItemIdentity,
    ) {
        if (record.itemFullName == item.fullName &&
            record.credentialContextFullName == item.fullName &&
            record.credentialContextIdentity == identity &&
            record.credentialContextMissingSinceMillis == null
        ) {
            return
        }
        record.itemFullName = item.fullName
        record.credentialContextFullName = item.fullName
        record.credentialContextIdentity = identity
        record.credentialContextMissingSinceMillis = null
        persistLocatorBestEffort()
    }

    private fun findItemByIdentity(
        jenkins: Jenkins,
        identity: CnbCancelledBuildMetadataItemIdentity,
    ): Item? {
        var match: Item? = null
        for (candidate in Items.allItems(jenkins, Item::class.java)) {
            if (itemIdentity(candidate) != identity) continue
            if (match != null) return null
            match = candidate
        }
        return match
    }

    private fun markMissing(record: CnbCancelledBuildMetadataRecord) {
        if (record.credentialContextMissingSinceMillis == null) {
            record.credentialContextMissingSinceMillis = clockMillis()
            persistLocatorBestEffort()
        }
        requestRecovery()
    }

    private fun missingContextExpired(missingSinceMillis: Long): Boolean {
        val now = clockMillis()
        return now >= missingSinceMillis && now - missingSinceMillis >= missingContextGraceMillis
    }

    private fun tombstone(record: CnbCancelledBuildMetadataRecord) {
        record.credentialContextKind = CnbCancelledBuildMetadataCredentialContext.DELETED
        record.credentialContextFullName = null
        record.credentialContextIdentity = null
        record.credentialContextMissingSinceMillis = null
    }

    private fun persistLocatorBestEffort() {
        try {
            writeState()
        } catch (_: Exception) {
            requestRecovery()
        }
    }

    private fun CnbCancelledBuildMetadataRecord.locator(): CnbCancelledBuildMetadataLocator =
        CnbCancelledBuildMetadataLocator(
            itemFullName = itemFullName,
            credentialContextKind = credentialContextKind,
            credentialContextFullName = credentialContextFullName,
            credentialContextIdentity = credentialContextIdentity,
            credentialContextMissingSinceMillis = credentialContextMissingSinceMillis,
        )

    private fun CnbCancelledBuildMetadataRecord.applyLocator(locator: CnbCancelledBuildMetadataLocator) {
        itemFullName = locator.itemFullName
        credentialContextKind = locator.credentialContextKind
        credentialContextFullName = locator.credentialContextFullName
        credentialContextIdentity = locator.credentialContextIdentity
        credentialContextMissingSinceMillis = locator.credentialContextMissingSinceMillis
    }

    private fun CnbCancelledBuildMetadataRecord.wouldRelocate(oldFullName: String): Boolean =
        samePathOrDescendant(itemFullName, oldFullName) ||
            (
                credentialContextKind == CnbCancelledBuildMetadataCredentialContext.ITEM &&
                    credentialContextFullName?.let { samePathOrDescendant(it, oldFullName) } == true
            )

    private companion object {
        const val DEFAULT_MISSING_CONTEXT_GRACE_MILLIS = 5 * 60 * 1000L

        fun itemIdentity(item: Item): CnbCancelledBuildMetadataItemIdentity? =
            try {
                val attributes =
                    Files.readAttributes(
                        item.rootDir.toPath(),
                        BasicFileAttributes::class.java,
                    )
                CnbCancelledBuildMetadataItemIdentity(
                    fileKey = attributes.fileKey()?.toString(),
                    creationTimeMillis = attributes.creationTime().toMillis(),
                )
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }

        fun relocatePath(
            value: String?,
            oldFullName: String,
            newFullName: String,
        ): String? =
            when {
                value == null -> null
                value == oldFullName -> newFullName
                value.startsWith("$oldFullName/") -> newFullName + value.removePrefix(oldFullName)
                else -> null
            }

        fun samePathOrDescendant(
            value: String,
            ancestor: String,
        ): Boolean = value == ancestor || value.startsWith("$ancestor/")

        fun asIOException(
            message: String,
            failure: Exception,
        ): IOException = failure as? IOException ?: IOException(message, failure)
    }
}

internal data class CnbCancelledBuildMetadataRecord(
    var itemFullName: String,
    val action: CnbBuildMetadataAction,
    var credentialContextKind: CnbCancelledBuildMetadataCredentialContext? = null,
    var credentialContextFullName: String? = null,
    var credentialContextIdentity: CnbCancelledBuildMetadataItemIdentity? = null,
    var credentialContextMissingSinceMillis: Long? = null,
) {
    fun copyLocatorFrom(other: CnbCancelledBuildMetadataRecord) {
        itemFullName = other.itemFullName
        credentialContextKind = other.credentialContextKind
        credentialContextFullName = other.credentialContextFullName
        credentialContextIdentity = other.credentialContextIdentity
        credentialContextMissingSinceMillis = other.credentialContextMissingSinceMillis
    }
}

internal data class CnbCancelledBuildMetadataItemIdentity(
    val fileKey: String?,
    val creationTimeMillis: Long,
)

internal data class CnbCancelledBuildMetadataLocator(
    val itemFullName: String,
    val credentialContextKind: CnbCancelledBuildMetadataCredentialContext?,
    val credentialContextFullName: String?,
    val credentialContextIdentity: CnbCancelledBuildMetadataItemIdentity?,
    val credentialContextMissingSinceMillis: Long?,
)

internal data class CnbCancelledBuildMetadataRelocationMutation(
    val dispatchKey: String,
    val before: CnbCancelledBuildMetadataLocator,
    val after: CnbCancelledBuildMetadataLocator,
)

internal data class CnbCancelledBuildMetadataRelocation(
    val mutations: MutableList<CnbCancelledBuildMetadataRelocationMutation> = ArrayList(),
)

internal enum class CnbCancelledBuildMetadataCredentialContext {
    ROOT,
    ITEM,
    DELETED,
}

internal data class CnbCancelledBuildMetadataCredentialResolution(
    val state: State,
    val item: Item?,
) {
    enum class State {
        AVAILABLE,
        MISSING,
        DELETED,
    }

    companion object {
        fun available(item: Item?): CnbCancelledBuildMetadataCredentialResolution =
            CnbCancelledBuildMetadataCredentialResolution(State.AVAILABLE, item)

        fun missing(): CnbCancelledBuildMetadataCredentialResolution = CnbCancelledBuildMetadataCredentialResolution(State.MISSING, null)

        fun deleted(): CnbCancelledBuildMetadataCredentialResolution = CnbCancelledBuildMetadataCredentialResolution(State.DELETED, null)
    }
}

internal data class CnbCancelledBuildMetadataRetention(
    val record: CnbCancelledBuildMetadataRecord,
    val initiallyPersisted: Boolean,
    val credentialContext: Item?,
)

/** One store per live Jenkins controller; tests can instantiate a store at an isolated path. */
internal object CnbCancelledBuildMetadataStores {
    private val stores = WeakHashMap<Jenkins, CnbCancelledBuildMetadataStore>()

    @Synchronized
    fun current(jenkins: Jenkins = Jenkins.get()): CnbCancelledBuildMetadataStore =
        stores.getOrPut(jenkins) {
            CnbCancelledBuildMetadataStore(
                jenkins.rootDir
                    .toPath()
                    .resolve("cnb")
                    .resolve("cancelled-build-metadata.xml"),
            )
        }
}
