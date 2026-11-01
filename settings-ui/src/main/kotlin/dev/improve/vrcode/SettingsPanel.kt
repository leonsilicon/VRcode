package dev.improve.vrcode

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.improve.xmux.connect.panel.BridgeChoice
import dev.improve.xmux.connect.panel.ConnectPanel
import dev.improve.xmux.connect.panel.ConnectStatus
import dev.improve.xmux.window.frame.TerminalWindowState
import dev.improve.xmux.window.frame.WindowChrome
import java.util.Locale

enum class SettingsPage(val title: String) { Workspace("Workspace"), Appearance("Appearance"), Customization("Customization"), Connection("Connection") }
enum class CustomizationTab(val title: String) { Pointer("Pointer"), Microphone("Microphone") }

private val colors = darkColorScheme(
  primary = Color(0xFFA9C7FF),
  onPrimary = Color(0xFF102D55),
  surface = Color(0xFF171C25),
  surfaceContainer = Color(0xFF222A37),
  background = Color(0xFF171C25),
)

@Composable
fun VrcodeTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun SettingsPanel(
  settings: AppSettings,
  page: SettingsPage,
  status: ConnectStatus,
  bridges: List<BridgeChoice>,
  onPage: (SettingsPage) -> Unit,
  onChange: (AppSettings) -> Unit,
  onArrange: () -> Unit,
  onConnect: (String) -> Unit,
  onDisconnect: () -> Unit,
  onClose: () -> Unit,
  customizationTab: CustomizationTab,
  onCustomizationTab: (CustomizationTab) -> Unit,
  connectionContent: (@Composable () -> Unit)? = null,
  onTerminalActions: (() -> Unit)? = null,
) {
  VrcodeTheme {
    Surface(Modifier.fillMaxSize().testTag("vrcode-settings"), shape = RoundedCornerShape(24.dp)) {
      Column {
        Row(Modifier.fillMaxWidth().padding(start = 30.dp, end = 20.dp, top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
          Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Make VRcode feel like your workspace", color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          if (onTerminalActions != null) TextButton(onTerminalActions) { Text("Terminal actions") }
          FilledTonalButton(onClose, Modifier.heightIn(min = 52.dp).testTag("settings-close")) {
            Icon(Icons.Default.Close, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Close")
          }
        }
        HorizontalDivider()
        Row(Modifier.weight(1f)) {
          Column(Modifier.width(210.dp).fillMaxHeight().background(Color(0xFF121720)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("VRcode", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (item in SettingsPage.entries) {
              if (page == item) {
                FilledTonalButton({ onPage(item) }, Modifier.fillMaxWidth().height(56.dp).testTag("settings-page-${item.name}")) { Text(item.title) }
              } else {
                TextButton({ onPage(item) }, Modifier.fillMaxWidth().height(56.dp).testTag("settings-page-${item.name}")) { Text(item.title) }
              }
            }
            Spacer(Modifier.weight(1f))
            Text("Palm toward you, then pinch your left index finger and thumb to open or close.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Column(Modifier.weight(1f).fillMaxHeight()) {
            if (page == SettingsPage.Customization) {
              TabRow(selectedTabIndex = customizationTab.ordinal, modifier = Modifier.testTag("settings-customization-tabs")) {
                for (tab in CustomizationTab.entries) {
                  Tab(
                    selected = customizationTab == tab,
                    onClick = { onCustomizationTab(tab) },
                    modifier = Modifier.heightIn(min = 56.dp).testTag("settings-customization-tab-${tab.name}"),
                    text = { Text(tab.title) },
                  )
                }
              }
            }
            key(page, customizationTab) {
              Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (page) {
                  SettingsPage.Workspace -> {
                    Text("Your space", style = MaterialTheme.typography.headlineSmall)
                    SettingSwitch("Passthrough", "See your room behind the windows.", settings.passthrough, "settings-passthrough") { onChange(settings.copy(passthrough = it)) }
                    HorizontalDivider()
                    Text("Terminal placement", style = MaterialTheme.typography.titleLarge)
                    Text("New window behavior", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                      for (mode in WindowMode.entries) {
                        Row(
                          Modifier.fillMaxWidth().testTag("settings-window-mode-${mode.storageValue}")
                            .selectable(settings.defaultWindowMode == mode, role = Role.RadioButton,
                              onClick = { onChange(settings.copy(defaultWindowMode = mode)) })
                            .padding(vertical = 10.dp),
                          verticalAlignment = Alignment.CenterVertically,
                        ) {
                          RadioButton(settings.defaultWindowMode == mode, onClick = null)
                          Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(mode.label, style = MaterialTheme.typography.titleMedium)
                            Text(mode.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                          }
                        }
                      }
                    }
                    Text("Applies to new windows. Use Fixed / Follow in a window’s title bar to change it individually.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Text("Window distance", Modifier.weight(1f))
                      Text(String.format(Locale.US, "%.1f m", settings.windowDistanceM), color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                      value = settings.windowDistanceM,
                      onValueChange = { onChange(settings.copy(windowDistanceM = it)) },
                      valueRange = AppSettings.MIN_DISTANCE..AppSettings.MAX_DISTANCE,
                      steps = 16,
                      modifier = Modifier.testTag("settings-distance").semantics { contentDescription = "Window distance in metres" },
                    )
                    Text("Used for new terminals. Arrange windows brings open terminals in front of you at this distance.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onArrange, Modifier.heightIn(min = 52.dp).testTag("settings-arrange")) { Text("Arrange windows here") }
                    TextButton({ onChange(AppSettings()) }, Modifier.heightIn(min = 48.dp).testTag("settings-defaults")) { Text("Restore default settings") }
                  }
                  SettingsPage.Appearance -> {
                    Text("Window style", style = MaterialTheme.typography.headlineSmall)
                    Text("Choose the look of your terminal windows. Applies to open and new windows.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                      for (style in WindowStyle.entries) {
                        WindowStyleOption(style, settings.windowStyle == style, Modifier.weight(1f)) {
                          onChange(settings.copy(windowStyle = style))
                        }
                      }
                    }
                    HorizontalDivider()
                    TerminalAppearanceControls(settings, onChange)
                  }
                  SettingsPage.Customization -> {
                    when (customizationTab) {
                      CustomizationTab.Pointer -> PointerCursorControls(settings, onChange)
                      CustomizationTab.Microphone -> MicrophoneControls(settings, onChange)
                    }
                  }
                  SettingsPage.Connection -> {
                    Text("Your Mac", style = MaterialTheme.typography.headlineSmall)
                    SettingSwitch("Connect on launch", "Reconnect to the last bridge when VRcode starts.", settings.autoConnect, "settings-auto-connect") { onChange(settings.copy(autoConnect = it)) }
                    if (connectionContent != null) connectionContent() else ConnectPanel(status, bridges, onConnect, onDisconnect, Modifier.fillMaxWidth())
                  }
                }
              }
            }
          }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Changes save automatically", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(Modifier.weight(1f))
          Text("VRcode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
      }
    }
  }
}

@Composable
private fun MicrophoneControls(settings: AppSettings, onChange: (AppSettings) -> Unit) {
  Text("Microphone", style = MaterialTheme.typography.headlineSmall)
  Text("Hold up your left hand to preview your voice prop. It stays attached while this tab is open, even with an open hand. Previewing does not record audio.", color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text("Voice prop", style = MaterialTheme.typography.titleLarge)
  Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    for (row in VoiceProp.entries.chunked(2)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (prop in row) {
          VoicePropOption(prop, settings.voiceProp == prop, Modifier.weight(1f)) {
            onChange(settings.copy(voiceProp = prop))
          }
        }
      }
    }
  }
  Text("Same gesture, different personality. The listening indicator and transcript work with every prop.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun VoicePropOption(prop: VoiceProp, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
  Surface(
    modifier,
    shape = RoundedCornerShape(16.dp),
    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      Modifier.testTag("settings-voice-prop-${prop.storageValue}")
        .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Image(painterResource(prop.preview), contentDescription = null,
        modifier = Modifier.fillMaxWidth().height(100.dp), contentScale = ContentScale.Fit)
      Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(prop.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
      }
      Text(prop.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun WindowStyleOption(style: WindowStyle, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
  val state = remember { TerminalWindowState("Terminal").apply { loading = false } }
  val chrome = remember { WindowChrome() }
  Surface(
    modifier,
    shape = RoundedCornerShape(16.dp),
    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      Modifier.testTag("settings-window-style-${style.storageValue}")
        .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(chrome.bodyColor)).clearAndSetSemantics {}) {
        VrcodeWindowHeader(state, style, chrome)
        Text("› _", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.primary)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(style.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
      }
      Text(style.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, tag: String, onChecked: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f).padding(end = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked, onChecked, Modifier.testTag(tag).semantics { contentDescription = title })
  }
}

@Composable
fun SettingsButton(tint: Color = Color(0xFFA9C7FF), onClick: () -> Unit) {
  IconButton(onClick, Modifier.size(48.dp).testTag("vrcode-open-settings")) {
    Icon(Icons.Default.Settings, contentDescription = "Open VRcode settings", tint = tint)
  }
}

