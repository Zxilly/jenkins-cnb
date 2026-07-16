package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.model.CnbBadge
import dev.zxilly.jenkins.cnb.api.model.CnbBadgeSummary
import dev.zxilly.jenkins.cnb.api.model.CnbBadgeUploadResult
import hudson.Extension
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import java.util.LinkedHashMap

/** Lists the README badges exposed by a CNB repository. */
class CnbBadgesStep
    @DataBoundConstructor
    constructor() : CnbContextAwareStep() {
        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.Badges, context)

        @Extension
        @Symbol("cnbBadges")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbBadges"

            override fun getDisplayName(): String = "List CNB badges"
        }
    }

/** Reads one CNB badge as JSON for latest or an eight-character commit hash. */
class CnbBadgeStep
    @DataBoundConstructor
    constructor(
        badge: String,
    ) : CnbContextAwareStep() {
        val badge: String = CnbReadInput.required(badge, "badge name")
        var revision: String = "latest"
            private set
        var branch: String? = null
            private set

        @DataBoundSetter fun setRevision(value: String?) {
            revision = CnbReadInput.optional(value, "badge revision") ?: "latest"
        }

        @DataBoundSetter fun setBranch(value: String?) {
            branch = CnbReadInput.optional(value, "badge branch")
        }

        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.Badge(badge, revision, branch), context)

        @Extension
        @Symbol("cnbBadge")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbBadge"

            override fun getDisplayName(): String = "Read a CNB badge"
        }
    }

/** Uploads explicit CNB badge data for the resolved build commit. */
class CnbUploadBadgeStep
    @DataBoundConstructor
    constructor(
        key: String,
    ) : CnbContextAwareStep() {
        val key: String = CnbBadgeInput.required(key, "badge key", MAX_KEY_LENGTH)
        var message: String? = null
            private set
        var link: String = ""
            private set
        var latest: Boolean = false
            private set
        var value: Long? = null
            private set

        @DataBoundSetter fun setMessage(value: String?) {
            message = CnbBadgeInput.optional(value, "badge message", MAX_TEXT_LENGTH)
        }

        @DataBoundSetter fun setLink(value: String?) {
            link = CnbBadgeInput.optional(value, "badge link", MAX_LINK_LENGTH).orEmpty()
        }

        @DataBoundSetter fun setLatest(value: Boolean) {
            latest = value
        }

        @DataBoundSetter fun setValue(value: Long?) {
            this.value = value
        }

        override fun start(context: StepContext): StepExecution =
            execution(CnbStepRequest.UploadBadge(key, message, link, latest, value), context)

        @Extension
        @Symbol("cnbUploadBadge")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbUploadBadge"

            override fun getDisplayName(): String = "Upload a CNB badge"
        }

        companion object {
            private const val MAX_KEY_LENGTH = 1_024
            private const val MAX_TEXT_LENGTH = 4_096
            private const val MAX_LINK_LENGTH = 8_192
        }
    }

internal object CnbBadgePipelineValues {
    fun summaries(values: List<CnbBadgeSummary>): ArrayList<LinkedHashMap<String, Any?>> =
        ArrayList<LinkedHashMap<String, Any?>>(values.size).apply {
            values.forEach { value ->
                add(
                    linkedMapOf(
                        "name" to value.name,
                        "description" to value.description,
                        "type" to value.type,
                        "url" to value.url,
                        "link" to value.link,
                        "group" to
                            linkedMapOf(
                                "status" to value.group.status,
                                "type" to value.group.type,
                                "englishType" to value.group.englishType,
                            ),
                    ),
                )
            }
        }

    fun badge(value: CnbBadge): LinkedHashMap<String, Any?> =
        linkedMapOf(
            "color" to value.color,
            "label" to value.label,
            "message" to value.message,
            "link" to value.link,
            "links" to ArrayList(value.links),
        )

    fun upload(value: CnbBadgeUploadResult): LinkedHashMap<String, String> =
        linkedMapOf(
            "url" to value.url,
            "latestUrl" to value.latestUrl,
        )
}

private object CnbBadgeInput {
    fun required(
        value: String,
        name: String,
        maxLength: Int,
    ): String = optional(value, name, maxLength) ?: throw IllegalArgumentException("CNB $name must not be blank")

    fun optional(
        value: String?,
        name: String,
        maxLength: Int,
    ): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        require(normalized.length <= maxLength) { "CNB $name must be at most $maxLength characters" }
        require(normalized.none { it.code < 0x20 || it.code == 0x7f }) { "CNB $name contains control characters" }
        return normalized
    }
}
