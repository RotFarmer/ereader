package com.rotfarmer.ereader.sketch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.InkStroke
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

/** A sketchbook as listed in the library. */
data class Sketchbook(
    val id: String,
    val title: String,
    val pages: Int,
    val paper: Int,
    val currentPage: Int,
    val updated: Long,
    val usedPages: Int,
    val syncUri: String?,
    val syncName: String?,
    val syncedAt: Long,
    val syncError: String?,
) {
    val shared: Boolean get() = syncUri != null
}

/** What merging a sketchbook file changed here. */
class MergeResult(val bookId: String, val created: Boolean, val changedPages: Set<Int>, val metaChanged: Boolean, val remoteIsBehind: Boolean)

/**
 * Sketchbooks on this device. Strokes are never edited: erasing marks a stroke deleted (keeping
 * its id), which is what lets two devices merge their copies safely.
 */
class SketchStore private constructor(context: Context) : SQLiteOpenHelper(context, "sketches.db", null, 2) {
    /** Imported pictures and documents, one file each, named by their SHA-256 (so never stored twice). */
    val assetsDir = File(context.filesDir, "sketch-assets").apply { mkdirs() }
    fun assetFile(name: String) = File(assetsDir, name)

    companion object {
        /** Paper styles. */
        const val BLANK = 0
        const val LINED = 1
        const val GRID = 2
        const val DOTS = 3

        @Volatile private var instance: SketchStore? = null
        fun of(context: Context): SketchStore = instance ?: synchronized(this) {
            instance ?: SketchStore(context.applicationContext).also { instance = it }
        }

        /** Sketchbook database work from the screens runs here, one task at a time. */
        val io: CoroutineDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "sketches").apply { isDaemon = true } }.asCoroutineDispatcher()

        private val random = SecureRandom()

        /** Random, so strokes drawn on different devices never share an id. */
        fun newStrokeId(): Long {
            while (true) { val v = random.nextLong() and Long.MAX_VALUE; if (v != 0L) return v }
        }

        fun pointsToBlob(p: FloatArray): ByteArray = ByteBuffer.allocate(p.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(p) }.array()
        fun blobToPoints(b: ByteArray): FloatArray = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { fb -> FloatArray(fb.remaining()).also { fb.get(it) } }

        fun toInk(r: SketchStrokeRecord) = InkStroke(r.id, 0L, Anchor(Anchor.PDF, r.page.toLong(), 0), r.tool, r.color, r.width, r.pts, r.created)
    }

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE books(
                id TEXT PRIMARY KEY, title TEXT NOT NULL, title_time INTEGER NOT NULL,
                paper INTEGER NOT NULL DEFAULT 0, paper_time INTEGER NOT NULL DEFAULT 0,
                pages INTEGER NOT NULL, current_page INTEGER NOT NULL DEFAULT 0,
                created INTEGER NOT NULL, updated INTEGER NOT NULL,
                edits INTEGER NOT NULL DEFAULT 0, synced_edits INTEGER NOT NULL DEFAULT 0,
                sync_uri TEXT, sync_name TEXT, synced_at INTEGER NOT NULL DEFAULT 0,
                remote_stamp TEXT, sync_error TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE strokes(
                id INTEGER PRIMARY KEY, book TEXT NOT NULL, page INTEGER NOT NULL,
                tool INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL,
                created INTEGER NOT NULL, points BLOB NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX strokes_page ON strokes(book, page, deleted)")
        createItems(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createItems(db)
    }

    private fun createItems(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE items(
                id INTEGER PRIMARY KEY, book TEXT NOT NULL, page INTEGER NOT NULL, kind INTEGER NOT NULL,
                asset TEXT NOT NULL, source_page INTEGER NOT NULL, x REAL NOT NULL, y REAL NOT NULL,
                w REAL NOT NULL, h REAL NOT NULL, created INTEGER NOT NULL, time INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX items_page ON items(book, page, deleted)")
    }

    private inline fun <T> tx(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { return block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    // ------------------------------------------------------------------ books

    private val listSql = """SELECT b.id, b.title, b.pages, b.paper, b.current_page, b.updated,
        (SELECT COUNT(*) FROM (SELECT page FROM strokes WHERE book = b.id AND deleted = 0 UNION SELECT page FROM items WHERE book = b.id AND deleted = 0)),
        b.sync_uri, b.sync_name, b.synced_at, b.sync_error FROM books b"""

    private fun books(where: String, args: Array<String>?): List<Sketchbook> =
        readableDatabase.rawQuery("$listSql $where", args).use { c ->
            buildList {
                while (c.moveToNext()) add(Sketchbook(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getLong(5), c.getInt(6),
                    c.getString(7), c.getString(8), c.getLong(9), c.getString(10)))
            }
        }

    fun list(): List<Sketchbook> = books("ORDER BY b.updated DESC", null)
    fun get(id: String): Sketchbook? = books("WHERE b.id = ?", arrayOf(id)).firstOrNull()

    fun create(title: String, pages: Int = 350): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        writableDatabase.insertOrThrow("books", null, ContentValues().apply {
            put("id", id); put("title", title.trim().ifEmpty { "Sketchbook" }); put("title_time", now)
            put("pages", pages.coerceIn(1, SketchFile.MAX_PAGES)); put("created", now); put("updated", now); put("edits", 1)
        })
        return id
    }

    private fun change(id: String, values: ContentValues) {
        values.put("updated", System.currentTimeMillis())
        writableDatabase.update("books", values, "id = ?", arrayOf(id))
        writableDatabase.execSQL("UPDATE books SET edits = edits + 1 WHERE id = ?", arrayOf(id))
    }

    fun rename(id: String, title: String) = change(id, ContentValues().apply { put("title", title.trim()); put("title_time", System.currentTimeMillis()) })
    fun setPaper(id: String, paper: Int) = change(id, ContentValues().apply { put("paper", paper); put("paper_time", System.currentTimeMillis()) })

    fun addPages(id: String, count: Int) {
        val b = get(id) ?: return
        change(id, ContentValues().apply { put("pages", (b.pages + count).coerceAtMost(SketchFile.MAX_PAGES)) })
    }

    /** Only remembered on this device (each device keeps its own place). */
    fun setCurrentPage(id: String, page: Int) {
        writableDatabase.update("books", ContentValues().apply { put("current_page", page); put("updated", System.currentTimeMillis()) }, "id = ?", arrayOf(id))
    }

    /** Removes the sketchbook from this device only. A shared file elsewhere is left alone. */
    fun delete(id: String) {
        tx { db ->
            db.delete("strokes", "book = ?", arrayOf(id))
            db.delete("items", "book = ?", arrayOf(id))
            db.delete("books", "id = ?", arrayOf(id))
        }
        // Files no other sketchbook uses.
        val used = readableDatabase.rawQuery("SELECT DISTINCT asset FROM items", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assetsDir.listFiles()?.filter { it.isFile && it.name !in used && SketchFile.isAssetName(it.name) }?.forEach { it.delete() }
    }

    fun meta(id: String): SketchMeta? = readableDatabase.rawQuery(
        "SELECT title, title_time, paper, paper_time, pages, created FROM books WHERE id = ?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) SketchMeta(id, c.getString(0), c.getLong(1), c.getInt(2), c.getLong(3), c.getInt(4), c.getLong(5)) else null }

    // ------------------------------------------------------------------ strokes

    fun strokes(id: String, page: Int): List<InkStroke> = readableDatabase.rawQuery(
        "SELECT id, tool, color, width, created, points FROM strokes WHERE book = ? AND page = ? AND deleted = 0 ORDER BY created, id",
        arrayOf(id, "$page")
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(InkStroke(c.getLong(0), 0L, Anchor(Anchor.PDF, page.toLong(), 0), c.getInt(1), c.getInt(2), c.getFloat(3), blobToPoints(c.getBlob(5)), c.getLong(4)))
        }
    }

    fun addStrokes(id: String, page: Int, list: List<InkStroke>) {
        if (list.isEmpty()) return
        tx { db ->
            for (s in list) db.insertWithOnConflict("strokes", null, ContentValues().apply {
                put("id", s.id); put("book", id); put("page", page); put("tool", s.tool); put("color", s.color)
                put("width", s.width); put("created", s.created); put("points", pointsToBlob(s.pts))
            }, SQLiteDatabase.CONFLICT_IGNORE)
            change(id, ContentValues())
        }
    }

    /** Erased strokes keep their id (with no points) so other devices learn they were erased. */
    fun eraseStrokes(id: String, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        tx { db ->
            for (s in ids) db.execSQL("UPDATE strokes SET deleted = ?, points = x'' WHERE id = ? AND book = ? AND deleted = 0", arrayOf<Any>(now, s, id))
            change(id, ContentValues())
        }
    }

    /** Pages with drawing, pictures or documents on them, in order. */
    fun usedPages(id: String): List<Int> = readableDatabase.rawQuery(
        "SELECT page FROM strokes WHERE book = ? AND deleted = 0 UNION SELECT page FROM items WHERE book = ? AND deleted = 0 ORDER BY page", arrayOf(id, id)
    ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }

    /** The page changed most recently, for the library picture. */
    fun latestPage(id: String): Int? = readableDatabase.rawQuery(
        "SELECT page, created AS t FROM strokes WHERE book = ? AND deleted = 0 UNION ALL SELECT page, time AS t FROM items WHERE book = ? AND deleted = 0 ORDER BY t DESC LIMIT 1", arrayOf(id, id)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

    // ------------------------------------------------------------------ pictures and documents

    private fun item(c: android.database.Cursor, book: String) = SketchItem(c.getLong(0), c.getInt(1), c.getInt(2), c.getString(3), c.getInt(4),
        c.getFloat(5), c.getFloat(6), c.getFloat(7), c.getFloat(8), c.getLong(9), c.getLong(10), c.getLong(11))

    private val itemCols = "id, page, kind, asset, source_page, x, y, w, h, created, time, deleted"

    /** What's placed on a page, bottom first: document pages, then pictures in the order added. */
    fun items(id: String, page: Int): List<SketchItem> = readableDatabase.rawQuery(
        "SELECT $itemCols FROM items WHERE book = ? AND page = ? AND deleted = 0 ORDER BY kind DESC, created, id", arrayOf(id, "$page")
    ).use { c -> buildList { while (c.moveToNext()) add(item(c, id)) } }

    private fun putItem(db: SQLiteDatabase, book: String, it: SketchItem, conflict: Int) = db.insertWithOnConflict("items", null, ContentValues().apply {
        put("id", it.id); put("book", book); put("page", it.page); put("kind", it.kind); put("asset", it.asset); put("source_page", it.sourcePage)
        put("x", it.x); put("y", it.y); put("w", it.w); put("h", it.h); put("created", it.created); put("time", it.time); put("deleted", it.deleted)
    }, conflict)

    fun addItems(id: String, list: List<SketchItem>) {
        if (list.isEmpty()) return
        tx { db ->
            for (it in list) putItem(db, id, it, SQLiteDatabase.CONFLICT_IGNORE)
            change(id, ContentValues())
        }
    }

    /** A picture was moved or resized. */
    fun moveItem(id: String, item: SketchItem) {
        tx { db ->
            db.execSQL("UPDATE items SET x = ?, y = ?, w = ?, h = ?, time = ? WHERE id = ? AND book = ? AND deleted = 0",
                arrayOf<Any>(item.x, item.y, item.w, item.h, System.currentTimeMillis(), item.id, id))
            change(id, ContentValues())
        }
    }

    /** Removed items keep their row so other devices learn they were removed. */
    fun removeItems(id: String, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        tx { db ->
            for (i in ids) db.execSQL("UPDATE items SET deleted = ?, time = ? WHERE id = ? AND book = ? AND deleted = 0", arrayOf<Any>(now, now, i, id))
            change(id, ContentValues())
        }
    }

    /** Every page placement of one imported document (to remove it everywhere at once). */
    fun itemsWithAsset(id: String, asset: String): List<SketchItem> = readableDatabase.rawQuery(
        "SELECT $itemCols FROM items WHERE book = ? AND asset = ? AND deleted = 0", arrayOf(id, asset)
    ).use { c -> buildList { while (c.moveToNext()) add(item(c, id)) } }

    /** True if pages [start] to [start] + [count] - 1 have nothing on them. */
    fun pagesFree(id: String, start: Int, count: Int): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM strokes WHERE book = ? AND deleted = 0 AND page BETWEEN ? AND ? UNION ALL SELECT 1 FROM items WHERE book = ? AND deleted = 0 AND page BETWEEN ? AND ? LIMIT 1",
        arrayOf(id, "$start", "${start + count - 1}", id, "$start", "${start + count - 1}")
    ).use { !it.moveToFirst() }

    /** Stores a received file if it's new here, checking it is the file its name says. */
    private fun receiveAsset(name: String, input: InputStream) {
        val dest = assetFile(name)
        if (dest.isFile) return
        val tmp = File(assetsDir, "$name.part")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        tmp.outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n); out.write(buf, 0, n) }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex == name.substringBefore('.') && tmp.renameTo(dest)) return
        tmp.delete()
    }

    // ------------------------------------------------------------------ sync bookkeeping

    fun edits(id: String): Long = readableDatabase.rawQuery("SELECT edits FROM books WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun syncInfo(id: String): Triple<String?, Long, String?>? = readableDatabase.rawQuery(
        "SELECT sync_uri, synced_edits, remote_stamp FROM books WHERE id = ?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getLong(1), c.getString(2)) else null }

    fun link(id: String, uri: String, name: String) {
        writableDatabase.update("books", ContentValues().apply {
            put("sync_uri", uri); put("sync_name", name); putNull("remote_stamp"); putNull("sync_error"); put("synced_edits", -1)
        }, "id = ?", arrayOf(id))
    }

    fun unlink(id: String) {
        writableDatabase.update("books", ContentValues().apply {
            putNull("sync_uri"); putNull("sync_name"); putNull("remote_stamp"); putNull("sync_error"); put("synced_at", 0)
        }, "id = ?", arrayOf(id))
    }

    fun markSynced(id: String, editsSent: Long, stamp: String?) {
        writableDatabase.update("books", ContentValues().apply {
            put("synced_edits", editsSent); put("synced_at", System.currentTimeMillis()); put("remote_stamp", stamp); putNull("sync_error")
        }, "id = ?", arrayOf(id))
    }

    fun setSyncError(id: String, message: String) {
        writableDatabase.update("books", ContentValues().apply { put("sync_error", message) }, "id = ?", arrayOf(id))
    }

    // ------------------------------------------------------------------ files

    /** Writes the whole sketchbook in the shared `.sketchbook` format. */
    fun write(id: String, out: OutputStream) {
        val meta = meta(id) ?: throw IOException("Sketchbook missing")
        val items = readableDatabase.rawQuery("SELECT $itemCols FROM items WHERE book = ?", arrayOf(id)).use { c -> buildList { while (c.moveToNext()) add(item(c, id)) } }
        val assets = items.filter { it.deleted == 0L }.map { it.asset }.distinct().map { assetFile(it) }.filter { it.isFile }
            .map { f -> f.name to { f.inputStream() as InputStream } }
        SketchFile.write(out, meta, items = items, assets = assets, deleted = { emit ->
            readableDatabase.rawQuery("SELECT id, deleted FROM strokes WHERE book = ? AND deleted > 0", arrayOf(id)).use { c ->
                while (c.moveToNext()) emit(c.getLong(0), c.getLong(1))
            }
        }, strokes = { emit ->
            readableDatabase.rawQuery("SELECT id, page, tool, color, width, created, points FROM strokes WHERE book = ? AND deleted = 0", arrayOf(id)).use { c ->
                while (c.moveToNext()) emit(SketchStrokeRecord(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getFloat(4), c.getLong(5), blobToPoints(c.getBlob(6))))
            }
        })
    }

    /**
     * Merges a `.sketchbook` file into this device's copy, creating the sketchbook if it is new
     * here. With [expectedId], refuses a file that holds a different sketchbook.
     */
    fun merge(input: InputStream, expectedId: String? = null): MergeResult = tx { db ->
        var bookId = ""
        var created = false
        var metaChanged = false
        val changed = HashSet<Int>()
        var remoteStrokes = 0
        var remoteDeleted = 0
        var remoteMeta: SketchMeta? = null
        var remoteItems = 0
        var itemsBehind = false
        SketchFile.read(input, onMeta = { remote ->
            if (expectedId != null && remote.id != expectedId) throw IOException("This file holds a different sketchbook (“${remote.title}”).")
            bookId = remote.id
            remoteMeta = remote
            val local = meta(remote.id)
            // Syncing a sketchbook that was deleted meanwhile must not bring it back.
            if (local == null && expectedId != null) throw IOException("This sketchbook is no longer on this device")
            if (local == null) {
                created = true
                db.insertOrThrow("books", null, ContentValues().apply {
                    put("id", remote.id); put("title", remote.title); put("title_time", remote.titleTime)
                    put("paper", remote.paper); put("paper_time", remote.paperTime); put("pages", remote.pages)
                    put("created", remote.created); put("updated", System.currentTimeMillis())
                })
            } else {
                val merged = SketchMerge.meta(local, remote)
                if (merged != local) {
                    metaChanged = true
                    db.update("books", ContentValues().apply {
                        put("title", merged.title); put("title_time", merged.titleTime); put("paper", merged.paper)
                        put("paper_time", merged.paperTime); put("pages", merged.pages); put("created", merged.created)
                    }, "id = ?", arrayOf(remote.id))
                }
            }
        }, onDeleted = { strokeId, time ->
            remoteDeleted++
            val page = db.rawQuery("SELECT page, deleted FROM strokes WHERE id = ?", arrayOf("$strokeId")).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else null }
            when {
                page == null -> db.insert("strokes", null, ContentValues().apply {
                    put("id", strokeId); put("book", bookId); put("page", -1); put("tool", 0); put("color", 0); put("width", 0f)
                    put("created", 0L); put("points", ByteArray(0)); put("deleted", time.coerceAtLeast(1L))
                })
                page.second == 0L -> {
                    db.execSQL("UPDATE strokes SET deleted = ?, points = x'' WHERE id = ?", arrayOf<Any>(time.coerceAtLeast(1L), strokeId))
                    changed += page.first
                }
            }
        }, onStroke = { s ->
            remoteStrokes++
            val row = db.insertWithOnConflict("strokes", null, ContentValues().apply {
                put("id", s.id); put("book", bookId); put("page", s.page); put("tool", s.tool); put("color", s.color)
                put("width", s.width); put("created", s.created); put("points", pointsToBlob(s.pts))
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (row != -1L) changed += s.page
        }, onItem = { remote ->
            remoteItems++
            val local = db.rawQuery("SELECT $itemCols FROM items WHERE id = ?", arrayOf("${remote.id}")).use { c -> if (c.moveToFirst()) item(c, bookId) else null }
            if (local == null) {
                putItem(db, bookId, remote, SQLiteDatabase.CONFLICT_IGNORE)
                if (remote.deleted == 0L) changed += remote.page
            } else {
                val merged = SketchMerge.item(local, remote)
                if (merged != local) {
                    putItem(db, bookId, merged, SQLiteDatabase.CONFLICT_REPLACE)
                    changed += local.page; changed += merged.page
                }
                if (merged != remote) itemsBehind = true
            }
        }, onAsset = { name, stream -> receiveAsset(name, stream) })
        if (changed.isNotEmpty() || metaChanged) db.execSQL("UPDATE books SET updated = ? WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), bookId))
        // Does the file lack anything this device has? Then it needs writing back.
        val (live, gone) = db.rawQuery("SELECT SUM(deleted = 0), SUM(deleted > 0) FROM strokes WHERE book = ?", arrayOf(bookId)).use { c ->
            if (c.moveToFirst()) c.getInt(0) to c.getInt(1) else 0 to 0
        }
        val ours = meta(bookId)
        val theirs = remoteMeta
        val settingsDiffer = ours == null || theirs == null || ours.title != theirs.title || ours.titleTime != theirs.titleTime ||
            ours.paper != theirs.paper || ours.paperTime != theirs.paperTime || ours.pages != theirs.pages
        val localItems = db.rawQuery("SELECT COUNT(*) FROM items WHERE book = ?", arrayOf(bookId)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        MergeResult(bookId, created, changed, metaChanged,
            live != remoteStrokes || gone != remoteDeleted || settingsDiffer || localItems != remoteItems || itemsBehind)
    }
}
