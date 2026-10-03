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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.abijith.expensetracker.ui.theme.FinanceTrackerCompanionTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** App chat and SMS diagnostics. The receiver also works while the screen is closed. */
class MainActivity : ComponentActivity() {
    private lateinit var store: SmsStore
    private lateinit var chat: ChatViewModel
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
        chat = ViewModelProvider(this)[ChatViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            val captureState by store.state.collectAsState()
            val chatState by chat.state.collectAsState()
            val cloudState by CloudClassificationSettings.state.collectAsState()
            var selectedTab by rememberSaveable { mutableStateOf(0) }
            FinanceTrackerCompanionTheme {
                Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                            text = { Text("Chat") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                            text = { Text("SMS capture") })
                    }
                    if (selectedTab == 0) {
                        ChatScreen(chatState, onSend = { chat.send(this@MainActivity, it) })
                    } else {
                        SmsDebugScreen(
                            state = captureState,
                            cloudState = cloudState,
                            permissionGranted = permissionGranted,
                            notice = notice,
                            onEnable = ::enableCapture,
                            onPause = {
                                if (store.setEnabled(false)) {
                                    SmsClassificationWorker.cancelAll(this@MainActivity)
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
                            onEnableCloud = { key, model, consent ->
                                CloudClassificationSettings.enable(key, model, consent)
                            },
                            onDisableCloud = {
                                CloudClassificationSettings.disable()
                                SmsClassificationWorker.cancelAll(this@MainActivity)
                            },
                            onClassify = { id -> store.activeGeneration()?.let {
                                SmsClassificationWorker.enqueue(this@MainActivity, id, it)
                            } },
                        )
                    }
                }

                if (showPermissionRationale) {
                    AlertDialog(
                        onDismissRequest = { showPermissionRationale = false },
                        title = { Text("Allow new SMS capture?") },
                        text = {
                            Text("SMS permission lets this POC display new messages on this phone. " +
                                "It captures all new SMS while enabled, not only financial messages. " +
                                "Capture alone stays local. Cloud classification is a separate " +
                                "opt-in that uploads SMS bodies to OpenAI. You can pause at any time.")
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
                                if (store.clear()) {
                                    SmsClassificationWorker.cancelAll(this@MainActivity)
                                    notice = "Captured copies cleared. Your SMS inbox is unchanged."
                                }
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
        if (!permissionGranted) {
            store.setEnabled(false)
            SmsClassificationWorker.cancelAll(this)
        }
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
    cloudState: CloudClassificationState,
    permissionGranted: Boolean,
    notice: String?,
    onEnable: () -> Unit,
    onPause: () -> Unit,
    onSettings: () -> Unit,
    onClear: () -> Unit,
    onEnableCloud: (String, String, Boolean) -> Boolean,
    onDisableCloud: () -> Unit,
    onClassify: (String) -> Unit,
) {
    val capturing = permissionGranted && state.enabled
    Scaffold(modifier = Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Finance Tracker", style = MaterialTheme.typography.headlineSmall)
                Text("Milestone 4: expenditure classification", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Text("While enabled, this app keeps the latest ${SmsStore.MAX_MESSAGES} incoming SMS messages " +
                    "on this phone, including non-financial messages. Use synthetic messages for testing. " +
                    "Python acknowledges each new capture inside this APK. " +
                    "Optional hosted classification identifies completed outgoing payments; " +
                    "it does not yet record expenses or identify spending categories.")
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
            item { CloudClassificationControls(cloudState, onEnableCloud, onDisableCloud) }
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
                        SmsClassificationDetails(message.classification,
                            canClassify = capturing && cloudState.enabled,
                            onClassify = { onClassify(message.id) })
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
        val display = handoff.performance?.bridgeTotalMs?.let { String.format(Locale.US, "%.3f", it) }
            ?: elapsed.toString()
        Text("Handoff time: $display ms (includes bridge wait and Python startup when needed)",
            style = MaterialTheme.typography.bodySmall)
    }
    handoff?.performance?.let { SmsPerformanceDetails(it) }
    handoff?.acknowledgement?.let { acknowledgement ->
        Text(acknowledgement, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SmsPerformanceDetails(performance: SmsPerformance) {
    Text("Processing timings", style = MaterialTheme.typography.titleSmall)
    val stages = listOf(
        "App entry to JSON ready" to performance.receiverToJsonReadyMs,
        "JSON conversion for Python" to performance.jsonEncodeMs,
        "Receiver setup" to performance.receiverSetupMs,
        "Executor wait" to performance.executorWaitMs,
        "Message preparation" to performance.messagePreparationMs,
        "Initial save" to performance.initialSaveMs,
        "Bridge wait" to performance.bridgeWaitMs,
        "Python setup" to performance.pythonSetupMs,
        "Python call" to performance.pythonCallMs,
        "Receipt validation" to performance.receiptValidationMs,
        "App entry to handoff result" to performance.receiverToHandoffMs,
    )
    stages.forEach { (label, duration) -> duration?.let {
        Text("$label: ${String.format(Locale.US, "%.3f", it)} ms",
            style = MaterialTheme.typography.bodySmall)
    } }
    Text("Python started in this call: ${performance.runtimeStarted}",
        style = MaterialTheme.typography.bodySmall)
    performance.smsTimestampToReceiverMs?.let {
        Text("SMS timestamp to app entry: $it ms (delivery/clock estimate, not app-only detection)",
            style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatTimestamp(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(millis))
