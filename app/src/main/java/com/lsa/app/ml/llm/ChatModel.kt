package com.lsa.app.ml.llm

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

enum class TurnRole { USER, ASSISTANT }

/** One finished turn of a conversation. Tool calls of earlier turns are not replayed to the model. */
data class ChatTurn(val role: TurnRole, val text: String, val images: List<ByteArray> = emptyList())

/** A function the model may call while answering. */
interface ChatTool {
    val name: String
    val description: String

    /** JSON schema of the arguments: `{"type":"object","properties":{...},"required":[...]}`. */
    val parameters: String

    /** Runs the tool (on a background thread) and returns the text that goes back to the model. */
    fun execute(args: JSONObject): String
}

sealed interface ChatEvent {
    /** A piece of the answer text. */
    data class Token(val text: String) : ChatEvent

    /** The model called a tool; [result] is what it received. */
    data class ToolRan(val name: String, val args: String, val result: String) : ChatEvent
}

/** A loaded local language model. */
interface ChatModel : AutoCloseable {
    /** True when images can be passed in [ChatTurn.images]. */
    val supportsImages: Boolean

    /**
     * Answers [user]. [conversationKey] identifies the conversation (session + tool selection): while it stays the same
     * and [history] is what the model already knows, the model's own state is reused instead of re-reading [history].
     * Cancelling the collector stops the generation. Fails with an exception on model errors.
     */
    fun reply(conversationKey: String, system: String, history: List<ChatTurn>, user: ChatTurn, tools: List<ChatTool>): Flow<ChatEvent>

    /** Drops any cached conversation state (history was edited or deleted). */
    fun reset()
}
