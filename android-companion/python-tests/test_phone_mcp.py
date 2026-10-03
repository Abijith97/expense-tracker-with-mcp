"""Offline MCP protocol and workflow checks; no key, SMS upload, or paid calls."""

import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
from expenditure_classifier import ClassificationError, MAX_BODY_CHARS
from phone_mcp_client import LocalJsonTransport, McpToolError, PhoneMcpClient, call_phone_tool
from phone_mcp_server import PhoneMcpServer, PROTOCOL_VERSION, serve_stdio


class PhoneMcpTests(unittest.TestCase):
    def setUp(self):
        self.server = PhoneMcpServer("DEMO-PRIVATE-KEY")
        self.transport = LocalJsonTransport(self.server)
        self.client = PhoneMcpClient(self.transport)
        self.addCleanup(self.transport.close)

    def request(self, method, params=None, request_id=7):
        return json.loads(self.server.exchange(json.dumps({"jsonrpc": "2.0", "id": request_id,
            "method": method, "params": {} if params is None else params})))

    def test_client_exchanges_initialize_notification_discovery_and_call(self):
        with patch.object(self.transport, "exchange", wraps=self.transport.exchange) as wire:
            self.client.connect()
            self.client.call_tool("get_help", {})
        messages = [json.loads(call.args[0]) for call in wire.call_args_list]
        self.assertEqual([message["method"] for message in messages], [
            "initialize", "notifications/initialized", "tools/list", "tools/call"])
        self.assertNotIn("id", messages[1])
        self.assertEqual(messages[-1]["params"], {"name": "get_help", "arguments": {}})
        self.assertNotIn("DEMO-PRIVATE-KEY", json.dumps(messages))

    def test_tool_catalogue_has_schemas_but_no_credentials_or_future_tools(self):
        self.client.connect()
        catalogue = self.request("tools/list")["result"]["tools"]
        self.assertEqual({tool["name"] for tool in catalogue}, {"get_help", "classify_financial_message"})
        for tool in catalogue:
            self.assertIs(tool["inputSchema"]["additionalProperties"], False)
            self.assertIsInstance(tool["outputSchema"], dict)
        self.assertNotIn("api_key", json.dumps(catalogue))
        self.assertNotIn("DEMO-PRIVATE-KEY", json.dumps(catalogue))

    def test_tools_cannot_run_before_initialized_notification(self):
        self.assertEqual(self.request("tools/list")["error"]["code"], -32000)
        initialization = self.request("initialize", {"protocolVersion": "unrecognized-version",
            "capabilities": {}, "clientInfo": {"name": "Demo", "version": "1"}})
        self.assertEqual(initialization["result"]["protocolVersion"], PROTOCOL_VERSION)
        self.assertEqual(self.request("tools/list")["error"]["code"], -32000)

    def test_invalid_initialization_does_not_open_session(self):
        for params in ({}, {"protocolVersion": 1}, {"protocolVersion": PROTOCOL_VERSION,
            "capabilities": {}, "clientInfo": {"name": 42, "version": "1"}}):
            self.assertEqual(self.request("initialize", params)["error"]["code"], -32602)
        self.assertIsNone(self.server.exchange('{"jsonrpc":"2.0","method":"notifications/initialized"}'))
        self.assertEqual(self.request("tools/list")["error"]["code"], -32000)

    def test_bad_json_invalid_envelopes_batches_and_boolean_ids_are_rejected(self):
        for raw in ('{"PRIVATE"', 'NaN', '{"jsonrpc":"2.0","id":1,"method":"ping","params":NaN}'):
            self.assertEqual(json.loads(self.server.exchange(raw))["error"]["code"], -32700)
        for message in ([], None, {"id": 1, "method": "ping"},
            {"jsonrpc": "2.0", "id": True, "method": "ping"}):
            result = json.loads(self.server.exchange(json.dumps(message)))
            self.assertEqual(result["error"]["code"], -32600)
            self.assertIsNone(result["id"])
        self.assertEqual(self.request("ping", ["PRIVATE"])["error"]["code"], -32602)

    def test_unknown_notifications_are_silent_and_ping_works(self):
        self.assertIsNone(self.server.exchange('{"jsonrpc":"2.0","method":"notifications/demo"}'))
        self.assertEqual(self.request("ping", request_id="demo")["id"], "demo")
        self.assertEqual(self.request("ping")["result"], {})

    def test_unknown_tools_methods_and_cursors_are_protocol_errors(self):
        self.client.connect()
        self.assertEqual(self.request("tools/call", {"name": "PRIVATE-TOOL"})["error"]["code"], -32602)
        self.assertEqual(self.request("tools/call", {"name": "get_help", "arguments": []})["error"]["code"], -32602)
        self.assertEqual(self.request("unimplemented")["error"]["code"], -32601)
        self.assertEqual(self.request("tools/list", {"cursor": "PRIVATE"})["error"]["code"], -32602)
        with self.assertRaises(McpToolError):
            self.client.call_tool("record_expense", {})

    def test_classification_returns_actual_booleans_and_compatible_text_content(self):
        self.client.connect()
        for decision in (True, False):
            with self.subTest(decision=decision), patch("phone_tools.classify_expenditure", return_value=(decision, 2.5)):
                result = self.request("tools/call", {"name": "classify_financial_message", "arguments": {"body": "DEMO-PRIVATE-BODY"}})["result"]
                self.assertIs(result["isError"], False)
                self.assertIs(result["structuredContent"]["is_expenditure"], decision)
                self.assertEqual(json.loads(result["content"][0]["text"]), result["structuredContent"])
                self.assertNotIn("DEMO-PRIVATE", json.dumps(result))

    def test_invalid_tool_arguments_never_invoke_the_classifier(self):
        self.client.connect()
        with patch("phone_tools.classify_expenditure") as call:
            for arguments in ({}, {"body": ""}, {"body": " "}, {"body": True},
                {"body": "x" * (MAX_BODY_CHARS + 1)}, {"body": "demo", "api_key": "PRIVATE"}):
                with self.subTest(arguments_type=type(arguments)), self.assertRaises(McpToolError) as caught:
                    self.client.call_tool("classify_financial_message", arguments)
                self.assertEqual(caught.exception.code, "invalid_input")
            call.assert_not_called()
        with self.assertRaises(McpToolError):
            self.client.call_tool("get_help", {"body": "PRIVATE"})

    def test_api_failures_have_is_error_and_no_false_result(self):
        self.client.connect()
        with patch("phone_tools.classify_expenditure", side_effect=ClassificationError("authentication")):
            result = self.request("tools/call", {"name": "classify_financial_message", "arguments": {"body": "demo"}})["result"]
            self.assertIs(result["isError"], True)
            self.assertNotIn("structuredContent", result)
            self.assertNotIn("is_expenditure", json.dumps(result))
            with self.assertRaises(McpToolError) as caught:
                self.client.call_tool("classify_financial_message", {"body": "demo"})
            self.assertEqual(caught.exception.code, "authentication")

    def test_unexpected_errors_do_not_leak_details(self):
        self.client.connect()
        for error in (RuntimeError("PRIVATE-KEY-BODY-DETAILS"), ClassificationError("PRIVATE-DETAILS")):
            with patch("phone_tools.classify_expenditure", side_effect=error):
                reply = self.request("tools/call", {"name": "classify_financial_message", "arguments": {"body": "demo"}})
                self.assertNotIn("PRIVATE", json.dumps(reply))
                self.assertEqual(json.loads(reply["result"]["content"][0]["text"])["error_code"], "processing")

    def test_missing_key_is_configuration_failure_and_help_is_offline(self):
        with patch("expenditure_classifier.urllib.request.build_opener") as network:
            self.assertIn("get_help MCP tool", call_phone_tool("get_help", {})["reply"])
            with self.assertRaises(McpToolError) as caught:
                call_phone_tool("classify_financial_message", {"body": "demo"})
            self.assertEqual(caught.exception.code, "configuration")
            network.assert_not_called()

    def test_close_forgets_credentials_and_disallows_further_calls(self):
        self.client.connect()
        self.transport.close()
        self.assertEqual(self.server._api_key, "")
        with self.assertRaises(McpToolError):
            self.client.call_tool("get_help", {})

    def test_helper_closes_on_failure_and_sessions_are_independent(self):
        with patch("phone_mcp_client.LocalJsonTransport.close", autospec=True) as close:
            with self.assertRaises(McpToolError):
                call_phone_tool("unimplemented", {})
            close.assert_called_once()
        other = PhoneMcpServer()
        self.addCleanup(other.close)
        self.client.connect()
        result = json.loads(other.exchange('{"jsonrpc":"2.0","id":1,"method":"tools/list"}'))
        self.assertEqual(result["error"]["code"], -32000)

    def test_client_rejects_mismatched_ids_and_malformed_successes(self):
        self.client.connect()
        bad_results = ({"isError": False, "structuredContent": {"is_expenditure": "false", "api_elapsed_ms": 1}},
            {"isError": False, "structuredContent": {"is_expenditure": False, "api_elapsed_ms": float("nan")}},
            {"isError": False, "structuredContent": {"is_expenditure": False, "api_elapsed_ms": -1}},
            {"isError": "false", "structuredContent": {}}, {"isError": False})
        for result in bad_results:
            def exchange(raw):
                return json.dumps({"jsonrpc": "2.0", "id": json.loads(raw)["id"], "result": result})
            with self.subTest(result=result), patch.object(self.transport, "exchange", side_effect=exchange):
                with self.assertRaises(McpToolError):
                    self.client.call_tool("classify_financial_message", {"body": "demo"})
        with patch.object(self.transport, "exchange", return_value='{"jsonrpc":"2.0","id":0,"result":{}}'):
            with self.assertRaises(McpToolError):
                self.client.call_tool("get_help", {})

    def test_stdio_exchanges_newline_messages_and_closes_on_eof(self):
        requests = [{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": PROTOCOL_VERSION, "capabilities": {}, "clientInfo": {"name": "demo", "version": "1"}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "get_help", "arguments": {}}}]
        output = io.StringIO()
        serve_stdio(io.StringIO("\n".join(json.dumps(request) for request in requests) + "\n"), output, self.server)
        replies = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual([reply["id"] for reply in replies], [1, 2, 3])
        self.assertIn("get_help", replies[-1]["result"]["structuredContent"]["reply"])
        self.assertEqual(self.server._api_key, "")

    def test_stdio_rejects_oversized_line_then_recovers(self):
        output = io.StringIO()
        serve_stdio(io.StringIO("x" * 100010 + '\n{"jsonrpc":"2.0","id":4,"method":"ping"}\n'), output, self.server)
        replies = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(replies[0]["error"]["code"], -32700)
        self.assertEqual(replies[1]["result"], {})


if __name__ == "__main__":
    unittest.main()
