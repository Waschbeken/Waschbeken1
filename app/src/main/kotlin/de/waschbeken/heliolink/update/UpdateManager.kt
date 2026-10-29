package de.waschbeken.heliolink.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import de.waschbeken.heliolink.BuildConfig
import de.waschbeken.heliolink.core.Versions
import de.waschbeken.heliolink.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    val htmlUrl: String,
)

/**
 * Looks for a newer APK in the GitHub releases of the update repository (tags `app-vX.Y.Z`),
 * downloads it and hands it to the system installer. The repository is public, so no token is needed.
 */
class UpdateManager(private val context: Context, private val prefs: Prefs) {
    val currentVersion: String = BuildConfig.VERSION_NAME.substringBefore("-")

    suspend fun check(): UpdateInfo? {
        val releases = get("https://api.github.com/repos/${prefs.updateRepo.trim()}/releases?per_page=30")
        prefs.lastUpdateCheck = System.currentTimeMillis()
        return pick(JSONArray(releases))
    }

    private fun pick(releases: JSONArray): UpdateInfo? {
        var best: UpdateInfo? = null
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft") || (r.optBoolean("prerelease") && !prefs.includePrereleases)) continue
            val tag = r.optString("tag_name")
            val version = Versions.ofTag(tag) ?: continue
            if (Versions.compare(version, currentVersion) <= 0) continue
            val assets = r.optJSONArray("assets") ?: continue
            var apk: UpdateInfo? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apk = UpdateInfo(
                        version = version,
                        tag = tag,
                        notes = r.optString("body").takeIf { it != "null" }.orEmpty(),
                        apkUrl = a.optString("browser_download_url"),
                        size = a.optLong("size"),
                        htmlUrl = r.optString("html_url"),
                    )
                    break
                }
            }
            if (apk != null && (best == null || Versions.compare(apk.version, best.version) > 0)) best = apk
        }
        return best
    }

    /** True when the check on app start should run (at most every 12 h, or when a version was announced). */
    fun isAutoCheckDue(): Boolean {
        if (!prefs.autoCheckUpdates) return false
        val announced = prefs.notifiedUpdateVersion.isNotBlank() && prefs.notifiedUpdateVersion != currentVersion
        return announced || System.currentTimeMillis() - prefs.lastUpdateCheck > 12 * 60 * 60 * 1000L
    }

    /** Background check, at most every 6 h: the update to announce, or null. */
    suspend fun checkInBackground(): UpdateInfo? {
        if (!prefs.autoCheckUpdates || System.currentTimeMillis() - prefs.lastUpdateCheck < 6 * 60 * 60 * 1000L) return null
        val info = try {
            check()
        } catch (_: IOException) {
            null
        } ?: return null
        if (prefs.notifiedUpdateVersion == info.version || prefs.skippedVersion == info.version) return null
        prefs.notifiedUpdateVersion = info.version
        return info
    }

    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "heliolink-${info.version}.apk")
        val tmp = File(dir, target.name + ".part")
        val connection = open(info.apkUrl, "application/octet-stream")
        try {
            if (connection.responseCode !in 200..299) throw IOException("Download fehlgeschlagen (${connection.responseCode})")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.size
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        target
    }

    /** Android requires the user to allow "install unknown apps" once for this app. */
    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = open(url, "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException(
                    when (code) {
                        403 -> "GitHub-Limit erreicht, bitte später erneut versuchen."
                        404 -> "Update-Quelle nicht gefunden (${prefs.updateRepo})."
                        else -> "GitHub-Fehler $code"
                    }
                )
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw if (e.message?.startsWith("GitHub") == true || e.message?.startsWith("Update") == true) e
            else IOException("Keine Verbindung zu GitHub (${e.message ?: e.javaClass.simpleName})")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "HelioLink-Android")
        }
}
