@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tengigabytes.gymnotus.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.power.TreeGrouping

// ACCESS_LOCAL_NETWORK is new in Android 17 (API 37); older releases ignore the request and need no such permission.
@SuppressLint("InlinedApi")
private val SETUP_PERMISSIONS = arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_LOCAL_NETWORK)

private enum class Page(@StringRes val label: Int) {
    LIVE(R.string.tab_live),
    LOG(R.string.tab_log),
    DETAILS(R.string.tab_details),
}

/** The three pages and what they share: the sampler's state, the chart selection, and the system dialogs. */
@Composable
fun GymnotusScreen(viewModel: ProbeViewModel) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val history by viewModel.history.collectAsState()
    val deviceMap by viewModel.deviceMap.collectAsState()
    var page by rememberSaveable { mutableStateOf(Page.LIVE) }
    var grouping by rememberSaveable { mutableStateOf(TreeGrouping.SUBSYSTEM) }
    val context = LocalContext.current

    val selection = remember { ChartSelection() }
    val batteryLabel = stringResource(R.string.battery_series)
    var defaultsApplied by remember { mutableStateOf(false) }
    if (!defaultsApplied && state.rails.any { it.reading?.powerMw != null }) {
        LaunchedEffect(Unit) {
            selectDefaults(selection, buildTree(state.rails, TreeGrouping.SUBSYSTEM, state.battery, deviceMap), batteryLabel)
            defaultsApplied = true
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

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                PrimaryTabRow(selectedTabIndex = page.ordinal) {
                    for (entry in Page.entries) {
                        Tab(selected = page == entry, onClick = { page = entry }, text = { Text(stringResource(entry.label)) })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                Page.LIVE -> LiveTab(
                    state = state,
                    history = history,
                    selection = selection,
                    deviceMap = deviceMap,
                    grouping = grouping,
                    onGrouping = { grouping = it },
                    onShowSetup = { page = Page.DETAILS },
                    onRetry = viewModel::retry,
                )
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
                    onSlowWhenScreenOff = viewModel::setSlowWhenScreenOff,
                    onExport = { viewModel.prepareExport()?.let(exportLauncher::launch) },
                    onRetry = viewModel::retry,
                )
                Page.DETAILS -> DetailsTab(
                    state = state,
                    deviceMap = deviceMap,
                    onInterval = viewModel::setInterval,
                    onReset = viewModel::reset,
                    // Asked here, inside the app: a permission prompt over Settings would close the pairing dialog.
                    onSetUp = { setupPermissionsLauncher.launch(SETUP_PERMISSIONS) },
                    onRetry = viewModel::retry,
                )
            }
        }
    }
}
