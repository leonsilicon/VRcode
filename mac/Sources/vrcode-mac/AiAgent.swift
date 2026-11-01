import Darwin
import Foundation

/// The AI mic's thinking mode: runs the chosen provider's coding-agent CLI headless on the Mac,
/// like a person would run an agent, in the target pane's working directory: Claude Code
/// (`claude -p`), Codex (`codex exec`) or Gemini CLI (`gemini -p`), each signed in with its own
/// login. The agent thinks and may look around first with read-only tools (read the project's
/// instructions and scripts, search the code, check git state); its thinking and each tool call
/// stream to the headset as AI_PROGRESS, which shows them above or below the target window. It
/// changes nothing itself: its answer is the keystrokes for the pane, which the headset types, so
/// whatever runs, runs in the user's terminal where they watch it.
///
/// Read-only is enforced by each CLI, not by the prompt: Claude Code runs `--restricted` (user and
/// project settings, with their allow rules, are ignored) with only Read, Grep, Glob and Bash, and
/// `--permission-prompts none`, so any command that is not read-only is denied; Codex runs in its
/// `read-only` sandbox; Gemini CLI in its `plan` approval mode.
enum AiAgent {
  /// What the agent is doing, for the headset's thinking panel.
  enum Progress: Equatable {
    /// One line about the run itself ("Claude Code in ~/project").
    case status(String)
    /// A chunk of the model's thinking (Claude's thinking summaries, Codex's reasoning summaries)
    /// or of what it wrote between tool calls. Chunks continue each other.
    case thinking(String)
    /// One tool call, as one line: `$ git status`, `Read package.json`.
    case step(String)

    var json: [String: String] {
      switch self {
      case .status(let text): return ["kind": "status", "text": text]
      case .thinking(let text): return ["kind": "thinking", "text": text]
      case .step(let text): return ["kind": "step", "text": text]
      }
    }
  }

  /// The agent's answer: the keystrokes for the pane and one sentence about them.
  struct Answer {
    var steps: [AiKeystroke]
    var summary: String
  }

  /// How long a run may take before it is stopped.
  static let timeout: TimeInterval = 120

  /// Read-only tool calls a run may make before it must answer (Claude Code's turn limit).
  static let maxTurns = 12

  static let system = """
    You turn a developer's spoken request into the keystrokes to send to one terminal pane. They are \
    in VR, speaking instead of typing, and watch your thinking next to that pane. You run on their \
    Mac in the pane's working directory. You get everything on the pane's screen, the line the \
    cursor is on (▮ marks the cursor), the working directory and a transcript of what they said; \
    speech recognition may mishear technical words, so correct them using the screen, the project \
    and common developer vocabulary.

    Think first, and look around when it helps you send the right thing: the project's instructions \
    and scripts (AGENTS.md, CLAUDE.md, README, package.json, Makefile, justfile, Cargo.toml, \
    pyproject.toml, ...), its files, the code, git state. Use only read-only tools and commands. \
    Never create, change or delete anything, install packages, or run builds, tests, servers or \
    anything slow yourself: whatever should run is what you send to the pane, where they watch it \
    run. Keep looking around to a few quick calls; they are waiting. Skip it when the screen \
    already makes the answer clear.

    Work out what the pane is showing and send what does what they asked there:
    - A shell prompt: one shell command (a short pipeline or && chain is fine), using the project's \
    own scripts and tools where it has them (the package manager its lockfile names, its make or \
    just targets, ...). Use the screen and the project for paths, branch names, file names and \
    earlier commands.
    - A coding agent (Claude Code, Codex or similar) waiting for input: a clear prompt that keeps \
    everything they asked for and nothing they didn't, with the file names and facts you found.
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

    Answer with structured output: `summary`, one short sentence for them about what you are sending \
    and why (for example "Runs the Bun test suite, as AGENTS.md says"), and `keys`, the steps in \
    order. Each step sets exactly one of `text`, typed exactly as given, and `key`, one key press, \
    and sets the other to null. Keys: enter, tab, backtab, escape, backspace, delete, up, down, left, \
    right, home, end, pageup, pagedown, space, f1 to f24, or a letter, digit or punctuation key, \
    optionally with ctrl+, alt+ or shift+ (ctrl+c, ctrl+u, alt+left). Text never contains a line \
    break; Enter is always its own key step. For example {"summary": "Shows what changed", "keys": \
    [{"text": "git status", "key": null}, {"text": null, "key": "enter"}]}.
    """

  /// The answer's shape. Strict-mode friendly (every property required, nulls allowed), which
  /// Codex's `--output-schema` needs.
  static let schema: [String: Any] = [
    "type": "object",
    "properties": [
      "summary": ["type": "string"],
      "keys": [
        "type": "array",
        "items": [
          "type": "object",
          "properties": ["text": ["type": ["string", "null"]], "key": ["type": ["string", "null"]]],
          "required": ["text", "key"],
          "additionalProperties": false,
        ],
      ],
    ],
    "required": ["summary", "keys"],
    "additionalProperties": false,
  ]

  /// The provider's agent CLI, as the user knows it.
  static func cliName(_ provider: AiProvider) -> String {
    switch provider {
    case .claude: return "Claude Code"
    case .openai: return "Codex"
    case .gemini: return "Gemini CLI"
    }
  }

  /// The executable's name on PATH.
  static func command(_ provider: AiProvider) -> String {
    switch provider {
    case .claude: return "claude"
    case .openai: return "codex"
    case .gemini: return "gemini"
    }
  }

  /// Runs [provider]'s agent CLI with [model] on [transcript] for [pane] in [cwd] (the home folder
  /// when nil or gone), reporting [progress] as it goes. Cancelling the task stops the CLI.
  static func run(
    provider: AiProvider, model: String, transcript: String, pane: AiService.Pane, cwd: String?,
    progress: @escaping @Sendable (Progress) -> Void
  ) async throws -> Answer {
    let name = cliName(provider)
    guard let executable = locate(provider) else {
      throw AiService.Failure(description: "\(name) isn't installed on the Mac (no `\(command(provider))` on PATH); install it and sign in, or turn thinking off in Settings → AI mic")
    }
    let home = FileManager.default.homeDirectoryForCurrentUser.path
    let directory = cwd.flatMap { isDirectory($0) ? $0 : nil } ?? home
    progress(.status("\(name) in \(abbreviate(directory, home: home))"))
    let prompt = "<working_directory>\(directory)</working_directory>\n\n" + AiService.prompt(transcript: transcript, pane: pane)

    var schemaFile: URL?
    defer { schemaFile.map { try? FileManager.default.removeItem(at: $0) } }
    let arguments: [String]
    let input: String
    switch provider {
    case .claude:
      arguments = claudeArguments(model: model)
      input = prompt
    case .openai:
      let file = FileManager.default.temporaryDirectory.appendingPathComponent("vrcode-ai-\(UUID().uuidString).json")
      try JSONSerialization.data(withJSONObject: schema).write(to: file)
      schemaFile = file
      arguments = codexArguments(model: model, schema: file.path, cwd: directory)
      input = system + "\n\n" + prompt
    case .gemini:
      arguments = geminiArguments(model: model)
      input = system + "\n\n" + prompt
    }

    var parser = StreamParser(provider: provider)
    let result = try await execute(executable, arguments: arguments, cwd: directory, input: input) { line in
      for event in parser.consume(line) { progress(event) }
    }
    if let failure = parser.failure { throw AiService.Failure(description: "\(name): \(failure)") }
    guard result.status == 0 || parser.answer != nil else {
      let detail = lastLine(result.stderr) ?? "exited with status \(result.status)"
      throw AiService.Failure(description: "\(name) failed: \(detail)")
    }
    guard let answer = parser.answer else { throw AiService.Failure(description: "\(name) gave no answer") }
    return try decode(answer, name: name)
  }

  /// The answer object (`{"summary", "keys"}`) as keystrokes; also accepts a bare array of steps.
  static func decode(_ answer: Any, name: String) throws -> Answer {
    let object = answer as? [String: Any]
    guard let items = object?["keys"] as? [Any] ?? answer as? [Any] else {
      throw AiService.Failure(description: "\(name)'s answer wasn't a list of keystrokes")
    }
    let steps = try AiKeystroke.parse(items)
    guard !steps.isEmpty else { throw AiService.Failure(description: "\(name) found nothing to type") }
    let summary = (object?["summary"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return Answer(steps: steps, summary: summary)
  }

  // MARK: Command lines

  static func claudeArguments(model: String) -> [String] {
    [
      "-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
      "--model", model, "--effort", "medium",
      // Thinking summaries stream as thinking_delta text; without them only token counts arrive.
      "--settings", #"{"showThinkingSummaries":true}"#,
      // Ignore user and project settings (a user's `Bash(*)` allow rule would let any command
      // run) and keep only the read-only tools plus Bash, whose non-read-only commands would need
      // approval, which nobody can give here, so they are denied.
      "--restricted", "--tools", "Read,Grep,Glob,Bash", "--permission-mode", "manual", "--permission-prompts", "none",
      "--safe-mode", "--strict-mcp-config", "--disable-slash-commands", "--no-session-persistence",
      "--max-turns", "\(maxTurns)",
      "--system-prompt", system,
      "--json-schema", String(data: try! JSONSerialization.data(withJSONObject: schema), encoding: .utf8)!,
    ]
  }

  static func codexArguments(model: String, schema: String, cwd: String) -> [String] {
    [
      "exec", "--json", "--sandbox", "read-only", "--skip-git-repo-check", "--ephemeral",
      "--model", model, "-c", #"model_reasoning_effort="medium""#, "-c", #"model_reasoning_summary="auto""#,
      "--output-schema", schema, "--cd", cwd, "-",
    ]
  }

  static func geminiArguments(model: String) -> [String] {
    // The prompt arrives on stdin; `-p` is appended to it and switches to headless mode.
    ["--output-format", "stream-json", "--approval-mode", "plan", "--skip-trust", "--model", model,
     "--prompt", "Answer the request above."]
  }

  // MARK: Stream parsing

  /// Turns one CLI's JSONL output into progress events, its answer and any failure it reports.
  struct StreamParser {
    let provider: AiProvider
    /// The answer object, once the CLI gave one.
    private(set) var answer: Any?
    private(set) var failure: String?
    /// Gemini: assistant text since the last tool call; the final stretch is the answer.
    private var pendingText = ""
    /// Claude: tool calls already reported (each arrives again in later assistant messages).
    private var reported = Set<String>()

    init(provider: AiProvider) { self.provider = provider }

    mutating func consume(_ line: String) -> [Progress] {
      guard let event = (try? JSONSerialization.jsonObject(with: Data(line.utf8))) as? [String: Any] else { return [] }
      switch provider {
      case .claude: return claude(event)
      case .openai: return codex(event)
      case .gemini: return gemini(event)
      }
    }

    /// The answer object in [text] (Codex and Gemini reply with it as text), or nil when [text] is
    /// not one: narration between tool calls.
    static func answer(in text: String) -> Any? {
      let trimmed = AiService.stripFences(text).trimmingCharacters(in: .whitespacesAndNewlines)
      guard let start = trimmed.firstIndex(where: { $0 == "{" || $0 == "[" }),
            let parsed = try? JSONSerialization.jsonObject(with: Data(trimmed[start...].utf8)) else { return nil }
      return (parsed as? [String: Any])?["keys"] != nil || parsed is [Any] ? parsed : nil
    }

    private mutating func claude(_ event: [String: Any]) -> [Progress] {
      switch event["type"] as? String {
      case "stream_event":
        let delta = (event["event"] as? [String: Any])?["delta"] as? [String: Any]
        guard event["parent_tool_use_id"] is NSNull || event["parent_tool_use_id"] == nil else { return [] }
        switch delta?["type"] as? String {
        case "thinking_delta":
          let text = delta?["thinking"] as? String ?? ""
          return text.isEmpty ? [] : [.thinking(text)]
        case "text_delta":
          let text = delta?["text"] as? String ?? ""
          return text.isEmpty ? [] : [.thinking(text)]
        default: return []
        }
      case "assistant":
        let blocks = (event["message"] as? [String: Any])?["content"] as? [[String: Any]] ?? []
        var out: [Progress] = []
        for block in blocks where block["type"] as? String == "tool_use" {
          guard let id = block["id"] as? String, !reported.contains(id) else { continue }
          reported.insert(id)
          let name = block["name"] as? String ?? "tool"
          if name == "StructuredOutput" { continue } // the answer itself
          out.append(.step(AiAgent.describe(tool: name, input: block["input"] as? [String: Any] ?? [:])))
        }
        return out
      case "result":
        if let structured = event["structured_output"], !(structured is NSNull) {
          answer = structured
        } else if event["subtype"] as? String == "error_max_turns" {
          failure = "it looked around for \(AiAgent.maxTurns) steps without answering"
        } else if event["is_error"] as? Bool == true || event["subtype"] as? String != "success" {
          let errors = (event["errors"] as? [Any])?.compactMap { $0 as? String }.joined(separator: "; ")
          failure = [event["result"] as? String, errors, event["subtype"] as? String].compactMap { $0 }.first { !$0.isEmpty } ?? "the run failed"
        } else if let text = event["result"] as? String {
          answer = Self.answer(in: text)
        }
        return []
      default: return []
      }
    }

    private mutating func codex(_ event: [String: Any]) -> [Progress] {
      switch event["type"] as? String {
      case "item.started", "item.completed":
        guard let item = event["item"] as? [String: Any] else { return [] }
        let started = event["type"] as? String == "item.started"
        switch item["type"] as? String {
        case "command_execution":
          guard started, let command = item["command"] as? String else { return [] }
          return [.step("$ " + AiAgent.unwrapShell(command))]
        case "reasoning":
          guard !started, let text = item["text"] as? String, !text.isEmpty else { return [] }
          return [.thinking(text.replacingOccurrences(of: "**", with: "") + "\n")]
        case "agent_message":
          guard !started, let text = item["text"] as? String else { return [] }
          if let parsed = Self.answer(in: text) {
            answer = parsed
            return []
          }
          // A message that isn't the answer is narration between tool calls.
          return text.isEmpty ? [] : [.thinking(text + "\n")]
        case "file_change", "mcp_tool_call", "web_search":
          guard started else { return [] }
          return [.step(String((item["type"] as? String ?? "tool").replacingOccurrences(of: "_", with: " ")))]
        default: return []
        }
      case "turn.failed":
        failure = ((event["error"] as? [String: Any])?["message"] as? String) ?? "the turn failed"
        return []
      case "error":
        failure = event["message"] as? String ?? "error"
        return []
      default: return []
      }
    }

    private mutating func gemini(_ event: [String: Any]) -> [Progress] {
      switch event["type"] as? String {
      case "message":
        guard event["role"] as? String == "assistant", let text = event["content"] as? String else { return [] }
        pendingText += text
        return []
      case "tool_use":
        var out: [Progress] = []
        if !pendingText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { out.append(.thinking(pendingText + "\n")) }
        pendingText = ""
        let name = event["tool_name"] as? String ?? "tool"
        out.append(.step(AiAgent.describe(tool: name, input: event["parameters"] as? [String: Any] ?? [:])))
        return out
      case "result":
        if event["status"] as? String != "success" {
          failure = ((event["error"] as? [String: Any])?["message"] as? String) ?? "the run failed"
        } else {
          answer = Self.answer(in: pendingText)
        }
        return []
      case "error":
        if event["severity"] as? String != "warning" { failure = event["message"] as? String ?? "error" }
        return []
      default: return []
      }
    }
  }

  /// One line for a tool call: `$ git status`, `Read src/main.ts`, `Search "TODO"`.
  static func describe(tool: String, input: [String: Any]) -> String {
    func path(_ key: String) -> String? { (input[key] as? String).map(shortPath) }
    let line: String
    switch tool {
    case "Bash", "run_shell_command": line = "$ " + (input["command"] as? String ?? "")
    case "Read", "read_file": line = "Read " + (path("file_path") ?? path("absolute_path") ?? path("path") ?? "a file")
    case "read_many_files": line = "Read " + ((input["paths"] as? [String])?.joined(separator: ", ") ?? "files")
    case "Grep", "search_file_content", "grep_search": line = "Search \"" + (input["pattern"] as? String ?? "") + "\""
    case "Glob", "glob": line = "Find " + (input["pattern"] as? String ?? "files")
    case "list_directory", "ls": line = "List " + (path("path") ?? path("dir_path") ?? ".")
    default: line = tool
    }
    let flat = line.components(separatedBy: .newlines).joined(separator: " ")
    return flat.count > 160 ? String(flat.prefix(159)) + "…" : flat
  }

  /// The last two components of an absolute path, so the panel shows `src/main.ts`.
  static func shortPath(_ path: String) -> String {
    let parts = path.split(separator: "/")
    return parts.count <= 2 ? path : parts.suffix(2).joined(separator: "/")
  }

  /// Codex runs commands as `/bin/zsh -lc "..."`; the panel shows the command inside.
  static func unwrapShell(_ command: String) -> String {
    for prefix in ["/bin/zsh -lc ", "/bin/bash -lc ", "bash -lc ", "zsh -lc ", "/bin/sh -c "] where command.hasPrefix(prefix) {
      var inner = String(command.dropFirst(prefix.count))
      if inner.count >= 2, let first = inner.first, first == "\"" || first == "'", inner.last == first {
        inner = String(inner.dropFirst().dropLast())
        if first == "\"" { inner = inner.replacingOccurrences(of: "\\\"", with: "\"") }
      }
      return inner
    }
    return command
  }

  // MARK: Process

  /// Directories searched for the CLIs besides PATH: under the menu-bar app's LaunchAgent PATH is
  /// only the system's, while these CLIs install to Homebrew, npm, Bun or their own folders.
  static func searchPath() -> [String] {
    let home = FileManager.default.homeDirectoryForCurrentUser.path
    let inherited = (ProcessInfo.processInfo.environment["PATH"] ?? "").split(separator: ":").map(String.init)
    let common = [
      "\(home)/.local/bin", "\(home)/.claude/local", "/opt/homebrew/bin", "/usr/local/bin", "\(home)/.bun/bin",
      "\(home)/.npm-global/bin", "\(home)/.volta/bin", "\(home)/.cargo/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin",
    ]
    var seen = Set<String>()
    return (inherited + common).filter { !$0.isEmpty && seen.insert($0).inserted }
  }

  /// [provider]'s CLI: `VRCODE_<NAME>_BIN` (also how tests substitute a fake), else the first on
  /// [searchPath].
  static func locate(_ provider: AiProvider) -> URL? {
    let name = command(provider)
    let manager = FileManager.default
    if let override = ProcessInfo.processInfo.environment["VRCODE_\(name.uppercased())_BIN"], manager.isExecutableFile(atPath: override) {
      return URL(fileURLWithPath: override)
    }
    return searchPath().map { "\($0)/\(name)" }.first(where: manager.isExecutableFile(atPath:)).map(URL.init(fileURLWithPath:))
  }

  private static func isDirectory(_ path: String) -> Bool {
    var directory: ObjCBool = false
    return FileManager.default.fileExists(atPath: path, isDirectory: &directory) && directory.boolValue
  }

  static func abbreviate(_ path: String, home: String) -> String {
    path == home ? "~" : path.hasPrefix(home + "/") ? "~" + path.dropFirst(home.count) : path
  }

  private static func lastLine(_ text: String) -> String? {
    text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }.last { !$0.isEmpty }
      .map { $0.count > 300 ? String($0.prefix(299)) + "…" : $0 }
  }

  /// Runs [executable] with [input] on stdin, handing each stdout line to [onLine] as it arrives.
  /// Stops it after [timeout] (a failure) or when the task is cancelled (a CancellationError).
  static func execute(
    _ executable: URL, arguments: [String], cwd: String, input: String, onLine: @escaping (String) -> Void
  ) async throws -> (status: Int32, stderr: String) {
    let process = Process()
    process.executableURL = executable
    process.arguments = arguments
    process.currentDirectoryURL = URL(fileURLWithPath: cwd)
    var environment = ProcessInfo.processInfo.environment
    environment["PATH"] = searchPath().joined(separator: ":")
    // Started from inside a Claude Code session (during development), claude would see itself nested.
    for key in ["CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SSE_PORT"] { environment.removeValue(forKey: key) }
    process.environment = environment
    let stdin = Pipe(), stdout = Pipe(), stderr = Pipe()
    process.standardInput = stdin
    process.standardOutput = stdout
    process.standardError = stderr
    let exited = AsyncStream<Int32>.makeStream()
    process.terminationHandler = { exited.continuation.yield($0.terminationStatus); exited.continuation.finish() }
    // A CLI that exits without reading its prompt must fail the write, not SIGPIPE the service.
    _ = fcntl(stdin.fileHandleForWriting.fileDescriptor, F_SETNOSIGPIPE, 1)
    try process.run()
    // Written off the reader's path, so a prompt larger than the pipe buffer cannot deadlock.
    Thread.detachNewThread {
      try? stdin.fileHandleForWriting.write(contentsOf: Data(input.utf8))
      try? stdin.fileHandleForWriting.close()
    }
    let errors = ErrorTail()
    Thread.detachNewThread {
      while let data = try? stderr.fileHandleForReading.read(upToCount: 4096), !data.isEmpty { errors.append(data) }
    }
    let timedOut = Flag()
    let watchdog = Task {
      try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
      timedOut.set()
      if process.isRunning { process.terminate() }
    }
    defer { watchdog.cancel() }
    return try await withTaskCancellationHandler {
      for try await line in stdout.fileHandleForReading.bytes.lines { onLine(line) }
      var status: Int32 = -1
      for await value in exited.stream { status = value }
      if timedOut.value { throw AiService.Failure(description: "The AI took longer than \(Int(timeout)) s and was stopped") }
      try Task.checkCancellation()
      return (status, errors.text)
    } onCancel: {
      if process.isRunning { process.terminate() }
    }
  }

  /// The last few KB of a CLI's stderr, for its error message.
  private final class ErrorTail: @unchecked Sendable {
    private let lock = NSLock()
    private var data = Data()
    func append(_ more: Data) { lock.withLock { data.append(more); if data.count > 8192 { data = data.suffix(4096) } } }
    var text: String { lock.withLock { String(decoding: data, as: UTF8.self) } }
  }

  private final class Flag: @unchecked Sendable {
    private let lock = NSLock()
    private var isSet = false
    func set() { lock.withLock { isSet = true } }
    var value: Bool { lock.withLock { isSet } }
  }

  // MARK: Working directory

  /// The working directory of what runs in cmux [terminal] (its foreground process group: the
  /// shell at a prompt, or the program it runs), from cmux's `terminal.process.get`; nil when cmux
  /// cannot say.
  static func workingDirectory(endpoint: CmuxEndpoint, terminal: String) -> String? {
    guard let path = try? endpoint.resolve(), let fd = try? CmuxEndpoint.open(path, receiveTimeout: 3) else { return nil }
    defer { Darwin.close(fd) }
    let id = "vrcode-ai-\(UUID().uuidString)"
    let request: [String: Any] = [
      "protocol": "cmux.protocol/2", "type": "request", "id": id, "operation": "terminal.process.get",
      "params": ["machine": "current", "session": "current", "terminal": terminal],
    ]
    guard var line = try? JSONSerialization.data(withJSONObject: request) else { return nil }
    line.append(10)
    let written = line.withUnsafeBytes { Darwin.write(fd, $0.baseAddress!, $0.count) }
    guard written == line.count else { return nil }
    var buffer = Data()
    var bytes = [UInt8](repeating: 0, count: 65536)
    while buffer.count <= maxBodyLength {
      let count = Darwin.read(fd, &bytes, bytes.count)
      if count < 0 && errno == EINTR { continue }
      guard count > 0 else { return nil }
      buffer.append(contentsOf: bytes.prefix(count))
      while let newline = buffer.firstIndex(of: 10) {
        let reply = Data(buffer[..<newline])
        buffer = Data(buffer[buffer.index(after: newline)...])
        guard let response = (try? JSONSerialization.jsonObject(with: reply)) as? [String: Any],
              response["type"] as? String == "response", response["id"] as? String == id else { continue }
        let result = response["result"] as? [String: Any]
        let value = result?["value"] as? [String: Any] ?? result
        return [value?["foreground_cwd"], value?["cwd"]].compactMap { $0 as? String }.first { !$0.isEmpty }
      }
    }
    return nil
  }
}
