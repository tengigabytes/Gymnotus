// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.sampler.LogStatus
import io.github.tengigabytes.gymnotus.sampler.MonitorListState
import io.github.tengigabytes.gymnotus.sampler.Sampler
import io.github.tengigabytes.gymnotus.sampler.SamplerState
import java.util.Locale

/** Getting readings out of the app: a log written while sampling, or the buffer of the last few minutes. */
@Composable
fun LogTab(
    state: SamplerState,
    onStartLog: () -> Unit,
    onStopLog: () -> Unit,
    onExport: () -> Unit,
    onRetry: () -> Unit,
) {
    if (state.listState != MonitorListState.READY) {
        ListStatus(state, onRetry)
        return
    }
    val log = state.log
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(stringResource(R.string.section_log))
        Text(stringResource(R.string.log_hint), style = MaterialTheme.typography.bodyMedium)
        if (log == null) {
            Button(onClick = onStartLog) { Text(stringResource(R.string.start_log)) }
        } else {
            Button(onClick = onStopLog) { Text(stringResource(R.string.stop_log)) }
            LogLine(log)
        }

        SectionTitle(stringResource(R.string.section_buffer))
        Text(stringResource(R.string.buffer_hint, (Sampler.BUFFER_SPAN_MS / 1000).toInt()), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onExport) { Text(stringResource(R.string.export_buffer)) }
            val filled = String.format(Locale.ROOT, "%.0f", state.bufferSpanMs / 1000.0)
            Mono(stringResource(R.string.buffer_span, filled, (Sampler.BUFFER_SPAN_MS / 1000).toInt()))
        }
    }
}

@Composable
private fun LogLine(log: LogStatus) {
    if (log.error != null) {
        Mono(log.error, error = true)
        return
    }
    val seconds = (SystemClock.elapsedRealtime() - log.startedElapsedMs) / 1000
    val elapsed = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    Mono(stringResource(R.string.log_status, log.fileName, log.polls, elapsed))
}
