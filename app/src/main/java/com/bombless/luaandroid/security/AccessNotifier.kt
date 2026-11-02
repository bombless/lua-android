package com.bombless.luaandroid.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.bombless.luaandroid.R
import com.bombless.luaandroid.ui.MainActivity

/** Tells the owner that an app is asking for access. Silent no-op when notifications are not allowed. */
class AccessNotifier(private val context: Context) {
    fun notifyRequest(request: PendingRequest) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_access), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_ACCESS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(context, request.packageName.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = if (request.signatureChanged) "${request.label} is signed with a different key than when you approved it"
            else "${request.label} wants to run Lua scripts"
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_lua)
                .setContentTitle("Lua Android access request")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText("$text\n${request.packageName}"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_BASE + (request.packageName.hashCode() and 0xFFFF), n)
        } catch (_: Exception) {
            // Notifications are a convenience; the Access tab always lists pending requests.
        }
    }

    private companion object {
        const val CHANNEL = "access_requests"
        const val NOTIFICATION_BASE = 4200
    }
}
