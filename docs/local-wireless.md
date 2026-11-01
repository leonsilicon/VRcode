# Switchable Mac ↔ Quest connections

Connection settings now contains ten choices. **Automatic** is the default. It tries authenticated
local Wi-Fi first, then a Quest Wi-Fi Direct group created by the app, USB, Wi-Fi ADB, an existing PAN network, and configured
Bluetooth Classic / BLE adapters. Explicit choices stay on that route rather than silently using
another one. An unavailable route reports an error and can be cancelled.

All new app routes carry both xmux and speech through a pinned TLS relay. Speech remains bound to
loopback on the Mac; it no longer needs a public, unauthenticated speech socket for wireless use.
The relay only opens the two configured application services. It does not patch xmux, replace its
certificate, or bypass its separate terminal pairing.

## First setup

Build and start the background Mac app (the bundle is necessary for macOS Bluetooth privacy):

```sh
bun scripts/run-mac-service.ts --bluetooth
```

For everyday use, install the app so it starts at login and relaunches after a crash:

```sh
bun run mac:install          # xmux bridge 7272 + speech 7273 + relay 7274, one signed app
```

The app bundles the xmux bridge, so no separate xmux LaunchAgent is needed (remove an old one with
`launchctl bootout gui/$(id -u)/dev.improve.xmux.bridge`). `mac:install` copies the packaged app out
of `mac/.build`, so later rebuilds and tests never replace the running binary. Re-run it after
rebuilding; `bun run mac:uninstall` removes it. The log is `~/Library/Logs/vrcode-mac.log`. See
[the Mac app](mac-app.md).

To add the relay alongside an already-running speech
service, use `bun scripts/run-mac-service.ts --link-only --bluetooth` instead. Without `--link-only`,
the service also starts speech on loopback port 7273. The relay listens on port 7274 and advertises
`_vrcode._tcp` using Bonjour. Bluetooth is opt-in on the Mac; omit `--bluetooth` for network-only use.
Allow the Mac's Bluetooth / local-network prompt when presented. The app lives in the menu bar; its
window (permissions, headset pairing, API key, log) opens from there and when a headset waits to
pair.

With the debug Quest app installed and an authorized ADB connection available:

```sh
bun scripts/quest-link.ts pair
```

The debug app imports the key automatically and connects: within about two seconds when it is
running, otherwise at its next launch. **Import from connected Mac** in Settings → Connection does
the same by hand. The helper copies the pairing configuration directly into the debug app's private
storage (owner-only, renamed into place), without printing the bearer key or using the clipboard.
The app encrypts the saved configuration with Android Keystore, then deletes the import file. Only
the app itself or `run-as` from an ADB-authorized Mac can write that file, so release apps use the
manual path below. The pairing survives `adb install -r`; uninstalling or clearing app data loses it.

Alternatively run the built `vrcode-mac --link-info --link-host MAC_ADDRESS` and paste the resulting
`vrcode:` connection key into the Quest's Mac connection key field, then Save Mac. The key contains
the host, port, certificate fingerprint, and a random 32-byte bearer secret. Treat it as a password;
share it only with the intended Quest. It stays stable across restarts. It is unrelated to the
six-digit xmux terminal pairing code.

Leave the selector at Automatic. Saving or importing a key connects immediately; afterwards the app
connects on every launch while **Connect on launch** is on. You can also enter an address or choose
a discovered Mac. If xmux asks for its own pairing, the terminal pairing code remains visible in the
connection pane. Existing debug-only `address` intents remain available for legacy diagnostics;
they are not the normal app connection path. A launch with `address` skips the relay at startup,
but a key provisioned later by `quest-link pair` still switches the app to the relay.

### How launch-time connection stays reliable

- **Address candidates.** The key lists every Mac IPv4 address at pairing time (`hosts`). Network
  routes try Bonjour results for the paired Mac, the last address that worked, the key's `host` and
  the remaining `hosts`. Attempts start 300 ms apart and run concurrently; the first that passes
  the certificate pin wins and becomes the remembered address. Explicit network modes put the
  entered address first.
- **Discovery by identity.** The Mac's Bonjour TXT record carries `fp=<certificate SHA-256>`, so a
  renamed instance (`Mac (2)`) or another relay advertising the same name cannot hide or replace
  the paired Mac. Adverts without `fp` (older Mac builds) still match by name. Bonjour does not cross
  routed subnets (for example campus Wi-Fi where the Quest and Mac get different /19 networks); there
  the remembered and listed addresses are what reconnects.
- **Prompt retries.** Retries back off from one to five seconds indefinitely, but Wi-Fi becoming
  available or the app resuming retries immediately and restarts discovery if it had stopped.
- **Says what to fix.** When a full round of routes fails, the Connection page names the fix and
  stays up while retries continue: **Make sure the VRcode Mac app is running** when nothing
  answers (the usual case; the Quest connects on its own as soon as the app starts), **Connect the
  Quest to Wi-Fi** in Automatic with no Wi-Fi, or **Your Mac's connection key has changed** when a
  Mac answers but rejects the key. Losing the Mac also unmounts the terminal windows, so the app
  opens the Connection page once per outage and closes it when the terminals return.
- **Always-on Mac.** With `mac:install`, a reboot, logout or crash does not leave the Quest waiting
  for a service that someone has to restart. The relay exits if its listener fails (for example a
  port conflict), and the app restarts it, as it does a crashed bridge; launchd relaunches the app
  itself after a crash.

If the Mac's address changes to one the key never listed, and Bonjour cannot reach it, the USB or
Wi-Fi debugging routes still connect when their mappings exist. Otherwise run `quest-link pair`
again; the running app picks up the new key without a tap.

## Modes

| Choice | Implemented route and setup |
|---|---|
| Automatic | Prefer Wi-Fi, then an app-created direct group, USB, Wi-Fi ADB, an existing PAN network, configured Classic, configured BLE. Every successful route authenticates the same Mac certificate and key. Retries use bounded backoff; a heartbeat detects dead links. It does not alter OS radio settings automatically. |
| Local Wi-Fi | A TLS socket bound to the Quest's Wi-Fi network, discovered with Bonjour or entered manually. No cable, ADB, cloud service, or internet is required for application traffic. Peer isolation and firewalls can still block it. |
| Mac Wi-Fi sharing | The same secure Wi-Fi transport with a guided Internet Sharing setup. Enable sharing on the Mac and join the Quest to that network. Apple's available upstream / downstream interfaces determine whether the Mac can host it; the app cannot create unsupported radio modes. |
| Phone hotspot | The secure Wi-Fi transport with hotspot setup guidance. Join both devices to the phone's network. The hotspot must permit traffic between clients. |
| Wi-Fi Direct | Calls Android `WifiP2pManager.createGroup`, displays the Quest network name and password, and lets you remove the app-owned group. Join that WPA network from the Mac, then discover or enter its address. The client uses the direct subnet route, not the default Wi-Fi socket binding. Unsupported or restricted Horizon OS versions report the platform failure. |
| Bluetooth | A real Android secure RFCOMM socket, a Mac SDP service and `IOBluetoothRFCOMMChannel`, and a duplex adapter carrying the pinned TLS stream. Pair the Mac and Quest in OS settings, allow Nearby devices, select the Mac from paired devices, and start the Mac service with Bluetooth enabled. |
| Bluetooth Low Energy | A real Mac CoreBluetooth GATT peripheral with receive/write and transmit/notify characteristics, plus an Android GATT client. Writes are acknowledged, notifications respect backpressure, data is bounded, and TLS remains end to end. This is a slower fallback for light terminal use; real-time dictation / display performance is not promised. An optional address selects one Mac; otherwise scanning searches for the service and certificate pinning enforces identity. |
| Bluetooth network | A secure socket explicitly bound to an existing Android Bluetooth PAN network. It fails if no PAN network exists, instead of routing over Wi-Fi while claiming Bluetooth. Creating PAN requires OS support and setup on both devices; Horizon OS may not expose it. Use the native Bluetooth mode when PAN is unavailable. |
| Wi-Fi debugging | TLS relay over a dedicated Wi-Fi ADB reverse mapping: Quest `127.0.0.1:27274` → Mac `127.0.0.1:7274`. Run `bun scripts/quest-link.ts wifi`. |
| USB | TLS relay over a separate USB reverse mapping: Quest `127.0.0.1:17274` → Mac `127.0.0.1:7274`. Run `bun scripts/quest-link.ts usb` with the cable attached. Separate device ports prevent a mode change from silently reusing the other transport. |

Mac sharing, phone hotspots and PAN are OS-managed networks. Their selector entries provide the
application route and setup controls/guidance; they cannot guarantee an OS feature or a phone's
client-forwarding policy. The app never changes the Mac's current Wi-Fi, disables network isolation,
changes firewall rules, removes existing Bluetooth bonds, or installs a persistent Quest daemon.

## ADB setup and diagnostics

```sh
bun scripts/quest-link.ts wifi     # authorize via USB initially, then unplug
bun scripts/quest-link.ts usb      # cable route
bun scripts/quest-link.ts status
bun scripts/quest-link.ts lan      # route-selected Mac address
bun scripts/quest-link.ts probe
bun scripts/quest-link.ts probe --direct
```

The helper also retains the legacy 7272 and 7273 mappings for older debug clients. New Settings
connections use the encrypted relay. The `probe` command uses a temporary echo socket and optional
reverse mapping, and removes only its own temporary resources. Its 64 KiB round trip includes ADB
startup and several link traversals; it is not a throughput benchmark or application RTT.

With multiple headsets, pass `--serial SERIAL`. The helper compares physical identities before
preferring a Wi-Fi transport over USB, scopes device operations, refuses conflicting mappings,
preserves unrelated ports, and rolls back only mappings it just created on failure. `--relay-port`
changes the Mac relay port while the device's USB and Wi-Fi ports remain distinct. `--bridge-port`
and `--service-port` configure the legacy forwards. Install Bun 1.4.4+ and Android SDK Platform
Tools; `--adb PATH` and `--mac-binary PATH` override discovery.

To reconnect an existing legacy TCP ADB listener without USB:

```sh
bun scripts/quest-link.ts wifi --host 192.168.1.30
```

After a Quest reboot or a network change, USB may be needed to enable it again. To actually stop
that listener, use `bun scripts/quest-link.ts off --serial IP:5555`; merely disconnecting the Mac leaves it
listening. The helper does not kill the shared ADB server or disconnect other devices.

Legacy TCP ADB is itself unencrypted. The new app's TLS relay protects its payload on this carrier,
but shell/debug traffic and older raw speech clients do not gain encryption. Use trusted networks.
If the Quest firmware exposes modern TLS Wireless debugging, pair with `adb pair` using its pairing
port, then connect using its distinct connection port; the helper can reuse that authorized serial.
Do not assume the standard Android pairing UI exists on every Horizon OS build.

## Build and verification

```sh
swift build --package-path mac
bun scripts/run-mac-service.ts --package-only
./gradlew :app:assembleDebug :connection:assembleDebugAndroidTest
VRCODE_RELAY_BINARY="$PWD/mac/.build/out/Products/Debug/vrcode-mac" bun test scripts/tests
bun scripts/check-relay-android.ts --serial emulator-5554
bun scripts/check-relay-android.ts --serial QUEST_WIFI_IP:5555 --host MAC_IP
```

The Android runner creates an isolated Mac relay, two echo services, and a temporary pairing key.
It tests the real Kotlin client against the Swift server without using production credentials.
`--bluetooth --classic-address MAC_BLUETOOTH_ADDRESS` additionally exercises the native radios;
Classic needs an existing OS bond. Radio tests use the packaged background app for a valid macOS
privacy identity and stop only that test instance afterward.

UI tests cover the default, all ten selectable modes, persistence, and invalid pairing. Native
relay tests cover TLS identity, authentication rejection, fragmented reads, two multiplexed binary
streams, channel independence, forbidden destinations, and oversized frames. Android integration
tests cover certificate/key rejection, Keystore storage, and very small carrier chunks.

The first-turn transport checks on 2026-11-01 used Quest `100.67.153.133:5555` and Mac
`100.67.24.253`, with actual xmux TLS and speech HELLO/WELCOME replies. Those are session addresses,
not code defaults. Test results for the expanded implementation are recorded in
[connection validation](connection-validation.md).

## Platform references and limitations

- [Meta: Quest Wi-Fi ADB](https://developers.meta.com/vr/documentation/native/android/ts-adb/)
- [AOSP: legacy TCP versus TLS wireless debugging](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/adb_wifi.md)
- [Apple: Internet Sharing](https://support.apple.com/guide/mac-help/share-internet-connection-mac-network-users-mchlp1540/mac)
- [Android: RFCOMM streams](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)
- [Apple: IOBluetoothRFCOMMChannel](https://developer.apple.com/documentation/iobluetooth/iobluetoothrfcommchannel)
- [Android: GATT transfers](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)
- [Android: Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifip2p)

BLE handles standard service-change callbacks. Some Horizon OS builds cache the Mac's pre-app GATT
table; a guarded, one-time attempt uses AOSP's non-public cache-refresh hook. This is optional
compatibility code, not a guaranteed API. If firmware blocks it, the app reports a re-pair/restart
action rather than bypassing certificate checks or clearing bonds automatically. See the
[AOSP implementation](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/dce557c1c1a393960bb8fa29de354ed2ee9ac525/framework/java/android/bluetooth/BluetoothGatt.java).
