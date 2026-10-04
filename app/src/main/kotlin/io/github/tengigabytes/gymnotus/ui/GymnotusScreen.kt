@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tengigabytes.gymnotus.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.power.TreeGrouping

// ACCESS_LOCAL_NETWORK is new in Android 17 (API 37); older releases ignore the request and need no such permission.
@SuppressLint("InlinedApi")
private val SETUP_PERMISSIONS = arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_LOCAL_NETWORK)

private enum class Page(@StringRes val label: Int) {
    LIVE(R.string.tab_live),
    DASHBOARD(R.string.tab_dashboard),
    LOG(R.string.tab_log),
    DETAILS(R.string.tab_details),
}

/** What covers the pages when the gear is tapped: the settings, or one of the legal texts opened from them. */
private enum class Overlay { NONE, SETTINGS, DOCUMENT }

/** The pages and what they share: the sampler's state, the chart selection, the settings and the system dialogs. */
@Composable
fun GymnotusScreen(viewModel: ProbeViewModel) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val history by viewModel.history.collectAsState()
    val deviceMap by viewModel.deviceMap.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var page by rememberSaveable { mutableStateOf(Page.LIVE) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    var document by rememberSaveable { mutableStateOf(LegalDocument.LICENSE) }
    var grouping by remember { mutableStateOf(viewModel.grouping) }
    var visual by remember { mutableStateOf(viewModel.visual) }
    var sortByPower by remember { mutableStateOf(viewModel.sortByPower) }
    val context = LocalContext.current

    val selection = remember { ChartSelection() }
    val batteryLabel = stringResource(R.string.battery_series)
    var defaultsApplied by remember { mutableStateOf(false) }
    if (!defaultsApplied && state.rails.any { it.reading?.powerMw != null }) {
        LaunchedEffect(Unit) {
            // The last selection if there was one, even an empty one; the defaults only on first use.
            val saved = viewModel.savedSelection(state.rails)
            if (saved != null) {
                selection.restore(saved)
            } else {
                selectDefaults(selection, buildTree(state.rails, TreeGrouping.SUBSYSTEM, state.battery, deviceMap, sortByPower = true), batteryLabel)
            }
            defaultsApplied = true
        }
    }
    // Saving starts only once the selection has been restored, or the empty starting state would overwrite it.
    if (defaultsApplied) {
        LaunchedEffect(Unit) {
            snapshotFlow { selection.series.toList() }.collect { viewModel.saveSelection(it, state.rails) }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(uri) else viewModel.cancelExport()
    }
    val logLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.startLog(uri)
    }
    // The log runs whether or not its notification may be shown, so the answer is not used.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        logLauncher.launch(viewModel.suggestedLogName())
    }
    val setupPermissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        viewModel.setUpFastMode(notificationsAllowed = results[Manifest.permission.POST_NOTIFICATIONS] == true)
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it) } }

    // Back leaves a legal text for the settings, and the settings for the pages.
    BackHandler(enabled = overlay != Overlay.NONE) {
        overlay = if (overlay == Overlay.DOCUMENT) Overlay.SETTINGS else Overlay.NONE
    }

    CompositionLocalProvider(LocalPowerUnit provides settings.powerUnit) {
        Scaffold(
            topBar = {
                Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    if (overlay == Overlay.NONE) {
                        PrimaryTabRow(selectedTabIndex = page.ordinal, modifier = Modifier.weight(1f)) {
                            for (entry in Page.entries) {
                                Tab(selected = page == entry, onClick = { page = entry }, text = { Text(stringResource(entry.label)) })
                            }
                        }
                        IconButton(onClick = { overlay = Overlay.SETTINGS }) {
                            Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings_title))
                        }
                    } else {
                        TextButton(onClick = { overlay = if (overlay == Overlay.DOCUMENT) Overlay.SETTINGS else Overlay.NONE }) {
                            Text(stringResource(R.string.back))
                        }
                        Text(
                            stringResource(if (overlay == Overlay.DOCUMENT) document.title else R.string.settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (overlay) {
                    Overlay.SETTINGS -> SettingsScreen(
                        state = state,
                        settings = settings,
                        onSettings = viewModel::updateSettings,
                        onInterval = viewModel::setInterval,
                        onSlowWhenScreenOff = viewModel::setSlowWhenScreenOff,
                        // Asked here, inside the app: a permission prompt over Settings would close the pairing dialog.
                        onSetUp = { setupPermissionsLauncher.launch(SETUP_PERMISSIONS) },
                        onDocument = {
                            document = it
                            overlay = Overlay.DOCUMENT
                        },
                    )
                    Overlay.DOCUMENT -> LegalText(remember(document) { viewModel.legalText(document) }, markdown = document.asset.endsWith(".md"))
                    Overlay.NONE -> when (page) {
                        Page.LIVE -> LiveTab(
                            state = state,
                            history = history,
                            selection = selection,
                            deviceMap = deviceMap,
                            flowSubsystems = settings.flowSubsystems,
                            grouping = grouping,
                            onGrouping = {
                                grouping = it
                                viewModel.grouping = it
                            },
                            visual = visual,
                            onVisual = {
                                visual = it
                                viewModel.visual = it
                            },
                            sortByPower = sortByPower,
                            onSortByPower = {
                                sortByPower = it
                                viewModel.sortByPower = it
                            },
                            onShowSetup = { overlay = Overlay.SETTINGS },
                            onRetry = viewModel::retry,
                        )
                        Page.DASHBOARD -> DashboardTab(state, history, deviceMap, onRetry = viewModel::retry)
                        Page.LOG -> LogTab(
                            state = state,
                            onStartLog = {
                                val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                                    PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    logLauncher.launch(viewModel.suggestedLogName())
                                } else {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onStopLog = viewModel::stopLog,
                            onExport = { viewModel.prepareExport()?.let(exportLauncher::launch) },
                            onRetry = viewModel::retry,
                        )
                        Page.DETAILS -> DetailsTab(state = state, deviceMap = deviceMap, onReset = viewModel::reset, onRetry = viewModel::retry)
                    }
                }
            }
        }
    }
}
