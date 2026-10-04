// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.sampler.MonitorListState
import io.github.tengigabytes.gymnotus.sampler.SamplerState

/** Small monospaced line for diagnostics and technical values. */
@Composable
fun Mono(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text = text,
        modifier = modifier,
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

// A changing value on a line of its own: label on the left, value right-aligned in tabular digits and never
// wrapped, so that a refresh replaces digits in place instead of moving text around.
@Composable
fun Field(label: String, value: String, error: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            fontFamily = FontFamily.Monospace,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
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
