package io.github.xpertsavenue.ridevault.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xpertsavenue.ridevault.device.Backlight
import io.github.xpertsavenue.ridevault.device.DeviceSetting
import io.github.xpertsavenue.ridevault.device.SpeedUnit

private enum class DeviceDialog { LANGUAGE, UNITS, TIME_ZONE, BACKLIGHT, TIMED_BACKLIGHT }

private fun hhmm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

@Composable
private fun backlightLabel(b: Backlight): String = when (b) {
    Backlight.Off -> stringResource(R.string.bl_off)
    Backlight.AlwaysOn -> stringResource(R.string.bl_always)
    is Backlight.Timeout -> backlightSecondsLabel(b.seconds)
}

@Composable
private fun backlightSecondsLabel(seconds: Int): String = when (seconds) {
    30 -> stringResource(R.string.bl_30)
    60 -> stringResource(R.string.bl_60)
    300 -> stringResource(R.string.bl_300)
    600 -> stringResource(R.string.bl_600)
    else -> "$seconds s"
}

/**
 * Einstellungen des Fahrradcomputers. Das Gerät meldet seine Werte nicht zurück, angezeigt wird der zuletzt
 * von dieser App gesendete Wert (oder "Nicht gesetzt").
 */
@Composable
fun DeviceSettingsCard(
    values: DeviceValues,
    busy: Boolean,
    autoSyncTime: Boolean,
    onAutoSyncTime: (Boolean) -> Unit,
    onApply: (DeviceSetting) -> Unit,
) {
    var dialog by remember { mutableStateOf<DeviceDialog?>(null) }
    val notSet = stringResource(R.string.dev_not_set)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(stringResource(R.string.dev_settings_title), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.dev_settings_note),
                Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.dev_auto_time)) },
                trailingContent = { Switch(checked = autoSyncTime, onCheckedChange = onAutoSyncTime) },
            )
            SettingRow(stringResource(R.string.dev_language), values.language?.let { DeviceLanguages.getOrNull(it - 1) } ?: notSet, !busy) { dialog = DeviceDialog.LANGUAGE }
            SettingRow(stringResource(R.string.dev_units), values.speedUnit?.let { if (it == SpeedUnit.MPH) "mph" else "km/h" } ?: notSet, !busy) { dialog = DeviceDialog.UNITS }
            SettingRow(
                stringResource(R.string.dev_timezone),
                if (values.tzHours != null && values.tzEast != null) {
                    timeZoneOption(values.tzHours, values.tzEast)?.label ?: notSet
                } else notSet,
                !busy,
            ) { dialog = DeviceDialog.TIME_ZONE }
            SettingRow(stringResource(R.string.dev_backlight), values.backlight?.let { backlightLabel(it) } ?: notSet, !busy) { dialog = DeviceDialog.BACKLIGHT }
            SettingRow(
                stringResource(R.string.dev_timed_backlight),
                when {
                    values.timedOn == null -> notSet
                    values.timedOn && values.timedStart != null && values.timedEnd != null -> "${hhmm(values.timedStart)} – ${hhmm(values.timedEnd)}"
                    else -> stringResource(R.string.bl_off)
                },
                !busy,
            ) { dialog = DeviceDialog.TIMED_BACKLIGHT }
        }
    }

    when (dialog) {
        DeviceDialog.LANGUAGE -> LanguageDialog(values.language, { onApply(DeviceSetting.Language(it)); dialog = null }) { dialog = null }
        DeviceDialog.UNITS -> UnitsDialog(values.speedUnit, { onApply(DeviceSetting.Units(it)); dialog = null }) { dialog = null }
        DeviceDialog.TIME_ZONE -> TimeZoneDialog(values, { h, east -> onApply(DeviceSetting.TimeZone(h, east)); dialog = null }) { dialog = null }
        DeviceDialog.BACKLIGHT -> BacklightDialog(values.backlight, { onApply(DeviceSetting.BacklightMode(it)); dialog = null }) { dialog = null }
        DeviceDialog.TIMED_BACKLIGHT -> TimedBacklightDialog(values, { s, e -> onApply(DeviceSetting.TimedBacklight(s, e)); dialog = null }) { dialog = null }
        null -> Unit
    }
}

@Composable
private fun SettingRow(title: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun ChoiceDialog(title: String, onDismiss: () -> Unit, confirm: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } },
        confirmButton = { confirm?.invoke() },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageDialog(current: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ChoiceDialog(stringResource(R.string.dev_language), onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeviceLanguages.forEachIndexed { i, name ->
                FilterChip(selected = current == i + 1, onClick = { onPick(i + 1) }, label = { Text(name) })
            }
        }
    }
}

@Composable
private fun UnitsDialog(current: SpeedUnit?, onPick: (SpeedUnit) -> Unit, onDismiss: () -> Unit) {
    ChoiceDialog(stringResource(R.string.dev_units), onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = current == SpeedUnit.KMH, onClick = { onPick(SpeedUnit.KMH) }, label = { Text("km/h") })
            FilterChip(selected = current == SpeedUnit.MPH, onClick = { onPick(SpeedUnit.MPH) }, label = { Text("mph") })
        }
    }
}

@Composable
private fun TimeZoneDialog(values: DeviceValues, onSave: (Int, Boolean) -> Unit, onDismiss: () -> Unit) {
    val current = if (values.tzHours != null && values.tzEast != null) timeZoneOption(values.tzHours, values.tzEast) else null
    var selected by remember { mutableStateOf(current ?: phoneTimeZoneOption() ?: TimeZoneOptions.first { it.offset == 1 }) }
    val phone = remember { phoneTimeZoneOption() }

    ChoiceDialog(
        stringResource(R.string.dev_timezone), onDismiss,
        confirm = { TextButton(onClick = { onSave(selected.hours, selected.east) }) { Text(stringResource(R.string.save)) } },
    ) {
        phone?.let {
            OutlinedButton(onClick = { selected = it }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tz_use_phone, it.label), maxLines = 2)
            }
        }
        Text(stringResource(R.string.tz_dst_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TimeZoneOptions.forEach { option ->
            Row(
                Modifier.fillMaxWidth().clickable { selected = option }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == option, onClick = { selected = option })
                Text(option.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BacklightDialog(current: Backlight?, onPick: (Backlight) -> Unit, onDismiss: () -> Unit) {
    ChoiceDialog(stringResource(R.string.dev_backlight), onDismiss) {
        val options = listOf(Backlight.Off, Backlight.AlwaysOn) + BacklightSeconds.map { Backlight.Timeout(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { b ->
                FilterChip(selected = current == b, onClick = { onPick(b) }, label = { Text(backlightLabel(b)) })
            }
        }
    }
}

@Composable
private fun TimedBacklightDialog(values: DeviceValues, onSave: (Int?, Int?) -> Unit, onDismiss: () -> Unit) {
    var on by remember { mutableStateOf(values.timedOn ?: false) }
    var start by remember { mutableIntStateOf(values.timedStart ?: (20 * 60)) }
    var end by remember { mutableIntStateOf(values.timedEnd ?: (6 * 60)) }
    var picking by remember { mutableStateOf<String?>(null) } // "start" oder "end"

    ChoiceDialog(
        stringResource(R.string.dev_timed_backlight), onDismiss,
        confirm = { TextButton(onClick = { if (on) onSave(start, end) else onSave(null, null) }) { Text(stringResource(R.string.save)) } },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dev_timed_on), Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { on = it })
        }
        if (on) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dev_from) + " " + hhmm(start)) }
                OutlinedButton(onClick = { picking = "end" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dev_to) + " " + hhmm(end)) }
            }
        }
    }
    picking?.let { which ->
        TimePickDialog(
            title = stringResource(if (which == "start") R.string.dev_from else R.string.dev_to),
            minutes = if (which == "start") start else end,
            onConfirm = { m -> if (which == "start") start = m else end = m; picking = null },
            onDismiss = { picking = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(title: String, minutes: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.settings_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
