package dev.zxilly.jenkins.cnb.status

import dev.zxilly.jenkins.cnb.scm.CnbReportingContextTrait
import dev.zxilly.jenkins.cnb.scm.CnbReportingContextValidator
import dev.zxilly.jenkins.cnb.scm.CnbSCMSource
import dev.zxilly.jenkins.cnb.scm.CnbSkipReportingTrait
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import hudson.model.Item
import jenkins.scm.api.SCMSource
import java.util.logging.Level
import java.util.logging.Logger

/** Reporting-only projection of CNB Branch Source traits. It never decorates discovery or checkout. */
internal data class CnbBranchSourceReportingPolicy(
    val automaticReportingEnabled: Boolean = true,
    val defaultContext: String? = null,
) {
    companion object {
        private val LOGGER = Logger.getLogger(CnbBranchSourceReportingPolicy::class.java.name)

        @SuppressFBWarnings(
            value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
            justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
        )
        fun forItem(item: Item?): CnbBranchSourceReportingPolicy {
            if (item == null) return CnbBranchSourceReportingPolicy()
            val source =
                try {
                    SCMSource.SourceByItem.findSource(item)
                } catch (e: RuntimeException) {
                    LOGGER.log(Level.FINE, "Unable to inspect CNB reporting traits", e)
                    null
                } as? CnbSCMSource ?: return CnbBranchSourceReportingPolicy()

            val traits = source.traits
            val configuredContext =
                traits
                    .filterIsInstance<CnbReportingContextTrait>()
                    .lastOrNull()
                    ?.context
                    ?.let(CnbReportingContextValidator::normalize)
            return CnbBranchSourceReportingPolicy(
                automaticReportingEnabled = traits.none { it is CnbSkipReportingTrait },
                // Applying SCM traits is ordered. Matching the normal decoration semantics means
                // the last configured context wins if a hand-written configuration contains a
                // duplicate trait.
                defaultContext =
                    configuredContext?.takeIf {
                        CnbReportingContextValidator.validationMessage(it) == null
                    },
            )
        }
    }
}
