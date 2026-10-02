package com.abijith.expensetracker

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.Executors

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val body: String,
    val elapsedMillis: Long? = null,
)

data class ChatState(
    val messages: List<ChatMessage> = listOf(ChatMessage(
        id = "welcome",
        role = ChatRole.ASSISTANT,
        body = "Welcome to Finance Tracker. Send a message to see this build's " +
            "capabilities. You can enable SMS access in the SMS capture tab.",
    )),
    val sending: Boolean = false,
)

/** Session-only chat survives screen rotation; Python runs off the main thread. */
class ChatViewModel : ViewModel() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mutableState = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = mutableState.asStateFlow()
    private var cleared = false

    @Synchronized
    fun send(context: Context, body: String): Boolean {
        val text = body.trim()
        if (cleared || mutableState.value.sending || text.isEmpty()) return false
        val id = UUID.randomUUID().toString()
        mutableState.value = mutableState.value.copy(
            messages = (mutableState.value.messages + ChatMessage(id, ChatRole.USER, text))
                .takeLast(MAX_MESSAGES),
            sending = true,
        )
        val appContext = context.applicationContext
        executor.execute {
            val result = PythonBridge.replyToChat(appContext, id, text)
            completeReply(id, result)
        }
        return true
    }

    @Synchronized
    private fun completeReply(id: String, result: PythonChatReply) {
        if (cleared) return
        mutableState.value = mutableState.value.copy(
            messages = (mutableState.value.messages + ChatMessage(
                id = "$id-reply",
                role = ChatRole.ASSISTANT,
                body = result.reply,
                elapsedMillis = result.elapsedMillis,
            )).takeLast(MAX_MESSAGES),
            sending = false,
        )
    }

    @Synchronized
    override fun onCleared() {
        cleared = true
        executor.shutdownNow()
        super.onCleared()
    }

    companion object {
        private const val MAX_MESSAGES = 60
    }
}
