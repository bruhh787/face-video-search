package com.facevideo.search

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

enum class DupKind(val label: String) {
    EXACT("Identical"),
    SIMILAR("Look the same"),
    NAME("Same name"),
}

data class DupVideo(
    val id: Long,
    val name: String,
    val durationMs: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val dateAdded: Long,
    val bucket: String,
) {
    val uri: Uri get() = ContentUris.withAppendedId(Db.videoBase(), id)
}

class DupGroup(val id: Int, val kind: DupKind, val videos: List<DupVideo>)

/**
 * Finds duplicate videos in three layers (strongest first):
 *  1. EXACT   - same size and same content samples (start / middle / end of the file).
 *  2. SIMILAR - about the same length and 5 frames spread over the video look the same
 *               (catches re-saved, compressed or renamed copies).
 *  3. NAME    - same name (ignoring "(1)" / "copy") and same length. Only a hint.
 * Hashes are cached in the database, so repeat scans only look at new videos.
 */
object DuplicateScan {

    private const val FRAMES = 5
    private val WORKERS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    private class PH(val h: LongArray, val flat: Int)

    suspend fun run(
        ctx: Context,
        onProgress: (stage: String, done: Int, total: Int) -> Unit,
    ): List<DupGroup> = withContext(Dispatchers.Default) {
        val db = Db.get(ctx)
        onProgress("Reading your videos", 0, 0)
        withContext(Dispatchers.IO) { db.syncVideos() }
        val vids = db.allForDedupe().filter { it.size > 0 }
        val cached = db.getHashes()
        val exact = HashMap<Long, String>()
        val phash = HashMap<Long, PH>()
        for ((id, p) in cached) {
            p.first?.let { exact[id] = it }
            p.second?.let { s -> parsePh(s)?.let { phash[id] = it } }
        }

        // ---- 1. exact: only files that share a size with another file need reading ----
        val bySize = vids.groupBy { it.size }.filter { it.value.size > 1 }
        val needExact = bySize.values.flatten().filter { it.id !in exact }
        parallel(needExact, onProgress, "Comparing file contents") { v ->
            val h = exactHash(ctx, v)
            if (h != null) {
                synchronized(exact) { exact[v.id] = h }
                db.saveExact(v.id, h)
            }
        }

        // ---- 2. frames: only videos that have a same-length neighbour ----
        val byDur = vids.filter { it.durationMs > 0 }.sortedBy { it.durationMs }
        val cand = HashSet<Long>()
        var win = 0
        for (i in byDur.indices) {
            win = max(win, i + 1)
            while (win < byDur.size && byDur[win].durationMs - byDur[i].durationMs <= tol(byDur[i].durationMs)) {
                cand.add(byDur[i].id)
                cand.add(byDur[win].id)
                win++
            }
        }
        // identical files share one frame hash, so hash one per exact group only
        val exactGroups = HashMap<String, MutableList<DupVideo>>()
        for (v in vids) if (v.id in cand) exact[v.id]?.let { exactGroups.getOrPut(it) { ArrayList() }.add(v) }
        val rep = HashMap<Long, Long>() // video -> representative of its exact group
        for (g in exactGroups.values) for (v in g) rep[v.id] = g[0].id
        val needPh = byDur.filter { it.id in cand && it.id !in phash && (rep[it.id] ?: it.id) == it.id }
        parallel(needPh, onProgress, "Comparing frames") { v ->
            val ph = framesHash(ctx, v)
            if (ph != null) {
                synchronized(phash) { phash[v.id] = ph }
                db.savePhash(v.id, encodePh(ph))
            }
        }
        for ((vid, r) in rep) {
            if (vid != r && vid !in phash) phash[r]?.let { p ->
                phash[vid] = p
                db.savePhash(vid, encodePh(p))
            }
        }

        // ---- 3. link videos into groups (strongest links first) ----
        onProgress("Grouping", 0, 0)
        val idx = HashMap<Long, Int>()
        for ((i, v) in vids.withIndex()) idx[v.id] = i
        val parent = IntArray(vids.size) { it }
        val strength = IntArray(vids.size) { 2 }
        fun find(x: Int): Int {
            var a = x
            while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }
            return a
        }
        fun union(a: Int, b: Int, s: Int) {
            val ra = find(a); val rb = find(b)
            if (ra == rb) return
            parent[ra] = rb
            strength[rb] = minOf(strength[ra], strength[rb], s)
        }

        for (g in vids.filter { it.id in exact }.groupBy { exact[it.id] }.values) {
            for (k in 1 until g.size) union(idx[g[0].id]!!, idx[g[k].id]!!, 2)
        }
        val simPairs = ArrayList<IntArray>()
        for (i in byDur.indices) {
            val a = byDur[i]
            val pa = phash[a.id] ?: continue
            var j = i + 1
            while (j < byDur.size && byDur[j].durationMs - a.durationMs <= tol(a.durationMs)) {
                val b = byDur[j]
                val pb = phash[b.id]
                if (pb != null) {
                    val (valid, ok) = compare(pa, pb)
                    if (valid >= 3 && ok >= max(3, valid - 1)) simPairs.add(intArrayOf(idx[a.id]!!, idx[b.id]!!))
                }
                j++
            }
        }
        for (p in simPairs) union(p[0], p[1], 1)

        for (g in vids.filter { it.durationMs > 0 }.groupBy { normName(it.name) }.values) {
            if (g.size < 2 || g.size > 40) continue
            val s = g.sortedBy { it.durationMs }
            for (i in s.indices) for (j in i + 1 until s.size) {
                if (s[j].durationMs - s[i].durationMs > tol(s[i].durationMs)) break
                val pa = phash[s[i].id]; val pb = phash[s[j].id]
                if (pa != null && pb != null) {
                    val (valid, ok) = compare(pa, pb)
                    if (valid >= 3 && ok * 2 < valid) continue // clearly different pictures
                }
                union(idx[s[i].id]!!, idx[s[j].id]!!, 0)
            }
        }

        val comps = HashMap<Int, MutableList<DupVideo>>()
        for ((i, v) in vids.withIndex()) comps.getOrPut(find(i)) { ArrayList() }.add(v)
        val groups = ArrayList<Pair<DupKind, List<DupVideo>>>()
        for ((root, list) in comps) {
            if (list.size < 2) continue
            val kind = when (strength[root]) { 2 -> DupKind.EXACT; 1 -> DupKind.SIMILAR; else -> DupKind.NAME }
            groups.add(kind to list)
        }
        groups.sortByDescending { (_, l) -> l.sumOf { it.size } - l.maxOf { it.size } }
        groups.mapIndexed { i, (k, l) -> DupGroup(i, k, l.sortedByDescending { it.size }) }
    }

    private fun tol(d: Long): Long = max(300L, d / 100)

    private fun compare(a: PH, b: PH): Pair<Int, Int> {
        var valid = 0
        var ok = 0
        for (k in 0 until FRAMES) {
            if (((a.flat shr k) and 1) == 1 || ((b.flat shr k) and 1) == 1) continue
            valid++
            if (java.lang.Long.bitCount(a.h[k] xor b.h[k]) <= 10) ok++
        }
        return valid to ok
    }

    private val COPY_SUFFIX = Regex("""[\s_\-]*(\(\d+\)|copy(\s*\d+)?)$""", RegexOption.IGNORE_CASE)

    private fun normName(n: String): String {
        var s = n.substringBeforeLast('.', n).trim()
        var prev: String
        do { prev = s; s = COPY_SUFFIX.replace(s, "").trim() } while (s != prev)
        return s.lowercase()
    }

    // ---- hashing ----

    private fun exactHash(ctx: Context, v: DupVideo): String? {
        val pfd = ctx.contentResolver.openFileDescriptor(v.uri, "r") ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ins ->
            val ch = ins.channel
            val size = ch.size()
            val md = MessageDigest.getInstance("SHA-1")
            md.update(size.toString().toByteArray())
            val chunk = 1 shl 20
            val buf = ByteBuffer.allocate(chunk)
            val offsets = if (size <= 3L * chunk) longArrayOf(0L, -1L)
            else longArrayOf(0L, size / 2 - chunk / 2, size - chunk)
            for (off in offsets) {
                if (off < 0) continue
                var pos = off
                val end = if (size <= 3L * chunk) size else off + chunk
                while (pos < end) {
                    buf.clear()
                    buf.limit(minOf(chunk.toLong(), end - pos).toInt())
                    val n = readAt(ch, buf, pos)
                    if (n <= 0) break
                    md.update(buf.array(), 0, n)
                    pos += n
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun readAt(ch: FileChannel, buf: ByteBuffer, pos: Long): Int = ch.read(buf, pos)

    private fun framesHash(ctx: Context, v: DupVideo): PH? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, v.uri)
            val h = LongArray(FRAMES)
            var flat = 0
            var got = 0
            for (k in 0 until FRAMES) {
                val tUs = v.durationMs * (2L * k + 1) / (2L * FRAMES) * 1000
                // OPTION_CLOSEST: re-encoded copies have different keyframes, so we need the exact frame
                val bmp = r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, 64, 64)
                if (bmp == null) { flat = flat or (1 shl k); continue }
                val (bits, isFlat) = dhash(bmp)
                bmp.recycle()
                h[k] = bits
                if (isFlat) flat = flat or (1 shl k) else got++
            }
            return if (got == 0) null else PH(h, flat)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } finally {
            r.release()
        }
    }

    /** 64-bit difference hash on a 9x8 grid of box-averaged grey levels. */
    private fun dhash(b: Bitmap): Pair<Long, Boolean> {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val cells = Array(8) { FloatArray(9) }
        var mn = 255f
        var mx = 0f
        for (cy in 0 until 8) for (cx in 0 until 9) {
            val x0 = cx * w / 9
            val x1 = max(x0 + 1, (cx + 1) * w / 9)
            val y0 = cy * h / 8
            val y1 = max(y0 + 1, (cy + 1) * h / 8)
            var sum = 0f
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val p = px[y * w + x]
                sum += 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                n++
            }
            val avg = sum / max(n, 1)
            cells[cy][cx] = avg
            if (avg < mn) mn = avg
            if (avg > mx) mx = avg
        }
        var bits = 0L
        var i = 0
        for (cy in 0 until 8) for (cx in 0 until 8) {
            if (cells[cy][cx] < cells[cy][cx + 1]) bits = bits or (1L shl i)
            i++
        }
        return bits to (mx - mn < 10f)
    }

    private fun encodePh(p: PH): String = p.h.joinToString(",") { java.lang.Long.toHexString(it) } + "|" + p.flat

    private fun parsePh(s: String): PH? = try {
        val parts = s.split("|")
        val h = parts[0].split(",").map { java.lang.Long.parseUnsignedLong(it, 16) }.toLongArray()
        if (h.size == FRAMES) PH(h, parts[1].toInt()) else null
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> parallel(
        items: List<T>,
        onProgress: (String, Int, Int) -> Unit,
        stage: String,
        block: (T) -> Unit,
    ) {
        onProgress(stage, 0, items.size)
        if (items.isEmpty()) return
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        coroutineScope {
            repeat(WORKERS) {
                launch {
                    while (true) {
                        ensureActive()
                        val i = next.getAndIncrement()
                        if (i >= items.size) break
                        try {
                            block(items[i])
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // unreadable file: skip it
                        }
                        val d = done.incrementAndGet()
                        if (d % 8 == 0 || d == items.size) onProgress(stage, d, items.size)
                    }
                }
            }
        }
    }
}
