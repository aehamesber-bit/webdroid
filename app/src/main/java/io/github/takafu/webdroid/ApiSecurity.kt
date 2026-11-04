package io.github.takafu.webdroid

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/** Local API credentials and opt-in for privileged arbitrary JavaScript. */
object ApiSecurity {
    private const val PREFS_NAME = "webdroid_api_security"
    private const val TOKEN_KEY = "api_token"
    private const val UNSAFE_JS_KEY = "allow_unsafe_javascript"
    private const val TOKEN_BYTES = 32
    private const val TOKEN_HEX = "0123456789abcdef"

    @Synchronized
    fun getOrCreateToken(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = preferences.getString(TOKEN_KEY, null)
        if (existing != null && existing.length == TOKEN_BYTES * 2 && existing.all { it in TOKEN_HEX }) {
            return existing
        }

        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        val token = buildString(TOKEN_BYTES * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(TOKEN_HEX[value ushr 4])
                append(TOKEN_HEX[value and 0x0f])
            }
        }
        preferences.edit().putString(TOKEN_KEY, token).apply()
        return token
    }

    fun isAuthorized(authorizationHeader: String?, expectedToken: String): Boolean {
        if (authorizationHeader == null || expectedToken.isEmpty()) return false
        if (!authorizationHeader.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return false
        val supplied = authorizationHeader.substring(7).trim()
        if (supplied.isEmpty() || supplied.length > 512) return false
        return MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.UTF_8),
            expectedToken.toByteArray(StandardCharsets.UTF_8)
        )
    }

    fun isUnsafeJavaScriptEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(UNSAFE_JS_KEY, false)

    fun setUnsafeJavaScriptEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(UNSAFE_JS_KEY, enabled).apply()
    }
}
