package de.waschbeken.heliolink.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import de.waschbeken.heliolink.app

/**
 * Lets the strap vibrate for calls and for messages of the apps the user picked. Only the app that
 * sent a notification is looked at – never its content – and nothing is stored. Not while asleep
 * and not while "Do not disturb" is on.
 */
class ArmNotificationListener : NotificationListenerService() {
    private val lastByKey = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = applicationContext.app
        val prefs = app.prefs
        if (!prefs.armNotify || sbn.packageName == packageName) return
        val controller = app.repo.controller ?: return
        val state = app.repo.state.value
        if (!state.connected || state.asleep == true) return
        if (currentInterruptionFilter > NotificationListenerService.INTERRUPTION_FILTER_ALL) return
        val n = sbn.notification ?: return
        val call = n.category == Notification.CATEGORY_CALL
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (!call && (sbn.isOngoing || sbn.packageName !in prefs.armApps)) return
        if (call && !prefs.armCalls) return
        // Updated notifications (e.g. a chat that grows) only count once a minute.
        val now = System.currentTimeMillis()
        val key = if (call) "call" else sbn.key
        if (now - (lastByKey[key] ?: 0) < (if (call) 30_000 else 60_000)) return
        lastByKey[key] = now
        if (call) controller.buzz(3, 800) else controller.buzz(2, 300)
    }

    companion object {
        /** Whether the user gave HelioLink access to notifications in the system settings. */
        fun hasAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, ArmNotificationListener::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
