//! One program's Rich Presence session: its connection, its activity, and the
//! optional relay to Discord.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::{json, Map, Value};
use tokio::sync::{mpsc, watch};

use super::activity;
use super::frame::{Frame, OP_CLOSE, OP_FRAME, OP_PING, OP_PONG};
use super::upstream::{self, Upstream};
use super::Rpc;

/// How many activity changes one program may make in a window, and how long the
/// window is.
const ACTIVITY_LIMIT: usize = 5;
const ACTIVITY_WINDOW: Duration = Duration::from_secs(20);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Transport {
    Ipc,
    Ws,
}

#[derive(Debug)]
pub enum Outgoing {
    Json(Value),
    Frame(Frame),
    Close { code: u32, message: String },
}

#[derive(Clone)]
#[allow(dead_code)] // The protocol identity is kept for logs and future relays.
pub struct AppConnection {
    pub id: String,
    pub transport: Transport,
    out: mpsc::UnboundedSender<Outgoing>,
    closed: Arc<AtomicBool>,
    closed_tx: watch::Sender<bool>,
}

impl AppConnection {
    pub fn new(
        id: String,
        transport: Transport,
        out: mpsc::UnboundedSender<Outgoing>,
        closed_tx: watch::Sender<bool>,
    ) -> Self {
        Self {
            id,
            transport,
            out,
            closed: Arc::new(AtomicBool::new(false)),
            closed_tx,
        }
    }

    pub fn closed_rx(&self) -> watch::Receiver<bool> {
        self.closed_tx.subscribe()
    }

    pub fn send_json(&self, value: Value) {
        if self.closed.load(Ordering::SeqCst) {
            return;
        }
        let _ = self.out.send(Outgoing::Json(value));
    }

    pub fn send_frame(&self, frame: &Frame) {
        if self.closed.load(Ordering::SeqCst) {
            return;
        }
        let _ = self.out.send(Outgoing::Frame(frame.clone()));
    }

    pub fn close(&self, code: u32, message: &str) {
        if self.closed.swap(true, Ordering::SeqCst) {
            return;
        }
        let _ = self.closed_tx.send(true);
        let _ = self.out.send(Outgoing::Close {
            code,
            message: message.to_string(),
        });
    }
}

/// Events that steer a session from outside its socket (its own reader task, a
/// timer, the service stopping, or the relay).
pub enum Event {
    AppFrame(Frame),
    AppClosed,
    UpstreamFrame(Frame),
    UpstreamClosed,
    Shutdown,
    Flush,
}

/// The handle a connection keeps while the program is connected.
#[derive(Clone)]
#[allow(dead_code)] // The session id belongs to the connection's identity.
pub struct SessionLink {
    pub session_id: String,
    tx: mpsc::UnboundedSender<Event>,
}

impl SessionLink {
    pub fn frame(&self, frame: Frame) {
        let _ = self.tx.send(Event::AppFrame(frame));
    }

    pub fn app_closed(&self) {
        let _ = self.tx.send(Event::AppClosed);
    }
}

/// Makes the entry and starts the session. The entry is in place before the
/// task runs, so its first changes are never lost.
pub fn open(rpc: Rpc, conn: AppConnection, client_id: String) -> SessionLink {
    let (tx, rx) = mpsc::unbounded_channel();
    let id = uuid::Uuid::new_v4().to_string();
    rpc.register_session(&id, &client_id, tx.clone());
    let session = Session {
        rpc: rpc.clone(),
        id: id.clone(),
        client_id,
        conn,
        events: tx.clone(),
        upstream: None,
        update_times: VecDeque::new(),
        pending: None,
        pending_timer: false,
        ended: false,
    };
    let task = tauri::async_runtime::spawn(session.run(rx));
    rpc.attach_session_task(&id, task.inner().abort_handle());
    SessionLink { session_id: id, tx }
}

struct Session {
    rpc: Rpc,
    id: String,
    client_id: String,
    conn: AppConnection,
    events: mpsc::UnboundedSender<Event>,
    upstream: Option<Upstream>,
    update_times: VecDeque<Instant>,
    pending: Option<Option<Value>>,
    pending_timer: bool,
    ended: bool,
}

impl Session {
    async fn run(mut self, mut events: mpsc::UnboundedReceiver<Event>) {
        self.start().await;
        if !self.ended {
            while let Some(event) = events.recv().await {
                match event {
                    Event::AppFrame(frame) => self.handle(frame).await,
                    Event::AppClosed => self.finish(),
                    Event::UpstreamFrame(frame) => {
                        if self.upstream.is_some() {
                            self.conn.send_frame(&frame);
                        }
                    }
                    Event::UpstreamClosed => self.upstream_closed(),
                    Event::Shutdown => {
                        self.finish();
                        break;
                    }
                    Event::Flush => self.flush_pending(),
                }
                if self.ended {
                    break;
                }
            }
        }
        self.finish();
    }

    async fn start(&mut self) {
        if self.rpc.relay_enabled() {
            let endpoints = self.rpc.discord_endpoints().await;
            for target in endpoints {
                match upstream::open(&target, &self.client_id, self.events.clone()).await {
                    upstream::Open::Ready { upstream, ready } => {
                        log::info!(
                            "rpc: {} is being relayed to Discord at {target}",
                            self.client_id
                        );
                        self.upstream = Some(upstream);
                        self.rpc.set_relayed(&self.id, true);
                        self.conn.send_frame(&ready);
                        return;
                    }
                    upstream::Open::Refused { code, message } => {
                        log::info!(
                            "rpc: Discord at {target} refused {}: {code} {message}",
                            self.client_id
                        );
                        self.conn.close(code, &message);
                        self.finish();
                        return;
                    }
                    upstream::Open::Unavailable => {}
                }
            }
        }
        self.conn.send_json(self.local_ready());
    }

    fn local_ready(&self) -> Value {
        let me = self.rpc.identity();
        let field = |key: &str| me.as_ref().and_then(|m| m.get(key)).and_then(Value::as_str);
        let id = field("id").unwrap_or("0");
        let username = field("username").unwrap_or("evilion");
        let global_name = field("global_name").unwrap_or("Nexa");
        json!({
            "cmd": "DISPATCH",
            "evt": "READY",
            "data": {
                "v": 1,
                "config": {
                    "cdn_host": "cdn.discordapp.com",
                    "api_endpoint": "//discord.com/api",
                    "environment": "production"
                },
                "user": {
                    "id": id,
                    "username": username,
                    "global_name": global_name,
                    "discriminator": "0",
                    "avatar": Value::Null,
                    "avatar_decoration_data": Value::Null,
                    "bot": false,
                    "flags": 0,
                    "premium_type": 0
                }
            }
        })
    }

    fn upstream_closed(&mut self) {
        if self.upstream.take().is_some() {
            self.rpc.set_relayed(&self.id, false);
            log::info!("rpc: Discord closed the relay for {}", self.client_id);
        }
    }

    async fn handle(&mut self, frame: Frame) {
        match frame.op {
            OP_CLOSE => {
                if let Some(upstream) = &mut self.upstream {
                    upstream.send(&frame);
                }
                self.finish();
                self.conn.close(1000, "Closed");
            }
            OP_PING => {
                if let Some(upstream) = &mut self.upstream {
                    upstream.send(&frame);
                } else {
                    self.conn.send_frame(&Frame::new(OP_PONG, frame.json));
                }
            }
            OP_PONG => {
                if let Some(upstream) = &mut self.upstream {
                    upstream.send(&frame);
                }
            }
            OP_FRAME => {
                let msg = &frame.json;
                if cmd(msg) == Some("SET_ACTIVITY") {
                    let args = msg.get("args").unwrap_or(&Value::Null);
                    self.read_activity(args);
                }
                if let Some(upstream) = &mut self.upstream {
                    upstream.send(&frame);
                    return;
                }
                self.answer_local(msg);
            }
            _ => {}
        }
    }

    fn read_activity(&mut self, args: &Value) {
        let now = super::now_ms();
        let activity = args
            .get("activity")
            .and_then(|a| activity::normalize_activity(&self.client_id, Some(a), now));

        let instant = Instant::now();
        while let Some(front) = self.update_times.front() {
            if instant.duration_since(*front) < ACTIVITY_WINDOW {
                break;
            }
            self.update_times.pop_front();
        }

        if self.update_times.len() < ACTIVITY_LIMIT && !self.pending_timer {
            self.update_times.push_back(instant);
            self.rpc.set_activity(&self.id, activity);
            return;
        }

        self.pending = Some(activity);
        if self.pending_timer {
            return;
        }
        let wait = self
            .update_times
            .front()
            .map(|first| {
                ACTIVITY_WINDOW
                    .checked_sub(instant.duration_since(*first))
                    .unwrap_or(Duration::ZERO)
            })
            .unwrap_or(Duration::ZERO);
        self.pending_timer = true;
        let events = self.events.clone();
        tauri::async_runtime::spawn(async move {
            tokio::time::sleep(wait).await;
            let _ = events.send(Event::Flush);
        });
    }

    fn flush_pending(&mut self) {
        self.pending_timer = false;
        if let Some(activity) = self.pending.take() {
            self.update_times.push_back(Instant::now());
            self.rpc.set_activity(&self.id, activity);
        }
    }

    fn answer_local(&self, msg: &Value) {
        let command = cmd(msg).unwrap_or("UNKNOWN");
        let nonce = msg.get("nonce").cloned().unwrap_or(Value::Null);
        let (evt, data) = match command {
            "SET_ACTIVITY" => {
                let activity = msg
                    .get("args")
                    .and_then(|args| args.get("activity"))
                    .and_then(|a| {
                        activity::normalize_activity(&self.client_id, Some(a), super::now_ms())
                    });
                let data = match activity {
                    Some(activity) => {
                        let mut map = activity.as_object().cloned().unwrap_or_default();
                        if !map.contains_key("name") {
                            map.insert("name".to_string(), Value::String(String::new()));
                        }
                        map.insert(
                            "created_at".to_string(),
                            Value::Number(super::now_ms().into()),
                        );
                        Value::Object(map)
                    }
                    None => Value::Null,
                };
                (None, data)
            }
            "SUBSCRIBE" | "UNSUBSCRIBE" => (
                None,
                json!({
                    "evt": msg.get("evt").and_then(Value::as_str),
                }),
            ),
            "SEND_ACTIVITY_JOIN_INVITE" | "CLOSE_ACTIVITY_REQUEST" => (None, Value::Null),
            _ => (
                Some("ERROR"),
                json!({
                    "code": 4002,
                    "message": "Nexa handles Rich Presence; this command needs Discord to be running."
                }),
            ),
        };
        let mut answer = Map::new();
        answer.insert("cmd".to_string(), Value::String(command.to_string()));
        answer.insert(
            "evt".to_string(),
            evt.map(|e| Value::String(e.to_string()))
                .unwrap_or(Value::Null),
        );
        answer.insert("nonce".to_string(), nonce);
        answer.insert("data".to_string(), data);
        self.conn.send_json(Value::Object(answer));
    }

    fn finish(&mut self) {
        if self.ended {
            return;
        }
        self.ended = true;
        self.pending = None;
        self.pending_timer = false;
        self.update_times.clear();
        if let Some(mut upstream) = self.upstream.take() {
            upstream.close();
        }
        self.rpc.session_ended(&self.id);
    }
}

fn cmd(msg: &Value) -> Option<&str> {
    msg.get("cmd").and_then(Value::as_str)
}
