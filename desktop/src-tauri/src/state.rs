use std::sync::atomic::AtomicBool;
use std::sync::Mutex;

use crate::originals::Originals;
use crate::rpc::Rpc;
use crate::settings::DesktopSettings;
use crate::strings::NativeStrings;
use crate::types::{UpdateState, WindowState};

pub struct AppState {
    pub settings: Mutex<DesktopSettings>,
    pub strings: Mutex<NativeStrings>,
    pub identity: Mutex<Option<serde_json::Value>>,
    pub window: Mutex<WindowState>,
    pub update: Mutex<UpdateState>,
    /// Installer bytes downloaded and waiting for the page's Restart.
    pub pending_update: Mutex<Option<Vec<u8>>>,
    pub call_active: AtomicBool,
    pub rpc: Rpc,
    pub originals: Originals,
}

impl Default for AppState {
    fn default() -> Self {
        let settings = DesktopSettings::default();
        Self {
            strings: Mutex::new(settings.strings.clone()),
            settings: Mutex::new(settings),
            identity: Mutex::new(None),
            window: Mutex::new(WindowState::default()),
            update: Mutex::new(UpdateState::default()),
            pending_update: Mutex::new(None),
            call_active: AtomicBool::new(false),
            rpc: Rpc::default(),
            originals: Originals::default(),
        }
    }
}
