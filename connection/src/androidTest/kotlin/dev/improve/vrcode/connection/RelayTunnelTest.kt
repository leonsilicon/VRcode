package dev.improve.vrcode.connection

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.FilterInputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.random.Random

class RelayTunnelTest {
  private fun profile(): ConnectionProfile {
    val configuration = InstrumentationRegistry.getArguments().getString("relay_config")
    assumeTrue("Pass an ephemeral relay_config for network integration tests", configuration != null)
    return ConnectionProfile().importMac(configuration!!)
  }

  private fun roundTrip(tunnel: RelayTunnel) {
    tunnel.start()
    for (port in listOf(tunnel.bridgePort, tunnel.speechPort)) Socket("127.0.0.1", port).use { socket ->
      socket.soTimeout = 10000
      val payload = Random.nextBytes(16384)
      socket.getOutputStream().write(payload)
      val actual = ByteArray(payload.size)
      java.io.DataInputStream(socket.getInputStream()).readFully(actual)
      assertArrayEquals(payload, actual)
    }
  }

  @Test fun tlsOpensBothServices() {
    val profile = profile()
    RelayTunnel.connect(profile, profile.host, profile.port) { }.use(::roundTrip)
  }

  @Test fun bluetoothCarrierAdapterPreservesTlsWithSmallChunks() {
    val profile = profile()
    val raw = Socket(profile.host, profile.port)
    val carrier = object : ByteCarrier {
      override val input = object : FilterInputStream(raw.getInputStream()) {
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int = super.read(bytes, offset, minOf(count, 11))
      }
      override val output = object : OutputStream() {
        override fun write(value: Int) = raw.getOutputStream().write(value)
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
          var start = offset
          while (start < offset + count) {
            val size = minOf(7, offset + count - start)
            raw.getOutputStream().write(bytes, start, size); start += size
          }
        }
      }
      override fun close() = raw.close()
    }
    val bridge = CarrierSocket(carrier)
    RelayTunnel.connect(profile, "127.0.0.1", bridge.port, bridge) { }.use(::roundTrip)
  }

  @Test fun changedCertificateIsRejected() {
    val profile = profile()
    val error = runCatching { RelayTunnel.connect(profile.copy(fingerprint = "0".repeat(64)), profile.host, profile.port) { }.close() }.exceptionOrNull()
    assertNotNull("An unknown Mac certificate must be rejected", error)
  }

  @Test fun wrongKeyIsRejected() {
    val profile = profile()
    val error = runCatching { RelayTunnel.connect(profile.copy(token = Base64.encodeToString(ByteArray(32), Base64.NO_WRAP)), profile.host, profile.port) { }.close() }.exceptionOrNull()
    assertNotNull("A wrong pairing key must be rejected", error)
  }

  @Test fun storesPairingEncryptedAndDefaultsToAutomatic() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = ConnectionStore(context)
    val saved = ConnectionProfile(mode = ConnectionMode.BluetoothLE, token = Base64.encodeToString(Random.nextBytes(32), Base64.NO_WRAP), fingerprint = "a".repeat(64), host = "192.168.1.2")
    store.save(saved)
    assertEquals(saved, store.load())
    val raw = context.getSharedPreferences("vrcode_connection", 0).getString("profile", "")!!
    assertFalse(raw.contains(saved.token)); assertFalse(raw.contains(saved.host))
    assertEquals(ConnectionMode.Auto, ConnectionProfile().mode)
    store.save(ConnectionProfile())
  }

  @Test fun actualBluetoothLowEnergy() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("bluetooth_hardware") == "true")
    val profile = profile()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val carrier = CarrierSocket(BluetoothCarriers.ble(context, ""))
    RelayTunnel.connect(profile, "127.0.0.1", carrier.port, carrier) { }.use(::roundTrip)
  }

  @Test fun actualBluetoothClassicWhenPaired() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("bluetooth_hardware") == "true")
    val profile = profile()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assumeTrue("Pair the Quest and Mac before running the Classic hardware test", BluetoothCarriers.paired(context).any { it.second.equals(profile.classicAddress, true) })
    val carrier = CarrierSocket(BluetoothCarriers.classic(context, profile.classicAddress))
    RelayTunnel.connect(profile, "127.0.0.1", carrier.port, carrier) { }.use(::roundTrip)
  }
}
