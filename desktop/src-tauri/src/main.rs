#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod bridge;
mod capture;
mod downloads;
mod originals;
mod rpc;
mod settings;
mod shortcuts;
mod state;
mod strings;
mod tray;
mod types;
mod update_trust;
mod updater;
mod window;

use tauri::Manager;

fn main() {
    let early = settings::load_early();
    if !early.hardware_acceleration {
        #[cfg(target_os = "linux")]
        std::env::set_var("WEBKIT_DISABLE_COMPOSITING_MODE", "1");
    }

    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            window::show_main(app);
        }))
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(shortcuts::plugin())
        .manage(state::AppState::default())
        .manage(capture::CaptureState::default())
        .setup(|app| {
            let loaded = settings::load(app.handle());
            {
                let state = app.state::<state::AppState>();
                *state.settings.lock().unwrap() = loaded.clone();
                *state.strings.lock().unwrap() = loaded.strings.clone();
            }
            window::create(app.handle(), &loaded)?;
            tray::create(app.handle(), &loaded.strings)?;
            bridge::apply_autostart(app.handle());
            {
                let state = app.state::<state::AppState>();
                state.rpc.start(app.handle());
                state.rpc.set_enabled(app.handle(), loaded.rpc_enabled);
                state.rpc.set_relay(app.handle(), loaded.rpc_relay);
                for client_id in &loaded.hidden_apps {
                    state.rpc.set_hidden(app.handle(), client_id, true);
                }
            }
            updater::start(app.handle());
            Ok(())
        })
        .on_window_event(window::on_event)
        .invoke_handler(tauri::generate_handler![
            bridge::get_settings,
            bridge::set_settings,
            bridge::set_strings,
            bridge::set_identity,
            bridge::get_versions,
            bridge::open_external,
            bridge::copy,
            bridge::paste,
            bridge::notify_app,
            bridge::rpc_state,
            bridge::set_rpc_enabled,
            bridge::set_rpc_relay,
            bridge::set_rpc_hidden,
            bridge::restart_discord,
            bridge::restart_app,
            window::get_window_state,
            window::set_badge,
            window::request_attention_command,
            window::set_call_active,
            window::focus_window,
            shortcuts::set_shortcuts,
            updater::check_for_updates,
            updater::download_update,
            updater::install_update,
            updater::get_update_state,
            capture::screen_access,
            capture::open_screen_settings,
            capture::reset_screen_access,
            capture::session::capture_sources,
            capture::session::capture_start,
            capture::session::capture_stop,
            originals::open_original,
        ])
        .run(tauri::generate_context!())
        .expect("Nexa could not start");
}
