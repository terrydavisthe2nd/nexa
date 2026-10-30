// Copyright 2026 terrydavisthe2nd / Nexa
// SPDX-License-Identifier: MIT

//! X11/portal video and one explicitly selected playback producer, not an
//! application's entire process tree or the output mix. Audio selection is
//! independent of video, especially of Wayland's synthetic portal source IDs.
//!
//! A persistent Pulse connection watches producer lifetimes between enumeration
//! and capture. A move, metadata change or disappearance ends the selection;
//! the user must select again. There is deliberately no default-source fallback.
//! Process validation rejects Nexa descendants and conservatively all WebKit
//! producers. It is not proof of attribution for arbitrary detached helpers,
//! forged same-user audio properties, sandboxes or third-party virtual mixers.

use std::{
    cell::{Cell, RefCell},
    collections::HashMap,
    fs,
    os::unix::fs::MetadataExt,
    rc::Rc,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, SyncSender, TryRecvError},
        Arc, Mutex, OnceLock,
    },
    thread,
    time::{Duration, Instant},
};

use libpulse_binding as pulse;
use libwebrtc::desktop_capturer::{
    CaptureError, DesktopCaptureSourceType, DesktopCapturer, DesktopCapturerOptions,
};
use pulse::{
    callbacks::ListResult,
    context::{
        introspect::SinkInputInfo,
        subscribe::{Facility, InterestMaskSet},
        Context,
    },
    mainloop::standard::{IterateResult, Mainloop},
    operation::Operation,
    proplist::Proplist,
    sample,
    stream::{PeekResult, Stream},
};
use serde::Serialize;

use super::{CaptureConfig, Sinks, SoundSource, Source};

const POLL: Duration = Duration::from_millis(5);
const QUERY_TIMEOUT: Duration = Duration::from_secs(3);
const MAX_PRODUCERS: usize = 128;
// 100 ms of 48 kHz, stereo PCM16. The shared Sinks queue is bounded too.
const MAX_AUDIO_BYTES: usize = 19_200;

#[derive(Clone)]
struct VideoChoice {
    kind: DesktopCaptureSourceType,
    id: Option<u64>,
    title: String,
}

static VIDEOS: OnceLock<Mutex<HashMap<String, VideoChoice>>> = OnceLock::new();

fn wayland() -> bool {
    std::env::var_os("WAYLAND_DISPLAY").is_some_and(|s| !s.is_empty())
        || std::env::var("XDG_SESSION_TYPE").is_ok_and(|s| s == "wayland")
}

fn capturer(kind: DesktopCaptureSourceType) -> Result<DesktopCapturer, String> {
    let mut options = DesktopCapturerOptions::new(kind);
    options.set_include_cursor(true);
    DesktopCapturer::new(options).ok_or_else(|| "Linux desktop capture is unavailable".into())
}

pub fn sources() -> Result<Vec<Source>, String> {
    let mut choices = HashMap::new();
    let mut result = Vec::new();
    if wayland() {
        // Probe the actual Generic capturer, not an invented XID/PID. Starting
        // this capturer later opens the compositor's monitor/window chooser.
        let _probe = capturer(DesktopCaptureSourceType::Generic)?;
        let id = uuid::Uuid::new_v4().to_string();
        choices.insert(
            id.clone(),
            VideoChoice {
                kind: DesktopCaptureSourceType::Generic,
                id: None,
                title: String::new(),
            },
        );
        result.push(Source {
            id,
            name: "Desktop Portal".into(),
            kind: "screen".into(),
            thumbnail: String::new(),
        });
    } else {
        for (kind, label) in [
            (DesktopCaptureSourceType::Screen, "screen"),
            (DesktopCaptureSourceType::Window, "window"),
        ] {
            let cap = capturer(kind)?;
            for source in cap.get_source_list().into_iter().take(256) {
                let title = source.title();
                let name = if title.is_empty() {
                    format!("{} {}", label, source.id())
                } else {
                    title.clone()
                };
                let id = uuid::Uuid::new_v4().to_string();
                choices.insert(
                    id.clone(),
                    VideoChoice {
                        kind,
                        id: Some(source.id()),
                        title,
                    },
                );
                result.push(Source {
                    id,
                    name,
                    kind: label.into(),
                    thumbnail: String::new(),
                });
            }
        }
    }
    *VIDEOS
        .get_or_init(Default::default)
        .lock()
        .map_err(|_| "capture source lock failed")? = choices;
    Ok(result)
}

pub fn sounds() -> Result<Vec<SoundSource>, String> {
    let (tx, rx) = mpsc::sync_channel(1);
    broker()?
        .try_send(Command::List(tx))
        .map_err(|_| "audio capture is busy")?;
    rx.recv_timeout(Duration::from_secs(10))
        .map_err(|_| "audio enumeration timed out")?
}

pub fn run(config: CaptureConfig, sinks: Sinks, stop: Arc<AtomicBool>) -> Result<(), String> {
    if config.width == 0 || config.height == 0 || !(1..=60).contains(&config.fps) {
        return Err("invalid Linux capture settings".into());
    }
    let choice = VIDEOS
        .get_or_init(Default::default)
        .lock()
        .map_err(|_| "capture source lock failed")?
        .get(&config.source_id)
        .cloned()
        .ok_or("selected video source expired")?;
    if (choice.id.is_none()) != wayland() {
        return Err("display session changed; select a source again".into());
    }
    let mut cap = capturer(choice.kind)?;
    let selected = match choice.id {
        Some(id) => Some(
            cap.get_source_list()
                .into_iter()
                .find(|s| s.id() == id && s.title() == choice.title)
                .ok_or("selected video source disappeared")?,
        ),
        None => None,
    };
    // Requested audio must be connected and scope-validated before ready().
    let audio = config
        .sound_id
        .as_ref()
        .map(|id| AudioSession::start(id.clone(), sinks.clone(), stop.clone()))
        .transpose()?;
    let status = Arc::new(Mutex::new((None::<Instant>, None::<String>)));
    let callback_status = status.clone();
    let callback_stop = stop.clone();
    let callback_sinks = sinks.clone();
    cap.start_capture(selected, move |result| {
        if callback_stop.load(Ordering::Acquire) {
            return;
        }
        let mut state = callback_status.lock().unwrap_or_else(|p| p.into_inner());
        match result {
            Ok(frame) => {
                let (w, h, stride) = (frame.width(), frame.height(), frame.stride());
                if w <= 0
                    || h <= 0
                    || w > 7680
                    || h > 7680
                    || stride < w as u32 * 4
                    || stride > 7680 * 8
                {
                    state.1 = Some("invalid desktop frame dimensions".into());
                    return;
                }
                callback_sinks.video(frame.data(), w as u32, h as u32, stride);
                state.0 = Some(Instant::now());
            }
            Err(CaptureError::Permanent) => {
                state.1 = Some("desktop capture ended or permission was denied".into())
            }
            Err(CaptureError::Temporary) => {}
        }
    });
    // Capturer creation/start/ticks/destruction all stay on this worker. Tauri's
    // GTK GLib main loop must remain running for portal callbacks. Do not enable
    // libwebrtc's competing glib-main-loop feature in the Tauri host.
    let started = Instant::now();
    let interval = Duration::from_secs_f64(1.0 / config.fps as f64);
    let mut next = started;
    let mut ready = false;
    while !stop.load(Ordering::Acquire) {
        if let Some(audio) = &audio {
            audio.check()?;
        }
        if Instant::now() >= next {
            cap.capture_frame();
            next = Instant::now() + interval;
        }
        let (last, error) = status.lock().map_err(|_| "video callback failed")?.clone();
        if let Some(error) = error {
            return Err(error);
        }
        if let Some(last) = last {
            if !ready {
                sinks.ready()?;
                if let Some(audio) = &audio {
                    audio.enabled.store(true, Ordering::Release);
                }
                ready = true;
            }
            if last.elapsed() > Duration::from_secs(5) {
                return Err("desktop capture stopped producing frames".into());
            }
        } else if started.elapsed() > Duration::from_secs(25) {
            return Err("desktop portal selection or first frame timed out".into());
        }
        thread::sleep(POLL.min(next.saturating_duration_since(Instant::now())));
    }
    Ok(())
}

// A long-lived connection is essential: snapshots of index/PID/name alone do
// not identify a stream lifetime if an index gets reused while a picker is up.
enum Command {
    List(SyncSender<Result<Vec<SoundSource>, String>>),
    Capture {
        id: String,
        sinks: Sinks,
        stop: Arc<AtomicBool>,
        enabled: Arc<AtomicBool>,
        initialized: SyncSender<Result<(), String>>,
        done: SyncSender<Result<(), String>>,
    },
}

fn broker() -> Result<&'static SyncSender<Command>, String> {
    static BROKER: OnceLock<Result<SyncSender<Command>, String>> = OnceLock::new();
    BROKER
        .get_or_init(|| {
            let (tx, rx) = mpsc::sync_channel(2);
            thread::Builder::new()
                .name("nexa-pulse-capture".into())
                .spawn(move || audio_broker(rx))
                .map_err(|e| format!("cannot start audio capture worker: {e}"))?;
            Ok(tx)
        })
        .as_ref()
        .map_err(Clone::clone)
}

struct AudioSession {
    stop: Arc<AtomicBool>,
    enabled: Arc<AtomicBool>,
    done: Receiver<Result<(), String>>,
}

impl AudioSession {
    fn start(id: String, sinks: Sinks, stop: Arc<AtomicBool>) -> Result<Self, String> {
        let (init_tx, init_rx) = mpsc::sync_channel(1);
        let (done_tx, done) = mpsc::sync_channel(1);
        let enabled = Arc::new(AtomicBool::new(false));
        let session = Self {
            stop: stop.clone(),
            enabled: enabled.clone(),
            done,
        };
        broker()?
            .try_send(Command::Capture {
                id,
                sinks,
                stop,
                enabled,
                initialized: init_tx,
                done: done_tx,
            })
            .map_err(|_| "audio capture is busy")?;
        init_rx
            .recv_timeout(Duration::from_secs(10))
            .map_err(|_| "audio initialization timed out")??;
        Ok(session)
    }

    fn check(&self) -> Result<(), String> {
        match self.done.try_recv() {
            Ok(Err(error)) => Err(error),
            Ok(Ok(())) | Err(TryRecvError::Disconnected) => Err("audio capture ended".into()),
            Err(TryRecvError::Empty) => Ok(()),
        }
    }
}

impl Drop for AudioSession {
    fn drop(&mut self) {
        self.enabled.store(false, Ordering::Release);
        self.stop.store(true, Ordering::Release);
        // No OS callback feeds the shared sinks after this acknowledgement.
        let _ = self
            .done
            .recv_timeout(QUERY_TIMEOUT + Duration::from_secs(1));
    }
}

fn audio_broker(rx: Receiver<Command>) {
    let mut connection: Option<Pulse> = None;
    loop {
        if connection.as_mut().is_some_and(|p| p.pump().is_err()) {
            connection = None; // Invalidates every selection on server loss.
        }
        let command = match rx.recv_timeout(POLL) {
            Ok(command) => command,
            Err(mpsc::RecvTimeoutError::Timeout) => continue,
            Err(mpsc::RecvTimeoutError::Disconnected) => return,
        };
        if connection.is_none() {
            match Pulse::new() {
                Ok(pulse) => connection = Some(pulse),
                Err(error) => {
                    match command {
                        Command::List(reply) => {
                            let _ = reply.send(Err(error));
                        }
                        Command::Capture {
                            initialized, done, ..
                        } => {
                            let _ = initialized.send(Err(error.clone()));
                            let _ = done.send(Err(error));
                        }
                    }
                    continue;
                }
            }
        }
        let Some(pulse) = connection.as_mut() else {
            continue;
        };
        match command {
            Command::List(reply) => {
                let _ = reply.send(pulse.list());
            }
            Command::Capture {
                id,
                sinks,
                stop,
                enabled,
                initialized,
                done,
            } => {
                pulse.cancel = Some(stop.clone());
                let result = pulse.capture(&id, &sinks, &stop, &enabled, &initialized);
                pulse.cancel = None;
                pulse.watch.borrow_mut().active = None;
                let _ = initialized.try_send(result.clone());
                let _ = done.send(result);
            }
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
struct ProcessIdentity {
    pid: u32,
    start: u64,
    device: u64,
    inode: u64,
}

fn parse_stat(stat: &str) -> Result<(u32, u64), String> {
    let (_, fields) = stat.rsplit_once(')').ok_or("invalid process stat")?;
    let fields: Vec<_> = fields.split_whitespace().collect();
    let parent = fields
        .get(1)
        .ok_or("missing process parent")?
        .parse()
        .map_err(|_| "invalid process parent")?;
    let start = fields
        .get(19)
        .ok_or("missing process lifetime")?
        .parse()
        .map_err(|_| "invalid process lifetime")?;
    Ok((parent, start))
}

fn process_identity(pid: u32) -> Result<ProcessIdentity, String> {
    if pid == 0 {
        return Err("unknown playback producer process".into());
    }
    let own = fs::metadata("/proc/self").map_err(|e| e.to_string())?;
    let mut current = pid;
    // Unknown/inaccessible/reparented namespace identities are not admitted.
    for _ in 0..128 {
        if current == std::process::id() {
            return Err("Nexa playback cannot be shared".into());
        }
        let stat = fs::read_to_string(format!("/proc/{current}/stat"))
            .map_err(|_| "playback process is unavailable")?;
        let (parent, _) = parse_stat(&stat)?;
        if parent == 0 {
            break;
        }
        if parent == current {
            return Err("invalid playback process ancestry".into());
        }
        current = parent;
        if current == 1 {
            break;
        }
    }
    let root = format!("/proc/{pid}");
    let metadata = fs::metadata(&root).map_err(|e| e.to_string())?;
    if metadata.uid() != own.uid() {
        return Err("playback producer belongs to another user".into());
    }
    let executable = fs::read_link(format!("{root}/exe"))
        .map_err(|_| "playback executable cannot be validated")?;
    let name = executable
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    // WebKit helpers may be detached/reparented or activated by a broker. Do not
    // guess ownership from names and then permit unknown WebKit playback.
    if name.contains("webkit")
        || matches!(
            name.as_str(),
            "pulseaudio" | "pipewire" | "wireplumber" | "pw-loopback"
        )
    {
        return Err("WebKit or aggregate audio producers are not eligible".into());
    }
    let exe = fs::metadata(format!("{root}/exe")).map_err(|e| e.to_string())?;
    let own_exe = fs::metadata("/proc/self/exe").map_err(|e| e.to_string())?;
    if exe.dev() == own_exe.dev() && exe.ino() == own_exe.ino() {
        return Err("Nexa playback cannot be shared".into());
    }
    let (_, start) =
        parse_stat(&fs::read_to_string(format!("{root}/stat")).map_err(|e| e.to_string())?)?;
    Ok(ProcessIdentity {
        pid,
        start,
        device: exe.dev(),
        inode: exe.ino(),
    })
}

#[derive(Clone, PartialEq, Eq, Serialize)]
struct Input {
    index: u32,
    client: u32,
    sink: u32,
    pid: Option<u32>,
    name: String,
    application: String,
    binary: String,
}

fn property_pid(properties: &Proplist) -> Option<u32> {
    properties
        .get_str("application.process.id")?
        .parse()
        .ok()
        .filter(|pid| *pid > 0)
}

fn input(info: &SinkInputInfo<'_>) -> Option<Input> {
    // Module-owned/anonymous producers may already be a mix of other clients.
    if info.owner_module.is_some() {
        return None;
    }
    if info
        .proplist
        .get_str("media.class")
        .is_some_and(|c| c != "Stream/Output/Audio")
    {
        return None;
    }
    if info
        .proplist
        .get_str("media.role")
        .is_some_and(|r| r == "filter")
    {
        return None;
    }
    Some(Input {
        index: info.index,
        client: info.client?,
        sink: info.sink,
        pid: property_pid(&info.proplist),
        name: info.name.as_deref().unwrap_or("").to_owned(),
        application: info
            .proplist
            .get_str("application.name")
            .unwrap_or_default(),
        binary: info
            .proplist
            .get_str("application.process.binary")
            .unwrap_or_default(),
    })
}

#[derive(Clone, Serialize)]
struct Selection {
    // UUID + live subscribed registry distinguishes two otherwise identical
    // producers even when the server recycles an index within one process.
    lifetime: String,
    input: Input,
    process: ProcessIdentity,
}

#[derive(Clone, Copy)]
struct Scope {
    input: u32,
    client: u32,
    sink: u32,
    monitor: u32,
}

impl Scope {
    fn affected(&self, facility: Option<Facility>, index: u32) -> bool {
        match facility {
            Some(Facility::SinkInput) => self.input == index,
            Some(Facility::Client) => self.client == index,
            Some(Facility::Sink) => self.sink == index,
            Some(Facility::Source) => self.monitor == index,
            Some(Facility::Server) | None => true,
            _ => false,
        }
    }
}

#[derive(Default)]
struct Watch {
    selections: HashMap<String, Selection>,
    active: Option<Scope>,
    invalidated: bool,
    revision: u64,
}

struct Pulse {
    context: Context,
    mainloop: Mainloop,
    watch: Rc<RefCell<Watch>>,
    cancel: Option<Arc<AtomicBool>>,
}

impl Drop for Pulse {
    fn drop(&mut self) {
        self.context.set_subscribe_callback(None);
        self.context.disconnect();
    }
}

impl Pulse {
    fn new() -> Result<Self, String> {
        let mainloop = Mainloop::new().ok_or("PulseAudio mainloop is unavailable")?;
        let mut context = Context::new(&mainloop, "Nexa selected playback capture")
            .ok_or("PulseAudio context is unavailable")?;
        context
            .connect(None, pulse::context::FlagSet::NOAUTOSPAWN, None)
            .map_err(|e| e.to_string())?;
        let watch = Rc::new(RefCell::new(Watch::default()));
        let mut this = Self {
            context,
            mainloop,
            watch: watch.clone(),
            cancel: None,
        };
        let deadline = Instant::now() + QUERY_TIMEOUT;
        while this.context.get_state() != pulse::context::State::Ready {
            this.pump_connecting()?;
            if Instant::now() >= deadline {
                return Err("PulseAudio connection timed out".into());
            }
            thread::sleep(POLL);
        }
        if this.context.is_local() != Some(true) {
            return Err("playback capture requires a local audio server".into());
        }
        this.context
            .set_subscribe_callback(Some(Box::new(move |facility, _, index| {
                let mut watch = watch.borrow_mut();
                watch.revision = watch.revision.wrapping_add(1);
                watch.selections.retain(|_, selected| {
                    !Scope {
                        input: selected.input.index,
                        client: selected.input.client,
                        sink: selected.input.sink,
                        monitor: u32::MAX,
                    }
                    .affected(facility, index)
                });
                if watch
                    .active
                    .is_some_and(|scope| scope.affected(facility, index))
                {
                    watch.invalidated = true;
                }
            })));
        let subscribed = Rc::new(Cell::new(false));
        let accepted = subscribed.clone();
        let op = this.context.subscribe(
            InterestMaskSet::SINK_INPUT
                | InterestMaskSet::CLIENT
                | InterestMaskSet::SINK
                | InterestMaskSet::SOURCE
                | InterestMaskSet::SERVER,
            move |ok| accepted.set(ok),
        );
        this.wait(op)?;
        if !subscribed.get() {
            return Err("audio producer lifecycle subscription was denied".into());
        }
        Ok(this)
    }

    fn pump_connecting(&mut self) -> Result<(), String> {
        if self
            .cancel
            .as_ref()
            .is_some_and(|s| s.load(Ordering::Acquire))
        {
            return Err("capture stopped".into());
        }
        match self.mainloop.iterate(false) {
            IterateResult::Success(_) => {}
            _ => return Err("PulseAudio mainloop failed".into()),
        }
        match self.context.get_state() {
            pulse::context::State::Failed | pulse::context::State::Terminated => {
                Err("audio server disconnected".into())
            }
            _ => Ok(()),
        }
    }

    fn pump(&mut self) -> Result<(), String> {
        self.pump_connecting()?;
        if self.context.get_state() != pulse::context::State::Ready {
            return Err("audio server is not ready".into());
        }
        Ok(())
    }

    fn wait<T: ?Sized>(&mut self, mut op: Operation<T>) -> Result<(), String> {
        let deadline = Instant::now() + QUERY_TIMEOUT;
        while op.get_state() == pulse::operation::State::Running {
            if let Err(error) = self.pump() {
                op.cancel();
                return Err(error);
            }
            if Instant::now() >= deadline {
                op.cancel();
                return Err("audio server query timed out".into());
            }
            thread::sleep(POLL);
        }
        if op.get_state() != pulse::operation::State::Done {
            return Err("audio server query cancelled".into());
        }
        Ok(())
    }

    fn inputs(&mut self, index: Option<u32>) -> Result<Vec<Input>, String> {
        let result = Rc::new(RefCell::new(Vec::new()));
        let output = result.clone();
        let failed = Rc::new(Cell::new(false));
        let error = failed.clone();
        let callback = move |item: ListResult<&SinkInputInfo<'_>>| match item {
            ListResult::Item(info) => {
                if output.borrow().len() >= MAX_PRODUCERS {
                    error.set(true);
                } else if let Some(input) = input(info) {
                    output.borrow_mut().push(input);
                }
            }
            ListResult::Error => error.set(true),
            ListResult::End => {}
        };
        let op = match index {
            Some(index) => self
                .context
                .introspect()
                .get_sink_input_info(index, callback),
            None => self.context.introspect().get_sink_input_info_list(callback),
        };
        self.wait(op)?;
        if failed.get() {
            return Err("playback producer enumeration failed or exceeded its limit".into());
        }
        let values = result.borrow().clone();
        Ok(values)
    }

    fn identity(&mut self, input: &Input) -> Result<ProcessIdentity, String> {
        let result = Rc::new(RefCell::new(None));
        let output = result.clone();
        let op = self
            .context
            .introspect()
            .get_client_info(input.client, move |item| {
                if let ListResult::Item(client) = item {
                    // A module or server-owned producer is not an original app.
                    if client.owner_module.is_none() {
                        *output.borrow_mut() = property_pid(&client.proplist);
                    }
                }
            });
        self.wait(op)?;
        let pid = result
            .borrow()
            .ok_or("playback client has no verifiable process")?;
        if input.pid.is_some_and(|p| p != pid) {
            return Err("playback producer and client process disagree".into());
        }
        process_identity(pid)
    }

    fn list(&mut self) -> Result<Vec<SoundSource>, String> {
        self.pump()?;
        self.watch.borrow_mut().selections.clear();
        let mut sources = Vec::new();
        for input in self.inputs(None)? {
            let revision = self.watch.borrow().revision;
            let process = match self.identity(&input) {
                Ok(p) => p,
                Err(_) => continue,
            };
            let selected = Selection {
                lifetime: uuid::Uuid::new_v4().to_string(),
                input,
                process,
            };
            let id = serde_json::to_string(&selected).map_err(|e| e.to_string())?;
            if self.watch.borrow().revision != revision {
                continue;
            }
            let name = if selected.input.application.is_empty() {
                selected.input.name.clone()
            } else if selected.input.name.is_empty()
                || selected.input.name == selected.input.application
            {
                selected.input.application.clone()
            } else {
                format!("{}: {}", selected.input.application, selected.input.name)
            };
            if name.is_empty() {
                continue;
            }
            self.watch
                .borrow_mut()
                .selections
                .insert(id.clone(), selected);
            sources.push(SoundSource { id, name });
        }
        Ok(sources)
    }

    fn capture(
        &mut self,
        id: &str,
        sinks: &Sinks,
        stop: &AtomicBool,
        enabled: &AtomicBool,
        initialized: &SyncSender<Result<(), String>>,
    ) -> Result<(), String> {
        self.pump()?;
        let selected = self
            .watch
            .borrow()
            .selections
            .get(id)
            .cloned()
            .ok_or("selected playback producer expired; select it again")?;
        let current = self
            .inputs(Some(selected.input.index))?
            .into_iter()
            .next()
            .ok_or("selected playback producer disappeared")?;
        if current != selected.input || self.identity(&current)? != selected.process {
            return Err("selected playback producer identity changed".into());
        }
        let monitor = Rc::new(RefCell::new(None));
        let output = monitor.clone();
        let op = self
            .context
            .introspect()
            .get_sink_info_by_index(current.sink, move |item| {
                if let ListResult::Item(sink) = item {
                    if let Some(name) = &sink.monitor_source_name {
                        *output.borrow_mut() = Some((sink.monitor_source, name.to_string()));
                    }
                }
            });
        self.wait(op)?;
        let (monitor_index, monitor_name) = monitor
            .borrow()
            .clone()
            .ok_or("selected producer has no output monitor")?;
        let verified = Rc::new(Cell::new(false));
        let output = verified.clone();
        let sink_index = current.sink;
        let expected_name = monitor_name.clone();
        let op = self
            .context
            .introspect()
            .get_source_info_by_index(monitor_index, move |item| {
                if let ListResult::Item(source) = item {
                    output.set(
                        source.monitor_of_sink == Some(sink_index)
                            && source.name.as_deref() == Some(expected_name.as_str()),
                    );
                }
            });
        self.wait(op)?;
        if !verified.get() {
            return Err("selected output monitor could not be verified".into());
        }
        {
            let mut watch = self.watch.borrow_mut();
            if !watch.selections.contains_key(id) {
                return Err("selected playback scope changed during initialization".into());
            }
            watch.active = Some(Scope {
                input: current.index,
                client: current.client,
                sink: current.sink,
                monitor: monitor_index,
            });
            watch.invalidated = false;
        }
        let spec = sample::Spec {
            format: sample::Format::S16le,
            channels: 2,
            rate: 48_000,
        };
        let mut map = pulse::channelmap::Map::default();
        map.init_stereo();
        let mut tap = Tap(Stream::new(
            &mut self.context,
            "Nexa selected producer",
            &spec,
            Some(&map),
        )
        .ok_or("audio monitor stream is unavailable")?);
        let overflow = Rc::new(Cell::new(false));
        let output = overflow.clone();
        tap.0
            .set_overflow_callback(Some(Box::new(move || output.set(true))));
        // Binding this BEFORE connect_record is mandatory. Some(monitor_name)
        // alone would capture every app, including Nexa, on that output.
        tap.0
            .set_monitor_stream(current.index)
            .map_err(|e| e.to_string())?;
        let attr = pulse::def::BufferAttr {
            maxlength: MAX_AUDIO_BYTES as u32,
            fragsize: 1920,
            tlength: u32::MAX,
            prebuf: u32::MAX,
            minreq: u32::MAX,
        };
        tap.0
            .connect_record(
                Some(&monitor_name),
                Some(&attr),
                pulse::stream::FlagSet::DONT_MOVE | pulse::stream::FlagSet::ADJUST_LATENCY,
            )
            .map_err(|e| format!("selected producer tap failed: {e}"))?;
        let deadline = Instant::now() + QUERY_TIMEOUT;
        while tap.0.get_state() != pulse::stream::State::Ready {
            self.pump()?;
            self.scope_valid()?;
            if matches!(
                tap.0.get_state(),
                pulse::stream::State::Failed | pulse::stream::State::Terminated
            ) {
                return Err("selected producer tap was refused".into());
            }
            if Instant::now() >= deadline {
                return Err("selected producer tap timed out".into());
            }
            thread::sleep(POLL);
        }
        if tap.0.get_monitor_stream() != Some(current.index)
            || tap.0.get_device_index() != Some(monitor_index)
            || !tap.0.get_sample_spec().is_some_and(|s| {
                s.format == spec.format && s.rate == spec.rate && s.channels == spec.channels
            })
        {
            return Err("audio server did not honor the selected producer or PCM format".into());
        }
        self.scope_valid()?;
        initialized
            .send(Ok(()))
            .map_err(|_| "capture initialization was cancelled")?;
        let mut checked = Instant::now();
        while !stop.load(Ordering::Acquire) {
            self.pump()?;
            self.scope_valid()?;
            if tap.0.get_state() != pulse::stream::State::Ready || overflow.get() {
                return Err("selected producer tap ended or overflowed".into());
            }
            if checked.elapsed() >= Duration::from_millis(100) {
                if process_identity(selected.process.pid)? != selected.process {
                    return Err("playback producer process lifetime changed".into());
                }
                checked = Instant::now();
            }
            // Bound both one packet and the amount drained per iteration. No
            // unbounded queue, realtime callback allocation or blocking send.
            for _ in 0..10 {
                if stop.load(Ordering::Acquire) {
                    break;
                }
                let pcm = match tap.0.peek().map_err(|e| e.to_string())? {
                    PeekResult::Empty => break,
                    PeekResult::Hole(size) if size <= MAX_AUDIO_BYTES && size % 4 == 0 => {
                        vec![0; size / 2]
                    }
                    PeekResult::Data(bytes) if bytes.len() <= MAX_AUDIO_BYTES => decode_pcm(bytes)?,
                    _ => return Err("invalid or oversized producer audio packet".into()),
                };
                tap.0.discard().map_err(|e| e.to_string())?;
                if enabled.load(Ordering::Acquire) {
                    for packet in pcm.chunks(960) {
                        sinks.audio(packet)?;
                    }
                }
            }
            thread::sleep(POLL);
        }
        Ok(())
    }

    fn scope_valid(&self) -> Result<(), String> {
        if self.watch.borrow().invalidated {
            // Do not replay buffered audio across a move/replacement. Fail
            // closed instead of silently following a new monitor or producer.
            Err("selected audio producer or output changed; select it again".into())
        } else {
            Ok(())
        }
    }
}

struct Tap(Stream);
impl Drop for Tap {
    fn drop(&mut self) {
        self.0.set_overflow_callback(None);
        let _ = self.0.disconnect();
    }
}

fn decode_pcm(bytes: &[u8]) -> Result<Vec<i16>, String> {
    if bytes.len() % 4 != 0 {
        return Err("producer audio is not complete stereo PCM16".into());
    }
    Ok(bytes
        .chunks_exact(2)
        .map(|b| i16::from_le_bytes([b[0], b[1]]))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn proc_identity_parses_names_containing_spaces_and_parentheses() {
        let stat = "42 (a ) tricky name) S 7 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 12345 0";
        assert_eq!(parse_stat(stat).unwrap(), (7, 12345));
        assert!(parse_stat("42 (bad) S 7").is_err());
    }

    #[test]
    fn producer_events_invalidate_only_the_selected_scope() {
        let scope = Scope {
            input: 3,
            client: 8,
            sink: 2,
            monitor: 9,
        };
        assert!(scope.affected(Some(Facility::SinkInput), 3));
        assert!(scope.affected(Some(Facility::Client), 8));
        assert!(scope.affected(Some(Facility::Sink), 2));
        assert!(scope.affected(Some(Facility::Source), 9));
        assert!(scope.affected(Some(Facility::Server), 0));
        assert!(scope.affected(None, 0));
        assert!(!scope.affected(Some(Facility::SinkInput), 4));
        assert!(!scope.affected(Some(Facility::SourceOutput), 3));
    }

    #[test]
    fn pcm_conversion_preserves_stereo_and_rejects_partial_frames() {
        assert_eq!(
            decode_pcm(&[1, 0, 255, 255, 0, 128, 255, 127]).unwrap(),
            vec![1, -1, -32768, 32767]
        );
        assert!(decode_pcm(&[1, 0]).is_err());
        assert!(decode_pcm(&[1]).is_err());
    }
}
