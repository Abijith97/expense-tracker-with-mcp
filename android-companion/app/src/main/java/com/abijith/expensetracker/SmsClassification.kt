package com.abijith.expensetracker

import org.json.JSONObject

enum class ClassificationStatus { WAITING, RUNNING, EXPENDITURE, OTHER, FAILED, INTERRUPTED }

/** Diagnostic result, not an expense record. Neither SMS content nor key is here. */
data class SmsClassification(
    val status: ClassificationStatus,
    val attemptId: String,
    val model: String? = null,
    val elapsedMs: Double? = null,
    val apiElapsedMs: Double? = null,
    val errorCode: String? = null,
    val category: SmsCategory? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("status", status.name).put("attempt_id", attemptId).also {
        model?.let { value -> it.put("model", value) }
        elapsedMs?.let { value -> it.put("elapsed_ms", value) }
        apiElapsedMs?.let { value -> it.put("api_elapsed_ms", value) }
        errorCode?.let { value -> it.put("error_code", value) }
        category?.let { value -> it.put("category", value.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject): SmsClassification {
            val status = ClassificationStatus.valueOf(json.getString("status"))
            // A running call cannot be resumed/replayed safely after process death.
            return SmsClassification(
                status = if (status == ClassificationStatus.RUNNING) ClassificationStatus.INTERRUPTED else status,
                attemptId = json.getString("attempt_id"),
                model = json.optString("model").takeIf { it.isNotBlank() },
                elapsedMs = if (json.has("elapsed_ms")) json.getDouble("elapsed_ms") else null,
                apiElapsedMs = if (json.has("api_elapsed_ms")) json.getDouble("api_elapsed_ms") else null,
                errorCode = json.optString("error_code").takeIf { it.isNotBlank() },
                // Older saved classifications have no category; do not replay them.
                category = json.optJSONObject("category")?.let(SmsCategory::fromJson),
            )
        }
    }
}

fun classificationFailureMessage(code: String?): String = when (code) {
    "authentication" -> "API key rejected. Check your key."
    "access_denied", "request_rejected" -> "Request or model access rejected. Check the selected model and project permissions."
    "rate_limit_or_quota" -> "Rate limit or API quota reached. Check API usage and billing."
    "timeout" -> "API request timed out. Retry when ready."
    "network" -> "Could not reach OpenAI. Check internet access."
    "configuration" -> "Enable cloud classification again with both API keys and consent."
    "refused" -> "The model refused to classify this message."
    "invalid_input" -> "Message is empty or too long for this POC."
    "invalid_response", "incomplete_response" -> "No valid classification was returned. Retry when ready."
    "service_unavailable" -> "OpenAI is temporarily unavailable."
    else -> "Classification did not complete. You can retry manually."
}
