package io.github.xpertsavenue.ridevault.app

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xpertsavenue.ridevault.model.StoredRide
import io.github.xpertsavenue.ridevault.model.Tag
import kotlinx.coroutines.launch

/** Kleiner, farbiger Chip für einen Tag (Text bleibt auf jeder Farbe lesbar). */
@Composable
fun TagChip(tag: Tag, modifier: Modifier = Modifier) {
    val bg = Color(tag.color)
    Surface(shape = RoundedCornerShape(50), color = bg, modifier = modifier) {
        Text(
            tag.name,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = onColor(bg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChipRow(tags: List<Tag>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { TagChip(it) }
    }
}

/** Farbauswahl: zuerst die zur Akzentfarbe passenden Farben, dann feste Farben. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatches(selected: Int?, onPick: (Int) -> Unit) {
    val palette = (LocalAccents.current.colors + FixedTagColors).distinctBy { it.toArgb() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        palette.forEach { c ->
            val isSelected = selected == c.toArgb()
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(c)
                    .border(if (isSelected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(0.dp, Color.Transparent), CircleShape)
                    .clickable { onPick(c.toArgb()) },
            )
        }
    }
}

@Composable
private fun NewTagRow(onCreate: suspend (String, Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf<Int?>(null) }
    val effective = color ?: LocalAccents.current.primary.toArgb()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text(stringResource(R.string.tag_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        ColorSwatches(effective) { color = it }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { if (name.isNotBlank()) TagChip(Tag(0, name.trim(), effective)) }
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { scope.launch { onCreate(name.trim(), effective); name = "" } },
            ) { Text(stringResource(R.string.tag_add)) }
        }
    }
}

/** Name und Tags einer Fahrt bearbeiten; neue Tags lassen sich direkt anlegen und sind dann ausgewählt. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditRideDialog(
    stored: StoredRide,
    allTags: List<Tag>,
    createTag: suspend (String, Int) -> Long,
    onSave: (name: String?, tagIds: Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(stored.id) { mutableStateOf(stored.name.orEmpty()) }
    var selected by remember(stored.id) { mutableStateOf(stored.tags.map { it.id }.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_title)) },
        confirmButton = { TextButton(onClick = { onSave(name.trim().ifEmpty { null }, selected) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.edit_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.edit_tags), style = MaterialTheme.typography.titleSmall)
                if (allTags.isEmpty()) {
                    Text(stringResource(R.string.tag_none_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        allTags.forEach { t ->
                            FilterChip(
                                selected = t.id in selected,
                                onClick = { selected = if (t.id in selected) selected - t.id else selected + t.id },
                                label = { Text(t.name) },
                                leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(t.color))) },
                            )
                        }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.tag_new), style = MaterialTheme.typography.titleSmall)
                NewTagRow { n, c -> selected = selected + createTag(n, c) }
            }
        },
    )
}

/** Alle Tags umbenennen, umfärben oder löschen und neue anlegen. */
@Composable
fun ManageTagsDialog(
    tags: List<Tag>,
    onUpdate: suspend (Tag) -> Boolean,
    onDelete: (Long) -> Unit,
    onCreate: suspend (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tag_manage)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tags.isEmpty()) {
                    Text(stringResource(R.string.tag_none_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                tags.forEach { tag -> key(tag.id) { TagEditRow(tag, onUpdate, onDelete) } }
                HorizontalDivider()
                Text(stringResource(R.string.tag_new), style = MaterialTheme.typography.titleSmall)
                NewTagRow(onCreate)
            }
        },
    )
}

@Composable
private fun TagEditRow(tag: Tag, onUpdate: suspend (Tag) -> Boolean, onDelete: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(tag.name) }
    var error by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(tag.color)).clickable { showColors = !showColors })
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    scope.launch { error = it.isBlank() || !onUpdate(tag.copy(name = it)) }
                },
                isError = error, singleLine = true, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { confirmDelete = true }) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.tag_delete))
            }
        }
        if (showColors) ColorSwatches(tag.color) { c -> scope.launch { onUpdate(tag.copy(name = name.ifBlank { tag.name }, color = c)) } }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tag_delete)) },
            text = { Text(tag.name) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(tag.id) }) { Text(stringResource(R.string.delete_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}


/**
 * Tags für mehrere Fahrten gleichzeitig. Jedes Tag zeigt, bei wie vielen der Fahrten es schon gesetzt ist. Tippen schaltet durch:
 * hinzufügen → entfernen → unverändert (bei teilweise gesetzten Tags), sonst einfach an/aus. Nur Geändertes wird angewendet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectionTagsDialog(
    rides: List<StoredRide>,
    allTags: List<Tag>,
    createTag: suspend (String, Int) -> Long,
    onApply: (add: Set<Long>, remove: Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val n = rides.size
    fun countOf(tagId: Long) = rides.count { r -> r.tags.any { it.id == tagId } }
    // Gewünschter Zustand je Tag: true = bei allen setzen, false = bei allen entfernen, fehlt = unverändert
    var wanted by remember { mutableStateOf(mapOf<Long, Boolean>()) }

    fun cycle(tagId: Long) {
        val count = countOf(tagId)
        val current = wanted[tagId]
        val next: Boolean? = when {
            count in 1 until n -> when (current) { null -> true; true -> false; else -> null }
            count == n -> if (current == null) false else null
            else -> if (current == null) true else null
        }
        wanted = if (next == null) wanted - tagId else wanted + (tagId to next)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sel_tags_title, n)) },
        confirmButton = {
            TextButton(onClick = {
                onApply(wanted.filterValues { it }.keys, wanted.filterValues { !it }.keys)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (allTags.isEmpty()) {
                    Text(stringResource(R.string.tag_none_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        allTags.forEach { t ->
                            val count = countOf(t.id)
                            val on = wanted[t.id] ?: (count == n)
                            val partial = wanted[t.id] == null && count in 1 until n
                            FilterChip(
                                selected = on,
                                onClick = { cycle(t.id) },
                                label = { Text(if (partial) "${t.name} · $count/$n" else t.name) },
                                leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(t.color))) },
                            )
                        }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.tag_new), style = MaterialTheme.typography.titleSmall)
                NewTagRow { name, color -> wanted = wanted + (createTag(name, color) to true) }
            }
        },
    )
}
