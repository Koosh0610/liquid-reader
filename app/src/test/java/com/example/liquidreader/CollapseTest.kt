package com.example.liquidreader

import org.junit.Assert.assertEquals
import org.junit.Test

class CollapseTest {
    @Test
    fun mergesOverlappingRanges() {
        assertEquals(listOf(0.5f..2f, 3f..4f), mergeRanges(listOf(1f..2f, 3f..4f, 0.5f..1.5f)))
    }

    @Test
    fun pageWithoutCollapsesIsOneSlice() {
        assertEquals(listOf(Seg.Visible(0f, 1f)), segments(2, listOf(0.2f..0.5f)))
    }

    @Test
    fun bandLivesInThePageWhereTheRangeStarts() {
        val r = 0.25f..2.5f
        assertEquals(listOf(Seg.Visible(0f, 0.25f), Seg.Band(r)), segments(0, listOf(r)))
        assertEquals(emptyList<Seg>(), segments(1, listOf(r)))
        assertEquals(listOf(Seg.Visible(0.5f, 1f)), segments(2, listOf(r)))
    }

    @Test
    fun rangeStartingOnPageBoundaryBelongsToThatPage() {
        assertEquals(listOf(Seg.Band(1f..1.5f), Seg.Visible(0.5f, 1f)), segments(1, listOf(1f..1.5f)))
    }

    @Test
    fun complementKeepsPaddedRanges() {
        assertEquals(listOf(0f..0.25f, 0.75f..2f), complement(listOf(0.375f..0.625f), 2f, 0.125f))
        assertEquals(listOf(0.5f..1f), complement(listOf(0f..0.25f), 1f, 0.25f))
    }
}

class ClusterTest {
    private fun stroke(id: Long, x: Float, y: Float) =
        Stroke(id, -1, listOf(androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x + 20f, y + 10f)), 0, 4f)

    @Test
    fun groupsNearbyStrokesTransitively() {
        val a = stroke(1, 0f, 0f)
        val b = stroke(2, 40f, 0f)
        val c = stroke(3, 80f, 5f)
        val far = stroke(4, 500f, 500f)
        assertEquals(setOf(1L, 2L, 3L), cluster(listOf(a, b, c, far), a, 30f).map { it.id }.toSet())
        assertEquals(listOf(4L), cluster(listOf(a, b, c, far), far, 30f).map { it.id })
    }
}
