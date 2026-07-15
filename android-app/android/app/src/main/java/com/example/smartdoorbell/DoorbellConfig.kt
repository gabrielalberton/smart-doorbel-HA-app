package com.example.smartdoorbell

import java.net.URI

object DoorbellConfig {
    val LOCAL_BASE_URL: String = BuildConfig.LOCAL_BASE_URL.trimEnd('/')
    val PUBLIC_BASE_URL: String = BuildConfig.PUBLIC_BASE_URL.trimEnd('/')
    val LOCAL_HOST: String? = hostOf(LOCAL_BASE_URL)
    val PUBLIC_HOST: String? = hostOf(PUBLIC_BASE_URL)
    const val DEFAULT_ATTEND_PATH = "/atender"
    val DEFAULT_ATTEND_URL: String = PUBLIC_BASE_URL + DEFAULT_ATTEND_PATH
    const val PREFS = "smart_doorbell_native"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_OPEN_URL = "open_url"
    const val EXTRA_PREVIEW_URL = "preview_url"
    const val ACTION_TEST_RING = "com.example.smartdoorbell.TEST_RING"
    const val ACTION_ANSWER = "com.example.smartdoorbell.ANSWER"
    const val ACTION_DECLINE = "com.example.smartdoorbell.DECLINE"

    fun previewUrlFromAnswer(answerUrl: String): String {
        val baseUrl = answerUrl.substringBefore(DEFAULT_ATTEND_PATH).trimEnd('/')
        return withAppVersion("$baseUrl/?native_call=1")
    }

    fun withAppVersion(url: String): String {
        if (url.contains("app_version=")) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}app_version=${BuildConfig.VERSION_NAME}"
    }

    fun isConfiguredHost(host: String?): Boolean =
        host != null && (host == PUBLIC_HOST || host == LOCAL_HOST || host == BuildConfig.AUTH_HOST)

    fun isDoorbellHost(host: String?): Boolean = host != null && (host == PUBLIC_HOST || host == LOCAL_HOST)

    fun hostOf(rawUrl: String): String? = try { URI(rawUrl).host } catch (_: Throwable) { null }
}
