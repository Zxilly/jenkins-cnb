package dev.zxilly.cnb;

import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/** Generates a signed additional update site from one stable release HPI. */
public final class UpdateCenter {
    private UpdateCenter() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Usage: HPI VERSION DOWNLOAD_URL OUTPUT_DIR KEYSTORE.p12; set UC_KEYSTORE_PASSWORD");
        }
        String password = System.getenv("UC_KEYSTORE_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("UC_KEYSTORE_PASSWORD is required");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(Path.of(args[4]))) { store.load(in, password.toCharArray()); }
        var key = (PrivateKey) store.getKey("cnb", password.toCharArray());
        var cert = (X509Certificate) store.getCertificate("cnb");
        if (key == null || cert == null) throw new IllegalArgumentException("Keystore must contain alias cnb");
        JSONObject metadata = metadata(Path.of(args[0]), args[1], args[2]);
        sign(metadata, key, cert);
        Path output = Path.of(args[3]);
        Files.createDirectories(output);
        String json = metadata.toString(2);
        Files.writeString(output.resolve("update-center.actual.json"), json + "\n");
        Files.writeString(output.resolve("update-center.json"), "updateCenter.post(\n" + json + "\n);\n");
        String pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        Files.writeString(output.resolve("cnb-update-center.crt"), pem);
        String fingerprint = HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        Files.writeString(output.resolve("certificate-sha256.txt"), fingerprint + "\n");
        Files.writeString(output.resolve("index.html"), "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>CNB Update Center</title>"
                + "<h1>CNB Update Center</h1><p>CNB Jenkins 插件的签名更新站点。</p>"
                + "<p><a href=\"https://github.com/Zxilly/jenkins-cnb/blob/master/docs/update-center.md\">安装与配置 / Setup</a></p>"
                + "<p><a href=\"update-center.json\">update-center.json</a> · <a href=\"cnb-update-center.crt\">签名证书</a></p>"
                + "<p>证书 SHA-256: <code>" + fingerprint + "</code></p></html>\n");
    }

    static JSONObject metadata(Path hpi, String version, String downloadUrl) throws Exception {
        if (!version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?")) {
            throw new IllegalArgumentException("Update site only accepts stable SemVer releases");
        }
        URI url = URI.create(downloadUrl);
        if (!"https".equals(url.getScheme()) || url.getHost() == null) throw new IllegalArgumentException("HTTPS download URL required");
        Manifest manifest;
        try (ZipFile zip = new ZipFile(hpi.toFile())) {
            var entry = zip.getEntry("META-INF/MANIFEST.MF");
            if (entry == null) throw new IllegalArgumentException("HPI manifest missing");
            try (var in = zip.getInputStream(entry)) { manifest = new Manifest(in); }
        }
        var attrs = manifest.getMainAttributes();
        if (!"cnb".equals(attrs.getValue("Short-Name")) || !version.equals(attrs.getValue("Plugin-Version"))) {
            throw new IllegalArgumentException("HPI identity/version does not match selected release");
        }
        JSONObject plugin = new JSONObject();
        plugin.put("name", "cnb");
        plugin.put("title", "CNB Integration");
        plugin.put("version", version);
        plugin.put("url", downloadUrl);
        plugin.put("wiki", "https://github.com/Zxilly/jenkins-cnb");
        plugin.put("excerpt", "Connect Jenkins to CNB repositories, pull requests, and build events.");
        for (var field : new String[][]{{"Jenkins-Version", "requiredCore"}, {"Java-Version", "minimumJavaVersion"}}) {
            String value = attrs.getValue(field[0]);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field[0]);
            plugin.put(field[1], value);
        }
        JSONArray dependencies = new JSONArray();
        String dependencyList = attrs.getValue("Plugin-Dependencies");
        if (dependencyList != null && !dependencyList.isBlank()) {
            for (String dependency : dependencyList.split(",")) {
                String[] parts = dependency.trim().split(";", 2);
                String[] identity = parts[0].split(":", 2);
                if (identity.length != 2) throw new IllegalArgumentException("Invalid dependency: " + dependency);
                JSONObject item = new JSONObject();
                item.put("name", identity[0]);
                item.put("version", identity[1]);
                item.put("optional", parts.length == 2 && parts[1].equals("resolution:=optional"));
                dependencies.add(item);
            }
        }
        plugin.put("dependencies", dependencies);
        plugin.put("size", Files.size(hpi));
        byte[] bytes = Files.readAllBytes(hpi);
        for (String algorithm : new String[]{"SHA-1", "SHA-256", "SHA-512"}) {
            plugin.put(algorithm.toLowerCase().replace("-", ""), Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(bytes)));
        }
        JSONObject plugins = new JSONObject();
        plugins.put("cnb", plugin);
        JSONObject result = new JSONObject();
        result.put("updateCenterVersion", "1");
        result.put("id", "cnb");
        result.put("connectionCheckUrl", "https://github.com/");
        result.put("generationTimestamp", Instant.now().toString());
        result.put("plugins", plugins);
        result.put("warnings", new JSONArray());
        return result;
    }

    static void sign(JSONObject metadata, PrivateKey key, X509Certificate cert) throws Exception {
        cert.checkValidity();
        if (cert.getBasicConstraints() < 0) throw new IllegalArgumentException("Signing certificate must be a trust anchor (CA)");
        StringWriter canonical = new StringWriter();
        metadata.writeCanonical(canonical);
        byte[] bytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA512withRSA");
        signer.initSign(key);
        signer.update(bytes);
        byte[] signature = signer.sign();
        signer.initVerify(cert);
        signer.update(bytes);
        if (!signer.verify(signature)) throw new IllegalArgumentException("Signing key and certificate do not match");
        JSONObject block = new JSONObject();
        block.put("certificates", new String[]{Base64.getEncoder().encodeToString(cert.getEncoded())});
        block.put("correct_digest512", Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes)));
        block.put("correct_signature512", Base64.getEncoder().encodeToString(signature));
        metadata.put("signature", block);
    }
}
