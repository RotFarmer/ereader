package com.rotfarmer.ereader.sketch

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Process-lifetime save queue: leaving an Activity does not cancel completed gestures. */
class SketchWriter private constructor(context:Context, private val book:Long) {
    private val store=SketchStore(context.applicationContext)
    private val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private data class Change(val page:Long,val before:SketchPage,val after:SketchPage)
    private val pending=ArrayDeque<Change>()
    private val waiting=mutableListOf<()->Unit>()
    private var running=false
    var error:String?=null;private set
    var listener:((String)->Unit)?=null
    val busy get()=pending.isNotEmpty()
    val state get()=error?.let { "Save failed · tap to retry" } ?: if(busy) "Saving…" else "Saved"
    fun enqueue(page:Long,before:SketchPage,after:SketchPage) { pending.addLast(Change(page,before,after));notifyState();drain() }
    fun afterSaved(action:()->Unit) { if(!busy)action() else waiting+=action }
    fun retry(){error=null;drain()}
    fun viewport(info:PageInfo){executor.execute { runCatching { store.viewport(info) } }}
    private fun notifyState(){listener?.invoke(state)}
    private fun drain() {
        if(running || error!=null)return
        val change=pending.firstOrNull() ?: run { notifyState();val actions=waiting.toList();waiting.clear();actions.forEach { it() };return }
        running=true
        executor.execute {
            val failure=runCatching { store.savePage(book,change.page,change.before,change.after) }.exceptionOrNull()
            main.post { running=false
                if(failure==null)pending.removeFirst() else error=failure.message ?: "Storage unavailable"
                notifyState();if(failure==null)drain()
            }
        }
    }
    companion object {
        private val sessions=mutableMapOf<Long,SketchWriter>()
        fun get(context:Context,book:Long)=sessions.getOrPut(book){SketchWriter(context,book)}
    }
}
