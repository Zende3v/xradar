package com.xradar.app.feature.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.xradar.app.designsystem.component.XRadarText
import com.xradar.app.designsystem.theme.XRadarTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

private val imageClient = OkHttpClient()

/** Circular avatar: loads [url] into an image, falling back to the [initial] letter. */
@Composable
fun AsyncAvatar(url: String?, initial: String, size: Dp) {
    val colors = XRadarTheme.colors
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = if (url == null) null else withContext(Dispatchers.IO) { loadBitmap(url) }
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(colors.accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(bitmap = b, contentDescription = null, modifier = Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            XRadarText(initial.take(1).uppercase(), style = XRadarTheme.typography.titleLarge, color = colors.accent)
        }
    }
}

private fun loadBitmap(url: String): ImageBitmap? = runCatching {
    imageClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
        val bytes = r.body?.bytes() ?: return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
}.getOrNull()

/** Read a picked image, downscale to ~256 px, and return a base64 data URL (JPEG). */
fun encodeAvatar(context: Context, uri: Uri): String? = runCatching {
    val src = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) } ?: return null
    val max = 256
    val scale = minOf(1f, max.toFloat() / maxOf(src.width, src.height))
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
    } else {
        src
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
    "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}.getOrNull()
