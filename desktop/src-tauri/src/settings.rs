use std::fs;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

use crate::strings::NativeStrings;

pub const IDENTIFIER: &str = "xyz.evilion.chat";

/// The four settings the page owns, exactly as its `DesktopSettings` type has them.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct DesktopSettings {
    pub launch_at_login: bool,
    pub start_minimized: bool,
    pub close_to_tray: bool,
    pub hardware_acceleration: bool,
    // Kept here so a settings save from the page cannot forget them.
    #[serde(default = "yes")]
    pub rpc_enabled: bool,
    #[serde(default = "yes")]
    pub rpc_relay: bool,
    #[serde(default)]
    pub hidden_apps: Vec<String>,
    #[serde(default)]
    pub strings: NativeStrings,
    #[serde(default)]
    pub last_update_seen: Option<String>,
}

fn yes() -> bool {
    true
}

impl Default for DesktopSettings {
    fn default() -> Self {
        Self {
            launch_at_login: true,
            start_minimized: false,
            close_to_tray: true,
            hardware_acceleration: true,
            rpc_enabled: true,
            rpc_relay: true,
            hidden_apps: Vec::new(),
            strings: NativeStrings::default(),
            last_update_seen: None,
        }
    }
}

/// The fields the page may change.
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsPatch {
    pub launch_at_login: Option<bool>,
    pub start_minimized: Option<bool>,
    pub close_to_tray: Option<bool>,
    pub hardware_acceleration: Option<bool>,
}

impl DesktopSettings {
    pub fn apply(&mut self, patch: SettingsPatch) {
        let hardware_acceleration = patch
            .hardware_acceleration
            .unwrap_or(self.hardware_acceleration);
        if self.hardware_acceleration != hardware_acceleration {
            // Only takes effect the next time the webview starts.
            log::info!(
                "hardware acceleration will be {} after a restart",
                if hardware_acceleration { "on" } else { "off" }
            );
        }
        self.launch_at_login = patch.launch_at_login.unwrap_or(self.launch_at_login);
        self.start_minimized = patch.start_minimized.unwrap_or(self.start_minimized);
        self.close_to_tray = patch.close_to_tray.unwrap_or(self.close_to_tray);
        self.hardware_acceleration = hardware_acceleration;
    }
}

pub fn config_dir() -> PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(IDENTIFIER)
}

pub fn path() -> PathBuf {
    config_dir().join("settings.json")
}

/// Read settings before the Tauri runtime exists (hardware acceleration and
/// start-minimized have to be known before the first window is made).
pub fn load_early() -> DesktopSettings {
    let file = path();
    match fs::read_to_string(&file) {
        Ok(text) => serde_json::from_str(&text).unwrap_or_else(|error| {
            log::warn!("settings at {} are unreadable: {error}", file.display());
            DesktopSettings::default()
        }),
        Err(_) => DesktopSettings::default(),
    }
}

pub fn load(app: &AppHandle) -> DesktopSettings {
    let file = app
        .path()
        .app_config_dir()
        .map(|dir| dir.join("settings.json"))
        .unwrap_or_else(|_| path());
    match fs::read_to_string(&file) {
        Ok(text) => serde_json::from_str(&text).unwrap_or_else(|error| {
            log::warn!("settings at {} are unreadable: {error}", file.display());
            DesktopSettings::default()
        }),
        Err(_) => DesktopSettings::default(),
    }
}

pub fn save(app: &AppHandle, settings: &DesktopSettings) {
    let file = app
        .path()
        .app_config_dir()
        .map(|dir| dir.join("settings.json"))
        .unwrap_or_else(|_| path());
    if let Some(parent) = file.parent() {
        if let Err(error) = fs::create_dir_all(parent) {
            log::error!("could not make {}: {error}", parent.display());
            return;
        }
    }
    let text = match serde_json::to_string_pretty(settings) {
        Ok(text) => text,
        Err(error) => {
            log::error!("could not encode settings: {error}");
            return;
        }
    };
    let temporary = file.with_extension("json.tmp");
    if let Err(error) = fs::write(&temporary, text) {
        log::error!("could not write {}: {error}", temporary.display());
        return;
    }
    if let Err(error) = fs::rename(&temporary, &file) {
        log::error!("could not replace {}: {error}", file.display());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_the_app() {
        let settings = DesktopSettings::default();
        assert!(settings.launch_at_login);
        assert!(!settings.start_minimized);
        assert!(settings.close_to_tray);
        assert!(settings.hardware_acceleration);
    }

    #[test]
    fn a_page_save_keeps_the_rpc_settings() {
        let mut settings = DesktopSettings::default();
        settings.hidden_apps.push("123".into());
        settings.rpc_relay = false;
        settings.apply(SettingsPatch {
            launch_at_login: Some(false),
            start_minimized: Some(true),
            close_to_tray: Some(false),
            hardware_acceleration: Some(false),
        });
        assert!(!settings.launch_at_login);
        assert!(settings.start_minimized);
        assert!(!settings.close_to_tray);
        assert!(!settings.hardware_acceleration);
        assert!(!settings.rpc_relay);
        assert_eq!(settings.hidden_apps, vec!["123".to_string()]);
    }

    #[test]
    fn settings_from_before_this_release_still_load() {
        let settings: DesktopSettings = serde_json::from_str("{\"launchAtLogin\":false}").unwrap();
        assert!(!settings.launch_at_login);
        assert!(settings.close_to_tray);
        assert!(settings.rpc_enabled);
    }
}
