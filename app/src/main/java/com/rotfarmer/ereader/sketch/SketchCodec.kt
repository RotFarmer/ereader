package com.rotfarmer.ereader.sketch

import android.util.Base64
import com.rotfarmer.ereader.ink.Anchor
import com.rotfarmer.ereader.ink.InkStroke
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lossless representation shared by the delta journal and editable backups. */
object SketchCodec {
    fun points(p: FloatArray): ByteArray = ByteBuffer.allocate(p.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(p) }.array()
    fun points(bytes: ByteArray): FloatArray {
        require(bytes.size % 12 == 0) { "Invalid ink points" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }
    fun stroke(s: InkStroke) = JSONObject().put("id", s.id).put("tool", s.tool).put("color", s.color)
        .put("width", s.width).put("created", s.created).put("points", Base64.encodeToString(points(s.pts), Base64.NO_WRAP))
    fun stroke(o: JSONObject, book: Long, page: Long) = InkStroke(o.getLong("id"), book, Anchor(Anchor.PDF, page, 0),
        o.getInt("tool"), o.getInt("color"), o.getDouble("width").toFloat(), points(Base64.decode(o.getString("points"), Base64.DEFAULT)), o.getLong("created"))
    fun reference(r: ReferenceLayer) = JSONObject().put("id", r.id).put("path", r.path).put("x", r.x).put("y", r.y)
        .put("width", r.width).put("height", r.height).put("opacity", r.opacity).put("rotation", r.rotation)
        .put("cropLeft", r.cropLeft).put("cropTop", r.cropTop).put("cropRight", r.cropRight).put("cropBottom", r.cropBottom)
        .put("locked", r.locked).put("visible", r.visible).put("docked", r.docked).put("dim", r.dim)
        .put("source", r.source).put("sourcePage", r.sourcePage).put("name", r.name)
        .put("sourceBook", r.sourceBook).put("sourceLocation", r.sourceLocation).put("order",r.order).put("original",r.original)
    fun reference(o: JSONObject) = ReferenceLayer(o.getLong("id"), o.getString("path"), o.getDouble("x").toFloat(),
        o.getDouble("y").toFloat(), o.getDouble("width").toFloat(), o.getDouble("height").toFloat(), o.getDouble("opacity").toFloat(),
        o.optDouble("rotation", 0.0).toFloat(), o.optDouble("cropLeft", 0.0).toFloat(), o.optDouble("cropTop", 0.0).toFloat(),
        o.optDouble("cropRight", 1.0).toFloat(), o.optDouble("cropBottom", 1.0).toFloat(), o.optBoolean("locked", true),
        o.optBoolean("visible", true), o.optBoolean("docked", false), o.optDouble("dim", 0.0).toFloat(),
        o.optString("source"), o.optInt("sourcePage"), o.optString("name", "Reference"), o.optLong("sourceBook", -1), o.optString("sourceLocation"),o.optInt("order"),o.optString("original"))
    fun page(p: SketchPage) = JSONObject().put("version", 1).put("paper", p.paper).put("spacing", p.spacing)
        .put("references", JSONArray().apply { p.references.forEach { put(reference(it)) } })
        .put("strokes", JSONArray().apply { p.strokes.forEach { put(stroke(it)) } })
    fun page(o: JSONObject, book: Long, id: Long) = SketchPage(o.getInt("paper"),
        o.getJSONArray("references").objects().map(::reference), o.getJSONArray("strokes").objects().map { stroke(it, book, id) }, o.optDouble("spacing", 1.0).toFloat())
    fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    fun same(a: InkStroke, b: InkStroke) = a.id == b.id && a.tool == b.tool && a.color == b.color && a.width == b.width && a.created == b.created && a.pts.contentEquals(b.pts)
}
