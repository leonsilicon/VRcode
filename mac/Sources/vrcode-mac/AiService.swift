import Foundation
import Security

/// The model providers the left-fist "speak to the AI" mode can use. The headset picks one and one
/// of its [models] in Settings and names both in each AI_REQUEST (`provider`, `model`); the news
/// mic's flag shows the provider's logo.
enum AiProvider: String, CaseIterable {
  case claude, openai, gemini

  var label: String {
    switch self {
    case .claude: return "Claude"
    case .openai: return "ChatGPT"
    case .gemini: return "Gemini"
    }
  }

  /// The models the headset's Settings offer (`AiModel` in settings-ui's AiProvider.kt lists the
  /// same ids). All run at low effort for short, latency-sensitive output.
  var models: [String] {
    switch self {
    case .claude: return ["claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1"]
    case .openai: return ["gpt-6-luna", "gpt-6.1-terra", "gpt-6.1-sol", "gpt-6-astra"]
    case .gemini: return ["gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-4-argon"]
    }
  }

  /// The model used when a request names none (an older headset build) or one not in [models].
  var defaultModel: String {
    switch self {
    case .claude: return "claude-sonnet-5-5"
    case .openai: return "gpt-6.1-sol"
    case .gemini: return "gemini-3.8-flash"
    }
  }

  /// [requested] when this provider offers it, else [defaultModel].
  func model(_ requested: String?) -> String {
    requested.flatMap { models.contains($0) ? $0 : nil } ?? defaultModel
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

/// The left-fist "speak to the AI" mode: turns what the user said into the keystrokes to send to
/// one terminal pane (text and key presses such as Enter or Ctrl-C, see [AiKeystroke]), using that
/// pane's visible screen and cursor as context. The model decides whether to press Enter. Calls the
/// chosen provider's API over raw HTTP (no official Swift SDKs). The headset types the result;
/// nothing here types into a terminal.
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
    You turn a developer's spoken request into the keystrokes to send to one terminal pane. They are \
    in VR, speaking instead of typing. You get everything currently on the pane's screen, the line \
    the cursor is on (▮ marks the cursor), and a transcript of what they said; speech recognition may \
    mishear technical words, so correct them using the screen and common developer vocabulary.

    Work out what the pane is showing and send what does what they asked there:
    - A shell prompt: one shell command (a short pipeline is fine). Use the screen for paths, branch \
    names, file names and earlier commands.
    - A coding agent (Claude Code, Codex or similar) waiting for input: a clear prompt that keeps \
    everything they asked for and nothing they didn't.
    - A menu, permission or yes/no question, pager, editor or other full-screen program: the keys \
    that answer or navigate it (an option's number or letter, arrows, y, q, escape, ...).
    - A running command they want stopped: ctrl+c.
    - Literal dictation ("type ..."): that text, fixing only recognition errors.
    If text is already typed at the cursor and they want something else, clear it first (ctrl+u at a \
    shell prompt).

    You decide whether to press Enter. Press it when they want the command run, the prompt sent or \
    the question answered, which is the usual case. Leave it off when they said to just type, write \
    or prepare it, or when the command deletes files or data, force-pushes, or is otherwise hard to \
    undo and they did not clearly ask to run it: then it waits at the prompt for them to check.

    Reply with only a JSON array of steps, in order: no explanation or code fences. A step is \
    {"text": "..."}, typed exactly as given, or {"key": "..."}, one key press: enter, tab, backtab, \
    escape, backspace, delete, up, down, left, right, home, end, pageup, pagedown, space, f1 to f24, \
    or a letter, digit or punctuation key, optionally with ctrl+, alt+ or shift+ (ctrl+c, ctrl+u, \
    alt+left). Text never contains a line break; Enter is always its own key step. For example \
    [{"text": "git status"}, {"key": "enter"}]. Latency-sensitive: begin your visible answer \
    immediately.
    """

  /// What the AI sees of the target pane: everything on its screen (one line per row, top to
  /// bottom), its window title, and the cursor's row (an index into [screen]'s lines) and column
  /// (in cells) when it is visible.
  struct Pane {
    var screen: String
    var title: String
    var cursorRow: Int?
    var cursorCol: Int = 0
  }

  /// The keystrokes to send for [transcript], given the target [pane], from [model] (one of
  /// [provider]'s [AiProvider.models]).
  static func complete(provider: AiProvider, model: String, transcript: String, pane: Pane) async throws -> [AiKeystroke] {
    guard let key = apiKey(provider) else {
      throw Failure(description: "\(provider.label) is off: add \(provider.keyName) in VRcode Mac (Keychain item dev.improve.vrcode / \(provider.keyAccount))")
    }
    let user = prompt(transcript: transcript, pane: pane)
    let text: String
    switch provider {
    case .claude: text = try await claude(key: key, model: model, user: user)
    case .openai: text = try await openai(key: key, model: model, user: user)
    case .gemini: text = try await gemini(key: key, model: model, user: user)
    }
    guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw Failure(description: "\(provider.label) returned nothing") }
    let steps = try AiKeystroke.parse(text)
    guard !steps.isEmpty else { throw Failure(description: "\(provider.label) found nothing to type") }
    return steps
  }

  /// The user turn: the pane's screen, its cursor line with ▮ at the cursor, and the transcript.
  static func prompt(transcript: String, pane: Pane) -> String {
    var user = "<pane title=\"\(pane.title.replacingOccurrences(of: "\"", with: "'"))\">\n\(pane.screen)\n</pane>\n\n"
    if let line = cursorLine(pane) { user += "<cursor_line>\(line)</cursor_line>\n\n" }
    user += "<transcript>\(transcript)</transcript>"
    return user
  }

  /// The screen line the cursor is on with ▮ inserted at the cursor (padding a short line with
  /// spaces); nil when the cursor is hidden or off the screen text.
  static func cursorLine(_ pane: Pane) -> String? {
    guard let row = pane.cursorRow, row >= 0 else { return nil }
    let lines = pane.screen.components(separatedBy: "\n")
    // The headset drops blank rows below the cursor, so a cursor just past the text is on a blank line.
    guard row <= lines.count else { return nil }
    var line = Array(row < lines.count ? lines[row] : "")
    let col = max(0, pane.cursorCol)
    if line.count < col { line += Array(repeating: " ", count: col - line.count) }
    line.insert("▮", at: col)
    return String(line)
  }

  // MARK: Providers

  /// Claude Messages API.
  private static func claude(key: String, model: String, user: String) async throws -> String {
    var body: [String: Any] = [
      "model": model,
      "max_tokens": 2048,
      // Short, latency-sensitive output. Thinking is adaptive and stays on (these models reject
      // disabling it); low effort keeps it brief.
      "output_config": ["effort": "low"],
      "system": system,
      "messages": [["role": "user", "content": user]],
    ]
    var headers = ["x-api-key": key, "anthropic-version": "2026-10-21"]
    // A safety-classifier decline is retried server-side on the model Anthropic recommends. Claude
    // Haiku 5.5 has no server-side fallback, so its declines just come back as a refusal.
    if model != "claude-haiku-5-5" {
      body["fallbacks"] = "default"
      headers["anthropic-beta"] = "server-side-fallback-2026-07-01"
    }
    let json = try await post(.claude, "https://api.anthropic.com/v1/messages", body: body, headers: headers)
    // A decline arrives as HTTP 200 with stop_reason "refusal": check before reading content.
    if json["stop_reason"] as? String == "refusal" {
      throw Failure(description: "Claude declined this request; paste what you said instead")
    }
    let blocks = json["content"] as? [[String: Any]] ?? []
    return blocks.filter { $0["type"] as? String == "text" }.compactMap { $0["text"] as? String }.joined()
  }

  /// OpenAI Responses API.
  private static func openai(key: String, model: String, user: String) async throws -> String {
    let body: [String: Any] = [
      "model": model,
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

  /// Gemini generateContent API. Every offered model accepts the `low` thinking level.
  private static func gemini(key: String, model: String, user: String) async throws -> String {
    let body: [String: Any] = [
      "system_instruction": ["parts": [["text": system]]],
      "contents": [["role": "user", "parts": [["text": user]]]],
      "generationConfig": ["maxOutputTokens": 2048, "thinkingConfig": ["thinkingLevel": "low"]],
    ]
    let url = "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent"
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
