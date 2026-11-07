package com.rotfarmer.ereader.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.RectF
import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.InkStroke
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "ereader.db", null, 1) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE books(id INTEGER PRIMARY KEY, title TEXT, author TEXT, format TEXT,
               path TEXT, cover TEXT, added INTEGER, opened INTEGER, progress REAL,
               loc_block INTEGER, loc_offset INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE bookmarks(id INTEGER PRIMARY KEY, book INTEGER, block INTEGER,
               off INTEGER, label TEXT, created INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE highlights(id INTEGER PRIMARY KEY, book INTEGER, sb INTEGER, so INTEGER,
               eb INTEGER, eo INTEGER, color INTEGER, text TEXT, note TEXT, label TEXT, created INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE excerpts(id INTEGER PRIMARY KEY, book INTEGER, sb INTEGER, so INTEGER,
               eb INTEGER, eo INTEGER, text TEXT, note TEXT, image TEXT, region TEXT, label TEXT,
               created INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE strokes(id INTEGER PRIMARY KEY, book INTEGER, kind INTEGER, a1 INTEGER,
               a2 INTEGER, tool INTEGER, color INTEGER, width REAL, points BLOB, created INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE spaces(id INTEGER PRIMARY KEY, book INTEGER, kind INTEGER, block INTEGER,
               off INTEGER, height REAL, paper INTEGER, created INTEGER)"""
        )
        for (t in listOf("bookmarks", "highlights", "excerpts", "strokes", "spaces")) {
            db.execSQL("CREATE INDEX idx_${t}_book ON $t(book)")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------------------------------------------------------------- books

    fun insertBook(b: Book) {
        val v = ContentValues().apply {
            put("id", b.id); put("title", b.title); put("author", b.author)
            put("format", b.format.name); put("path", b.path); put("cover", b.coverPath)
            put("added", b.added); put("opened", b.opened); put("progress", b.progress)
            put("loc_block", b.loc.block); put("loc_offset", b.loc.offset)
        }
        writableDatabase.insertWithOnConflict("books", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun Cursor.toBook() = Book(
        id = getLong(0), title = getString(1) ?: "", author = getString(2) ?: "",
        format = BookFormat.of(getString(3)), path = getString(4) ?: "", coverPath = getString(5),
        added = getLong(6), opened = getLong(7), progress = getFloat(8),
        loc = Locator(getInt(9), getInt(10)),
    )

    private val bookCols = "id,title,author,format,path,cover,added,opened,progress,loc_block,loc_offset"

    fun books(): List<Book> = readableDatabase.rawQuery(
        "SELECT $bookCols FROM books ORDER BY MAX(opened, added) DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.toBook()) } }

    fun book(id: Long): Book? = readableDatabase.rawQuery(
        "SELECT $bookCols FROM books WHERE id=?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) c.toBook() else null }

    fun findBookByTitle(title: String, format: BookFormat): Book? = readableDatabase.rawQuery(
        "SELECT $bookCols FROM books WHERE title=? AND format=?", arrayOf(title, format.name)
    ).use { c -> if (c.moveToFirst()) c.toBook() else null }

    fun updateProgress(id: Long, loc: Locator, progress: Float, opened: Long) {
        val v = ContentValues().apply {
            put("loc_block", loc.block); put("loc_offset", loc.offset)
            put("progress", progress); put("opened", opened)
        }
        writableDatabase.update("books", v, "id=?", arrayOf(id.toString()))
    }

    fun deleteBook(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in listOf("bookmarks", "highlights", "excerpts", "strokes", "spaces")) {
                db.delete(t, "book=?", arrayOf(id.toString()))
            }
            db.delete("books", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------------------ bookmarks

    fun bookmarks(book: Long): MutableList<Bookmark> = readableDatabase.rawQuery(
        "SELECT id,book,block,off,label,created FROM bookmarks WHERE book=? ORDER BY block,off",
        arrayOf(book.toString())
    ).use { c ->
        val out = ArrayList<Bookmark>()
        while (c.moveToNext()) out += Bookmark(c.getLong(0), c.getLong(1), Locator(c.getInt(2), c.getInt(3)), c.getString(4) ?: "", c.getLong(5))
        out
    }

    fun insertBookmark(b: Bookmark) {
        val v = ContentValues().apply {
            put("id", b.id); put("book", b.bookId); put("block", b.loc.block); put("off", b.loc.offset)
            put("label", b.label); put("created", b.created)
        }
        writableDatabase.insertWithOnConflict("bookmarks", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id=?", arrayOf(id.toString()))
    }

    // ----------------------------------------------------------- highlights

    fun highlights(book: Long): MutableList<Highlight> = readableDatabase.rawQuery(
        "SELECT id,book,sb,so,eb,eo,color,text,note,label,created FROM highlights WHERE book=? ORDER BY sb,so",
        arrayOf(book.toString())
    ).use { c ->
        val out = ArrayList<Highlight>()
        while (c.moveToNext()) out += Highlight(
            c.getLong(0), c.getLong(1), Locator(c.getInt(2), c.getInt(3)), Locator(c.getInt(4), c.getInt(5)),
            c.getInt(6), c.getString(7) ?: "", c.getString(8), c.getString(9) ?: "", c.getLong(10)
        )
        out
    }

    fun saveHighlight(h: Highlight) {
        val v = ContentValues().apply {
            put("id", h.id); put("book", h.bookId); put("sb", h.start.block); put("so", h.start.offset)
            put("eb", h.end.block); put("eo", h.end.offset); put("color", h.color); put("text", h.text)
            put("note", h.note); put("label", h.label); put("created", h.created)
        }
        writableDatabase.insertWithOnConflict("highlights", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteHighlight(id: Long) {
        writableDatabase.delete("highlights", "id=?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------- excerpts

    private val excerptCols = "e.id,e.book,e.sb,e.so,e.eb,e.eo,e.text,e.note,e.image,e.region,e.label,e.created"

    private fun Cursor.toExcerpt(withTitle: Boolean) = Excerpt(
        id = getLong(0), bookId = getLong(1), start = Locator(getInt(2), getInt(3)), end = Locator(getInt(4), getInt(5)),
        text = getString(6) ?: "", note = getString(7), imagePath = getString(8), region = parseRect(getString(9)),
        label = getString(10) ?: "", created = getLong(11), bookTitle = if (withTitle) getString(12) ?: "" else "",
    )

    fun excerpts(book: Long): MutableList<Excerpt> = readableDatabase.rawQuery(
        "SELECT $excerptCols FROM excerpts e WHERE e.book=? ORDER BY e.sb,e.so", arrayOf(book.toString())
    ).use { c -> val out = ArrayList<Excerpt>(); while (c.moveToNext()) out += c.toExcerpt(false); out }

    fun allExcerpts(): List<Excerpt> = readableDatabase.rawQuery(
        "SELECT $excerptCols, b.title FROM excerpts e JOIN books b ON b.id=e.book ORDER BY e.created DESC", null
    ).use { c -> val out = ArrayList<Excerpt>(); while (c.moveToNext()) out += c.toExcerpt(true); out }

    fun saveExcerpt(e: Excerpt) {
        val v = ContentValues().apply {
            put("id", e.id); put("book", e.bookId); put("sb", e.start.block); put("so", e.start.offset)
            put("eb", e.end.block); put("eo", e.end.offset); put("text", e.text); put("note", e.note)
            put("image", e.imagePath); put("region", e.region?.let { "${it.left},${it.top},${it.right},${it.bottom}" })
            put("label", e.label); put("created", e.created)
        }
        writableDatabase.insertWithOnConflict("excerpts", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteExcerpt(id: Long) {
        writableDatabase.delete("excerpts", "id=?", arrayOf(id.toString()))
    }

    private fun parseRect(s: String?): RectF? {
        val p = s?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return null
        return if (p.size == 4) RectF(p[0], p[1], p[2], p[3]) else null
    }

    // -------------------------------------------------------------- strokes

    fun strokes(book: Long): List<InkStroke> = readableDatabase.rawQuery(
        "SELECT id,book,kind,a1,a2,tool,color,width,points,created FROM strokes WHERE book=?",
        arrayOf(book.toString())
    ).use { c ->
        val out = ArrayList<InkStroke>()
        while (c.moveToNext()) {
            val blob = c.getBlob(8) ?: continue
            val fb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val pts = FloatArray(fb.remaining()).also { fb.get(it) }
            out += InkStroke(
                c.getLong(0), c.getLong(1), Anchor(c.getInt(2), c.getLong(3), c.getInt(4)),
                c.getInt(5), c.getInt(6), c.getFloat(7), pts, c.getLong(9)
            )
        }
        out
    }

    fun insertStrokes(list: List<InkStroke>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in list) {
                val bb = ByteBuffer.allocate(s.pts.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bb.asFloatBuffer().put(s.pts)
                val v = ContentValues().apply {
                    put("id", s.id); put("book", s.bookId); put("kind", s.anchor.kind); put("a1", s.anchor.a1)
                    put("a2", s.anchor.a2); put("tool", s.tool); put("color", s.color); put("width", s.width)
                    put("points", bb.array()); put("created", s.created)
                }
                db.insertWithOnConflict("strokes", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteStrokes(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.delete("strokes", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --------------------------------------------------------------- spaces

    fun spaces(book: Long): MutableList<WritingSpace> = readableDatabase.rawQuery(
        "SELECT id,book,kind,block,off,height,paper,created FROM spaces WHERE book=? ORDER BY block,off,created",
        arrayOf(book.toString())
    ).use { c ->
        val out = ArrayList<WritingSpace>()
        while (c.moveToNext()) out += WritingSpace(
            c.getLong(0), c.getLong(1), c.getInt(2), Locator(c.getInt(3), c.getInt(4)),
            c.getFloat(5), c.getInt(6), c.getLong(7)
        )
        out
    }

    fun saveSpace(s: WritingSpace) {
        val v = ContentValues().apply {
            put("id", s.id); put("book", s.bookId); put("kind", s.kind); put("block", s.loc.block)
            put("off", s.loc.offset); put("height", s.height); put("paper", s.paper); put("created", s.created)
        }
        writableDatabase.insertWithOnConflict("spaces", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteSpace(id: Long) {
        val db = writableDatabase
        db.delete("spaces", "id=?", arrayOf(id.toString()))
        db.delete("strokes", "kind=? AND a1=?", arrayOf(Anchor.SPACE.toString(), id.toString()))
    }

    companion object {
        /** All database work runs on one thread, in order. */
        val io: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "db").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
