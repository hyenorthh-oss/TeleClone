package com.teleclone.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.teleclone.app.data.model.CloneTaskEntity
import com.teleclone.app.data.model.CloneTaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CloneTaskDao {

    @Query("SELECT * FROM clone_tasks ORDER BY updatedAt DESC")
    fun observeAllTasks(): Flow<List<CloneTaskEntity>>

    @Query(
        "SELECT * FROM clone_tasks WHERE status IN ('${CloneTaskStatus.RUNNING}', '${CloneTaskStatus.PAUSED}') " +
            "ORDER BY updatedAt DESC LIMIT 1"
    )
    fun observeActiveTask(): Flow<CloneTaskEntity?>

    @Query("SELECT * FROM clone_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CloneTaskEntity?

    @Query("SELECT COALESCE(SUM(clonedCount), 0) FROM clone_tasks")
    fun observeTotalClonedMessages(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: CloneTaskEntity)

    @Update
    suspend fun update(task: CloneTaskEntity)

    @Delete
    suspend fun delete(task: CloneTaskEntity)

    @Query("DELETE FROM clone_tasks WHERE id = :taskId")
    suspend fun deleteById(taskId: Long)

    @Query(
        "UPDATE clone_tasks SET lastClonedMessageId = :lastClonedMessageId, " +
            "clonedCount = :clonedCount, updatedAt = :updatedAt WHERE id = :taskId"
    )
    suspend fun updateProgress(
        taskId: Long,
        lastClonedMessageId: Long,
        clonedCount: Int,
        updatedAt: Long,
    )

    @Query("UPDATE clone_tasks SET status = :status, updatedAt = :updatedAt WHERE id = :taskId")
    suspend fun updateStatus(taskId: Long, status: String, updatedAt: Long)

    @Query("UPDATE clone_tasks SET totalMessagesEstimate = :total, updatedAt = :updatedAt WHERE id = :taskId")
    suspend fun updateTotalEstimate(taskId: Long, total: Int, updatedAt: Long)
}
