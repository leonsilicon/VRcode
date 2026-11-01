package dev.improve.vrcode

enum class WindowMode(val storageValue: String, val label: String, val detail: String) {
  Fixed("fixed", "Fixed", "Stays in the same place in your room"),
  Follow("follow", "Follow", "Stays around you as you walk; looking around does not move it");

  companion object {
    fun fromStorage(value: String?): WindowMode = entries.firstOrNull { it.storageValue == value } ?: Follow
  }
}
