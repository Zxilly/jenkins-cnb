package dev.zxilly.jenkins.cnb

import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.credentials.CnbTokenCredentials
import dev.zxilly.jenkins.cnb.status.CnbBuildMetadataReconciliationWork
import hudson.init.Terminator
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
}
