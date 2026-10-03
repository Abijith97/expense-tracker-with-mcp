"""Safe token telemetry and calculated USD cost, never a provider invoice.

Rates verified 2026-10-03 against the GPT-5.6 Luna model and prompt-caching docs.
Only the exact supported model and reported standard tier are priced. Unknown
usage/rates remain unavailable; they are not interpreted as free requests.
"""

import re
from decimal import Decimal

PRICING_DATE = "2026-10-03"
PRICED_MODEL = "gpt-5.6-luna"
# USD per million tokens: ordinary input, cache reads, cache writes, output.
RATES = tuple(Decimal(value) for value in ("0.20", "0.02", "0.25", "1.20"))


def _count(value):
    return type(value) is int and 0 <= value <= 10_000_000


def read_usage(response, requested_model):
    """Whitelist numbers/identifiers only. Bad telemetry must not ruin inference.

    Input includes cache reads/writes. Output already includes reasoning tokens;
    adding reasoning again would double-count. Absent cache details are unknown.
    """
    if not isinstance(response, dict) or not isinstance(response.get("usage"), dict):
        return None
    usage = response["usage"]
    incoming, outgoing = usage.get("input_tokens"), usage.get("output_tokens")
    if not _count(incoming) or not _count(outgoing):
        return None
    result = {"input_tokens": incoming, "output_tokens": outgoing}
    model = response.get("model")
    if isinstance(model, str) and re.fullmatch(r"[A-Za-z0-9._-]{1,100}", model):
        result["model"] = model
    tier = response.get("service_tier")
    if tier in ("default", "priority", "flex", "ultrafast"):
        result["service_tier"] = tier
    details = usage.get("input_tokens_details")
    if not isinstance(details, dict):
        return result
    cached, written = details.get("cached_tokens"), details.get("cache_write_tokens")
    if not _count(cached) or not _count(written) or cached + written > incoming:
        return result
    result.update(cached_input_tokens=cached, cache_write_tokens=written)
    if requested_model != PRICED_MODEL or model != PRICED_MODEL or tier != "default":
        return result
    ordinary = incoming - cached - written
    input_rate, read_rate, write_rate, output_rate = RATES
    # Long-context premium applies to the entire request, not just the excess.
    if incoming > 272_000:
        input_rate *= 2
        read_rate *= 2
        write_rate *= 2
        output_rate *= Decimal("1.5")
    cost = (ordinary * input_rate + cached * read_rate + written * write_rate +
            outgoing * output_rate) / 1_000_000
    result.update(estimated_cost_usd=float(cost), pricing_date=PRICING_DATE)
    return result
