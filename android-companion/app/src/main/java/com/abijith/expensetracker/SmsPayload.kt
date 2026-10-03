package com.abijith.expensetracker

import org.json.JSONObject

/** Encode the versioned Python request once, independently of Python startup. */
object SmsPayload {
    fun encode(message: CapturedSms): String = JSONObject()
        .put("schema_version", 1)
        .put("message_id", message.id)
        .put("sender", message.sender)
        .put("body", message.body)
        .put("sent_at_ms", message.sentAtMillis)
        .put("received_at_ms", message.receivedAtMillis)
        .toString()
}
