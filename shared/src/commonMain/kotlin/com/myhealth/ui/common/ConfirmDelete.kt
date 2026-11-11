package com.myhealth.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.myhealth.resources.*

/**
 * Puts a confirmation in front of a delete (POLISH-32, POLISH-50): the returned function opens an
 * "are you sure" dialog for the id it is given, and [onConfirm] only runs after "Delete".
 *
 * The pending id survives a rotation, so the dialog does not vanish half-way through.
 */
@Composable
fun rememberDeleteConfirmation(message: String, onConfirm: (Long) -> Unit): (Long) -> Unit {
    var pendingId by rememberSaveable { mutableStateOf<Long?>(null) }
    pendingId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingId = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    pendingId = null
                    onConfirm(id)
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingId = null }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
    return { id -> pendingId = id }
}
