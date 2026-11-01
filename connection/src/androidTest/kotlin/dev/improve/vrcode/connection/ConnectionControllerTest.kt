package dev.improve.vrcode.connection

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectionControllerTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val scope = MainScope()
  private var controller: ConnectionController? = null

  private fun profile(): ConnectionProfile {
    val value = InstrumentationRegistry.getArguments().getString("relay_config")
    assumeTrue("An ephemeral native relay is required", value != null)
    return ConnectionProfile().importMac(value!!)
  }

  @After fun close() {
    compose.runOnUiThread { controller?.close(); scope.cancel(); ConnectionStore(compose.activity).save(ConnectionProfile()) }
  }

  @Test fun automaticAuthenticatesWifiAndOpensBothServices() {
    val profile = profile()
    val networks = compose.activity.getSystemService(ConnectivityManager::class.java)
    assumeTrue("Automatic Wi-Fi requires an active Wi-Fi network", networks.allNetworks.any {
      networks.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    })
    val ready = CountDownLatch(1)
    var ports = emptyList<Int>()
    compose.runOnUiThread {
      controller = ConnectionController(compose.activity, scope, { bridge, speech -> ports = listOf(bridge, speech); ready.countDown() }, { })
      controller!!.update(profile)
      controller!!.connect()
    }
    val wasReady = ready.await(15, TimeUnit.SECONDS)
    assertTrue("Automatic did not connect: ${controller!!.status.value.detail}", wasReady)
    compose.runOnIdle { assertTrue(controller!!.status.value.title.contains("Local Wi-Fi")) }
    for (port in ports) Socket("127.0.0.1", port).use { socket ->
      socket.soTimeout = 5000
      val payload = "automatic uses the authenticated route".toByteArray()
      socket.getOutputStream().write(payload)
      val reply = ByteArray(payload.size).also { DataInputStream(socket.getInputStream()).readFully(it) }
      assertArrayEquals(payload, reply)
    }
  }

  @Test fun panDoesNotClaimWifiAsBluetooth() {
    val profile = profile().copy(mode = ConnectionMode.BluetoothPAN)
    var connected = false
    compose.runOnUiThread {
      controller = ConnectionController(compose.activity, scope, { _, _ -> connected = true }, { })
      controller!!.update(profile)
      controller!!.connect()
    }
    compose.waitUntil(10000) { controller!!.status.value.detail.contains("No Bluetooth network") || connected }
    compose.runOnIdle {
      assertFalse("PAN must not silently borrow Wi-Fi", connected)
      assertTrue(controller!!.status.value.detail.contains("No Bluetooth network"))
    }
  }
}
