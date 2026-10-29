//! Discord Rich Presence, ported from the Electron app.
//!
//! The service listens where programs look for Discord: the local IPC socket
//! (`discord-ipc-N`, a Unix socket or a Windows named pipe) and Discord's local
//! WebSocket port (127.0.0.1:6463-6472). A program's SET_ACTIVITY is read on
//! the way past and handed to the page; when the real Discord client is
//! running, the program's frames are also relayed there so its presence shows
//! in Discord as usual.

use serde::Serialize;

/// The Rich Presence server's public shape, as the page's `RpcState` has it.
#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RpcState {
    pub enabled: bool,
    pub relay: bool,
    pub listening: RpcListening,
    /// connected, absent, first
    pub discord: String,
    pub apps: Vec<RpcApp>,
}

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RpcListening {
    pub ipc: Option<String>,
    pub ws: Option<u16>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RpcApp {
    pub client_id: String,
    pub name: Option<String>,
    pub activity: Option<serde_json::Value>,
    pub hidden: bool,
    pub connected_at: u64,
}

mod activity;
mod discord;
mod frame;
mod ipc_server;
mod local;
mod paths;
mod service;
mod session;
mod upstream;
mod ws_server;

pub use service::Rpc;

/// A waiter that ends when its channel says stop, or when the sender is gone.
pub(crate) async fn stop_requested(rx: &mut tokio::sync::watch::Receiver<bool>) {
    loop {
        if *rx.borrow() {
            return;
        }
        if rx.changed().await.is_err() {
            return;
        }
    }
}

/// Discord's client id rule, shared by the IPC handshake and the WebSocket
/// query: one to twenty decimal digits.
pub(crate) fn valid_client_id(client_id: &str) -> bool {
    !client_id.is_empty() && client_id.len() <= 20 && client_id.bytes().all(|b| b.is_ascii_digit())
}

pub(crate) fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis().min(u64::MAX as u128) as u64)
        .unwrap_or(0)
}
