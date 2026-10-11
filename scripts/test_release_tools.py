import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile


def load(filename):
    spec = importlib.util.spec_from_file_location(filename, Path(__file__).parent / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


select = load("select-update-release.py").select
verify = load("verify-release.py").verify


class ReleaseToolsTest(unittest.TestCase):
    def release(self, tag, prerelease=False, draft=False, assets=True):
        return {"tag_name": tag, "prerelease": prerelease, "draft": draft, "published_at": "2026-01-01T00:00:00Z",
                "assets": [{"name": "cnb.hpi", "browser_download_url": "https://example.org/" + tag}] if assets else []}

    def test_stable_selection_does_not_downgrade_or_accept_prereleases(self):
        releases = [[self.release("v1.9.0"), self.release("v1.10.0"), self.release("v9.0.0-rc.1"),
                     self.release("v2.0.0", prerelease=True), self.release("v3.0.0", draft=True)],
                    [self.release("v4.0.0", assets=False), self.release("v1.0.0")]]
        self.assertEqual("v1.10.0", select(releases)[0])
        self.assertEqual("v1.10.0+build.1", select([[self.release("v1.10.0+build.1")]])[0])
        with self.assertRaises(ValueError):
            select([[self.release("v1.0.0-rc.1")]])

    def test_manifest_unfolding_artifact_metadata_missing_and_extra_jars(self):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            pom = temp / "pom.xml"
            pom.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0"><properties>
              <jenkins.version>2.541.3</jenkins.version><maven.compiler.release>17</maven.compiler.release>
              <hpi.bundledArtifacts>httpclient5,kotlin-stdlib</hpi.bundledArtifacts>
              </properties></project>''', encoding="utf-8")

            def hpi(extra=False, missing=False, commit="abcdef"):
                path = temp / "cnb.hpi"
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nShort-Name: cnb\r\n"
                                     "Plugin-Version: 1.0.0\r\nJenkins-Version: 2.541.3\r\nJava-Version: 17\r\n"
                                     f"Plugin-ScmTag: v1.0.0\r\nImplementation-Build: {commit[:3]}\r\n {commit[3:]}\r\n\r\n")
                    archive.writestr("WEB-INF/lib/cnb.jar", b"")
                    for artifact in ["httpclient5"] + ([] if missing else ["kotlin-stdlib"]) + (["unexpected"] if extra else []):
                        jar_bytes = io.BytesIO()
                        with zipfile.ZipFile(jar_bytes, "w") as jar:
                            if artifact == "httpclient5":
                                jar.writestr(f"META-INF/maven/test/{artifact}/pom.properties", f"artifactId={artifact}\nversion=99.0\n")
                        archive.writestr(f"WEB-INF/lib/{artifact}-99.0.jar", jar_bytes.getvalue())
                return path

            verify(hpi(), pom, "v1.0.0", "abcdef")
            with self.assertRaises(ValueError):
                verify(hpi(extra=True), pom, "v1.0.0", "abcdef")
            with self.assertRaises(ValueError):
                verify(hpi(missing=True), pom, "v1.0.0", "abcdef")
            with self.assertRaises(ValueError):
                verify(hpi(commit="wrong"), pom, "v1.0.0", "abcdef")


if __name__ == "__main__":
    unittest.main()
