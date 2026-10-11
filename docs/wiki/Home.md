# Testing wiki

Start with the [BMTC persona](Persona-BMTC-Expenditure.md), then follow the component pages to understand each method's purpose and test contract. [Testing.md](Testing.md) explains runners, commands, CI, reports and the full suite list.

These are versioned Markdown pages in the private repository, with copies in the [GitHub Wiki](https://github.com/Abijith97/expense-tracker-with-mcp/wiki). Its separate Git repository became accessible on October 11, 2026 after the initial Home page was created. Repository privacy and access settings were not changed. Wiki copies use Wiki-page links and absolute source-code links; this folder keeps repository-relative links.

## Read the evidence labels

- **A - automated assertion:** the cited existing suite explicitly checks the stated behavior. It does not imply every possible input/branch is checked.
- **I - indirect:** exercised through another entry point or UI/lifecycle; no dedicated assertion establishes the complete method contract.
- **G - gap:** a proposed test case, not an implemented or passing test.
- **M - manual:** a persona/device/paid-model check to run deliberately; not a CI result.

Every table row is a method-level test contract: arrange the input/state described, invoke the method through its normal boundary, and check the expected result. A wiki case is **not executable test code**. This documentation update does not add runnable tests or claim complete coverage.

## Component pages

| Component | Separate test page |
| --- | --- |
| SMS broadcast reception | [SmsReceiver](Method-Tests-SmsReceiver.md) |
| Shared local SMS processing | [SmsProcessor](Method-Tests-SmsProcessor.md) |
| History and state transitions | [SmsStore](Method-Tests-SmsStore.md) |
| Background scheduling and execution | [SmsClassificationWorker](Method-Tests-SmsClassificationWorker.md) |
| Kotlin-to-Python boundary | [PythonBridge](Method-Tests-PythonBridge.md) |
| Classification/category result contracts | [Classification and category](Method-Tests-Classification-and-Category.md) |
| Human category review and recording choice | [Category review](Method-Tests-Category-Review.md) |
| Cloud consent and authorization sessions | [Cloud settings](Method-Tests-Cloud-Settings.md) |
| Encrypted credential persistence | [Credential vault](Method-Tests-Cloud-Credential-Vault.md) |
| Retry policy | [Inference retry](Method-Tests-Inference-Retry.md) |
| JSON handoff, token costs and timings | [Usage and timing](Method-Tests-Usage-and-Timing.md) |
| In-app conversation | [Chat](Method-Tests-Chat.md) |
| Activity and SMS screen wiring | [MainActivity](Method-Tests-MainActivity.md) |
| Cloud settings/results UI | [Cloud controls](Method-Tests-Cloud-Controls.md) |
| Debug simulation and release stubs | [SMS simulation](Method-Tests-Sms-Simulation.md) |
| App startup and theme | [Application and theme](Method-Tests-Application-and-Theme.md) |
| Python stages in the persona | [Python flow](Method-Tests-Python-Flow.md) |

## Inventory scope

The Android pages inventory all explicitly named Kotlin functions in `app/src/main/java`, `app/src/debug/java` and `app/src/release/java`, including private and local helpers. Compiler-generated methods, property accessors, constructors/init blocks and anonymous callbacks are not separate methods in this inventory. Relevant constructor/callback contracts are described alongside their owning entry points. `Color.kt` and `Type.kt` contain values, not explicitly named functions; `ComposeTestActivity` has no declared methods.

The Python-flow page documents the production entry points used in the BMTC scenario; it is not an exhaustive Python method inventory. Python suite details remain in [Testing.md](Testing.md#python-unit-tests-56-checks).

## Verified baseline, not a new test run

Source baseline: `2426d38`, app `0.13.0-expenditure-review`. [Successful CI run](https://github.com/Abijith97/expense-tracker-with-mcp/actions/runs/38054981327): 56 Python tests on each of Windows/Linux and 80 Android tests on an API 35 emulator. Android JaCoCo: 83.34% line, 65.90% branch coverage. Python coverage.py: 99% combined statement/branch coverage.

The prior report showed **zero coverage for `SmsReceiver.onReceive()` and `SmsClassificationWorker.doWork()`**. Unit/component checks do not prove the full receiver-to-worker flow. Coverage means execution, not correctness or live LLM accuracy. Reports cover Kotlin/Java separately from Python; gcov/lcov are not used.

No private SMS, credentials or paid API calls are required for existing CI. Use isolated storage and synthetic credentials in new checks. Do not clear the user's real data or grant real cloud consent from a test.
