package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.Messages
import hudson.Extension
import hudson.model.Item
import hudson.util.FormValidation
import jenkins.scm.api.SCMSource
import jenkins.scm.api.trait.SCMSourceContext
import jenkins.scm.api.trait.SCMSourceTrait
import jenkins.scm.api.trait.SCMSourceTraitDescriptor
import org.jenkinsci.Symbol
import org.kohsuke.stapler.AncestorInPath
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import java.text.Normalizer

/** Disables lifecycle metadata emitted by Jenkins listeners for this branch source. */
class CnbSkipReportingTrait
    @DataBoundConstructor
    constructor() : SCMSourceTrait() {
        @Extension
        @Symbol("cnbSkipReporting")
        class DescriptorImpl : SCMSourceTraitDescriptor() {
            override fun getDisplayName(): String = Messages.CnbSkipReportingTrait_DisplayName()

            override fun getContextClass(): Class<out SCMSourceContext<*, *>> = CnbSCMSourceContext::class.java

            override fun getSourceClass(): Class<out SCMSource> = CnbSCMSource::class.java
        }
    }

/** Supplies the default context for lifecycle metadata emitted for this branch source. */
class CnbReportingContextTrait
    @DataBoundConstructor
    constructor(
        context: String,
    ) : SCMSourceTrait() {
        val context: String = CnbReportingContextValidator.normalize(context)

        init {
            val validationMessage = CnbReportingContextValidator.validationMessage(this.context)
            require(validationMessage == null) { validationMessage ?: "Invalid CNB reporting context" }
        }

        @Extension
        @Symbol("cnbReportingContext")
        class DescriptorImpl : SCMSourceTraitDescriptor() {
            override fun getDisplayName(): String = Messages.CnbReportingContextTrait_DisplayName()

            override fun getContextClass(): Class<out SCMSourceContext<*, *>> = CnbSCMSourceContext::class.java

            override fun getSourceClass(): Class<out SCMSource> = CnbSCMSource::class.java

            @POST
            fun doCheckContext(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation {
                if (item != null && !item.hasPermission(Item.CONFIGURE)) return FormValidation.ok()
                val message = CnbReportingContextValidator.validationMessage(value)
                return if (message == null) FormValidation.ok() else FormValidation.error(message)
            }
        }
    }

internal object CnbReportingContextValidator {
    const val MAX_LENGTH = 200

    fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)

    fun validationMessage(value: String?): String? {
        val normalized = value?.let(::normalize).orEmpty()
        if (normalized.isEmpty()) return "Context is required"
        if (normalized.length > MAX_LENGTH) return "Context must be at most $MAX_LENGTH characters"
        if (containsUnsafeCodePoint(normalized)) {
            return "Context must not contain control, invisible, private-use, or malformed characters"
        }
        return null
    }

    private fun containsUnsafeCodePoint(value: String): Boolean {
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (
                Character.isISOControl(codePoint) ||
                Character.getType(codePoint) in UNSAFE_CHARACTER_TYPES
            ) {
                return true
            }
            offset += Character.charCount(codePoint)
        }
        return false
    }

    private val UNSAFE_CHARACTER_TYPES =
        setOf(
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
        )
}
