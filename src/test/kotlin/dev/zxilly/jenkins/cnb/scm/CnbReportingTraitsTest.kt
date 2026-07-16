package dev.zxilly.jenkins.cnb.scm

import hudson.util.FormValidation
import io.jenkins.plugins.casc.ConfigurationContext
import io.jenkins.plugins.casc.Configurator
import io.jenkins.plugins.casc.ConfiguratorRegistry
import io.jenkins.plugins.casc.misc.Util
import io.jenkins.plugins.casc.model.Mapping
import jenkins.branch.BranchSource
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbReportingTraitsTest {
    @Test
    fun `reporting context is normalized and strongly validated`() {
        assertEquals("持续集成/jenkins.release", CnbReportingContextTrait("  持续集成/jenkins.release  ").context)
        assertEquals("café", CnbReportingContextTrait("cafe\u0301").context)
        assertThrows(IllegalArgumentException::class.java) { CnbReportingContextTrait("   ") }
        assertThrows(IllegalArgumentException::class.java) {
            CnbReportingContextTrait("x".repeat(CnbReportingContextValidator.MAX_LENGTH + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { CnbReportingContextTrait("line\nbreak") }
        assertThrows(IllegalArgumentException::class.java) { CnbReportingContextTrait("spoof\u202econtext") }
        assertThrows(IllegalArgumentException::class.java) { CnbReportingContextTrait("private\ue000context") }

        val descriptor = CnbReportingContextTrait.DescriptorImpl()
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckContext(null, "ci/jenkins").kind)
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckContext(null, "\u0000").kind)
        assertEquals(CnbSCMSource::class.java, descriptor.sourceClass)
        assertEquals(CnbSCMSourceContext::class.java, descriptor.contextClass)
        assertArrayEquals(
            arrayOf("cnbReportingContext"),
            CnbReportingContextTrait.DescriptorImpl::class.java.getAnnotation(Symbol::class.java).value,
        )

        val skipDescriptor = CnbSkipReportingTrait.DescriptorImpl()
        assertEquals(CnbSCMSource::class.java, skipDescriptor.sourceClass)
        assertEquals(CnbSCMSourceContext::class.java, skipDescriptor.contextClass)
        assertArrayEquals(
            arrayOf("cnbSkipReporting"),
            CnbSkipReportingTrait.DescriptorImpl::class.java.getAnnotation(Symbol::class.java).value,
        )
    }

    @Test
    fun `branch source reporting traits survive Jenkins configuration roundtrip`(jenkins: JenkinsRule) {
        val project =
            jenkins.jenkins.createProject(
                WorkflowMultiBranchProject::class.java,
                "reporting-traits-roundtrip",
            )
        val source = CnbSCMSource("cnb-cool", "team/repository")
        source.setTraits(
            listOf(
                CnbBranchDiscoveryTrait(3),
                CnbReportingContextTrait("ci/jenkins/production"),
                CnbSkipReportingTrait(),
            ),
        )
        project.sourcesList.add(BranchSource(source))

        jenkins.configRoundtrip(project)

        val reloaded = project.sourcesList.single().source as CnbSCMSource
        assertEquals(
            "ci/jenkins/production",
            reloaded.traits
                .filterIsInstance<CnbReportingContextTrait>()
                .single()
                .context,
        )
        assertEquals(1, reloaded.traits.filterIsInstance<CnbSkipReportingTrait>().size)
    }

    @Test
    fun `reporting traits support JCasC import and export`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.pluginManager.getPlugin("configuration-as-code") != null)
        val context = ConfigurationContext(ConfiguratorRegistry.get())
        val reportingConfigurator: Configurator<CnbReportingContextTrait> =
            context.lookupOrFail(CnbReportingContextTrait::class.java)
        val imported =
            reportingConfigurator.configure(
                Mapping().apply { put("context", "ci/jcasc") },
                context,
            )

        assertEquals("ci/jcasc", imported.context)
        val exported = Util.toYamlString(reportingConfigurator.describe(imported, context))
        assertTrue(exported.contains("context"), exported)
        assertTrue(exported.contains("ci/jcasc"), exported)

        val skipConfigurator: Configurator<CnbSkipReportingTrait> =
            context.lookupOrFail(CnbSkipReportingTrait::class.java)
        assertInstanceOf(CnbSkipReportingTrait::class.java, skipConfigurator.configure(Mapping.EMPTY, context))
    }
}
