package dev.zxilly.jenkins.cnb.ui

import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.scm.CnbSCMSource
import dev.zxilly.jenkins.cnb.security.CnbRepositoryPath
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataAction
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataResolver
import dev.zxilly.jenkins.cnb.trigger.CnbPushTrigger
import hudson.Extension
import hudson.model.Action
import hudson.model.Job
import hudson.model.Run
import jenkins.model.TransientActionFactory
import jenkins.scm.api.SCMSource
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A safe external link assembled exclusively from administrator configuration and resolved SCM data. */
class CnbExternalLinkAction internal constructor(
    private val label: String,
    private val targetUrl: String,
) : Action {
    override fun getIconFileName(): String = "symbol-link plugin-ionicons-api"

    override fun getDisplayName(): String = label

    override fun getUrlName(): String = targetUrl
}

@Extension
class CnbJobLinkActionFactory : TransientActionFactory<Job<*, *>>() {
    override fun type(): Class<Job<*, *>> = Job::class.java

    override fun createFor(target: Job<*, *>): Collection<Action> =
        CnbLinkResolver.forJob(target)?.let { link -> listOf(link.repositoryAction()) }.orEmpty()
}

@Extension
class CnbRunLinkActionFactory : TransientActionFactory<Run<*, *>>() {
    override fun type(): Class<Run<*, *>> = Run::class.java

    @Suppress("DEPRECATION") // getAllActions/getAction would recursively invoke this transient factory.
    override fun createFor(target: Run<*, *>): Collection<Action> {
        val job = CnbLinkResolver.forJob(target.parent)
        // Read only the persisted Action list. Calling a resolver that in turn asks Jenkins for
        // typed/all Actions from inside a TransientActionFactory re-enters this factory and can
        // overflow the stack while another plugin enumerates Run actions.
        val resolved =
            target.actions
                .asSequence()
                .filterIsInstance<CnbBuildMetadataAction>()
                .mapNotNull(CnbBuildMetadataAction::target)
                .firstOrNull()

        val link =
            if (resolved != null) {
                CnbLinkResolver.fromResolved(
                    resolved.serverId,
                    resolved.repository,
                    resolved.commitRepository,
                    resolved.sha,
                    resolved.pullRequestNumber,
                    resolved.tag,
                )
            } else {
                job
            } ?: return emptyList()
        return listOf(link.mostSpecificAction())
    }
}

internal data class CnbLinkTarget(
    val webUrl: String,
    val repository: String,
    val commitRepository: String = repository,
    val sha: String? = null,
    val pullRequestNumber: String? = null,
    val tag: String? = null,
) {
    fun repositoryAction(): CnbExternalLinkAction = CnbExternalLinkAction("CNB repository", "$webUrl/${repository.pathSegments()}")

    fun mostSpecificAction(): CnbExternalLinkAction =
        when {
            pullRequestNumber != null -> {
                CnbExternalLinkAction(
                    "CNB pull request #$pullRequestNumber",
                    "$webUrl/${repository.pathSegments()}/-/pulls/${segment(pullRequestNumber)}",
                )
            }

            tag != null -> {
                CnbExternalLinkAction(
                    "CNB tag $tag",
                    "$webUrl/${repository.pathSegments()}/-/releases/tag/${segment(tag)}",
                )
            }

            sha != null -> {
                CnbExternalLinkAction(
                    "CNB commit ${sha.take(12)}",
                    "$webUrl/${commitRepository.pathSegments()}/-/commit/${segment(sha)}",
                )
            }

            else -> {
                repositoryAction()
            }
        }

    private fun String.pathSegments(): String = split('/').joinToString("/") { segment(it) }
}

internal object CnbLinkResolver {
    fun forJob(job: Job<*, *>): CnbLinkTarget? {
        val source = runCatching { SCMSource.SourceByItem.findSource(job) }.getOrNull() as? CnbSCMSource
        if (source != null) return fromResolved(source.serverId, source.repositoryPath)

        val trigger = runCatching { jenkins.model.ParameterizedJobMixIn.getTrigger(job, CnbPushTrigger::class.java) }.getOrNull()
        return trigger?.let { fromResolved(it.serverId, it.repositoryPath) }
    }

    fun fromResolved(
        serverId: String,
        repository: String,
        commitRepository: String = repository,
        sha: String? = null,
        pullRequestNumber: String? = null,
        tag: String? = null,
    ): CnbLinkTarget? {
        if (!CnbRepositoryPath.isValid(repository) || !CnbRepositoryPath.isValid(commitRepository)) return null
        if (sha != null && !SHA.matches(sha)) return null
        if (pullRequestNumber != null && !PULL_REQUEST.matches(pullRequestNumber)) return null
        val normalizedTag = tag?.trim()?.takeIf(String::isNotEmpty)
        if (tag != null && (normalizedTag == null || !CnbBuildMetadataResolver.isTag(normalizedTag))) {
            return null
        }
        val server = runCatching { CnbGlobalConfiguration.get().findServer(serverId) }.getOrNull() ?: return null
        return CnbLinkTarget(
            server.webUrl.trimEnd('/'),
            repository,
            commitRepository,
            sha,
            pullRequestNumber,
            normalizedTag,
        )
    }

    private val SHA = Regex("[0-9a-fA-F]{7,64}")
    private val PULL_REQUEST = Regex("[1-9][0-9]{0,18}")
}

private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
