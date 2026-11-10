package com.rotfarmer.ereader.sketch

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.rotfarmer.ereader.render.Fonts
import com.rotfarmer.ereader.ui.TextButton
import com.rotfarmer.ereader.ui.dp
import com.rotfarmer.ereader.ui.label
import com.rotfarmer.ereader.ui.panelBg
import com.rotfarmer.ereader.ui.sectionTitle
import com.rotfarmer.ereader.ui.slider
import com.rotfarmer.ereader.ui.toggleRow

/** The pictures and documents on this page that you trace over: choose one, then adjust it. */
class SketchReferencePanel(private val host: SketchActivity, private val anchor: View) {
    private var selected = host.page.references.maxByOrNull { it.order }?.id ?: -1L

    fun show() = render()

    private fun current() = host.page.references.firstOrNull { it.id == selected }

    private fun change(r: ReferenceLayer) {
        host.edit(host.page.copy(references = host.page.references.map { if (it.id == r.id) r else it }))
    }

    private fun render() {
        val refs = host.page.references
        val ref = current() ?: refs.firstOrNull()?.also { selected = it.id } ?: run { host.popups.dismissAll(); return }
        val ui = host.colors
        host.panel("References", { b ->
            if (refs.size > 1) {
                b.addView(sectionTitle(host, "On this page", ui))
                for (r in refs.sortedByDescending { it.order }) b.addView(row(r, r.id == ref.id))
            }
            b.addView(sectionTitle(host, ref.name, ui).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            fun action(t: String, f: () -> Unit) = actions.addView(TextButton(host, t, ui).apply { setOnClickListener { f() } },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = host.dp(6) })
            action("Move & resize") { host.arrangeReference(ref.id) }
            if (ref.sourceBook >= 0) action("Open book") { host.popups.dismissAll(); host.openSource(ref) }
            action("Remove") {
                host.edit(host.page.copy(references = host.page.references.filterNot { it.id == ref.id }))
                host.toast("Reference removed. Undo brings it back; your drawing is untouched.")
                render()
            }
            b.addView(actions)
            b.addView(toggleRow(host, "Show", null, ref.visible, ui) { change(current()!!.copy(visible = it)) })
            b.addView(toggleRow(host, "Pin beside the page", "In its own panel, not under your drawing", ref.docked, ui) { change(current()!!.copy(docked = it, visible = true)) })
            b.addView(toggleRow(host, "Lock in place", "So a finger can't move it by accident", ref.locked, ui) { change(current()!!.copy(locked = it)) })
            b.addView(sectionTitle(host, "Opacity", ui))
            b.addView(slider(host, 100, (ref.opacity * 100).toInt(), ui) { v, done -> if (done) change(current()!!.copy(opacity = v / 100f)) })
            b.addView(sectionTitle(host, "Dim in dark mode", ui))
            b.addView(slider(host, 80, (ref.dim * 100).toInt(), ui) { v, done -> if (done) change(current()!!.copy(dim = v / 100f)) })
            b.addView(sectionTitle(host, "Crop", ui))
            fun crop(name: String, value: Float, apply: (ReferenceLayer, Float) -> ReferenceLayer) {
                b.addView(label(host, name, ui, 13f, ui.muted).apply { setPadding(host.dp(4), host.dp(2), 0, 0) })
                b.addView(slider(host, 90, (value * 100).toInt(), ui) { v, done -> if (done) change(apply(current()!!, v / 100f)) })
            }
            crop("Left", ref.cropLeft) { r, v -> r.copy(cropLeft = v.coerceAtMost(r.cropRight - .05f)) }
            crop("Top", ref.cropTop) { r, v -> r.copy(cropTop = v.coerceAtMost(r.cropBottom - .05f)) }
            crop("Right", 1f - ref.cropRight) { r, v -> r.copy(cropRight = (1f - v).coerceAtLeast(r.cropLeft + .05f)) }
            crop("Bottom", 1f - ref.cropBottom) { r, v -> r.copy(cropBottom = (1f - v).coerceAtLeast(r.cropTop + .05f)) }
            b.addView(sectionTitle(host, "Layer", ui))
            val order = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            order.addView(TextButton(host, "Bring to front", ui).apply {
                setOnClickListener { change(current()!!.copy(order = (host.page.references.maxOfOrNull { it.order } ?: 0) + 1)); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = host.dp(6) })
            order.addView(TextButton(host, "Send to back", ui).apply {
                setOnClickListener { change(current()!!.copy(order = (host.page.references.minOfOrNull { it.order } ?: 0) - 1)); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            b.addView(order)
        }, anchor, 400)
    }

    private fun row(r: ReferenceLayer, active: Boolean): View {
        val ui = host.colors
        return LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(8), host.dp(8), host.dp(8), host.dp(8))
            if (active) background = panelBg(host, ui, 6f, 0f, ui.soft)
            addView(TextView(host).apply {
                text = if (r.source.endsWith(".pdf", true)) "" else ""
                typeface = Fonts.icons(host); textSize = 20f; setTextColor(ui.muted); includeFontPadding = false
            }, LinearLayout.LayoutParams(host.dp(28), -2))
            addView(label(host, r.name, ui, 14.5f, bold = active).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            if (!r.visible) addView(label(host, "hidden", ui, 12f, ui.muted))
            else if (r.docked) addView(label(host, "pinned", ui, 12f, ui.muted))
            isClickable = true
            setOnClickListener { selected = r.id; render() }
        }
    }
}
