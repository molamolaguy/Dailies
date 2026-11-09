package com.dailygames.hub

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Reads the text in a screenshot (a game's result screen) on the phone, with ML Kit text recognition
 * from Google Play services. Nothing is uploaded. [done] gets the text, or null if it couldn't be read
 * (no Play services, unreadable image).
 */
object ScreenshotReader {
    fun read(ctx: Context, uri: Uri, done: (String?) -> Unit) {
        val image = runCatching { InputImage.fromFilePath(ctx, uri) }.getOrNull() ?: return done(null)
        val recognizer = runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }.getOrNull() ?: return done(null)
        try {
            recognizer.process(image)
                .addOnSuccessListener { done(it.text) }
                .addOnFailureListener { done(null) }
                .addOnCompleteListener { runCatching { recognizer.close() } }
        } catch (e: Exception) {
            done(null)
        }
    }
}
