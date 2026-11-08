package io.github.xpertsavenue.ridevault.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Bestätigung vor dem Löschen. Der Text erklärt den Unterschied: Gelöschte Fahrten können beim nächsten Import wieder auftauchen,
 * wenn der Fahrradcomputer sie noch gespeichert hat; archivierte werden nicht mehr importiert.
 */
@Composable
fun DeleteDialog(
    count: Int,
    canArchive: Boolean,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) stringResource(R.string.delete_title) else stringResource(R.string.sel_delete_title, count)) },
        text = { Text(stringResource(if (canArchive) R.string.delete_explain else R.string.delete_explain_archived)) },
        confirmButton = {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            if (canArchive) TextButton(onClick = onArchive) { Text(stringResource(R.string.archive_action)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
