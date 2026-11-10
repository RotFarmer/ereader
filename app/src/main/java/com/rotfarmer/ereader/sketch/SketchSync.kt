package com.rotfarmer.ereader.sketch

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Sharing a sketchbook between devices.
 *
 * - Keep in sync: the sketchbook is linked to a `.sketchbook` file in a cloud folder the user
 *   picks (Google Drive, Dropbox, OneDrive…). Each sync reads that file, merges it into this
 *   device's copy, and writes the merged sketchbook back if the file was missing anything. Every
 *   linked device does the same, so they all end up with every stroke.
 * - Send a copy: a one-off `.sketchbook` file for Quick Share, email and so on. Opening it on
 *   another device adds the sketchbook there, or merges it into the copy already there.
 *
 * All of this runs on [dispatcher], one sketchbook at a time.
 */
object SketchSync {
    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "sketch-sync").apply { isDaemon = true } }.asCoroutineDispatcher()

    /** What a sync brought in from other devices. */
    class Result(val changedPages: Set<Int>, val metaChanged: Boolean) {
        val anything get() = changedPages.isNotEmpty() || metaChanged
    }

    /**
     * Brings a linked sketchbook up to date with its shared file. Returns null if it isn't
     * linked. Problems are recorded on the sketchbook (shown as its sync status), not thrown.
     */
    fun sync(context: Context, bookId: String, force: Boolean = false): Result? {
        val store = SketchStore.of(context)
        val (uriText, syncedEdits, lastStamp) = store.syncInfo(bookId) ?: return null
        val uri = Uri.parse(uriText ?: return null)
        val cr = context.contentResolver
        return try {
            val editsAtStart = store.edits(bookId)
            val stamp = stamp(cr, uri)
            var result = Result(emptySet(), false)
            var fileBehind = false
            if (force || stamp == null || stamp != lastStamp || syncedEdits < 0) {
                val copy = download(context, uri)
                if (copy == null) fileBehind = true // a new, empty file: fill it
                else try {
                    val merged = copy.inputStream().use { store.merge(it, expectedId = bookId) }
                    result = Result(merged.changedPages, merged.metaChanged)
                    fileBehind = merged.remoteIsBehind
                } finally { copy.delete() }
            }
            var newStamp = stamp
            if (fileBehind || editsAtStart != syncedEdits) {
                openForWriting(cr, uri).use { out -> store.write(bookId, out) }
                newStamp = stamp(cr, uri)
            }
            store.markSynced(bookId, editsAtStart, newStamp)
            result
        } catch (e: SecurityException) {
            store.setSyncError(bookId, "E-Reader can't reach the shared file any more. Link it again from the share menu.")
            Result(emptySet(), false)
        } catch (e: FileNotFoundException) {
            store.setSyncError(bookId, "The shared file was moved or deleted. Link it again from the share menu.")
            Result(emptySet(), false)
        } catch (e: Exception) {
            store.setSyncError(bookId, e.message ?: "Couldn't sync")
            Result(emptySet(), false)
        }
    }

    /** True if the shared file may have changed since the last sync (a cheap check, no download). */
    fun remoteChanged(context: Context, bookId: String): Boolean {
        val (uriText, _, lastStamp) = SketchStore.of(context).syncInfo(bookId) ?: return false
        val uri = Uri.parse(uriText ?: return false)
        return try { stamp(context.contentResolver, uri).let { it == null || it != lastStamp } } catch (e: Exception) { false }
    }

    /** Links [bookId] to a new, empty file the user just created in a shared folder, and fills it. */
    fun linkNewFile(context: Context, bookId: String, uri: Uri) {
        persist(context, uri)
        SketchStore.of(context).link(bookId, uri.toString(), displayName(context.contentResolver, uri) ?: "Shared sketchbook")
        sync(context, bookId, force = true)
    }

    /**
     * Opens a shared `.sketchbook` file made on another device: adds the sketchbook here (or
     * merges it into the copy already here) and keeps it in sync with that file.
     */
    fun openSharedFile(context: Context, uri: Uri): String {
        persist(context, uri)
        val cr = context.contentResolver
        val store = SketchStore.of(context)
        val copy = download(context, uri) ?: throw IOException("That file is empty")
        val merged = try { copy.inputStream().use { store.merge(it) } } finally { copy.delete() }
        store.link(merged.bookId, uri.toString(), displayName(cr, uri) ?: "Shared sketchbook")
        sync(context, merged.bookId, force = true)
        return merged.bookId
    }

    /** Adds a sketchbook from a received copy, or merges the copy into the one already here. */
    fun importCopy(context: Context, uri: Uri): MergeResult {
        val store = SketchStore.of(context)
        val copy = download(context, uri) ?: throw IOException("That file is empty")
        val merged = try { copy.inputStream().use { store.merge(it) } } finally { copy.delete() }
        // A linked sketchbook passes what the copy brought on to its shared file.
        if (merged.changedPages.isNotEmpty() || merged.metaChanged) {
            store.writableDatabase.execSQL("UPDATE books SET edits = edits + 1 WHERE id = ?", arrayOf(merged.bookId))
            sync(context, merged.bookId)
        }
        return merged
    }

    /** A `.sketchbook` copy in the app's share folder, ready to hand to the share sheet. */
    fun exportCopy(context: Context, bookId: String): File {
        val store = SketchStore.of(context)
        val title = store.get(bookId)?.title ?: "Sketchbook"
        val file = File(SharedFiles.dir(context), "${safeName(title)}.sketchbook")
        file.outputStream().use { store.write(bookId, it) }
        return file
    }

    fun shareIntent(context: Context, file: File, mime: String, subject: String): Intent =
        Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, SharedFiles.uriFor(context, file))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, subject)

    fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").trim().take(60).ifEmpty { "Sketchbook" }

    // ------------------------------------------------------------------ documents

    /**
     * Copies a (possibly cloud) document to a temporary file first, so merging never holds the
     * database while waiting on the network. Null if the document is empty.
     */
    private fun download(context: Context, uri: Uri): File? {
        val tmp = File.createTempFile("sketch-", ".tmp", context.cacheDir)
        try {
            val n = (context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the shared file")).use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            if (n == 0L) { tmp.delete(); return null }
            return tmp
        } catch (e: Exception) { tmp.delete(); throw e }
    }

    private fun persist(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try { context.contentResolver.takePersistableUriPermission(uri, flags) } catch (e: SecurityException) {
            // Some providers only grant read access; then syncing can only bring changes in.
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
        }
    }

    /** "wt" replaces the file; a few cloud providers only accept "w". */
    private fun openForWriting(cr: ContentResolver, uri: Uri): OutputStream {
        val replace = try { cr.openOutputStream(uri, "wt") } catch (e: SecurityException) { throw e } catch (e: Exception) { null }
        return replace ?: cr.openOutputStream(uri, "w") ?: throw IOException("The shared file can't be written to")
    }

    private fun stamp(cr: ContentResolver, uri: Uri): String? = try {
        cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else {
                val modified = if (c.isNull(0)) 0L else c.getLong(0)
                val size = if (c.isNull(1)) -1L else c.getLong(1)
                if (modified == 0L && size < 0) null else "$modified:$size"
            }
        }
    } catch (e: IllegalArgumentException) { null }

    fun displayName(cr: ContentResolver, uri: Uri): String? = try {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null }
}
