package com.facevideo.search

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * "Scan with duration in mind": re-reads every video from the device and, for videos whose
 * length Android doesn't report, measures it directly from the file.
 */
object DurationScan {
    suspend fun run(ctx: Context, onProgress: (done: Int, total: Int) -> Unit) = withContext(Dispatchers.IO) {
        val db = Db.get(ctx)
        db.syncVideos()
        val missing = ArrayList<Long>()
        db.readableDatabase.rawQuery("SELECT id FROM videos WHERE duration_ms<=0", null).use { c ->
            while (c.moveToNext()) missing.add(c.getLong(0))
        }
        onProgress(0, missing.size)
        for ((i, id) in missing.withIndex()) {
            ensureActive()
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, ContentUris.withAppendedId(Db.videoBase(), id))
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (d > 0) {
                    val cv = ContentValues()
                    cv.put("duration_ms", d)
                    db.writableDatabase.update("videos", cv, "id=?", arrayOf(id.toString()))
                }
            } catch (e: Exception) {
                // unreadable file: leave its duration as unknown
            } finally {
                r.release()
            }
            onProgress(i + 1, missing.size)
        }
    }
}
