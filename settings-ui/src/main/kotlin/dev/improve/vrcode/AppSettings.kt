package dev.improve.vrcode

import android.content.SharedPreferences

/** Only options that the app currently implements are offered in Settings. */
data class AppSettings(
  val passthrough: Boolean = true,
  val windowDistanceM: Float = 1.4f,
  val autoConnect: Boolean = true,
  val windowStyle: WindowStyle = WindowStyle.MacOS,
  /** How opaque window backgrounds are; below 1 the room shows through while text stays solid. */
  val windowOpacity: Float = DEFAULT_WINDOW_OPACITY,
  val textMicProp: VoiceProp = VoiceProp.Microphone,
  val aiMicProp: VoiceProp = VoiceProp.NewsMicrophone,
  val aiProvider: AiProvider = AiProvider.Claude,
  /** Each provider's chosen model (see [aiModel]); switching providers keeps the others' choices. */
  val claudeModel: AiModel = AiProvider.Claude.defaultModel,
  val chatGptModel: AiModel = AiProvider.ChatGpt.defaultModel,
  val geminiModel: AiModel = AiProvider.Gemini.defaultModel,
  /** Whether the AI mic runs the provider's agent on the Mac first, and where its thinking shows. */
  val aiThinking: AiThinking = AiThinking.Above,
  val terminalTheme: TerminalTheme = TerminalTheme.Mac,
  val terminalFontSize: Float = DEFAULT_FONT_SIZE,
  val terminalCursor: TerminalCursor = TerminalCursor.Terminal,
  val defaultWindowMode: WindowMode = WindowMode.Follow,
  val defaultTerminalSize: TerminalSize = TerminalSize(),
  val pointerCursor: PointerCursorStyle = PointerCursorStyle.Arrow,
  val pointerCursorSize: Float = DEFAULT_POINTER_SIZE,
  /** The left index-pinch ring: what each of its eight directions sends. */
  val quickInputs: QuickInputRing = QuickInputRing.Default,
) {
  /** The model chosen for [provider] (the AI mic's current provider by default). */
  fun aiModel(provider: AiProvider = aiProvider): AiModel = when (provider) {
    AiProvider.Claude -> claudeModel
    AiProvider.ChatGpt -> chatGptModel
    AiProvider.Gemini -> geminiModel
  }.takeIf { it.provider == provider } ?: provider.defaultModel

  /** Chooses [model] for its provider, leaving the other providers' choices and the provider as they are. */
  fun withAiModel(model: AiModel): AppSettings = when (model.provider) {
    AiProvider.Claude -> copy(claudeModel = model)
    AiProvider.ChatGpt -> copy(chatGptModel = model)
    AiProvider.Gemini -> copy(geminiModel = model)
  }

  fun sanitized(): AppSettings = copy(
    claudeModel = aiModel(AiProvider.Claude),
    chatGptModel = aiModel(AiProvider.ChatGpt),
    geminiModel = aiModel(AiProvider.Gemini),
    defaultTerminalSize = defaultTerminalSize.sanitized(),
    windowDistanceM = if (windowDistanceM.isFinite()) windowDistanceM.coerceIn(MIN_DISTANCE, MAX_DISTANCE) else 1.4f,
    windowOpacity = sanitizeWindowOpacity(windowOpacity) ?: DEFAULT_WINDOW_OPACITY,
    terminalFontSize = if (terminalFontSize.isFinite()) terminalFontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE) else DEFAULT_FONT_SIZE,
    pointerCursorSize = if (pointerCursorSize.isFinite()) pointerCursorSize.coerceIn(MIN_POINTER_SIZE, MAX_POINTER_SIZE) else DEFAULT_POINTER_SIZE,
    quickInputs = quickInputs.sanitized(),
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
    const val DEFAULT_WINDOW_OPACITY = 0.8f
    const val MIN_WINDOW_OPACITY = 0.3f
    const val MAX_WINDOW_OPACITY = 1f
    /** Opacity moves in 5% steps. */
    const val WINDOW_OPACITY_STEP = 0.05f

    /**
     * [opacity] clamped to the offered range and snapped to a 5% step (dividing, so 0.7 comes out
     * exactly 0.7f rather than 0.70000005), or null when it is not a number.
     */
    fun sanitizeWindowOpacity(opacity: Float): Float? = opacity.takeIf { it.isFinite() }
      ?.let { kotlin.math.round(it * 20f) / 20f }
      ?.coerceIn(MIN_WINDOW_OPACITY, MAX_WINDOW_OPACITY)
  }
}

class SettingsStore(private val preferences: SharedPreferences) {
  fun load(): AppSettings = AppSettings(
    passthrough = preferences.getBoolean("passthrough", true),
    windowDistanceM = preferences.getFloat("window_distance_m", 1.4f),
    autoConnect = preferences.getBoolean("auto_connect", true),
    windowStyle = WindowStyle.fromStorage(preferences.getString("window_style", null)),
    windowOpacity = preferences.getFloat("window_opacity", AppSettings.DEFAULT_WINDOW_OPACITY),
    textMicProp =VoiceProp.fromStorage(preferences.getString("text_mic_prop", null)),
    // The old setting only rendered on the left hand. Keep custom props, while upgrading
    // its old classic default (also saved by unrelated settings changes) to the AI default.
    aiMicProp = VoiceProp.fromStorage(
      preferences.getString("ai_mic_prop", null)
        ?: preferences.getString("voice_prop", null)?.takeUnless { it == "microphone" },
      fallback = VoiceProp.NewsMicrophone,
    ),
    aiProvider = AiProvider.fromStorage(preferences.getString("ai_provider", null)),
    claudeModel = AiModel.fromStorage(AiProvider.Claude, preferences.getString(AiProvider.Claude.modelKey, null)),
    chatGptModel = AiModel.fromStorage(AiProvider.ChatGpt, preferences.getString(AiProvider.ChatGpt.modelKey, null)),
    geminiModel = AiModel.fromStorage(AiProvider.Gemini, preferences.getString(AiProvider.Gemini.modelKey, null)),
    aiThinking = AiThinking.fromStorage(preferences.getString("ai_thinking", null)),
    terminalTheme = TerminalTheme.fromStorage(preferences.getString("terminal_theme", null)),
    terminalFontSize = preferences.getFloat("terminal_font_size", AppSettings.DEFAULT_FONT_SIZE),
    terminalCursor = TerminalCursor.fromStorage(preferences.getString("terminal_cursor", null)),
    defaultWindowMode = WindowMode.fromStorage(preferences.getString("default_window_mode", null)),
    defaultTerminalSize = TerminalSize(
      preferences.getInt("default_terminal_cols", TerminalSize.DEFAULT_COLS),
      preferences.getInt("default_terminal_rows", TerminalSize.DEFAULT_ROWS),
    ),
    pointerCursor = PointerCursorStyle.fromStorage(preferences.getString("pointer_cursor", null)),
    pointerCursorSize = preferences.getFloat("pointer_cursor_size", AppSettings.DEFAULT_POINTER_SIZE),
    quickInputs = QuickInputRing.fromStorage(preferences.getString("quick_inputs", null)),
  ).sanitized()

  fun save(settings: AppSettings) {
    val s = settings.sanitized()
    preferences.edit()
      .putBoolean("passthrough", s.passthrough)
      .putFloat("window_distance_m", s.windowDistanceM)
      .putBoolean("auto_connect", s.autoConnect)
      .putString("window_style", s.windowStyle.storageValue)
      .putFloat("window_opacity", s.windowOpacity)
      .putString("text_mic_prop", s.textMicProp.storageValue)
      .putString("ai_mic_prop", s.aiMicProp.storageValue)
      .remove("voice_prop")
      .putString("ai_provider", s.aiProvider.storageValue)
      .putString(AiProvider.Claude.modelKey, s.claudeModel.id)
      .putString(AiProvider.ChatGpt.modelKey, s.chatGptModel.id)
      .putString(AiProvider.Gemini.modelKey, s.geminiModel.id)
      .putString("ai_thinking", s.aiThinking.storageValue)
      .putString("terminal_theme", s.terminalTheme.storageValue)
      .putFloat("terminal_font_size", s.terminalFontSize)
      .putString("terminal_cursor", s.terminalCursor.storageValue)
      .putString("default_window_mode", s.defaultWindowMode.storageValue)
      .putInt("default_terminal_cols", s.defaultTerminalSize.cols)
      .putInt("default_terminal_rows", s.defaultTerminalSize.rows)
      .putString("pointer_cursor", s.pointerCursor.storageValue)
      .putFloat("pointer_cursor_size", s.pointerCursorSize)
      .putString("quick_inputs", s.quickInputs.storageValue)
      .apply()
  }
}

