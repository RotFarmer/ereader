package com.rotfarmer.ereader.ink

import android.graphics.Path
import android.graphics.RectF

/** Pen tools. Ids are stored in the database, so never renumber them. */
object Tools {
    const val PEN = 0
    const val FOUNTAIN = 1
    const val CALLIGRAPHY = 2
    const val PENCIL = 3
    const val MARKER = 4
    const val HIGHLIGHTER = 5
    const val ERASER = 6
    const val BRUSH = 7
    const val SOFT_BRUSH = 8

    val DRAWING = intArrayOf(PEN, FOUNTAIN, CALLIGRAPHY, PENCIL, MARKER, HIGHLIGHTER, BRUSH, SOFT_BRUSH)

    fun name(tool: Int): String = when (tool) {
        PEN -> "Ballpoint"
        FOUNTAIN -> "Fountain pen"
        CALLIGRAPHY -> "Calligraphy"
        PENCIL -> "Pencil"
        MARKER -> "Marker"
        HIGHLIGHTER -> "Highlighter"
        ERASER -> "Eraser"
        BRUSH -> "Brush"
        SOFT_BRUSH -> "Soft brush"
        else -> "Pen"
    }

    /** Default width in dp. */
    fun defaultWidth(tool: Int): Float = when (tool) {
        PEN -> 1.6f
        FOUNTAIN -> 2.4f
        CALLIGRAPHY -> 4.5f
        PENCIL -> 1.5f
        MARKER -> 3.5f
        HIGHLIGHTER -> 16f
        ERASER -> 14f
        BRUSH -> 5f
        SOFT_BRUSH -> 16f
        else -> 2f
    }

    fun minWidth(tool: Int): Float = when (tool) {
        HIGHLIGHTER -> 6f
        ERASER -> 6f
        BRUSH -> 0.8f
        SOFT_BRUSH -> 4f
        else -> 0.6f
    }

    fun maxWidth(tool: Int): Float = when (tool) {
        HIGHLIGHTER -> 40f
        ERASER -> 50f
        CALLIGRAPHY -> 14f
        MARKER -> 14f
        BRUSH -> 24f
        SOFT_BRUSH -> 40f
        else -> 9f
    }

    fun defaultColor(tool: Int): Int = when (tool) {
        HIGHLIGHTER -> 0xFFFFE14D.toInt()
        MARKER -> 0xFF2F5DB8.toInt()
        PENCIL -> 0xFF3A3A3A.toInt()
        SOFT_BRUSH -> 0xFF4A4A4A.toInt()
        else -> 0xFF1A1A1A.toInt()
    }

    val INK_COLORS = intArrayOf(
        0xFF1A1A1A.toInt(), 0xFF5A5A5A.toInt(), 0xFF9C9C9C.toInt(), 0xFFFFFFFF.toInt(),
        0xFF1F3A93.toInt(), 0xFF2F6FDF.toInt(), 0xFF138A72.toInt(), 0xFF3C9A3C.toInt(),
        0xFFC62828.toInt(), 0xFF7B1E3A.toInt(), 0xFFE67E22.toInt(), 0xFF6A3FA0.toInt(),
        0xFF6D4C41.toInt(), 0xFFB8860B.toInt(), 0xFFD81B60.toInt(), 0xFF00838F.toInt(),
    )

    val HIGHLIGHTER_COLORS = intArrayOf(
        0xFFFFE14D.toInt(), 0xFF9BE37A.toInt(), 0xFF8CC8FF.toInt(), 0xFFFF9EC7.toInt(),
        0xFFFFC078.toInt(), 0xFFC9A8FF.toInt(), 0xFFBDBDBD.toInt(), 0xFF7FE0D4.toInt(),
    )
}

/**
 * What a stroke is attached to, so it moves with the content when pages re-flow.
 * BLOCK: a1 = paragraph index, a2 = character offset of the line the stroke sits next to.
 * SPACE: a1 = writing space id, a2 = which part of a split PDF page (0 or 1).
 * PDF:   a1 = page index.
 */
class Anchor(val kind: Int, val a1: Long, val a2: Int) {
    val key: Long get() = keyOf(kind, a1)

    companion object {
        const val BLOCK = 0
        const val SPACE = 1
        const val PDF = 2
        fun keyOf(kind: Int, a1: Long): Long = (kind.toLong() shl 58) xor a1
    }
}

/**
 * A finished stroke. Points are (x, y, pressure) triples in "anchor units":
 * relative to the anchor's origin and divided by the anchor's unit length
 * (content width for text, page width for PDFs), so ink scales with the page.
 */
class InkStroke(
    val id: Long,
    val bookId: Long,
    val anchor: Anchor,
    val tool: Int,
    val color: Int,
    val width: Float,
    val pts: FloatArray,
    val created: Long,
) {
    val count: Int get() = pts.size / 3

    /** Outline cached in anchor units. */
    var shape: StrokeShape? = null

    val bounds: RectF by lazy {
        // Plain min/max: RectF.union(x, y) uses if/else-if and misses edges for strokes
        // that only move up or only move left (they then could never be erased).
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            i += 3
        }
        if (minX > maxX) RectF() else RectF(minX - width, minY - width, maxX + width, maxY + width)
    }
}

class StrokeShape(val path: Path, val filled: Boolean, val avgPressure: Float)

/** All strokes of an open book, indexed by what they are attached to. */
class InkStore {
    private val byKey = HashMap<Long, ArrayList<InkStroke>>()

    fun add(s: InkStroke) {
        byKey.getOrPut(s.anchor.key) { ArrayList() }.add(s)
    }

    fun remove(s: InkStroke) {
        byKey[s.anchor.key]?.remove(s)
    }

    fun get(kind: Int, a1: Long): List<InkStroke> = byKey[Anchor.keyOf(kind, a1)] ?: emptyList()

    fun all(): List<InkStroke> = byKey.values.flatten()

    fun removeAnchor(kind: Int, a1: Long): List<InkStroke> = byKey.remove(Anchor.keyOf(kind, a1)) ?: emptyList()

    fun clear() = byKey.clear()
}
