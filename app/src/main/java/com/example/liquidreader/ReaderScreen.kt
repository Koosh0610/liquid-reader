package com.example.liquidreader

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs

enum class Tool { Select, Box, Pen, Eraser }

class Selection(val page: Int, val text: String, val rects: List<Rect>, val highlight: Highlight? = null)

data class SliceInfo(val page: Int, val f0: Float, val f1: Float, val bounds: Rect)

data class Ghost(val text: String, val pos: Offset)

enum class Pane { Doc, Ws }

// a passage and the workspace area it's connected to, in canvas coordinates
data class LinkedPair(val anchor: Anchor, val target: Rect, val color: Int, val arrow: Boolean)

class ReaderUi(val project: Project, val doc: PdfDoc, private val scope: CoroutineScope) {
    var tool by mutableStateOf(Tool.Select)
    var color by mutableIntStateOf(Palette.highlighters[0])
    var inkColor by mutableIntStateOf(Palette.inks[0])
    var selection by mutableStateOf<Selection?>(null)
    var selecting by mutableStateOf(false)
    var selectedCard by mutableStateOf<Long?>(null)
    var editCard by mutableStateOf<Long?>(null)
    var flash by mutableStateOf<Anchor?>(null)
    var pulse by mutableStateOf<Long?>(null)
    var ghost by mutableStateOf<Ghost?>(null)
    val list = LazyListState()

    // stylus: once a pen has touched the screen, fingers only scroll and zoom
    var stylusSeen by mutableStateOf(false)
    var follow by mutableStateOf(true)
    var leader = Pane.Doc
    var draggingCard = false

    // text -> ink linking in progress, and ink selected for linking the other way
    var pending by mutableStateOf<Tether?>(null)
    var selectedStrokes by mutableStateOf<List<Long>>(emptyList())
    var linkingStrokes by mutableStateOf<List<Long>?>(null)

    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<Anchor>>(emptyList())
    var hitIndex by mutableIntStateOf(0)

    // temporary collapses for HighlightView and search, layered over the saved ones
    var focus by mutableStateOf<List<Range>?>(null)
    var focusLabel by mutableStateOf("")
    val collapses: List<Range> get() = focus ?: project.collapses

    val slices = mutableMapOf<Pair<Int, Float>, SliceInfo>()
    val cardSizes = mutableStateMapOf<Long, IntSize>()
    var docRect = Rect.Zero
    var wsRect = Rect.Zero
    var pageWidthPx = 1f
    var pageTopPx = 0f

    val anchorsWithCards get() = project.cards.mapNotNull { it.anchor }.toSet()

    fun toCanvas(root: Offset) = (root - wsRect.topLeft - project.pan) / project.zoom

    fun select(page: Int, a: Offset, b: Offset) {
        selection = doc.select(page, a, b)?.let { Selection(page, it.first, it.second) }
    }

    fun tapDoc(page: Int, n: Offset) {
        val h = project.highlights.lastOrNull { it.anchor.page == page && it.anchor.rects.any { r -> r.inflate(0.004f).contains(n) } }
        selectedCard = null
        if (h == null) {
            selection = null
            return
        }
        val card = project.cards.firstOrNull { it.anchor == h.anchor }
        if (card != null) {
            selection = null
            reveal(card)
        } else selection = Selection(page, h.text, h.anchor.rects, h)
    }

    fun highlight(sel: Selection, c: Int = color): Highlight {
        sel.highlight?.let { it.color = c; return it }
        return Highlight(project.nextId(), Anchor(sel.page, sel.rects), c, sel.text).also { project.highlights += it }
    }

    // first spot in the visible area, scanning down then across, that doesn't overlap a card
    private fun spot(h: Float = 260f): Offset {
        val origin = toCanvas(wsRect.topLeft + Offset(48f, 48f))
        val w = 720f
        val bottom = toCanvas(wsRect.bottomLeft).y - h
        for (col in 0 until 4) {
            var y = origin.y
            while (y < bottom) {
                val r = Rect(origin.x + col * (w + 40f), y, origin.x + col * (w + 40f) + w, y + h)
                val hit = project.cards.mapNotNull { cardRect(it.id) }.firstOrNull { it.overlaps(r) }
                if (hit == null) return r.topLeft
                y = hit.bottom + 32f
            }
        }
        return origin + Offset(24f, 24f) * (project.cards.size % 8).toFloat()
    }

    fun excerpt(sel: Selection, at: Offset? = null) {
        val h = highlight(sel, sel.highlight?.color ?: color)
        val pos = at ?: spot()
        val card = Card(project.nextId(), Kind.Excerpt, h.text, h.color, h.anchor, pos.x, pos.y)
        project.cards += card
        selection = null
        selectedCard = card.id
        pulseCard(card.id)
    }

    fun comment(sel: Selection) {
        val h = highlight(sel, sel.highlight?.color ?: color)
        val pos = spot()
        val note = Card(project.nextId(), Kind.Note, "", Palette.Note.toArgb(), h.anchor, pos.x, pos.y)
        project.cards += note
        selection = null
        selectedCard = note.id
        editCard = note.id
    }

    fun newNote(at: Offset) {
        val note = Card(project.nextId(), Kind.Note, "", Palette.Note.toArgb(), null, at.x, at.y)
        project.cards += note
        selectedCard = note.id
        editCard = note.id
    }

    fun boxExcerpt(page: Int, r: Rect) = scope.launch {
        val id = project.nextId()
        val name = "img_$id.png"
        val ok = withContext(Dispatchers.IO) {
            doc.crop(page, r)?.let { b -> File(project.dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } == true
        }
        if (!ok) return@launch
        val anchor = Anchor(page, listOf(r))
        project.highlights += Highlight(project.nextId(), anchor, color)
        val aspect = r.height * doc.aspect(page) / r.width
        val pos = spot(600f * aspect + 120f)
        project.cards += Card(id, Kind.Image, "", color, anchor, pos.x, pos.y, name)
        selectedCard = id
        pulseCard(id)
    }

    fun eraseDoc(page: Int, n: Offset) {
        project.removeStrokes { it.page == page && it.points.any { p -> (p - n).getDistance() < 0.015f } }
        val linked = anchorsWithCards
        project.highlights.filter { it.anchor.page == page && it.anchor !in linked && it.anchor.rects.any { r -> r.contains(n) } }
            .forEach { project.removeHighlight(it) }
    }

    fun eraseCanvas(pt: Offset) {
        project.removeStrokes { it.page == -1 && it.points.any { p -> (p - pt).getDistance() < 18f / project.zoom } }
    }

    // maps a root y coordinate to a document position, snapping to the nearest visible slice
    fun docPos(y: Float): Float? = slices.values.filter {
        it.bounds.height > 0f && it.bounds.bottom > docRect.top && it.bounds.top < docRect.bottom
    }.minByOrNull {
        when {
            y < it.bounds.top -> it.bounds.top - y
            y > it.bounds.bottom -> y - it.bounds.bottom
            else -> 0f
        }
    }?.let { s -> s.page + s.f0 + ((y - s.bounds.top) / s.bounds.height).coerceIn(0f, 1f) * (s.f1 - s.f0) }

    fun collapseBetween(y1: Float, y2: Float) {
        val a = docPos(y1) ?: return
        val b = docPos(y2) ?: return
        if (b - a < 0.02f) return
        focus = null
        val merged = mergeRanges(project.collapses + (a..b))
        project.collapses.clear()
        project.collapses += merged
        selection = null
        // keep the start of the collapse under the upper finger
        val page = a.toInt()
        val offset = (a - page) * pageWidthPx * doc.aspect(page) + pageTopPx - (y1 - docRect.top)
        scope.launch { list.scrollToItem(page, offset.roundToInt().coerceAtLeast(0)) }
    }

    fun expandBetween(y1: Float, y2: Float) {
        val a = docPos(y1) ?: return
        val b = docPos(y2) ?: return
        if (focus != null) focus = focus?.filterNot { it.start in a..b }
        else project.collapses.removeAll { it.start in a..b }
    }

    fun expand(r: Range) {
        if (focus != null) focus = focus?.minus(r) else project.collapses.remove(r)
    }

    fun expandAll() {
        focus = null
        project.collapses.clear()
    }

    fun toggleHighlightView() {
        if (focus != null && focusLabel == "Highlights") {
            focus = null
            return
        }
        val keep = project.highlights.map { it.anchor.top..it.anchor.bottom }
        if (keep.isEmpty()) return
        focusLabel = "Highlights"
        focus = complement(keep, doc.pageCount.toFloat(), 0.03f)
    }

    fun focusHits() {
        if (hits.isEmpty()) return
        focusLabel = "Search results"
        focus = complement(hits.map { it.top..it.bottom }, doc.pageCount.toFloat(), 0.04f)
    }

    fun startLink(sel: Selection) {
        val h = highlight(sel, sel.highlight?.color ?: color)
        selection = null
        selectedStrokes = emptyList()
        pending = project.tethers.firstOrNull { it.highlight == h.id } ?: Tether(project.nextId(), h.id)
    }

    private fun addToPending(strokes: List<Long> = emptyList(), cards: List<Long> = emptyList()) {
        val t = pending ?: return
        t.strokes = (t.strokes + strokes).distinct()
        t.cards = (t.cards + cards).distinct()
        if (t !in project.tethers && !t.isEmpty) project.tethers += t
    }

    fun finishLink() {
        pending = null
    }

    fun addStroke(s: Stroke) {
        project.strokes += s
        if (s.page == -1) addToPending(strokes = listOf(s.id))
    }

    fun tapCardWhileLinking(c: Card): Boolean {
        if (pending == null) return false
        addToPending(cards = listOf(c.id))
        return true
    }

    // tapping ink selects the whole piece of handwriting it belongs to
    fun tapInk(pt: Offset): Boolean {
        val hit = project.strokes.lastOrNull { s ->
            s.page == -1 && s.bounds.inflate(24f).contains(pt) &&
                s.points.any { (it - pt).getDistance() < 28f / project.zoom }
        } ?: return false
        val group = cluster(project.strokes.filter { it.page == -1 }, hit, 36f).map { it.id }
        if (pending != null) addToPending(strokes = group)
        else {
            selectedStrokes = group
            selectedCard = null
        }
        return true
    }

    fun linkSelectedInk() {
        linkingStrokes = selectedStrokes
        selectedStrokes = emptyList()
    }

    // called when a text selection gesture ends; completes ink -> text linking
    fun selectionDone() {
        selecting = false
        val ink = linkingStrokes ?: return
        val sel = selection ?: return
        val h = highlight(sel, sel.highlight?.color ?: color)
        val t = project.tethers.firstOrNull { it.highlight == h.id }
        if (t != null) t.strokes = (t.strokes + ink).distinct()
        else project.tethers += Tether(project.nextId(), h.id, ink)
        linkingStrokes = null
        selection = null
    }

    fun inkBoundsOf(ids: Collection<Long>): Rect? {
        val set = ids.toSet()
        val boxes = project.strokes.filter { it.id in set }.map { it.bounds }
        if (boxes.isEmpty()) return null
        return boxes.reduce { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
    }

    private fun tetherBounds(t: Tether): Rect? {
        val boxes = listOfNotNull(inkBoundsOf(t.strokes)) + t.cards.mapNotNull { cardRect(it) }
        if (boxes.isEmpty()) return null
        return boxes.reduce { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
    }

    fun linkedPairs(): List<LinkedPair> =
        project.tethers.mapNotNull { t ->
            val h = project.highlight(t.highlight) ?: return@mapNotNull null
            tetherBounds(t)?.let { LinkedPair(h.anchor, it, h.color, arrow = true) }
        } + project.cards.mapNotNull { c ->
            val a = c.anchor ?: return@mapNotNull null
            cardRect(c.id)?.let { LinkedPair(a, it, c.color, arrow = c.kind == Kind.Note) }
        }

    // root coordinates of the top of an anchor and the right edge of its page, if laid out
    fun anchorRoot(a: Anchor): Offset? {
        val f = a.rects.minOf { it.top }
        val s = slices.values.firstOrNull { it.page == a.page && f >= it.f0 && f <= it.f1 && it.bounds.height > 0f } ?: return null
        return Offset(s.bounds.right, s.bounds.top + (f - s.f0) / (s.f1 - s.f0) * s.bounds.height)
    }

    fun toScreen(c: Offset) = wsRect.topLeft + project.pan + c * project.zoom

    // each frame, nudge the pane that isn't being touched so linked notes sit level with their passage
    suspend fun followStep() {
        if (!follow || selecting || ghost != null || draggingCard) return
        val pairs = linkedPairs()
        if (pairs.isEmpty()) return
        if (leader == Pane.Doc) {
            val focus = docRect.top + docRect.height * 0.35f
            val (p, a) = pairs.mapNotNull { p -> anchorRoot(p.anchor)?.takeIf { it.y in docRect.top..docRect.bottom }?.let { p to it } }
                .minByOrNull { abs(it.second.y - focus) } ?: return
            val dy = a.y - toScreen(p.target.topLeft).y
            var dx = 0f
            if (!project.notebook) {
                val l = toScreen(p.target.topLeft).x
                val r = toScreen(p.target.bottomRight).x
                if (l < wsRect.left + 16f || r > wsRect.right - 16f) dx = (wsRect.left + 48f) - l
            }
            if (abs(dy) > 0.5f || abs(dx) > 0.5f) {
                val y = project.pan.y + dy * 0.2f
                project.pan = Offset(project.pan.x + dx * 0.2f, if (project.notebook) min(y, NOTEBOOK_TOP) else y)
            }
        } else {
            val focus = wsRect.top + wsRect.height * 0.35f
            val p = pairs.filter { toScreen(it.target.topLeft).y in wsRect.top..wsRect.bottom }
                .minByOrNull { abs(toScreen(it.target.topLeft).y - focus) } ?: return
            val ty = toScreen(p.target.topLeft).y
            val a = anchorRoot(p.anchor)
            if (a == null) {
                val off = (p.anchor.top - p.anchor.page) * pageWidthPx * doc.aspect(p.anchor.page) + pageTopPx - (ty - docRect.top)
                list.scrollToItem(p.anchor.page, off.roundToInt().coerceAtLeast(0))
                return
            }
            val dy = a.y - ty
            if (abs(dy) > 0.5f) list.dispatchRawDelta(dy * 0.2f)
        }
    }

    fun scrollTo(a: Anchor) {
        val pos = a.top
        if (collapses.any { pos in it }) {
            focus = null
            project.collapses.removeAll { pos in it }
        }
        leader = Pane.Doc
        scope.launch {
            val offset = ((a.top - a.page) * pageWidthPx * doc.aspect(a.page) - 160f).coerceAtLeast(0f)
            list.animateScrollToItem(a.page, offset.roundToInt())
            flash = a
            delay(1600)
            if (flash == a) flash = null
        }
    }

    fun reveal(c: Card) {
        val size = cardSizes[c.id] ?: IntSize(600, 200)
        val z = project.zoom
        val target = Offset(wsRect.width / 2, wsRect.height / 2) - Offset(c.x + size.width / 2f, c.y + size.height / 2f) * z
        val start = project.pan
        leader = Pane.Ws
        selectedCard = c.id
        scope.launch { animate(0f, 1f, animationSpec = tween(450)) { v, _ -> project.pan = lerp(start, target, v) } }
        pulseCard(c.id)
    }

    private fun pulseCard(id: Long) = scope.launch {
        pulse = id
        delay(900)
        if (pulse == id) pulse = null
    }

    fun search(q: String) = scope.launch {
        query = q
        delay(250)
        val found = withContext(Dispatchers.IO) { doc.search(q) }
        if (query != q) return@launch
        hits = found
        hitIndex = 0
        found.firstOrNull()?.let { scrollTo(it) }
    }

    fun step(d: Int) {
        if (hits.isEmpty()) return
        hitIndex = (hitIndex + d).mod(hits.size)
        scrollTo(hits[hitIndex])
    }
}

@Composable
fun ReaderScreen(project: Project, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val doc = remember(project) { PdfDoc(project.pdf) }
    val ui = remember(project) { ReaderUi(project, doc, scope) }

    DisposableEffect(project) {
        onDispose {
            project.save()
            doc.close()
        }
    }
    LaunchedEffect(project) {
        snapshotFlow { project.toJson().toString() }.drop(1).collectLatest { json ->
            delay(600)
            withContext(Dispatchers.IO) { File(project.dir, "project.json").writeText(json) }
        }
    }
    LaunchedEffect(ui) {
        while (true) {
            withFrameNanos { }
            ui.followStep()
        }
    }
    BackHandler {
        when {
            ui.pending != null -> ui.finishLink()
            ui.linkingStrokes != null -> ui.linkingStrokes = null
            ui.selection != null -> ui.selection = null
            ui.searchOpen -> ui.searchOpen = false
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Chrome)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(ui, onBack)
            if (ui.searchOpen) SearchBar(ui)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val total = constraints.maxWidth.toFloat()
                val density = LocalDensity.current
                Row(Modifier.fillMaxSize()) {
                    DocumentPane(ui, Modifier.width(with(density) { (total * project.split).toDp() }).fillMaxHeight())
                    SplitHandle { dx -> project.split = ((project.split * total + dx) / total).coerceIn(0.3f, 0.8f) }
                    Workspace(ui, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        LinkArrows(ui)
        ui.selection?.takeIf { !ui.selecting && ui.linkingStrokes == null }?.let { SelectionPopup(ui, it) }
        ui.ghost?.let { GhostCard(it) }
    }
}

@Composable
private fun TopBar(ui: ReaderUi, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(Palette.Chrome).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Library", tint = Palette.Ink) }
        Text(
            ui.project.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White).border(1.dp, Palette.Hairline, RoundedCornerShape(22.dp)).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Icons.Outlined.FormatColorText, "Select text", ui.tool == Tool.Select) { ui.tool = Tool.Select }
            ToolButton(Icons.Outlined.Crop, "Region excerpt", ui.tool == Tool.Box) { ui.tool = Tool.Box }
            ToolButton(Icons.Outlined.Draw, "Pen", ui.tool == Tool.Pen) { ui.tool = Tool.Pen }
            ToolButton(Icons.Outlined.CleaningServices, "Eraser", ui.tool == Tool.Eraser) { ui.tool = Tool.Eraser }
            Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(24.dp).background(Palette.Hairline))
            val pen = ui.tool == Tool.Pen
            (if (pen) Palette.inks else Palette.highlighters).forEach { c ->
                val selected = if (pen) ui.inkColor == c else ui.color == c
                Swatch(c, selected, 22) { if (pen) ui.inkColor = c else ui.color = c }
            }
        }
        Spacer(Modifier.weight(1f))
        val highlightsOnly = ui.focus != null && ui.focusLabel == "Highlights"
        ToolButton(Icons.Outlined.Highlight, "Show highlights only", highlightsOnly) { ui.toggleHighlightView() }
        if (ui.collapses.isNotEmpty()) ToolButton(Icons.Outlined.UnfoldMore, "Expand all", false) { ui.expandAll() }
        ToolButton(Icons.Outlined.Sync, "Follow links while scrolling", ui.follow) { ui.follow = !ui.follow }
        ToolButton(Icons.Outlined.Search, "Search", ui.searchOpen) { ui.searchOpen = !ui.searchOpen }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
}

@Composable
fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(2.dp).size(40.dp).clip(CircleShape)
            .background(if (selected) Palette.Accent.copy(alpha = 0.13f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (selected) Palette.Accent else Palette.Ink, modifier = Modifier.size(22.dp)) }
}

@Composable
fun Swatch(color: Int, selected: Boolean, sizeDp: Int, ring: Color = Palette.Ink, onClick: () -> Unit) {
    Box(
        Modifier.padding(3.dp).size(sizeDp.dp).clip(CircleShape)
            .border(if (selected) 2.dp else 0.dp, if (selected) ring else Color.Transparent, CircleShape)
            .padding(if (selected) 3.dp else 0.dp).clip(CircleShape)
            .background(Color(color)).clickable(onClick = onClick),
    )
}

@Composable
private fun SearchBar(ui: ReaderUi) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(Palette.Chrome).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)
                .border(1.dp, Palette.Hairline, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = Palette.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (ui.query.isEmpty()) Text("Search document", color = Palette.Muted, fontSize = 15.sp)
                BasicTextField(
                    ui.query, { ui.search(it) }, singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = Palette.Ink), cursorBrush = SolidColor(Palette.Accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            when {
                ui.query.isBlank() -> ""
                ui.hits.isEmpty() -> "No matches"
                else -> "${ui.hitIndex + 1} of ${ui.hits.size}"
            },
            color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.widthIn(min = 80.dp),
        )
        IconButton(onClick = { ui.step(-1) }) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous") }
        IconButton(onClick = { ui.step(1) }) { Icon(Icons.Outlined.KeyboardArrowDown, "Next") }
        ToolButton(Icons.Outlined.UnfoldLess, "Collapse to results", ui.focus != null && ui.focusLabel == "Search results") {
            if (ui.focus != null && ui.focusLabel == "Search results") ui.focus = null else ui.focusHits()
        }
        IconButton(onClick = { ui.searchOpen = false; ui.query = ""; ui.hits = emptyList(); if (ui.focusLabel == "Search results") ui.focus = null }) {
            Icon(Icons.Outlined.Close, "Close search")
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
}

@Composable
private fun SplitHandle(onDrag: (Float) -> Unit) {
    Box(
        Modifier.width(14.dp).fillMaxHeight().background(Palette.Chrome)
            .pointerInput(Unit) { detectHorizontalDragGestures { change, dx -> change.consume(); onDrag(dx) } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).width(1.dp).fillMaxHeight().background(Palette.Hairline))
        Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight().background(Palette.Hairline))
        Box(Modifier.width(4.dp).height(40.dp).clip(CircleShape).background(Palette.Muted.copy(alpha = 0.5f)))
    }
}

@Composable
private fun SelectionPopup(ui: ReaderUi, sel: Selection) {
    val slice = ui.slices.values.firstOrNull { it.page == sel.page && sel.rects.first().top in it.f0..it.f1 } ?: return
    val first = sel.rects.first()
    val density = LocalDensity.current
    val y = slice.bounds.top + (first.top - slice.f0) / (slice.f1 - slice.f0) * slice.bounds.height
    val x = slice.bounds.left + first.left * slice.bounds.width
    val clipboard = LocalClipboardManager.current
    var handle: LayoutCoordinates? = null
    val popupH = with(density) { 52.dp.toPx() }
    val top = if (y - popupH - 12f > ui.docRect.top) y - popupH - 12f else y + with(density) { 40.dp.toPx() }

    Row(
        Modifier.offset { IntOffset(x.coerceIn(ui.docRect.left + 8f, ui.docRect.right - with(density) { 520.dp.toPx() }).coerceAtLeast(8f).roundToInt(), top.roundToInt()) }
            .shadow(12.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(Palette.Pill)
            .height(52.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Palette.highlighters.forEach { c ->
            Swatch(c, sel.highlight?.color == c, 24, ring = Color.White) {
                ui.color = c
                ui.highlight(sel, c)
                ui.selection = null
            }
        }
        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(26.dp).background(Color.White.copy(alpha = 0.2f)))
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Palette.Accent)
                .onGloballyPositioned { handle = it }
                .pointerInput(sel) {
                    detectDragGestures(
                        onDragStart = { o -> handle?.let { ui.ghost = Ghost(sel.text, it.localToRoot(o)) } },
                        onDrag = { change, d -> change.consume(); ui.ghost = ui.ghost?.let { it.copy(pos = it.pos + d) } },
                        onDragEnd = {
                            val g = ui.ghost
                            ui.ghost = null
                            if (g != null && ui.wsRect.contains(g.pos)) ui.excerpt(sel, ui.toCanvas(g.pos) - Offset(40f, 30f))
                        },
                        onDragCancel = { ui.ghost = null },
                    )
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.OpenWith, "Drag to workspace", tint = Color.White, modifier = Modifier.size(22.dp)) }
        PillButton("Excerpt") { ui.excerpt(sel) }
        PillIcon(Icons.Outlined.Link, "Link to notes") { ui.startLink(sel) }
        PillIcon(Icons.Outlined.ChatBubbleOutline, "Comment") { ui.comment(sel) }
        PillIcon(Icons.Outlined.ContentCopy, "Copy") { clipboard.setText(AnnotatedString(sel.text)); ui.selection = null }
        sel.highlight?.let { h ->
            PillIcon(Icons.Outlined.Delete, "Remove highlight") { ui.project.removeHighlight(h); ui.selection = null }
        }
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PillIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun GhostCard(g: Ghost) {
    Box(
        Modifier.offset { IntOffset(g.pos.x.roundToInt() - 40, g.pos.y.roundToInt() - 30) }
            .width(240.dp).shadow(16.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
            .background(Color.White).border(2.dp, Palette.Accent, RoundedCornerShape(8.dp)).padding(12.dp),
    ) {
        Text(g.text, fontFamily = FontFamily.Serif, fontSize = 13.sp, color = Palette.Ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

// arrows across the divider from each linked passage to its notes; drawn only while both ends are on screen
@Composable
private fun LinkArrows(ui: ReaderUi) {
    Canvas(Modifier.fillMaxSize()) {
        // reading these makes the overlay redraw as either pane moves
        ui.list.firstVisibleItemScrollOffset
        ui.list.firstVisibleItemIndex
        ui.project.pan
        ui.project.zoom
        for (p in ui.linkedPairs()) {
            if (!p.arrow) continue
            val a = ui.anchorRoot(p.anchor) ?: continue
            val b = ui.toScreen(Offset(p.target.left, p.target.top + 18f / ui.project.zoom))
            if (a.y !in ui.docRect.top..ui.docRect.bottom || b.y !in ui.wsRect.top..ui.wsRect.bottom || b.x < ui.wsRect.left + 8f) continue
            val start = Offset(a.x - 4f, a.y + 10f)
            val end = Offset(b.x - 10f, b.y)
            val dx = (end.x - start.x) * 0.5f
            val c = Color(p.color).let { Color(it.red * 0.7f, it.green * 0.7f, it.blue * 0.7f) }
            drawPath(
                Path().apply { moveTo(start.x, start.y); cubicTo(start.x + dx, start.y, end.x - dx, end.y, end.x, end.y) },
                c, style = StrokeStyle(4f, cap = StrokeCap.Round),
            )
            drawCircle(c, 7f, start)
            drawPath(
                Path().apply { moveTo(end.x + 2f, end.y); lineTo(end.x - 16f, end.y - 10f); lineTo(end.x - 16f, end.y + 10f); close() },
                c,
            )
        }
    }
}
