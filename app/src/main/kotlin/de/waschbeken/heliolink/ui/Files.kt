package de.waschbeken.heliolink.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Speichern unter …": lets the user pick a place (e.g. Downloads or Google Drive) and runs [write]
 * there off the main thread. Returns the function that opens the picker with a suggested file name.
 */
@Composable
fun rememberSaveFile(mime: String, done: String = "Gespeichert", write: (Uri) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    write(uri)
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            Toast.makeText(context, error?.let { "Fehler: $it" } ?: done, Toast.LENGTH_LONG).show()
        }
    }
    return { name -> launcher.launch(name) }
}

/** Opens a file picker for [mimes] and runs [read] with the chosen file off the main thread. */
@Composable
fun rememberOpenFile(mimes: Array<String>, done: String? = null, onSuccess: () -> Unit = {}, read: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    read(uri)
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            val text = error?.let { "Fehler: $it" } ?: done
            if (text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            if (error == null) onSuccess()
        }
    }
    return { launcher.launch(mimes) }
}

/** Shares a file from the cache with another app (mail, messenger, Drive …). */
fun shareFile(context: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(file.name, uri)
    val chooser = Intent.createChooser(intent, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(chooser)
}

/** A file in the cache directory used for sharing. */
fun exportFile(context: Context, name: String): File = File(File(context.cacheDir, "exports").apply { mkdirs() }, name)
