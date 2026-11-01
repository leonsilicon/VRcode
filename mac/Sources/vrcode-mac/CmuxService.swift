import AppKit
import Darwin
import Foundation

/// Public cmux.protocol/2 only. No shell commands, synthesized keys, app activation, or Mac UI.
final class CmuxService: @unchecked Sendable {
  private let queue = DispatchQueue(label: "vrcode.cmux")
  private let endpoint: CmuxEndpoint
  private let emit: ([String: Any]) -> Void
  private var descriptor: Int32 = -1
  private var source: DispatchSourceRead?
  private var buffer = Data()
  private var pending: [String: UUID] = [:]
  private var cancelTargets: [String: (id: String, token: UUID?)] = [:]
  private var closed = false

  init(endpoint: CmuxEndpoint, emit: @escaping ([String: Any]) -> Void) {
    self.endpoint = endpoint
    self.emit = emit
  }

  func request(_ object: [String: Any]) {
    queue.async { [self] in
      let id = object["id"] as? String ?? ""
      do {
        guard !closed else { return }
        guard object["protocol"] as? String == "cmux.protocol/2", object["type"] as? String == "request",
              !id.isEmpty, id.utf8.count <= 128,
              let operation = object["operation"] as? String,
              var params = object["params"] as? [String: Any],
              pending[id] == nil, pending.count < 128 else {
          throw CmuxFailure("Invalid or duplicate cmux request")
        }
        guard let kind = cmuxOperationClasses[operation] ?? (operation.hasPrefix("vrcode.clipboard.") ? "local" : nil) else {
          throw CmuxFailure("Unknown public cmux operation: \(operation)")
        }
        if operation == "vrcode.clipboard.read" || operation == "vrcode.clipboard.write" {
          clipboard(operation, id: id, params: params)
          return
        }
        guard kind != "local" else { throw CmuxFailure("Unknown clipboard operation") }
        if kind == "mutation" {
          guard let key = object["idempotency_key"] as? String, !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                key.utf8.count <= 128, !key.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else {
            throw CmuxFailure("Mutations require a stable idempotency key")
          }
        }
        // The Unix socket pins the machine/session. Callers cannot route to another Mac endpoint.
        if !["machine.list", "request.cancel"].contains(operation) { params["machine"] = "current" }
        if !["machine.list", "machine.get", "session.list", "request.cancel"].contains(operation) { params["session"] = "current" }
        var request = object
        request["params"] = params
        var line = try JSONSerialization.data(withJSONObject: request)
        line.append(10)
        guard line.count <= maxBodyLength else { throw CmuxFailure("cmux request is too large") }
        try connect()
        let token = UUID()
        pending[id] = token
        if operation == "request.cancel", let target = params["request_id"] as? String {
          cancelTargets[id] = (target, pending[target])
        }
        try line.withUnsafeBytes { raw in
          var offset = 0
          while offset < raw.count {
            let written = Darwin.write(descriptor, raw.baseAddress!.advanced(by: offset), raw.count - offset)
            if written < 0 && errno == EINTR { continue }
            guard written > 0 else { throw CmuxFailure("cmux socket write failed; mutation outcome may be unknown") }
            offset += written
          }
        }
        if !["terminal.wait", "terminal.wait_exit"].contains(operation) {
          queue.asyncAfter(deadline: .now() + 30) { [weak self] in
            guard let self, self.pending[id] == token else { return }
            self.fail("cmux did not respond; refresh state before repeating a mutation")
          }
        }
      } catch {
        if pending[id] != nil { fail("\(error)") }
        else { emit(Self.failure(id: id, message: "\(error)")) }
      }
    }
  }

  private func connect() throws {
    if descriptor >= 0 { return }
    let path = try endpoint.resolve()
    var address = sockaddr_un()
    address.sun_family = sa_family_t(AF_UNIX)
    let bytes = Array(path.utf8) + [UInt8(0)]
    guard bytes.count <= MemoryLayout.size(ofValue: address.sun_path) else { throw CmuxFailure("cmux socket path is too long") }
    withUnsafeMutableBytes(of: &address.sun_path) { $0.copyBytes(from: bytes) }
    address.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
    let fd = Darwin.socket(AF_UNIX, SOCK_STREAM, 0)
    guard fd >= 0 else { throw CmuxFailure("Could not create cmux socket") }
    var noSignal: Int32 = 1
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &noSignal, socklen_t(MemoryLayout.size(ofValue: noSignal)))
    var timeout = timeval(tv_sec: 3, tv_usec: 0)
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout.size(ofValue: timeout)))
    let result = withUnsafePointer(to: &address) { ptr in
      ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
    }
    guard result == 0 else { Darwin.close(fd); throw CmuxFailure("Could not connect to cmux session at \(path)") }
    descriptor = fd
    let reader = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
    reader.setEventHandler { [weak self] in self?.receive(fd) }
    reader.setCancelHandler { Darwin.close(fd) }
    source = reader
    reader.resume()
  }

  private func receive(_ expected: Int32) {
    guard descriptor >= 0, descriptor == expected else { return }
    var bytes = [UInt8](repeating: 0, count: 65536)
    let count = Darwin.read(descriptor, &bytes, bytes.count)
    if count < 0 && errno == EINTR { return }
    guard count > 0 else { fail("cmux connection closed; in-flight mutation outcomes may be unknown"); return }
    buffer.append(contentsOf: bytes.prefix(count))
    while let newline = buffer.firstIndex(of: 10) {
      let line = Data(buffer[..<newline])
      buffer = Data(buffer[buffer.index(after: newline)...])
      guard line.count <= maxBodyLength,
            let response = (try? JSONSerialization.jsonObject(with: line)) as? [String: Any] else {
        fail("Invalid cmux response"); return
      }
      if response["type"] as? String == "response", let id = response["id"] as? String {
        pending.removeValue(forKey: id)
        if let target = cancelTargets.removeValue(forKey: id), response["ok"] as? Bool == true,
           (response["result"] as? [String: Any])?["canceled"] as? Bool == true,
           let token = target.token, pending[target.id] == token {
          // cmux guarantees a cancelled wait sends no response; release its pending slot here.
          pending.removeValue(forKey: target.id)
        }
      }
      emit(response)
    }
    if buffer.count > maxBodyLength { fail("cmux response is too large") }
  }

  private func clipboard(_ operation: String, id: String, params: [String: Any]) {
    DispatchQueue.main.async { [emit] in
      let board = NSPasteboard.general
      if operation == "vrcode.clipboard.write" {
        guard let text = params["text"] as? String else {
          emit(Self.failure(id: id, message: "Clipboard write requires text")); return
        }
        board.clearContents()
        board.setString(text, forType: .string)
        emit(Self.success(id: id, result: ["copied": true]))
      } else { emit(Self.success(id: id, result: ["text": board.string(forType: .string) ?? ""])) }
    }
  }

  private func fail(_ message: String) {
    if descriptor >= 0 { Darwin.shutdown(descriptor, SHUT_RDWR) }
    source?.cancel()
    source = nil
    descriptor = -1
    buffer.removeAll()
    let requests = pending
    pending.removeAll()
    cancelTargets.removeAll()
    for (id, _) in requests { emit(Self.failure(id: id, message: message, code: "vrcode.connection_lost")) }
    emit(["protocol": "cmux.protocol/2", "type": "connection_end", "message": message])
  }

  func close() {
    queue.async { [self] in closed = true; fail("Quest control connection closed") }
  }

  private static func success(id: String, result: [String: Any]) -> [String: Any] {
    ["protocol": "cmux.protocol/2", "type": "response", "id": id, "ok": true, "result": result]
  }

  static func failure(id: String, message: String, code: String = "vrcode.invalid_request") -> [String: Any] {
    ["protocol": "cmux.protocol/2", "type": "response", "id": id, "ok": false,
     "error": ["code": code, "message": message, "retryable": false]]
  }
}

struct CmuxFailure: Error, CustomStringConvertible {
  let description: String
  init(_ message: String) { description = message }
}

struct CmuxEndpoint {
  var session = "main"
  var socket: String?

  /// The cmux CLI resolves its own environment, long-name socket hashing, and durable daemon.
  /// `server ensure` starts only the background owner; it never attaches a terminal frontend.
  func resolve() throws -> String {
    if let socket { return socket }
    let process = Process()
    let home = FileManager.default.homeDirectoryForCurrentUser.path
    let paths = (ProcessInfo.processInfo.environment["PATH"] ?? "").split(separator: ":").map(String.init)
    let override = ProcessInfo.processInfo.environment["CMUX_BIN"].map { [$0] } ?? []
    guard let binary = (override + paths.map { $0 + "/cmux" } + [home + "/.cmux/bin/cmux"])
      .first(where: FileManager.default.isExecutableFile(atPath:)) else { throw CmuxFailure("cmux CLI is not installed") }
    process.executableURL = URL(fileURLWithPath: binary)
    process.arguments = ["--session", session, "--json", "server", "ensure"]
    let output = Pipe()
    process.standardOutput = output
    process.standardError = FileHandle.nullDevice
    try process.run()
    let data = output.fileHandleForReading.readDataToEndOfFile()
    process.waitUntilExit()
    guard process.terminationStatus == 0,
          let status = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
          let path = status["socket"] as? String else { throw CmuxFailure("Could not start or locate cmux session \(session)") }
    return path
  }
}
