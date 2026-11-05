package com.facevideo.search

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class Sort { NEWEST, SHORTEST, LONGEST }

data class Stats(val total: Int = 0, val indexed: Int = 0, val faces: Int = 0)

data class VideoItem(
    val id: Long,
    val name: String,
    val durationMs: Long,
    val dateAdded: Long,
    val score: Float = 0f,
    val hits: Int = 0,
    val bestMs: Long = 0L,
    val fromFace: Boolean = false,
) {
    val uri: Uri get() = ContentUris.withAppendedId(Db.videoBase(), id)
}

/**
 * Local SQLite store:
 *  videos(id = MediaStore id, name, duration, size, scanned flag)
 *  faces(video_id, time_ms, emb = 512 float32 little-endian)
 */
class Db private constructor(private val appCtx: Context) :
    SQLiteOpenHelper(appCtx, "faces.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE videos(id INTEGER PRIMARY KEY, name TEXT, duration_ms INTEGER, " +
                "size INTEGER, date_added INTEGER, scanned INTEGER NOT NULL DEFAULT 0, " +
                "faces INTEGER NOT NULL DEFAULT 0, width INTEGER NOT NULL DEFAULT 0, " +
                "height INTEGER NOT NULL DEFAULT 0, bucket TEXT)"
        )
        db.execSQL("CREATE TABLE vhash(video_id INTEGER PRIMARY KEY, exact TEXT, phash TEXT)")
        db.execSQL("CREATE TABLE faces(video_id INTEGER NOT NULL, time_ms INTEGER NOT NULL, emb BLOB NOT NULL)")
        db.execSQL("CREATE INDEX idx_faces_video ON faces(video_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: embeddings now come from ArcFace; the old FaceNet vectors are not comparable -> rescan
        if (oldVersion < 2) {
            db.execSQL("DELETE FROM faces")
            db.execSQL("UPDATE videos SET scanned=0, faces=0")
        }
        if (oldVersion < 3) {
            // v3: duplicate finder needs resolution + folder, and a cache of file/frame hashes
            db.execSQL("ALTER TABLE videos ADD COLUMN width INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE videos ADD COLUMN height INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE videos ADD COLUMN bucket TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS vhash(video_id INTEGER PRIMARY KEY, exact TEXT, phash TEXT)")
        }
    }

    private class Row(
        val id: Long, val name: String, val dur: Long, val size: Long, val date: Long,
        val w: Int, val h: Int, val bucket: String,
    )

    /** Reads every video from MediaStore (fast: metadata only) and syncs the table. */
    fun syncVideos() {
        val rows = ArrayList<Row>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        appCtx.contentResolver.query(videoBase(), proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Row(
                        c.getLong(0), c.getString(1) ?: "video", c.getLong(2), c.getLong(3), c.getLong(4),
                        c.getInt(5), c.getInt(6), c.getString(7) ?: "",
                    )
                )
            }
        }
        val db = writableDatabase
        val known = HashMap<Long, Long>()
        db.rawQuery("SELECT id, size FROM videos", null).use { c ->
            while (c.moveToNext()) known[c.getLong(0)] = c.getLong(1)
        }
        db.beginTransaction()
        try {
            val seen = HashSet<Long>()
            for (r in rows) {
                seen.add(r.id)
                val cv = ContentValues().apply {
                    put("name", r.name)
                    put("duration_ms", r.dur)
                    put("size", r.size)
                    put("date_added", r.date)
                    put("width", r.w)
                    put("height", r.h)
                    put("bucket", r.bucket)
                }
                val oldSize = known[r.id]
                if (oldSize == null) {
                    cv.put("id", r.id)
                    db.insert("videos", null, cv)
                } else {
                    if (oldSize != r.size) { // file was edited -> needs a fresh scan
                        cv.put("scanned", 0)
                        cv.put("faces", 0)
                        db.delete("faces", "video_id=?", arrayOf(r.id.toString()))
                        db.delete("vhash", "video_id=?", arrayOf(r.id.toString()))
                    }
                    db.update("videos", cv, "id=?", arrayOf(r.id.toString()))
                }
            }
            if (rows.isNotEmpty()) {
                for (id in known.keys) {
                    if (id !in seen) {
                        db.delete("faces", "video_id=?", arrayOf(id.toString()))
                        db.delete("vhash", "video_id=?", arrayOf(id.toString()))
                        db.delete("videos", "id=?", arrayOf(id.toString()))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun pendingVideos(): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        // short videos first so results show up early
        readableDatabase.rawQuery(
            "SELECT id,name,duration_ms,date_added FROM videos WHERE scanned=0 ORDER BY duration_ms ASC", null
        ).use { c ->
            while (c.moveToNext()) out.add(VideoItem(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getLong(3)))
        }
        return out
    }

    fun saveFaces(videoId: Long, faces: List<Pair<Long, FloatArray>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("faces", "video_id=?", arrayOf(videoId.toString()))
            for ((t, e) in faces) {
                val cv = ContentValues()
                cv.put("video_id", videoId)
                cv.put("time_ms", t)
                cv.put("emb", toBlob(e))
                db.insert("faces", null, cv)
            }
            val cv = ContentValues()
            cv.put("scanned", 1)
            cv.put("faces", faces.size)
            db.update("videos", cv, "id=?", arrayOf(videoId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun resetFaces() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("faces", null, null)
            db.execSQL("UPDATE videos SET scanned=0, faces=0")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Everything the duplicate finder needs, straight from the local table. */
    fun allForDedupe(): List<DupVideo> {
        val out = ArrayList<DupVideo>()
        readableDatabase.rawQuery(
            "SELECT id,name,duration_ms,size,width,height,date_added,bucket FROM videos", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DupVideo(
                        c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getLong(3),
                        c.getInt(4), c.getInt(5), c.getLong(6), c.getString(7) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** id -> (exact hash, frame hash); either may be null when not computed yet. */
    fun getHashes(): Map<Long, Pair<String?, String?>> {
        val m = HashMap<Long, Pair<String?, String?>>()
        readableDatabase.rawQuery("SELECT video_id, exact, phash FROM vhash", null).use { c ->
            while (c.moveToNext()) m[c.getLong(0)] = c.getString(1) to c.getString(2)
        }
        return m
    }

    fun saveExact(id: Long, exact: String) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO vhash(video_id) VALUES(?)", arrayOf<Any?>(id))
        db.execSQL("UPDATE vhash SET exact=? WHERE video_id=?", arrayOf<Any?>(exact, id))
    }

    fun savePhash(id: Long, phash: String) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO vhash(video_id) VALUES(?)", arrayOf<Any?>(id))
        db.execSQL("UPDATE vhash SET phash=? WHERE video_id=?", arrayOf<Any?>(phash, id))
    }

    fun stats(): Stats {
        readableDatabase.rawQuery("SELECT COUNT(*), SUM(scanned), SUM(faces) FROM videos", null).use { c ->
            if (c.moveToFirst()) return Stats(c.getInt(0), c.getInt(1), c.getInt(2))
        }
        return Stats()
    }

    /** Plain duration filter (no face involved). */
    fun listVideos(minMs: Long, maxMs: Long, sort: Sort): List<VideoItem> {
        val order = when (sort) {
            Sort.NEWEST -> "date_added DESC"
            Sort.SHORTEST -> "duration_ms ASC"
            Sort.LONGEST -> "duration_ms DESC"
        }
        val out = ArrayList<VideoItem>()
        readableDatabase.rawQuery(
            "SELECT id,name,duration_ms,date_added FROM videos " +
                "WHERE duration_ms>=? AND duration_ms<=? ORDER BY $order",
            arrayOf(minMs.toString(), maxMs.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(VideoItem(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getLong(3)))
        }
        return out
    }

    private class Acc(var best: Float, var bestMs: Long, var hits: Int)

    /**
     * Compares the reference embeddings with every stored face (streamed, so memory stays low).
     * Embeddings are L2-normalised, so dot product == cosine similarity.
     */
    fun searchByFace(refs: List<FloatArray>, threshold: Float, minMs: Long, maxMs: Long): List<VideoItem> {
        val acc = HashMap<Long, Acc>()
        val tmp = FloatArray(FaceEngine.EMB_SIZE)
        readableDatabase.rawQuery(
            "SELECT f.video_id, f.time_ms, f.emb FROM faces f JOIN videos v ON v.id=f.video_id " +
                "WHERE v.duration_ms>=? AND v.duration_ms<=?",
            arrayOf(minMs.toString(), maxMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                fromBlob(c.getBlob(2), tmp)
                var s = -1f
                for (r in refs) {
                    val d = dot(r, tmp)
                    if (d > s) s = d
                }
                if (s >= threshold) {
                    val vid = c.getLong(0)
                    val a = acc.getOrPut(vid) { Acc(s, c.getLong(1), 0) }
                    a.hits++
                    if (s > a.best) {
                        a.best = s
                        a.bestMs = c.getLong(1)
                    }
                }
            }
        }
        if (acc.isEmpty()) return emptyList()
        val out = ArrayList<VideoItem>()
        readableDatabase.rawQuery(
            "SELECT id,name,duration_ms,date_added FROM videos WHERE duration_ms>=? AND duration_ms<=?",
            arrayOf(minMs.toString(), maxMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val a = acc[id] ?: continue
                out.add(
                    VideoItem(id, c.getString(1) ?: "", c.getLong(2), c.getLong(3), a.best, a.hits, a.bestMs, true)
                )
            }
        }
        out.sortByDescending { it.score }
        return out
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun toBlob(e: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(e.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(e)
        return bb.array()
    }

    private fun fromBlob(blob: ByteArray, out: FloatArray) {
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
    }

    companion object {
        @Volatile private var inst: Db? = null

        fun get(ctx: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }

        fun videoBase(): Uri =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
}
