package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.security.CnbGitObjectId
import dev.zxilly.jenkins.cnb.security.CnbRepositoryPath
import jenkins.plugins.git.AbstractGitSCMSource
import jenkins.plugins.git.GitTagSCMHead
import jenkins.plugins.git.GitTagSCMRevision
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import jenkins.scm.api.mixin.ChangeRequestSCMHead2
import jenkins.scm.api.mixin.ChangeRequestSCMRevision
import jenkins.scm.api.mixin.TagSCMHead
import org.eclipse.jgit.lib.Repository
import org.kohsuke.stapler.export.Exported

/** A branch advertised by CNB. */
class CnbBranchSCMHead(
    name: String,
) : SCMHead(requireValidBranchName(name)) {
    override fun getPronoun(): String = "Branch"

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** An immutable branch revision. */
class CnbBranchSCMRevision(
    head: CnbBranchSCMHead,
    hash: String,
) : AbstractGitSCMSource.SCMRevisionImpl(head, CnbGitObjectId.canonical(hash)) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** A tag advertised by CNB. */
class CnbTagSCMHead(
    name: String,
    timestamp: Long,
) : GitTagSCMHead(requireValidTagName(name), timestamp),
    TagSCMHead {
    override fun getPronoun(): String = "Tag"

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** An immutable tag revision. */
class CnbTagSCMRevision(
    head: CnbTagSCMHead,
    hash: String,
) : GitTagSCMRevision(head, CnbGitObjectId.canonical(hash)) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * A pull request head.
 *
 * The checkout strategy is encoded in the head name and retained explicitly so that Jenkins can
 * safely keep HEAD and MERGE jobs side by side when both strategies are enabled.
 */
class CnbPullRequestSCMHead(
    name: String,
    val number: String,
    val targetBranch: CnbBranchSCMHead,
    private val strategy: ChangeRequestCheckoutStrategy,
    private val origin: SCMHeadOrigin,
    val sourceRepository: String,
    val sourceBranch: String,
    val author: String,
    val title: String,
) : SCMHead(name),
    ChangeRequestSCMHead2 {
    init {
        require(number.isNotBlank()) { "CNB pull request number must not be blank" }
        require(CnbRepositoryPath.isValid(sourceRepository)) { "CNB pull request source repository must be canonical" }
        requireValidBranchName(sourceBranch)
    }

    override fun getPronoun(): String = "Pull Request"

    override fun getId(): String = number

    override fun getTarget(): CnbBranchSCMHead = targetBranch

    override fun getOriginName(): String = sourceBranch

    override fun getOrigin(): SCMHeadOrigin = origin

    override fun getCheckoutStrategy(): ChangeRequestCheckoutStrategy = strategy

    val isMerge: Boolean
        get() = strategy == ChangeRequestCheckoutStrategy.MERGE

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * A pull request revision, retaining both sides of the change request.
 *
 * [mergeHash] is the server-computed merge revision when CNB exposes one. Jenkins performs a local,
 * deterministic merge when it is absent, using [baseHash] and [headHash].
 */
class CnbPullRequestSCMRevision(
    head: CnbPullRequestSCMHead,
    baseHash: String,
    headHash: String,
    mergeHash: String? = null,
) : ChangeRequestSCMRevision<CnbPullRequestSCMHead>(
        head,
        CnbBranchSCMRevision(head.targetBranch, baseHash),
    ) {
    val baseHash: String = CnbGitObjectId.canonical(baseHash)

    @get:Exported
    val headHash: String = CnbGitObjectId.canonical(headHash)

    val mergeHash: String? = mergeHash?.let(CnbGitObjectId::canonical)

    override fun equivalent(revision: ChangeRequestSCMRevision<*>): Boolean =
        revision is CnbPullRequestSCMRevision &&
            head == revision.head &&
            headHash == revision.headHash

    override fun _hashCode(): Int = 31 * head.hashCode() + headHash.hashCode()

    override fun toString(): String =
        if ((head as CnbPullRequestSCMHead).isMerge) {
            "$headHash+$baseHash (${mergeHash ?: "LOCAL_MERGE"})"
        } else {
            headHash
        }

    companion object {
        private const val serialVersionUID = 1L
    }
}

private fun requireValidBranchName(name: String): String {
    require(Repository.isValidRefName("refs/heads/$name")) { "CNB branch name is not a valid Git ref" }
    return name
}

private fun requireValidTagName(name: String): String {
    require(Repository.isValidRefName("refs/tags/$name")) { "CNB tag name is not a valid Git ref" }
    return name
}
