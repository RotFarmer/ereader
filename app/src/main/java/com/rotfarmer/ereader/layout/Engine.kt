package com.rotfarmer.ereader.layout

import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import com.rotfarmer.ereader.data.Locator
import com.rotfarmer.ereader.data.WritingSpace

data class PagePos(val ch: Int, val idx: Int)

/** Everything that affects how pages are laid out. */
class LayoutSpec(
    val width: Int,
    val height: Int,
    val density: Float,
    val typeface: Typeface,
    val textSizePx: Float,
    val lineSpacing: Float,
    /** Where text and pictures go; header and footer live outside it. */
    val content: RectF,
    val justify: Boolean,
    val indent: Boolean,
    val textColor: Int,
)

class Page(val chapter: Int, val index: Int) {
    val items = ArrayList<PageItem>(8)
    var start = Locator(0, 0)
    var end = Locator(0, 0)
}

sealed class PageItem(val rect: RectF)

class TextItem(
    rect: RectF,
    val block: Int,
    /** Offset in the block where this layout's text begins (blocks split by writing spaces). */
    val pieceStart: Int,
    val layout: StaticLayout,
    val lineStart: Int,
    val lineEnd: Int,
) : PageItem(rect) {
    val absStart: Int get() = pieceStart + layout.getLineStart(lineStart)
    val absEnd: Int get() = pieceStart + layout.getLineEnd(lineEnd - 1)
    val pieceEnd: Int get() = pieceStart + layout.text.length

    /** Top of [line] in page coordinates. */
    fun lineTop(line: Int): Float = rect.top + layout.getLineTop(line) - layout.getLineTop(lineStart)
    fun lineBottom(line: Int): Float = rect.top + layout.getLineBottom(line) - layout.getLineTop(lineStart)

    fun lineAt(y: Float): Int {
        val local = (y - rect.top + layout.getLineTop(lineStart)).toInt()
        return layout.getLineForVertical(local).coerceIn(lineStart, lineEnd - 1)
    }

    /** Character offset (absolute in the block) nearest to a page point. */
    fun offsetAt(x: Float, y: Float): Int {
        val line = lineAt(y)
        return pieceStart + layout.getOffsetForHorizontal(line, x - rect.left)
    }

    fun contains(absOffset: Int): Boolean = absOffset in absStart until absEnd ||
        (absOffset == absEnd && lineEnd == layout.lineCount)
}

class ImageItem(rect: RectF, val block: Int, val path: String) : PageItem(rect)

class RuleItem(rect: RectF, val block: Int) : PageItem(rect)

class SpaceItem(rect: RectF, val space: WritingSpace, val part: Int) : PageItem(rect)

/** A slice [y0, y1] of a PDF page; [pageRect] is where the whole page would be on screen. */
class PdfSliceItem(rect: RectF, val page: Int, val y0: Float, val y1: Float, val pageRect: RectF) : PageItem(rect) {
    val isWhole: Boolean get() = y0 <= 0f && y1 >= 1f
}

interface Engine {
    val isFixed: Boolean
    val chapterCount: Int
    fun pageCount(ch: Int): Int
    fun page(ch: Int, idx: Int): Page
    fun position(loc: Locator): PagePos
    /** -1 while pagination is still running. */
    fun totalPages(): Int
    fun globalIndex(pos: PagePos): Int
    fun posForGlobal(g: Int): PagePos?
    fun setSpaces(spaces: List<WritingSpace>, changedBlock: Int)
    fun startBackground(fromChapter: Int, onReady: () -> Unit)
    fun cancel()

    fun next(pos: PagePos): PagePos? = when {
        pos.idx + 1 < pageCount(pos.ch) -> PagePos(pos.ch, pos.idx + 1)
        pos.ch + 1 < chapterCount -> PagePos(pos.ch + 1, 0)
        else -> null
    }

    fun prev(pos: PagePos): PagePos? = when {
        pos.idx > 0 -> PagePos(pos.ch, pos.idx - 1)
        pos.ch > 0 -> PagePos(pos.ch - 1, pageCount(pos.ch - 1) - 1)
        else -> null
    }
}
