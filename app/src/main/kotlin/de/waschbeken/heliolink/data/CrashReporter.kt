package de.waschbeken.heliolink.data

import android.content.Context
import android.os.Build
import de.waschbeken.heliolink.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/**
 * Writes uncaught exceptions to a file, so the next start can show what happened and the user can
 * send the report. Afterwards Android's default handler closes the app as usual.
 */
object CrashReporter {
    private const val FILE = "crash.txt"
    private const val ERRORS = "errors.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    appendLine("HelioLink ${BuildConfig.VERSION_NAME} – Absturz am ${LocalDateTime.now().withNano(0)}")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(sw.toString())
                }
                File(appContext.filesDir, FILE).writeText(text.take(60_000))
            } catch (_: Throwable) {
                // never fail while reporting a failure
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Exceptions the app caught and survived; they go into the data export (fehler.txt). */
    fun nonFatal(context: Context, error: Throwable) {
        try {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val file = File(context.filesDir, ERRORS)
            val old = if (file.exists()) file.readText().takeLast(40_000) else ""
            file.writeText(old + "--- ${BuildConfig.VERSION_NAME} ${LocalDateTime.now().withNano(0)}\n" + sw.toString().take(4_000) + "\n")
        } catch (_: Throwable) {
        }
    }

    fun errors(context: Context): String? = File(context.filesDir, ERRORS).takeIf { it.exists() }?.readText()

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
