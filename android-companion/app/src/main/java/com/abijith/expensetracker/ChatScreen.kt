package com.abijith.expensetracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** App-owned chat UI; messages stay in memory for the current app session. */
@Composable
fun ChatScreen(state: ChatState, onSend: (String) -> Boolean) {
    var draft by rememberSaveable { mutableStateOf("") }
    val scrollState = rememberLazyListState()
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (state.messages.isNotEmpty()) scrollState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(
        modifier = Modifier.fillMaxSize().imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Finance Tracker", style = MaterialTheme.typography.headlineSmall)
        Text("Built-in help is available. Expense tracking and AI answers are coming later.",
            style = MaterialTheme.typography.bodySmall)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = scrollState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (message.role == ChatRole.USER) "You" else "Tracker",
                            style = MaterialTheme.typography.labelLarge)
                        Text(message.body)
                        message.elapsedMillis?.let {
                            Text("Processed locally in $it ms",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (state.sending) Text("Processing your message...")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Message") },
                maxLines = 4,
                enabled = !state.sending,
                modifier = Modifier.weight(1f).testTag("chat_input"),
            )
            Button(
                onClick = { if (onSend(draft)) draft = "" },
                enabled = draft.isNotBlank() && !state.sending,
                modifier = Modifier.testTag("chat_send"),
            ) { Text("Send") }
        }
    }
}
