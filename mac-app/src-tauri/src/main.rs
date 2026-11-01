//! VRcode Mac: the signed menu-bar app that runs vrcode's Mac side.
//!
//! It bundles and supervises two helpers:
//! - `vrcode-mac`, the Swift service in `mac/` (speech, relay, cmux control). It gets this app's
//!   arguments.
//! - `xmux-bridge`, which serves the cmux session to the headset. Its arguments are derived from
//!   the service's.
//!
//! Because this app launches them, macOS attributes the helpers' privacy access (Screen & System
//! Audio Recording, Accessibility, Bluetooth, Local Network, Keychain) to one stable, signed
//! app. The app restarts a failed helper and shows status, permissions, headset pairing and the
//! log.

mod bridge;
mod keychain;
mod permissions;
mod service;
mod settings;
mod tray;

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use serde::Serialize;
use tauri::{ActivationPolicy, AppHandle, Manager, RunEvent, WindowEvent};

use service::{Helper, Log, OnStop, Service};

/// The LaunchAgent that `bun run mac:install` writes (scripts/run-mac-service.ts).
const LAUNCH_AGENT: &str = "dev.improve.vrcode.mac";

#[derive(Clone)]
struct Helpers {
    all: Vec<Arc<Service>>,
    log: Arc<Log>,
    /// The Quest's settings through the vrcode service; `None` when it isn't run locally.
    settings: Option<Arc<settings::Client>>,
}

impl Helpers {
    fn named(&self, name: &str) -> Option<&Arc<Service>> {
        self.all.iter().find(|service| service.name() == name)
    }
}

#[derive(Serialize)]
struct Status {
    version: &'static str,
    services: Vec<service::Snapshot>,
    logs: Vec<service::LogLine>,
    permissions: permissions::Permissions,
    /// Which AI providers have a key in the Keychain.
    api_keys: std::collections::BTreeMap<&'static str, bool>,
    launch_agent: bool,
    /// `None` when the bridge isn't run by this app.
    pairing: Option<bridge::Pairing>,
}

#[tauri::command]
async fn status(helpers: tauri::State<'_, Helpers>, since: u64) -> Result<Status, String> {
    let helpers = helpers.inner().clone();
    // Reading the bridge's status runs its CLI; keep that off the main thread.
    tauri::async_runtime::spawn_blocking(move || Status {
        version: env!("CARGO_PKG_VERSION"),
        services: helpers.all.iter().map(|service| service.snapshot()).collect(),
        logs: helpers.log.since(since),
        permissions: permissions::check(),
        api_keys: keychain::saved_keys(),
        launch_agent: launch_agent_installed(),
        pairing: helpers.named("xmux-bridge").map(|bridge| bridge::pairing(bridge.executable())),
    })
    .await
    .map_err(|error| error.to_string())
}

/// Restarts one helper by name, or all of them.
#[tauri::command]
fn restart_service(helpers: tauri::State<'_, Helpers>, name: Option<String>) {
    for service in &helpers.all {
        if name.as_deref().map_or(true, |name| name == service.name()) {
            service.restart();
        }
    }
}

#[tauri::command]
fn request_permission(kind: String) -> Result<(), String> {
    permissions::request(&kind)
}

#[tauri::command]
fn open_settings(kind: String) -> Result<(), String> {
    permissions::open_settings(&kind)
}

/// Saves the API key for an AI provider (`anthropic`, `openai`, `gemini`).
#[tauri::command]
fn set_api_key(helpers: tauri::State<'_, Helpers>, provider: String, key: String) -> Result<(), String> {
    let service = helpers.named("vrcode-mac").ok_or("vrcode-mac isn't bundled")?;
    keychain::set_api_key(&provider, &key, service.executable())
}

/// The Quest's settings: the latest SETTINGS body when newer than `since`, link state, errors.
#[tauri::command]
fn settings(helpers: tauri::State<'_, Helpers>, since: u64) -> Option<settings::View> {
    helpers.settings.as_ref().map(|client| client.view(since))
}

#[tauri::command]
fn settings_set(helpers: tauri::State<'_, Helpers>, values: serde_json::Value) -> Result<(), String> {
    helpers.settings.as_ref().ok_or("Quest settings aren't available in this mode.")?.set(values)
}

#[tauri::command]
fn settings_action(helpers: tauri::State<'_, Helpers>, action: String) -> Result<(), String> {
    helpers.settings.as_ref().ok_or("Quest settings aren't available in this mode.")?.action(&action)
}

#[tauri::command]
fn delete_api_key(provider: String) -> Result<(), String> {
    keychain::delete_api_key(&provider)
}

#[tauri::command]
async fn approve_pairing(helpers: tauri::State<'_, Helpers>, code: String) -> Result<String, String> {
    let bridge = helpers.named("xmux-bridge").ok_or("The xmux bridge isn't running in this app.")?;
    let executable = bridge.executable().to_path_buf();
    tauri::async_runtime::spawn_blocking(move || bridge::approve(&executable, &code))
        .await
        .map_err(|error| error.to_string())?
}

#[tauri::command]
async fn reject_pairing(helpers: tauri::State<'_, Helpers>) -> Result<String, String> {
    let bridge = helpers.named("xmux-bridge").ok_or("The xmux bridge isn't running in this app.")?;
    let executable = bridge.executable().to_path_buf();
    tauri::async_runtime::spawn_blocking(move || bridge::reject(&executable))
        .await
        .map_err(|error| error.to_string())?
}

#[tauri::command]
async fn unpair_headset(helpers: tauri::State<'_, Helpers>, id: String) -> Result<String, String> {
    let bridge = helpers.named("xmux-bridge").ok_or("The xmux bridge isn't running in this app.")?;
    let executable = bridge.executable().to_path_buf();
    tauri::async_runtime::spawn_blocking(move || bridge::unpair(&executable, &id))
        .await
        .map_err(|error| error.to_string())?
}

/// While the bridge runs, watches for headsets waiting to pair. The window (hidden at start)
/// comes up when one starts waiting, and the menu bar shows how many are waiting.
fn watch_pairing(app: AppHandle, bridge: Arc<Service>, tray: Arc<tray::Tray>) {
    let _ = std::thread::Builder::new().name("pairing watcher".into()).spawn(move || {
        let mut shown = 0;
        loop {
            std::thread::sleep(Duration::from_secs(2));
            let waiting = if bridge.is_running() { bridge::pairing(bridge.executable()).waiting.len() } else { 0 };
            if waiting != shown {
                tray.set_waiting(waiting);
                if waiting > shown {
                    show_window(&app);
                }
                shown = waiting;
            }
        }
    });
}

fn launch_agent_installed() -> bool {
    std::env::var_os("HOME")
        .map(|home| PathBuf::from(home).join(format!("Library/LaunchAgents/{LAUNCH_AGENT}.plist")).exists())
        .unwrap_or(false)
}

pub fn show_window(app: &AppHandle) {
    let _ = app.show();
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// SIGTERM (launchctl, logout, the relay tests) quits through the normal exit path, which
/// stops the helpers instead of orphaning them.
fn quit_on_signals(app: AppHandle) {
    use signal_hook::consts::{SIGHUP, SIGINT, SIGTERM};
    let mut signals = signal_hook::iterator::Signals::new([SIGTERM, SIGINT, SIGHUP]).expect("signal handlers");
    std::thread::spawn(move || {
        if signals.forever().next().is_some() {
            app.exit(0);
        }
    });
}

fn helpers(args: Vec<String>) -> Helpers {
    let log = Log::new();
    let service_args: Vec<String> = args.iter().filter(|arg| *arg != bridge::NO_BRIDGE_FLAG).cloned().collect();
    let mut all = vec![Service::new(
        Helper {
            name: "vrcode-mac",
            executable: service::sidecar("vrcode-mac"),
            args: service_args,
            on_stop: OnStop::QuitApp,
            port: None,
            rewrite: None,
        },
        log.clone(),
    )];
    if let Some((serve, port)) = bridge::serve_args(&args) {
        all.push(Service::new(
            Helper {
                name: "xmux-bridge",
                executable: service::sidecar("xmux-bridge"),
                args: serve,
                on_stop: OnStop::Restart,
                port: Some(port),
                rewrite: Some(bridge::friendly_log),
            },
            log.clone(),
        ));
    }
    let settings = settings::service_port(&args).map(settings::Client::start);
    Helpers { all, log, settings }
}

fn main() {
    let helpers = helpers(service::passthrough_args(std::env::args().skip(1)));
    let on_exit = helpers.clone();
    let app = tauri::Builder::default()
        .manage(helpers.clone())
        .invoke_handler(tauri::generate_handler![
            status,
            restart_service,
            request_permission,
            open_settings,
            set_api_key,
            delete_api_key,
            settings,
            settings_set,
            settings_action,
            approve_pairing,
            reject_pairing,
            unpair_headset
        ])
        .on_window_event(|window, event| {
            // Closing the window only hides it; the app lives in the menu bar.
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .setup(move |app| {
            app.set_activation_policy(ActivationPolicy::Accessory);
            let names: Vec<&'static str> = helpers.all.iter().map(|service| service.name()).collect();
            let tray = Arc::new(tray::build(app.handle(), helpers.all.clone(), &names)?);
            for service in &helpers.all {
                let tray = tray.clone();
                service.start(app.handle().clone(), move |name, state| tray.set_state(name, state));
            }
            if let Some(bridge) = helpers.named("xmux-bridge") {
                watch_pairing(app.handle().clone(), bridge.clone(), tray.clone());
            }
            quit_on_signals(app.handle().clone());
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("VRcode Mac failed to start");
    app.run(move |app, event| match event {
        // Opening the app again (Finder, Spotlight, `open`) shows the window.
        RunEvent::Reopen { .. } => show_window(app),
        RunEvent::Exit => {
            for service in &on_exit.all {
                service.shutdown();
            }
        }
        _ => {}
    });
}
