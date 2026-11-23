package com.sfdm.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun statusToString(s: DownloadStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): DownloadStatus = DownloadStatus.valueOf(s)
    @TypeConverter fun modeToString(m: NetworkMode): String = m.name
    @TypeConverter fun stringToMode(s: String): NetworkMode = NetworkMode.valueOf(s)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','CONNECTING','RUNNING','WAITING_NETWORK') ORDER BY createdAt ASC")
    suspend fun getActive(): List<DownloadEntity>

    @Insert
    suspend fun insert(d: DownloadEntity): Long

    @Update
    suspend fun update(d: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, error: String? = null)

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, wifiBytes = :wifi, mobileBytes = :mobile WHERE id = :id")
    suspend fun setProgress(id: Long, downloaded: Long, wifi: Long, mobile: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM segments WHERE downloadId = :downloadId ORDER BY start ASC")
    suspend fun segments(downloadId: Long): List<SegmentEntity>

    @Query("DELETE FROM segments WHERE downloadId = :downloadId")
    suspend fun deleteSegments(downloadId: Long)

    @Insert
    suspend fun insertSegments(s: List<SegmentEntity>)

    @Transaction
    suspend fun replaceSegments(downloadId: Long, s: List<SegmentEntity>) {
        deleteSegments(downloadId)
        insertSegments(s)
    }
}

@Database(entities = [DownloadEntity::class, SegmentEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN quality TEXT")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "sfdm.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
