import Foundation

/// Live speech-to-text from ElevenLabs Scribe v2 Realtime.
///
/// With an ElevenLabs key in VRcode Mac (Keychain service `dev.improve.vrcode`, account
/// `elevenlabs-api-key`; else `ELEVENLABS_API_KEY`, else `~/.config/vrcode/elevenlabs-api-key`),
/// each utterance streams to ElevenLabs' Realtime Speech to Text WebSocket instead of Apple's
/// on-device recognizer (Transcription.swift), so both mics' TRANSCRIPT frames come from Scribe:
/// its partial transcript is `volatile`, its committed segments are `final`. One utterance is one
/// manual-commit session: AUDIO_END commits what was said and reports it `done`. Developer names
/// (AiHearing's vocabulary) go along as keyterms, so "Claude Code" isn't written "claw code", and
/// filler words are dropped (`no_verbatim`). The key is read per utterance, so adding or removing
/// it takes effect on the next dictation.
///
/// When Scribe can't take an utterance (no connection, a bad key, quota, a server error), the
/// on-device recognizer takes over if its model is ready: it hears the utterance's audio so far and
/// the rest, and its transcript replaces Scribe's. Without it the headset gets an ERROR.
enum ElevenLabs {
  static let keyAccount = "elevenlabs-api-key"
  static let keyEnvironment = "ELEVENLABS_API_KEY"
  static let model = "scribe_v2_realtime"

  static func apiKey() -> String? { AiService.key(account: keyAccount, environment: keyEnvironment) }

  /// The rates Scribe's `pcm_<rate>` formats take (16-bit little-endian, mono only).
  static let sampleRates: Set<Int> = [8000, 16000, 22050, 24000, 44100, 48000]

  /// Realtime sessions take at most 50 keyterms of at most 20 characters each.
  static let keyterms = Array(AiHearing.vocabulary.filter { $0.count <= 20 }.prefix(50))

  /// Sent with an utterance's first audio as `previous_text` (best kept under 50 characters).
  static let context = "A software developer dictating in a terminal."

  /// The ISO 639-1 language of a locale identifier such as `en-US`.
  static func language(_ locale: String) -> String? { Locale(identifier: locale).language.languageCode?.identifier }

  /// The Realtime session URL for one utterance. VRCODE_ELEVENLABS_API_BASE points it at another
  /// server (`scripts/tests/elevenlabs-speech.test.ts` uses a fake).
  static func sessionURL(sampleRate: Int, language: String?) -> URL? {
    let base = ProcessInfo.processInfo.environment["VRCODE_ELEVENLABS_API_BASE"].flatMap { $0.isEmpty ? nil : $0 }
      ?? "wss://api.elevenlabs.io"
    guard var components = URLComponents(string: base + "/v1/speech-to-text/realtime") else { return nil }
    var items = [
      URLQueryItem(name: "model_id", value: model),
      URLQueryItem(name: "audio_format", value: "pcm_\(sampleRate)"),
      URLQueryItem(name: "commit_strategy", value: "manual"),
      URLQueryItem(name: "no_verbatim", value: "true"),
    ]
    if let language { items.append(URLQueryItem(name: "language_code", value: language)) }
    items += keyterms.map { URLQueryItem(name: "keyterms", value: $0) }
    components.queryItems = items
    return components.url
  }
}

/// One utterance being recognized: the headset's PCM in, TRANSCRIPT updates out.
protocol SpeechUtterance: AnyObject, Sendable {
  var id: UInt32 { get }
  /// Raw little-endian 16-bit PCM in the utterance's format.
  func feed(_ pcm: Data)
  /// Ends the input and reports the last update with `done`.
  func finish() async
  /// Drops the utterance without reporting anything more.
  func cancel() async
}

extension Utterance: SpeechUtterance {}

/// Starts recognizing utterance [id]: with Scribe when there is an ElevenLabs key and it takes the
/// format, else on device with [engine] once its model is ready; nil when neither can. [onError]
/// reports a failure that ends the utterance (Scribe's, with no on-device recognizer to take over).
func startUtterance(
  id: UInt32, sampleRate: Double, channels: Int, locale: String, engine: SpeechEngine?,
  onUpdate: @escaping @Sendable (Utterance.Update) -> Void, onError: @escaping @Sendable (String) -> Void
) async throws -> (any SpeechUtterance)? {
  var onDevice: (@Sendable () async throws -> any SpeechUtterance)?
  if let engine, engine.ready {
    onDevice = { try await Utterance.start(id: id, engine: engine, sampleRate: sampleRate, channels: channels, onUpdate: onUpdate) }
  }
  if let key = ElevenLabs.apiKey(),
    let scribe = ScribeUtterance.start(
      id: id, key: key, sampleRate: sampleRate, channels: channels, language: ElevenLabs.language(engine?.locale.identifier ?? locale),
      onUpdate: onUpdate, onError: onError, fallback: onDevice)
  {
    return scribe
  }
  return try await onDevice?()
}

/// One utterance as one ElevenLabs Realtime session with manual commit.
final class ScribeUtterance: SpeechUtterance, @unchecked Sendable {
  private enum State {
    /// The session is open or opening; audio goes to it.
    case streaming
    /// AUDIO_END: the commit is sent (or waits for the session), and finish() waits for its text.
    case finishing
    /// Scribe failed and the on-device recognizer is starting.
    case replacing
    /// The on-device recognizer has the audio so far, gets the rest and reports for itself.
    case replaced(any SpeechUtterance)
    /// Done reported, cancelled, or failed with nothing to take over.
    case over
  }

  private enum Outcome {
    case committed
    /// No commit came back but there is text: the best guess there is.
    case bestGuess
    case replaced(Task<(any SpeechUtterance)?, Never>)
    case over
  }

  static let connectTimeout: TimeInterval = 5
  static let commitTimeout: TimeInterval = 6
  /// Audio kept for the on-device recognizer to replay (about 3 minutes at 16 kHz mono).
  static let maxKept = 6 * 1024 * 1024

  let id: UInt32
  private let socket: URLSessionWebSocketTask
  private let sampleRate: Int
  private let onUpdate: @Sendable (Utterance.Update) -> Void
  private let onError: @Sendable (String) -> Void
  private let fallback: (@Sendable () async throws -> any SpeechUtterance)?
  private let started = Date()

  // Everything below is touched only on [queue].
  private let queue: DispatchQueue
  private var state = State.streaming
  /// session_started arrived, so audio can be sent.
  private var open = false
  private var sentAudio = false
  private var unsent = Data()
  private var fedBytes = 0
  /// All of the utterance's audio, for the on-device recognizer if Scribe fails; nil past [maxKept].
  private var heard: Data? = Data()
  private var finalText = ""
  private var volatileText = ""
  private var finishedAt: Date?
  private var waiter: CheckedContinuation<Outcome, Never>?
  private var replacement: Task<(any SpeechUtterance)?, Never>?

  private init(
    id: UInt32, socket: URLSessionWebSocketTask, sampleRate: Int, onUpdate: @escaping @Sendable (Utterance.Update) -> Void,
    onError: @escaping @Sendable (String) -> Void, fallback: (@Sendable () async throws -> any SpeechUtterance)?
  ) {
    self.id = id
    self.socket = socket
    self.sampleRate = sampleRate
    self.onUpdate = onUpdate
    self.onError = onError
    self.fallback = fallback
    queue = DispatchQueue(label: "vrcode.scribe.\(id)")
  }

  /// Opens the session for mono 16-bit PCM at [sampleRate]; audio fed before it opens is held.
  /// Nil when Scribe doesn't take the format.
  static func start(
    id: UInt32, key: String, sampleRate: Double, channels: Int, language: String?,
    onUpdate: @escaping @Sendable (Utterance.Update) -> Void, onError: @escaping @Sendable (String) -> Void,
    fallback: (@Sendable () async throws -> any SpeechUtterance)?
  ) -> ScribeUtterance? {
    let rate = Int(sampleRate)
    guard channels == 1, Double(rate) == sampleRate, ElevenLabs.sampleRates.contains(rate),
      let url = ElevenLabs.sessionURL(sampleRate: rate, language: language)
    else { return nil }
    var request = URLRequest(url: url)
    request.timeoutInterval = connectTimeout
    request.setValue(key, forHTTPHeaderField: "xi-api-key")
    let utterance = ScribeUtterance(
      id: id, socket: URLSession.shared.webSocketTask(with: request), sampleRate: rate, onUpdate: onUpdate, onError: onError,
      fallback: fallback)
    utterance.begin()
    return utterance
  }

  private func begin() {
    socket.resume()
    receive()
    queue.asyncAfter(deadline: .now() + Self.connectTimeout) { [self] in
      if !open { fail("no session after \(Int(Self.connectTimeout)) s") }
    }
  }

  func feed(_ pcm: Data) {
    queue.async { [self] in
      switch state {
      case .streaming:
        fedBytes += pcm.count
        if let size = heard?.count { heard = size + pcm.count <= Self.maxKept ? heard! + pcm : nil }
        unsent.append(pcm)
        flush(commit: false)
      case .replacing:
        // Short-lived: kept whole, the on-device recognizer replays it once it starts.
        heard?.append(pcm)
      case .replaced(let utterance):
        utterance.feed(pcm)
      case .finishing, .over:
        break
      }
    }
  }

  func finish() async {
    let outcome = await withCheckedContinuation { (continuation: CheckedContinuation<Outcome, Never>) in
      queue.async { [self] in
        switch state {
        case .streaming:
          guard fedBytes > 0 else {
            continuation.resume(returning: .committed)  // nothing was said
            return
          }
          state = .finishing
          finishedAt = Date()
          waiter = continuation
          flush(commit: true)  // until the session opens, session_started sends it
          queue.asyncAfter(deadline: .now() + Self.commitTimeout) { [self] in
            guard waiter != nil else { return }
            if finalText.isEmpty && volatileText.isEmpty {
              fail("no transcript \(Int(Self.commitTimeout)) s after the commit")
            } else {
              log("utterance \(id): ElevenLabs sent no commit in \(Int(Self.commitTimeout)) s; using its partial transcript")
              resume(.bestGuess)
            }
          }
        case .replacing, .replaced:
          continuation.resume(returning: replacement.map(Outcome.replaced) ?? .over)
        case .finishing, .over:
          continuation.resume(returning: .over)
        }
      }
    }
    switch outcome {
    case .replaced(let task):
      await task.value?.finish()
    case .over:
      return
    case .committed, .bestGuess:
      let update = await withCheckedContinuation { (continuation: CheckedContinuation<Utterance.Update, Never>) in
        queue.async { [self] in
          state = .over
          socket.cancel(with: .normalClosure, reason: nil)
          // Anything still partial at the end is the best guess there is.
          finalText = join(finalText, volatileText)
          volatileText = ""
          heard = nil
          if let finishedAt {
            let audio = Double(fedBytes) / Double(sampleRate * 2)
            log("utterance \(id): ElevenLabs final \(String(format: "%.2f", Date().timeIntervalSince(finishedAt))) s after the end (\(String(format: "%.1f", audio)) s of audio, \(finalText.count) chars)")
          }
          continuation.resume(returning: Utterance.Update(final: finalText, volatile: "", done: true))
        }
      }
      onUpdate(update)
    }
  }

  func cancel() async {
    let replaced = await withCheckedContinuation { (continuation: CheckedContinuation<(any SpeechUtterance)?, Never>) in
      queue.async { [self] in
        var replaced: (any SpeechUtterance)?
        if case .replaced(let utterance) = state { replaced = utterance }
        state = .over  // a recognizer still starting (.replacing) cancels itself when it sees this
        socket.cancel(with: .goingAway, reason: nil)
        resume(.over)
        heard = nil
        unsent = Data()
        continuation.resume(returning: replaced)
      }
    }
    await replaced?.cancel()
  }

  // MARK: Session

  private var live: Bool {
    switch state {
    case .streaming, .finishing: return true
    default: return false
    }
  }

  private func receive() {
    socket.receive { [self] result in
      queue.async { [self] in
        guard live else { return }
        switch result {
        case .success(.string(let text)): handle(text)
        case .success(.data(let data)): handle(String(decoding: data, as: UTF8.self))
        case .success: break
        case .failure(let error):
          fail(describe(error))
          return
        }
        if live { receive() }
      }
    }
  }

  private func handle(_ text: String) {
    guard let json = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { return }
    let type = json["message_type"] as? String ?? ""
    switch type {
    case "session_started":
      open = true
      let wait = String(format: "%.2f", Date().timeIntervalSince(started))
      log("utterance \(id): ElevenLabs session \(json["session_id"] as? String ?? "?") open after \(wait) s")
      if case .finishing = state { flush(commit: true) } else { flush(commit: false) }
    case "partial_transcript":
      let partial = json["text"] as? String ?? ""
      guard partial != volatileText else { return }
      volatileText = partial
      emit()
    case "committed_transcript":
      finalText = join(finalText, json["text"] as? String ?? "")
      volatileText = ""
      if case .finishing = state { resume(.committed) } else { emit() }
    case "warning":
      log("utterance \(id): ElevenLabs warning: \(json["warning"] as? String ?? text)")
    default:
      // Every error type (auth_error, quota_exceeded, rate_limited, ...) carries `error`, then the server closes.
      if let error = json["error"] as? String { fail("\(type): \(error)") }
    }
  }

  /// Sends the held audio in chunks of at most 1 s, the last one carrying [commit]. Without a
  /// commit, waits until 100 ms are held.
  private func flush(commit: Bool) {
    guard open, live else { return }
    let maximum = sampleRate * 2
    if !commit && unsent.count < maximum / 10 { return }
    var offset = unsent.startIndex
    while unsent.endIndex - offset > maximum {
      send(unsent[offset..<offset + maximum], commit: false)
      offset += maximum
    }
    send(unsent[offset...], commit: commit)
    unsent = Data()
  }

  private func send(_ audio: Data, commit: Bool) {
    var message: [String: Any] = [
      "message_type": "input_audio_chunk", "audio_base_64": audio.base64EncodedString(), "commit": commit, "sample_rate": sampleRate,
    ]
    if !sentAudio {
      message["previous_text"] = ElevenLabs.context  // only the first chunk may carry it
      sentAudio = true
    }
    guard let data = try? JSONSerialization.data(withJSONObject: message, options: [.withoutEscapingSlashes]) else { return }
    socket.send(.string(String(decoding: data, as: UTF8.self))) { [self] error in
      if let error { queue.async { [self] in fail("send failed: \(describe(error))") } }
    }
  }

  private func emit() {
    onUpdate(Utterance.Update(final: finalText, volatile: volatileText, done: false))
  }

  private func resume(_ outcome: Outcome) {
    waiter?.resume(returning: outcome)
    waiter = nil
  }

  /// Scribe can't go on: the on-device recognizer takes over when it can; else, while finishing
  /// with text, that text is the result; else the headset gets an ERROR.
  private func fail(_ reason: String) {
    guard live else { return }
    let finishing: Bool
    if case .finishing = state { finishing = true } else { finishing = false }
    socket.cancel(with: .goingAway, reason: nil)
    unsent = Data()
    if let fallback, heard != nil {
      log("utterance \(id): ElevenLabs failed (\(reason)); the on-device recognizer takes over")
      state = .replacing
      let task = Task<(any SpeechUtterance)?, Never> { [self] in
        let utterance: any SpeechUtterance
        do {
          utterance = try await fallback()
        } catch {
          queue.async { [self] in
            guard case .replacing = state else { return }
            state = .over
            onError("ElevenLabs failed (\(reason)) and the on-device recognizer couldn't start: \(error)")
          }
          return nil
        }
        return await withCheckedContinuation { (continuation: CheckedContinuation<(any SpeechUtterance)?, Never>) in
          queue.async { [self] in
            guard case .replacing = state else {
              Task { await utterance.cancel() }  // cancelled meanwhile
              continuation.resume(returning: nil)
              return
            }
            if let heard { utterance.feed(heard) }
            heard = nil
            state = .replaced(utterance)
            continuation.resume(returning: utterance)
          }
        }
      }
      replacement = task
      if finishing { resume(.replaced(task)) }
    } else if finishing && !(finalText.isEmpty && volatileText.isEmpty) {
      log("utterance \(id): ElevenLabs failed while finishing (\(reason)); using what it heard")
      state = .over
      resume(.bestGuess)
    } else {
      log("utterance \(id): ElevenLabs failed (\(reason))")
      state = .over
      onError("ElevenLabs speech-to-text failed: \(reason)")
      resume(.over)
    }
  }

  private func describe(_ error: Error) -> String {
    if let status = (socket.response as? HTTPURLResponse)?.statusCode, status != 101 { return "HTTP \(status)" }
    if socket.closeCode != .invalid {
      let reason = socket.closeReason.map { String(decoding: $0, as: UTF8.self) } ?? ""
      return "closed (\(socket.closeCode.rawValue)\(reason.isEmpty ? "" : ": \(reason)"))"
    }
    return error.localizedDescription
  }
}
