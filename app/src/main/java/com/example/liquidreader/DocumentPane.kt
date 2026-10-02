package com.example.liquidreader

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun DocumentPane(ui: ReaderUi, modifier: Modifier) {
    LaunchedEffect(ui.list.isScrollInProgress) { if (ui.list.isScrollInProgress) ui.selection = null }
    Box(
        modifier.background(Palette.Backdrop)
            .onGloballyPositioned { ui.docRect = it.boundsInRoot() }
            .pointerInput(Unit) { pinchToCollapse(ui) },
    ) {
        LazyColumn(
            state = ui.list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 30.dp, top = 6.dp, bottom = 240.dp),
        ) {
            items(ui.doc.pageCount, key = { it }) { page -> PageItem(ui, page) }
        }
        ScrollMarks(ui, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(14.dp).padding(vertical = 10.dp))
        if (ui.linkingStrokes != null) Row(
            Modifier.align(Alignment.TopCenter).padding(top = 14.dp).shadow(10.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp)).background(Palette.Pill).padding(start = 16.dp, end = 4.dp).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Select the text to link these notes to", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable { ui.linkingStrokes = null }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text("Cancel", color = Color(0xFF8DB4FF), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        ui.focus?.let {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).shadow(8.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp)).background(Palette.Pill).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${ui.focusLabel} only", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable { ui.focus = null }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text("Show all", color = Color(0xFF8DB4FF), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// a vertical two-finger pinch squeezes everything between the fingers into a band; spreading reopens it
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.pinchToCollapse(ui: ReaderUi) = awaitEachGesture {
    val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    ui.leader = Pane.Doc
    if (first.isPen) ui.stylusSeen = true
    var start: Pair<Float, Float>? = null
    var done = false
    var lastMid: Float? = null
    var ev: PointerEvent
    do {
        ev = awaitPointerEvent(PointerEventPass.Initial)
        val down = ev.changes.filter { it.pressed }
        if (down.size >= 2) {
            val ys = down.take(2).map { it.position.y }.sorted()
            val s = start ?: (ys[0] to ys[1]).also { start = it }
            val span0 = s.second - s.first
            val span = ys[1] - ys[0]
            if (!done && span0 > 80f && span < span0 * 0.55f) {
                done = true
                ui.collapseBetween(s.first + ui.docRect.top, s.second + ui.docRect.top)
            }
            if (!done && span > span0 * 1.5f + 60f) {
                done = true
                ui.expandBetween(s.first + ui.docRect.top, s.second + ui.docRect.top)
            }
            // a two-finger drag that isn't a pinch scrolls, so drawing tools can still move around
            val mid = (ys[0] + ys[1]) / 2
            if (!done) lastMid?.let { ui.list.dispatchRawDelta(it - mid) }
            lastMid = mid
            ev.changes.forEach { it.consume() }
        } else lastMid = null
    } while (ev.changes.any { it.pressed })
}

@Composable
private fun PageItem(ui: ReaderUi, page: Int) {
    val segs = segments(page, ui.collapses)
    Column(Modifier.fillMaxWidth().animateContentSize(tween(260))) {
        for (s in segs) when (s) {
            is Seg.Visible -> key(s.f0) { PageSlice(ui, page, s.f0, s.f1) }
            is Seg.Band -> key("band${s.range.start}") { CollapseBand(s.range) { ui.expand(s.range) } }
        }
    }
}

@Composable
private fun CollapseBand(r: Range, onExpand: () -> Unit) {
    val pages = r.endInclusive - r.start
    val lines = (pages * 6).roundToInt().coerceIn(2, 7)
    Box(
        Modifier.fillMaxWidth().height(34.dp).shadow(1.dp).background(Color.White).clickable(onClick = onExpand),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 6.dp)) {
            val gap = size.height / (lines + 1)
            for (i in 1..lines) {
                val y = gap * i
                drawLine(Color(0xFFC9C9D1), Offset(0f, y), Offset(size.width * (0.75f + 0.25f * ((i * 37) % 10) / 10f), y), 1.5f)
            }
        }
        Text(
            if (pages >= 1f) "%.1f pages".format(pages) else "${(pages * 100).roundToInt()}% of a page",
            fontSize = 11.sp, color = Palette.Muted,
            modifier = Modifier.padding(end = 10.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Chrome).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun PageSlice(ui: ReaderUi, page: Int, f0: Float, f1: Float) {
    val doc = ui.doc
    val project = ui.project
    val aspect = doc.aspect(page)
    val bmp by produceState(doc.cached(page), page) {
        if (value == null) value = withContext(Dispatchers.IO) { doc.bitmap(page)?.asImageBitmap() }
    }
    var drag by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val flashAlpha by animateFloatAsState(if (ui.flash?.page == page) 1f else 0f, tween(300), label = "flash")
    val density = LocalDensity.current
    val sliceKey = page to f0
    DisposableEffect(sliceKey) { onDispose { ui.slices.remove(sliceKey) } }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = if (f0 == 0f) 16.dp else 0.dp)) {
        val w = constraints.maxWidth.toFloat()
        ui.pageWidthPx = w
        ui.pageTopPx = with(density) { 22.dp.toPx() }
        val pageH = w * aspect
        val h = pageH * (f1 - f0)
        fun norm(o: Offset) = Offset(o.x / w, f0 + o.y / pageH)
        fun px(n: Offset) = Offset(n.x * w, (n.y - f0) * pageH)

        val gestures = when (ui.tool) {
            Tool.Select -> Modifier
                .pointerInput(page, f0, f1, ui.tool) { detectTapGestures { ui.tapDoc(page, norm(it)) } }
                .pointerInput(page, f0, f1, ui.tool) {
                    var a = Offset.Zero
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            a = norm(it)
                            ui.selecting = true
                            ui.select(page, a, a + Offset(0.002f, 0f))
                        },
                        onDrag = { change, _ -> change.consume(); ui.select(page, a, norm(change.position)) },
                        onDragEnd = { ui.selectionDone() },
                        onDragCancel = { ui.selecting = false },
                    )
                }
            Tool.Box -> Modifier.pointerInput(page, f0, f1, ui.tool) {
                detectDragGestures(
                    onDragStart = { drag = it to it },
                    onDrag = { change, _ -> change.consume(); drag = drag?.copy(second = change.position) },
                    onDragEnd = {
                        drag?.let { (s, e) ->
                            val a = norm(Offset(min(s.x, e.x), min(s.y, e.y)))
                            val b = norm(Offset(max(s.x, e.x), max(s.y, e.y)))
                            val clamped = Rect(a.x.coerceIn(0f, 1f), a.y.coerceIn(0f, 1f), b.x.coerceIn(0f, 1f), b.y.coerceIn(0f, 1f))
                            if (clamped.width > 0.03f && clamped.height > 0.01f) ui.boxExcerpt(page, clamped)
                        }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                )
            }
            Tool.Pen -> Modifier.pointerInput(page, f0, f1, ui.tool, ui.inkColor) {
                trackPointer(
                    accept = { !ui.stylusSeen && !it.isPen },
                    onStart = { o, _ -> live = listOf(norm(o)) },
                    onMove = { o, _ -> live = live + norm(o) },
                    onEnd = {
                        if (live.size > 1) ui.addStroke(Stroke(project.nextId(), page, live, ui.inkColor, 0.0035f))
                        live = emptyList()
                    },
                )
            }
            Tool.Eraser -> Modifier.pointerInput(page, f0, f1, ui.tool) {
                trackPointer(
                    accept = { !ui.stylusSeen && !it.isPen },
                    onStart = { o, _ -> ui.eraseDoc(page, norm(o)) },
                    onMove = { o, _ -> ui.eraseDoc(page, norm(o)) },
                    onEnd = {},
                )
            }
        }

        // the stylus acts directly: it selects text without a long-press, inks with pressure, and its eraser end erases
        var penPressures by remember { mutableStateOf<List<Float>>(emptyList()) }
        val stylus = Modifier.pointerInput(page, f0, f1, ui.tool, ui.inkColor) {
            var a = Offset.Zero
            var erasing = false
            trackPointer(
                accept = { it.isPen },
                pass = PointerEventPass.Initial,
                onStart = { o, pr ->
                    ui.stylusSeen = true
                    erasing = currentEventIsEraser || ui.tool == Tool.Eraser
                    a = norm(o)
                    when {
                        erasing -> ui.eraseDoc(page, a)
                        ui.tool == Tool.Select -> { ui.selecting = true; ui.select(page, a, a + Offset(0.002f, 0f)) }
                        ui.tool == Tool.Pen -> { live = listOf(a); penPressures = listOf(pr) }
                        ui.tool == Tool.Box -> drag = o to o
                    }
                },
                onMove = { o, pr ->
                    when {
                        erasing -> ui.eraseDoc(page, norm(o))
                        ui.tool == Tool.Select -> ui.select(page, a, norm(o))
                        ui.tool == Tool.Pen -> { live = live + norm(o); penPressures = penPressures + pr }
                        ui.tool == Tool.Box -> drag = drag?.copy(second = o)
                    }
                },
                onEnd = {
                    when {
                        erasing -> {}
                        ui.tool == Tool.Select -> ui.selectionDone()
                        ui.tool == Tool.Pen -> {
                            if (live.size > 1) ui.addStroke(Stroke(project.nextId(), page, live, ui.inkColor, 0.0035f, penPressures))
                            live = emptyList()
                        }
                        ui.tool == Tool.Box -> {
                            drag?.let { (s, e) ->
                                val p0 = norm(Offset(min(s.x, e.x), min(s.y, e.y)))
                                val p1 = norm(Offset(max(s.x, e.x), max(s.y, e.y)))
                                val r = Rect(p0.x.coerceIn(0f, 1f), p0.y.coerceIn(0f, 1f), p1.x.coerceIn(0f, 1f), p1.y.coerceIn(0f, 1f))
                                if (r.width > 0.03f && r.height > 0.01f) ui.boxExcerpt(page, r)
                            }
                            drag = null
                        }
                    }
                },
            )
        }

        Box(
            Modifier.fillMaxWidth().height(with(density) { h.toDp() })
                .shadow(2.dp).background(Color.White)
                .onGloballyPositioned { ui.slices[sliceKey] = SliceInfo(page, f0, f1, Rect(it.positionInRoot(), it.size.toSize())) }
                .then(gestures)
                .then(stylus)
                .drawWithContent {
                    bmp?.let { drawSlice(it, f0, f1) }
                    fun rectPx(r: Rect) = Rect(px(r.topLeft), px(r.bottomRight))
                    for (hl in project.highlights) if (hl.anchor.page == page) for (r in hl.anchor.rects) {
                        val rp = rectPx(r)
                        if (hl.text.isEmpty()) {
                            // region excerpt: tint and outline so figures stay legible
                            drawRect(Color(hl.color), rp.topLeft, rp.size, alpha = 0.22f, blendMode = BlendMode.Multiply)
                            drawRoundRect(Color(hl.color), rp.topLeft, rp.size, CornerRadius(6f), style = StrokeStyle(4f))
                        } else drawRect(Color(hl.color), rp.topLeft, rp.size, alpha = 0.75f, blendMode = BlendMode.Multiply)
                    }
                    ui.hits.forEachIndexed { i, a ->
                        if (a.page == page) for (r in a.rects) {
                            val rp = rectPx(r)
                            drawRect(Palette.SearchHit, rp.topLeft, rp.size, alpha = if (i == ui.hitIndex) 0.6f else 0.3f, blendMode = BlendMode.Multiply)
                        }
                    }
                    ui.selection?.takeIf { it.page == page && it.highlight == null }?.rects?.forEach {
                        val rp = rectPx(it)
                        drawRect(Palette.Accent, rp.topLeft, rp.size, alpha = 0.22f)
                    }
                    ui.flash?.takeIf { it.page == page && flashAlpha > 0f }?.let { a ->
                        val u = a.rects.map(::rectPx).reduce { acc, r -> Rect(min(acc.left, r.left), min(acc.top, r.top), max(acc.right, r.right), max(acc.bottom, r.bottom)) }
                        drawRoundRect(
                            Palette.Accent, u.topLeft - Offset(6f, 6f), Size(u.width + 12f, u.height + 12f),
                            CornerRadius(8f), alpha = flashAlpha, style = StrokeStyle(4f),
                        )
                    }
                    for (s in project.strokes) if (s.page == page) drawInk(s.points.map(::px), Color(s.color), s.width * w, s.pressures)
                    if (live.size > 1) drawInk(live.map(::px), Color(ui.inkColor), 0.0035f * w, penPressures.takeIf { it.size == live.size }.orEmpty())
                    drag?.let { (s, e) ->
                        val tl = Offset(min(s.x, e.x), min(s.y, e.y))
                        val sz = Size(abs(s.x - e.x), abs(s.y - e.y))
                        drawRect(Palette.Accent, tl, sz, alpha = 0.12f)
                        drawRect(Palette.Accent, tl, sz, style = StrokeStyle(3f))
                    }
                    drawContent()
                },
        ) {
            // margin markers link back to workspace cards and linked handwriting
            val markerSize = 26.dp
            project.tethers.forEach { t ->
                val h = project.highlight(t.highlight) ?: return@forEach
                val a = h.anchor
                if (a.page != page) return@forEach
                val top = a.rects.minOf { it.top }
                if (top < f0 || top >= f1) return@forEach
                Box(
                    Modifier.offset {
                        IntOffset((w - markerSize.toPx() - 6.dp.toPx()).roundToInt(), ((top - f0) * pageH).roundToInt() - 2)
                    }.size(markerSize).shadow(3.dp, CircleShape).clip(CircleShape)
                        .background(Color(h.color)).clickable { ui.revealInk(t) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Draw, "Show linked notes", tint = Palette.Ink, modifier = Modifier.size(14.dp)) }
            }
            project.cards.forEach { c ->
                val a = c.anchor ?: return@forEach
                if (a.page != page) return@forEach
                val top = a.rects.minOf { it.top }
                if (top < f0 || top >= f1) return@forEach
                val note = c.kind == Kind.Note
                Box(
                    Modifier.offset {
                        IntOffset((w - markerSize.toPx() - 6.dp.toPx()).roundToInt(), ((top - f0) * pageH).roundToInt() - 2)
                    }.size(markerSize).shadow(3.dp, CircleShape).clip(CircleShape)
                        .background(if (note) Palette.Note else Palette.Accent)
                        .clickable { ui.reveal(c) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (note) Icons.Outlined.ChatBubble else Icons.AutoMirrored.Outlined.CallMade, "Show in workspace",
                        tint = if (note) Palette.Ink else Color.White, modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawSlice(img: ImageBitmap, f0: Float, f1: Float) {
    val srcY = (f0 * img.height).roundToInt().coerceIn(0, img.height - 1)
    val srcH = ((f1 - f0) * img.height).roundToInt().coerceIn(1, img.height - srcY)
    drawImage(
        img, srcOffset = IntOffset(0, srcY), srcSize = IntSize(img.width, srcH),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.High,
    )
}

fun DrawScope.drawInk(points: List<Offset>, color: Color, width: Float, pressures: List<Float> = emptyList()) {
    if (points.size < 2) return
    if (pressures.size == points.size) {
        for (i in 1 until points.size) {
            val w = width * (0.35f + 1.3f * (pressures[i - 1] + pressures[i]) / 2f)
            drawLine(color, points[i - 1], points[i], w, cap = StrokeCap.Round)
        }
        return
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val m = (points[i - 1] + points[i]) / 2f
            quadraticTo(points[i - 1].x, points[i - 1].y, m.x, m.y)
        }
        lineTo(points.last().x, points.last().y)
    }
    drawPath(path, color, style = StrokeStyle(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun ScrollMarks(ui: ReaderUi, modifier: Modifier) {
    val n = ui.doc.pageCount.toFloat()
    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { o -> ui.scrollTo(Anchor((o.y / size.height * n).toInt().coerceIn(0, ui.doc.pageCount - 1), listOf(Rect(0f, 0f, 1f, 0.05f)))) }
        },
    ) {
        val x = size.width / 2
        drawLine(Palette.Hairline, Offset(x, 0f), Offset(x, size.height), 2f)
        fun tick(pos: Float, c: Color, wide: Float) {
            val y = pos / n * size.height
            drawLine(c, Offset(x - wide, y), Offset(x + wide, y), 5f, cap = StrokeCap.Round)
        }
        ui.hits.forEach { tick(it.top, Palette.SearchHit, 6f) }
        ui.project.highlights.forEach { tick(it.anchor.top, Color(it.color), 5f) }
        ui.project.cards.forEach { c -> c.anchor?.let { tick(it.top, if (c.kind == Kind.Note) Color(0xFFE0B400) else Palette.Accent, 3f) } }
    }
}

val PointerInputChange.isPen get() = type == PointerType.Stylus || type == PointerType.Eraser

// true while the current pen gesture started with the stylus eraser end
var currentEventIsEraser = false
    private set

// follows one pointer from down to up; accept decides whether this gesture is ours
suspend fun PointerInputScope.trackPointer(
    accept: (PointerInputChange) -> Boolean,
    onStart: (Offset, Float) -> Unit,
    onMove: (Offset, Float) -> Unit,
    onEnd: () -> Unit,
    pass: PointerEventPass = PointerEventPass.Main,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
    if (!accept(down)) return@awaitEachGesture
    currentEventIsEraser = down.type == PointerType.Eraser
    down.consume()
    onStart(down.position, down.pressure)
    while (true) {
        val ev = awaitPointerEvent(pass)
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
        if (!ch.pressed) break
        onMove(ch.position, ch.pressure)
        ch.consume()
    }
    onEnd()
}
