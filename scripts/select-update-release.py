"""Select the highest stable SemVer HPI release, independently of GitHub's latest marker."""
import json
import re
import sys


def select(pages):
    candidates = []
    for page in pages:
        for release in page:
            version = re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?", release["tag_name"])
            if release["draft"] or release["prerelease"] or not version:
                continue
            assets = [a for a in release["assets"] if a["name"] == "cnb.hpi"]
            if len(assets) == 1:
                candidates.append((tuple(map(int, version.groups()[:3])), release["published_at"], release, assets[0]))
    if not candidates:
        raise ValueError("No stable release with cnb.hpi found; publish a stable release first")
    _, _, release, asset = max(candidates, key=lambda item: item[:2])
    return release["tag_name"], asset["browser_download_url"]


if __name__ == "__main__":
    tag, url = select(json.load(open(sys.argv[1], encoding="utf-8")))
    with open(sys.argv[2], "a", encoding="utf-8") as output:
        output.write(f"tag={tag}\nurl={url}\n")
