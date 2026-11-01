package dev.improve.vrcode.connection

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ConnectionPanel(controller: ConnectionController, bridgeDetail: String? = null) {
  val profile by controller.profile.collectAsState()
  val status by controller.status.collectAsState()
  val macs by controller.macs.collectAsState()
  val setup by controller.setup.collectAsState()
  var menu by remember { mutableStateOf(false) }
  var importText by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  var paired by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().testTag("connection-selector")) {
    Text(status.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("connection-status"))
    if (status.detail.isNotBlank()) Text(status.detail, style = MaterialTheme.typography.bodyMedium)
    bridgeDetail?.let { Text(it, modifier = Modifier.testTag("connection-bridge-status")) }
    Box {
      OutlinedButton({ menu = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("connection-mode")) { Text(profile.mode.title) }
      DropdownMenu(menu, { menu = false }) {
        ConnectionMode.entries.forEach { mode ->
          DropdownMenuItem(text = { Text(mode.title) }, onClick = { controller.update(profile.copy(mode = mode)); menu = false }, modifier = Modifier.testTag("connection-mode-${mode.name}"))
        }
      }
    }
    Text(profile.mode.detail)
    if (!profile.paired) Text("On your Mac, run vrcode-mac --link-info and paste its connection key below. Pairing protects both terminal and speech traffic.")
    else Text("Paired Mac: ${profile.name}")
    OutlinedTextField(importText, { importText = it }, label = { Text(if (profile.paired) "Replace Mac connection key" else "Mac connection key") },
      singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("connection-import"))
    TextButton({ error = controller.importMac(importText); if (error == null) importText = "" }, enabled = importText.isNotBlank(), modifier = Modifier.testTag("connection-save-mac")) { Text("Save Mac") }
    TextButton({ error = controller.importFromConnectedMac() }, modifier = Modifier.testTag("connection-import-file")) { Text("Import from connected Mac") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (profile.mode !in listOf(ConnectionMode.BluetoothClassic, ConnectionMode.BluetoothLE, ConnectionMode.USB, ConnectionMode.WiFiADB)) {
      OutlinedTextField(profile.host, { controller.update(profile.copy(host = it.trim())) }, label = { Text("Mac address") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("connection-host"))
      for (mac in macs) TextButton({ controller.update(profile.copy(host = mac.host, port = mac.port)) }, Modifier.testTag("connection-found-mac")) { Text("Use ${mac.name} · ${mac.host}") }
      TextButton({ controller.openNetworkSettings() }) { Text("Open Wi-Fi settings") }
    }
    when (profile.mode) {
      ConnectionMode.MacHotspot -> Text("On the Mac: System Settings → General → Sharing → Internet Sharing. Share a supported upstream connection over Wi-Fi, then join that network on the Quest. The available choices depend on your Mac's interfaces.")
      ConnectionMode.PhoneHotspot -> Text("Enable the phone's hotspot, then join it on both devices. If connection fails, the hotspot may block traffic between clients; try a private router.")
      ConnectionMode.WiFiDirect -> {
        OutlinedButton(controller::createDirectNetwork, Modifier.testTag("connection-create-direct")) { Text("Create Quest network") }
        TextButton(controller::removeDirectNetwork) { Text("Remove Quest network") }
      }
      ConnectionMode.BluetoothClassic, ConnectionMode.BluetoothLE -> {
        Text("Start the Mac service with --bluetooth. The same Mac connection key is required for Bluetooth.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(controller::requestBluetooth) { Text("Allow Nearby devices") }
          TextButton({ controller.openNetworkSettings(true) }) { Text("Bluetooth settings") }
        }
        if (profile.mode == ConnectionMode.BluetoothClassic) {
          TextButton({ paired = controller.pairedBluetooth() }) { Text("Show paired devices") }
          paired.forEach { (name, address) -> TextButton({ controller.update(profile.copy(classicAddress = address)) }) { Text(name) } }
          OutlinedTextField(profile.classicAddress, { controller.update(profile.copy(classicAddress = it.trim().uppercase())) }, label = { Text("Mac Bluetooth address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        } else {
          Text("Leave the address blank to scan for a vrcode Mac. Certificate checks still enforce your paired Mac's identity.")
          OutlinedTextField(profile.bleAddress, { controller.update(profile.copy(bleAddress = it.trim().uppercase())) }, label = { Text("BLE address (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
      }
      ConnectionMode.BluetoothPAN -> {
        Text("Pair both devices and enable a Bluetooth network in their system settings first. vrcode uses its IP route. If Horizon OS does not offer PAN, this mode cannot create it; choose Bluetooth instead.")
        TextButton({ controller.openNetworkSettings(true) }) { Text("Open Bluetooth settings") }
      }
      ConnectionMode.USB, ConnectionMode.WiFiADB -> Text("On the Mac, run bun scripts/quest-link.ts ${if (profile.mode == ConnectionMode.USB) "usb" else "wifi"}. It must forward relay port ${profile.port}. The Mac connection key is still required.")
      else -> Unit
    }
    if (setup.isNotBlank()) Text(setup, modifier = Modifier.testTag("connection-setup-result"))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(controller::connect, enabled = profile.paired, modifier = Modifier.heightIn(min = 52.dp).testTag("connection-connect")) { Text(if (status.connected || status.busy) "Reconnect" else "Connect") }
      OutlinedButton(controller::disconnect, modifier = Modifier.heightIn(min = 52.dp).testTag("connection-disconnect")) { Text(if (status.busy) "Cancel" else "Disconnect") }
    }
  }
}
