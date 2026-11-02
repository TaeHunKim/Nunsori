package com.lsa.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class TaskTrigger { TIME, FILE }

/** PENDING_CONFIRM: proposed by the model, waiting for the user. DONE: a one-time task that has run. */
enum class TaskStatus { PENDING_CONFIRM, ACTIVE, PAUSED, DONE }

enum class TaskRepeat { ONCE, EVERY_MINUTES, DAILY, WEEKLY }

enum class TaskResult { OK, FAILED, SKIPPED }

/**
 * An instruction the user asked a chat to carry out later. It lives and dies with its chat ([sessionId], cascade).
 * TIME tasks fire at [nextRunAt]; FILE tasks fire after an indexing pass that added files matching [fileQuery]/[fileKinds].
 */
@Entity(
    tableName = "scheduled_tasks",
    foreignKeys = [ForeignKey(ChatSessionEntity::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class ScheduledTaskEntity(
    @PrimaryKey(autoGenerate = true) val taskId: Long = 0,
    val sessionId: Long,
    val trigger: TaskTrigger,
    val status: TaskStatus,
    val instruction: String,
    val repeat: TaskRepeat = TaskRepeat.ONCE,
    /** Minutes after local midnight (DAILY, WEEKLY). */
    val atMinuteOfDay: Int = 0,
    /** Bit 0 = Monday … bit 6 = Sunday (WEEKLY). */
    val weekdayMask: Int = 0,
    val intervalMinutes: Int = 0,
    /** ONCE: the exact moment. */
    val onceAtMs: Long? = null,
    val nextRunAt: Long? = null,
    val fileQuery: String = "",
    /** Comma separated [ItemKind] names; empty = every kind. */
    val fileKinds: String = "",
    /** FILE: new matching item ids waiting for the next run (comma separated). */
    val pendingItemIds: String = "",
    val createdAt: Long,
    val activatedAt: Long? = null,
    val lastRunAt: Long? = null,
    val lastResult: TaskResult? = null,
    val lastError: String? = null,
)

@Dao
interface ScheduledTaskDao {
    @Insert
    suspend fun insert(task: ScheduledTaskEntity): Long

    @Update
    suspend fun update(task: ScheduledTaskEntity)

    @Query("SELECT * FROM scheduled_tasks WHERE taskId = :id")
    suspend fun get(id: Long): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE taskId = :id")
    fun observe(id: Long): Flow<ScheduledTaskEntity?>

    @Query("SELECT * FROM scheduled_tasks WHERE sessionId = :sessionId AND status != 'PENDING_CONFIRM' ORDER BY taskId")
    fun observeOfSession(sessionId: Long): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE sessionId = :sessionId ORDER BY taskId")
    suspend fun ofSession(sessionId: Long): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE status = 'ACTIVE'")
    suspend fun active(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE status = 'ACTIVE' AND `trigger` = 'FILE'")
    suspend fun activeFileTasks(): List<ScheduledTaskEntity>

    @Query("SELECT COUNT(*) FROM scheduled_tasks WHERE status IN ('ACTIVE', 'PAUSED')")
    suspend fun liveCount(): Int

    @Query("SELECT COUNT(*) FROM scheduled_tasks WHERE sessionId = :sessionId AND status IN ('ACTIVE', 'PAUSED', 'PENDING_CONFIRM')")
    suspend fun liveCountOfSession(sessionId: Long): Int

    /** Chats that have at least one scheduled (active or paused) task, for the list icon. */
    @Query("SELECT DISTINCT sessionId FROM scheduled_tasks WHERE status IN ('ACTIVE', 'PAUSED')")
    fun observeScheduledSessions(): Flow<List<Long>>

    @Query("UPDATE scheduled_tasks SET status = :status, activatedAt = :activatedAt, nextRunAt = :nextRunAt WHERE taskId = :id")
    suspend fun setStatus(id: Long, status: TaskStatus, activatedAt: Long?, nextRunAt: Long?)

    @Query("UPDATE scheduled_tasks SET nextRunAt = :nextRunAt WHERE taskId = :id")
    suspend fun setNextRun(id: Long, nextRunAt: Long?)

    @Query("UPDATE scheduled_tasks SET lastRunAt = :at, lastResult = :result, lastError = :error WHERE taskId = :id")
    suspend fun setLastRun(id: Long, at: Long, result: TaskResult, error: String?)

    @Query("UPDATE scheduled_tasks SET pendingItemIds = :ids WHERE taskId = :id")
    suspend fun setPending(id: Long, ids: String)

    @Query("DELETE FROM scheduled_tasks WHERE taskId = :id")
    suspend fun delete(id: Long)
}
