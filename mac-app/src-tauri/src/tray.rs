//! The menu-bar item: one status line per helper, Approve Headset, Open, Restart Services,
//! Quit. While headsets wait to pair, their count shows next to the icon.

use std::sync::Arc;

use tauri::image::Image;
use tauri::menu::{IsMenuItem, Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{TrayIcon, TrayIconBuilder};
use tauri::{AppHandle, Wry};

use crate::service::{Service, State};

pub struct Tray {
    icon: TrayIcon<Wry>,
    lines: Vec<(&'static str, MenuItem<Wry>)>,
    approve: MenuItem<Wry>,
}

impl Tray {
    pub fn set_state(&self, name: &str, state: State) {
        if let Some((_, item)) = self.lines.iter().find(|(line, _)| *line == name) {
            let _ = item.set_text(format!("{name}: {}", state.label()));
        }
    }

    pub fn set_waiting(&self, waiting: usize) {
        let _ = self.approve.set_enabled(waiting > 0);
        let _ = self.approve.set_text(match waiting {
            0 => "Approve Headset…".to_string(),
            1 => "Approve Headset (1 waiting)…".to_string(),
            n => format!("Approve Headset ({n} waiting)…"),
        });
        let _ = self.icon.set_title((waiting > 0).then(|| waiting.to_string()));
    }
}

pub fn build(app: &AppHandle, services: Vec<Arc<Service>>, names: &[&'static str]) -> tauri::Result<Tray> {
    let mut lines = Vec::new();
    for name in names {
        let item = MenuItem::with_id(app, format!("status-{name}"), format!("{name}: starting"), false, None::<&str>)?;
        lines.push((*name, item));
    }
    let approve = MenuItem::with_id(app, "approve", "Approve Headset…", false, None::<&str>)?;
    let open = MenuItem::with_id(app, "open", "Open VRcode Mac…", true, None::<&str>)?;
    let restart = MenuItem::with_id(app, "restart", "Restart Services", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit VRcode Mac", true, None::<&str>)?;
    let separator = PredefinedMenuItem::separator(app)?;
    let separator2 = PredefinedMenuItem::separator(app)?;
    let mut items: Vec<&dyn IsMenuItem<Wry>> = lines.iter().map(|(_, item)| item as &dyn IsMenuItem<Wry>).collect();
    if names.contains(&"xmux-bridge") {
        items.push(&approve);
    }
    items.extend([&separator as &dyn IsMenuItem<Wry>, &open, &restart, &separator2, &quit]);
    let menu = Menu::with_items(app, &items)?;
    let icon = TrayIconBuilder::with_id("vrcode")
        .icon(glyph())
        .icon_as_template(true)
        .tooltip("VRcode Mac")
        .menu(&menu)
        .show_menu_on_left_click(true)
        .on_menu_event(move |app, event| match event.id().as_ref() {
            "open" | "approve" => crate::show_window(app),
            "restart" => services.iter().for_each(|service| service.restart()),
            "quit" => app.exit(0),
            _ => {}
        })
        .build(app)?;
    Ok(Tray { icon, lines, approve })
}

/// A headset visor with a nose cut-out, drawn as a template image (only alpha matters; macOS
/// tints it for the menu bar).
fn glyph() -> Image<'static> {
    const SIZE: u32 = 64;
    let mut rgba = Vec::with_capacity((SIZE * SIZE * 4) as usize);
    for y in 0..SIZE {
        for x in 0..SIZE {
            let (px, py) = (x as f32 + 0.5, y as f32 + 0.5);
            let visor = rounded_rect(px, py, 32.0, 32.0, 29.0, 16.0, 10.0);
            let nose = ((px - 32.0).powi(2) + (py - 48.0).powi(2)).sqrt() - 9.0;
            let distance = visor.max(-nose);
            let alpha = (0.5 - distance).clamp(0.0, 1.0);
            rgba.extend_from_slice(&[0, 0, 0, (alpha * 255.0).round() as u8]);
        }
    }
    Image::new_owned(rgba, SIZE, SIZE)
}

/// Signed distance from (px, py) to a rounded rectangle with half extents (hw, hh).
fn rounded_rect(px: f32, py: f32, cx: f32, cy: f32, hw: f32, hh: f32, radius: f32) -> f32 {
    let qx = (px - cx).abs() - (hw - radius);
    let qy = (py - cy).abs() - (hh - radius);
    let outside = (qx.max(0.0).powi(2) + qy.max(0.0).powi(2)).sqrt();
    outside + qx.max(qy).min(0.0) - radius
}
