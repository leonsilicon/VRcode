package dev.improve.vrcode

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Quest's settings as data, so VRcode Mac can show exactly the same settings and change them
 * (vrcode protocol SETTINGS / SETTINGS_SET / SETTINGS_ACTION, docs/vrcode-protocol.md).
 *
 * Keys are [SettingsStore]'s preference keys and enum values their `storageValue`s. Option lists,
 * labels and details come from the same enums the Settings panel renders, so a new theme, mic or
 * AI provider appears on the Mac without another change. Layout follows [SettingsPage] and
 * [CustomizationTab]. Keep section and item labels in step with the panel when editing it.
 */
object SettingsSync {
  const val ARRANGE_WINDOWS = "arrange_windows"

  fun values(settings: AppSettings): Map<String, Any> = settings.sanitized().let { s ->
    linkedMapOf(
      "passthrough" to s.passthrough,
      "window_distance_m" to s.windowDistanceM,
      "auto_connect" to s.autoConnect,
      "window_style" to s.windowStyle.storageValue,
      "window_opacity" to s.windowOpacity,
      "text_mic_prop" to s.textMicProp.storageValue,
      "ai_mic_prop" to s.aiMicProp.storageValue,
      "ai_provider" to s.aiProvider.storageValue,
      AiProvider.Claude.modelKey to s.claudeModel.id,
      AiProvider.ChatGpt.modelKey to s.chatGptModel.id,
      AiProvider.Gemini.modelKey to s.geminiModel.id,
      "ai_thinking" to s.aiThinking.storageValue,
      "terminal_theme" to s.terminalTheme.storageValue,
      "terminal_font_size" to s.terminalFontSize,
      "terminal_cursor" to s.terminalCursor.storageValue,
      "default_window_mode" to s.defaultWindowMode.storageValue,
      "default_terminal_cols" to s.defaultTerminalSize.cols,
      "default_terminal_rows" to s.defaultTerminalSize.rows,
      "pointer_cursor" to s.pointerCursor.storageValue,
      "pointer_cursor_size" to s.pointerCursorSize,
      "pinch_drag" to s.pinchDrag.storageValue,
      "quick_inputs" to s.quickInputs.storageValues,
    )
  }

  /** Applies a partial update from the Mac. Unknown keys and values of the wrong type are ignored. */
  fun apply(settings: AppSettings, values: Map<String, Any?>): AppSettings {
    var s = settings
    fun bool(key: String) = values[key] as? Boolean
    fun float(key: String) = (values[key] as? Number)?.toFloat()
    fun int(key: String) = (values[key] as? Number)?.toDouble()?.roundToInt()
    fun <T> enum(key: String, entries: List<T>, id: (T) -> String): T? = (values[key] as? String)?.let { v -> entries.firstOrNull { id(it) == v } }
    bool("passthrough")?.let { s = s.copy(passthrough = it) }
    float("window_distance_m")?.let { s = s.copy(windowDistanceM = it) }
    bool("auto_connect")?.let { s = s.copy(autoConnect = it) }
    enum("window_style", WindowStyle.entries) { it.storageValue }?.let { s = s.copy(windowStyle = it) }
    float("window_opacity")?.let { s = s.copy(windowOpacity = it) }
    enum("text_mic_prop", VoiceMic.Text.props) { it.storageValue }?.let { s = s.copy(textMicProp = it) }
    enum("ai_mic_prop", VoiceMic.Ai.props) { it.storageValue }?.let { s = s.copy(aiMicProp = it) }
    enum("ai_provider", AiProvider.entries) { it.storageValue }?.let { s = s.copy(aiProvider = it) }
    for (provider in AiProvider.entries) enum(provider.modelKey, provider.models) { it.id }?.let { s = s.withAiModel(it) }
    enum("ai_thinking", AiThinking.entries) { it.storageValue }?.let { s = s.copy(aiThinking = it) }
    enum("terminal_theme", TerminalTheme.entries) { it.storageValue }?.let { s = s.copy(terminalTheme = it) }
    float("terminal_font_size")?.let { s = s.copy(terminalFontSize = it.roundToInt().toFloat()) }
    enum("terminal_cursor", TerminalCursor.entries) { it.storageValue }?.let { s = s.copy(terminalCursor = it) }
    enum("default_window_mode", WindowMode.entries) { it.storageValue }?.let { s = s.copy(defaultWindowMode = it) }
    int("default_terminal_cols")?.let { s = s.copy(defaultTerminalSize = s.defaultTerminalSize.copy(cols = it)) }
    int("default_terminal_rows")?.let { s = s.copy(defaultTerminalSize = s.defaultTerminalSize.copy(rows = it)) }
    enum("pointer_cursor", PointerCursorStyle.entries) { it.storageValue }?.let { s = s.copy(pointerCursor = it) }
    float("pointer_cursor_size")?.let { s = s.copy(pointerCursorSize = it) }
    enum("pinch_drag", PinchDrag.entries) { it.storageValue }?.let { s = s.copy(pinchDrag = it) }
    // Eight entries, clockwise from the top, "" where empty; anything else is ignored.
    (values["quick_inputs"] as? List<*>)?.takeIf { it.size == QuickInputSlot.entries.size }?.let { list ->
      s = s.copy(quickInputs = QuickInputRing.fromStorageValues(list.map { it as? String }))
    }
    return s.sanitized()
  }

  private fun option(value: String, label: String, detail: String? = null, extra: Map<String, Any?> = emptyMap()) =
    linkedMapOf<String, Any?>("value" to value, "label" to label).apply { if (detail != null) put("detail", detail); putAll(extra) }
  private fun item(type: String, vararg fields: Pair<String, Any?>) = linkedMapOf<String, Any?>("type" to type).apply { fields.forEach { (k, v) -> if (v != null) put(k, v) } }
  private fun section(title: String?, items: List<Map<String, Any?>>, detail: String? = null, tab: String? = null) =
    linkedMapOf<String, Any?>().apply { if (title != null) put("title", title); if (detail != null) put("detail", detail); if (tab != null) put("tab", tab); put("items", items) }
  private fun slider(key: String, label: String, min: Float, max: Float, step: Float, unit: String, decimals: Int = 0, scale: Int = 1) =
    item("slider", "key" to key, "label" to label, "min" to min, "max" to max, "step" to step, "unit" to unit, "decimals" to decimals, "scale" to scale)
  private fun hex(color: Long) = String.format(Locale.US, "#%06X", color and 0xFFFFFF)

  /** The left index-pinch ring: one input (or nothing) per direction, as [QuickInputControls] shows it. */
  private fun quickInputSections(): List<Map<String, Any?>> = listOf(
    section(QUICK_INPUTS_TITLE, listOf(
      item("quick_inputs", "key" to "quick_inputs",
        "slots" to QuickInputSlot.entries.map { linkedMapOf("id" to it.name, "label" to it.label) },
        "options" to QuickInput.entries.map { option(it.storageValue, it.label, it.detail) }),
      item("note", "text" to QUICK_INPUTS_NOTE),
      item("reset", "label" to "Restore default quick inputs", "values" to mapOf("quick_inputs" to QuickInputRing.Default.storageValues)),
    ), detail = QUICK_INPUTS_DETAIL, tab = CustomizationTab.QuickInputs.name),
  )

  private fun micSections(mic: VoiceMic, key: String): List<Map<String, Any?>> {
    val tab = if (mic == VoiceMic.Text) CustomizationTab.TextMic else CustomizationTab.AiMic
    val intro = section("${mic.label} · ${mic.hand.replaceFirstChar { it.uppercase() }} hand", listOf(
      item("note", "text" to mic.detail),
      item("note", "text" to "Hold up your ${mic.hand} hand to preview. Previewing does not record audio."),
    ), tab = tab.name)
    val provider = if (mic != VoiceMic.Ai) null else section("AI provider", listOf(
      item("choice", "key" to "ai_provider", "options" to AiProvider.entries.map { option(it.storageValue, it.label, it.company) }),
      // One model choice per provider, each shown only while its provider is selected.
      *AiProvider.entries.map { ai ->
        item("choice", "key" to ai.modelKey, "label" to "Model", "options" to ai.models.map { option(it.id, it.label, it.detail) },
          "shownWhen" to mapOf("ai_provider" to ai.storageValue))
      }.toTypedArray(),
      item("choice", "key" to "ai_thinking", "label" to "Thinking", "options" to AiThinking.entries.map { option(it.storageValue, it.label, it.detail) }),
      item("api_key", "providerKey" to "ai_provider"),
      item("note", "text" to "With thinking on, your Mac runs the provider's agent (Claude Code, Codex or Gemini CLI, signed in with its own login) in the pane's folder: it reads your project, never changes it, and its keystrokes run in your terminal. A quick answer uses the provider's API key on this Mac instead. The news microphone shows the provider's logo."),
    ), tab = tab.name)
    val look = section("Mic appearance", listOf(
      item("choice", "key" to key, "options" to mic.props.map { option(it.storageValue, it.label, it.detail) } +
        listOfNotNull(if (!mic.comingSoon) null else option(VoiceMic.COMING_SOON_VALUE, VoiceMic.COMING_SOON_LABEL,
          VoiceMic.COMING_SOON_DETAIL, mapOf("disabled" to true)))),
      item("note", "text" to mic.appearanceNote),
    ), tab = tab.name)
    return listOfNotNull(intro, provider, look)
  }

  /** The settings layout, in the Quest panel's page, tab, section and item order. */
  fun schema(): Map<String, Any?> {
    val defaults = values(AppSettings())
    val terminalDefaults = listOf("terminal_theme", "terminal_font_size", "terminal_cursor").associateWith { defaults[it] }
    val pages = SettingsPage.entries.map { page ->
      val sections: List<Map<String, Any?>> = when (page) {
        SettingsPage.Workspace -> listOf(
          section("Your space", listOf(item("toggle", "key" to "passthrough", "label" to "Passthrough", "detail" to "See your room behind the windows."))),
          section("New terminal size", listOf(
            item("presets", "options" to TerminalSizePreset.entries.map {
              linkedMapOf("label" to "${it.label} · ${it.size.cols} × ${it.size.rows}", "values" to mapOf("default_terminal_cols" to it.size.cols, "default_terminal_rows" to it.size.rows))
            }),
            slider("default_terminal_cols", "Width", TerminalSize.MIN_COLS.toFloat(), TerminalSize.MAX_COLS.toFloat(), 1f, " columns"),
            slider("default_terminal_rows", "Height", TerminalSize.MIN_ROWS.toFloat(), TerminalSize.MAX_ROWS.toFloat(), 1f, " rows"),
            item("note", "text" to "Standard (120 × 36) is the default. Open terminals keep their current size."),
          ), detail = "Width in columns and height in rows. Applies to terminals created in VRcode, including Codex and Claude Code."),
          section("Terminal placement", listOf(
            item("choice", "key" to "default_window_mode", "label" to "New window behavior",
              "options" to WindowMode.entries.map { option(it.storageValue, it.label, it.detail) }),
            item("note", "text" to "Applies to new windows. Use Fixed / Follow in a window’s title bar to change it individually."),
            slider("window_distance_m", "Window distance", AppSettings.MIN_DISTANCE, AppSettings.MAX_DISTANCE, 0.1f, " m", decimals = 1),
            item("note", "text" to "Used for new terminals. Arrange windows brings open terminals in front of you at this distance."),
            item("action", "action" to ARRANGE_WINDOWS, "label" to "Arrange windows here"),
            item("reset", "label" to "Restore default settings", "values" to defaults),
          )),
        )
        SettingsPage.Appearance -> listOf(
          section("Window style", listOf(
            item("choice", "key" to "window_style", "options" to WindowStyle.entries.map { option(it.storageValue, it.label, it.detail) }),
            slider("window_opacity", "Window opacity", AppSettings.MIN_WINDOW_OPACITY, AppSettings.MAX_WINDOW_OPACITY,
              AppSettings.WINDOW_OPACITY_STEP, "%", scale = 100),
            item("note", "text" to WINDOW_OPACITY_NOTE),
          ), detail = "Choose the look of your terminal windows. Applies to open and new windows."),
          section("Terminal", listOf(
            item("choice", "key" to "terminal_theme", "label" to "Color theme", "options" to TerminalTheme.entries.map {
              option(it.storageValue, it.label, it.detail, mapOf("colors" to mapOf("background" to hex(it.background), "foreground" to hex(it.foreground), "accent" to hex(it.accent))))
            }),
            item("note", "text" to "Themes set the default text, background and cursor. Apps keep their own colors."),
            slider("terminal_font_size", "Text size", AppSettings.MIN_FONT_SIZE, AppSettings.MAX_FONT_SIZE, 1f, " px"),
            item("note", "text" to "JetBrains Mono. Larger text expands the window while keeping the same rows and columns."),
            item("choice", "key" to "terminal_cursor", "label" to "Cursor shape", "options" to TerminalCursor.entries.map { option(it.storageValue, it.label) }),
            item("reset", "label" to "Reset terminal appearance", "values" to terminalDefaults),
          ), detail = "Make it yours. Changes apply to open and new terminals."),
        )
        SettingsPage.Customization -> listOf(
          section("Pointer cursor", listOf(
            item("choice", "key" to "pointer_cursor", "options" to PointerCursorStyle.entries.map { option(it.storageValue, it.label) }),
            slider("pointer_cursor_size", "Pointer size", AppSettings.MIN_POINTER_SIZE, AppSettings.MAX_POINTER_SIZE, 0.25f, "%", scale = 100)
              .apply { put("hiddenWhen", mapOf("pointer_cursor" to PointerCursorStyle.None.storageValue)) },
          ), detail = POINTER_DETAIL, tab = CustomizationTab.Pointer.name),
          section(PINCH_DRAG_TITLE, listOf(
            item("choice", "key" to "pinch_drag", "options" to PinchDrag.entries.map { option(it.storageValue, it.label, it.detail) }),
            item("note", "text" to PINCH_DRAG_NOTE),
          ), tab = CustomizationTab.Pointer.name),
        ) + micSections(VoiceMic.Text, "text_mic_prop") + micSections(VoiceMic.Ai, "ai_mic_prop") + quickInputSections()
        SettingsPage.Connection -> listOf(
          section("Your Mac", listOf(item("toggle", "key" to "auto_connect", "label" to "Connect on launch", "detail" to "Reconnect to your Mac when VRcode starts."))),
        )
      }
      linkedMapOf<String, Any?>("id" to page.name.lowercase(), "title" to page.title).apply {
        if (page == SettingsPage.Customization) put("tabs", CustomizationTab.entries.map { mapOf("id" to it.name, "title" to it.title) })
        put("sections", sections)
      }
    }
    return linkedMapOf("version" to 1, "pages" to pages)
  }

  /** The SETTINGS body. */
  fun state(settings: AppSettings, device: String): String =
    json(linkedMapOf("device" to device, "schema" to schema(), "values" to values(settings)))

  /** A small JSON encoder for maps, lists, strings, numbers and booleans (JVM tests run without org.json). */
  fun json(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

  private fun write(out: StringBuilder, value: Any?) {
    when (value) {
      null -> out.append("null")
      is Boolean -> out.append(value)
      is Float -> out.append(if (value.isFinite()) value.toBigDecimal().stripTrailingZeros().toPlainString() else "null")
      is Double -> out.append(if (value.isFinite()) value.toBigDecimal().stripTrailingZeros().toPlainString() else "null")
      is Number -> out.append(value)
      is String -> {
        out.append('"')
        for (c in value) when {
          c == '"' -> out.append("\\\"")
          c == '\\' -> out.append("\\\\")
          c == '\n' -> out.append("\\n")
          c < ' ' -> out.append(String.format(Locale.US, "\\u%04x", c.code))
          else -> out.append(c)
        }
        out.append('"')
      }
      is Map<*, *> -> {
        out.append('{')
        value.entries.forEachIndexed { i, (k, v) -> if (i > 0) out.append(','); write(out, k.toString()); out.append(':'); write(out, v) }
        out.append('}')
      }
      is Iterable<*> -> { out.append('['); value.forEachIndexed { i, v -> if (i > 0) out.append(','); write(out, v) }; out.append(']') }
      else -> write(out, value.toString())
    }
  }
}
