package com.rotfarmer.ereader.sketch

import android.graphics.Bitmap
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rotfarmer.ereader.ui.Segmented
import com.rotfarmer.ereader.ui.card
import com.rotfarmer.ereader.ui.dp
import com.rotfarmer.ereader.ui.label
import com.rotfarmer.ereader.ui.panelBg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every page as a thumbnail, with search and filters. Holds page details and a small thumbnail
 * cache, never the contents of hundreds of pages. Tap to open a page; long-press for its menu.
 */
class SketchPageBrowser(private val host: SketchActivity, private val pages: List<PageInfo>) {
    fun show() {
        val ui = host.colors
        var query = ""; var filter = 0; var shown = pages
        val current = host.info?.id
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val cache = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) { override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount }
        val requests = mutableSetOf<Long>()

        val card = card(host, ui)
        val head = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(label(host, "Pages", ui, 19f, bold = true, serif = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(label(host, "${pages.count { it.used }} used of ${pages.size}", ui, 13f, ui.muted))
        card.addView(head)
        val search = EditText(host).apply {
            hint = "Page number, title or section"; setSingleLine(); textSize = 15f
            setTextColor(ui.fg); setHintTextColor(ui.muted)
            background = panelBg(host, ui, 8f, 1f, ui.soft)
            setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8))
        }
        card.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(10) })

        val grid = GridView(host).apply {
            numColumns = GridView.AUTO_FIT; columnWidth = host.dp(132); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = host.dp(12); verticalSpacing = host.dp(14)
            setPadding(0, host.dp(12), 0, host.dp(4)); clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = shown[position].id
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val p = shown[position]
                val cell = (recycled as? LinearLayout) ?: LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = AbsListView.LayoutParams(-1, -2)
                    addView(ImageView(host).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(host.dp(2), host.dp(2), host.dp(2), host.dp(2)) },
                        LinearLayout.LayoutParams(-1, host.dp(176)))
                    addView(label(host, "", ui, 13f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, host.dp(6), 0, 0) })
                    addView(label(host, "", ui, 11.5f, ui.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                }
                cell.tag = p.id
                val image = cell.getChildAt(0) as ImageView
                image.background = panelBg(host, ui, 3f, if (p.id == current) 2.5f else 1f, if (ui.dark) 0xFF202020.toInt() else android.graphics.Color.WHITE)
                image.setImageBitmap(cache.get(p.id))
                (cell.getChildAt(1) as TextView).text = "${p.index + 1}${if (p.bookmarked) "  ★" else ""}${if (p.title.isNotBlank()) "  ${p.title}" else ""}"
                (cell.getChildAt(2) as TextView).text = p.section
                if (p.used && cache.get(p.id) == null && requests.add(p.id)) jobs.launch {
                    try {
                        val bitmap = withContext(Dispatchers.IO) { SketchRenderer.thumbnail(host.store.referencesDir, host.store.pageById(host.bookId, p.id), 240, host.settings.dark) }
                        cache.put(p.id, bitmap)
                        for (i in 0 until grid.childCount) { val v = grid.getChildAt(i) as? LinearLayout ?: continue; if (v.tag == p.id) (v.getChildAt(0) as ImageView).setImageBitmap(bitmap) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { /* A page that can't be drawn just has no thumbnail. */ }
                    finally { requests.remove(p.id) }
                }
                return cell
            }
        }
        grid.adapter = adapter
        fun update() {
            shown = pages.filter {
                (filter != 1 || it.used) && (filter != 2 || it.bookmarked) &&
                    (query.isBlank() || "${it.index + 1}" == query || it.title.contains(query, true) || it.section.contains(query, true))
            }
            adapter.notifyDataSetChanged()
        }
        card.addView(Segmented(host, listOf("All", "Used", "Bookmarked"), 0, ui) { filter = it; update() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(10) })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString().trim(); update() }
            override fun afterTextChanged(s: Editable?) {}
        })
        val height = minOf(host.dp(620), (host.resources.displayMetrics.heightPixels * 0.62f).toInt())
        card.addView(grid, LinearLayout.LayoutParams(-1, height))
        card.minimumWidth = minOf(host.dp(760), host.resources.displayMetrics.widthPixels - host.dp(32))
        grid.setOnItemClickListener { _, _, position, _ -> val id = shown[position].id; host.popups.dismissAll(); host.navigateId(id) }
        grid.setOnItemLongClickListener { _, _, position, _ -> val p = shown[position]; host.popups.dismissAll(); host.pageMenu(p); true }
        host.popups.dismissAll()
        host.popups.show(card, null, maxWidthDp = 760, onDismiss = { jobs.cancel(); cache.evictAll() })
        // Start near the current page.
        pages.indexOfFirst { it.id == current }.takeIf { it > 0 }?.let { grid.setSelection(it) }
    }
}
