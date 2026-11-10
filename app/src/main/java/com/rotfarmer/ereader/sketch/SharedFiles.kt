package com.rotfarmer.ereader.sketch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands files the app made for sharing (sketchbook copies, page pictures, PDFs) to other apps
 * through the share sheet, read-only, one file at a time.
 */
class SharedFiles : ContentProvider() {
    companion object {
        private fun authority(context: Context) = "${context.packageName}.files"

        /** Where files for sharing are written. Old ones are cleared out after a day. */
        fun dir(context: Context): File = File(context.cacheDir, "shared").apply {
            mkdirs()
            listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 24 * 3600_000L }?.forEach { it.delete() }
        }

        fun uriFor(context: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(file.name).build()
    }

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val base = File(ctx.cacheDir, "shared").canonicalFile
        val f = File(base, name).canonicalFile
        if (f.parentFile != base || !f.isFile) throw FileNotFoundException(name)
        return f
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
        "png" -> "image/png"
        "pdf" -> "application/pdf"
        else -> "application/zip"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { c -> when (c) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
