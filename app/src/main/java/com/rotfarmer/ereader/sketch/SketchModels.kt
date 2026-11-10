package com.rotfarmer.ereader.sketch

import com.rotfarmer.ereader.ink.InkStroke

data class Sketchbook(val id: Long, val title: String, val pageCount: Int, val currentPage: Int, val updated: Long,
    val defaultPaper: Int = 0, val defaultSpacing: Float = 1f)
data class ReferenceLayer(val id: Long, val path: String, val x: Float, val y: Float,
    val width: Float, val height: Float, val opacity: Float,
    val rotation: Float = 0f, val cropLeft: Float = 0f, val cropTop: Float = 0f,
    val cropRight: Float = 1f, val cropBottom: Float = 1f,
    val locked: Boolean = true, val visible: Boolean = true, val docked: Boolean = false,
    val dim: Float = 0f, val source: String = "", val sourcePage: Int = 0,
    val name: String = "Reference", val sourceBook: Long = -1, val sourceLocation: String = "",
    val order: Int = 0, val original: String = "")
data class SketchPage(val paper: Int, val references: List<ReferenceLayer>, val strokes: List<InkStroke>, val spacing: Float = 1f)
data class PageInfo(val id: Long, val book: Long, val index: Int, val slot: Int, val title: String = "",
    val section: String = "", val bookmarked: Boolean = false, val used: Boolean = false,
    val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f, val rotation: Float = 0f)
data class TrashItem(val id: Long, val title: String, val page: Boolean, val deleted: Long)
object SketchIds {
    private val last = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() * 1000)
    fun next(): Long = last.incrementAndGet()
}

/** New objects intentionally discard InkPainter's cached outline after a transform. */
fun InkStroke.copy(id: Long = this.id, width: Float = this.width, pts: FloatArray = this.pts,
    tool: Int = this.tool, color: Int = this.color) = InkStroke(id, bookId, anchor, tool, color, width, pts, created)
