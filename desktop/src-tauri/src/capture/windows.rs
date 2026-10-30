//! Windows native video and explicitly selected application-tree audio.
//!
//! No endpoint-mix fallback. A window's owner is not necessarily its audio
//! renderer; sound is a separate, explicitly selected application, not a window.
use super::{CaptureConfig, Sinks, SoundSource, Source};
use libwebrtc::desktop_capturer::{
    CaptureError, DesktopCaptureSourceType, DesktopCapturer, DesktopCapturerOptions,
};
use std::{
    collections::{HashMap, HashSet},
    mem::{size_of, ManuallyDrop},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};
use wasapi::{AudioClient, Direction, SampleType, StreamMode, WaveFormat};
use windows::{
    core::{implement, Interface, Ref, HRESULT, PWSTR},
    Win32::{
        Foundation::{CloseHandle, FILETIME, HANDLE, HWND, WAIT_OBJECT_0, WAIT_TIMEOUT},
        Media::Audio::{
            ActivateAudioInterfaceAsync, IActivateAudioInterfaceAsyncOperation,
            IActivateAudioInterfaceCompletionHandler,
            IActivateAudioInterfaceCompletionHandler_Impl, IAudioCaptureClient, IAudioClient,
            AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY, AUDCLNT_BUFFERFLAGS_SILENT,
            AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR, AUDIOCLIENT_ACTIVATION_PARAMS,
            AUDIOCLIENT_ACTIVATION_PARAMS_0, AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK,
            AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS, PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE,
            VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK,
        },
        System::{
            Com::{
                StructuredStorage::{
                    PROPVARIANT, PROPVARIANT_0, PROPVARIANT_0_0, PROPVARIANT_0_0_0,
                },
                BLOB,
            },
            Diagnostics::ToolHelp::{
                CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
                TH32CS_SNAPPROCESS,
            },
            Threading::{
                GetProcessTimes, OpenProcess, QueryFullProcessImageNameW, WaitForSingleObject,
                PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_SYNCHRONIZE,
            },
            Variant::VT_BLOB,
        },
        UI::WindowsAndMessaging::{GetWindowThreadProcessId, IsWindow},
    },
};

fn error(e: impl std::fmt::Display) -> String {
    e.to_string()
}

struct OwnedHandle(HANDLE);
impl Drop for OwnedHandle {
    fn drop(&mut self) {
        // SAFETY: this handle is owned, and never closed anywhere else.
        let _ = unsafe { CloseHandle(self.0) };
    }
}

struct Com;
impl Com {
    fn new() -> Result<Self, String> {
        wasapi::initialize_mta().ok().map_err(error)?;
        Ok(Self)
    }
}
impl Drop for Com {
    fn drop(&mut self) {
        wasapi::deinitialize();
    }
}

struct Process {
    handle: OwnedHandle,
    pid: u32,
    created: u64,
    image: String,
}
impl Process {
    fn open(pid: u32) -> Result<Self, String> {
        // SAFETY: requests only identity/lifetime access, not process memory.
        let handle = OwnedHandle(
            unsafe {
                OpenProcess(
                    PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
                    false,
                    pid,
                )
            }
            .map_err(error)?,
        );
        let mut created = FILETIME::default();
        let mut exited = FILETIME::default();
        let mut kernel = FILETIME::default();
        let mut user = FILETIME::default();
        let mut image = vec![0u16; 32768];
        let mut len = image.len() as u32;
        // SAFETY: all output buffers are valid and the handle remains owned.
        unsafe {
            GetProcessTimes(handle.0, &mut created, &mut exited, &mut kernel, &mut user)
                .map_err(error)?;
            QueryFullProcessImageNameW(
                handle.0,
                PROCESS_NAME_WIN32,
                PWSTR(image.as_mut_ptr()),
                &mut len,
            )
            .map_err(error)?;
        }
        let process = Self {
            handle,
            pid,
            created: ((created.dwHighDateTime as u64) << 32) | created.dwLowDateTime as u64,
            image: String::from_utf16_lossy(&image[..len as usize]),
        };
        process.alive()?;
        Ok(process)
    }

    fn alive(&self) -> Result<(), String> {
        // SAFETY: borrowed process handle, zero timeout.
        match unsafe { WaitForSingleObject(self.handle.0, 0) } {
            WAIT_TIMEOUT => Ok(()),
            WAIT_OBJECT_0 => Err("The selected application closed".into()),
            _ => Err("Could not check the selected application's lifetime".into()),
        }
    }

    fn id(&self) -> String {
        format!("app:{}:{}", self.pid, self.created)
    }
}

struct ProcessEntry {
    parent: u32,
    executable: String,
}

fn process_tree() -> Result<HashMap<u32, ProcessEntry>, String> {
    // SAFETY: process snapshot only; the owned handle closes on every path.
    let snapshot =
        OwnedHandle(unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) }.map_err(error)?);
    let mut entry = PROCESSENTRY32W {
        dwSize: size_of::<PROCESSENTRY32W>() as u32,
        ..Default::default()
    };
    let mut entries = HashMap::new();
    unsafe { Process32FirstW(snapshot.0, &mut entry) }.map_err(error)?;
    loop {
        let end = entry
            .szExeFile
            .iter()
            .position(|c| *c == 0)
            .unwrap_or(entry.szExeFile.len());
        entries.insert(
            entry.th32ProcessID,
            ProcessEntry {
                parent: entry.th32ParentProcessID,
                executable: String::from_utf16_lossy(&entry.szExeFile[..end]).to_lowercase(),
            },
        );
        match unsafe { Process32NextW(snapshot.0, &mut entry) } {
            Ok(()) => (),
            Err(e) if e.code() == HRESULT::from_win32(18) => break, // ERROR_NO_MORE_FILES
            Err(e) => return Err(error(e)),
        }
    }
    Ok(entries)
}

fn descends_from(mut pid: u32, root: u32, tree: &HashMap<u32, ProcessEntry>) -> bool {
    let mut seen = HashSet::new();
    while pid != 0 && seen.insert(pid) {
        if pid == root {
            return true;
        }
        pid = match tree.get(&pid) {
            Some(entry) => entry.parent,
            None => break,
        };
    }
    false
}

fn external(pid: u32, tree: &HashMap<u32, ProcessEntry>) -> bool {
    let own = std::process::id();
    // Reject both descendants and ancestors: capturing an ancestor's tree can
    // include Nexa. Never promote a window PID to a launcher/browser ancestor.
    if pid == 0
        || !tree.contains_key(&pid)
        || descends_from(pid, own, tree)
        || descends_from(own, pid, tree)
    {
        return false;
    }
    let own_exe = std::env::current_exe()
        .ok()
        .and_then(|p| p.file_name().map(|s| s.to_string_lossy().to_lowercase()));
    // Conservatively reject ALL WebView2 trees, not just known Nexa helpers.
    // Snapshot ancestry is best effort, not proof of complete helper exclusion.
    !tree.iter().any(|(&child, entry)| {
        (entry.executable == "msedgewebview2.exe"
            || own_exe.as_deref() == Some(entry.executable.as_str()))
            && descends_from(child, pid, tree)
    })
}

fn capturer(kind: DesktopCaptureSourceType) -> Result<DesktopCapturer, String> {
    let mut options = DesktopCapturerOptions::new(kind);
    options.set_include_cursor(true);
    DesktopCapturer::new(options).ok_or_else(|| "Native video capture is unavailable".into())
}

fn window_process(id: u64) -> Result<Process, String> {
    let window = HWND(id as usize as *mut std::ffi::c_void);
    let mut pid = 0;
    // SAFETY: Windows validates the opaque HWND before returning its owner.
    if !unsafe { IsWindow(Some(window)) }.as_bool()
        || unsafe { GetWindowThreadProcessId(window, Some(&mut pid)) } == 0
    {
        return Err("The selected window closed".into());
    }
    Process::open(pid)
}

pub fn sources() -> Result<Vec<Source>, String> {
    let _com = Com::new()?;
    let mut result = Vec::new();
    for (kind, label) in [
        (DesktopCaptureSourceType::Screen, "screen"),
        (DesktopCaptureSourceType::Window, "window"),
    ] {
        let capture = capturer(kind)?;
        for source in capture.get_source_list() {
            let id = if kind == DesktopCaptureSourceType::Window {
                let Ok(process) = window_process(source.id()) else {
                    continue;
                };
                format!("window:{}:{}:{}", source.id(), process.pid, process.created)
            } else {
                format!("screen:{}", source.id())
            };
            let title = source.title();
            result.push(Source {
                id,
                name: if title.is_empty() {
                    format!("Screen {}", result.len() + 1)
                } else {
                    title
                },
                kind: label.into(),
                // Enumeration does not start capture or retain pixel buffers.
                thumbnail: String::new(),
            });
        }
    }
    Ok(result)
}

pub fn sounds() -> Result<Vec<SoundSource>, String> {
    let _com = Com::new()?;
    let tree = process_tree()?;
    let capture = capturer(DesktopCaptureSourceType::Window)?;
    let mut seen = HashSet::new();
    let mut result = Vec::new();
    for source in capture.get_source_list() {
        let Ok(process) = window_process(source.id()) else {
            continue;
        };
        if !external(process.pid, &tree) || !seen.insert(process.pid) {
            continue;
        }
        let executable = process.image.rsplit('\\').next().unwrap_or(&process.image);
        result.push(SoundSource {
            id: process.id(),
            name: format!("{} ({executable}, PID {})", source.title(), process.pid),
        });
    }
    result.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(result)
}

// wasapi's convenience process-loopback constructor waits on a Condvar without
// a timeout. Use the same activation parameters with a cancellable wait, then
// its public from_iaudioclient/initialize_client APIs for stream setup.
#[implement(IActivateAudioInterfaceCompletionHandler)]
struct Activation {
    done: Arc<AtomicBool>,
    // Keep the blob alive even if the caller cancels before Windows completes.
    _params: Box<AUDIOCLIENT_ACTIVATION_PARAMS>,
}
impl IActivateAudioInterfaceCompletionHandler_Impl for Activation_Impl {
    fn ActivateCompleted(
        &self,
        _: Ref<IActivateAudioInterfaceAsyncOperation>,
    ) -> windows::core::Result<()> {
        self.done.store(true, Ordering::Release);
        Ok(())
    }
}

fn activate(pid: u32, stop: &AtomicBool) -> Result<AudioClient, String> {
    let mut params = Box::new(AUDIOCLIENT_ACTIVATION_PARAMS {
        ActivationType: AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK,
        Anonymous: AUDIOCLIENT_ACTIVATION_PARAMS_0 {
            ProcessLoopbackParams: AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS {
                TargetProcessId: pid,
                ProcessLoopbackMode: PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE,
            },
        },
    });
    // This borrowed BLOB is not CoTaskMem-owned: never PropVariantClear it.
    let property = PROPVARIANT {
        Anonymous: PROPVARIANT_0 {
            Anonymous: ManuallyDrop::new(PROPVARIANT_0_0 {
                vt: VT_BLOB,
                Anonymous: PROPVARIANT_0_0_0 {
                    blob: BLOB {
                        cbSize: size_of::<AUDIOCLIENT_ACTIVATION_PARAMS>() as u32,
                        pBlobData: (&mut *params as *mut AUDIOCLIENT_ACTIVATION_PARAMS).cast(),
                    },
                },
                ..Default::default()
            }),
        },
    };
    let done = Arc::new(AtomicBool::new(false));
    let callback: IActivateAudioInterfaceCompletionHandler = Activation {
        done: done.clone(),
        _params: params,
    }
    .into();
    // SAFETY: the property is valid throughout the activation call, and its
    // blob stays owned by the completion handler until Windows releases it.
    let operation = unsafe {
        ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, &IAudioClient::IID,
            Some(&property), &callback)
    }.map_err(|e| format!("Application audio activation failed (requires supported Windows process loopback): {e}"))?;
    let deadline = Instant::now() + Duration::from_secs(10);
    while !done.load(Ordering::Acquire) {
        if stop.load(Ordering::Acquire) {
            return Err("Capture cancelled".into());
        }
        if Instant::now() >= deadline {
            return Err("Application audio activation timed out".into());
        }
        thread::sleep(Duration::from_millis(5));
    }
    let mut status = HRESULT(0);
    let mut interface = None;
    unsafe { operation.GetActivateResult(&mut status, &mut interface) }.map_err(error)?;
    status.ok().map_err(error)?;
    let raw: IAudioClient = interface
        .ok_or("Application audio returned no client")?
        .cast()
        .map_err(error)?;
    Ok(AudioClient::from_iaudioclient(raw, Direction::Render))
}

struct Audio {
    client: AudioClient,
    reader: IAudioCaptureClient,
    _event: wasapi::Handle,
    process: Process,
    running: bool,
}
impl Audio {
    fn start(id: &str, stop: &AtomicBool) -> Result<Self, String> {
        let fields: Vec<_> = id.split(':').collect();
        if fields.len() != 3 || fields[0] != "app" {
            return Err("Invalid application audio selection".into());
        }
        let process = Process::open(fields[1].parse().map_err(error)?)?;
        if process.id() != id || !external(process.pid, &process_tree()?) {
            return Err(
                "The selected sound application is unavailable or unsafe to capture".into(),
            );
        }
        let mut client = activate(process.pid, stop)?;
        process.alive()?;
        let format = WaveFormat::new(32, 32, &SampleType::Float, 48_000, 2, None);
        client
            .initialize_client(
                &format,
                &Direction::Capture,
                &StreamMode::EventsShared {
                    autoconvert: true,
                    buffer_duration_hns: 0,
                },
            )
            .map_err(error)?;
        let event = client.set_get_eventhandle().map_err(error)?;
        let reader = unsafe { client.as_iaudioclient().GetService::<IAudioCaptureClient>() }
            .map_err(error)?;
        // Construct the guard before Start, so every later error stops the client.
        let mut audio = Self {
            client,
            reader,
            _event: event,
            process,
            running: false,
        };
        audio.client.start_stream().map_err(error)?;
        audio.running = true;
        Ok(audio)
    }

    fn stop(&mut self) -> Result<(), String> {
        if self.running {
            self.client.stop_stream().map_err(error)?;
            self.running = false;
        }
        Ok(())
    }

    fn drain(&self, sinks: &Sinks, stop: &AtomicBool) -> Result<(), String> {
        // Polling a bounded number of packets prevents capture from starving stop
        // or video. Do not trust process-loopback GetBufferSize's huge value.
        for _ in 0..32 {
            if stop.load(Ordering::Acquire) {
                break;
            }
            let next = unsafe { self.reader.GetNextPacketSize() }.map_err(error)?;
            if next == 0 {
                break;
            }
            let mut data = std::ptr::null_mut();
            let (mut frames, mut flags, mut position, mut qpc) = (0, 0, 0, 0);
            unsafe {
                self.reader.GetBuffer(
                    &mut data,
                    &mut frames,
                    &mut flags,
                    Some(&mut position),
                    Some(&mut qpc),
                )
            }
            .map_err(error)?;
            // Always release, including invalid/oversized packets and sink errors.
            let copied: Result<Vec<i16>, String> = (|| {
                if frames > 48_000 {
                    return Err("Application audio packet exceeded one second".into());
                }
                let mut pcm = vec![0i16; frames as usize * 2];
                if flags & AUDCLNT_BUFFERFLAGS_SILENT.0 as u32 == 0 && frames != 0 {
                    if data.is_null() {
                        return Err("Application audio returned a null non-silent buffer".into());
                    }
                    // SAFETY: successful GetBuffer owns frames of the negotiated
                    // stereo float format until ReleaseBuffer. Read unaligned.
                    for (i, sample) in pcm.iter_mut().enumerate() {
                        let value = unsafe { (data.add(i * 4) as *const f32).read_unaligned() };
                        *sample = if value.is_finite() {
                            (value.clamp(-1.0, 1.0) * 32768.0)
                                .round()
                                .clamp(i16::MIN as f32, i16::MAX as f32)
                                as i16
                        } else {
                            0
                        };
                    }
                }
                if flags
                    & (AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY.0
                        | AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR.0) as u32
                    != 0
                {
                    log::debug!("Application audio flags={flags:#x} position={position} qpc={qpc}");
                }
                Ok(pcm)
            })();
            let released = unsafe { self.reader.ReleaseBuffer(frames) }.map_err(error);
            let pcm = copied?;
            released?;
            // Keep each queue item within the shared sink's 100 ms limit.
            for chunk in pcm.chunks(9600) {
                if stop.load(Ordering::Acquire) {
                    return Ok(());
                }
                sinks.audio(chunk)?;
            }
        }
        Ok(())
    }
}
impl Drop for Audio {
    fn drop(&mut self) {
        if let Err(e) = self.stop() {
            log::debug!("Stopping application audio: {e}");
        }
    }
}

pub fn run(config: CaptureConfig, sinks: Sinks, stop: Arc<AtomicBool>) -> Result<(), String> {
    let _com = Com::new()?;
    if config.width == 0
        || config.height == 0
        || config.fps == 0
        || config.width > 7680
        || config.height > 4320
        || config.fps > 120
    {
        return Err("Invalid native capture dimensions or frame rate".into());
    }
    if stop.load(Ordering::Acquire) {
        return Ok(());
    }
    let fields: Vec<_> = config.source_id.split(':').collect();
    let kind = match fields.first().copied() {
        Some("screen") if fields.len() == 2 => DesktopCaptureSourceType::Screen,
        Some("window") if fields.len() == 4 => DesktopCaptureSourceType::Window,
        _ => return Err("Invalid native video source".into()),
    };
    let source_id: u64 = fields[1].parse().map_err(error)?;
    let window = if kind == DesktopCaptureSourceType::Window {
        let process = window_process(source_id)?;
        if config.source_id != format!("window:{source_id}:{}:{}", process.pid, process.created) {
            return Err("The selected window was replaced".into());
        }
        Some(process)
    } else {
        None
    };
    let mut capture = capturer(kind)?;
    let source = capture
        .get_source_list()
        .into_iter()
        .find(|s| s.id() == source_id)
        .ok_or("The selected video source is no longer available")?;
    // Complete all requested audio setup before video can announce readiness.
    let mut audio = config
        .sound_id
        .as_deref()
        .map(|id| Audio::start(id, &stop))
        .transpose()?;
    if stop.load(Ordering::Acquire) {
        return Ok(());
    }
    let failure = Arc::new(Mutex::new(None::<String>));
    let last_frame = Arc::new(Mutex::new(None::<Instant>));
    let callback_failure = failure.clone();
    let callback_frame = last_frame.clone();
    let video_sinks = sinks.clone();
    let callback_stop = stop.clone();
    let (max_width, max_height) = (config.width, config.height);
    capture.start_capture(Some(source), move |result| {
        if callback_stop.load(Ordering::Acquire) {
            return;
        }
        match result {
            Err(CaptureError::Temporary) => (),
            Err(CaptureError::Permanent) => {
                *callback_failure.lock().unwrap() = Some("Native video capture ended".into());
            }
            Ok(frame) => {
                let (width, height, stride) = (frame.width(), frame.height(), frame.stride());
                if width <= 0
                    || height <= 0
                    || width > 32768
                    || height > 32768
                    || u64::from(stride) < width as u64 * 4
                    || u64::from(stride) * height as u64 > frame.data().len() as u64
                {
                    *callback_failure.lock().unwrap() = Some("Invalid native video frame".into());
                    return;
                }
                let (width, height) = (width as u32, height as u32);
                let scale = (max_width as f64 / width as f64)
                    .min(max_height as f64 / height as f64)
                    .min(1.0);
                let (out_w, out_h) = (
                    (width as f64 * scale) as u32,
                    (height as f64 * scale) as u32,
                );
                let (out_w, out_h) = (out_w.max(1), out_h.max(1));
                if out_w == width && out_h == height {
                    video_sinks.video(frame.data(), width, height, stride);
                } else {
                    let mut pixels = vec![0u8; out_w as usize * out_h as usize * 4];
                    for y in 0..out_h {
                        for x in 0..out_w {
                            let input = (y as u64 * height as u64 / out_h as u64) as usize
                                * stride as usize
                                + (x as u64 * width as u64 / out_w as u64) as usize * 4;
                            let output = (y as usize * out_w as usize + x as usize) * 4;
                            pixels[output..output + 4]
                                .copy_from_slice(&frame.data()[input..input + 4]);
                        }
                    }
                    video_sinks.video(&pixels, out_w, out_h, out_w * 4);
                }
                *callback_frame.lock().unwrap() = Some(Instant::now());
            }
        }
    });
    let started = Instant::now();
    let mut next_frame = started;
    let mut check_identity = started;
    let mut ready = false;
    let period = Duration::from_secs_f64(1.0 / config.fps as f64);
    // All COM, capturer and audio objects remain on this worker thread. Drop
    // capture first (including callbacks), then audio, then the COM apartment.
    let result = (|| {
        while !stop.load(Ordering::Acquire) {
            let now = Instant::now();
            if now >= check_identity {
                if let Some(process) = &window {
                    process.alive()?;
                    let current = window_process(source_id)?;
                    if current.pid != process.pid || current.created != process.created {
                        return Err("The selected window was replaced".into());
                    }
                } else if !capture
                    .get_source_list()
                    .iter()
                    .any(|s| s.id() == source_id)
                {
                    return Err("The selected screen disconnected".into());
                }
                if let Some(audio) = &audio {
                    audio.process.alive()?;
                    if !external(audio.process.pid, &process_tree()?) {
                        return Err(
                            "The selected application tree is no longer safe to capture".into()
                        );
                    }
                }
                check_identity = now + Duration::from_millis(250);
            }
            if now >= next_frame {
                capture.capture_frame();
                next_frame = Instant::now() + period;
            }
            if let Some(e) = failure.lock().unwrap().take() {
                return Err(e);
            }
            let last = *last_frame.lock().unwrap();
            if !ready && last.is_some() {
                sinks.ready()?;
                ready = true;
            }
            if now.saturating_duration_since(last.unwrap_or(started)) > Duration::from_secs(10) {
                return Err("Native video capture stopped delivering frames".into());
            }
            if let Some(audio) = &audio {
                audio.drain(&sinks, &stop)?;
            }
            thread::sleep(Duration::from_millis(5));
        }
        Ok(())
    })();
    drop(capture);
    let stopped = audio.as_mut().map(Audio::stop).transpose();
    drop(audio);
    match (result, stopped) {
        (Err(primary), Err(cleanup)) => Err(format!("{primary}; audio shutdown: {cleanup}")),
        (Err(primary), _) => Err(primary),
        (Ok(()), Err(cleanup)) => Err(cleanup),
        (Ok(()), Ok(_)) => Ok(()),
    }
}
