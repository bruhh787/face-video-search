package com.facevideo.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Face pipeline:  ML Kit detector (accurate mode) -> roll-aligned square crop with margin ->
 * FaceNet-512 (Inception-ResNet-v1, VGGFace2) -> L2-normalised 512-d embedding.
 */
class FaceEngine(ctx: Context) : AutoCloseable {

    class Result(val emb: FloatArray, val preview: Bitmap?)

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.05f)
            .build()
    )

    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(EMB_SIZE) }
    private val pixels = IntArray(SIZE * SIZE)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val fd = ctx.assets.openFd(MODEL_FILE)
        val buf = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(buf, Interpreter.Options().setNumThreads(4))
    }

    /**
     * @param strict true for video frames (skips tiny / strongly turned faces, which give noisy matches);
     *               false for the reference photo the user picks.
     */
    @Synchronized
    fun detect(frame: Bitmap, strict: Boolean, wantPreview: Boolean = false, maxFaces: Int = 6): List<Result> {
        val bmp = if (frame.config == Bitmap.Config.ARGB_8888) frame else frame.copy(Bitmap.Config.ARGB_8888, false)
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
        val picked = faces
            .filter { f ->
                val b = f.boundingBox
                !strict || (min(b.width(), b.height()) >= MIN_FACE_PX &&
                    abs(f.headEulerAngleY) <= 40f && abs(f.headEulerAngleX) <= 30f)
            }
            .sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
            .take(maxFaces)

        val out = ArrayList<Result>()
        for (f in picked) {
            val crop = crop(bmp, f.boundingBox, f.headEulerAngleZ)
            val emb = embed(crop)
            out.add(Result(emb, if (wantPreview) crop else null))
            if (!wantPreview) crop.recycle()
        }
        if (bmp !== frame) bmp.recycle()
        return out
    }

    private fun crop(src: Bitmap, box: Rect, rollDeg: Float): Bitmap {
        val side = max(box.width(), box.height()) * 1.25f
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.postTranslate(-box.exactCenterX(), -box.exactCenterY())
        m.postRotate(rollDeg.coerceIn(-45f, 45f)) // ML Kit: positive Z = counter-clockwise -> rotate clockwise to level
        m.postScale(SIZE / side, SIZE / side)
        m.postTranslate(SIZE / 2f, SIZE / 2f)
        Canvas(out).drawBitmap(src, m, paint)
        return out
    }

    private fun embed(crop: Bitmap): FloatArray {
        crop.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val n = SIZE * SIZE * 3
        var sum = 0.0
        for (p in pixels) sum += ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
        val mean = sum / n
        var sq = 0.0
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) - mean
            val g = ((p shr 8) and 0xFF) - mean
            val b = (p and 0xFF) - mean
            sq += r * r + g * g + b * b
        }
        val std = max(sqrt(sq / n), 1.0 / sqrt(n.toDouble())) // per-image standardisation (FaceNet)
        input.rewind()
        for (p in pixels) {
            input.putFloat((((p shr 16) and 0xFF) - mean).div(std).toFloat())
            input.putFloat((((p shr 8) and 0xFF) - mean).div(std).toFloat())
            input.putFloat(((p and 0xFF) - mean).div(std).toFloat())
        }
        input.rewind()
        interpreter.run(input, output)
        val e = output[0].copyOf()
        var norm = 0f
        for (v in e) norm += v * v
        norm = sqrt(norm).coerceAtLeast(1e-9f)
        for (i in e.indices) e[i] /= norm
        return e
    }

    override fun close() {
        detector.close()
        interpreter.close()
    }

    companion object {
        const val MODEL_FILE = "facenet_512.tflite"
        const val SIZE = 160
        const val EMB_SIZE = 512
        const val MIN_FACE_PX = 56
    }
}
