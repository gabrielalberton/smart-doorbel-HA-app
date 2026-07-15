package com.example.smartdoorbell

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat

class ApkDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (completedId < 0 || completedId != ApkDownloadSupport.pendingDownloadId(context)) return

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(completedId)
        manager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                ApkDownloadSupport.clearDownloadId(context)
                Toast.makeText(context, "Falha ao baixar a atualização", Toast.LENGTH_LONG).show()
                return
            }
        }

        val apkUri = manager.getUriForDownloadedFile(completedId)
        if (apkUri == null || !hasApkMagic(context, apkUri)) {
            ApkDownloadSupport.clearDownloadId(context)
            Toast.makeText(context, "O arquivo baixado não é um APK válido", Toast.LENGTH_LONG).show()
            return
        }

        ApkDownloadSupport.clearDownloadId(context)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME)
            clipData = ClipData.newRawUri("Campainha APK", apkUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            INSTALL_NOTIFICATION_ID,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        ensureUpdateChannel(context)
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_doorbell)
            .setContentTitle("Atualização da Campainha pronta")
            .setContentText("Toque para instalar a nova versão")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(INSTALL_NOTIFICATION_ID, notification)

        Toast.makeText(
            context,
            "Download concluído. Toque em “Atualização da Campainha pronta” para instalar.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun ensureUpdateChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            UPDATE_CHANNEL_ID,
            "Atualizações da Campainha",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Avisa quando uma atualização do app está pronta para instalar"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasApkMagic(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) return@runCatching false
            val magic = ByteArray(4)
            input.read(magic) == 4 &&
                magic[0] == 'P'.code.toByte() &&
                magic[1] == 'K'.code.toByte() &&
                magic[2] == 3.toByte() &&
                magic[3] == 4.toByte()
        }
    }.getOrDefault(false)

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val UPDATE_CHANNEL_ID = "campainha_app_updates_v1"
        private const val INSTALL_NOTIFICATION_ID = 42020
    }
}
