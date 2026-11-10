package com.rotfarmer.ereader.sketch

import com.rotfarmer.ereader.sketch.SketchCodec.objects
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID
import java.util.zip.*

/** Portable archive: metadata, one JSON entry per page, and deduplicated original assets. */
object SketchArchive {
    fun write(store:SketchStore,book:Long,output:OutputStream) {
        val b=store.get(book) ?: error("Sketchbook missing")
        val infos=store.pages(book);val manifest=JSONObject().put("version",1).put("origin",store.archiveOrigin)
            .put("title",b.title).put("currentPage",b.currentPage).put("defaultPaper",b.defaultPaper).put("defaultSpacing",b.defaultSpacing)
        val pages=JSONArray();val assets=linkedMapOf<String,String>()
        // Buffered, and never closing the caller's stream. FilterOutputStream on its own writes
        // one byte at a time, which made backups with pictures take minutes.
        val zip=ZipOutputStream(BufferedOutputStream(object:FilterOutputStream(output){
            override fun write(b:ByteArray,off:Int,len:Int){out.write(b,off,len)}
            override fun close(){flush()}
        },1 shl 16))
        try {
            infos.forEachIndexed { index,p->
                val path=if(p.used) "pages/$index.json" else ""
                pages.put(JSONObject().put("entry",path).put("title",p.title).put("section",p.section).put("bookmark",p.bookmarked)
                    .put("zoom",p.zoom).put("panX",p.panX).put("panY",p.panY).put("rotation",p.rotation))
                if (p.used) {
                    val content=store.pageById(book,p.id)
                    content.references.forEach { r->listOf(r.path,r.source,r.original).filter { it.isNotBlank() }.forEach { name->assets.getOrPut(name){"assets/${assets.size}"} } }
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(SketchCodec.page(content).toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            val entries=JSONArray()
            assets.forEach { (name,path)->
                val file=SketchRenderer.asset(store.referencesDir,name);require(file.isFile){"Missing reference asset: $name"}
                entries.put(JSONObject().put("name",name).put("entry",path));zip.putNextEntry(ZipEntry(path));file.inputStream().use { it.copyTo(zip) };zip.closeEntry()
            }
            manifest.put("pages",pages).put("assets",entries)
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toString().toByteArray(Charsets.UTF_8));zip.closeEntry();zip.finish()
        }finally{zip.close()}
    }
    fun restore(store:SketchStore,input:InputStream):Long {
        val stage=File(store.referencesDir.parentFile,"sketch-restore-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val copied=mutableListOf<File>()
        try {
            val files=mutableMapOf<String,File>();var total=0L
            ZipInputStream(BufferedInputStream(object:FilterInputStream(input){override fun close(){}},1 shl 16)).use { zip->
                while(true){val entry=zip.nextEntry ?: break;val name=entry.name
                    require(!entry.isDirectory && name.matches(Regex("manifest\\.json|pages/[0-9]+\\.json|assets/[0-9]+")) && name !in files){"Invalid or duplicate backup entry"}
                    require(files.size<30001){"Backup contains too many files"}
                    val file=File(stage,name.replace('/','_'));files[name]=file
                    file.outputStream().use { out->val buffer=ByteArray(65536);var size=0L
                        while(true){val n=zip.read(buffer);if(n<0)break;size+=n;total+=n
                            require(total<=1024L*1024*1024 && size<=512L*1024*1024){"Backup exceeds the supported size"}
                            if(name.endsWith(".json"))require(size<=64L*1024*1024){"Backup page is too large"};out.write(buffer,0,n)
                        }
                    };zip.closeEntry()
                }
            }
            val mf=files["manifest.json"] ?: error("Not a sketchbook backup");require(mf.length()<=8L*1024*1024){"Backup metadata is too large"}
            val manifest=JSONObject(mf.readText());require(manifest.getInt("version")==1){"Unsupported backup version"}
            val pages=manifest.getJSONArray("pages").objects();require(pages.size in 1..10000){"Invalid page count"}
            val assetEntries=manifest.getJSONArray("assets").objects();val mapping=mutableMapOf<String,String>()
            val expected=hashSetOf("manifest.json")
            assetEntries.forEach { asset->
                val name=asset.getString("name");val entry=asset.getString("entry")
                require(name.isNotBlank() && name !in mapping && entry.startsWith("assets/") && expected.add(entry) && files.containsKey(entry)){"Invalid backup asset"}
                val ext=name.substringAfterLast('.',"bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
                mapping[name]="${UUID.randomUUID()}.$ext"
            }
            // Validate every page before any destination book or permanent asset is created.
            pages.forEach { metadata->val entry=metadata.getString("entry");if (entry.isEmpty()) return@forEach
                require(entry.startsWith("pages/") && expected.add(entry)){"Duplicate backup page"}
                val p=readPage(files[entry] ?: error("Missing backup page"));validate(p,mapping)
            }
            require(files.keys==expected){"Unexpected backup entries"}
            val title=manifest.getString("title").trim();require(title.isNotEmpty()){ "Missing sketchbook title" }
            val current=manifest.getInt("currentPage");require(current in pages.indices){"Invalid current page"}
            val defaultPaper=manifest.optInt("defaultPaper");val defaultSpacing=manifest.optDouble("defaultSpacing",1.0).toFloat()
            require(defaultPaper in 0..3 && defaultSpacing in .5f..3f){"Invalid paper defaults"}
            assetEntries.forEach { asset->val dest=File(store.referencesDir,mapping.getValue(asset.getString("name")));copied+=dest;files.getValue(asset.getString("entry")).copyTo(dest) }
            val sameOrigin=manifest.optString("origin")==store.archiveOrigin
            return store.transaction {
                val book=store.create(title,pages.size);store.defaults(book,defaultPaper,defaultSpacing)
                pages.forEachIndexed { index,meta->val info=store.info(book,index)
                    val entry = meta.getString("entry")
                    if (entry.isNotEmpty()) {
                        val content=readPage(files.getValue(entry))
                        val refs=content.references.map { r->r.copy(id=SketchIds.next(),path=mapping.getValue(r.path),source=if(r.source.isBlank())"" else mapping.getValue(r.source),original=if(r.original.isBlank())"" else mapping.getValue(r.original),sourceBook=if(sameOrigin)r.sourceBook else -1) }
                        store.importPage(book,info.id,content.copy(strokes=content.strokes.map { it.copy(id=SketchIds.next()) },references=refs))
                    }
                    store.metadata(info.copy(title=meta.optString("title"),section=meta.optString("section"),bookmarked=meta.optBoolean("bookmark"),zoom=meta.optDouble("zoom",1.0).toFloat(),panX=meta.optDouble("panX").toFloat().finiteOrZero(),panY=meta.optDouble("panY").toFloat().finiteOrZero(),rotation=meta.optDouble("rotation").toFloat().finiteOrZero()))
                };store.setCurrentPage(book,current);book
            }
        }catch(e:Throwable){copied.forEach { it.delete() };throw e}finally{stage.deleteRecursively()}
    }
    private fun Float.finiteOrZero()=if(isFinite())this else 0f
    private fun readPage(file:File)=SketchCodec.page(JSONObject(file.readText()),0,0)
    private fun validate(p:SketchPage,mapping:Map<String,String>) {
        require(p.paper in 0..3 && p.spacing in .5f..3f){"Invalid page background"}
        require(p.strokes.size<=100000 && p.references.size<=1000){"Backup page has too many objects"}
        p.strokes.forEach { s->require(s.tool in 0..8 && s.width.isFinite() && s.width>0 && s.pts.size>=3 && s.pts.all { it.isFinite() }){"Invalid pen stroke"} }
        p.references.forEach { r->
            require(listOf(r.path,r.source,r.original).filter { it.isNotBlank() }.all { it in mapping } && r.path.isNotBlank()){"Missing reference asset"}
            require(listOf(r.x,r.y,r.width,r.height,r.opacity,r.rotation,r.dim).all { it.isFinite() } && r.width>0 && r.height>0 && r.opacity in 0f..1f && r.dim in 0f..1f){"Invalid reference transform"}
            require(r.cropLeft in 0f..1f && r.cropTop in 0f..1f && r.cropRight>r.cropLeft && r.cropRight<=1f && r.cropBottom>r.cropTop && r.cropBottom<=1f){"Invalid reference crop"}
        }
    }
}
