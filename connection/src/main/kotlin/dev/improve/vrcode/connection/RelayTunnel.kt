package dev.improve.vrcode.connection

import android.util.Base64
import android.net.Network
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** The same pinned TLS protocol runs on every carrier, including both Bluetooth implementations. */
class RelayTunnel private constructor(private val tls: SSLSocket, private val carrier: Closeable?, private val onClosed: (String) -> Unit) : Closeable {
  private val executor = Executors.newCachedThreadPool()
  private val output = tls.outputStream.buffered()
  private val input = DataInputStream(tls.inputStream.buffered())
  private val closed = AtomicBoolean()
  private val heartbeat = Executors.newSingleThreadScheduledExecutor()
  @Volatile private var lastPong = System.nanoTime()
  private val nextId = AtomicInteger(1)
  private data class Channel(val socket: Socket, val ready: CountDownLatch = CountDownLatch(1), var opened: Boolean = false)
  private val channels = ConcurrentHashMap<Int, Channel>()
  private val servers = listOf(ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")), ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")))
  val bridgePort: Int get() = servers[0].localPort
  val speechPort: Int get() = servers[1].localPort

  fun start() {
    executor.execute {
      try {
        while (!closed.get()) {
          val kind = input.readUnsignedByte()
          val id = Integer.reverseBytes(input.readInt())
          val length = Integer.reverseBytes(input.readInt())
          require(length in 0..32768) { "Invalid relay packet" }
          val data = ByteArray(length).also(input::readFully)
          when (kind) {
            2 -> channels[id]?.socket?.getOutputStream()?.apply { write(data); flush() }
            3 -> drop(id)
            4 -> channels[id]?.let { it.opened = true; it.ready.countDown() }
            7 -> lastPong = System.nanoTime()
            else -> error("Unknown relay packet")
          }
        }
      } catch (error: Exception) { end(error.message ?: "Connection lost") }
    }
    heartbeat.scheduleAtFixedRate({
      if (!closed.get()) {
        if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - lastPong) > if (carrier == null) 20 else 60) end("Mac stopped responding")
        else executor.execute { runCatching { send(6, 0) }.onFailure { end("Connection lost") } }
      }
    }, 5, 5, TimeUnit.SECONDS)
    for ((index, server) in servers.withIndex()) executor.execute {
      try {
        while (!closed.get()) {
          val socket = server.accept().apply { tcpNoDelay = true }
          if (channels.size >= 32) { socket.close(); continue }
          val id = nextId.getAndIncrement()
          val channel = Channel(socket)
          channels[id] = channel
          if (closed.get()) { drop(id); break }
          try { send(1, id, byteArrayOf((index + 1).toByte())) } catch (error: Exception) { drop(id); throw error }
          executor.execute {
            try {
              check(channel.ready.await(8, TimeUnit.SECONDS) && channel.opened) { "Mac service did not open" }
              val buffer = ByteArray(8192)
              val source = socket.getInputStream()
              while (!closed.get()) {
                val count = source.read(buffer)
                if (count < 0) break
                send(2, id, buffer.copyOf(count))
              }
            } catch (_: Exception) {
              // Closing a channel must not drop another terminal or the speech stream.
            } finally { runCatching { send(3, id) }; drop(id) }
          }
        }
      } catch (error: Exception) { if (!closed.get()) end(error.message ?: "Local connection closed") }
    }
  }

  @Synchronized private fun send(kind: Int, id: Int, data: ByteArray = ByteArray(0)) {
    if (closed.get()) throw EOFException("Relay closed")
    val header = java.nio.ByteBuffer.allocate(9).order(java.nio.ByteOrder.LITTLE_ENDIAN).put(kind.toByte()).putInt(id).putInt(data.size).array()
    output.write(header); output.write(data); output.flush()
  }

  private fun drop(id: Int) { channels.remove(id)?.let { it.ready.countDown(); runCatching { it.socket.close() } } }
  private fun end(reason: String) { if (!closed.get()) { android.util.Log.i("VrcodeConnection", "Relay disconnected: $reason"); close(); onClosed(reason) } }
  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { tls.close() }; runCatching { carrier?.close() }
    servers.forEach { runCatching { it.close() } }
    // Kotlin's size-optimized toList can race with a shrinking concurrent map.
    channels.forEach { id, _ -> drop(id) }
    channels.clear()
    executor.shutdownNow()
    heartbeat.shutdownNow()
  }

  companion object {
    fun connect(profile: ConnectionProfile, host: String, port: Int, carrier: Closeable? = null, network: Network? = null, onClosed: (String) -> Unit): RelayTunnel {
      require(profile.paired) { "Add your Mac connection key first." }
      val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("Client certificates not used")
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
          val actual = chain.firstOrNull()?.encoded?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) } }
          if (actual == null || !actual.equals(profile.fingerprint, true)) throw CertificateException("This is not your paired Mac. Check the Mac connection key.")
        }
      }
      val raw = network?.socketFactory?.createSocket() ?: Socket()
      var tls: SSLSocket? = null
      try {
        raw.connect(if (network == null) InetSocketAddress(host, port) else InetSocketAddress(network.getAllByName(host).first(), port), 4000)
        raw.tcpNoDelay = true
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        tls = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        tls.soTimeout = if (carrier == null) 8000 else 30000
        tls.startHandshake()
        tls.outputStream.write(Base64.decode(profile.token, Base64.DEFAULT)); tls.outputStream.flush()
        val ack = ByteArray(4).also { DataInputStream(tls.inputStream).readFully(it) }
        check(ack.contentEquals("VRL1".toByteArray())) { "Mac did not authorize this connection key." }
        tls.soTimeout = 0
        return RelayTunnel(tls, carrier, onClosed)
      } catch (error: Exception) {
        runCatching { tls?.close() }; runCatching { raw.close() }; runCatching { carrier?.close() }
        throw error
      }
    }
  }
}
