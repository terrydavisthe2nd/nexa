//! The way out to the real Discord client, when it is running.

use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tokio::sync::mpsc;

use super::frame::{self, Frame, FrameDecoder, OP_CLOSE, OP_FRAME, OP_HANDSHAKE};
use super::local::{self, PipeReader, PipeWriter};
use super::session::Event;

const OPEN_TIMEOUT: Duration = Duration::from_secs(5);

pub enum Open {
    Ready { upstream: Upstream, ready: Frame },
    Refused { code: u32, message: String },
    Unavailable,
}

pub struct Upstream {
    writer: Option<PipeWriter>,
}

impl Upstream {
    pub fn send(&mut self, frame: &Frame) {
        let Some(writer) = &self.writer else {
            return;
        };
        let bytes = frame::encode_frame(frame.op, &frame.json);
        let _ = writer.to_app.send(bytes);
    }

    pub fn close(&mut self) {
        // Dropping the writer closes the pipe; Discord sees the client go.
        self.writer.take();
    }
}

impl Drop for Upstream {
    fn drop(&mut self) {
        self.close();
    }
}

/// Connects to one Discord endpoint and waits for its READY. Everything
/// Discord says afterwards goes to `events`.
pub async fn open(target: &str, client_id: &str, events: mpsc::UnboundedSender<Event>) -> Open {
    let pipe = match tokio::time::timeout(OPEN_TIMEOUT, local::pipe_connect(target)).await {
        Ok(Ok(pipe)) => pipe,
        Ok(Err(err)) => {
            log::debug!("rpc: Discord at {target} did not answer: {err}");
            return Open::Unavailable;
        }
        Err(_) => {
            log::debug!("rpc: Discord at {target} took too long to connect");
            return Open::Unavailable;
        }
    };
    let PipeReader { mut from_app } = pipe.reader;
    let writer = pipe.writer;

    let handshake = frame::encode_frame(OP_HANDSHAKE, &json!({ "v": 1, "client_id": client_id }));
    if writer.to_app.send(handshake).is_err() {
        return Open::Unavailable;
    }

    let deadline = Instant::now() + OPEN_TIMEOUT;
    let mut decoder = FrameDecoder::new();
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            log::debug!("rpc: Discord at {target} did not become ready");
            return Open::Unavailable;
        }
        let chunk = match tokio::time::timeout(remaining, from_app.recv()).await {
            Ok(Some(chunk)) => chunk,
            Ok(None) => return Open::Unavailable,
            Err(_) => {
                log::debug!("rpc: Discord at {target} did not become ready");
                return Open::Unavailable;
            }
        };
        let frames = match decoder.push(&chunk) {
            Ok(frames) => frames,
            Err(err) => {
                log::warn!("rpc: Discord at {target} sent a broken frame: {err}");
                return Open::Unavailable;
            }
        };
        let mut ready: Option<Frame> = None;
        let mut rest = Vec::new();
        for f in frames {
            if ready.is_some() {
                rest.push(f);
                continue;
            }
            if f.op == OP_CLOSE {
                let code = f.json.get("code").and_then(Value::as_u64).unwrap_or(1000) as u32;
                let message = f
                    .json
                    .get("message")
                    .and_then(Value::as_str)
                    .unwrap_or("Closed")
                    .to_string();
                log::info!("rpc: Discord at {target} refused the connection: {code} {message}");
                return Open::Refused { code, message };
            }
            if f.op == OP_FRAME && f.json.get("evt").and_then(Value::as_str) == Some("READY") {
                ready = Some(f);
            }
        }
        if let Some(ready) = ready {
            let upstream = Upstream {
                writer: Some(writer),
            };
            spawn_reader(
                crate::rpc::local::PipeReader { from_app },
                decoder,
                events,
                rest,
            );
            return Open::Ready { upstream, ready };
        }
    }
}

fn spawn_reader(
    mut reader: PipeReader,
    mut decoder: FrameDecoder,
    events: mpsc::UnboundedSender<Event>,
    rest: Vec<Frame>,
) {
    tauri::async_runtime::spawn(async move {
        for frame in rest {
            if events.send(Event::UpstreamFrame(frame)).is_err() {
                return;
            }
        }
        loop {
            match reader.from_app.recv().await {
                Some(chunk) => match decoder.push(&chunk) {
                    Ok(frames) => {
                        for frame in frames {
                            if events.send(Event::UpstreamFrame(frame)).is_err() {
                                return;
                            }
                        }
                    }
                    Err(err) => {
                        log::warn!("rpc: Discord sent a broken frame: {err}");
                        break;
                    }
                },
                None => break,
            }
        }
        let _ = events.send(Event::UpstreamClosed);
    });
}
