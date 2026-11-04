package io.github.takafu.webdroid.automation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

data class ScreenshotOptions(
    val format: String = "jpeg",
    val quality: Int = 75,
    val scale: Float = 1.0f,
    val maxWidth: Int = 720,
    val maxHeight: Int = 1280
)

/**
 * Result of a screenshot capture, including the actual produced image dimensions.
 */
data class ScreenshotResult(
    val base64: String,
    val format: String,
    val quality: Int,
    val width: Int,
    val height: Int,
    val sizeBytes: Int
)

/**
 * Memory-conscious screenshot capture.
 *
 * Uses the WebView's actual dimensions. If the WebView has not been laid out
 * (zero width/height), capture is refused with a clear error rather than
 * fabricating dimensions or permanently resizing the WebView layout.
 *
 * Scales down to fit the requested maximum bounds while preserving aspect ratio,
 * encodes as JPEG by default for a small memory footprint on the ~1.7 GB
 * Galaxy A10, and releases Bitmap/stream resources on every path.
 */
class ScreenshotManager {
    private companion object {
        private const val PNG = "png"
        private const val JPEG = "jpeg"
        private const val HALF_SCALE = 0.5f
    }

    /**
     * Validate and normalize [options]. Throws [IllegalArgumentException] on the
     * first invalid value. Returns normalized options.
     */
    internal fun validateOptions(options: ScreenshotOptions): ScreenshotOptions {
        val format = options.format.lowercase()
        if (format != PNG && format != JPEG) {
            throw IllegalArgumentException("Invalid format '${options.format}': expected 'jpeg' or 'png'")
        }
        if (options.quality < 0 || options.quality > 100) {
            throw IllegalArgumentException("Invalid quality '${options.quality}': must be between 0 and 100")
        }
        val scale = options.scale
        if (scale.isNaN() || scale.isInfinite() || scale <= 0f) {
            throw IllegalArgumentException("Invalid scale '$scale': must be a positive finite number")
        }
        if (options.maxWidth < 1) {
            throw IllegalArgumentException("Invalid max_width '${options.maxWidth}': must be >= 1")
        }
        if (options.maxHeight < 1) {
            throw IllegalArgumentException("Invalid max_height '${options.maxHeight}': must be >= 1")
        }
        return ScreenshotOptions(
            format = format,
            quality = options.quality.coerceIn(0, 100),
            scale = scale,
            maxWidth = options.maxWidth,
            maxHeight = options.maxHeight
        )
    }

    fun capture(webView: WebView, options: ScreenshotOptions): ScreenshotResult? {
        val opt = validateOptions(options)

        // Use the WebView's real dimensions. Never measure/layout the WebView
        // just to take a screenshot: that would permanently alter the display.
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0) {
            throw IllegalStateException("WebView is not laid out (${width}x${height}); cannot capture screenshot")
        }

        val target = computeTargetSize(width, height, opt)

        var bitmap: Bitmap? = null
        // Primary allocation attempt, then a single smaller fallback. No retry
        // loop under memory pressure.
        try {
            bitmap = Bitmap.createBitmap(target.first, target.second, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w("ScreenshotManager", "Primary bitmap allocation failed (OOM), trying fallback", e)
            val sw = max((target.first * HALF_SCALE).toInt(), 1)
            val sh = max((target.second * HALF_SCALE).toInt(), 1)
            try {
                bitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            } catch (e2: OutOfMemoryError) {
                Log.e("ScreenshotManager", "Fallback bitmap allocation also failed (OOM)", e2)
                bitmap = null
            }
        } catch (e: Exception) {
            Log.w("ScreenshotManager", "Primary bitmap allocation failed", e)
            bitmap = null
        }
        if (bitmap == null) return null

        val actualWidth = bitmap.width
        val actualHeight = bitmap.height

        var base64: String? = null
        var compressOk = false
        try {
            val canvas = Canvas(bitmap)
            // Map WebView content onto the ACTUAL bitmap dimensions so Canvas and
            // bitmap always match, even when a fallback bitmap is used.
            val sx = actualWidth.toFloat() / width
            val sy = actualHeight.toFloat() / height
            canvas.scale(sx, sy)
            webView.draw(canvas)

            val stream = ByteArrayOutputStream()
            try {
                compressOk = when {
                    opt.format == PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    else -> bitmap.compress(Bitmap.CompressFormat.JPEG, opt.quality, stream)
                }
                if (!compressOk) {
                    Log.e("ScreenshotManager", "bitmap.compress() returned false")
                    return null
                }
                val bytes = stream.toByteArray()
                base64 = String(Base64.encode(bytes, 0, bytes.size, Base64.NO_WRAP))
            } finally {
                stream.close()
            }
        } catch (e: OutOfMemoryError) {
            Log.e("ScreenshotManager", "OutOfMemoryError during screenshot encoding", e)
            return null
        } catch (e: Exception) {
            Log.e("ScreenshotManager", "Screenshot encoding failed", e)
            return null
        } finally {
            bitmap.recycle()
        }

        if (base64 == null) return null

        return ScreenshotResult(
            base64 = base64,
            format = opt.format,
            quality = opt.quality,
            width = actualWidth,
            height = actualHeight,
            sizeBytes = base64.length
        )
    }

    internal fun computeTargetSize(viewWidth: Int, viewHeight: Int, options: ScreenshotOptions): Pair<Int, Int> {
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
