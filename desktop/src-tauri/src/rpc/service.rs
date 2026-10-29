use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::Value;
use tauri::{AppHandle, Emitter};
use tokio::sync::{mpsc, Mutex as AsyncMutex};

use super::discord::{Claim, Locator};
use super::ipc_server::{self, IpcHandle};
use super::session::Event as LocalEvent;
use super::ws_server::{self, WsHandle};
use super::{RpcApp, RpcListening, RpcState};

struct AppMeta {
    name: Option<String>,
    activity: Option<Value>,
    hidden: bool,
    connected_at: u64,
}

struct SessionMeta {
    client_id: String,
    relayed: bool,
    activity: Option<Value>,
    task: Option<tokio::task::AbortHandle>,
    to_app: mpsc::UnboundedSender<LocalEvent>,
}

struct Running {
    ipc: Option<IpcHandle>,
    ws: Option<WsHandle>,
}

struct Inner {
    app: Mutex<Option<AppHandle>>,
    enabled: AtomicBool,
    relay: AtomicBool,
    identity: Mutex<Option<Value>>,
    apps: Mutex<HashMap<String, AppMeta>>,
    sessions: Mutex<HashMap<String, SessionMeta>>,
    listening: Mutex<RpcListening>,
    discord: Mutex<String>,
    locator: AsyncMutex<Option<Locator>>,
    running: Mutex<Option<Running>>,
    wake: tokio::sync::Notify,
}

#[derive(Clone)]
pub struct Rpc {
    inner: Arc<Inner>,
}

impl Default for Rpc {
    fn default() -> Self {
        Self {
            inner: Arc::new(Inner {
                app: Mutex::new(None),
                enabled: AtomicBool::new(true),
                relay: AtomicBool::new(true),
                identity: Mutex::new(None),
                apps: Mutex::new(HashMap::new()),
                sessions: Mutex::new(HashMap::new()),
                listening: Mutex::new(RpcListening::default()),
                discord: Mutex::new("absent".into()),
                locator: AsyncMutex::new(None),
                running: Mutex::new(None),
                wake: tokio::sync::Notify::new(),
            }),
        }
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|span| span.as_millis() as u64)
        .unwrap_or(0)
}

impl Rpc {
    pub fn start(&self, app: &AppHandle) {
        {
            let mut slot = self.inner.app.lock().unwrap();
            if slot.is_some() {
                return;
            }
            *slot = Some(app.clone());
        }
        let rpc = self.clone();
        tauri::async_runtime::spawn(async move {
            // Let the caller apply the saved enabled/relay flags first.
            tokio::time::sleep(std::time::Duration::from_millis(80)).await;
            rpc.supervise().await;
        });
    }

    pub fn state(&self) -> RpcState {
        let listening = self.inner.listening.lock().unwrap().clone();
        let apps = self
            .inner
            .apps
            .lock()
            .unwrap()
            .iter()
            .map(|(client_id, meta)| RpcApp {
                client_id: client_id.clone(),
                name: meta.name.clone(),
                activity: meta.activity.clone(),
                hidden: meta.hidden,
                connected_at: meta.connected_at,
            })
            .collect();
        RpcState {
            enabled: self.inner.enabled.load(Ordering::Relaxed),
            relay: self.inner.relay.load(Ordering::Relaxed),
            listening,
            discord: self.inner.discord.lock().unwrap().clone(),
            apps,
        }
    }

    pub fn set_enabled(&self, _app: &AppHandle, on: bool) {
        self.inner.enabled.store(on, Ordering::SeqCst);
        self.inner.wake.notify_waiters();
        self.emit_state();
    }

    pub fn set_relay(&self, _app: &AppHandle, on: bool) {
        self.inner.relay.store(on, Ordering::SeqCst);
        self.inner.wake.notify_waiters();
        self.emit_state();
    }

    pub fn set_hidden(&self, _app: &AppHandle, client_id: &str, hidden: bool) {
        if let Some(meta) = self.inner.apps.lock().unwrap().get_mut(client_id) {
            meta.hidden = hidden;
        }
        self.emit_state();
        self.emit_activities();
    }

    pub fn set_identity(&self, _app: &AppHandle, identity: Option<Value>) {
        *self.inner.identity.lock().unwrap() = identity;
    }

    pub fn relay_enabled(&self) -> bool {
        self.inner.relay.load(Ordering::Relaxed)
    }

    pub fn identity(&self) -> Option<Value> {
        self.inner.identity.lock().unwrap().clone()
    }

    pub async fn discord_endpoints(&self) -> Vec<String> {
        let mut guard = self.inner.locator.lock().await;
        let Some(locator) = guard.as_mut() else {
            return Vec::new();
        };
        let ours = self
            .inner
            .listening
            .lock()
            .unwrap()
            .ipc
            .clone()
            .unwrap_or_default();
        locator.endpoints(Some(&ours)).await
    }

    pub fn register_session(
        &self,
        id: &str,
        client_id: &str,
        to_app: mpsc::UnboundedSender<LocalEvent>,
    ) {
        let now = now_ms();
        self.inner.sessions.lock().unwrap().insert(
            id.to_string(),
            SessionMeta {
                client_id: client_id.to_string(),
                relayed: false,
                activity: None,
                task: None,
                to_app,
            },
        );
        {
            let mut apps = self.inner.apps.lock().unwrap();
            let meta = apps
                .entry(client_id.to_string())
                .or_insert_with(|| AppMeta {
                    name: None,
                    activity: None,
                    hidden: false,
                    connected_at: now,
                });
            meta.connected_at = meta.connected_at.min(now);
        }
        self.emit_state();
    }

    pub fn attach_session_task(&self, id: &str, task: tokio::task::AbortHandle) {
        if let Some(session) = self.inner.sessions.lock().unwrap().get_mut(id) {
            session.task = Some(task);
        }
    }

    pub fn session_ended(&self, id: &str) {
        let gone = self.inner.sessions.lock().unwrap().remove(id);
        if let Some(session) = gone {
            let still_open = self
                .inner
                .sessions
                .lock()
                .unwrap()
                .values()
                .any(|other| other.client_id == session.client_id);
            if !still_open {
                self.inner.apps.lock().unwrap().remove(&session.client_id);
            }
        }
        self.emit_state();
        self.emit_activities();
    }

    pub fn set_activity(&self, id: &str, activity: Option<Value>) {
        let client_id = {
            let mut sessions = self.inner.sessions.lock().unwrap();
            let Some(session) = sessions.get_mut(id) else {
                return;
            };
            session.activity = activity.clone();
            session.client_id.clone()
        };
        if let Some(meta) = self.inner.apps.lock().unwrap().get_mut(&client_id) {
            meta.activity = activity;
        }
        self.emit_state();
        self.emit_activities();
    }

    pub fn set_relayed(&self, id: &str, relayed: bool) {
        if let Some(session) = self.inner.sessions.lock().unwrap().get_mut(id) {
            session.relayed = relayed;
        }
        self.emit_state();
    }

    pub fn restart_discord(&self, _app: &AppHandle) -> bool {
        #[cfg(target_os = "windows")]
        {
            let builds = super::discord::running_discord_builds();
            if builds.is_empty() {
                self.bounce();
                return true;
            }
            self.bounce();
            let rpc = self.clone();
            tauri::async_runtime::spawn(async move {
                discord::quit_builds(&builds).await;
                discord::wait_for_pipe_gone().await;
                rpc.bounce();
                tokio::time::sleep(std::time::Duration::from_secs(1)).await;
                discord::launch_builds(&builds);
            });
            return true;
        }
        #[cfg(not(target_os = "windows"))]
        {
            self.bounce();
            true
        }
    }

    fn bounce(&self) {
        self.stop_listeners();
        self.inner.wake.notify_waiters();
    }

    fn emit_state(&self) {
        let app = self.inner.app.lock().unwrap().clone();
        if let Some(app) = app {
            let _ = app.emit("desktop://rpc-state", self.state());
        }
    }

    fn emit_activities(&self) {
        let Some(app) = self.inner.app.lock().unwrap().clone() else {
            return;
        };
        let list: Vec<Value> = self
            .inner
            .apps
            .lock()
            .unwrap()
            .iter()
            .filter(|(_, meta)| !meta.hidden)
            .filter_map(|(client_id, meta)| {
                meta.activity.clone().map(|mut activity| {
                    if let Some(object) = activity.as_object_mut() {
                        object
                            .entry("application_id")
                            .or_insert_with(|| Value::String(client_id.clone()));
                        if let Some(name) = &meta.name {
                            object
                                .entry("name")
                                .or_insert_with(|| Value::String(name.clone()));
                        }
                    }
                    activity
                })
            })
            .collect();
        let _ = app.emit("desktop://activity", list);
    }

    async fn supervise(&self) {
        loop {
            if !self.inner.enabled.load(Ordering::SeqCst) {
                self.inner.listening.lock().unwrap().ipc = None;
                self.inner.listening.lock().unwrap().ws = None;
                self.inner.wake.notified().await;
                continue;
            }
            let mut locator = Locator::new();
            let claim = locator.claim_first().await;
            let word = match claim {
                Claim::Ready => "connected",
                Claim::First => "first",
            };
            *self.inner.discord.lock().unwrap() = word.into();
            *self.inner.locator.lock().await = Some(locator);

            let ipc = ipc_server::start(self.clone()).await;
            let ws = ws_server::start(self.clone()).await;
            {
                let mut listening = self.inner.listening.lock().unwrap();
                listening.ipc = ipc.as_ref().map(|handle| handle.path.clone());
                listening.ws = ws.as_ref().map(|handle| handle.port);
                if listening.ipc.is_none() && listening.ws.is_none() {
                    *self.inner.discord.lock().unwrap() = "absent".into();
                }
            }
            *self.inner.running.lock().unwrap() = Some(Running { ipc, ws });
            self.emit_state();

            loop {
                if !self.inner.enabled.load(Ordering::SeqCst) {
                    break;
                }
                tokio::select! {
                    _ = tokio::time::sleep(std::time::Duration::from_secs(1)) => {}
                    _ = self.inner.wake.notified() => {}
                }
            }

            self.stop_listeners();
            *self.inner.discord.lock().unwrap() = "absent".into();
            if let Some(mut locator) = self.inner.locator.lock().await.take() {
                locator.restore();
            }
            self.emit_state();
            if self.inner.enabled.load(Ordering::SeqCst) {
                // A bounce: go round and claim the endpoint again.
                continue;
            }
        }
    }

    fn stop_listeners(&self) {
        if let Some(running) = self.inner.running.lock().unwrap().take() {
            if let Some(ipc) = running.ipc {
                let _ = ipc.stop.send(true);
                ipc.task.abort();
            }
            if let Some(ws) = running.ws {
                let _ = ws.stop.send(true);
                ws.task.abort();
            }
        }
        let sessions: Vec<SessionMeta> = {
            let mut sessions = self.inner.sessions.lock().unwrap();
            sessions.drain().map(|(_, meta)| meta).collect()
        };
        for session in sessions {
            if let Some(task) = session.task {
                task.abort();
            }
            let _ = session.to_app.send(LocalEvent::Shutdown);
        }
        self.inner.apps.lock().unwrap().clear();
        {
            let mut listening = self.inner.listening.lock().unwrap();
            listening.ipc = None;
            listening.ws = None;
        }
    }
}
