package dev.improve.vrcode.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class MacCandidatesTest {
  private val mac = BondedDevice("5C:E9:1E:82:C8:72", "Leon’s MacBook Pro", computer = true, hasLinkService = false)
  private val airPods = BondedDevice("74:77:86:40:6F:A7", "Leon’s AirPods Pro", computer = false, hasLinkService = false)
  private val otherMac = BondedDevice("11:22:33:44:55:66", "Studio", computer = true, hasLinkService = false)

  @Test fun bondedMacIsFoundWithNoAddressConfigured() {
    assertEquals(listOf(mac.address, otherMac.address), macCandidates(listOf(airPods, otherMac, mac), remembered = "", macName = "Leon's MacBook Pro"))
  }

  @Test fun headphonesAndControllersAreNeverTried() {
    assertEquals(emptyList<String>(), macCandidates(listOf(airPods), remembered = "", macName = "Mac"))
  }

  @Test fun rememberedAddressThenAdvertisedServiceComeFirst() {
    val advertising = otherMac.copy(hasLinkService = true)
    assertEquals(listOf(mac.address, advertising.address),
      macCandidates(listOf(advertising, mac), remembered = mac.address.lowercase(), macName = "Other"))
    assertEquals(listOf(advertising.address, mac.address),
      macCandidates(listOf(mac, advertising), remembered = "", macName = mac.name))
  }

  @Test fun atMostThreeComputersAreTried() {
    val many = (1..6).map { BondedDevice("00:00:00:00:00:0$it", "PC $it", computer = true, hasLinkService = false) }
    assertEquals(3, macCandidates(many, remembered = "", macName = "Mac").size)
  }
}
