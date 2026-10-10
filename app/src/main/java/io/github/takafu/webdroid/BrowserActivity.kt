package io.github.takafu.webdroid

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class BrowserActivity : Activity() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        var instance: BrowserActivity? = null

        var webView: WebView? = null
    }

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private var servicesStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        showStartupScreen()
        refreshStartupScreen()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized && Settings.canDrawOverlays(this) && !servicesStarted) {
            startWebDroidServices()
        } else if (::statusText.isInitialized) {
            refreshStartupScreen()
        }
    }

    private fun showStartupScreen() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "WebDroid"
            textSize = 26f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, padding / 2, 0, padding / 2)
        }
        actionButton = Button(this)
        actionButton.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startWebDroidServices()
            } else {
                requestOverlayPermission()
            }
        }

        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(actionButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)
    }

    private fun refreshStartupScreen() {
        if (Settings.canDrawOverlays(this)) {
            statusText.text = "Overlay permission is granted. WebDroid will start its services."
            actionButton.text = "Start WebDroid"
        } else {
            statusText.text = "Allow WebDroid to display over other apps. This permission is required for the floating browser."
            actionButton.text = "Grant display-over-apps permission"
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (fallbackError: Exception) {
                Log.e("BrowserActivity", "Unable to open overlay permission settings", fallbackError)
                statusText.text = "Open Settings > Apps > WebDroid > Appear on top, then return here."
            }
        }
    }

    private fun startWebDroidServices() {
        if (!Settings.canDrawOverlays(this)) {
            refreshStartupScreen()
            return
        }
        if (servicesStarted) return

        try {
            val automationIntent = Intent(this, AutomationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(automationIntent)
            } else {
                startService(automationIntent)
            }
            startService(Intent(this, FloatingBubbleService::class.java))
            servicesStarted = true
            statusText.text = "WebDroid is running. The floating browser should appear on screen."
            actionButton.text = "WebDroid started"
            actionButton.isEnabled = false
        } catch (e: Exception) {
            Log.e("BrowserActivity", "Unable to start WebDroid services", e)
            statusText.text = "Startup failed: ${e.message ?: e.javaClass.simpleName}"
            actionButton.text = "Try again"
            actionButton.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
