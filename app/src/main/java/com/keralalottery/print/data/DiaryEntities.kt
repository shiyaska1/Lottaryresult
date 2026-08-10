package com.keralalottery.print.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class AttachmentType { IMAGE, VIDEO, AUDIO, DOCUMENT }

/** A personal diary entry: a title, free-form body text, and its attachments. */
@Entity(tableName = "diary_entries")
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** A file attached to a diary entry, copied into app-private storage. */
@Entity(tableName = "diary_attachments")
data class DiaryAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val path: String,
    val name: String,
    val mime: String,
    val type: AttachmentType
)

@Dao
interface DiaryDao {
    @Query(
        "SELECT * FROM diary_entries " +
            "WHERE title LIKE '%' || :q || '%' OR body LIKE '%' || :q || '%' " +
            "ORDER BY updatedAt DESC"
    )
    fun search(q: String): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM diary_entries WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): DiaryEntry?

    @Insert
    suspend fun insert(entry: DiaryEntry): Long

    @androidx.room.Update
    suspend fun update(entry: DiaryEntry)

    @Delete
    suspend fun delete(entry: DiaryEntry)

    @Query("SELECT * FROM diary_attachments WHERE entryId = :entryId ORDER BY id ASC")
    suspend fun attachmentsFor(entryId: Long): List<DiaryAttachment>

    @Insert
    suspend fun insertAttachment(attachment: DiaryAttachment): Long

    @Delete
    suspend fun deleteAttachment(attachment: DiaryAttachment)

    @Query("DELETE FROM diary_attachments WHERE entryId = :entryId")
    suspend fun deleteAttachmentsFor(entryId: Long)
}
