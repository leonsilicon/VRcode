//! The bundled xmux bridge: its arguments, derived from the vrcode service's, and headset
//! pairing through its own CLI (`xmux-bridge status --json`, `xmux-bridge pair CODE`), which
//! talks to the running bridge over its control socket.

use std::io::Read;
use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use serde::Serialize;
use serde_json::Value;

pub const DEFAULT_PORT: u16 = 7272;
pub const DEFAULT_SESSION: &str = "main";
/// App flag (not passed to vrcode-mac): don't run the bridge.
pub const NO_BRIDGE_FLAG: &str = "--no-xmux-bridge";

/// The bridge's `serve` arguments for the vrcode service's arguments, or `None` when it
/// shouldn't run. It doesn't run in relay-only and one-shot modes; the relay tests run a test
/// copy of the app with `--link-only` and a stand-in bridge port.
///
/// It serves the same cmux session (`--cmux-session`, `--cmux-socket`) on the port the
/// relay forwards to (`--bridge-port`).
pub fn serve_args(service_args: &[String]) -> Option<(Vec<String>, u16)> {
    let skip = ["--link-only", "--link-info", "--transcribe", "-h", "--help", NO_BRIDGE_FLAG];
    if service_args.iter().any(|arg| skip.contains(&arg.as_str())) {
        return None;
    }
    let value = |flag: &str| service_args.windows(2).find(|pair| pair[0] == flag).map(|pair| pair[1].clone());
    let port = value("--bridge-port").and_then(|port| port.parse().ok()).unwrap_or(DEFAULT_PORT);
    let mut args = vec!["serve".to_string(), "--port".into(), port.to_string()];
    match value("--cmux-socket") {
        Some(socket) => args.extend(["--socket".into(), socket]),
        None => args.extend(["--session".into(), value("--cmux-session").unwrap_or_else(|| DEFAULT_SESSION.into())]),
    }
    Some((args, port))
}

#[derive(Serialize)]
pub struct Waiting {
    name: String,
    peer: String,
}

#[derive(Serialize)]
pub struct Device {
    id: String,
    name: String,
    /// Unix seconds.
    paired_at: Option<u64>,
    connected: bool,
}

#[derive(Serialize, Default)]
pub struct Pairing {
    /// Headsets waiting for approval.
    pub waiting: Vec<Waiting>,
    devices: Vec<Device>,
    connected: usize,
    session: Option<String>,
    listen: Option<String>,
    /// Why the status couldn't be read (the bridge is down or still starting).
    error: Option<String>,
}

/// The bridge's log points people at its CLI (`xmux pair`); in VRcode Mac that's the window.
pub fn friendly_log(line: &str) -> String {
    line.replace("then run `xmux pair`", "then approve it in VRcode Mac under Headset pairing")
        .replace("run `xmux pair`", "approve it in VRcode Mac under Headset pairing")
}

pub fn pairing(bridge: &Path) -> Pairing {
    let status = match run(bridge, &["status", "--json"]) {
        Ok(out) => serde_json::from_str::<Value>(&out).map_err(|error| error.to_string()),
        Err(error) => Err(error),
    };
    let status = match status {
        Ok(status) => status,
        Err(error) => return Pairing { error: Some(error), ..Pairing::default() },
    };
    let text = |pointer: &str| status.pointer(pointer).and_then(Value::as_str).map(String::from);
    let connected: Vec<&str> =
        status.get("headsets").and_then(Value::as_array).map(|ids| ids.iter().filter_map(Value::as_str).collect()).unwrap_or_default();
    let devices = status
        .get("devices")
        .and_then(Value::as_array)
        .map(|devices| {
            devices
                .iter()
                .filter_map(|d| {
                    let id = d.get("id").and_then(Value::as_str)?.to_string();
                    Some(Device {
                        name: d.get("name").and_then(Value::as_str).unwrap_or("Headset").into(),
                        paired_at: d.get("paired_at").and_then(Value::as_u64),
                        connected: connected.contains(&id.as_str()),
                        id,
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    let waiting = status
        .get("pending")
        .and_then(Value::as_array)
        .map(|pending| {
            pending
                .iter()
                .map(|p| Waiting {
                    name: p.get("name").and_then(Value::as_str).unwrap_or("Headset").into(),
                    peer: p.get("peer").and_then(Value::as_str).unwrap_or("").into(),
                })
                .collect()
        })
        .unwrap_or_default();
    Pairing {
        waiting,
        devices,
        connected: connected.len(),
        session: text("/session"),
        listen: text("/listen"),
        error: None,
    }
}

/// Forgets a paired headset; it has to pair again to connect.
pub fn unpair(bridge: &Path, id: &str) -> Result<String, String> {
    if id.is_empty() || !id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
        return Err("Unknown headset.".into());
    }
    run(bridge, &["unpair", id])
}

/// Approves the waiting headset showing `code`.
pub fn approve(bridge: &Path, code: &str) -> Result<String, String> {
    let code: String = code.chars().filter(|c| !c.is_whitespace()).collect();
    if code.len() != 6 || !code.chars().all(|c| c.is_ascii_digit()) {
        return Err("Enter the six-digit code shown on the headset.".into());
    }
    run(bridge, &["pair", &code])
}

pub fn reject(bridge: &Path) -> Result<String, String> {
    run(bridge, &["pair", "--reject"])
}

/// Runs the bridge CLI with a deadline, returning stdout, or stderr as the error.
fn run(bridge: &Path, args: &[&str]) -> Result<String, String> {
    let mut child = Command::new(bridge)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| error.to_string())?;
    let deadline = Instant::now() + Duration::from_secs(3);
    let status = loop {
        match child.try_wait().map_err(|error| error.to_string())? {
            Some(status) => break status,
            None if Instant::now() >= deadline => {
                let _ = child.kill();
                let _ = child.wait();
                return Err("the xmux bridge didn't answer".into());
            }
            None => std::thread::sleep(Duration::from_millis(20)),
        }
    };
    let mut out = String::new();
    let mut err = String::new();
    if let Some(mut stdout) = child.stdout.take() {
        let _ = stdout.read_to_string(&mut out);
    }
    if let Some(mut stderr) = child.stderr.take() {
        let _ = stderr.read_to_string(&mut err);
    }
    if status.success() {
        Ok(out.trim().to_string())
    } else {
        let message = if err.trim().is_empty() { out } else { err };
        Err(message.trim().trim_start_matches("Error: ").to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(list: &[&str]) -> Vec<String> {
        list.iter().map(|arg| arg.to_string()).collect()
    }

    #[test]
    fn serves_the_default_session_on_the_default_port() {
        let (serve, port) = serve_args(&args(&["--bluetooth"])).unwrap();
        assert_eq!(serve, args(&["serve", "--port", "7272", "--session", "main"]));
        assert_eq!(port, 7272);
    }

    #[test]
    fn follows_the_service_session_socket_and_bridge_port() {
        let (serve, port) = serve_args(&args(&["--cmux-session", "work", "--bridge-port", "17272"])).unwrap();
        assert_eq!(serve, args(&["serve", "--port", "17272", "--session", "work"]));
        assert_eq!(port, 17272);
        let (serve, _) = serve_args(&args(&["--cmux-socket", "/tmp/c.sock", "--cmux-session", "work"])).unwrap();
        assert_eq!(serve, args(&["serve", "--port", "7272", "--socket", "/tmp/c.sock"]));
    }

    #[test]
    fn log_points_at_the_window_instead_of_the_cli() {
        assert_eq!(
            friendly_log("pair a headset: open XMUX on it, then run `xmux pair`"),
            "pair a headset: open XMUX on it, then approve it in VRcode Mac under Headset pairing"
        );
        assert!(!friendly_log("INFO pairing requested; run `xmux pair` peer=1.2.3.4").contains("xmux pair"));
    }

    #[test]
    fn stays_off_in_relay_only_and_one_shot_modes() {
        assert!(serve_args(&args(&["--link-only", "--bridge-port", "5000"])).is_none());
        assert!(serve_args(&args(&["--link-info"])).is_none());
        assert!(serve_args(&args(&["--transcribe", "a.aiff"])).is_none());
        assert!(serve_args(&args(&[NO_BRIDGE_FLAG])).is_none());
    }
}
