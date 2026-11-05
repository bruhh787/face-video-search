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
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Face pipeline:  ML Kit detector (accurate mode, with landmarks) -> 112x112 similarity-aligned crop
 * (eyes + mouth corners mapped onto the standard ArcFace template) -> ArcFace (InsightFace, WebFace600K)
 * via ONNX Runtime -> L2-normalised 512-d embedding.
 */
class FaceEngine(ctx: Context) : AutoCloseable {

    class Result(val emb: FloatArray, val preview: Bitmap?)

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.05f)
            .build()
    )

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val paint get() = Paint(Paint.FILTER_BITMAP_FLAG) // per call: workers share this engine

    init {
        // ONNX Runtime wants a real file; copy the bundled model out of the APK once (it is large).
        val dst = File(ctx.filesDir, MODEL_FILE)
        val len = ctx.assets.openFd(MODEL_FILE).use { it.length }
        if (!dst.exists() || dst.length() != len) {
            val tmp = File(ctx.filesDir, "$MODEL_FILE.tmp")
            ctx.assets.open(MODEL_FILE).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            tmp.renameTo(dst)
        }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(dst.absolutePath, opts)
        inputName = session.inputNames.first()
    }

    /**
     * @param strict true for video frames (skips tiny / strongly turned faces, which give noisy matches);
     *               false for the reference photo the user picks.
     */
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
            val crop = align(bmp, f)
            val emb = embed(crop)
            out.add(Result(emb, if (wantPreview) crop else null))
            if (!wantPreview) crop.recycle()
        }
        if (bmp !== frame) bmp.recycle()
        return out
    }

    /** Warps the face onto the 112x112 ArcFace template using eyes + mouth corners (least-squares similarity). */
    private fun align(src: Bitmap, f: Face): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        val pts = landmarks(f)
        if (pts != null) {
            // template order: eyeL, eyeR, mouthL, mouthR (image-left first)
            val dst = floatArrayOf(
                38.2946f, 51.6963f, 73.5318f, 51.5014f,
                41.5493f, 92.3655f, 70.7299f, 92.2041f,
            )
            var pmx = 0f; var pmy = 0f; var qmx = 0f; var qmy = 0f
            for (i in 0 until 4) {
                pmx += pts[2 * i]; pmy += pts[2 * i + 1]; qmx += dst[2 * i]; qmy += dst[2 * i + 1]
            }
            pmx /= 4; pmy /= 4; qmx /= 4; qmy /= 4
            var num1 = 0f; var num2 = 0f; var den = 0f
            for (i in 0 until 4) {
                val px = pts[2 * i] - pmx; val py = pts[2 * i + 1] - pmy
                val qx = dst[2 * i] - qmx; val qy = dst[2 * i + 1] - qmy
                num1 += px * qx + py * qy
                num2 += px * qy - py * qx
                den += px * px + py * py
            }
            if (den > 1e-3f) {
                val a = num1 / den
                val b = num2 / den
                val tx = qmx - (a * pmx - b * pmy)
                val ty = qmy - (b * pmx + a * pmy)
                m.setValues(floatArrayOf(a, -b, tx, b, a, ty, 0f, 0f, 1f))
                Canvas(out).drawBitmap(src, m, paint)
                return out
            }
        }
        // fallback (no landmarks): level the face by roll angle and crop a square around the box
        val box = f.boundingBox
        val side = max(box.width(), box.height()) * 1.15f
        m.postTranslate(-box.exactCenterX(), -box.exactCenterY())
        m.postRotate(f.headEulerAngleZ.coerceIn(-45f, 45f))
        m.postScale(SIZE / side, SIZE / side)
        m.postTranslate(SIZE / 2f, SIZE / 2f)
        Canvas(out).drawBitmap(src, m, paint)
        return out
    }

    /** x,y of [eye(image-left), eye(image-right), mouth(image-left), mouth(image-right)] or null. */
    private fun landmarks(f: Face): FloatArray? {
        val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val ml = f.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return null
        val mr = f.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return null
        // ML Kit's left/right is from the subject's side; order by image x so we never depend on that
        val eyes: List<PointF> = listOf(le, re).sortedBy { it.x }
        val mouth: List<PointF> = listOf(ml, mr).sortedBy { it.x }
        return floatArrayOf(
            eyes[0].x, eyes[0].y, eyes[1].x, eyes[1].y,
            mouth[0].x, mouth[0].y, mouth[1].x, mouth[1].y,
        )
    }

    private fun embed(crop: Bitmap): FloatArray {
        // buffers are per call so several scan workers can share one engine
        val pixels = IntArray(SIZE * SIZE)
        crop.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        // NCHW, RGB, (x - 127.5) / 127.5
        val plane = SIZE * SIZE
        val inBuf: FloatBuffer = java.nio.ByteBuffer.allocateDirect(3 * plane * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until plane) {
            val p = pixels[i]
            inBuf.put(i, (((p shr 16) and 0xFF) - 127.5f) / 127.5f)
            inBuf.put(plane + i, (((p shr 8) and 0xFF) - 127.5f) / 127.5f)
            inBuf.put(2 * plane + i, ((p and 0xFF) - 127.5f) / 127.5f)
        }
        inBuf.rewind()
        val tensor = OnnxTensor.createTensor(env, inBuf, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
        val e: FloatArray = try {
            val r = session.run(mapOf(inputName to tensor))
            try {
                @Suppress("UNCHECKED_CAST")
                (r[0].value as Array<FloatArray>)[0].copyOf()
            } finally {
                r.close()
            }
        } finally {
            tensor.close()
        }
        var norm = 0f
        for (v in e) norm += v * v
        norm = sqrt(norm).coerceAtLeast(1e-9f)
        for (i in e.indices) e[i] /= norm
        return e
    }

    override fun close() {
        detector.close()
        session.close()
    }

    companion object {
        const val MODEL_FILE = "arcface.onnx"
        const val SIZE = 112
        const val EMB_SIZE = 512
        const val MIN_FACE_PX = 56
    }
}
