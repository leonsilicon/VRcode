package dev.improve.vrcode

import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {
  @Test fun terminalSizesHaveAUsableDefaultAndClampCustomDimensions() {
    assertEquals(TerminalSize(120, 36), AppSettings().defaultTerminalSize)
    assertEquals(TerminalSize(40, 72), AppSettings(defaultTerminalSize = TerminalSize(-1, Int.MAX_VALUE)).sanitized().defaultTerminalSize)
    assertEquals(TerminalSize(200, 12), TerminalSize(Int.MAX_VALUE, 0).sanitized())
    assertEquals(TerminalSize(137, 41), AppSettings(defaultTerminalSize = TerminalSize(137, 41)).sanitized().defaultTerminalSize)
    assertEquals(4, TerminalSizePreset.entries.map { it.size }.distinct().size)
    for (preset in TerminalSizePreset.entries) assertEquals(preset.size, preset.size.sanitized())
    assertEquals(AppSettings().defaultTerminalSize, TerminalSizePreset.Standard.size)
  }

  @Test fun invalidSavedDistancesCannotPutWindowsOutOfReach() {
    assertEquals(1.4f, AppSettings(windowDistanceM = Float.NaN).sanitized().windowDistanceM)
    assertEquals(1.4f, AppSettings(windowDistanceM = Float.POSITIVE_INFINITY).sanitized().windowDistanceM)
    assertEquals(AppSettings.MIN_DISTANCE, AppSettings(windowDistanceM = -1f).sanitized().windowDistanceM)
    assertEquals(AppSettings.MAX_DISTANCE, AppSettings(windowDistanceM = 100f).sanitized().windowDistanceM)
  }

  @Test fun sanitizingPreservesValidUserChoices() {
    val chosen = AppSettings(passthrough = false, windowDistanceM = 2.1f, autoConnect = false, windowStyle = WindowStyle.Windows, textMicProp = VoiceProp.Banana, aiMicProp = VoiceProp.Megaphone, defaultWindowMode = WindowMode.Follow,
      pointerCursor = PointerCursorStyle.Crosshair, pointerCursorSize = 1.5f)
    assertEquals(chosen, chosen.sanitized())
  }

  @Test fun missingAndUnknownWindowModesDefaultToFollowAndSavedChoicesSurvive() {
    assertEquals(WindowMode.Follow, AppSettings().defaultWindowMode)
    assertEquals(WindowMode.Follow, WindowMode.fromStorage(null))
    assertEquals(WindowMode.Follow, WindowMode.fromStorage("future-mode"))
    for (mode in WindowMode.entries) assertEquals(mode, WindowMode.fromStorage(mode.storageValue))
  }

  @Test fun missingOrUnknownWindowStyleUsesExistingMacAppearance() {
    assertEquals(WindowStyle.MacOS, AppSettings().windowStyle)
    assertEquals(WindowStyle.MacOS, WindowStyle.fromStorage(null))
    assertEquals(WindowStyle.MacOS, WindowStyle.fromStorage("future-style"))
    assertEquals(WindowStyle.Windows, WindowStyle.fromStorage("windows"))
    assertEquals(WindowStyle.MacOS, WindowStyle.fromStorage("macos"))
  }

  @Test fun missingOrUnknownVoicePropFallsBackToMicrophone() {
    assertEquals(VoiceProp.Microphone, AppSettings().textMicProp)
    assertEquals(VoiceProp.NewsMicrophone, AppSettings().aiMicProp)
    assertEquals(VoiceProp.Microphone, VoiceProp.fromStorage(null))
    assertEquals(VoiceProp.Microphone, VoiceProp.fromStorage("removed-prop"))
    for (prop in VoiceProp.entries) assertEquals(prop, VoiceProp.fromStorage(prop.storageValue))
    assertEquals(VoiceProp.NewsMicrophone, VoiceProp.fromStorage(null, VoiceMic.Ai.defaultProp))
    assertEquals(VoiceProp.NewsMicrophone, VoiceProp.fromStorage("removed-prop", VoiceMic.Ai.defaultProp))
  }

  @Test fun theAiProviderDefaultsToClaudeAndPicksTheNewsMicLogo() {
    assertEquals(AiProvider.Claude, AppSettings().aiProvider)
    assertEquals(AiProvider.Claude, AiProvider.fromStorage(null))
    assertEquals(AiProvider.Claude, AiProvider.fromStorage("removed-provider"))
    for (provider in AiProvider.entries) assertEquals(provider, AiProvider.fromStorage(provider.storageValue))
    // The storage values double as the Mac protocol's provider names.
    assertEquals(listOf("claude", "openai", "gemini"), AiProvider.entries.map { it.storageValue })
    assertEquals("voice-props/news-microphone-gemini.glb", VoiceProp.NewsMicrophone.assetPath(AiProvider.Gemini))
    assertEquals("voice-props/news-microphone-openai.glb", VoiceProp.NewsMicrophone.assetPath(AiProvider.ChatGpt))
    // Other props never change with the provider.
    assertEquals("voice-props/banana.glb", VoiceProp.Banana.assetPath(AiProvider.Gemini))
    assertEquals(VoiceProp.Banana.preview, VoiceProp.Banana.preview(AiProvider.Gemini))
    assertEquals(AiProvider.Gemini.newsMicrophonePreview, VoiceProp.NewsMicrophone.preview(AiProvider.Gemini))
  }

  @Test fun eachProviderKeepsItsOwnModelAndClaudeDefaultsToSonnet() {
    val defaults = AppSettings()
    assertEquals(AiModel.ClaudeSonnet, defaults.aiModel())
    assertEquals("claude-sonnet-5-5", defaults.aiModel().id)
    assertEquals(AiModel.GptSol, defaults.aiModel(AiProvider.ChatGpt))
    assertEquals(AiModel.GeminiFlash, defaults.aiModel(AiProvider.Gemini))
    for (provider in AiProvider.entries) {
      assertTrue(provider.models.isNotEmpty())
      assertTrue(provider.models.all { it.provider == provider })
      assertTrue(provider.defaultModel in provider.models)
      assertEquals(provider.defaultModel, AiModel.fromStorage(provider, null))
      assertEquals(provider.defaultModel, AiModel.fromStorage(provider, "retired-model"))
      for (model in provider.models) assertEquals(model, AiModel.fromStorage(provider, model.id))
    }
    // Ids are what the Mac receives; another provider's id is not a valid choice.
    assertEquals(AiModel.entries.size, AiModel.entries.map { it.id }.distinct().size)
    assertEquals(AiModel.ClaudeSonnet, AiModel.fromStorage(AiProvider.Claude, AiModel.GptSol.id))
    assertEquals("ai_model_openai", AiProvider.ChatGpt.modelKey)

    // Choosing a model changes only its provider's choice, never the selected provider.
    val chosen = defaults.withAiModel(AiModel.ClaudeHaiku).withAiModel(AiModel.GeminiArgon)
    assertEquals(AiProvider.Claude, chosen.aiProvider)
    assertEquals(AiModel.ClaudeHaiku, chosen.aiModel())
    assertEquals(AiModel.GptSol, chosen.aiModel(AiProvider.ChatGpt))
    assertEquals(AiModel.GeminiArgon, chosen.copy(aiProvider = AiProvider.Gemini).aiModel())
    assertEquals(AiModel.ClaudeHaiku, chosen.copy(aiProvider = AiProvider.Gemini).copy(aiProvider = AiProvider.Claude).aiModel())
    assertEquals(chosen, chosen.sanitized())
    // A model filed under the wrong provider falls back to that provider's default.
    assertEquals(AiModel.GptSol, AppSettings(chatGptModel = AiModel.ClaudeOpus).sanitized().chatGptModel)
  }

  @Test fun savedBoomboxSelectionMigratesToNewsMicrophone() {
    assertEquals(VoiceProp.NewsMicrophone, VoiceProp.fromStorage("boombox"))
  }

  @Test fun existingInstallsAndUnknownPointerStylesKeepTheArrow() {
    assertEquals(PointerCursorStyle.Arrow, AppSettings().pointerCursor)
    assertEquals(PointerCursorStyle.Arrow, PointerCursorStyle.fromStorage(null))
    assertEquals(PointerCursorStyle.Arrow, PointerCursorStyle.fromStorage("future-cursor"))
    for (style in PointerCursorStyle.entries) assertEquals(style, PointerCursorStyle.fromStorage(style.storageValue))
  }

  @Test fun invalidPointerSizesCannotMakeThePointerInvisibleOrTooLarge() {
    assertEquals(1f, AppSettings(pointerCursorSize = Float.NaN).sanitized().pointerCursorSize)
    assertEquals(1f, AppSettings(pointerCursorSize = Float.POSITIVE_INFINITY).sanitized().pointerCursorSize)
    assertEquals(AppSettings.MIN_POINTER_SIZE, AppSettings(pointerCursorSize = 0f).sanitized().pointerCursorSize)
    assertEquals(AppSettings.MAX_POINTER_SIZE, AppSettings(pointerCursorSize = 100f).sanitized().pointerCursorSize)
    assertEquals(PointerCursorStyle.None, AppSettings(pointerCursor = PointerCursorStyle.None).sanitized().pointerCursor)
  }

  @Test fun windowsAreSemiTransparentByDefaultAndOpacityStaysReadable() {
    assertEquals(0.8f, AppSettings().windowOpacity)
    assertEquals(0.8f, AppSettings(windowOpacity = Float.NaN).sanitized().windowOpacity)
    assertEquals(0.8f, AppSettings(windowOpacity = Float.NEGATIVE_INFINITY).sanitized().windowOpacity)
    // Never so clear that text loses its background entirely, never more than opaque.
    assertEquals(AppSettings.MIN_WINDOW_OPACITY, AppSettings(windowOpacity = 0f).sanitized().windowOpacity)
    assertEquals(AppSettings.MAX_WINDOW_OPACITY, AppSettings(windowOpacity = 3f).sanitized().windowOpacity)
    // Snapped to exact 5% steps, so the label and the saved value agree.
    assertEquals(0.7f, AppSettings(windowOpacity = 0.71f).sanitized().windowOpacity)
    assertEquals(0.65f, AppSettings(windowOpacity = 0.6499f).sanitized().windowOpacity)
    for (percent in 30..100 step 5) {
      val opacity = percent / 100f
      assertEquals(opacity, AppSettings(windowOpacity = opacity).sanitized().windowOpacity)
      assertEquals("$percent%", windowOpacityLabel(opacity))
    }
  }

  @Test fun windowBackgroundsTakeTheOpacityAndTheirThemeColors() {
    assertEquals(0xCC1E1E1EL, withOpacity(0xFF1E1E1E, 0.8f))
    assertEquals(0xFF282A36L, withOpacity(0xFF282A36, 1f))
    assertEquals(0x00282A36L, withOpacity(0xFF282A36, 0f))
    val mac = dev.improve.xmux.window.frame.WindowChrome(bodyColor = 0).withTerminalTheme(TerminalTheme.Mac, 0.5f)
    // Follow Mac ignores the clear frame color terminal windows are built with.
    assertEquals(0x801E1E1EL, mac.bodyColor)
    assertEquals(0x802B2B2BL, mac.headerColor)
    assertEquals(0xFFC8C8C8L, mac.titleColor)
    val dracula = dev.improve.xmux.window.frame.WindowChrome().withTerminalTheme(TerminalTheme.Dracula)
    assertEquals(TerminalTheme.Dracula.background, dracula.bodyColor)
    assertEquals(TerminalTheme.Dracula.background, dracula.headerColor)
    assertEquals(TerminalTheme.Dracula.foreground, dracula.titleColor)
  }
}
