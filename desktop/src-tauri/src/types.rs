use serde::{Deserialize, Serialize};

/// What the page knows about the window, used for notification policy.
#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WindowState {
    pub focused: bool,
    pub visible: bool,
    pub minimized: bool,
}

/// The page's view of the updater.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateState {
    /// idle, checking, downloading, ready, manual, error
    pub status: String,
    pub version: Option<String>,
    /// 0 to 1, as the page's UpdateState has it.
    pub progress: f32,
    pub download_url: Option<String>,
    pub error: Option<String>,
    /// Unix milliseconds.
    pub last_check: Option<u64>,
}

impl Default for UpdateState {
    fn default() -> Self {
        Self {
            status: "idle".into(),
            version: None,
            progress: 0.0,
            download_url: None,
            error: None,
            last_check: None,
        }
    }
}

/// The page's `screenAccess` answer: macOS reports its TCC decision here.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScreenAccess {
    /// granted, denied, not-determined, restricted, unsupported
    pub status: String,
    pub usable: bool,
    pub stale: bool,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShortcutSpec {
    pub action: String,
    pub accelerator: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShortcutResult {
    pub action: String,
    pub ok: bool,
}
