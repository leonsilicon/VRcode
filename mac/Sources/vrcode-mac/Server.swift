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
  /// Agent runs in flight, by AI_REQUEST id, so AI_CANCEL or a disconnect can stop them.
  private let aiLock = NSLock()
  private var aiTasks: [UInt32: Task<Void, Never>] = [:]

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
    aiLock.withLock { aiTasks.values.forEach { $0.cancel() }; aiTasks.removeAll() }
    cmux.close()
  }

  func welcome() {
    let engine = speech.engine
    send(.welcome, body: jsonBody([
      "service": "vrcode-mac", "protocol": Int(protocolVersion), "locale": engine?.locale.identifier ?? "",
      "ready": engine?.ready ?? false, "cmux": allowControl, "settings": true,
    ]))
  }

  private func handle(_ frame: Frame) async {
    switch Kind(rawValue: frame.kind) {
    case .hello:
      welcome()
      // VRcode Mac mirrors the Quest's settings (SettingsHub). Loopback only, like cmux control.
      if allowControl, frame.json()?["client"] as? String == "vrcode-mac-app" { SettingsHub.shared.addObserver(self) }
    case .settings:
      if let state = frame.json() { SettingsHub.shared.headset(self, state: state) }
    case .settingsSet:
      if let problem = SettingsHub.shared.set(frame.json()?["values"] as? [String: Any] ?? [:], from: self) { sendError(problem, id: 0) }
    case .settingsAction:
      if let problem = SettingsHub.shared.action(frame.json()?["action"] as? String ?? "", from: self) { sendError(problem, id: 0) }
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
      let cursor = info["cursor"] as? [String: Any]
      let pane = AiService.Pane(
        screen: info["screen"] as? String ?? "",
        title: info["title"] as? String ?? "",
        cursorRow: cursor?["visible"] as? Bool == false ? nil : (cursor?["row"] as? NSNumber)?.intValue,
        cursorCol: (cursor?["col"] as? NSNumber)?.intValue ?? 0
      )
      let provider = AiProvider.from(info["provider"] as? String)
      let model = provider.model(info["model"] as? String)
      if info["agent"] as? Bool == true {
        runAgent(id: id, provider: provider, model: model, transcript: transcript, pane: pane, terminal: info["terminal"] as? String)
        return
      }
      Task { [weak self] in
        do {
          let steps = try await AiService.complete(provider: provider, model: model, transcript: transcript, pane: pane)
          log("ai \(id) (\(provider.rawValue) \(model)): \(AiKeystroke.summary(steps))")
          // `text` is what the steps type, for headset builds that predate `keys`.
          let typed = steps.compactMap { step -> String? in if case .text(let text) = step { return text } else { return nil } }.joined()
          self?.send(.aiResult, id: id, body: jsonBody([
            "keys": steps.map(\.json), "text": typed, "provider": provider.rawValue, "model": model,
          ]))
        } catch {
          self?.sendError("\(error)", id: id)
        }
      }
    case .aiCancel:
      let task = aiLock.withLock { aiTasks.removeValue(forKey: frame.id) }
      if let task {
        task.cancel()
        log("ai \(frame.id): cancelled by the headset")
      }
    case .welcome, .transcript, .error, .cmuxEvent, .aiResult, .aiProgress, .none:
      break
    }
  }

  func send(_ kind: Kind, id: UInt32 = 0, body: Data = Data()) {
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

  /**
   * Thinking mode: runs the provider's agent CLI in the target terminal's working directory (from
   * cmux), streams what it does as AI_PROGRESS, then answers with AI_RESULT (adding `summary`) or
   * ERROR. A cancelled run (AI_CANCEL, or the headset left) sends nothing more.
   */
  private func runAgent(id: UInt32, provider: AiProvider, model: String, transcript: String, pane: AiService.Pane, terminal: String?) {
    let endpoint = cmuxEndpoint
    let report: @Sendable (AiAgent.Progress) -> Void = { [weak self] progress in
      self?.send(.aiProgress, id: id, body: jsonBody(progress.json))
    }
    let task = Task { [weak self] in
      let cwd = terminal.flatMap { AiAgent.workingDirectory(endpoint: endpoint, terminal: $0) }
      let started = Date()
      do {
        let answer = try await AiAgent.run(provider: provider, model: model, transcript: transcript, pane: pane, cwd: cwd, progress: report)
        let seconds = String(format: "%.1f", Date().timeIntervalSince(started))
        log("ai \(id) (agent \(provider.rawValue) \(model), \(seconds) s): \(AiKeystroke.summary(answer.steps))")
        let typed = answer.steps.compactMap { step -> String? in if case .text(let text) = step { return text } else { return nil } }.joined()
        self?.send(.aiResult, id: id, body: jsonBody([
          "keys": answer.steps.map(\.json), "text": typed, "summary": answer.summary, "provider": provider.rawValue, "model": model,
        ]))
      } catch is CancellationError {
        log("ai \(id): agent stopped")
      } catch {
        if !Task.isCancelled { self?.sendError("\(error)", id: id) }
      }
      self?.aiLock.withLock { _ = self?.aiTasks.removeValue(forKey: id) }
    }
    aiLock.withLock { aiTasks[id] = task }
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
    SettingsHub.shared.remove(self)
    frames.finish()
    connection.cancel()
    onClose()
  }
}
