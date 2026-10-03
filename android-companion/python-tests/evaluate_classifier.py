"""Optional PAID hosted-model check, never part of unittest discovery.

Set OPENAI_API_KEY privately in the process environment first. Only sanitized
SMS bodies are sent. Keys, bodies, and provider error details are never printed.
Prompt examples are excluded; held-out similar templates are not broad accuracy.
"""

import os
from pathlib import Path
import statistics
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
from classifier_examples import EXPENDITURE_EXAMPLES, OTHER_EXAMPLES, PROMPT_EXAMPLES
from expenditure_classifier import ClassificationError, DEFAULT_MODEL, classify_expenditure


def main():
    key = os.environ.get("OPENAI_API_KEY", "")
    if not key:
        print("Set OPENAI_API_KEY privately in your environment. No requests were made.")
        return 1
    model = os.environ.get("OPENAI_CLASSIFIER_MODEL", DEFAULT_MODEL)
    prompt_bodies = {body for body, _ in PROMPT_EXAMPLES}
    cases = [(body, True) for body in EXPENDITURE_EXAMPLES if body not in prompt_bodies]
    cases += [(body, False) for body in OTHER_EXAMPLES if body not in prompt_bodies]
    print(f"Paid evaluation: {len(cases)} sanitized held-out cases. Model: {model}")
    correct, failed, times = 0, 0, []
    for index, (body, expected) in enumerate(cases, start=1):
        try:
            actual, elapsed = classify_expenditure(body, key, model)
            correct += actual == expected
            times.append(elapsed)
            print(f"case={index} expected={expected} actual={actual} api_ms={elapsed:.3f}")
        except ClassificationError as error:
            failed += 1
            print(f"case={index} failed={error.code}")
            if error.code in ("authentication", "access_denied", "rate_limit_or_quota", "request_rejected"):
                print("Stopped: fix API access/billing/model configuration before retrying.")
                return 1
    print(f"Correct: {correct}/{len(cases)}; request failures: {failed}")
    if times:
        print(f"Median API latency: {statistics.median(times):.3f} ms")
    return 0 if correct == len(cases) else 1


if __name__ == "__main__":
    raise SystemExit(main())
