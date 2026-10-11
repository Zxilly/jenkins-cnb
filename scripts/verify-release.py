"""Validate release identity and bundled artifacts against the POM and each JAR's metadata."""
import io
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile


def manifest(data):
    text = data.decode("utf-8").replace("\r\n", "\n").replace("\n ", "")
    return dict(line.split(": ", 1) for line in text.split("\n\n", 1)[0].splitlines() if ": " in line)


def verify(hpi, pom, version, commit):
    root = ET.parse(pom).getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    props = {item.tag.split("}")[-1]: item.text for item in root.find("m:properties", ns)}
    expected = set(props["hpi.bundledArtifacts"].split(","))
    with zipfile.ZipFile(hpi) as archive:
        attrs = manifest(archive.read("META-INF/MANIFEST.MF"))
        for key, value in {"Short-Name": "cnb", "Plugin-Version": version.removeprefix("v"),
                           "Jenkins-Version": props["jenkins.version"], "Java-Version": props["maven.compiler.release"],
                           "Plugin-ScmTag": version, "Implementation-Build": commit}.items():
            if attrs.get(key) != value:
                raise ValueError(f"{key}: expected {value}, found {attrs.get(key)}")
        found = set()
        for name in archive.namelist():
            if not name.startswith("WEB-INF/lib/") or not name.endswith(".jar"):
                continue
            if name == "WEB-INF/lib/cnb.jar":
                continue
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as jar:
                artifact_ids = set()
                for metadata in jar.namelist():
                    if metadata.startswith("META-INF/maven/") and metadata.endswith("/pom.properties"):
                        for line in jar.read(metadata).decode("utf-8").splitlines():
                            if line.startswith("artifactId="):
                                artifact_ids.add(line.split("=", 1)[1])
            # Kotlin libraries can omit Maven metadata. Match a full declared artifact ID followed by a numeric version.
            matches = artifact_ids & expected
            if not matches:
                basename = Path(name).name
                matches = {artifact for artifact in expected if basename.startswith(artifact + "-")
                           and basename[len(artifact) + 1:len(artifact) + 2].isdigit()}
            if len(matches) != 1 or matches & found:
                raise ValueError(f"Unexpected or duplicate bundled JAR: {name} ({matches})")
            found.update(matches)
        if found != expected:
            raise ValueError(f"Missing bundled artifacts: {sorted(expected - found)}")
        if "WEB-INF/lib/cnb.jar" not in archive.namelist():
            raise ValueError("Plugin implementation JAR missing")


if __name__ == "__main__":
    verify(sys.argv[1], sys.argv[2], os.environ["VERSION"], os.environ["TAG_COMMIT"])
    print("HPI identity and bundled artifacts verified")
