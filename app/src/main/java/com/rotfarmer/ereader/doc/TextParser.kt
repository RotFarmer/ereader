package com.rotfarmer.ereader.doc

import com.rotfarmer.ereader.data.Locator
import org.jsoup.Jsoup
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object NoResources : ResourceSource {
    override fun open(path: String): InputStream? = null
    override fun close() {}
}

/** Images next to a standalone HTML file. */
class DirSource(private val dir: File) : ResourceSource {
    override fun open(path: String): InputStream? = File(dir, path).takeIf { it.isFile }?.inputStream()
    override fun close() {}
}

object TextParser {
    private val CHAPTER = Regex("^\\s*(chapter|book|part|prologue|epilogue|act)\\b.{0,60}$", RegexOption.IGNORE_CASE)

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    fun parseText(file: File, title: String): FlowDocument = parseString(decode(file.readBytes()), title)

    fun parseString(raw: String, title: String): FlowDocument {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        val blankLines = lines.count { it.isBlank() }
        val byBlank = blankLines > lines.size / 20
        val out = DocBuilder()
        val para = StringBuilder()

        fun flush() {
            val s = para.toString().trim()
            para.setLength(0)
            if (s.isEmpty()) return
            if (s.length < 70 && !s.contains('\n') && CHAPTER.matches(s)) {
                out.startChapter()
                out.toc += TocEntry(s, 1, Locator(out.blocks.size, 0))
                out.blocks += TextBlock(s, TextBlock.HEADING, 2)
            } else {
                out.blocks += TextBlock(s.replace(Regex("\\s*\n\\s*"), " "))
            }
        }

        for (line in lines) {
            if (byBlank) {
                if (line.isBlank()) flush() else para.append(line).append('\n')
            } else {
                para.append(line); flush()
            }
        }
        flush()
        return out.build(title, "", NoResources)
    }

    fun parseHtml(file: File, fallbackTitle: String): FlowDocument {
        val doc = Jsoup.parse(file, null)
        val out = DocBuilder()
        val css = CssLite()
        doc.select("style").forEach { css.parse(it.data()) }
        HtmlConverter(out, "", "", css).convert(doc.body())
        out.blocks.forEachIndexed { i, b ->
            if (b is TextBlock && b.kind == TextBlock.HEADING && b.level <= 2) {
                out.toc += TocEntry(b.text.toString().take(80), b.level, Locator(i, 0))
            }
        }
        val title = doc.title().trim().ifEmpty { fallbackTitle }
        return out.build(title, "", DirSource(file.parentFile ?: file))
    }
}
