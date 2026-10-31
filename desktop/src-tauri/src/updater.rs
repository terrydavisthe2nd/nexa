//! Signed updates from the project's GitHub releases, through Tauri's updater
//! plugin. The plugin verifies the Ed25519 (minisign) signature of every
//! download before it is installed; there is no unsigned fallback.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_updater::UpdaterExt;

use crate::state::AppState;
use crate::types::UpdateState;

static CHECKING: AtomicBool = AtomicBool::new(false);

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|span| span.as_millis() as u64)
        .unwrap_or(0)
}

fn publish(app: &AppHandle, next: UpdateState) {
    if let Some(state) = app.try_state::<AppState>() {
        *state.update.lock().unwrap() = next.clone();
    }
    let _ = app.emit("desktop://update", next);
}

fn patch(app: &AppHandle, change: impl FnOnce(&mut UpdateState)) {
    let next = {
        let Some(state) = app.try_state::<AppState>() else {
            return;
        };
        let mut guard = state.update.lock().unwrap();
        change(&mut guard);
        guard.clone()
    };
    let _ = app.emit("desktop://update", next);
}

fn builder(app: &AppHandle) -> Result<tauri_plugin_updater::Updater, String> {
    let key = crate::update_trust::public_key(option_env!("NEXA_UPDATER_PUBLIC_KEY"))?;
    let endpoint = crate::update_trust::ENDPOINT
        .parse::<tauri::Url>()
        .map_err(|error| format!("the update address is not usable: {error}"))?;
    app.updater_builder()
        .pubkey(key)
        .endpoints(vec![endpoint])
        .map_err(|error| format!("the update address is not usable: {error}"))?
        .build()
        .map_err(|error| format!("the updater could not start: {error}"))
}

fn manual_install() -> bool {
    if std::env::var("NEXA_UPDATE_MANUAL").is_ok() {
        return true;
    }
    cfg!(target_os = "linux") && std::env::var_os("APPIMAGE").is_none()
}

pub async fn check(app: &AppHandle, manual: bool) -> UpdateState {
    if CHECKING.swap(true, Ordering::SeqCst) {
        return app
            .try_state::<AppState>()
            .map(|state| state.update.lock().unwrap().clone())
            .unwrap_or_default();
    }
    let result = check_inner(app, manual).await;
    CHECKING.store(false, Ordering::SeqCst);
    result
}

async fn check_inner(app: &AppHandle, manual: bool) -> UpdateState {
    patch(app, |state| {
        state.status = "checking".into();
        state.error = None;
        state.progress = 0.0;
    });

    let updater = match builder(app) {
        Ok(updater) => updater,
        Err(error) => {
            let next = UpdateState {
                status: "error".into(),
                error: Some(error),
                last_check: Some(now_ms()),
                ..UpdateState::default()
            };
            publish(app, next.clone());
            return next;
        }
    };

    match updater.check().await {
        Ok(Some(update)) => {
            let version = update.version.clone();
            let url = update.download_url.to_string();
            if manual_install() {
                let next = UpdateState {
                    status: "manual".into(),
                    version: Some(version),
                    progress: 0.0,
                    download_url: Some(url),
                    error: None,
                    last_check: Some(now_ms()),
                };
                publish(app, next.clone());
                return next;
            }
            patch(app, |state| {
                state.status = "ready".into();
                state.version = Some(version);
                state.download_url = Some(url);
                state.progress = 0.0;
                state.last_check = Some(now_ms());
            });
            // A found update downloads by itself, as the Electron app did.
            let handle = app.clone();
            tauri::async_runtime::spawn(async move {
                download(&handle).await;
            });
            app.try_state::<AppState>()
                .map(|state| state.update.lock().unwrap().clone())
                .unwrap_or_default()
        }
        Ok(None) => {
            let next = UpdateState {
                status: "idle".into(),
                last_check: Some(now_ms()),
                ..UpdateState::default()
            };
            publish(app, next.clone());
            let _ = manual;
            next
        }
        Err(error) => {
            let next = UpdateState {
                status: "error".into(),
                error: Some(format!("{error}")),
                last_check: Some(now_ms()),
                ..UpdateState::default()
            };
            publish(app, next.clone());
            next
        }
    }
}

pub async fn download(app: &AppHandle) -> UpdateState {
    let updater = match builder(app) {
        Ok(updater) => updater,
        Err(error) => {
            let next = UpdateState {
                status: "error".into(),
                error: Some(error),
                last_check: Some(now_ms()),
                ..UpdateState::default()
            };
            publish(app, next.clone());
            return next;
        }
    };
    let update = match updater.check().await {
        Ok(Some(update)) => update,
        Ok(None) => {
            let next = UpdateState {
                status: "idle".into(),
                last_check: Some(now_ms()),
                ..UpdateState::default()
            };
            publish(app, next.clone());
            return next;
        }
        Err(error) => {
            let next = UpdateState {
                status: "error".into(),
                error: Some(format!("{error}")),
                last_check: Some(now_ms()),
                ..UpdateState::default()
            };
            publish(app, next.clone());
            return next;
        }
    };

    patch(app, |state| {
        state.status = "downloading".into();
        state.version = Some(update.version.clone());
        state.progress = 0.0;
        state.error = None;
        state.last_check = Some(now_ms());
    });

    let progress_app = app.clone();
    let mut last_emit = std::time::Instant::now();
    let downloaded = update
        .download(
            move |chunk, total| {
                if let Some(total) = total {
                    if total == 0 {
                        return;
                    }
                    let guard = progress_app
                        .try_state::<AppState>()
                        .map(|state| state.update.lock().unwrap().progress)
                        .unwrap_or(0.0);
                    // The plugin reports this chunk, not the running total, so
                    // keep a running sum in the stored progress instead.
                    let fraction = guard + (chunk as f32 / total as f32);
                    if last_emit.elapsed() > Duration::from_millis(120) || fraction >= 1.0 {
                        last_emit = std::time::Instant::now();
                        if let Some(state) = progress_app.try_state::<AppState>() {
                            let mut guard = state.update.lock().unwrap();
                            guard.progress = fraction.min(0.99);
                            let snapshot = guard.clone();
                            drop(guard);
                            let _ = progress_app.emit("desktop://update", snapshot);
                        }
                    } else if let Some(state) = progress_app.try_state::<AppState>() {
                        state.update.lock().unwrap().progress = fraction.min(0.99);
                    }
                }
            },
            || {},
        )
        .await;

    match downloaded {
        Ok(bytes) => {
            let next = {
                let Some(state) = app.try_state::<AppState>() else {
                    return UpdateState::default();
                };
                let mut guard = state.update.lock().unwrap();
                *state.pending_update.lock().unwrap() = Some(bytes);
                guard.status = "ready".into();
                guard.progress = 1.0;
                guard.error = None;
                guard.version = Some(update.version.clone());
                guard.download_url = Some(update.download_url.to_string());
                guard.last_check = Some(now_ms());
                guard.clone()
            };
            publish(app, next.clone());
            next
        }
        Err(error) => {
            let next = UpdateState {
                status: "error".into(),
                error: Some(format!("{error}")),
                last_check: Some(now_ms()),
                ..UpdateState::default()
            };
            publish(app, next.clone());
            next
        }
    }
}

pub fn install(app: &AppHandle, downloaded_version: Option<&str>) -> Result<(), String> {
    let bytes = app
        .try_state::<AppState>()
        .and_then(|state| state.pending_update.lock().unwrap().take())
        .ok_or_else(|| "there is no downloaded update".to_string())?;
    let updater = builder(app)?;
    let update = tauri::async_runtime::block_on(updater.check())
        .map_err(|error| format!("the update could not be checked again: {error}"))?
        .ok_or_else(|| "that update is no longer offered".to_string())?;
    if let Some(expected) = downloaded_version {
        if update.version != expected {
            // A newer release has gone out since this one was downloaded.
            // Installing the old bytes against the new manifest would be a
            // downgrade with no way back, so the app offers this one instead.
            publish(
                app,
                UpdateState {
                    status: "available".into(),
                    version: Some(update.version.clone()),
                    ..Default::default()
                },
            );
            tauri::async_runtime::spawn({
                let app = app.clone();
                async move {
                    let _ = check(&app, true).await;
                }
            });
            return Err(format!(
                "the update changed while it was downloading (now {})",
                update.version
            ));
        }
    }
    update
        .install(&bytes)
        .map_err(|error| format!("the update could not be installed: {error}"))?;
    app.restart();
}

pub fn start(app: &AppHandle) {
    // Qualification/local builds must not poll any feed without an approved key.
    if crate::update_trust::public_key(option_env!("NEXA_UPDATER_PUBLIC_KEY")).is_err() {
        return;
    }
    let handle = app.clone();
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_secs(10)).await;
        loop {
            let state = check(&handle, false).await;
            let wait = if state.status == "error" { 300 } else { 60 };
            tokio::time::sleep(Duration::from_secs(wait)).await;
        }
    });
}

#[tauri::command]
pub async fn check_for_updates(app: AppHandle) -> UpdateState {
    check(&app, true).await
}

#[tauri::command]
pub async fn download_update(app: AppHandle) -> UpdateState {
    download(&app).await
}

#[tauri::command]
pub fn install_update(app: AppHandle) -> Result<(), String> {
    let state = app
        .try_state::<AppState>()
        .map(|state| state.update.lock().unwrap().clone())
        .unwrap_or_default();
    install(&app, state.version.as_deref())
}

#[tauri::command]
pub fn get_update_state(state: tauri::State<'_, AppState>) -> UpdateState {
    state.update.lock().unwrap().clone()
}
