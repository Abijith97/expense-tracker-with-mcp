"""MCP server that owns expense-recording and reporting operations."""

from __future__ import annotations

from datetime import date as CalendarDate, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any

from mcp.server import MCPServer
from sqlalchemy import func, select

import storage
from config import VALID_CATEGORIES


expense_mcp = MCPServer(
    "Expense Tracker",
    instructions=(
        "Use these tools to record and query only the authenticated WhatsApp user's expenses. "
        "Never calculate financial totals yourself when a summary tool is available."
    ),
)


def _as_currency(amount: Decimal) -> str:
    """Return a predictable decimal string for MCP JSON responses."""
    return format(amount.quantize(Decimal("0.01")), "f")


def _expense_to_dict(expense: storage.Expense) -> dict[str, object]:
    """Serialize a database expense for an MCP tool result."""
    return {
        "id": expense.id,
        "date": expense.occurred_on.isoformat(),
        "category": expense.category,
        "event": expense.event,
        "amount": _as_currency(expense.amount),
    }


def _parse_expense(date_text: str, category: str, event: str, amount: float | str) -> tuple[CalendarDate, Decimal]:
    """Validate tool input before creating a database row."""
    try:
        occurred_on = CalendarDate.fromisoformat(date_text)
        parsed_amount = Decimal(str(amount)).quantize(Decimal("0.01"))
    except (TypeError, ValueError, InvalidOperation) as error:
        raise ValueError("date must be YYYY-MM-DD and amount must be numeric.") from error

    if category not in VALID_CATEGORIES:
        raise ValueError(f"Unsupported category: {category}")
    if not event.strip():
        raise ValueError("event cannot be empty.")
    if parsed_amount <= 0:
        raise ValueError("amount must be greater than zero.")
    return occurred_on, parsed_amount


def _period_bounds(period: str, reference_date: str | None) -> tuple[CalendarDate, CalendarDate]:
    """Return inclusive dates for a daily, weekly, monthly, or yearly summary."""
    try:
        anchor = CalendarDate.fromisoformat(reference_date) if reference_date else CalendarDate.today()
    except ValueError as error:
        raise ValueError("reference_date must be YYYY-MM-DD.") from error

    if period == "daily":
        return anchor, anchor
    if period == "weekly":
        start = anchor - timedelta(days=anchor.weekday())
        return start, start + timedelta(days=6)
    if period == "monthly":
        start = anchor.replace(day=1)
        next_month = (start.replace(day=28) + timedelta(days=4)).replace(day=1)
        return start, next_month - timedelta(days=1)
    if period == "yearly":
        return anchor.replace(month=1, day=1), anchor.replace(month=12, day=31)
    raise ValueError("period must be daily, weekly, monthly, or yearly.")


@expense_mcp.tool()
def get_help() -> dict[str, object]:
    """Explain the tracker and show examples of supported user requests."""
    return {
        "message": "I can record expenses and answer questions about your spending.",
        "examples": [
            "Lunch ₹120 today",
            "What did I spend this week?",
            "FIND lunch",
            "Show my grocery expense from yesterday",
            "Delete expense 42",
        ],
        "periods": ["daily", "weekly", "monthly", "yearly"],
        "deletion_note": "Deleting an expense requires a separate confirmation.",
    }


@expense_mcp.tool()
def record_expense(
    whatsapp_number: str,
    date: str,
    category: str,
    event: str,
    amount: float | str,
) -> dict[str, object]:
    """Record one validated expense for a WhatsApp user."""
    occurred_on, parsed_amount = _parse_expense(date, category, event, amount)
    with storage.SessionLocal() as session:
        user = storage.get_or_create_user(session, whatsapp_number)
        expense = storage.Expense(
            user_id=user.id,
            occurred_on=occurred_on,
            category=category,
            event=event.strip(),
            amount=parsed_amount,
        )
        session.add(expense)
        session.commit()
        session.refresh(expense)
        return {"recorded": True, "expense": _expense_to_dict(expense)}


@expense_mcp.tool()
def get_summary(
    whatsapp_number: str,
    period: str,
    reference_date: str | None = None,
) -> dict[str, object]:
    """Return exact category totals for a user's daily, weekly, monthly, or yearly period."""
    start, end = _period_bounds(period, reference_date)
    with storage.SessionLocal() as session:
        user = storage.get_user(session, whatsapp_number)
        if user is None:
            return {"period": period, "start": start.isoformat(), "end": end.isoformat(), "total": "0.00", "categories": []}

        rows = session.execute(
            select(storage.Expense.category, func.sum(storage.Expense.amount))
            .where(
                storage.Expense.user_id == user.id,
                storage.Expense.occurred_on.between(start, end),
            )
            .group_by(storage.Expense.category)
            .order_by(storage.Expense.category)
        ).all()
        categories = [{"category": category, "amount": _as_currency(amount)} for category, amount in rows]
        total = sum((Decimal(item["amount"]) for item in categories), Decimal("0.00"))
        return {
            "period": period,
            "start": start.isoformat(),
            "end": end.isoformat(),
            "total": _as_currency(total),
            "categories": categories,
        }


@expense_mcp.tool()
def find_expenses(
    whatsapp_number: str,
    date: str | None = None,
    category: str | None = None,
    text: str | None = None,
) -> dict[str, object]:
    """Find a user's expenses by optional date, category, or description text."""
    if date:
        try:
            occurred_on = CalendarDate.fromisoformat(date)
        except ValueError as error:
            raise ValueError("date must be YYYY-MM-DD.") from error
    else:
        occurred_on = None

    with storage.SessionLocal() as session:
        user = storage.get_user(session, whatsapp_number)
        if user is None:
            return {"expenses": []}

        statement = select(storage.Expense).where(storage.Expense.user_id == user.id)
        if occurred_on:
            statement = statement.where(storage.Expense.occurred_on == occurred_on)
        if category:
            statement = statement.where(storage.Expense.category == category)
        if text:
            statement = statement.where(storage.Expense.event.ilike(f"%{text.strip()}%"))

        expenses = session.scalars(statement.order_by(storage.Expense.occurred_on.desc(), storage.Expense.id.desc()).limit(20)).all()
        return {"expenses": [_expense_to_dict(expense) for expense in expenses]}


@expense_mcp.tool()
def get_expense(whatsapp_number: str, expense_id: int) -> dict[str, object]:
    """Retrieve one specific expense, only when it belongs to the requesting user."""
    with storage.SessionLocal() as session:
        user = storage.get_user(session, whatsapp_number)
        if user is None:
            return {"found": False}
        expense = session.scalar(
            select(storage.Expense).where(storage.Expense.id == expense_id, storage.Expense.user_id == user.id)
        )
        return {"found": expense is not None, "expense": _expense_to_dict(expense) if expense else None}


@expense_mcp.tool()
def delete_expense(whatsapp_number: str, expense_id: int, confirmed: bool = False) -> dict[str, object]:
    """Preview or delete one expense. A caller must set confirmed to true to delete it."""
    with storage.SessionLocal() as session:
        user = storage.get_user(session, whatsapp_number)
        if user is None:
            return {"deleted": False, "reason": "Expense not found."}
        expense = session.scalar(
            select(storage.Expense).where(storage.Expense.id == expense_id, storage.Expense.user_id == user.id)
        )
        if expense is None:
            return {"deleted": False, "reason": "Expense not found."}

        preview = _expense_to_dict(expense)
        if not confirmed:
            return {"deleted": False, "requires_confirmation": True, "expense": preview}

        session.delete(expense)
        session.commit()
        return {"deleted": True, "expense": preview}
