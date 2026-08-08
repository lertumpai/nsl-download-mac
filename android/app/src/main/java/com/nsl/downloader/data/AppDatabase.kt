package com.nsl.downloader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [VideoEntity::class, FolderEntity::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun folderDao(): FolderDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE videos ADD COLUMN lastPositionMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds library folders and the per-entry mime type. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE videos ADD COLUMN folderId INTEGER")
                database.execSQL(
                    "ALTER TABLE videos ADD COLUMN mimeType TEXT NOT NULL DEFAULT 'video/mp4'"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS folders (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Keeps the download request on the row, so a download that failed can
         * be resumed later without whatever queued it still being alive.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                listOf(
                    "req_kind TEXT NOT NULL DEFAULT 'GENERIC'",
                    "req_headers TEXT NOT NULL DEFAULT ''",
                    "req_ytFormat TEXT NOT NULL DEFAULT 'MP4'",
                    "req_ytHeight INTEGER NOT NULL DEFAULT 0",
                    "req_mp3Bitrate INTEGER NOT NULL DEFAULT 192",
                    "req_userAgent TEXT NOT NULL DEFAULT ''",
                    "req_folderName TEXT"
                ).forEach { database.execSQL("ALTER TABLE videos ADD COLUMN $it") }
                // Rows from before this column existed never recorded how they
                // were fetched, and the default above would claim they were
                // plain file downloads — a YouTube watch URL fetched that way
                // is a saved web page, not a video. Blanking the kind marks
                // them as unknown; DownloadService.inferKind reads it back off
                // the source URL when one of them is resumed.
                database.execSQL("UPDATE videos SET req_kind = ''")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nsl_downloader.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
    }
}
