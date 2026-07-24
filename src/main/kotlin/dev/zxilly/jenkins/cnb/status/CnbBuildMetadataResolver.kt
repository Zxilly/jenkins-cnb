package dev.zxilly.jenkins.cnb.status

import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestSCMHead
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestSCMRevision
import dev.zxilly.jenkins.cnb.scm.CnbTagSCMRevision
import dev.zxilly.jenkins.cnb.security.CnbRepositoryPath
import hudson.model.Actionable
import hudson.model.Cause
import hudson.model.Item
import jenkins.plugins.git.AbstractGitSCMSource
import jenkins.scm.api.SCMRevision
import jenkins.scm.api.SCMRevisionAction
import jenkins.scm.api.SCMSource
import org.eclipse.jgit.lib.Repository
import java.net.URI
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

internal data class CnbBuildMetadataResolution(
    val relevant: Boolean,
    val target: CnbBuildMetadataTarget?,
)

/** Resolves CNB metadata without performing network I/O. */
internal object CnbBuildMetadataResolver {
    private val LOGGER = Logger.getLogger(CnbBuildMetadataResolver::class.java.name)
    private const val PLUGIN_PACKAGE_PREFIX = "dev.zxilly.jenkins.cnb."

    fun resolve(
        actionable: Actionable,
        item: Item?,
        causes: List<Cause>,
        explicit: CnbBuildMetadataConfiguration,
        previous: CnbBuildMetadataTarget?,
        defaultContext: String? = null,
    ): CnbBuildMetadataResolution {
        val revision = revisionMetadata(actionable)
        val source = sourceMetadata(item)
        val cause = causeMetadata(causes)

        val relevant =
            !explicit.isEmpty() ||
                revision.relevant ||
                source.relevant ||
                cause.relevant ||
                previous != null
        if (!relevant) return CnbBuildMetadataResolution(false, null)

        val defaultServerId =
            runCatching {
                CnbGlobalConfiguration
                    .get()
                    .getServers()
                    .firstOrNull()
                    ?.id
            }.getOrNull()

        // Explicit administrator/Pipeline values win. Trusted SCM revision/source data is
        // preferred over metadata from this plugin's persisted Cause objects.
        val serverId =
            first(
                explicit.serverId,
                source.serverId,
                cause.serverId,
                previous?.serverId,
                defaultServerId,
            )
        val repository =
            first(
                explicit.repository,
                source.repository,
                cause.repository,
                previous?.repository,
            )?.let(::normalizeRepository)
        val commitRepository =
            first(
                explicit.commitRepository,
                revision.commitRepository,
                cause.commitRepository,
                previous?.commitRepository,
                repository,
            )?.let(::normalizeRepository)
        val sha =
            first(
                explicit.sha,
                revision.sha,
                cause.sha,
                previous?.sha,
            )?.trim()?.lowercase(Locale.ROOT)
        val pullRequestNumber =
            first(
                explicit.pullRequestNumber,
                revision.pullRequestNumber,
                cause.pullRequestNumber,
                previous?.pullRequestNumber,
            )?.trim()
        val tag =
            first(
                explicit.tag,
                revision.tag,
                cause.tag,
                previous?.tag,
            )?.trim()
        val context =
            first(
                explicit.context,
                previous?.context,
                defaultContext,
                item?.fullName,
                "jenkins",
            )?.trim()
        val credentialsId =
            first(
                explicit.credentialsId,
                previous?.credentialsId,
            )

        if (
            serverId.isNullOrBlank() ||
            repository.isNullOrBlank() ||
            commitRepository.isNullOrBlank() ||
            sha.isNullOrBlank() ||
            context.isNullOrBlank()
        ) {
            return CnbBuildMetadataResolution(true, null)
        }
        if (
            !isRepository(repository) ||
            !isRepository(commitRepository) ||
            !isSha(sha) ||
            !isPullRequestNumber(pullRequestNumber) ||
            !isTag(tag)
        ) {
            return CnbBuildMetadataResolution(true, null)
        }

        return CnbBuildMetadataResolution(
            relevant = true,
            target =
                CnbBuildMetadataTarget(
                    serverId = serverId,
                    repository = repository,
                    sha = sha,
                    pullRequestNumber = pullRequestNumber,
                    context = context.take(MAX_CONTEXT_LENGTH),
                    credentialsId = credentialsId,
                    commitRepository = commitRepository,
                    tag = tag,
                ),
        )
    }

    fun isRepository(value: String?): Boolean {
        if (value == null) return false
        return CnbRepositoryPath.isValid(normalizeRepository(value))
    }

    fun isSha(value: String?): Boolean = value != null && SHA_PATTERN.matches(value.trim())

    fun isPullRequestNumber(value: String?): Boolean =
        value.isNullOrBlank() || (PULL_REQUEST_PATTERN.matches(value.trim()) && value.trim().toLongOrNull() != null)

    fun isTag(value: String?): Boolean =
        value.isNullOrBlank() ||
            value.trim().let { tag ->
                tag.length <= 1024 && Repository.isValidRefName("refs/tags/$tag")
            }

    private fun revisionMetadata(actionable: Actionable): Metadata {
        val revision =
            actionable.actions
                .filterIsInstance<SCMRevisionAction>()
                .firstOrNull()
                ?.revision ?: return Metadata()
        return when (revision) {
            is CnbPullRequestSCMRevision -> {
                Metadata(
                    sha = revision.headHash,
                    pullRequestNumber = (revision.head as? CnbPullRequestSCMHead)?.number,
                    commitRepository = (revision.head as? CnbPullRequestSCMHead)?.sourceRepository,
                    relevant = true,
                )
            }

            is CnbTagSCMRevision -> {
                Metadata(
                    sha = revision.hash,
                    tag = revision.head.name,
                    relevant = true,
                )
            }

            is AbstractGitSCMSource.SCMRevisionImpl -> {
                Metadata(sha = revision.hash, relevant = revision.javaClass.name.startsWith(PLUGIN_PACKAGE_PREFIX))
            }

            else -> {
                Metadata(
                    sha = beanValue(revision, "getHeadHash", "getHash"),
                    pullRequestNumber = beanValue(revision.head, "getNumber", "getId"),
                    relevant = revision.javaClass.name.startsWith(PLUGIN_PACKAGE_PREFIX),
                )
            }
        }
    }

    private fun sourceMetadata(item: Item?): Metadata {
        if (item == null) return Metadata()
        val source =
            try {
                SCMSource.SourceByItem.findSource(item)
            } catch (e: RuntimeException) {
                LOGGER.log(Level.FINE, "Unable to inspect SCMSource while resolving CNB metadata", e)
                null
            } ?: return Metadata()
        if (!source.javaClass.name.startsWith(PLUGIN_PACKAGE_PREFIX)) return Metadata()
        return Metadata(
            serverId = beanValue(source, "getServerId"),
            repository = beanValue(source, "getRepositoryPath", "getRepository"),
            relevant = true,
        )
    }

    private fun causeMetadata(causes: List<Cause>): Metadata {
        causes.forEach { cause ->
            if (!cause.javaClass.name.startsWith(PLUGIN_PACKAGE_PREFIX)) return@forEach
            val variables = beanStringMap(cause, "buildVariables")
            val pullRequestNumber =
                first(
                    beanValue(cause, "getPullRequestNumber"),
                    variables["CNB_PULL_REQUEST_IID"],
                    variables["CNB_PULL_REQUEST_NUMBER"],
                )
            val sha =
                if (pullRequestNumber.isNullOrBlank()) {
                    first(
                        beanValue(cause, "getCommit", "getSha"),
                        variables["CNB_COMMIT"],
                        variables["CNB_BRANCH_SHA"],
                    )
                } else {
                    first(
                        beanValue(cause, "getPullRequestSourceSha", "getCommit", "getSha"),
                        variables["CNB_PULL_REQUEST_SHA"],
                        variables["CNB_PULL_REQUEST_SOURCE_SHA"],
                        variables["CNB_COMMIT"],
                    )
                }
            val tag =
                variables["CNB_BRANCH"]
                    ?.takeIf { variables["CNB_IS_TAG"].equals("true", ignoreCase = true) }
            return Metadata(
                serverId = first(beanValue(cause, "getServerId"), variables["CNB_SERVER_ID"]),
                repository =
                    first(
                        beanValue(cause, "getRepository", "getRepositoryPath"),
                        variables["CNB_REPOSITORY"],
                        variables["CNB_REPO_SLUG"],
                    ),
                sha = sha,
                commitRepository =
                    first(
                        beanValue(cause, "getPullRequestSourceRepository"),
                        variables["CNB_PULL_REQUEST_SLUG"],
                        variables["CNB_REPO_SLUG"],
                    ),
                pullRequestNumber = pullRequestNumber,
                tag = tag,
                relevant = true,
            )
        }
        return Metadata()
    }

    private fun beanValue(
        bean: Any,
        vararg getters: String,
    ): String? {
        if (!bean.javaClass.name.startsWith(PLUGIN_PACKAGE_PREFIX)) return null
        for (getter in getters) {
            val method =
                bean.javaClass.methods.firstOrNull {
                    it.name == getter && it.parameterCount == 0
                } ?: continue
            val value =
                try {
                    method.invoke(bean)
                } catch (e: ReflectiveOperationException) {
                    LOGGER.log(Level.FINE, "Unable to inspect CNB metadata getter", e)
                    null
                }
            when (value) {
                is CharSequence -> {
                    value
                        .toString()
                        .trim()
                        .takeIf(String::isNotEmpty)
                        ?.let { return it }
                }

                is Number -> {
                    return value.toString()
                }
            }
        }
        return null
    }

    private fun beanStringMap(
        bean: Any,
        methodName: String,
    ): Map<String, String> {
        if (!bean.javaClass.name.startsWith(PLUGIN_PACKAGE_PREFIX)) return emptyMap()
        val method =
            bean.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                ?: return emptyMap()
        val value =
            try {
                method.invoke(bean)
            } catch (e: ReflectiveOperationException) {
                LOGGER.log(Level.FINE, "Unable to inspect CNB metadata variables", e)
                return emptyMap()
            }
        val map = value as? Map<*, *> ?: return emptyMap()
        val strings = LinkedHashMap<String, String>()
        for ((key, entryValue) in map) {
            if (key is String && entryValue is String) {
                strings[key] = entryValue
            }
        }
        return strings
    }

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
        val commitRepository: String? = null,
        val sha: String? = null,
        val pullRequestNumber: String? = null,
        val tag: String? = null,
        val relevant: Boolean = false,
    )

    private val SHA_PATTERN = Regex("[0-9a-fA-F]{7,64}")
    private val PULL_REQUEST_PATTERN = Regex("[1-9][0-9]{0,18}")
    private const val MAX_CONTEXT_LENGTH = 200
}
