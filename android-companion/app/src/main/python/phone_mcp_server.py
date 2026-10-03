"""Small tools-only MCP server for the embedded Android Python runtime.

Implements MCP 2025-11-25 lifecycle, ping, tools/list and tools/call using
JSON-RPC 2.0. No SDK/native dependencies, HTTP listener, resources, sampling,
or agent loop. Android uses the documented custom in-memory transport in
phone_mcp_client.py; running this file on a computer provides standard stdio.
This is a deliberately limited POC implementation, not a general MCP SDK.
"""

import json
import os
import sys

import phone_tools
from expenditure_classifier import ClassificationError, DEFAULT_MODEL, MAX_BODY_CHARS

PROTOCOL_VERSION = "2025-11-25"
SERVER_VERSION = "0.5.0"
MAX_MESSAGE_CHARS = 100000
SAFE_ERROR_CODES = frozenset({
    "configuration", "invalid_input", "invalid_response", "authentication",
    "access_denied", "rate_limit_or_quota", "service_unavailable",
    "request_rejected", "timeout", "network", "refused", "incomplete_response",
})


def _tool_definitions():
    """Fresh schemas: callers cannot mutate the server's catalogue."""
    return [
        {"name": "get_help", "description": "Explain the current phone POC's capabilities and setup.",
         "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
         "outputSchema": {"type": "object", "properties": {"reply": {"type": "string"}},
                          "required": ["reply"], "additionalProperties": False},
         "annotations": {"readOnlyHint": True, "openWorldHint": False}},
        {"name": "classify_financial_message",
         "description": "Build a few-shot prompt and call hosted OpenAI to decide whether an SMS describes a completed outgoing expenditure. Uploads the body; requires prior cloud consent. Does not verify ownership, assign a category, or record an expense.",
         "inputSchema": {"type": "object", "properties": {
             "body": {"type": "string", "minLength": 1, "maxLength": MAX_BODY_CHARS}},
             "required": ["body"], "additionalProperties": False},
         "outputSchema": {"type": "object", "properties": {
             "is_expenditure": {"type": "boolean"},
             "api_elapsed_ms": {"type": "number", "minimum": 0}},
             "required": ["is_expenditure", "api_elapsed_ms"], "additionalProperties": False},
         "annotations": {"readOnlyHint": True, "openWorldHint": True}},
    ]


def _error(request_id, code, message):
    # Fixed messages only: never include request text, credentials or tracebacks.
    return {"jsonrpc": "2.0", "id": request_id,
            "error": {"code": code, "message": message}}


def _tool_result(data, is_error=False):
    result = {"content": [{"type": "text", "text": json.dumps(data, allow_nan=False)}],
              "isError": is_error}
    # Successful results obey outputSchema; errors must not masquerade as false.
    if not is_error:
        result["structuredContent"] = data
    return result


def _reject_constant(_value):
    raise ValueError("Non-JSON constant.")


class PhoneMcpServer:
    """One connection/session. Key and model are private host-bound context.

    Android creates a session only after its worker has checked permission and
    current upload consent. The key never appears in tool schemas or arguments.
    A session is not shared between concurrent classification/chat requests.
    """

    def __init__(self, api_key="", model=DEFAULT_MODEL):
        self._api_key = api_key
        self._model = model
        self._initializing = False
        self._ready = False
        self._closed = False

    def close(self):
        self._api_key = ""
        self._ready = False
        self._closed = True

    def exchange(self, message_json):
        """Accept one JSON-RPC message; return serialized reply or None.

        Notifications never produce replies. A closed transport accepts no work.
        No protocol payloads are printed or persisted on the Android path.
        """
        if self._closed:
            raise RuntimeError("MCP session closed.")
        try:
            if not isinstance(message_json, str) or len(message_json) > MAX_MESSAGE_CHARS:
                raise ValueError("Invalid message.")
            message = json.loads(message_json, parse_constant=_reject_constant)
        except (ValueError, RecursionError):
            reply = _error(None, -32700, "Invalid JSON message.")
        else:
            reply = self._dispatch(message)
        return None if reply is None else json.dumps(reply, allow_nan=False)

    def _dispatch(self, message):
        if (not isinstance(message, dict) or message.get("jsonrpc") != "2.0"
                or not isinstance(message.get("method"), str)
                or ("id" in message and type(message["id"]) not in (str, int))):
            return _error(None, -32600, "Invalid JSON-RPC request.")
        method = message["method"]
        if "id" not in message:
            if method == "notifications/initialized" and self._initializing:
                self._ready = True
            return None
        request_id = message["id"]
        params = message.get("params", {})
        if not isinstance(params, dict):
            return _error(request_id, -32602, "Invalid request parameters.")
        if method == "ping":
            result = {}
        elif method == "initialize":
            info = params.get("clientInfo")
            if (self._initializing or not isinstance(params.get("protocolVersion"), str)
                    or not isinstance(params.get("capabilities"), dict)
                    or not isinstance(info, dict)
                    or not isinstance(info.get("name"), str)
                    or not isinstance(info.get("version"), str)):
                return _error(request_id, -32602, "Invalid initialization.")
            self._initializing = True
            result = {"protocolVersion": PROTOCOL_VERSION,
                      "capabilities": {"tools": {"listChanged": False}},
                      "serverInfo": {"name": "Android Finance Tools", "version": SERVER_VERSION}}
        elif not self._ready:
            return _error(request_id, -32000, "Initialize the MCP session first.")
        elif method == "tools/list":
            if params.get("cursor") is not None:
                return _error(request_id, -32602, "Unsupported pagination cursor.")
            result = {"tools": _tool_definitions()}
        elif method == "tools/call":
            name, arguments = params.get("name"), params.get("arguments", {})
            if (not isinstance(name, str) or not isinstance(arguments, dict)
                    or name not in {tool["name"] for tool in _tool_definitions()}):
                return _error(request_id, -32602, "Unknown tool or malformed tool call.")
            result = self._call_tool(name, arguments)
        else:
            return _error(request_id, -32601, "Method not supported.")
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    def _call_tool(self, name, arguments):
        try:
            if name == "get_help":
                if arguments:
                    raise ClassificationError("invalid_input")
                data = phone_tools.get_help()
            else:
                body = arguments.get("body")
                if (set(arguments) != {"body"} or not isinstance(body, str)
                        or not body.strip() or len(body) > MAX_BODY_CHARS):
                    raise ClassificationError("invalid_input")
                data = phone_tools.classify_financial_message(body, self._api_key, self._model)
            return _tool_result(data)
        except ClassificationError as error:
            code = error.code if error.code in SAFE_ERROR_CODES else "processing"
            return _tool_result({"error_code": code}, is_error=True)
        except Exception:
            return _tool_result({"error_code": "processing"}, is_error=True)


def serve_stdio(input_stream, output_stream, server):
    """Standard newline-delimited MCP stdio; EOF closes and forgets context.

    Sequential POC only: no concurrent cancellation or progress notifications.
    Oversized lines are rejected without buffering the rest into memory.
    """
    try:
        while True:
            line = input_stream.readline(MAX_MESSAGE_CHARS + 2)
            if not line:
                break
            if len(line) > MAX_MESSAGE_CHARS:
                while line and not line.endswith("\n"):
                    line = input_stream.readline(MAX_MESSAGE_CHARS + 2)
                reply = json.dumps(_error(None, -32700, "Message too large."))
            else:
                reply = server.exchange(line)
            if reply is not None:
                output_stream.write(reply + "\n")
                output_stream.flush()
    finally:
        server.close()


if __name__ == "__main__":
    # Desktop interoperability/debugging only. Android supplies its memory-only
    # credentials directly, never through environment variables or tool input.
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    serve_stdio(sys.stdin, sys.stdout, PhoneMcpServer(
        os.environ.get("OPENAI_API_KEY", ""),
        os.environ.get("OPENAI_CLASSIFIER_MODEL", DEFAULT_MODEL),
    ))
