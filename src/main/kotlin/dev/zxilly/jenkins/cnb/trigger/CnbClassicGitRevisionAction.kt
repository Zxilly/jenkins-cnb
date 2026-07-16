package dev.zxilly.jenkins.cnb.trigger

import hudson.model.Job
import hudson.plugins.git.GitSCM
import hudson.plugins.git.RevisionParameterAction
import jenkins.triggers.SCMTriggerItem
import org.eclipse.jgit.transport.URIish
import java.net.URISyntaxException
import java.util.Locale

/** Selects an exact configured Git remote that can actually provide a verified CNB revision. */
internal object CnbClassicGitRevisionAction {
    fun create(
        job: Job<*, *>,
        commit: String,
        sourceCloneUrl: String,
    ): RevisionParameterAction? {
        val source =
            try {
                URIish(sourceCloneUrl)
            } catch (_: URISyntaxException) {
                return null
            }
        val item = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(job) ?: return null
        for (scm in item.getSCMs()) {
            val git = scm as? GitSCM ?: continue
            for (repository in git.repositories) {
                for (configured in repository.getURIs()) {
                    if (sameRepository(configured, source)) {
                        // GitSCM compares this URI to its RemoteConfig before honoring the commit.
                        return RevisionParameterAction(commit, configured)
                    }
                }
            }
        }
        return null
    }

    private fun sameRepository(
        configured: URIish,
        source: URIish,
    ): Boolean {
        if (!configured.scheme.orEmpty().equals(source.scheme.orEmpty(), ignoreCase = true)) return false
        if (!configured.host.orEmpty().equals(source.host.orEmpty(), ignoreCase = true)) return false
        if (effectivePort(configured) != effectivePort(source)) return false
        return canonicalPath(configured.path) == canonicalPath(source.path)
    }

    private fun effectivePort(uri: URIish): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.orEmpty().lowercase(Locale.ROOT) == "https" -> 443
            uri.scheme.orEmpty().lowercase(Locale.ROOT) == "http" -> 80
            uri.scheme.orEmpty().lowercase(Locale.ROOT) == "ssh" -> 22
            else -> -1
        }

    private fun canonicalPath(value: String?): String =
        value
            .orEmpty()
            .replace('\\', '/')
            .trimEnd('/')
            .removeSuffix(".git")
}
