"""Run the full Android suite, injecting synthetic SMS at test readiness markers.

Requires a disposable emulator, never a physical phone. No OpenAI credentials.
The instrumented test replaces HTTP before requesting each emulated SMS.
"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import uuid

SCENARIOS = {"happy", "other", "timeout", "retry", "malformed", "category_failure", "uncertain"}
APP_ID = "com.abijith.expensetracker"
MARKER = "SMS_SYSTEM_READY|"


def scenario_from_line(line, token):
    """Ignore stale markers and reject unknown requests; do not interpret shell text."""
    if MARKER not in line:
        return None
    fields = line.split(MARKER, 1)[1].strip().split("|")
    if len(fields) != 2 or fields[0] != token:
        return None
    if fields[1] not in SCENARIOS:
        raise ValueError("Unknown SMS system-test scenario")
    return fields[1]


def sms_body(scenario):
    if scenario not in SCENARIOS:
        raise ValueError("Unknown SMS system-test scenario")
    base = "Get Rs.300 cashback on your next recharge." if scenario == "other" else "Sent Rs.23.00 to BMTC."
    return base + " Test:" + scenario + "."


def adb(serial, *arguments):
    return subprocess.check_output(["adb", "-s", serial, *arguments], text=True, timeout=30).strip()


def require_emulator(serial):
    if not re.fullmatch(r"emulator-\d+", serial) or adb(serial, "shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("System tests require an explicitly selected disposable emulator, never a phone")


def require_only_device(serial):
    # AGP connected tests can discover more than ANDROID_SERIAL. Never let this
    # reset-capable suite run while a phone or a second emulator is connected.
    listing = subprocess.check_output(["adb", "devices"], text=True, timeout=30)
    devices = [line.split() for line in listing.splitlines()[1:] if line.strip()]
    if devices != [[serial, "device"]]:
        raise RuntimeError("Disconnect other devices; only the disposable emulator may be connected")


def reset_installed_app(serial):
    # `pm path` exits 1 on a fresh emulator. Listing is successful even when the
    # app is absent; exact matching avoids resetting a similarly named package.
    packages = adb(serial, "shell", "pm", "list", "packages", "--user", "0", APP_ID).splitlines()
    if "package:" + APP_ID in packages:
        if adb(serial, "shell", "pm", "clear", APP_ID) != "Success":
            raise RuntimeError("Could not reset test app")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--allow-disposable-emulator-reset", action="store_true",
                        help="Allow clearing only this app's data on the selected emulator")
    parser.add_argument("--coverage", action="store_true")
    args = parser.parse_args()
    require_emulator(args.serial)
    require_only_device(args.serial)
    if not args.allow_disposable_emulator_reset:
        parser.error("Explicit --allow-disposable-emulator-reset acknowledgement is required")
    root = Path(__file__).resolve().parents[1]
    token = uuid.uuid4().hex
    output = root / "app/build/reports/system-tests"
    output.mkdir(parents=True, exist_ok=True)
    errors = []
    injected = set()
    # Clear only this POC's data on a validated disposable emulator, if installed.
    reset_installed_app(args.serial)
    logcat = subprocess.Popen(["adb", "-s", args.serial, "logcat", "-v", "raw", "-T", "1",
                              "SmsSystemTest:I", "*:S"], stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True)

    def inject_when_ready():
        try:
            for line in logcat.stdout:
                scenario = scenario_from_line(line, token)
                if scenario is None:
                    continue
                if scenario in injected:
                    raise RuntimeError("Duplicate readiness marker")
                reply = adb(args.serial, "emu", "sms", "send", "5550100", sms_body(scenario))
                if "KO" in reply or "OK" not in reply:
                    raise RuntimeError("Emulator rejected SMS injection")
                injected.add(scenario)
                print("Injected synthetic SMS scenario: " + scenario, flush=True)
        except Exception as error:
            errors.append(type(error).__name__ + ": " + str(error))

    watcher = threading.Thread(target=inject_when_ready, daemon=True)
    watcher.start()
    command = ([str(root / "gradlew.bat")] if os.name == "nt" else ["bash", "./gradlew"])
    if args.coverage:
        command += ["-Pcoverage=true", ":app:createDebugAndroidTestCoverageReport"]
    else:
        command += [":app:connectedDebugAndroidTest"]
    command += ["-Pandroid.testInstrumentationRunnerArguments.smsSystemRunToken=" + token, "--console=plain"]
    environment = dict(os.environ, ANDROID_SERIAL=args.serial)
    try:
        result = subprocess.run(command, cwd=root, env=environment, timeout=1800)
    finally:
        logcat.terminate()
        try:
            logcat.wait(timeout=10)
        except subprocess.TimeoutExpired:
            logcat.kill()
            logcat.wait()
        watcher.join(timeout=35)
        if watcher.is_alive():
            errors.append("SMS injector did not stop")
        # Useful even when Gradle/test assertions fail. No keys or SMS bodies logged.
        (output / "sms-injection.json").write_text(json.dumps({
            "expected": sorted(SCENARIOS), "injected": sorted(injected), "errors": errors,
        }, indent=2), encoding="utf-8")
    missing = SCENARIOS - injected
    if errors or missing:
        print("SMS harness failed: " + repr(errors) + "; missing scenarios: " + repr(sorted(missing)), file=sys.stderr)
    return result.returncode or (1 if errors or missing else 0)


if __name__ == "__main__":
    sys.exit(main())
