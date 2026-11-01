# Connection implementation validation

Checked on 2026-11-01 with the Quest 3 (Android API 34), an Android API 36 emulator and this Mac.
The checks exercise application transport and UI selection; they are not a throughput or latency
benchmark and do not establish display-streaming performance.

| Check | Evidence |
|---|---|
| Mac build and packaging | `swift build` and the Bun background-app packaging command compile the TLS relay and both native Bluetooth adapters. The app bundle supplies macOS Bluetooth privacy metadata. |
| Quest app build | Gradle debug APK builds with the selector embedded in the existing Connection settings. |
| Tooling / native relay | Bun tests passed with the native relay checks enabled, including actual Swift TLS authentication, both multiplexed services, binary preservation, fragmented headers, wrong-key rejection, channel independence, invalid destinations and oversized frames. Asset validation also passed. |
| Android transport | Both emulator and Quest passed the real Kotlin-to-Swift TLS tests, certificate/key rejection, Keystore persistence, and a carrier adapter with very small chunks. |
| Native Bluetooth radios | A complete seven-test run passed on the Quest, including actual RFCOMM and actual BLE. Each radio carried pinned TLS, authenticated the Mac, and round-tripped 16 KiB on each application service. |
| Wi-Fi Direct | The Quest successfully created a group, returned a nonempty network name and WPA passphrase, and removed the test-owned group. Mac joining and application traffic over that group were not tested. |
| Connection UI | Four emulator UI tests passed: Automatic default, all ten selections, selection persistence / setup controls, and rejection of invalid pairing. A rendered screenshot was inspected. |
| PAN | The controller requires an Android Bluetooth network and binds its socket to that network. It cannot claim a Wi-Fi connection as PAN. No functioning PAN network was available for an end-to-end test. |
| Hotspot profiles | Wi-Fi socket binding and explicit setup guidance are implemented. No Mac Internet Sharing or phone hotspot was created during this task. Their availability / client-forwarding policies remain OS-specific prerequisites. |

Hardware testing found and fixed three Bluetooth interoperability issues: a cached pre-app Mac GATT
table, the 512-byte attribute limit even when Android negotiates MTU 517, and Classic registration
on RFCOMM channel zero. Classic also refreshes SDP and retries once after a transient connect
failure, because a Mac service restart can invalidate the previous channel. The guarded GATT cache
refresh remains a firmware-specific compatibility path; the app reports a recovery action if it is
unavailable.

The emulator does not necessarily expose the host route as an active Android Wi-Fi network. The
Automatic Wi-Fi integration check requires that capability; the physical Quest is the authoritative
test for Wi-Fi selection. The raw TLS tunnel can still be tested through the emulator host route.

Use `bun scripts/check-relay-android.ts --controller` for additional Automatic/PAN controller
checks and `--bluetooth --classic-address ADDRESS` for radio checks. Test fixtures use temporary
identities and echo services, then stop only their own Mac instance. They never clear OS bonds or
change the Mac's active network. Real Bluetooth tests need the Quest awake and an existing Classic
bond. The optional Wi-Fi Direct hardware test creates and removes only its own group.

No full immersive end-to-end speech dictation or terminal interaction is claimed by these relay
checks. Existing xmux pairing still applies, and the raw service framing remains unchanged.
