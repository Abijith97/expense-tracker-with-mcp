package com.abijith.expensetracker

import org.json.JSONObject

// Keep the active hierarchy aligned with category_examples.CATEGORY_HIERARCHY.
val SPENDING_CATEGORY_HIERARCHY = mapOf(
    "learning" to setOf("engineering"),
    "utilities" to setOf("transport", "food", "prepaid recharge", "groceries", "health"),
    "fun" to setOf("gift", "food"),
    "Not sure" to setOf("Not sure"),
)
val SPENDING_CATEGORIES = SPENDING_CATEGORY_HIERARCHY.keys
internal fun isSpendingCategoryPair(name: String?, subCategory: String?): Boolean =
    name != null && subCategory != null && subCategory in SPENDING_CATEGORY_HIERARCHY[name].orEmpty()

// Read-only compatibility: old cards keep their original labels, never inferred
// sub-categories or silent uploads. New API replies must contain a current pair.
private val LEGACY_SPENDING_CATEGORIES = setOf("Transport", "Groceries", "Food & dining", "Bills & utilities",
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
    val usage: LlmUsage? = null,
    val subCategory: String? = null,
    val networkAttempts: Int = 1,
) {
    init {
        require(networkAttempts in 0..3)
        require(if (status == CategoryStatus.IDENTIFIED) {
            if (subCategory == null) name in LEGACY_SPENDING_CATEGORIES else isSpendingCategoryPair(name, subCategory)
        } else name == null && subCategory == null)
        require(elapsedMs == null || elapsedMs.isFinite() && elapsedMs >= 0)
        require(apiElapsedMs == null || apiElapsedMs.isFinite() && apiElapsedMs >= 0)
    }

    fun toJson(): JSONObject = JSONObject().put("status", status.name).also {
        name?.let { value -> it.put("name", value) }
        subCategory?.let { value -> it.put("sub_category", value) }
        elapsedMs?.let { value -> it.put("elapsed_ms", value) }
        apiElapsedMs?.let { value -> it.put("api_elapsed_ms", value) }
        errorCode?.let { value -> it.put("error_code", value) }
        usage?.let { value -> it.put("usage", value.toJson()) }
        it.put("network_attempts", networkAttempts)
    }

    companion object {
        fun fromJson(json: JSONObject) = SmsCategory(
            status = CategoryStatus.valueOf(json.getString("status")),
            name = if (json.has("name")) json.getString("name") else null,
            elapsedMs = if (json.has("elapsed_ms")) json.getDouble("elapsed_ms") else null,
            apiElapsedMs = if (json.has("api_elapsed_ms")) json.getDouble("api_elapsed_ms") else null,
            errorCode = json.optString("error_code").takeIf { it.isNotBlank() },
            usage = LlmUsage.fromJson(json.optJSONObject("usage")),
            subCategory = if (json.has("sub_category")) json.getString("sub_category") else null,
            networkAttempts = json.optInt("network_attempts", 1),
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
