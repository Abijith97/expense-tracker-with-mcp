package com.abijith.expensetracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun CloudClassificationControls(state: CloudClassificationState,
                                onEnable: suspend (String, String, String, Boolean, Boolean) -> Boolean,
                                onDisable: suspend () -> Unit) {
    // Never use rememberSaveable for secrets: no key in Android saved state.
    var classificationApiKey by remember { mutableStateOf("") }
    var categoryApiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(state.model) }
    var consent by remember { mutableStateOf(false) }
    var rememberKeys by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cloud expenditure classification", style = MaterialTheme.typography.titleMedium)
            Text("Cloud: ${when { state.loading -> "Restoring settings"; state.enabled && state.remembered ->
                "Enabled - remembered on this device"; state.enabled -> "Enabled for this app process"; else -> "Off" }}")
            Text("Personal POC only. Keys are never embedded or logged. By default they stay in memory. " +
                "Remembering them encrypts keys and upload consent on this device for background processing. " +
                "A compromised phone/app can still expose them; this is not a distribution design.",
                style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.error != null && !state.enabled) {
                OutlinedButton(onClick = { scope.launch { busy = true; try { onDisable() } finally { busy = false } } },
                    enabled = !busy && !state.loading, modifier = Modifier.testTag("cloud_forget")) {
                    Text("Forget saved keys")
                }
            }
            if (state.enabled) {
                Text("Model: ${state.model}")
                OutlinedButton(onClick = { scope.launch { busy = true; try { onDisable() } finally { busy = false } } },
                    enabled = !busy, modifier = Modifier.testTag("cloud_disable")) {
                    Text("Disable cloud and forget keys")
                }
            } else {
                OutlinedTextField(value = classificationApiKey,
                    onValueChange = { classificationApiKey = it; error = false },
                    label = { Text("Financial classification API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("cloud_classification_key"))
                OutlinedTextField(value = categoryApiKey,
                    onValueChange = { categoryApiKey = it; error = false },
                    label = { Text("Category identification API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("cloud_category_key"))
                Text("Use two different keys dedicated to these functions to separate OpenAI-reported " +
                    "costs. SMS cards show calculated per-attempt costs, not billing totals.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = model, onValueChange = { model = it },
                    label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    Checkbox(checked = consent, onCheckedChange = { consent = it },
                        modifier = Modifier.testTag("cloud_consent"))
                    Text("I agree to send captured SMS bodies to OpenAI for classification. " +
                        "Expenditures get a second upload for category identification. " +
                        "This includes non-financial messages and may incur API charges.")
                }
                Row {
                    Checkbox(checked = rememberKeys, onCheckedChange = { rememberKeys = it },
                        enabled = !busy && !state.loading, modifier = Modifier.testTag("cloud_remember"))
                    Text("Remember keys on this device. Continue uploading new SMS after app/process " +
                        "restarts until I disable cloud. No screen needs to stay open.")
                }
                Text("Sender and message ID are not uploaded, but the body can contain personal " +
                    "details or OTPs. This POC does not redact bodies before upload. " +
                    "Use synthetic SMS for the demo. Enabling does not upload existing history.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    scope.launch {
                        busy = true
                        try {
                            error = !onEnable(classificationApiKey, categoryApiKey, model, consent, rememberKeys)
                            if (!error) { classificationApiKey = ""; categoryApiKey = ""; consent = false; rememberKeys = false }
                        } finally { busy = false }
                    }
                }, enabled = !busy && !state.loading && classificationApiKey.isNotBlank() && categoryApiKey.isNotBlank() &&
                    classificationApiKey.trim() != categoryApiKey.trim() && model.isNotBlank() && consent,
                    modifier = Modifier.testTag("cloud_enable")) { Text("Enable cloud classification") }
                if (error) Text("Enter two different keys without internal whitespace, a model, and consent.",
                    color = MaterialTheme.colorScheme.error)
            }
            Text("Pausing capture or disabling cloud stops pending work. A request already sent " +
                "cannot be recalled. No expenses are recorded in this milestone.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SmsClassificationDetails(classification: SmsClassification?, canClassify: Boolean, onClassify: () -> Unit) {
    val label = when (classification?.status) {
        null -> "Not classified"
        ClassificationStatus.WAITING -> "Waiting for background work / internet"
        ClassificationStatus.RUNNING -> "Checking expenditure / identifying category with hosted LLM"
        ClassificationStatus.EXPENDITURE -> "Expenditure (true)"
        ClassificationStatus.OTHER -> "Other (false)"
        ClassificationStatus.FAILED -> "Failed - ${classificationFailureMessage(classification.errorCode)}"
        ClassificationStatus.INTERRUPTED -> "Interrupted - you can retry manually"
    }
    Text("Classification: $label", style = MaterialTheme.typography.titleSmall)
    Text("Classification runs through the Python orchestrator.", style = MaterialTheme.typography.bodySmall)
    classification?.elapsedMs?.let {
        Text("Total analysis processing: ${String.format(Locale.US, "%.3f", it)} ms")
    }
    classification?.apiElapsedMs?.let {
        Text("Expenditure API round trip and validation: ${String.format(Locale.US, "%.3f", it)} ms")
    }
    if (classification?.status in setOf(ClassificationStatus.EXPENDITURE, ClassificationStatus.OTHER,
            ClassificationStatus.FAILED, ClassificationStatus.INTERRUPTED)) {
        LlmUsageDetails("Classification", classification?.usage)
    }
    if (classification?.status == ClassificationStatus.EXPENDITURE) {
        val category = classification.category
        val categoryLabel = when (category?.status) {
            null -> "Not identified (older result)"
            CategoryStatus.IDENTIFIED -> if (category.name == "Not sure") "Not sure - needs review"
                else if (category.subCategory == null) "${category.name} (legacy suggestion, not confirmed)"
                else "${category.name} (suggested, not confirmed)"
            CategoryStatus.FAILED -> "Failed - ${classificationFailureMessage(category.errorCode)}"
            CategoryStatus.INTERRUPTED -> "Interrupted - you can retry manually"
        }
        Text("Category: $categoryLabel", style = MaterialTheme.typography.titleSmall)
        if (category?.status == CategoryStatus.IDENTIFIED) {
            Text("Sub-category: ${when (category.subCategory) {
                null -> "Not recorded (older result)"
                "Not sure" -> "Not sure - needs review"
                else -> "${category.subCategory} (suggested, not confirmed)"
            }}", style = MaterialTheme.typography.titleSmall)
        }
        category?.elapsedMs?.let {
            Text("Category processing: ${String.format(Locale.US, "%.3f", it)} ms")
        }
        category?.apiElapsedMs?.let {
            Text("Category API round trip and validation: ${String.format(Locale.US, "%.3f", it)} ms")
        }
        LlmUsageDetails("Category", category?.usage)
    } else if (classification?.status == ClassificationStatus.OTHER) {
        Text("Category: Skipped (not an expenditure)")
    }
    if (classification != null && classification.status !in setOf(ClassificationStatus.WAITING, ClassificationStatus.RUNNING)) {
        val total = analysisEstimatedCostUsd(classification)
        Text(if (total != null) "Calculated cost this attempt: USD ${String.format(Locale.US, "%.8f", total)}"
            else "Calculated cost this attempt: Unavailable (not assumed zero)")
        Text("Calculated from reported tokens and supported rates, not a final bill. " +
            "Latest attempt only; earlier retries and unknown charges are not included. " +
            "Existing timing measurements remain separate.", style = MaterialTheme.typography.bodySmall)
    }
    if (canClassify && classification?.status !in setOf(ClassificationStatus.WAITING, ClassificationStatus.RUNNING)) {
        OutlinedButton(onClick = onClassify) {
            Text(if (classification == null) "Classify this message (uploads body)" else "Reclassify (uploads body)")
        }
    }
}

@Composable
private fun LlmUsageDetails(stage: String, usage: LlmUsage?) {
    Text(if (usage != null) "$stage tokens: input ${usage.inputTokens}, output ${usage.outputTokens}, " +
        "cached ${usage.cachedInputTokens ?: "unknown"}, cache writes ${usage.cacheWriteTokens ?: "unknown"}"
        else "$stage tokens: Unavailable")
    Text(usage?.estimatedCostUsd?.let { "$stage calculated cost: USD ${String.format(Locale.US, "%.8f", it)}" }
        ?: "$stage calculated cost: Unavailable")
    usage?.pricingDate?.let { Text("$stage pricing: ${usage.model}, ${usage.serviceTier}, rates dated $it",
        style = MaterialTheme.typography.bodySmall) }
}
