package dev.improve.vrcode

/** The two voice inputs have independent appearances and fixed hand/processing behavior. */
enum class VoiceMic(val label: String, val hand: String, val detail: String, val defaultProp: VoiceProp) {
  Text("Text mic", "right", "Speak with your right fist closed. Your words are transcribed as spoken, ready to paste.", VoiceProp.Microphone),
  Ai("AI mic", "left", "Speak with your left fist closed. AI uses the focused pane to turn your request into text before you paste.", VoiceProp.NewsMicrophone);

  fun prop(settings: AppSettings): VoiceProp = if (this == Text) settings.textMicProp else settings.aiMicProp

  fun withProp(settings: AppSettings, prop: VoiceProp): AppSettings =
    if (this == Text) settings.copy(textMicProp = prop) else settings.copy(aiMicProp = prop)
}
