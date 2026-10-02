package com.abijith.expensetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.abijith.expensetracker.ui.theme.FinanceTrackerCompanionTheme
import java.text.DateFormat
import java.util.Date

/** Setup/debug screen only. The SMS receiver also works while this screen is closed. */
class MainActivity : ComponentActivity() {
    private lateinit var store: SmsStore
    private var permissionGranted by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)
    private var showPermissionRationale by mutableStateOf(false)
    private var showClearConfirmation by mutableStateOf(false)

    private val requestSmsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        store.setEnabled(granted)
        notice = if (granted) {
            "Permission granted. Send an ordinary test SMS to this phone."
        } else {
            "Permission was not granted. You can try again or use Open app settings if Android no longer shows a prompt."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SmsStore.get(this)
        enableEdgeToEdge()
        setContent {
            val captureState by store.state.collectAsState()
            FinanceTrackerCompanionTheme {
                SmsDebugScreen(
                    state = captureState,
                    permissionGranted = permissionGranted,
                    notice = notice,
                    onEnable = ::enableCapture,
                    onPause = {
                        if (store.setEnabled(false)) {
                            notice = "Capture paused. Existing diagnostic copies remain until cleared."
                        }
                    },
                    onSettings = {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:$packageName")),
                        )
                    },
                    onClear = { showClearConfirmation = true },
                )

                if (showPermissionRationale) {
                    AlertDialog(
                        onDismissRequest = { showPermissionRationale = false },
                        title = { Text("Allow new SMS capture?") },
                        text = {
                            Text("SMS permission lets this POC display new messages on this phone. " +
                                "It captures all new SMS while enabled, not only financial messages. " +
                                "Nothing is uploaded or sent. You can pause capture at any time.")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showPermissionRationale = false
                                requestSmsPermission.launch(Manifest.permission.RECEIVE_SMS)
                            }) { Text("Continue") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermissionRationale = false }) { Text("Not now") }
                        },
                    )
                }

                if (showClearConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showClearConfirmation = false },
                        title = { Text("Clear captured messages?") },
                        text = { Text("This removes only this app's diagnostic copies. Your SMS inbox is unchanged.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showClearConfirmation = false
                                if (store.clear()) notice = "Captured copies cleared. Your SMS inbox is unchanged."
                            }) { Text("Clear copies") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions can be revoked in Settings while this screen is away.
        permissionGranted = hasSmsPermission()
        if (!permissionGranted) store.setEnabled(false)
    }

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun enableCapture() {
        notice = null
        permissionGranted = hasSmsPermission()
        when {
            permissionGranted -> store.setEnabled(true)
            shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS) ->
                showPermissionRationale = true
            else -> requestSmsPermission.launch(Manifest.permission.RECEIVE_SMS)
        }
    }
}

@Composable
private fun SmsDebugScreen(
    state: SmsCaptureState,
    permissionGranted: Boolean,
    notice: String?,
    onEnable: () -> Unit,
    onPause: () -> Unit,
    onSettings: () -> Unit,
    onClear: () -> Unit,
) {
    val capturing = permissionGranted && state.enabled
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Finance Tracker Companion", style = MaterialTheme.typography.headlineSmall)
                Text("Milestone 2: on-phone Python handoff", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Text("While enabled, this app keeps the latest ${SmsStore.MAX_MESSAGES} incoming SMS messages " +
                    "on this phone, including non-financial messages. Use synthetic messages for testing. " +
                    "Python acknowledges each new capture inside this APK. " +
                    "No internet access, AI processing, or WhatsApp connection is included.")
            }
            item {
                Text("SMS permission: ${if (permissionGranted) "Granted" else "Not granted"}")
                Text("Capture: ${if (capturing) "Enabled" else "Paused"}")
                Text("Captured messages: ${state.messages.size} / ${SmsStore.MAX_MESSAGES}")
            }
            state.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            notice?.let { message -> item { Text(message) } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (capturing) {
                        Button(onClick = onPause, modifier = Modifier.fillMaxWidth()) { Text("Pause capture") }
                    } else {
                        Button(
                            onClick = onEnable,
                            enabled = state.error == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (permissionGranted) "Start capture" else "Enable SMS access") }
                    }
                    OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Open app settings")
                    }
                    OutlinedButton(
                        onClick = onClear,
                        enabled = state.messages.isNotEmpty() || state.error != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Clear captured messages") }
                }
            }
            item {
                Text("New SMS only: no inbox history, RCS, or MMS. Android may delay or hide protected OTP messages. " +
                    "Backgrounding is supported; Force Stop prevents receipt until you reopen the app.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.messages.isEmpty()) {
                item { Text("No captured messages yet. Enable capture, then send an ordinary SMS to this phone.") }
            }
            items(state.messages, key = { it.id }) { message ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("From: ${message.sender}", style = MaterialTheme.typography.titleMedium)
                        Text("SMS timestamp: ${formatTimestamp(message.sentAtMillis)}", style = MaterialTheme.typography.bodySmall)
                        Text("Captured locally: ${formatTimestamp(message.receivedAtMillis)}", style = MaterialTheme.typography.bodySmall)
                        Text(message.body)
                        PythonHandoffDetails(message.pythonHandoff)
                    }
                }
            }
        }
    }
}

@Composable
private fun PythonHandoffDetails(handoff: PythonHandoff?) {
    val status = when (handoff?.status) {
        null -> "Not processed (captured before the Python handoff update)"
        HandoffStatus.PENDING -> "Pending"
        HandoffStatus.ACKNOWLEDGED -> "Acknowledged"
        HandoffStatus.FAILED -> "Failed; the SMS is still saved. Send a new test SMS after troubleshooting."
        HandoffStatus.INTERRUPTED -> "Interrupted or paused; the SMS is still saved. Send a new test SMS to retry."
    }
    Text("Python: $status", style = MaterialTheme.typography.titleSmall)
    handoff?.elapsedMillis?.let { elapsed ->
        Text("Handoff time: $elapsed ms (includes Python startup on the first call)",
            style = MaterialTheme.typography.bodySmall)
    }
    handoff?.acknowledgement?.let { acknowledgement ->
        Text(acknowledgement, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatTimestamp(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(millis))
