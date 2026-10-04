package io.github.tengigabytes.gymnotus.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tengigabytes.gymnotus.GymnotusApp
import io.github.tengigabytes.gymnotus.power.CsvExport
import io.github.tengigabytes.gymnotus.sampler.ExportSnapshot
import io.github.tengigabytes.gymnotus.sampler.LogService
import io.github.tengigabytes.gymnotus.sampler.Sampler
import io.github.tengigabytes.gymnotus.sampler.SamplerState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connects the screen to the process-wide [Sampler] and performs the user's file operations. */
class ProbeViewModel(app: Application) : AndroidViewModel(app) {
    private val sampler = (app as GymnotusApp).sampler

    val state: StateFlow<SamplerState> = sampler.state

    private val _message = MutableStateFlow<String?>(null)

    /** Outcome of the last export or log action. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private var pendingExport: ExportSnapshot? = null

    /** Called while the activity is visible; a running log keeps the sampler going after [stop]. */
    fun start() = sampler.acquire(Sampler.Holder.UI)

    fun stop() = sampler.release(Sampler.Holder.UI)

    fun retry() = sampler.retry()

    fun setInterval(ms: Int) = sampler.setInterval(ms)

    fun reset() = sampler.reset()

    /** Freezes the current buffer for export; returns the suggested file name, or null if there is nothing yet. */
    fun prepareExport(): String? {
        pendingExport = sampler.snapshot()
        if (pendingExport == null) {
            _message.value = "Nothing to export yet"
            return null
        }
        return sampler.suggestedFileName("buffer")
    }

    fun cancelExport() {
        pendingExport = null
    }

    fun export(uri: Uri) {
        val snapshot = pendingExport ?: return
        pendingExport = null
        viewModelScope.launch {
            _message.value = try {
                withContext(Dispatchers.IO) {
                    val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: error("Cannot open $uri for writing")
                    stream.bufferedWriter().use {
                        CsvExport.write(it, snapshot.meta, snapshot.monitors, snapshot.polls, snapshot.stats)
                    }
                }
                "Exported ${snapshot.polls.size} polls x ${snapshot.monitors.size} monitors"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Export failed: $e"
            }
        }
    }

    fun suggestedLogName(): String = sampler.suggestedFileName("log")

    fun startLog(uri: Uri) {
        val app = getApplication<Application>()
        // The grant from the document picker would otherwise end with the activity that received it.
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        app.startForegroundService(LogService.startIntent(app, uri, displayName(uri)))
        _message.value = null
    }

    fun stopLog() {
        val app = getApplication<Application>()
        val polls = state.value.log?.polls
        app.startService(LogService.stopIntent(app))
        _message.value = polls?.let { "Log closed after $it polls" }
    }

    override fun onCleared() {
        stop()
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
    }
}
