package com.abijith.expensetracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
    onConfirm: suspend (String, String, String, String) -> Boolean,
) {
    val attempt = message.classification?.attemptId.orEmpty()
    var category by remember(message.id, attempt) { mutableStateOf("") }
    var subCategory by remember(message.id, attempt) { mutableStateOf("") }
    var saving by remember(message.id, attempt) { mutableStateOf(false) }
    var error by remember(message.id, attempt) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    OutlinedCard(Modifier.fillMaxWidth().testTag("category_review_${message.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (message.categoryReview == null) "Which category fits this payment?" else "Category saved",
                style = MaterialTheme.typography.titleMedium)
            Text("From: ${message.sender}")
            Text("Captured: ${DateFormat.getDateTimeInstance().format(Date(message.receivedAtMillis))}",
                style = MaterialTheme.typography.bodySmall)
            Text(message.body)
            val review = message.categoryReview
            if (review != null) {
                Text("Confirmed category: ${review.category}")
                Text("Confirmed sub-category: ${review.subCategory}")
                Text("Saved on this phone, not model training.", style = MaterialTheme.typography.bodySmall)
            } else if (message.needsCategoryReview()) {
                Text("The model returned Not sure. Enter your own labels or use these examples:")
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
                if (error) Text("Could not save. The message may have changed or been cleared. Check it and try again.",
                    color = MaterialTheme.colorScheme.error)
                Button(
                    enabled = !saving && validReviewLabel(category) && validReviewLabel(subCategory),
                    modifier = Modifier.testTag("review_submit_${message.id}"),
                    onClick = {
                        val categorySnapshot = category.trim()
                        val subCategorySnapshot = subCategory.trim()
                        saving = true
                        scope.launch {
                            try {
                                error = !onConfirm(message.id, attempt, categorySnapshot, subCategorySnapshot)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Never put input or storage exception details in logs/UI.
                                error = true
                            } finally { saving = false }
                        }
                    },
                ) { Text(if (saving) "Saving..." else "Submit category") }
            }
        }
    }
}
