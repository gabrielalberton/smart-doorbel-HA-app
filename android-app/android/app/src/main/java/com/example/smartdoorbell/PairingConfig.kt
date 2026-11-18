package com.example.smartdoorbell

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class RuntimeDoorbellConfig(
    val publicBaseUrl: String,
    val localBaseUrl: String,
    val authHost: String,
    val githubReleaseRepo: String,
    val homeAssistantPackagePrefix: String,
    val triggerTag: String,
    val triggerChannel: String,
    val doorbellTitle: String
)

/** Single-use code that turns into this phone's device key once the WebView posts it. */
data class DeviceEnrollment(val enrollUrl: String, val code: String)

data class PairingResult(val config: RuntimeDoorbellConfig, val enrollment: DeviceEnrollment?)

object PairingConfig {
    private const val PREFS = "smart_doorbell_pairing"
    private const val PUBLIC_URL = "public_base_url"
    private const val LOCAL_URL = "local_base_url"
    private const val AUTH_HOST = "auth_host"
    private const val RELEASE_REPO = "github_release_repo"
    private const val HA_PACKAGE_PREFIX = "ha_package_prefix"
    private const val TRIGGER_TAG = "trigger_tag"
    private const val TRIGGER_CHANNEL = "trigger_channel"
    private const val DOORBELL_TITLE = "doorbell_title"

    fun load(context: Context): RuntimeDoorbellConfig? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val publicUrl = prefs.getString(PUBLIC_URL, null)?.let(::normalizeHttpsBaseUrl) ?: return null
        return RuntimeDoorbellConfig(
            publicBaseUrl = publicUrl,
            localBaseUrl = prefs.getString(LOCAL_URL, "").orEmpty().let(::normalizeOptionalHttpsBaseUrl),
            authHost = prefs.getString(AUTH_HOST, "").orEmpty().trim(),
            githubReleaseRepo = prefs.getString(RELEASE_REPO, BuildConfig.GITHUB_RELEASE_REPO).orEmpty().trim(),
            homeAssistantPackagePrefix = prefs.getString(HA_PACKAGE_PREFIX, BuildConfig.HA_PACKAGE_PREFIX).orEmpty().trim(),
            triggerTag = prefs.getString(TRIGGER_TAG, BuildConfig.TRIGGER_TAG).orEmpty().trim(),
            triggerChannel = prefs.getString(TRIGGER_CHANNEL, BuildConfig.TRIGGER_CHANNEL).orEmpty().trim(),
            doorbellTitle = prefs.getString(DOORBELL_TITLE, BuildConfig.DOORBELL_TITLE).orEmpty().trim()
        )
    }

    fun save(context: Context, config: RuntimeDoorbellConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PUBLIC_URL, config.publicBaseUrl)
            .putString(LOCAL_URL, config.localBaseUrl)
            .putString(AUTH_HOST, config.authHost)
            .putString(RELEASE_REPO, config.githubReleaseRepo)
            .putString(HA_PACKAGE_PREFIX, config.homeAssistantPackagePrefix)
            .putString(TRIGGER_TAG, config.triggerTag)
            .putString(TRIGGER_CHANNEL, config.triggerChannel)
            .putString(DOORBELL_TITLE, config.doorbellTitle)
            .apply()
    }

    /** Build-time default endpoint (empty in the generic build). */
    val defaultEndpoint: String get() = normalizeOptionalHttpsBaseUrl(BuildConfig.DEFAULT_PAIRING_ENDPOINT)

    fun pairAsync(
        endpoint: String,
        password: String,
        deviceName: String,
        callback: (Result<PairingResult>) -> Unit
    ) {
        Thread {
            val result = runCatching {
                val config = pair(endpoint, password)
                // Activation is only accepted on the home network, so outside it the
                // phone stays paired and can be activated later from the settings.
                val enrollment = runCatching { requestEnrollment(config, password, deviceName) }.getOrNull()
                PairingResult(config, enrollment)
            }
            Handler(Looper.getMainLooper()).post { callback(result) }
        }.start()
    }

    private fun requestEnrollment(config: RuntimeDoorbellConfig, password: String, deviceName: String): DeviceEnrollment? {
        if (config.localBaseUrl.isBlank()) return null
        val connection = (URL("${config.localBaseUrl}/api/device/enroll-code").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2_500
            readTimeout = 5_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val body = JSONObject().put("password", password).put("name", deviceName).toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) return null
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val enrollUrl = response.optString("enrollUrl")
            val code = response.optString("code")
            // The code is posted from the WebView, so it may only go to the paired public origin.
            if (!response.optBoolean("ok") || code.isBlank() || !enrollUrl.startsWith("${config.publicBaseUrl}/")) return null
            return DeviceEnrollment(enrollUrl, code)
        } finally {
            connection.disconnect()
        }
    }

    private fun pair(endpoint: String, password: String): RuntimeDoorbellConfig {
        val baseUrl = normalizeHttpsBaseUrl(endpoint)
            ?: throw IllegalArgumentException("Use um endpoint HTTPS válido")
        if (password.isBlank()) throw IllegalArgumentException("Informe a senha")

        val connection = (URL("$baseUrl/api/pair").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val body = JSONObject().put("password", password).toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status == 401) throw SecurityException("Senha incorreta")
            if (status !in 200..299) throw IllegalStateException("Servidor recusou o pareamento (HTTP $status)")

            val response = JSONObject(responseText)
            if (!response.optBoolean("ok")) throw IllegalStateException("Resposta de pareamento inválida")
            val publicUrl = normalizeHttpsBaseUrl(response.optString("publicBaseUrl"))
                ?: throw IllegalStateException("Servidor não informou uma URL pública HTTPS válida")
            return RuntimeDoorbellConfig(
                publicBaseUrl = publicUrl,
                localBaseUrl = normalizeOptionalHttpsBaseUrl(response.optString("localBaseUrl")),
                authHost = response.optString("authHost").trim(),
                githubReleaseRepo = response.optString("githubReleaseRepo", BuildConfig.GITHUB_RELEASE_REPO).trim(),
                homeAssistantPackagePrefix = response.optString("homeAssistantPackagePrefix", BuildConfig.HA_PACKAGE_PREFIX).trim(),
                triggerTag = response.optString("triggerTag", BuildConfig.TRIGGER_TAG).trim(),
                triggerChannel = response.optString("triggerChannel", BuildConfig.TRIGGER_CHANNEL).trim(),
                doorbellTitle = response.optString("doorbellTitle", BuildConfig.DOORBELL_TITLE).trim()
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeOptionalHttpsBaseUrl(raw: String): String = normalizeHttpsBaseUrl(raw).orEmpty()

    private fun normalizeHttpsBaseUrl(raw: String): String? {
        val candidate = raw.trim().trimEnd('/')
        if (candidate.isBlank()) return null
        return try {
            val uri = URI(candidate)
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawQuery != null || uri.rawFragment != null) null
            else candidate
        } catch (_: Throwable) {
            null
        }
    }
}
