# Demo APK installation, updates and signing

The public [README](../../README.md) contains phone install/update instructions. Download the **expense-tracker-demo-apk** artifact from a successful main run, not the internal intermediate APK. GitHub artifact downloads require sign-in; artifacts expire after 14 days. The downloadable APK is a debuggable POC, not a Play Store release.

## Consistent project identity

The dedicated **expense-tracker-demo** key signs distributed APKs. It is not this PC's shared Android debug key. Its public certificate SHA-256 is pinned in [demo-certificate.sha256](../../android-companion/ci/demo-certificate.sha256). No private key or password belongs in Git.

CI first runs the offline Python tests and complete Android emulator suite using disposable runner debug signing. It then rebuilds the app without coverage and stages that APK as **expense-tracker-tested-apk-internal**, retained for one day. A separate signing job downloads those exact build outputs, re-signs them and verifies the signature against the pinned certificate before publishing **expense-tracker-demo-apk** with a public `.signing.json` certificate/APK hash report. It does not rebuild app code with signing secrets available. Re-signing changes the signature; the distributed APK is not byte-identical to the tested intermediate.

The signing job runs only after successful tests on **main** push/manual runs. GitHub environment **demo-signing** additionally allows only the **main branch**, not PR refs or other branches. The environment holds `ANDROID_DEMO_KEYSTORE_BASE64` and `ANDROID_DEMO_KEYSTORE_PASSWORD`; only the signing step receives their values. Pull requests still test/build, but cannot use this environment or publish the signed demo. Missing secrets, tool failure or a certificate mismatch fails closed—there is no random-key fallback. Keep repository write/admin access restricted: trusted code on main can use the environment.

## Update compatibility and versions

Updates need the same application ID and compatible certificate and cannot downgrade Android's version code. Distributed CI builds use `versionCode = 10000 + github.run_number`; workflow reruns keep the same code, while new runs increase it. Do not reset/replace the workflow's numbering with a lower range without planning a higher version-code base. Local debug builds remain separately signed development builds (`versionCode` 16 by default); do not mix them with the distributed installation.

The first dedicated-key APK **cannot directly update older locally signed or random-CI-signed installations**. Do not uninstall the user's current app or promise data migration: this POC has no export/import migration implemented. Uninstalling loses local captured copies, reviews, choices and remembered credentials; the phone's SMS inbox is separate. An existing user can keep their old install until a migration is designed. New testers should start with the dedicated-key artifact so later downloads update normally. No phone installation, uninstall or data clearing is part of this signing setup.

## Key custody and recovery

The initial maintainer backup is outside the repository at `C:\Users\unnia\.android\expense-tracker-demo-signing`, restricted by Windows ACLs to the owner and SYSTEM. It contains the keystore and its credentials. Keep a separate encrypted/offline backup; the local directory alone is not disaster recovery. GitHub secrets cannot be downloaded later, so losing the backup and secrets can prevent compatible future updates. The existing shared `debug.keystore` is unchanged.

[provision_demo_signing.py](../../android-companion/ci/provision_demo_signing.py) generates/reuses that dedicated backup and uploads it to an already main-only environment; it refuses an in-repository or incomplete backup. It never overwrites existing key material. On Windows, create/restrict the backup directory's ACL before running it. Do not run it with a new directory to rotate a key casually: a different certificate would break the current update identity. [sign_demo_apk.py](../../android-companion/ci/sign_demo_apk.py) handles temporary key decoding, signing, pin verification and cleanup. The private key does not enter Gradle caches or uploaded artifacts.

References: [Android app update requirements](https://developer.android.com/google/play/app-updates?hl=en), [APK signing/verification](https://developer.android.com/tools/apksigner), [GitHub environment branch restrictions](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).
