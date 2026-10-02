package com.example.liquidreader

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// notebook pages are this many canvas units wide and start just below the pane's top edge
const val NOTEBOOK_WIDTH = 1000f
const val NOTEBOOK_TOP = 24f
private const val NOTEBOOK_MARGIN = 36f
private const val INK_WIDTH = 4f

@Composable
fun Workspace(ui: ReaderUi, modifier: Modifier) {
    val p = ui.project
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var livePressures by remember { mutableStateOf<List<Float>>(emptyList()) }
    var linking by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var paneWidth by remember { mutableFloatStateOf(0f) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // a notebook is a canvas locked to one page width that only scrolls vertically
    LaunchedEffect(p.notebook, paneWidth) {
        if (p.notebook && paneWidth > 0f) {
            p.zoom = (paneWidth - 2 * NOTEBOOK_MARGIN) / NOTEBOOK_WIDTH
            p.pan = Offset(NOTEBOOK_MARGIN, min(p.pan.y, NOTEBOOK_TOP))
        }
    }

    Box(
        modifier.clipToBounds()
            .background(if (p.notebook) Palette.NotebookBackdrop else Palette.Paper)
            .onSizeChanged { paneWidth = it.width.toFloat() }
            .onGloballyPositioned { ui.wsRect = it.boundsInRoot() }
            .drawBehind { drawPaper(p.paper, p.notebook, p.pan, p.zoom) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    ui.leader = Pane.Ws
                    if (down.isPen) ui.stylusSeen = true
                }
            }
            .pointerInput(ui.tool, ui.inkColor, p.notebook) {
                canvasGestures(
                    ui, scope,
                    onTap = { focusManager.clearFocus() },
                    onLive = { pts, pr -> live = pts; livePressures = pr },
                )
            },
    ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    translationX = p.pan.x
                    translationY = p.pan.y
                    scaleX = p.zoom
                    scaleY = p.zoom
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .drawBehind {
                    for (l in p.links) {
                        val a = ui.cardRect(l.a) ?: continue
                        val b = ui.cardRect(l.b) ?: continue
                        drawLink(a.center, b.center)
                    }
                    linking?.let { (a, b) -> drawLink(a, b, dashed = true) }
                    for (s in p.strokes) if (s.page == -1) drawInk(s.points, Color(s.color), s.width, s.pressures)
                    if (live.size > 1) drawInk(live, Color(ui.inkColor), INK_WIDTH, livePressures.takeIf { it.size == live.size }.orEmpty())
                    ui.inkBoundsOf(ui.selectedStrokes)?.let { selectionBox(it, Palette.Accent) }
                    ui.pending?.let { t -> ui.inkBoundsOf(t.strokes)?.let { selectionBox(it, Color(ui.project.highlight(t.highlight)?.color ?: 0)) } }
                },
        ) {
            p.cards.forEach { c ->
                key(c.id) { CardView(ui, c, onLinking = { linking = it }) }
            }
            ui.inkBoundsOf(ui.selectedStrokes)?.let { InkToolbar(ui, it) }
        }
        if (p.cards.isEmpty() && p.strokes.none { it.page == -1 }) EmptyHint(p.notebook, Modifier.align(Alignment.Center))
        ui.pending?.let { LinkBanner(ui, it, Modifier.align(Alignment.TopCenter).padding(top = 14.dp)) }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperMenu(ui)
            if (!p.notebook) Chip("${(p.zoom * 100).roundToInt()}%") { p.zoom = 1f; p.pan = Offset(40f, 40f) }
        }
    }
}

@Composable
private fun EmptyHint(notebook: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (notebook) Icons.Outlined.Edit else Icons.Outlined.TouchApp, null, tint = Palette.PaperDot, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            if (notebook) "Write here with a stylus, or drag text in" else "Select text in the document, then drag it here",
            color = Palette.Muted, fontSize = 15.sp,
        )
        Text("Double-tap for a typed note · link notes to passages with the link button", color = Palette.Muted.copy(alpha = 0.7f), fontSize = 13.sp)
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.shadow(4.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Color.White)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(label, fontSize = 13.sp, color = Palette.Muted) }
}

@Composable
private fun PaperMenu(ui: ReaderUi) {
    val p = ui.project
    var open by remember { mutableStateOf(false) }
    Box {
        Chip("${if (p.notebook) "Notebook" else "Canvas"} · ${p.paper.name}") { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            listOf(false to "Infinite canvas", true to "Notebook").forEach { (nb, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = { if (p.notebook == nb) Icon(Icons.Outlined.Check, null) },
                    onClick = {
                        if (!nb && p.notebook) p.zoom = 1f
                        p.notebook = nb
                        open = false
                    },
                )
            }
            HorizontalDivider()
            Paper.entries.forEach { paper ->
                DropdownMenuItem(
                    text = { Text(paper.name) },
                    trailingIcon = { if (p.paper == paper) Icon(Icons.Outlined.Check, null) },
                    onClick = { p.paper = paper; open = false },
                )
            }
        }
    }
}

@Composable
private fun LinkBanner(ui: ReaderUi, t: Tether, modifier: Modifier) {
    val h = ui.project.highlight(t.highlight)
    Row(
        modifier.shadow(10.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Palette.Pill)
            .padding(start = 16.dp, end = 6.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(h?.color ?: 0)))
        Spacer(Modifier.width(10.dp))
        Text(
            if (t.isEmpty) "Write, or tap ink and notes, to link “${h?.text?.take(28) ?: ""}…”" else "Linked ${t.strokes.size} strokes, ${t.cards.size} notes",
            color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(RoundedCornerShape(16.dp)).clickable { ui.finishLink() }.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Done", color = Color(0xFF8DB4FF), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun InkToolbar(ui: ReaderUi, bounds: Rect) {
    Row(
        Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.bottom.roundToInt() + 28) }
            .shadow(8.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
            .background(Palette.Pill).height(44.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(18.dp)).clickable { ui.linkSelectedInk() }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Link, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Link to text", color = Color.White, fontSize = 14.sp)
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable {
                val ids = ui.selectedStrokes.toSet()
                ui.project.removeStrokes { it.id in ids }
                ui.selectedStrokes = emptyList()
            },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Delete, "Delete", tint = Color.White, modifier = Modifier.size(19.dp)) }
    }
}

fun ReaderUi.cardRect(id: Long): Rect? {
    val c = project.card(id) ?: return null
    val s = cardSizes[id] ?: return null
    return Rect(c.x, c.y, c.x + s.width, c.y + s.height)
}

fun ReaderUi.revealInk(t: Tether) {
    val b = inkBoundsOf(t.strokes) ?: t.cards.firstNotNullOfOrNull { cardRect(it) } ?: return
    leader = Pane.Doc
    project.pan = Offset(
        if (project.notebook) project.pan.x else wsRect.width / 2 - b.center.x * project.zoom,
        min(wsRect.height * 0.35f - b.top * project.zoom, if (project.notebook) NOTEBOOK_TOP else Float.MAX_VALUE),
    )
}

private fun DrawScope.selectionBox(b: Rect, color: Color) {
    drawRoundRect(
        color, b.topLeft - Offset(14f, 14f), Size(b.width + 28f, b.height + 28f), CornerRadius(12f),
        style = StrokeStyle(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
    )
}

// paper scrolls with the canvas; in a notebook it's a single page with a red margin
private fun DrawScope.drawPaper(paper: Paper, notebook: Boolean, pan: Offset, zoom: Float) {
    val left = if (notebook) pan.x else 0f
    val right = if (notebook) pan.x + NOTEBOOK_WIDTH * zoom else size.width
    val top = if (notebook) maxOf(pan.y, 0f) else 0f
    if (notebook) {
        drawRect(Color.Black.copy(alpha = 0.06f), Offset(left - 2f, top), Size(right - left + 4f, size.height))
        drawRect(Color.White, Offset(left, top), Size(right - left, size.height))
    }
    fun eachLine(step: Float, from: Float, until: Float, origin: Float, block: (Float) -> Unit) {
        if (step < 6f) return
        var v = from + (origin - from).mod(step)
        while (v < until) {
            block(v)
            v += step
        }
    }
    when (paper) {
        Paper.Blank -> {}
        Paper.Ruled -> {
            val step = 56f * zoom
            eachLine(step, top, size.height, pan.y + 120f * zoom) { y -> drawLine(Color(0xFFCFE0F3), Offset(left, y), Offset(right, y), 2f) }
            if (notebook) drawLine(Color(0xFFF2B8B8), Offset(left + 96f * zoom, top), Offset(left + 96f * zoom, size.height), 2f)
        }
        Paper.Grid -> {
            val step = 40f * zoom
            eachLine(step, top, size.height, pan.y) { y -> drawLine(Color(0xFFE4E4EA), Offset(left, y), Offset(right, y), 1.5f) }
            eachLine(step, left, right, pan.x) { x -> drawLine(Color(0xFFE4E4EA), Offset(x, top), Offset(x, size.height), 1.5f) }
        }
        Paper.Dots -> {
            val step = 28f * zoom
            val r = (1.6f * zoom).coerceIn(1f, 2.4f)
            eachLine(step, left, right, pan.x) { x -> eachLine(step, top, size.height, pan.y) { y -> drawCircle(Palette.PaperDot, r, Offset(x, y)) } }
        }
    }
}

private fun DrawScope.drawLink(a: Offset, b: Offset, dashed: Boolean = false) {
    val dx = abs(b.x - a.x) * 0.5f + 40f
    val path = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(a.x + if (b.x >= a.x) dx else -dx, a.y, b.x - if (b.x >= a.x) dx else -dx, b.y, b.x, b.y)
    }
    val color = Palette.Accent.copy(alpha = 0.7f)
    drawPath(
        path, color,
        style = StrokeStyle(3f, cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null),
    )
    drawCircle(color, 6f, a)
    drawCircle(color, 6f, b)
}

// a stylus always writes (its eraser end erases); fingers pan, or draw only when no stylus has been seen.
// two fingers pan and zoom the canvas, or scroll the notebook
private suspend fun PointerInputScope.canvasGestures(
    ui: ReaderUi,
    scope: CoroutineScope,
    onTap: () -> Unit,
    onLive: (List<Offset>, List<Float>) -> Unit,
) {
    var lastTap = 0L
    var lastTapPos = Offset.Zero
    var fling: Job? = null
    awaitEachGesture {
        val down = awaitFirstDown()
        fling?.cancel()
        val p = ui.project
        fun canvas(o: Offset) = (o - p.pan) / p.zoom
        fun scrollBy(d: Offset) {
            p.pan = if (p.notebook) Offset(p.pan.x, min(p.pan.y + d.y, NOTEBOOK_TOP)) else p.pan + d
        }
        val pen = down.isPen
        val eraser = down.type == PointerType.Eraser || (ui.tool == Tool.Eraser && (pen || !ui.stylusSeen))
        val inks = !eraser && (pen || (ui.tool == Tool.Pen && !ui.stylusSeen))
        val pts = mutableListOf(canvas(down.position))
        val pressures = mutableListOf(down.pressure)
        val velocity = VelocityTracker()
        var multi = false
        var moved = false
        var ev: PointerEvent
        do {
            ev = awaitPointerEvent()
            val pressed = ev.changes.count { it.pressed }
            if (pressed >= 2 && !pen) {
                multi = true
                onLive(emptyList(), emptyList())
                if (p.notebook) scrollBy(ev.calculatePan())
                else {
                    val c = ev.calculateCentroid()
                    val z = (p.zoom * ev.calculateZoom()).coerceIn(0.3f, 2.5f)
                    p.pan = c - (c - p.pan) * (z / p.zoom) + ev.calculatePan()
                    p.zoom = z
                }
            } else if (!multi) {
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                velocity.addPosition(ch.uptimeMillis, ch.position)
                if (!moved && (ch.position - down.position).getDistance() > if (pen) 1f else viewConfiguration.touchSlop) moved = true
                if (moved) when {
                    inks -> { pts += canvas(ch.position); pressures += ch.pressure; onLive(pts.toList(), if (pen) pressures.toList() else emptyList()) }
                    eraser -> ui.eraseCanvas(canvas(ch.position))
                    else -> scrollBy(ch.positionChange())
                }
            }
            ev.changes.forEach { it.consume() }
        } while (ev.changes.any { it.pressed })

        onLive(emptyList(), emptyList())
        if (multi) return@awaitEachGesture
        if (moved && inks && pts.size > 1) {
            ui.addStroke(Stroke(p.nextId(), -1, pts.toList(), ui.inkColor, INK_WIDTH, if (pen) pressures.toList() else emptyList()))
            return@awaitEachGesture
        }
        if (moved && !inks && !eraser) {
            val v = velocity.calculateVelocity()
            fling = scope.launch {
                var lastX = 0f
                var lastY = 0f
                launch {
                    animateDecay(0f, v.y, FloatExponentialDecaySpec()) { value, _ -> scrollBy(Offset(0f, value - lastY)); lastY = value }
                }
                if (!p.notebook) animateDecay(0f, v.x, FloatExponentialDecaySpec()) { value, _ -> scrollBy(Offset(value - lastX, 0f)); lastX = value }
            }
            return@awaitEachGesture
        }
        if (moved) return@awaitEachGesture

        // a tap: eraser dabs, ink selection, double-tap for a note, otherwise clear selections
        onTap()
        val at = canvas(down.position)
        if (eraser) {
            ui.eraseCanvas(at)
            return@awaitEachGesture
        }
        if (ui.tapInk(at)) return@awaitEachGesture
        val t = down.uptimeMillis
        if (t - lastTap < 350 && (down.position - lastTapPos).getDistance() < 60f) {
            ui.newNote(at - Offset(40f, 20f))
            lastTap = 0
        } else {
            lastTap = t
            lastTapPos = down.position
            ui.selectedCard = null
            ui.selectedStrokes = emptyList()
            ui.selection = null
        }
    }
}

@Composable
private fun CardView(ui: ReaderUi, c: Card, onLinking: (Pair<Offset, Offset>?) -> Unit) {
    val p = ui.project
    val selected = ui.selectedCard == c.id
    val pulse by animateFloatAsState(if (ui.pulse == c.id) 1f else 0f, tween(300), label = "pulse")
    val note = c.kind == Kind.Note
    val linkedTo = ui.pending?.cards?.contains(c.id) == true

    Box(
        Modifier.offset { IntOffset(c.x.roundToInt(), c.y.roundToInt()) }
            .onSizeChanged { ui.cardSizes[c.id] = it }
            .graphicsLayer { scaleX = 1f + 0.05f * pulse; scaleY = 1f + 0.05f * pulse }
            .width(if (c.kind == Kind.Image) 300.dp else 270.dp)
            .pointerInput(c.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var dragging = false
                    var ev: PointerEvent
                    do {
                        ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!dragging && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                            ui.draggingCard = true
                            p.cards.remove(c)
                            p.cards.add(c)
                        }
                        if (dragging) {
                            val d = ch.positionChange()
                            c.x += d.x
                            c.y += d.y
                        }
                        ch.consume()
                    } while (ch.pressed)
                    ui.draggingCard = false
                    if (!dragging && !ui.tapCardWhileLinking(c)) {
                        ui.selectedCard = c.id
                        ui.selectedStrokes = emptyList()
                        ui.selection = null
                    }
                }
            },
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (note) Palette.Note else Color.White,
            shadowElevation = if (selected) 12.dp else 3.dp,
            border = when {
                linkedTo -> BorderStroke(2.dp, Color(p.highlight(ui.pending!!.highlight)?.color ?: 0))
                selected || pulse > 0f -> BorderStroke(2.dp, Palette.Accent)
                else -> BorderStroke(0.5.dp, Palette.Hairline)
            },
        ) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                if (!note) Box(Modifier.width(5.dp).fillMaxHeight().background(Color(c.color)))
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 6.dp)) {
                    when (c.kind) {
                        Kind.Excerpt -> Text(
                            c.text, fontFamily = FontFamily.Serif, fontSize = 14.sp, lineHeight = 20.sp,
                            color = Palette.Ink, maxLines = 14, overflow = TextOverflow.Ellipsis,
                        )
                        Kind.Image -> CardImage(File(p.dir, c.image ?: ""))
                        Kind.Note -> NoteField(ui, c)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (note) Icon(Icons.Outlined.DragIndicator, null, tint = Palette.Muted.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                        val a = c.anchor
                        Text(if (a != null) "p. ${a.page + 1}" else "Note", fontSize = 11.sp, color = Palette.Muted, modifier = Modifier.padding(start = 2.dp))
                        Spacer(Modifier.weight(1f))
                        if (a != null) Box(
                            Modifier.size(28.dp).clip(CircleShape).clickable { ui.scrollTo(a) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.AutoMirrored.Outlined.KeyboardReturn, "Go to source", tint = Palette.Accent, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
        if (selected) {
            CardToolbar(ui, c, Modifier.align(Alignment.TopStart).offset(y = (-54).dp))
            LinkBall(ui, c, onLinking, Modifier.align(Alignment.CenterEnd).offset(x = 12.dp))
        }
    }
}

@Composable
private fun NoteField(ui: ReaderUi, c: Card) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(ui.editCard) {
        if (ui.editCard == c.id) {
            focus.requestFocus()
            ui.editCard = null
        }
    }
    Box {
        if (c.text.isEmpty()) Text("Write a note…", fontSize = 14.sp, color = Palette.Muted)
        BasicTextField(
            c.text, { c.text = it },
            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = Palette.Ink),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

@Composable
private fun CardImage(file: File) {
    val img by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    img?.let {
        Image(
            it, null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(it.width / it.height.toFloat()).clip(RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun CardToolbar(ui: ReaderUi, c: Card, modifier: Modifier) {
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
            .background(Palette.Pill).height(44.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (c.kind != Kind.Note) Palette.highlighters.forEach { col ->
            Swatch(col, c.color == col, 20, ring = Color.White) {
                c.color = col
                ui.project.highlights.firstOrNull { it.anchor == c.anchor }?.color = col
            }
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable { ui.project.removeCard(c); ui.selectedCard = null },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Delete, "Delete", tint = Color.White, modifier = Modifier.size(19.dp)) }
    }
}

// drag the blue ball onto another card to connect them
@Composable
private fun LinkBall(ui: ReaderUi, c: Card, onLinking: (Pair<Offset, Offset>?) -> Unit, modifier: Modifier) {
    Box(
        modifier.size(24.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Palette.Accent)
            .border(3.dp, Color.White, CircleShape)
            .pointerInput(c.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val from = ui.cardRect(c.id)?.let { Offset(it.right, it.center.y) } ?: return@awaitEachGesture
                    var to = from
                    var ev: PointerEvent
                    do {
                        ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        to = from + (ch.position - down.position)
                        onLinking(from to to)
                        ch.consume()
                    } while (ch.pressed)
                    onLinking(null)
                    val target = ui.project.cards.lastOrNull { it.id != c.id && ui.cardRect(it.id)?.contains(to) == true }
                    if (target != null) {
                        val l = Link(c.id, target.id)
                        if (ui.project.links.none { (it.a == l.a && it.b == l.b) || (it.a == l.b && it.b == l.a) }) ui.project.links += l
                    }
                }
            },
    )
}
