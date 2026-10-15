"""Publish verified CI output as a public, direct-download demo release.

Each run gets its own tag. Assets are never overwritten. A partially uploaded
draft can be resumed; the latest download changes only after both assets exist.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

APK_NAME = "expense-tracker.apk"
REPORT_NAME = "expense-tracker.signing.json"


def gh(*arguments):
    return subprocess.run(["gh", *arguments], check=True, capture_output=True, text=True).stdout


def release(repository, suffix):
    if suffix.startswith("tags/"):
        # The by-tag REST endpoint is for published releases. Listing with this
        # job's write token also includes drafts, including interrupted uploads.
        pages = json.loads(gh("api", "--paginate", "--slurp",
                              "repos/" + repository + "/releases?per_page=100"))
        return next((entry for page in pages for entry in page if entry["tag_name"] == suffix[5:]), None)
    try:
        return json.loads(gh("api", "repos/" + repository + "/releases/" + suffix))
    except subprocess.CalledProcessError as error:
        if "HTTP 404" in (error.stderr or ""):
            return None
        raise


def validate_bundle(apk, fingerprint):
    apk = Path(apk)
    report = apk.with_suffix(".signing.json")
    metadata = json.loads(report.read_text(encoding="utf-8"))
    expected_certificate = Path(fingerprint).read_text(encoding="utf-8").strip().lower()
    if not re.fullmatch(r"[a-f0-9]{64}", expected_certificate):
        raise ValueError("Invalid pinned certificate")
    if metadata.get("certificate_sha256") != expected_certificate:
        raise ValueError("Certificate report does not match project pin")
    if metadata.get("apk_sha256") != hashlib.sha256(apk.read_bytes()).hexdigest():
        raise ValueError("APK does not match verified signing report")
    return report


def assert_release_assets(existing, expected, commit):
    if existing["target_commitish"] != commit:
        raise ValueError("Existing demo tag targets a different commit")
    assets = {asset["name"]: asset for asset in existing["assets"]}
    for name, digest in expected.items():
        if name not in assets or assets[name].get("digest") != "sha256:" + digest:
            raise ValueError("Release asset missing or checksum mismatch; refusing overwrite")


def publish(repository, run_number, commit, run_id, apk, fingerprint):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid repository")
    if run_number < 1 or not re.fullmatch(r"[a-f0-9]{40}", commit) or not str(run_id).isdigit():
        raise ValueError("Invalid CI identity")
    apk = Path(apk)
    report = validate_bundle(apk, fingerprint)
    latest = release(repository, "latest")
    latest_number = re.fullmatch(r"android-demo-(\d+)", latest["tag_name"]) if latest else None
    if latest_number and int(latest_number[1]) > run_number:
        return "A newer verified demo is already published; latest download unchanged."
    tag = "android-demo-" + str(run_number)
    existing = release(repository, "tags/" + tag)
    expected = {APK_NAME: hashlib.sha256(apk.read_bytes()).hexdigest(),
                REPORT_NAME: hashlib.sha256(report.read_bytes()).hexdigest()}
    if existing and not existing["draft"]:
        assert_release_assets(existing, expected, commit)
        return "Identical demo release already published; assets unchanged."
    if existing and existing["target_commitish"] != commit:
        raise ValueError("Existing draft targets a different commit")
    if not existing:
        gh("release", "create", tag, "--repo", repository, "--target", commit,
           "--draft", "--title", "Android demo build " + str(run_number))
    # Check an existing draft before resuming. Never delete/re-upload assets.
    existing_assets = {asset["name"]: asset for asset in (existing or {}).get("assets", [])}
    with tempfile.TemporaryDirectory(prefix="expense-demo-release-") as directory:
        for name, source in ((APK_NAME, apk), (REPORT_NAME, report)):
            if name in existing_assets:
                if existing_assets[name].get("digest") != "sha256:" + expected[name]:
                    raise ValueError("Draft has a different asset; refusing overwrite")
                continue
            destination = Path(directory) / name
            shutil.copyfile(source, destination)
            gh("release", "upload", tag, str(destination), "--repo", repository)
    uploaded = release(repository, "tags/" + tag)
    if uploaded is None:
        raise ValueError("Uploaded draft could not be verified")
    assert_release_assets(uploaded, expected, commit)
    notes = ("Android learning POC / debuggable demo, not a production release.\n\n"
             "Download expense-tracker.apk on your phone to install or update. No ZIP extraction needed.\n\n"
             "Tests, lint, builds, and dedicated-certificate signing passed before publication.\n"
             "Source: " + commit + "\nCI: https://github.com/" + repository + "/actions/runs/" + str(run_id))
    gh("release", "edit", tag, "--repo", repository, "--draft=false", "--latest", "--notes", notes)
    return "Published verified demo: " + tag


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--run-number", required=True, type=int)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--fingerprint", default=str(Path(__file__).with_name("demo-certificate.sha256")))
    args = parser.parse_args()
    print(publish(args.repo, args.run_number, args.commit, args.run_id, args.apk, args.fingerprint))
