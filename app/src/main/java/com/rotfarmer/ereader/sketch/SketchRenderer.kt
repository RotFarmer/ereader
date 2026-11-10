package com.rotfarmer.ereader.sketch

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.rotfarmer.ereader.ink.InkPainter
import com.rotfarmer.ereader.ink.Placement
import com.rotfarmer.ereader.render.ReaderColors
import java.io.File

/** Shared page rendering for canvas, thumbnails and exports. Decode on an IO thread. */
object SketchRenderer {
    fun asset(dir:File,name:String):File {
        require(name.isNotBlank()) { "Missing reference asset" }
        val f=File(dir,name).canonicalFile
        require(f.toPath().startsWith(dir.canonicalFile.toPath()) && f!=dir.canonicalFile) { "Invalid reference path" }
        return f
    }
    fun decode(dir:File,ref:ReferenceLayer,pixels:Int):Bitmap? {
        val target=pixels.coerceIn(64,3072)
        if(ref.source.endsWith(".pdf",true)) {
            ParcelFileDescriptor.open(asset(dir,ref.source),ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    pdf.openPage(ref.sourcePage.coerceIn(0,pdf.pageCount-1)).use { page ->
                        val scale=minOf(target.toFloat()/page.width,3072f/page.height)
                        val b=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE);page.render(b,null,Matrix().apply { setScale(scale,scale) },PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return b
                    }
                }
            }
        }
        val f=asset(dir,ref.path)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(f.path,bounds)
        if(bounds.outWidth<=0)return null
        var sample=1
        while(bounds.outWidth/sample>target*1.5f || bounds.outHeight/sample>3072)sample*=2
        return BitmapFactory.decodeFile(f.path,BitmapFactory.Options().apply { inSampleSize=sample })
    }
    fun reference(c:Canvas,r:ReferenceLayer,b:Bitmap,dark:Boolean) {
        if(!r.visible || r.docked)return
        val dst=RectF(r.x,r.y,r.x+r.width,r.y+r.height)
        val src=Rect((r.cropLeft*b.width).toInt(),(r.cropTop*b.height).toInt(),(r.cropRight*b.width).toInt(),(r.cropBottom*b.height).toInt())
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha=(r.opacity.coerceIn(0f,1f)*255).toInt()
            colorFilter=when { dark && r.source.endsWith(".pdf",true) -> ReaderColors(true).pdfFilter
                dark && r.dim>0 -> ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1-r.dim,0f,0f,0f,0f,0f,1-r.dim,0f,0f,0f,0f,0f,1-r.dim,0f,0f,0f,0f,0f,1f,0f)))
                else -> null }
        }
        c.save();c.rotate(r.rotation,dst.centerX(),dst.centerY());c.drawBitmap(b,src,dst,paint);c.restore()
    }
    fun draw(c:Canvas,page:SketchPage,unit:Float,dark:Boolean,pressure:Float=0.75f,ink:InkPainter=InkPainter(),bitmap:(ReferenceLayer)->Bitmap?) {
        val rect=RectF(0f,0f,1f,SketchGeometry.HEIGHT)
        SketchPaper.draw(c,rect,page.paper,1f/unit.coerceAtLeast(1f),dark,page.spacing)
        c.save();c.clipRect(rect)
        page.references.sortedBy { it.order }.forEach { r -> if(r.visible && !r.docked)bitmap(r)?.let { reference(c,r,it,dark) } }
        val painter=ink.apply { this.dark=dark;sensitivity=pressure }
        val placement=Placement(0f,0f,1f,null)
        page.strokes.forEach { painter.draw(c,it,placement) }
        c.restore()
    }
    fun thumbnail(dir:File,page:SketchPage,width:Int,dark:Boolean):Bitmap {
        val b=Bitmap.createBitmap(width,(width*SketchGeometry.HEIGHT).toInt(),Bitmap.Config.ARGB_8888)
        val c=Canvas(b);c.scale(width.toFloat(),width.toFloat())
        val refs=mutableListOf<Bitmap>()
        try { draw(c,page,width.toFloat(),dark) { r -> runCatching { decode(dir,r,width) }.getOrNull()?.also { refs+=it } } }
        finally { refs.forEach { it.recycle() } };return b
    }
}
