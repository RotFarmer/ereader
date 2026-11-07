package com.rotfarmer.ereader.layout

import android.graphics.RectF
import com.rotfarmer.ereader.data.Locator
import com.rotfarmer.ereader.data.WritingSpace
import com.rotfarmer.ereader.doc.PdfDoc
import kotlin.math.min

/**
 * PDF pages, one per screen. A page can be split by a writing space (the rest of the page moves
 * to the next screen, leaving room to write) and blank pages can be inserted after any page.
 */
class PdfEngine(val pdf: PdfDoc, val spec: LayoutSpec, spaces: List<WritingSpace>) : Engine {
    override val isFixed = true
    override val chapterCount = 1

    private class Screen(
        val page: Int,
        val y0: Float,
        val y1: Float,
        val before: WritingSpace? = null,
        val after: WritingSpace? = null,
        val blank: WritingSpace? = null,
        val order: Int = 0,
    ) {
        val start: Locator get() = if (blank != null) Locator(page, 10000 + order) else Locator(page, (y0 * 10000).toInt())
    }

    @Volatile private var screens: List<Screen> = build(spaces)
    private val cache = HashMap<Int, Page>()

    private fun build(spaces: List<WritingSpace>): List<Screen> {
        val splits = spaces.filter { it.kind == WritingSpace.PDF_SPLIT }.groupBy { it.loc.block }
        val blanks = spaces.filter { it.kind == WritingSpace.PDF_BLANK }.groupBy { it.loc.block }
        val out = ArrayList<Screen>(pdf.pageCount)
        for (p in 0 until pdf.pageCount) {
            var y = 0f
            var prev: WritingSpace? = null
            val ss = splits[p]?.filter { it.loc.offset in 200..9800 }?.sortedBy { it.loc.offset } ?: emptyList()
            for (s in ss) {
                val sy = s.loc.offset / 10000f
                if (sy <= y + 0.02f) continue
                out += Screen(p, y, sy, before = prev, after = s)
                y = sy
                prev = s
            }
            out += Screen(p, y, 1f, before = prev)
            blanks[p]?.sortedBy { it.created }?.forEachIndexed { k, b -> out += Screen(p, 1f, 1f, blank = b, order = k) }
        }
        if (out.isEmpty()) out += Screen(0, 0f, 1f)
        return out
    }

    /** Screens shown side by side (2 in landscape two-page mode). */
    private val cols = spec.columns.coerceAtLeast(1)

    private fun spreads() = (screens.size + cols - 1) / cols

    override fun pageCount(ch: Int) = spreads()

    override fun page(ch: Int, idx: Int): Page = synchronized(cache) {
        val i = idx.coerceIn(0, spreads() - 1)
        cache.getOrPut(i) { buildSpread(i) }
    }

    private fun buildSpread(idx: Int): Page {
        val page = Page(0, idx)
        val first = idx * cols
        page.start = screens[first].start
        page.end = screens.getOrNull(first + cols)?.start ?: Locator(pdf.pageCount, 0)
        for (k in 0 until cols) {
            if (first + k < screens.size) addScreen(page, first + k, k)
        }
        return page
    }

    private fun addScreen(page: Page, idx: Int, col: Int) {
        val s = screens[idx]
        val left0 = spec.columnLeft(col)
        val c = RectF(left0, spec.content.top, left0 + spec.content.width(), spec.content.bottom)
        fun add(item: PageItem) {
            item.colLeft = c.left
            item.colRight = c.right
            page.items += item
        }
        if (s.blank != null) {
            add(SpaceItem(RectF(c), s.blank, 0))
            return
        }
        val size = if (pdf.pageCount > 0) pdf.pageSize(s.page) else android.util.SizeF(612f, 792f)
        val whole = s.y0 <= 0f && s.y1 >= 1f
        val scale = min(c.width() / size.width, c.height() / size.height)
        val pw = size.width * scale
        val ph = size.height * scale
        // In two-page mode the pages meet in the middle, like an open book.
        val left = when {
            cols == 2 && col == 0 -> c.right - pw
            cols == 2 && col == 1 -> c.left
            else -> c.left + (c.width() - pw) / 2
        }
        val top = if (whole) c.top + (c.height() - ph) / 2 else c.top - s.y0 * ph
        val pageRect = RectF(left, top, left + pw, top + ph)
        val slice = RectF(left, top + s.y0 * ph, left + pw, top + s.y1 * ph)
        add(PdfSliceItem(slice, s.page, s.y0, s.y1, pageRect))
        if (!whole) {
            val space = s.after ?: s.before
            val part = if (s.after != null) 0 else 1
            if (space != null && c.bottom - slice.bottom > 4) {
                add(SpaceItem(RectF(c.left, slice.bottom, c.right, c.bottom), space, part))
            }
        }
    }

    override fun position(loc: Locator): PagePos {
        val list = screens
        var idx = 0
        for (i in list.indices) if (list[i].start <= loc) idx = i else break
        return PagePos(0, idx / cols)
    }

    override fun totalPages() = spreads()
    override fun globalIndex(pos: PagePos) = pos.idx
    override fun posForGlobal(g: Int) = PagePos(0, g.coerceIn(0, spreads() - 1))

    override fun setSpaces(spaces: List<WritingSpace>, changedBlock: Int) {
        val s = build(spaces)
        synchronized(cache) {
            screens = s
            cache.clear()
        }
    }

    @Volatile private var cancelled = false

    override fun startBackground(fromChapter: Int, onReady: () -> Unit) {
        onReady()
        // Measure every page up front so turning pages never waits on the renderer.
        Thread({
            for (i in 0 until pdf.pageCount) {
                if (cancelled) return@Thread
                pdf.pageSize(i)
            }
        }, "pdf-sizes").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    override fun cancel() {
        cancelled = true
    }
}
