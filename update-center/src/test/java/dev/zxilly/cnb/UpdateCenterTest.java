package dev.zxilly.cnb;

import static org.junit.Assert.*;

import hudson.util.FormValidation;
import hudson.model.UpdateSite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import jenkins.util.JSONSignatureValidator;
import net.sf.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public class UpdateCenterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void jenkinsAcceptsSignatureAndRejectsTampering() throws Exception {
        Path temp = temporary.newFolder("signed").toPath();
        Path storePath = temp.resolve("test.p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "cnb", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Disposable CNB Test CA",
                "-ext", "bc=ca:true", "-validity", "2", "-storetype", "PKCS12", "-keystore", storePath.toString(),
                "-storepass", "disposable-test-password", "-noprompt").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(output, 0, process.waitFor());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(storePath)) { store.load(in, "disposable-test-password".toCharArray()); }
        var cert = (X509Certificate) store.getCertificate("cnb");
        Path hpi = hpi(temp, "1.2.3");
        JSONObject data = UpdateCenter.metadata(hpi, "1.2.3", "https://example.org/cnb.hpi");
        JSONObject plugin = data.getJSONObject("plugins").getJSONObject("cnb");
        assertEquals("2.541.3", plugin.getString("requiredCore"));
        assertEquals("17", plugin.getString("minimumJavaVersion"));
        assertEquals(2, plugin.getJSONArray("dependencies").size());
        assertFalse(plugin.getJSONArray("dependencies").getJSONObject(0).getBoolean("optional"));
        assertTrue(plugin.getJSONArray("dependencies").getJSONObject(1).getBoolean("optional"));
        UpdateSite.Plugin parsed = new UpdateSite("cnb", "https://example.org/update-center.json").new Plugin("cnb", plugin);
        assertEquals("1.2.3", parsed.version);
        assertEquals("2.541.3", parsed.requiredCore);
        assertEquals("5.8.0", parsed.dependencies.get("git"));
        assertEquals("1.0", parsed.optionalDependencies.get("optional-plugin"));
        assertEquals(plugin.getString("sha512"), parsed.getSha512());
        UpdateCenter.sign(data, (PrivateKey) store.getKey("cnb", "disposable-test-password".toCharArray()), cert);
        JSONSignatureValidator validator = new JSONSignatureValidator("CNB test") {
            @Override protected Set<TrustAnchor> loadTrustAnchors(CertificateFactory factory) {
                return Set.of(new TrustAnchor(cert, null));
            }
        };
        assertEquals(FormValidation.Kind.OK, validator.verifySignature(JSONObject.fromObject(data.toString())).kind);
        String java = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path site = temp.resolve("site");
        ProcessBuilder generate = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", java).toString(),
                "-cp", System.getProperty("java.class.path"), UpdateCenter.class.getName(), hpi.toString(), "1.2.3",
                "https://example.org/cnb.hpi", site.toString(), storePath.toString()).redirectErrorStream(true);
        generate.environment().put("UC_KEYSTORE_PASSWORD", "disposable-test-password");
        Process generated = generate.start();
        String generationOutput = new String(generated.getInputStream().readAllBytes());
        assertEquals(generationOutput, 0, generated.waitFor());
        String actual = Files.readString(site.resolve("update-center.actual.json"));
        assertEquals(FormValidation.Kind.OK, validator.verifySignature(JSONObject.fromObject(actual)).kind);
        assertEquals("updateCenter.post(\n" + actual.stripTrailing() + "\n);\n", Files.readString(site.resolve("update-center.json")));
        try (var in = Files.newInputStream(site.resolve("cnb-update-center.crt"))) {
            assertEquals(cert, CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        assertFalse(Files.readString(site.resolve("index.html")).contains("PRIVATE KEY"));
        JSONObject tampered = JSONObject.fromObject(data.toString());
        tampered.getJSONObject("plugins").getJSONObject("cnb").put("url", "https://example.org/tampered.hpi");
        assertEquals(FormValidation.Kind.ERROR, validator.verifySignature(tampered).kind);
        JSONSignatureValidator untrusted = new JSONSignatureValidator("Untrusted CNB test") {
            @Override protected Set<TrustAnchor> loadTrustAnchors(CertificateFactory factory) { return Set.of(); }
        };
        assertEquals(FormValidation.Kind.ERROR, untrusted.verifySignature(JSONObject.fromObject(data.toString())).kind);
    }

    @Test public void rejectsPrereleaseWrongIdentityAndInsecureDownload() throws Exception {
        Path temp = temporary.newFolder("invalid").toPath();
        Path hpi = hpi(temp, "1.2.3");
        assertThrows(IllegalArgumentException.class, () -> UpdateCenter.metadata(hpi, "1.2.3-rc.1", "https://example.org/cnb.hpi"));
        assertThrows(IllegalArgumentException.class, () -> UpdateCenter.metadata(hpi, "1.2.4", "https://example.org/cnb.hpi"));
        assertThrows(IllegalArgumentException.class, () -> UpdateCenter.metadata(hpi, "1.2.3", "http://example.org/cnb.hpi"));
    }

    private static Path hpi(Path directory, String version) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.putValue("Manifest-Version", "1.0");
        attrs.putValue("Short-Name", "cnb");
        attrs.putValue("Plugin-Version", version);
        attrs.putValue("Jenkins-Version", "2.541.3");
        attrs.putValue("Java-Version", "17");
        attrs.putValue("Plugin-Dependencies", "git:5.8.0,optional-plugin:1.0;resolution:=optional");
        Path path = directory.resolve("cnb.hpi");
        try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) { }
        return path;
    }
}
