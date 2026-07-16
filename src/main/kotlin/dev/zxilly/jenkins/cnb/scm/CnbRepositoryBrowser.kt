package dev.zxilly.jenkins.cnb.scm

import hudson.Extension
import hudson.model.Descriptor
import hudson.plugins.git.GitChangeSet
import hudson.plugins.git.browser.GitRepositoryBrowser
import hudson.scm.RepositoryBrowser
import org.jenkinsci.Symbol
import org.kohsuke.stapler.DataBoundConstructor
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Links Git changelog entries to the matching CNB web pages. */
class CnbRepositoryBrowser
    @DataBoundConstructor
    constructor(
        repositoryUrl: String,
    ) : GitRepositoryBrowser(repositoryUrl.trimEnd('/') + "/") {
        val repositoryUrl: String
            get() = repoUrl.trimEnd('/')

        override fun getChangeSetLink(changeSet: GitChangeSet): URL = page("-/commit/${segment(changeSet.id)}")

        override fun getDiffLink(path: GitChangeSet.Path): URL? = null

        override fun getFileLink(path: GitChangeSet.Path): URL =
            if (path.editType == hudson.scm.EditType.DELETE) {
                URI(repositoryUrl).toURL()
            } else {
                page("-/blob/${segment(path.changeSet.id)}/${path.path.split('/').joinToString("/") { segment(it) }}")
            }

        private fun page(relative: String): URL = URI(repoUrl.trimEnd('/') + "/$relative").toURL()

        @Extension
        @Symbol("cnbBrowser")
        class DescriptorImpl : Descriptor<RepositoryBrowser<*>>() {
            override fun getDisplayName(): String = "CNB"
        }

        companion object {
            private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        }
    }
