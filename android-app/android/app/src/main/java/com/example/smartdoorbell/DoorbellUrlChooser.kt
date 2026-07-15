package com.example.smartdoorbell

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object DoorbellUrlChooser {
    private const val CONNECT_TIMEOUT_MS = 900
    private const val READ_TIMEOUT_MS = 900

    fun chooseAsync(preferredUrl: String?, defaultPath: String = "/", callback: (String) -> Unit) {
        Thread {
            val chosen = choose(preferredUrl, defaultPath)
            Handler(Looper.getMainLooper()).post { callback(chosen) }
        }.start()
    }

    fun choose(preferredUrl: String?, defaultPath: String = "/"): String {
        val path = normalizedPath(preferredUrl, defaultPath)
        val localOk = isLocalReachable()
        return if (localOk) {
            DoorbellConfig.LOCAL_BASE_URL + path
        } else {
            when {
                preferredUrl != null && isHttpDoorbellUrl(preferredUrl) -> preferredUrl
                path.startsWith("/atender") -> DoorbellConfig.PUBLIC_BASE_URL + path
                else -> DoorbellConfig.PUBLIC_BASE_URL + path
            }
        }
    }

    fun alternateForFailedUrl(failedUrl: String): String? {
        val path = normalizedPath(failedUrl, "/")
        return when (hostOf(failedUrl)) {
            DoorbellConfig.PUBLIC_HOST -> DoorbellConfig.LOCAL_BASE_URL + path
            DoorbellConfig.LOCAL_HOST -> DoorbellConfig.PUBLIC_BASE_URL + path
            else -> null
        }
    }

    private fun isLocalReachable(): Boolean {
        return try {
            val conn = (URL(DoorbellConfig.LOCAL_BASE_URL + "/healthz").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
            }
            try {
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun normalizedPath(rawUrl: String?, defaultPath: String): String {
        if (rawUrl.isNullOrBlank()) return normalizePath(defaultPath)
        return try {
            val uri = URI(rawUrl)
            val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: defaultPath
            val query = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
            normalizePath(path) + query
        } catch (_: Throwable) {
            normalizePath(defaultPath)
        }
    }

    private fun normalizePath(path: String): String = if (path.startsWith("/")) path else "/$path"

    private fun hostOf(rawUrl: String): String? = try { URI(rawUrl).host } catch (_: Throwable) { null }

    private fun isHttpDoorbellUrl(rawUrl: String): Boolean {
        return try {
            val uri = URI(rawUrl)
            uri.scheme == "https" && DoorbellConfig.isDoorbellHost(uri.host)
        } catch (_: Throwable) {
            false
        }
    }
}
