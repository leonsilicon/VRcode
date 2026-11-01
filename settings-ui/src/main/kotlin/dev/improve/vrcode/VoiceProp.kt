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
  NewsMicrophone("news-microphone", "News microphone", "You're live. Make headlines.", R.drawable.voice_prop_news_microphone),
  Megaphone("megaphone", "Megaphone", "Make an announcement.", R.drawable.voice_prop_megaphone),
  Banana("banana", "Banana Phone", "Your daily serving of dictation.", R.drawable.voice_prop_banana);

  val assetPath: String get() = "voice-props/$storageValue.glb"

  companion object {
    fun fromStorage(value: String?): VoiceProp =
      if (value == "boombox") NewsMicrophone else entries.firstOrNull { it.storageValue == value } ?: Microphone
  }
}
