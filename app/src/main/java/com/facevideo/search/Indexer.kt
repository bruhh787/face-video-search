package com.facevideo.search

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class IndexState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val current: String = "",
    val faces: Int = 0,
    val finished: Boolean = false,
    val error: String? = null,
    val startMs: Long = 0L,
)

/**
 * Scans every video, samples frames, stores one embedding per detected face. Resumable.
 *
 * Speed: several videos are processed at the same time (one worker per ~2 CPU cores), and frames are
 * decoded straight at the target size (getScaledFrameAtTime) instead of decoding full-res and shrinking.
 */
object Indexer {

    enum class Mode(val label: String, val stepMs: Long, val accurateSeek: Boolean, val maxFrames: Int) {
        FAST("Fast", 4000, false, 100),
        BALANCED("Balanced", 2000, false, 200),
        THOROUGH("Thorough", 1000, true, 400),
    }

    val state = MutableStateFlow(IndexState())

    @Volatile var job: Job? = null

    private const val MAX_DIM = 1280

    private val WORKERS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 3)

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
            state.value = IndexState(running = true, total = pending.size, startMs = System.currentTimeMillis())
            if (pending.isEmpty()) {
                state.update { it.copy(finished = true) }
                return@withContext
            }
            val eng = FaceEngine(ctx)
            engine = eng
            val next = AtomicInteger(0)
            val doneCount = AtomicInteger(0)
            val faceCount = AtomicInteger(0)
            coroutineScope {
                repeat(WORKERS) {
                    launch {
                        while (true) {
                            ensureActive()
                            val i = next.getAndIncrement()
                            if (i >= pending.size) break
                            val v = pending[i]
                            state.update { it.copy(current = v.name) }
                            val found = try {
                                indexVideo(ctx, eng, v, mode)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                emptyList() // unreadable/corrupt video: mark as done so we don't retry forever
                            }
                            db.saveFaces(v.id, found)
                            val d = doneCount.incrementAndGet()
                            val f = faceCount.addAndGet(found.size)
                            state.update { it.copy(done = max(it.done, d), faces = max(it.faces, f)) }
                        }
                    }
                }
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
            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val turned = rot == 90 || rot == 270
            val dispW = if (turned) vh else vw
            val dispH = if (turned) vw else vh

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

            var lastSig: IntArray? = null
            for (t in times) {
                currentCoroutineContext().ensureActive()
                val bmp = grab(r, t * 1000, option, dispW, dispH) ?: continue
                val sig = signature(bmp)
                val prev = lastSig
                if (prev == null || !nearlySame(sig, prev)) { // skip if the seek returned the same picture again
                    lastSig = sig
                    for (res in engine.detect(bmp, strict = true)) out.add(t to res.emb)
                }
                bmp.recycle()
            }
        } finally {
            r.release()
        }
        return out
    }

    /** Decodes the frame directly at (at most) MAX_DIM, which is much cheaper than full size + resize. */
    private fun grab(r: MediaMetadataRetriever, tUs: Long, option: Int, dw: Int, dh: Int): Bitmap? {
        if (dw <= 0 || dh <= 0) {
            val raw = r.getFrameAtTime(tUs, option) ?: return null
            return shrink(raw)
        }
        val s = min(1f, MAX_DIM.toFloat() / max(dw, dh))
        val tw = max(2, (dw * s).roundToInt())
        val th = max(2, (dh * s).roundToInt())
        val raw = r.getScaledFrameAtTime(tUs, option, tw, th) ?: return null
        // some decoders squeeze rotated clips: put the true proportions back
        val want = dw.toFloat() / dh
        val got = raw.width.toFloat() / raw.height
        if (abs(got / want - 1f) > 0.06f) {
            val fixed = Bitmap.createScaledBitmap(raw, tw, th, true)
            if (fixed !== raw) raw.recycle()
            return fixed
        }
        return raw
    }

    private fun shrink(src: Bitmap): Bitmap {
        val m = max(src.width, src.height)
        if (m <= MAX_DIM) return src
        val s = MAX_DIM.toFloat() / m
        val out = Bitmap.createScaledBitmap(
            src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true
        )
        if (out !== src) src.recycle()
        return out
    }

    private fun signature(b: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(b, 16, 16, true)
        val px = IntArray(256)
        small.getPixels(px, 0, 16, 0, 0, 16, 16)
        if (small !== b) small.recycle()
        return IntArray(256) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
        }
    }

    private fun nearlySame(a: IntArray, b: IntArray): Boolean {
        var d = 0
        for (i in a.indices) d += abs(a[i] - b[i])
        return d / a.size <= 1
    }
}
