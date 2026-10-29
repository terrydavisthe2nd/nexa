//! A byte pipe over Discord's local IPC transport: a Unix domain socket on
//! Linux and macOS, a named pipe on Windows.
//!
//! The platform readers and writers run as their own tasks/threads and meet the
//! rest of the service at channels, so a program that stops reading can never
//! stall a tokio worker.

use std::io;

use tokio::sync::mpsc;

pub struct PipeReader {
    pub from_app: mpsc::UnboundedReceiver<Vec<u8>>,
}

pub struct PipeWriter {
    pub to_app: mpsc::UnboundedSender<Vec<u8>>,
}

pub struct Pipe {
    pub reader: PipeReader,
    pub writer: PipeWriter,
}

pub struct PipeListener {
    inner: imp::Listener,
}

impl PipeListener {
    pub async fn accept(&mut self) -> io::Result<Pipe> {
        self.inner.accept().await
    }
}

pub async fn pipe_connect(target: &str) -> io::Result<Pipe> {
    imp::connect(target).await
}

pub fn pipe_listen(target: &str) -> io::Result<PipeListener> {
    Ok(PipeListener {
        inner: imp::listen(target)?,
    })
}

/// On Unix a socket file is left behind by a process that died; the Electron
/// app removed a stale one before binding.
#[cfg(target_os = "windows")]
pub fn remove_socket(path: &str) {
    #[cfg(unix)]
    {
        let _ = std::fs::remove_file(path);
    }
    #[cfg(not(unix))]
    {
        let _ = path;
    }
}

#[cfg(unix)]
mod imp {
    use super::*;
    use std::io;
    use std::os::unix::fs::PermissionsExt;
    use std::path::PathBuf;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::{UnixListener, UnixStream};

    pub struct Listener {
        listener: UnixListener,
        path: PathBuf,
    }

    impl Listener {
        pub async fn accept(&mut self) -> io::Result<Pipe> {
            let (stream, _) = self.listener.accept().await?;
            from_stream(stream)
        }
    }

    impl Drop for Listener {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.path);
        }
    }

    pub fn listen(target: &str) -> io::Result<Listener> {
        let listener = UnixListener::bind(target)?;
        // Discord's own socket is 0600. Bind first, then take the group and
        // other bits away, rather than changing the process umask.
        std::fs::set_permissions(target, std::fs::Permissions::from_mode(0o600))?;
        Ok(Listener {
            listener,
            path: PathBuf::from(target),
        })
    }

    pub async fn connect(target: &str) -> io::Result<Pipe> {
        let stream = UnixStream::connect(target).await?;
        from_stream(stream)
    }

    fn from_stream(stream: UnixStream) -> io::Result<Pipe> {
        let (mut read, mut write) = stream.into_split();
        let (from_tx, from_rx) = mpsc::unbounded_channel::<Vec<u8>>();
        let (to_tx, mut to_rx) = mpsc::unbounded_channel::<Vec<u8>>();

        tauri::async_runtime::spawn(async move {
            let mut buf = vec![0u8; 16 * 1024];
            loop {
                tokio::select! {
                    result = read.read(&mut buf) => match result {
                        Ok(0) => break,
                        Ok(n) => {
                            if from_tx.send(buf[..n].to_vec()).is_err() {
                                break;
                            }
                        }
                        Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
                        Err(_) => break,
                    },
                    message = to_rx.recv() => match message {
                        Some(bytes) => {
                            if write.write_all(&bytes).await.is_err() {
                                break;
                            }
                        }
                        None => break,
                    },
                }
            }
            let _ = write.shutdown().await;
        });

        Ok(Pipe {
            reader: PipeReader { from_app: from_rx },
            writer: PipeWriter { to_app: to_tx },
        })
    }
}

#[cfg(windows)]
mod imp {
    use super::*;
    use interprocess::local_socket::prelude::*;
    use interprocess::local_socket::{
        ConnectOptions, ListenerNonblockingMode, ListenerOptions, Stream,
    };
    use interprocess::{ConnectWaitMode, TryClone};
    use std::io::{self, Read, Write};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;
    use std::thread;
    use std::time::Duration;

    /// `\\?\pipe\discord-ipc-0` and `\\.\pipe\discord-ipc-0` name the same
    /// pipe; interprocess wants the latter.
    fn pipe_path(target: &str) -> String {
        if let Some(rest) = target.strip_prefix(r"\\?\pipe\") {
            format!(r"\\.\pipe\{rest}")
        } else {
            target.to_string()
        }
    }

    pub struct Listener {
        rx: mpsc::UnboundedReceiver<Pipe>,
        stop: Arc<AtomicBool>,
    }

    impl Listener {
        pub async fn accept(&mut self) -> io::Result<Pipe> {
            self.rx.recv().await.ok_or_else(|| {
                io::Error::new(io::ErrorKind::ConnectionAborted, "IPC listener stopped")
            })
        }
    }

    impl Drop for Listener {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Relaxed);
        }
    }

    pub fn listen(target: &str) -> io::Result<Listener> {
        let path = pipe_path(target);
        let name = path.to_fs_name::<GenericFilePath>()?;
        let listener = ListenerOptions::new()
            .name(name)
            .nonblocking(ListenerNonblockingMode::Accept)
            .create_sync()?;
        let (tx, rx) = mpsc::unbounded_channel::<Pipe>();
        let stop = Arc::new(AtomicBool::new(false));
        let thread_stop = Arc::clone(&stop);
        thread::spawn(move || {
            while !thread_stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok(stream) => match from_stream(stream) {
                        Ok(pipe) => {
                            if tx.send(pipe).is_err() {
                                break;
                            }
                        }
                        Err(err) => log::warn!("rpc: could not set up a named pipe: {err}"),
                    },
                    Err(err) if err.kind() == io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(20));
                    }
                    Err(err) => {
                        log::warn!("rpc: named pipe accept failed: {err}");
                        thread::sleep(Duration::from_millis(50));
                    }
                }
            }
        });
        Ok(Listener { rx, stop })
    }

    pub async fn connect(target: &str) -> io::Result<Pipe> {
        let target = target.to_string();
        let stream = tokio::task::spawn_blocking(move || -> io::Result<Stream> {
            let path = pipe_path(&target);
            let name = path.to_fs_name::<GenericFilePath>()?;
            ConnectOptions::new()
                .name(name)
                .wait_mode(ConnectWaitMode::Timeout(Duration::from_secs(5)))
                .connect_sync()
        })
        .await
        .map_err(|err| io::Error::other(err.to_string()))??;
        from_stream(stream)
    }

    /// A blocking connect, for probing whether a pipe is there.
    pub fn connect_blocking(target: &str, timeout: Duration) -> io::Result<()> {
        let path = pipe_path(target);
        let name = path.to_fs_name::<GenericFilePath>()?;
        ConnectOptions::new()
            .name(name)
            .wait_mode(ConnectWaitMode::Timeout(timeout))
            .connect_sync()
            .map(|_| ())
    }

    fn from_stream(stream: Stream) -> io::Result<Pipe> {
        let mut writer_stream = stream.try_clone()?;
        let (from_tx, from_rx) = mpsc::unbounded_channel::<Vec<u8>>();
        let (to_tx, mut to_rx) = mpsc::unbounded_channel::<Vec<u8>>();

        let mut reader_stream = stream;
        thread::spawn(move || {
            let mut buf = vec![0u8; 16 * 1024];
            loop {
                match reader_stream.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        if from_tx.send(buf[..n].to_vec()).is_err() {
                            break;
                        }
                    }
                }
            }
        });
        thread::spawn(move || {
            while let Some(bytes) = to_rx.blocking_recv() {
                if writer_stream.write_all(&bytes).is_err() {
                    break;
                }
                if writer_stream.flush().is_err() {
                    break;
                }
            }
        });

        Ok(Pipe {
            reader: PipeReader { from_app: from_rx },
            writer: PipeWriter { to_app: to_tx },
        })
    }
}
