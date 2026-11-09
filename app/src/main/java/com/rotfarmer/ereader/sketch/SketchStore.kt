package com.rotfarmer.ereader.sketch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.InkStroke
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Sketchbook(val id: Long, val title: String, val pageCount: Int, val currentPage: Int, val updated: Long)
data class ReferenceLayer(
    val id: Long, val path: String, val x: Float, val y: Float,
    val width: Float, val height: Float, val opacity: Float
)
data class SketchPage(val paper: Int, val references: List<ReferenceLayer>, val strokes: List<InkStroke>)

/** Sparse sketchbook storage. A new 350-page book has one row, not 350 bitmaps. */
class SketchStore(ctx: Context) : SQLiteOpenHelper(ctx, "sketchbooks.db", null, 1) {
    val referencesDir = File(ctx.filesDir, "sketch-references").apply { mkdirs() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sketchbooks(id INTEGER PRIMARY KEY, title TEXT NOT NULL, page_count INTEGER NOT NULL, current_page INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE sketch_pages(book INTEGER NOT NULL, page INTEGER NOT NULL, paper INTEGER NOT NULL, PRIMARY KEY(book,page))")
        db.execSQL("CREATE TABLE sketch_strokes(id INTEGER PRIMARY KEY, book INTEGER NOT NULL, page INTEGER NOT NULL, tool INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL, points BLOB NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX sketch_strokes_page ON sketch_strokes(book,page)")
        db.execSQL("CREATE TABLE sketch_refs(id INTEGER PRIMARY KEY, book INTEGER NOT NULL, page INTEGER NOT NULL, path TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, width REAL NOT NULL, height REAL NOT NULL, opacity REAL NOT NULL)")
        db.execSQL("CREATE INDEX sketch_refs_page ON sketch_refs(book,page)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun list(): List<Sketchbook> = readableDatabase.rawQuery(
        "SELECT id,title,page_count,current_page,updated FROM sketchbooks ORDER BY updated DESC", null
    ).use { c ->
        buildList { while (c.moveToNext()) add(Sketchbook(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3), c.getLong(4))) }
    }

    fun get(id: Long): Sketchbook? = readableDatabase.rawQuery(
        "SELECT id,title,page_count,current_page,updated FROM sketchbooks WHERE id=?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) Sketchbook(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3), c.getLong(4)) else null }

    fun create(title: String): Long = writableDatabase.insertOrThrow("sketchbooks", null, ContentValues().apply {
        put("title", title); put("page_count", 350); put("current_page", 0); put("updated", System.currentTimeMillis())
    })

    fun rename(id: Long, title: String) { writableDatabase.update("sketchbooks", ContentValues().apply {
        put("title", title); put("updated", System.currentTimeMillis())
    }, "id=?", arrayOf(id.toString())) }

    fun addPages(id: Long, count: Int) {
        require(count > 0)
        writableDatabase.execSQL("UPDATE sketchbooks SET page_count=page_count+?,updated=? WHERE id=?",
            arrayOf(count, System.currentTimeMillis(), id))
    }

    fun setCurrentPage(id: Long, page: Int) {
        require(page >= 0)
        writableDatabase.execSQL("UPDATE sketchbooks SET current_page=?,updated=? WHERE id=? AND page_count>?",
            arrayOf(page, System.currentTimeMillis(), id, page))
    }

    fun setPaper(id: Long, page: Int, paper: Int) {
        require(paper in 0..3)
        if (paper == 0) writableDatabase.delete("sketch_pages", "book=? AND page=?", arrayOf(id.toString(), page.toString()))
        else writableDatabase.insertWithOnConflict("sketch_pages", null, ContentValues().apply {
            put("book", id); put("page", page); put("paper", paper)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        touch(id)
    }

    fun page(id: Long, page: Int): SketchPage {
        val paper = readableDatabase.rawQuery("SELECT paper FROM sketch_pages WHERE book=? AND page=?", arrayOf(id.toString(), page.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val refs = readableDatabase.rawQuery(
            "SELECT id,path,x,y,width,height,opacity FROM sketch_refs WHERE book=? AND page=? ORDER BY id",
            arrayOf(id.toString(), page.toString())
        ).use { c -> buildList {
            while (c.moveToNext()) add(ReferenceLayer(c.getLong(0), c.getString(1), c.getFloat(2), c.getFloat(3), c.getFloat(4), c.getFloat(5), c.getFloat(6)))
        } }
        val strokes = readableDatabase.rawQuery(
            "SELECT id,tool,color,width,points,created FROM sketch_strokes WHERE book=? AND page=? ORDER BY id",
            arrayOf(id.toString(), page.toString())
        ).use { c -> buildList {
            while (c.moveToNext()) {
                val floats = ByteBuffer.wrap(c.getBlob(4)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val points = FloatArray(floats.remaining()).also { floats.get(it) }
                add(InkStroke(c.getLong(0), id, Anchor(Anchor.PDF, page.toLong(), 0), c.getInt(1), c.getInt(2), c.getFloat(3), points, c.getLong(5)))
            }
        } }
        return SketchPage(paper, refs, strokes)
    }

    fun insertStroke(id: Long, page: Int, stroke: InkStroke): Long {
        val bytes = ByteBuffer.allocate(stroke.pts.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(stroke.pts) }.array()
        val result = writableDatabase.insertOrThrow("sketch_strokes", null, ContentValues().apply {
            put("book", id); put("page", page); put("tool", stroke.tool); put("color", stroke.color)
            put("width", stroke.width); put("points", bytes); put("created", stroke.created)
        })
        touch(id)
        return result
    }

    fun deleteStroke(strokeId: Long) { writableDatabase.delete("sketch_strokes", "id=?", arrayOf(strokeId.toString())) }

    fun restoreStroke(id: Long, page: Int, stroke: InkStroke) {
        val bytes = ByteBuffer.allocate(stroke.pts.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(stroke.pts) }.array()
        writableDatabase.insertWithOnConflict("sketch_strokes", null, ContentValues().apply {
            put("id", stroke.id); put("book", id); put("page", page); put("tool", stroke.tool); put("color", stroke.color)
            put("width", stroke.width); put("points", bytes); put("created", stroke.created)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        touch(id)
    }

    fun addReference(id: Long, page: Int, path: String, width: Float, height: Float): Long {
        val result = writableDatabase.insertOrThrow("sketch_refs", null, ContentValues().apply {
            put("book", id); put("page", page); put("path", path); put("x", 0.1f); put("y", 0.1f)
            put("width", width); put("height", height); put("opacity", 1f)
        })
        touch(id)
        return result
    }

    fun updateReference(ref: ReferenceLayer) {
        writableDatabase.update("sketch_refs", ContentValues().apply {
            put("x", ref.x); put("y", ref.y); put("width", ref.width); put("height", ref.height); put("opacity", ref.opacity)
        }, "id=?", arrayOf(ref.id.toString()))
    }

    fun removeReference(id: Long, ref: ReferenceLayer) {
        writableDatabase.delete("sketch_refs", "id=? AND book=?", arrayOf(ref.id.toString(), id.toString()))
        File(referencesDir, ref.path).delete()
        touch(id)
    }

    fun delete(id: Long) {
        val paths = readableDatabase.rawQuery("SELECT path FROM sketch_refs WHERE book=?", arrayOf(id.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("sketch_refs", "book=?", arrayOf(id.toString()))
            db.delete("sketch_strokes", "book=?", arrayOf(id.toString()))
            db.delete("sketch_pages", "book=?", arrayOf(id.toString()))
            db.delete("sketchbooks", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        paths.forEach { File(referencesDir, it).delete() }
    }

    private fun touch(id: Long) { writableDatabase.execSQL("UPDATE sketchbooks SET updated=? WHERE id=?", arrayOf(System.currentTimeMillis(), id)) }
}
