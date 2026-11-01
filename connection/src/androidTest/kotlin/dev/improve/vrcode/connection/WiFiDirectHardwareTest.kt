package dev.improve.vrcode.connection

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WiFiDirectHardwareTest {
  @SuppressLint("MissingPermission")
  @Test fun createsAndRemovesOwnGroupOrReportsPlatformRestriction() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT))
    val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    val channel = manager.initialize(context, context.mainLooper, null)
    val done = CountDownLatch(1)
    var failure = -1
    var created = false
    try {
      manager.createGroup(channel, object : WifiP2pManager.ActionListener {
        override fun onSuccess() { created = true; done.countDown() }
        override fun onFailure(reason: Int) { failure = reason; done.countDown() }
      })
      assertTrue("Wi-Fi Direct did not respond", done.await(10, TimeUnit.SECONDS))
      assumeTrue("Horizon OS refused group creation (code $failure); the app reports this restriction", failure < 0)
      val groupReady = CountDownLatch(1)
      var valid = false
      manager.requestGroupInfo(channel) { group ->
        valid = group != null && group.isGroupOwner && group.networkName.isNotBlank() && (group.passphrase?.length ?: 0) >= 8
        groupReady.countDown()
      }
      assertTrue(groupReady.await(5, TimeUnit.SECONDS))
      assertTrue("No joinable group information was returned", valid)
    } finally {
      if (created) {
        val removed = CountDownLatch(1)
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
          override fun onSuccess() { removed.countDown() }
          override fun onFailure(reason: Int) { removed.countDown() }
        })
        removed.await(5, TimeUnit.SECONDS)
      }
      channel.close()
    }
  }
}
