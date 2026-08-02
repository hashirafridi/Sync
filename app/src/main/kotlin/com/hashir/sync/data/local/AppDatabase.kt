package com.hashir.sync.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {

    // Enums are stored as their names rather than ordinals on purpose: ordinals silently
    // break the moment someone reorders the enum, and the corruption is invisible.

    @TypeConverter
    fun postTypeToString(value: PostType): String = value.name

    @TypeConverter
    fun stringToPostType(value: String): PostType =
        runCatching { PostType.valueOf(value) }.getOrDefault(PostType.TEXT)

    @TypeConverter
    fun jobStateToString(value: JobState): String = value.name

    @TypeConverter
    fun stringToJobState(value: String): JobState =
        runCatching { JobState.valueOf(value) }.getOrDefault(JobState.FAILED)
}

@Database(
    entities = [
        FbAccountEntity::class,
        FbPageEntity::class,
        PublishJobEntity::class,
        DownloadRecordEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun pageDao(): PageDao
    abstract fun publishJobDao(): PublishJobDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        const val NAME: String = "fbpublisher.db"
    }
}
