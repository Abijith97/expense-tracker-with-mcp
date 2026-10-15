"""Re-sign a tested APK with the project's dedicated demo key, then verify it.

Private material is supplied by the branch-restricted GitHub environment, never
by Git or command-line arguments. This script never prints provider/tool errors.
"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def certificate_digest(output):
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$", output, re.MULTILINE)
    if len(digests) != 1:
        raise ValueError("Expected exactly one verified APK signer")
    return digests[0].lower()


def sign_apk(apksigner, source, destination, fingerprint_file, environment=None):
    environment = dict(os.environ if environment is None else environment)
    source, destination = Path(source).resolve(), Path(destination).resolve()
    if not source.is_file() or source == destination or destination.exists():
        raise ValueError("Use an existing input APK and a distinct, new output path")
    expected = Path(fingerprint_file).read_text(encoding="utf-8").strip().lower()
    if not re.fullmatch(r"[a-f0-9]{64}", expected):
        raise ValueError("Expected certificate fingerprint is missing or invalid")
    encoded = environment.pop("ANDROID_DEMO_KEYSTORE_BASE64", "")
    if not encoded or not environment.get("ANDROID_DEMO_KEYSTORE_PASSWORD"):
        raise ValueError("Dedicated demo signing secrets are required; refusing an ephemeral fallback")
    try:
        key_bytes = base64.b64decode(encoded, validate=True)
    except (ValueError, base64.binascii.Error):
        raise ValueError("Invalid signing secret encoding") from None
    if not key_bytes:
        raise ValueError("Empty signing key")
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Output is published only after signature verification and pin comparison.
    with tempfile.TemporaryDirectory(prefix="expense-demo-sign-") as temporary:
        directory = Path(temporary)
        os.chmod(directory, 0o700)
        keystore = directory / "demo.jks"
        with keystore.open("xb") as stream:
            os.chmod(keystore, 0o600)
            stream.write(key_bytes)
        candidate = directory / "signed.apk"
        try:
            subprocess.run([
                str(apksigner), "sign", "--ks", str(keystore), "--ks-key-alias", "expense-tracker-demo",
                "--ks-pass", "env:ANDROID_DEMO_KEYSTORE_PASSWORD",
                "--key-pass", "env:ANDROID_DEMO_KEYSTORE_PASSWORD",
                "--out", str(candidate), str(source),
            ], check=True, capture_output=True, text=True, env=environment)
            verification = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(candidate)],
                                          check=True, capture_output=True, text=True, env=environment)
        except (subprocess.SubprocessError, OSError):
            raise ValueError("APK signing or signature verification failed; no APK published") from None
        digest = certificate_digest(verification.stdout)
        if digest != expected:
            raise ValueError("APK certificate does not match the pinned project certificate")
        # No edits to the APK after signing. Copy exact verified bytes across filesystems.
        signed_bytes = candidate.read_bytes()
        with destination.open("xb") as stream:
            stream.write(signed_bytes)
    report = {"certificate_sha256": digest, "apk_sha256": hashlib.sha256(signed_bytes).hexdigest()}
    destination.with_suffix(".signing.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--fingerprint", default=str(Path(__file__).with_name("demo-certificate.sha256")))
    args = parser.parse_args()
    try:
        report = sign_apk(args.apksigner, args.input, args.output, args.fingerprint)
    except (ValueError, OSError) as error:
        parser.exit(1, "Demo signing failed: " + str(error) + "\n")
    print("Verified dedicated demo certificate SHA-256: " + report["certificate_sha256"])


if __name__ == "__main__":
    main()
