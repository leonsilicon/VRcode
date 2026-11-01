import AVFoundation
import Foundation
import Speech

enum SpeechError: Error, CustomStringConvertible {
  case unavailable
  case unsupportedLocale(String)
  case unsupportedAudio(String)

  var description: String {
    switch self {
    case .unavailable: return "SpeechTranscriber is not available on this Mac"
    case .unsupportedLocale(let id): return "no speech model for locale \(id)"
    case .unsupportedAudio(let what): return "unsupported audio: \(what)"
    }
  }
}

/// The locale and the on-device speech assets every utterance uses.
final class SpeechEngine: @unchecked Sendable {
  let locale: Locale
  private let lock = NSLock()
  private var _ready = false

  var ready: Bool { lock.withLock { _ready } }

  private init(locale: Locale) {
    self.locale = locale
  }

  static func make(localeIdentifier: String) async throws -> SpeechEngine {
    guard SpeechTranscriber.isAvailable else { throw SpeechError.unavailable }
    guard let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Locale(identifier: localeIdentifier)) else {
      throw SpeechError.unsupportedLocale(localeIdentifier)
    }
    return SpeechEngine(locale: locale)
  }

  /// Downloads the speech model for the locale if it is missing.
  func prepare() async throws {
    let transcriber = makeTranscriber()
    if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
      log("downloading speech assets for \(locale.identifier)")
      try await request.downloadAndInstall()
    }
    lock.withLock { _ready = true }
    log("speech ready (\(locale.identifier))")
  }

  func makeTranscriber() -> SpeechTranscriber {
    SpeechTranscriber(
      locale: locale,
      transcriptionOptions: [],
      reportingOptions: [.volatileResults, .fastResults],
      attributeOptions: []
    )
  }
}

/// One stretch of speech: PCM in, finalized and volatile text out.
final class Utterance: @unchecked Sendable {
  struct Update: Sendable {
    var final: String
    var volatile: String
    var done: Bool
  }

  let id: UInt32
  private let analyzer: SpeechAnalyzer
  private let converter: AnalyzerInputConverter
  private let inputFormat: AVAudioFormat
  private let input: AsyncStream<AnalyzerInput>.Continuation
  private let onUpdate: @Sendable (Update) -> Void
  private let lock = NSLock()
  private var finalText = ""
  private var volatileText = ""
  private var results: Task<Void, Never>?
  private var closed = false
  private var cancelled = false

  private init(
    id: UInt32,
    analyzer: SpeechAnalyzer,
    converter: AnalyzerInputConverter,
    inputFormat: AVAudioFormat,
    input: AsyncStream<AnalyzerInput>.Continuation,
    onUpdate: @escaping @Sendable (Update) -> Void
  ) {
    self.id = id
    self.analyzer = analyzer
    self.converter = converter
    self.inputFormat = inputFormat
    self.input = input
    self.onUpdate = onUpdate
  }

  /// Starts analysing 16-bit interleaved PCM at [sampleRate] with [channels] channels.
  static func start(
    id: UInt32,
    engine: SpeechEngine,
    sampleRate: Double,
    channels: Int,
    onUpdate: @escaping @Sendable (Update) -> Void
  ) async throws -> Utterance {
    guard sampleRate >= 8000, sampleRate <= 48000, (1...2).contains(channels),
      let format = AVAudioFormat(
        commonFormat: .pcmFormatInt16, sampleRate: sampleRate, channels: AVAudioChannelCount(channels), interleaved: true)
    else { throw SpeechError.unsupportedAudio("\(sampleRate) Hz, \(channels) channels") }
    let transcriber = engine.makeTranscriber()
    let analyzer = SpeechAnalyzer(
      modules: [transcriber],
      options: SpeechAnalyzer.Options(priority: .userInitiated, modelRetention: .processLifetime)
    )
    let converter = try await AnalyzerInputConverter.converter(compatibleWith: [transcriber])
    let (stream, continuation) = AsyncStream<AnalyzerInput>.makeStream()
    let utterance = Utterance(
      id: id, analyzer: analyzer, converter: converter, inputFormat: format, input: continuation, onUpdate: onUpdate)
    utterance.results = Task {
      do {
        for try await result in transcriber.results {
          utterance.apply(String(result.text.characters), isFinal: result.isFinal)
        }
      } catch {
        log("utterance \(id): results ended with \(error)")
      }
    }
    try await analyzer.start(inputSequence: stream)
    return utterance
  }

  /// Feeds raw little-endian 16-bit PCM in the format given to [start].
  func feed(_ pcm: Data) {
    let bytesPerFrame = Int(inputFormat.streamDescription.pointee.mBytesPerFrame)
    let frames = pcm.count / bytesPerFrame
    guard frames > 0, !lock.withLock({ closed }),
      let buffer = AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: AVAudioFrameCount(frames))
    else { return }
    buffer.frameLength = AVAudioFrameCount(frames)
    let target = buffer.mutableAudioBufferList.pointee.mBuffers
    pcm.withUnsafeBytes { raw in
      if let dst = target.mData, let src = raw.baseAddress { memcpy(dst, src, frames * bytesPerFrame) }
    }
    do {
      for item in try converter.convert(buffer, at: nil) { input.yield(item) }
    } catch {
      log("utterance \(id): convert failed: \(error)")
    }
  }

  /// Ends the input, waits for the last results and reports them with `done`.
  func finish() async {
    guard closeInput() else { return }
    if let rest = try? converter.flush() { for item in rest { input.yield(item) } }
    input.finish()
    do {
      try await analyzer.finalizeAndFinishThroughEndOfInput()
    } catch {
      log("utterance \(id): finalize failed: \(error)")
    }
    await results?.value
    let update = lock.withLock { () -> Update in
      // Anything still volatile at the end is the best guess there is.
      finalText = join(finalText, volatileText)
      volatileText = ""
      return Update(final: finalText, volatile: "", done: true)
    }
    onUpdate(update)
  }

  /// Drops the utterance without reporting anything more.
  func cancel() async {
    guard closeInput() else { return }
    lock.withLock { cancelled = true }
    input.finish()
    await analyzer.cancelAndFinishNow()
    results?.cancel()
  }

  private func closeInput() -> Bool {
    lock.withLock {
      if closed { return false }
      closed = true
      return true
    }
  }

  private func apply(_ text: String, isFinal: Bool) {
    let update = lock.withLock { () -> Update in
      if isFinal {
        finalText = join(finalText, text)
        volatileText = ""
      } else {
        volatileText = text
      }
      return Update(final: finalText, volatile: volatileText, done: false)
    }
    if !lock.withLock({ cancelled }) { onUpdate(update) }
  }
}

/// Joins two transcript segments with one space between words.
func join(_ a: String, _ b: String) -> String {
  let tail = b.trimmingCharacters(in: .whitespacesAndNewlines)
  if tail.isEmpty { return a }
  let head = a.trimmingCharacters(in: .whitespacesAndNewlines)
  return head.isEmpty ? tail : head + " " + tail
}

func log(_ message: String) {
  let stamp = ISO8601DateFormatter.string(from: Date(), timeZone: .current, formatOptions: [.withTime, .withColonSeparatorInTime])
  FileHandle.standardError.write(Data("[\(stamp)] \(message)\n".utf8))
}
