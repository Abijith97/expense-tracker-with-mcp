package com.abijith.expensetracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Correlated forms, not free-text chat parsing; only submitted feedback persists. */
@Composable
internal fun CategoryReviewCard(
    message: CapturedSms,
    onEdit: suspend (String, String, String, String, Long) -> Boolean = { _, _, _, _, _ -> false },
    showMessage: Boolean = true,
    onConfirm: suspend (String, String, String, String) -> Boolean,
) {
    val attempt = message.classification?.attemptId.orEmpty()
    val review = message.categoryReview
    var category by remember(message.id, attempt, review) { mutableStateOf(review?.category.orEmpty()) }
    var subCategory by remember(message.id, attempt, review) { mutableStateOf(review?.subCategory.orEmpty()) }
    var editing by remember(message.id, attempt, review) { mutableStateOf(false) }
    var saving by remember(message.id, attempt, review) { mutableStateOf(false) }
    var error by remember(message.id, attempt, review) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun saveLabels(categorySnapshot: String, subCategorySnapshot: String) {
        saving = true
        scope.launch {
            try {
                error = if (review == null) !onConfirm(message.id, attempt, categorySnapshot, subCategorySnapshot)
                    else !onEdit(message.id, review.attemptId, categorySnapshot, subCategorySnapshot, review.confirmedAtMillis)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { saving = false }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("category_review_${message.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (review == null) "Which category fits this payment?" else "Category saved",
                style = MaterialTheme.typography.titleMedium)
            if (showMessage) {
                Text("From: ${message.sender}")
                Text("Captured: ${DateFormat.getDateTimeInstance().format(Date(message.receivedAtMillis))}",
                    style = MaterialTheme.typography.bodySmall)
                Text(message.body)
            }
            if (review != null && !editing) {
                Text("Confirmed category: ${review.category}")
                Text("Confirmed sub-category: ${review.subCategory}")
                Text("Saved on this phone, not model training.", style = MaterialTheme.typography.bodySmall)
                if (message.classification?.status == ClassificationStatus.EXPENDITURE) {
                    OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("review_edit_${message.id}")) {
                        Text("Edit category")
                    }
                }
            } else if (message.needsCategoryValidation() && !message.needsCategoryReview() && !editing) {
                val suggestion = message.classification!!.category!!
                Text("Suggested: ${suggestion.name} / ${suggestion.subCategory}")
                Text("Was this predicted correctly?")
                Button(enabled = !saving, modifier = Modifier.testTag("review_yes_${message.id}"),
                    onClick = { saveLabels(suggestion.name!!, suggestion.subCategory!!) }) { Text("Yes") }
                OutlinedButton(enabled = !saving, modifier = Modifier.testTag("review_no_${message.id}"),
                    onClick = { category = ""; subCategory = ""; editing = true }) { Text("No, correct it") }
            } else if (message.needsCategoryValidation() || editing) {
                Text(if (review != null) "Edit your saved labels. Original model output is unchanged."
                    else "Enter your own labels or use these examples:")
                Text("learning / engineering\nutilities / transport, food, prepaid recharge, groceries, health\nfun / gift, food",
                    style = MaterialTheme.typography.bodySmall)
                Text("Custom labels are welcome, including clothing. Choose the category that fits your finances.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(category, { category = it; error = false },
                    label = { Text("Category") }, singleLine = true, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("review_category_${message.id}"))
                OutlinedTextField(subCategory, { subCategory = it; error = false },
                    label = { Text("Sub-category") }, singleLine = true, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("review_subcategory_${message.id}"))
                Text("Both fields are required (up to 80 characters each). Submit saves feedback locally; no API call.",
                    style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = !saving && validReviewLabel(category) && validReviewLabel(subCategory),
                    modifier = Modifier.testTag("review_submit_${message.id}"),
                    onClick = {
                        saveLabels(category.trim(), subCategory.trim())
                    },
                ) { Text(if (saving) "Saving..." else if (review != null) "Save changes" else "Submit category") }
                if (review != null) OutlinedButton(enabled = !saving, onClick = {
                    category = review.category; subCategory = review.subCategory; editing = false; error = false
                }) { Text("Cancel edit") }
            }
            if (error) Text("Could not save. The message may have changed or been cleared. Check it and try again.",
                color = MaterialTheme.colorScheme.error)
        }
    }
}
