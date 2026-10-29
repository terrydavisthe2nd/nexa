use serde::{Deserialize, Serialize};

pub const OPEN: &str = "Open";
pub const MUTE: &str = "Mute";
pub const DEAFEN: &str = "Deafen";
pub const CHECK_UPDATES: &str = "Check for Updates";
pub const QUIT: &str = "Quit";

/// The words the native tray draws. The page sends its current language here,
/// so the tray follows the app's language like the rest of the window.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct NativeStrings {
    pub open: String,
    pub mute: String,
    pub deafen: String,
    pub check_for_updates: String,
    pub quit: String,
    pub view: String,
    pub reload: String,
    pub add_to_dictionary: String,
    pub copy_link: String,
    pub open_link_in_browser: String,
    pub offline_title: String,
    pub offline_body: String,
    pub offline_retry: String,
    pub about: String,
    pub move_title: Option<String>,
    pub move_body: Option<String>,
    pub move_now: Option<String>,
    pub move_later: Option<String>,
}

impl Default for NativeStrings {
    fn default() -> Self {
        Self {
            open: OPEN.into(),
            mute: MUTE.into(),
            deafen: DEAFEN.into(),
            check_for_updates: CHECK_UPDATES.into(),
            quit: QUIT.into(),
            view: "View".into(),
            reload: "Reload".into(),
            add_to_dictionary: "Add to Dictionary".into(),
            copy_link: "Copy Link".into(),
            open_link_in_browser: "Open Link in Browser".into(),
            offline_title: "No Connection".into(),
            offline_body: "Nexa could not reach the server. Check your connection and try again."
                .into(),
            offline_retry: "Retry".into(),
            about: "About Nexa".into(),
            move_title: None,
            move_body: None,
            move_now: None,
            move_later: None,
        }
    }
}
