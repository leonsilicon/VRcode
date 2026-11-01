# Connection relay, version 1

The Mac listens on TCP 7274 with TLS 1.2 or later and a persistent self-signed certificate. Bonjour
advertises `_vrcode._tcp`; discovery supplies an address, not trust. The Quest verifies the exact
SHA-256 DER certificate fingerprint from its imported Mac configuration before sending a random
32-byte bearer key inside TLS. The Mac compares all 32 bytes before accepting application traffic.
It replies with the four ASCII bytes `VRL1`. A wrong key closes the connection without opening any
upstream sockets. Bearer authentication times out ten seconds after TLS is ready; a separate
60-second bound covers slow carrier handshakes.

LAN, hotspots, PAN, Wi-Fi Direct and ADB connect to this listener. The native Bluetooth carriers
forward opaque bytes to the same local listener. The Android Bluetooth adapters expose a temporary
loopback socket so standard JSSE TLS can run over them; they do not replace TLS with custom crypto.
The Mac loads its PKCS#12 identity into memory without adding it to the login keychain. Private
identity/token files live in `~/Library/Application Support/dev.improve.vrcode/link` with mode 600
inside a mode 700 directory. Quest configuration is encrypted using Android Keystore.

After authentication, packets are a nine-byte header and a body:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | kind |
| 1 | 4 | channel ID, little endian; zero reserved for connection control |
| 5 | 4 | body length, little endian; maximum 32768 |

| Kind | Direction | Meaning |
|---|---|---|
| 1 | Quest → Mac | Open channel. Body is one service byte: 1 = xmux, 2 = vrcode service. |
| 2 | Both | Opaque stream bytes on an open channel. |
| 3 | Both | Close a channel. Other channels remain usable. |
| 4 | Mac → Quest | Upstream connected; the Quest may now send bytes. |
| 6 | Quest → Mac | Heartbeat ping, channel 0. |
| 7 | Mac → Quest | Heartbeat pong, channel 0. |

The Mac only connects to its two configured loopback service ports. Unknown services, duplicate
channel IDs, zero channel IDs for opens, excessive channel count, oversized packets and invalid
kinds terminate the peer. Limits are eight peers, 32 channels per peer and bounded queued bytes.
Local proxies are ephemeral and bound to loopback; transport changes close old sockets, stop speech
capture, reconnect the existing clients and preserve their staged transcript data.

xmux's own TLS, certificate pinning, pairing, and terminal framing run unchanged inside service 1.
The vrcode protocol runs unchanged inside service 2. Neither protocol is decoded by the relay.
Future vrcode control kinds therefore remain compatible with every carrier.

Bluetooth Classic uses custom SDP service UUID `94ce62e0-9f70-4f80-a008-927272730001`. BLE uses the
same service UUID with RX `...0002` (write with response) and TX `...0003` (notify). BLE operations
are serialized, MTU-aware, and have bounded timeouts. The optional cache-refresh compatibility
path is described in [local wireless](local-wireless.md).

USB and Wi-Fi ADB deliberately use distinct Quest relay endpoints: 17274 and 27274 respectively,
both forwarded to the configured Mac relay port. This prevents one mode from selecting a reverse
mapping created for the other transport. Standard application use does not need ADB.
