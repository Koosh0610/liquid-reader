package com.example.liquidreader

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(onOpen: (Project) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>?>(null) }
    var importing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { projects = withContext(Dispatchers.IO) { Store.list(ctx) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            val p = withContext(Dispatchers.IO) { Store.create(ctx, uri) }
            importing = false
            onOpen(p)
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Chrome).systemBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp)) {
            Spacer(Modifier.height(36.dp))
            Text("Library", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            Text(
                projects?.let { "${it.size} project${if (it.size == 1) "" else "s"}" } ?: "",
                style = MaterialTheme.typography.bodyLarge, color = Palette.Muted,
            )
            Spacer(Modifier.height(24.dp))
            val list = projects
            if (list != null && list.isEmpty()) EmptyLibrary()
            else LazyVerticalGrid(
                columns = GridCells.Adaptive(180.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                items(list.orEmpty(), key = { it.dir.name }) { p ->
                    ProjectTile(p, onOpen = { onOpen(p) }, onDelete = {
                        Store.delete(p)
                        projects = projects?.minus(p)
                    })
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { if (!importing) picker.launch(arrayOf("application/pdf")) },
            icon = {
                if (importing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Outlined.Add, null)
            },
            text = { Text("Import PDF") },
            containerColor = Palette.Accent,
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp),
        )
    }
}

@Composable
private fun EmptyLibrary() {
    Column(Modifier.fillMaxSize().padding(bottom = 120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.AutoStories, null, Modifier.size(72.dp), tint = Palette.Hairline)
        Spacer(Modifier.height(16.dp))
        Text("No projects yet", style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
        Text("Import a PDF to start reading, excerpting and connecting ideas.", color = Palette.Muted)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectTile(p: Project, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, p) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p.thumb.path)?.asImageBitmap() }
    }
    Column(Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.77f)
                .shadow(6.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).background(Color.White),
        ) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Delete project") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(p.title, fontWeight = FontWeight.Medium, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val n = p.cards.count { it.kind != Kind.Note }
        Text(
            "$n excerpt${if (n == 1) "" else "s"} · ${DateUtils.getRelativeTimeSpanString(p.updated)}",
            style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
        )
    }
}
