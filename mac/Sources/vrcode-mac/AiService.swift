import Foundation
import Security

/// The left-fist "speak to the AI" mode: turns what the user said into the exact text to type into
/// one terminal pane, using that pane's screen as context. Calls the Claude Messages API over raw
/// HTTP (there is no official Swift SDK). The headset stages the result for review; nothing here
/// types into a terminal.
///
/// The API key comes from the macOS Keychain (generic password, service `dev.improve.vrcode`,
/// account `anthropic-api-key`), else `ANTHROPIC_API_KEY` (for `swift run` during development),
/// else the first line of `~/.config/vrcode/anthropic-api-key`. Under the menu-bar app's LaunchAgent
/// the shell environment is absent, so the Keychain is the normal source. It never leaves the Mac.
enum AiService {
  struct Failure: Error, CustomStringConvertible {
    let description: String
  }

  static let model = "claude-opus-5-5"

  private static let system = """
    You turn a developer's spoken request into the exact text to type into one terminal pane. They \
    are in VR, speaking instead of typing. You get the pane's visible screen and a transcript of what \
    they said; speech recognition may mishear technical words, so correct them using the screen and \
    common developer vocabulary.

    - If the pane shows a shell prompt, reply with one shell command (a short pipeline is fine) that \
    does what they asked. Use the screen for paths, branch names, file names and earlier commands.
    - If the pane shows a coding agent (Claude Code, Codex or similar) or another program waiting for \
    text, reply with the text to send it. For an agent, write a clear prompt that keeps everything \
    they asked for and nothing they didn't.
    - If they are clearly dictating literal text, return that text, fixing only recognition errors.

    Reply with only the text to type: no explanation, quotes or code fences, and no trailing newline \
    (the user reviews it and presses Enter themselves). Latency-sensitive: begin your visible answer \
    immediately.
    """

  /// The text to type for [transcript], given the pane's [screen] text and window [title].
  static func complete(transcript: String, screen: String, title: String) async throws -> String {
    guard let key = apiKey() else {
      throw Failure(description: "AI is off: add an Anthropic API key on the Mac (Keychain item dev.improve.vrcode / anthropic-api-key)")
    }
    var user = "<pane title=\"\(title.replacingOccurrences(of: "\"", with: "'"))\">\n\(screen)\n</pane>\n\n"
    user += "<transcript>\(transcript)</transcript>"
    let body: [String: Any] = [
      "model": model,
      "max_tokens": 2048,
      // Short, latency-sensitive output: thinking can't be disabled on Claude Opus 5.5; low effort keeps it brief.
      "output_config": ["effort": "low"],
      // A safety-classifier decline is retried server-side on the model Anthropic recommends.
      "fallbacks": "default",
      "system": system,
      "messages": [["role": "user", "content": user]],
    ]
    var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
    request.httpMethod = "POST"
    request.timeoutInterval = 30
    request.setValue("application/json", forHTTPHeaderField: "content-type")
    request.setValue(key, forHTTPHeaderField: "x-api-key")
    request.setValue("2026-10-21", forHTTPHeaderField: "anthropic-version")
    request.setValue("server-side-fallback-2026-07-01", forHTTPHeaderField: "anthropic-beta")
    request.httpBody = try JSONSerialization.data(withJSONObject: body)

    let (data, response) = try await URLSession.shared.data(for: request)
    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
    guard status == 200 else {
      let message = (json["error"] as? [String: Any])?["message"] as? String ?? "HTTP \(status)"
      throw Failure(description: "AI request failed: \(message)")
    }
    // A decline arrives as HTTP 200 with stop_reason "refusal": check before reading content.
    if json["stop_reason"] as? String == "refusal" {
      throw Failure(description: "The AI declined this request; paste what you said instead")
    }
    let blocks = json["content"] as? [[String: Any]] ?? []
    let text = blocks.filter { $0["type"] as? String == "text" }.compactMap { $0["text"] as? String }.joined()
    let cleaned = stripFences(text).trimmingCharacters(in: .whitespacesAndNewlines)
    guard !cleaned.isEmpty else { throw Failure(description: "The AI returned nothing") }
    return cleaned
  }

  /// Removes a surrounding ``` fence if the model added one anyway.
  static func stripFences(_ text: String) -> String {
    var lines = text.trimmingCharacters(in: .whitespacesAndNewlines).components(separatedBy: "\n")
    guard lines.count >= 2, lines.first?.hasPrefix("```") == true, lines.last?.hasPrefix("```") == true else { return text }
    lines.removeFirst()
    lines.removeLast()
    return lines.joined(separator: "\n")
  }

  private static func apiKey() -> String? {
    if let key = keychainKey() { return key }
    if let key = ProcessInfo.processInfo.environment["ANTHROPIC_API_KEY"], !key.isEmpty { return key }
    let file = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".config/vrcode/anthropic-api-key")
    let key = (try? String(contentsOf: file, encoding: .utf8))?.components(separatedBy: .newlines).first?
      .trimmingCharacters(in: .whitespaces)
    return key?.isEmpty == false ? key : nil
  }

  /// The key stored with `security add-generic-password -s dev.improve.vrcode -a anthropic-api-key -w`.
  private static func keychainKey() -> String? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: "dev.improve.vrcode",
      kSecAttrAccount as String: "anthropic-api-key",
      kSecReturnData as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var item: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data,
      let key = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines), !key.isEmpty
    else { return nil }
    return key
  }
}
