package de.waschbeken.heliolink.ui.progress

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import de.waschbeken.heliolink.BuildConfig
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Prefs
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.service.StressPrompt
import de.waschbeken.heliolink.ui.German
import de.waschbeken.heliolink.ui.exportFile
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * A PDF for the doctor: the averages of the last days, charts of the night values and the entries
 * with notes. Created on the phone; it only leaves it when the user shares it.
 */
internal object HealthReport {
    const val W = 595 // A4 in points
    const val H = 842
    private const val LEFT = 48f
    private const val RIGHT = W - 48f

    private val dayFormat = DateTimeFormatter.ofPattern("d.M.yyyy", German)
    private val shortDay = DateTimeFormatter.ofPattern("d.M.", German)

    /** Where the pages are drawn: a PDF on the phone, bitmaps in tests. */
    interface PageSink {
        fun begin(number: Int): Canvas
        fun end()
    }

    private class PdfSink(val doc: PdfDocument) : PageSink {
        private var page: PdfDocument.Page? = null

        override fun begin(number: Int): Canvas {
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, number).create())
            page = p
            return p.canvas
        }

        override fun end() {
            page?.let { doc.finishPage(it) }
            page = null
        }
    }

    private class Writer(val sink: PageSink, val footer: String) {
        private var number = 0
        private var open = false
        lateinit var canvas: Canvas
        var y = 0f

        fun newPage() {
            if (open) sink.end()
            number++
            canvas = sink.begin(number)
            open = true
            canvas.drawText("$footer · Seite $number", LEFT, H - 24f, paint(8f, Color.GRAY))
            y = 56f
        }

        fun need(height: Float) {
            if (!open || y + height > H - 48f) newPage()
        }

        fun finish() {
            if (open) sink.end()
            open = false
        }
    }

    private fun paint(size: Float, color: Int = Color.BLACK, bold: Boolean = false) = Paint().apply {
        textSize = size
        this.color = color
        isAntiAlias = true
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun fmt(v: Double, decimals: Int) = if (decimals == 0) "%.0f".format(German, v) else "%.${decimals}f".format(German, v)

    private fun hm(minutes: Int) = "%d:%02d h".format(minutes / 60, minutes % 60)

    fun create(context: Context, db: HealthDb, prefs: Prefs, days: Int): File {
        val file = exportFile(context, "HelioLink-Bericht-${LocalDate.now()}.pdf")
        val doc = PdfDocument()
        try {
            draw(PdfSink(doc), db, prefs, days)
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return file
    }

    /** Collects the values of the last [days] days and draws the report page by page into [sink]. */
    fun draw(sink: PageSink, db: HealthDb, prefs: Prefs, days: Int) {
        val zone = ZoneId.systemDefault()
        val to = LocalDate.now()
        val from = to.minusDays(days - 1L)
        val start = from.atStartOfDay(zone).toEpochSecond()
        val end = to.plusDays(1).atStartOfDay(zone).toEpochSecond()
        fun day(ts: Long) = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate()

        val nights = Queries.nightSummaries(db, from, to, zone)
        val resting = db.values(HealthDb.RESTING_HR, start, end).filter { it.value in 25..150 }
            .groupBy { day(it.timestamp) }.mapValues { it.value.last().value.toDouble() }
        val hrv = Queries.hrvDaily(db, from, to, zone)
        // Night values: from 18:00 the evening before to noon, attributed to the morning.
        fun nightly(table: String, scale: Double, range: IntRange): Map<LocalDate, List<Double>> =
            db.values(table, start - 6 * 3600, end).filter { it.value in range }
                .groupBy { day(it.timestamp + 6 * 3600) }
                .filterKeys { it in from..to }
                .filter { (d, list) ->
                    val n = nights[d] ?: return@filter true
                    list.any { it.timestamp in n.start..n.end }
                }
                .mapValues { (d, list) ->
                    val n = nights[d]
                    (if (n != null) list.filter { it.timestamp in n.start..n.end } else list).map { it.value * scale }
                }
        val spo2 = nightly(HealthDb.SPO2, 1.0, 70..100)
        val temperature = nightly(HealthDb.TEMPERATURE, 0.01, 2500..4200)
        val breath = nightly(HealthDb.RESPIRATION, 1.0, 5..40)
        val stats = ProgressData.stats(db, prefs.profile, from, to)
        val events = db.events(start, end).filter { !it.tag.startsWith(StressPrompt.PREFIX) }
        val feelings = db.feelings(from.toEpochDay(), to.toEpochDay()).values.toList()

        run {
            val w = Writer(sink, "HelioLink ${BuildConfig.VERSION_NAME} · erstellt am ${to.format(dayFormat)}")
            w.need(0f)
            val c = { w.canvas }
            c().drawText("Gesundheitsbericht", LEFT, w.y, paint(22f, bold = true))
            w.y += 20f
            c().drawText(
                "${from.format(dayFormat)} – ${to.format(dayFormat)} ($days Tage) · Amazfit Helio Strap",
                LEFT, w.y, paint(11f, Color.DKGRAY),
            )
            w.y += 14f
            val profile = prefs.profile
            c().drawText(
                "Profil: ${profile.age} Jahre, ${profile.heightCm} cm, ${profile.weightKg} kg",
                LEFT, w.y, paint(9f, Color.GRAY),
            )
            w.y += 26f

            // Summary table.
            c().drawText("Überblick", LEFT, w.y, paint(14f, bold = true))
            w.y += 18f
            fun allValues(m: Map<LocalDate, List<Double>>) = m.values.flatten()
            val rows = listOf(
                "Nächte mit Schlafdaten" to "${stats.nights} von $days",
                "Schlaf Ø" to (stats.sleepMinutes?.let(::hm) ?: "–"),
                "Schlaf-Score Ø" to (stats.sleepScore?.toString() ?: "–"),
                "Eingeschlafen Ø" to (stats.bedtime?.let { "%02d:%02d Uhr".format(((it + 1440) % 1440) / 60, ((it + 1440) % 1440) % 60) } ?: "–"),
                "Ruhepuls Ø (Spanne)" to (resting.values.takeIf { it.isNotEmpty() }?.let { "${fmt(it.average(), 0)} bpm (${fmt(it.min(), 0)}–${fmt(it.max(), 0)})" } ?: "–"),
                "HRV nachts Ø" to (hrv.values.takeIf { it.isNotEmpty() }?.let { "${fmt(it.average(), 0)} ms" } ?: "–"),
                "SpO₂ nachts Ø / tiefster Wert" to (allValues(spo2).takeIf { it.isNotEmpty() }?.let { "${fmt(it.average(), 0)} % / ${fmt(it.min(), 0)} %" } ?: "–"),
                "Atemfrequenz nachts Ø" to (allValues(breath).takeIf { it.isNotEmpty() }?.let { "${fmt(it.average(), 1)} /min" } ?: "–"),
                "Hauttemperatur nachts Ø" to (allValues(temperature).takeIf { it.isNotEmpty() }?.let { "${fmt(it.average(), 1)} °C" } ?: "–"),
                "Schritte pro Tag Ø" to (stats.stepsPerDay?.let { "%,d".format(German, it) } ?: "–"),
                "Stress Ø (0–100)" to (stats.stress?.toString() ?: "–"),
                "Trainings" to "${stats.workouts} (${stats.workoutMinutes} min)",
                "Befinden Ø (1–5)" to (feelings.takeIf { it.isNotEmpty() }?.let { "${fmt(it.map { f -> f.score }.average(), 1)} an ${it.size} Tagen" } ?: "–"),
            )
            val label = paint(10.5f, Color.DKGRAY)
            val value = paint(10.5f, bold = true)
            rows.forEachIndexed { i, (k, v) ->
                if (i % 2 == 0) c().drawRect(LEFT - 4, w.y - 11, RIGHT + 4, w.y + 4, Paint().apply { color = 0xFFF2F2F5.toInt() })
                c().drawText(k, LEFT, w.y, label)
                c().drawText(v, LEFT + 260, w.y, value)
                w.y += 15f
            }
            w.y += 16f

            fun series(m: Map<LocalDate, Double>) = m.filterKeys { it in from..to }.toSortedMap().map { it.key to it.value }
            fun avgSeries(m: Map<LocalDate, List<Double>>) = series(m.mapValues { it.value.average() })
            chart(w, "Schlafdauer (Stunden)", series(nights.mapValues { it.value.asleepMinutes / 60.0 }), from, to, 0xFF6E56CF.toInt(), bars = true, decimals = 1)
            chart(w, "Ruhepuls (bpm)", series(resting), from, to, 0xFFE5484D.toInt(), bars = false, decimals = 0)
            chart(w, "HRV nachts (ms)", series(hrv), from, to, 0xFF30A46C.toInt(), bars = false, decimals = 0)
            chart(w, "SpO₂ nachts – tiefster Wert (%)", series(spo2.mapValues { it.value.min() }), from, to, 0xFF0090FF.toInt(), bars = false, decimals = 0)
            chart(w, "Hauttemperatur nachts (°C)", avgSeries(temperature), from, to, 0xFFE54666.toInt(), bars = false, decimals = 1)
            chart(w, "Atemfrequenz nachts (/min)", avgSeries(breath), from, to, 0xFF00A2C7.toInt(), bars = false, decimals = 1)

            // Entries.
            w.need(60f)
            c().drawText("Einträge", LEFT, w.y, paint(14f, bold = true))
            w.y += 16f
            if (events.isEmpty()) {
                c().drawText("Keine Einträge in diesem Zeitraum.", LEFT, w.y, paint(10f, Color.DKGRAY))
                w.y += 14f
            } else {
                val counts = events.groupingBy { it.tag }.eachCount().entries.sortedByDescending { it.value }
                counts.chunked(3).forEach { line ->
                    w.need(14f)
                    line.forEachIndexed { i, e -> c().drawText("${e.key}: ${e.value}×", LEFT + i * 170f, w.y, paint(10f)) }
                    w.y += 14f
                }
                w.y += 8f
                val time = DateTimeFormatter.ofPattern("d.M. HH:mm", German)
                val noted = events.filter { it.note.isNotBlank() || it.tag !in setOf("Koffein", "Nikotin", "Essen", "Sport") }.takeLast(60)
                if (noted.isNotEmpty()) {
                    w.need(16f)
                    c().drawText("Beschwerden, Medikamente und Notizen", LEFT, w.y, paint(11f, bold = true))
                    w.y += 14f
                    noted.forEach { e ->
                        w.need(13f)
                        c().drawText(Instant.ofEpochSecond(e.timestamp).atZone(zone).format(time), LEFT, w.y, paint(9.5f, Color.DKGRAY))
                        c().drawText(e.tag, LEFT + 70, w.y, paint(9.5f, bold = true))
                        if (e.note.isNotBlank()) c().drawText(e.note.take(80), LEFT + 190, w.y, paint(9.5f))
                        w.y += 13f
                    }
                }
            }
            w.y += 18f
            w.need(30f)
            c().drawText(
                "Messwerte eines Fitness-Trackers (am Arm getragen), ausgewertet von HelioLink – keine medizinische Diagnose.",
                LEFT, w.y, paint(8.5f, Color.GRAY),
            )
            w.finish()
        }
    }

    /** A small chart over the whole period: bars or a line, with the range and the average. */
    private fun chart(w: Writer, title: String, values: List<Pair<LocalDate, Double>>, from: LocalDate, to: LocalDate, color: Int, bars: Boolean, decimals: Int) {
        w.need(150f)
        val c = w.canvas
        c.drawText(title, LEFT, w.y, paint(12f, bold = true))
        val top = w.y + 10f
        val bottom = top + 92f
        val left = LEFT + 34f
        val right = RIGHT
        val axis = Paint().apply { this.color = 0xFFCCCCD4.toInt(); strokeWidth = 0.7f }
        c.drawLine(left, bottom, right, bottom, axis)
        if (values.isEmpty()) {
            c.drawText("Keine Daten", left + 8, top + 46, paint(10f, Color.GRAY))
        } else {
            val span = ChronoUnit.DAYS.between(from, to).coerceAtLeast(1).toFloat()
            fun x(d: LocalDate) = left + (ChronoUnit.DAYS.between(from, d) / span) * (right - left - 8f) + 4f
            var lo = values.minOf { it.second }
            var hi = values.maxOf { it.second }
            if (bars) lo = 0.0
            if (hi - lo < 1e-6) { hi += 1; lo -= 1 }
            val pad = (hi - lo) * 0.1
            hi += pad
            if (!bars) lo -= pad
            fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
            val fill = Paint().apply { this.color = color; isAntiAlias = true }
            if (bars) {
                val bw = ((right - left) / (span + 1) * 0.7f).coerceIn(1.5f, 14f)
                values.forEach { (d, v) -> c.drawRect(RectF(x(d) - bw / 2, y(v), x(d) + bw / 2, bottom), fill) }
            } else {
                val line = Paint().apply { this.color = color; strokeWidth = 1.6f; style = Paint.Style.STROKE; isAntiAlias = true }
                val path = Path()
                var prev: LocalDate? = null
                for ((d, v) in values) {
                    val p = prev
                    if (p == null || ChronoUnit.DAYS.between(p, d) > 3) path.moveTo(x(d), y(v)) else path.lineTo(x(d), y(v))
                    prev = d
                }
                c.drawPath(path, line)
                values.forEach { (d, v) -> c.drawCircle(x(d), y(v), 1.8f, fill) }
            }
            val avg = values.map { it.second }.average()
            val dashed = Paint().apply { this.color = Color.DKGRAY; strokeWidth = 0.7f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f) }
            c.drawLine(left, y(avg), right, y(avg), dashed)
            val small = paint(8f, Color.DKGRAY)
            c.drawText(fmt(values.maxOf { it.second }, decimals), LEFT, y(values.maxOf { it.second }) + 3, small)
            c.drawText(fmt(values.minOf { it.second }, decimals), LEFT, y(values.minOf { it.second }) + 3, small)
            c.drawText("Ø ${fmt(avg, decimals)}", right - 40f, y(avg) - 3, small)
        }
        val small = paint(8f, Color.GRAY)
        c.drawText(from.format(shortDay), left, bottom + 11, small)
        val endText = to.format(shortDay)
        c.drawText(endText, right - small.measureText(endText), bottom + 11, small)
        w.y = bottom + 34f
    }
}
