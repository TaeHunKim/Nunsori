package com.lsa.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One conversation. [enabledTools] is a comma separated list of tool names the model may use in it. */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val title: String,
    val enabledTools: String,
    /** Search the index before every question and give the model the best excerpts (no tool calling needed). */
    val autoSearch: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** Pinned chats are listed first. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
)

enum class ChatRole { USER, ASSISTANT, TOOL }

/**
 * One message. [ChatRole.TOOL] rows record a tool call the model made while answering ([toolName], [toolArgs] as JSON,
 * [text] = the result); they are shown in the chat but not replayed to the model.
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [ForeignKey(ChatSessionEntity::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val msgId: Long = 0,
    val sessionId: Long,
    val role: ChatRole,
    val text: String,
    val toolName: String? = null,
    val toolArgs: String? = null,
    /** JSON array of attachments: `{"itemId":42}` for indexed files, `{"photo":"<file name>"}` for picked photos. */
    val attachments: String? = null,
    /** Set on a scheduled task's confirmation card and on the messages one of its runs produced. */
    val taskId: Long? = null,
    val createdAt: Long,
)
