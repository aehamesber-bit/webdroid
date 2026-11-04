package io.github.takafu.webdroid.automation

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAutomationManagerTest {

    private val manager = BrowserAutomationManager(Gson()) { }

    @Test
    fun `boundTimeout clamps to min and max`() {
        assertEquals(500L, manager.boundTimeout(100L))
        assertEquals(500L, manager.boundTimeout(0L))
        assertEquals(500L, manager.boundTimeout(-10L))
        assertEquals(10000L, manager.boundTimeout(10000L))
        assertEquals(300000L, manager.boundTimeout(999999L))
        assertEquals(300000L, manager.boundTimeout(600000L))
    }
}
