package com.keralalottery.print.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

private class Converters {
    @TypeConverter
    fun fromAttachmentType(t: AttachmentType): String = t.name

    @TypeConverter
    fun toAttachmentType(s: String): AttachmentType = AttachmentType.valueOf(s)
}

@Database(
    entities = [SavedCalc::class, DiaryEntry::class, DiaryAttachment::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun savedCalcDao(): SavedCalcDao
    abstract fun diaryDao(): DiaryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kerala_info_hub.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
