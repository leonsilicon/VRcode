import Foundation
import Network

/// Accepts headset connections on the vrcode service port.
final class Server: @unchecked Sendable {
  private let listener: NWListener
  private let speech = SpeechServiceState()
  private let cmuxEndpoint: CmuxEndpoint
  private let queue = DispatchQueue(label: "vrcode.server")
  private var clients: [UUID: Client] = [:]

  /// Listens on [port]; loopback only unless [anyInterface] (USB with `adb reverse` reaches loopback).
  init(port: UInt16, anyInterface: Bool, cmuxEndpoint: CmuxEndpoint = CmuxEndpoint()) throws {
    let params = NWParameters.tcp
    params.allowLocalEndpointReuse = true
    if !anyInterface { params.requiredInterfaceType = .loopback }
    guard let nwPort = NWEndpoint.Port(rawValue: port) else { throw SpeechError.unsupportedAudio("port \(port)") }
    listener = try NWListener(using: params, on: nwPort)
    self.cmuxEndpoint = cmuxEndpoint
  }

  func setSpeechEngine(_ engine: SpeechEngine) {
    speech.engine = engine
    queue.async { [self] in clients.values.forEach { $0.welcome() } }
  }

  func start() {
    listener.stateUpdateHandler = { state in
      switch state {
      case .ready: log("listening on port \(self.listener.port?.rawValue ?? 0)")
      case .failed(let error):
        log("listener failed: \(error)")
        exit(1)
      default: break
      }
    }
    listener.newConnectionHandler = { [self] connection in
      guard clients.count < 32 else { connection.cancel(); return }
      let id = UUID()
      let client = Client(connection: connection, speech: speech, cmuxEndpoint: cmuxEndpoint,
        allowControl: Self.isLoopback(connection.endpoint)) { [weak self] in
        self?.queue.async { self?.clients.removeValue(forKey: id) }
      }
      clients[id] = client
      client.start()
    }
    listener.start(queue: queue)
  }

  private static func isLoopback(_ endpoint: NWEndpoint) -> Bool {
    guard case .hostPort(let host, _) = endpoint else { return false }
    switch host {
    case .ipv4(let address): return address.rawValue.first == 127
    case .ipv6(let address): return address == IPv6Address.loopback
    default: return false
    }
  }
}

final class SpeechServiceState: @unchecked Sendable {
  private let lock = NSLock()
  private var value: SpeechEngine?
  var engine: SpeechEngine? {
    get { lock.withLock { value } }
    set { lock.withLock { value = newValue } }
  }
}

/// One headset connection: frames in order, one utterance at a time.
final class Client: @unchecked Sendable {
  private let connection: NWConnection
  private let speech: SpeechServiceState
  private let cmuxEndpoint: CmuxEndpoint
  private let onClose: () -> Void
  private let allowControl: Bool
  private lazy var cmux = CmuxService(endpoint: cmuxEndpoint) { [weak self] response in
    self?.send(.cmuxEvent, body: jsonBody(response))
  }
  private let queue = DispatchQueue(label: "vrcode.client")
  private let sendLock = NSLock()
  private var sendSequence: UInt32 = 0
  private var pendingSendBytes = 0
  private var didClose = false
  private var reader = FrameReader()
  private let frames: AsyncStream<Frame>.Continuation
  private let frameStream: AsyncStream<Frame>
  private var utterance: Utterance?
  private let name: String

  init(connection: NWConnection, speech: SpeechServiceState, cmuxEndpoint: CmuxEndpoint, allowControl: Bool, onClose: @escaping () -> Void) {
    self.connection = connection
    self.speech = speech
    self.cmuxEndpoint = cmuxEndpoint
    self.onClose = onClose
    self.allowControl = allowControl
    name = "\(connection.endpoint)"
    (frameStream, frames) = AsyncStream<Frame>.makeStream()
  }

  func start() {
    log("\(name): connected")
    connection.stateUpdateHandler = { [weak self] state in
      guard let self else { return }
      switch state {
      case .failed(let error):
        log("\(self.name): failed: \(error)")
        self.close()
      case .cancelled:
        self.close()
      default: break
      }
    }
    connection.start(queue: queue)
    receive()
    Task { await self.process() }
  }

  private func receive() {
    connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
      guard let self else { return }
      if let data, !data.isEmpty {
        self.reader.append(data)
        do {
          while let frame = try self.reader.next() { self.frames.yield(frame) }
        } catch {
          log("\(self.name): \(error)")
          self.close()
          return
        }
      }
      if isComplete || error != nil {
        log("\(self.name): disconnected")
        self.close()
      } else {
        self.receive()
      }
    }
  }

  private func process() async {
    for await frame in frameStream {
      await handle(frame)
    }
    await utterance?.cancel()
    utterance = nil
    cmux.close()
  }

  func welcome() {
    let engine = speech.engine
    send(.welcome, body: jsonBody([
      "service": "vrcode-mac", "protocol": Int(protocolVersion), "locale": engine?.locale.identifier ?? "",
      "ready": engine?.ready ?? false, "cmux": allowControl,
    ]))
  }

  private func handle(_ frame: Frame) async {
    switch Kind(rawValue: frame.kind) {
    case .hello:
      welcome()
    case .cmuxRequest:
      if !allowControl {
        send(.cmuxEvent, body: jsonBody(CmuxService.failure(id: frame.json()?["id"] as? String ?? "",
          message: "Use the paired VRcode relay for cmux control", code: "vrcode.paired_relay_required")))
      } else if let request = frame.json() { cmux.request(request) }
      else { send(.cmuxEvent, body: jsonBody(CmuxService.failure(id: "", message: "Expected a cmux request object"))) }
    case .audioStart:
      await utterance?.cancel()
      utterance = nil
      let info = frame.json() ?? [:]
      let encoding = info["encoding"] as? String ?? "pcm_s16le"
      guard encoding == "pcm_s16le" else {
        sendError("unsupported encoding \(encoding)", id: frame.id)
        return
      }
      guard let engine = speech.engine, engine.ready else {
        sendError("speech model is still downloading", id: frame.id)
        return
      }
      let sampleRate = (info["sampleRate"] as? NSNumber)?.doubleValue ?? 16000
      let channels = (info["channels"] as? NSNumber)?.intValue ?? 1
      let id = frame.id
      do {
        utterance = try await Utterance.start(
          id: id, engine: engine, sampleRate: sampleRate, channels: channels
        ) { [weak self] update in
          self?.send(.transcript, id: id, body: jsonBody([
            "final": update.final, "volatile": update.volatile, "done": update.done,
          ]))
        }
        log("\(name): utterance \(id) started (\(Int(sampleRate)) Hz)")
      } catch {
        sendError("\(error)", id: id)
      }
    case .audio:
      if let u = utterance, u.id == frame.id { u.feed(frame.body) }
    case .audioEnd:
      guard let u = utterance, u.id == frame.id else { return }
      utterance = nil
      await u.finish()
      log("\(name): utterance \(u.id) finished")
    case .audioCancel:
      guard let u = utterance, u.id == frame.id else { return }
      utterance = nil
      await u.cancel()
      log("\(name): utterance \(u.id) cancelled")
    case .aiRequest:
      // Runs beside the audio path: a slow AI reply never holds up frames behind it.
      let info = frame.json() ?? [:]
      let id = frame.id
      let transcript = info["transcript"] as? String ?? ""
      let screen = info["screen"] as? String ?? ""
      let title = info["title"] as? String ?? ""
      Task { [weak self] in
        do {
          let text = try await AiService.complete(transcript: transcript, screen: screen, title: title)
          log("ai \(id): \(text.count) chars")
          self?.send(.aiResult, id: id, body: jsonBody(["text": text, "model": AiService.model]))
        } catch {
          self?.sendError("\(error)", id: id)
        }
      }
    case .welcome, .transcript, .error, .cmuxEvent, .aiResult, .none:
      break
    }
  }

  private func send(_ kind: Kind, id: UInt32 = 0, body: Data = Data()) {
    sendLock.withLock {
      guard !didClose else { return }
      guard body.count <= maxBodyLength, pendingSendBytes + headerLength + body.count <= 8 * 1024 * 1024 else {
        connection.cancel()
        return
      }
      sendSequence &+= 1
      let data = encodeFrame(kind: kind, id: id, sequence: sendSequence, body: body)
      pendingSendBytes += data.count
      connection.send(content: data, completion: .contentProcessed { [weak self] _ in
        self?.sendLock.withLock { self?.pendingSendBytes -= data.count }
      })
    }
  }

  private func sendError(_ message: String, id: UInt32) {
    log("\(name): error: \(message)")
    send(.error, id: id, body: jsonBody(["message": message]))
  }

  private func close() {
    let first = sendLock.withLock { () -> Bool in
      if didClose { return false }
      didClose = true
      return true
    }
    guard first else { return }
    frames.finish()
    connection.cancel()
    onClose()
  }
}
