package com.ryn.aispeed.core.database

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ryn.aispeed.core.security.KeystoreKeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Entity(
    tableName = "downloads",
    indices = [Index(value = ["status", "priority", "created_at"])]
)
data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    @ColumnInfo(name = "final_url") val finalUrl: String? = null,
    @ColumnInfo(name = "file_name") val fileName: String,
    val category: String = "OTHER",
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "file_size") val fileSize: Long? = null,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long = 0L,
    val status: String = "QUEUED",
    val priority: Int = 1,
    @ColumnInfo(name = "max_connections") val maxConnections: Int = 4,
    @ColumnInfo(name = "speed_limit_bps") val speedLimitBps: Long = 0L,
    @ColumnInfo(name = "supports_ranges") val supportsRanges: Int = 0,
    val etag: String? = null,
    @ColumnInfo(name = "last_modified") val lastModified: String? = null,
    @ColumnInfo(name = "sha256_expected") val sha256Expected: String? = null,
    @ColumnInfo(name = "sha256_actual") val sha256Actual: String? = null,
    @ColumnInfo(name = "temp_path") val tempPath: String? = null,
    @ColumnInfo(name = "save_path") val savePath: String,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
    @ColumnInfo(name = "last_error_message") val lastErrorMessage: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(
    tableName = "download_segments",
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["download_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["download_id", "status"])]
)
data class DownloadSegmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "download_id") val downloadId: String,
    @ColumnInfo(name = "segment_index") val segmentIndex: Int,
    @ColumnInfo(name = "start_byte") val startByte: Long,
    @ColumnInfo(name = "end_byte") val endByte: Long,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long = 0L,
    val status: String = "PENDING",
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY priority DESC, created_at DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(entity: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegments(segments: List<DownloadSegmentEntity>)

    @Query("SELECT * FROM download_segments WHERE download_id = :downloadId ORDER BY segment_index ASC")
    suspend fun getSegments(downloadId: String): List<DownloadSegmentEntity>
}

@Database(
    entities = [DownloadEntity::class, DownloadSegmentEntity::class],
    version = 1,
    exportSchema = true
)
abstract class RynDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideRynDatabase(@ApplicationContext context: Context): RynDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = KeystoreKeyManager(context).getOrCreateDatabasePassphrase()
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(context, RynDatabase::class.java, "ryn_ai_speed.db")
            .openHelperFactory(factory)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }

    @Provides
    fun provideDownloadDao(db: RynDatabase): DownloadDao = db.downloadDao()
}
