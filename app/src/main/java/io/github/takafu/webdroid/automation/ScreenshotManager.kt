package io.github.takafu.webdroid.automation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.view.View
import android.webkit.WebView
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.minOf

data class ScreenshotOptions(
    val format: String = "jpeg",
    val quality: Int = 75,
    val scale: Float = 1.0f,
    val maxWidth: Int = 720,
    val maxHeight: Int = 1280
)

/**
 * Memory-conscious screenshot capture.
 *
 * Uses the WebView's actual dimensions (falling back to a measured layout when
 * the WebView has zero size, e.g. hidden/bubble state), scales down to fit the
 * requested maximum bounds while preserving aspect ratio, and encodes as JPEG by
 * default for a small memory footprint on the ~1.7 GB Galaxy A10.
 */
class ScreenshotManager {
    private companion object {
        private const val PNG = "png"
    }

    fun capture(webView: WebView, options: ScreenshotOptions): String? {
        if (webView.width <= 0 || webView.height <= 0) {
            val fbW = max(options.maxWidth, 1)
            val fbH = max(options.maxHeight, 1)
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(fbW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(fbH, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, fbW, fbH)
        }

        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0) return null

        val target = computeTargetSize(width, height, options)

        val bitmap: Bitmap? = try {
            Bitmap.createBitmap(target.first, target.second, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        } ?: run {
            val sw = max((target.first * 0.5).toInt(), 1)
            val sh = max((target.second * 0.5).toInt(), 1)
            try {
                Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            } catch (e2: Exception) {
                null
            }
        } ?: return null

        var base64: String? = null
        try {
            val canvas = Canvas(bitmap)
            // Scale the WebView content to the ACTUAL bitmap dimensions. When the
            // target bitmap could not be allocated and a smaller fallback bitmap
            // is used, scaling against `target` would draw content larger than the
            // canvas and clip it. Use bitmap.width/height so Canvas and bitmap
            // dimensions always match.
            val sx = bitmap.width.toFloat() / width
            val sy = bitmap.height.toFloat() / height
            canvas.scale(sx, sy)
            webView.draw(canvas)

            val stream = ByteArrayOutputStream()
            try {
                val fmt = options.format.lowercase()
                if (fmt == PNG) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                } else {
                    val q = max(0, min(100, options.quality))
                    bitmap.compress(Bitmap.CompressFormat.JPEG, q, stream)
                }
                base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } finally {
                stream.close()
            }
        } finally {
            bitmap.recycle()
        }
        return base64
    }

    private fun computeTargetSize(viewWidth: Int, viewHeight: Int, options: ScreenshotOptions): Pair<Int, Int> {
        var w = max((viewWidth.toFloat() * options.scale).toInt(), 1)
        var h = max((viewHeight.toFloat() * options.scale).toInt(), 1)
        val maxW = max(options.maxWidth, 1)
        val maxH = max(options.maxHeight, 1)
        // Fit within max bounds while preserving aspect ratio (never upscale).
        val fit = minOf(maxW.toFloat() / w, maxH.toFloat() / h, 1.0f)
        w = max((w * fit).toInt(), 1)
        h = max((h * fit).toInt(), 1)
        return Pair(w, h)
    }
}
