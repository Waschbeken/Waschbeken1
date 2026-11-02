package de.waschbeken.heliolink.update

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Installs updates without Android's confirmation dialog through Shizuku (https://shizuku.rikka.app),
 * which runs commands with ADB rights: the APK is piped into `pm install`. Installing replaces the
 * running app, so it closes; a small shell job started before opens it again once the new version
 * is in place, and StartReceiver reconnects to the strap and posts "installiert".
 */
object ShizukuInstaller {
    private const val PERMISSION_REQUEST = 4711
    private const val PREFS = "updater"
    private const val KEY_SILENT = "silent_install"

    enum class Status {
        /** The Shizuku app is missing or not started. */
        NOT_RUNNING,

        /** Shizuku runs but HelioLink is not allowed yet. */
        NO_PERMISSION,

        /** Shizuku older than version 11. */
        UNSUPPORTED,
        READY,
    }

    fun status(): Status = try {
        when {
            !Shizuku.pingBinder() -> Status.NOT_RUNNING
            Shizuku.isPreV11() -> Status.UNSUPPORTED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Status.READY
            else -> Status.NO_PERMISSION
        }
    } catch (e: Exception) {
        Status.NOT_RUNNING
    }

    /**
     * The binder arrives shortly after the process starts (e.g. when a background job woke the app);
     * waits up to [timeoutMs] for it. Returns the status afterwards.
     */
    suspend fun awaitStatus(timeoutMs: Long = 3_000): Status {
        if (Shizuku.pingBinder()) return status()
        val received = CompletableDeferred<Unit>()
        val listener = Shizuku.OnBinderReceivedListener { received.complete(Unit) }
        Shizuku.addBinderReceivedListenerSticky(listener)
        try {
            withTimeoutOrNull(timeoutMs) { received.await() }
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
        return status()
    }

    /** Shows Shizuku's permission dialog; [onResult] gets true when HelioLink was allowed. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != PERMISSION_REQUEST) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST)
        } catch (e: Exception) {
            // Shizuku stopped in the meantime.
            Shizuku.removeRequestPermissionResultListener(listener)
            onResult(false)
        }
    }

    /**
     * Installs [apk] over the running app. On success Android usually ends this process before the
     * call returns; on failure it throws with the message from `pm` (e.g. a signature mismatch).
     */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        check(awaitStatus() == Status.READY) { "Shizuku ist nicht bereit" }
        markSilentInstall(context, true)
        try {
            startRelauncher(context, apk)
            // "--user current": without it pm installs for every user on the phone (e.g. Xiaomi's hidden
            // dual apps user), and a normal uninstall later leaves a copy there that blocks reinstalling.
            val process = newProcess(arrayOf("pm", "install", "-r", "--user", "current", "-S", apk.length().toString()))
            process.outputStream.use { out -> apk.inputStream().use { it.copyTo(out) } }
            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            if (code != 0 || !output.contains("Success")) {
                throw IllegalStateException(error.ifEmpty { output }.ifEmpty { "pm install endete mit Code $code" })
            }
        } catch (e: Exception) {
            markSilentInstall(context, false)
            throw e
        }
    }

    /**
     * Starts a shell job (through Shizuku, so it outlives this process) that waits until Android has
     * installed the version in [apk] and then opens HelioLink again. Gives up after 2 minutes; if the
     * install fails, the app keeps running and nothing happens.
     */
    private fun startRelauncher(context: Context, apk: File) {
        try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            val pkg = context.packageName
            val activity = "$pkg/de.waschbeken.heliolink.ui.MainActivity"
            val job = "for i in \$(seq 1 120); do sleep 1; " +
                "if dumpsys package $pkg | grep -q \"versionCode=$code \"; then sleep 2; am start -n $activity; exit 0; fi; done"
            // The subshell in the background is not tied to this process or to Shizuku's handle.
            newProcess(arrayOf("sh", "-c", "( sh -c '$job' >/dev/null 2>&1 & )")).waitFor()
        } catch (e: Exception) {
            // Only a convenience: without it the app just has to be opened by hand.
        }
    }

    /** True once after a silent install, so only those updates get the "installiert" notification. */
    fun consumeSilentInstall(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val silent = prefs.getBoolean(KEY_SILENT, false)
        if (silent) prefs.edit().remove(KEY_SILENT).apply()
        return silent
    }

    // Written synchronously: the process may be gone a moment later.
    private fun markSilentInstall(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SILENT, value).commit()
    }

    /** Shizuku 13 made newProcess private; it is still the simplest way to run one shell command. */
    private fun newProcess(cmd: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, cmd, null, null) as Process
    }
}
