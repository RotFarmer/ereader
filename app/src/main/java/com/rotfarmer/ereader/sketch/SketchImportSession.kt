package com.rotfarmer.ereader.sketch

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.rotfarmer.ereader.doc.*
import com.rotfarmer.ereader.layout.*
import com.rotfarmer.ereader.render.ImageCache
import com.rotfarmer.ereader.render.PageRenderer
import com.rotfarmer.ereader.render.ReaderColors
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

/** A staged import has no effect on a sketchbook until its selected pages commit atomically. */
class SketchImportSession private constructor(private val store:SketchStore,val directory:File,val name:String,
    private val original:File,private val display:File,val pageCount:Int,
    private val sourceBook:Long=-1,private val sourceLocation:String=""):Closeable {
    @Volatile var cancelled=false
    val isDocument get() = display.extension == "pdf"
    private fun check(){if(cancelled || Thread.currentThread().isInterrupted)throw CancellationException("Import canceled")}
    private fun prototype(index:Int):ReferenceLayer {
        val ratio=if(display.extension=="pdf")ParcelFileDescriptor.open(display,ParcelFileDescriptor.MODE_READ_ONLY).use { fd->PdfRenderer(fd).use { pdf->pdf.openPage(index).use { it.height.toFloat()/it.width } } }
            else BitmapFactory.Options().apply { inJustDecodeBounds=true;BitmapFactory.decodeFile(display.path,this) }.let { it.outHeight.toFloat()/it.outWidth }
        val width=minOf(.8f,1.15f/ratio)
        return ReferenceLayer(SketchIds.next(),display.name,.1f,.1f,width,width*ratio,1f,source=if(display.extension=="pdf")display.name else "",sourcePage=index,name=if(pageCount>1)"$name · ${index+1}" else name,sourceBook=sourceBook,sourceLocation=sourceLocation,original=original.name)
    }
    fun preview(index:Int,pixels:Int=800)=SketchRenderer.decode(directory,prototype(index),pixels)
    fun commit(book:Long,pageId:Long,selected:List<Int>,docked:Boolean=false,progress:(Int,Int)->Unit={_,_->}):List<Long> {
        require(selected.isNotEmpty() && selected.distinct().size==selected.size && selected.all { it in 0 until pageCount })
        val copied=mutableListOf<File>();val mapping=mutableMapOf<String,String>()
        try {
            check()
            listOf(original,display).distinct().forEach { src->
                val dest=File(store.referencesDir,UUID.randomUUID().toString()+"."+src.extension)
                copied+=dest;src.inputStream().use { input->dest.outputStream().use { output->val buffer=ByteArray(65536);while(true){check();val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n)} } };mapping[src.name]=dest.name
            }
            return store.transaction {
                val current=store.infoById(book,pageId)
                val ids=if(selected.size==1)listOf(pageId) else store.insertPages(book,current.index+1,selected.size)
                selected.forEachIndexed { i,index->check();val before=store.pageById(book,ids[i]);val r=prototype(index)
                    val ref=r.copy(path=mapping.getValue(display.name),source=if(r.source.isBlank())"" else mapping.getValue(display.name),original=mapping.getValue(original.name),docked=docked,order=(before.references.maxOfOrNull { it.order } ?: 0)+1)
                    store.savePage(book,ids[i],before,before.copy(references=before.references+ref));progress(i+1,selected.size)
                };check();store.setCurrentId(book,ids.first());ids
            }
        } catch(e:Throwable){copied.forEach { it.delete() };throw e}
    }
    override fun close(){cancelled=true;directory.deleteRecursively()}
    companion object {
        private fun directory(ctx:Context)=File(ctx.cacheDir,"sketch-import-"+UUID.randomUUID()).apply { check(mkdirs()) }
        fun prepare(ctx:Context,store:SketchStore,uri:Uri,mime:String?,cancel:()->Boolean={false},progress:(String)->Unit={}):SketchImportSession {
            val dir=directory(ctx)
            try {
                val name=ctx.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst())it.getString(0) else null } ?: uri.lastPathSegment ?: "Reference"
                val source=File(dir,"source.bin");ctx.contentResolver.openInputStream(uri)?.use { input->source.outputStream().use { out->val buffer=ByteArray(65536);var total=0L
                    while(true){if(cancel())throw CancellationException("Import canceled");val n=input.read(buffer);if(n<0)break;total+=n;require(total<=512L*1024*1024){"Choose a file smaller than 512 MB"};out.write(buffer,0,n)} }
                } ?: error("Could not read the selected file")
                val header=ByteArray(80);val read=source.inputStream().use { it.read(header).coerceAtLeast(0) }
                val isPdf=read>=4 && String(header,0,4,Charsets.ISO_8859_1)=="%PDF"
                val zip=read>=2 && header[0]=='P'.code.toByte() && header[1]=='K'.code.toByte()
                val isDocx=zip && ZipFile(source).use { it.getEntry("word/document.xml")!=null }
                val isEpub=zip && !isDocx && ZipFile(source).use { z -> z.getEntry("META-INF/container.xml")!=null ||
                    z.getEntry("mimetype")?.let { e -> z.getInputStream(e).use { String(it.readBytes()).trim() } }=="application/epub+zip" }
                val isMobi=read>=68 && String(header,60,8,Charsets.ISO_8859_1).let { it=="BOOKMOBI" || it=="TEXtREAd" }
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true;BitmapFactory.decodeFile(source.path,this) }
                val image=bounds.outWidth>0
                val ext=when { isPdf->"pdf";isDocx->"docx";isEpub->"epub";isMobi->"mobi";image->when(bounds.outMimeType){"image/jpeg"->"jpg";"image/webp"->"webp";else->"png"};mime=="text/plain" || name.endsWith(".txt",true)->"txt"
                    else->error("Choose a picture (JPG, PNG, WebP) or a PDF, EPUB, MOBI, Word (.docx) or text file") }
                val original=File(dir,"original.$ext");check(source.renameTo(original))
                val title=name.substringBeforeLast('.').ifBlank { name }
                // Books and documents are laid out as pages, then used like a PDF.
                val flow:FlowDocument?=when(ext){"docx"->DocxParser.parse(original,title);"txt"->TextParser.parseText(original,title)
                    "epub"->EpubParser.parse(original,title);"mobi"->MobiParser.parse(original,title);else->null}
                val display=if(flow!=null)File(dir,"document.pdf").also { convert(flow,it,cancel,progress) } else original
                val count=pageCount(display);require(count in 1..10000){"Choose a document with at most 10,000 pages"}
                return SketchImportSession(store,dir,name,original,display,count)
            }catch(e:Throwable){dir.deleteRecursively();throw e}
        }
        fun excerpt(ctx:Context,store:SketchStore,path:String?,text:String?,book:Long,location:String,label:String):SketchImportSession {
            val dir=directory(ctx)
            try {
                val original:File;val display:File
                if(path!=null){val source=File(path).canonicalFile
                    require(source.toPath().startsWith(ctx.filesDir.canonicalFile.toPath()) || SketchLinks.isTemp(ctx,source)){"Invalid excerpt source"}
                    original=File(dir,"excerpt.png");source.copyTo(original);display=original
                    if(SketchLinks.isTemp(ctx,source))source.delete()}
                else {original=File(dir,"excerpt.txt").apply { writeText(text.orEmpty()) };display=File(dir,"excerpt.pdf");convert(TextParser.parseString(text.orEmpty(),label),display,{false})}
                return SketchImportSession(store,dir,label.ifBlank { "Reading excerpt" },original,display,pageCount(display),book,location)
            }catch(e:Throwable){dir.deleteRecursively();throw e}
        }
        private fun pageCount(file:File)=if(file.extension=="pdf")ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { it.pageCount } } else 1
        private fun convert(doc:FlowDocument,dest:File,cancel:()->Boolean,progress:(String)->Unit={}) {
            doc.use {
                val images=ImageCache(doc.res)
                try {
                    val spec=LayoutSpec(1200,1697,1f,Typeface.SERIF,32f,1.5f,RectF(80f,80f,1120f,1617f),true,false,Color.BLACK)
                    val engine=FlowEngine(doc,spec,emptyList()){images.size(it)}
                    val renderer=PageRenderer(1f).apply { colors=ReaderColors(false);this.images=images;eink=false }
                    val pdf=PdfDocument()
                    try {var number=0
                        for(ch in 0 until engine.chapterCount)for(i in 0 until engine.pageCount(ch)) {
                            if(cancel())throw CancellationException("Import canceled");require(++number<=10000){"Document has too many pages"}
                            if(number%10==1)progress("Laying out page $number…")
                            val layout=engine.page(ch,i)
                            layout.items.filterIsInstance<ImageItem>().forEach { image->images.getBlocking(image.path,image.rect.width().toInt().coerceAtLeast(1),image.rect.height().toInt().coerceAtLeast(1),false) }
                            val page=pdf.startPage(PdfDocument.PageInfo.Builder(1200,1697,number).create());page.canvas.drawColor(Color.WHITE);renderer.draw(page.canvas,layout);pdf.finishPage(page)
                        }
                        dest.outputStream().use { pdf.writeTo(it) }
                    } finally { pdf.close() }
                }finally{images.close()}
            }
        }
    }
}
