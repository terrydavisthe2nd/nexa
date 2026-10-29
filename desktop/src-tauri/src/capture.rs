use crate::types::ScreenAccess;

#[cfg(target_os = "macos")]
#[link(name = "CoreGraphics", kind = "framework")]
extern "C" {
    fn CGPreflightScreenCaptureAccess() -> bool;
    fn CGRequestScreenCaptureAccess() -> bool;
}

pub fn access() -> ScreenAccess {
    #[cfg(target_os = "macos")]
    {
        // A window's picture needs Screen Recording. Preflight only reports;
        // the first system picker asks macOS for it, and the settings page is
        // offered when it was denied.
        let granted = unsafe { CGPreflightScreenCaptureAccess() };
        ScreenAccess {
            status: if granted { "granted" } else { "denied" }.into(),
            usable: granted,
            stale: false,
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        // Windows shows its own picker, and the portal on Linux asks if it
        // must. Neither has an app-level permission to check.
        ScreenAccess {
            status: "granted".into(),
            usable: true,
            stale: false,
        }
    }
}

pub fn open_settings() {
    #[cfg(target_os = "macos")]
    {
        let _ = std::process::Command::new("open")
            .arg("x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture")
            .spawn();
    }
    #[cfg(target_os = "windows")]
    {
        let _ = std::process::Command::new("cmd")
            .args(["/C", "start", "ms-settings:privacy-graphicscapture"])
            .spawn();
    }
    #[cfg(target_os = "linux")]
    {
        let _ = std::process::Command::new("xdg-open")
            .arg("settings://privacy")
            .spawn();
    }
}

pub fn reset_access() {
    #[cfg(target_os = "macos")]
    {
        let _ = std::process::Command::new("tccutil")
            .args(["reset", "ScreenCapture", crate::settings::IDENTIFIER])
            .output();
    }
}

#[tauri::command]
pub fn screen_access() -> ScreenAccess {
    access()
}

#[tauri::command]
pub fn open_screen_settings() {
    open_settings();
}

#[tauri::command]
pub fn reset_screen_access() {
    reset_access();
}

/// The system picker is what captures a screen now. The page's own picker
/// asks for sources through this event, which is never sent, so the browser's
/// picker takes over instead.
#[tauri::command]
pub fn choose_display_source(_request_id: String, _source_id: String, _with_audio: bool) {}
