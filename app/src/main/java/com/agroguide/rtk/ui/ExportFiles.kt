package com.agroguide.rtk.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Writes an export file and opens the share sheet. */
object ExportFiles {
    private const val AUTHORITY = "com.agroguide.rtk.files"

    fun writeAndShare(context: Context, fileName: String, mime: String, content: ByteArray) {
        val file = File(exportDir(context), fileName)
        file.writeBytes(content)
        share(context, file, mime)
    }

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(intent, context.getString(com.agroguide.rtk.R.string.share_via))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Export directory. External app storage is preferred (easy to reach from a
     * PC), but it is unavailable while the device is in some USB modes or when
     * the volume is not mounted — fall back to internal storage, which is also
     * declared in the FileProvider paths.
     */
    fun exportDir(context: Context): File {
        val external = context.getExternalFilesDir(null)
        val dir = if (external != null) File(external, "export") else File(context.filesDir, "export")
        dir.mkdirs()
        return dir
    }
}
