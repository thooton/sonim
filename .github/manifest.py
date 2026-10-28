#!/usr/bin/env python3
"""Writes manifest.json for the "latest" release: everything the Updater needs.

    python3 .github/manifest.py <apks dir> <aapt2>

For each <app>.apk in the directory, read from the APK itself (so it can't
drift from the build files): versionName, versionCode, package name and
label; then its size and SHA-256, and what's new in it, from the newest
commits that touched the app's directory, leaving out those that changed
only its docs (git log, so CI needs the history: checkout with
fetch-depth 0).

A commit subject leads with the apps it changes ("Updater 1.2.4: drop
"more" from every row"). That lead is cut off, leaving the sentence ("Drop
"more" from every row."), and a version in it is kept beside the text.

Also writes versions.txt and packages.txt from the same data, only so that
phones still on an Updater older than 1.3.0 (which read those two files)
can update to one that reads the manifest.
"""

import datetime
import hashlib
import json
import os
import re
import subprocess
import sys

# Release names whose sources aren't in a directory of the same name.
DIRS = {"AppDrawer": "launcher"}
NOTES = 10
# Files in an app's directory that aren't built into its APK (SPEC.md,
# AGENTS.md, mockups): a commit touching only these isn't what's new in it.
DOCS = ["*.md", "mockups"]

NAME = r"[A-Za-z][\w-]*(?: v?\d+(?:\.\d+)*)?"
LEAD = re.compile(r"^(%s(?:\s*(?:,|&|/|\band\b)\s*%s)*):\s*(.+)$" % (NAME, NAME))
VERSION = re.compile(r"^%s$" % NAME)


def badging(aapt2, apk):
    out = subprocess.run([aapt2, "dump", "badging", apk], check=True,
                         capture_output=True, text=True).stdout
    first = out.splitlines()[0]
    package = re.search(r"name='([^']*)'", first)
    code = re.search(r"versionCode='([^']*)'", first)
    version = re.search(r"versionName='([^']*)'", first)
    label = re.search(r"^application-label:'([^']*)'", out, re.M)
    if not (package and code and version):
        sys.exit("No version or package in %s: %s" % (apk, first))
    return {
        "package": package.group(1),
        "versionCode": int(code.group(1)),
        "version": version.group(1),
        "label": label.group(1) if label else None,
    }


def note(app, label, sha, time, subject):
    """One commit as the Updater shows it."""
    out = {"commit": sha, "time": time}
    lead = LEAD.match(subject)
    text = lead.group(2).strip() if lead else subject
    text = text[:1].upper() + text[1:]
    if text and text[-1:] not in ".!?…":
        text += "."
    out["text"] = text
    if lead:
        names = {app.lower(), (label or "").lower()}
        for part in re.split(r"\s*(?:,|&|/|\band\b)\s*", lead.group(1)):
            words = part.split(" ")
            if len(words) == 2 and words[0].lower() in names:
                out["version"] = words[1].lstrip("v")
    return out


def notes(app, label):
    directory = DIRS.get(app, app)
    if not os.path.isdir(directory):
        return []
    log = subprocess.run(
        ["git", "log", "--no-merges", "-n", str(NOTES), "--format=%h%x00%cI%x00%s", "--", directory]
        + [":(exclude)%s/%s" % (directory, docs) for docs in DOCS],
        check=True, capture_output=True, text=True).stdout
    out = []
    for line in log.splitlines():
        sha, time, subject = line.split("\0", 2)
        out.append(note(app, label, sha, time, subject.strip()))
    return out


def main():
    apks, aapt2 = sys.argv[1], sys.argv[2]
    apps = {}
    for file in sorted(os.listdir(apks), key=str.lower):
        if not file.endswith(".apk"):
            continue
        app = file[:-4]
        path = os.path.join(apks, file)
        entry = badging(aapt2, path)
        with open(path, "rb") as f:
            entry["sha256"] = hashlib.sha256(f.read()).hexdigest()
        entry["size"] = os.path.getsize(path)
        entry["apk"] = file
        entry["notes"] = notes(app, entry["label"])
        apps[app] = entry

    commit = subprocess.run(["git", "rev-parse", "HEAD"], check=True,
                            capture_output=True, text=True).stdout.strip()
    manifest = {
        "schema": 1,
        "built": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "commit": commit,
        "apps": apps,
    }
    with open(os.path.join(apks, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")
    with open(os.path.join(apks, "versions.txt"), "w") as f:
        f.writelines("%s = %s\n" % (app, e["version"]) for app, e in apps.items())
    with open(os.path.join(apks, "packages.txt"), "w") as f:
        f.writelines("%s = %s\n" % (app, e["package"]) for app, e in apps.items())


if __name__ == "__main__":
    main()
