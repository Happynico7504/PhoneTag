package com.phonetag.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * Stable ID the game server uses to lock out cheaters across reconnects.
 *
 * Based on ANDROID_ID, which is scoped to this app's signing key and user and survives reinstalls,
 * so uninstalling doesn't dodge a lockout. Only a salted SHA-256 of it leaves the phone; the raw
 * identifier is never sent. Falls back to a random per-install UUID if ANDROID_ID is unavailable.
 */
object DeviceId {
    @Volatile private var cached: String? = null

    @SuppressLint("HardwareIds") // hashed before use, and only used for anti-cheat lockouts
    fun get(context: Context): String = cached ?: synchronized(this) {
        cached ?: run {
            val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
                ?: fallback(context)
            hash(raw).also { cached = it }
        }
    }

    /** 32 hex chars (128 bits) of a salted SHA-256: unlinkable to the raw ID or to other apps. */
    fun hash(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest("phonetag-device:$raw".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    private fun fallback(context: Context): String {
        val prefs = context.getSharedPreferences("phonetag", Context.MODE_PRIVATE)
        return prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("install_id", it).apply()
        }
    }
}
