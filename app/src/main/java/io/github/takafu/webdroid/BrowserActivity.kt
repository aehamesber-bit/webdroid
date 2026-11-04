package io.github.takafu.webdroid

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
    private lateinit var apiTokenText: TextView
    private lateinit var tokenButton: Button
    private lateinit var copyTokenButton: Button
    private lateinit var unsafeJavaScriptButton: Button
    private var revealApiToken = false
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
        apiTokenText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, padding / 3, 0, padding / 3)
        }
        tokenButton = Button(this).apply {
            setOnClickListener {
                revealApiToken = !revealApiToken
                refreshApiSecurityControls()
            }
        }
        copyTokenButton = Button(this).apply {
            text = "Copy API token"
            setOnClickListener {
                val token = ApiSecurity.getOrCreateToken(this@BrowserActivity)
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("WebDroid API token", token))
                statusText.text = "API token copied. Keep it private."
            }
        }
        unsafeJavaScriptButton = Button(this).apply {
            setOnClickListener { toggleUnsafeJavaScript() }
        }
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
        root.addView(apiTokenText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(tokenButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(copyTokenButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(unsafeJavaScriptButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)
        refreshApiSecurityControls()
    }

    private fun refreshStartupScreen() {
        if (Settings.canDrawOverlays(this)) {
            statusText.text = "Overlay permission is granted. WebDroid will start its services."
            actionButton.text = "Start WebDroid"
        } else {
            statusText.text = "Allow WebDroid to display over other apps. This permission is required for the floating browser."
            actionButton.text = "Grant display-over-apps permission"
        }
        refreshApiSecurityControls()
    }

    private fun refreshApiSecurityControls() {
        if (!::apiTokenText.isInitialized) return
        val token = ApiSecurity.getOrCreateToken(this)
        apiTokenText.text = if (revealApiToken) {
            "API token (private):\n$token"
        } else {
            "API token: hidden"
        }
        tokenButton.text = if (revealApiToken) "Hide API token" else "Show API token"
        unsafeJavaScriptButton.text = if (ApiSecurity.isUnsafeJavaScriptEnabled(this)) {
            "Disable privileged JavaScript"
        } else {
            "Enable privileged JavaScript (unsafe)"
        }
    }

    private fun toggleUnsafeJavaScript() {
        val currentlyEnabled = ApiSecurity.isUnsafeJavaScriptEnabled(this)
        if (!currentlyEnabled) {
            AlertDialog.Builder(this)
                .setTitle("Enable privileged JavaScript?")
                .setMessage("This allows authenticated API callers to run arbitrary JavaScript in the current page. It can read passwords and other page data. Keep disabled unless you trust every process that has the API token.")
                .setPositiveButton("Enable") { _, _ ->
                    ApiSecurity.setUnsafeJavaScriptEnabled(this, true)
                    refreshApiSecurityControls()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            ApiSecurity.setUnsafeJavaScriptEnabled(this, false)
            refreshApiSecurityControls()
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
            statusText.text = "Startup requested. Confirm API readiness with browser_ping in Termux."
            actionButton.text = "Startup requested"
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
