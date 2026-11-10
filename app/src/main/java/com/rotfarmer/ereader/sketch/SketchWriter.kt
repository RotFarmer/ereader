package com.rotfarmer.ereader.sketch

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Saves finished gestures in order, one sketchbook per queue. The queue outlives the editor
 * screen, so leaving a sketchbook never cancels a stroke that is still being written.
 *
 * A failed save is kept and retried automatically (with a growing pause) until it succeeds,
 * so a temporary problem such as a full disk never loses work or traps the user on the screen.
 */
class SketchWriter private constructor(private val store: SketchStore, private val book: Long) {
    private data class Change(val page: Long, val before: SketchPage, val after: SketchPage)

    private val pending = ArrayDeque<Change>()
    private val waiting = mutableListOf<() -> Unit>()
    private val listeners = LinkedHashSet<(String) -> Unit>()
    private var running = false
    private var failures = 0
    private val retryTask = Runnable { drain() }

    var error: String? = null
        private set
    val busy get() = pending.isNotEmpty()
    val state get() = when {
        error != null -> "Not saved · tap to retry"
        busy -> "Saving…"
        else -> "Saved"
    }

    fun addListener(listener: (String) -> Unit) { listeners += listener }
    fun removeListener(listener: (String) -> Unit) { listeners -= listener }

    fun enqueue(page: Long, before: SketchPage, after: SketchPage) {
        pending.addLast(Change(page, before, after))
        notifyState()
        drain()
    }

    /** Runs [action] once everything queued so far is on disk. */
    fun afterSaved(action: () -> Unit) {
        if (!busy) action() else waiting += action
    }

    fun retry() {
        main.removeCallbacks(retryTask)
        failures = 0
        error = null
        notifyState()
        drain()
    }

    fun viewport(info: PageInfo) {
        executor.execute { runCatching { store.viewport(info) } }
    }

    private fun notifyState() {
        val s = state
        listeners.toList().forEach { it(s) }
    }

    private fun drain() {
        if (running) return
        val change = pending.firstOrNull() ?: run {
            error = null
            notifyState()
            val actions = waiting.toList()
            waiting.clear()
            actions.forEach { it() }
            return
        }
        running = true
        executor.execute {
            val failure = runCatching { store.savePage(book, change.page, change.before, change.after) }.exceptionOrNull()
            main.post {
                running = false
                if (failure == null) {
                    pending.removeFirst()
                    failures = 0
                    error = null
                    drain()
                } else {
                    error = failure.message ?: "Storage unavailable"
                    failures++
                    notifyState()
                    // 2 s, 4 s, 8 s … up to a minute between attempts.
                    main.postDelayed(retryTask, (1000L shl failures.coerceAtMost(6)).coerceAtMost(60_000L))
                }
            }
        }
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        /** One thread for every sketchbook: writes to the single database are serialized anyway. */
        private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sketch-writer").apply { isDaemon = true } }
        private val sessions = mutableMapOf<Long, SketchWriter>()

        fun get(context: Context, book: Long) = sessions.getOrPut(book) { SketchWriter(SketchStore.of(context), book) }
    }
}
