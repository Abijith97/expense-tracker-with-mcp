# Method tests: cloud settings and sessions

[Index and evidence legend](Home.md) | [Credential vault](Method-Tests-Cloud-Credential-Vault.md)

Source: [CloudClassificationSettings.kt](../../android-companion/app/src/main/java/com/abijith/expensetracker/CloudClassificationSettings.kt). `CloudSettings` is the isolated-testable lifecycle; `CloudClassificationSettings` is the app-wide facade. Evidence: [CloudKeys](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CloudKeysInstrumentedTest.kt), [CloudPersistence](../../android-companion/app/src/androidTest/java/com/abijith/expensetracker/CloudPersistenceInstrumentedTest.kt).

| Case / method | Project purpose | Test contract / evidence |
| --- | --- | --- |
| CS-01 `CloudSettings.restore()` | Restore authenticated encrypted credentials/consent | **A:** same keys/model/session restored in reconstructed instance; corrupt/missing encryption key fails closed with safe error. **G:** decrypted malformed session/model boundary matrix. |
| CS-02 `CloudSettings.enable()` | Validate keys/consent, create new session, optionally remember | **A:** missing/equal/whitespace keys and no consent rejected; keys trimmed; distinct keys retained privately; rotation invalidates prior session; memory-only mode clears previous remembered data. |
| CS-03 `CloudSettings.disable()` | Forget credentials/consent and invalidate current session | **A:** session inaccessible and restart disabled after forgetting. **G:** failed ciphertext deletion leaves safe disabled state/error. |
| CS-04 `CloudSettings.session()` | Return active authorization token or null | **A:** disabled returns null; enable/restore yields current token. |
| CS-05 `CloudSettings.credentialsFor()` | Authorize only current session | **A:** wrong/rotated/disabled session returns null; matching session returns dedicated keys, absent from public state/toString. |
| CS-06 private `valid()` / `validKey()` | Enforce distinct well-formed keys and bounded model identifier | **A/I:** exercised through enable rejection cases. **G:** explicit maximum key/model length boundary tests. |
| CS-07 `CloudClassificationSettings.initialize()` | Restore process-wide settings once under synchronization | **I:** real app startup exercises initialization. **G:** concurrent initialization/idempotence and cold receiver/worker startup without Activity. |
| CS-08 facade `enable()` / `disable()` / `session()` / `credentialsFor()` | Route application callers to shared lifecycle | **G:** dedicated facade/wiring assertions; isolated lifecycle tests are not proof of the real global settings state. Preserve real credentials when designing the harness. |

Use synthetic keys and unique vault aliases. Public state must never reveal keys. Remembered-consent recovery tests reconstruct isolated instances; they do not demonstrate OS reboot/process-kill behavior. An already-sent hosted request cannot be recalled by disabling cloud.
