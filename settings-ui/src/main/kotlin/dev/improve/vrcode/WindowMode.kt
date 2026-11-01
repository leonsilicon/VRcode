package dev.improve.vrcode

enum class WindowMode(val storageValue: String, val label: String, val detail: String) {
  Fixed("fixed", "Fixed", "Stays in the same place in your room"),
  Follow("follow", "Follow", "Stays in your viewport as you move and look around");

  companion object {
    fun fromStorage(value: String?): WindowMode = entries.firstOrNull { it.storageValue == value } ?: Fixed
  }
}
