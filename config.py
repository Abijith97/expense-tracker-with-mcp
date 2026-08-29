"""Runtime configuration shared by the application functions."""

from __future__ import annotations

import os


OLLAMA_HOST = os.getenv("OLLAMA_CLIENT_HOST", "http://127.0.0.1:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:8b")
DEBUG_TRACING = os.getenv("DEBUG_TRACING", "true").lower() in {"1", "true", "yes", "on"}

VALID_CATEGORIES = (
    "Food",
    "Transport",
    "Groceries",
    "Family",
    "Gifts",
    "Utilities",
    "Clothing",
    "Investments",
    "Home & Kitchen",
    "Family & Leisure",
    "Family & Celebration",
    "Snacks",
    "Other",
)
