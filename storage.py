"""Database models and persistence helpers for the expense tracker."""

from __future__ import annotations

import os
from datetime import date, datetime, timezone
from decimal import Decimal

from sqlalchemy import Date, DateTime, ForeignKey, Numeric, String, create_engine, select
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, relationship, sessionmaker


DEFAULT_DATABASE_URL = "sqlite:///./expense_tracker.db"
DATABASE_URL = os.getenv("DATABASE_URL", DEFAULT_DATABASE_URL)


class Base(DeclarativeBase):
    """Base class shared by all SQLAlchemy models."""


class User(Base):
    """A tracker account identified by a WhatsApp number."""

    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    whatsapp_number: Mapped[str] = mapped_column(String(32), unique=True, index=True)
    currency: Mapped[str] = mapped_column(String(3), default="INR")
    timezone: Mapped[str] = mapped_column(String(64), default="Asia/Kolkata")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))

    expenses: Mapped[list[Expense]] = relationship(back_populates="user")
    webhook_events: Mapped[list[WebhookEvent]] = relationship(back_populates="user")


class Expense(Base):
    """One validated expense belonging to one user."""

    __tablename__ = "expenses"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    occurred_on: Mapped[date] = mapped_column(Date, index=True)
    category: Mapped[str] = mapped_column(String(64), index=True)
    event: Mapped[str] = mapped_column(String(255))
    amount: Mapped[Decimal] = mapped_column(Numeric(12, 2))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))

    user: Mapped[User] = relationship(back_populates="expenses")


class WebhookEvent(Base):
    """Tracks Twilio messages so a retry cannot create duplicate expenses."""

    __tablename__ = "webhook_events"

    id: Mapped[int] = mapped_column(primary_key=True)
    message_sid: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    status: Mapped[str] = mapped_column(String(16), default="processing")
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    processed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    user: Mapped[User] = relationship(back_populates="webhook_events")


def _engine_options(database_url: str) -> dict[str, object]:
    """Return SQLite-specific engine options when they are required."""
    if database_url.startswith("sqlite"):
        return {"connect_args": {"check_same_thread": False}}
    return {}


engine = create_engine(DATABASE_URL, **_engine_options(DATABASE_URL))
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def configure_database(database_url: str) -> None:
    """Point the storage layer at a database URL, primarily for tests."""
    global DATABASE_URL, engine

    engine.dispose()
    DATABASE_URL = database_url
    engine = create_engine(DATABASE_URL, **_engine_options(DATABASE_URL))
    SessionLocal.configure(bind=engine)


def initialize_database() -> None:
    """Create the initial local schema if it does not already exist."""
    Base.metadata.create_all(bind=engine)


def get_or_create_user(session: Session, whatsapp_number: str) -> User:
    """Return the account for a WhatsApp number, creating it on first message."""
    user = session.scalar(select(User).where(User.whatsapp_number == whatsapp_number))
    if user is not None:
        return user

    user = User(whatsapp_number=whatsapp_number)
    session.add(user)
    session.flush()
    return user


def get_user(session: Session, whatsapp_number: str) -> User | None:
    """Return an existing user without creating an account."""
    return session.scalar(select(User).where(User.whatsapp_number == whatsapp_number))


def get_webhook_event(session: Session, message_sid: str) -> WebhookEvent | None:
    """Look up a prior delivery of a Twilio message."""
    return session.scalar(select(WebhookEvent).where(WebhookEvent.message_sid == message_sid))


def begin_webhook_event(session: Session, message_sid: str, user: User) -> WebhookEvent:
    """Record that a new Twilio message is being processed."""
    webhook_event = WebhookEvent(message_sid=message_sid, user_id=user.id, status="processing")
    session.add(webhook_event)
    session.commit()
    return webhook_event


def save_expenses(
    session: Session,
    *,
    user: User,
    webhook_event: WebhookEvent | None,
    expenses: list[dict[str, object]],
) -> None:
    """Persist extracted expenses and mark the source webhook as processed."""
    for expense in expenses:
        session.add(
            Expense(
                user_id=user.id,
                occurred_on=expense["date"],
                category=expense["category"],
                event=expense["event"],
                amount=expense["price"],
            )
        )

    if webhook_event is not None:
        webhook_event.status = "processed"
        webhook_event.processed_at = datetime.now(timezone.utc)

    session.commit()


def mark_webhook_failed(session: Session, webhook_event: WebhookEvent | None) -> None:
    """Record a failed attempt so it can be diagnosed or retried later."""
    if webhook_event is None:
        return

    webhook_event.status = "failed"
    session.commit()


def mark_webhook_processed(session: Session, webhook_event: WebhookEvent | None) -> None:
    """Mark a non-expense tool request as successfully handled."""
    if webhook_event is None:
        return

    webhook_event.status = "processed"
    webhook_event.processed_at = datetime.now(timezone.utc)
    session.commit()
