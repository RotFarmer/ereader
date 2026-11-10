package com.rotfarmer.ereader.sketch

import android.app.ProgressDialog
import android.widget.*
import com.rotfarmer.ereader.ui.*
import kotlinx.coroutines.*

class SketchImportPanel(private val host:SketchActivity,private val session:SketchImportSession,private val closed:()->Unit) {
    fun show() {
        val body=host.column()
        val image=ImageView(host).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER
            if (host.settings.dark && session.isDocument) {
                colorFilter = com.rotfarmer.ereader.render.ReaderColors(true).pdfFilter
            }
        }
        body.addView(image,LinearLayout.LayoutParams(-1,host.dp(230)))
        val input=EditText(host).apply { setTextColor(host.colors.fg);setHintTextColor(host.colors.muted);hint="Pages, e.g. 1, 3-5";setText("1");setSingleLine() }
        body.addView(label(host,"${session.pageCount} ${if(session.pageCount==1)"page" else "pages"} · choose pages to import",host.colors,14f));body.addView(input)
        val pinned=CheckBox(host).apply { text="Pin beside the page";setTextColor(host.colors.fg) };body.addView(pinned)
        body.addView(label(host,"Multiple pages are inserted after the current page.",host.colors,12f,host.colors.muted))
        val dialog=dialog(host,host.settings.dark).setTitle(session.name).setView(body).setNegativeButton("Cancel",null).setPositiveButton("Import",null).show()
        var committing=false;var request=0;var bitmap:android.graphics.Bitmap?=null
        fun preview(index:Int){val generation=++request;host.scope.launch {
            val result=withContext(Dispatchers.IO){runCatching { session.preview(index) }.getOrNull()}
            if(dialog.isShowing && generation==request){image.setImageBitmap(null);bitmap?.recycle();bitmap=result;image.setImageBitmap(result)}else result?.recycle()
        }}
        body.addView(host.button("Preview selected page"){runCatching { parse(input.text.toString(),session.pageCount).first() }.onSuccess { preview(it) }.onFailure { host.toast(it.message ?: "Invalid page selection") }})
        preview(0)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selected=try { parse(input.text.toString(),session.pageCount) }catch(e:Exception){host.toast(e.message ?: "Invalid page selection");return@setOnClickListener}
            val current=host.info ?: return@setOnClickListener
            committing=true;dialog.dismiss()
            val progress=ProgressDialog(host).apply { setTitle("Importing references");setMessage("Preparing selected pages…");setCancelable(false);setButton(android.app.AlertDialog.BUTTON_NEGATIVE,"Cancel"){_,_->session.cancelled=true};show() }
            host.io({try { session.commit(host.bookId,current.id,selected,pinned.isChecked){done,total->host.runOnUiThread { progress.setMessage("$done of $total pages") }} }finally{session.close();host.runOnUiThread { progress.dismiss();closed() }}}){ids->host.navigateId(ids.first())}
        }
        dialog.setOnDismissListener { image.setImageBitmap(null);bitmap?.recycle();if(!committing){session.close();closed()} }
    }
    companion object {
        fun parse(value:String,count:Int):List<Int> {
            require(value.isNotBlank()){ "Enter page numbers" };val result=sortedSetOf<Int>()
            value.split(',').forEach { piece->val parts=piece.trim().split('-');require(parts.size in 1..2){"Use pages such as 1, 3-5"}
                val first=parts[0].trim().toIntOrNull() ?: error("Invalid page number");val last=if(parts.size==2)parts[1].trim().toIntOrNull() ?: error("Invalid page range") else first
                require(first in 1..count && last in first..count){"Choose pages from 1 to $count"};for(i in first..last)result+=i-1
            };return result.toList()
        }
    }
}
