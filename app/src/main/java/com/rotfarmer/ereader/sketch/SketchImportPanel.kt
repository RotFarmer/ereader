package com.rotfarmer.ereader.sketch

import android.app.ProgressDialog
import android.graphics.Bitmap
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.rotfarmer.ereader.ui.TextButton
import com.rotfarmer.ereader.ui.dialogTheme
import com.rotfarmer.ereader.ui.dp
import com.rotfarmer.ereader.ui.label
import com.rotfarmer.ereader.ui.panelBg
import com.rotfarmer.ereader.ui.sectionTitle
import com.rotfarmer.ereader.ui.toggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Choose which pages of an imported file to place in the sketchbook, with a preview. */
class SketchImportPanel(private val host: SketchActivity, private val session: SketchImportSession, private val closed: () -> Unit) {
    fun show() {
        val ui = host.colors
        var committing = false
        var request = 0
        var bitmap: Bitmap? = null
        var pinned = false
        val image = ImageView(host).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = panelBg(host, ui, 4f, 1f, if (ui.dark) 0xFF202020.toInt() else android.graphics.Color.WHITE)
            setPadding(host.dp(4), host.dp(4), host.dp(4), host.dp(4))
            if (host.settings.dark && session.isDocument) colorFilter = com.rotfarmer.ereader.render.ReaderColors(true).pdfFilter
        }
        val pagesInput = EditText(host).apply {
            setTextColor(ui.fg); setHintTextColor(ui.muted); hint = "e.g. 1, 3-5"; setText("1"); setSingleLine(); textSize = 15f
            background = panelBg(host, ui, 8f, 1f, ui.soft)
            setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8))
        }
        fun preview(index: Int) {
            val generation = ++request
            host.scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { session.preview(index) }.getOrNull() }
                if (!committing && generation == request) { image.setImageBitmap(null); bitmap?.recycle(); bitmap = result; image.setImageBitmap(result) } else result?.recycle()
            }
        }
        fun commit() {
            val selected = try { parse(pagesInput.text.toString(), session.pageCount) } catch (e: Exception) { host.toast(e.message ?: "Invalid page selection"); return }
            val current = host.info ?: return
            committing = true
            host.popups.dismissAll()
            @Suppress("DEPRECATION")
            val progress = ProgressDialog(host, dialogTheme(host.settings.dark)).apply {
                setTitle("Importing"); setMessage("Preparing the pages…"); setCancelable(false)
                setButton(android.app.AlertDialog.BUTTON_NEGATIVE, "Cancel") { _, _ -> session.cancelled = true }
                show()
            }
            host.io({
                try {
                    session.commit(host.bookId, current.id, selected, pinned) { done, total -> host.runOnUiThread { progress.setMessage("$done of $total pages") } }
                } finally {
                    session.close()
                    host.runOnUiThread { progress.dismiss(); closed() }
                }
            }) { ids -> host.navigateId(ids.first()) }
        }
        host.panel(session.name, { b ->
            b.addView(image, LinearLayout.LayoutParams(-1, host.dp(250)).apply { topMargin = host.dp(8) })
            if (session.pageCount > 1) {
                b.addView(sectionTitle(host, "Pages to import · ${session.pageCount} in the file", ui))
                val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(pagesInput, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = host.dp(8) })
                row.addView(TextButton(host, "Preview", ui).apply {
                    setOnClickListener {
                        runCatching { parse(pagesInput.text.toString(), session.pageCount).first() }
                            .onSuccess { preview(it) }.onFailure { host.toast(it.message ?: "Invalid page selection") }
                    }
                })
                b.addView(row)
                b.addView(label(host, "One page goes onto this page. Several pages are added as new pages after it.", ui, 12.5f, ui.muted).apply { setPadding(0, host.dp(6), 0, 0) })
            }
            b.addView(toggleRow(host, "Pin beside the page", "In its own panel instead of under your drawing", false, ui) { pinned = it })
            val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END; setPadding(0, host.dp(10), 0, 0) }
            actions.addView(TextButton(host, "Cancel", ui).apply { setOnClickListener { host.popups.dismissAll() } },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = host.dp(8) })
            actions.addView(TextButton(host, "Import", ui, filled = true).apply { setOnClickListener { commit() } })
            b.addView(actions)
        }, null, 460, onDismiss = {
            image.setImageBitmap(null); bitmap?.recycle(); bitmap = null
            if (!committing) { session.close(); closed() }
        })
        preview(0)
    }

    companion object {
        fun parse(value: String, count: Int): List<Int> {
            require(value.isNotBlank()) { "Enter page numbers" }
            val result = sortedSetOf<Int>()
            value.split(',').forEach { piece ->
                val parts = piece.trim().split('-')
                require(parts.size in 1..2) { "Use pages such as 1, 3-5" }
                val first = parts[0].trim().toIntOrNull() ?: error("Invalid page number")
                val last = if (parts.size == 2) parts[1].trim().toIntOrNull() ?: error("Invalid page range") else first
                require(first in 1..count && last in first..count) { "Choose pages from 1 to $count" }
                for (i in first..last) result += i - 1
            }
            return result.toList()
        }
    }
}
