package com.example.liquidreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                var open by remember { mutableStateOf<Project?>(null) }
                when (val p = open) {
                    null -> LibraryScreen(onOpen = { open = it })
                    else -> ReaderScreen(p, onBack = { open = null })
                }
            }
        }
    }
}
