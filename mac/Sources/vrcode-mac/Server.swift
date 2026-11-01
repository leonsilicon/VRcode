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
  /// [locale] is the speech language, also for ElevenLabs before the on-device model is ready.
  init(port: UInt16, anyInterface: Bool, cmuxEndpoint: CmuxEndpoint = CmuxEndpoint(), locale: String = "en-US") throws {
    let params = NWParameters.tcp
    params.allowLocalEndpointReuse = true
    if !anyInterface { params.requiredInterfaceType = .loopback }
    guard let nwPort = NWEndpoint.Port(rawValue: port) else { throw SpeechError.unsupportedAudio("port \(port)") }
    listener = try NWListener(using: params, on: nwPort)
    self.cmuxEndpoint = cmuxEndpoint
    speech.locale = locale
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
  private var localeValue = "en-US"
  var engine: SpeechEngine? {
    get { lock.withLock { value } }
    set { lock.withLock { value = newValue } }
  }
  var locale: String {
    get { lock.withLock { localeValue } }
    set { lock.withLock { localeValue = newValue } }
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
  private var utterance: (any SpeechUtterance)?
  /// AI-mic dictations' audio (AUDIO_START `ai`), by utterance id, for the AI to hear (AiHearing);
  /// [recordingOrder] is oldest first. Touched only while handling frames, one at a time.
  private var recordings: [UInt32: AiHearing.Audio] = [:]
  private var recordingOrder: [UInt32] = []
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
    recordings.removeAll()
    recordingOrder.removeAll()
    aiLock.withLock { aiTasks.values.forEach { $0.cancel() }; aiTasks.removeAll() }
    cmux.close()
  }

  func welcome() {
    let engine = speech.engine
    // With an ElevenLabs key, speech works before (or without) the on-device model.
    send(.welcome, body: jsonBody([
      "service": "vrcode-mac", "protocol": Int(protocolVersion), "locale": engine?.locale.identifier ?? speech.locale,
      "ready": engine?.ready == true || ElevenLabs.apiKey() != nil, "cmux": allowControl, "settings": true,
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
      let sampleRate = (info["sampleRate"] as? NSNumber)?.doubleValue ?? 16000
      let channels = (info["channels"] as? NSNumber)?.intValue ?? 1
      let id = frame.id
      // Kept whether or not a recognizer is ready: the AI hears the recording itself.
      if info["ai"] as? Bool == true { record(id, sampleRate: Int(sampleRate), channels: channels) }
      do {
        // ElevenLabs Scribe with a key, else the on-device recognizer (ElevenLabsTranscription.swift).
        guard let started = try await startUtterance(
          id: id, sampleRate: sampleRate, channels: channels, locale: speech.locale, engine: speech.engine,
          onUpdate: { [weak self] update in
            self?.send(.transcript, id: id, body: jsonBody([
              "final": update.final, "volatile": update.volatile, "done": update.done,
            ]))
          },
          onError: { [weak self] message in self?.sendError(message, id: id) }
        ) else {
          sendError("speech model is still downloading (or add an ElevenLabs key in VRcode Mac)", id: id)
          return
        }
        utterance = started
        log("\(name): utterance \(id) started (\(Int(sampleRate)) Hz, \(started is ScribeUtterance ? "ElevenLabs" : "on-device"))")
      } catch {
        sendError("\(error)", id: id)
      }
    case .audio:
      if let u = utterance, u.id == frame.id { u.feed(frame.body) }
      // Read the size first: holding a copy of the recording while appending would copy all of it.
      if let size = recordings[frame.id]?.pcm.count, size + frame.body.count <= Self.maxRecordingBytes {
        recordings[frame.id]?.pcm.append(frame.body)
      }
    case .audioEnd:
      guard let u = utterance, u.id == frame.id else { return }
      utterance = nil
      await u.finish()
      log("\(name): utterance \(u.id) finished")
    case .audioCancel:
      forget(frame.id)
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
      let audio = recorded((info["utterances"] as? [NSNumber])?.map(\.uint32Value) ?? [])
      if info["agent"] as? Bool == true {
        runAgent(
          id: id, provider: provider, model: model, transcript: transcript, audio: audio, pane: pane, terminal: info["terminal"] as? String)
        return
      }
      Task { [weak self] in
        let transcript = await self?.hear(id: id, audio: audio, transcript: transcript, pane: pane, provider: provider, model: model) ?? transcript
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
   * Agent mode: the provider's coding agent works on the user's behalf (AiAgent.swift), starting in
   * the target terminal's working directory (from cmux) and acting in that terminal itself. What it
   * does streams as AI_PROGRESS; it ends with AI_RESULT carrying its `summary` and no `keys` (it
   * already typed what it meant to), or ERROR. A cancelled run (AI_CANCEL, or the headset left)
   * sends nothing more.
   */
  private func runAgent(
    id: UInt32, provider: AiProvider, model: String, transcript: String, audio: AiHearing.Audio?, pane: AiService.Pane,
    terminal: String?
  ) {
    let endpoint = cmuxEndpoint
    let report: @Sendable (AiAgent.Progress) -> Void = { [weak self] progress in
      self?.send(.aiProgress, id: id, body: jsonBody(progress.json))
    }
    let task = Task { [weak self] in
      let transcript = await self?.hear(id: id, audio: audio, transcript: transcript, pane: pane, provider: provider, model: model) ?? transcript
      let cwd = terminal.flatMap { AiAgent.workingDirectory(endpoint: endpoint, terminal: $0) }
      let cmux = terminal == nil ? nil : AiAgent.cmux(endpoint)
      let started = Date()
      do {
        let summary = try await AiAgent.run(
          provider: provider, model: model, transcript: transcript, pane: pane, terminal: terminal, cwd: cwd, cmux: cmux, progress: report)
        let seconds = String(format: "%.1f", Date().timeIntervalSince(started))
        log("ai \(id) (agent \(provider.rawValue) \(model), \(seconds) s): done, \(summary.count)-char summary")
        self?.send(.aiResult, id: id, body: jsonBody([
          "keys": [Any](), "text": "", "summary": summary, "acted": true, "provider": provider.rawValue, "model": model,
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

  /**
   * What the user said to the AI: [audio], their recording, as a model heard it (AiHearing), also
   * sent to the headset as AI_PROGRESS `heard` so their pane shows it. [transcript], the live
   * preview, when there is no recording, no Gemini or OpenAI key to hear it with, or hearing fails.
   */
  private func hear(
    id: UInt32, audio: AiHearing.Audio?, transcript: String, pane: AiService.Pane, provider: AiProvider, model: String
  ) async -> String {
    guard let audio else { return transcript }
    guard let listener = AiHearing.listener(provider: provider, model: model) else {
      log("ai \(id): no Gemini or OpenAI key to hear the recording with (Claude's API takes no audio), so the AI gets the live transcript; add one in VRcode Mac")
      return transcript
    }
    let started = Date()
    do {
      let heard = try await AiHearing.hear(audio, pane: pane, by: listener)
      let took = String(format: "%.1f", Date().timeIntervalSince(started)), length = String(format: "%.1f", audio.seconds)
      log("ai \(id): \(listener.model) heard \(length) s of audio in \(took) s: \"\(heard)\" (live: \"\(transcript)\")")
      guard !heard.isEmpty else { return transcript }
      send(.aiProgress, id: id, body: jsonBody(["kind": "heard", "text": heard]))
      return heard
    } catch {
      if !Task.isCancelled { log("ai \(id): \(listener.model) couldn't hear it (\(error)); the AI gets the live transcript") }
      return transcript
    }
  }

  /// At most this much audio is kept per recording, and heard per request (about 3 minutes at 16 kHz mono).
  private static let maxRecordingBytes = 6 * 1024 * 1024
  /// Recordings kept per connection; older ones are dropped.
  private static let maxRecordings = 12

  /// Starts keeping utterance [id]'s audio for the AI to hear.
  private func record(_ id: UInt32, sampleRate: Int, channels: Int) {
    guard (8000...48000).contains(sampleRate), (1...2).contains(channels) else { return }
    forget(id)
    recordings[id] = AiHearing.Audio(pcm: Data(), sampleRate: sampleRate, channels: channels)
    recordingOrder.append(id)
    while recordingOrder.count > Self.maxRecordings { recordings.removeValue(forKey: recordingOrder.removeFirst()) }
  }

  private func forget(_ id: UInt32) {
    guard recordings.removeValue(forKey: id) != nil else { return }
    recordingOrder.removeAll { $0 == id }
  }

  /// [utterances]' recordings joined in order; nil when there are none, any is gone, or they differ in format.
  private func recorded(_ utterances: [UInt32]) -> AiHearing.Audio? {
    guard let first = utterances.first.flatMap({ recordings[$0] }) else { return nil }
    var audio = AiHearing.Audio(pcm: Data(), sampleRate: first.sampleRate, channels: first.channels)
    for id in utterances {
      guard let part = recordings[id], part.sampleRate == audio.sampleRate, part.channels == audio.channels,
        audio.pcm.count + part.pcm.count <= Self.maxRecordingBytes
      else { return nil }
      audio.pcm.append(part.pcm)
    }
    return audio.pcm.isEmpty ? nil : audio
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
