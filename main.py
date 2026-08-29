"""Application composition root for the WhatsApp expense tracker."""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI

import storage
from expense_mcp_server import expense_mcp
from webhook_ingress import router as webhook_router


# Build the child ASGI app before accessing its session manager in FastAPI's
# lifespan. The mounted endpoint is available to MCP Inspector at /mcp.
mcp_asgi_app = expense_mcp.streamable_http_app()


@asynccontextmanager
async def lifespan(_: FastAPI):
    """Initialize durable state and the MCP server before serving requests."""
    storage.initialize_database()
    async with expense_mcp.session_manager.run():
        yield


app = FastAPI(lifespan=lifespan)
app.include_router(webhook_router)

# Mount after application routes so /webhook remains owned by FastAPI.
app.mount("/", mcp_asgi_app)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
