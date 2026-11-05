package com.facevideo.search

import android.app.Application
import android.graphics.Bitmap
import android.content.IntentSender
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class Reference(val emb: FloatArray, val preview: Bitmap)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Db.get(app)

    // duration filter (seconds, as typed)
    var minText by mutableStateOf("")
        private set
    var maxText by mutableStateOf("")
        private set
    var sort by mutableStateOf(Sort.NEWEST)
        private set

    // face search
    var refs by mutableStateOf<List<Reference>>(emptyList())
        private set
    var threshold by mutableStateOf(0.40f)
        private set

    var mode by mutableStateOf(Indexer.Mode.BALANCED)
        private set

    var results by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var stats by mutableStateOf(Stats())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    // tabs + duration search (separate from face search)
    var tab by mutableStateOf(0)
        private set
    var durResults by mutableStateOf<List<VideoItem>?>(null)
        private set
    var durBusy by mutableStateOf(false)
        private set
    var durDone by mutableStateOf(0)
        private set
    var durTotal by mutableStateOf(0)
        private set
    var durInfo by mutableStateOf("")
        private set

    // duplicate finder
    var dupGroups by mutableStateOf<List<DupGroup>>(emptyList())
        private set
    var dupScanned by mutableStateOf(false)
        private set
    var dupBusy by mutableStateOf(false)
        private set
    var dupStage by mutableStateOf("")
        private set
    var dupDone by mutableStateOf(0)
        private set
    var dupTotal by mutableStateOf(0)
        private set
    var preferBucket by mutableStateOf<String?>(null)
        private set
    var keepers by mutableStateOf<Map<Int, Long>>(emptyMap())
        private set
    var skipped by mutableStateOf<Set<Int>>(emptySet())
        private set
    var useTrash by mutableStateOf(true)

    private var job: Job? = null
    private var dupJob: Job? = null
    private var pendingDeleteIds: List<Long> = emptyList()

    fun sync() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.syncVideos() }
            refresh(0)
        }
    }

    fun selectTab(i: Int) {
        tab = i
    }

    fun setRange(min: String, max: String) {
        minText = min
        maxText = max
    }

    fun searchDuration() {
        if (durBusy) return
        var minMs = parseMs(minText) ?: 0L
        var maxMs = parseMs(maxText) ?: Long.MAX_VALUE
        if (minMs > maxMs) { val t = minMs; minMs = maxMs; maxMs = t }
        val lo = minMs
        val hi = maxMs
        val sortNow = sort
        viewModelScope.launch {
            durBusy = true
            durDone = 0
            durTotal = 0
            try {
                val list = withContext(Dispatchers.IO) {
                    DurationScan.run(getApplication<Application>()) { d, t ->
                        durDone = d
                        durTotal = t
                    }
                    db.listVideos(lo, hi, sortNow)
                }
                durResults = list
                durInfo = describeRange(lo, hi)
                stats = withContext(Dispatchers.IO) { db.stats() }
            } finally {
                durBusy = false
            }
        }
    }

    private fun describeRange(lo: Long, hi: Long): String = when {
        lo <= 0L && hi == Long.MAX_VALUE -> "any length"
        lo <= 0L -> "up to " + fmtDuration(hi)
        hi == Long.MAX_VALUE -> fmtDuration(lo) + " or longer"
        else -> fmtDuration(lo) + " to " + fmtDuration(hi)
    }

    @JvmName("updateSort")
    fun setSort(s: Sort) {
        sort = s
        durResults = durResults?.let { l ->
            when (s) {
                Sort.NEWEST -> l.sortedByDescending { it.dateAdded }
                Sort.SHORTEST -> l.sortedBy { it.durationMs }
                Sort.LONGEST -> l.sortedByDescending { it.durationMs }
            }
        }
    }

    @JvmName("updateThreshold")
    fun setThreshold(t: Float) {
        threshold = t
        refresh(250)
    }

    @JvmName("updateMode")
    fun setMode(m: Indexer.Mode) {
        mode = m
    }

    fun clearMessage() {
        message = null
    }

    fun clearFaces() {
        refs = emptyList()
        refresh()
    }

    fun rescanAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.resetFaces() }
            refresh(0)
        }
    }

    fun refresh(debounceMs: Long = 150) {
        job?.cancel()
        job = viewModelScope.launch {
            delay(debounceMs)
            busy = true
            try {
                val currentRefs = refs.map { it.emb }
                val th = threshold
                val (s, r) = withContext(Dispatchers.Default) {
                    val st = db.stats()
                    val list = if (currentRefs.isEmpty()) emptyList<VideoItem>()
                    else db.searchByFace(currentRefs, th, 0L, Long.MAX_VALUE)
                    st to list
                }
                stats = s
                results = r
            } finally {
                busy = false
            }
        }
    }

    fun addReferences(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = true
            val found = withContext(Dispatchers.Default) {
                val engine = FaceEngine(getApplication<Application>())
                try {
                    uris.mapNotNull { u ->
                        try {
                            val bmp = decode(u)
                            val res = engine.detect(bmp, strict = false, wantPreview = true, maxFaces = 1).firstOrNull()
                            bmp.recycle()
                            res
                        } catch (e: Exception) {
                            null
                        }
                    }
                } finally {
                    engine.close()
                }
            }
            if (found.size < uris.size) {
                message = "No face found in ${uris.size - found.size} of ${uris.size} photo(s). Use a clear, front-facing photo."
            }
            refs = refs + found.mapNotNull { r -> r.preview?.let { Reference(r.emb, it) } }
            busy = false
            refresh(0)
        }
    }

    private fun decode(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val m = max(info.size.width, info.size.height)
            if (m > 1600) {
                val s = 1600f / m
                dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }
    }

    // ---------------- duplicates ----------------

    fun scanDuplicates() {
        if (dupBusy) return
        dupJob = viewModelScope.launch {
            dupBusy = true
            dupStage = "Starting"
            dupDone = 0
            dupTotal = 0
            try {
                val g = DuplicateScan.run(getApplication<Application>()) { st, d, t ->
                    dupStage = st
                    dupDone = d
                    dupTotal = t
                }
                dupGroups = g
                dupScanned = true
                // "same name" matches are only a hint, so they start excluded from deletion
                skipped = g.filter { it.kind == DupKind.NAME }.map { it.id }.toSet()
                keepers = pickKeepers(g, preferBucket)
                stats = withContext(Dispatchers.IO) { db.stats() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Duplicate scan failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                dupBusy = false
            }
        }
    }

    fun cancelDuplicates() {
        dupJob?.cancel()
    }

    private fun bestOf(videos: List<DupVideo>, prefer: String?): DupVideo {
        val pool = if (prefer != null) videos.filter { it.bucket == prefer }.ifEmpty { videos } else videos
        return pool.maxWithOrNull(
            compareBy<DupVideo>({ it.width.toLong() * it.height }, { it.size }, { -it.dateAdded })
        ) ?: videos.first()
    }

    private fun pickKeepers(groups: List<DupGroup>, prefer: String?): Map<Int, Long> =
        groups.associate { it.id to bestOf(it.videos, prefer).id }

    fun setPrefer(bucket: String?) {
        preferBucket = bucket
        keepers = pickKeepers(dupGroups, bucket)
    }

    fun chooseKeeper(groupId: Int, videoId: Long) {
        keepers = keepers + (groupId to videoId)
    }

    fun toggleSkip(groupId: Int) {
        skipped = if (groupId in skipped) skipped - groupId else skipped + groupId
    }

    fun deletions(): List<DupVideo> =
        dupGroups.filter { it.id !in skipped }
            .flatMap { g -> g.videos.filter { it.id != keepers[g.id] } }

    /** The system shows its own "allow this app to delete/trash N videos?" dialog for this. */
    fun buildDeleteIntent(): IntentSender? {
        if (Build.VERSION.SDK_INT < 30) {
            message = "Deleting videos needs Android 11 or newer."
            return null
        }
        val list = deletions()
        if (list.isEmpty()) return null
        pendingDeleteIds = list.map { it.id }
        val uris = list.map { it.uri }
        val cr = getApplication<Application>().contentResolver
        return try {
            if (useTrash) MediaStore.createTrashRequest(cr, uris, true).intentSender
            else MediaStore.createDeleteRequest(cr, uris).intentSender
        } catch (e: Exception) {
            message = "Couldn't start the delete request: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    fun onDeleteResult(ok: Boolean) {
        if (!ok) {
            message = "Nothing was deleted."
            return
        }
        val gone = pendingDeleteIds.toSet()
        val freed = dupGroups.flatMap { it.videos }.filter { it.id in gone }.sumOf { it.size }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.syncVideos() }
            val next = dupGroups
                .map { g -> DupGroup(g.id, g.kind, g.videos.filter { it.id !in gone }) }
                .filter { it.videos.size >= 2 }
            val ids = next.map { it.id }.toSet()
            dupGroups = next
            keepers = keepers.filterKeys { it in ids }
            skipped = skipped.filter { it in ids }.toSet()
            message = "${gone.size} video${if (gone.size == 1) "" else "s"} removed · ${fmtSize(freed)} freed"
            stats = withContext(Dispatchers.IO) { db.stats() }
            refresh(0)
        }
    }

    private fun parseMs(s: String): Long? = s.trim().toDoubleOrNull()?.let { (it * 1000).toLong() }
}
