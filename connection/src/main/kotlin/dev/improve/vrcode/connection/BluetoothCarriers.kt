package dev.improve.vrcode.connection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

val LINK_SERVICE: UUID = UUID.fromString("94ce62e0-9f70-4f80-a008-927272730001")
private val LINK_RX = UUID.fromString("94ce62e0-9f70-4f80-a008-927272730002")
private val LINK_TX = UUID.fromString("94ce62e0-9f70-4f80-a008-927272730003")
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

interface ByteCarrier : Closeable { val input: InputStream; val output: OutputStream }

/** Adapts native byte streams to a local TCP socket so JSSE can run real TLS over Bluetooth. */
class CarrierSocket(private val carrier: ByteCarrier) : Closeable {
  private val executor = Executors.newFixedThreadPool(3)
  private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
  private var socket: Socket? = null
  private val closed = AtomicBoolean()
  val port: Int get() = server.localPort
  init {
    executor.execute {
      try {
        val peer = server.accept().apply { tcpNoDelay = true }
        socket = peer
        executor.execute { copy(carrier.input, peer.getOutputStream()) }
        copy(peer.getInputStream(), carrier.output)
      } catch (_: Exception) { close() }
    }
  }
  private fun copy(input: InputStream, output: OutputStream) {
    try {
      val bytes = ByteArray(4096)
      while (!closed.get()) {
        val count = input.read(bytes)
        if (count < 0) break
        output.write(bytes, 0, count); output.flush()
      }
    } catch (error: Exception) {
      if (!closed.get()) android.util.Log.i("VrcodeConnection", "Native carrier: ${error.message}")
    } finally { close() }
  }
  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { server.close() }; runCatching { socket?.close() }; runCatching { carrier.close() }
    executor.shutdownNow()
  }
}

@SuppressLint("MissingPermission")
object BluetoothCarriers {
  private fun adapter(context: Context): BluetoothAdapter {
    check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { "Allow Nearby devices to connect using Bluetooth." }
    return (context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth is not available on this device.")).also {
      check(it.isEnabled) { "Turn on Bluetooth in system settings." }
    }
  }

  fun paired(context: Context): List<Pair<String, String>> = adapter(context).bondedDevices.map { (it.name ?: "Bluetooth device") to it.address }

  fun classic(context: Context, address: String): ByteCarrier {
    require(BluetoothAdapter.checkBluetoothAddress(address)) { "Choose a paired Mac Bluetooth address first." }
    val device = adapter(context).getRemoteDevice(address)
    var lastError: Exception? = null
    repeat(2) { attempt ->
      val socket = device.createRfcommSocketToServiceRecord(LINK_SERVICE)
      val executor = Executors.newSingleThreadExecutor()
      try {
        executor.submit { socket.connect() }.get(15, TimeUnit.SECONDS)
        return object : ByteCarrier {
          override val input = socket.inputStream
          override val output = socket.outputStream
          override fun close() = socket.close()
        }
      } catch (error: Exception) {
        socket.close(); lastError = error
        if (attempt == 0) {
          // The Mac may republish a different RFCOMM channel after restarting.
          device.fetchUuidsWithSdp()
          Thread.sleep(1000)
        }
      } finally { executor.shutdownNow() }
    }
    throw IllegalStateException("Bluetooth connection failed. Pair the Mac and enable Bluetooth in the Mac service.", lastError)
  }

  fun ble(context: Context, address: String): ByteCarrier {
    val adapter = adapter(context)
    check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) { "Allow Nearby devices to find the Mac." }
    val found = CountDownLatch(1)
    var device: BluetoothDevice? = null
    val callback = object : ScanCallback() {
      override fun onScanResult(type: Int, result: ScanResult) {
        if (address.isBlank() || result.device.address.equals(address, true)) { device = result.device; found.countDown() }
      }
      override fun onScanFailed(code: Int) { found.countDown() }
    }
    val scanner = adapter.bluetoothLeScanner ?: error("Bluetooth LE scanning is unavailable.")
    scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(LINK_SERVICE)).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
    try { check(found.await(12, TimeUnit.SECONDS) && device != null) { "No vrcode Mac found. Start the Mac service with Bluetooth enabled and keep it nearby." } }
    finally { scanner.stopScan(callback) }
    return GattCarrier(context, device!!)
  }
}

private class ByteQueue : InputStream() {
  private val chunks = LinkedBlockingQueue<ByteArray>()
  private val queuedBytes = AtomicInteger()
  private var current = ByteArray(0)
  private var offset = 0
  private val closed = AtomicBoolean()
  fun append(value: ByteArray) {
    if (closed.get()) return
    if (queuedBytes.addAndGet(value.size) > 2 * 1024 * 1024) {
      android.util.Log.w("VrcodeConnection", "BLE receive buffer exceeded its byte limit")
      close(); return
    }
    chunks.offer(value.copyOf())
  }
  @Synchronized override fun read(buffer: ByteArray, start: Int, count: Int): Int {
    if (count == 0) return 0
    while (offset == current.size) {
      if (closed.get() && chunks.isEmpty()) return -1
      current = chunks.take(); queuedBytes.addAndGet(-current.size); offset = 0
      if (current.isEmpty() && closed.get()) return -1
    }
    val length = minOf(count, current.size - offset)
    current.copyInto(buffer, start, offset, offset + length); offset += length
    return length
  }
  override fun read(): Int { val byte = ByteArray(1); return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255 }
  override fun close() { if (closed.compareAndSet(false, true)) { chunks.clear(); chunks.offer(ByteArray(0)) } }
}

@SuppressLint("MissingPermission")
private class GattCarrier(context: Context, device: BluetoothDevice) : ByteCarrier {
  override val input = ByteQueue()
  private val ready = CountDownLatch(1)
  private var writeReady = CountDownLatch(0)
  private var failed: String? = null
  private var mtu = 23
  private var rx: BluetoothGattCharacteristic? = null
  private var refreshed = false
  private var subscribed = false
  private val closed = AtomicBoolean()
  private var gatt: BluetoothGatt? = null
  private val callback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
      if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
      else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) fail("Bluetooth LE disconnected ($status)")
    }
    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
      if (status != BluetoothGatt.GATT_SUCCESS) { fail("Bluetooth services could not be read"); return }
      if (g.getService(LINK_SERVICE) == null && !refreshed) {
        refreshed = true
        // Horizon OS can retain the Mac's old GATT table from before the app was
        // installed. AOSP has no public cache-reset API. Try its optional refresh
        // hook once; blocked firmware falls back to an actionable re-pair error.
        val reset = runCatching { g.javaClass.getMethod("refresh").invoke(g) as? Boolean == true }.getOrDefault(false)
        if (reset) { Handler(Looper.getMainLooper()).postDelayed({ if (!closed.get()) g.discoverServices() }, 300); return }
      }
      if (!g.requestMtu(185)) subscribe(g)
    }
    override fun onServiceChanged(g: BluetoothGatt) {
      if (subscribed) fail("Mac Bluetooth services changed. Reconnect to the Mac.") else g.discoverServices()
    }
    override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) { if (status == BluetoothGatt.GATT_SUCCESS) mtu = value; subscribe(g) }
    private fun subscribe(g: BluetoothGatt) {
      val service = g.getService(LINK_SERVICE)
      rx = service?.getCharacteristic(LINK_RX)
      val tx = service?.getCharacteristic(LINK_TX)
      val descriptor = tx?.getDescriptor(CCCD)
      if (rx == null || tx == null || descriptor == null) {
        fail("Mac Bluetooth services are not available yet. Restart the Mac service; if needed, forget and re-pair this Mac in Bluetooth settings."); return
      }
      if (!g.setCharacteristicNotification(tx, true)) { fail("Quest could not enable Bluetooth notifications"); return }
      if (g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) fail("Could not subscribe to the Mac")
    }
    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
      if (status != BluetoothGatt.GATT_SUCCESS) fail("Mac notification subscription failed") else { subscribed = true; ready.countDown() }
    }
    override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { if (characteristic.uuid == LINK_TX) input.append(value) }
    override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
      if (status != BluetoothGatt.GATT_SUCCESS) failed = "BLE write failed ($status)"
      writeReady.countDown()
    }
  }
  init {
    try {
      gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
      check(ready.await(15, TimeUnit.SECONDS) && failed == null && rx != null) { failed ?: "Bluetooth LE setup timed out" }
    } catch (error: Exception) { close(); throw error }
  }
  private fun fail(reason: String) {
    android.util.Log.i("VrcodeConnection", reason)
    failed = reason; ready.countDown(); writeReady.countDown(); input.close()
  }
  override val output = object : OutputStream() {
    override fun write(value: Int) = write(byteArrayOf(value.toByte()))
    @Synchronized override fun write(bytes: ByteArray, start: Int, count: Int) {
      var offset = start
      while (offset < start + count) {
        if (closed.get() || failed != null) throw EOFException(failed ?: "BLE closed")
        // Android 14 negotiates MTU 517; GATT attributes still have a 512-byte
        // limit. MTU-3 alone makes larger TLS records fail mid-stream.
        val end = minOf(start + count, offset + minOf(mtu - 3, 512))
        writeReady = CountDownLatch(1)
        check(gatt!!.writeCharacteristic(rx!!, bytes.copyOfRange(offset, end), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS) { "BLE write queue is busy" }
        check(writeReady.await(5, TimeUnit.SECONDS) && failed == null) { failed ?: "BLE write timed out" }
        offset = end
      }
    }
  }
  override fun close() { if (closed.compareAndSet(false, true)) { input.close(); ready.countDown(); writeReady.countDown(); gatt?.disconnect(); gatt?.close() } }
}
