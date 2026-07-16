package dev.zxilly.jenkins.cnb.scm

import hudson.util.FormValidation
import io.jenkins.plugins.casc.ConfigurationContext
import io.jenkins.plugins.casc.Configurator
import io.jenkins.plugins.casc.ConfiguratorException
import io.jenkins.plugins.casc.ConfiguratorRegistry
import io.jenkins.plugins.casc.misc.Util
import io.jenkins.plugins.casc.model.Mapping
import jenkins.branch.BranchSource
import jenkins.model.Jenkins
import jenkins.scm.api.SCMHeadObserver
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbPullRequestCommentTriggerTraitTest {
    @Test
    fun `trait enables an explicit RE2 comment and member policy`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val trait = CnbPullRequestCommentTriggerTrait("rebuild(?:\\s+please)?")
        val context = CnbSCMSourceContext(null, SCMHeadObserver.none()).withTraits(listOf(trait))

        assertEquals("rebuild(?:\\s+please)?", trait.getCommentPattern())
        assertEquals("Developer", trait.getMinimumRole())
        assertEquals(trait.getCommentPattern(), requireNotNull(context.pullRequestCommentPolicy).pattern)
        assertEquals(trait.getMinimumRole(), requireNotNull(context.pullRequestCommentPolicy).minimumRole)
        assertThrows(IllegalArgumentException::class.java) {
            CnbPullRequestCommentTriggerTrait("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CnbPullRequestCommentTriggerTrait("(?=rebuild)")
        }
    }

    @Test
    fun `descriptor is scoped to CNB sources and has a stable symbol`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val descriptor = CnbPullRequestCommentTriggerTrait.DescriptorImpl()

        assertEquals(CnbSCMSource::class.java, descriptor.sourceClass)
        assertEquals(CnbSCMSourceContext::class.java, descriptor.contextClass)
        assertArrayEquals(
            arrayOf("cnbPullRequestCommentTrigger"),
            CnbPullRequestCommentTriggerTrait.DescriptorImpl::class.java.getAnnotation(Symbol::class.java).value,
        )
        assertEquals(listOf("Developer", "Reporter", "Master", "Owner"), descriptor.doFillMinimumRoleItems().map { it.value })
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckCommentPattern(null, "rebuild").kind)
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckCommentPattern(null, "(?=rebuild)").kind)
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckMinimumRole(null, "Owner").kind)
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckMinimumRole(null, "Guest").kind)
    }

    @Test
    fun `trait survives Jenkins configuration roundtrip`(jenkins: JenkinsRule) {
        val project =
            jenkins.jenkins.createProject(
                WorkflowMultiBranchProject::class.java,
                "comment-trait-roundtrip",
            )
        val source = CnbSCMSource("cnb-cool", "team/repository")
        source.setTraits(
            listOf(
                CnbOriginPullRequestDiscoveryTrait(CnbOriginPullRequestDiscoveryTrait.HEAD),
                CnbPullRequestCommentTriggerTrait("rebuild").apply { setMinimumRole("Reporter") },
            ),
        )
        project.sourcesList.add(BranchSource(source))

        jenkins.configRoundtrip(project)

        val restored =
            (project.sourcesList.single().source as CnbSCMSource)
                .traits
                .filterIsInstance<CnbPullRequestCommentTriggerTrait>()
                .single()
        assertEquals("rebuild", restored.getCommentPattern())
        assertEquals("Reporter", restored.getMinimumRole())
    }

    @Test
    fun `default role survives Jenkins configuration roundtrip`(jenkins: JenkinsRule) {
        val project =
            jenkins.jenkins.createProject(
                WorkflowMultiBranchProject::class.java,
                "comment-trait-default-roundtrip",
            )
        val source = CnbSCMSource("cnb-cool", "team/repository")
        source.setTraits(listOf(CnbPullRequestCommentTriggerTrait("rebuild")))
        project.sourcesList.add(BranchSource(source))

        jenkins.configRoundtrip(project)

        val restored =
            (project.sourcesList.single().source as CnbSCMSource)
                .traits
                .filterIsInstance<CnbPullRequestCommentTriggerTrait>()
                .single()
        assertEquals("Developer", restored.getMinimumRole())
    }

    @Test
    fun `trait supports JCasC import and export`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.pluginManager.getPlugin("configuration-as-code") != null)
        val context = ConfigurationContext(ConfiguratorRegistry.get())
        val configurator: Configurator<CnbPullRequestCommentTriggerTrait> =
            context.lookupOrFail(CnbPullRequestCommentTriggerTrait::class.java)
        val imported =
            configurator.configure(
                Mapping().apply {
                    put("commentPattern", "rebuild")
                    put("minimumRole", "Reporter")
                },
                context,
            )

        assertEquals("rebuild", imported.getCommentPattern())
        assertEquals("Reporter", imported.getMinimumRole())
        val exported = Util.toYamlString(configurator.describe(imported, context))
        assertTrue(exported.contains("commentPattern"), exported)
        assertTrue(exported.contains("minimumRole"), exported)
        assertTrue(exported.contains("Reporter"), exported)
        assertThrows(ConfiguratorException::class.java) {
            configurator.configure(
                Mapping().apply {
                    put("commentPattern", "(?=rebuild)")
                    put("minimumRole", "Guest")
                },
                context,
            )
        }

        val omittedRole =
            configurator.configure(
                Mapping().apply { put("commentPattern", "rebuild") },
                context,
            )
        assertEquals("Developer", omittedRole.getMinimumRole())
    }

    @Test
    fun `invalid legacy role disables the comment trigger`(jenkins: JenkinsRule) {
        assertTrue(jenkins.jenkins.isUseSecurity.not())
        val trait = CnbPullRequestCommentTriggerTrait("rebuild")
        CnbPullRequestCommentTriggerTrait::class.java
            .getDeclaredField("minimumRole")
            .apply { isAccessible = true }
            .set(trait, "Guest")

        val restored =
            Jenkins.XSTREAM2.fromXML(
                Jenkins.XSTREAM2.toXML(trait),
            ) as CnbPullRequestCommentTriggerTrait
        val context = CnbSCMSourceContext(null, SCMHeadObserver.none()).withTraits(listOf(restored))

        assertEquals("", restored.getCommentPattern())
        assertEquals("Developer", restored.getMinimumRole())
        assertNull(context.pullRequestCommentPolicy)
    }
}
