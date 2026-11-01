import Foundation

/// Mirrors the Quest's settings into VRcode Mac (docs/vrcode-protocol.md, kinds 13–15).
///
/// The Quest is the source of truth. It sends SETTINGS (its schema and values) after WELCOME and
/// after every change. A Mac app connection (HELLO `client: "vrcode-mac-app"`, loopback only) gets
/// the latest copy at once and on every change, also while the Quest is away; the copy survives
/// restarts in `quest-settings.json`. The app's SETTINGS_SET goes to every connected Quest, or
/// waits as `pending` and is sent after the Quest's next SETTINGS. SETTINGS_ACTION needs a Quest.
final class SettingsHub: @unchecked Sendable {
  static let shared = SettingsHub()

  private let lock = NSLock()
  private let file: URL
  private var state: [String: Any]?
  private var pending: [String: Any] = [:]
  private var headsets: [ObjectIdentifier: Client] = [:]
  private var observers: [ObjectIdentifier: Client] = [:]

  init(file: URL? = nil) {
    let support = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/dev.improve.vrcode")
    self.file = file ?? ProcessInfo.processInfo.environment["VRCODE_SETTINGS_FILE"].map(URL.init(fileURLWithPath:))
      ?? support.appendingPathComponent("quest-settings.json")
    if let data = try? Data(contentsOf: self.file), let saved = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
      state = saved["state"] as? [String: Any]
      pending = saved["pending"] as? [String: Any] ?? [:]
    }
  }

  /// A Mac app connection: it hears the current settings now and every change after.
  func addObserver(_ client: Client) {
    let body = lock.withLock { () -> Data in
      observers[ObjectIdentifier(client)] = client
      return snapshot()
    }
    client.send(.settings, body: body)
  }

  /// SETTINGS from a Quest: remember it, hand it any queued edits, and tell the Mac apps.
  func headset(_ client: Client, state body: [String: Any]) {
    let (queued, update, targets) = lock.withLock { () -> ([String: Any], Data, [Client]) in
      headsets[ObjectIdentifier(client)] = client
      state = body
      let queued = pending
      pending = [:]
      save()
      return (queued, snapshot(), Array(observers.values))
    }
    if !queued.isEmpty { client.send(.settingsSet, body: jsonBody(["values": queued])) }
    targets.forEach { $0.send(.settings, body: update) }
  }

  /// SETTINGS_SET from a Mac app. Returns an error message for a non-app connection.
  func set(_ values: [String: Any], from client: Client) -> String? {
    let (quests, update, targets) = lock.withLock { () -> ([Client], Data?, [Client]) in
      guard observers[ObjectIdentifier(client)] != nil else { return ([], nil, []) }
      if headsets.isEmpty {
        pending.merge(values) { _, new in new }
        save()
        return ([], snapshot(), Array(observers.values))
      }
      return (Array(headsets.values), nil, [])
    }
    guard update != nil || !quests.isEmpty else { return "Only VRcode Mac can change the Quest's settings" }
    // A connected Quest applies the values and answers with a fresh SETTINGS for everyone.
    quests.forEach { $0.send(.settingsSet, body: jsonBody(["values": values])) }
    if let update { targets.forEach { $0.send(.settings, body: update) } }
    return nil
  }

  /// SETTINGS_ACTION from a Mac app. Actions are never queued.
  func action(_ action: String, from client: Client) -> String? {
    let quests = lock.withLock { observers[ObjectIdentifier(client)] == nil ? nil : Array(headsets.values) }
    guard let quests else { return "Only VRcode Mac can run Quest actions" }
    guard !quests.isEmpty else { return "Connect the Quest to do this" }
    quests.forEach { $0.send(.settingsAction, body: jsonBody(["action": action])) }
    return nil
  }

  func remove(_ client: Client) {
    let (update, targets) = lock.withLock { () -> (Data?, [Client]) in
      let id = ObjectIdentifier(client)
      observers.removeValue(forKey: id)
      guard headsets.removeValue(forKey: id) != nil, headsets.isEmpty else { return (nil, []) }
      return (snapshot(), Array(observers.values))
    }
    if let update { targets.forEach { $0.send(.settings, body: update) } }
  }

  /// Caller holds [lock].
  private func snapshot() -> Data {
    var body = state ?? [:]
    body["connected"] = !headsets.isEmpty
    body["pending"] = pending
    return jsonBody(body)
  }

  /// Caller holds [lock].
  private func save() {
    var saved: [String: Any] = ["pending": pending]
    if let state { saved["state"] = state }
    guard let data = try? JSONSerialization.data(withJSONObject: saved, options: [.sortedKeys]) else { return }
    try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
    try? data.write(to: file, options: .atomic)
  }
}
