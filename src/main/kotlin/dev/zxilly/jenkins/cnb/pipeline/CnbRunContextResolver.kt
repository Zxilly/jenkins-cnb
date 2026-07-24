package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.CnbClientFactory
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestSCMHead
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestSCMRevision
import dev.zxilly.jenkins.cnb.scm.CnbSCMSource
import dev.zxilly.jenkins.cnb.security.CnbRepositoryPath
import dev.zxilly.jenkins.cnb.trigger.CnbPushCause
import dev.zxilly.jenkins.cnb.trigger.CnbRepositoryEventCause
import hudson.AbortException
import hudson.EnvVars
import hudson.model.Run
import hudson.model.TaskListener
import jenkins.plugins.git.AbstractGitSCMSource
import jenkins.scm.api.SCMRevisionAction
import jenkins.scm.api.SCMSource
import java.io.Serializable
import java.net.URI
import java.util.Locale

/** Values a Pipeline step or Publisher may use to override discovered CNB context. */
data class CnbRunContextInput(
    val serverId: String? = null,
    val repository: String? = null,
    val pullRequestNumber: String? = null,
    val sha: String? = null,
    val credentialsId: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** A validated CNB destination. Credentials are only resolved inside the owning Jenkins item. */
data class CnbRunContext(
    val serverId: String,
    val repository: String,
    val pullRequestNumber: String?,
    val sha: String?,
    val credentialsId: String?,
) : Serializable {
    fun client(run: Run<*, *>): CnbClient = CnbClientFactory.create(serverId, credentialsId, run.parent)

    fun requirePullRequestNumber(): String =
        pullRequestNumber
            ?: throw AbortException(
                "CNB pull request number could not be resolved; set pullRequestNumber explicitly or run from a CNB pull request job",
            )

    fun requireSha(): String =
        sha
            ?: throw AbortException(
                "CNB commit SHA could not be resolved; set sha explicitly or run from a CNB-triggered/SCM job",
            )

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** Resolves values in the documented order: explicit, CNB Cause/environment, then CNB SCM metadata. */
object CnbRunContextResolver {
    fun resolve(
        run: Run<*, *>,
        listener: TaskListener,
        supplied: CnbRunContextInput,
        environment: EnvVars = run.getEnvironment(listener),
    ): CnbRunContext {
        val explicit = supplied.expand(environment)
        val event = eventMetadata(run, environment)
        val scm = scmMetadata(run)
        val defaultServerId =
            CnbGlobalConfiguration
                .get()
                .getServers()
                .firstOrNull()
                ?.id

        val serverId =
            first(explicit.serverId, event.serverId, scm.serverId, defaultServerId)
                ?: throw AbortException("CNB server could not be resolved; configure a CNB server or set serverId explicitly")
        try {
            CnbGlobalConfiguration.get().findServer(serverId)
        } catch (_: IllegalArgumentException) {
            throw AbortException("Unknown CNB server profile: $serverId")
        }

        val repository =
            first(explicit.repository, event.repository, scm.repository)
                ?.let(::normalizeRepository)
                ?.takeIf(CnbRepositoryPath::isValid)
                ?: throw AbortException(
                    "CNB repository could not be resolved; set repository explicitly or run from a CNB-triggered/SCM job",
                )
        val pullRequestNumber =
            first(explicit.pullRequestNumber, event.pullRequestNumber, scm.pullRequestNumber)
                ?.takeIf(PULL_REQUEST_PATTERN::matches)
                ?: first(explicit.pullRequestNumber, event.pullRequestNumber, scm.pullRequestNumber)
                    ?.let { throw AbortException("CNB pull request number must be a positive integer") }
        val sha =
            first(explicit.sha, event.sha, scm.sha)
                ?.lowercase(Locale.ROOT)
                ?.takeIf(SHA_PATTERN::matches)
                ?: first(explicit.sha, event.sha, scm.sha)
                    ?.let { throw AbortException("CNB commit SHA must contain 7-64 hexadecimal characters") }
        val credentialsId =
            first(
                explicit.credentialsId,
                event.credentialsId?.takeIf { event.serverId == serverId },
                scm.credentialsId?.takeIf { scm.serverId == serverId },
            )

        return CnbRunContext(serverId, repository, pullRequestNumber, sha, credentialsId)
    }

    private fun CnbRunContextInput.expand(environment: EnvVars): CnbRunContextInput =
        CnbRunContextInput(
            serverId = clean(serverId, environment),
            repository = clean(repository, environment),
            pullRequestNumber = clean(pullRequestNumber, environment),
            sha = clean(sha, environment),
            // A credentials identifier is configuration, never build data. Expanding it can turn
            // a bound secret into plaintext that is then persisted in Pipeline state or build.xml.
            credentialsId = credentialsId?.trim()?.takeIf(String::isNotEmpty),
        )

    private fun eventMetadata(
        run: Run<*, *>,
        environment: EnvVars,
    ): Metadata {
        val causeVariables =
            run.getCause(CnbPushCause::class.java)?.buildVariables()
                ?: run.getCause(CnbRepositoryEventCause::class.java)?.buildVariables()
                ?: emptyMap()

        fun value(vararg names: String): String? =
            names.firstNotNullOfOrNull { name -> clean(causeVariables[name], environment) }
                ?: names.firstNotNullOfOrNull { name -> clean(environment[name], environment) }
        val pullRequestNumber = value("CNB_PULL_REQUEST_IID", "CNB_PULL_REQUEST_NUMBER")
        val sha =
            if (pullRequestNumber == null) {
                value("CNB_COMMIT", "CNB_BRANCH_SHA")
            } else {
                value("CNB_PULL_REQUEST_SHA", "CNB_PULL_REQUEST_SOURCE_SHA", "CNB_COMMIT")
            }
        return Metadata(
            serverId = value("CNB_SERVER_ID"),
            repository = value("CNB_REPOSITORY", "CNB_REPO_SLUG"),
            pullRequestNumber = pullRequestNumber,
            sha = sha,
        )
    }

    private fun scmMetadata(run: Run<*, *>): Metadata {
        val source =
            runCatching { SCMSource.SourceByItem.findSource(run.parent) }
                .getOrNull() as? CnbSCMSource
        val revision = run.getAction(SCMRevisionAction::class.java)?.revision
        return Metadata(
            serverId = source?.serverId,
            repository = source?.repositoryPath,
            pullRequestNumber =
                (revision as? CnbPullRequestSCMRevision)
                    ?.let { it.head as? CnbPullRequestSCMHead }
                    ?.number,
            sha =
                when (revision) {
                    is CnbPullRequestSCMRevision -> revision.headHash
                    is AbstractGitSCMSource.SCMRevisionImpl -> revision.hash
                    else -> null
                },
            credentialsId = source?.getApiCredentialsId(),
        )
    }

    private fun clean(
        value: String?,
        environment: EnvVars,
    ): String? = value?.let(environment::expand)?.trim()?.takeIf(String::isNotEmpty)

    private fun normalizeRepository(value: String): String {
        val trimmed = value.trim()
        val path =
            if (trimmed.contains("://")) {
                runCatching { URI(trimmed).path }.getOrNull() ?: trimmed
            } else {
                trimmed
            }
        return path.trim('/').removeSuffix(".git")
    }

    private fun first(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private data class Metadata(
        val serverId: String? = null,
        val repository: String? = null,
        val pullRequestNumber: String? = null,
        val sha: String? = null,
        val credentialsId: String? = null,
    )

    private val PULL_REQUEST_PATTERN = Regex("[1-9][0-9]{0,18}")
    private val SHA_PATTERN = Regex("[0-9a-fA-F]{7,64}")
}
