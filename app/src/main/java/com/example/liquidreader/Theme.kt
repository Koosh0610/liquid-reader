package com.example.liquidreader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Backdrop = Color(0xFFE3E3E8)
    val Paper = Color(0xFFFBFAF7)
    val NotebookBackdrop = Color(0xFFECECF0)
    val PaperDot = Color(0xFFDCD8CF)
    val Chrome = Color(0xFFF6F6F8)
    val Hairline = Color(0xFFD5D5DC)
    val Accent = Color(0xFF2F6FED)
    val Ink = Color(0xFF1D1D1F)
    val Muted = Color(0xFF6E6E76)
    val Pill = Color(0xFF2B2B2F)
    val Note = Color(0xFFFFF4C2)
    val SearchHit = Color(0xFFFF8A00)

    val highlighters = listOf(0xFFFFD84D, 0xFF8BDB6A, 0xFF62B8FF, 0xFFFF86B4, 0xFFFFA953, 0xFFB79CFF).map { it.toInt() }
    val inks = listOf(0xFF1D1D1F, 0xFFE5484D, 0xFF2F6FED, 0xFF30A46C).map { it.toInt() }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = lightColorScheme(
        primary = Palette.Accent,
        background = Palette.Chrome,
        surface = Color.White,
        onSurface = Palette.Ink,
        surfaceVariant = Palette.Chrome,
    ),
    content = content,
)
