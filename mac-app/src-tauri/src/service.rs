//! Runs the bundled helpers (`vrcode-mac`, `xmux-bridge`) and keeps them running.
//!
//! Each helper's output is echoed verbatim to this process's stdout and stderr (the LaunchAgent
//! log and the relay tests read it there). It is also kept in a shared ring buffer for the status
//! window, tagged with the helper's name.
//!
//! A crash or a non-zero exit restarts the helper with backoff: 1 s doubling to 30 s, reset after
//! a minute of uptime. What a deliberate stop means depends on the helper ([`OnStop`]).

use std::collections::VecDeque;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::os::unix::process::ExitStatusExt;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitStatus, Stdio};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

use serde::Serialize;
use tauri::AppHandle;

const LOG_LINES: usize = 2000;
const HEALTHY_AFTER: Duration = Duration::from_secs(60);
const FIRST_BACKOFF: Duration = Duration::from_secs(1);
const MAX_BACKOFF: Duration = Duration::from_secs(30);
const STOP_GRACE: Duration = Duration::from_secs(3);

/// What a deliberate stop (SIGTERM, SIGINT, SIGHUP or a clean exit) of the helper means.
#[derive(Clone, Copy, PartialEq)]
pub enum OnStop {
    /// Quit the app with status 0. The LaunchAgent (`KeepAlive.SuccessfulExit = false`) then
    /// leaves it stopped. The relay tests stop a test copy of the app this way.
    QuitApp,
    /// Start it again like after a crash.
    Restart,
}

pub struct Helper {
    pub name: &'static str,
    pub executable: PathBuf,
    pub args: Vec<String>,
    pub on_stop: OnStop,
    /// The TCP port it listens on, for a clear message when something else holds it.
    pub port: Option<u16>,
    /// Rewrites its log lines for the status window (the echoed output stays verbatim).
    pub rewrite: Option<fn(&str) -> String>,
}

/// A bundled helper: Tauri places sidecars next to the main executable (Contents/MacOS in the
/// bundle, target/<profile> during `cargo tauri dev`).
pub fn sidecar(name: &str) -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(|dir| dir.join(name)))
        .unwrap_or_else(|| PathBuf::from(name))
}

/// This app's own arguments, minus macOS launch noise.
pub fn passthrough_args(args: impl Iterator<Item = String>) -> Vec<String> {
    args.filter(|arg| !arg.starts_with("-psn_")).collect()
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum State {
    Starting,
    Running,
    Restarting,
    Stopped,
}

impl State {
    pub fn label(self) -> &'static str {
        match self {
            State::Starting => "starting",
            State::Running => "running",
            State::Restarting => "restarting",
            State::Stopped => "stopped",
        }
    }
}

#[derive(Clone, Serialize)]
pub struct LogLine {
    pub seq: u64,
    pub source: &'static str,
    pub stream: &'static str,
    pub text: String,
}

/// The log shared by all helpers and the app.
pub struct Log {
    lines: Mutex<(VecDeque<LogLine>, u64)>,
}

impl Log {
    pub fn new() -> Arc<Self> {
        Arc::new(Self { lines: Mutex::new((VecDeque::with_capacity(LOG_LINES), 1)) })
    }

    pub fn since(&self, seq: u64) -> Vec<LogLine> {
        let lines = self.lines.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        lines.0.iter().filter(|line| line.seq > seq).cloned().collect()
    }

    fn push(&self, source: &'static str, stream: &'static str, text: String) {
        let mut lines = self.lines.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let seq = lines.1;
        lines.1 += 1;
        if lines.0.len() == LOG_LINES {
            lines.0.pop_front();
        }
        lines.0.push_back(LogLine { seq, source, stream, text });
    }
}

#[derive(Serialize)]
pub struct Snapshot {
    name: &'static str,
    state: State,
    pid: Option<u32>,
    uptime_secs: Option<u64>,
    restarts: u32,
    last_exit: Option<String>,
    args: Vec<String>,
}

struct Inner {
    state: State,
    pid: Option<u32>,
    started: Option<Instant>,
    restarts: u32,
    last_exit: Option<String>,
    restart_requested: bool,
    shutting_down: bool,
}

type StateListener = Box<dyn Fn(&'static str, State) + Send + Sync>;

pub struct Service {
    helper: Helper,
    log: Arc<Log>,
    inner: Mutex<Inner>,
    changed: Condvar,
    listener: Mutex<Option<StateListener>>,
}

impl Service {
    pub fn new(helper: Helper, log: Arc<Log>) -> Arc<Self> {
        Arc::new(Self {
            helper,
            log,
            inner: Mutex::new(Inner {
                state: State::Starting,
                pid: None,
                started: None,
                restarts: 0,
                last_exit: None,
                restart_requested: false,
                shutting_down: false,
            }),
            changed: Condvar::new(),
            listener: Mutex::new(None),
        })
    }

    pub fn name(&self) -> &'static str {
        self.helper.name
    }

    pub fn executable(&self) -> &Path {
        &self.helper.executable
    }

    pub fn is_running(&self) -> bool {
        self.lock().state == State::Running
    }

    /// Starts supervising; `on_state` hears every state change (the tray's status lines).
    pub fn start(self: &Arc<Self>, app: AppHandle, on_state: impl Fn(&'static str, State) + Send + Sync + 'static) {
        *self.listener.lock().unwrap() = Some(Box::new(on_state));
        self.reap_orphan();
        let service = self.clone();
        thread::Builder::new()
            .name(format!("{} supervisor", self.helper.name))
            .spawn(move || service.supervise(app))
            .expect("supervisor thread");
    }

    pub fn snapshot(&self) -> Snapshot {
        let inner = self.lock();
        Snapshot {
            name: self.helper.name,
            state: inner.state,
            pid: inner.pid,
            uptime_secs: inner.started.map(|started| started.elapsed().as_secs()),
            restarts: inner.restarts,
            last_exit: inner.last_exit.clone(),
            args: self.helper.args.clone(),
        }
    }

    /// Stops the helper and starts it again at once (no backoff, not counted as a failure).
    pub fn restart(&self) {
        let pid = {
            let mut inner = self.lock();
            inner.restart_requested = true;
            inner.pid
        };
        self.changed.notify_all();
        if let Some(pid) = pid {
            signal(pid, libc::SIGTERM);
        }
    }

    /// Stops the helper for good, as the app exits: SIGTERM, then SIGKILL after a grace period.
    pub fn shutdown(&self) {
        let pid = {
            let mut inner = self.lock();
            inner.shutting_down = true;
            inner.pid
        };
        self.changed.notify_all();
        let Some(pid) = pid else { return };
        signal(pid, libc::SIGTERM);
        let inner = self.lock();
        let (inner, timeout) =
            self.changed.wait_timeout_while(inner, STOP_GRACE, |inner| inner.pid == Some(pid)).unwrap();
        drop(inner);
        if timeout.timed_out() {
            signal(pid, libc::SIGKILL);
        }
    }

    fn supervise(self: Arc<Self>, app: AppHandle) {
        let name = self.helper.name;
        let mut backoff = FIRST_BACKOFF;
        loop {
            if self.lock().shutting_down {
                break;
            }
            self.set_state(State::Starting);
            let spawned = Command::new(&self.helper.executable)
                .args(&self.helper.args)
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn();
            let mut child = match spawned {
                Ok(child) => child,
                Err(error) => {
                    self.note(format!("cannot start {}: {error}", self.helper.executable.display()));
                    self.lock().restarts += 1;
                    self.set_state(State::Restarting);
                    self.pause(backoff);
                    backoff = (backoff * 2).min(MAX_BACKOFF);
                    continue;
                }
            };
            let pid = child.id();
            self.record_pid(Some(pid));
            {
                let mut inner = self.lock();
                inner.pid = Some(pid);
                inner.started = Some(Instant::now());
                inner.restart_requested = false;
            }
            self.note(format!("started {name} (pid {pid}) {}", self.helper.args.join(" ")));
            self.set_state(State::Running);
            // Readers are detached: a grandchild holding the pipes open must not block us.
            if let Some(stdout) = child.stdout.take() {
                self.pipe(stdout, "stdout");
            }
            if let Some(stderr) = child.stderr.take() {
                self.pipe(stderr, "stderr");
            }
            let status = child.wait().ok();
            self.record_pid(None);

            let description = describe(status);
            let (ran, restart_requested, shutting_down) = {
                let mut inner = self.lock();
                inner.pid = None;
                inner.last_exit = Some(description.clone());
                let ran = inner.started.take().map(|started| started.elapsed()).unwrap_or_default();
                (ran, inner.restart_requested, inner.shutting_down)
            };
            self.changed.notify_all();

            if shutting_down {
                self.set_state(State::Stopped);
                break;
            }
            if restart_requested {
                self.note(format!("{name} {description}; restarting on request"));
                backoff = FIRST_BACKOFF;
                continue;
            }
            if self.helper.on_stop == OnStop::QuitApp && deliberate_stop(status) {
                self.note(format!("{name} {description}; quitting"));
                self.set_state(State::Stopped);
                app.exit(0);
                break;
            }
            if ran >= HEALTHY_AFTER {
                backoff = FIRST_BACKOFF;
            }
            if let Some(port) = self.helper.port.filter(|port| ran < Duration::from_secs(10) && port_taken(*port)) {
                self.note(format!(
                    "port {port} is held by another process, so {name} can't listen. Stop the other copy \
                     (for the xmux bridge LaunchAgent: launchctl bootout gui/$(id -u)/dev.improve.xmux.bridge)."
                ));
            }
            self.lock().restarts += 1;
            self.set_state(State::Restarting);
            self.note(format!("{name} {description}; restarting in {} s", backoff.as_secs()));
            self.pause(backoff);
            backoff = (backoff * 2).min(MAX_BACKOFF);
        }
    }

    /// Sleeps for `duration`, waking early for a restart request or shutdown.
    fn pause(&self, duration: Duration) {
        let inner = self.lock();
        let _ = self
            .changed
            .wait_timeout_while(inner, duration, |inner| !inner.restart_requested && !inner.shutting_down)
            .unwrap();
    }

    fn pipe(self: &Arc<Self>, reader: impl Read + Send + 'static, stream: &'static str) {
        let service = self.clone();
        let _ = thread::Builder::new().name(format!("{} {stream}", self.helper.name)).spawn(move || {
            let mut reader = BufReader::new(reader);
            let mut buffer = Vec::new();
            loop {
                buffer.clear();
                match reader.read_until(b'\n', &mut buffer) {
                    Ok(0) | Err(_) => break,
                    Ok(_) => {}
                }
                if stream == "stdout" {
                    let _ = std::io::stdout().lock().write_all(&buffer);
                } else {
                    let _ = std::io::stderr().lock().write_all(&buffer);
                }
                let text = String::from_utf8_lossy(&buffer);
                let text = text.trim_end_matches(['\r', '\n']);
                let text = service.helper.rewrite.map_or_else(|| text.to_string(), |rewrite| rewrite(text));
                service.log.push(service.helper.name, stream, text);
            }
        });
    }

    /// A line from this app itself, logged next to the helpers' output.
    fn note(&self, message: String) {
        let text = format!("[VRcode Mac] {message}");
        let _ = writeln!(std::io::stderr().lock(), "{text}");
        self.log.push(self.helper.name, "app", text);
    }

    fn set_state(&self, state: State) {
        self.lock().state = state;
        if let Some(listener) = self.listener.lock().unwrap().as_ref() {
            listener(self.helper.name, state);
        }
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Where the running helper's pid is recorded, per helper and bundle location.
    fn pid_file(&self) -> PathBuf {
        pid_file_for(self.helper.name, &self.helper.executable)
    }

    fn record_pid(&self, pid: Option<u32>) {
        let path = self.pid_file();
        match pid {
            Some(pid) => {
                if let Some(dir) = path.parent() {
                    let _ = std::fs::create_dir_all(dir);
                }
                let _ = std::fs::write(&path, pid.to_string());
            }
            None => {
                let _ = std::fs::remove_file(&path);
            }
        }
    }

    /// Stops the helper a crashed app left behind, so it doesn't hold the ports. Only the pid
    /// this app recorded is considered, and only while it is still this bundle's helper and
    /// has lost its parent (reparented to launchd). Unrelated processes are never touched, even
    /// apps that `open` started (their parent is launchd too).
    fn reap_orphan(&self) {
        let path = self.pid_file();
        let Ok(recorded) = std::fs::read_to_string(&path) else { return };
        let _ = std::fs::remove_file(&path);
        let Ok(pid) = recorded.trim().parse::<u32>() else { return };
        let Ok(output) = Command::new("/bin/ps").args(["-o", "ppid=,comm=", "-p", &pid.to_string()]).output() else {
            return;
        };
        let line = String::from_utf8_lossy(&output.stdout);
        let Some((ppid, command)) = line.trim().split_once(char::is_whitespace) else { return };
        if ppid == "1" && Path::new(command.trim()) == self.helper.executable {
            self.note(format!("stopping {} (pid {pid}) left running by a previous instance", self.helper.name));
            signal(pid, libc::SIGTERM);
        }
    }
}

fn pid_file_for(name: &str, executable: &Path) -> PathBuf {
    // FNV-1a of the helper's path: copies of the app in different places don't share a file.
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in executable.as_os_str().as_encoded_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0100_0000_01b3);
    }
    std::env::temp_dir().join("dev.improve.vrcode.mac").join(format!("{name}-{hash:016x}.pid"))
}

fn port_taken(port: u16) -> bool {
    TcpStream::connect_timeout(&SocketAddr::from(([127, 0, 0, 1], port)), Duration::from_millis(300)).is_ok()
}

fn deliberate_stop(status: Option<ExitStatus>) -> bool {
    match status {
        Some(status) if status.success() => true,
        Some(status) => matches!(status.signal(), Some(libc::SIGTERM | libc::SIGINT | libc::SIGHUP)),
        None => false,
    }
}

fn describe(status: Option<ExitStatus>) -> String {
    match status {
        None => "ended (status unknown)".into(),
        Some(status) => match (status.code(), status.signal()) {
            (Some(code), _) => format!("exited with status {code}"),
            (None, Some(signal)) => format!("ended by signal {signal} ({})", signal_name(signal)),
            (None, None) => "ended".into(),
        },
    }
}

fn signal_name(signal: i32) -> &'static str {
    match signal {
        libc::SIGTERM => "SIGTERM",
        libc::SIGINT => "SIGINT",
        libc::SIGHUP => "SIGHUP",
        libc::SIGKILL => "SIGKILL",
        libc::SIGSEGV => "SIGSEGV",
        libc::SIGBUS => "SIGBUS",
        libc::SIGABRT => "SIGABRT",
        libc::SIGILL => "SIGILL",
        libc::SIGTRAP => "SIGTRAP",
        _ => "signal",
    }
}

fn signal(pid: u32, signal: i32) {
    unsafe {
        libc::kill(pid as libc::pid_t, signal);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_launch_noise_from_arguments() {
        let args = ["-psn_0_12345", "--bluetooth", "--port", "7273"].map(String::from);
        assert_eq!(passthrough_args(args.into_iter()), ["--bluetooth", "--port", "7273"]);
    }

    #[test]
    fn stop_signals_and_clean_exits_are_deliberate() {
        let signalled = |signal: i32| Some(ExitStatus::from_raw(signal));
        let exited = |code: i32| Some(ExitStatus::from_raw(code << 8));
        assert!(deliberate_stop(exited(0)));
        assert!(deliberate_stop(signalled(libc::SIGTERM)));
        assert!(deliberate_stop(signalled(libc::SIGINT)));
        assert!(!deliberate_stop(exited(1)));
        assert!(!deliberate_stop(signalled(libc::SIGKILL)));
        assert!(!deliberate_stop(signalled(libc::SIGSEGV)));
        assert!(!deliberate_stop(None));
        assert_eq!(describe(signalled(libc::SIGSEGV)), "ended by signal 11 (SIGSEGV)");
        assert_eq!(describe(exited(1)), "exited with status 1");
    }

    #[test]
    fn pid_files_are_per_helper_and_per_bundle_location() {
        let installed = Path::new("/Users/x/Library/Application Support/VRcode Mac.app/Contents/MacOS/vrcode-mac");
        let built = Path::new("/repo/mac/.build/VRcode Mac.app/Contents/MacOS/vrcode-mac");
        assert_ne!(pid_file_for("vrcode-mac", installed), pid_file_for("vrcode-mac", built));
        assert_ne!(pid_file_for("vrcode-mac", installed), pid_file_for("xmux-bridge", installed));
        assert_eq!(pid_file_for("vrcode-mac", built), pid_file_for("vrcode-mac", built));
    }

    #[test]
    fn log_keeps_order_and_returns_only_newer_lines() {
        let log = Log::new();
        log.push("vrcode-mac", "stderr", "one".into());
        log.push("xmux-bridge", "stdout", "two".into());
        let lines = log.since(1);
        assert_eq!(lines.len(), 1);
        assert_eq!((lines[0].source, lines[0].text.as_str()), ("xmux-bridge", "two"));
    }
}
