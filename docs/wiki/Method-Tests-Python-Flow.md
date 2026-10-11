# Method tests: Python stages in the BMTC persona

[Index and evidence legend](Home.md) | [BMTC persona](Persona-BMTC-Expenditure.md)

This page complements the Android method inventory. It documents the Python entry points used by that scenario, not every Python helper. [Testing.md](Testing.md#python-unit-tests-56-checks) has the complete current 56-check suite and execution commands. All paths below are the production Python packaged in the APK; host tests import those same sources.

Sources: [orchestrator.py](../../android-companion/app/src/main/python/orchestrator.py), [expenditure_classifier.py](../../android-companion/app/src/main/python/expenditure_classifier.py), [category_identifier.py](../../android-companion/app/src/main/python/category_identifier.py), [llm_api.py](../../android-companion/app/src/main/python/llm_api.py), [llm_usage.py](../../android-companion/app/src/main/python/llm_usage.py).

| Case / function | Project purpose | Existing test contract / limit |
| --- | --- | --- |
| PY-01 `orchestrator.handle_sms()` | Validate JSON and return local correlated receipt | **A:** [test_orchestrator.py](../../android-companion/python-tests/test_orchestrator.py): malformed/types/timestamps/Unicode/count/privacy; no network/inference/persistence. |
| PY-02 `orchestrator.classify_sms()` | Validate payload, call classifier and wrap safe result/metrics | **A:** [test_classifier.py](../../android-companion/python-tests/test_classifier.py): mocked direct routing, genuine boolean/timing, safe failure. |
| PY-03 `expenditure_classifier.build_request()` | Build few-shot expenditure prompt and strict boolean schema | **A:** classifier tests preserve sanitized examples/repayment exclusion/schema. This checks prompt content, not actual model decisions. |
| PY-04 `expenditure_classifier.classify_expenditure()` / `parse_response()` | Send one hosted request and validate boolean result | **A:** mocked successes/refused/incomplete/malformed/error outputs; safe failures and request settings. BMTC true is **M** when live. |
| PY-05 `orchestrator.identify_sms_category()` | Wrap independent category result for Kotlin | **A:** [test_category.py](../../android-companion/python-tests/test_category.py): mocked entry-point routing, valid pair/timing and safe failure. Android owns the expenditure gate. |
| PY-06 `category_identifier.build_request()` | Build personal-category few-shot prompt/schema | **A:** category tests preserve all 24 sanitized labels and explicit purpose/uncertainty rules. BMTC maps to utilities/transport in prompt examples, not a hardcoded lookup. |
| PY-07 `category_identifier.identify_category()` / `parse_response()` | Send second request and validate pair | **A:** mocked known/uncertain pairs; reject wrong/missing labels; failed requests stay distinct from Not sure. Live predictions **M**. |
| PY-08 `llm_api.send_request()` / `parse_output()` | HTTPS transport and Responses-envelope parsing | **A:** classifier/category tests mock transport failures, timeout settings, blocked redirects, safe codes, malformed/refused/incomplete output. Socket timeout 60 seconds per blocking operation, not whole-workflow deadline. Android owns retries. |
| PY-09 `llm_usage.read_usage()` | Parse actual provider counts and calculate optional dated estimate | **A:** [test_usage.py](../../android-companion/python-tests/test_usage.py): synthetic counts/pricing/cache/missing data/error telemetry; not external invoice reconciliation. |
| PY-10 `orchestrator.handle_chat()` | Return local capability help | **A:** [test_chat.py](../../android-companion/python-tests/test_chat.py): valid correlated help and safe invalid input; no spending-query interpretation or hosted call. |

Prompt samples are in [classifier_examples.py](../../android-companion/app/src/main/python/classifier_examples.py) and [category_examples.py](../../android-companion/app/src/main/python/category_examples.py). Only the message body is added as runtime SMS input to hosted requests; sender/ID metadata stays in the local handoff. Few-shot examples/instructions are also sent. Keys are separate arguments, never SMS payload fields. No local confirmation retrains the model or automatically adds prompt examples today.

**G:** one offline test that assembles the real Android worker, bridge and mocked Python transport from a captured SMS through final persisted card. Existing separate tests cannot be presented as this end-to-end result. The optional paid classifier evaluation is not run by CI and does not measure category accuracy.
