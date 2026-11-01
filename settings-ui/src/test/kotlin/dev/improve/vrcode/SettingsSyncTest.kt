package dev.improve.vrcode

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SettingsSyncTest {
  private fun items(): List<Map<*, *>> = (SettingsSync.schema()["pages"] as List<*>).flatMap { page ->
    ((page as Map<*, *>)["sections"] as List<*>).flatMap { ((it as Map<*, *>)["items"] as List<*>).map { item -> item as Map<*, *> } }
  }

  @Test fun everySettingIsEditableOnTheMac() {
    val keys = items().mapNotNull { it["key"] as? String }.toSet() +
      items().filter { it["type"] == "presets" }.flatMap { (it["options"] as List<*>).flatMap { o -> ((o as Map<*, *>)["values"] as Map<*, *>).keys.map(Any?::toString) } }
    assertEquals(SettingsSync.values(AppSettings()).keys, keys)
    // Every saved preference is synced, so a new AppSettings field must be added here too.
    assertEquals(AppSettings::class.java.declaredFields.count { !java.lang.reflect.Modifier.isStatic(it.modifiers) } + 1,
      SettingsSync.values(AppSettings()).size) // TerminalSize is two values
  }

  @Test fun pagesFollowThePanelAndOptionsComeFromTheEnums() {
    val pages = SettingsSync.schema()["pages"] as List<*>
    assertEquals(SettingsPage.entries.map { it.title }, pages.map { (it as Map<*, *>)["title"] })
    val providers = items().first { it["key"] == "ai_provider" }["options"] as List<*>
    assertEquals(AiProvider.entries.map { it.storageValue }, providers.map { (it as Map<*, *>)["value"] })
    val themes = items().first { it["key"] == "terminal_theme" }["options"] as List<*>
    assertEquals(TerminalTheme.entries.size, themes.size)
    assertTrue(items().any { it["type"] == "api_key" && it["providerKey"] == "ai_provider" })
    // Each provider's model list shows on the Mac only while that provider is selected.
    for (provider in AiProvider.entries) {
      val models = items().single { it["key"] == provider.modelKey }
      assertEquals(mapOf("ai_provider" to provider.storageValue), models["shownWhen"])
      assertEquals(provider.models.map { it.id }, (models["options"] as List<*>).map { (it as Map<*, *>)["value"] })
    }
  }

  @Test fun aiModelsSyncPerProvider() {
    val values = SettingsSync.values(AppSettings())
    assertEquals("claude-sonnet-5-5", values["ai_model_claude"])
    assertEquals("gpt-6.1-sol", values["ai_model_openai"])
    assertEquals("gemini-3.8-flash", values["ai_model_gemini"])
    val updated = SettingsSync.apply(AppSettings(), mapOf("ai_model_claude" to "claude-opus-5-5", "ai_model_gemini" to "gemini-4-argon"))
    assertEquals(AiModel.ClaudeOpus, updated.aiModel())
    assertEquals(AiModel.GeminiArgon, updated.aiModel(AiProvider.Gemini))
    assertEquals(AiProvider.Claude, updated.aiProvider)
    // Unknown ids and another provider's model are ignored.
    val ignored = SettingsSync.apply(updated, mapOf("ai_model_claude" to "gpt-6.1-sol", "ai_model_openai" to "gpt-99"))
    assertEquals(updated, ignored)
  }

  @Test fun aiThinkingDefaultsToAboveAndSyncs() {
    assertEquals("above", SettingsSync.values(AppSettings())["ai_thinking"])
    val choice = items().single { it["key"] == "ai_thinking" }
    assertEquals(listOf("above", "below", "off"), (choice["options"] as List<*>).map { (it as Map<*, *>)["value"] })
    assertEquals(AiThinking.Off, SettingsSync.apply(AppSettings(), mapOf("ai_thinking" to "off")).aiThinking)
    assertEquals(AiThinking.Above, SettingsSync.apply(AppSettings(), mapOf("ai_thinking" to "sideways")).aiThinking)
    assertFalse(AiThinking.Off.agent)
    assertTrue(AiThinking.Below.agent)
  }

  @Test fun everyOfferedValueAppliesAndRoundTrips() {
    val base = AppSettings()
    for (item in items()) {
      val key = item["key"] as? String ?: continue
      when (item["type"]) {
        "choice" -> for (option in item["options"] as List<*>) {
          val value = (option as Map<*, *>)["value"]
          assertEquals("$key=$value", value, SettingsSync.values(SettingsSync.apply(base, mapOf(key to value)))[key])
        }
        "toggle" -> assertEquals(false, SettingsSync.values(SettingsSync.apply(base, mapOf(key to false)))[key])
        "slider" -> {
          val max = (item["max"] as Number)
          assertEquals(max.toDouble(), (SettingsSync.values(SettingsSync.apply(base, mapOf(key to max)))[key] as Number).toDouble(), 1e-6)
        }
      }
    }
    val custom = AppSettings(passthrough = false, terminalTheme = TerminalTheme.Nord, aiProvider = AiProvider.Gemini,
      claudeModel = AiModel.ClaudeFable, geminiModel = AiModel.GeminiFlashLite,
      defaultTerminalSize = TerminalSize(100, 48), pointerCursorSize = 1.5f, windowOpacity = 0.55f)
    assertEquals(custom, SettingsSync.apply(AppSettings(), SettingsSync.values(custom)))
  }

  @Test fun badValuesFromTheMacAreIgnoredOrClamped() {
    val base = AppSettings(terminalTheme = TerminalTheme.Dracula)
    val updated = SettingsSync.apply(base, mapOf("terminal_theme" to "nope", "passthrough" to "yes", "window_distance_m" to 99,
      "default_terminal_cols" to 9999.0, "unknown" to 1, "terminal_font_size" to 27.6, "window_opacity" to 0.02))
    assertEquals(TerminalTheme.Dracula, updated.terminalTheme)
    assertTrue(updated.passthrough)
    assertEquals(AppSettings.MAX_DISTANCE, updated.windowDistanceM)
    assertEquals(TerminalSize.MAX_COLS, updated.defaultTerminalSize.cols)
    assertEquals(28f, updated.terminalFontSize)
    assertEquals(AppSettings.MIN_WINDOW_OPACITY, updated.windowOpacity)
  }

  @Test fun resetsRestoreDefaults() {
    val changed = AppSettings(passthrough = false, terminalTheme = TerminalTheme.Nord, terminalFontSize = 30f)
    for (reset in items().filter { it["type"] == "reset" }) {
      @Suppress("UNCHECKED_CAST") val values = reset["values"] as Map<String, Any?>
      val applied = SettingsSync.values(SettingsSync.apply(changed, values))
      for ((key, value) in values) assertEquals(key, value, applied[key])
    }
  }

  @Test fun stateIsValidJsonWithEscaping() {
    val body = SettingsSync.state(AppSettings(), "Quest \"3\"\n")
    assertTrue(body.startsWith("{\"device\":\"Quest \\\"3\\\"\\n\",\"schema\":{\"version\":1,"))
    assertTrue(body.contains("\"window_distance_m\":1.4"))
    assertTrue(body.contains("\"default_terminal_cols\":120"))
    // A sample for the Mac app (VRcode Mac) to render against.
    File("build/settings-sync").apply { mkdirs() }.resolve("state.json").writeText(body)
  }
}
