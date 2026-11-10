package com.rotfarmer.ereader.sketch

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import com.rotfarmer.ereader.App
import com.rotfarmer.ereader.data.Locator
import com.rotfarmer.ereader.ui.UiColors
import com.rotfarmer.ereader.ui.dialog
import org.json.JSONObject
import java.util.concurrent.Executors

/** Source excerpts own their imported copy; navigation links are optional conveniences. */
object SketchLinks {
    private val io=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    fun send(activity:Activity,book:Long,start:Locator,text:String?,path:String?,label:String) {
        val app=App.of(activity);val ui=UiColors(app.settings.dark)
        fun open(id:Long){activity.startActivity(SketchActivity.intent(activity,id)
            .putExtra("source_book",book).putExtra("source_location",JSONObject().put("block",start.block).put("offset",start.offset).toString())
            .putExtra("source_label",label).putExtra("excerpt_text",text).putExtra("excerpt_path",path))}
        io.execute {
            val result=runCatching { SketchStore(activity.applicationContext).use { it.list() } }
            main.post {
                if(activity.isDestroyed || activity.isFinishing)return@post
                val books=result.getOrElse { Toast.makeText(activity,it.message,Toast.LENGTH_LONG).show();return@post }
                dialog(activity,app.settings.dark).setTitle("Send to sketchbook").setItems((books.map { it.title }+"Create sketchbook").toTypedArray()){_,index->
                    if(index<books.size)open(books[index].id)
                    else {val input=EditText(activity).apply { setSingleLine();setTextColor(ui.fg);setHintTextColor(ui.muted);hint="Sketchbook name" }
                        dialog(activity,app.settings.dark).setTitle("New sketchbook · 350 pages").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Create"){_,_->
                            val title=input.text.toString().trim().ifBlank { "Untitled sketchbook" }
                            io.execute { val created=runCatching { SketchStore(activity.applicationContext).use { it.create(title) } };main.post { if(!activity.isDestroyed)created.onSuccess(::open).onFailure { Toast.makeText(activity,it.message,Toast.LENGTH_LONG).show() } } }
                        }.show()
                    }
                }.show()
            }
        }
    }
}
