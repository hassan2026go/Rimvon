package com.rimvon.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class Notifier(private val ctx: Context) {
    companion object {
        const val CH_MSG = "messages"
        const val CH_CALL = "calls"
        const val CH_SVC = "service"
        const val ID_SVC = 7000
        const val ID_CALL = 7001
    }

    private val nm: NotificationManager = ctx.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_MSG, "الرسائل", NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_CALL, "المكالمات الواردة", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null) // the app plays the ringtone itself
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_SVC, "حالة الاتصال", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    private fun openIntent(peer: String?, call: Boolean, code: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (peer != null) putExtra("peer", peer)
            if (call) putExtra("call", true)
        }
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun message(peer: String, name: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFF5392D1.toInt())
            .setAutoCancel(true)
            .setContentIntent(openIntent(peer, false, peer.hashCode()))
            .build()
        nm.notify(peer.hashCode(), n)
    }

    fun cancel(peer: String) = nm.cancel(peer.hashCode())

    fun serviceNotification(text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SVC)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Rimvon")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openIntent(null, false, 1))
            .build()

    fun incomingCall(name: String, video: Boolean) {
        val pi = openIntent(null, true, 2)
        val n = NotificationCompat.Builder(ctx, CH_CALL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setContentText(if (video) "مكالمة فيديو واردة" else "مكالمة صوتية واردة")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setColor(0xFF5392D1.toInt())
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        nm.notify(ID_CALL, n)
    }

    fun cancelCall() = nm.cancel(ID_CALL)

    fun missedCall(peer: String, name: String) {
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("مكالمة فائتة")
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(openIntent(null, false, 3))
            .build()
        nm.notify(("missed" + peer).hashCode(), n)
    }

    fun cancelAll() = nm.cancelAll()
}
