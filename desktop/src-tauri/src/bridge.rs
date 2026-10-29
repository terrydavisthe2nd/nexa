use serde::Serialize;
use serde_json::Value;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_autostart::ManagerExt as AutostartExt;
use tauri_plugin_clipboard_manager::ClipboardExt;
use tauri_plugin_notification::NotificationExt;

use crate::rpc::RpcState;
use crate::settings::{self, DesktopSettings, SettingsPatch};
use crate::state::AppState;
use crate::strings::NativeStrings;

pub const SCRIPT: &str = include_str!("../bridge.js");

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PublicSettings {
    pub launch_at_login: bool,
    pub start_minimized: bool,
    pub close_to_tray: bool,
    pub hardware_acceleration: bool,
}

impl From<&DesktopSettings> for PublicSettings {
    fn from(value: &DesktopSettings) -> Self {
        Self {
            launch_at_login: value.launch_at_login,
            start_minimized: value.start_minimized,
            close_to_tray: value.close_to_tray,
            hardware_acceleration: value.hardware_acceleration,
        }
    }
}

#[tauri::command]
pub fn get_settings(state: State<'_, AppState>) -> PublicSettings {
    PublicSettings::from(&*state.settings.lock().unwrap())
}

#[tauri::command]
pub fn set_settings(
    app: AppHandle,
    state: State<'_, AppState>,
    next: SettingsPatch,
) -> PublicSettings {
    let saved = {
        let mut current = state.settings.lock().unwrap();
        current.apply(next);
        settings::save(&app, &current);
        PublicSettings::from(&*current)
    };
    apply_autostart(&app);
    saved
}

pub fn apply_autostart(app: &AppHandle) {
    let wanted = app
        .try_state::<AppState>()
        .map(|state| state.settings.lock().unwrap().launch_at_login)
        .unwrap_or(false);
    let manager = app.autolaunch();
    let enabled = manager.is_enabled().unwrap_or(false);
    let result = if wanted && !enabled {
        manager.enable()
    } else if !wanted && enabled {
        manager.disable()
    } else {
        Ok(())
    };
    if let Err(error) = result {
        log::warn!("could not change launch at login: {error}");
    }
}

#[tauri::command]
pub fn set_strings(app: AppHandle, state: State<'_, AppState>, strings: NativeStrings) {
    {
        let mut settings = state.settings.lock().unwrap();
        settings.strings = strings.clone();
        settings::save(&app, &settings);
    }
    *state.strings.lock().unwrap() = strings.clone();
    crate::tray::refresh(&app, &strings);
}

#[tauri::command]
pub fn set_identity(app: AppHandle, state: State<'_, AppState>, identity: Value) {
    state.rpc.set_identity(&app, Some(identity.clone()));
    *state.identity.lock().unwrap() = Some(identity);
}

#[tauri::command]
pub fn get_versions(app: AppHandle) -> Value {
    serde_json::json!({
        "app": app.package_info().version.to_string(),
        "tauri": tauri::VERSION,
        "webview": std::env::var("WEBVIEW2_VERSION").ok(),
    })
}

#[tauri::command]
pub fn open_external(app: AppHandle, url: String) -> Result<(), String> {
    let parsed: tauri::Url = url
        .parse()
        .map_err(|_| "that address cannot be opened".to_string())?;
    if parsed.scheme() != "http" && parsed.scheme() != "https" {
        return Err("only web addresses can be opened".into());
    }
    tauri_plugin_opener::open_url(parsed.as_str(), None::<&str>)
        .map_err(|error| format!("could not open the browser: {error}"))?;
    let _ = app;
    Ok(())
}

#[tauri::command]
pub fn copy(app: AppHandle, text: String) -> bool {
    app.clipboard().write_text(text).is_ok()
}

/// Paste into whatever has focus. The webview has no native paste command, so
/// the clipboard text is inserted the way the browser's own command does.
#[tauri::command]
pub fn paste(app: AppHandle, window: tauri::WebviewWindow) -> bool {
    let text = match app.clipboard().read_text() {
        Ok(text) if !text.is_empty() => text,
        _ => return false,
    };
    let quoted = match serde_json::to_string(&text) {
        Ok(quoted) => quoted,
        Err(_) => return false,
    };
    let script =
        format!("try {{ document.execCommand('insertText', false, {quoted}); }} catch (_) {{}}");
    window.eval(&script).is_ok()
}

#[tauri::command]
pub fn notify_app(app: AppHandle, title: String, body: String) -> bool {
    let notification = app.notification();
    notification
        .builder()
        .title(title)
        .body(body)
        .show()
        .is_ok()
}

#[tauri::command]
pub fn rpc_state(state: State<'_, AppState>) -> RpcState {
    state.rpc.state()
}

#[tauri::command]
pub fn set_rpc_enabled(app: AppHandle, state: State<'_, AppState>, on: bool) {
    {
        let mut settings = state.settings.lock().unwrap();
        settings.rpc_enabled = on;
        settings::save(&app, &settings);
    }
    state.rpc.set_enabled(&app, on);
}

#[tauri::command]
pub fn set_rpc_relay(app: AppHandle, state: State<'_, AppState>, on: bool) {
    {
        let mut settings = state.settings.lock().unwrap();
        settings.rpc_relay = on;
        settings::save(&app, &settings);
    }
    state.rpc.set_relay(&app, on);
}

#[tauri::command]
pub fn set_rpc_hidden(app: AppHandle, state: State<'_, AppState>, client_id: String, hidden: bool) {
    {
        let mut settings = state.settings.lock().unwrap();
        if hidden {
            if !settings.hidden_apps.contains(&client_id) {
                settings.hidden_apps.push(client_id.clone());
            }
        } else {
            settings.hidden_apps.retain(|id| id != &client_id);
        }
        settings::save(&app, &settings);
    }
    state.rpc.set_hidden(&app, &client_id, hidden);
}

#[tauri::command]
pub fn restart_discord(app: AppHandle, state: State<'_, AppState>) -> bool {
    state.rpc.restart_discord(&app)
}

#[tauri::command]
pub fn restart_app(app: AppHandle) {
    app.restart();
}
