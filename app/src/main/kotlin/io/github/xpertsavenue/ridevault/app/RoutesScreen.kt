package io.github.xpertsavenue.ridevault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xpertsavenue.ridevault.device.DeviceRoute

/** Stand der Streckenliste des Geräts. */
data class RouteUiState(
    val routes: List<DeviceRoute> = emptyList(),
    val loading: Boolean = false,
    /** 0..100 während eine Strecke gesendet wird, sonst null. */
    val sending: Int? = null,
    val loaded: Boolean = false,
)

@Composable
private fun routeTypeName(type: Int?): String = when (type) {
    1 -> "CNX"; 2 -> "GPX"; 3 -> "FIT"; 4 -> "TCX"; 5 -> "XML"
    else -> stringResource(R.string.routes_type_unknown)
}

/**
 * Strecken (Navigation) auf dem Gerät: Liste lesen, eine Strecke vom Handy senden, Strecken löschen.
 * Das Senden ist ein Versuch: Welche Dateiformate der BSC200 annimmt, ist nicht dokumentiert.
 */
@Composable
fun RoutesScreen(
    state: RouteUiState,
    busy: Boolean,
    onPickFile: () -> Unit,
    onDelete: (DeviceRoute) -> Unit,
    onUse: (DeviceRoute) -> Unit,
    onRename: (DeviceRoute, String) -> Unit,
    onReload: () -> Unit,
    helpOpen: Boolean,
) {
    var confirmDelete by remember { mutableStateOf<DeviceRoute?>(null) }
    var renaming by remember { mutableStateOf<DeviceRoute?>(null) }
    var newName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.animation.AnimatedVisibility(helpOpen) {
            Card(
                Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pages_help_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.routes_help_1), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.routes_help_2), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.routes_help_3), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Button(onClick = onPickFile, enabled = !busy && state.sending == null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.routes_send))
        }
        state.sending?.let { p ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.routes_sending), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(stringResource(R.string.routes_loading))
            }
        } else if (state.loaded && state.routes.isEmpty()) {
            Text(stringResource(R.string.routes_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.routes.forEach { route ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(route.name?.takeIf { it.isNotBlank() } ?: "#${route.id}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(
                                route.distanceM?.let { "${fmt(it / 1000.0)} km" },
                                routeTypeName(route.fileType),
                                if (route.inUse == true) stringResource(R.string.routes_in_use) else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (route.inUse == true) {
                            Text(
                                stringResource(R.string.routes_in_use_hint), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (route.inUse != true) {
                        TextButton(onClick = { onUse(route) }, enabled = !busy) { Text(stringResource(R.string.routes_use)) }
                    }
                    IconButton(onClick = { renaming = route; newName = route.name.orEmpty() }, enabled = !busy) {
                        Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.routes_rename))
                    }
                    // Die gerade benutzte Strecke lässt sich auf dem Gerät nicht entfernen
                    IconButton(onClick = { confirmDelete = route }, enabled = !busy && route.inUse != true) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete_confirm))
                    }
                }
            }
        }
        OutlinedButton(onClick = onReload, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cfg_reload)) }
    }

    renaming?.let { route ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.routes_rename)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newName, onValueChange = { newName = it.take(24) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { renaming = null; onRename(route, newName.trim()) },
                    enabled = newName.isNotBlank() && newName.trim() != route.name,
                ) { Text(stringResource(R.string.routes_rename_ok)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    confirmDelete?.let { route ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.routes_delete_title, route.name ?: "#${route.id}")) },
            text = { Text(stringResource(R.string.routes_delete_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; onDelete(route) }) { Text(stringResource(R.string.delete_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
