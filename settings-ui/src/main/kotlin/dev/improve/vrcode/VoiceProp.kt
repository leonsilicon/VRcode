package dev.improve.vrcode

import androidx.annotation.DrawableRes
import dev.improve.vrcode.settings.R

/** Stable preference IDs and bundled assets shared by the picker and the VR renderer. */
enum class VoiceProp(
  val storageValue: String,
  val label: String,
  val detail: String,
  @DrawableRes val preview: Int,
) {
  Microphone("microphone", "Classic microphone", "A silver grille and cyan ring.", R.drawable.voice_prop_microphone),
  NewsMicrophone("news-microphone", "News microphone", "Your AI provider's logo on the cube.", R.drawable.voice_prop_news_microphone_claude),
  WalkieTalkie("walkie-talkie", "Walkie-talkie", "Breaker, breaker. Come in.", R.drawable.voice_prop_walkie_talkie),
  Megaphone("megaphone", "Megaphone", "Make an announcement.", R.drawable.voice_prop_megaphone),
  Banana("banana", "Banana Phone", "Your daily serving of dictation.", R.drawable.voice_prop_banana);

  /** The model to load; the news microphone's flag shows the chosen AI [provider]'s logo. */
  fun assetPath(provider: AiProvider): String =
    if (this == NewsMicrophone) provider.newsMicrophoneAsset else "voice-props/$storageValue.glb"

  /** The picker image, matching [assetPath]. */
  @DrawableRes fun preview(provider: AiProvider): Int = if (this == NewsMicrophone) provider.newsMicrophonePreview else preview

  companion object {
    fun fromStorage(value: String?, fallback: VoiceProp = Microphone): VoiceProp =
      if (value == "boombox") NewsMicrophone else entries.firstOrNull { it.storageValue == value } ?: fallback
  }
}
