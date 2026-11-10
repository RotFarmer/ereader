package com.rotfarmer.ereader.sketch

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

object SketchExport {
    fun pdf(store:SketchStore,book:Long,pageIds:List<Long>,output:OutputStream,dark:Boolean=false) {
        require(pageIds.isNotEmpty()) { "Choose pages to export" }
        val pdf=PdfDocument()
        try {
            pageIds.forEachIndexed { index,id->
                val content=store.pageById(book,id)
                val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,index+1).create())
                val bitmaps=mutableListOf<Bitmap>()
                try { page.canvas.scale(595f,595f)
                    SketchRenderer.draw(page.canvas,content,595f,dark) { ref->
                        SketchRenderer.decode(store.referencesDir,ref,1800)?.also { bitmaps+=it } ?: error("Could not render ${ref.name}")
                    }
                }finally{pdf.finishPage(page);bitmaps.forEach { it.recycle() }}
            }
            pdf.writeTo(output)
        }finally{pdf.close()}
    }
    fun png(store:SketchStore,book:Long,pageId:Long,output:OutputStream,dark:Boolean=false) {
        val content=store.pageById(book,pageId)
        val b=Bitmap.createBitmap(1600,(1600*SketchGeometry.HEIGHT).toInt(),Bitmap.Config.ARGB_8888)
        val bitmaps=mutableListOf<Bitmap>()
        try { val c=android.graphics.Canvas(b);c.scale(1600f,1600f)
            SketchRenderer.draw(c,content,1600f,dark){ref->SketchRenderer.decode(store.referencesDir,ref,1600)?.also { bitmaps+=it } ?: error("Could not render ${ref.name}")}
            check(b.compress(Bitmap.CompressFormat.PNG,100,output)){"Could not save PNG"}
        }finally{b.recycle();bitmaps.forEach { it.recycle() }}
    }
}
