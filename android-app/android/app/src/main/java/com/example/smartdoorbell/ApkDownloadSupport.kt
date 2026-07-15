package com.example.smartdoorbell

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast

object ApkDownloadSupport {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val PREFS = "campainha_apk_update"
    private const val KEY_PENDING_URL = "pending_url"
    private const val KEY_PENDING_USER_AGENT = "pending_user_agent"
    private const val KEY_PENDING_DISPOSITION = "pending_disposition"
    private const val KEY_PENDING_MIME = "pending_mime"
    private const val KEY_DOWNLOAD_ID = "download_id"

    fun install(activity: Activity, webView: WebView) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!isTrustedUpdateUrl(url)) {
                Toast.makeText(activity, "Download bloqueado: origem não permitida", Toast.LENGTH_LONG).show()
                return@setDownloadListener
            }
            if (!canInstallPackages(activity)) {
                savePending(activity, url, userAgent, contentDisposition, mimeType)
                Toast.makeText(
                    activity,
                    "Ative “Permitir desta fonte” para atualizar o app e volte para a Campainha",
                    Toast.LENGTH_LONG
                ).show()
                openInstallPermission(activity)
                return@setDownloadListener
            }
            enqueue(activity, url, userAgent, contentDisposition, mimeType)
        }
    }

    fun resumePendingIfAllowed(activity: Activity) {
        if (!canInstallPackages(activity)) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_PENDING_URL, null) ?: return
        if (!isTrustedUpdateUrl(url)) {
            clearPending(activity)
            return
        }
        val userAgent = prefs.getString(KEY_PENDING_USER_AGENT, null)
        val disposition = prefs.getString(KEY_PENDING_DISPOSITION, null)
        val mime = prefs.getString(KEY_PENDING_MIME, null)
        clearPending(activity)
        enqueue(activity, url, userAgent, disposition, mime)
    }

    fun pendingDownloadId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DOWNLOAD_ID, -1L)

    fun clearDownloadId(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DOWNLOAD_ID)
            .apply()
    }

    private fun enqueue(
        activity: Activity,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        runCatching {
            val suggested = URLUtil.guessFileName(url, contentDisposition, APK_MIME)
            val baseName = suggested.removeSuffix(".apk").ifBlank { "Campainha-update" }
            val fileName = "$baseName-${System.currentTimeMillis()}.apk"
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(APK_MIME)
                .setTitle(fileName)
                .setDescription("Baixando atualização do app Campainha")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            if (!mimeType.isNullOrBlank()) request.addRequestHeader("Accept", APK_MIME)

            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .apply()
            Toast.makeText(activity, "Atualização sendo baixada", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(activity, "Não foi possível baixar a atualização", Toast.LENGTH_LONG).show()
        }
    }

    private fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun openInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    private fun savePending(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_URL, url)
            .putString(KEY_PENDING_USER_AGENT, userAgent)
            .putString(KEY_PENDING_DISPOSITION, contentDisposition)
            .putString(KEY_PENDING_MIME, mimeType)
            .apply()
    }

    private fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_URL)
            .remove(KEY_PENDING_USER_AGENT)
            .remove(KEY_PENDING_DISPOSITION)
            .remove(KEY_PENDING_MIME)
            .apply()
    }

    private fun isTrustedUpdateUrl(rawUrl: String): Boolean {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
        if (uri.scheme != "https") return false

        val isConfiguredServer = DoorbellConfig.isDoorbellHost(uri.host) &&
            uri.path == "/app-update/latest.apk"
        if (isConfiguredServer) return true

        val repo = DoorbellConfig.GITHUB_RELEASE_REPO.trim().trim('/')
        return repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) &&
            uri.host == "github.com" &&
            uri.path?.startsWith("/$repo/releases/download/") == true &&
            uri.path?.endsWith(".apk", ignoreCase = true) == true
    }
}
