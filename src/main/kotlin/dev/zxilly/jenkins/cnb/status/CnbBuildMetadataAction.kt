package dev.zxilly.jenkins.cnb.status

import hudson.model.InvisibleAction
import hudson.model.Run
import jenkins.model.RunAction2
import java.io.Serializable
import java.security.SecureRandom

/**
 * Durable state for asynchronous CNB metadata reconciliation.
 *
 * The action is attached while queued and normally copied to the Run for build.xml persistence.
 * A cancelled queue item parks the same action in the plugin's controller-local state store.
 * No token or secret is stored here; a Jenkins credentials ID is only an opaque reference.
 */
class CnbBuildMetadataAction(
    identitySeed: String,
) : InvisibleAction(),
    RunAction2,
    Serializable {
    private var explicitConfiguration = CnbBuildMetadataConfiguration()
    private var resolvedTarget: CnbBuildMetadataTarget? = null
    private var desiredState: CnbBuildMetadataState = CnbBuildMetadataState.QUEUED
    private var desiredVersion: Long = 0
    private var reportedVersion: Long = -1
    private var stateChangedAt: String = nowIso8601()
    private var buildDisplayName: String = "Jenkins build"
    private var buildUrl: String = ""
    private var commentId: String? = null
    private var markerToken: String = randomToken(identitySeed)

    @Transient
    private var owner: Run<*, *>? = null

    @Transient
    private var itemPersistenceBlocked: Boolean = false

    @Synchronized
    internal fun configure(configuration: CnbBuildMetadataConfiguration?) {
        if (configuration == null) return
        val merged = configuration.overlay(explicitConfiguration)
        if (merged != explicitConfiguration) {
            explicitConfiguration = merged
            desiredVersion++
            stateChangedAt = nowIso8601()
        }
    }

    @Synchronized
    internal fun configuration(): CnbBuildMetadataConfiguration = explicitConfiguration.copy()

    @Synchronized
    internal fun target(): CnbBuildMetadataTarget? = resolvedTarget?.copy()

    @Synchronized
    internal fun contextKey(): String = resolvedTarget?.context ?: explicitConfiguration.context.orEmpty()

    @Synchronized
    internal fun state(): CnbBuildMetadataState = desiredState

    @Synchronized
    internal fun version(): Long = desiredVersion

    @Synchronized
    internal fun requireItemPersistence() {
        itemPersistenceBlocked = true
    }

    @Synchronized
    internal fun releaseItemPersistence() {
        itemPersistenceBlocked = false
    }

    @Synchronized
    internal fun itemPersistenceRequired(): Boolean = itemPersistenceBlocked

    /** Advances monotonically from queued to running to a terminal state. */
    @Synchronized
    internal fun advance(
        target: CnbBuildMetadataTarget?,
        state: CnbBuildMetadataState,
        displayName: String,
        url: String,
    ): Boolean {
        val acceptedState =
            when {
                desiredState.terminal -> desiredState
                desiredState == CnbBuildMetadataState.RUNNING && state == CnbBuildMetadataState.QUEUED -> desiredState
                else -> state
            }
        val acceptedTarget = target ?: resolvedTarget
        val changed =
            acceptedTarget != resolvedTarget ||
                acceptedState != desiredState ||
                displayName != buildDisplayName ||
                url != buildUrl
        if (!changed) return false

        resolvedTarget = acceptedTarget
        desiredState = acceptedState
        buildDisplayName = displayName
        buildUrl = url
        desiredVersion++
        stateChangedAt = nowIso8601()
        return true
    }

    @Synchronized
    internal fun snapshot(): CnbBuildMetadataSnapshot? {
        if (itemPersistenceBlocked) return null
        val target = resolvedTarget ?: return null
        if (reportedVersion >= desiredVersion) return null
        return CnbBuildMetadataSnapshot(
            version = desiredVersion,
            markerToken = markerToken,
            target = target,
            state = desiredState,
            stateChangedAt = stateChangedAt,
            buildDisplayName = buildDisplayName,
            buildUrl = buildUrl,
            knownCommentId = commentId,
        )
    }

    @Synchronized
    internal fun markReported(
        version: Long,
        updatedCommentId: String?,
    ): CnbBuildMetadataReportCheckpoint {
        val checkpoint = CnbBuildMetadataReportCheckpoint(version, reportedVersion)
        if (version > reportedVersion) {
            reportedVersion = version
        }
        if (!updatedCommentId.isNullOrBlank()) {
            commentId = updatedCommentId
        }
        return checkpoint
    }

    /**
     * Restores only the delivery acknowledgement when persisting it failed. The discovered comment
     * ID is deliberately retained so the next idempotent report updates the same remote comment.
     */
    @Synchronized
    internal fun restorePending(checkpoint: CnbBuildMetadataReportCheckpoint) {
        if (reportedVersion == checkpoint.version) {
            reportedVersion = checkpoint.previousReportedVersion
        }
    }

    @Synchronized
    internal fun isPending(): Boolean = resolvedTarget != null && reportedVersion < desiredVersion

    @Synchronized
    internal fun clearTarget() {
        if (resolvedTarget != null) {
            resolvedTarget = null
            desiredVersion++
            stateChangedAt = nowIso8601()
        }
    }

    @Synchronized
    internal fun dispatchKey(): String = markerToken

    override fun onAttached(run: Run<*, *>) {
        owner = run
    }

    override fun onLoad(run: Run<*, *>) {
        owner = run
        if (reconcileAfterLoad(run)) {
            CnbBuildMetadataDispatcher.schedule(run, this, INITIAL_RELOAD_DELAY_SECONDS)
        }
    }

    @Synchronized
    private fun reconcileAfterLoad(run: Run<*, *>): Boolean {
        val recoveredState =
            if (run.isBuilding) CnbBuildMetadataState.RUNNING else CnbBuildMetadataState.fromResult(run.result)
        advance(
            resolvedTarget,
            recoveredState,
            run.fullDisplayName,
            buildUrl.ifBlank { run.url },
        )
        return isPending()
    }

    @Synchronized
    private fun readResolve(): Any {
        // XStream can restore a field missing from legacy XML as a JVM null even though Kotlin's
        // source type is non-null. Assigning to a nullable local avoids dereferencing that value.
        val restoredMarker: String? = markerToken
        if (restoredMarker.isNullOrBlank()) {
            markerToken = randomToken("legacy:$buildDisplayName:$buildUrl")
        }
        return this
    }

    companion object {
        private const val serialVersionUID = 1L
        private const val INITIAL_RELOAD_DELAY_SECONDS = 5L

        private val RANDOM = SecureRandom()

        private fun randomToken(identitySeed: String): String {
            require(identitySeed.isNotEmpty()) { "CNB metadata identity must not be empty" }
            // The non-secret identity is validated for caller correctness. Unpredictability comes
            // entirely from SecureRandom and the resulting token is persisted with the Action.
            val random = ByteArray(32)
            RANDOM.nextBytes(random)
            val token = random.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            random.fill(0)
            return token
        }
    }
}

internal data class CnbBuildMetadataReportCheckpoint(
    val version: Long,
    val previousReportedVersion: Long,
)
