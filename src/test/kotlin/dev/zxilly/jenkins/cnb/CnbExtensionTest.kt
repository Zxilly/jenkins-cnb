package dev.zxilly.jenkins.cnb

import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.credentials.CnbTokenCredentials
import dev.zxilly.jenkins.cnb.scm.CnbChangeLogAnnotator
import dev.zxilly.jenkins.cnb.scm.CnbRepositoryBrowser
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataReconciliationWork
import hudson.init.Terminator
import hudson.model.Run
import hudson.plugins.git.GitChangeSet
import hudson.scm.ChangeLogAnnotator
import hudson.scm.ChangeLogSet
import hudson.scm.RepositoryBrowser
import hudson.util.FormValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.annotation_indexer.Index
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.lang.reflect.Method

@WithJenkins
class CnbExtensionTest {
    @Test
    fun `Kotlin extensions are indexed and loaded`(jenkins: JenkinsRule) {
        assertNotNull(CnbGlobalConfiguration.get())
        assertNotNull(jenkins.jenkins.getDescriptor(CnbServer::class.java))
        val credentialsDescriptor =
            requireNotNull(jenkins.jenkins.getDescriptor(CnbTokenCredentials::class.java)) as
                CnbTokenCredentials.DescriptorImpl
        assertEquals(FormValidation.Kind.ERROR, credentialsDescriptor.doCheckId(jenkins.jenkins, "invalid id").kind)
        assertNotNull(jenkins.jenkins.getExtensionList(CnbBuildMetadataReconciliationWork::class.java).singleOrNull())
        assertNotNull(
            jenkins.jenkins
                .getExtensionList(ChangeLogAnnotator::class.java)
                .filterIsInstance<CnbChangeLogAnnotator>()
                .singleOrNull(),
        )
        val run = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject("annotated-changelog"))
        val change =
            GitChangeSet(
                listOf(
                    "commit $COMMIT_SHA",
                    "author CNB User <user@example.invalid> 0 +0000",
                    "committer CNB User <user@example.invalid> 0 +0000",
                    "",
                    "    Fix PR #42",
                ),
                true,
            )
        AnnotatedChangeLogSet(run, CnbRepositoryBrowser("https://cnb.cool/example/project"), change)
        val annotated = change.msgAnnotated
        assertTrue(annotated.contains("https://cnb.cool/example/project/-/pulls/42"))
        assertTrue(annotated.contains("https://cnb.cool/example/project/-/commit/$COMMIT_SHA"))
        val terminators =
            Index.list(Terminator::class.java, javaClass.classLoader, Method::class.java).toList()
        assertTrue(
            terminators.any { method ->
                method.declaringClass.name == "dev.zxilly.jenkins.cnb.status.CnbBuildMetadataLifecycleKt" &&
                    method.name == "shutdownCnbBuildMetadataReporting"
            },
            "The plugin shutdown hook must be present in Jenkins' runtime annotation index",
        )
    }

    private class AnnotatedChangeLogSet(
        run: Run<*, *>,
        browser: RepositoryBrowser<*>,
        private val change: GitChangeSet,
    ) : ChangeLogSet<GitChangeSet>(run, browser) {
        init {
            change.setParent(this)
        }

        override fun isEmptySet(): Boolean = false

        override fun iterator(): MutableIterator<GitChangeSet> = mutableListOf(change).iterator()
    }

    companion object {
        private const val COMMIT_SHA = "98b0c3b8d29219fb51bad8a4deafce9e04ea21e5"
    }
}
