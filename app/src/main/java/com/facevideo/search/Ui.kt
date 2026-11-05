package com.facevideo.search

import android.content.IntentSender
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class Preset(val label: String, val min: String, val max: String)

private val PRESETS = listOf(
    Preset("Any", "", ""),
    Preset("< 5 s", "", "5"),
    Preset("< 15 s", "", "15"),
    Preset("< 30 s", "", "30"),
    Preset("< 1 min", "", "60"),
    Preset("1–5 min", "60", "300"),
    Preset("> 5 min", "300", ""),
)

fun fmtDuration(ms: Long): String {
    if (ms < 60_000) return String.format(Locale.US, "%.1fs", ms / 1000.0)
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%d KB", bytes / 1024)
}

private fun fmtDate(sec: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(sec * 1000))

private fun fmtEta(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "under a minute left"
        min < 60 -> "about $min min left"
        else -> "about ${min / 60} h ${min % 60} min left"
    }
}

// ───────────────────────────── shell ─────────────────────────────

@Composable
fun MainScreen(
    vm: MainViewModel,
    onStartScan: () -> Unit,
    onPickFaces: () -> Unit,
    onOpen: (VideoItem) -> Unit,
    onRequestDelete: (IntentSender) -> Unit,
) {
    val ctx = LocalContext.current
    val idx by Indexer.state.collectAsStateWithLifecycle()
    val loader = remember {
        ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build()
    }

    // refresh face results while a scan is running (every 5 videos) and when it starts/stops
    LaunchedEffect(idx.running, idx.done / 5) { vm.refresh() }

    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snack.showSnackbar(it)
            vm.clearMessage()
        }
    }

    // keep the screen awake while the duplicate scan runs (it is not a background service)
    val view = LocalView.current
    DisposableEffect(vm.dupBusy) {
        view.keepScreenOn = vm.dupBusy
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = vm.tab == 0, onClick = { vm.selectTab(0) },
                    icon = { Icon(Icons.Filled.Face, contentDescription = null) }, label = { Text("Faces") }
                )
                NavigationBarItem(
                    selected = vm.tab == 1, onClick = { vm.selectTab(1) },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) }, label = { Text("Duration") }
                )
                NavigationBarItem(
                    selected = vm.tab == 2, onClick = { vm.selectTab(2) },
                    icon = { Icon(Icons.Filled.Delete, contentDescription = null) }, label = { Text("Duplicates") }
                )
            }
        }
    ) { pad ->
        when (vm.tab) {
            0 -> FaceTab(vm, idx, loader, pad, onStartScan, onPickFaces, onOpen)
            1 -> DurationTab(vm, loader, pad, onOpen)
            else -> DuplicatesTab(vm, loader, pad, onOpen, onRequestDelete)
        }
    }
}

private fun screenPad(pad: PaddingValues, extraBottom: Int = 24) = PaddingValues(
    start = 16.dp, end = 16.dp,
    top = pad.calculateTopPadding() + 12.dp,
    bottom = pad.calculateBottomPadding() + extraBottom.dp,
)

private fun LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

// ───────────────────────────── shared pieces ─────────────────────────────

@Composable
private fun Hero(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(cs.primary, cs.tertiary)))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = cs.onPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimary.copy(alpha = 0.88f))
        content()
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.18f)).padding(12.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onPrimary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onPrimary.copy(alpha = 0.85f))
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        modifier = modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Progress(p: Float) {
    LinearProgressIndicator(
        progress = { p.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))
    )
}

@Composable
private fun VideoTile(v: VideoItem, loader: ImageLoader, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val request = remember(v.id, v.bestMs) {
        ImageRequest.Builder(ctx).data(v.uri).videoFrameMillis(v.bestMs).build()
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(18.dp))
            .background(cs.surfaceVariant).clickable { onClick() }
    ) {
        AsyncImage(
            model = request, imageLoader = loader, contentDescription = v.name,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxWidth().height(48.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
        )
        Text(
            fmtDuration(v.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        )
        if (v.fromFace) {
            Pill(
                String.format(Locale.US, "%.0f%%", v.score * 100),
                cs.primary, cs.onPrimary,
                Modifier.align(Alignment.TopStart).padding(6.dp)
            )
            Text(
                "${v.hits}× @${fmtDuration(v.bestMs)}", color = Color.White,
                style = MaterialTheme.typography.labelSmall, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
            )
        }
    }
}

// ───────────────────────────── faces ─────────────────────────────

@Composable
private fun FaceTab(
    vm: MainViewModel, idx: IndexState, loader: ImageLoader, pad: PaddingValues,
    onStartScan: () -> Unit, onPickFaces: () -> Unit, onOpen: (VideoItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
        contentPadding = screenPad(pad),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        full {
            Hero("Find a face", "Search every video on your phone by a person's face. Nothing leaves your phone.") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("${vm.stats.total}", "videos", Modifier.weight(1f))
                    Stat("${vm.stats.indexed}", "scanned", Modifier.weight(1f))
                    Stat("${vm.stats.faces}", "faces", Modifier.weight(1f))
                }
            }
        }
        full { ScanCard(vm, idx, onStartScan) }
        full { FaceCard(vm, onPickFaces) }
        full {
            val n = vm.results.size
            Text(
                if (vm.refs.isEmpty()) "Pick a face photo above to search"
                else "$n video${if (n == 1) "" else "s"} with this face" + if (vm.busy) "  ·  searching…" else "",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)
            )
        }
        items(vm.results, key = { it.id }) { v -> VideoTile(v, loader) { onOpen(v) } }
    }
}

@Composable
private fun ScanCard(vm: MainViewModel, idx: IndexState, onStartScan: () -> Unit) {
    SectionCard("1 · Scan your videos") {
        if (idx.running) {
            val p = if (idx.total > 0) idx.done.toFloat() / idx.total else 0f
            Progress(p)
            val eta = if (idx.done > 2 && idx.startMs > 0)
                "  ·  " + fmtEta((System.currentTimeMillis() - idx.startMs) / idx.done * (idx.total - idx.done))
            else ""
            Text("${idx.done} / ${idx.total}$eta", style = MaterialTheme.typography.titleSmall)
            Hint(idx.current)
            OutlinedButton(onClick = { Indexer.cancel() }) { Text("Stop (progress is kept)") }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in Indexer.Mode.values()) {
                    FilterChip(selected = vm.mode == m, onClick = { vm.setMode(m) }, label = { Text(m.label) })
                }
            }
            val pending = vm.stats.total - vm.stats.indexed
            Button(onClick = onStartScan, enabled = vm.stats.total > 0, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (vm.stats.indexed == 0) "Scan all videos"
                    else if (pending > 0) "Scan $pending new videos" else "Scan again for new videos"
                )
            }
            if (vm.stats.indexed > 0) {
                TextButton(onClick = { vm.rescanAll() }) { Text("Re-index everything") }
            }
            if (idx.error != null) Text("Error: ${idx.error}", color = MaterialTheme.colorScheme.error)
            if (idx.finished && idx.total > 0) Hint("Scan finished.")
            Hint(
                "Fast = a frame every 4 s, Balanced = 2 s, Thorough = 1 s (slower, catches short appearances). " +
                    "Runs in the background; you can stop and resume."
            )
        }
    }
}

@Composable
private fun FaceCard(vm: MainViewModel, onPickFaces: () -> Unit) {
    SectionCard("2 · Search by face") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPickFaces) { Text(if (vm.refs.isEmpty()) "Pick face photo(s)" else "Add more photos") }
            if (vm.refs.isNotEmpty()) TextButton(onClick = { vm.clearFaces() }) { Text("Clear") }
        }
        if (vm.refs.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in vm.refs) {
                    Image(
                        bitmap = r.preview.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Text(
                "Match strictness  " + String.format(Locale.US, "%.2f", vm.threshold),
                style = MaterialTheme.typography.titleSmall
            )
            Slider(value = vm.threshold, onValueChange = { vm.setThreshold(it) }, valueRange = 0.2f..0.8f)
            Hint("Higher = fewer but surer matches. Lower it if you miss videos, raise it if you get wrong people.")
            if (vm.stats.indexed < vm.stats.total) {
                Text(
                    "Only ${vm.stats.indexed} of ${vm.stats.total} videos are scanned so far. Scan the rest to include them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            Hint("Pick 1–5 clear photos of the person (front-facing, good light). More photos = better matching.")
        }
    }
}

// ───────────────────────────── duration ─────────────────────────────

@Composable
private fun DurationTab(vm: MainViewModel, loader: ImageLoader, pad: PaddingValues, onOpen: (VideoItem) -> Unit) {
    val list = vm.durResults ?: emptyList()
    LazyVerticalGrid(
        columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
        contentPadding = screenPad(pad),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        full { Hero("Search by length", "Find clips by how long they are, like everything under 5 seconds.") }
        full { DurationCard(vm) }
        full {
            val n = list.size
            Text(
                if (vm.durResults == null) "Set a length above and press Search"
                else "$n video${if (n == 1) "" else "s"}  ·  ${vm.durInfo}",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)
            )
        }
        items(list, key = { it.id }) { v -> VideoTile(v, loader) { onOpen(v) } }
    }
}

@Composable
private fun DurationCard(vm: MainViewModel) {
    SectionCard("Length range") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in PRESETS) {
                FilterChip(
                    selected = vm.minText == p.min && vm.maxText == p.max,
                    onClick = { vm.setRange(p.min, p.max) },
                    label = { Text(p.label) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = vm.minText, onValueChange = { vm.setRange(it, vm.maxText) },
                label = { Text("Min (sec)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = vm.maxText, onValueChange = { vm.setRange(vm.minText, it) },
                label = { Text("Max (sec)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.sort == Sort.NEWEST, onClick = { vm.setSort(Sort.NEWEST) }, label = { Text("Newest") })
            FilterChip(selected = vm.sort == Sort.SHORTEST, onClick = { vm.setSort(Sort.SHORTEST) }, label = { Text("Shortest") })
            FilterChip(selected = vm.sort == Sort.LONGEST, onClick = { vm.setSort(Sort.LONGEST) }, label = { Text("Longest") })
        }
        Button(onClick = { vm.searchDuration() }, enabled = !vm.durBusy, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.durBusy) "Scanning…" else "Search videos")
        }
        if (vm.durBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)))
            Hint(
                if (vm.durTotal > 0) "Measuring video lengths… ${vm.durDone} / ${vm.durTotal}"
                else "Scanning ${vm.stats.total} videos…"
            )
        }
        Hint("Leave Min empty for \"up to\", leave Max empty for \"or longer\".")
    }
}

// ───────────────────────────── duplicates ─────────────────────────────

@Composable
private fun DuplicatesTab(
    vm: MainViewModel, loader: ImageLoader, pad: PaddingValues,
    onOpen: (VideoItem) -> Unit, onRequestDelete: (IntentSender) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf(false) }
    val removals = vm.deletions()
    val freeBytes = removals.sumOf { it.size }
    val folders = remember(vm.dupGroups) {
        vm.dupGroups.flatMap { it.videos }.filter { it.bucket.isNotBlank() }
            .groupingBy { it.bucket }.eachCount().entries
            .sortedByDescending { it.value }.take(6).map { it.key }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = screenPad(pad, extraBottom = 110),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Hero("Clean up duplicates", "Finds the same video saved more than once, keeps the best copy and removes the rest.") {
                    if (vm.dupScanned) {
                        val certain = vm.dupGroups.filter { it.kind != DupKind.NAME }
                        val extra = certain.sumOf { it.videos.size - 1 }
                        val bytes = certain.sumOf { g -> g.videos.sumOf { it.size } - g.videos.maxOf { it.size } }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Stat("${vm.dupGroups.size}", "groups", Modifier.weight(1f))
                            Stat("$extra", "extra copies", Modifier.weight(1f))
                            Stat(fmtSize(bytes), "to free", Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                SectionCard("Scan") {
                    if (vm.dupBusy) {
                        if (vm.dupTotal > 0) Progress(vm.dupDone.toFloat() / vm.dupTotal)
                        else LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)))
                        Text(
                            if (vm.dupTotal > 0) "${vm.dupStage}  ${vm.dupDone} / ${vm.dupTotal}" else vm.dupStage,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Hint("Keep the app open. The screen stays on while this runs.")
                        OutlinedButton(onClick = { vm.cancelDuplicates() }) { Text("Stop") }
                    } else {
                        Button(onClick = { vm.scanDuplicates() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (vm.dupScanned) "Scan again" else "Scan for duplicates")
                        }
                        Hint(
                            "Three checks: identical files, videos that look the same (5 frames compared), and same name + length. " +
                                "The first scan can take a few minutes; later scans only check new videos."
                        )
                    }
                }
            }
            if (vm.dupGroups.isNotEmpty()) {
                item {
                    SectionCard("Which copy to keep") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = vm.preferBucket == null, onClick = { vm.setPrefer(null) },
                                label = { Text("Best quality") }
                            )
                            for (f in folders) {
                                FilterChip(
                                    selected = vm.preferBucket == f, onClick = { vm.setPrefer(f) },
                                    label = { Text("Prefer $f") }
                                )
                            }
                        }
                        Hint(
                            "Best quality = highest resolution, then biggest file, then oldest. " +
                                "Tap any video below to keep that one instead. \"Same name\" groups start skipped because they are only a hint."
                        )
                    }
                }
            }
            if (vm.dupScanned && vm.dupGroups.isEmpty()) {
                item {
                    SectionCard("No duplicates found") { Hint("Nothing looks like a copy. Your library is clean.") }
                }
            }
            items(vm.dupGroups, key = { it.id }) { g ->
                GroupCard(g, vm, loader, onOpen)
            }
        }

        if (removals.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = pad.calculateBottomPadding() + 12.dp),
                shape = RoundedCornerShape(24.dp), tonalElevation = 8.dp, shadowElevation = 10.dp,
                color = cs.surfaceContainerHigh
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${removals.size} video${if (removals.size == 1) "" else "s"} selected",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold
                        )
                        Text("Frees ${fmtSize(freeBytes)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Button(
                        onClick = { confirm = true },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = cs.error, contentColor = cs.onError
                        )
                    ) { Text("Remove") }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove ${removals.size} video${if (removals.size == 1) "" else "s"}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This frees ${fmtSize(freeBytes)}. The copy you chose to keep in each group stays.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Move to trash", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (vm.useTrash) "Recoverable from your gallery's trash for about 30 days."
                                else "Permanent. Android will ask you to confirm.",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                            )
                        }
                        Switch(checked = vm.useTrash, onCheckedChange = { vm.useTrash = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.buildDeleteIntent()?.let(onRequestDelete)
                }) { Text(if (vm.useTrash) "Move to trash" else "Delete", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun GroupCard(g: DupGroup, vm: MainViewModel, loader: ImageLoader, onOpen: (VideoItem) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val skipped = g.id in vm.skipped
    val keep = vm.keepers[g.id]
    val (bg, fg) = when (g.kind) {
        DupKind.EXACT -> cs.primaryContainer to cs.onPrimaryContainer
        DupKind.SIMILAR -> cs.tertiaryContainer to cs.onTertiaryContainer
        DupKind.NAME -> cs.secondaryContainer to cs.onSecondaryContainer
    }
    val keepSize = g.videos.firstOrNull { it.id == keep }?.size ?: g.videos.maxOf { it.size }
    val reclaim = g.videos.sumOf { it.size } - keepSize
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(g.kind.label, bg, fg)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${g.videos.size} copies · ${fmtSize(reclaim)} to free",
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.toggleSkip(g.id) }) { Text(if (skipped) "Include" else "Skip") }
            }
            if (g.kind == DupKind.NAME) {
                Hint("Same name and length, but the pictures weren't confirmed to match. Check them before including.")
            }
            Column(Modifier.alpha(if (skipped) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (v in g.videos) {
                    DupRow(
                        v, isKeep = v.id == keep, skipped = skipped, loader = loader,
                        onChoose = { vm.chooseKeeper(g.id, v.id) },
                        onPlay = { onOpen(VideoItem(v.id, v.name, v.durationMs, v.dateAdded)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DupRow(
    v: DupVideo, isKeep: Boolean, skipped: Boolean, loader: ImageLoader,
    onChoose: () -> Unit, onPlay: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val request = remember(v.id) {
        ImageRequest.Builder(ctx).data(v.uri).videoFrameMillis(v.durationMs / 2).build()
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (isKeep && !skipped) cs.primary.copy(alpha = 0.10f) else Color.Transparent)
            .clickable { onChoose() }.padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(width = 96.dp, height = 64.dp).clip(RoundedCornerShape(12.dp))
                .background(cs.surfaceVariant).clickable { onPlay() }
        ) {
            AsyncImage(
                model = request, imageLoader = loader, contentDescription = v.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            Text(
                fmtDuration(v.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                v.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "${v.width}×${v.height} · ${fmtSize(v.size)}",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
            )
            Text(
                (if (v.bucket.isNotBlank()) v.bucket + " · " else "") + fmtDate(v.dateAdded),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        when {
            skipped -> Pill("SKIP", cs.surfaceVariant, cs.onSurfaceVariant)
            isKeep -> Pill("KEEP", cs.primary, cs.onPrimary)
            else -> Pill("REMOVE", cs.errorContainer, cs.onErrorContainer)
        }
    }
}
