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

  @Test fun existingInstallsAndUnknownWindowModesKeepWindowsFixed() {
    assertEquals(WindowMode.Fixed, AppSettings().defaultWindowMode)
    assertEquals(WindowMode.Fixed, WindowMode.fromStorage(null))
    assertEquals(WindowMode.Fixed, WindowMode.fromStorage("future-mode"))
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
}
