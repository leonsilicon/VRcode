package dev.improve.vrcode

enum class WindowStyle(val storageValue: String, val label: String, val detail: String) {
  MacOS("macos", "macOS", "Traffic lights on the left"),
  Windows("windows", "Windows", "Window controls on the right");

  companion object {
    fun fromStorage(value: String?): WindowStyle = entries.firstOrNull { it.storageValue == value } ?: MacOS
  }
}
