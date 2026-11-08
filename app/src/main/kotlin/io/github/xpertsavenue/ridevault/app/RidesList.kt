package io.github.xpertsavenue.ridevault.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xpertsavenue.ridevault.model.GroupBy
import io.github.xpertsavenue.ridevault.model.GroupKey
import io.github.xpertsavenue.ridevault.model.ListOptions
import io.github.xpertsavenue.ridevault.model.RideGroup
import io.github.xpertsavenue.ridevault.model.SortKey
import io.github.xpertsavenue.ridevault.model.StoredRide
import io.github.xpertsavenue.ridevault.model.organize
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fahrtenliste mit Sortierung, Gruppierung und zwei Darstellungen (kompakt/ausführlich). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RidesScreen(vm: RidesViewModel, settings: AppSettings, onSettings: (AppSettings) -> Unit) {
    val rides by vm.visibleRides.collectAsState()
    val selection by vm.selection.collectAsState()
    val showArchive by vm.showArchive.collectAsState()
    val archivedCount by vm.archivedCount.collectAsState()
    val haptics = rememberHaptics(settings.haptics)
    if (rides.isEmpty() && !showArchive && archivedCount == 0) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.rides_empty), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val options = ListOptions(settings.sortKey, settings.sortDescending, settings.groupBy)
    val groups = remember(rides, options) { organize(rides, options) }

    Column(Modifier.fillMaxSize()) {
        ListControls(settings, onSettings, showArchive, archivedCount, vm::setShowArchive)
        if (showArchive) {
            Text(
                stringResource(R.string.archive_explain),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (rides.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (showArchive) R.string.archive_empty else R.string.rides_all_archived),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (settings.viewMode == ViewMode.COMPACT) 8.dp else 12.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            groups.forEachIndexed { index, group ->
                if (settings.groupBy != GroupBy.NONE) {
                    stickyHeader(key = "group-$index") {
                        val ids = group.rides.map { it.id }
                        val chosen = ids.count { it in selection }
                        GroupHeader(
                            group,
                            state = when {
                                chosen == 0 -> androidx.compose.ui.state.ToggleableState.Off
                                chosen == ids.size -> androidx.compose.ui.state.ToggleableState.On
                                else -> androidx.compose.ui.state.ToggleableState.Indeterminate
                            },
                            onToggle = { haptics.toggle(chosen != ids.size); vm.toggleGroup(ids) },
                            selecting = selection.isNotEmpty(), // Kästchen erst, wenn schon eine Fahrt markiert ist
                        )
                    }
                }
                // Bei Gruppierung nach Tag kann dieselbe Fahrt in mehreren Gruppen stehen: Schlüssel mit Gruppenindex
                items(group.rides, key = { "$index-${it.id}" }) { stored ->
                    val selected = stored.id in selection
                    // Ist schon etwas ausgewählt, wählt ein Tipp weiter aus/ab; sonst öffnet er die Fahrt. Langes Drücken beginnt die Auswahl.
                    val tap = {
                        if (selection.isEmpty()) vm.open(stored.id) else { haptics.toggle(!selected); vm.toggleSelection(stored.id) }
                    }
                    val hold = { haptics.longPress(); vm.toggleSelection(stored.id) }
                    // animateItem: Karten gleiten beim Sortieren, Gruppieren, Filtern und Archivieren an ihren neuen Platz
                    Box(Modifier.animateItem()) {
                    SwipeActions(
                        enabled = selection.isEmpty(), archived = showArchive, haptics = haptics,
                        onArchive = { vm.setArchived(listOf(stored.id), !showArchive) },
                        onDelete = { vm.askDelete(listOf(stored.id)) },
                    ) {
                        if (settings.viewMode == ViewMode.COMPACT) CompactCard(stored, selected, tap, hold) else FullCard(stored, selected, tap, hold)
                    }
                    }
                }
            }
        }
    }
}

// ---- Steuerleiste ------------------------------------------------------------------------------------------------

/**
 * Kompakte Steuerleiste in einer Zeile: Sortierung (Tippen auf den aktiven Eintrag im Menü kehrt die Richtung um),
 * Gruppierung und ein Umschalter für die Ansicht. Bricht nur bei sehr schmalen Bildschirmen um.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ListControls(
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    showArchive: Boolean,
    archivedCount: Int,
    onShowArchive: (Boolean) -> Unit,
) {
    val sortNames = listOf(
        SortKey.DATE to stringResource(R.string.sort_date),
        SortKey.NAME to stringResource(R.string.sort_name),
        SortKey.DISTANCE to stringResource(R.string.sort_distance),
        SortKey.DURATION to stringResource(R.string.sort_duration),
        SortKey.AVG_SPEED to stringResource(R.string.sort_avg_speed),
        SortKey.MAX_SPEED to stringResource(R.string.sort_max_speed),
    )
    val groupNames = listOf(
        GroupBy.NONE to stringResource(R.string.group_none),
        GroupBy.MONTH to stringResource(R.string.group_month),
        GroupBy.YEAR to stringResource(R.string.group_year),
        GroupBy.DEVICE to stringResource(R.string.group_device),
        GroupBy.TAG to stringResource(R.string.group_tag),
    )
    val directionIcon = if (settings.sortDescending) R.drawable.ic_arrow_downward else R.drawable.ic_arrow_upward
    val directionText = stringResource(if (settings.sortDescending) R.string.sort_descending else R.string.sort_ascending)
    val compact = settings.viewMode == ViewMode.COMPACT

    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        var sortOpen by remember { mutableStateOf(false) }
        Box {
            AssistChip(
                onClick = { sortOpen = true },
                label = { Text(sortNames.first { it.first == settings.sortKey }.second, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_sort), stringResource(R.string.sort_label), Modifier.size(18.dp)) },
                trailingIcon = { Icon(painterResource(directionIcon), directionText, Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                sortNames.forEach { (key, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        trailingIcon = if (key == settings.sortKey) {
                            { Icon(painterResource(directionIcon), directionText, Modifier.size(18.dp)) }
                        } else null,
                        onClick = {
                            sortOpen = false
                            onSettings(
                                if (key == settings.sortKey) settings.copy(sortDescending = !settings.sortDescending)
                                else settings.copy(sortKey = key),
                            )
                        },
                    )
                }
            }
        }

        var groupOpen by remember { mutableStateOf(false) }
        Box {
            FilterChip(
                selected = settings.groupBy != GroupBy.NONE,
                onClick = { groupOpen = true },
                label = {
                    Text(
                        if (settings.groupBy == GroupBy.NONE) stringResource(R.string.group_label)
                        else groupNames.first { it.first == settings.groupBy }.second,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_category), stringResource(R.string.group_label), Modifier.size(18.dp)) },
                trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), null, Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = groupOpen, onDismissRequest = { groupOpen = false }) {
                groupNames.forEach { (value, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { groupOpen = false; onSettings(settings.copy(groupBy = value)) })
                }
            }
        }

        if (archivedCount > 0 || showArchive) {
            FilterChip(
                selected = showArchive,
                onClick = { onShowArchive(!showArchive) },
                label = { Text(stringResource(R.string.archive_label, archivedCount), maxLines = 1) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_archive), null, Modifier.size(18.dp)) },
            )
        }

        // Ein Knopf statt zwei: das Symbol zeigt die Ansicht, zu der umgeschaltet wird
        IconButton(
            onClick = { onSettings(settings.copy(viewMode = if (compact) ViewMode.FULL else ViewMode.COMPACT)) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                painterResource(if (compact) R.drawable.ic_view_agenda else R.drawable.ic_list),
                stringResource(if (compact) R.string.view_full else R.string.view_compact),
            )
        }
    }
}

// ---- Gruppenköpfe ----------------------------------------------------------------------------------------------------

@Composable
private fun GroupHeader(group: RideGroup, state: androidx.compose.ui.state.ToggleableState, onToggle: () -> Unit, selecting: Boolean) {
    val groupSelectDescription = stringResource(R.string.group_select)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        // Solange nichts markiert ist, ist der Kopf nur eine Überschrift; danach wählt ein Tipp die ganze Gruppe (oder hebt sie auf)
        Row(
            (if (selecting) Modifier.clickable(onClick = onToggle) else Modifier).padding(vertical = if (selecting) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = selecting,
                enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut(),
            ) {
                androidx.compose.material3.TriStateCheckbox(
                    state = state, onClick = onToggle,
                    modifier = Modifier.semantics { contentDescription = groupSelectDescription },
                )
            }
            (group.key as? GroupKey.TagGroup)?.tag?.let { tag ->
                Box(Modifier.padding(end = 8.dp).size(12.dp).clip(CircleShape).background(Color(tag.color)))
            }
            Text(groupLabel(group.key), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.group_summary, group.rides.size, fmt(group.totalKm)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun groupLabel(key: GroupKey): String = when (key) {
    GroupKey.None -> ""
    is GroupKey.Month -> DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()).format(key.month)
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    is GroupKey.Year -> key.year.toString()
    is GroupKey.Device -> key.name ?: stringResource(R.string.ride_unknown_device)
    is GroupKey.TagGroup -> key.tag?.name ?: stringResource(R.string.group_untagged)
}

// ---- Karten ----------------------------------------------------------------------------------------------------------

/** Schmaler Balken in der Akzentfarbe: die einzige größere Akzentfläche der Karten. */
@Composable
private fun AccentBar(width: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxHeight().width(width).background(MaterialTheme.colorScheme.primary))
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CompactCard(stored: StoredRide, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val s = stored.ride.summary
    // Hintergrund der Karte; ausgewählt ist er in der Akzent-Container-Farbe. Texte mit eigener Farbe werden darauf lesbar gehalten.
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val accentText = ensureContrast(MaterialTheme.colorScheme.primary, container)
    val mutedText = ensureContrast(MaterialTheme.colorScheme.onSurfaceVariant, container)
    Card(
        modifier = Modifier.fillMaxWidth().selectionBorder(selected, CardDefaults.shape)
            .clip(CardDefaults.shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            AccentBar(3.dp)
            Row(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // Hat die Fahrt einen Namen, ist er der Titel und das Datum rutscht in die zweite Zeile
                    Text(
                        stored.name ?: stored.ride.startTime.format(dateFormatter()),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            // Mit Namen steht das Datum hier (statt als Titel); die Uhrzeit entfällt, damit die Zeile nicht umbricht
                            if (stored.name != null) stored.ride.startTime.format(dateFormatter())
                            else stored.ride.startTime.format(timeFormatter()),
                            s.durationSeconds?.let(::formatDuration),
                            s.avgSpeedKmh?.let { "Ø ${fmt(it)} km/h" },
                        ).joinToString("  ·  "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = mutedText,
                    )
                }
                if (selected) CheckMark(accentText)
                s.distanceKm?.let {
                    Text("${fmt(it)} km", style = MaterialTheme.typography.titleMedium, color = accentText)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FullCard(stored: StoredRide, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val r = stored.ride
    val s = r.summary
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val muted = ensureContrast(MaterialTheme.colorScheme.onSurfaceVariant, container)
    val accentText = ensureContrast(MaterialTheme.colorScheme.primary, container)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().selectionBorder(selected, CardDefaults.elevatedShape)
            .clip(CardDefaults.elevatedShape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.elevatedCardColors(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            AccentBar(4.dp)
            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stored.name ?: r.startTime.format(dateFormatter()),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (stored.name != null) "${r.startTime.format(dateFormatter())} · ${r.startTime.format(timeFormatter())}"
                            else r.startTime.format(timeFormatter()),
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    }
                    if (selected) CheckMark(accentText)
                    s.distanceKm?.let {
                        Text("${fmt(it)} km", style = MaterialTheme.typography.headlineSmall, color = accentText)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Stat(stringResource(R.string.label_duration), s.durationSeconds?.let(::formatDuration) ?: "–", Modifier.weight(1f), muted)
                    // Kurze Beschriftungen ("Ø", "Max"), damit nichts umbricht; die Einheit steht im Wert
                    Stat("Ø", s.avgSpeedKmh?.let { "${fmt(it)} km/h" } ?: "–", Modifier.weight(1f), muted)
                    Stat("Max", s.maxSpeedKmh?.let { "${fmt(it)} km/h" } ?: "–", Modifier.weight(1f), muted)
                }
                TagChipRow(stored.tags) // kleine Chips: nur in der ausführlichen Ansicht
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ride_imported_from, stored.deviceName ?: stringResource(R.string.ride_unknown_device)),
                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = muted,
                    )
                    Text(stored.importedAt.formatLocal(dateFormatter()), style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, labelColor: androidx.compose.ui.graphics.Color) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor)
    }
}


/** Rahmen in der Akzentfarbe um ausgewählte Karten. */
@Composable
private fun Modifier.selectionBorder(selected: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (selected) this.border(2.dp, MaterialTheme.colorScheme.primary, shape) else this

@Composable
private fun CheckMark(tint: androidx.compose.ui.graphics.Color) {
    Icon(
        painterResource(R.drawable.ic_check_circle), null,
        Modifier.padding(end = 8.dp).size(22.dp), tint = tint,
    )
}

// ---- Wischen: Archivieren und Löschen ---------------------------------------------------------------------------------

/**
 * Karte, die sich nach rechts (Archivieren bzw. aus dem Archiv holen) und nach links (Löschen) wischen lässt. Die Karte springt
 * immer zurück; die Aktion verändert die Liste, und das Löschen fragt vorher nach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeActions(
    enabled: Boolean,
    archived: Boolean,
    haptics: Haptics,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Unempfindlicher: Die Karte muss mehr als die halbe Breite weit gezogen werden; ein kurzes Wischen oder Antippen reicht nicht.
    var widthPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val holder = remember { arrayOfNulls<androidx.compose.material3.SwipeToDismissBoxState>(1) }
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        positionalThreshold = { total -> total * 0.55f },
        confirmValueChange = { value ->
            val offset = runCatching { kotlin.math.abs(holder[0]!!.requireOffset()) }.getOrDefault(Float.MAX_VALUE)
            val far = widthPx <= 0f || offset >= widthPx * 0.5f
            if (far) {
                when (value) {
                    androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> { haptics.confirm(); onArchive() }
                    androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> { haptics.confirm(); onDelete() }
                    else -> Unit
                }
            }
            false
        },
    )
    holder[0] = state
    // Spürbarer Hinweis, sobald Loslassen die Aktion auslösen würde
    androidx.compose.runtime.LaunchedEffect(state.targetValue) {
        if (state.targetValue != androidx.compose.material3.SwipeToDismissBoxValue.Settled) haptics.threshold()
    }
    androidx.compose.material3.SwipeToDismissBox(
        modifier = Modifier.onSizeChanged { widthPx = it.width.toFloat() },
        state = state,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            val toRight = state.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
            val toLeft = state.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
            val scheme = MaterialTheme.colorScheme
            val container = when {
                toRight -> scheme.tertiaryContainer
                toLeft -> scheme.errorContainer
                else -> Color.Transparent
            }
            val on = if (toLeft) scheme.onErrorContainer else scheme.onTertiaryContainer
            Row(
                Modifier.fillMaxSize().clip(CardDefaults.shape).background(container).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toLeft) Arrangement.End else Arrangement.Start,
            ) {
                if (toRight) {
                    Icon(painterResource(if (archived) R.drawable.ic_unarchive else R.drawable.ic_archive), null, tint = on)
                    Text(
                        stringResource(if (archived) R.string.restore_from_archive else R.string.archive_action),
                        Modifier.padding(start = 12.dp), color = on, style = MaterialTheme.typography.titleSmall,
                    )
                } else if (toLeft) {
                    Text(stringResource(R.string.delete_confirm), Modifier.padding(end = 12.dp), color = on, style = MaterialTheme.typography.titleSmall)
                    Icon(painterResource(R.drawable.ic_delete), null, tint = on)
                }
            }
        },
        content = { content() },
    )
}
