//! The macOS privacy permissions the service needs. The bundled helper is launched by this app,
//! so macOS attributes its access to this app: checking here reflects what the helper gets.

use std::process::Command;

use core_foundation::base::TCFType;
use core_foundation::boolean::CFBoolean;
use core_foundation::dictionary::{CFDictionary, CFDictionaryRef};
use core_foundation::string::CFString;
use serde::Serialize;

#[link(name = "ApplicationServices", kind = "framework")]
extern "C" {
    fn AXIsProcessTrusted() -> u8;
    fn AXIsProcessTrustedWithOptions(options: CFDictionaryRef) -> u8;
}

#[link(name = "CoreGraphics", kind = "framework")]
extern "C" {
    fn CGPreflightScreenCaptureAccess() -> bool;
    fn CGRequestScreenCaptureAccess() -> bool;
}

#[derive(Serialize)]
pub struct Permissions {
    accessibility: bool,
    screen_recording: bool,
}

pub fn check() -> Permissions {
    unsafe {
        Permissions {
            accessibility: AXIsProcessTrusted() != 0,
            screen_recording: CGPreflightScreenCaptureAccess(),
        }
    }
}

/// Asks macOS for the permission (which adds the app to the list in System Settings), then
/// opens that list so it can be switched on.
pub fn request(kind: &str) -> Result<(), String> {
    match kind {
        "accessibility" => unsafe {
            let prompt = CFString::from_static_string("AXTrustedCheckOptionPrompt");
            let options = CFDictionary::from_CFType_pairs(&[(prompt.as_CFType(), CFBoolean::true_value().as_CFType())]);
            AXIsProcessTrustedWithOptions(options.as_concrete_TypeRef());
        },
        "screen-recording" => unsafe {
            CGRequestScreenCaptureAccess();
        },
        _ => {}
    }
    open_settings(kind)
}

pub fn open_settings(kind: &str) -> Result<(), String> {
    let anchor = match kind {
        "accessibility" => "Privacy_Accessibility",
        "screen-recording" => "Privacy_ScreenCapture",
        "bluetooth" => "Privacy_Bluetooth",
        "local-network" => "Privacy_LocalNetwork",
        other => return Err(format!("unknown permission {other}")),
    };
    Command::new("/usr/bin/open")
        .arg(format!("x-apple.systempreferences:com.apple.preference.security?{anchor}"))
        .status()
        .map_err(|error| error.to_string())
        .and_then(|status| if status.success() { Ok(()) } else { Err(format!("open exited with {status}")) })
}
