package com.facevideo.search

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

data class IndexState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val current: String = "",
    val faces: Int = 0,
    val finished: Boolean = false,
    val error: String? = null,
)

/** Scans every video, samples frames, stores one embedding per detected face. Resumable. */
object Indexer {

    enum class Mode(val label: String, val stepMs: Long, val accurateSeek: Boolean, val maxFrames: Int) {
        FAST("Fast", 4000, false, 100),
        BALANCED("Balanced", 2000, false, 200),
        THOROUGH("Thorough", 1000, true, 400),
    }

    val state = MutableStateFlow(IndexState())

    @Volatile var job: Job? = null

    private const val MAX_DIM = 1280

    fun start(ctx: Context, mode: Mode) {
        if (state.value.running) return
        state.value = IndexState(running = true)
        ContextCompat.startForegroundService(
            ctx, Intent(ctx, ScanService::class.java).putExtra("mode", mode.name)
        )
    }

    fun cancel() {
        job?.cancel()
    }

    suspend fun run(ctx: Context, mode: Mode) = withContext(Dispatchers.Default) {
        val db = Db.get(ctx)
        var engine: FaceEngine? = null
        try {
            db.syncVideos()
            val pending = db.pendingVideos()
            state.value = IndexState(running = true, total = pending.size)
            if (pending.isEmpty()) {
                state.update { it.copy(finished = true) }
                return@withContext
            }
            val eng = FaceEngine(ctx)
            engine = eng
            var facesTotal = 0
            for ((i, v) in pending.withIndex()) {
                currentCoroutineContext().ensureActive()
                state.update { it.copy(current = v.name, done = i) }
                val found = try {
                    indexVideo(ctx, eng, v, mode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList() // unreadable/corrupt video: mark as done so we don't retry forever
                }
                db.saveFaces(v.id, found)
                facesTotal += found.size
                state.update { it.copy(done = i + 1, faces = facesTotal) }
            }
            state.update { it.copy(finished = true, current = "") }
        } finally {
            engine?.close()
            state.update { it.copy(running = false) }
        }
    }

    private suspend fun indexVideo(
        ctx: Context, engine: FaceEngine, v: VideoItem, mode: Mode
    ): List<Pair<Long, FloatArray>> {
        val out = ArrayList<Pair<Long, FloatArray>>()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, v.uri)
            val dur = if (v.durationMs > 0) v.durationMs
            else r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val step = max(mode.stepMs, dur / mode.maxFrames)
            val option = if (mode.accurateSeek) MediaMetadataRetriever.OPTION_CLOSEST
            else MediaMetadataRetriever.OPTION_CLOSEST_SYNC

            val times = ArrayList<Long>()
            if (dur <= step) {
                times.add(dur / 2)
            } else {
                var t = step / 2
                while (t < dur) {
                    times.add(t)
                    t += step
                }
            }

            var lastHash = 0
            for (t in times) {
                currentCoroutineContext().ensureActive()
                val raw = r.getFrameAtTime(t * 1000, option) ?: continue
                val bmp = downscale(raw)
                val h = fingerprint(bmp)
                if (h != lastHash) { // skip if the seek returned the same keyframe again
                    lastHash = h
                    for (res in engine.detect(bmp, strict = true)) out.add(t to res.emb)
                }
                bmp.recycle()
            }
        } finally {
            r.release()
        }
        return out
    }

    private fun downscale(src: Bitmap): Bitmap {
        val m = max(src.width, src.height)
        if (m <= MAX_DIM) return src
        val s = MAX_DIM.toFloat() / m
        val out = Bitmap.createScaledBitmap(
            src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true
        )
        if (out !== src) src.recycle()
        return out
    }

    private fun fingerprint(b: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(b, 16, 16, true)
        val px = IntArray(256)
        small.getPixels(px, 0, 16, 0, 0, 16, 16)
        if (small !== b) small.recycle()
        return px.contentHashCode()
    }
}
