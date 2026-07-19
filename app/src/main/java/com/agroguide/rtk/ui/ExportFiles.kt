package com.agroguide.rtk.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Writes an export file to app-external storage and opens the share sheet. */
object ExportFiles {
    fun writeAndShare(context: Context, fileName: String, mime: String, content: ByteArray) {
        val dir = File(context.getExternalFilesDir(null), "export").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(content)
        share(context, file, mime)
    }

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "com.agroguide.rtk.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(intent, context.getString(com.agroguide.rtk.R.string.share_via))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun exportDir(context: Context): File = File(context.getExternalFilesDir(null), "export").apply { mkdirs() }
}
