import CryptoKit
import Foundation
import Network
import Security

/// A pinned TLS tunnel shared by LAN, hotspots, USB/ADB and the Bluetooth carriers.
/// Only the two local application services can be opened; this is not a general TCP proxy.
struct LinkIdentity {
  let identity: sec_identity_t
  let fingerprint: String
  let token: Data

  static func load(directory: URL) throws -> LinkIdentity {
    let fm = FileManager.default
    try fm.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    let p12 = directory.appendingPathComponent("link.p12")
    if !fm.fileExists(atPath: p12.path) {
      let temporary = directory.appendingPathComponent(UUID().uuidString, isDirectory: true)
      try fm.createDirectory(at: temporary, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
      defer { try? fm.removeItem(at: temporary) }
      func openssl(_ args: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        process.arguments = args
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw LinkFailure.message("Could not generate the relay TLS identity") }
      }
      let key = temporary.appendingPathComponent("key.pem").path
      let cert = temporary.appendingPathComponent("cert.pem").path
      let bundle = temporary.appendingPathComponent("identity.p12")
      try openssl(["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", cert,
                   "-days", "3650", "-subj", "/CN=vrcode.local"])
      try openssl(["pkcs12", "-export", "-inkey", key, "-in", cert, "-out", bundle.path, "-passout", "pass:vrcode-local-identity"])
      try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: bundle.path)
      try fm.moveItem(at: bundle, to: p12)
    }
    var items: CFArray?
    let status = SecPKCS12Import(try Data(contentsOf: p12) as CFData,
      [kSecImportExportPassphrase: "vrcode-local-identity", kSecImportToMemoryOnly: true] as CFDictionary, &items)
    guard status == errSecSuccess, let item = (items as? [[String: Any]])?.first,
          let raw = item[kSecImportItemIdentity as String] else {
      throw LinkFailure.message("Could not load the relay identity (\(status))")
    }
    let secIdentity = raw as! SecIdentity
    var certificate: SecCertificate?
    guard SecIdentityCopyCertificate(secIdentity, &certificate) == errSecSuccess, let certificate,
          let identity = sec_identity_create(secIdentity) else { throw LinkFailure.message("Missing relay certificate") }
    let fingerprint = SHA256.hash(data: SecCertificateCopyData(certificate) as Data).map { String(format: "%02x", $0) }.joined()
    let tokenURL = directory.appendingPathComponent("link-token")
    if !fm.fileExists(atPath: tokenURL.path) {
      var bytes = [UInt8](repeating: 0, count: 32)
      guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw LinkFailure.message("Random key generation failed") }
      try Data(bytes).write(to: tokenURL, options: .atomic)
      try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: tokenURL.path)
    }
    let token = try Data(contentsOf: tokenURL)
    guard token.count == 32 else { throw LinkFailure.message("Invalid relay token file") }
    return LinkIdentity(identity: identity, fingerprint: fingerprint, token: token)
  }

  /// `hosts` lists every current Mac address. The Quest tries them all and remembers the one
  /// that worked, so a routed network (where Bonjour cannot cross subnets) still reconnects.
  func configuration(host: String, port: UInt16) -> String {
    var hosts = [host]
    for address in localIPv4Addresses() where !hosts.contains(address) { hosts.append(address) }
    let data = jsonBody(["host": host, "hosts": hosts, "port": Int(port), "fingerprint": fingerprint,
                         "token": token.base64EncodedString(), "name": Host.current().localizedName ?? "Mac"])
    return "vrcode:" + data.base64EncodedString()
  }
}

/// IPv4 addresses of interfaces that are up, excluding loopback and link-local (169.254/16).
/// Wi-Fi / Ethernet interfaces come first; Internet Sharing bridges and VPN tunnels follow.
func localIPv4Addresses() -> [String] {
  var list: UnsafeMutablePointer<ifaddrs>?
  guard getifaddrs(&list) == 0, let first = list else { return [] }
  defer { freeifaddrs(list) }
  var found: [(name: String, address: String)] = []
  for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
    let flags = Int32(entry.pointee.ifa_flags)
    guard let addr = entry.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
          flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0 else { continue }
    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
    guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
    let address = String(cString: host)
    if address.hasPrefix("169.254.") { continue }
    found.append((String(cString: entry.pointee.ifa_name), address))
  }
  let ordered = found.filter { $0.name.hasPrefix("en") } + found.filter { !$0.name.hasPrefix("en") }
  return ordered.map(\.address).reduce(into: []) { if !$0.contains($1) { $0.append($1) } }
}

enum LinkFailure: Error, CustomStringConvertible {
  case message(String)
  var description: String { switch self { case .message(let value): return value } }
}

final class LinkRelay {
  let listener: NWListener
  let identity: LinkIdentity
  private let queue = DispatchQueue(label: "vrcode.link")
  private var peers: [UUID: RelayPeer] = [:]

  init(port: UInt16, bridgePort: UInt16, speechPort: UInt16, directory: URL) throws {
    identity = try LinkIdentity.load(directory: directory)
    let tls = NWProtocolTLS.Options()
    sec_protocol_options_set_local_identity(tls.securityProtocolOptions, identity.identity)
    sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
    let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
    parameters.allowLocalEndpointReuse = true
    listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: port)!)
    // The fingerprint lets the Quest recognize its paired Mac even after Bonjour renames the
    // instance ("Mac (2)") or another relay advertises the same name. It is not a secret.
    listener.service = NWListener.Service(name: Host.current().localizedName ?? "VRcode Mac", type: "_vrcode._tcp",
                                          txtRecord: NWTXTRecord(["fp": identity.fingerprint, "v": "1"]).data)
    listener.newConnectionHandler = { [weak self] connection in
      guard let self, self.peers.count < 8 else { connection.cancel(); return }
      let id = UUID()
      let peer = RelayPeer(connection, token: self.identity.token, destinations: [1: bridgePort, 2: speechPort], queue: self.queue) {
        self.peers.removeValue(forKey: id)
      }
      self.peers[id] = peer
      peer.start()
    }
    listener.stateUpdateHandler = { state in
      switch state {
      case .ready: log("secure connection relay listening on \(port) (LAN / hotspots / Bluetooth / ADB)")
      // Exit so launchd (bun run mac:install) restarts the service, e.g. after a port conflict.
      case .failed(let error): log("fatal: secure relay failed: \(error)"); exit(1)
      default: break
      }
    }
  }

  func start() { listener.start(queue: queue) }
}

private final class RelayPeer {
  private let connection: NWConnection
  private let token: Data
  private let destinations: [UInt8: UInt16]
  private let queue: DispatchQueue
  private let onClose: () -> Void
  private var buffer = Data()
  private var authenticated = false
  private var closed = false
  private var channels: [UInt32: NWConnection] = [:]
  private var queuedBytes = 0
  private var upstreamBytes = 0

  init(_ connection: NWConnection, token: Data, destinations: [UInt8: UInt16], queue: DispatchQueue, onClose: @escaping () -> Void) {
    self.connection = connection; self.token = token; self.destinations = destinations; self.queue = queue; self.onClose = onClose
  }

  func start() {
    connection.stateUpdateHandler = { [weak self] state in
      if case .failed(let error) = state { log("relay TLS connection failed: \(error)"); self?.close() }
      if case .cancelled = state { self?.close() }
      if case .ready = state {
        self?.queue.asyncAfter(deadline: .now() + 10) { [weak self] in
          if self?.authenticated == false { log("relay authentication timed out after TLS"); self?.close() }
        }
      }
    }
    connection.start(queue: queue)
    // BLE can take longer than LAN to carry the TLS handshake. Start the bearer
    // deadline after TLS is ready, with a separate bound on total handshake time.
    queue.asyncAfter(deadline: .now() + 60) { [weak self] in
      if self?.authenticated == false { log("relay handshake timed out"); self?.close() }
    }
    receive()
  }

  private func receive() {
    connection.receive(minimumIncompleteLength: 1, maximumLength: 32768) { [weak self] data, _, done, error in
      guard let self, !self.closed else { return }
      if let data { self.buffer.append(data) }
      self.consume()
      if done || error != nil { self.close() } else if !self.closed { self.receive() }
    }
  }

  private func consume() {
    if !authenticated {
      guard buffer.count >= 32 else { return }
      // Constant-time comparison; no application sockets are opened before authentication.
      var difference: UInt8 = 0
      for i in 0..<32 { difference |= buffer[buffer.startIndex + i] ^ token[i] }
      guard difference == 0 else { close(); return }
      buffer = Data(buffer.dropFirst(32))
      authenticated = true
      sendRaw(Data("VRL1".utf8))
    }
    while buffer.count >= 9 {
      let start = buffer.startIndex
      let kind = buffer[start]
      let channel = buffer.readLE(UInt32.self, at: start + 1)
      let length = Int(buffer.readLE(UInt32.self, at: start + 5))
      guard length <= 32768 else { close(); return }
      guard buffer.count >= 9 + length else { return }
      let body = Data(buffer.dropFirst(9).prefix(length))
      buffer = Data(buffer.dropFirst(9 + length))
      switch kind {
      case 1:
        guard body.count == 1, let port = destinations[body[0]], channels[channel] == nil,
              channel != 0, channels.count < 32 else { close(); return }
        let upstream = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        channels[channel] = upstream
        upstream.stateUpdateHandler = { [weak self, weak upstream] state in
          guard let self, let upstream, self.channels[channel] === upstream else { return }
          switch state {
          case .ready: self.send(4, channel); self.readUpstream(channel, upstream)
          case .failed: self.drop(channel, notify: true)
          default: break
          }
        }
        upstream.start(queue: queue)
      case 2:
        guard let upstream = channels[channel] else { close(); return }
        upstreamBytes += body.count
        guard upstreamBytes <= 2 * 1024 * 1024 else { close(); return }
        upstream.send(content: body, completion: .contentProcessed { [weak self] error in
          self?.upstreamBytes -= body.count
          if error != nil { self?.drop(channel, notify: true) }
        })
      case 3: drop(channel, notify: false)
      case 6: send(7, 0)
      default: close(); return
      }
    }
  }

  private func readUpstream(_ id: UInt32, _ upstream: NWConnection) {
    upstream.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self, weak upstream] data, _, done, error in
      guard let self, let upstream, self.channels[id] === upstream else { return }
      if let data, !data.isEmpty { self.send(2, id, data) }
      if done || error != nil { self.drop(id, notify: true) } else { self.readUpstream(id, upstream) }
    }
  }

  private func send(_ kind: UInt8, _ id: UInt32, _ body: Data = Data()) {
    var packet = Data([kind]); packet.appendLE(id); packet.appendLE(UInt32(body.count)); packet.append(body)
    sendRaw(packet)
  }

  private func sendRaw(_ bytes: Data) {
    guard !closed else { return }
    queuedBytes += bytes.count
    guard queuedBytes <= 2 * 1024 * 1024 else { close(); return }
    connection.send(content: bytes, completion: .contentProcessed { [weak self] error in
      guard let self else { return }; self.queuedBytes -= bytes.count
      if error != nil { self.close() }
    })
  }

  private func drop(_ id: UInt32, notify: Bool) {
    channels.removeValue(forKey: id)?.cancel()
    if notify { send(3, id) }
  }

  private func close() {
    guard !closed else { return }; closed = true
    for upstream in channels.values { upstream.cancel() }; channels.removeAll()
    connection.cancel(); onClose()
  }
}
