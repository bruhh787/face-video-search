package com.facevideo.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
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

@Composable
fun MainScreen(
    vm: MainViewModel,
    onStartScan: () -> Unit,
    onPickFaces: () -> Unit,
    onOpen: (VideoItem) -> Unit,
) {
    val ctx = LocalContext.current
    val idx by Indexer.state.collectAsStateWithLifecycle()
    val loader = remember {
        ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build()
    }

    // refresh results while a scan is running (every 5 videos) and when it starts/stops
    LaunchedEffect(idx.running, idx.done / 5) { vm.refresh() }

    val list = if (vm.tab == 0) vm.results else (vm.durResults ?: emptyList())

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Face Video Search", style = MaterialTheme.typography.headlineSmall)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            TabRow(selectedTabIndex = vm.tab) {
                Tab(selected = vm.tab == 0, onClick = { vm.selectTab(0) }, text = { Text("Face search") })
                Tab(selected = vm.tab == 1, onClick = { vm.selectTab(1) }, text = { Text("Duration search") })
            }
        }
        if (vm.tab == 0) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ScanCard(vm, idx, onStartScan)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FaceCard(vm, onPickFaces)
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DurationCard(vm)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val n = list.size
            val plural = if (n == 1) "" else "s"
            val label = if (vm.tab == 0) {
                if (vm.refs.isEmpty()) "Pick a face photo above to search"
                else "$n video$plural with this face" + if (vm.busy) "  ·  searching…" else ""
            } else {
                if (vm.durResults == null) "Set a length above and press Search"
                else "$n video$plural  ·  ${vm.durInfo}"
            }
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
        items(list, key = { it.id }) { v ->
            VideoTile(v, loader) { onOpen(v) }
        }
    }
}

@Composable
private fun ScanCard(vm: MainViewModel, idx: IndexState, onStartScan: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. Scan your videos", style = MaterialTheme.typography.titleMedium)
            Text(
                "${vm.stats.total} videos found · ${vm.stats.indexed} scanned · ${vm.stats.faces} faces indexed",
                style = MaterialTheme.typography.bodyMedium
            )
            if (idx.running) {
                val p = if (idx.total > 0) idx.done.toFloat() / idx.total else 0f
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${idx.done} / ${idx.total}  ${idx.current}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1
                )
                OutlinedButton(onClick = { Indexer.cancel() }) { Text("Stop (progress is kept)") }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in Indexer.Mode.values()) {
                        FilterChip(selected = vm.mode == m, onClick = { vm.setMode(m) }, label = { Text(m.label) })
                    }
                }
                val pending = vm.stats.total - vm.stats.indexed
                Button(onClick = onStartScan, enabled = vm.stats.total > 0) {
                    Text(if (vm.stats.indexed == 0) "Scan all videos" else if (pending > 0) "Scan $pending new videos" else "Scan again for new videos")
                }
                if (vm.stats.indexed > 0) {
                    TextButton(onClick = { vm.rescanAll() }) { Text("Re-index everything") }
                }
                if (idx.error != null) Text("Error: ${idx.error}", color = MaterialTheme.colorScheme.error)
                if (idx.finished && idx.total > 0) Text("Scan finished.", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Fast = a frame every 4 s, Balanced = 2 s, Thorough = 1 s (slower, finds shorter appearances). " +
                        "The first scan can take a long time; it runs in the background and can be stopped and resumed.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun FaceCard(vm: MainViewModel, onPickFaces: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("2. Search by face", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickFaces) { Text(if (vm.refs.isEmpty()) "Pick face photo(s)" else "Add more photos") }
                if (vm.refs.isNotEmpty()) TextButton(onClick = { vm.clearFaces() }) { Text("Clear") }
            }
            if (vm.refs.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in vm.refs) {
                        Image(
                            bitmap = r.preview.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop
                        )
                    }
                }
                Text(
                    "Match strictness: " + String.format(Locale.US, "%.2f", vm.threshold) +
                        "  (higher = fewer but surer matches)",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = vm.threshold, onValueChange = { vm.setThreshold(it) },
                    valueRange = 0.4f..0.9f
                )
                if (vm.stats.indexed < vm.stats.total) {
                    Text(
                        "Only ${vm.stats.indexed} of ${vm.stats.total} videos are scanned so far — scan the rest to include them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Text(
                    "Pick 1–5 clear photos of the person (front-facing, good light). More photos = better matching.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            vm.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.clearMessage() }) { Text("OK") }
            }
        }
    }
}

@Composable
private fun DurationCard(vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Search by duration", style = MaterialTheme.typography.titleMedium)
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
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = vm.maxText, onValueChange = { vm.setRange(vm.minText, it) },
                    label = { Text("Max (sec)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
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
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    if (vm.durTotal > 0) "Measuring video lengths… ${vm.durDone} / ${vm.durTotal}"
                    else "Scanning ${vm.stats.total} videos…",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Pick a preset or type a range, then press Search. Leave Min empty for \"up to\", leave Max empty for \"or longer\".",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun VideoTile(v: VideoItem, loader: ImageLoader, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val request = remember(v.id, v.bestMs) {
        ImageRequest.Builder(ctx).data(v.uri).videoFrameMillis(v.bestMs).build()
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray).clickable { onClick() }
    ) {
        AsyncImage(
            model = request, imageLoader = loader, contentDescription = v.name,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
        )
        Text(
            fmtDuration(v.durationMs),
            color = Color.White, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
        )
        if (v.fromFace) {
            Text(
                String.format(Locale.US, "%.2f · %d hit%s · @%s", v.score, v.hits, if (v.hits == 1) "" else "s", fmtDuration(v.bestMs)),
                color = Color.White, fontSize = 10.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}
