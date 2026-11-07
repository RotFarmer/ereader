package com.rotfarmer.ereader.doc

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.rotfarmer.ereader.render.EmphasisSpan
import com.rotfarmer.ereader.data.Locator
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** A tiny CSS reader: only simple `tag`, `.class` and `tag.class` rules, which covers most e-books. */
class CssLite {
    private val rules = HashMap<String, HashMap<String, String>>()

    fun parse(css: String) {
        val clean = COMMENT.replace(css, " ")
        for (chunk in clean.split('}')) {
            val i = chunk.lastIndexOf('{')
            if (i < 0) continue
            val selectors = chunk.substring(0, i).trim()
            val decls = parseDecls(chunk.substring(i + 1))
            if (decls.isEmpty()) continue
            for (sel in selectors.split(',')) {
                val k = sel.trim().lowercase()
                if (SIMPLE.matches(k)) rules.getOrPut(k) { HashMap() }.putAll(decls)
            }
        }
    }

    fun propsFor(tag: String, classes: Set<String>, into: HashMap<String, String>) {
        rules[tag]?.let { into.putAll(it) }
        for (c in classes) {
            val lc = c.lowercase()
            rules[".$lc"]?.let { into.putAll(it) }
            rules["$tag.$lc"]?.let { into.putAll(it) }
        }
    }

    companion object {
        private val COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val SIMPLE = Regex("^[a-z0-9]*(\\.[a-z0-9_-]+)?$")

        fun parseDecls(body: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (d in body.split(';')) {
                val c = d.indexOf(':')
                if (c <= 0) continue
                val k = d.substring(0, c).trim().lowercase()
                val v = d.substring(c + 1).replace("!important", "").trim().lowercase()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
            return out
        }
    }
}

/** Turns an (X)HTML body into text blocks with bold/italic/etc. spans. */
class HtmlConverter(
    private val out: DocBuilder,
    private val fileKey: String,
    private val dir: String,
    private val css: CssLite?,
) {
    private class Ctx(val kind: Int, val level: Int, val indent: Int, val align: Int, val pre: Boolean)

    private val sb = SpannableStringBuilder()
    private var generation = 0
    private var pendingPrefix = 0
    /** A list marker waiting for the first paragraph inside its list item. */
    private var carry: String? = null
    private val lists = ArrayList<IntArray>() // [ordered 0/1, counter]

    fun convert(root: Element) {
        val base = Ctx(TextBlock.BODY, 0, 0, TextBlock.ALIGN_AUTO, false)
        walk(root, base)
        flush(base)
    }

    private fun walk(node: Node, ctx: Ctx) {
        when (node) {
            is TextNode -> text(node.wholeText, ctx.pre)
            is Element -> element(node, ctx)
            else -> {}
        }
    }

    private fun element(el: Element, ctx: Ctx) {
        val tag = el.normalName().substringAfterLast(':')
        if (tag in SKIP) return
        val props = HashMap<String, String>()
        css?.propsFor(tag, el.classNames(), props)
        val inline = el.attr("style")
        if (inline.isNotEmpty()) props.putAll(CssLite.parseDecls(inline))
        // Old HTML / Mobipocket: <p align="center">.
        val alignAttr = el.attr("align")
        if (alignAttr.isNotEmpty() && "text-align" !in props) props["text-align"] = alignAttr.lowercase()
        if (props["display"] == "none" || props["visibility"] == "hidden") return

        when (tag) {
            "br" -> {
                sb.append('\n'); return
            }
            "img", "image" -> {
                image(el, ctx); return
            }
            "hr" -> {
                flush(ctx); out.blocks.add(RuleBlock()); return
            }
            "pagebreak" -> { // <mbp:pagebreak/> in MOBI books
                flush(ctx); out.startChapter(); return
            }
        }

        val isBlock = tag in BLOCK_TAGS
        var c = ctx
        if (isBlock) {
            flush(ctx)
            c = blockCtx(tag, ctx, props)
        }
        anchor(el)
        val startGen = generation
        val start = sb.length

        if (tag == "li") {
            carry = null
            val top = lists.lastOrNull()
            val prefix = if (top != null && top[0] == 1) {
                top[1]++; "${top[1]}. "
            } else "• "
            sb.append(prefix)
            pendingPrefix = prefix.length
        }
        if (tag == "ul" || tag == "ol") {
            val startAt = el.attr("start").toIntOrNull()?.minus(1) ?: 0
            lists.add(intArrayOf(if (tag == "ol") 1 else 0, startAt))
        }

        for (child in el.childNodes()) walk(child, c)

        if (tag == "ul" || tag == "ol") lists.removeAt(lists.size - 1)
        val href = if (tag == "a") el.attr("href") else ""
        applySpans(tag, props, if (generation == startGen) start else 0, href)
        if (isBlock) flush(c) else if (tag == "td" || tag == "th") sb.append("   ")
    }

    private fun blockCtx(tag: String, ctx: Ctx, props: Map<String, String>): Ctx {
        var kind = ctx.kind
        var level = ctx.level
        var indent = ctx.indent
        var align = ctx.align
        var pre = ctx.pre
        when (tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                kind = TextBlock.HEADING; level = tag[1] - '0'
            }
            "blockquote" -> {
                if (kind != TextBlock.HEADING) kind = TextBlock.QUOTE
                indent++
            }
            "ul", "ol", "dd" -> indent++
            "li" -> kind = TextBlock.LIST
            "pre" -> {
                kind = TextBlock.PRE; pre = true
            }
            "figcaption", "caption" -> {
                kind = TextBlock.CAPTION; align = TextBlock.ALIGN_CENTER
            }
            "center" -> align = TextBlock.ALIGN_CENTER
        }
        when (props["text-align"]) {
            "center" -> align = TextBlock.ALIGN_CENTER
            "right", "end" -> align = TextBlock.ALIGN_RIGHT
            "left", "start" -> align = TextBlock.ALIGN_LEFT
            "justify" -> align = TextBlock.ALIGN_JUSTIFY
        }
        return Ctx(kind, level, indent.coerceAtMost(4), align, pre)
    }

    private fun anchor(el: Element) {
        val id = el.id().ifEmpty { if (el.normalName() == "a") el.attr("name") else "" }
        if (id.isNotEmpty()) out.anchors["$fileKey#$id"] = Locator(out.blocks.size, sb.length)
    }

    private fun image(el: Element, ctx: Ctx) {
        val src = el.attr("src").ifEmpty { el.attr("xlink:href") }.ifEmpty { el.attr("href") }
        if (src.isBlank() || src.startsWith("data:")) return
        anchor(el)
        flush(ctx)
        out.blocks.add(ImageBlock(resolvePath(dir, src), el.attr("alt").ifBlank { null }))
    }

    private fun text(raw: String, pre: Boolean) {
        if (pre) {
            applyCarry()
            sb.append(raw.replace("\r\n", "\n"))
            return
        }
        var s = WS.replace(raw, " ")
        if (s.isBlank() && sb.isEmpty()) return
        applyCarry()
        if (s.isEmpty()) return
        if (s[0] == ' ' && (sb.isEmpty() || sb[sb.length - 1] == ' ' || sb[sb.length - 1] == '\n')) s = s.substring(1)
        if (s.isNotEmpty()) sb.append(s)
    }

    private fun applySpans(tag: String, props: Map<String, String>, start: Int, href: String) {
        val end = sb.length
        if (end <= start) return
        fun span(o: Any) = sb.setSpan(o, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        when (tag) {
            "b", "strong", "dt" -> span(EmphasisSpan(Typeface.BOLD))
            "i", "em", "cite", "dfn", "var" -> span(EmphasisSpan(Typeface.ITALIC))
            "u", "ins" -> span(UnderlineSpan())
            "s", "strike", "del" -> span(StrikethroughSpan())
            "sup" -> {
                span(SuperscriptSpan()); span(RelativeSizeSpan(0.7f))
            }
            "sub" -> {
                span(SubscriptSpan()); span(RelativeSizeSpan(0.7f))
            }
            "small" -> span(RelativeSizeSpan(0.85f))
            "big" -> span(RelativeSizeSpan(1.2f))
            "code", "tt", "kbd", "samp" -> span(TypefaceSpan("monospace"))
        }
        if (href.isNotEmpty() && !href.contains("://") && !href.startsWith("mailto:")) {
            val target = if (href.startsWith("#")) fileKey + href else {
                val path = resolvePath(dir, href.substringBefore('#'))
                if (href.contains('#')) "$path#${href.substringAfter('#')}" else path
            }
            span(LinkSpan(target))
        }
        if (props.isEmpty()) return
        when (props["font-style"]) {
            "italic", "oblique" -> span(EmphasisSpan(Typeface.ITALIC))
        }
        when (props["font-weight"]) {
            "bold", "bolder", "600", "700", "800", "900" -> span(EmphasisSpan(Typeface.BOLD))
        }
        val deco = props["text-decoration"] ?: props["text-decoration-line"]
        if (deco != null) {
            if (deco.contains("underline")) span(UnderlineSpan())
            if (deco.contains("line-through")) span(StrikethroughSpan())
        }
        when (props["vertical-align"]) {
            "super" -> {
                span(SuperscriptSpan()); span(RelativeSizeSpan(0.7f))
            }
            "sub" -> {
                span(SubscriptSpan()); span(RelativeSizeSpan(0.7f))
            }
        }
        if (tag.length != 2 || tag[0] != 'h') {
            fontSize(props["font-size"])?.let { if (it < 0.95f || it > 1.05f) span(RelativeSizeSpan(it)) }
        }
    }

    private fun fontSize(v: String?): Float? {
        v ?: return null
        val f = when {
            v.endsWith("em") -> v.removeSuffix("em").trim().toFloatOrNull()
            v.endsWith("%") -> v.removeSuffix("%").trim().toFloatOrNull()?.div(100f)
            v == "small" -> 0.85f
            v == "x-small" -> 0.75f
            v == "smaller" -> 0.85f
            v == "large" -> 1.15f
            v == "x-large" -> 1.35f
            v == "xx-large" -> 1.6f
            v == "larger" -> 1.15f
            else -> null
        }
        return f?.coerceIn(0.6f, 2.4f)
    }

    private fun applyCarry() {
        val c = carry ?: return
        if (sb.isNotEmpty()) return
        sb.append(c)
        pendingPrefix = c.length
        carry = null
    }

    private fun flush(ctx: Ctx) {
        // "<li><p>text</p></li>": keep the bullet for the paragraph instead of a line of its own.
        if (pendingPrefix > 0 && sb.length >= pendingPrefix && sb.substring(pendingPrefix).isBlank()) {
            carry = sb.substring(0, pendingPrefix)
            sb.clearSpans()
            sb.clear()
            generation++
            pendingPrefix = 0
            return
        }
        var end = sb.length
        while (end > 0 && sb[end - 1].isWhitespace()) end--
        if (end < sb.length) sb.delete(end, sb.length)
        var st = 0
        while (st < sb.length && (sb[st] == ' ' || sb[st] == '\n')) st++
        if (st > 0) sb.delete(0, st)
        if (sb.isNotEmpty() && sb.any { !it.isWhitespace() }) {
            out.blocks.add(
                TextBlock(SpannedString(sb), ctx.kind, ctx.level, ctx.indent, ctx.align, pendingPrefix.coerceAtMost(sb.length))
            )
        }
        sb.clearSpans()
        sb.clear()
        generation++
        pendingPrefix = 0
    }

    companion object {
        private val WS = Regex("[ \\t\\n\\r\\u000C]+")
        private val SKIP = setOf("head", "script", "style", "title", "meta", "link", "noscript", "rt", "rp", "template", "button", "input", "select", "math")
        private val BLOCK_TAGS = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "blockquote", "pre",
            "section", "article", "header", "footer", "aside", "nav", "figure", "figcaption", "table",
            "tr", "thead", "tbody", "tfoot", "dl", "dt", "dd", "center", "address", "main", "body",
            "html", "hgroup", "caption", "details", "summary",
        )

        /** Resolves an href relative to a directory inside the archive. */
        fun resolvePath(dir: String, href: String): String {
            val decoded = try {
                java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                href
            }
            val parts = ArrayList<String>()
            if (!decoded.startsWith("/") && dir.isNotEmpty()) parts.addAll(dir.split('/').filter { it.isNotEmpty() })
            for (p in decoded.split('/')) {
                when (p) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(p)
                }
            }
            return parts.joinToString("/")
        }
    }
}
