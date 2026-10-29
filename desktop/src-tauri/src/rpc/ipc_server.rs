//! The local IPC endpoint programs open: `discord-ipc-0` and its neighbours.

use std::time::Duration;

use serde_json::{json, Value};
use tokio::sync::{mpsc, watch};

use super::frame::{encode_frame, FrameDecoder, OP_CLOSE, OP_FRAME, OP_HANDSHAKE};
use super::local::{self, Pipe, PipeWriter};
use super::paths::{self, Probe};
use super::session::{self, AppConnection, Outgoing, SessionLink, Transport};
use super::{valid_client_id, Rpc};

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

pub struct IpcHandle {
    pub path: String,
    pub stop: watch::Sender<bool>,
    pub task: tauri::async_runtime::JoinHandle<()>,
}

pub async fn start(rpc: Rpc) -> Option<IpcHandle> {
    for index in 0..=paths::MAX_INDEX {
        let target = paths::endpoint(index);
        match paths::probe(&target).await {
            Probe::Listening => continue,
            Probe::Stale => {
                paths::remove_stale(&target);
                if paths::probe(&target).await == Probe::Listening {
                    continue;
                }
            }
            Probe::Free => {}
        }
        match local::pipe_listen(&target) {
            Ok(mut listener) => {
                log::info!("rpc: listening for programs at {target}");
                let (stop, mut stop_rx) = watch::channel(false);
                let path = target.clone();
                let accept_rpc = rpc.clone();
                let task = tauri::async_runtime::spawn(async move {
                    loop {
                        tokio::select! {
                            _ = super::stop_requested(&mut stop_rx) => break,
                            accepted = listener.accept() => match accepted {
                                Ok(pipe) => {
                                    let rpc = accept_rpc.clone();
                                    let stop = stop_rx.clone();
                                    tauri::async_runtime::spawn(handle_connection(rpc, pipe, stop));
                                }
                                Err(err) => {
                                    log::warn!("rpc: could not accept a program at {target}: {err}");
                                    break;
                                }
                            }
                        }
                    }
                });
                return Some(IpcHandle { path, stop, task });
            }
            Err(err) => {
                log::debug!("rpc: could not listen at {target}: {err}");
            }
        }
    }
    log::error!("rpc: no free IPC endpoint between discord-ipc-0 and discord-ipc-9");
    None
}

async fn handle_connection(rpc: Rpc, pipe: Pipe, mut stop: watch::Receiver<bool>) {
    let id = uuid::Uuid::new_v4().to_string();
    let (out, mut outgoing) = mpsc::unbounded_channel::<Outgoing>();
    let (closed_tx, _) = watch::channel(false);
    let conn = AppConnection::new(id, Transport::Ipc, out, closed_tx.clone());

    let Pipe { reader, writer } = pipe;
    let writer_closed = closed_tx.clone();
    tauri::async_runtime::spawn(async move {
        write_loop(writer, &mut outgoing).await;
        let _ = writer_closed.send(true);
    });

    let mut reader = reader;
    let mut decoder = FrameDecoder::new();
    let mut link: Option<SessionLink> = None;
    let mut closed = conn.closed_rx();
    let deadline = tokio::time::sleep(HANDSHAKE_TIMEOUT);
    tokio::pin!(deadline);

    'connection: loop {
        tokio::select! {
            _ = super::stop_requested(&mut stop) => break,
            _ = &mut deadline, if link.is_none() => {
                log::debug!("rpc: a program did not finish its handshake");
                conn.close(4000, "Handshake timed out");
                break;
            }
            changed = closed.changed() => {
                if changed.is_err() || *closed.borrow() {
                    break;
                }
            }
            chunk = reader.from_app.recv() => {
                let Some(chunk) = chunk else { break };
                let frames = match decoder.push(&chunk) {
                    Ok(frames) => frames,
                    Err(err) => {
                        conn.close(1003, &err.message());
                        break;
                    }
                };
                for frame in frames {
                    if let Some(link) = &link {
                        link.frame(frame);
                        continue;
                    }
                    if frame.op != OP_HANDSHAKE {
                        conn.close(1003, "Handshake first");
                        break 'connection;
                    }
                    let handshake = &frame.json;
                    if !version_is_one(handshake.get("v")) {
                        conn.close(4004, "Invalid Version");
                        break 'connection;
                    }
                    let client_id = handshake
                        .get("client_id")
                        .and_then(Value::as_str)
                        .unwrap_or("");
                    if !valid_client_id(client_id) {
                        conn.close(4000, "Invalid Client ID");
                        break 'connection;
                    }
                    log::debug!("rpc: program {client_id} connected over IPC");
                    link = Some(session::open(rpc.clone(), conn.clone(), client_id.to_string()));
                }
            }
        }
    }

    if let Some(link) = &link {
        link.app_closed();
    }
}

async fn write_loop(writer: PipeWriter, outgoing: &mut mpsc::UnboundedReceiver<Outgoing>) {
    while let Some(message) = outgoing.recv().await {
        let (bytes, done) = match message {
            Outgoing::Json(value) => (encode_frame(OP_FRAME, &value), false),
            Outgoing::Frame(frame) => {
                let done = frame.op == OP_CLOSE;
                (encode_frame(frame.op, &frame.json), done)
            }
            Outgoing::Close { code, message } => (
                encode_frame(OP_CLOSE, &json!({ "code": code, "message": message })),
                true,
            ),
        };
        if writer.to_app.send(bytes).is_err() || done {
            break;
        }
    }
}

fn version_is_one(v: Option<&Value>) -> bool {
    match v {
        Some(Value::Number(n)) => n.as_f64() == Some(1.0),
        Some(Value::String(s)) => s.trim().parse::<f64>().map(|n| n == 1.0).unwrap_or(false),
        Some(Value::Bool(b)) => *b,
        _ => false,
    }
}
