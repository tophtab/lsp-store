#!/usr/bin/env python3
"""Build the variant-selection regression fixture from downloaded module JSONs.

Input: a directory containing ``<packageName>.json`` files as served by
``https://modules.lsposed.org/module/<packageName>.json`` (the same files the
app consumes). Output: a compact corpus with, per module, every multi-APK
release plus the latest release (deduplicated).

Usage:
    python3 tools/build_variant_fixture.py /tmp/mods \
        app/src/test/resources/variant-corpus.json
"""
import json
import os
import sys


def apks(release):
    return [
        [a.get("name"), a.get("size", 0), a.get("downloadCount", 0)]
        for a in release.get("releaseAssets") or []
        if (a.get("name") or "").lower().endswith(".apk")
    ]


def build(directory):
    corpus = []
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as handle:
            module = json.load(handle)
        package = name[: -len(".json")]
        releases = module.get("releases") or []
        if not releases:
            continue
        latest = next((r for r in releases if r.get("isLatest")), releases[0])
        keep = []
        for release in releases:
            assets = apks(release)
            if len(assets) > 1 or release is latest:
                keep.append(
                    {
                        "tag": release.get("tagName"),
                        "latest": bool(release.get("isLatest")),
                        "apks": assets,
                    }
                )
        corpus.append(
            {
                "pkg": package,
                "repoLatest": module.get("latestRelease"),
                "releases": keep,
            }
        )
    return corpus


def main():
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[-1])
        return 1
    corpus = build(sys.argv[1])
    payload = json.dumps(corpus, separators=(",", ":"), ensure_ascii=False)
    with open(sys.argv[2], "w", encoding="utf-8") as handle:
        handle.write(payload)
    multi = sum(1 for m in corpus for r in m["releases"] if len(r["apks"]) > 1)
    print(
        f"wrote {sys.argv[2]}: {len(corpus)} modules, "
        f"{multi} multi-APK releases, {len(payload) / 1e6:.2f} MB"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
