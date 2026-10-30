package de.waschbeken.heliolink.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.widget.RemoteViews
import de.waschbeken.heliolink.R
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.data.CrashReporter
import de.waschbeken.heliolink.ui.MainActivity
import de.waschbeken.heliolink.ui.Routes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Home screen widget with the heart rate of the last 12 hours as a chart, sleep shaded. */
class HelioChartWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Reading the database takes a moment: not on the main thread.
        val pending = goAsync()
        Thread {
            try {
                refresh(context)
            } catch (e: Exception) {
                CrashReporter.nonFatal(context, e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val W = 720
        private const val H = 220
        private const val HOURS = 12

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, HelioChartWidget::class.java))

        /** Redraws all chart widgets from the database. Call off the main thread. */
        fun refresh(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val now = System.currentTimeMillis() / 1000
            val from = now - HOURS * 3600L
            val samples = context.app.db.activity(from, now + 60)
            val hr = samples.filter { it.heartRate in 30..230 }.map { it.timestamp to it.heartRate }
            val sleep = samples.filter { it.isSleep }.map { it.timestamp }
            val p = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
            val v = RemoteViews(context.packageName, R.layout.widget_chart)
            val current = p.getInt("hr", 0).takeIf { it > 0 } ?: hr.lastOrNull()?.second
            v.setTextViewText(R.id.chart_hr, current?.let { "♥ $it" } ?: "–")
            v.setTextViewText(
                R.id.chart_steps,
                p.getLong("steps", -1).takeIf { it >= 0 }?.let { "%,d".format(it).replace(',', '.') + " Schritte" } ?: "",
            )
            v.setTextViewText(R.id.chart_sleep, p.getString("sleep", null)?.let { "☾ $it" } ?: "")
            v.setImageViewBitmap(R.id.chart_image, draw(hr, sleep, from, now))
            val clock = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            v.setTextViewText(
                R.id.chart_caption,
                if (hr.isEmpty()) {
                    "Noch keine Pulswerte in den letzten $HOURS Stunden"
                } else {
                    "Puls $HOURS h · ${hr.minOf { it.second }}–${hr.maxOf { it.second }} bpm · Stand $clock"
                },
            )
            val open = PendingIntent.getActivity(
                context, 1, MainActivity.openIntent(context, Routes.metric("heart")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.chart_root, open)
            AppWidgetManager.getInstance(context).updateAppWidget(ids, v)
        }

        private fun draw(hr: List<Pair<Long, Int>>, sleep: List<Long>, from: Long, to: Long): Bitmap {
            val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
            val bottom = H - 26f
            fun x(t: Long) = ((t - from).toFloat() / (to - from)) * W
            val grid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 2f }
            for (i in 0..2) c.drawLine(0f, bottom * i / 2f + 2f, W.toFloat(), bottom * i / 2f + 2f, grid)
            val sleepPaint = Paint().apply { color = 0x406E56CF }
            sleep.forEach { t -> c.drawRect(x(t), 0f, x(t + 60) + 1f, bottom, sleepPaint) }
            val label = Paint().apply { color = 0x99FFFFFF.toInt(); textSize = 22f; isAntiAlias = true }
            c.drawText("−$HOURS h", 4f, H - 4f, label)
            c.drawText("−${HOURS / 2} h", W / 2f - 24f, H - 4f, label)
            c.drawText("jetzt", W - 54f, H - 4f, label)
            if (hr.size < 2) return bitmap
            val lo = hr.minOf { it.second } - 5f
            val hi = hr.maxOf { it.second } + 5f
            fun y(v: Int) = bottom - (v - lo) / (hi - lo) * (bottom - 8f)
            val line = Path()
            val area = Path()
            var start = -1f
            var last = -1f
            var prevT = 0L
            fun closeArea() {
                if (start >= 0) {
                    area.lineTo(last, bottom)
                    area.lineTo(start, bottom)
                    area.close()
                }
            }
            for ((t, v) in hr) {
                val px = x(t)
                if (start < 0 || t - prevT > 20 * 60) {
                    closeArea()
                    line.moveTo(px, y(v))
                    area.moveTo(px, bottom)
                    area.lineTo(px, y(v))
                    start = px
                } else {
                    line.lineTo(px, y(v))
                    area.lineTo(px, y(v))
                }
                last = px
                prevT = t
            }
            closeArea()
            val fill = Paint().apply {
                isAntiAlias = true
                shader = LinearGradient(0f, 0f, 0f, bottom, 0x77FF6B4A, 0x00FF6B4A, Shader.TileMode.CLAMP)
            }
            c.drawPath(area, fill)
            val stroke = Paint().apply {
                isAntiAlias = true
                color = 0xFFFF6B4A.toInt()
                strokeWidth = 4f
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
            }
            c.drawPath(line, stroke)
            c.drawText("${hi.toInt() - 5}", W - 46f, 24f, label)
            c.drawText("${lo.toInt() + 5}", W - 46f, bottom - 6f, label)
            return bitmap
        }
    }
}
