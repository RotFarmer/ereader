package com.rotfarmer.ereader.sketch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.InkStroke
import com.rotfarmer.ereader.sketch.SketchCodec.objects
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Content uses immutable slots; stable page identities and display order are independent. */
class SketchStore(private val context: Context) : SQLiteOpenHelper(context, "sketchbooks.db", null, 2), java.io.Closeable {
    val referencesDir = File(context.filesDir, "sketch-references").apply { mkdirs() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sketchbooks(id INTEGER PRIMARY KEY,title TEXT NOT NULL,page_count INTEGER NOT NULL,current_page INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE sketch_pages(book INTEGER NOT NULL,page INTEGER NOT NULL,paper INTEGER NOT NULL,PRIMARY KEY(book,page))")
        db.execSQL("CREATE TABLE sketch_strokes(id INTEGER PRIMARY KEY,book INTEGER NOT NULL,page INTEGER NOT NULL,tool INTEGER NOT NULL,color INTEGER NOT NULL,width REAL NOT NULL,points BLOB NOT NULL,created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX sketch_strokes_page ON sketch_strokes(book,page)")
        db.execSQL("CREATE TABLE sketch_refs(id INTEGER PRIMARY KEY,book INTEGER NOT NULL,page INTEGER NOT NULL,path TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,width REAL NOT NULL,height REAL NOT NULL,opacity REAL NOT NULL)")
        db.execSQL("CREATE INDEX sketch_refs_page ON sketch_refs(book,page)")
        upgradeSchema(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // OpenHelper holds the migration write transaction: retain the original database/WAL pair.
            val recovery = File(context.filesDir, "sketch-recovery-v1").apply { mkdirs() }
            val original = context.getDatabasePath("sketchbooks.db")
            listOf(original, File(original.path + "-wal")).filter { it.exists() }.forEach {
                val target = File(recovery, it.name); if (!target.exists()) it.copyTo(target)
            }
            upgradeSchema(db)
            db.rawQuery("SELECT id,page_count FROM sketchbooks", null).use { c ->
                while (c.moveToNext()) repeat(c.getInt(1)) { insertOrder(db, c.getLong(0), it, it) }
            }
            db.execSQL("UPDATE sketchbooks SET current_id=(SELECT id FROM sketch_order WHERE book=sketchbooks.id AND position=current_page)")
            check(db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" })
        }
    }
    private fun upgradeSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sketchbooks ADD COLUMN current_id INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sketchbooks ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sketchbooks ADD COLUMN default_paper INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sketchbooks ADD COLUMN default_spacing REAL NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE sketch_pages ADD COLUMN spacing REAL NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE sketch_refs ADD COLUMN details TEXT NOT NULL DEFAULT '{}'")
        db.execSQL("CREATE TABLE sketch_order(id INTEGER PRIMARY KEY AUTOINCREMENT,book INTEGER NOT NULL,position INTEGER NOT NULL,slot INTEGER NOT NULL,title TEXT NOT NULL DEFAULT '',section TEXT NOT NULL DEFAULT '',bookmark INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0,zoom REAL NOT NULL DEFAULT 1,pan_x REAL NOT NULL DEFAULT 0,pan_y REAL NOT NULL DEFAULT 0,rotation REAL NOT NULL DEFAULT 0,UNIQUE(book,slot))")
        db.execSQL("CREATE INDEX sketch_order_book ON sketch_order(book,deleted,position)")
        db.execSQL("CREATE TABLE sketch_history(id INTEGER PRIMARY KEY AUTOINCREMENT,page_id INTEGER NOT NULL,payload TEXT NOT NULL,applied INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE INDEX sketch_history_page ON sketch_history(page_id,id)")
    }
    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase; db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    private fun insertOrder(db: SQLiteDatabase, book: Long, position: Int, slot: Int) = db.insertOrThrow("sketch_order", null,
        ContentValues().apply { put("book", book); put("position", position); put("slot", slot) })
    private fun books(where: String, args: Array<String>? = null) = readableDatabase.rawQuery(
        "SELECT b.id,b.title,b.page_count,COALESCE((SELECT position FROM sketch_order WHERE id=b.current_id AND deleted=0),0),b.updated,b.default_paper,b.default_spacing FROM sketchbooks b $where", args).use { c ->
        buildList { while(c.moveToNext()) add(Sketchbook(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getInt(5), c.getFloat(6))) }
    }
    fun list(): List<Sketchbook> = books("WHERE b.deleted=0 ORDER BY b.updated DESC")
    fun get(id: Long): Sketchbook? = books("WHERE b.id=? AND b.deleted=0", arrayOf("$id")).firstOrNull()
    fun create(title: String, pageCount:Int=350): Long = transaction {
        require(title.isNotBlank() && pageCount in 1..10000)
        val id = writableDatabase.insertOrThrow("sketchbooks", null, ContentValues().apply {
            put("title", title.trim()); put("page_count", pageCount); put("updated", System.currentTimeMillis())
        })
        repeat(pageCount) { insertOrder(writableDatabase, id, it, it) }; setCurrentPage(id, 0); id
    }
    fun rename(id: Long, title: String) { require(title.isNotBlank()); writableDatabase.update("sketchbooks", ContentValues().apply {
        put("title", title.trim()); put("updated", System.currentTimeMillis())
    }, "id=?", arrayOf("$id")) }
    fun defaults(id: Long, paper: Int, spacing: Float) { require(paper in 0..3 && spacing in 0.5f..3f)
        writableDatabase.update("sketchbooks", ContentValues().apply { put("default_paper", paper); put("default_spacing", spacing) }, "id=?", arrayOf("$id")) }
    fun addPages(id: Long, count: Int) { insertPages(id, get(id)?.pageCount ?: error("Sketchbook missing"), count) }
    fun insertPages(book: Long, at: Int, count: Int): List<Long> = transaction {
        val b = get(book) ?: error("Sketchbook missing"); require(count in 1..10000 && at in 0..b.pageCount)
        val slot = readableDatabase.rawQuery("SELECT COALESCE(MAX(slot),-1)+1 FROM sketch_order WHERE book=?", arrayOf("$book")).use { it.moveToFirst(); it.getInt(0) }
        writableDatabase.execSQL("UPDATE sketch_order SET position=position+? WHERE book=? AND deleted=0 AND position>=?", arrayOf(count, book, at))
        val ids = (0 until count).map { i -> insertOrder(writableDatabase, book, at+i, slot+i).also {
            if (b.defaultPaper != 0 || b.defaultSpacing != 1f) writePaper(book,slot+i,b.defaultPaper,b.defaultSpacing)
        } }; updateCount(book); ids
    }
    fun setCurrentPage(id: Long, page: Int) { setCurrentId(id, info(id,page).id) }
    fun setCurrentId(book: Long, pageId: Long) {
        val p = infoById(book,pageId)
        writableDatabase.update("sketchbooks", ContentValues().apply { put("current_id",pageId); put("current_page",p.index); put("updated",System.currentTimeMillis()) },"id=?",arrayOf("$book"))
    }
    private fun infos(where: String, args: Array<String>) = readableDatabase.rawQuery(
        "SELECT o.id,o.book,o.position,o.slot,o.title,o.section,o.bookmark,o.zoom,o.pan_x,o.pan_y,o.rotation,(EXISTS(SELECT 1 FROM sketch_strokes s WHERE s.book=o.book AND s.page=o.slot) OR EXISTS(SELECT 1 FROM sketch_refs r WHERE r.book=o.book AND r.page=o.slot) OR EXISTS(SELECT 1 FROM sketch_pages p WHERE p.book=o.book AND p.page=o.slot)) FROM sketch_order o $where",args).use { c -> buildList {
        while(c.moveToNext()) add(PageInfo(c.getLong(0),c.getLong(1),c.getInt(2),c.getInt(3),c.getString(4),c.getString(5),c.getInt(6)!=0,c.getInt(11)!=0,c.getFloat(7),c.getFloat(8),c.getFloat(9),c.getFloat(10)))
    } }
    fun pages(book: Long): List<PageInfo> = infos("WHERE o.book=? AND o.deleted=0 ORDER BY o.position",arrayOf("$book"))
    fun info(book: Long,index: Int): PageInfo = infos("WHERE o.book=? AND o.position=? AND o.deleted=0",arrayOf("$book","$index")).firstOrNull() ?: error("Page missing")
    fun infoById(book: Long,id: Long): PageInfo = infos("WHERE o.book=? AND o.id=? AND o.deleted=0",arrayOf("$book","$id")).firstOrNull() ?: error("Page missing")
    fun metadata(p: PageInfo) { writableDatabase.update("sketch_order",ContentValues().apply {
        put("title",p.title);put("section",p.section);put("bookmark",if(p.bookmarked)1 else 0)
        put("zoom",p.zoom.coerceIn(1f,8f));put("pan_x",p.panX);put("pan_y",p.panY);put("rotation",p.rotation)
    },"id=? AND book=?",arrayOf("${p.id}","${p.book}")) }
    fun viewport(p:PageInfo) { writableDatabase.update("sketch_order",ContentValues().apply {
        put("zoom",p.zoom.coerceIn(1f,8f));put("pan_x",p.panX);put("pan_y",p.panY);put("rotation",p.rotation)
    },"id=? AND book=?",arrayOf("${p.id}","${p.book}")) }
    fun movePage(book: Long,id: Long,to: Int) = transaction {
        val list=pages(book).toMutableList();require(to in list.indices);val p=list.first { it.id==id };list.remove(p);list.add(to,p)
        list.forEachIndexed { i,item -> writableDatabase.execSQL("UPDATE sketch_order SET position=? WHERE id=?",arrayOf(i,item.id)) };touch(book)
    }
    fun duplicatePage(book: Long,id: Long): Long = transaction {
        val p=infoById(book,id);val source=pageById(book,id);val dest=insertPages(book,p.index+1,1).single()
        metadata(infoById(book,dest).copy(title=p.title,section=p.section))
        writePage(book,infoById(book,dest).slot,copyContent(source));dest
    }
    private fun copyContent(p:SketchPage) = p.copy(strokes=p.strokes.map { it.copy(id=SketchIds.next()) },references=p.references.map { it.copy(id=SketchIds.next()) })
    fun duplicate(book: Long): Long = transaction {
        val source=get(book) ?: error("Sketchbook missing");val dest=create(source.title+" copy")
        writableDatabase.delete("sketch_order","book=?",arrayOf("$dest"))
        pages(book).forEach { p -> val id=insertOrder(writableDatabase,dest,p.index,p.index);metadata(p.copy(id=id,book=dest,slot=p.index));if(p.used) writePage(dest,p.index,copyContent(pageById(book,p.id))) }
        defaults(dest,source.defaultPaper,source.defaultSpacing);updateCount(dest);setCurrentPage(dest,source.currentPage);dest
    }
    fun setPaper(id:Long,page:Int,paper:Int) { val p=info(id,page);val before=pageById(id,p.id);savePage(id,p.id,before,before.copy(paper=paper)) }
    fun page(id:Long,page:Int):SketchPage = pageById(id,info(id,page).id)
    fun pageById(book:Long,pageId:Long):SketchPage {
        val slot=infoById(book,pageId).slot;val args=arrayOf("$book","$slot")
        val paper=readableDatabase.rawQuery("SELECT paper,spacing FROM sketch_pages WHERE book=? AND page=?",args).use { if(it.moveToFirst()) it.getInt(0) to it.getFloat(1) else 0 to 1f }
        val refs=readableDatabase.rawQuery("SELECT id,path,x,y,width,height,opacity,details FROM sketch_refs WHERE book=? AND page=? ORDER BY id",args).use { c -> buildList {
            while(c.moveToNext()) { val o=JSONObject(c.getString(7));o.put("id",c.getLong(0)).put("path",c.getString(1)).put("x",c.getFloat(2)).put("y",c.getFloat(3)).put("width",c.getFloat(4)).put("height",c.getFloat(5)).put("opacity",c.getFloat(6));add(SketchCodec.reference(o)) }
        } }
        val strokes=readableDatabase.rawQuery("SELECT id,tool,color,width,points,created FROM sketch_strokes WHERE book=? AND page=? ORDER BY created,id",args).use { c -> buildList {
            while(c.moveToNext()) add(InkStroke(c.getLong(0),book,Anchor(Anchor.PDF,pageId,0),c.getInt(1),c.getInt(2),c.getFloat(3),SketchCodec.points(c.getBlob(4)),c.getLong(5)))
        } };return SketchPage(paper.first,refs,strokes,paper.second)
    }
    private fun writePaper(book:Long,slot:Int,paper:Int,spacing:Float) {
        require(paper in 0..3 && spacing in 0.5f..3f)
        if(paper==0 && spacing==1f) writableDatabase.delete("sketch_pages","book=? AND page=?",arrayOf("$book","$slot"))
        else writableDatabase.insertWithOnConflict("sketch_pages",null,ContentValues().apply { put("book",book);put("page",slot);put("paper",paper);put("spacing",spacing) },SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun writeStroke(book:Long,slot:Int,s:InkStroke) = writableDatabase.insertWithOnConflict("sketch_strokes",null,ContentValues().apply {
        put("id",s.id);put("book",book);put("page",slot);put("tool",s.tool);put("color",s.color);put("width",s.width);put("points",SketchCodec.points(s.pts));put("created",s.created)
    },SQLiteDatabase.CONFLICT_REPLACE)
    private fun writeReference(book:Long,slot:Int,r:ReferenceLayer) = writableDatabase.insertWithOnConflict("sketch_refs",null,ContentValues().apply {
        put("id",r.id);put("book",book);put("page",slot);put("path",r.path);put("x",r.x);put("y",r.y);put("width",r.width);put("height",r.height);put("opacity",r.opacity);put("details",SketchCodec.reference(r).toString())
    },SQLiteDatabase.CONFLICT_REPLACE)
    private fun writePage(book:Long,slot:Int,p:SketchPage) {
        writePaper(book,slot,p.paper,p.spacing)
        writableDatabase.delete("sketch_strokes","book=? AND page=?",arrayOf("$book","$slot"));p.strokes.forEach { writeStroke(book,slot,it) }
        writableDatabase.delete("sketch_refs","book=? AND page=?",arrayOf("$book","$slot"));p.references.forEach { writeReference(book,slot,it) }
    }
    fun importPage(book:Long,id:Long,p:SketchPage) = transaction { writePage(book,infoById(book,id).slot,p);touch(book) }
    val archiveOrigin:String get()=synchronized(SketchStore::class.java) {
        val prefs=context.getSharedPreferences("sketchbook-backups",Context.MODE_PRIVATE)
        prefs.getString("origin",null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("origin",it).commit() }
    }
    /** Journal contains only changed strokes; reference metadata is small and never contains bitmaps. */
    fun savePage(book:Long,pageId:Long,before:SketchPage,after:SketchPage) = transaction {
        val slot=infoById(book,pageId).slot;val old=before.strokes.associateBy { it.id };val new=after.strokes.associateBy { it.id }
        val removed=before.strokes.filter { new[it.id]?.let { n -> SketchCodec.same(it,n) } != true }
        val added=after.strokes.filter { old[it.id]?.let { o -> SketchCodec.same(it,o) } != true }
        if(removed.isEmpty() && added.isEmpty() && before.paper==after.paper && before.spacing==after.spacing && before.references==after.references) return@transaction
        val delta=JSONObject().put("removed",JSONArray().apply { removed.forEach { put(SketchCodec.stroke(it)) } })
            .put("added",JSONArray().apply { added.forEach { put(SketchCodec.stroke(it)) } })
            .put("before",SketchCodec.page(before.copy(strokes=emptyList()))).put("after",SketchCodec.page(after.copy(strokes=emptyList())))
        applyDelta(book,pageId,slot,delta,true)
        writableDatabase.delete("sketch_history","page_id=? AND applied=0",arrayOf("$pageId"))
        writableDatabase.insertOrThrow("sketch_history",null,ContentValues().apply { put("page_id",pageId);put("payload",delta.toString()) })
        writableDatabase.execSQL("DELETE FROM sketch_history WHERE page_id=? AND id NOT IN (SELECT id FROM sketch_history WHERE page_id=? ORDER BY id DESC LIMIT 100)",arrayOf(pageId,pageId));touch(book)
    }
    private fun applyDelta(book:Long,pageId:Long,slot:Int,d:JSONObject,forward:Boolean) {
        d.getJSONArray(if(forward) "removed" else "added").objects().forEach { writableDatabase.delete("sketch_strokes","id=? AND book=? AND page=?",arrayOf("${it.getLong("id")}","$book","$slot")) }
        d.getJSONArray(if(forward) "added" else "removed").objects().forEach { writeStroke(book,slot,SketchCodec.stroke(it,book,pageId)) }
        val p=SketchCodec.page(d.getJSONObject(if(forward) "after" else "before"),book,pageId);writePaper(book,slot,p.paper,p.spacing)
        writableDatabase.delete("sketch_refs","book=? AND page=?",arrayOf("$book","$slot"));p.references.forEach { writeReference(book,slot,it) }
    }
    fun history(pageId:Long):Pair<Boolean,Boolean> = readableDatabase.rawQuery("SELECT applied FROM sketch_history WHERE page_id=? GROUP BY applied",arrayOf("$pageId")).use { c ->
        var undo=false;var redo=false;while(c.moveToNext()) if(c.getInt(0)==1) undo=true else redo=true;undo to redo
    }
    fun undo(book:Long,pageId:Long,redo:Boolean=false):SketchPage = transaction {
        val slot=infoById(book,pageId).slot
        readableDatabase.rawQuery("SELECT id,payload FROM sketch_history WHERE page_id=? AND applied=? ORDER BY id ${if(redo) "ASC" else "DESC"} LIMIT 1",arrayOf("$pageId",if(redo) "0" else "1")).use { c ->
            if(c.moveToFirst()) { applyDelta(book,pageId,slot,JSONObject(c.getString(1)),redo);writableDatabase.execSQL("UPDATE sketch_history SET applied=? WHERE id=?",arrayOf(if(redo)1 else 0,c.getLong(0))) }
        };touch(book);pageById(book,pageId)
    }
    fun insertStroke(id:Long,page:Int,stroke:InkStroke):Long { val p=info(id,page);val s=stroke.copy(id=SketchIds.next());writeStroke(id,p.slot,s);touch(id);return s.id }
    fun deleteStroke(strokeId:Long) { writableDatabase.delete("sketch_strokes","id=?",arrayOf("$strokeId")) }
    fun restoreStroke(id:Long,page:Int,stroke:InkStroke) { writeStroke(id,info(id,page).slot,stroke);touch(id) }
    fun addReference(id:Long,page:Int,path:String,width:Float,height:Float):Long {
        val ref=ReferenceLayer(SketchIds.next(),path,0.1f,0.1f,width,height,1f);writeReference(id,info(id,page).slot,ref);touch(id);return ref.id
    }
    fun updateReference(ref:ReferenceLayer) { writableDatabase.update("sketch_refs",ContentValues().apply {
        put("x",ref.x);put("y",ref.y);put("width",ref.width);put("height",ref.height);put("opacity",ref.opacity);put("details",SketchCodec.reference(ref).toString())
    },"id=?",arrayOf("${ref.id}")) }
    fun removeReference(id:Long,ref:ReferenceLayer) { writableDatabase.delete("sketch_refs","id=? AND book=?",arrayOf("${ref.id}","$id"));touch(id) }
    fun delete(id:Long) { writableDatabase.execSQL("UPDATE sketchbooks SET deleted=? WHERE id=?",arrayOf(System.currentTimeMillis(),id)) }
    fun deletePage(book:Long,id:Long) = transaction {
        require((get(book)?.pageCount ?: 0)>1) { "Keep at least one page" };infoById(book,id)
        writableDatabase.execSQL("UPDATE sketch_order SET deleted=? WHERE id=?",arrayOf(System.currentTimeMillis(),id));normalize(book)
    }
    fun trash(book:Long?=null):List<TrashItem> {
        val sql=if(book==null) "SELECT id,title,deleted FROM sketchbooks WHERE deleted>0 ORDER BY deleted DESC" else "SELECT id,CASE WHEN title='' THEN 'Page '||(position+1) ELSE title END,deleted FROM sketch_order WHERE deleted>0 AND book=? ORDER BY deleted DESC"
        return readableDatabase.rawQuery(sql,book?.let { arrayOf("$it") }).use { c -> buildList { while(c.moveToNext()) add(TrashItem(c.getLong(0),c.getString(1),book!=null,c.getLong(2))) } }
    }
    fun restore(id:Long,book:Long?=null) = transaction {
        if(book==null) writableDatabase.execSQL("UPDATE sketchbooks SET deleted=0 WHERE id=?",arrayOf(id))
        else { writableDatabase.execSQL("UPDATE sketch_order SET deleted=0,position=(SELECT page_count FROM sketchbooks WHERE id=?) WHERE id=? AND book=?",arrayOf(book,id,book));normalize(book) }
    }
    /** Run off the UI thread. Assets referenced by live content, Trash or undo are retained. */
    fun purgeExpired(now:Long=System.currentTimeMillis()) {
        val cutoff=now-30L*24*60*60*1000
        transaction {
            val expired=readableDatabase.rawQuery("SELECT id FROM sketchbooks WHERE deleted>0 AND deleted<?",arrayOf("$cutoff")).use { c->buildList { while(c.moveToNext())add(c.getLong(0)) } }
            expired.forEach { book->
                writableDatabase.execSQL("DELETE FROM sketch_history WHERE page_id IN (SELECT id FROM sketch_order WHERE book=?)",arrayOf(book))
                listOf("sketch_refs","sketch_strokes","sketch_pages","sketch_order").forEach { writableDatabase.delete(it,"book=?",arrayOf("$book")) }
                writableDatabase.delete("sketchbooks","id=?",arrayOf("$book"))
            }
            readableDatabase.rawQuery("SELECT id,book,slot FROM sketch_order WHERE deleted>0 AND deleted<?",arrayOf("$cutoff")).use { c->
                while(c.moveToNext()) {
                    listOf("sketch_refs","sketch_strokes","sketch_pages").forEach { writableDatabase.delete(it,"book=? AND page=?",arrayOf("${c.getLong(1)}","${c.getInt(2)}")) }
                    writableDatabase.delete("sketch_history","page_id=?",arrayOf("${c.getLong(0)}"))
                }
            }
            writableDatabase.delete("sketch_order","deleted>0 AND deleted<?",arrayOf("$cutoff"))
        }
        referencesDir.listFiles()?.filter { it.isFile && it.lastModified()<now-24L*60*60*1000 }?.forEach { file->
            val quoted="%\"${file.name}\"%"
            val used=readableDatabase.rawQuery("SELECT 1 FROM sketch_refs WHERE path=? OR details LIKE ? UNION ALL SELECT 1 FROM sketch_history WHERE payload LIKE ? LIMIT 1",arrayOf(file.name,quoted,quoted)).use { it.moveToFirst() }
            if(!used)file.delete()
        }
    }
    private fun normalize(book:Long) { pages(book).forEachIndexed { i,p -> writableDatabase.execSQL("UPDATE sketch_order SET position=? WHERE id=?",arrayOf(i,p.id)) };updateCount(book) }
    private fun updateCount(book:Long) { writableDatabase.execSQL("UPDATE sketchbooks SET page_count=(SELECT COUNT(*) FROM sketch_order WHERE book=? AND deleted=0),updated=? WHERE id=?",arrayOf(book,System.currentTimeMillis(),book)) }
    private fun touch(id:Long) { writableDatabase.execSQL("UPDATE sketchbooks SET updated=? WHERE id=?",arrayOf(System.currentTimeMillis(),id)) }
}
