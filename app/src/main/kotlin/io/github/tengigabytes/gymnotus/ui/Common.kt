package io.github.tengigabytes.gymnotus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.power.IntervalSummary
import io.github.tengigabytes.gymnotus.sampler.MonitorListState
import io.github.tengigabytes.gymnotus.sampler.SamplerState

/** Small monospaced line for diagnostics and technical values. */
@Composable
fun Mono(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
fun minMedMax(summary: IntervalSummary): String =
    if (summary.count == 0L) {
        stringResource(R.string.min_med_max_none)
    } else {
        stringResource(R.string.min_med_max, summary.minMs ?: 0L, summary.medianMs ?: 0L, summary.maxMs ?: 0L)
    }

/** What to show instead of readings while the monitor list is loading, empty or failed. */
@Composable
fun ListStatus(state: SamplerState, onRetry: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.listState) {
            MonitorListState.LOADING -> Text(stringResource(R.string.list_waiting))
            MonitorListState.EMPTY -> {
                Text(stringResource(R.string.list_empty))
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            MonitorListState.FAILED -> {
                // The system's own error text, shown as it came.
                Mono(state.listError.orEmpty(), error = true)
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            MonitorListState.READY -> Unit
        }
    }
}
