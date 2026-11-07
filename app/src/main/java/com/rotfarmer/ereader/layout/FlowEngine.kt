package com.rotfarmer.ereader.layout

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.Size
import com.rotfarmer.ereader.data.Locator
import com.rotfarmer.ereader.data.WritingSpace
import com.rotfarmer.ereader.doc.FlowDocument
import com.rotfarmer.ereader.doc.ImageBlock
import com.rotfarmer.ereader.doc.RuleBlock
import com.rotfarmer.ereader.doc.TextBlock
import com.rotfarmer.ereader.render.Fonts
import kotlin.math.min

/**
 * Splits a reflowable document into pages. Chapters are laid out lazily (the current one first)
 * and the rest in a background thread, so books open instantly.
 */
class FlowEngine(
    val doc: FlowDocument,
    val spec: LayoutSpec,
    spaces: List<WritingSpace>,
    private val imageSize: (String) -> Size?,
) : Engine {
    override val isFixed = false
    override val chapterCount = doc.chapters.size

    private val lock = Any()
    private val pages = arrayOfNulls<List<Page>>(chapterCount)
    @Volatile private var offsets: IntArray? = null
    @Volatile private var cancelled = false
    private var spacesByBlock: Map<Int, List<WritingSpace>> = group(spaces)

    private val em = spec.textSizePx
    private val content = spec.content
    private val cw = content.width()
    private val ch = content.height()

    private val body = paint(spec.typeface, em)

    /**
     * Line-spacing multiplier that gives lines exactly spec.lineSpacing em apart, whatever the
     * font's own line height is (Literata's is tall, Fraktur's short), so "Normal" feels the same.
     */
    private val bodyMult: Float = run {
        val fm = body.fontMetrics
        val natural = (fm.descent - fm.ascent).coerceAtLeast(1f)
        (em * spec.lineSpacing / natural).coerceIn(0.8f, 2.6f)
    }
    private val pre = paint(Typeface.MONOSPACE, em * 0.82f)
    private val caption = paint(spec.typeface, em * 0.86f)
    private val headings = HashMap<Int, TextPaint>()

    private fun paint(tf: Typeface, size: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = tf
        textSize = size
        color = spec.textColor
    }

    private fun headingPaint(level: Int) = headings.getOrPut(level) {
        val scale = when (level) {
            1 -> 1.55f; 2 -> 1.35f; 3 -> 1.2f; 4 -> 1.1f; else -> 1.0f
        }
        paint(Fonts.styled(spec.typeface, Typeface.BOLD), em * scale)
    }

    private fun group(list: List<WritingSpace>) = list.filter { it.kind == WritingSpace.FLOW }
        .sortedWith(compareBy({ it.loc.offset }, { it.created }))
        .groupBy { it.loc.block }

    // ------------------------------------------------------------------ Engine

    override fun pageCount(ch: Int): Int = chapterPages(ch).size

    override fun page(ch: Int, idx: Int): Page {
        val ps = chapterPages(ch)
        return ps[idx.coerceIn(0, ps.size - 1)]
    }

    private fun chapterPages(c: Int): List<Page> {
        pages[c]?.let { return it }
        synchronized(lock) {
            pages[c]?.let { return it }
            val p = layoutChapter(c)
            pages[c] = p
            return p
        }
    }

    override fun position(loc: Locator): PagePos {
        val c = doc.chapterOf(loc.block.coerceIn(0, doc.blocks.size - 1))
        val ps = chapterPages(c)
        var idx = 0
        for (i in ps.indices) if (ps[i].start <= loc) idx = i else break
        // A full-page writing space starts where the next page starts; prefer the space.
        while (idx > 0 && ps[idx - 1].start == loc && ps[idx].start == loc) idx--
        return PagePos(c, idx)
    }

    override fun totalPages(): Int {
        val o = offsets ?: computeOffsets() ?: return -1
        return o[chapterCount]
    }

    override fun globalIndex(pos: PagePos): Int {
        val o = offsets ?: return -1
        return o[pos.ch] + pos.idx
    }

    override fun posForGlobal(g: Int): PagePos? {
        val o = offsets ?: return null
        for (c in 0 until chapterCount) if (g < o[c + 1]) return PagePos(c, (g - o[c]).coerceAtLeast(0))
        return PagePos(chapterCount - 1, pageCount(chapterCount - 1) - 1)
    }

    private fun computeOffsets(): IntArray? {
        val o = IntArray(chapterCount + 1)
        for (c in 0 until chapterCount) {
            val p = pages[c] ?: return null
            o[c + 1] = o[c] + p.size
        }
        offsets = o
        return o
    }

    override fun setSpaces(spaces: List<WritingSpace>, changedBlock: Int) {
        synchronized(lock) {
            spacesByBlock = group(spaces)
            if (changedBlock >= 0) pages[doc.chapterOf(changedBlock)] = null else pages.fill(null)
            offsets = null
        }
        if (changedBlock >= 0) {
            chapterPages(doc.chapterOf(changedBlock))
            computeOffsets()
        }
    }

    override fun startBackground(fromChapter: Int, onReady: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val order = (fromChapter until chapterCount) + (fromChapter - 1 downTo 0)
            for (c in order) {
                if (cancelled) return@Thread
                chapterPages(c)
            }
            if (!cancelled && computeOffsets() != null) main.post { if (!cancelled) onReady() }
        }, "paginate").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }

    override fun cancel() {
        cancelled = true
    }

    // ------------------------------------------------------------------ layout

    private inner class Builder(val chapter: Int) {
        val out = ArrayList<Page>()
        var page = Page(chapter, 0)
        var y = 0f
        var lastKind = -1
        /** Which column of the screen we are filling (two-page mode has two). */
        var col = 0
        var colItems = 0

        val left: Float get() = spec.columnLeft(col)

        fun add(item: PageItem) {
            item.colLeft = left
            item.colRight = left + cw
            page.items += item
            colItems++
        }

        /** Moves on to the next column, or the next screen. */
        fun newPage() {
            if (col == 0 && page.items.isEmpty()) return
            if (col + 1 < spec.columns) {
                col++; y = 0f; colItems = 0
                return
            }
            out.add(page)
            page = Page(chapter, out.size)
            y = 0f
            col = 0
            colItems = 0
        }

        fun finish(): List<Page> {
            if (page.items.isNotEmpty() || out.isEmpty()) out.add(page)
            for (p in out) {
                p.start = p.items.firstOrNull()?.let { startOf(it) } ?: Locator(doc.chapters[chapter].start, 0)
            }
            for (i in out.indices) {
                out[i].end = if (i + 1 < out.size) out[i + 1].start else Locator(doc.chapters[chapter].end, 0)
            }
            return out
        }

        fun startOf(it: PageItem): Locator = when (it) {
            is TextItem -> Locator(it.block, it.absStart)
            is ImageItem -> Locator(it.block, 0)
            is RuleItem -> Locator(it.block, 0)
            is SpaceItem -> it.space.loc
            is PdfSliceItem -> Locator(it.page, 0)
        }
    }

    private fun layoutChapter(c: Int): List<Page> {
        val chap = doc.chapters[c]
        val b = Builder(c)
        for (bi in chap.start until chap.end) {
            if (cancelled) break
            val block = doc.blocks[bi]
            val spaces = spacesByBlock[bi] ?: emptyList()
            when (block) {
                is TextBlock -> {
                    var from = 0
                    for (s in spaces) {
                        val cut = s.loc.offset.coerceIn(0, block.text.length)
                        if (cut > from) layoutText(b, bi, block, from, cut)
                        placeSpace(b, s)
                        from = maxOf(from, cut)
                    }
                    if (from < block.text.length) layoutText(b, bi, block, from, block.text.length)
                    b.lastKind = block.kind
                }
                is ImageBlock -> {
                    spaces.filter { it.loc.offset == 0 }.forEach { placeSpace(b, it) }
                    placeImage(b, bi, block)
                    spaces.filter { it.loc.offset != 0 }.forEach { placeSpace(b, it) }
                    b.lastKind = -1
                }
                is RuleBlock -> {
                    spaces.filter { it.loc.offset == 0 }.forEach { placeSpace(b, it) }
                    val h = em * 2f
                    if (b.y + h > ch) b.newPage()
                    b.add(RuleItem(RectF(b.left, content.top + b.y, b.left + cw, content.top + b.y + h), bi))
                    b.y += h
                    spaces.filter { it.loc.offset != 0 }.forEach { placeSpace(b, it) }
                    b.lastKind = -1
                }
            }
        }
        return b.finish()
    }

    private fun placeSpace(b: Builder, s: WritingSpace) {
        val h = (s.height.coerceIn(0.05f, 1f) * ch)
        if (b.y > 0 && b.y + h > ch + 1) b.newPage()
        b.add(SpaceItem(RectF(b.left, content.top + b.y, b.left + cw, content.top + b.y + h), s, 0))
        b.y += h
    }

    private fun placeImage(b: Builder, bi: Int, block: ImageBlock) {
        val size = imageSize(block.path) ?: return
        if (size.width <= 0 || size.height <= 0) return
        var w = size.width.toFloat()
        var h = size.height.toFloat()
        // Small pictures keep roughly their CSS size; large ones fill the width.
        val cssScale = spec.density * 0.9f
        if (w * cssScale < cw * 0.35f) {
            w *= cssScale; h *= cssScale
        } else {
            val s = cw / w
            w *= s; h *= s
        }
        val maxH = ch * 0.98f
        if (h > maxH) {
            val s = maxH / h
            w *= s; h *= s
        }
        val gap = if (b.y > 0) em * 0.6f else 0f
        if (b.y + gap + h > ch) b.newPage() else b.y += gap
        val left = b.left + (cw - w) / 2
        b.add(ImageItem(RectF(left, content.top + b.y, left + w, content.top + b.y + h), bi, block.path))
        b.y += h + em * 0.4f
    }

    private fun layoutText(b: Builder, bi: Int, block: TextBlock, fromIn: Int, to: Int) {
        var from = fromIn
        if (from > 0) while (from < to && block.text[from] == ' ') from++
        if (from >= to) return
        val text = if (from == 0 && to == block.text.length) block.text else block.text.subSequence(from, to)

        val paint = when (block.kind) {
            TextBlock.HEADING -> headingPaint(block.level)
            TextBlock.PRE -> pre
            TextBlock.CAPTION -> caption
            else -> body
        }
        val indentUnit = em * 1.5f
        val leftIndent = when (block.kind) {
            TextBlock.QUOTE, TextBlock.LIST -> block.indent * indentUnit
            else -> block.indent * indentUnit * 0.6f
        }.coerceAtMost(cw * 0.4f)
        val rightIndent = if (block.kind == TextBlock.QUOTE) em else 0f
        val width = (cw - leftIndent - rightIndent).toInt().coerceAtLeast(40)

        val rtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, 0, text.length)
        val alignment = when (block.align) {
            TextBlock.ALIGN_CENTER -> Layout.Alignment.ALIGN_CENTER
            TextBlock.ALIGN_RIGHT -> if (rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
            TextBlock.ALIGN_LEFT -> if (rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            else -> if (block.kind == TextBlock.HEADING && block.level <= 2) Layout.Alignment.ALIGN_CENTER
            else if (block.kind == TextBlock.CAPTION) Layout.Alignment.ALIGN_CENTER
            else Layout.Alignment.ALIGN_NORMAL
        }
        val justify = alignment == Layout.Alignment.ALIGN_NORMAL && block.kind != TextBlock.PRE &&
            block.kind != TextBlock.HEADING && (block.align == TextBlock.ALIGN_JUSTIFY || (spec.justify && block.align == TextBlock.ALIGN_AUTO))

        val mult = if (block.kind == TextBlock.HEADING) 1.1f else bodyMult
        val hyphenate = block.kind == TextBlock.BODY || block.kind == TextBlock.QUOTE
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setLineSpacing(0f, mult)
            .setIncludePad(false)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            // More hyphenation points when justifying, so word gaps don't get squeezed.
            .setHyphenationFrequency(
                when {
                    !hyphenate -> Layout.HYPHENATION_FREQUENCY_NONE
                    justify -> Layout.HYPHENATION_FREQUENCY_FULL
                    else -> Layout.HYPHENATION_FREQUENCY_NORMAL
                }
            )
        if (justify) builder.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD)

        val firstIndent = spec.indent && from == 0 && block.kind == TextBlock.BODY &&
            b.lastKind == TextBlock.BODY && alignment == Layout.Alignment.ALIGN_NORMAL
        val hang = if (block.prefixLen > 0 && from == 0) paint.measureText(text, 0, min(block.prefixLen, text.length)).toInt() else 0
        if (firstIndent || hang > 0) {
            val ind = if (firstIndent) intArrayOf((em * 1.4f).toInt(), 0) else intArrayOf(0, hang)
            if (rtl) builder.setIndents(null, ind) else builder.setIndents(ind, null)
        }
        val layout = builder.build()

        val gapBefore = when (block.kind) {
            TextBlock.HEADING -> em * 1.4f
            TextBlock.LIST -> em * 0.25f
            TextBlock.QUOTE, TextBlock.PRE, TextBlock.CAPTION -> em * 0.5f
            else -> if (spec.indent && b.lastKind == TextBlock.BODY) 0f else em * 0.6f
        }
        if (b.y > 0 && from == 0) b.y += gapBefore
        if (b.y >= ch) b.newPage()

        // Keep headings together with the start of the next paragraph.
        if (block.kind == TextBlock.HEADING && b.y > 0 && b.y + layout.height + em * 3.5f > ch) b.newPage()

        val lineCount = layout.lineCount
        var line = 0
        while (line < lineCount) {
            val top = layout.getLineTop(line)
            val avail = ch - b.y
            var end = line
            while (end < lineCount && layout.getLineBottom(end) - top <= avail + 0.5f) end++
            if (end == line) {
                if (b.colItems == 0) end = line + 1 else {
                    b.newPage(); continue
                }
            }
            // Avoid a lone first line at the bottom of a page.
            if (line == 0 && end == 1 && lineCount > 2 && b.colItems > 0 && block.kind == TextBlock.BODY) {
                b.newPage(); continue
            }
            // Avoid a lone last line at the top of the next page.
            if (end == lineCount - 1 && end - line > 2) end--
            val h = layout.getLineBottom(end - 1) - top
            val x = b.left + leftIndent
            b.add(TextItem(RectF(x, content.top + b.y, x + width, content.top + b.y + h), bi, from, layout, line, end))
            b.y += h
            line = end
            if (line < lineCount) b.newPage()
        }
        // StaticLayout leaves the line spacing off a paragraph's last line, which made the gap
        // between paragraphs smaller than the gap between lines. Add it back.
        if (block.kind != TextBlock.HEADING && lineCount > 0) {
            val last = lineCount - 1
            b.y += (layout.getLineBottom(last) - layout.getLineTop(last)) * (mult - 1f)
        }
        if (block.kind == TextBlock.HEADING) b.y += em * 0.6f
        if (block.kind == TextBlock.QUOTE || block.kind == TextBlock.PRE) b.y += em * 0.4f
    }
}
