package com.dailygames.hub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the text in a screenshot (a game's result screen) on the phone, with ML Kit text recognition
 * from Google Play services. Nothing is uploaded.
 *
 * [readDetailed] delivers `(text, error)` where `error` is null on success (even when `text` is
 * empty `""`), or `"unreadable_image"`, `"model_unavailable"`, or `"ocr_failed"` on failure.
 */
object ScreenshotReader {
    private const val MAX_DIM = 2048

    const val ERR_UNREADABLE = "unreadable_image"
    const val ERR_MODEL_UNAVAILABLE = "model_unavailable"
    const val ERR_OCR_FAILED = "ocr_failed"

    fun read(ctx: Context, uri: Uri, done: (String?) -> Unit) {
        readDetailed(ctx, uri) { text, _ -> done(text) }
    }

    fun readDetailed(ctx: Context, uri: Uri, done: (text: String?, error: String?) -> Unit) {
        val app = ctx.applicationContext
        val finished = AtomicBoolean(false)
        fun finish(text: String?, error: String?) {
            if (finished.compareAndSet(false, true)) done(text, error)
        }
        Thread({
            var bitmap: Bitmap? = null
            try {
                val (decoded, rotationDegrees) = decodeSampledAndOriented(app, uri)
                bitmap = decoded
                if (bitmap == null) {
                    finish(null, ERR_UNREADABLE)
                    return@Thread
                }
                val image = InputImage.fromBitmap(bitmap, rotationDegrees)
                val recognizer = runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }.getOrNull()
                if (recognizer == null) {
                    runCatching { bitmap.recycle() }
                    finish(null, ERR_MODEL_UNAVAILABLE)
                    return@Thread
                }
                val bmpRef = bitmap
                bitmap = null
                recognizer.process(image)
                    .addOnSuccessListener { finish(it.text, null) }
                    .addOnFailureListener { e -> finish(null, classifyError(e)) }
                    .addOnCanceledListener { finish(null, ERR_OCR_FAILED) }
                    .addOnCompleteListener {
                        runCatching { recognizer.close() }
                        runCatching { if (!bmpRef.isRecycled) bmpRef.recycle() }
                    }
            } catch (e: Throwable) {
                runCatching { if (bitmap != null && !bitmap.isRecycled) bitmap.recycle() }
                finish(null, classifyError(e))
            }
        }, "screenshot-ocr").apply { isDaemon = true }.start()
    }

    private fun decodeSampledAndOriented(ctx: Context, uri: Uri): Pair<Bitmap?, Int> {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null to 0
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null to 0

        var inSampleSize = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / inSampleSize > MAX_DIM) {
            inSampleSize *= 2
        }

        val rotationDegrees = runCatching {
            cr.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)

        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        return bitmap to rotationDegrees
    }

    private fun classifyError(e: Throwable?): String {
        if (e is MlKitException) {
            return when (e.errorCode) {
                MlKitException.UNAVAILABLE,
                MlKitException.UNIMPLEMENTED,
                MlKitException.MODEL_INCOMPATIBLE_WITH_TFLITE,
                MlKitException.NOT_ENOUGH_SPACE,
                MlKitException.MODEL_HASH_MISMATCH,
                MlKitException.NOT_FOUND,
                MlKitException.FAILED_PRECONDITION -> ERR_MODEL_UNAVAILABLE
                else -> ERR_OCR_FAILED
            }
        }
        val msg = e?.message.orEmpty()
        if (msg.contains("model", ignoreCase = true) ||
            msg.contains("play services", ignoreCase = true) ||
            msg.contains("unavailable", ignoreCase = true)
        ) {
            return ERR_MODEL_UNAVAILABLE
        }
        return ERR_OCR_FAILED
    }
}

