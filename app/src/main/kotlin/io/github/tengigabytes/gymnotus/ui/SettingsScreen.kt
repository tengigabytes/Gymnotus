package io.github.tengigabytes.gymnotus.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.sampler.Sampler
import io.github.tengigabytes.gymnotus.sampler.SamplerState

private const val GRANT_COMMAND =
    "adb shell pm grant io.github.tengigabytes.gymnotus android.permission.ACCESS_FINE_POWER_MONITORS"

/** The three legal texts bundled as assets (see the legalAssets task in app/build.gradle.kts). */
enum class LegalDocument(val asset: String, @StringRes val title: Int) {
    LICENSE("legal/LICENSE", R.string.about_license_button),
    THIRD_PARTY("legal/THIRD_PARTY.md", R.string.about_third_party),
    PRIVACY("legal/PRIVACY.md", R.string.about_privacy),
}

/** Everything the user can switch, in one place; the other pages only show. */
@Composable
fun SettingsScreen(
    state: SamplerState,
    settings: UiSettings,
    onSettings: (UiSettings) -> Unit,
    onInterval: (Int) -> Unit,
    onSlowWhenScreenOff: (Boolean) -> Unit,
    onSetUp: () -> Unit,
    onDocument: (LegalDocument) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionTitle(stringResource(R.string.section_mode))
        ModeNotice(state.finePermission, onSetUp)

        SectionTitle(stringResource(R.string.section_polling))
        // One log has one interval and one run of poll numbers, so the interval is locked while it is open.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (ms in Sampler.INTERVAL_CHOICES_MS) {
                FilterChip(
                    selected = state.intervalMs == ms,
                    enabled = state.log == null,
                    onClick = { onInterval(ms) },
                    label = { Text(stringResource(R.string.interval_ms, ms)) },
                )
            }
        }
        SwitchRow(
            title = stringResource(R.string.slow_screen_off, Sampler.SCREEN_OFF_INTERVAL_MS / 1000),
            hint = stringResource(R.string.slow_screen_off_hint),
            checked = state.slowWhenScreenOff,
            onChecked = onSlowWhenScreenOff,
        )

        SectionTitle(stringResource(R.string.section_display))
        SwitchRow(
            title = stringResource(R.string.setting_keep_screen_on),
            hint = stringResource(R.string.setting_keep_screen_on_hint),
            checked = settings.keepScreenOn,
            onChecked = { onSettings(settings.copy(keepScreenOn = it)) },
        )
        Text(stringResource(R.string.setting_unit), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (unit in PowerUnit.entries) {
                FilterChip(selected = settings.powerUnit == unit, onClick = { onSettings(settings.copy(powerUnit = unit)) }, label = { Text(unit.symbol) })
            }
        }
        Hint(stringResource(R.string.setting_unit_hint))
        Text(stringResource(R.string.setting_flow_nodes), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (count in UiSettings.FLOW_SUBSYSTEM_CHOICES) {
                FilterChip(
                    selected = settings.flowSubsystems == count,
                    onClick = { onSettings(settings.copy(flowSubsystems = count)) },
                    label = { Text(count.toString()) },
                )
            }
        }
        Hint(stringResource(R.string.setting_flow_nodes_hint))

        SectionTitle(stringResource(R.string.section_about))
        val context = LocalContext.current
        val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
        Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodyMedium)
        for (document in LegalDocument.entries) {
            TextButton(onClick = { onDocument(document) }) { Text(stringResource(document.title)) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Which of the two refresh limits applies, and how to get the faster one. */
@Composable
private fun ModeNotice(finePermission: Boolean, onSetUp: () -> Unit) {
    if (finePermission) {
        Text(stringResource(R.string.mode_fast), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val context = LocalContext.current
    Text(stringResource(R.string.mode_standard), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.setup_steps), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onSetUp) { Text(stringResource(R.string.setup_button)) }
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                    // Developer options are hidden until enabled in About phone; nothing to open yet.
                }
            },
        ) { Text(stringResource(R.string.developer_options)) }
    }
    Mono(stringResource(R.string.setup_computer))
    SelectionContainer { Mono(GRANT_COMMAND) }
}

/** A bundled legal text, shown as it is. */
@Composable
fun LegalText(text: String) {
    SelectionContainer {
        // The files are hard-wrapped for a terminal; the screen is narrower, so it wraps them itself.
        Text(
            remember(text) { reflow(text) },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}
