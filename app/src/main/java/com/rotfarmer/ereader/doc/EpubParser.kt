package com.rotfarmer.ereader.doc

import com.rotfarmer.ereader.data.Locator
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Reads files from a zip archive, forgiving differences in case and URL-encoding. */
class ZipSource(file: File) : ResourceSource {
    val zip = ZipFile(file)
    private val names: Map<String, String> = buildMap {
        val e = zip.entries()
        while (e.hasMoreElements()) {
            val n = e.nextElement().name
            put(n.lowercase(), n)
        }
    }

    fun has(path: String) = find(path) != null

    private fun find(path: String): String? {
        val p = path.trimStart('/')
        if (zip.getEntry(p) != null) return p
        return names[p.lowercase()]
    }

    override fun open(path: String): InputStream? {
        val name = find(path) ?: return null
        return zip.getInputStream(zip.getEntry(name))
    }

    fun bytes(path: String): ByteArray? = open(path)?.use { it.readBytes() }

    fun xml(path: String): Document? = bytes(path)?.let {
        Jsoup.parse(ByteArrayInputStream(it), null, "", Parser.xmlParser())
    }

    override fun close() = zip.close()
}

internal fun Element.local(): String = tagName().substringAfterLast(':').lowercase()

object EpubParser {

    class Meta(val title: String?, val author: String?, val coverPath: String?)

    private class Opf(
        val path: String,
        val dir: String,
        val doc: Document,
        val items: Map<String, Element>,
    )

    private fun readOpf(src: ZipSource): Opf {
        val container = src.xml("META-INF/container.xml")
        var opfPath = container?.allElements?.firstOrNull { it.local() == "rootfile" }?.attr("full-path")
        if (opfPath.isNullOrEmpty()) {
            val e = src.zip.entries()
            while (e.hasMoreElements()) {
                val n = e.nextElement().name
                if (n.endsWith(".opf", true)) {
                    opfPath = n; break
                }
            }
        }
        val path = opfPath ?: throw IllegalArgumentException("Not a valid EPUB (no package file)")
        val doc = src.xml(path) ?: throw IllegalArgumentException("Not a valid EPUB (missing $path)")
        val items = doc.allElements.filter { it.local() == "item" }.associateBy { it.attr("id") }
        return Opf(path, path.substringBeforeLast('/', ""), doc, items)
    }

    fun readMeta(file: File): Meta = ZipSource(file).use { src ->
        val opf = readOpf(src)
        val title = opf.doc.allElements.firstOrNull { it.local() == "title" }?.text()?.trim()
        val author = opf.doc.allElements.firstOrNull { it.local() == "creator" }?.text()?.trim()
        Meta(title, author, coverPath(src, opf))
    }

    /** Path of the cover picture inside the book, if it declares one. */
    fun coverPathOf(file: File): String? = try {
        ZipSource(file).use { src -> coverPath(src, readOpf(src)) }
    } catch (e: Exception) {
        null
    }

    fun coverBytes(file: File): ByteArray? = try {
        ZipSource(file).use { src -> coverPath(src, readOpf(src))?.let { src.bytes(it) } }
    } catch (e: Exception) {
        null
    }

    private fun coverPath(src: ZipSource, opf: Opf): String? {
        val byProp = opf.items.values.firstOrNull { it.attr("properties").split(' ').contains("cover-image") }
        val metaId = opf.doc.allElements.firstOrNull { it.local() == "meta" && it.attr("name") == "cover" }?.attr("content")
        val byMeta = metaId?.let { opf.items[it] }?.takeIf { it.attr("media-type").startsWith("image/") }
        val byName = opf.items.values.firstOrNull {
            it.attr("media-type").startsWith("image/") && (it.attr("id").contains("cover", true) || it.attr("href").contains("cover", true))
        }
        val item = byProp ?: byMeta ?: byName ?: return null
        return HtmlConverter.resolvePath(opf.dir, item.attr("href")).takeIf { src.has(it) }
    }

    /** Converts one content document of the book into blocks. */
    private fun convertFile(src: ZipSource, path: String, bytes: ByteArray, type: String, out: DocBuilder, cssCache: HashMap<String, String>) {
        val isXml = type.contains("xhtml") || type.contains("xml") || path.endsWith(".xhtml", true)
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, "", if (isXml) Parser.xmlParser() else Parser.htmlParser())
        val dir = path.substringBeforeLast('/', "")
        val css = CssLite()
        for (el in doc.allElements) {
            when (el.local()) {
                "link" -> if (el.attr("rel").contains("stylesheet", true)) {
                    val cssPath = HtmlConverter.resolvePath(dir, el.attr("href"))
                    css.parse(cssCache.getOrPut(cssPath) { src.bytes(cssPath)?.let { String(it, Charsets.UTF_8) } ?: "" })
                }
                "style" -> css.parse(el.data())
            }
        }
        val body = doc.allElements.firstOrNull { it.local() == "body" } ?: doc
        HtmlConverter(out, path, dir, css).convert(body)
    }

    /**
     * Just the book's title page, for use as a thumbnail. Found from the book's own landmarks or
     * guide, else a file named like a title page, else the first early page that contains the title.
     */
    fun titlePage(file: File, title: String?): FlowDocument? {
        val src = ZipSource(file)
        try {
            val opf = readOpf(src)
            val spine = opf.doc.allElements.filter { it.local() == "itemref" }.mapNotNull { opf.items[it.attr("idref")] }
            fun pathOf(item: Element) = HtmlConverter.resolvePath(opf.dir, item.attr("href"))
            val coverPaths = HashSet<String>()
            opf.doc.allElements.filter { it.local() == "reference" && it.attr("type").equals("cover", true) }
                .forEach { coverPaths += HtmlConverter.resolvePath(opf.dir, it.attr("href").substringBefore('#')) }
            spine.filter { (it.attr("id") + " " + it.attr("href")).contains("cover", true) }.forEach { coverPaths += pathOf(it) }

            var target: String? = opf.doc.allElements.firstOrNull {
                it.local() == "reference" && it.attr("type").replace("-", "").equals("titlepage", true)
            }?.let { HtmlConverter.resolvePath(opf.dir, it.attr("href").substringBefore('#')) }

            if (target == null) {
                val navItem = opf.items.values.firstOrNull { it.attr("properties").split(' ').contains("nav") }
                if (navItem != null) {
                    val navPath = pathOf(navItem)
                    src.xml(navPath)?.allElements?.firstOrNull {
                        it.local() == "a" && it.attr("epub:type").split(' ').any { t -> t.equals("titlepage", true) }
                    }?.let { target = HtmlConverter.resolvePath(navPath.substringBeforeLast('/', ""), it.attr("href").substringBefore('#')) }
                }
            }
            if (target == null) {
                target = spine.firstOrNull {
                    val s = (it.attr("id") + " " + it.attr("href")).lowercase()
                    "title" in s && "cover" !in s
                }?.let { pathOf(it) }
            }
            if (target == null && !title.isNullOrBlank()) {
                val words = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.take(3)
                if (words.isNotEmpty()) {
                    for (item in spine.take(10)) {
                        val p = pathOf(item)
                        if (p in coverPaths) continue
                        val text = src.bytes(p)?.let { Jsoup.parse(String(it, Charsets.UTF_8)).text().lowercase().take(3000) } ?: continue
                        if (words.all { it in text }) {
                            target = p; break
                        }
                    }
                }
            }
            val path = target ?: run { src.close(); return null }
            val item = spine.firstOrNull { pathOf(it) == path }
            val bytes = src.bytes(path) ?: run { src.close(); return null }
            val out = DocBuilder()
            convertFile(src, path, bytes, item?.attr("media-type") ?: "application/xhtml+xml", out, HashMap())
            return out.build(title ?: "", "", src)
        } catch (e: Throwable) {
            src.close()
            return null
        }
    }

    fun parse(file: File, fallbackTitle: String): FlowDocument {
        val src = ZipSource(file)
        try {
            val opf = readOpf(src)
            val title = opf.doc.allElements.firstOrNull { it.local() == "title" }?.text()?.trim().orEmpty().ifEmpty { fallbackTitle }
            val author = opf.doc.allElements.firstOrNull { it.local() == "creator" }?.text()?.trim().orEmpty()
            val out = DocBuilder()
            val fileStarts = HashMap<String, Locator>()
            val cssCache = HashMap<String, String>()

            val spine = opf.doc.allElements.filter { it.local() == "itemref" }.mapNotNull { opf.items[it.attr("idref")] }
            for (item in spine) {
                val path = HtmlConverter.resolvePath(opf.dir, item.attr("href"))
                val bytes = src.bytes(path) ?: continue
                out.startChapter()
                fileStarts[path] = Locator(out.blocks.size, 0)
                out.anchors[path] = Locator(out.blocks.size, 0)
                convertFile(src, path, bytes, item.attr("media-type"), out, cssCache)
            }

            fun resolve(base: String, href: String): Locator? {
                val path = HtmlConverter.resolvePath(base, href.substringBefore('#'))
                val frag = href.substringAfter('#', "")
                return (if (frag.isNotEmpty()) out.anchors["$path#$frag"] else null) ?: fileStarts[path]
            }

            // Table of contents: EPUB 3 navigation document, else EPUB 2 NCX.
            val navItem = opf.items.values.firstOrNull { it.attr("properties").split(' ').contains("nav") }
            if (navItem != null) {
                val navPath = HtmlConverter.resolvePath(opf.dir, navItem.attr("href"))
                val navDir = navPath.substringBeforeLast('/', "")
                src.xml(navPath)?.let { nav ->
                    val navs = nav.allElements.filter { it.local() == "nav" }
                    val toc = navs.firstOrNull { it.attr("epub:type").contains("toc") } ?: navs.firstOrNull()
                    val ol = toc?.children()?.firstOrNull { it.local() == "ol" }
                    if (ol != null) readNavList(ol, 1) { t, lvl, href -> resolve(navDir, href)?.let { out.toc += TocEntry(t, lvl, it) } }
                }
            }
            if (out.toc.isEmpty()) {
                val ncxId = opf.doc.allElements.firstOrNull { it.local() == "spine" }?.attr("toc")
                val ncxItem = ncxId?.let { opf.items[it] } ?: opf.items.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
                if (ncxItem != null) {
                    val ncxPath = HtmlConverter.resolvePath(opf.dir, ncxItem.attr("href"))
                    val ncxDir = ncxPath.substringBeforeLast('/', "")
                    src.xml(ncxPath)?.let { ncx ->
                        val map = ncx.allElements.firstOrNull { it.local() == "navmap" }
                        if (map != null) readNcx(map, 1) { t, lvl, href -> resolve(ncxDir, href)?.let { out.toc += TocEntry(t, lvl, it) } }
                    }
                }
            }
            if (out.toc.isEmpty()) {
                // Fall back to headings.
                out.blocks.forEachIndexed { i, b ->
                    if (b is TextBlock && b.kind == TextBlock.HEADING && b.level <= 2) {
                        out.toc += TocEntry(b.text.toString().replace('\n', ' ').take(80), b.level, Locator(i, 0))
                    }
                }
            }
            fillMissingTitles(out)
            return out.build(title, author, src)
        } catch (e: Throwable) {
            src.close()
            throw e
        }
    }

    /**
     * Some books' tables of contents have entries with no real title (just "5." — the title is a
     * picture or missing). Fill those in from the first headings at the start of that chapter.
     */
    private fun fillMissingTitles(out: DocBuilder) {
        for (i in out.toc.indices) {
            val e = out.toc[i]
            if (e.title.any { it.isLetter() }) continue
            val found = ArrayList<String>()
            var b = e.loc.block
            val end = minOf(out.blocks.size, e.loc.block + 8)
            while (b < end && found.size < 2) {
                val blk = out.blocks[b]
                if (blk is TextBlock) {
                    val t = blk.text.toString().replace('\n', ' ').trim()
                    val headingLike = blk.kind == TextBlock.HEADING ||
                        (t.length <= 60 && (blk.align == TextBlock.ALIGN_CENTER || blk.align == TextBlock.ALIGN_RIGHT))
                    if (!headingLike) {
                        if (found.isNotEmpty() || t.length > 60) break
                    } else if (t.any { it.isLetter() } && t !in found) found += t.take(60)
                }
                b++
            }
            if (found.isNotEmpty()) {
                val num = e.title.trim()
                out.toc[i] = TocEntry(if (num.isEmpty()) found.joinToString(" · ") else "$num ${found.joinToString(" · ")}", e.level, e.loc)
            }
        }
    }

    private fun readNavList(ol: Element, level: Int, add: (String, Int, String) -> Unit) {
        for (li in ol.children()) {
            if (li.local() != "li") continue
            val label = li.children().firstOrNull { it.local() == "a" || it.local() == "span" }
            var title = label?.text()?.trim().orEmpty()
            if (title.none { it.isLetter() }) {
                // e.g. <li><a href="..">5.</a> <span>Title</span></li>: use the whole entry's text.
                val whole = li.childNodes().filterNot { it is Element && it.local() == "ol" }.joinToString(" ") {
                    when (it) {
                        is org.jsoup.nodes.TextNode -> it.text()
                        is Element -> it.text()
                        else -> ""
                    }
                }.replace(Regex("\\s+"), " ").trim()
                if (whole.any { it.isLetter() }) title = whole
            }
            val href = label?.attr("href")?.ifEmpty { null } ?: li.children().firstOrNull { it.local() == "a" }?.attr("href").orEmpty()
            if (title.isNotEmpty() && href.isNotEmpty()) add(title, level, href)
            li.children().firstOrNull { it.local() == "ol" }?.let { readNavList(it, level + 1, add) }
        }
    }

    private fun readNcx(parent: Element, level: Int, add: (String, Int, String) -> Unit) {
        for (np in parent.children()) {
            if (np.local() != "navpoint") continue
            val title = np.children().firstOrNull { it.local() == "navlabel" }?.text()?.trim().orEmpty()
            val src = np.children().firstOrNull { it.local() == "content" }?.attr("src").orEmpty()
            if (title.isNotEmpty() && src.isNotEmpty()) add(title, level, src)
            readNcx(np, level + 1, add)
        }
    }
}
