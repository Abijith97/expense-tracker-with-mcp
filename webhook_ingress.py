"""Twilio webhook ingress: validation, idempotency, and response creation."""

from __future__ import annotations

from time import perf_counter

from fastapi import APIRouter, HTTPException, Request, Response

import storage
from llm_mcp_dispatcher import dispatch_message
from observability import mask_identifier, trace
from responses import twiml_message


router = APIRouter()


@router.post("/webhook")
async def whatsapp_webhook(request: Request) -> Response:
    """Receive one Twilio message and route it through the application flow."""
    started_at = perf_counter()
    data = await request.form()
    user_message = str(data.get("Body", "")).strip()
    sender = str(data.get("From", "")).strip()
    message_sid = str(data.get("MessageSid", "")).strip()

    if not user_message or not sender:
        raise HTTPException(status_code=400, detail="Twilio webhook must include Body and From values.")

    trace(
        "webhook_received",
        sender=mask_identifier(sender),
        message_sid=mask_identifier(message_sid),
        body=user_message[:500],
    )

    with storage.SessionLocal() as session:
        is_first_message = storage.get_user(session, sender) is None
        user = storage.get_or_create_user(session, sender)

        if message_sid:
            existing_event = storage.get_webhook_event(session, message_sid)
            if existing_event is not None and existing_event.status in {"processed", "processing"}:
                trace("webhook_duplicate", message_sid=mask_identifier(message_sid), status=existing_event.status)
                return twiml_message("This message has already been received.")
            storage.begin_webhook_event(session, message_sid, user)

    try:
        reply = await dispatch_message(sender, user_message, is_first_message)
    except Exception as error:
        trace("webhook_failed", message_sid=mask_identifier(message_sid), error=str(error))
        with storage.SessionLocal() as session:
            storage.mark_webhook_failed(session, storage.get_webhook_event(session, message_sid) if message_sid else None)
        raise

    with storage.SessionLocal() as session:
        storage.mark_webhook_processed(session, storage.get_webhook_event(session, message_sid) if message_sid else None)
    trace(
        "webhook_completed",
        message_sid=mask_identifier(message_sid),
        elapsed_seconds=round(perf_counter() - started_at, 3),
        reply=reply[:500],
    )
    return twiml_message(reply)
