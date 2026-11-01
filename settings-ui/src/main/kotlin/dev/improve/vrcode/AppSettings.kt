package dev.improve.vrcode

import android.content.SharedPreferences

/** Only options that the app currently implements are offered in Settings. */
data class AppSettings(
  val passthrough: Boolean = true,
  val windowDistanceM: Float = 1.4f,
  val autoConnect: Boolean = true,
  val windowStyle: WindowStyle = WindowStyle.MacOS,
  val textMicProp: VoiceProp = VoiceProp.Microphone,
  val aiMicProp: VoiceProp = VoiceProp.NewsMicrophone,
  val terminalTheme: TerminalTheme = TerminalTheme.Mac,
  val terminalFontSize: Float = DEFAULT_FONT_SIZE,
  val terminalCursor: TerminalCursor = TerminalCursor.Terminal,
  val defaultWindowMode: WindowMode = WindowMode.Fixed,
  val pointerCursor: PointerCursorStyle = PointerCursorStyle.Arrow,
  val pointerCursorSize: Float = DEFAULT_POINTER_SIZE,
) {
  fun sanitized(): AppSettings = copy(
    windowDistanceM = if (windowDistanceM.isFinite()) windowDistanceM.coerceIn(MIN_DISTANCE, MAX_DISTANCE) else 1.4f,
    terminalFontSize = if (terminalFontSize.isFinite()) terminalFontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE) else DEFAULT_FONT_SIZE,
    pointerCursorSize = if (pointerCursorSize.isFinite()) pointerCursorSize.coerceIn(MIN_POINTER_SIZE, MAX_POINTER_SIZE) else DEFAULT_POINTER_SIZE,
  )

  companion object {
    const val MIN_DISTANCE = 0.8f
    const val MAX_DISTANCE = 2.5f
    const val DEFAULT_FONT_SIZE = 25f
    const val MIN_FONT_SIZE = 18f
    const val MAX_FONT_SIZE = 36f
    const val DEFAULT_POINTER_SIZE = 1f
    const val MIN_POINTER_SIZE = 0.75f
    const val MAX_POINTER_SIZE = 2f
  }
}

class SettingsStore(private val preferences: SharedPreferences) {
  fun load(): AppSettings = AppSettings(
    passthrough = preferences.getBoolean("passthrough", true),
    windowDistanceM = preferences.getFloat("window_distance_m", 1.4f),
    autoConnect = preferences.getBoolean("auto_connect", true),
    windowStyle = WindowStyle.fromStorage(preferences.getString("window_style", null)),
    textMicProp = VoiceProp.fromStorage(preferences.getString("text_mic_prop", null)),
    // The old setting only rendered on the left hand. Keep custom props, while upgrading
    // its old classic default (also saved by unrelated settings changes) to the AI default.
    aiMicProp = VoiceProp.fromStorage(
      preferences.getString("ai_mic_prop", null)
        ?: preferences.getString("voice_prop", null)?.takeUnless { it == "microphone" },
      fallback = VoiceProp.NewsMicrophone,
    ),
    terminalTheme = TerminalTheme.fromStorage(preferences.getString("terminal_theme", null)),
    terminalFontSize = preferences.getFloat("terminal_font_size", AppSettings.DEFAULT_FONT_SIZE),
    terminalCursor = TerminalCursor.fromStorage(preferences.getString("terminal_cursor", null)),
    defaultWindowMode = WindowMode.fromStorage(preferences.getString("default_window_mode", null)),
    pointerCursor = PointerCursorStyle.fromStorage(preferences.getString("pointer_cursor", null)),
    pointerCursorSize = preferences.getFloat("pointer_cursor_size", AppSettings.DEFAULT_POINTER_SIZE),
  ).sanitized()

  fun save(settings: AppSettings) {
    val s = settings.sanitized()
    preferences.edit()
      .putBoolean("passthrough", s.passthrough)
      .putFloat("window_distance_m", s.windowDistanceM)
      .putBoolean("auto_connect", s.autoConnect)
      .putString("window_style", s.windowStyle.storageValue)
      .putString("text_mic_prop", s.textMicProp.storageValue)
      .putString("ai_mic_prop", s.aiMicProp.storageValue)
      .remove("voice_prop")
      .putString("terminal_theme", s.terminalTheme.storageValue)
      .putFloat("terminal_font_size", s.terminalFontSize)
      .putString("terminal_cursor", s.terminalCursor.storageValue)
      .putString("default_window_mode", s.defaultWindowMode.storageValue)
      .putString("pointer_cursor", s.pointerCursor.storageValue)
      .putFloat("pointer_cursor_size", s.pointerCursorSize)
      .apply()
  }
}

