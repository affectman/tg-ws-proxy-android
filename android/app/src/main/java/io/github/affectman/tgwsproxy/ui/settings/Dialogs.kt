package io.github.affectman.tgwsproxy.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.affectman.tgwsproxy.R
import io.github.affectman.tgwsproxy.ui.theme.MonoStyle

/** What a settings row asks the user to edit. */
sealed interface EditRequest {
    val title: String

    data class Text(
        override val title: String,
        val initial: String,
        val hint: String? = null,
        val keyboard: KeyboardType = KeyboardType.Text,
        val validate: (String) -> Boolean = { true },
        val onSave: (String) -> Unit,
    ) : EditRequest

    /** One entry per line, e.g. domains or `DC:IP` pairs. */
    data class Lines(
        override val title: String,
        val initial: List<String>,
        val hint: String? = null,
        val onSave: (List<String>) -> Unit,
    ) : EditRequest

    data class Confirm(
        override val title: String,
        val text: String,
        val confirm: String,
        val onConfirm: () -> Unit,
    ) : EditRequest
}

@Composable
fun EditDialog(request: EditRequest, onDismiss: () -> Unit) {
    when (request) {
        is EditRequest.Text -> TextDialog(request, onDismiss)
        is EditRequest.Lines -> LinesDialog(request, onDismiss)
        is EditRequest.Confirm -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(request.title) },
            text = { Text(request.text) },
            confirmButton = {
                TextButton(onClick = { request.onConfirm(); onDismiss() }) { Text(request.confirm) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun TextDialog(request: EditRequest.Text, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(request.initial) }
    val valid = request.validate(value.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = request.keyboard),
                    modifier = Modifier.fillMaxWidth(),
                )
                request.hint?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { request.onSave(value.trim()); onDismiss() }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun LinesDialog(request: EditRequest.Lines, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(request.initial.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = MonoStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    request.hint ?: stringResource(R.string.hint_one_per_line),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                request.onSave(value.lines().map { it.trim() }.filter { it.isNotEmpty() })
                onDismiss()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
