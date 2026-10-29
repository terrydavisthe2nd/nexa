//! Where Discord's local IPC endpoints live, and whether anybody is listening.

use std::path::PathBuf;
use std::time::Duration;

/// discord-ipc-0 through discord-ipc-9.
pub const MAX_INDEX: u32 = 9;

pub fn is_windows() -> bool {
    cfg!(windows)
}

/// The socket directory, in the same order Discord and the Electron app used:
/// XDG_RUNTIME_DIR, TMPDIR, TMP, TEMP, then /tmp.
pub fn socket_dir() -> PathBuf {
    let raw = std::env::var_os("XDG_RUNTIME_DIR")
        .or_else(|| std::env::var_os("TMPDIR"))
        .or_else(|| std::env::var_os("TMP"))
        .or_else(|| std::env::var_os("TEMP"))
        .map(|v| v.to_string_lossy().into_owned())
        .unwrap_or_else(|| "/tmp".to_string());
    let trimmed = raw.trim();
    let mut dir = trimmed.trim_end_matches(['/', '\\']).to_string();
    if dir.is_empty() {
        dir = if cfg!(windows) {
            ".".to_string()
        } else {
            "/".to_string()
        };
    }
    PathBuf::from(dir)
}

/// The endpoint at `index`, as programs name it to Discord.
pub fn endpoint(index: u32) -> String {
    if cfg!(windows) {
        format!(r"\\?\pipe\discord-ipc-{index}")
    } else {
        socket_dir()
            .join(format!("discord-ipc-{index}"))
            .to_string_lossy()
            .into_owned()
    }
}

pub fn endpoint_path(index: u32) -> PathBuf {
    if cfg!(windows) {
        PathBuf::from(endpoint(index))
    } else {
        socket_dir().join(format!("discord-ipc-{index}"))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Probe {
    Listening,
    Free,
    /// A socket file is there but nobody answers (a program that died).
    Stale,
}

pub async fn probe(target: &str) -> Probe {
    probe_timeout(target, 800).await
}

#[cfg(unix)]
pub async fn probe_timeout(target: &str, timeout_ms: u64) -> Probe {
    match tokio::time::timeout(
        Duration::from_millis(timeout_ms),
        tokio::net::UnixStream::connect(target),
    )
    .await
    {
        // Something holds the name but did not finish connecting in time; treat
        // it as alive, as the Electron app did.
        Err(_) => Probe::Listening,
        Ok(Ok(stream)) => {
            drop(stream);
            Probe::Listening
        }
        Ok(Err(err)) if err.kind() == std::io::ErrorKind::ConnectionRefused => Probe::Stale,
        Ok(Err(_)) => Probe::Free,
    }
}

#[cfg(windows)]
pub async fn probe_timeout(target: &str, timeout_ms: u64) -> Probe {
    let target = target.to_string();
    let attempt = tokio::task::spawn_blocking(move || {
        super::local::connect_blocking(&target, Duration::from_millis(timeout_ms))
    });
    match tokio::time::timeout(Duration::from_millis(timeout_ms + 250), attempt).await {
        Err(_) => Probe::Listening,
        Ok(Ok(Ok(()))) => Probe::Listening,
        // A pipe that exists but is busy answers with an error other than
        // "not found"; Discord is still there. A missing pipe is free.
        Ok(Ok(Err(err))) => {
            if err.kind() == std::io::ErrorKind::NotFound
                || err.kind() == std::io::ErrorKind::ConnectionRefused
            {
                Probe::Free
            } else {
                Probe::Listening
            }
        }
        Ok(Err(_)) => Probe::Free,
    }
}

#[cfg(unix)]
pub fn remove_stale(path: &str) {
    let _ = std::fs::remove_file(path);
}

#[cfg(windows)]
pub fn remove_stale(_path: &str) {
    // Named pipes disappear with their server; there is no file to remove.
}
