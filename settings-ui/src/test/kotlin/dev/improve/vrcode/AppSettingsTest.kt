package dev.improve.vrcode

import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {
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
