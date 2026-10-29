use std::str::FromStr;
use std::sync::Mutex;

use tauri::plugin::TauriPlugin;
use tauri::{AppHandle, Emitter, Runtime};
use tauri_plugin_global_shortcut::{GlobalShortcutExt, Shortcut, ShortcutState};

use crate::types::{ShortcutResult, ShortcutSpec};

static REGISTERED: Mutex<Vec<(Shortcut, String)>> = Mutex::new(Vec::new());

pub fn plugin<R: Runtime>() -> TauriPlugin<R> {
    tauri_plugin_global_shortcut::Builder::new()
        .with_handler(|app, shortcut, event| {
            if event.state != ShortcutState::Pressed {
                return;
            }
            let action = REGISTERED
                .lock()
                .unwrap()
                .iter()
                .find(|(registered, _)| registered == shortcut)
                .map(|(_, action)| action.clone());
            if let Some(action) = action {
                let _ = app.emit("desktop://shortcut", action);
            }
        })
        .build()
}

/// Replace every registered shortcut with this list. The page sends the same
/// accelerators it always did (Electron's spelling, which this parser reads).
pub fn apply<R: Runtime>(app: &AppHandle<R>, specs: &[ShortcutSpec]) -> Vec<ShortcutResult> {
    let manager = app.global_shortcut();
    if let Err(error) = manager.unregister_all() {
        log::warn!("could not clear old shortcuts: {error}");
    }
    REGISTERED.lock().unwrap().clear();

    let mut results = Vec::with_capacity(specs.len());
    for spec in specs {
        let parsed = Shortcut::from_str(&spec.accelerator);
        let ok = match parsed {
            Ok(shortcut) => match manager.register(shortcut) {
                Ok(()) => {
                    REGISTERED
                        .lock()
                        .unwrap()
                        .push((shortcut, spec.action.clone()));
                    true
                }
                Err(error) => {
                    log::warn!("shortcut {} is unavailable: {error}", spec.accelerator);
                    false
                }
            },
            Err(error) => {
                log::warn!("shortcut {} cannot be read: {error}", spec.accelerator);
                false
            }
        };
        results.push(ShortcutResult {
            action: spec.action.clone(),
            ok,
        });
    }
    results
}

#[tauri::command]
pub fn set_shortcuts(app: AppHandle, shortcuts: Vec<ShortcutSpec>) -> Vec<ShortcutResult> {
    apply(&app, &shortcuts)
}
