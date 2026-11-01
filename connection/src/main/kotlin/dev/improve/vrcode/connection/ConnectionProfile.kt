package dev.improve.vrcode.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ConnectionMode(val title: String, val detail: String) {
  Auto("Automatic (recommended)", "Prefer secure local Wi-Fi. Fall back to an available cable or configured Bluetooth connection."),
  LocalWiFi("Local Wi-Fi", "Connect both devices to the same network."),
  MacHotspot("Mac Wi-Fi sharing", "Join the Wi-Fi network created by Internet Sharing on your Mac."),
  PhoneHotspot("Phone hotspot", "Join both devices to a hotspot that allows connections between them."),
  WiFiDirect("Wi-Fi Direct", "Create a Quest network and join it from your Mac. Availability depends on Horizon OS."),
  BluetoothClassic("Bluetooth", "Use the Mac's Bluetooth connection. Pair in system settings first."),
  BluetoothLE("Bluetooth Low Energy", "A slower fallback for light terminal use. Large screens and dictation can be delayed."),
  BluetoothPAN("Bluetooth network", "Use an existing Bluetooth PAN network. Requires OS support on both devices."),
  WiFiADB("Wi-Fi debugging", "Use the wireless debugging connection configured on this Mac."),
  USB("USB", "Use the cable connection configured on this Mac."),
}

data class ConnectionProfile(
  val mode: ConnectionMode = ConnectionMode.Auto,
  val host: String = "",
  val port: Int = 7274,
  val fingerprint: String = "",
  val token: String = "",
  val name: String = "Mac",
  val classicAddress: String = "",
  val bleAddress: String = "",
  /** Every address the Mac reported when its key was created. */
  val hosts: List<String> = emptyList(),
  /** The address of the last successful network connection, tried first next time. */
  val lastHost: String = "",
) {
  val paired: Boolean get() = fingerprint.matches(Regex("[a-fA-F0-9]{64}")) && runCatching { Base64.decode(token, Base64.DEFAULT).size == 32 }.getOrDefault(false)

  /**
   * Addresses for a network route, most likely first. Discovery (matched by certificate
   * fingerprint) is freshest; an explicitly chosen mode keeps the entered address first.
   * Trying stale candidates is safe: the certificate pin rejects any other machine.
   */
  fun candidateHosts(discovered: List<String>): List<String> =
    (if (mode == ConnectionMode.Auto) discovered + lastHost + host + hosts else listOf(host) + discovered + lastHost + hosts)
      .map(String::trim).filter(String::isNotBlank).distinct()

  fun importMac(value: String): ConnectionProfile {
    require(value.trim().startsWith("vrcode:")) { "Paste the Mac connection key starting with vrcode:." }
    val json = JSONObject(String(Base64.decode(value.trim().removePrefix("vrcode:"), Base64.DEFAULT), Charsets.UTF_8))
    val listed = json.optJSONArray("hosts")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
    val updated = copy(host = json.getString("host"), port = json.getInt("port"),
      fingerprint = json.getString("fingerprint"), token = json.getString("token"), name = json.optString("name", "Mac"),
      classicAddress = json.optString("bluetooth", classicAddress),
      hosts = listed.map(String::trim).filter(String::isNotBlank).distinct().take(8),
      lastHost = if (json.getString("fingerprint").equals(fingerprint, ignoreCase = true)) lastHost else "")
    require(updated.paired && updated.host.isNotBlank() && updated.port in 1..65535) { "The Mac connection key is invalid." }
    return updated
  }
}

/** The bearer key is encrypted using a non-exportable Android Keystore key. */
class ConnectionStore(context: Context) {
  private val preferences = context.getSharedPreferences("vrcode_connection", Context.MODE_PRIVATE)
  private fun key(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey("vrcode.connection", null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
      init(KeyGenParameterSpec.Builder("vrcode.connection", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
  }

  fun load(): ConnectionProfile = runCatching {
    val saved = preferences.getString("profile", null) ?: return ConnectionProfile()
    val parts = saved.split(":")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
    val json = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
    ConnectionProfile(mode = runCatching { ConnectionMode.valueOf(json.optString("mode")) }.getOrDefault(ConnectionMode.Auto),
      host = json.optString("host"), port = json.optInt("port", 7274), fingerprint = json.optString("fingerprint"),
      token = json.optString("token"), name = json.optString("name", "Mac"),
      classicAddress = json.optString("classic"), bleAddress = json.optString("ble"),
      hosts = json.optJSONArray("hosts")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty(),
      lastHost = json.optString("lastHost"))
  }.getOrDefault(ConnectionProfile())

  fun save(profile: ConnectionProfile) {
    val json = JSONObject().put("mode", profile.mode.name).put("host", profile.host).put("port", profile.port)
      .put("fingerprint", profile.fingerprint).put("token", profile.token).put("name", profile.name)
      .put("classic", profile.classicAddress).put("ble", profile.bleAddress)
      .put("hosts", org.json.JSONArray(profile.hosts)).put("lastHost", profile.lastHost)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
    val encrypted = cipher.doFinal(json.toString().toByteArray())
    preferences.edit().putString("profile", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
  }
}
