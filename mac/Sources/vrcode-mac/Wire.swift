import Foundation

/// Frame kinds of the vrcode service protocol (docs/vrcode-protocol.md).
enum Kind: UInt8 {
  case hello = 1
  case welcome = 2
  case audioStart = 3
  case audio = 4
  case audioEnd = 5
  case audioCancel = 6
  case transcript = 7
  case error = 8
  case cmuxRequest = 9
  case cmuxEvent = 10
  case aiRequest = 11
  case aiResult = 12
  /// Quest -> Mac: its settings schema and values. Mac -> VRcode Mac app: the same plus `connected`, `pending`.
  case settings = 13
  /// VRcode Mac app -> Mac -> Quest: `{"values":{…}}`, a partial update (queued while the Quest is away).
  case settingsSet = 14
  /// VRcode Mac app -> Mac -> Quest: `{"action":"arrange_windows"}`.
  case settingsAction = 15
  /// Mac -> Quest, while an agent AI_REQUEST runs: `{"kind":"status"|"thinking"|"step","text":…}`.
  case aiProgress = 16
  /// Quest -> Mac: stop the agent running for this AI_REQUEST id (no reply).
  case aiCancel = 17
}

let protocolVersion: UInt16 = 1
let headerLength = 16
/// Frames larger than this are a protocol error (one second of 48 kHz stereo PCM is 192 KB).
let maxBodyLength = 4 * 1024 * 1024

struct Frame {
  var kind: UInt8
  var id: UInt32
  var sequence: UInt32
  var body: Data

  func json() -> [String: Any]? {
    (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
  }
}

enum WireError: Error, CustomStringConvertible {
  case version(UInt16)
  case tooLarge(Int)

  var description: String {
    switch self {
    case .version(let v): return "unsupported protocol version \(v)"
    case .tooLarge(let n): return "frame body of \(n) bytes is too large"
    }
  }
}

func encodeFrame(kind: Kind, id: UInt32 = 0, sequence: UInt32, body: Data = Data()) -> Data {
  var out = Data(capacity: headerLength + body.count)
  out.appendLE(UInt32(body.count))
  out.append(kind.rawValue)
  out.append(0)
  out.appendLE(protocolVersion)
  out.appendLE(id)
  out.appendLE(sequence)
  out.append(body)
  return out
}

func jsonBody(_ object: [String: Any]) -> Data {
  (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data("{}".utf8)
}

/// Accumulates bytes from the socket and yields whole frames.
struct FrameReader {
  private var buffer = Data()

  mutating func append(_ data: Data) {
    buffer.append(data)
  }

  /// The next complete frame, nil when more bytes are needed.
  mutating func next() throws -> Frame? {
    guard buffer.count >= headerLength else { return nil }
    let start = buffer.startIndex
    let bodyLength = Int(buffer.readLE(UInt32.self, at: start))
    let kind = buffer[start + 4]
    let version = buffer.readLE(UInt16.self, at: start + 6)
    guard version == protocolVersion else { throw WireError.version(version) }
    guard bodyLength <= maxBodyLength else { throw WireError.tooLarge(bodyLength) }
    guard buffer.count >= headerLength + bodyLength else { return nil }
    let id = buffer.readLE(UInt32.self, at: start + 8)
    let sequence = buffer.readLE(UInt32.self, at: start + 12)
    let body = Data(buffer[(start + headerLength)..<(start + headerLength + bodyLength)])
    buffer = Data(buffer[(start + headerLength + bodyLength)...])
    return Frame(kind: kind, id: id, sequence: sequence, body: body)
  }
}

extension Data {
  mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
    var le = value.littleEndian
    Swift.withUnsafeBytes(of: &le) { append(contentsOf: $0) }
  }

  func readLE<T: FixedWidthInteger>(_: T.Type, at offset: Index) -> T {
    var value: T = 0
    for i in 0..<MemoryLayout<T>.size {
      value |= T(self[offset + i]) << (8 * i)
    }
    return value
  }
}
