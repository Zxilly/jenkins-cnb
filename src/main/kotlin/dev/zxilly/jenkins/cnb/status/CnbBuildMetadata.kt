package dev.zxilly.jenkins.cnb.status

import hudson.model.Result
import java.io.Serializable
import java.time.Instant
import java.util.Objects

/** Lifecycle states represented as CNB metadata, not as native commit statuses. */
enum class CnbBuildMetadataState(
    val wireName: String,
    val terminal: Boolean,
) {
    QUEUED("queued", false),
    RUNNING("running", false),
    SUCCESS("success", true),
    UNSTABLE("unstable", true),
    FAILURE("failure", true),
    ABORTED("aborted", true),
    NOT_BUILT("not_built", true),
    ;

    companion object {
        fun fromResult(result: Result?): CnbBuildMetadataState =
            when (result) {
                Result.SUCCESS -> SUCCESS
                Result.UNSTABLE -> UNSTABLE
                Result.FAILURE -> FAILURE
                Result.ABORTED -> ABORTED
                Result.NOT_BUILT, null -> NOT_BUILT
                else -> FAILURE
            }
    }
}

/** Explicit values supplied by an administrator-configured publisher or trusted Pipeline step. */
data class CnbBuildMetadataConfiguration(
    val serverId: String? = null,
    val repository: String? = null,
    val commitRepository: String? = null,
    val sha: String? = null,
    val pullRequestNumber: String? = null,
    val context: String? = null,
    val credentialsId: String? = null,
) : Serializable {
    fun normalized(): CnbBuildMetadataConfiguration =
        copy(
            serverId = serverId.clean(),
            repository = repository.clean(),
            commitRepository = commitRepository.clean(),
            sha = sha.clean(),
            pullRequestNumber = pullRequestNumber.clean(),
            context = context.clean(),
            credentialsId = credentialsId.clean(),
        )

    /** Non-empty values in this instance take precedence over [base]. */
    fun overlay(base: CnbBuildMetadataConfiguration): CnbBuildMetadataConfiguration {
        val value = normalized()
        return CnbBuildMetadataConfiguration(
            serverId = value.serverId ?: base.serverId,
            repository = value.repository ?: base.repository,
            commitRepository = value.commitRepository ?: base.commitRepository,
            sha = value.sha ?: base.sha,
            pullRequestNumber = value.pullRequestNumber ?: base.pullRequestNumber,
            context = value.context ?: base.context,
            credentialsId = value.credentialsId ?: base.credentialsId,
        )
    }

    fun isEmpty(): Boolean =
        serverId == null &&
            repository == null &&
            commitRepository == null &&
            sha == null &&
            pullRequestNumber == null &&
            context == null &&
            credentialsId == null

    private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** A fully resolved CNB reporting destination persisted with the Jenkins queue item/run. */
data class CnbBuildMetadataTarget(
    val serverId: String,
    val repository: String,
    val sha: String,
    val pullRequestNumber: String?,
    val context: String,
    val credentialsId: String?,
    /** Repository that owns [sha]; differs from [repository] for cross-repository pull requests. */
    val commitRepository: String = repository,
) : Serializable {
    private fun readResolve(): Any {
        // A field added after an object was serialized is restored as JVM null even though its
        // Kotlin type is non-null. Objects.toString handles that legacy stream shape explicitly.
        val restoredCommitRepository = Objects.toString(commitRepository, "")
        return if (restoredCommitRepository.isBlank()) copy(commitRepository = repository) else this
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

internal data class CnbBuildMetadataSnapshot(
    val version: Long,
    val markerToken: String,
    val target: CnbBuildMetadataTarget,
    val state: CnbBuildMetadataState,
    val stateChangedAt: String,
    val buildDisplayName: String,
    val buildUrl: String,
    val knownCommentId: String?,
)

internal fun nowIso8601(): String = Instant.now().toString()
