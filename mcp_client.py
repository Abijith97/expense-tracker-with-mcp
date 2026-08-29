"""MCP client boundary used by the application orchestrator."""

from __future__ import annotations

from time import perf_counter
from typing import Any

from mcp import Client

from expense_mcp_server import expense_mcp
from observability import trace


async def call_expense_tool(tool_name: str, arguments: dict[str, object]) -> dict[str, Any]:
    """Invoke an Expense MCP tool through the application's MCP client."""
    safe_arguments = {key: value for key, value in arguments.items() if key != "whatsapp_number"}
    trace("mcp_call_started", tool=tool_name, arguments=safe_arguments)
    started_at = perf_counter()
    async with Client(expense_mcp) as client:
        result = await client.call_tool(tool_name, arguments)

    if result.is_error:
        message = result.content[0].text if result.content else "The expense tool could not complete the request."
        raise RuntimeError(message)
    if not isinstance(result.structured_content, dict):
        raise RuntimeError("The expense tool returned an unexpected response.")
    trace(
        "mcp_call_completed",
        tool=tool_name,
        elapsed_seconds=round(perf_counter() - started_at, 3),
        result=result.structured_content,
    )
    return result.structured_content
