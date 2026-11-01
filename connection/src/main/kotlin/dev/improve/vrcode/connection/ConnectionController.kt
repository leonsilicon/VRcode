package dev.improve.vrcode.connection

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.p2p.WifiP2pManager
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.CancellationException

data class LinkStatus(val title: String = "Not connected", val detail: String = "", val connected: Boolean = false, val busy: Boolean = false)
data class FoundMac(val name: String, val host: String, val port: Int)

class ConnectionController(
  private val activity: Activity,
  private val scope: CoroutineScope,
  private val onConnected: (Int, Int) -> Unit,
  private val onDisconnected: () -> Unit,
) : Closeable {
  private val store = ConnectionStore(activity)
  private val mutableProfile = MutableStateFlow(store.load())
  val profile = mutableProfile.asStateFlow()
  private val mutableStatus = MutableStateFlow(LinkStatus())
  val status = mutableStatus.asStateFlow()
  private val mutableMacs = MutableStateFlow<List<FoundMac>>(emptyList())
  val macs = mutableMacs.asStateFlow()
  private val mutableSetup = MutableStateFlow("")
  val setup = mutableSetup.asStateFlow()
  private var job: Job? = null
  private var live: RelayTunnel? = null
  private var generation = 0
  private val nsd = activity.getSystemService(NsdManager::class.java)
  private var discovery: NsdManager.DiscoveryListener? = null
  private val p2p = activity.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
  private var p2pChannel: WifiP2pManager.Channel? = null
  private var ownsGroup = false

  fun update(value: ConnectionProfile) { store.save(value); mutableProfile.value = value }
  fun importMac(value: String): String? = runCatching { update(profile.value.importMac(value)); null }.getOrElse { it.message ?: "Invalid Mac connection key" }
  fun importFromConnectedMac(): String? = runCatching {
    val file = java.io.File(activity.filesDir, "vrcode-pairing")
    check(file.isFile) { "On the Mac, run bun scripts/quest-link.ts pair while this app is installed, then choose Import from connected Mac." }
    val error = importMac(file.readText())
    if (error == null) file.delete()
    error
  }.getOrElse { it.message ?: "Could not import the Mac connection key" }

  fun connect() {
    disconnect()
    val selected = profile.value
    if (!selected.paired) { mutableStatus.value = LinkStatus("Add your Mac first", "Paste the connection key from the Mac service."); return }
    val thisGeneration = generation
    job = scope.launch {
      var retry = 0
      while (isActive && thisGeneration == generation) {
        val attempts = if (selected.mode == ConnectionMode.Auto) buildList {
          add(ConnectionMode.LocalWiFi)
          if (ownsGroup) add(ConnectionMode.WiFiDirect)
          add(ConnectionMode.USB); add(ConnectionMode.WiFiADB)
          val networks = activity.getSystemService(ConnectivityManager::class.java)
          if (networks.allNetworks.any { networks.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true }) {
            add(ConnectionMode.BluetoothPAN)
          }
          if (selected.classicAddress.isNotBlank()) add(ConnectionMode.BluetoothClassic)
          if (selected.bleAddress.isNotBlank()) add(ConnectionMode.BluetoothLE)
        } else listOf(selected.mode)
        var connected = false
        val failures = mutableListOf<String>()
        for (mode in attempts) {
          ensureActive()
          var tunnel: RelayTunnel? = null
          try {
            mutableStatus.value = LinkStatus("Connecting via ${mode.title}", "${selected.name} · attempt ${retry + 1}", busy = true)
            val ended = CompletableDeferred<String>()
            // Native Bluetooth calls are bounded. Always reclaim a result even if cancelled
            // while a platform connect call was in progress.
            tunnel = withContext(Dispatchers.IO + NonCancellable) {
              var carrier: CarrierSocket? = null
              try {
                val connectivity = activity.getSystemService(ConnectivityManager::class.java)
                val network = when (mode) {
                  ConnectionMode.BluetoothPAN -> connectivity.allNetworks.firstOrNull { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true }
                    ?: error("No Bluetooth network is connected. Horizon OS must expose PAN in its system settings; otherwise choose Bluetooth instead.")
                  ConnectionMode.LocalWiFi, ConnectionMode.MacHotspot, ConnectionMode.PhoneHotspot -> connectivity.allNetworks.firstOrNull {
                    connectivity.getNetworkCapabilities(it)?.let { caps -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
                  } ?: error("Join the selected Wi-Fi network on the Quest first.")
                  ConnectionMode.WiFiDirect -> { check(ownsGroup) { "Create the Quest network and join it from your Mac first." }; null }
                  else -> null
                }
                val endpoint = when (mode) {
                  ConnectionMode.USB -> "127.0.0.1" to 17274
                  ConnectionMode.WiFiADB -> "127.0.0.1" to 27274
                  ConnectionMode.BluetoothClassic -> {
                    carrier = CarrierSocket(BluetoothCarriers.classic(activity, selected.classicAddress)); "127.0.0.1" to carrier.port
                  }
                  ConnectionMode.BluetoothLE -> {
                    carrier = CarrierSocket(BluetoothCarriers.ble(activity, selected.bleAddress)); "127.0.0.1" to carrier.port
                  }
                  else -> {
                    val found = macs.value.firstOrNull { it.name == selected.name }
                    val useDiscovery = selected.mode == ConnectionMode.Auto || selected.host.isBlank()
                    (if (useDiscovery) found?.host ?: selected.host else selected.host).also {
                      require(it.isNotBlank()) { "Enter your Mac address or discover it on the network." }
                    } to (if (useDiscovery) found?.port ?: selected.port else selected.port)
                  }
                }
                RelayTunnel.connect(selected, endpoint.first, endpoint.second, carrier, network) { ended.complete(it) }
              } catch (error: Exception) { carrier?.close(); throw error }
            }
            ensureActive()
            if (thisGeneration != generation) break
            live = tunnel
            tunnel.start()
            onConnected(tunnel.bridgePort, tunnel.speechPort)
            connected = true
            val label = mode.title
            mutableStatus.value = LinkStatus("Connected via $label", "${selected.name} · encrypted and authenticated", connected = true)
            retry = 0
            val reason = ended.await()
            if (thisGeneration == generation) { onDisconnected(); mutableStatus.value = LinkStatus("Connection lost", reason, busy = true) }
            break
          } catch (error: CancellationException) { throw error }
          catch (error: Exception) { failures += "${mode.title}: ${error.cause?.message ?: error.message ?: "unavailable"}" }
          finally { tunnel?.close(); if (live === tunnel) live = null }
        }
        ensureActive()
        if (!connected) mutableStatus.value = LinkStatus("Could not connect", failures.joinToString("\n"), busy = true)
        retry++
        delay(minOf(5000L, retry * 1000L))
      }
    }
  }

  fun disconnect() {
    generation++; job?.cancel(); job = null; live?.close(); live = null
    onDisconnected(); mutableStatus.value = LinkStatus()
  }

  @Suppress("DEPRECATION")
  fun discover() {
    if (discovery != null || nsd == null) return
    val listener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(type: String) { }
      override fun onDiscoveryStopped(type: String) { discovery = null }
      override fun onStartDiscoveryFailed(type: String, code: Int) { discovery = null }
      override fun onStopDiscoveryFailed(type: String, code: Int) { discovery = null }
      override fun onServiceLost(service: NsdServiceInfo) { mutableMacs.value = mutableMacs.value.filterNot { it.name == service.serviceName } }
      override fun onServiceFound(service: NsdServiceInfo) {
        runCatching { nsd.resolveService(service, object : NsdManager.ResolveListener {
          override fun onResolveFailed(info: NsdServiceInfo, code: Int) { }
          override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            mutableMacs.value = mutableMacs.value.filterNot { it.name == info.serviceName } + FoundMac(info.serviceName, host, info.port)
          }
        }) }
      }
    }
    discovery = listener
    runCatching { nsd.discoverServices("_vrcode._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { discovery = null }
  }

  fun requestBluetooth() { activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 42) }
  fun pairedBluetooth(): List<Pair<String, String>> = runCatching { BluetoothCarriers.paired(activity) }.getOrDefault(emptyList())
  fun openNetworkSettings(bluetooth: Boolean = false) {
    val intent = Intent(if (bluetooth) Settings.ACTION_BLUETOOTH_SETTINGS else Settings.ACTION_WIFI_SETTINGS)
    runCatching { activity.startActivity(intent) }.onFailure {
      mutableSetup.value = "Open the Quest's quick settings to change ${if (bluetooth) "Bluetooth" else "Wi-Fi"}; this firmware does not expose the Android settings page."
    }
  }

  @SuppressLint("MissingPermission")
  fun createDirectNetwork() {
    if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) || p2p == null) {
      mutableSetup.value = "Wi-Fi Direct is not supported by this device."; return
    }
    if (activity.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
      activity.requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 43)
      mutableSetup.value = "Allow Nearby Wi-Fi devices, then choose Create Quest network again."; return
    }
    val channel = p2pChannel ?: p2p.initialize(activity, activity.mainLooper) { mutableSetup.value = "Wi-Fi Direct became unavailable." }.also { p2pChannel = it }
    mutableSetup.value = "Creating a Quest Wi-Fi network…"
    p2p.createGroup(channel, object : WifiP2pManager.ActionListener {
      override fun onSuccess() {
        ownsGroup = true
        p2p.requestGroupInfo(channel) { group ->
          mutableSetup.value = if (group == null) "Network requested. Check Quest Wi-Fi settings for the group details." else
            "On the Mac, join Wi-Fi ${group.networkName}\nPassword: ${group.passphrase}\nThen discover or enter the Mac address and connect. This network may not provide internet."
        }
      }
      override fun onFailure(reason: Int) { mutableSetup.value = "Quest could not create a Wi-Fi Direct network (code $reason). Use a shared Wi-Fi network or hotspot; some Horizon OS versions restrict this API." }
    })
  }

  @SuppressLint("MissingPermission")
  fun removeDirectNetwork() {
    if (ownsGroup && p2pChannel != null) {
      p2p?.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
        override fun onSuccess() { ownsGroup = false; mutableSetup.value = "Quest network removed." }
        override fun onFailure(reason: Int) { mutableSetup.value = "Could not remove the Quest network (code $reason)." }
      })
    }
  }

  override fun close() {
    disconnect(); discovery?.let { runCatching { nsd?.stopServiceDiscovery(it) } }; discovery = null
    val channel = p2pChannel
    p2pChannel = null
    if (ownsGroup && channel != null && p2p != null) {
      // Keep the channel alive until removal is acknowledged. Closing it first
      // can cancel cleanup and leave an app-created access point running.
      @SuppressLint("MissingPermission")
      fun remove() = p2p.removeGroup(channel, object : WifiP2pManager.ActionListener {
        override fun onSuccess() { ownsGroup = false; channel.close() }
        override fun onFailure(reason: Int) { channel.close() }
      })
      runCatching { remove() }.onFailure { channel.close() }
    } else channel?.close()
  }
}
