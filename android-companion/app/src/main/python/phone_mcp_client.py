"""Phone MCP client and custom, serialized in-memory transport.

Connection: one new server/session for each orchestrator tool invocation.
Exchange: serialize initialize -> initialized notification -> tools/list ->
tools/call, and parse/validate every response. Closing drops session credentials.
No socket or subprocess is needed in the APK. This is real JSON-RPC exchange,
not a direct call to the business function disguised as an MCP client.
"""

import json
import math

from expenditure_classifier import DEFAULT_MODEL
from phone_mcp_server import PhoneMcpServer, PROTOCOL_VERSION, SAFE_ERROR_CODES


class McpToolError(Exception):
    """Only a fixed code crosses the bridge; never server/raw-response details."""

    def __init__(self, code="processing"):
        self.code = code
        super().__init__(code)


class LocalJsonTransport:
    def __init__(self, server):
        self._server = server

    def exchange(self, message_json):
        return self._server.exchange(message_json)

    def close(self):
        self._server.close()


class PhoneMcpClient:
    """Synchronous client, used only on the app's background executors."""

    def __init__(self, transport):
        self._transport = transport
        self._next_id = 0
        self._tools = None

    def _request(self, method, params):
        self._next_id += 1
        request = {"jsonrpc": "2.0", "id": self._next_id, "method": method, "params": params}
        try:
            raw = self._transport.exchange(json.dumps(request, allow_nan=False))
            reply = json.loads(raw)
            if (not isinstance(reply, dict) or reply.get("jsonrpc") != "2.0"
                    or type(reply.get("id")) is not int or reply["id"] != self._next_id
                    or "error" in reply or not isinstance(reply.get("result"), dict)):
                raise McpToolError()
            return reply["result"]
        except McpToolError:
            raise
        except Exception:
            raise McpToolError() from None

    def connect(self):
        result = self._request("initialize", {
            "protocolVersion": PROTOCOL_VERSION, "capabilities": {},
            "clientInfo": {"name": "Android Finance Orchestrator", "version": "0.5.0"},
        })
        if (result.get("protocolVersion") != PROTOCOL_VERSION
                or not isinstance(result.get("capabilities"), dict)
                or "tools" not in result["capabilities"]):
            raise McpToolError()
        reply = self._transport.exchange(json.dumps({
            "jsonrpc": "2.0", "method": "notifications/initialized"}))
        if reply is not None:
            raise McpToolError()
        tools = self._request("tools/list", {}).get("tools")
        if (not isinstance(tools, list) or not all(isinstance(tool, dict)
                and isinstance(tool.get("name"), str)
                and isinstance(tool.get("inputSchema"), dict) for tool in tools)):
            raise McpToolError()
        self._tools = {tool["name"] for tool in tools}

    def call_tool(self, name, arguments):
        if self._tools is None or name not in self._tools:
            raise McpToolError()
        result = self._request("tools/call", {"name": name, "arguments": arguments})
        if type(result.get("isError")) is not bool:
            raise McpToolError()
        if result["isError"]:
            try:
                data = json.loads(result["content"][0]["text"])
                code = data.get("error_code")
                if code not in SAFE_ERROR_CODES:
                    code = "processing"
            except (ValueError, TypeError, KeyError, IndexError, AttributeError):
                code = "processing"
            raise McpToolError(code)
        data = result.get("structuredContent")
        if not isinstance(data, dict):
            raise McpToolError("invalid_response")
        # The POC's two output schemas are small enough to validate explicitly.
        if name == "get_help":
            valid = set(data) == {"reply"} and isinstance(data["reply"], str) and bool(data["reply"].strip())
        elif name == "classify_financial_message":
            elapsed = data.get("api_elapsed_ms")
            valid = (set(data) == {"is_expenditure", "api_elapsed_ms"}
                     and type(data["is_expenditure"]) is bool
                     and type(elapsed) in (int, float) and math.isfinite(elapsed) and elapsed >= 0)
        else:
            valid = False
        if not valid:
            raise McpToolError("invalid_response")
        return data


def call_phone_tool(name, arguments, api_key="", model=DEFAULT_MODEL):
    """Host binds credentials; initialize/discover/call/close for one operation."""
    transport = LocalJsonTransport(PhoneMcpServer(api_key, model))
    try:
        client = PhoneMcpClient(transport)
        client.connect()
        return client.call_tool(name, arguments)
    finally:
        transport.close()
