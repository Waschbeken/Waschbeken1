package de.waschbeken.heliolink.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateState

    /** Silent install through Shizuku; the app closes when it is done. */
    data class Installing(val info: UpdateInfo) : UpdateState

    /** The update is signed with a different key (the new password protected one): reinstall needed. */
    data class KeyChanged(val info: UpdateInfo, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** App-wide update state shared by the settings, the dialog and the start screen. */
class AppUpdater(
    val manager: UpdateManager,
    private val skipped: () -> String,
    private val skip: (String) -> Unit,
    private val useShizuku: () -> Boolean = { false },
    /** Runs on a background thread right before an update is installed (automatic backup). */
    private val beforeInstall: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val showDialog = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val currentVersion get() = manager.currentVersion

    fun check(silent: Boolean = false) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        scope.launch {
            _state.value = UpdateState.Checking
            try {
                val info = manager.check()
                val ignore = silent && info?.version == skipped()
                _state.value = when {
                    info == null -> UpdateState.UpToDate
                    ignore -> UpdateState.Idle
                    else -> UpdateState.Available(info)
                }
                if (info != null && !ignore) showDialog.value = true
                if (info == null && !silent) _messages.tryEmit("Du hast die neueste Version ($currentVersion).")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = if (silent) UpdateState.Idle else UpdateState.Failed(e.message ?: "Update-Prüfung fehlgeschlagen")
                if (!silent) _messages.tryEmit(e.message ?: "Update-Prüfung fehlgeschlagen")
            }
        }
    }

    fun download(info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        scope.launch {
            _state.value = UpdateState.Downloading(info, 0f)
            try {
                val apk = manager.download(info) { p -> _state.value = UpdateState.Downloading(info, p) }
                if (manager.sameSignature(apk) == false) {
                    _state.value = UpdateState.KeyChanged(info, apk)
                    showDialog.value = true
                    return@launch
                }
                _state.value = UpdateState.Ready(info, apk)
                install(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "Download fehlgeschlagen")
            }
        }
    }

    fun install(apk: File) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { beforeInstall() } }
            installNow(apk)
        }
    }

    private fun installNow(apk: File) {
        if (useShizuku() && ShizukuInstaller.status() == ShizukuInstaller.Status.READY) {
            val info = (_state.value as? UpdateState.Ready)?.info
            scope.launch {
                try {
                    if (info != null) _state.value = UpdateState.Installing(info)
                    ShizukuInstaller.install(manager.context, apk)
                    // Normally not reached: Android restarts the app with the new version.
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _messages.tryEmit("Shizuku-Installation fehlgeschlagen: ${e.message} – öffne den Android-Installer")
                    if (info != null) _state.value = UpdateState.Ready(info, apk)
                    installWithSystem(apk)
                }
            }
            return
        }
        installWithSystem(apk)
    }

    private fun installWithSystem(apk: File) {
        if (!manager.canInstallPackages()) {
            _messages.tryEmit("Bitte erlaube HelioLink einmal, Apps zu installieren, und tippe dann erneut auf „Installieren“.")
            manager.openInstallPermissionSettings()
            return
        }
        manager.install(apk)
    }

    fun dismiss(skipVersion: Boolean) {
        val s = _state.value
        showDialog.value = false
        if (skipVersion && s is UpdateState.Available) {
            skip(s.info.version)
            _state.value = UpdateState.Idle
        }
    }

    /**
     * Background install through Shizuku while the app is not open. Returns true when the install
     * was started (the app then closes and restarts).
     */
    suspend fun installSilently(info: UpdateInfo): Boolean {
        if (ShizukuInstaller.awaitStatus() != ShizukuInstaller.Status.READY) return false
        return try {
            val apk = manager.download(info) {}
            if (manager.sameSignature(apk) == false) return false
            withContext(Dispatchers.IO) { runCatching { beforeInstall() } }
            ShizukuInstaller.install(manager.context, apk)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Shows an update the background check announced. */
    fun announce(info: UpdateInfo) {
        val s = _state.value
        if (s is UpdateState.Downloading || s is UpdateState.Ready || s is UpdateState.KeyChanged) return
        _state.value = UpdateState.Available(info)
    }
}
