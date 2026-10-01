package com.eona.app.data.bugs

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Prépare capture JPEG sans charger image originale en pleine résolution. */
object BugScreenshot {
    suspend fun read(resolver: ContentResolver, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
            val original = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val ratio = minOf(1.0, 1600.0 / maxOf(original.width, original.height))
            val scaled = Bitmap.createScaledBitmap(original,
                (original.width * ratio).roundToInt().coerceAtLeast(1),
                (original.height * ratio).roundToInt().coerceAtLeast(1), true)
            if (scaled !== original) original.recycle()
            try {
                for (quality in listOf(80, 65, 50)) {
                    val output = ByteArrayOutputStream()
                    if (scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        val bytes = output.toByteArray()
                        if (bytes.size <= 1024 * 1024) return@runCatching bytes
                    }
                }
                null
            } finally { scaled.recycle() }
        }.getOrNull()
    }
}
