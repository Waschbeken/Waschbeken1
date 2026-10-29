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
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** App-wide update state shared by the settings, the dialog and the start screen. */
class AppUpdater(val manager: UpdateManager, private val skipped: () -> String, private val skip: (String) -> Unit) {
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

    /** Shows an update the background check announced. */
    fun announce(info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Ready) return
        _state.value = UpdateState.Available(info)
    }
}
