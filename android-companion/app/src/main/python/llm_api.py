"""Shared hosted-LLM transport and response envelope parsing.

No SMS content, credentials, provider errors, or raw responses are logged.
This module knows nothing about expenditure decisions or spending categories.
"""

import json
import socket
import urllib.error
import urllib.request

ENDPOINT = "https://api.openai.com/v1/responses"
MAX_RESPONSE_BYTES = 128000


class InferenceError(Exception):
    """A fixed safe code; never include private input or provider details."""

    def __init__(self, code):
        self.code = code
        super().__init__(code)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the Authorization header or SMS to another endpoint.
        return None


def send_request(payload, api_key):
    """One HTTPS request, bounded response, no retries, 20-second socket timeout.

    Timeout applies to each blocking socket operation, not a total deadline.
    Run this in Android background work, not the SMS receiver or UI thread.
    """
    if (not isinstance(api_key, str) or not api_key.strip()
            or any(char.isspace() for char in api_key.strip())):
        raise InferenceError("configuration")
    request = urllib.request.Request(
        ENDPOINT, data=json.dumps(payload).encode("utf-8"),
        headers={"Authorization": "Bearer " + api_key.strip(), "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.build_opener(_NoRedirect).open(request, timeout=20) as reply:
            raw = reply.read(MAX_RESPONSE_BYTES + 1)
        if len(raw) > MAX_RESPONSE_BYTES:
            raise InferenceError("invalid_response")
        return json.loads(raw)
    except urllib.error.HTTPError as error:
        status = error.code
        error.close()
        code = {401: "authentication", 403: "access_denied", 429: "rate_limit_or_quota"}.get(
            status, "service_unavailable" if status >= 500 else "request_rejected")
        raise InferenceError(code) from None
    except (TimeoutError, socket.timeout):
        raise InferenceError("timeout") from None
    except urllib.error.URLError as error:
        code = "timeout" if isinstance(error.reason, (TimeoutError, socket.timeout)) else "network"
        raise InferenceError(code) from None
    except (ValueError, UnicodeError, RecursionError):
        raise InferenceError("invalid_response") from None
    except OSError:
        raise InferenceError("network") from None


def parse_output(response):
    """Read exactly one JSON object; callers validate their own result schema."""
    if not isinstance(response, dict) or response.get("status") != "completed":
        raise InferenceError("incomplete_response")
    texts = []
    try:
        for item in response["output"]:
            if item.get("type") != "message":
                continue
            for part in item["content"]:
                if part.get("type") == "refusal":
                    raise InferenceError("refused")
                if part.get("type") == "output_text":
                    texts.append(part["text"])
        if len(texts) != 1:
            raise InferenceError("invalid_response")
        result = json.loads(texts[0])
        if not isinstance(result, dict):
            raise InferenceError("invalid_response")
        return result
    except (KeyError, TypeError, ValueError, AttributeError, RecursionError):
        raise InferenceError("invalid_response") from None
