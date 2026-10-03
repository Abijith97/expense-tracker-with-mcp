package com.abijith.expensetracker

import org.json.JSONObject

/** Actual reported token counts, plus an optional calculated USD estimate. No secrets. */
data class LlmUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val model: String? = null,
    val serviceTier: String? = null,
    val estimatedCostUsd: Double? = null,
    val pricingDate: String? = null,
) {
    init {
        require(inputTokens in 0L..10_000_000L && outputTokens in 0L..10_000_000L)
        require(cachedInputTokens == null || cachedInputTokens in 0..inputTokens)
        require(cacheWriteTokens == null || cacheWriteTokens in 0..inputTokens)
        require(cachedInputTokens == null || cacheWriteTokens == null ||
            cachedInputTokens + cacheWriteTokens <= inputTokens)
        require(model == null || model.matches(Regex("[A-Za-z0-9._-]{1,100}")))
        require(serviceTier == null || serviceTier in setOf("default", "priority", "flex", "ultrafast"))
        require(pricingDate == null || pricingDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        require(estimatedCostUsd == null || estimatedCostUsd.isFinite() && estimatedCostUsd >= 0 &&
            cachedInputTokens != null && cacheWriteTokens != null && model == DEFAULT_CLASSIFIER_MODEL &&
            serviceTier == "default" && pricingDate == "2026-10-03")
    }

    fun toJson(): JSONObject = JSONObject().put("input_tokens", inputTokens).put("output_tokens", outputTokens).also {
        cachedInputTokens?.let { value -> it.put("cached_input_tokens", value) }
        cacheWriteTokens?.let { value -> it.put("cache_write_tokens", value) }
        model?.let { value -> it.put("model", value) }
        serviceTier?.let { value -> it.put("service_tier", value) }
        estimatedCostUsd?.let { value -> it.put("estimated_cost_usd", value) }
        pricingDate?.let { value -> it.put("pricing_date", value) }
    }

    companion object {
        /** Invalid/missing telemetry is unavailable, never a zero-cost claim or inference error. */
        fun fromJson(json: JSONObject?): LlmUsage? {
            if (json == null) return null
            return try {
                fun count(name: String): Long {
                    val value = json.get(name)
                    require(value is Int || value is Long)
                    return (value as Number).toLong()
                }
                fun optionalCount(name: String) = if (json.has(name)) count(name) else null
                val cost = if (json.has("estimated_cost_usd")) {
                    val value = json.get("estimated_cost_usd")
                    require(value is Number)
                    value.toDouble()
                } else null
                LlmUsage(count("input_tokens"), count("output_tokens"), optionalCount("cached_input_tokens"),
                    optionalCount("cache_write_tokens"), json.optString("model").takeIf { it.isNotBlank() },
                    json.optString("service_tier").takeIf { it.isNotBlank() }, cost,
                    json.optString("pricing_date").takeIf { it.isNotBlank() })
            } catch (_: Exception) { null }
        }
    }
}

/** Only total the current attempt when every required stage has a known cost. */
fun analysisEstimatedCostUsd(classification: SmsClassification): Double? {
    val first = classification.usage?.estimatedCostUsd ?: return null
    return when (classification.status) {
        ClassificationStatus.OTHER, ClassificationStatus.FAILED -> first
        ClassificationStatus.EXPENDITURE -> classification.category?.takeIf {
            it.status in setOf(CategoryStatus.IDENTIFIED, CategoryStatus.FAILED)
        }?.usage?.estimatedCostUsd?.let { first + it }
        else -> null
    }
}
