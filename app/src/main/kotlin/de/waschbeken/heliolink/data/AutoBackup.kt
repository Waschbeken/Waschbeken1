package de.waschbeken.heliolink.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import de.waschbeken.heliolink.app
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Automatic backups (database, settings and auth key), so nothing is lost when the phone breaks or
 * the app has to be reinstalled. They go to a folder the user picked, otherwise to
 * Downloads/HelioLink; both survive uninstalling the app. Only the newest [Prefs.autoBackupKeep]
 * automatic backups are kept.
 */
object AutoBackup {
    const val PREFIX = "heliolink-auto-"
    private const val FOLDER = "HelioLink"
    private val lock = Any()

    fun isDue(prefs: Prefs, now: Long = System.currentTimeMillis()): Boolean =
        prefs.autoBackupDays > 0 && now - prefs.lastAutoBackup >= prefs.autoBackupDays * 86_400_000L - 3_600_000L

    /** The chosen folder, if the app still may write there (the permission is lost on reinstall). */
    fun tree(context: Context): Uri? {
        val text = context.app.prefs.autoBackupTree ?: return null
        val uri = Uri.parse(text)
        return uri.takeIf { u -> context.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission } }
    }

    /** Human readable place of the backups. */
    fun placeName(context: Context): String {
        val tree = tree(context)
        return when {
            tree != null -> "Ordner „" + (DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifBlank { "Hauptordner" }) + "“"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> "Downloads/$FOLDER"
            else -> "App-Ordner (wird beim Deinstallieren gelöscht – wähle einen Ordner)"
        }
    }

    /** Runs a backup now. Returns where it went; throws with a readable message on failure. */
    fun run(context: Context): String = synchronized(lock) {
        val app = context.app
        val backup = Backup(context, app.prefs, app.db)
        val name = PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".zip"
        try {
            val tree = tree(context)
            when {
                tree != null -> writeToTree(context, tree, name, backup)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> writeToDownloads(context, name, backup)
                else -> writeToAppFolder(context, name, backup)
            }
            val place = placeName(context)
            app.prefs.lastAutoBackup = System.currentTimeMillis()
            app.prefs.lastAutoBackupInfo = place
            app.prefs.lastAutoBackupFailed = false
            place
        } catch (e: Exception) {
            app.prefs.lastAutoBackupInfo = e.message ?: e.javaClass.simpleName
            app.prefs.lastAutoBackupFailed = true
            throw e
        }
    }

    /** Backup if one is due; never throws. */
    fun runIfDue(context: Context) {
        synchronized(lock) {
            if (!isDue(context.app.prefs)) return
            try {
                run(context)
            } catch (e: Exception) {
                CrashReporter.nonFatal(context, e)
            }
        }
    }

    /** [runIfDue] on a background thread. */
    fun runIfDueInBackground(context: Context) {
        val app = context.applicationContext
        if (!isDue(app.app.prefs)) return
        kotlin.concurrent.thread(name = "backup", isDaemon = true) { runIfDue(app) }
    }

    private fun writeToTree(context: Context, tree: Uri, name: String, backup: Backup) {
        val resolver = context.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val doc = DocumentsContract.createDocument(resolver, parent, "application/zip", name)
            ?: throw Backup.BackupException("Im gewählten Ordner lässt sich keine Datei anlegen")
        backup.createBackup(doc)
        pruneTree(context, tree, context.app.prefs.autoBackupKeep)
    }

    /** Deletes all but the newest [keep] automatic backups in the current place. */
    fun prune(context: Context, keep: Int) = synchronized(lock) {
        val tree = tree(context)
        when {
            tree != null -> pruneTree(context, tree, keep)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> pruneDownloads(context, keep)
            else -> pruneAppFolder(context, keep)
        }
    }

    private fun pruneTree(context: Context, tree: Uri, keep: Int) {
        val resolver = context.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val old = ArrayList<Pair<String, String>>() // name, id
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1) ?: continue
                if (n.startsWith(PREFIX) && n.endsWith(".zip")) old += n to c.getString(0)
            }
        }
        old.sortedByDescending { it.first }.drop(keep.coerceAtLeast(1)).forEach { (_, id) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    private fun writeToDownloads(context: Context, name: String, backup: Backup) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val folder = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: throw Backup.BackupException("Downloads ist nicht verfügbar")
        try {
            backup.createBackup(uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        pruneDownloads(context, context.app.prefs.autoBackupKeep)
    }

    private fun pruneDownloads(context: Context, keep: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val folder = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Only files this installation created are visible here; older ones stay.
        val old = ArrayList<Pair<String, Long>>()
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(folder, "$PREFIX%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) old += (c.getString(1) ?: "") to c.getLong(0)
        }
        old.sortedByDescending { it.first }.drop(keep.coerceAtLeast(1)).forEach { (_, id) ->
            runCatching { resolver.delete(android.content.ContentUris.withAppendedId(collection, id), null, null) }
        }
    }

    private fun writeToAppFolder(context: Context, name: String, backup: Backup) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Sicherungen").apply { mkdirs() }
        backup.createBackup(File(dir, name).outputStream())
        pruneAppFolder(context, context.app.prefs.autoBackupKeep)
    }

    private fun pruneAppFolder(context: Context, keep: Int) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Sicherungen")
        dir.listFiles { f -> f.name.startsWith(PREFIX) }?.sortedByDescending { it.name }
            ?.drop(keep.coerceAtLeast(1))?.forEach { it.delete() }
    }
}
