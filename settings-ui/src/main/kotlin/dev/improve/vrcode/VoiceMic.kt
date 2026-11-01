package dev.improve.vrcode

/** The two voice inputs have independent appearances and fixed hand/processing behavior. */
enum class VoiceMic(val label: String, val hand: String, val detail: String, val defaultProp: VoiceProp) {
  Text("Text mic", "right", "Speak with your right fist closed. Your words are transcribed as spoken, ready to paste.", VoiceProp.Microphone),
  Ai("AI mic", "left", "Speak with your left fist closed. AI uses the focused pane to turn your request into text before you paste.", VoiceProp.NewsMicrophone);

  /**
   * The looks this mic offers, in picker order. The news microphone shows the AI provider's logo, so
   * it belongs to the AI mic alone, which wears nothing else for now ([comingSoon]).
   */
  val props: List<VoiceProp>
    get() = if (this == Text) VoiceProp.entries - VoiceProp.NewsMicrophone else listOf(VoiceProp.NewsMicrophone)

  /** Whether the picker ends with a placeholder for looks that are not available yet. It cannot be chosen. */
  val comingSoon: Boolean get() = this == Ai

  /** [prop] if this mic offers it, else this mic's default. */
  fun allowed(prop: VoiceProp): VoiceProp = if (prop in props) prop else defaultProp

  /** The line under the picker, on the Quest and the Mac. */
  val appearanceNote: String
    get() = if (this == Ai) "For now the AI mic always wears the news microphone."
    else "Changes only the ${label.lowercase()}'s appearance. Each mic keeps its own selection."

  fun prop(settings: AppSettings): VoiceProp = if (this == Text) settings.textMicProp else settings.aiMicProp

  fun withProp(settings: AppSettings, prop: VoiceProp): AppSettings =
    if (this == Text) settings.copy(textMicProp = prop) else settings.copy(aiMicProp = prop)

  companion object {
    const val COMING_SOON_VALUE = "coming-soon"
    const val COMING_SOON_LABEL = "Coming soon"
    const val COMING_SOON_DETAIL = "More AI mic looks are on the way."
  }
}
