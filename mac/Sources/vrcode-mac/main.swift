import AVFoundation
import AppKit
import Foundation

// vrcode-mac: the Mac side of vrcode. Today it transcribes speech for the headset (see
// docs/vrcode-protocol.md); later it also carries the Mac display, Mac audio and clipboard.
//
//   vrcode-mac [--port 7273] [--any-interface] [--locale en-US]
//   vrcode-mac --transcribe speech.aiff     # runs a file through the same path and prints the text

struct Options {
  var port: UInt16 = 7273
  var anyInterface = false
  var locale = "en-US"
  var transcribe: String?
  var linkPort: UInt16 = 7274
  var bridgePort: UInt16 = 7272
  var linkOnly = false
  var linkInfo = false
  var controlOnly = false
  var cmuxSession = "main"
  var cmuxSocket: String?
  var bluetooth = false
  var pidFile: String?
  var linkHost = ProcessInfo.processInfo.hostName
  var linkDirectory = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/dev.improve.vrcode/link")
}

func parseOptions() -> Options {
  var options = Options()
  var args = CommandLine.arguments.dropFirst().makeIterator()
  while let arg = args.next() {
    switch arg {
    case "--port": options.port = args.next().flatMap(UInt16.init) ?? options.port
    case "--any-interface": options.anyInterface = true
    case "--locale": options.locale = args.next() ?? options.locale
    case "--transcribe": options.transcribe = args.next()
    case "--link-port": options.linkPort = args.next().flatMap(UInt16.init) ?? options.linkPort
    case "--bridge-port": options.bridgePort = args.next().flatMap(UInt16.init) ?? options.bridgePort
    case "--link-only": options.linkOnly = true
    case "--link-info": options.linkInfo = true
    case "--control-only": options.controlOnly = true
    case "--cmux-session": options.cmuxSession = args.next() ?? options.cmuxSession
    case "--cmux-socket": options.cmuxSocket = args.next()
    case "--bluetooth": options.bluetooth = true
    case "--link-pid-file": options.pidFile = args.next()
    case "--link-host": options.linkHost = args.next() ?? options.linkHost
    case "--link-directory": if let path = args.next() { options.linkDirectory = URL(fileURLWithPath: path) }
    case "-h", "--help":
      print("usage: vrcode-mac [--port 7273] [--locale en-US] [--transcribe FILE] [--link-port 7274] [--link-only] [--control-only] [--cmux-session main] [--cmux-socket PATH] [--bluetooth] [--link-info] [--link-host HOST] [--link-directory PATH]")
      exit(0)
    default:
      FileHandle.standardError.write(Data("unknown argument \(arg)\n".utf8))
      exit(2)
    }
  }
  return options
}

/// Streams [path] as 16 kHz mono PCM in 100 ms chunks through the recognizer the headset would
/// get (ElevenLabs with a key, else [engine]), like the headset does.
func transcribeFile(_ path: String, locale: String, engine: SpeechEngine?) async throws {
  let file = try AVAudioFile(forReading: URL(fileURLWithPath: path))
  let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true)!
  guard let converter = AVAudioConverter(from: file.processingFormat, to: target) else {
    throw SpeechError.unsupportedAudio("cannot convert \(file.processingFormat)")
  }
  let source = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length))!
  try file.read(into: source)
  let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: AVAudioFrameCount(Double(file.length) * 16000 / file.processingFormat.sampleRate) + 1024)!
  var fed = false
  var error: NSError?
  converter.convert(to: out, error: &error) { _, status in
    if fed {
      status.pointee = .endOfStream
      return nil
    }
    fed = true
    status.pointee = .haveData
    return source
  }
  if let error { throw error }
  let pcm = Data(bytes: out.int16ChannelData![0], count: Int(out.frameLength) * 2)

  guard let utterance = try await startUtterance(
    id: 1, sampleRate: 16000, channels: 1, locale: locale, engine: engine,
    onUpdate: { update in
      if update.done {
        print("FINAL: \(update.final)")
      } else {
        log("final=\"\(update.final)\" volatile=\"\(update.volatile)\"")
      }
    },
    onError: { message in
      log("error: \(message)")
      exit(1)
    }
  ) else { throw SpeechError.unavailable }
  log("transcribing with \(utterance is ScribeUtterance ? "ElevenLabs \(ElevenLabs.model)" : "the on-device recognizer")")
  let chunk = 3200  // 100 ms
  var offset = 0
  while offset < pcm.count {
    utterance.feed(pcm.subdata(in: offset..<min(offset + chunk, pcm.count)))
    offset += chunk
    try await Task.sleep(nanoseconds: 20_000_000)
  }
  await utterance.finish()  // reports the done update before returning
}

let options = parseOptions()
let runsAsApp = Bundle.main.bundleURL.pathExtension == "app"
if runsAsApp { NSApplication.shared.setActivationPolicy(.accessory) }
if let pidFile = options.pidFile { try? String(ProcessInfo.processInfo.processIdentifier).write(toFile: pidFile, atomically: true, encoding: .utf8) }
var linkRelay: LinkRelay?
var classicCarrier: ClassicCarrier?
var bleCarrier: BLECarrier?
do {
  if options.transcribe == nil {
    if options.linkInfo {
      let identity = try LinkIdentity.load(directory: options.linkDirectory)
      print(identity.configuration(host: options.linkHost, port: options.linkPort))
      exit(0)
    }
    let relay = try LinkRelay(port: options.linkPort, bridgePort: options.bridgePort, speechPort: options.port, directory: options.linkDirectory)
    linkRelay = relay
    relay.start()
    if options.bluetooth {
      if runsAsApp {
        // Start after AppKit has finished launching and its run loop is serving
        // Bluetooth / privacy events. Initializing IOBluetooth before that can
        // stall SDP registration on a cold daemon.
        DispatchQueue.main.async {
          bleCarrier = BLECarrier(port: options.linkPort)
          bleCarrier?.start {
            classicCarrier = ClassicCarrier(port: options.linkPort)
            do { try classicCarrier?.start() } catch { log("\(error)") }
          }
        }
      } else { log("Bluetooth requires the macOS app bundle for privacy permission. Run bun scripts/run-mac-service.ts --bluetooth.") }
    }
  }
} catch { log("fatal: \(error)"); exit(1) }
if options.linkOnly {
  if runsAsApp { NSApplication.shared.run() } else { RunLoop.main.run() }
  exit(0)
}
// Control starts before speech permissions or model downloads; cmux always works in background.
var service: Server?
if options.transcribe == nil {
  do {
    let server = try Server(port: options.port, anyInterface: options.anyInterface,
      cmuxEndpoint: CmuxEndpoint(session: options.cmuxSession, socket: options.cmuxSocket), locale: options.locale)
    service = server
    server.start()
  } catch { log("fatal: \(error)"); exit(1) }
  log(ElevenLabs.apiKey() != nil
    ? "speech: ElevenLabs \(ElevenLabs.model), the on-device recognizer as fallback"
    : "speech: on-device (an ElevenLabs key in VRcode Mac switches it to ElevenLabs Scribe)")
}
// --control-only skips the on-device recognizer; with an ElevenLabs key, speech still works.
if options.controlOnly { RunLoop.main.run(); exit(0) }
Task {
  var engine: SpeechEngine?
  do {
    let made = try await SpeechEngine.make(localeIdentifier: options.locale)
    try await made.prepare()
    engine = made
  } catch {
    log("on-device speech unavailable: \(error)")
  }
  if let path = options.transcribe {
    do { try await transcribeFile(path, locale: options.locale, engine: engine) } catch {
      log("speech unavailable: \(error)")
      exit(1)
    }
    exit(0)
  }
  if let engine { service?.setSpeechEngine(engine) }
}
if runsAsApp { NSApplication.shared.run() } else { RunLoop.main.run() }
