package com.rotfarmer.ereader.doc

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import com.rotfarmer.ereader.render.EmphasisSpan
import android.util.Xml
import com.rotfarmer.ereader.data.Locator
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream

/** Reads Word .docx files: paragraphs, headings, bold/italic/underline, lists, page breaks and pictures. */
object DocxParser {

    private class Style(val name: String, val basedOn: String?, val outline: Int?)
    private class Level(val fmt: String, val text: String)

    fun readMeta(file: File): Pair<String?, String?> = try {
        ZipSource(file).use { src ->
            val core = src.xml("docProps/core.xml")
            val title = core?.allElements?.firstOrNull { it.local() == "title" }?.text()?.trim()?.ifEmpty { null }
            val author = core?.allElements?.firstOrNull { it.local() == "creator" }?.text()?.trim()?.ifEmpty { null }
            title to author
        }
    } catch (e: Exception) {
        null to null
    }

    private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(input, null)
    }

    private fun XmlPullParser.attr(local: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i) == local) return getAttributeValue(i)
        return null
    }

    private fun XmlPullParser.relId(local: String): String? {
        for (i in 0 until attributeCount) {
            if (getAttributeName(i) == local && (getAttributeNamespace(i) ?: "").contains("relationships")) return getAttributeValue(i)
        }
        return null
    }

    private fun isOn(v: String?) = v == null || !(v == "0" || v == "false" || v == "off" || v == "none")

    private fun rels(src: ZipSource): Map<String, String> {
        val out = HashMap<String, String>()
        val input = src.open("word/_rels/document.xml.rels") ?: return out
        input.use {
            val p = parser(it)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    if (p.attr("TargetMode") == "External") continue
                    val id = p.attr("Id") ?: continue
                    val target = p.attr("Target") ?: continue
                    out[id] = if (target.startsWith("/")) target.trimStart('/') else HtmlConverter.resolvePath("word", target)
                }
            }
        }
        return out
    }

    private fun styles(src: ZipSource): Map<String, Style> {
        val out = HashMap<String, Style>()
        val input = src.open("word/styles.xml") ?: return out
        input.use {
            val p = parser(it)
            var id: String? = null
            var name = ""
            var based: String? = null
            var outline: Int? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "style" -> {
                            id = p.attr("styleId"); name = ""; based = null; outline = null
                        }
                        "name" -> if (id != null) name = p.attr("val") ?: ""
                        "basedOn" -> if (id != null) based = p.attr("val")
                        "outlineLvl" -> if (id != null) outline = p.attr("val")?.toIntOrNull()
                    }
                } else if (p.eventType == XmlPullParser.END_TAG && p.name == "style") {
                    id?.let { out[it] = Style(name.lowercase(), based, outline) }
                    id = null
                }
            }
        }
        return out
    }

    private fun numbering(src: ZipSource): Map<String, Map<Int, Level>> {
        val abstracts = HashMap<String, HashMap<Int, Level>>()
        val nums = HashMap<String, String>()
        val input = src.open("word/numbering.xml") ?: return emptyMap()
        input.use {
            val p = parser(it)
            var abs: String? = null
            var lvl = -1
            var fmt = "bullet"
            var text = ""
            var num: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "abstractNum" -> abs = p.attr("abstractNumId")
                        "lvl" -> {
                            lvl = p.attr("ilvl")?.toIntOrNull() ?: 0; fmt = "bullet"; text = ""
                        }
                        "numFmt" -> if (lvl >= 0) fmt = p.attr("val") ?: "bullet"
                        "lvlText" -> if (lvl >= 0) text = p.attr("val") ?: ""
                        "num" -> num = p.attr("numId")
                        "abstractNumId" -> if (num != null) p.attr("val")?.let { v -> nums[num!!] = v }
                    }
                } else if (p.eventType == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "lvl" -> {
                            abs?.let { a -> abstracts.getOrPut(a) { HashMap() }[lvl] = Level(fmt, text) }
                            lvl = -1
                        }
                        "abstractNum" -> abs = null
                        "num" -> num = null
                    }
                }
            }
        }
        return nums.mapValues { (_, a) -> abstracts[a] ?: emptyMap() }
    }

    private fun headingLevel(styles: Map<String, Style>, id: String?, depth: Int = 0): Int {
        if (id == null || depth > 8) return 0
        val s = styles[id] ?: return HEADING_ID.find(id.lowercase())?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 6) ?: 0
        HEADING_NAME.find(s.name)?.let { return it.groupValues[1].toInt().coerceIn(1, 6) }
        if (s.name == "title") return 1
        if (s.name == "subtitle") return 2
        if (s.outline != null && s.outline in 0..5) return s.outline + 1
        return headingLevel(styles, s.basedOn, depth + 1)
    }

    private val HEADING_NAME = Regex("^heading\\s*(\\d)")
    private val HEADING_ID = Regex("^heading(\\d)$")

    private fun roman(n: Int): String {
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        var x = n
        val sb = StringBuilder()
        for (i in vals.indices) while (x >= vals[i]) {
            sb.append(syms[i]); x -= vals[i]
        }
        return sb.toString()
    }

    private fun letters(n: Int): String {
        var x = n
        val sb = StringBuilder()
        while (x > 0) {
            x--; sb.insert(0, 'a' + x % 26); x /= 26
        }
        return sb.toString()
    }

    fun parse(file: File, fallbackTitle: String): FlowDocument {
        val src = ZipSource(file)
        try {
            val (metaTitle, metaAuthor) = readMeta(file)
            val rels = rels(src)
            val styles = styles(src)
            val numbering = numbering(src)
            val counters = HashMap<String, IntArray>()
            val out = DocBuilder()
            val input = src.open("word/document.xml") ?: throw IllegalArgumentException("Not a Word document")

            val sb = SpannableStringBuilder()
            var pStyle: String? = null
            var jc: String? = null
            var numId: String? = null
            var ilvl = 0
            var inPPr = false
            var inRPr = false
            var inT = false
            var runStart = 0
            var bold = false
            var italic = false
            var under = false
            var strike = false
            var vert: String? = null
            val images = ArrayList<String>()
            var breakNext = false

            fun emitParagraph() {
                var end = sb.length
                while (end > 0 && sb[end - 1].isWhitespace()) end--
                if (end < sb.length) sb.delete(end, sb.length)
                val level = headingLevel(styles, pStyle)
                if (sb.isNotEmpty() && sb.any { !it.isWhitespace() }) {
                    if (breakNext) {
                        out.startChapter(); breakNext = false
                    }
                    var prefixLen = 0
                    var kind = if (level > 0) TextBlock.HEADING else TextBlock.BODY
                    var indent = 0
                    val nid = numId
                    if (nid != null && nid != "0" && level == 0) {
                        val lv = numbering[nid]?.get(ilvl)
                        val c = counters.getOrPut(nid) { IntArray(9) }
                        val li = ilvl.coerceIn(0, 8)
                        c[li]++
                        for (k in li + 1 until 9) c[k] = 0
                        val n = c[li]
                        val marker = when (lv?.fmt) {
                            "decimal", "decimalZero" -> (lv.text.ifEmpty { "%1." }).replace(Regex("%\\d"), n.toString())
                            "lowerLetter" -> "${letters(n)}."
                            "upperLetter" -> "${letters(n).uppercase()}."
                            "lowerRoman" -> "${roman(n)}."
                            "upperRoman" -> "${roman(n).uppercase()}."
                            "none" -> ""
                            else -> "•"
                        }
                        if (marker.isNotEmpty()) {
                            val prefix = "$marker "
                            sb.insert(0, prefix)
                            prefixLen = prefix.length
                        }
                        kind = TextBlock.LIST
                        indent = (ilvl + 1).coerceAtMost(4)
                    }
                    val align = when (jc) {
                        "center" -> TextBlock.ALIGN_CENTER
                        "right", "end" -> TextBlock.ALIGN_RIGHT
                        "both", "distribute" -> TextBlock.ALIGN_JUSTIFY
                        "left", "start" -> TextBlock.ALIGN_LEFT
                        else -> TextBlock.ALIGN_AUTO
                    }
                    if (level > 0 && level <= 3) {
                        out.toc += TocEntry(sb.toString().replace('\n', ' ').trim().take(80), level, Locator(out.blocks.size, 0))
                    }
                    out.blocks += TextBlock(SpannedString(sb), kind, level, indent, align, prefixLen)
                }
                for (img in images) {
                    if (breakNext) {
                        out.startChapter(); breakNext = false
                    }
                    out.blocks += ImageBlock(img, null)
                }
                images.clear()
                sb.clearSpans()
                sb.clear()
            }

            input.use {
                val p = parser(it)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "p" -> {
                                sb.clearSpans(); sb.clear(); pStyle = null; jc = null; numId = null; ilvl = 0
                            }
                            "pPr" -> inPPr = true
                            "rPr" -> inRPr = true
                            "pStyle" -> if (inPPr) pStyle = p.attr("val")
                            "jc" -> if (inPPr) jc = p.attr("val")
                            "numId" -> if (inPPr) numId = p.attr("val")
                            "ilvl" -> if (inPPr) ilvl = p.attr("val")?.toIntOrNull() ?: 0
                            "pageBreakBefore" -> if (inPPr && isOn(p.attr("val"))) breakNext = true
                            "r" -> {
                                runStart = sb.length; bold = false; italic = false; under = false; strike = false; vert = null
                            }
                            "b" -> if (inRPr && !inPPr) bold = isOn(p.attr("val"))
                            "i" -> if (inRPr && !inPPr) italic = isOn(p.attr("val"))
                            "u" -> if (inRPr && !inPPr) under = isOn(p.attr("val"))
                            "strike", "dstrike" -> if (inRPr && !inPPr) strike = isOn(p.attr("val"))
                            "vertAlign" -> if (inRPr && !inPPr) vert = p.attr("val")
                            "t" -> inT = true
                            "tab" -> if (!inPPr) sb.append('\t')
                            "br" -> {
                                if (p.attr("type") == "page") {
                                    if (sb.isNotEmpty()) {
                                        val keepStyle = pStyle
                                        emitParagraph()
                                        pStyle = keepStyle
                                    }
                                    breakNext = true
                                } else sb.append('\n')
                            }
                            "cr" -> sb.append('\n')
                            "noBreakHyphen" -> sb.append('-')
                            "softHyphen" -> sb.append('­')
                            "blip" -> p.relId("embed")?.let { id -> rels[id]?.let { images += it } }
                            "imagedata" -> p.relId("id")?.let { id -> rels[id]?.let { images += it } }
                        }
                        XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                        XmlPullParser.END_TAG -> when (p.name) {
                            "t" -> inT = false
                            "pPr" -> inPPr = false
                            "rPr" -> inRPr = false
                            "r" -> {
                                val end = sb.length
                                if (end > runStart) {
                                    fun span(o: Any) = sb.setSpan(o, runStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    if (bold && italic) span(EmphasisSpan(Typeface.BOLD_ITALIC))
                                    else if (bold) span(EmphasisSpan(Typeface.BOLD))
                                    else if (italic) span(EmphasisSpan(Typeface.ITALIC))
                                    if (under) span(UnderlineSpan())
                                    if (strike) span(StrikethroughSpan())
                                    if (vert == "superscript") {
                                        span(SuperscriptSpan()); span(RelativeSizeSpan(0.7f))
                                    } else if (vert == "subscript") {
                                        span(SubscriptSpan()); span(RelativeSizeSpan(0.7f))
                                    }
                                }
                            }
                            "p" -> emitParagraph()
                        }
                    }
                }
            }
            val title = metaTitle ?: fallbackTitle
            return out.build(title, metaAuthor ?: "", src)
        } catch (e: Throwable) {
            src.close()
            throw e
        }
    }
}
