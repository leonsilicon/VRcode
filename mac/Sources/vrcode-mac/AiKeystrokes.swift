import Foundation

/// One step of what the AI mic sends to a terminal pane, in order: literal text, or one key press
/// in cmux's chord syntax (`enter`, `ctrl+c`, `alt+left`). The headset types text with
/// `TerminalHandle.write` and presses keys with `TerminalHandle.keys`; the xmux bridge hands them
/// to cmux one at a time, in the order sent. Enter is only ever a key step, so whether a command
/// runs is the AI's explicit choice.
enum AiKeystroke: Equatable {
  case text(String)
  case key(String)

  /// The AI_RESULT form: `{"text": "..."}` or `{"key": "enter"}`.
  var json: [String: String] {
    switch self {
    case .text(let text): return ["text": text]
    case .key(let chord): return ["key": chord]
    }
  }

  /// Key names cmux presses (`terminal.keys`; cmux-tui `spec/commands.md` and ghostty-vt's
  /// `key_input_from_chord`), besides `f1`-`f24` and [singleKeys].
  static let namedKeys: Set<String> = [
    "enter", "tab", "backtab", "escape", "backspace", "delete", "insert", "space",
    "up", "down", "left", "right", "home", "end", "pageup", "pagedown",
  ]

  /// Characters cmux can press as a key (with ctrl/alt/shift); other text goes in a text step.
  static let singleKeys = Set("abcdefghijklmnopqrstuvwxyz0123456789`\\[],=-.';/")

  /// Spellings models use for keys cmux names differently.
  private static let aliases: [String: String] = [
    "return": "enter", "esc": "escape", "del": "delete", "ins": "insert", "spacebar": "space",
    "arrowup": "up", "arrowdown": "down", "arrowleft": "left", "arrowright": "right",
    "pgup": "pageup", "pgdn": "pagedown", "pgdown": "pagedown",
    "page_up": "pageup", "page_down": "pagedown", "page-up": "pageup", "page-down": "pagedown",
  ]

  private static let modifierNames: [String: String] = [
    "ctrl": "ctrl", "control": "ctrl", "alt": "alt", "option": "alt", "meta": "alt", "shift": "shift",
  ]

  /// [chord] in cmux's form (lower case, modifiers as `ctrl+alt+shift+` in that order), or nil
  /// when cmux cannot press it. Accepts `Ctrl-C`, `ESC`, `return` and the like.
  static func normalize(_ chord: String) -> String? {
    var spelled = chord.trimmingCharacters(in: .whitespaces).lowercased()
    // `ctrl-c` style: a dash after a modifier name means `+` (a lone `-` is still the minus key).
    for name in modifierNames.keys { spelled = spelled.replacingOccurrences(of: "\(name)-", with: "\(name)+") }
    var parts = spelled.split(separator: "+", omittingEmptySubsequences: false).map(String.init)
    guard let last = parts.popLast(), !last.isEmpty else { return nil }
    var mods = Set<String>()
    for part in parts {
      guard let mod = modifierNames[part] else { return nil }
      mods.insert(mod)
    }
    let name = aliases[last] ?? last
    let known =
      namedKeys.contains(name)
      || (name.count == 1 && singleKeys.contains(name.first!))
      || (name.hasPrefix("f") && Int(name.dropFirst()).map { (1...24).contains($0) } == true)
    guard known else { return nil }
    return ["ctrl", "alt", "shift"].filter(mods.contains).map { "\($0)+" }.joined() + name
  }

  /// The model's reply as steps: a JSON array of `{"text": ...}` and `{"key": ...}` objects (a
  /// bare string counts as text). Adjacent text steps merge, empty ones drop, and a line break
  /// inside text becomes a space, so only an explicit `enter` step submits anything.
  static func parse(_ reply: String) throws -> [AiKeystroke] {
    let trimmed = AiService.stripFences(reply).trimmingCharacters(in: .whitespacesAndNewlines)
    guard let start = trimmed.firstIndex(of: "["), let end = trimmed.lastIndex(of: "]"), start < end,
      let items = (try? JSONSerialization.jsonObject(with: Data(trimmed[start...end].utf8))) as? [Any]
    else { throw AiService.Failure(description: "The AI's reply wasn't a list of keystrokes") }
    return try parse(items)
  }

  /// [items] as steps, as [parse] reads a reply's array. A step may also name both fields with
  /// the unused one null (`{"text": null, "key": "enter"}`), the form the agent's schema asks for.
  static func parse(_ items: [Any]) throws -> [AiKeystroke] {
    var steps: [AiKeystroke] = []
    for item in items {
      let object = item as? [String: Any]
      if let text = item as? String ?? object?["text"] as? String {
        let flat = text.components(separatedBy: .newlines).joined(separator: " ")
        guard !flat.isEmpty else { continue }
        if case .text(let before)? = steps.last {
          steps[steps.count - 1] = .text(before + flat)
        } else {
          steps.append(.text(flat))
        }
      } else if let chord = object?["key"] as? String {
        guard let key = normalize(chord) else { throw AiService.Failure(description: "The AI asked for a key cmux can't press: \(chord)") }
        steps.append(.key(key))
      } else {
        throw AiService.Failure(description: "The AI's reply wasn't a list of keystrokes")
      }
    }
    return steps
  }

  /// [steps] for the log without what they type: `12 chars, enter`.
  static func summary(_ steps: [AiKeystroke]) -> String {
    let chars = steps.reduce(0) { count, step in if case .text(let text) = step { return count + text.count } else { return count } }
    let keys = steps.compactMap { step -> String? in if case .key(let chord) = step { return chord } else { return nil } }
    return (["\(chars) chars"] + keys).joined(separator: ", ")
  }
}
