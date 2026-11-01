package dev.improve.vrcode.connection

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.p2p.WifiP2pManager
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** [attention] marks a problem the user must fix on a device; the app shows the Connection page for it. */
data class LinkStatus(val title: String = "Not connected", val detail: String = "", val connected: Boolean = false, val busy: Boolean = false,
                      val attention: Boolean = false)

/** No network of the kind the route needs (Wi-Fi, Bluetooth PAN) is connected on the Quest. */
private const val TAG = "VrcodeConnection"

private class MissingNetwork(message: String) : IllegalStateException(message)
/** Every address of one route failed. [identity] is set when a Mac answered but was not the paired one or refused the key. */
private class RouteFailure(message: String, val identity: Boolean) : java.io.IOException(message)

/** A Mac answered but its certificate or our key did not match: retrying will not fix it. */
private fun Throwable.isIdentityFailure(): Boolean = this is RouteFailure && identity ||
  generateSequence(this) { it.cause }.any { it is java.security.cert.CertificateException || it is javax.net.ssl.SSLPeerUnverifiedException ||
    it.message?.let { text -> "did not authorize" in text || "not your paired Mac" in text } == true }

/** The status after a full round of failed routes. */
internal fun failureStatus(macName: String, automatic: Boolean, failures: List<String>, identity: Boolean, missingWifi: Boolean, missingNetwork: Boolean,
                           bluetooth: Boolean = false): LinkStatus {
  val details = failures.joinToString("\n")
  return when {
    identity -> LinkStatus("Your Mac's connection key has changed",
      "A Mac answered but did not accept this Quest's key. Pair again: paste the Mac's current connection key below, " +
        "or run bun scripts/quest-link.ts pair.\n\n$details", busy = true, attention = true)
    automatic && missingWifi && bluetooth -> LinkStatus("Make sure the VRcode Mac app is running",
      "There is no Wi-Fi, and $macName did not answer over Bluetooth. Keep the Mac nearby with Bluetooth on and the VRcode " +
        "Mac app running; the Quest connects automatically. Joining Wi-Fi also works, and is faster.\n\n$details", busy = true, attention = true)
    automatic && missingWifi -> LinkStatus("Connect the Quest to Wi-Fi",
      "VRcode reaches $macName over Wi-Fi and connects automatically once the Quest joins a network.\n\n$details", busy = true, attention = true)
    !automatic && missingNetwork -> LinkStatus("Could not connect", details, busy = true, attention = true)
    else -> LinkStatus("Make sure the VRcode Mac app is running",
      "Open VRcode Mac on $macName. The Quest connects automatically as soon as it starts. " +
        "If it is already running, check that both devices are on the same network.\n\n$details", busy = true, attention = true)
  }
}
/** A Bonjour `_vrcode._tcp` advert. [fingerprint] comes from its TXT record (absent on older Macs). */
data class FoundMac(val name: String, val host: String, val port: Int, val fingerprint: String? = null)

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
  private val wake = Channel<Unit>(Channel.CONFLATED)
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private var provisioning: Job? = null

  fun update(value: ConnectionProfile) { store.save(value); mutableProfile.value = value }
  fun importMac(value: String): String? = runCatching { update(profile.value.importMac(value)); null }.getOrElse { it.message ?: "Invalid Mac connection key" }
  fun importFromConnectedMac(): String? = runCatching {
    val file = java.io.File(activity.filesDir, "vrcode-pairing")
    check(file.isFile) { "On the Mac, run bun scripts/quest-link.ts pair while this app is installed, then choose Import from connected Mac." }
    val error = importMac(file.readText())
    if (error == null) file.delete()
    error
  }.getOrElse { it.message ?: "Could not import the Mac connection key" }

  /**
   * App launch: start discovery and network-change handling, import a key provisioned by
   * `quest-link pair` (debug builds), then connect if a Mac is paired and [autoConnect] allows it.
   */
  fun start(autoConnect: Boolean) {
    discover()
    watchNetworks()
    if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) watchProvisionedKey()
    if (autoConnect && profile.value.paired) connect()
  }

  /** Retry now instead of after the backoff, e.g. when Wi-Fi returns or the app resumes. */
  fun kick() { wake.trySend(Unit) }

  // Only this app, or `run-as` from an ADB-authorized Mac on a debuggable build, can write the
  // provisioning file, so importing it without a tap does not widen who can pair.
  private fun watchProvisionedKey() {
    if (provisioning != null) return
    provisioning = scope.launch {
      val file = java.io.File(activity.filesDir, "vrcode-pairing")
      var rejected = -1L
      while (isActive) {
        val stamp = withContext(Dispatchers.IO) { if (file.isFile) file.lastModified() else -1L }
        if (stamp >= 0 && stamp != rejected) {
          val before = profile.value
          val error = importFromConnectedMac()
          val after = profile.value
          if (error != null) { rejected = stamp; mutableStatus.value = LinkStatus("Could not import the Mac connection key", error, attention = true) }
          else if (!(status.value.connected && before.fingerprint == after.fingerprint && before.token == after.token)) connect()
        }
        delay(2000)
      }
    }
  }

  private fun watchNetworks() {
    if (networkCallback != null) return
    val connectivity = activity.getSystemService(ConnectivityManager::class.java) ?: return
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) { activity.runOnUiThread { discover(); kick() } }
    }
    runCatching {
      connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
      networkCallback = callback
    }
  }

  /** Adverts from the paired Mac: matched by certificate fingerprint, or by name for older Macs. */
  private fun discovered(selected: ConnectionProfile): List<FoundMac> = macs.value.filter { found ->
    found.fingerprint?.equals(selected.fingerprint, ignoreCase = true) ?: (found.name == selected.name)
  }

  /**
   * Opens a tunnel to the first endpoint that authenticates. Several stale addresses would
   * otherwise each cost a connect timeout, so attempts start 300 ms apart and run concurrently.
   */
  private fun connectFirst(selected: ConnectionProfile, endpoints: List<Pair<String, Int>>, carrier: CarrierSocket?,
                           network: Network?, onClosed: (String) -> Unit): Pair<RelayTunnel, String> {
    if (endpoints.size == 1) endpoints[0].let { (host, port) -> return RelayTunnel.connect(selected, host, port, carrier, network, onClosed) to host }
    val pool = Executors.newFixedThreadPool(endpoints.size)
    val attempts = ExecutorCompletionService<Pair<RelayTunnel, String>>(pool)
    endpoints.forEachIndexed { index, (host, port) ->
      attempts.submit {
        if (index > 0) Thread.sleep(index * 300L)
        try { RelayTunnel.connect(selected, host, port, null, network, onClosed) to host }
        catch (error: Exception) { throw IOException("$host: ${error.cause?.message ?: error.message ?: "unavailable"}", error) }
      }
    }
    pool.shutdown()
    val errors = mutableListOf<String>()
    var identity = false
    for (remaining in endpoints.size downTo 1) {
      val result = runCatching { attempts.take().get() }
      result.getOrNull()?.let { winner ->
        // Unstarted tunnels never report onClosed; close any slower duplicates quietly.
        if (remaining > 1) thread(isDaemon = true, name = "vrcode-relay-losers") { repeat(remaining - 1) { runCatching { attempts.take().get().first.close() } } }
        return winner
      }
      result.exceptionOrNull()?.let { (it as? ExecutionException)?.cause ?: it }?.let { error ->
        errors += error.message ?: "unavailable"
        identity = identity || error.isIdentityFailure()
      }
    }
    throw RouteFailure(errors.joinToString("; "), identity)
  }

  fun connect() {
    disconnect()
    val selected = profile.value
    if (!selected.paired) { mutableStatus.value = LinkStatus("Add your Mac first", "Paste the connection key from the Mac service."); return }
    val thisGeneration = generation
    job = scope.launch {
      var retry = 0
      while (isActive && thisGeneration == generation) {
        // Automatic needs no Bluetooth setup: a Mac bonded in the Quest's Bluetooth settings is found
        // here, so the link survives with no Wi-Fi at all. Wi-Fi stays first because it is faster.
        val bluetoothMacs = if (selected.mode == ConnectionMode.Auto) BluetoothCarriers.bondedMacs(activity, selected.classicAddress, selected.name) else emptyList()
        val attempts = if (selected.mode == ConnectionMode.Auto) buildList {
          add(ConnectionMode.LocalWiFi)
          if (ownsGroup) add(ConnectionMode.WiFiDirect)
          add(ConnectionMode.USB); add(ConnectionMode.WiFiADB)
          val networks = activity.getSystemService(ConnectivityManager::class.java)
          if (networks.allNetworks.any { networks.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true }) {
            add(ConnectionMode.BluetoothPAN)
          }
          if (selected.classicAddress.isNotBlank() || bluetoothMacs.isNotEmpty()) add(ConnectionMode.BluetoothClassic)
          // BLE needs no pairing but scans for seconds, so it is the last resort when no Mac is bonded.
          if (selected.bleAddress.isNotBlank() || (bluetoothMacs.isEmpty() && BluetoothCarriers.canScan(activity))) add(ConnectionMode.BluetoothLE)
        } else listOf(selected.mode)
        if (retry == 0) Log.i(TAG, "routes: ${attempts.joinToString { it.title }}" + (if (bluetoothMacs.isEmpty()) "" else "; paired Mac candidates ${bluetoothMacs}"))
        var connected = false
        val failures = mutableListOf<String>()
        var identity = false
        var missingWifi = false
        var missingNetwork = false
        for (mode in attempts) {
          ensureActive()
          var tunnel: RelayTunnel? = null
          var usedClassic: String? = null
          try {
            // Keep an actionable message (e.g. "Make sure the VRcode Mac app is running") on screen while retrying.
            if (!mutableStatus.value.attention) mutableStatus.value = LinkStatus("Connecting via ${mode.title}", "${selected.name} · attempt ${retry + 1}", busy = true)
            val ended = CompletableDeferred<String>()
            // Native Bluetooth calls are bounded. Always reclaim a result even if cancelled
            // while a platform connect call was in progress.
            val (opened, usedHost) = withContext(Dispatchers.IO + NonCancellable) {
              var carrier: CarrierSocket? = null
              try {
                val connectivity = activity.getSystemService(ConnectivityManager::class.java)
                val network = when (mode) {
                  ConnectionMode.BluetoothPAN -> connectivity.allNetworks.firstOrNull { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true }
                    ?: throw MissingNetwork("No Bluetooth network is connected. Horizon OS must expose PAN in its system settings; otherwise choose Bluetooth instead.")
                  ConnectionMode.LocalWiFi, ConnectionMode.MacHotspot, ConnectionMode.PhoneHotspot -> connectivity.allNetworks.firstOrNull {
                    connectivity.getNetworkCapabilities(it)?.let { caps -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
                  } ?: throw MissingNetwork("Join the selected Wi-Fi network on the Quest first.")
                  ConnectionMode.WiFiDirect -> { check(ownsGroup) { "Create the Quest network and join it from your Mac first." }; null }
                  else -> null
                }
                val endpoints = when (mode) {
                  ConnectionMode.USB -> listOf("127.0.0.1" to 17274)
                  ConnectionMode.WiFiADB -> listOf("127.0.0.1" to 27274)
                  ConnectionMode.BluetoothClassic -> {
                    val (address, socket) = openClassic(bluetoothMacs.ifEmpty { listOf(selected.classicAddress) })
                    usedClassic = address
                    carrier = CarrierSocket(socket); listOf("127.0.0.1" to carrier.port)
                  }
                  ConnectionMode.BluetoothLE -> {
                    carrier = CarrierSocket(BluetoothCarriers.ble(activity, selected.bleAddress)); listOf("127.0.0.1" to carrier.port)
                  }
                  else -> {
                    val found = discovered(selected)
                    selected.candidateHosts(found.map { it.host }).map { host -> host to (found.firstOrNull { it.host == host }?.port ?: selected.port) }
                      .also { require(it.isNotEmpty()) { "Enter your Mac address or discover it on the network." } }
                  }
                }
                connectFirst(selected, endpoints, carrier, network) { ended.complete(it) }
              } catch (error: Exception) { carrier?.close(); throw error }
            }
            tunnel = opened
            ensureActive()
            if (thisGeneration != generation) break
            if (usedHost != "127.0.0.1" && usedHost != selected.lastHost && profile.value.fingerprint == selected.fingerprint) {
              update(profile.value.copy(lastHost = usedHost))
            }
            // Remember the Mac's Bluetooth address, so the next fallback goes straight to it.
            if (usedClassic != null && !usedClassic.equals(selected.classicAddress, true) && profile.value.fingerprint == selected.fingerprint) {
              update(profile.value.copy(classicAddress = usedClassic!!))
            }
            Log.i(TAG, "connected via ${mode.title}" + (usedClassic?.let { " ($it)" } ?: if (usedHost != "127.0.0.1") " ($usedHost)" else ""))
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
          catch (error: Exception) {
            val text = if (error is RouteFailure || error is MissingNetwork) error.message else error.cause?.message ?: error.message
            failures += "${mode.title}: ${text ?: "unavailable"}"
            identity = identity || error.isIdentityFailure()
            if (error is MissingNetwork) { missingNetwork = true; if (mode == ConnectionMode.LocalWiFi) missingWifi = true }
          }
          finally { tunnel?.close(); if (live === tunnel) live = null }
        }
        ensureActive()
        if (!connected) mutableStatus.value = failureStatus(selected.name, selected.mode == ConnectionMode.Auto, failures, identity, missingWifi, missingNetwork,
          bluetooth = ConnectionMode.BluetoothClassic in attempts || ConnectionMode.BluetoothLE in attempts)
        retry++
        withTimeoutOrNull(minOf(5000L, retry * 1000L)) { wake.receive() }
      }
    }
  }

  /** The first of [addresses] whose RFCOMM VRcode service answers, with its open carrier. */
  private fun openClassic(addresses: List<String>): Pair<String, ByteCarrier> {
    val errors = mutableListOf<String>()
    for (address in addresses) {
      try { return address to BluetoothCarriers.classic(activity, address) }
      catch (error: Exception) { errors += "$address: ${error.cause?.message ?: error.message ?: "unavailable"}" }
    }
    throw IllegalStateException(errors.joinToString("; ").ifBlank { "No paired Mac" })
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
      override fun onServiceFound(service: NsdServiceInfo) { resolve(service, 0) }
      fun resolve(service: NsdServiceInfo, attempt: Int) {
        runCatching { nsd.resolveService(service, object : NsdManager.ResolveListener {
          override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
            // Older NSD implementations resolve one service at a time.
            if (code == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 5) scope.launch { delay(300L * (attempt + 1)); resolve(service, attempt + 1) }
          }
          override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            val fingerprint = info.attributes["fp"]?.toString(Charsets.UTF_8)
            mutableMacs.value = mutableMacs.value.filterNot { it.name == info.serviceName } + FoundMac(info.serviceName, host, info.port, fingerprint)
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
    provisioning?.cancel(); provisioning = null
    networkCallback?.let { callback -> runCatching { activity.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) } }
    networkCallback = null
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
