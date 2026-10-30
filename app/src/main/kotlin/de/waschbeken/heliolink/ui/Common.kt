package de.waschbeken.heliolink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.Bucket
import de.waschbeken.heliolink.core.Buckets
import de.waschbeken.heliolink.core.Granularity
import de.waschbeken.heliolink.data.HealthDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

val German: Locale = Locale.GERMANY

fun Int.fmt(): String = NumberFormat.getIntegerInstance(German).format(this)

fun Long.fmt(): String = NumberFormat.getIntegerInstance(German).format(this)

fun formatMinutes(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

fun formatHours(minutes: Int): String = "%d:%02d h".format(minutes / 60, minutes % 60)

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Loads something from the database off the main thread, reloading when a key changes. */
@Composable
fun <T> dbValue(vararg keys: Any?, load: (HealthDb) -> T): State<T?> {
    val context = LocalContext.current.applicationContext
    return produceState<T?>(null, *keys) {
        ScreenLoads.run(context, load).onSuccess { value = it }
    }
}

/** Database loads of the screens. A failing load is logged instead of closing the app. */
object ScreenLoads {
    /** Loads in progress; tests wait until it is 0 before looking at a screen. */
    val running = java.util.concurrent.atomic.AtomicInteger()

    /** Set by tests: a failing load fails the test instead of being only logged. */
    @Volatile
    var strict = false

    suspend fun <T> run(context: android.content.Context, load: (HealthDb) -> T): Result<T> {
        running.incrementAndGet()
        try {
            return Result.success(withContext(Dispatchers.IO) { load(context.app.db) })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (strict) throw e
            android.util.Log.e("HelioLink", "Laden fehlgeschlagen", e)
            de.waschbeken.heliolink.data.CrashReporter.nonFatal(context, e)
            return Result.failure(e)
        } finally {
            running.decrementAndGet()
        }
    }
}

/**
 * Last results of [cachedDbValue], so a screen shows its data at once when you come back to it.
 * Only the most recent ones are kept (screens with many dates or periods would add up otherwise).
 */
private val screenCache = object : LinkedHashMap<String, Any?>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any?>?) = size > 40
}

/**
 * Like [dbValue], but starts with the last result stored under [cacheKey] instead of nothing: going
 * back to a screen shows the previous values right away while the new ones load in the background.
 */
@Composable
fun <T> cachedDbValue(cacheKey: String, vararg keys: Any?, load: (HealthDb) -> T): State<T?> {
    val context = LocalContext.current.applicationContext
    @Suppress("UNCHECKED_CAST")
    return produceState(screenCache[cacheKey] as T?, cacheKey, *keys) {
        ScreenLoads.run(context, load).onSuccess { loaded ->
            screenCache[cacheKey] = loaded
            value = loaded
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") } },
        actions = { actions() },
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Rounded panel like the cards of the Lernkarten app. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(20.dp),
        color = color,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Panel with a title row (kept for older screens). */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Panel(modifier) {
        if (title != null || trailing != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title.orEmpty(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
        }
        content()
    }
}

/** Stat tile: icon, label, value, optional caption. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    extra: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(Modifier.size(26.dp).background(tint.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            extra?.invoke()
        }
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CountPill(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun Legend(items: List<Pair<String, Color>>) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (name, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        action()
    }
}

/** Day, week or month view of a chart, or a long view over several months ([months] > 0). */
enum class Period(val label: String, val months: Int = 0) {
    DAY("Tag"), WEEK("Woche"), MONTH("Monat"),
    MONTHS3("3 Monate", 3), MONTHS6("6 Monate", 6), YEAR("1 Jahr", 12), YEARS3("3 Jahre", 36);

    /** More than a month: charts show weeks or months instead of days. */
    val long get() = months > 0

    val granularity: Granularity
        get() = when (this) {
            DAY, WEEK, MONTH -> Granularity.DAY
            MONTHS3, MONTHS6 -> Granularity.WEEK
            YEAR, YEARS3 -> Granularity.MONTH
        }

    fun range(date: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
        DAY -> date to date
        WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
        MONTH -> date.withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
        else -> date.withDayOfMonth(1).let { it.minusMonths(months - 1L) to it.plusMonths(1).minusDays(1) }
    }

    fun shift(date: LocalDate, by: Long): LocalDate = when (this) {
        DAY -> date.plusDays(by)
        WEEK -> date.plusWeeks(by)
        MONTH -> date.plusMonths(by)
        else -> date.plusMonths(by * months)
    }

    /** Buckets (bars) of the period around [date]. */
    fun buckets(date: LocalDate): List<Bucket> = range(date).let { (from, to) -> Buckets.of(from, to, granularity, weekdays = this == WEEK) }

    /** What one bar stands for, e.g. "pro Woche". */
    val perBar: String
        get() = when (granularity) {
            Granularity.DAY -> "pro Tag"
            Granularity.WEEK -> "pro Woche (Ø je Tag)"
            Granularity.MONTH -> "pro Monat (Ø je Tag)"
        }

    fun label(date: LocalDate): String {
        val today = LocalDate.now()
        return when (this) {
            DAY -> when (date) {
                today -> "Heute"
                today.minusDays(1) -> "Gestern"
                else -> date.format(DateTimeFormatter.ofPattern("EEE, d. MMM", German))
            }
            WEEK -> {
                val (from, to) = range(date)
                if (today in from..to) "Diese Woche" else "${from.format(DateTimeFormatter.ofPattern("d. MMM", German))} – ${to.format(DateTimeFormatter.ofPattern("d. MMM", German))}"
            }
            MONTH -> date.format(DateTimeFormatter.ofPattern("MMMM yyyy", German))
            else -> {
                val (from, to) = range(date)
                if (today in from..to) {
                    when (this) {
                        YEAR -> "Letzte 12 Monate"
                        YEARS3 -> "Letzte 3 Jahre"
                        else -> "Letzte $label"
                    }
                } else {
                    val f = DateTimeFormatter.ofPattern("MMM yyyy", German)
                    "${from.format(f)} – ${to.format(f)}"
                }
            }
        }
    }
}

/** Chips for all periods; they scroll sideways on small screens. */
@Composable
fun PeriodSelector(period: Period, onChange: (Period) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Period.entries.forEach { p ->
            FilterChip(selected = period == p, onClick = { onChange(p) }, label = { Text(p.label) })
        }
    }
}

/** "‹  Heute  ›" with arrows; the future cannot be selected. */
@Composable
fun DateNavigator(period: Period, date: LocalDate, onChange: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val canForward = period.range(date).second < today
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(period.shift(date, -1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Zurück")
        }
        Text(
            period.label(date),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onChange(period.shift(date, 1).let { if (it > today) today else it }) }, enabled = canForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Vor")
        }
    }
}

@Composable
fun DateSwitcher(date: LocalDate, onChange: (LocalDate) -> Unit) = DateNavigator(Period.DAY, date, onChange)

fun stressColor(v: Int): Color = when {
    v < 40 -> Color(0xFF3E9BF7)
    v < 60 -> Color(0xFF46A758)
    v < 80 -> Color(0xFFF59E0B)
    else -> Color(0xFFE5484D)
}

fun stressLabel(v: Int): String = when {
    v < 40 -> "entspannt"
    v < 60 -> "normal"
    v < 80 -> "mittel"
    else -> "hoch"
}

fun scoreColor(score: Int) = when {
    score >= 80 -> Color(0xFF46A758)
    score >= 60 -> Color(0xFFF59E0B)
    else -> Color(0xFFE5484D)
}

/** "Mo 27. Okt", "20.–26. Okt", "Oktober 2026" – for tooltips of week and month charts. */
fun Bucket.describe(): String {
    val day = DateTimeFormatter.ofPattern("EEE d. MMM", German)
    val dm = DateTimeFormatter.ofPattern("d. MMM", German)
    return when {
        from == to -> from.format(day)
        from.dayOfMonth == 1 && to == from.plusMonths(1).minusDays(1) -> from.format(DateTimeFormatter.ofPattern("MMMM yyyy", German))
        from.month == to.month -> "${from.dayOfMonth}.–${to.format(dm)}"
        else -> "${from.format(dm)} – ${to.format(dm)}"
    }
}

/** Which collapsible lists are open; remembered in the settings. */
class SectionStates(private val prefs: de.waschbeken.heliolink.data.Prefs) {
    private val map = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    fun isOpen(key: String, default: Boolean = false): Boolean = map[key] ?: prefs.isExpanded(key, default)

    fun toggle(key: String, default: Boolean = false) {
        val v = !isOpen(key, default)
        map[key] = v
        prefs.setExpanded(key, v)
    }
}

@Composable
fun rememberSections(): SectionStates {
    val prefs = LocalContext.current.app.prefs
    return androidx.compose.runtime.remember { SectionStates(prefs) }
}

/** Header of a list that opens and closes, with the number of entries and a short summary when closed. */
@Composable
fun CollapsibleHeader(title: String, expanded: Boolean, count: Int? = null, summary: String? = null, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (!expanded && summary != null) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (count != null) {
                CountPill("$count", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Zuklappen" else "Aufklappen",
            )
        }
    }
}


/**
 * Pull down to refresh: starts a sync with the strap; the screens reload by themselves when new
 * data arrived. The indicator stays while that sync runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRefreshBox(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.syncing) { if (!state.syncing) pulled = false }
    LaunchedEffect(pulled) {
        // If no sync starts (e.g. not connected), do not spin forever.
        if (pulled) {
            kotlinx.coroutines.delay(2_500)
            if (!app.repo.state.value.syncing) pulled = false
        }
    }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = pulled,
        onRefresh = {
            pulled = true
            if (app.repo.state.value.connected) {
                app.repo.controller?.syncNow()
            } else {
                android.widget.Toast.makeText(context, "Strap nicht verbunden – zeige gespeicherte Daten", android.widget.Toast.LENGTH_SHORT).show()
            }
            app.repo.dataChanged()
        },
        modifier = modifier,
        content = content,
    )
}
