package com.yenreh.caligo.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.yenreh.caligo.AppInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Backup files on their way out of the app and into it */
interface BackupFiles {
    /** Write [text] as the backup file and offer it through the share sheet */
    suspend fun share(text: String)

    /** The text of the document the user picked */
    suspend fun read(uri: Uri): String
}

/**
 * [BackupFiles] through the system: the file is written to the cache, as
 * share_plus did from the temporary directory, and handed out through
 * the app's FileProvider; a picked document is read through its provider.
 */
class AndroidBackupFiles(private val context: Context) : BackupFiles {
    override suspend fun share(text: String) {
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, SHARED_FOLDER).apply { mkdirs() }
                .resolve(FILE_NAME)
                .apply { writeText(text) }
        }
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "${AppInfo.NAME} Backup")
            // The clip carries the read grant through the chooser
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // From the application, so the share outlives the screen that asked
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    override suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: error("Cannot open $uri")
        stream.bufferedReader().use { it.readText() }
    }

    companion object {
        const val FILE_NAME = "caligo_backup.json"
        const val MIME_TYPE = "application/json"

        /** Under the cache, as named in res/xml/file_paths.xml */
        private const val SHARED_FOLDER = "shared"

        fun authority(context: Context) = "${context.packageName}.files"
    }
}
