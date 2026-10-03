package com.abijith.expensetracker

import org.json.JSONObject

// Keep the phone-side whitelist aligned with category_examples.CATEGORIES.
val SPENDING_CATEGORIES = setOf("Transport", "Groceries", "Food & dining", "Bills & utilities",
    "Subscriptions", "Shopping", "Health", "Education", "Entertainment", "Housing",
    "Personal care", "Other", "Not sure")

enum class CategoryStatus { IDENTIFIED, FAILED, INTERRUPTED }

/** A suggested category, not a confirmed expense or evidence of model training. */
data class SmsCategory(
    val status: CategoryStatus,
    val name: String? = null,
    val elapsedMs: Double? = null,
    val apiElapsedMs: Double? = null,
    val errorCode: String? = null,
) {
    init {
        require(if (status == CategoryStatus.IDENTIFIED) name in SPENDING_CATEGORIES else name == null)
        require(elapsedMs == null || elapsedMs.isFinite() && elapsedMs >= 0)
        require(apiElapsedMs == null || apiElapsedMs.isFinite() && apiElapsedMs >= 0)
    }

    fun toJson(): JSONObject = JSONObject().put("status", status.name).also {
        name?.let { value -> it.put("name", value) }
        elapsedMs?.let { value -> it.put("elapsed_ms", value) }
        apiElapsedMs?.let { value -> it.put("api_elapsed_ms", value) }
        errorCode?.let { value -> it.put("error_code", value) }
    }

    companion object {
        fun fromJson(json: JSONObject) = SmsCategory(
            status = CategoryStatus.valueOf(json.getString("status")),
            name = if (json.has("name")) json.getString("name") else null,
            elapsedMs = if (json.has("elapsed_ms")) json.getDouble("elapsed_ms") else null,
            apiElapsedMs = if (json.has("api_elapsed_ms")) json.getDouble("api_elapsed_ms") else null,
            errorCode = json.optString("error_code").takeIf { it.isNotBlank() },
        )
    }
}

/** The fixed workflow's gate: no category request for Other or failed inference.
 * Android must recheck permission, capture, retained attempt, and cloud consent
 * between hosted calls. A category error does not overwrite the expenditure.
 */
internal fun withCategoryIfExpenditure(classification: SmsClassification,
                                      canContinue: () -> Boolean,
                                      identify: () -> SmsCategory): SmsClassification {
    if (classification.status != ClassificationStatus.EXPENDITURE) return classification
    return classification.copy(category = if (canContinue()) identify()
        else SmsCategory(CategoryStatus.INTERRUPTED))
}
