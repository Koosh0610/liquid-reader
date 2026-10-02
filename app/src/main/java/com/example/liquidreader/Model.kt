package com.example.liquidreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

typealias Range = ClosedFloatingPointRange<Float>

// rects are normalized to 0..1 of the page; a document position is page index + fraction
data class Anchor(val page: Int, val rects: List<Rect>) {
    val top get() = page + rects.minOf { it.top }
    val bottom get() = page + rects.maxOf { it.bottom }
}

class Highlight(val id: Long, val anchor: Anchor, color: Int, val text: String = "") {
    var color by mutableIntStateOf(color)
}

enum class Kind { Excerpt, Image, Note }

class Card(
    val id: Long,
    val kind: Kind,
    text: String,
    color: Int,
    val anchor: Anchor?,
    x: Float,
    y: Float,
    val image: String? = null,
) {
    var text by mutableStateOf(text)
    var color by mutableIntStateOf(color)
    var x by mutableFloatStateOf(x)
    var y by mutableFloatStateOf(y)
}

data class Link(val a: Long, val b: Long)

// page = -1 means the workspace; page strokes use normalized page coords, workspace strokes canvas px.
// pressures is empty for finger ink, otherwise one 0..1 value per point
class Stroke(
    val id: Long,
    val page: Int,
    val points: List<Offset>,
    val color: Int,
    val width: Float,
    val pressures: List<Float> = emptyList(),
) {
    val bounds by lazy { inkBounds(points) }
}

// links a highlighted passage to handwriting and notes in the workspace, without excerpting it
class Tether(val id: Long, val highlight: Long, strokes: List<Long> = emptyList(), cards: List<Long> = emptyList()) {
    var strokes by mutableStateOf(strokes)
    var cards by mutableStateOf(cards)
    val isEmpty get() = strokes.isEmpty() && cards.isEmpty()
}

enum class Paper { Blank, Ruled, Grid, Dots }

class Project(val dir: File) {
    var title by mutableStateOf("Untitled")
    var split by mutableFloatStateOf(0.55f)
    var pan by mutableStateOf(Offset(40f, 40f))
    var zoom by mutableFloatStateOf(1f)
    var notebook by mutableStateOf(false)
    var paper by mutableStateOf(Paper.Dots)
    val tethers = mutableStateListOf<Tether>()
    val highlights = mutableStateListOf<Highlight>()
    val cards = mutableStateListOf<Card>()
    val links = mutableStateListOf<Link>()
    val strokes = mutableStateListOf<Stroke>()
    val collapses = mutableStateListOf<Range>()

    val pdf get() = File(dir, "doc.pdf")
    val thumb get() = File(dir, "thumb.png")
    val updated get() = File(dir, "project.json").lastModified()

    private var lastId = System.currentTimeMillis()
    fun nextId() = ++lastId

    fun card(id: Long) = cards.firstOrNull { it.id == id }

    fun removeCard(c: Card) {
        cards.remove(c)
        tethers.forEach { it.cards -= c.id }
        tethers.removeAll { it.isEmpty }
        links.removeAll { it.a == c.id || it.b == c.id }
        c.anchor?.let { a -> if (cards.none { it.anchor == a }) highlights.removeAll { it.anchor == a } }
        c.image?.let { File(dir, it).delete() }
    }

    fun removeStrokes(gone: (Stroke) -> Boolean) {
        val ids = strokes.filter(gone).map { it.id }.toSet()
        if (ids.isEmpty()) return
        strokes.removeAll { it.id in ids }
        tethers.forEach { it.strokes -= ids }
        tethers.removeAll { it.isEmpty }
    }

    fun removeHighlight(h: Highlight) {
        highlights.remove(h)
        tethers.removeAll { it.highlight == h.id }
    }

    fun highlight(id: Long) = highlights.firstOrNull { it.id == id }

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("split", split.toDouble())
        put("pan", JSONArray(listOf(pan.x.toDouble(), pan.y.toDouble())))
        put("zoom", zoom.toDouble())
        put("notebook", notebook)
        put("paper", paper.name)
        put("tethers", JSONArray(tethers.map {
            JSONObject().put("id", it.id).put("highlight", it.highlight)
                .put("strokes", JSONArray(it.strokes)).put("cards", JSONArray(it.cards))
        }))
        put("highlights", JSONArray(highlights.map {
            JSONObject().put("id", it.id).put("color", it.color).put("text", it.text).put("anchor", it.anchor.json())
        }))
        put("cards", JSONArray(cards.map { c ->
            JSONObject().put("id", c.id).put("kind", c.kind.name).put("text", c.text).put("color", c.color)
                .put("x", c.x.toDouble()).put("y", c.y.toDouble())
                .apply { c.anchor?.let { put("anchor", it.json()) }; c.image?.let { put("image", it) } }
        }))
        put("links", JSONArray(links.map { JSONArray(listOf(it.a, it.b)) }))
        put("strokes", JSONArray(strokes.map { s ->
            JSONObject().put("id", s.id).put("page", s.page).put("color", s.color).put("width", s.width.toDouble())
                .put("points", JSONArray(s.points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }))
                .apply { if (s.pressures.isNotEmpty()) put("pressures", JSONArray(s.pressures.map { it.toDouble() })) }
        }))
        put("collapses", JSONArray(collapses.map { JSONArray(listOf(it.start.toDouble(), it.endInclusive.toDouble())) }))
    }

    fun save() = File(dir, "project.json").writeText(toJson().toString())

    companion object {
        fun load(dir: File): Project = Project(dir).apply {
            val o = JSONObject(File(dir, "project.json").readText())
            title = o.optString("title", title)
            split = o.optDouble("split", 0.55).toFloat()
            o.optJSONArray("pan")?.let { pan = Offset(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
            zoom = o.optDouble("zoom", 1.0).toFloat()
            notebook = o.optBoolean("notebook", false)
            paper = Paper.valueOf(o.optString("paper", Paper.Dots.name))
            o.optJSONArray("tethers")?.objects()?.forEach {
                tethers += Tether(it.getLong("id"), it.getLong("highlight"), it.getJSONArray("strokes").longs(), it.getJSONArray("cards").longs())
            }
            o.optJSONArray("highlights")?.objects()?.forEach {
                highlights += Highlight(it.getLong("id"), anchor(it.getJSONObject("anchor")), it.getInt("color"), it.optString("text"))
            }
            o.optJSONArray("cards")?.objects()?.forEach {
                cards += Card(
                    it.getLong("id"), Kind.valueOf(it.getString("kind")), it.getString("text"), it.getInt("color"),
                    it.optJSONObject("anchor")?.let(::anchor),
                    it.getDouble("x").toFloat(), it.getDouble("y").toFloat(),
                    if (it.has("image")) it.getString("image") else null,
                )
            }
            o.optJSONArray("links")?.let { a ->
                for (i in 0 until a.length()) a.getJSONArray(i).let { links += Link(it.getLong(0), it.getLong(1)) }
            }
            o.optJSONArray("strokes")?.objects()?.forEach {
                val p = it.getJSONArray("points")
                strokes += Stroke(
                    it.getLong("id"), it.getInt("page"),
                    (0 until p.length() / 2).map { i -> Offset(p.getDouble(2 * i).toFloat(), p.getDouble(2 * i + 1).toFloat()) },
                    it.getInt("color"), it.getDouble("width").toFloat(),
                    it.optJSONArray("pressures")?.let { a -> (0 until a.length()).map { i -> a.getDouble(i).toFloat() } }.orEmpty(),
                )
            }
            o.optJSONArray("collapses")?.let { a ->
                for (i in 0 until a.length()) a.getJSONArray(i).let {
                    collapses += it.getDouble(0).toFloat()..it.getDouble(1).toFloat()
                }
            }
            lastId = maxOf(lastId, (cards.map { it.id } + highlights.map { it.id } + strokes.map { it.id } + tethers.map { it.id }).maxOrNull() ?: 0)
        }
    }
}

private fun Anchor.json() = JSONObject().put("page", page).put("rects", JSONArray(rects.map {
    JSONArray(listOf(it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble()))
}))

private fun anchor(o: JSONObject): Anchor {
    val r = o.getJSONArray("rects")
    return Anchor(o.getInt("page"), (0 until r.length()).map { i ->
        r.getJSONArray(i).let { Rect(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat()) }
    })
}

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

private fun JSONArray.longs() = (0 until length()).map { getLong(it) }

object Store {
    private fun root(ctx: Context) = File(ctx.filesDir, "projects").apply { mkdirs() }

    fun list(ctx: Context): List<Project> =
        root(ctx).listFiles().orEmpty()
            .filter { File(it, "project.json").exists() }
            .mapNotNull { runCatching { Project.load(it) }.getOrNull() }
            .sortedByDescending { it.updated }

    fun create(ctx: Context, uri: Uri): Project {
        val dir = File(root(ctx), UUID.randomUUID().toString()).apply { mkdirs() }
        val p = Project(dir)
        ctx.contentResolver.openInputStream(uri)!!.use { input -> p.pdf.outputStream().use { input.copyTo(it) } }
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) p.title = it.getString(0).removeSuffix(".pdf").removeSuffix(".PDF")
        }
        PdfDoc(p.pdf).use { it.thumbnail(p.thumb) }
        p.save()
        return p
    }

    fun delete(p: Project) = p.dir.deleteRecursively()
}

// collapse ranges: pure helpers, unit tested

fun mergeRanges(ranges: List<Range>): List<Range> {
    val out = mutableListOf<Range>()
    for (r in ranges.sortedBy { it.start }) {
        val last = out.lastOrNull()
        if (last != null && r.start <= last.endInclusive) out[out.lastIndex] = last.start..maxOf(last.endInclusive, r.endInclusive)
        else out += r
    }
    return out
}

// what to hide so only the kept ranges (padded) stay visible
fun complement(keep: List<Range>, total: Float, pad: Float): List<Range> {
    val shown = mergeRanges(keep.map { (it.start - pad).coerceAtLeast(0f)..(it.endInclusive + pad).coerceAtMost(total) })
    val out = mutableListOf<Range>()
    var f = 0f
    for (r in shown) {
        if (r.start > f) out += f..r.start
        f = r.endInclusive
    }
    if (f < total) out += f..total
    return out
}

sealed interface Seg {
    // page fractions
    data class Visible(val f0: Float, val f1: Float) : Seg
    data class Band(val range: Range) : Seg
}

// a page is drawn as visible slices; a collapsed range becomes one band in the page where it starts
fun segments(page: Int, collapses: List<Range>): List<Seg> {
    val end = page + 1f
    var f = page.toFloat()
    val out = mutableListOf<Seg>()
    for (c in mergeRanges(collapses)) {
        if (c.endInclusive <= f || c.start >= end) continue
        if (c.start > f) out += Seg.Visible(f - page, c.start - page)
        if (c.start >= page) out += Seg.Band(c)
        f = c.endInclusive
    }
    if (f < end) out += Seg.Visible(f - page, 1f)
    return out
}

fun inkBounds(points: List<Offset>) = Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })

// strokes whose bounds come within gap of each other belong to the same piece of handwriting
fun cluster(all: List<Stroke>, seed: Stroke, gap: Float): List<Stroke> {
    val out = mutableListOf(seed)
    val queue = ArrayDeque(listOf(seed))
    val left = all.filter { it.id != seed.id }.toMutableList()
    while (queue.isNotEmpty()) {
        val b = queue.removeFirst().bounds.inflate(gap)
        val near = left.filter { it.bounds.overlaps(b) }
        left.removeAll(near)
        out += near
        queue += near
    }
    return out
}
