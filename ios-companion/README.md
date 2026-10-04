# iOS development guide — feature-by-feature porting plan

This is a **living development plan, not an implemented iPhone app**. There is no Xcode project, iOS executable, or verified iOS test suite in this repository yet. The developer currently has Windows and no Mac access.

Last reviewed: **October 4, 2026**. Android baseline: tag `v0.11.0`, app version `0.11.0-sms-simulator`, version code 13. Android verification at that checkpoint: **54 Python tests and 53 Android checks passed**; debug app/test APK builds, unsigned release build, and lint passed. Those results do not establish iOS compatibility or live-model accuracy.

Keep developing Android until a message can become a saved expense and a useful spending summary. Prepare portable contracts now; start the iOS implementation once Mac access is available. Investigate iOS message-ingestion feasibility early rather than leaving that question until the entire project is finished.

Related references: [current project](../README.md), [Android setup and implementation](../android-companion/README.md), [test quick start](../android-companion/python-tests/README.md), and [detailed testing guide](../docs/wiki/Testing.md).

## Development tools and first setup

| Requirement | What to do | Why it is needed |
| --- | --- | --- |
| Mac access | Use a local or remotely accessible Mac with macOS compatible with the chosen Xcode version. Check compatibility before obtaining hardware. | The native Xcode/iOS build workflow is macOS-based, not a Windows installation. |
| Xcode | Install a supported stable version from the Mac App Store; install an iOS Simulator runtime when prompted. | Editor, compiler, debugger, signing, simulator integration, and test runner. |
| Swift + SwiftUI | Use Xcode's Swift toolchain and SwiftUI app template. No separate Flutter installation is proposed. | Swift replaces Kotlin; SwiftUI replaces Compose for this native POC. |
| Apple Account | Sign in to Xcode for personal-device testing. | A free Personal Team supports basic on-device testing; provisioning expires after seven days and needs rebuilding/reinstallation. |
| Test device | Start with iPhone Simulator, then a physical iPhone for lifecycle, storage, and any message-filter investigation. | Simulator tests alone cannot establish real message delivery or device lifecycle behaviour. |
| Paid membership | Defer until required capabilities or TestFlight/App Store distribution justify it. | Distribution and some capabilities require Apple Developer Program membership. |

Sources: [Xcode requirements](https://developer.apple.com/xcode/system-requirements), [Xcode](https://developer.apple.com/xcode/), [SwiftUI](https://developer.apple.com/swiftui/), and [Apple account/signing limits](https://developer.apple.com/help/account/basics/about-your-developer-account).

When Mac access is available:

1. Clone the repository on the Mac, preserving the working Android project.
2. Create a separate iOS App project using Swift and SwiftUI under this directory; do not replace Android sources. Choose a unique bundle identifier and a supported minimum iOS version based on the actual test device.
3. Add unit and UI test targets before adding inference or persistence.
4. Run a basic screen on Simulator; then configure signing and run on an iPhone.
5. Implement pasted/synthetic message processing with cloud off before entering credentials or enabling paid inference.

Until then, Windows is sufficient for maintaining this guide, sample data, contracts, and the existing Python tests. It does not verify the native iPhone app. Do not add hypothetical `xcodebuild` commands claiming a project or scheme already exists. Apple's [Develop in Swift tutorials](https://developer.apple.com/tutorials/develop-in-swift) provide an introductory learning path.

## Critical feasibility decisions

### Automatic SMS capture is not a direct port

Android currently uses `RECEIVE_SMS` and `SmsReceiver`. Do not create an iOS permission screen promising the same access to all incoming SMS or the existing inbox.

Apple's IdentityLookup Message Filter extension is a filtering mechanism for eligible unknown-sender messages; its documented SMS/MMS behaviour excludes contacts and iMessage. It is not a general inbox reader. See [SMS/MMS filtering](https://developer.apple.com/documentation/identitylookup/sms-and-mms-message-filtering).

There are additional extension restrictions: Apple's explanation describes limits on writing files shared with the containing app and on direct networking, with server analysis using a prescribed deferral mechanism. A finance collector cannot assume that installing this extension allows copying messages into the main app's expense store. Review current APIs, permitted data handling, device behaviour, and capability requirements before choosing this design. See [Apple's extension explanation](https://developer.apple.com/videos/play/wwdc2017/249/) and [creating a filter extension](https://developer.apple.com/documentation/identitylookup/creating-a-message-filter-app-extension).

**Initial iOS input:** explicit paste/manual entry and debug-generated samples. Investigate automatic capture separately on a real device. If filtering cannot support the intended finance workflow, retain manual input or agree on another ingestion source; do not imply automatic Android parity. Email access and Share Extensions are separate future features, not implemented fallbacks. Do not assume Messages offers an arbitrary SMS-body share action to our app.

### Python does not transfer automatically

Android embeds Python using Chaquopy, whose documentation currently says it does not support iOS. Copying `PythonBridge.kt` or the Python files into Xcode does not create a working bridge. [Chaquopy FAQ](https://www.chaquo.com/chaquopy/doc/current/faq.html).

**Proposed initial approach:** Swift implementations of local validation, orchestration, parsing, and the HTTPS client, reusing the same prompts, labelled examples, and behavioural contracts. This keeps the personal POC phone-local apart from hosted inference; it does not require a new application server.

Alternative approaches—hosting the Python helpers or investigating a separate iOS Python embedding solution—remain decisions to discuss, not dependencies added by this guide. A hosted backend would require deployment, authentication, and explicit agreement about data leaving the phone. No Flutter rewrite is proposed merely to prepare for iOS.

## Functionality map and status

All iOS implementation below is **planned/unverified**. "Android implemented" means present at `v0.11.0`, not proof that it is production-ready.

| ID | Functionality | Android status/reference | Proposed iOS approach and main difference |
| --- | --- | --- | --- |
| F01 | Onboarding and help chat | Implemented: `ChatViewModel`, `ChatScreen`, `app_help.py`; every chat message gets local help. | SwiftUI chat with local capability help; do not promise finance queries yet. |
| F02 | Incoming message capture | Implemented: `SmsReceiver`, permission/capture controls. | Automatic SMS parity is unresolved; start with user-pasted text. |
| F03 | Debug simulation | Implemented: debug `SmsSimulationControls`, `SmsProcessor`. | Debug-only sample-entry screen sharing the real processing service; no SMS permission bypass or fictional delivery measurement. |
| F04 | Input validation and local handoff | Implemented: `SmsPayload`, `PythonBridge`, `orchestrator.handle_sms`. | Swift typed contracts/local validation; Python runtime choice is separate. |
| F05 | Expenditure classification | Implemented: `classify_sms`, `expenditure_classifier.py`, `classifier_examples.py`. | Reuse few-shot examples and strict boolean contract through a Swift inference service. |
| F06 | Category/sub-category identification | Implemented: `category_identifier.py`, `category_examples.py`, `SmsCategory`. | Same personal hierarchy and valid-pair checks, only after Expenditure. |
| F07 | Cloud consent and two keys | Implemented: `CloudClassificationSettings`, `CloudClassificationControls`. | Explicit upload consent; distinct feature keys; memory-only default, optional Keychain persistence. |
| F08 | History, paging, clear, and stale-result protection | Implemented: `SmsStore`; retained diagnostic copies, 20 cards per page. | App-private versioned storage and paging; generation/attempt/session tokens rather than UI-only cancellation. |
| F09 | Async work and restart handling | Implemented: WorkManager and remembered-settings recovery. | Foreground async work first; separately investigate supported background execution. No always-running-service promise. |
| F10 | Performance timings and calculated costs | Implemented: `SmsPerformance`, `LlmUsage`, `llm_usage.py`. | Equivalent measured stages and provider-reported usage; unavailable values stay unavailable. |
| F11 | Transaction-detail extraction | Planned on Android. | Specify amount, currency, date/time, merchant/payee, direction, and ambiguity before porting. |
| F12 | Category confirmation/correction | Planned on Android. | In-app review linked to a specific transaction; no automatic model-training claim. |
| F13 | Expense ledger and deletion | Planned on Android; existing history is not a ledger. | Separate confirmed expense records from diagnostic messages; deletion/duplicate rules need definition. |
| F14 | Spending queries, summaries, and reports | Planned on Android. | Compute from saved expenses; define date ranges/time zone and weekly delivery expectations. |
| F15 | MCP/tool selection or autonomous agents | Deferred on Android. | No current MCP requirement; revisit for a future chatbot/tool-selection use case. |

Android Kotlin references live under `../android-companion/app/src/main/java/com/abijith/expensetracker/`; Python references under `../android-companion/app/src/main/python/`. The simulator is under `app/src/debug`, not the main source set. The [Android code map](../android-companion/README.md#code-map) describes current responsibilities.

## Behaviour and verification per functionality

### F01 — onboarding and chat

- Explain the actual input method, uploads, optional costs, and features not available yet. Do not advertise automatic capture before F02 is verified.
- Match current help-only behaviour first: blank Send disabled, user message and reply correlated by ID, generic failures, and no cloud request for local help.
- Android currently retains at most 60 chat entries in memory, not persistent finance history. Record any intentional iOS difference.
- **Verify:** offline help works; tab navigation preserves the session; help is accurate; empty input cannot submit; failures expose no private exception text.

### F02/F03 — manual input and simulation

- Paste one message, save a diagnostic copy, and run the same local/inference path as any future accepted input adapter. Keep debug simulation separate from the supported manual-entry feature.
- Android's simulator rejects blank input, sender length above 100, and body length above 12,000 UTF-16 units. Reproduce that boundary deliberately, not via Swift's grapheme-cluster `String.count` by accident. Repeated identical simulations get different IDs; ingestion deduplication is a separate rule.
- Label test cards and persist their provenance. Cloud off means no upload; cloud on requires the same clear charge confirmation. Expenditures can incur two calls.
- **Verify:** blank/overlong input blocked; editable positive/negative samples work; cancellation adds no card and makes no request; release builds exclude the debug entry point; no inbox write occurs.

For automatic capture, F02 also needs a **separate feasibility result**: identify eligible sources, extension/app data boundaries, any server requirement, supported device/OS, capability limitations, and a real-device test. Mark unsupported cases explicitly rather than assuming the Android receiver can be reproduced.

### F04 — data contracts and local processing

- Preserve stable IDs, schema version, body, and timestamps; never put credentials in message payloads, persisted jobs, receipts, or public UI state.
- Current Python payload: `schema_version: 1`, `message_id`, `sender`, `body`, `sent_at_ms`, `received_at_ms`. Real timestamps are nonnegative integers, not booleans.
- Preserve Unicode code-point counting when comparing with Python receipts; Swift grapheme count and UTF-16 length are different quantities.
- A native port should report a native validation/processing receipt, **not** an invented Python version or "Python acknowledged" status. Preserve intent while documenting that schema/UI difference.
- Android's local `simulated` storage flag is not in the inference payload. If iOS introduces an input-source field, version and test the change rather than silently changing the shared contract.
- **Verify:** malformed JSON/types rejected, emoji/multiline text round-trips, receipt ID matches, sender/body are not echoed in safe receipts, and no credentials appear in serialization.

### F05 — expenditure classification

- Reuse sanitized classification examples and the existing prompt intent: completed outgoing payment is true; advertisement, loan offer, OTP, failed payment, or incoming credit is not expenditure.
- Decode an actual boolean; `"true"`, `1`, a malformed response, refusal, timeout, and authentication failure must not become a successful false/Other result.
- Keep body-only classification distinct from ownership verification: a copied bank alert can describe expenditure without proving that it belongs to this user. Source/ownership checks remain deferred.
- **Verify:** mocked positive/negative outputs, invalid types/envelopes, safe failure states, and correlation to the original message. Live prediction checks require explicit paid opt-in; mocked tests are not accuracy measurements.

### F06 — category identification

Only call this after a valid Expenditure decision and renewed authorization. Reuse the 24 sanitized personal examples and this hierarchy:

| Category | Allowed sub-categories |
| --- | --- |
| `learning` | `engineering` |
| `utilities` | `transport`, `food`, `prepaid recharge`, `groceries`, `health` |
| `fun` | `gift`, `food` |
| `Not sure` | `Not sure` only |

Validate both strings and their pairing. Current result intent: `{"category":"utilities","sub_category":"groceries"}`. Explicit stated purpose overrides a merchant preference; ambiguous input returns Not sure for both. A category failure must preserve the valid Expenditure decision and first-stage telemetry. Examples are prompting, not weight updates or automatic learning.

**Verify:** all valid pairs accepted, cross-pairs/missing fields rejected, Other/failed classification skips this stage, consent withdrawal prevents it, and unknown/failed category does not erase classification. Carry over legacy-read tests only if iOS actually imports legacy data; there is no existing Android-to-iPhone migration feature.

### F07 — credentials, consent, and networking

- Match two dedicated keys and separate routing: classification key for F05, category key for F06. Do not embed a developer's key in distributed builds or add an organization Admin key.
- Memory-only default; remembered authorization is explicit opt-in. Use iOS Keychain rather than copying Android Keystore ciphertext or placing secrets in preferences. See [Apple Keychain credential storage](https://developer.apple.com/documentation/security/adding-a-password-to-the-keychain).
- Decide Keychain accessibility, device-lock behaviour, backup/synchronization policy, and forget/reset semantics before promising background recovery. Keychain storage is not a guarantee against a compromised device/app.
- Disable/forget must remove remembered authorization and invalidate old sessions. Missing/unreadable credentials fail closed; UI/public state/logs must not expose them.
- Use an injectable HTTPS transport, such as `URLSession` with async/await; retain explicit timeout, safe-error, redirect/endpoint, and response-validation behaviour from `llm_api.py`. Network work must not block UI. [Apple URLSession guidance](https://developer.apple.com/videos/play/wwdc2021/10095/).
- **Verify:** distinct-key checks, consent required, key routing, isolated remembered recovery/forget/failures, HTTP mocked, and secrets absent from logs/storage fixtures. Treat uploads as chargeable even when the result later times out.

### F08/F09 — persistence and lifecycle

- Diagnostic history must remain distinct from future expense records. Retain cards until explicitly cleared; show 20 at a time without deleting older ones.
- Choose versioned app-private storage for the POC; record a later ledger-storage decision separately. Match atomic-update, corrupted-history, backup/privacy, and source-label requirements rather than copying Android filesystem paths.
- Retain generation, attempt ID, and cloud-session checks. Clearing, pausing, forgetting consent, or a new attempt must prevent stale results from recreating/updating an invalid record.
- Do not silently replay an interrupted paid request after restart; an uncertain outcome may already have incurred charges. Preserve visible Interrupted/Failed outcomes and an explicit retry policy.
- Start with foreground Swift concurrency. Apple schedules background execution according to system conditions; WorkManager and an always-running Python service are not iOS implementations. Investigate expiration/cancellation/recovery with the appropriate supported APIs only when needed. [Apple background-strategy guidance](https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app).
- **Verify:** more than 20 retained messages, paging, relaunch, corrupted state, clear/pause during processing, lost consent, and late results. Simulated scheduling success is not a claim that arbitrary closed-app SMS processing works.

### F10 — timings and cost telemetry

- Measure local JSON encoding, service wait/setup, validation, hosted classification, hosted category, and total analysis separately. Swift has no Python-startup timing unless a real Python runtime is introduced.
- Use a monotonic elapsed-time clock; store durations, not process-local clock origins. Apple's [ContinuousClock](https://developer.apple.com/documentation/swift/continuousclock) is a candidate, subject to the chosen deployment target.
- Pasted/simulated input has no real SMS delivery/receiver latency. Omit those measurements rather than displaying zero or labelling local processing as detection time.
- Port the behavioural tests in `test_usage.py`/`LlmUsageInstrumentedTest`: actual reported tokens feed calculated estimates, unsupported/missing prices or usage stay unavailable, both stage costs combine only when known, and failures retain available usage/timing.
- Do not copy a fixed price into this guide. Preserve dated rates/model/tier with each estimate and revalidate pricing when implementing. Estimated per-attempt costs are not the provider's final bill or a cumulative audit of retries.
- **Verify:** Unicode JSON benchmarks, cold/warm native processing, delayed/failed mocked HTTP, retained per-stage data, no double-counting of reasoning/cache usage, and unknown totals never presented as free. Never benchmark live financial texts without consent.

### F11–F15 — future finance and chatbot functions

These features are not implemented on Android or iOS yet. Define their contracts and acceptance tests when Android adds them:

| Function | Decisions to capture | Minimum functional verification |
| --- | --- | --- |
| Transaction extraction | Amount precision/currency, merchant, transaction date versus capture date, time zone, direction, ambiguous/multiple transactions. | Exact amounts; missing fields require review; malformed/ambiguous output never silently becomes an expense. |
| Category review | Original suggestion versus confirmed label, correlation, custom-category rules, no reply/late reply, and correction history. | Edit one selected transaction; another transaction is unchanged; no double insert; confirmation is not claimed to train weights. |
| Expense ledger/deletion | Record IDs, deduplication, diagnostic versus financial retention, confirmation/undo rules, and local POC scope. | One intended expense recorded once; reload correct; cancellation keeps data; confirmed deletion affects only selected records. |
| Spending queries/reports | Date ranges, week start, currency, categories, averages, exclusions, and empty periods. | Fixed fixtures produce exact totals; incomplete/unknown records handled explicitly; corrections/deletions update summaries. |
| Sunday 8 pm report | Local-time scheduling, notification permission, report generation versus delivery, and missed schedules. | Test timezone/empty-week cases; do not promise an exact background computation time on iOS. Separate reminders from generated reports. |
| MCP/agents/training | Actual need for tool discovery/LLM-selected actions, transport/host location, authorization, or a separately evaluated training pipeline. | Tool arguments/results validated and authorized; no arbitrary writes; measured evaluation before any improvement claim. |

No WhatsApp, Twilio, ngrok, new backend, autonomous agent loop, or model-training service is added by this plan. Revisit only when a specific agreed feature requires it.

## Staged iOS implementation and test plan

| Stage | Scope | Verifiable stopping condition |
| --- | --- | --- |
| 0 — now | Maintain this guide; document ingestion/runtime decisions; finish the useful Android vertical slice. | Portable contracts/examples and explicit unresolved platform assumptions, with no claimed iOS build. |
| 1 — Mac available | Xcode project, SwiftUI tabs, local help, manual/debug input, local validation. | Runs offline on Simulator and iPhone; blank input blocked; no network required. |
| 2 — local history | Versioned storage, source labels, paging, pause/clear/stale guards. | Relaunch and 26-message fixtures retain correct data; invalid results cannot resurrect cleared cards. |
| 3 — mocked analysis | Swift orchestration/transport interfaces, strict boolean/pair parsing, failures, cost/timing contracts. | Automated unit/UI tests pass with HTTP blocked or mocked; no paid calls. |
| 4 — live opt-in | Consent, keys/Keychain, explicit synthetic cloud check. | One known-positive and one known-negative input complete; inspect both stages, costs, timings, and safe failures. Not an accuracy percentage. |
| 5 — useful finance flow | Port F11–F14 once Android contracts are established. | A message becomes a reviewed/saved expense and produces a fixture-verified spending summary. |
| Separate investigation | Eligible automatic ingestion and background behaviour. | Documented real-device feasibility evidence; no substitution of simulator results for delivery evidence. |

Use Swift Testing for new native unit tests and XCTest/XCUIAutomation for UI tests; Apple supports both in Xcode. Inject transport, clocks, settings, and storage so tests use synthetic messages and isolated credentials/data. Keep current Python tests as an Android/reference behavioural suite, not proof that a Swift port works. [Apple testing documentation](https://developer.apple.com/documentation/xcode/testing).

Once a real Xcode project exists, record its scheme, deployment target, Simulator/device configuration, exact run commands, test counts, and results here and in the [testing guide](../docs/wiki/Testing.md). Simulator UI success does not verify message filtering, signing/capabilities, device locks, or background delivery; those need separate physical-device checks. There is currently no iOS CI workflow or verified iOS test count.

## Keep this guide aligned with Android

This is a documentation workflow, **not an automatic synchronization service**. During each relevant Android feature change:

- Update the corresponding F-ID: Android status/source, portable contract, iOS approach, limitations, and test cases.
- Add an F-ID for a new functionality instead of hiding it inside an unrelated feature.
- Mark iOS work Planned, Implemented-unverified, or Verified, with a date and actual device/test evidence. Do not infer parity from matching screenshots.
- Record changes to enums, input limits, fixtures, schema versions, privacy/consent, storage, and cancellation rules.
- Update shared examples deliberately; do not copy private live SMS, API keys, signing material, databases, or screenshots containing secrets into the guide/tests.
- Link to Android sources and canonical tests rather than duplicating prompts or price tables. Recheck Apple restrictions when implementing platform-specific features.
- Keep the baseline/review date current. Retain immutable tags as checkpoints; do not move `v0.11.0` to make it include later documentation.

### Decisions still open

- Mac access and the target iPhone/iOS version.
- Minimum iOS version and Swift/Xcode configuration.
- Whether manual/pasted input is sufficient for the first iPhone demo.
- Whether any supported automatic-ingestion route fits finance tracking and allowed data handling.
- Swift port versus a separately evaluated Python runtime/backend, if preserving Python execution becomes necessary.
- Native history/ledger storage and backup/Keychain accessibility policies.
- The exact phase at which category validation, transaction extraction, queries, or MCP becomes useful.

Do not begin an automatic-capture implementation or introduce hosting based on an assumption that these decisions have already been made.
