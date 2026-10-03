package com.dronewukong.apophenia.phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Android binds this only after the user grants Notification Access in system settings.
 * It does not persist content; the event/control collector reads the active snapshot in memory.
 */
class NotificationCaptureService : NotificationListenerService() {
    override fun onListenerConnected() {
        current = this
    }

    override fun onListenerDisconnected() {
        if (current === this) current = null
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var current: NotificationCaptureService? = null

        fun activeSnapshot(): List<StatusBarNotification>? = current?.let { service ->
            runCatching { service.activeNotifications.orEmpty().toList() }.getOrNull()
        }
    }
}
