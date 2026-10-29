use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter};

use crate::strings::NativeStrings;

const TRAY_ID: &str = "nexa";

fn menu(app: &AppHandle, strings: &NativeStrings) -> tauri::Result<Menu<tauri::Wry>> {
    let open = MenuItem::with_id(app, "open", &strings.open, true, None::<&str>)?;
    let mute = MenuItem::with_id(app, "mute", &strings.mute, true, None::<&str>)?;
    let deafen = MenuItem::with_id(app, "deafen", &strings.deafen, true, None::<&str>)?;
    let updates = MenuItem::with_id(
        app,
        "updates",
        &strings.check_for_updates,
        true,
        None::<&str>,
    )?;
    let quit = MenuItem::with_id(app, "quit", &strings.quit, true, None::<&str>)?;
    let one = PredefinedMenuItem::separator(app)?;
    let two = PredefinedMenuItem::separator(app)?;
    let three = PredefinedMenuItem::separator(app)?;
    Menu::with_items(
        app,
        &[&open, &one, &mute, &deafen, &two, &updates, &three, &quit],
    )
}

pub fn create(app: &AppHandle, strings: &NativeStrings) -> tauri::Result<()> {
    let items = menu(app, strings)?;
    let mut builder = TrayIconBuilder::with_id(TRAY_ID)
        .tooltip("Nexa")
        .menu(&items)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "open" => crate::window::show_main(app),
            "mute" => {
                let _ = app.emit("desktop://tray-action", "mute");
            }
            "deafen" => {
                let _ = app.emit("desktop://tray-action", "deafen");
            }
            "updates" => {
                let handle = app.clone();
                tauri::async_runtime::spawn(async move {
                    crate::updater::check(&handle, true).await;
                });
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                crate::window::show_main(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        builder = builder.icon(icon.clone());
    }
    builder.build(app)?;
    Ok(())
}

pub fn refresh(app: &AppHandle, strings: &NativeStrings) {
    let Some(tray) = app.tray_by_id(TRAY_ID) else {
        return;
    };
    match menu(app, strings) {
        Ok(items) => {
            let _ = tray.set_menu(Some(items));
        }
        Err(error) => log::error!("could not rebuild the tray menu: {error}"),
    }
}
