package io.github.xpertsavenue.ridevault.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xpertsavenue.ridevault.device.ConfigItem
import io.github.xpertsavenue.ridevault.device.ConfigKind
import io.github.xpertsavenue.ridevault.device.ConfigState

/** Beschriftung einer Einstellung (Kennung -> Text). */
private fun labelRes(id: String): Int = when (id) {
    "unit.distance" -> R.string.cfg_unit_distance
    "unit.elevation" -> R.string.cfg_unit_elevation
    "unit.weight" -> R.string.cfg_unit_weight
    "unit.temperature" -> R.string.cfg_unit_temperature
    "backlight.auto" -> R.string.cfg_bl_auto
    "backlight.night" -> R.string.cfg_bl_night
    "backlight.time" -> R.string.cfg_bl_time
    "backlight.day" -> R.string.cfg_bl_day
    "backlight.night_level" -> R.string.cfg_bl_night_level
    "sound.key" -> R.string.cfg_snd_key
    "sound.alert" -> R.string.cfg_snd_alert
    "sound.record_start" -> R.string.cfg_snd_start
    "sound.record_pause" -> R.string.cfg_snd_pause
    "sound.record_save" -> R.string.cfg_snd_save
    "sound.lap" -> R.string.cfg_snd_lap
    "auto.pause" -> R.string.cfg_auto_pause
    "auto.record" -> R.string.cfg_auto_record
    "auto.power_off" -> R.string.cfg_auto_off
    "auto.sleep" -> R.string.cfg_auto_sleep
    "auto.home" -> R.string.cfg_auto_home
    "auto.smart" -> R.string.cfg_auto_smart
    "auto.power_save" -> R.string.cfg_auto_psave
    "auto.page" -> R.string.cfg_auto_page
    "language" -> R.string.cfg_language
    "user.sex" -> R.string.cfg_user_sex
    "user.age" -> R.string.cfg_user_age
    "user.height" -> R.string.cfg_user_height
    "user.weight" -> R.string.cfg_user_weight
    else -> R.string.cfg_none
}

private fun groupRes(group: String): Int = when (group) {
    "units" -> R.string.cfg_group_units
    "display" -> R.string.cfg_group_display
    "sound" -> R.string.cfg_group_sound
    "auto" -> R.string.cfg_group_auto
    "language" -> R.string.cfg_group_language
    else -> R.string.cfg_group_user
}

/** Sprachen des iGPSPORT-Geräts (Nummer = LANGUAGE_TYPE), jeweils in der eigenen Sprache. */
private val GeraeteSprachen = mapOf(
    1 to "English", 2 to "Español", 3 to "Français", 4 to "Deutsch", 5 to "日本語", 6 to "Italiano", 7 to "Português", 8 to "한국어",
    9 to "简体中文", 10 to "繁體中文", 11 to "Polski", 12 to "Hrvatski", 13 to "Čeština", 14 to "Dansk", 15 to "Nederlands", 16 to "Suomi",
    17 to "Ελληνικά", 18 to "Magyar", 19 to "Norsk", 20 to "Slovenčina", 21 to "Slovenščina", 22 to "Svenska", 23 to "Русский",
    24 to "Türkçe", 25 to "Latviešu", 26 to "Українська", 27 to "العربية", 28 to "فارسی", 29 to "Български", 30 to "Română",
    31 to "ไทย", 32 to "עברית", 33 to "Português (Brasil)", 34 to "Bahasa Indonesia", 35 to "Bahasa Melayu", 36 to "Tiếng Việt",
    37 to "မြန်မာ", 38 to "Монгол",
)

@Composable
private fun optionLabel(id: String, value: Int): String = when (id) {
    "unit.distance", "unit.elevation", "unit.weight", "unit.temperature" ->
        stringResource(if (value == 2) R.string.cfg_imperial else R.string.cfg_metric)
    "backlight.time" -> when {
        value < 0 -> stringResource(R.string.bl_always)
        value < 60 -> "$value s"
        else -> "${value / 60} min"
    }
    "auto.power_save" -> stringResource(
        when (value) { 0 -> R.string.cfg_off; 1 -> R.string.cfg_on; else -> R.string.cfg_smart },
    )
    "auto.page" -> stringResource(
        when (value) { 0 -> R.string.cfg_off; 1 -> R.string.cfg_on; 2 -> R.string.cfg_fast; 3 -> R.string.cfg_slow; else -> R.string.cfg_to_main },
    )
    "language" -> GeraeteSprachen[value] ?: "#$value"
    "user.sex" -> stringResource(if (value == 1) R.string.cfg_male else R.string.cfg_female)
    else -> value.toString()
}

private fun unitOf(id: String): String = when (id) {
    "backlight.day", "backlight.night_level" -> " %"
    "user.height" -> " cm"
    "user.weight" -> " kg"
    else -> ""
}

/**
 * Einstellungen eines Geräts, das sie lesen und ändern lässt (iGPSPORT). Gezeigt wird nur, was das Gerät gemeldet hat.
 * Eine Änderung wird sofort an das Gerät gesendet.
 */
@Composable
fun ConfigSettingsCard(
    items: List<ConfigItem>,
    state: ConfigState?,
    loading: Boolean,
    busy: Boolean,
    onChange: (id: String, value: Int) -> Unit,
    onReload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(stringResource(R.string.cfg_loading))
            }
            return@Column
        }
        val shown = items.filter { state != null && it.id in state.values }
        if (shown.isEmpty()) {
            Text(stringResource(R.string.cfg_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        shown.groupBy { it.group }.forEach { (group, list) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(groupRes(group)), style = MaterialTheme.typography.titleMedium)
                    list.forEach { item ->
                        val value = state!!.values.getValue(item.id)
                        ConfigRow(item, value, state.choices[item.id], enabled = !busy, onChange = { onChange(item.id, it) })
                    }
                }
            }
        }
        Text(stringResource(R.string.cfg_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onReload, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cfg_reload)) }
    }
}

@Composable
private fun ConfigRow(item: ConfigItem, value: Int, choices: List<Int>?, enabled: Boolean, onChange: (Int) -> Unit) {
    val label = stringResource(labelRes(item.id))
    when (val kind = item.kind) {
        ConfigKind.Toggle -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = value == 1, onCheckedChange = { onChange(if (it) 1 else 0) }, enabled = enabled)
        }
        is ConfigKind.Choice -> {
            var open by remember { mutableStateOf(false) }
            val options = choices ?: kind.options
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }, verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                Text(optionLabel(item.id, value), color = MaterialTheme.colorScheme.primary)
            }
            if (open) {
                AlertDialog(
                    onDismissRequest = { open = false },
                    title = { Text(label) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            options.forEach { o ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { open = false; if (o != value) onChange(o) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = o == value, onClick = { open = false; if (o != value) onChange(o) })
                                    Text(optionLabel(item.id, o))
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
        is ConfigKind.Number -> {
            var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f))
                    Text("${local.toInt()}${unitOf(item.id)}", color = MaterialTheme.colorScheme.primary)
                }
                val steps = ((kind.max - kind.min) / kind.step - 1).coerceAtLeast(0)
                Slider(
                    value = local,
                    onValueChange = { local = it },
                    onValueChangeFinished = { if (local.toInt() != value) onChange(local.toInt()) },
                    valueRange = kind.min.toFloat()..kind.max.toFloat(),
                    steps = steps,
                    enabled = enabled,
                )
            }
        }
    }
}
