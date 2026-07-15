package com.example.smartdoorbell

import android.content.Context
import java.net.URI

object DoorbellConfig {
    @Volatile var LOCAL_BASE_URL: String = ""
        private set
    @Volatile var PUBLIC_BASE_URL: String = ""
        private set
    @Volatile var AUTH_HOST: String = ""
        private set
    @Volatile var GITHUB_RELEASE_REPO: String = BuildConfig.GITHUB_RELEASE_REPO
        private set
    @Volatile var HA_PACKAGE_PREFIX: String = BuildConfig.HA_PACKAGE_PREFIX
        private set
    @Volatile var TRIGGER_TAG: String = BuildConfig.TRIGGER_TAG
        private set
    @Volatile var TRIGGER_CHANNEL: String = BuildConfig.TRIGGER_CHANNEL
        private set
    @Volatile var DOORBELL_TITLE: String = BuildConfig.DOORBELL_TITLE
        private set

    val LOCAL_HOST: String? get() = hostOf(LOCAL_BASE_URL)
    val PUBLIC_HOST: String? get() = hostOf(PUBLIC_BASE_URL)
    val DEFAULT_ATTEND_URL: String get() = PUBLIC_BASE_URL + DEFAULT_ATTEND_PATH

    const val DEFAULT_ATTEND_PATH = "/atender"
    const val PREFS = "smart_doorbell_native"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_OPEN_URL = "open_url"
    const val EXTRA_PREVIEW_URL = "preview_url"
    const val ACTION_TEST_RING = "com.example.smartdoorbell.TEST_RING"
    const val ACTION_ANSWER = "com.example.smartdoorbell.ANSWER"
    const val ACTION_DECLINE = "com.example.smartdoorbell.DECLINE"

    fun initialize(context: Context): Boolean {
        val config = PairingConfig.load(context) ?: return false
        apply(config)
        return true
    }

    fun apply(config: RuntimeDoorbellConfig) {
        PUBLIC_BASE_URL = config.publicBaseUrl.trimEnd('/')
        LOCAL_BASE_URL = config.localBaseUrl.trimEnd('/')
        AUTH_HOST = config.authHost.trim()
        GITHUB_RELEASE_REPO = config.githubReleaseRepo.trim().ifBlank { BuildConfig.GITHUB_RELEASE_REPO }
        HA_PACKAGE_PREFIX = config.homeAssistantPackagePrefix.trim().ifBlank { BuildConfig.HA_PACKAGE_PREFIX }
        TRIGGER_TAG = config.triggerTag.trim().ifBlank { BuildConfig.TRIGGER_TAG }
        TRIGGER_CHANNEL = config.triggerChannel.trim().ifBlank { BuildConfig.TRIGGER_CHANNEL }
        DOORBELL_TITLE = config.doorbellTitle.trim().ifBlank { BuildConfig.DOORBELL_TITLE }
    }

    fun isConfigured(): Boolean = PUBLIC_BASE_URL.startsWith("https://")

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
        host != null && (host == PUBLIC_HOST || host == LOCAL_HOST || host == AUTH_HOST)

    fun isDoorbellHost(host: String?): Boolean = host != null && (host == PUBLIC_HOST || host == LOCAL_HOST)

    fun hostOf(rawUrl: String): String? = try { URI(rawUrl).host } catch (_: Throwable) { null }
}
