package com.rotfarmer.ereader.doc

import com.rotfarmer.ereader.data.Locator
import java.io.Closeable
import java.io.InputStream

sealed class Block

class TextBlock(
    val text: CharSequence,
    val kind: Int = BODY,
    /** Heading level 1..6. */
    val level: Int = 0,
    /** Nesting depth for quotes and lists. */
    val indent: Int = 0,
    val align: Int = ALIGN_AUTO,
    /** Length of a list marker ("• ", "3. ") at the start, used for a hanging indent. */
    val prefixLen: Int = 0,
) : Block() {
    companion object {
        const val BODY = 0
        const val HEADING = 1
        const val QUOTE = 2
        const val LIST = 3
        const val PRE = 4
        const val CAPTION = 5

        const val ALIGN_AUTO = 0
        const val ALIGN_LEFT = 1
        const val ALIGN_CENTER = 2
        const val ALIGN_RIGHT = 3
        const val ALIGN_JUSTIFY = 4
    }
}

class ImageBlock(val path: String, val alt: String?) : Block()

class RuleBlock : Block()

/** Blocks [start, end) that begin on a new page. */
class Chapter(val start: Int, val end: Int)

class TocEntry(val title: String, val level: Int, val loc: Locator)

interface ResourceSource : Closeable {
    fun open(path: String): InputStream?
}

/** An internal link inside the text, to an anchor resolved by the document. */
class LinkSpan(val target: String)

/** A reflowable document: EPUB, Word, text or HTML, turned into a list of blocks. */
class FlowDocument(
    val title: String,
    val author: String,
    val blocks: List<Block>,
    val chapters: List<Chapter>,
    val toc: List<TocEntry>,
    val res: ResourceSource,
    private val anchors: Map<String, Locator> = emptyMap(),
) : Closeable {

    /** charStart[b] = characters before block b, for progress and reading-time estimates. */
    private val charStart = IntArray(blocks.size + 1).also { a ->
        for (i in blocks.indices) a[i + 1] = a[i] + blockWeight(blocks[i])
    }

    val totalChars: Int get() = charStart[blocks.size]

    private fun blockWeight(b: Block) = when (b) {
        is TextBlock -> b.text.length
        is ImageBlock -> 300
        is RuleBlock -> 10
    }

    fun charPos(loc: Locator): Int {
        val b = loc.block.coerceIn(0, blocks.size)
        if (b == blocks.size) return totalChars
        return charStart[b] + loc.offset.coerceIn(0, blockWeight(blocks[b]))
    }

    fun blockLength(b: Int): Int = (blocks.getOrNull(b) as? TextBlock)?.text?.length ?: 0

    fun chapterOf(block: Int): Int {
        var lo = 0
        var hi = chapters.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (chapters[mid].start <= block) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun chapterChars(ch: Int): Pair<Int, Int> {
        val c = chapters[ch]
        return charStart[c.start] to charStart[c.end]
    }

    fun resolveLink(target: String): Locator? = anchors[target] ?: anchors[target.substringBefore('#')]

    /** The innermost table-of-contents entry at or before [loc]. */
    fun sectionTitle(loc: Locator): String? {
        var best: TocEntry? = null
        for (e in toc) if (e.loc <= loc && (best == null || e.loc >= best.loc)) best = e
        return best?.title
    }

    fun textBetween(a: Locator, b: Locator): String {
        val sb = StringBuilder()
        for (bi in a.block..b.block) {
            val t = (blocks.getOrNull(bi) as? TextBlock)?.text ?: continue
            val s = if (bi == a.block) a.offset.coerceIn(0, t.length) else 0
            val e = if (bi == b.block) b.offset.coerceIn(0, t.length) else t.length
            if (e > s) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(t, s, e)
            }
        }
        return sb.toString().trim()
    }

    override fun close() = res.close()
}

/** Collects blocks while a parser walks a document. */
class DocBuilder {
    val blocks = ArrayList<Block>()
    val anchors = HashMap<String, Locator>()
    val toc = ArrayList<TocEntry>()
    private val chapterStarts = ArrayList<Int>()

    fun startChapter() {
        if (chapterStarts.lastOrNull() != blocks.size) chapterStarts.add(blocks.size)
    }

    fun build(title: String, author: String, res: ResourceSource): FlowDocument {
        if (blocks.isEmpty()) blocks.add(TextBlock("This document appears to be empty.", align = TextBlock.ALIGN_CENTER))
        val starts = (listOf(0) + chapterStarts).filter { it < blocks.size }.distinct().sorted()
        val chapters = starts.mapIndexed { i, s -> Chapter(s, if (i + 1 < starts.size) starts[i + 1] else blocks.size) }
        return FlowDocument(title, author, blocks, chapters, toc, res, anchors)
    }
}
