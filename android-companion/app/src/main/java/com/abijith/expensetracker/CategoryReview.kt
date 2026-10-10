package com.abijith.expensetracker

import org.json.JSONObject

/** Human feedback is separate from the original model suggestion and telemetry.
 * Custom labels are allowed here, not in the model's fixed output schema.
 * Saving this record neither uploads it nor trains or changes the model/prompt.
 */
data class CategoryReview(
    val category: String,
    val subCategory: String,
    val attemptId: String,
    val confirmedAtMillis: Long,
) {
    init {
        require(validReviewLabel(category) && category == category.trim())
        require(validReviewLabel(subCategory) && subCategory == subCategory.trim())
        require(attemptId.isNotBlank() && confirmedAtMillis >= 0)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("category", category).put("sub_category", subCategory)
        .put("attempt_id", attemptId).put("confirmed_at_millis", confirmedAtMillis)

    companion object {
        fun fromJson(json: JSONObject) = CategoryReview(
            json.getString("category"), json.getString("sub_category"),
            json.getString("attempt_id"), json.getLong("confirmed_at_millis"),
        )
    }
}

internal fun validReviewLabel(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.isNotEmpty() && trimmed.length <= 80 &&
        !trimmed.equals("Not sure", ignoreCase = true) && !trimmed.any { it.isISOControl() }
}

/** Failed calls are not uncertainty, and non-expenditures never need this form. */
internal fun CapturedSms.needsCategoryReview(): Boolean = categoryReview == null &&
    classification?.status == ClassificationStatus.EXPENDITURE &&
    classification.category?.let {
        it.status == CategoryStatus.IDENTIFIED && it.name == "Not sure" && it.subCategory == "Not sure"
    } == true

/** A confident current pair can be confirmed with Yes, or corrected with No. */
internal fun CapturedSms.needsCategoryValidation(): Boolean = categoryReview == null &&
    classification?.status == ClassificationStatus.EXPENDITURE &&
    classification.category?.let {
        it.status == CategoryStatus.IDENTIFIED && it.subCategory != null
    } == true
