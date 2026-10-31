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
    var threshold by mutableStateOf(0.70f)
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

    private var job: Job? = null

    fun sync() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.syncVideos() }
            refresh(0)
        }
    }

    fun setRange(min: String, max: String) {
        minText = min
        maxText = max
        refresh()
    }

    fun setSort(s: Sort) {
        sort = s
        refresh()
    }

    fun setThreshold(t: Float) {
        threshold = t
        refresh(250)
    }

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
                val minMs = parseMs(minText) ?: 0L
                val maxMs = parseMs(maxText) ?: Long.MAX_VALUE
                val currentRefs = refs.map { it.emb }
                val th = threshold
                val sortNow = sort
                val (s, r) = withContext(Dispatchers.Default) {
                    val st = db.stats()
                    val list = if (currentRefs.isEmpty()) db.listVideos(minMs, maxMs, sortNow)
                    else db.searchByFace(currentRefs, th, minMs, maxMs)
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
