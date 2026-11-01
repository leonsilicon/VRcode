//! The Quest's settings, mirrored in the window through the vrcode service (`vrcode-mac`).
//!
//! The app is one more client on the service port (127.0.0.1:`--port`, default 7273). It
//! speaks the vrcode protocol (docs/vrcode-protocol.md):
//! 1. It sends HELLO; the service answers WELCOME and then SETTINGS.
//! 2. SETTINGS carries the schema, the values and any changes queued while the Quest is away,
//!    and comes again on every change.
//! 3. The app sends SETTINGS_SET (partial values) and SETTINGS_ACTION. Failures come back as
//!    ERROR.
//!
//! It reconnects on its own when the service restarts.

use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

use serde::Serialize;
use serde_json::{json, Value};

const HEADER_LEN: usize = 16;
const VERSION: u16 = 1;
const MAX_BODY: usize = 16 << 20;
const DEFAULT_PORT: u16 = 7273;
const MAX_BACKOFF: Duration = Duration::from_secs(5);

pub const HELLO: u8 = 1;
pub const WELCOME: u8 = 2;
pub const ERROR: u8 = 8;
pub const SETTINGS: u8 = 13;
pub const SETTINGS_SET: u8 = 14;
pub const SETTINGS_ACTION: u8 = 15;

/// The service port for the vrcode service's arguments, or `None` in relay-only and one-shot
/// modes, where nothing of ours listens there (the relay tests point `--port` at a stand-in).
pub fn service_port(service_args: &[String]) -> Option<u16> {
    let skip = ["--link-only", "--link-info", "--transcribe", "-h", "--help"];
    if service_args.iter().any(|arg| skip.contains(&arg.as_str())) {
        return None;
    }
    let port = service_args.windows(2).find(|pair| pair[0] == "--port").and_then(|pair| pair[1].parse().ok());
    Some(port.unwrap_or(DEFAULT_PORT))
}

/// A frame in xmux's 16-byte little-endian header layout: body length, kind, flags (0),
/// version (1), id (0 here), per-direction sequence.
pub fn encode(kind: u8, sequence: u32, body: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(HEADER_LEN + body.len());
    frame.extend_from_slice(&(body.len() as u32).to_le_bytes());
    frame.push(kind);
    frame.push(0);
    frame.extend_from_slice(&VERSION.to_le_bytes());
    frame.extend_from_slice(&0u32.to_le_bytes());
    frame.extend_from_slice(&sequence.to_le_bytes());
    frame.extend_from_slice(body);
    frame
}

pub struct Frame {
    pub kind: u8,
    pub body: Vec<u8>,
}

pub fn read_frame(reader: &mut impl Read) -> io::Result<Frame> {
    let mut header = [0u8; HEADER_LEN];
    reader.read_exact(&mut header)?;
    let len = u32::from_le_bytes([header[0], header[1], header[2], header[3]]) as usize;
    let version = u16::from_le_bytes([header[6], header[7]]);
    if version != VERSION {
        return Err(io::Error::new(io::ErrorKind::InvalidData, format!("protocol version {version}")));
    }
    if len > MAX_BODY {
        return Err(io::Error::new(io::ErrorKind::InvalidData, format!("{len}-byte frame")));
    }
    let mut body = vec![0; len];
    reader.read_exact(&mut body)?;
    Ok(Frame { kind: header[4], body })
}

#[derive(Default)]
struct State {
    /// Connected to the service (WELCOME received).
    linked: bool,
    settings: Option<Value>,
    /// Bumped on every SETTINGS, so the window only fetches new payloads.
    revision: u64,
    error: Option<(u64, String)>,
}

/// What the window polls.
#[derive(Serialize)]
pub struct View {
    linked: bool,
    revision: u64,
    /// The latest SETTINGS body, only when newer than the caller's revision.
    settings: Option<Value>,
    error_id: u64,
    error: Option<String>,
}

pub struct Client {
    address: SocketAddr,
    state: Mutex<State>,
    writer: Mutex<Option<(TcpStream, u32)>>,
}

impl Client {
    pub fn start(port: u16) -> Arc<Self> {
        let client = Arc::new(Self {
            address: SocketAddr::from(([127, 0, 0, 1], port)),
            state: Mutex::new(State::default()),
            writer: Mutex::new(None),
        });
        let runner = client.clone();
        let _ = thread::Builder::new().name("settings client".into()).spawn(move || runner.run());
        client
    }

    pub fn view(&self, since: u64) -> View {
        let state = self.state();
        let (error_id, error) = match &state.error {
            Some((id, message)) => (*id, Some(message.clone())),
            None => (0, None),
        };
        View {
            linked: state.linked,
            revision: state.revision,
            settings: (state.revision > since).then(|| state.settings.clone()).flatten(),
            error_id,
            error,
        }
    }

    /// Changes settings (a partial `{key: value}` map). The service forwards them to the Quest,
    /// or queues them until it reconnects, and echoes a fresh SETTINGS either way.
    pub fn set(&self, values: Value) -> Result<(), String> {
        if !values.is_object() {
            return Err("settings values must be an object".into());
        }
        self.send(SETTINGS_SET, &json!({ "values": values }))
    }

    /// Runs a Quest action (only while the Quest is connected; otherwise the service answers ERROR).
    pub fn action(&self, action: &str) -> Result<(), String> {
        self.send(SETTINGS_ACTION, &json!({ "action": action }))
    }

    fn send(&self, kind: u8, body: &Value) -> Result<(), String> {
        let mut writer = self.writer.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let Some((stream, sequence)) = writer.as_mut() else {
            return Err("The vrcode service isn't reachable right now.".into());
        };
        let frame = encode(kind, *sequence, body.to_string().as_bytes());
        *sequence = sequence.wrapping_add(1);
        stream.write_all(&frame).map_err(|error| format!("Couldn't reach the vrcode service: {error}"))
    }

    fn run(self: Arc<Self>) {
        let mut backoff = Duration::from_millis(500);
        loop {
            if let Ok(stream) = TcpStream::connect_timeout(&self.address, Duration::from_secs(2)) {
                let started = Instant::now();
                let _ = stream.set_nodelay(true);
                if let Ok(writer) = stream.try_clone() {
                    *self.writer.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = Some((writer, 0));
                    if self.send(HELLO, &json!({ "client": "vrcode-mac-app", "protocol": 1 })).is_ok() {
                        self.read(stream);
                    }
                }
                *self.writer.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
                self.state().linked = false;
                if started.elapsed() > Duration::from_secs(10) {
                    backoff = Duration::from_millis(500);
                }
            }
            thread::sleep(backoff);
            backoff = (backoff * 2).min(MAX_BACKOFF);
        }
    }

    fn read(&self, mut stream: TcpStream) {
        while let Ok(frame) = read_frame(&mut stream) {
            match frame.kind {
                WELCOME => self.state().linked = true,
                SETTINGS => {
                    if let Ok(settings) = serde_json::from_slice::<Value>(&frame.body) {
                        let mut state = self.state();
                        state.linked = true;
                        state.settings = Some(settings);
                        state.revision += 1;
                    }
                }
                ERROR => {
                    let message = serde_json::from_slice::<Value>(&frame.body)
                        .ok()
                        .and_then(|body| body.get("message").and_then(Value::as_str).map(String::from))
                        .unwrap_or_else(|| "The vrcode service reported an error.".into());
                    let mut state = self.state();
                    let id = state.error.as_ref().map_or(1, |(id, _)| id + 1);
                    state.error = Some((id, message));
                }
                _ => {} // Speech, AI and cmux frames are for the Quest.
            }
        }
    }

    fn state(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    fn args(list: &[&str]) -> Vec<String> {
        list.iter().map(|arg| arg.to_string()).collect()
    }

    #[test]
    fn finds_the_service_port() {
        assert_eq!(service_port(&args(&["--bluetooth"])), Some(7273));
        assert_eq!(service_port(&args(&["--port", "17273"])), Some(17273));
        assert_eq!(service_port(&args(&["--link-only", "--port", "5000"])), None);
    }

    #[test]
    fn frames_round_trip_and_reject_other_versions() {
        let frame = encode(SETTINGS_SET, 7, br#"{"values":{}}"#);
        assert_eq!(frame.len(), HEADER_LEN + 13);
        assert_eq!(&frame[12..16], &7u32.to_le_bytes());
        let read = read_frame(&mut frame.as_slice()).unwrap();
        assert_eq!((read.kind, read.body.as_slice()), (SETTINGS_SET, &br#"{"values":{}}"#[..]));
        let mut other = frame.clone();
        other[6] = 2;
        assert!(read_frame(&mut other.as_slice()).is_err());
    }

    #[test]
    fn mirrors_settings_and_sends_changes_and_actions() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let hello = read_frame(&mut stream).unwrap();
            assert_eq!(hello.kind, HELLO);
            let hello: Value = serde_json::from_slice(&hello.body).unwrap();
            assert_eq!(hello["client"], "vrcode-mac-app");
            stream.write_all(&encode(WELCOME, 0, b"{}")).unwrap();
            let settings = json!({"connected": true, "schema": {"version": 1, "pages": []}, "values": {"size": 28}, "pending": {}});
            stream.write_all(&encode(SETTINGS, 1, settings.to_string().as_bytes())).unwrap();
            let set = read_frame(&mut stream).unwrap();
            let action = read_frame(&mut stream).unwrap();
            stream.write_all(&encode(ERROR, 2, br#"{"message":"The Quest is offline"}"#)).unwrap();
            (set, action)
        });

        let client = Client::start(port);
        let deadline = Instant::now() + Duration::from_secs(5);
        while client.view(0).settings.is_none() && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(20));
        }
        let view = client.view(0);
        assert!(view.linked);
        assert_eq!(view.settings.unwrap()["values"]["size"], 28);
        assert!(client.view(view.revision).settings.is_none(), "unchanged settings aren't resent");

        client.set(json!({"size": 32})).unwrap();
        client.action("arrange_windows").unwrap();
        let (set, action) = server.join().unwrap();
        assert_eq!(set.kind, SETTINGS_SET);
        assert_eq!(serde_json::from_slice::<Value>(&set.body).unwrap(), json!({"values": {"size": 32}}));
        assert_eq!(action.kind, SETTINGS_ACTION);
        assert_eq!(serde_json::from_slice::<Value>(&action.body).unwrap(), json!({"action": "arrange_windows"}));

        let deadline = Instant::now() + Duration::from_secs(5);
        while client.view(0).error.is_none() && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(20));
        }
        assert_eq!(client.view(0).error.as_deref(), Some("The Quest is offline"));
        assert!(client.set(json!(5)).is_err(), "values must be an object");
    }
}
