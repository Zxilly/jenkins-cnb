package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.security.CnbGitObjectId
import hudson.Extension
import hudson.model.Descriptor
import hudson.plugins.git.GitChangeSet
import hudson.plugins.git.browser.GitRepositoryBrowser
import hudson.scm.RepositoryBrowser
import org.jenkinsci.Symbol
import org.kohsuke.stapler.DataBoundConstructor
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Links Git changelog entries to the matching CNB web pages. */
class CnbRepositoryBrowser
    @DataBoundConstructor
    constructor(
        repositoryUrl: String,
    ) : GitRepositoryBrowser(repositoryUrl.trimEnd('/') + "/") {
        private var pullRequestRepositoryUrl: String? = repositoryUrl.trimEnd('/')

        internal constructor(
            repositoryUrl: String,
            pullRequestRepositoryUrl: String,
        ) : this(repositoryUrl) {
            this.pullRequestRepositoryUrl = pullRequestRepositoryUrl.trimEnd('/')
        }

        val repositoryUrl: String
            get() = repoUrl.trimEnd('/')

        override fun getChangeSetLink(changeSet: GitChangeSet): URL = commitLink(changeSet.id)

        internal fun commitLink(objectId: String): URL = page("-/commit/${segment(CnbGitObjectId.canonical(objectId))}")

        internal fun pullRequestLink(number: String): URL {
            require(PULL_REQUEST_NUMBER.matches(number)) { "CNB pull request number must be a positive integer" }
            return page("-/pulls/$number", pullRequestRepositoryUrl ?: repositoryUrl)
        }

        internal fun supportsSafeChangelogLinks(): Boolean =
            isSafeWebUrl(repositoryUrl) && isSafeWebUrl(pullRequestRepositoryUrl ?: repositoryUrl)

        override fun getDiffLink(path: GitChangeSet.Path): URL? = null

        override fun getFileLink(path: GitChangeSet.Path): URL =
            if (path.editType == hudson.scm.EditType.DELETE) {
                URI(repositoryUrl).toURL()
            } else {
                page("-/blob/${segment(path.changeSet.id)}/${path.path.split('/').joinToString("/") { segment(it) }}")
            }

        private fun page(
            relative: String,
            baseUrl: String = repositoryUrl,
        ): URL = URI(baseUrl.trimEnd('/') + "/$relative").toURL()

        @Extension
        @Symbol("cnbBrowser")
        class DescriptorImpl : Descriptor<RepositoryBrowser<*>>() {
            override fun getDisplayName(): String = "CNB"
        }

        companion object {
            private val ALLOWED_WEB_SCHEMES = setOf("http", "https")
            private val PULL_REQUEST_NUMBER = Regex("[1-9][0-9]{0,19}")

            private fun isSafeWebUrl(value: String): Boolean {
                val uri =
                    try {
                        URI(value)
                    } catch (_: URISyntaxException) {
                        return false
                    }
                return uri.isAbsolute &&
                    !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    uri.scheme.lowercase(Locale.ROOT) in ALLOWED_WEB_SCHEMES
            }

            private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        }
    }
