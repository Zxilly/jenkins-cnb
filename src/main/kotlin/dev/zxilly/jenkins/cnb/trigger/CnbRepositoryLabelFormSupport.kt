package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.security.CnbRepositoryPath
import hudson.model.AutoCompletionCandidates
import hudson.model.Item
import hudson.util.FormValidation

/** Shared, permission-aware repository-label behavior for Classic and SCM Source forms. */
internal class CnbRepositoryLabelFormSupport(
    private val lookup: CnbRepositoryLabelLookup,
) {
    fun autocomplete(
        item: Item?,
        serverId: String?,
        repositoryPath: String?,
        credentialsId: String?,
        value: String?,
    ): AutoCompletionCandidates {
        val candidates = AutoCompletionCandidates()
        val request = request(item, serverId, repositoryPath, credentialsId) ?: return candidates
        val query = value.orEmpty().substringAfterLast(',').trim()
        if (query.isEmpty() || query.length > MAX_LABEL_LENGTH) return candidates
        val catalog = lookup.lookup(request) as? CnbRepositoryLabelCatalogResult.Available ?: return candidates
        catalog.labels
            .asSequence()
            .filter { label -> label.startsWith(query, ignoreCase = true) }
            .take(MAX_AUTOCOMPLETE_LABELS)
            .forEach(candidates::add)
        return candidates
    }

    fun validate(
        item: Item?,
        required: String?,
        excluded: String?,
        serverId: String?,
        repositoryPath: String?,
        credentialsId: String?,
        validateRequired: Boolean,
    ): FormValidation {
        if (item == null || !item.hasPermission(Item.CONFIGURE)) return FormValidation.ok()
        val policy =
            try {
                CnbPullRequestLabelPolicy(required.orEmpty(), excluded.orEmpty())
            } catch (failure: IllegalArgumentException) {
                return FormValidation.error(failure.message)
            }
        val configured =
            (if (validateRequired) policy.requiredConfiguration else policy.excludedConfiguration)
                .split(',')
                .filter(String::isNotEmpty)
        if (configured.isEmpty()) return FormValidation.ok()
        val request = request(item, serverId, repositoryPath, credentialsId) ?: return FormValidation.ok()
        return when (val catalog = lookup.lookup(request)) {
            CnbRepositoryLabelCatalogResult.Unavailable -> {
                FormValidation.warning("Could not verify labels against CNB; runtime matching remains fail-closed")
            }

            is CnbRepositoryLabelCatalogResult.Available -> {
                val unknown = configured.filterNot(catalog.labels.toHashSet()::contains)
                when {
                    !catalog.complete -> {
                        FormValidation.warning("CNB has more labels than the configuration catalog can display")
                    }

                    unknown.isNotEmpty() -> {
                        FormValidation.warning("One or more labels were not found in the CNB repository")
                    }

                    else -> {
                        FormValidation.ok()
                    }
                }
            }
        }
    }

    private fun request(
        item: Item?,
        serverId: String?,
        repositoryPath: String?,
        credentialsId: String?,
    ): CnbRepositoryLabelLookupRequest? {
        if (item == null || !item.hasPermission(Item.CONFIGURE)) return null
        val normalizedServerId = serverId?.trim()?.takeIf { it.matches(SERVER_ID_PATTERN) } ?: return null
        val normalizedRepository = repositoryPath?.trim()?.trim('/')?.takeIf(CnbRepositoryPath::isValid) ?: return null
        return CnbRepositoryLabelLookupRequest(
            normalizedServerId,
            normalizedRepository,
            credentialsId?.trim()?.takeIf(String::isNotEmpty),
            item,
        )
    }

    private companion object {
        const val MAX_LABEL_LENGTH = 100
        const val MAX_AUTOCOMPLETE_LABELS = 50
        val SERVER_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}
