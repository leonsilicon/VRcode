import Foundation
import Security

/// The model providers the left-fist "speak to the AI" mode can use. The headset picks one in
/// Settings and names it in each AI_REQUEST (`provider`); the news mic's flag shows its logo.
enum AiProvider: String, CaseIterable {
  case claude, openai, gemini

  var label: String {
    switch self {
    case .claude: return "Claude"
    case .openai: return "ChatGPT"
    case .gemini: return "Gemini"
    }
  }

  /// Each provider's model, chosen for short, latency-sensitive output at low effort.
  var model: String {
    switch self {
    case .claude: return "claude-opus-5-5"
    case .openai: return "gpt-6.1-sol"
    case .gemini: return "gemini-3.8-flash"
    }
  }

  /// The Keychain item's account (service `dev.improve.vrcode`) and the matching file name in
  /// `~/.config/vrcode/`.
  var keyAccount: String {
    switch self {
    case .claude: return "anthropic-api-key"
    case .openai: return "openai-api-key"
    case .gemini: return "gemini-api-key"
    }
  }

  /// The environment variable read during development (`swift run`).
  var keyEnvironment: String {
    switch self {
    case .claude: return "ANTHROPIC_API_KEY"
    case .openai: return "OPENAI_API_KEY"
    case .gemini: return "GEMINI_API_KEY"
    }
  }

  var keyName: String {
    switch self {
    case .claude: return "an Anthropic API key"
    case .openai: return "an OpenAI API key"
    case .gemini: return "a Gemini API key"
    }
  }

  /// A request without a `provider` (an older headset build) uses Claude.
  static func from(_ value: String?) -> AiProvider { value.flatMap(AiProvider.init(rawValue:)) ?? .claude }
}

/// The left-fist "speak to the AI" mode: turns what the user said into the exact text to type into
/// one terminal pane, using that pane's screen as context. Calls the chosen provider's API over raw
/// HTTP (no official Swift SDKs). The headset stages the result for review; nothing here types into
/// a terminal.
///
/// Each provider's API key comes from the macOS Keychain (generic password, service
/// `dev.improve.vrcode`, account [AiProvider.keyAccount]), else its environment variable (for `swift
/// run` during development), else the first line of `~/.config/vrcode/<account>`. Under the menu-bar
/// app's LaunchAgent the shell environment is absent, so the Keychain is the normal source. Keys
/// never leave the Mac.
enum AiService {
  struct Failure: Error, CustomStringConvertible {
    let description: String
  }

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
  static func complete(provider: AiProvider, transcript: String, screen: String, title: String) async throws -> String {
    guard let key = apiKey(provider) else {
      throw Failure(description: "\(provider.label) is off: add \(provider.keyName) in VRcode Mac (Keychain item dev.improve.vrcode / \(provider.keyAccount))")
    }
    var user = "<pane title=\"\(title.replacingOccurrences(of: "\"", with: "'"))\">\n\(screen)\n</pane>\n\n"
    user += "<transcript>\(transcript)</transcript>"
    let text: String
    switch provider {
    case .claude: text = try await claude(key: key, user: user)
    case .openai: text = try await openai(key: key, user: user)
    case .gemini: text = try await gemini(key: key, user: user)
    }
    let cleaned = stripFences(text).trimmingCharacters(in: .whitespacesAndNewlines)
    guard !cleaned.isEmpty else { throw Failure(description: "\(provider.label) returned nothing") }
    return cleaned
  }

  // MARK: Providers

  /// Claude Messages API.
  private static func claude(key: String, user: String) async throws -> String {
    let body: [String: Any] = [
      "model": AiProvider.claude.model,
      "max_tokens": 2048,
      // Short, latency-sensitive output: thinking can't be disabled on Claude Opus 5.5; low effort keeps it brief.
      "output_config": ["effort": "low"],
      // A safety-classifier decline is retried server-side on the model Anthropic recommends.
      "fallbacks": "default",
      "system": system,
      "messages": [["role": "user", "content": user]],
    ]
    let json = try await post(.claude, "https://api.anthropic.com/v1/messages", body: body, headers: [
      "x-api-key": key,
      "anthropic-version": "2026-10-21",
      "anthropic-beta": "server-side-fallback-2026-07-01",
    ])
    // A decline arrives as HTTP 200 with stop_reason "refusal": check before reading content.
    if json["stop_reason"] as? String == "refusal" {
      throw Failure(description: "Claude declined this request; paste what you said instead")
    }
    let blocks = json["content"] as? [[String: Any]] ?? []
    return blocks.filter { $0["type"] as? String == "text" }.compactMap { $0["text"] as? String }.joined()
  }

  /// OpenAI Responses API.
  private static func openai(key: String, user: String) async throws -> String {
    let body: [String: Any] = [
      "model": AiProvider.openai.model,
      "instructions": system,
      "input": user,
      "reasoning": ["effort": "low"],
      "max_output_tokens": 2048,
      "store": false,
    ]
    let json = try await post(.openai, "https://api.openai.com/v1/responses", body: body, headers: ["Authorization": "Bearer \(key)"])
    // The output holds several items (reasoning, messages); join every message's text parts.
    var text = ""
    for item in json["output"] as? [[String: Any]] ?? [] where item["type"] as? String == "message" {
      for part in item["content"] as? [[String: Any]] ?? [] {
        switch part["type"] as? String {
        case "output_text": text += part["text"] as? String ?? ""
        case "refusal": throw Failure(description: "ChatGPT declined this request; paste what you said instead")
        default: break
        }
      }
    }
    return text
  }

  /// Gemini generateContent API.
  private static func gemini(key: String, user: String) async throws -> String {
    let body: [String: Any] = [
      "system_instruction": ["parts": [["text": system]]],
      "contents": [["role": "user", "parts": [["text": user]]]],
      "generationConfig": ["maxOutputTokens": 2048, "thinkingConfig": ["thinkingLevel": "low"]],
    ]
    let url = "https://generativelanguage.googleapis.com/v1beta/models/\(AiProvider.gemini.model):generateContent"
    let json = try await post(.gemini, url, body: body, headers: ["x-goog-api-key": key])
    if let blocked = (json["promptFeedback"] as? [String: Any])?["blockReason"] as? String {
      throw Failure(description: "Gemini declined this request (\(blocked)); paste what you said instead")
    }
    let candidate = (json["candidates"] as? [[String: Any]])?.first ?? [:]
    if candidate["finishReason"] as? String == "SAFETY" {
      throw Failure(description: "Gemini declined this request; paste what you said instead")
    }
    let parts = (candidate["content"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []
    // Thought summaries (if any) are marked `thought`; only the answer is typed.
    return parts.filter { $0["thought"] as? Bool != true }.compactMap { $0["text"] as? String }.joined()
  }

  /// POSTs [body] as JSON and returns the decoded reply, throwing the API's own message on failure.
  private static func post(_ provider: AiProvider, _ url: String, body: [String: Any], headers: [String: String]) async throws -> [String: Any] {
    var request = URLRequest(url: URL(string: url)!)
    request.httpMethod = "POST"
    request.timeoutInterval = 30
    request.setValue("application/json", forHTTPHeaderField: "content-type")
    for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
    request.httpBody = try JSONSerialization.data(withJSONObject: body)
    let (data, response) = try await URLSession.shared.data(for: request)
    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
    guard status == 200 else {
      // All three APIs put a human-readable message at error.message.
      let message = (json["error"] as? [String: Any])?["message"] as? String ?? "HTTP \(status)"
      throw Failure(description: "\(provider.label) request failed: \(message)")
    }
    return json
  }

  /// Removes a surrounding ``` fence if the model added one anyway.
  static func stripFences(_ text: String) -> String {
    var lines = text.trimmingCharacters(in: .whitespacesAndNewlines).components(separatedBy: "\n")
    guard lines.count >= 2, lines.first?.hasPrefix("```") == true, lines.last?.hasPrefix("```") == true else { return text }
    lines.removeFirst()
    lines.removeLast()
    return lines.joined(separator: "\n")
  }

  // MARK: Keys

  private static func apiKey(_ provider: AiProvider) -> String? {
    if let key = keychainKey(provider.keyAccount) { return key }
    if let key = ProcessInfo.processInfo.environment[provider.keyEnvironment], !key.isEmpty { return key }
    let file = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".config/vrcode/\(provider.keyAccount)")
    let key = (try? String(contentsOf: file, encoding: .utf8))?.components(separatedBy: .newlines).first?
      .trimmingCharacters(in: .whitespaces)
    return key?.isEmpty == false ? key : nil
  }

  /// A key stored with `security add-generic-password -s dev.improve.vrcode -a <account> -w`.
  private static func keychainKey(_ account: String) -> String? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: "dev.improve.vrcode",
      kSecAttrAccount as String: account,
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
