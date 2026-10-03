"""Optional desktop interoperability check with the official MCP Python SDK.

Requires the SDK in the desktop environment only, not in the APK or unit tests.
Launches the real stdio server with an explicitly empty key: no paid API calls.
"""

import asyncio
from pathlib import Path
import sys

from mcp import Client, StdioServerParameters


async def main():
    server = Path(__file__).resolve().parents[1] / "app/src/main/python/phone_mcp_server.py"
    parameters = StdioServerParameters(command=sys.executable, args=[str(server)],
        env={"OPENAI_API_KEY": "", "OPENAI_CLASSIFIER_MODEL": "gpt-5.6-luna"})
    async with Client(parameters, mode="legacy", read_timeout_seconds=15) as client:
        tools = await client.list_tools()
        assert {tool.name for tool in tools.tools} == {"get_help", "classify_financial_message"}
        help_result = await client.call_tool("get_help", {})
        assert not help_result.is_error
        assert "get_help MCP tool" in help_result.structured_content["reply"]
        failure = await client.call_tool("classify_financial_message", {"body": "Demo purchase Rs.120."})
        assert failure.is_error
        assert "configuration" in failure.content[0].text
    print("Official MCP SDK: stdio initialization, discovery, help, and safe missing-key failure passed.")


if __name__ == "__main__":
    asyncio.run(main())
