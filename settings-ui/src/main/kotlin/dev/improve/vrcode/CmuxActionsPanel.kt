package dev.improve.vrcode

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Quest-owned cmux menus. Text entry, selection, and clipboard UI never visit a Mac window. */
@Composable
fun CmuxActionsPanel(
  catalog: CmuxCatalog,
  target: Map<String, String>,
  resources: List<CmuxResource>,
  connected: Boolean,
  busy: Boolean,
  status: String,
  text: String?,
  streams: List<String>,
  onRun: (String, JSONObject) -> Unit,
  onCopy: (String) -> Unit,
  onPaste: (Boolean) -> Unit,
  onReadText: () -> Unit,
  onRefresh: () -> Unit,
  onStopStream: (String) -> Unit,
  onAppClick: () -> Unit,
  onCancel: () -> Unit,
  onClose: () -> Unit,
) {
  var search by remember { mutableStateOf("") }
  var all by remember { mutableStateOf(false) }
  var selected by remember(target) { mutableStateOf<CmuxOperation?>(null) }
  var values by remember(target) { mutableStateOf<Map<String, String>>(emptyMap()) }
  var formError by remember { mutableStateOf("") }
  var nullFields by remember(selected, target) { mutableStateOf<Set<String>>(emptySet()) }
  var showOptional by remember(selected) { mutableStateOf(false) }
  val common = remember { setOf("tab.create_terminal", "pane.split", "pane.run", "tab.focus", "pane.focus_direction", "pane.zoom",
    "screen.create", "workspace.create", "tab.rename", "pane.rename", "screen.rename", "workspace.rename", "tab.move", "pane.swap",
    "pane.split_ratio.set", "pane.viewport_width.set", "screen.focus", "screen.layout.undo", "workspace.focus", "workspace.move",
    "terminal.viewport.scroll", "terminal.input.keys", "terminal.project", "terminal.process.get", "terminal.history.clear",
    "tab.close", "pane.close", "screen.close", "workspace.close") }

  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text("Terminal actions", style = MaterialTheme.typography.headlineSmall)
          Row {
            if (busy) TextButton(onClick = onCancel) { Text("Cancel action") }
            TextButton(onClick = onRefresh, enabled = connected && !busy) { Text("Refresh") }
            TextButton(onClick = onClose) { Text("Close") }
          }
        }
        Text(if (connected) "Connected to cmux in the background" else "Connect to VRcode Mac to use these actions",
          style = MaterialTheme.typography.bodyMedium)
        val targetLabel = resources.firstOrNull { it.id == (target["terminal"] ?: target["pane"] ?: target["screen"]) }?.label
        Text(targetLabel ?: "Choose a target in an action below", style = MaterialTheme.typography.labelLarge)
        if (status.isNotBlank()) Text(status, Modifier.testTag("cmux-status"), color = MaterialTheme.colorScheme.secondary)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))

        val operation = selected
        if (operation != null) {
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { selected = null }) { Text("Back") }
            Button(onClick = {
              runCatching { catalog.parameters(operation, values, nullFields) }.onSuccess {
                formError = ""; onRun(operation.name, it)
              }.onFailure { formError = it.message ?: "Check the fields" }
            }, enabled = connected && !busy, modifier = Modifier.testTag("cmux-run")) { Text("Run action") }
          }
          Text(operation.label, style = MaterialTheme.typography.titleLarge)
          if (operation.name.endsWith(".close")) Text("Closes the selected resource. Closing a terminal ends its process.")
          if (formError.isNotBlank()) Text(formError, color = MaterialTheme.colorScheme.error)
          LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(operation.fields.filter {
              if (operation.kind == "stream_open" && it.name == "stream_id") showOptional else it.required || it.name in values || showOptional
            }, key = { it.name }) { field ->
              Column {
                Row {
                  Text(field.label + if (field.required) " *" else " (optional)", Modifier.weight(1f))
                  if (!field.required) Checkbox(field.name in values, onCheckedChange = { include ->
                    values = if (include) values + (field.name to "") else values - field.name
                  })
                }
                if (field.required || field.name in values) {
                  if (field.nullable) Row {
                    Checkbox(field.name in nullFields, onCheckedChange = { clear -> nullFields = if (clear) nullFields + field.name else nullFields - field.name })
                    Text("Clear value")
                  }
                  if (field.name !in nullFields) CmuxFieldInput(field, values[field.name] ?: "", resources, onValue = { values = values + (field.name to it) })
                }
              }
            }
            item {
              TextButton(onClick = { showOptional = !showOptional }) { Text(if (showOptional) "Hide optional fields" else "Show optional fields") }
              if (showOptional) operation.constraints.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
          }
        } else {
          if (target["row"] != null) TextButton(onClick = onAppClick, enabled = connected && !busy) { Text("Send right-click to terminal app") }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onCopy("screen") }, enabled = connected && !busy && target["terminal"] != null) { Text("Copy screen") }
            TextButton(onClick = { onCopy("scrollback") }, enabled = connected && !busy && target["terminal"] != null) { Text("Copy history") }
            TextButton(onClick = { onPaste(false) }, enabled = connected && !busy && target["terminal"] != null) { Text("Paste") }
            TextButton(onClick = { onPaste(true) }, enabled = connected && !busy && target["terminal"] != null) { Text("Paste from Mac") }
            TextButton(onClick = onReadText, enabled = connected && !busy && target["terminal"] != null) { Text("Select / find text") }
          }
          if (text != null) {
            CmuxTextSelection(text, Modifier.fillMaxWidth().height(260.dp))
          }
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(search, { search = it }, label = { Text("Find an action") }, singleLine = true,
              modifier = Modifier.weight(1f).testTag("cmux-action-search"))
            FilterChip(all, onClick = { all = !all }, label = { Text("All ${catalog.operations.size} actions") })
          }
          LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(catalog.operations.filter { op -> (all || op.name in common) && search.split(Regex("[\\s._·]+")).filter { it.isNotEmpty() }
              .all { term -> op.label.contains(term, true) || op.name.contains(term, true) } }
              .sortedBy { if (all) 0 else common.indexOf(it.name) }, key = { it.name }) { op ->
              TextButton(onClick = { selected = op; values = catalog.defaults(op, target); formError = "" },
                modifier = Modifier.fillMaxWidth().testTag("cmux-action-${op.name}")) {
                Text(op.label, Modifier.fillMaxWidth())
              }
            }
            items(streams, key = { it }) { stream ->
              TextButton(onClick = { onStopStream(stream) }, enabled = connected) { Text("Stop live stream ${stream.takeLast(8)}") }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun CmuxFieldInput(field: CmuxField, value: String, resources: List<CmuxResource>, onValue: (String) -> Unit) {
  val kind = field.inputType.optString("kind")
  val choices = when {
    field.selector || kind == "resource_id" -> resources.filter { it.kind == field.inputType.optString("resource") }.map { it.id to it.label }
    kind == "enum" -> field.inputType.getJSONArray("values").strings().map { it to it }
    kind == "primitive" && field.inputType.optString("name") == "boolean" -> listOf("true" to "Yes", "false" to "No")
    else -> emptyList()
  }
  if (choices.isNotEmpty()) {
    var expanded by remember { mutableStateOf(false) }
    Box {
      OutlinedButton(onClick = { expanded = true }) { Text(choices.firstOrNull { it.first == value }?.second ?: "Choose ${field.label}") }
      DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
        choices.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onValue(key); expanded = false }) }
      }
    }
  }
  // Explicit IDs and structured values remain available for resources absent from the snapshot.
  if (choices.isEmpty()) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth().testTag("cmux-field-${field.name}"),
      singleLine = kind in listOf("primitive", "resource_id"),
      label = { Text(if (kind in listOf("object", "array", "union", "map", "ref")) "${field.label} (JSON)" else field.label) })
  }
}

@Composable
private fun CmuxTextSelection(text: String, modifier: Modifier = Modifier) {
  var field by remember(text) { mutableStateOf(TextFieldValue(text)) }
  var query by remember { mutableStateOf("") }
  Column(modifier) {
    Row {
      OutlinedTextField(query, { query = it }, label = { Text("Find in terminal text") }, singleLine = true, modifier = Modifier.weight(1f))
      TextButton(onClick = {
        if (query.isNotEmpty()) {
          val index = text.indexOf(query, field.selection.end, ignoreCase = true).takeIf { it >= 0 }
            ?: text.indexOf(query, ignoreCase = true)
          if (index >= 0) field = field.copy(selection = TextRange(index, index + query.length))
        }
      }) { Text("Find next") }
    }
    OutlinedTextField(field, { field = field.copy(selection = it.selection) }, readOnly = true,
      label = { Text("Select text to copy") }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("cmux-terminal-text"))
  }
}
