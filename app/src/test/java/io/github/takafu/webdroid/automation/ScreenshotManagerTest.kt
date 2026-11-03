package io.github.takafu.webdroid.automation

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class ScreenshotManagerTest {

    private val manager = ScreenshotManager()

    @Test
    fun `computeTargetSize fits within max bounds preserving aspect ratio`() {
        // 1080x1920 scaled to fit 720x1280 (0.667 factor both) -> 720x1280
        val (w, h) = manager.computeTargetSize(1080, 1920, ScreenshotOptions(maxWidth = 720, maxHeight = 1280))
        assertEquals(720, w)
        assertEquals(1280, h)

        // Landscape 1920x1080 fit 720x1280: width is limiting (0.375) -> 720x405
        val (w2, h2) = manager.computeTargetSize(1920, 1080, ScreenshotOptions(maxWidth = 720, maxHeight = 1280))
        assertEquals(720, w2)
        assertEquals(405, h2)

        // Small image never upscaled beyond max; scale=1 keeps size when under max
        val (w3, h3) = manager.computeTargetSize(360, 640, ScreenshotOptions(scale = 1.0f, maxWidth = 720, maxHeight = 1280))
        assertEquals(360, w3)
        assertEquals(640, h3)
    }

    @Test
    fun `computeTargetSize applies scale then clamps to max`() {
        val (w, h) = manager.computeTargetSize(360, 640, ScreenshotOptions(scale = 2.0f, maxWidth = 720, maxHeight = 1280))
        assertEquals(720, w)
        assertEquals(1280, h)
    }

    @Test
    fun `validateOptions accepts valid options`() {
        val opt = manager.validateOptions(ScreenshotOptions(format = "png", quality = 90, scale = 1.5f, maxWidth = 1080, maxHeight = 1920))
        assertEquals("png", opt.format)
        assertEquals(90, opt.quality)
        assertEquals(1.5f, opt.scale, 0.001f)
        assertEquals(1080, opt.maxWidth)
        assertEquals(1920, opt.maxHeight)
    }

    @Test
    fun `validateOptions normalizes format to lowercase`() {
        val opt = manager.validateOptions(ScreenshotOptions(format = "JPEG"))
        assertEquals("jpeg", opt.format)
    }

    @Test
    fun `validateOptions rejects invalid format`() {
        assertFailsWith<IllegalArgumentException> {
            manager.validateOptions(ScreenshotOptions(format = "webp"))
        }
    }

    @Test
    fun `validateOptions rejects out of range quality`() {
        assertFailsWith<IllegalArgumentException> {
            manager.validateOptions(ScreenshotOptions(quality = 150))
        }
        assertFailsWith<IllegalArgumentException> {
            manager.validateOptions(ScreenshotOptions(quality = -1))
        }
    }

    @Test
    fun `validateOptions rejects invalid scale`() {
        assertFailsWith<IllegalArgumentException> {
            manager.validateOptions(ScreenshotOptions(scale = 0f))
        }
        assertFailsWith<IllegalArgumentException> {
            manager.validateOptions(ScreenshotOptions(scale = -1f))
        }
        assertFailsWith<IllegalArgumentException> {
            manager.validateOptions(ScreenshotOptions(scale = Float.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            manager.validateOptions(ScreenshotOptions(scale = Float.POSITIVE_INFINITY))
        }
    }

    @Test
    fun `validateOptions rejects zero max dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            manager.validateOptions(ScreenshotOptions(maxWidth = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            manager.validateOptions(ScreenshotOptions(maxHeight = 0))
        }
    }
}
