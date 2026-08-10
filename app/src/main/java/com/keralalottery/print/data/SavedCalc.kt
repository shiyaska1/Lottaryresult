package com.keralalottery.print.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A saved calculator tape. Amounts are kept as a comma-separated list rather than a second
 * table - a tape is only ever read and written whole. Minus entries are stored negative,
 * exactly as they are on screen.
 */
@Entity(tableName = "saved_calcs")
data class SavedCalc(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateMillis: Long,
    val amounts: String,
    val total: Double,
    val label: String = ""
) {
    val amountList: List<Double>
        get() = amounts.split(',').mapNotNull { it.trim().toDoubleOrNull() }

    companion object {
        fun pack(values: List<Double>) = values.joinToString(",")
    }
}

@Dao
interface SavedCalcDao {
    @Query("SELECT * FROM saved_calcs ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<SavedCalc>>

    @Insert
    suspend fun insert(c: SavedCalc): Long

    @Query("DELETE FROM saved_calcs WHERE id = :id")
    suspend fun delete(id: Long)
}
