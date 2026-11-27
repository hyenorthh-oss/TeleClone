package com.teleclone.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.teleclone.app.data.model.CloneMessageEntity
import com.teleclone.app.data.model.CloneMessageStatus

@Dao
interface CloneMessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(items: List<CloneMessageEntity>)

    @Query(
        "SELECT * FROM clone_messages WHERE taskId = :taskId AND status = '${CloneMessageStatus.PENDING}' " +
            "ORDER BY messageId ASC"
    )
    suspend fun getPendingMessages(taskId: Long): List<CloneMessageEntity>

    @Query("SELECT * FROM clone_messages WHERE taskId = :taskId ORDER BY messageId ASC")
    suspend fun getAllForTask(taskId: Long): List<CloneMessageEntity>

    @Query("SELECT COUNT(*) FROM clone_messages WHERE taskId = :taskId AND status = :status")
    suspend fun countByStatus(taskId: Long, status: String): Int

    @Query(
        "UPDATE clone_messages SET status = '${CloneMessageStatus.SENT}', errorMessage = '' " +
            "WHERE taskId = :taskId AND messageId IN (:messageIds)"
    )
    suspend fun markSentChunk(taskId: Long, messageIds: List<Long>)

    @Query(
        "UPDATE clone_messages SET status = '${CloneMessageStatus.FAILED_PERMANENT}', " +
            "attemptCount = attemptCount + 1, errorMessage = :reason " +
            "WHERE taskId = :taskId AND messageId IN (:messageIds)"
    )
    suspend fun markFailedPermanentChunk(taskId: Long, messageIds: List<Long>, reason: String)

    @Query(
        "UPDATE clone_messages SET attemptCount = attemptCount + 1, errorMessage = :reason " +
            "WHERE taskId = :taskId AND messageId IN (:messageIds)"
    )
    suspend fun incrementAttemptChunk(taskId: Long, messageIds: List<Long>, reason: String)

    @Query("DELETE FROM clone_messages WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)
}
