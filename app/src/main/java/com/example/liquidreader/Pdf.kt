package com.example.liquidreader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.PageSelection
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.Closeable
import java.io.File
import kotlin.math.abs

// PdfRenderer allows one open page at a time, so every call is synchronized
@SuppressLint("NewApi")
class PdfDoc(file: File) : Closeable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    val pageCount = renderer.pageCount
    private val sizes = (0 until pageCount).map { i -> renderer.openPage(i).use { Size(it.width.toFloat(), it.height.toFloat()) } }

    // text selection and search arrived in Android 15 / S extension 13
    val textSupported = SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

    private val cache = object : LruCache<Int, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    fun aspect(page: Int) = sizes[page].height / sizes[page].width

    fun cached(page: Int): ImageBitmap? = cache.get(page)?.asImageBitmap()

    @Volatile
    private var closed = false

    @Synchronized
    fun bitmap(page: Int): Bitmap? = if (closed) null else
        cache.get(page) ?: render(page, 1400).copy(Bitmap.Config.RGB_565, false).also { cache.put(page, it) }

    private fun render(page: Int, width: Int): Bitmap = renderer.openPage(page).use { p ->
        Bitmap.createBitmap(width, (width * p.height.toFloat() / p.width).toInt(), Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
            p.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    @Synchronized
    fun thumbnail(out: File) = out.outputStream().use { render(0, 400).compress(Bitmap.CompressFormat.PNG, 100, it) }

    fun crop(page: Int, r: Rect): Bitmap? = bitmap(page)?.let { b ->
        Bitmap.createBitmap(
            b, (r.left * b.width).toInt(), (r.top * b.height).toInt(),
            (r.width * b.width).toInt().coerceAtLeast(1), (r.height * b.height).toInt().coerceAtLeast(1),
        )
    }

    // a and b are normalized page points; returns the selected text and its line rects
    @Synchronized
    fun select(page: Int, a: Offset, b: Offset): Pair<String, List<Rect>>? {
        if (!textSupported || closed) return null
        val s = sizes[page]
        fun boundary(o: Offset) = SelectionBoundary(Point((o.x * s.width).toInt(), (o.y * s.height).toInt()))
        return renderer.openPage(page).use { p ->
            val raw = p.selectContent(boundary(a), boundary(b)) ?: return null
            val sel = wholeWords(p, raw) ?: raw
            val parts = sel.selectedTextContents
            val text = parts.joinToString("") { it.text }.trim()
            if (text.isEmpty()) null else text to parts.flatMap { it.bounds }.map { it.norm(s) }
        }
    }

    // reselects so the range never starts or ends mid-word; the selected text can occur
    // several times on a page, so keep the occurrence nearest the original selection
    private fun wholeWords(p: PdfRenderer.Page, raw: PageSelection): PageSelection? {
        val selected = raw.selectedTextContents.joinToString("") { it.text }
        val rawTop = raw.selectedTextContents.flatMap { it.bounds }.minOfOrNull { it.top } ?: return null
        if (selected.isBlank()) return null
        val text = p.textContents.joinToString("") { it.text }
        return generateSequence(text.indexOf(selected).takeIf { it >= 0 }) { prev ->
            text.indexOf(selected, prev + 1).takeIf { it >= 0 }
        }.take(8).mapNotNull { start ->
            var i = start
            var j = start + selected.length
            while (i > 0 && text[i - 1].isLetterOrDigit()) i--
            while (j < text.length && text[j].isLetterOrDigit()) j++
            p.selectContent(SelectionBoundary(i), SelectionBoundary(j))?.takeIf { it.selectedTextContents.isNotEmpty() }
        }.minByOrNull { s -> abs((s.selectedTextContents.flatMap { it.bounds }.minOfOrNull { it.top } ?: 0f) - rawTop) }
    }

    @Synchronized
    fun search(query: String): List<Anchor> {
        if (!textSupported || closed || query.isBlank()) return emptyList()
        return (0 until pageCount).flatMap { i ->
            renderer.openPage(i).use { p -> p.searchText(query).map { m -> Anchor(i, m.bounds.map { it.norm(sizes[i]) }) } }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        renderer.close()
        fd.close()
    }
}

private fun RectF.norm(s: Size) = Rect(left / s.width, top / s.height, right / s.width, bottom / s.height)
