import Darwin
import Foundation

/// The AI mic's agent mode: the provider's own coding agent (Claude Code, Codex or Gemini CLI, each
/// signed in with its own login) works on the user's behalf on the Mac. It can look through their
/// files, run commands and act in the terminal they were looking at, where they watch it; its
/// thinking and each tool call stream to the headset as AI_PROGRESS, shown beside that terminal.
///
/// The agents are not driven here: `mac/agent/vrcode-agent.ts` (bundled into the app's Resources and
/// run by its `vrcode-bun` sidecar) talks to them over the Agent Client Protocol with the official SDK. This file runs
/// that runner, one process shared by every headset connection, and trades JSON lines with it
/// ([AgentRunner]).
enum AiAgent {
  /// What the agent is doing, for the headset's thinking panel.
  enum Progress: Equatable {
    /// One line about the run itself ("Claude Code in ~/project").
    case status(String)
    /// A chunk of the model's thinking, or of what it wrote between tool calls. Chunks continue each other.
    case thinking(String)
    /// One tool call, as one line: `$ git status`, `Types: cd ~/project · Presses: enter`.
    case step(String)

    var json: [String: String] {
      switch self {
      case .status(let text): return ["kind": "status", "text": text]
      case .thinking(let text): return ["kind": "thinking", "text": text]
      case .step(let text): return ["kind": "step", "text": text]
      }
    }
  }

  /// The provider's agent, as the user knows it.
  static func cliName(_ provider: AiProvider) -> String {
    switch provider {
    case .claude: return "Claude Code"
    case .openai: return "Codex"
    case .gemini: return "Gemini CLI"
    }
  }

  /// Runs [provider]'s agent with [model] on [transcript] for [pane], reporting [progress] as it
  /// goes, and returns its one-sentence summary of what it did. [terminal] is cmux's id for the
  /// terminal the user was looking at and [cmux] the CLI and socket to act in it; [cwd] is where
  /// the agent starts (the home folder when nil or gone). Cancelling the task stops the run.
  static func run(
    provider: AiProvider, model: String, transcript: String, pane: AiService.Pane, terminal: String?,
    cwd: String?, cmux: (binary: String, socket: String)?, progress: @escaping @Sendable (Progress) -> Void
  ) async throws -> String {
    let home = FileManager.default.homeDirectoryForCurrentUser.path
    let directory = cwd.flatMap { isDirectory($0) ? $0 : nil } ?? home
    var request: [String: Any] = [
      "type": "run", "provider": provider.rawValue, "model": model, "cwd": directory,
      "prompt": AiService.prompt(transcript: transcript, pane: pane),
    ]
    if let terminal, let cmux {
      request["terminal"] = terminal
      request["cmux"] = ["binary": cmux.binary, "socket": cmux.socket]
    }
    return try await AgentRunner.shared.run(request, progress: progress)
  }

  // MARK: Environment

  /// Directories searched for programs: the login shell's PATH (under the menu-bar app's
  /// LaunchAgent PATH is only the system's, while Bun, cmux and the agents' CLIs live in Homebrew,
  /// npm, Bun or their own folders), then the usual install folders.
  static func searchPath() -> [String] {
    let home = FileManager.default.homeDirectoryForCurrentUser.path
    let inherited = (ProcessInfo.processInfo.environment["PATH"] ?? "").split(separator: ":").map(String.init)
    let common = [
      "\(home)/.local/bin", "\(home)/.cmux/bin", "\(home)/.bun/bin", "/opt/homebrew/bin", "/usr/local/bin",
      "\(home)/.npm-global/bin", "\(home)/.volta/bin", "\(home)/.cargo/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin",
    ]
    var seen = Set<String>()
    return (inherited + loginShellPath + common).filter { !$0.isEmpty && seen.insert($0).inserted }
  }

  /// The user's login shell's PATH, read once (`$SHELL -l -c`); empty if it takes over 5 s.
  private static let loginShellPath: [String] = {
    let shell = ProcessInfo.processInfo.environment["SHELL"] ?? getpwuid(getuid()).map { String(cString: $0.pointee.pw_shell) } ?? "/bin/zsh"
    let process = Process()
    process.executableURL = URL(fileURLWithPath: shell)
    process.arguments = ["-l", "-c", #"printf '%s' "$PATH""#]
    let output = Pipe()
    process.standardOutput = output
    process.standardError = FileHandle.nullDevice
    process.standardInput = FileHandle.nullDevice
    guard (try? process.run()) != nil else { return [] }
    let deadline = DispatchTime.now() + 5
    DispatchQueue.global().asyncAfter(deadline: deadline) { if process.isRunning { process.terminate() } }
    let data = output.fileHandleForReading.readDataToEndOfFile()
    process.waitUntilExit()
    guard process.terminationStatus == 0 else { return [] }
    return String(decoding: data, as: UTF8.self).split(separator: ":").map(String.init)
  }()

  /// The first executable named [name] on [searchPath].
  static func which(_ name: String) -> String? {
    searchPath().map { "\($0)/\(name)" }.first(where: FileManager.default.isExecutableFile(atPath:))
  }

  private static func isDirectory(_ path: String) -> Bool {
    var directory: ObjCBool = false
    return FileManager.default.fileExists(atPath: path, isDirectory: &directory) && directory.boolValue
  }

  static func abbreviate(_ path: String, home: String) -> String {
    path == home ? "~" : path.hasPrefix(home + "/") ? "~" + path.dropFirst(home.count) : path
  }

  // MARK: cmux

  /// The cmux CLI and the socket of [endpoint]'s session, for the agent to act in the user's terminal.
  static func cmux(_ endpoint: CmuxEndpoint) -> (binary: String, socket: String)? {
    guard let binary = CmuxEndpoint.binary(), let socket = try? endpoint.resolve() else { return nil }
    return (binary, socket)
  }

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

/// The agent runner process (`vrcode-agent.js` under the bundled Bun), started on first use and again after it exits.
/// Requests go in as JSON lines (`run`, `cancel`) with an id of its own; progress, `done` and
/// `error` lines come back with that id.
final class AgentRunner: @unchecked Sendable {
  static let shared = AgentRunner()

  private struct Pending {
    let progress: @Sendable (AiAgent.Progress) -> Void
    let continuation: CheckedContinuation<String, Error>
  }

  private let lock = NSLock()
  private var process: Process?
  private var input: FileHandle?
  private var nextId = 0
  private var pending: [Int: Pending] = [:]
  /// The last lines the runner wrote to stderr, for the error when it exits.
  private var stderrTail: [String] = []

  /// Runs [request] (without `id`) and returns the agent's summary; cancelling the task cancels the run.
  func run(_ request: [String: Any], progress: @escaping @Sendable (AiAgent.Progress) -> Void) async throws -> String {
    let id = lock.withLock { () -> Int in nextId += 1; return nextId }
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
        lock.withLock {
          if Task.isCancelled { return continuation.resume(throwing: CancellationError()) }
          do { try startLocked() } catch { return continuation.resume(throwing: error) }
          pending[id] = Pending(progress: progress, continuation: continuation)
          var line = request
          line["id"] = id
          writeLocked(line)
        }
      }
    } onCancel: {
      lock.withLock {
        guard let waiting = pending.removeValue(forKey: id) else { return }
        writeLocked(["type": "cancel", "id": id])
        waiting.continuation.resume(throwing: CancellationError())
      }
    }
  }

  /// The runner's command line: `VRCODE_AGENT_BIN` (an executable runner), else the app bundle's
  /// Bun (the `vrcode-bun` sidecar next to this executable) running its bundled
  /// `Resources/vrcode-agent.js`, else, in a development build, `bun mac/agent/vrcode-agent.ts`
  /// from the checkout this was built from.
  static func command() -> (executable: String, arguments: [String])? {
    let manager = FileManager.default
    if let override = ProcessInfo.processInfo.environment["VRCODE_AGENT_BIN"], manager.isExecutableFile(atPath: override) {
      return (override, [])
    }
    if let executable = Bundle.main.executableURL?.resolvingSymlinksInPath() {
      let macOS = executable.deletingLastPathComponent()
      let bun = macOS.appendingPathComponent("vrcode-bun").path
      let script = macOS.deletingLastPathComponent().appendingPathComponent("Resources/vrcode-agent.js").path
      if manager.isExecutableFile(atPath: bun), manager.fileExists(atPath: script) { return (bun, [script]) }
    }
    let source = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
      .appendingPathComponent("agent/vrcode-agent.ts").path
    if manager.fileExists(atPath: source), let bun = AiAgent.which("bun") { return (bun, [source]) }
    return nil
  }

  private func startLocked() throws {
    if process?.isRunning == true { return }
    guard let (executable, arguments) = Self.command() else {
      throw AiService.Failure(description: "The AI agent runner (vrcode-bun and vrcode-agent.js) is missing from VRcode Mac; reinstall it")
    }
    let process = Process()
    process.executableURL = URL(fileURLWithPath: executable)
    process.arguments = arguments
    process.currentDirectoryURL = FileManager.default.homeDirectoryForCurrentUser
    var environment = ProcessInfo.processInfo.environment
    environment["PATH"] = AiAgent.searchPath().joined(separator: ":")
    // Started from inside a Claude Code session (during development), Claude Code would see itself nested.
    for key in ["CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SSE_PORT"] { environment.removeValue(forKey: key) }
    process.environment = environment
    let stdin = Pipe(), stdout = Pipe(), stderr = Pipe()
    process.standardInput = stdin
    process.standardOutput = stdout
    process.standardError = stderr
    // A runner that exits must fail the next write, not SIGPIPE the service.
    _ = fcntl(stdin.fileHandleForWriting.fileDescriptor, F_SETNOSIGPIPE, 1)
    try process.run()
    self.process = process
    input = stdin.fileHandleForWriting
    stderrTail = []
    log("agent runner started: \(([executable] + arguments).joined(separator: " "))")
    Thread.detachNewThread { [weak self] in
      var buffer = Data()
      // availableData returns whatever has arrived (read(upToCount:) would wait for a full buffer).
      for data in AnyIterator({ () -> Data? in let data = stderr.fileHandleForReading.availableData; return data.isEmpty ? nil : data }) {
        buffer.append(data)
        while let newline = buffer.firstIndex(of: 10) {
          let line = String(decoding: buffer[..<newline], as: UTF8.self)
          buffer = Data(buffer[buffer.index(after: newline)...])
          guard !line.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
          log("agent runner: \(line)")
          self?.lock.withLock {
            self?.stderrTail.append(line)
            if let count = self?.stderrTail.count, count > 5 { self?.stderrTail.removeFirst(count - 5) }
          }
        }
      }
    }
    Thread.detachNewThread { [weak self] in
      var buffer = Data()
      for data in AnyIterator({ () -> Data? in let data = stdout.fileHandleForReading.availableData; return data.isEmpty ? nil : data }) {
        buffer.append(data)
        while let newline = buffer.firstIndex(of: 10) {
          let line = Data(buffer[..<newline])
          buffer = Data(buffer[buffer.index(after: newline)...])
          self?.receive(line)
        }
      }
      process.waitUntilExit()
      self?.exited(process)
    }
  }

  private func writeLocked(_ object: [String: Any]) {
    guard var line = try? JSONSerialization.data(withJSONObject: object) else { return }
    line.append(10)
    try? input?.write(contentsOf: line)
  }

  private func receive(_ line: Data) {
    guard let message = (try? JSONSerialization.jsonObject(with: line)) as? [String: Any],
          let id = (message["id"] as? NSNumber)?.intValue else { return }
    switch message["type"] as? String {
    case "progress":
      let text = message["text"] as? String ?? ""
      let progress: AiAgent.Progress
      switch message["kind"] as? String {
      case "status": progress = .status(text)
      case "step": progress = .step(text)
      default: progress = .thinking(text)
      }
      lock.withLock { pending[id] }?.progress(progress)
    case "done":
      lock.withLock { pending.removeValue(forKey: id) }?.continuation.resume(returning: message["summary"] as? String ?? "")
    case "error":
      let failure = AiService.Failure(description: message["message"] as? String ?? "The AI agent failed")
      lock.withLock { pending.removeValue(forKey: id) }?.continuation.resume(throwing: failure)
    default:
      break
    }
  }

  /// The runner exited: every run in flight fails, and the next request starts it again.
  private func exited(_ ended: Process) {
    let (failed, detail) = lock.withLock { () -> ([Pending], String) in
      guard process === ended else { return ([], "") }
      process = nil
      input = nil
      let failed = Array(pending.values)
      pending = [:]
      return (failed, stderrTail.last ?? "exit status \(ended.terminationStatus)")
    }
    log("agent runner exited (\(ended.terminationStatus))")
    for waiting in failed {
      waiting.continuation.resume(throwing: AiService.Failure(description: "The AI agent runner stopped: \(detail)"))
    }
  }
}
