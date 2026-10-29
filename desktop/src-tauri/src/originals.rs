//! Open Original: decrypted pictures and videos are handed to the default
//! browser from memory over a loopback listener that answers only exact random
//! paths, for ten minutes. Nothing is written to disk and nothing is uploaded.
//!
//! The Electron app did this with a Node HTTP server. This is the same
//! contract (Tauri calls it through one command) with the same limits.

use std::collections::HashMap;
use std::io::{self, Read};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use rand::distr::{Alphanumeric, SampleString};
use serde::Serialize;
use tauri::ipc::{InvokeBody, Request};
use tauri::State;
use tiny_http::{Header, Response, Server, StatusCode};

const MAX_ONE: usize = 256 * 1024 * 1024;
const MAX_ALL: usize = 256 * 1024 * 1024;
const MAX_FILES: usize = 8;
const TTL: Duration = Duration::from_secs(10 * 60);

struct Entry {
    bytes: Arc<Vec<u8>>,
    mime: String,
    name: String,
    expires: Instant,
}

struct Shared {
    port: u16,
    files: Mutex<HashMap<String, Entry>>,
}

#[derive(Default)]
pub struct Originals {
    server: Mutex<Option<Arc<Shared>>>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Original {
    url: String,
}

impl Originals {
    fn server(&self) -> Result<Arc<Shared>, String> {
        let mut slot = self.server.lock().unwrap();
        if let Some(server) = slot.as_ref() {
            return Ok(server.clone());
        }
        let listener = Server::http(("127.0.0.1", 0))
            .map_err(|error| format!("could not open the loopback listener: {error}"))?;
        let port = listener
            .server_addr()
            .to_ip()
            .ok_or_else(|| "the loopback listener has no port".to_string())?
            .port();
        let shared = Arc::new(Shared {
            port,
            files: Mutex::new(HashMap::new()),
        });
        let serving = shared.clone();
        std::thread::Builder::new()
            .name("nexa-originals".into())
            .spawn(move || serve(listener, serving))
            .map_err(|error| format!("could not start the loopback listener: {error}"))?;
        *slot = Some(shared.clone());
        Ok(shared)
    }

    fn put(&self, bytes: Vec<u8>, mime: String, name: String) -> Result<Original, String> {
        if bytes.is_empty() {
            return Err("there is nothing to open".into());
        }
        if bytes.len() > MAX_ONE {
            return Err("that file is too large to open here".into());
        }
        let shared = self.server()?;
        let token = Alphanumeric.sample_string(&mut rand::rng(), 48);
        {
            let mut files = shared.files.lock().unwrap();
            let now = Instant::now();
            files.retain(|_, entry| entry.expires > now);
            while files.len() >= MAX_FILES {
                if let Some(oldest) = files
                    .iter()
                    .min_by_key(|(_, entry)| entry.expires)
                    .map(|(token, _)| token.clone())
                {
                    files.remove(&oldest);
                } else {
                    break;
                }
            }
            let mut total: usize = files.values().map(|entry| entry.bytes.len()).sum();
            while total + bytes.len() > MAX_ALL {
                if let Some(oldest) = files
                    .iter()
                    .min_by_key(|(_, entry)| entry.expires)
                    .map(|(token, _)| token.clone())
                {
                    if let Some(gone) = files.remove(&oldest) {
                        total -= gone.bytes.len();
                    }
                } else {
                    return Err("too much is open already".into());
                }
            }
            files.insert(
                token.clone(),
                Entry {
                    bytes: Arc::new(bytes),
                    mime,
                    name,
                    expires: now + TTL,
                },
            );
        }
        Ok(Original {
            url: format!("http://127.0.0.1:{}/o/{token}", shared.port),
        })
    }
}

fn serve(server: Server, shared: Arc<Shared>) {
    for request in server.incoming_requests() {
        let url = request.url().to_string();
        let path = url.split('?').next().unwrap_or("");
        let host_ok = request
            .headers()
            .iter()
            .find(|header| header.field.equiv("Host"))
            .map(|header| {
                let value = header.value.as_str();
                value == format!("127.0.0.1:{}", shared.port)
                    || value == format!("localhost:{}", shared.port)
            })
            .unwrap_or(false);
        if !host_ok {
            let _ = request.respond(Response::empty(StatusCode(403)));
            continue;
        }
        let token = match path.strip_prefix("/o/") {
            Some(token) if !token.contains('/') => token,
            _ => {
                let _ = request.respond(Response::empty(StatusCode(404)));
                continue;
            }
        };
        let entry = {
            let mut files = shared.files.lock().unwrap();
            let now = Instant::now();
            files.retain(|_, entry| entry.expires > now);
            match files.get(token) {
                Some(entry) => (entry.bytes.clone(), entry.mime.clone(), entry.name.clone()),
                None => {
                    let _ = request.respond(Response::empty(StatusCode(404)));
                    continue;
                }
            }
        };
        let (bytes, mime, name) = entry;
        let length = bytes.len();
        let headers = vec![
            header("Content-Type", &mime),
            header("Cache-Control", "no-store"),
            header("X-Content-Type-Options", "nosniff"),
            header("Referrer-Policy", "no-referrer"),
            header("Content-Security-Policy", "default-src 'none'; sandbox"),
            header(
                "Content-Disposition",
                &format!("inline; filename=\"{}\"", safe_name(&name)),
            ),
        ];
        let reader = ArcReader {
            data: bytes.clone(),
            offset: 0,
        };
        let response = Response::new(
            StatusCode(200),
            headers,
            Box::new(reader) as Box<dyn Read + Send>,
            Some(length),
            None,
        );
        let _ = request.respond(response);
    }
}

struct ArcReader {
    data: Arc<Vec<u8>>,
    offset: usize,
}

impl Read for ArcReader {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        let remaining = &self.data[self.offset..];
        let take = remaining.len().min(buffer.len());
        buffer[..take].copy_from_slice(&remaining[..take]);
        self.offset += take;
        Ok(take)
    }
}

fn header(name: &str, value: &str) -> Header {
    Header::from_bytes(name.as_bytes(), value.as_bytes()).expect("a literal header")
}

fn safe_name(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .filter(|c| !c.is_control() && *c != '"' && *c != '\\' && *c != '/')
        .take(120)
        .collect();
    if cleaned.trim().is_empty() {
        "file".into()
    } else {
        cleaned
    }
}

fn media_type(raw: &str) -> String {
    let value = raw.trim().to_ascii_lowercase();
    let safe = value
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || matches!(c, '/' | '+' | '-' | '.'));
    if safe
        && (value.starts_with("image/")
            || value.starts_with("video/")
            || value.starts_with("audio/"))
    {
        value
    } else {
        "application/octet-stream".into()
    }
}

#[tauri::command]
pub async fn open_original(
    state: State<'_, crate::state::AppState>,
    request: Request<'_>,
) -> Result<Original, String> {
    let bytes = match request.body() {
        InvokeBody::Raw(bytes) => bytes.clone(),
        _ => return Err("Open Original needs the file itself".into()),
    };
    let mime = request
        .headers()
        .get("x-nexa-type")
        .and_then(|value| value.to_str().ok())
        .map(media_type)
        .unwrap_or_else(|| "application/octet-stream".into());
    let name = request
        .headers()
        .get("x-nexa-name")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| urlencoding::decode(value).ok())
        .map(|value| value.into_owned())
        .unwrap_or_else(|| "file".into());
    let original = state.originals.put(bytes, mime, name)?;
    tauri_plugin_opener::open_url(original.url.clone(), None::<&str>)
        .map_err(|error| format!("could not open the browser: {error}"))?;
    Ok(original)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_name_cannot_break_out_of_the_header() {
        assert_eq!(safe_name("a\"\nb\\c/d"), "abcd");
        assert_eq!(safe_name("   "), "file");
        assert_eq!(safe_name(&"x".repeat(300)).len(), 120);
    }

    #[test]
    fn only_media_types_are_served_as_themselves() {
        assert_eq!(media_type("IMAGE/PNG"), "image/png");
        assert_eq!(media_type("video/mp4"), "video/mp4");
        assert_eq!(media_type("text/html"), "application/octet-stream");
        assert_eq!(
            media_type("image/svg+xml\r\nX: y"),
            "application/octet-stream"
        );
    }
}
