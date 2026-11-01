package dev.improve.vrcode

import androidx.annotation.DrawableRes
import dev.improve.vrcode.settings.R

/**
 * The model provider the AI mic (left fist) talks to, through the Mac. [storageValue] is both the
 * saved preference and the `provider` sent in each AI request; the news microphone's flag shows the
 * provider's logo ([newsMicrophoneAsset]). Each needs its API key added in VRcode Mac on the Mac.
 */
enum class AiProvider(
  val storageValue: String,
  val label: String,
  val company: String,
  @DrawableRes val logo: Int,
  @DrawableRes val newsMicrophonePreview: Int,
) {
  Claude("claude", "Claude", "Anthropic", R.drawable.ai_provider_claude, R.drawable.voice_prop_news_microphone_claude),
  ChatGpt("openai", "ChatGPT", "OpenAI", R.drawable.ai_provider_openai, R.drawable.voice_prop_news_microphone_openai),
  Gemini("gemini", "Gemini", "Google", R.drawable.ai_provider_gemini, R.drawable.voice_prop_news_microphone_gemini);

  val newsMicrophoneAsset: String get() = "voice-props/news-microphone-$storageValue.glb"

  /** The models offered for this provider, fastest first. */
  val models: List<AiModel> get() = AiModel.entries.filter { it.provider == this }

  /** The model used until one is picked, and for a saved model that is no longer offered. */
  val defaultModel: AiModel get() = when (this) {
    Claude -> AiModel.ClaudeSonnet
    ChatGpt -> AiModel.GptSol
    Gemini -> AiModel.GeminiFlash
  }

  /** The preference (and synced settings) key holding this provider's chosen model. */
  val modelKey: String get() = "ai_model_$storageValue"

  /** The provider's agent CLI the Mac runs when the AI mic thinks first ([AiThinking]). */
  val agentCli: String get() = when (this) {
    Claude -> "Claude Code"
    ChatGpt -> "Codex"
    Gemini -> "Gemini CLI"
  }

  companion object {
    fun fromStorage(value: String?): AiProvider = entries.firstOrNull { it.storageValue == value } ?: Claude
  }
}

/**
 * Whether the AI mic thinks before it types, and where its thinking shows. [Above] and [Below] run
 * the provider's agent CLI on the Mac ([AiProvider.agentCli], signed in with its own login) in the
 * target pane's folder: it thinks and may read the project (read-only) before choosing the
 * keystrokes, and its thinking streams onto a panel just above or below the target window. [Off]
 * asks the provider's API once, quickly, with the API key added in VRcode Mac and no panel.
 * [storageValue] is the saved preference and the synced settings value.
 */
enum class AiThinking(val storageValue: String, val label: String, val detail: String) {
  Above("above", "Think, shown above", "Looks at your project and thinks first. Its thinking shows above the window."),
  Below("below", "Think, shown below", "Looks at your project and thinks first. Its thinking shows below the window."),
  Off("off", "Quick answer", "One fast call to the model's API. Sees only the screen; no thinking shown.");

  /** True when the Mac runs the agent CLI (and the panel shows its thinking). */
  val agent: Boolean get() = this != Off

  companion object {
    fun fromStorage(value: String?): AiThinking = entries.firstOrNull { it.storageValue == value } ?: Above
  }
}

/**
 * A model the AI mic can use. Each provider keeps its own choice, so switching providers and back
 * keeps it. [id] is the provider's API model id: the saved preference and the `model` sent in each
 * AI request. VRcode Mac's `AiService.swift` lists the same ids, and uses the provider's default for
 * an id it doesn't know.
 */
enum class AiModel(val provider: AiProvider, val id: String, val label: String, val detail: String) {
  ClaudeHaiku(AiProvider.Claude, "claude-haiku-5-5", "Claude Haiku 5.5", "Fastest and cheapest."),
  ClaudeSonnet(AiProvider.Claude, "claude-sonnet-5-5", "Claude Sonnet 5.5", "Fast and capable. The default."),
  ClaudeOpus(AiProvider.Claude, "claude-opus-5-5", "Claude Opus 5.5", "Smarter, a little slower."),
  ClaudeFable(AiProvider.Claude, "claude-fable-5-1", "Claude Fable 5.1", "Most capable. Slowest and priciest."),
  GptLuna(AiProvider.ChatGpt, "gpt-6-luna", "GPT-6 Luna", "Fastest and cheapest."),
  GptTerra(AiProvider.ChatGpt, "gpt-6.1-terra", "GPT-6.1 Terra", "Fast and efficient."),
  GptSol(AiProvider.ChatGpt, "gpt-6.1-sol", "GPT-6.1 Sol", "Smart and quick. The default."),
  GptAstra(AiProvider.ChatGpt, "gpt-6-astra", "GPT-6 Astra", "Most capable. Slowest and priciest."),
  GeminiFlashLite(AiProvider.Gemini, "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "Fastest and cheapest."),
  GeminiFlash(AiProvider.Gemini, "gemini-3.8-flash", "Gemini 3.8 Flash", "Fast and capable. The default."),
  GeminiArgon(AiProvider.Gemini, "gemini-4-argon", "Gemini 4 Argon", "Most capable. Slowest and priciest.");

  companion object {
    /** [provider]'s saved model, or its default when none is saved or the saved one isn't offered. */
    fun fromStorage(provider: AiProvider, value: String?): AiModel =
      provider.models.firstOrNull { it.id == value } ?: provider.defaultModel
  }
}
