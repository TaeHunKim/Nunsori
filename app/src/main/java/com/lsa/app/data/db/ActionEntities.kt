package com.lsa.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** What an action card does once the user approves it. */
enum class ActionKind { CALENDAR_EVENT, CONTACT }

/** PROPOSED: waiting for the user. DONE: approved and carried out (or handed to the system screen). */
enum class ActionStatus { PROPOSED, DONE, DISMISSED, FAILED }

/**
 * Something the model proposed to do for the user (add a calendar event or a contact, later also move or delete files).
 * It only happens after the user approves the card in the chat. Lives and dies with its chat ([sessionId], cascade).
 * [payload] is the JSON the executor of [kind] needs.
 */
@Entity(
    tableName = "pending_actions",
    foreignKeys = [ForeignKey(ChatSessionEntity::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val actionId: Long = 0,
    val sessionId: Long,
    val kind: ActionKind,
    val status: ActionStatus,
    val payload: String,
    val createdAt: Long,
    /** What happened when approved (or why it failed). */
    val resultNote: String? = null,
)

@Dao
interface PendingActionDao {
    @Insert
    suspend fun insert(a: PendingActionEntity): Long

    @Query("SELECT * FROM pending_actions WHERE actionId = :id")
    suspend fun get(id: Long): PendingActionEntity?

    @Query("SELECT * FROM pending_actions WHERE actionId = :id")
    fun observe(id: Long): Flow<PendingActionEntity?>

    @Query("UPDATE pending_actions SET status = :status, resultNote = :note WHERE actionId = :id")
    suspend fun setStatus(id: Long, status: ActionStatus, note: String?)

    @Query("SELECT COUNT(*) FROM pending_actions WHERE sessionId = :sessionId AND status = 'PROPOSED'")
    suspend fun proposedCount(sessionId: Long): Int
}
