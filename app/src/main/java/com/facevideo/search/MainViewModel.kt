package com.facevideo.search

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private var job: Job? = null

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

    private fun parseMs(s: String): Long? = s.trim().toDoubleOrNull()?.let { (it * 1000).toLong() }
}
