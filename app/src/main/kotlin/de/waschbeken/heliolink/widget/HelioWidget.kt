package de.waschbeken.heliolink.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import de.waschbeken.heliolink.R
import de.waschbeken.heliolink.ui.MainActivity

/** Home screen widget: last heart rate, steps today, last night's sleep and battery. */
class HelioWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        private const val PREFS = "widget"

        /** Stores the values and refreshes all widgets. Called by the service, throttled. */
        fun update(context: Context, heartRate: Int?, steps: Long?, sleep: String?, battery: Int?) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            heartRate?.let { p.putInt("hr", it) }
            steps?.let { p.putLong("steps", it) }
            sleep?.let { p.putString("sleep", it) }
            battery?.let { p.putInt("battery", it) }
            p.apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HelioWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { manager.updateAppWidget(it, views(context)) }
        }

        private fun views(context: Context): RemoteViews {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val v = RemoteViews(context.packageName, R.layout.widget_helio)
            v.setTextViewText(R.id.widget_hr, p.getInt("hr", 0).takeIf { it > 0 }?.let { "♥ $it" } ?: "–")
            v.setTextViewText(R.id.widget_steps, p.getLong("steps", -1).takeIf { it >= 0 }?.let { "%,d".format(it).replace(',', '.') } ?: "–")
            v.setTextViewText(R.id.widget_sleep, p.getString("sleep", null) ?: "–")
            v.setTextViewText(R.id.widget_battery, p.getInt("battery", -1).takeIf { it >= 0 }?.let { "Schlaf · Akku $it %" } ?: "Schlaf")
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }
    }
}
