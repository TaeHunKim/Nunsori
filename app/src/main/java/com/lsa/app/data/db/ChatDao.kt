package com.lsa.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY pinned DESC, updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE sessionId = :id")
    suspend fun session(id: Long): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE sessionId = :id")
    fun observeSession(id: Long): Flow<ChatSessionEntity?>

    @Insert
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Query("UPDATE chat_sessions SET title = :title WHERE sessionId = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE chat_sessions SET enabledTools = :tools, autoSearch = :autoSearch WHERE sessionId = :id")
    suspend fun setTools(id: Long, tools: String, autoSearch: Boolean)

    @Query("UPDATE chat_sessions SET pinned = :pinned WHERE sessionId = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE chat_sessions SET updatedAt = :at WHERE sessionId = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("DELETE FROM chat_sessions WHERE sessionId = :id")
    suspend fun deleteSession(id: Long)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :id ORDER BY msgId")
    fun observeMessages(id: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :id ORDER BY msgId")
    suspend fun messages(id: Long): List<ChatMessageEntity>

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :id")
    suspend fun messageCount(id: Long): Int
}
