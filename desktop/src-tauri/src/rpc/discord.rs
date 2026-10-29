//! Finding Discord and, on Unix, moving its socket aside for ours.
//!
//! Programs connect to `discord-ipc-0` first. To hear them while Discord runs,
//! this service needs index 0 and passes everything on to wherever Discord is.
//! A Unix socket file can be renamed while Discord keeps listening, so Discord's
//! is moved to a free index and put back when the service stops. Windows named
//! pipes cannot be moved; there Discord keeps index 0 and the app starts before
//! it, or offers to restart it.

use std::path::{Path, PathBuf};
#[cfg(target_os = "windows")]
use std::time::Duration;

use serde::{Deserialize, Serialize};

use super::paths::{self, Probe};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Moved {
    pub from: String,
    pub to: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Claim {
    /// Index 0 is ours (Discord was absent, or its socket was moved).
    Ready,
    /// Windows only: Discord holds index 0; the app started after it.
    First,
}

pub struct Locator {
    moved: Option<Moved>,
}

impl Locator {
    pub fn new() -> Self {
        Self { moved: None }
    }

    /// Makes index 0 ours to take. A socket left moved by a run that crashed is
    /// adopted as Discord's.
    pub async fn claim_first(&mut self) -> Claim {
        let zero = paths::endpoint(0);
        let zero_path = paths::endpoint_path(0);

        if !paths::is_windows() {
            if let Some(leftover) = load_moved() {
                let same_dir = Path::new(&leftover.from).parent() == Path::new(&zero).parent();
                if same_dir
                    && Path::new(&leftover.to).exists()
                    && paths::probe(&leftover.to).await == Probe::Listening
                    && paths::probe(&zero).await != Probe::Listening
                {
                    log::info!("rpc: adopting Discord's socket left at {}", leftover.to);
                    self.moved = Some(leftover);
                }
            }
        }

        let state = paths::probe(&zero).await;
        if state == Probe::Free {
            return Claim::Ready;
        }
        if paths::is_windows() {
            return if state == Probe::Listening {
                Claim::First
            } else {
                Claim::Ready
            };
        }
        if state == Probe::Stale {
            paths::remove_stale(&zero);
            return Claim::Ready;
        }

        // Discord holds index 0. Move it as high as possible, so the indices
        // programs scan next stay free for them.
        for i in (1..=paths::MAX_INDEX).rev() {
            let to = paths::endpoint(i);
            if Path::new(&to).exists() {
                continue;
            }
            match std::fs::rename(&zero_path, &to) {
                Ok(()) => {
                    let moved = Moved {
                        from: zero.clone(),
                        to: to.clone(),
                    };
                    log::info!("rpc: moved Discord's socket to {to}");
                    self.moved = Some(moved.clone());
                    save_moved(Some(&moved));
                    return Claim::Ready;
                }
                Err(err) => {
                    log::warn!("rpc: could not move Discord's socket: {err}");
                    return Claim::First;
                }
            }
        }
        Claim::First
    }

    /// Puts Discord's socket back where Discord made it, once ours has gone.
    pub fn restore(&mut self) {
        let Some(moved) = self.moved.take() else {
            return;
        };
        if Path::new(&moved.to).exists() && !Path::new(&moved.from).exists() {
            if let Err(err) = std::fs::rename(&moved.to, &moved.from) {
                // Discord remade its socket meanwhile, or quit; either way there
                // is nothing to put back.
                log::debug!("rpc: Discord's socket was not put back: {err}");
            }
        }
        save_moved(None);
    }

    /// Where Discord (or another Rich Presence server) listens, not counting
    /// ours, best first.
    pub async fn endpoints(&mut self, ours: Option<&str>) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        if let Some(moved) = &self.moved {
            if paths::probe(&moved.to).await == Probe::Listening {
                out.push(moved.to.clone());
            }
        }

        let mut candidates: Vec<String> = (0..=paths::MAX_INDEX).map(paths::endpoint).collect();
        if cfg!(target_os = "linux") {
            // Discord installed as a Flatpak or a Snap keeps its socket in a
            // folder of its own.
            let dir = paths::socket_dir();
            for sub in [
                "app/com.discordapp.Discord",
                "app/com.discordapp.DiscordCanary",
                "snap.discord",
                "snap.discord-canary",
            ] {
                for i in 0..=2 {
                    candidates.push(
                        dir.join(sub)
                            .join(format!("discord-ipc-{i}"))
                            .to_string_lossy()
                            .into_owned(),
                    );
                }
            }
        }

        for target in candidates {
            if ours == Some(target.as_str()) || out.iter().any(|t| t == &target) {
                continue;
            }
            if paths::probe_timeout(&target, 300).await == Probe::Listening {
                out.push(target);
            }
        }
        out
    }
}

fn moved_file() -> PathBuf {
    crate::settings::config_dir().join("rpc-moved-socket.json")
}

fn load_moved() -> Option<Moved> {
    let raw = std::fs::read(moved_file()).ok()?;
    serde_json::from_slice::<Moved>(&raw)
        .ok()
        .filter(|m| !m.from.is_empty() && !m.to.is_empty())
}

fn save_moved(moved: Option<&Moved>) {
    let path = moved_file();
    match moved {
        Some(moved) => {
            if let Some(parent) = path.parent() {
                if let Err(err) = std::fs::create_dir_all(parent) {
                    log::warn!("rpc: could not keep the moved socket path: {err}");
                    return;
                }
            }
            match serde_json::to_vec(moved) {
                Ok(json) => {
                    if let Err(err) = std::fs::write(&path, json) {
                        log::warn!("rpc: could not keep the moved socket path: {err}");
                    }
                }
                Err(err) => log::warn!("rpc: could not encode the moved socket path: {err}"),
            }
        }
        None => {
            let _ = std::fs::remove_file(path);
        }
    }
}

#[cfg(target_os = "windows")]
const DISCORD_BUILDS: [&str; 3] = ["Discord", "DiscordPTB", "DiscordCanary"];

/// Windows' own tools, by their full path, so a `tasklist.exe` in the current
/// directory can never run in their place.
#[cfg(windows)]
fn system32(tool: &str) -> PathBuf {
    let root = std::env::var_os("SystemRoot")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(r"C:\Windows"));
    let full = root.join("System32").join(format!("{tool}.exe"));
    if full.exists() {
        full
    } else {
        PathBuf::from(tool)
    }
}

#[cfg(windows)]
fn run_tool(file: &Path, args: &[&str]) -> String {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    match std::process::Command::new(file)
        .args(args)
        .creation_flags(CREATE_NO_WINDOW)
        .output()
    {
        Ok(out) => String::from_utf8_lossy(&out.stdout).into_owned(),
        Err(err) => {
            log::debug!("rpc: {} failed: {err}", file.display());
            String::new()
        }
    }
}

/// Which Discord builds are running on Windows.
#[cfg(windows)]
#[cfg(target_os = "windows")]
pub fn running_discord_builds() -> Vec<String> {
    let out = run_tool(&system32("tasklist"), &["/FO", "CSV", "/NH"]);
    DISCORD_BUILDS
        .iter()
        .filter(|build| {
            let needle = format!("\"{}.exe\"", build.to_ascii_lowercase());
            out.lines()
                .any(|line| line.trim_start().to_ascii_lowercase().starts_with(&needle))
        })
        .map(|build| (*build).to_string())
        .collect()
}

#[cfg(not(windows))]
#[cfg(target_os = "windows")]
pub fn running_discord_builds() -> Vec<String> {
    Vec::new()
}

/// Quits the running builds, letting the pipe go when the processes do.
#[cfg(target_os = "windows")]
pub async fn quit_builds(builds: &[String]) {
    #[cfg(windows)]
    {
        let builds = builds.to_vec();
        let _ = tokio::task::spawn_blocking(move || {
            for build in builds {
                let _ = run_tool(
                    &system32("taskkill"),
                    &["/IM", &format!("{build}.exe"), "/T", "/F"],
                );
            }
        })
        .await;
    }
    #[cfg(not(windows))]
    {
        let _ = builds;
    }
}

/// Lets the pipe go when the processes do (up to ten seconds, as the Electron
/// app waited).
#[cfg(target_os = "windows")]
pub async fn wait_for_pipe_gone() {
    let zero = paths::endpoint(0);
    for _ in 0..40 {
        if paths::probe(&zero).await != Probe::Listening {
            return;
        }
        tokio::time::sleep(Duration::from_millis(250)).await;
    }
}

/// Starts each build again through its updater, as its shortcuts do.
#[cfg(target_os = "windows")]
pub fn launch_builds(builds: &[String]) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let local = std::env::var_os("LOCALAPPDATA")
            .map(PathBuf::from)
            .unwrap_or_default();
        for build in builds {
            let updater = local.join(build).join("Update.exe");
            if !updater.exists() {
                continue;
            }
            let _ = std::process::Command::new(updater)
                .arg("--processStart")
                .arg(format!("{build}.exe"))
                .creation_flags(CREATE_NO_WINDOW)
                .spawn();
        }
    }
    #[cfg(not(windows))]
    {
        let _ = builds;
    }
}
