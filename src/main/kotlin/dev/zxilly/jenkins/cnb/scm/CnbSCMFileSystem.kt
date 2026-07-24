package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.CnbClientFactory
import hudson.Extension
import hudson.model.Item
import hudson.scm.SCM
import hudson.scm.SCMDescriptor
import jenkins.plugins.git.AbstractGitSCMSource
import jenkins.scm.api.SCMFile
import jenkins.scm.api.SCMFileSystem
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMRevision
import jenkins.scm.api.SCMSource
import jenkins.scm.api.SCMSourceDescriptor
import java.io.IOException

/** A fixed-revision CNB file system for revisions representable by the contents REST API. */
class CnbSCMFileSystem private constructor(
    revision: SCMRevision?,
    private val client: CnbClient,
    private val repositoryPath: String,
    private val ref: String,
    private val modified: Long,
) : SCMFileSystem(revision) {
    @Volatile
    private var open = true

    override fun lastModified(): Long = modified

    override fun getRoot(): SCMFile {
        check(open) { "CNB SCM file system is closed" }
        return CnbSCMFile.root(client, repositoryPath, ref, modified)
    }

    override fun close() {
        if (!open) {
            return
        }
        synchronized(this) {
            if (open) {
                open = false
                client.close()
            }
        }
    }

    @Extension
    open class BuilderImpl : Builder() {
        override fun supports(scm: SCM): Boolean = false

        override fun supports(source: SCMSource): Boolean = source is CnbSCMSource

        override fun supportsDescriptor(descriptor: SCMDescriptor<*>): Boolean = false

        override fun supportsDescriptor(descriptor: SCMSourceDescriptor): Boolean = descriptor is CnbSCMSource.DescriptorImpl

        override fun build(
            owner: Item,
            scm: SCM,
            revision: SCMRevision?,
        ): SCMFileSystem? = null

        override fun build(
            source: SCMSource,
            head: SCMHead,
            revision: SCMRevision?,
        ): SCMFileSystem? {
            source as CnbSCMSource
            val client = createClient(source)
            try {
                val targetRepository = source.remember(client.getRepository(source.repositoryPath))
                val location =
                    resolve(client, source, targetRepository.path, head, revision) ?: run {
                        client.close()
                        return null
                    }
                return CnbSCMFileSystem(
                    location.revision,
                    client,
                    location.repositoryPath,
                    location.ref,
                    location.modified,
                )
            } catch (failure: Exception) {
                client.close()
                throw failure
            }
        }

        protected open fun createClient(source: CnbSCMSource): CnbClient =
            CnbClientFactory.create(source.serverId, source.getApiCredentialsId(), source.owner)

        private fun resolve(
            client: CnbClient,
            source: CnbSCMSource,
            targetRepository: String,
            head: SCMHead,
            revision: SCMRevision?,
        ): Location? =
            when {
                head is CnbPullRequestSCMHead && revision is CnbBranchSCMRevision -> {
                    if (revision.head != head.target) {
                        throw IOException("Trusted CNB revision does not belong to pull request ${head.number} target")
                    }
                    Location(targetRepository, revision.hash, revision, 0)
                }

                head is CnbPullRequestSCMHead && revision is CnbPullRequestSCMRevision -> {
                    if (revision.head != head) {
                        throw IOException("CNB revision does not belong to pull request ${head.number}")
                    }
                    if (head.isMerge) {
                        null
                    } else {
                        Location(head.sourceRepository, revision.headHash, revision, 0)
                    }
                }

                head is CnbPullRequestSCMHead && revision == null -> {
                    if (head.isMerge) {
                        return null
                    }
                    val pullRequest = CnbPullRequestIdentity.fetchOpen(client, targetRepository, head) ?: return null
                    val resolved =
                        CnbPullRequestSCMRevision(
                            head,
                            pullRequest.targetSha,
                            pullRequest.sourceSha,
                            pullRequest.mergeSha,
                        )
                    Location(pullRequest.sourceRepo, pullRequest.sourceSha, resolved, 0)
                }

                head is CnbBranchSCMHead -> {
                    val resolved =
                        revision as? AbstractGitSCMSource.SCMRevisionImpl
                            ?: CnbBranchSCMRevision(head, client.getBranch(targetRepository, head.name).sha)
                    Location(targetRepository, resolved.hash, resolved, 0)
                }

                head is CnbTagSCMHead -> {
                    val resolved =
                        revision as? AbstractGitSCMSource.SCMRevisionImpl
                            ?: try {
                                CnbTagSCMRevision(head, client.getTag(targetRepository, head.name).sha)
                            } catch (failure: CnbApiException) {
                                if (failure.statusCode != 404) throw failure
                                null
                            }
                            ?: return null
                    Location(targetRepository, resolved.hash, resolved, head.timestamp)
                }

                else -> {
                    null
                }
            }

        private data class Location(
            val repositoryPath: String,
            val ref: String,
            val revision: SCMRevision?,
            val modified: Long,
        )
    }
}
