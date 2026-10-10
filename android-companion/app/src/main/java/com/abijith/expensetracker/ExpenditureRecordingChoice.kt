package com.abijith.expensetracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The switch changes recording intent, not SMS capture, analysis or visibility. */
@Composable
internal fun ExpenditureRecordingChoice(
    message: CapturedSms,
    onChoose: suspend (String, String, Boolean) -> Boolean,
) {
    if (message.classification?.status != ClassificationStatus.EXPENDITURE) return
    var saving by remember(message.id, message.classification.attemptId) { mutableStateOf(false) }
    var error by remember(message.id, message.classification.attemptId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Do you want to record this expenditure?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = message.recordExpenditure == true, enabled = !saving,
                modifier = Modifier.testTag("record_expenditure_${message.id}"),
                onCheckedChange = { selected ->
                    saving = true
                    scope.launch {
                        try { error = !onChoose(message.id, message.classification.attemptId, selected) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                        finally { saving = false }
                    }
                })
            Text(when (message.recordExpenditure) {
                true -> "Selected for recording"
                false -> "Excluded from recording"
                null -> "Not selected (choose whether to record)"
            })
        }
        Text("Choice saved locally. Expense-ledger updates and training are not implemented yet.")
        if (error) Text("Could not save the choice. Check the message and try again.")
    }
}
