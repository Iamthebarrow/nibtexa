package io.github.iamthebarrow.nibtexa.storage

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File

/**
 * Android's answer to a damaged database is to delete it. This keeps a copy first (Phase 33), so that nothing is lost for good, and leaves
 * a note for the app to tell the user when it next opens.
 */
class KeepDamagedDatabase(private val folder: File) : SupportSQLiteOpenHelper.Factory {
    private val inner = FrameworkSQLiteOpenHelperFactory()

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val callback = configuration.callback
        val keeping = object : SupportSQLiteOpenHelper.Callback(callback.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                keep(db.path)
                callback.onCorruption(db)
            }
        }
        return inner.create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(keeping)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                .build(),
        )
    }

    private fun keep(path: String?) {
        val source = path?.let(::File)?.takeIf { it.isFile } ?: return
        try {
            val copy = File(folder, "${System.currentTimeMillis()}").apply { mkdirs() }
            for (name in listOf(source.name, "${source.name}-wal", "${source.name}-shm")) {
                val file = File(source.parentFile, name)
                if (file.isFile) file.copyTo(File(copy, name), overwrite = true)
            }
            Log.e(TAG, "The database was damaged: a copy is in $copy")
        } catch (e: Exception) {
            Log.e(TAG, "Could not keep a copy of the damaged database", e)
        }
    }

    companion object {
        private const val TAG = "KeepDamagedDatabase"

        fun folder(context: Context) = File(context.filesDir, "damaged-notes")

        /** When the latest damaged copy was kept, in milliseconds; 0 if there is none. */
        fun latest(context: Context): Long = folder(context).listFiles().orEmpty().mapNotNull { it.name.toLongOrNull() }.maxOrNull() ?: 0L
    }
}
