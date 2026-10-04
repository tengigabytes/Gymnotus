package io.github.tengigabytes.gymnotus.ui

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tengigabytes.gymnotus.GymnotusApp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.adb.FastModeService
import io.github.tengigabytes.gymnotus.adb.SelfAdb
import io.github.tengigabytes.gymnotus.power.CsvExport
import io.github.tengigabytes.gymnotus.power.DeviceMap
import io.github.tengigabytes.gymnotus.power.TreeGrouping
import io.github.tengigabytes.gymnotus.sampler.ExportSnapshot
import io.github.tengigabytes.gymnotus.sampler.HistoryPoint
import io.github.tengigabytes.gymnotus.sampler.LogService
import io.github.tengigabytes.gymnotus.sampler.RailRow
import io.github.tengigabytes.gymnotus.sampler.Sampler
import io.github.tengigabytes.gymnotus.sampler.SamplerState
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
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

    val history: StateFlow<List<HistoryPoint>> = sampler.historyFlow

    private val _deviceMap = MutableStateFlow<DeviceMap?>(null)

    /** The bundled map for this device, or null if there is none. */
    val deviceMap: StateFlow<DeviceMap?> = _deviceMap.asStateFlow()

    init {
        viewModelScope.launch { _deviceMap.value = withContext(Dispatchers.IO) { loadDeviceMap() } }
    }

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
            _message.value = text(R.string.msg_nothing_to_export)
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
                text(R.string.msg_exported, snapshot.polls.size, snapshot.monitors.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                text(R.string.msg_export_failed, e.toString())
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
        _message.value = polls?.let { text(R.string.msg_log_closed, it) }
    }

    fun setSlowWhenScreenOff(enabled: Boolean) = sampler.setSlowWhenScreenOff(enabled)

    /** Shows the notification that takes the wireless-debugging pairing code; the rest happens in FastModeService. */
    fun setUpFastMode(notificationsAllowed: Boolean) {
        if (!notificationsAllowed) {
            _message.value = text(R.string.msg_setup_needs_notifications)
            return
        }
        FastModeService.showPrompt(getApplication())
        _message.value = text(if (SelfAdb.canDiscover(getApplication())) R.string.msg_setup_posted else R.string.msg_setup_posted_manual)
    }

    override fun onCleared() {
        stop()
    }

    private val uiPrefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    // What the Live page was last showing; unknown or missing values fall back to the defaults.
    var grouping: TreeGrouping
        get() = TreeGrouping.entries.firstOrNull { it.name == uiPrefs.getString(PREF_GROUPING, null) } ?: TreeGrouping.SUBSYSTEM
        set(value) = uiPrefs.edit { putString(PREF_GROUPING, value.name) }

    var sortByPower: Boolean
        get() = uiPrefs.getBoolean(PREF_SORT_BY_POWER, false)
        set(value) = uiPrefs.edit { putBoolean(PREF_SORT_BY_POWER, value) }

    var visual: Visual
        get() = Visual.entries.firstOrNull { it.name == uiPrefs.getString(PREF_VISUAL, null) } ?: Visual.FLOW
        set(value) = uiPrefs.edit { putString(PREF_VISUAL, value.name) }

    // Monitors are saved by name rather than index: the names identify a rail, the order is only today's listing.
    fun saveSelection(series: List<SeriesSpec>, rails: List<RailRow>) {
        val names = rails.associate { it.info.index to it.info.name }
        val array = JSONArray()
        for (spec in series) {
            array.put(
                JSONObject()
                    .put("key", spec.key)
                    .put("label", spec.label)
                    .put("slot", spec.slot)
                    .put("monitors", JSONArray(spec.indices.mapNotNull { names[it] })),
            )
        }
        uiPrefs.edit { putString(PREF_SELECTION, array.toString()) }
    }

    // Null when nothing was ever saved, so the caller can tell "no choice yet" from "chose nothing".
    // A series whose monitors no longer exist is dropped.
    fun savedSelection(rails: List<RailRow>): List<SeriesSpec>? {
        val json = uiPrefs.getString(PREF_SELECTION, null) ?: return null
        val indexOf = rails.associate { it.info.name to it.info.index }
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val item = array.getJSONObject(i)
                val monitors = item.getJSONArray("monitors")
                val indices = (0 until monitors.length()).mapNotNull { indexOf[monitors.getString(it)] }
                if (indices.size != monitors.length() || indices.isEmpty()) null else SeriesSpec(item.getString("key"), item.getString("label"), indices, item.getInt("slot"))
            }
        } catch (e: JSONException) {
            null
        }
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun loadDeviceMap(): DeviceMap? {
        val assets = getApplication<Application>().assets
        return assets.list(DEVICE_MAPS_DIR).orEmpty().filter { it.endsWith(".json") }.firstNotNullOfOrNull { file ->
            try {
                DeviceMap.parse(assets.open("$DEVICE_MAPS_DIR/$file").bufferedReader().use { it.readText() })
                    .takeIf { Build.DEVICE in it.devices }
            } catch (e: Exception) {
                // A malformed contributed map must not take the app down; it is simply not used.
                null
            }
        }
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
    }

    private companion object {
        const val DEVICE_MAPS_DIR = "device-maps"
        const val PREF_GROUPING = "grouping"
        const val PREF_VISUAL = "visual"
        const val PREF_SORT_BY_POWER = "sort_by_power"
        const val PREF_SELECTION = "chart_selection"
    }
}
