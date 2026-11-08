package io.github.xpertsavenue.ridevault.app

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private val swatches = listOf(
    0xFF2E7D32, 0xFF00796B, 0xFF0277BD, 0xFF3949AB, 0xFF6A1B9A,
    0xFFC2185B, 0xFFD32F2F, 0xFFE64A19, 0xFFF9A825, 0xFF455A64,
).map { it.toInt() }

/** Adresse des Quellcode-Repositories. */
private const val REPO_URL = "https://github.com/xpertsavenue/RideVault"

/** Versionsname und -nummer der installierten App. */
@Composable
private fun appVersionText(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName} ($code)"
        }.getOrDefault("?")
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Einstellungen als eigene Seite (statt Dialog): Darstellung, Tags, Daten, Sprache und Informationen zur App. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onManageTags: () -> Unit,
    onExportAll: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    val systemColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSection(stringResource(R.string.settings_appearance)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark).forEach { (m, label) ->
                    FilterChip(selected = settings.themeMode == m, onClick = { onChange(settings.copy(themeMode = m)) }, label = { Text(stringResource(label)) })
                }
            }
            Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.titleSmall)
            if (systemColorAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_system_color), Modifier.weight(1f))
                    Switch(checked = settings.useSystemColor, onCheckedChange = { onChange(settings.copy(useSystemColor = it)) })
                }
            }
            if (!systemColorAvailable || !settings.useSystemColor) {
                Swatches(settings.seedColor) { onChange(settings.copy(seedColor = it)) }
                HexField(settings.seedColor) { onChange(settings.copy(seedColor = it)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_haptics), Modifier.weight(1f))
                Switch(checked = settings.haptics, onCheckedChange = { onChange(settings.copy(haptics = it)) })
            }
        }

        SettingsSection(stringResource(R.string.settings_map_default)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    MapStyle.STANDARD to R.string.map_standard, MapStyle.CYCLING to R.string.map_cycling,
                    MapStyle.TERRAIN to R.string.map_terrain, MapStyle.SATELLITE to R.string.map_satellite,
                ).forEach { (style, label) ->
                    FilterChip(
                        selected = settings.mapStyle == style,
                        onClick = { onChange(settings.copy(mapStyle = style)) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        }

        SettingsSection(stringResource(R.string.settings_language)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.language == "system",
                    onClick = { onChange(settings.copy(language = "system")) },
                    label = { Text(stringResource(R.string.language_system)) },
                )
                LocaleHelper.supported.forEach { (tag, name) ->
                    FilterChip(
                        selected = settings.language == tag,
                        onClick = { onChange(settings.copy(language = tag)) },
                        label = { Text(name) },
                    )
                }
            }
        }

        SettingsSection(stringResource(R.string.edit_tags)) {
            OutlinedButton(onClick = onManageTags, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tag_manage)) }
        }

        SettingsSection(stringResource(R.string.data_title)) {
            OutlinedButton(onClick = onExportAll, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_all)) }
            OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_create)) }
            OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_restore)) }
        }

        SettingsSection(stringResource(R.string.settings_about)) {
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_version), Modifier.weight(1f))
                Text(appVersionText(), color = MaterialTheme.colorScheme.primary)
            }
            Column {
                Text(stringResource(R.string.settings_repo))
                // Platzhalter: noch kein Repository veröffentlicht
                Text(REPO_URL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.settings_about_text),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Swatches(selected: Int, onPick: (Int) -> Unit) {
    // Zwei Zeilen à fünf Farben
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        swatches.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { c ->
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                            .border(
                                if (c == selected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(0.dp, Color.Transparent),
                                CircleShape,
                            )
                            .clickable { onPick(c) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HexField(current: Int, onValid: (Int) -> Unit) {
    var text by remember { mutableStateOf("#%06X".format(current and 0xFFFFFF)) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            val hex = v.removePrefix("#")
            if (hex.length == 6) hex.toLongOrNull(16)?.let { onValid((0xFF000000 or it).toInt()) }
        },
        label = { Text(stringResource(R.string.settings_custom_color)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
