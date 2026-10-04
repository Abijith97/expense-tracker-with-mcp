package com.abijith.expensetracker

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.launch

/** Exists only in the debug source set. No broadcast, inbox write, or SMS send. */
internal object SimulatedSms {
    const val MAX_BODY_LENGTH = 12000
    const val EXPENDITURE_EXAMPLE = "Spent Rs.100 On HDFC Bank Card 0000 At BLINKIT On 2026-10-04:12:00:00."
    const val OTHER_EXAMPLE = "Get Rs.100 cashback on your next recharge. Offer ends soon!"

    fun isValid(sender: String, body: String): Boolean =
        sender.isNotBlank() && sender.length <= 100 && body.isNotBlank() && body.length <= MAX_BODY_LENGTH

    fun create(sender: String, body: String): CapturedSms {
        require(isValid(sender, body))
        val now = System.currentTimeMillis()
        // Independent tests with identical text must not be mistaken for duplicate broadcasts.
        return CapturedSms("sim-${UUID.randomUUID()}", sender.trim(), body, now, now,
            pythonHandoff = PythonHandoff(HandoffStatus.PENDING), simulated = true)
    }
}

/** The Activity calls this on Dispatchers.IO; permission/capture/consent still apply. */
internal fun submitSimulatedSms(context: Context, store: SmsStore, sender: String, body: String,
                                allowCloud: Boolean): Boolean {
    if (!SimulatedSms.isValid(sender, body)) return false
    val generation = store.activeGeneration() ?: return false
    return SmsProcessor.forContext(context, store, allowCloud)
        .process(SimulatedSms.create(sender, body), generation)
}

@Composable
internal fun SmsSimulationControls(enabled: Boolean, cloudEnabled: Boolean, cloudLoading: Boolean,
                                   onSimulate: suspend (String, String, Boolean) -> Boolean) {
    var open by remember { mutableStateOf(false) }
    // Drafts can contain sensitive text: do not place them in saved-instance state.
    var sender by remember { mutableStateOf("DEMO") }
    var body by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit(draft: Pair<String, String>, allowCloud: Boolean) {
        busy = true
        scope.launch {
            try {
                val saved = onSimulate(draft.first, draft.second, allowCloud)
                notice = if (saved) "Test card saved below. ${if (allowCloud) "Cloud analysis runs in the background when allowed." else "Local Python handoff only; no cloud request was scheduled."}"
                    else "Could not add test message. Check capture, SMS permission, and local storage."
                if (saved) body = ""
            } catch (_: Exception) {
                notice = "Could not add test message. No private error details are displayed."
            } finally { busy = false }
        }
    }

    OutlinedButton(onClick = { open = true; notice = null }, modifier = Modifier.fillMaxWidth().testTag("simulate_sms_open")) {
        Text("Simulate SMS (debug only)")
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { if (!busy) { open = false; body = ""; confirmation = null } },
            title = { Text("Simulate SMS") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Adds a labelled test card to this app only. No SMS is sent and your inbox is unchanged. " +
                        "Uses the same local Python handoff and optional background classification/category worker.")
                    Text("Does not test SMS reception, receiver/delivery timings, or closed-app delivery.",
                        style = MaterialTheme.typography.bodySmall)
                    if (!enabled) Text("Enable SMS capture and grant permission first.")
                    if (cloudLoading) Text("Wait for cloud settings to finish restoring.")
                    Text(if (cloudEnabled) "Cloud is ON: submitting uploads the body to OpenAI and can incur API charges. Expenditures use two calls."
                        else "Cloud is OFF: local handoff only. Enable cloud in SMS capture for classification.")
                    OutlinedTextField(sender, { sender = it }, label = { Text("Test sender") }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("simulate_sms_sender"))
                    OutlinedTextField(body, { body = it }, label = { Text("Sample SMS body") }, minLines = 3, maxLines = 6,
                        enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("simulate_sms_body"))
                    Text("Use synthetic text; maximum ${SimulatedSms.MAX_BODY_LENGTH} characters.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { body = SimulatedSms.EXPENDITURE_EXAMPLE }, enabled = !busy,
                        modifier = Modifier.testTag("simulate_sms_expense_example")) { Text("Load expenditure example") }
                    OutlinedButton(onClick = { body = SimulatedSms.OTHER_EXAMPLE }, enabled = !busy,
                        modifier = Modifier.testTag("simulate_sms_other_example")) { Text("Load other example") }
                    notice?.let { Text(it, modifier = Modifier.testTag("simulate_sms_notice")) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val draft = sender to body
                    if (cloudEnabled) confirmation = draft else submit(draft, false)
                }, enabled = enabled && !cloudLoading && !busy && SimulatedSms.isValid(sender, body),
                    modifier = Modifier.testTag("simulate_sms_submit")) { Text(if (busy) "Adding..." else "Add test message") }
            },
            dismissButton = {
                TextButton(onClick = { open = false; body = ""; confirmation = null }, enabled = !busy) { Text("Close") }
            },
        )
    }
    confirmation?.let { draft ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("Allow this test's API charges?") },
            text = { Text("This test body will be uploaded using your enabled cloud settings. " +
                "Classification is one API call; an expenditure gets a second category call. " +
                "Costs and timings appear on the test card. This is not a free or mocked LLM test.") },
            confirmButton = {
                TextButton(onClick = { confirmation = null; submit(draft, true) },
                    modifier = Modifier.testTag("simulate_sms_confirm_cloud")) { Text("Submit with cloud") }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }, modifier = Modifier.testTag("simulate_sms_cancel_cloud")) { Text("Cancel") }
            },
        )
    }
}
