package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.security.CnbGitObjectId
import hudson.Extension
import hudson.Functions
import hudson.MarkupText
import hudson.model.Run
import hudson.scm.ChangeLogAnnotator
import hudson.scm.ChangeLogSet
import java.util.logging.Level
import java.util.logging.Logger

/** Adds safe CNB links to Git changelog messages rendered by Jenkins. */
@Extension
class CnbChangeLogAnnotator : ChangeLogAnnotator() {
    override fun annotate(
        build: Run<*, *>,
        change: ChangeLogSet.Entry,
        text: MarkupText,
    ) {
        try {
            val browser = change.parent?.browser as? CnbRepositoryBrowser ?: return
            annotate(browser, change.commitId, text)
        } catch (failure: RuntimeException) {
            LOGGER.log(
                Level.WARNING,
                "CNB changelog annotation failed with {0}",
                failure.javaClass.simpleName,
            )
        }
    }

    internal fun annotate(
        browser: CnbRepositoryBrowser,
        commitId: String?,
        text: MarkupText,
    ) {
        if (!browser.supportsSafeChangelogLinks()) return

        PULL_REQUEST_REFERENCE.findAll(text.text).forEach { match ->
            val reference = requireNotNull(match.groups[1])
            val number = reference.value.removePrefix("#")
            text.addHyperlink(
                reference.range.first,
                reference.range.last + 1,
                browser.pullRequestLink(number).toExternalForm(),
            )
        }

        val canonical = commitId?.takeIf(CnbGitObjectId::isPresent)?.let(CnbGitObjectId::canonical) ?: return
        val commitUrl = Functions.htmlAttributeEscape(browser.commitLink(canonical).toExternalForm())
        text.addMarkup(
            text.length(),
            " (<a href='$commitUrl'>commit: ${canonical.take(COMMIT_ABBREVIATION_LENGTH)}</a>)",
        )
    }

    companion object {
        private const val COMMIT_ABBREVIATION_LENGTH = 12
        private val LOGGER = Logger.getLogger(CnbChangeLogAnnotator::class.java.name)
        private val PULL_REQUEST_REFERENCE =
            Regex(
                """(?i)(?<![\p{L}\p{N}_./:@?&=#-])(?:PR|pull(?:[ \t]+|-)request)[ \t]*(#[1-9][0-9]{0,19})(?![\p{L}\p{N}_])""",
            )
    }
}
