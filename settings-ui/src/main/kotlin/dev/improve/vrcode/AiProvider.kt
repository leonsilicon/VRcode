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

  companion object {
    fun fromStorage(value: String?): AiProvider = entries.firstOrNull { it.storageValue == value } ?: Claude
  }
}
