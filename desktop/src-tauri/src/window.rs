use std::sync::Mutex;

use std::path::{Path, PathBuf};

use tauri::webview::DownloadEvent;
use tauri::{
    AppHandle, Emitter, Manager, UserAttentionType, WebviewUrl, WebviewWindow,
    WebviewWindowBuilder, WindowEvent,
};

use crate::settings::DesktopSettings;
use crate::state::AppState;
use crate::types::WindowState;

const APP_NAME: &str = "Nexa";

fn default_url() -> String {
    std::env::var("NEXA_DESKTOP_URL").unwrap_or_else(|_| "https://nexa.fish".into())
}

fn allowed(url: &tauri::Url) -> bool {
    let host = url.host_str().unwrap_or("");
    let scheme = url.scheme();
    let web = scheme == "http" || scheme == "https";
    if !web {
        return false;
    }
    // A development or test build points at its own server; only that exact
    // origin is allowed, never every service listening on loopback.
    if let Ok(dev) = std::env::var("NEXA_DESKTOP_URL") {
        if let Ok(dev) = tauri::Url::parse(&dev) {
            if url.origin() == dev.origin() {
                return true;
            }
        }
    }
    host == "nexa.fish"
        || host == "www.nexa.fish"
        || host == "mathtest.lol"
        || host == "www.mathtest.lol"
        || host == "chat.evilion.xyz"
}

pub fn create(app: &AppHandle, settings: &DesktopSettings) -> tauri::Result<WebviewWindow> {
    let url: tauri::Url = default_url()
        .parse()
        .unwrap_or_else(|_| "https://nexa.fish".parse().expect("a literal URL"));
    #[allow(unused_mut)]
    let mut builder = WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url))
        .title(APP_NAME)
        .inner_size(1280.0, 800.0)
        .min_inner_size(360.0, 400.0)
        .visible(!settings.start_minimized)
        .initialization_script(crate::bridge::SCRIPT)
        .on_permission_request(|_webview, permission| {
            use tauri::webview::{PermissionKind, PermissionResponse};
            match permission {
                // The window only ever shows Nexa's own origins (on_navigation
                // below refuses anything else), and the operating system still
                // asks its own camera, microphone and screen questions.
                PermissionKind::Microphone
                | PermissionKind::Camera
                | PermissionKind::DisplayCapture
                | PermissionKind::Notifications
                | PermissionKind::ClipboardRead => PermissionResponse::Allow,
                _ => PermissionResponse::Default,
            }
        })
        .on_download(|_webview, event| {
            if let DownloadEvent::Requested { url, destination } = event {
                if let Some(dir) = dirs::download_dir() {
                    let _ = std::fs::create_dir_all(&dir);
                    *destination = unique_path(&dir, &download_name(&url));
                }
            }
            true
        })
        .on_navigation(|url| {
            if allowed(url) {
                return true;
            }
            if url.scheme() == "http" || url.scheme() == "https" {
                let _ = tauri_plugin_opener::open_url(url.as_str(), None::<&str>);
            }
            false
        });

    #[cfg(target_os = "windows")]
    {
        // Chromium throttles background timers, renderer work and occluded
        // windows by default. A call keeps running while the window is hidden,
        // so those three are off; the page's own idle work (audio rests, no
        // video pulled off screen) still stops when nothing is happening.
        // WebView2 takes one argument string, so every flag is composed here:
        // a second call would replace these rather than add to them.
        let mut args = String::from(
            "--autoplay-policy=no-user-gesture-required \
             --disable-background-timer-throttling \
             --disable-backgrounding-occluded-windows \
             --disable-renderer-backgrounding \
             --disable-features=msWebOOUI,msPdfOOUI",
        );
        if !settings.hardware_acceleration {
            args.push_str(" --disable-gpu");
        }
        builder = builder.additional_browser_args(args);
    }

    // WKWebView suspends a hidden or covered page unless told not to (macOS 14
    // and newer; older releases ignore the policy). The web page also holds a
    // Web Lock during a call for engines without this switch.
    #[cfg(target_os = "macos")]
    {
        builder = builder
            .background_throttling(tauri::utils::config::BackgroundThrottlingPolicy::Disabled);
    }

    let window = builder.build()?;
    #[cfg(target_os = "macos")]
    enable_macos_screen_capture(&window);
    if settings.start_minimized {
        let _ = window.hide();
    } else {
        let _ = window.show();
        let _ = window.set_focus();
    }
    emit_state(app);
    Ok(window)
}

pub fn emit_state(app: &AppHandle) {
    let Some(window) = app.get_webview_window("main") else {
        return;
    };
    let parked = *PARKED.lock().unwrap();
    let next = WindowState {
        focused: parked == Parked::No && window.is_focused().unwrap_or(false),
        visible: parked == Parked::No && window.is_visible().unwrap_or(false),
        minimized: parked == Parked::Minimized || window.is_minimized().unwrap_or(false),
    };
    if let Some(state) = app.try_state::<AppState>() {
        *state.window.lock().unwrap() = next.clone();
    }
    let _ = app.emit("desktop://window-state", next);
}

pub fn on_event(window: &tauri::Window, event: &WindowEvent) {
    match event {
        WindowEvent::CloseRequested { api, .. } => {
            let close_to_tray = window
                .app_handle()
                .try_state::<AppState>()
                .map(|state| state.settings.lock().unwrap().close_to_tray)
                .unwrap_or(true);
            if close_to_tray {
                api.prevent_close();
                let in_call = window
                    .app_handle()
                    .try_state::<AppState>()
                    .map(|state| state.call_active.load(std::sync::atomic::Ordering::Relaxed))
                    .unwrap_or(false);
                if in_call && should_park() {
                    // Linux WebKit has no background policy switch, so keep the
                    // page mapped while the call runs.
                    park_for_call(window.app_handle(), true);
                } else {
                    let _ = window.hide();
                    emit_state(window.app_handle());
                }
            } else {
                window.app_handle().exit(0);
            }
        }
        WindowEvent::Focused(_) | WindowEvent::Resized(_) | WindowEvent::Moved(_) => {
            let in_call = window
                .app_handle()
                .try_state::<AppState>()
                .map(|state| state.call_active.load(std::sync::atomic::Ordering::Relaxed))
                .unwrap_or(false);
            if in_call && should_park() && window.is_minimized().unwrap_or(false) {
                park_for_call(window.app_handle(), false);
            }
            emit_state(window.app_handle());
        }
        _ => {}
    }
}

pub fn show_main(app: &AppHandle) {
    unpark_after_call(app, false);
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
        emit_state(app);
    }
}

pub fn apply_badge(app: &AppHandle, count: i64) {
    let Some(window) = app.get_webview_window("main") else {
        return;
    };
    let _ = window.set_badge_count(Some(count));
}

pub fn request_attention(app: &AppHandle, on: bool) {
    if let Some(window) = app.get_webview_window("main") {
        let attention = if on {
            Some(UserAttentionType::Informational)
        } else {
            None
        };
        let _ = window.request_user_attention(attention);
    }
}

#[tauri::command]
pub fn get_window_state(state: tauri::State<'_, AppState>) -> WindowState {
    state.window.lock().unwrap().clone()
}

#[tauri::command]
pub fn set_badge(app: AppHandle, count: u32) {
    apply_badge(&app, count.min(9999) as i64);
}

#[tauri::command]
pub fn request_attention_command(app: AppHandle, on: bool) {
    request_attention(&app, on);
}

#[tauri::command]
pub async fn set_call_active(app: AppHandle, on: bool) {
    let was = app
        .try_state::<AppState>()
        .map(|state| {
            state
                .call_active
                .swap(on, std::sync::atomic::Ordering::Relaxed)
        })
        .unwrap_or(false);
    if was == on {
        return;
    }
    if on {
        #[cfg(target_os = "macos")]
        app_nap::begin();
        if should_park() {
            park_for_call(&app, false);
        }
    } else {
        #[cfg(target_os = "macos")]
        app_nap::end();
        unpark_after_call(&app, true);
    }
}

#[tauri::command]
pub async fn focus_window(app: AppHandle) {
    show_main(&app);
}

/// WKWebView keeps its ScreenCaptureKit picker behind a private preference
/// (`_setScreenCaptureEnabled:`). WebKit ships the same DisplayCaptureSessionManager
/// Safari uses, so setting it gives macOS a real screen/window picker instead of
/// refusing `getDisplayMedia`. The app is distributed outside the App Store.
#[cfg(target_os = "macos")]
fn enable_macos_screen_capture(window: &WebviewWindow) {
    use objc::runtime::{Object, Sel, BOOL, NO, YES};
    use objc::{msg_send, sel, sel_impl};

    let _ = window.with_webview(|webview| unsafe {
        let view = webview.inner() as *mut Object;
        if view.is_null() {
            return;
        }
        let configuration: *mut Object = msg_send![view, configuration];
        let prefs: *mut Object = msg_send![configuration, preferences];
        if prefs.is_null() {
            return;
        }
        let selector = Sel::register("_setScreenCaptureEnabled:");
        let responds: BOOL = msg_send![prefs, respondsToSelector: selector];
        if responds != NO {
            let _: () = msg_send![prefs, _setScreenCaptureEnabled: YES];
            log::info!("ScreenCaptureKit picker enabled for WKWebView");
        } else {
            log::warn!("this WKWebView has no screen capture preference");
        }
    });
}

#[derive(Clone, Copy, PartialEq)]
enum Parked {
    No,
    Hidden,
    Minimized,
}

static PARKED: Mutex<Parked> = Mutex::new(Parked::No);
static SAVED_POSITION: Mutex<Option<(i32, i32)>> = Mutex::new(None);

/// Linux WebKitGTK has no background scheduling policy, so a window that is
/// mapped but off screen is the only way to keep its page running while a call
/// is up. Wayland does not let an application move its own window, so there a
/// hidden window is left alone and the page holds a Web Lock instead.
#[cfg(target_os = "linux")]
fn should_park() -> bool {
    std::env::var_os("WAYLAND_DISPLAY").is_none()
}

/// Windows keeps the page running through WebView2's browser arguments and
/// macOS through WKWebView's inactive scheduling policy, so neither needs an
/// off-screen window.
#[cfg(not(target_os = "linux"))]
fn should_park() -> bool {
    false
}

/// Put the window somewhere the compositor still considers visible, without a
/// taskbar button. Chromium and WebKit throttle a hidden or iconified page, and
/// a call's timers have to keep running while the app sits in the tray.
fn park_for_call(app: &AppHandle, closing: bool) {
    let Some(window) = app.get_webview_window("main") else {
        return;
    };
    if *PARKED.lock().unwrap() != Parked::No {
        return;
    }
    let hidden = !window.is_visible().unwrap_or(false);
    let minimized = window.is_minimized().unwrap_or(false);
    if !hidden && !minimized && !closing {
        return;
    }
    if let Ok(position) = window.outer_position() {
        *SAVED_POSITION.lock().unwrap() = Some((position.x, position.y));
    }
    *PARKED.lock().unwrap() = if minimized && !closing {
        Parked::Minimized
    } else {
        Parked::Hidden
    };
    let _ = window.set_skip_taskbar(true);
    let _ = window.unminimize();
    let _ = window.set_position(tauri::PhysicalPosition::new(-32000, -32000));
    let _ = window.show();
    emit_state(app);
    log::info!("call mode parked the window off screen");
}

fn unpark_after_call(app: &AppHandle, restore_hidden: bool) {
    let parked = {
        let mut guard = PARKED.lock().unwrap();
        std::mem::replace(&mut *guard, Parked::No)
    };
    if parked == Parked::No {
        return;
    }
    let Some(window) = app.get_webview_window("main") else {
        return;
    };
    let position = SAVED_POSITION.lock().unwrap().take();
    if let Some((x, y)) = position {
        let _ = window.set_position(tauri::PhysicalPosition::new(x, y));
    }
    let _ = window.set_skip_taskbar(false);
    if restore_hidden {
        match parked {
            Parked::Hidden => {
                let _ = window.hide();
            }
            Parked::Minimized => {
                let _ = window.minimize();
            }
            Parked::No => {}
        }
    }
    emit_state(app);
    log::info!("call mode put the window back");
}

#[cfg(target_os = "macos")]
mod app_nap {
    use std::sync::Mutex;

    use objc::runtime::Object;
    use objc::{class, msg_send, sel, sel_impl};

    /// NSActivityUserInitiatedAllowingIdleSystemSleep: no App Nap, but the
    /// display may still sleep.
    const OPTIONS: u64 = 0x00EF_FFFF;
    static ACTIVITY: Mutex<Option<usize>> = Mutex::new(None);

    pub fn begin() {
        let mut guard = ACTIVITY.lock().unwrap();
        if guard.is_some() {
            return;
        }
        unsafe {
            let process: *mut Object = msg_send![class!(NSProcessInfo), processInfo];
            let reason: *mut Object = msg_send![
                class!(NSString),
                stringWithUTF8String: b"call in progress\0".as_ptr()
            ];
            let activity: *mut Object =
                msg_send![process, beginActivityWithOptions: OPTIONS reason: reason];
            if !activity.is_null() {
                let _: *mut Object = msg_send![activity, retain];
                *guard = Some(activity as usize);
            }
        }
    }

    pub fn end() {
        let activity = ACTIVITY.lock().unwrap().take();
        if let Some(activity) = activity {
            unsafe {
                let process: *mut Object = msg_send![class!(NSProcessInfo), processInfo];
                let token = activity as *mut Object;
                let _: () = msg_send![process, endActivity: token];
                let _: () = msg_send![token, release];
            }
        }
    }
}

fn download_name(url: &tauri::Url) -> String {
    let raw = url
        .path_segments()
        .and_then(|segments| segments.last())
        .filter(|name| !name.is_empty())
        .unwrap_or("download");
    urlencoding::decode(raw)
        .map(|name| name.into_owned())
        .unwrap_or_else(|_| raw.to_string())
}

fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let candidate = dir.join(name);
    if !candidate.exists() {
        return candidate;
    }
    let path = Path::new(name);
    let stem = path
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or("download");
    let extension = path.extension().and_then(|value| value.to_str());
    for number in 2..10_000 {
        let file = match extension {
            Some(extension) => format!("{stem} ({number}).{extension}"),
            None => format!("{stem} ({number})"),
        };
        let candidate = dir.join(file);
        if !candidate.exists() {
            return candidate;
        }
    }
    dir.join(name)
}
