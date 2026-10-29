//! Discord's local WebSocket, which some libraries use instead of the IPC
//! socket. It speaks the same JSON frames.

use futures_util::stream::SplitSink;
use futures_util::{SinkExt, StreamExt};
use serde_json::Value;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, watch};
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_tungstenite::tungstenite::protocol::{CloseFrame, Message, WebSocketConfig};
use tokio_tungstenite::{accept_hdr_async_with_config, WebSocketStream};

use super::frame::{MAX_PAYLOAD, OP_CLOSE, OP_FRAME};
use super::session::{self, AppConnection, Outgoing, Transport};
use super::{valid_client_id, Rpc};

const FIRST_PORT: u16 = 6463;
const LAST_PORT: u16 = 6472;

pub struct WsHandle {
    pub port: u16,
    pub stop: watch::Sender<bool>,
    pub task: tauri::async_runtime::JoinHandle<()>,
}

pub async fn start(rpc: Rpc) -> Option<WsHandle> {
    for port in FIRST_PORT..=LAST_PORT {
        let listener = match TcpListener::bind(("127.0.0.1", port)).await {
            Ok(listener) => listener,
            Err(_) => continue,
        };
        log::info!("rpc: WebSocket listening at 127.0.0.1:{port}");
        let (stop, mut stop_rx) = watch::channel(false);
        let accept_rpc = rpc.clone();
        let task = tauri::async_runtime::spawn(async move {
            loop {
                tokio::select! {
                    _ = super::stop_requested(&mut stop_rx) => break,
                    accepted = listener.accept() => match accepted {
                        Ok((stream, _)) => {
                            let rpc = accept_rpc.clone();
                            let stop = stop_rx.clone();
                            tauri::async_runtime::spawn(handle_connection(rpc, stream, stop));
                        }
                        Err(err) => {
                            log::warn!("rpc: WebSocket accept failed: {err}");
                            break;
                        }
                    }
                }
            }
        });
        return Some(WsHandle { port, stop, task });
    }
    log::warn!("rpc: every Discord WebSocket port is taken");
    None
}

#[derive(Default)]
struct Meta {
    origin: Option<String>,
    query: String,
}

async fn handle_connection(rpc: Rpc, stream: TcpStream, mut stop: watch::Receiver<bool>) {
    let mut meta = Meta::default();
    let mut config = WebSocketConfig::default();
    config.max_message_size = Some(MAX_PAYLOAD);
    config.max_frame_size = Some(MAX_PAYLOAD);
    let mut ws = match accept_hdr_async_with_config(
        stream,
        |request: &tokio_tungstenite::tungstenite::handshake::server::Request,
         response: tokio_tungstenite::tungstenite::handshake::server::Response| {
            meta.origin = request
                .headers()
                .get("origin")
                .map(|v| v.to_str().unwrap_or("invalid").to_string());
            meta.query = request.uri().query().unwrap_or("").to_string();
            Ok(response)
        },
        Some(config),
    )
    .await
    {
        Ok(ws) => ws,
        Err(err) => {
            log::debug!("rpc: a WebSocket handshake failed: {err}");
            return;
        }
    };

    if meta
        .origin
        .as_deref()
        .is_some_and(|origin| !origin.is_empty())
    {
        close_ws(&mut ws, 4001, "Invalid Origin").await;
        return;
    }
    let params = query_params(&meta.query);
    let version = params
        .iter()
        .find(|(name, _)| name == "v")
        .map(|(_, value)| value.as_str())
        .unwrap_or("1");
    if !version_is_one(version) {
        close_ws(&mut ws, 4004, "Invalid Version").await;
        return;
    }
    let encoding = params
        .iter()
        .find(|(name, _)| name == "encoding")
        .map(|(_, value)| value.as_str())
        .unwrap_or("json");
    if encoding != "json" {
        close_ws(&mut ws, 4000, "Invalid Encoding").await;
        return;
    }
    let client_id = params
        .iter()
        .find(|(name, _)| name == "client_id")
        .map(|(_, value)| value.as_str())
        .unwrap_or("");
    if !valid_client_id(client_id) {
        close_ws(&mut ws, 4000, "Invalid Client ID").await;
        return;
    }
    let client_id = client_id.to_string();

    let id = uuid::Uuid::new_v4().to_string();
    let (out, mut outgoing) = mpsc::unbounded_channel::<Outgoing>();
    let (closed_tx, _) = watch::channel(false);
    let conn = AppConnection::new(id, Transport::Ws, out, closed_tx.clone());

    let (write, mut read) = ws.split();
    let writer_closed = closed_tx.clone();
    tauri::async_runtime::spawn(async move {
        write_loop(write, &mut outgoing).await;
        let _ = writer_closed.send(true);
    });

    log::debug!("rpc: program {client_id} connected over WebSocket");
    let link = session::open(rpc, conn.clone(), client_id);
    let mut closed = conn.closed_rx();

    loop {
        tokio::select! {
            _ = super::stop_requested(&mut stop) => break,
            changed = closed.changed() => {
                if changed.is_err() || *closed.borrow() {
                    break;
                }
            }
            message = read.next() => match message {
                Some(Ok(Message::Text(text))) => match serde_json::from_str::<Value>(&text) {
                    Ok(json) => link.frame(super::frame::Frame::new(OP_FRAME, json)),
                    Err(_) => {
                        conn.close(1003, "Bad JSON");
                        break;
                    }
                },
                Some(Ok(Message::Binary(_))) => {}
                Some(Ok(Message::Close(_))) | None => break,
                Some(Ok(_)) => {}
                Some(Err(err)) => {
                    log::debug!("rpc: WebSocket read failed: {err}");
                    break;
                }
            }
        }
    }
    link.app_closed();
}

async fn write_loop(
    mut write: SplitSink<WebSocketStream<TcpStream>, Message>,
    outgoing: &mut mpsc::UnboundedReceiver<Outgoing>,
) {
    while let Some(message) = outgoing.recv().await {
        match message {
            Outgoing::Json(value) => {
                if write
                    .send(Message::Text(value.to_string().into()))
                    .await
                    .is_err()
                {
                    break;
                }
            }
            Outgoing::Frame(frame) => {
                if frame.op == OP_FRAME {
                    if write
                        .send(Message::Text(frame.json.to_string().into()))
                        .await
                        .is_err()
                    {
                        break;
                    }
                } else if frame.op == OP_CLOSE {
                    let (code, message) = close_parts(&frame.json);
                    let _ = write
                        .send(Message::Close(Some(close_frame(code, message))))
                        .await;
                    break;
                }
            }
            Outgoing::Close { code, message } => {
                let _ = write
                    .send(Message::Close(Some(close_frame(close_code(code), message))))
                    .await;
                break;
            }
        }
    }
}

fn query_params(query: &str) -> Vec<(String, String)> {
    tauri::Url::parse(&format!("http://127.0.0.1/?{query}"))
        .map(|url| {
            url.query_pairs()
                .map(|(name, value)| (name.into_owned(), value.into_owned()))
                .collect()
        })
        .unwrap_or_default()
}

fn version_is_one(version: &str) -> bool {
    if version.trim().is_empty() {
        // Number("") is 0, not 1.
        return false;
    }
    version
        .trim()
        .parse::<f64>()
        .map(|n| n == 1.0)
        .unwrap_or(false)
}

fn close_code(code: u32) -> u16 {
    if code >= 4000 {
        code.min(4999) as u16
    } else {
        1000
    }
}

fn close_parts(json: &Value) -> (u16, String) {
    let code = json
        .get("code")
        .and_then(Value::as_u64)
        .filter(|code| *code >= 4000)
        .map(|code| code.min(4999) as u16)
        .unwrap_or(1000);
    let message = json
        .get("message")
        .and_then(Value::as_str)
        .map(reason_text)
        .unwrap_or_default();
    (code, message)
}

fn close_frame(code: u16, message: String) -> CloseFrame {
    CloseFrame {
        code: CloseCode::from(code),
        reason: reason_text(&message).into(),
    }
}

/// A close frame's reason may be 123 bytes at most; cut on a character.
fn reason_text(message: &str) -> String {
    let mut out = String::new();
    for ch in message.chars().take(120) {
        if out.len() + ch.len_utf8() > 120 {
            break;
        }
        out.push(ch);
    }
    out
}

async fn close_ws(ws: &mut WebSocketStream<TcpStream>, code: u16, message: &str) {
    let _ = ws.close(Some(close_frame(code, message.to_string()))).await;
}
