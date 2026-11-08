package io.github.xpertsavenue.ridevault.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xpertsavenue.ridevault.device.DevicePage
import io.github.xpertsavenue.ridevault.device.PageCapabilities

/** Seitenlayouts des BSC200: je Anzahl der Datenfelder die Bitmaske der Felder je Zeile und die Zeilenhöhen (aus der Hersteller-App). */
object Bsc200Layouts {
    data class Preset(val layout: Int, val lineWidth: Int)

    /** Anordnung A (Standard). */
    val styleA = mapOf(
        1 to Preset(0x1, 0x4), 2 to Preset(0x9, 0x12), 3 to Preset(0x49, 0x51), 4 to Preset(0x249, 0x249),
        5 to Preset(0x449, 0x249), 6 to Preset(0x44A, 0x249), 7 to Preset(0x48A, 0x249), 8 to Preset(0x492, 0x249),
    )

    /** Anordnung B: ab vier Feldern anders verteilt. */
    val styleB = mapOf(
        1 to Preset(0x1, 0x4), 2 to Preset(0x9, 0x12), 3 to Preset(0x49, 0x51), 4 to Preset(0x89, 0x51),
        5 to Preset(0x8A, 0x51), 6 to Preset(0x489, 0x249), 7 to Preset(0x491, 0x249), 8 to Preset(0x492, 0x249),
    )

    fun preset(count: Int, variantB: Boolean): Preset? = (if (variantB) styleB else styleA)[count]

    /** true, wenn das Layout der Seite die Anordnung B ist (und nicht A). */
    fun isVariantB(page: DevicePage): Boolean {
        val count = page.data.size
        val a = styleA[count]
        val b = styleB[count]
        return b != null && a != b && page.layout == b.layout && page.lineWidth == b.lineWidth
    }
}

/** Felder je Zeile aus der Bitmaske (3 Bit je Zeile, Zeile 1 unten); leer, wenn die Maske nicht zu den Daten passt. */
private fun rowsOf(page: DevicePage): List<List<Int>> {
    val counts = (0 until 8).map { (page.layout shr (3 * it)) and 7 }.takeWhile { it > 0 }
    val rows = ArrayList<List<Int>>()
    var at = 0
    for (c in counts) {
        if (at + c > page.data.size) return listOf(page.data)
        rows += page.data.subList(at, at + c)
        at += c
    }
    return if (at == page.data.size && rows.isNotEmpty()) rows else if (page.data.isEmpty()) emptyList() else listOf(page.data)
}

/** Relative Zeilenhöhen aus der Bitmaske; 0 oder fehlende Werte = gleich hoch. */
private fun rowHeights(page: DevicePage, rowCount: Int): List<Float> {
    val w = (0 until rowCount).map { ((page.lineWidth shr (3 * it)) and 7).toFloat() }
    return if (w.all { it > 0f }) w else List(rowCount) { 1f }
}

@Composable
private fun groupName(group: Int): String = stringResource(
    when (group) {
        0x1 -> R.string.pg_g_speed; 0x2 -> R.string.pg_g_cadence; 0x3 -> R.string.pg_g_hr; 0x4 -> R.string.pg_g_power
        0x5 -> R.string.pg_g_distance; 0x6 -> R.string.pg_g_elevation; 0x7 -> R.string.pg_g_time; 0x8 -> R.string.pg_g_other
        0x9 -> R.string.pg_g_calories; 0xB -> R.string.pg_g_graphics; 0xA, 0xC -> R.string.pg_g_gears; else -> R.string.pg_g_ebike
    },
)

@Composable
private fun pageTypeName(type: Int): String = stringResource(
    when (type) {
        1 -> R.string.pg_t_data; 2 -> R.string.pg_t_elevation; 3 -> R.string.pg_t_map; 4 -> R.string.pg_t_zones
        5, 6 -> R.string.pg_t_training; 7 -> R.string.pg_t_compass; 8 -> R.string.pg_t_laps; 9 -> R.string.pg_t_roadbook
        10 -> R.string.pg_t_climb; else -> R.string.pg_t_partner
    },
)

/** Verkleinerte Darstellung der Seite: Zeilen und Felder wie auf dem Gerät, mit den Namen der Datenfelder. */
@Composable
private fun PagePreview(page: DevicePage, modifier: Modifier = Modifier) {
    val rows = remember(page.data, page.layout) { rowsOf(page) }
    if (rows.isEmpty()) return
    val heights = remember(page.lineWidth, rows.size) { rowHeights(page, rows.size) }
    val total = heights.sum()
    Surface(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.height(132.dp)) {
            rows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth().weight(heights[r] / total)) {
                    row.forEach { id ->
                        Box(Modifier.weight(1f).fillMaxSize().padding(1.dp), contentAlignment = Alignment.Center) {
                            Text(
                                PageDataCatalog.label(id), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Seiten des Geräts einrichten: Fahrmodus wählen, Seiten ein- und ausschalten, Anordnung und Datenfelder festlegen.
 * Änderungen gelten erst, wenn sie mit "Auf das Gerät übertragen" gesendet werden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PagesScreen(
    state: PageUiState,
    busy: Boolean,
    onMode: (Int) -> Unit,
    onEdit: (DevicePage) -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onRestore: () -> Unit,
    /** Erklärung anzeigen; wird mit dem Info-Symbol in der Titelleiste umgeschaltet. */
    helpOpen: Boolean,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(stringResource(R.string.pages_loading))
            }
            return@Column
        }
        androidx.compose.animation.AnimatedVisibility(helpOpen) {
            Card(
                Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pages_help_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.pages_help_1), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.pages_help_2), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.pages_help_3), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.pages_help_4), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (state.modes.size > 1) {
            Text(stringResource(R.string.pages_mode), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.modes.filter { it.valid }.forEach { m ->
                    FilterChip(
                        selected = m.index == state.mode, enabled = !busy,
                        onClick = { if (m.index != state.mode) onMode(m.index) },
                        label = { Text(m.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.pages_mode_n, m.index)) },
                    )
                }
            }
        }
        if (state.pages.isEmpty()) {
            Text(stringResource(R.string.pages_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        state.pages.forEach { page ->
            Card(Modifier.fillMaxWidth().clickable(enabled = !busy) { editing = page.index }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                page.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.pages_page, page.index),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(pageTypeName(page.type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (page.type == 3) {
                                // Vermutung aus dem Gerätverhalten: die Kartenseite zeigt der Computer nur bei aktiver Navigation
                                Text(
                                    stringResource(R.string.pages_map_hint), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        Switch(checked = page.enabled, enabled = !busy && !page.mainPage, onCheckedChange = { onEdit(page.copy(enabled = it)) })
                    }
                    PagePreview(page)
                }
            }
        }
        Text(stringResource(R.string.pages_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onApply, enabled = state.dirty && !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pages_apply)) }
        OutlinedButton(onClick = onDiscard, enabled = state.dirty && !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pages_discard)) }
        if (state.hasBackup) {
            OutlinedButton(onClick = { confirmRestore = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pages_restore))
            }
        }
    }

    editing?.let { index ->
        val page = state.pages.firstOrNull { it.index == index }
        if (page == null) editing = null else {
            PageEditDialog(page, state.capabilities, onEdit, onDismiss = { editing = null })
        }
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.pages_restore_title)) },
            text = { Text(stringResource(R.string.pages_restore_text)) },
            confirmButton = { TextButton(onClick = { confirmRestore = false; onRestore() }) { Text(stringResource(R.string.pages_restore)) } },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Datenfelder, die ein neues Feld bekommen kann (häufige zuerst), wenn die Seite mehr Felder erhält. */
private val FillOrder = listOf(0x101, 0x501, 0x703, 0x601, 0x602, 0x804, 0x605, 0x901, 0x102, 0x502, 0x701, 0x301, 0x201, 0x401)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageEditDialog(page: DevicePage, caps: PageCapabilities?, onEdit: (DevicePage) -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf<Int?>(null) } // Position des Datenfelds, das gewählt wird
    val unsupported = caps?.unsupportedData.orEmpty()
    val rows = remember(page.data, page.layout) { rowsOf(page) }
    val maxFields = ((caps?.maxRows ?: 4) * (caps?.maxPerRow ?: 2)).coerceIn(1, 8)
    // Anordnung und Anzahl der Felder lassen sich bei Datenseiten ändern; erlaubt sind die Layouts, die das Gerät meldet
    val canLayout = page.type == 1
    fun allowed(p: Bsc200Layouts.Preset?) = p != null && (caps == null || caps.layouts.isEmpty() || p.layout in caps.layouts)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(page.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.pages_page, page.index)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pages_done)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PagePreview(page)
                if (canLayout) {
                    Text(stringResource(R.string.pages_layout), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..maxFields).forEach { n ->
                            val variantB = Bsc200Layouts.isVariantB(page)
                            val preset = Bsc200Layouts.preset(n, variantB) ?: Bsc200Layouts.preset(n, false)
                            FilterChip(
                                selected = page.data.size == n,
                                enabled = allowed(preset),
                                onClick = { applyCount(page, n, variantB, unsupported, onEdit) },
                                label = { Text(n.toString()) },
                            )
                        }
                    }
                    val n = page.data.size
                    if (Bsc200Layouts.styleA[n] != Bsc200Layouts.styleB[n] && Bsc200Layouts.styleB[n] != null) {
                        Text(stringResource(R.string.pages_variant), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(false to "A", true to "B").forEach { (b, label) ->
                                FilterChip(
                                    selected = Bsc200Layouts.isVariantB(page) == b,
                                    enabled = allowed(Bsc200Layouts.preset(n, b)),
                                    onClick = { applyCount(page, n, b, unsupported, onEdit) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
                Text(stringResource(R.string.pages_fields), style = MaterialTheme.typography.titleSmall)
                var position = 0
                rows.forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { id ->
                            val pos = position++
                            OutlinedButton(onClick = { picking = pos }, modifier = Modifier.weight(1f)) {
                                Text(PageDataCatalog.label(id), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
    )

    picking?.let { pos ->
        DataFieldPicker(
            current = page.data.getOrNull(pos),
            unsupported = unsupported,
            onPick = { id ->
                onEdit(page.copy(data = page.data.mapIndexed { i, old -> if (i == pos) id else old }))
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** Setzt Anzahl der Felder und Anordnung; fehlende Felder werden mit häufigen Datenfeldern aufgefüllt, überzählige entfernt. */
private fun applyCount(page: DevicePage, count: Int, variantB: Boolean, unsupported: Set<Int>, onEdit: (DevicePage) -> Unit) {
    val preset = Bsc200Layouts.preset(count, variantB) ?: return
    val data = page.data.toMutableList()
    while (data.size > count) data.removeAt(data.lastIndex)
    for (candidate in FillOrder) {
        if (data.size >= count) break
        if (candidate !in data && candidate !in unsupported) data += candidate
    }
    for (id in PageDataCatalog.labels.keys) {
        if (data.size >= count) break
        if (id !in data && id !in unsupported) data += id
    }
    onEdit(page.copy(data = data, layout = preset.layout, lineWidth = preset.lineWidth))
}

@Composable
private fun DataFieldPicker(current: Int?, unsupported: Set<Int>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pages_pick)) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PageDataCatalog.groups.forEach { g ->
                    val ids = PageDataCatalog.labels.keys.filter { PageDataCatalog.group(it) == g && it !in unsupported }.sorted()
                    if (ids.isEmpty()) return@forEach
                    Text(groupName(g), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    ids.forEach { id ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(id) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = id == current, onClick = { onPick(id) })
                            Text(PageDataCatalog.label(id))
                        }
                    }
                }
            }
        },
    )
}
