import Foundation

/// The AI mic's hearing: what the user said, written down by a model that hears their recording.
///
/// Apple's on-device recognizer (Transcription.swift) gives the headset its live preview while the
/// fist is held, but it spells developer names by sound: "claw code" for Claude Code. So the headset
/// has the Mac keep an AI-mic dictation's audio (AUDIO_START `ai`), and when it asks the AI
/// (AI_REQUEST `utterances`), the recording goes to a model that hears it with the target pane's
/// screen in view and writes down what was said, names spelled the way developers write them. That
/// text, not the preview, is what the quick answer or the agent works from.
///
/// Claude's API takes no audio, so the listener is Gemini (the recording and the screen in one
/// generateContent request) or OpenAI's `gpt-transcribe` (the screen and developer vocabulary as its
/// prompt and keywords), whichever has a key in VRcode Mac: the AI mic's own provider when it is
/// one of them, else Gemini, then OpenAI. With neither key the preview transcript is used.
enum AiHearing {
  /// 16-bit little-endian PCM, as the headset streamed it.
  struct Audio: Equatable {
    var pcm: Data
    var sampleRate: Int
    var channels: Int

    var seconds: Double { Double(pcm.count) / Double(max(1, sampleRate * channels * 2)) }
  }

  /// The model that hears a request.
  struct Listener: Equatable {
    var provider: AiProvider
    var model: String
  }

  /// OpenAI's speech model; it takes the screen as a prompt and names as keywords.
  static let openaiModel = "gpt-transcribe"

  /// A request waits this long for the listener before the preview transcript is used instead.
  static let timeout: TimeInterval = 20

  /// Names developers say that speech recognition spells by sound. OpenAI gets them as keywords;
  /// Gemini sees the screen and its instructions name the common ones. (One string: the repository's
  /// script check reads a quoted package-manager name as a subprocess.)
  static let vocabulary = """
    Claude, Claude Code, Anthropic, Codex, ChatGPT, OpenAI, Gemini, Gemini CLI, cmux, tmux, vrcode, Quest, \
    Bun, bunx, npm, npx, pnpm, Node, TypeScript, Kotlin, Swift, Xcode, Gradle, adb, git, GitHub, gh, zsh, \
    Homebrew, Docker, README, AGENTS.md, CLAUDE.md, package.json
    """.components(separatedBy: ", ")

  static let system = """
    You hear a developer speaking to an AI assistant through a VR headset, about the terminal pane \
    sent with the recording. Write down what they said: their words, in order, as plain text. Only \
    write it down: do not answer it, carry it out or add to it.

    You are hearing it because speech recognition spells developer names by sound. Spell names the \
    way a developer writes them, using the screen and developer vocabulary: products and tools \
    (Claude Code, not "claw code"; Codex, Gemini CLI, cmux, Bun, npm, Xcode, Gradle, adb, GitHub), \
    commands and flags, branches, files, folders and code. When what they said sounds like a name on \
    the screen, write that name. Spoken symbols that name something are written as typed ("package \
    dot json" is package.json, "dash dash force" is --force); everything else stays as they said it.

    Reply with only what they said. If the recording has no intelligible speech, reply with nothing.
    """

  /// Who hears a request for [provider]'s [model]: that provider when it can hear audio and has a
  /// key, else Gemini (its default model), then OpenAI, whichever has a key; nil when neither does.
  static func listener(
    provider: AiProvider, model: String, hasKey: (AiProvider) -> Bool = { AiService.apiKey($0) != nil }
  ) -> Listener? {
    if provider == .gemini, hasKey(.gemini) { return Listener(provider: .gemini, model: model) }
    if provider == .openai, hasKey(.openai) { return Listener(provider: .openai, model: openaiModel) }
    if hasKey(.gemini) { return Listener(provider: .gemini, model: AiProvider.gemini.defaultModel) }
    if hasKey(.openai) { return Listener(provider: .openai, model: openaiModel) }
    return nil
  }

  /// What was said in [audio], as [listener] heard it with [pane] in view; empty when it heard no speech.
  static func hear(_ audio: Audio, pane: AiService.Pane, by listener: Listener) async throws -> String {
    guard let key = AiService.apiKey(listener.provider) else {
      throw AiService.Failure(description: "\(listener.provider.label) has no key to hear with")
    }
    let text: String
    switch listener.provider {
    case .gemini: text = try await gemini(key: key, model: listener.model, audio: audio, pane: pane)
    case .openai: text = try await openai(key: key, audio: audio, pane: pane)
    case .claude: throw AiService.Failure(description: "Claude's API takes no audio")
    }
    return text.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  /// The user turn's text for Gemini: the pane's screen and cursor line, as the AI later sees them.
  static func context(_ pane: AiService.Pane) -> String {
    var text = "<pane title=\"\(pane.title.replacingOccurrences(of: "\"", with: "'"))\">\n\(pane.screen)\n</pane>"
    if let line = AiService.cursorLine(pane) { text += "\n\n<cursor_line>\(line)</cursor_line>" }
    return text + "\n\nWrite down what they say in this recording."
  }

  /// OpenAI's prompt: what the recording is about, with the end of the pane's screen.
  static func openaiPrompt(_ pane: AiService.Pane) -> String {
    let screen = String(pane.screen.suffix(2000))
    return """
      A developer wearing a VR headset is speaking to an AI coding assistant about this terminal \
      (\(pane.title)). Names of tools, products, commands and files are spelled the way developers \
      write them.

      \(screen)
      """
  }

  /// [audio] as a WAV file.
  static func wav(_ audio: Audio) -> Data {
    var out = Data(capacity: 44 + audio.pcm.count)
    out.append(contentsOf: Array("RIFF".utf8))
    out.appendLE(UInt32(36 + audio.pcm.count))
    out.append(contentsOf: Array("WAVEfmt ".utf8))
    out.appendLE(UInt32(16))
    out.appendLE(UInt16(1)) // integer PCM
    out.appendLE(UInt16(audio.channels))
    out.appendLE(UInt32(audio.sampleRate))
    out.appendLE(UInt32(audio.sampleRate * audio.channels * 2))
    out.appendLE(UInt16(audio.channels * 2))
    out.appendLE(UInt16(16))
    out.append(contentsOf: Array("data".utf8))
    out.appendLE(UInt32(audio.pcm.count))
    out.append(audio.pcm)
    return out
  }

  // MARK: Providers

  /// Gemini generateContent with the recording inline: it hears the audio and reads the screen in one turn.
  private static func gemini(key: String, model: String, audio: Audio, pane: AiService.Pane) async throws -> String {
    let body: [String: Any] = [
      "system_instruction": ["parts": [["text": system]]],
      "contents": [[
        "role": "user",
        "parts": [
          ["text": context(pane)],
          ["inline_data": ["mime_type": "audio/wav", "data": wav(audio).base64EncodedString()]],
        ],
      ]],
      "generationConfig": ["maxOutputTokens": 2048, "thinkingConfig": ["thinkingLevel": "low"]],
    ]
    let url = base("VRCODE_GEMINI_API_BASE", "https://generativelanguage.googleapis.com") + "/v1beta/models/\(model):generateContent"
    let json = try await AiService.post(.gemini, url, body: body, headers: ["x-goog-api-key": key], timeout: timeout)
    if let blocked = (json["promptFeedback"] as? [String: Any])?["blockReason"] as? String {
      throw AiService.Failure(description: "Gemini declined to hear it (\(blocked))")
    }
    let candidate = (json["candidates"] as? [[String: Any]])?.first ?? [:]
    let parts = (candidate["content"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []
    return parts.filter { $0["thought"] as? Bool != true }.compactMap { $0["text"] as? String }.joined()
  }

  /// OpenAI's transcription endpoint (multipart): the recording, the screen as the prompt, names as keywords.
  private static func openai(key: String, audio: Audio, pane: AiService.Pane) async throws -> String {
    let boundary = "vrcode-\(UUID().uuidString)"
    var body = Data()
    func field(_ name: String, _ value: String) {
      body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".utf8))
    }
    field("model", openaiModel)
    field("prompt", openaiPrompt(pane))
    for word in vocabulary { field("keywords[]", word) }
    body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".utf8))
    body.append(wav(audio))
    body.append(Data("\r\n--\(boundary)--\r\n".utf8))
    var request = URLRequest(url: URL(string: base("VRCODE_OPENAI_API_BASE", "https://api.openai.com") + "/v1/audio/transcriptions")!)
    request.httpMethod = "POST"
    request.timeoutInterval = timeout
    request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "content-type")
    request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
    request.httpBody = body
    let json = try await AiService.send(.openai, request)
    return json["text"] as? String ?? ""
  }

  /// An API's base URL; the environment variable [variable] points hearing elsewhere (tests use a fake API).
  private static func base(_ variable: String, _ standard: String) -> String {
    ProcessInfo.processInfo.environment[variable].flatMap { $0.isEmpty ? nil : $0 } ?? standard
  }
}
