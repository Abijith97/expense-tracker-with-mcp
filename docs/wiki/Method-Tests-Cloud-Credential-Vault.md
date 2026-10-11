# Method tests: encrypted credential vault

[Index and evidence legend](Home.md) | [Cloud settings](Method-Tests-Cloud-Settings.md)

Source: [CloudCredentialVault.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/CloudCredentialVault.kt). Evidence: [CloudPersistenceInstrumentedTest.kt](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CloudPersistenceInstrumentedTest.kt). These tests use real Android Keystore with isolated files and unique aliases, not real API keys.

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| CV-01 `save()` | AES-GCM encrypt credentials with fresh IV and atomically write envelope | **A/I:** saved ciphertext does not contain synthetic key strings; recreated settings recover keys/model/session/consent. **G:** repeated-save IV uniqueness and I/O failure atomic recovery checks. |
| CV-02 `load()` | Bounded authenticated decrypt; require consent and existing key | **A:** corrupt ciphertext and missing key fail closed; absent memory-only credentials do not restore. **G:** oversized envelope, wrong version/IV, tampered associated-data and backup recovery matrix. |
| CV-03 `clear()` | Remove envelope and attempt orphan-key cleanup | **A/I:** forget/disable and memory-only transitions prevent restart recovery; test teardown cleans only isolated files/alias. **G:** explicitly simulate deletion failure and orphan-key cleanup exception. |
| CV-04 `keyStore()` (private) | Open AndroidKeyStore provider | **I:** real save/load/clear tests use provider. Assert through public encryption/recovery behavior, not a mock claiming encryption. |
| CV-05 `getOrCreateKey()` (private) | Reuse existing AES key or create 256-bit randomized GCM key on write | **I:** first save/restoration exercises successful path. **G:** two writes retain decryptability; read with missing key never creates a replacement. Existing missing-key test establishes fail-closed recovery, not every key-generation setting. |

`ciphertextRestoresKeysConsentModelAndTheSameQueuedSessionWithoutAnActivity` and `missingEncryptionKeyNeverGeneratesAReplacementOrAuthorizesUploads` are concrete existing checks. Encryption here protects stored keys, not captured SMS text, and does not protect against a compromised app/process. Never delete the user's real Keystore alias to simulate failure.
