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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun CloudClassificationControls(state: CloudClassificationState,
                                onEnable: (String, String, Boolean) -> Boolean,
                                onDisable: () -> Unit) {
    // Never use rememberSaveable for secrets: no key in Android saved state.
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(state.model) }
    var consent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cloud expenditure classification", style = MaterialTheme.typography.titleMedium)
            Text("Cloud: ${if (state.enabled) "Enabled for this app process" else "Off"}")
            Text("Personal POC only. Your key stays in phone memory, not the APK, source code, " +
                "logs, or disk. Re-enter it after Android ends the process. A distributed app " +
                "must use a backend to protect API keys.", style = MaterialTheme.typography.bodySmall)
            if (state.enabled) {
                Text("Model: ${state.model}")
                OutlinedButton(onClick = onDisable, modifier = Modifier.testTag("cloud_disable")) {
                    Text("Disable cloud classification")
                }
            } else {
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it; error = false },
                    label = { Text("OpenAI API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("cloud_key"))
                OutlinedTextField(value = model, onValueChange = { model = it },
                    label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    Checkbox(checked = consent, onCheckedChange = { consent = it },
                        modifier = Modifier.testTag("cloud_consent"))
                    Text("I agree to send captured SMS bodies to OpenAI for classification. " +
                        "This includes non-financial messages and may incur API charges.")
                }
                Text("Sender and message ID are not uploaded, but the body can contain personal " +
                    "details or OTPs. This POC does not redact bodies before upload. " +
                    "Use synthetic SMS for the demo. Enabling does not upload existing history.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    error = !onEnable(apiKey, model, consent)
                    if (!error) { apiKey = ""; consent = false }
                }, enabled = apiKey.isNotBlank() && model.isNotBlank() && consent,
                    modifier = Modifier.testTag("cloud_enable")) { Text("Enable cloud classification") }
                if (error) Text("Check the key, model, and consent.", color = MaterialTheme.colorScheme.error)
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
        ClassificationStatus.RUNNING -> "Classifying with hosted LLM"
        ClassificationStatus.EXPENDITURE -> "Expenditure (true)"
        ClassificationStatus.OTHER -> "Other (false)"
        ClassificationStatus.FAILED -> "Failed - ${classificationFailureMessage(classification.errorCode)}"
        ClassificationStatus.INTERRUPTED -> "Interrupted - you can retry manually"
    }
    Text("Classification: $label", style = MaterialTheme.typography.titleSmall)
    Text("MCP tool: classify_financial_message", style = MaterialTheme.typography.bodySmall)
    classification?.elapsedMs?.let {
        Text("Classification processing: ${String.format(Locale.US, "%.3f", it)} ms")
    }
    classification?.apiElapsedMs?.let {
        Text("Hosted API round trip and validation: ${String.format(Locale.US, "%.3f", it)} ms")
    }
    if (canClassify && classification?.status !in setOf(ClassificationStatus.WAITING, ClassificationStatus.RUNNING)) {
        OutlinedButton(onClick = onClassify) {
            Text(if (classification == null) "Classify this message (uploads body)" else "Reclassify (uploads body)")
        }
    }
}
