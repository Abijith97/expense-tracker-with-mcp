"""One-time maintainer setup. Generate a dedicated key, back it up outside Git,
and upload it only to an already main-restricted GitHub signing environment.
Never prints private material. Existing backups are reused, never overwritten.
"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess

ENVIRONMENT = "demo-signing"
ALIAS = "expense-tracker-demo"


def gh(*args, data=None):
    return subprocess.check_output(["gh", *args], input=data, stderr=subprocess.PIPE)


def provision(backup, keytool, repository):
    backup = Path(backup).resolve()
    # Caller creates this directory with owner-only ACLs on Windows before use.
    if not backup.is_dir():
        raise ValueError("Create a private backup directory outside the repository first")
    repository_root = Path(__file__).resolve().parents[2]
    if backup.is_relative_to(repository_root):
        raise ValueError("Signing backups must stay outside the repository")
    prefix = "repos/" + repository + "/environments/" + ENVIRONMENT
    configuration = json.loads(gh("api", prefix))
    policy = configuration.get("deployment_branch_policy") or {}
    branches = json.loads(gh("api", prefix + "/deployment-branch-policies"))["branch_policies"]
    if policy != {"protected_branches": False, "custom_branch_policies": True} or [
        (branch["name"], branch["type"]) for branch in branches
    ] != [("main", "branch")]:
        raise ValueError("Signing environment must allow only the main branch")
    keystore, credentials = backup / "expense-tracker-demo.jks", backup / "credentials.json"
    if keystore.exists() != credentials.exists():
        raise ValueError("Incomplete signing backup; inspect it before retrying")
    if not credentials.exists():
        password = secrets.token_urlsafe(48)
        environment = dict(os.environ, EXPENSE_DEMO_NEW_PASSWORD=password)
        subprocess.run([
            str(keytool), "-genkeypair", "-keystore", str(keystore), "-storetype", "JKS",
            "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=Expense Tracker Demo, OU=POC, O=Expense Tracker",
            "-storepass:env", "EXPENSE_DEMO_NEW_PASSWORD", "-keypass:env", "EXPENSE_DEMO_NEW_PASSWORD", "-noprompt",
        ], check=True, capture_output=True, env=environment)
        with credentials.open("x", encoding="utf-8") as stream:
            os.chmod(credentials, 0o600)
            json.dump({"alias": ALIAS, "password": password}, stream)
        os.chmod(keystore, 0o600)
    password = json.loads(credentials.read_text(encoding="utf-8"))["password"]
    environment = dict(os.environ, EXPENSE_DEMO_NEW_PASSWORD=password)
    certificate = subprocess.check_output([
        str(keytool), "-exportcert", "-keystore", str(keystore), "-alias", ALIAS,
        "-storepass:env", "EXPENSE_DEMO_NEW_PASSWORD",
    ], env=environment, stderr=subprocess.PIPE)
    digest = hashlib.sha256(certificate).hexdigest()
    gh("secret", "set", "ANDROID_DEMO_KEYSTORE_BASE64", "--repo", repository, "--env", ENVIRONMENT,
       data=base64.b64encode(keystore.read_bytes()))
    gh("secret", "set", "ANDROID_DEMO_KEYSTORE_PASSWORD", "--repo", repository, "--env", ENVIRONMENT,
       data=password.encode("utf-8"))
    print("Dedicated signing key backed up outside Git; two environment secrets uploaded.")
    print("Public certificate SHA-256: " + digest)
    return digest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backup-dir", required=True)
    parser.add_argument("--keytool", required=True)
    parser.add_argument("--repo", required=True)
    arguments = parser.parse_args()
    try:
        provision(arguments.backup_dir, arguments.keytool, arguments.repo)
    except (ValueError, OSError, subprocess.SubprocessError):
        parser.exit(1, "Signing setup failed. Check the environment policy and private backup; no secret values logged.\n")
