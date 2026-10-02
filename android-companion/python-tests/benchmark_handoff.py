"""Report local Python processing time; this is a measurement, not a pass/fail test.

Run from android-companion: python python-tests/benchmark_handoff.py
This excludes Android, bridge/runtime startup, and actual SMS delivery.
"""

import json
from pathlib import Path
import statistics
import sys
from time import perf_counter_ns

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
from orchestrator import handle_sms


def main():
    payload = json.dumps({
        "schema_version": 1,
        "message_id": "demo-benchmark-001",
        "sender": "DEMO-SENDER",
        "body": "Demo \u20b9120 \U0001f6d2",
        "sent_at_ms": 1_700_000_000_000,
        "received_at_ms": 1_700_000_000_100,
    })
    for _ in range(5):
        handle_sms(payload)

    durations_ms = []
    for _ in range(100):
        started_at = perf_counter_ns()
        handle_sms(payload)
        durations_ms.append((perf_counter_ns() - started_at) / 1_000_000)

    print(f"Host Python {sys.version.split()[0]}, 100 calls after 5 warm-up calls")
    print(f"Median: {statistics.median(durations_ms):.3f} ms")
    print(f"95th percentile (nearest rank): {sorted(durations_ms)[94]:.3f} ms")
    print("No timing threshold; compare measurements on the same machine.")


if __name__ == "__main__":
    main()
