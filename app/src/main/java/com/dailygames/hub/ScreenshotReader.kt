package com.dailygames.hub

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the text in a screenshot (a game's result screen) on the phone, with ML Kit text recognition
 * from Google Play services. Nothing is uploaded. [done] gets the text, or null if it couldn't be read
 * (no Play services, unreadable image).
 */
object ScreenshotReader {
    fun read(ctx: Context, uri: Uri, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        val finished = AtomicBoolean(false)
        fun finish(text: String?) {
            if (finished.compareAndSet(false, true)) done(text)
        }
        Thread({
            val image = runCatching { InputImage.fromFilePath(app, uri) }.getOrNull()
            if (image == null) {
                finish(null)
                return@Thread
            }
            val recognizer = runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }.getOrNull()
            if (recognizer == null) {
                finish(null)
                return@Thread
            }
            try {
                recognizer.process(image)
                    .addOnSuccessListener { finish(it.text) }
                    .addOnFailureListener { finish(null) }
                    .addOnCanceledListener { finish(null) }
                    .addOnCompleteListener { runCatching { recognizer.close() } }
            } catch (e: Exception) {
                runCatching { recognizer.close() }
                finish(null)
            }
        }, "screenshot-ocr").apply { isDaemon = true }.start()
    }
}
