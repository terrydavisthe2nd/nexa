//! ScreenCaptureKit video and explicitly approved application audio.
//! No whole-system audio or microphone fallback is permitted here. Audio belongs
//! to the selected application, not just one of its windows.

use super::{CaptureConfig, Sinks, SoundSource, Source};
use screencapturekit::cm::SCFrameStatus;
use screencapturekit::cv::CVPixelBufferLockFlags;
use screencapturekit::prelude::*;
use std::ffi::{c_char, c_void, CStr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

// These are stable Darwin libproc/CoreMedia C ABIs. Keeping these small FFI
// declarations here avoids adding a second Objective-C or audio binding crate.
#[repr(C)]
struct BsdInfo {
    header: [u32; 12],
    command: [u8; 16],
    name: [u8; 32],
    details: [u32; 6],
    start_seconds: u64,
    start_microseconds: u64,
}

#[link(name = "proc")]
extern "C" {
    fn proc_pidinfo(pid: i32, flavor: i32, arg: u64, buffer: *mut c_void, size: i32) -> i32;
    fn proc_pidpath(pid: i32, buffer: *mut c_void, size: u32) -> i32;
}

extern "C" {
    fn sysctlbyname(
        name: *const c_char,
        old: *mut c_void,
        old_len: *mut usize,
        new: *mut c_void,
        new_len: usize,
    ) -> i32;
}

#[repr(C)]
#[derive(Clone, Copy)]
struct AudioDescription {
    sample_rate: f64,
    format: u32,
    flags: u32,
    bytes_per_packet: u32,
    frames_per_packet: u32,
    bytes_per_frame: u32,
    channels: u32,
    bits: u32,
    reserved: u32,
}

#[link(name = "CoreMedia", kind = "framework")]
extern "C" {
    fn CMAudioFormatDescriptionGetStreamBasicDescription(
        description: *mut c_void,
    ) -> *const AudioDescription;
}

fn supported(audio: bool) -> Result<(), String> {
    let mut version = [0u8; 64];
    let mut len = version.len();
    // kern.osproductversion is available on every macOS version SCK supports.
    let result = unsafe {
        sysctlbyname(
            b"kern.osproductversion\0".as_ptr().cast(),
            version.as_mut_ptr().cast(),
            &mut len,
            std::ptr::null_mut(),
            0,
        )
    };
    if result != 0 || len == 0 || len > version.len() {
        return Err("Could not determine the macOS version".into());
    }
    let version = std::str::from_utf8(&version[..len])
        .map_err(|_| "Invalid macOS version")?
        .trim_end_matches('\0');
    let mut parts = version.split('.').map(|p| p.parse::<u32>().unwrap_or(0));
    let major = parts.next().unwrap_or(0);
    let minor = parts.next().unwrap_or(0);
    if audio && major < 13 {
        return Err("Application sound capture requires macOS 13 or newer".into());
    }
    if major < 12 || (major == 12 && minor < 3) {
        return Err("Native screen capture requires macOS 12.3 or newer".into());
    }
    Ok(())
}

#[derive(Clone, PartialEq, Eq)]
struct AppIdentity {
    pid: i32,
    bundle: String,
    started: (u64, u64),
    executable: PathBuf,
}

fn process(pid: i32) -> Option<((u64, u64), PathBuf)> {
    if pid <= 0 {
        return None;
    }
    // PROC_PIDTBSDINFO, struct proc_bsdinfo, from Darwin <libproc.h>.
    let mut info = std::mem::MaybeUninit::<BsdInfo>::zeroed();
    let size = std::mem::size_of::<BsdInfo>() as i32;
    let read = unsafe { proc_pidinfo(pid, 3, 0, info.as_mut_ptr().cast(), size) };
    if read != size {
        return None;
    }
    let info = unsafe { info.assume_init() };
    let mut path = [0u8; 4096]; // PROC_PIDPATHINFO_MAXSIZE
    let read = unsafe { proc_pidpath(pid, path.as_mut_ptr().cast(), path.len() as u32) };
    if read <= 0 || !path.contains(&0) {
        return None;
    }
    let path = unsafe { CStr::from_ptr(path.as_ptr().cast()) };
    let executable = PathBuf::from(path.to_str().ok()?);
    Some(((info.start_seconds, info.start_microseconds), executable))
}

fn own_app(app: &SCRunningApplication) -> bool {
    let bundle = app.bundle_identifier();
    let ours = crate::settings::IDENTIFIER;
    if app.process_id() == std::process::id() as i32
        || bundle == ours
        || bundle.starts_with(&format!("{ours}."))
        // Shared WebKit helper attribution is not proof of an external app.
        || bundle.starts_with("com.apple.WebKit.")
    {
        return true;
    }
    let Ok(executable) = std::env::current_exe() else {
        return false;
    };
    let Some(root) = executable
        .ancestors()
        .find(|p| p.extension().is_some_and(|ext| ext == "app"))
    else {
        return process(app.process_id()).is_some_and(|(_, path)| path == executable);
    };
    process(app.process_id()).is_some_and(|(_, path)| path.starts_with(root))
}

fn identity(app: &SCRunningApplication) -> Option<AppIdentity> {
    if own_app(app) || app.bundle_identifier().is_empty() {
        return None;
    }
    let (started, executable) = process(app.process_id())?;
    Some(AppIdentity {
        pid: app.process_id(),
        bundle: app.bundle_identifier(),
        started,
        executable,
    })
}

impl AppIdentity {
    fn id(&self) -> String {
        format!(
            "app:{}:{}:{}:{}",
            self.pid, self.started.0, self.started.1, self.bundle
        )
    }

    fn alive(&self) -> bool {
        process(self.pid)
            .is_some_and(|(started, path)| started == self.started && path == self.executable)
    }
}

fn window_id(window: &SCWindow, app: &AppIdentity) -> String {
    format!("window:{}:{}", window.window_id(), app.id())
}

pub fn sources() -> Result<Vec<Source>, String> {
    supported(false)?;
    let content = SCShareableContent::get().map_err(|e| e.to_string())?;
    let mut sources = Vec::new();
    for (i, display) in content.displays().into_iter().enumerate() {
        sources.push(Source {
            id: format!("screen:{}", display.display_id()),
            name: format!(
                "Screen {} ({} × {})",
                i + 1,
                display.width(),
                display.height()
            ),
            kind: "screen".into(),
            thumbnail: String::new(),
        });
    }
    for window in content.windows() {
        if window.window_layer() != 0 || !window.is_on_screen() {
            continue;
        }
        let Some(app) = window.owning_application() else {
            continue;
        };
        let Some(identity) = identity(&app) else {
            continue;
        };
        sources.push(Source {
            id: window_id(&window, &identity),
            name: format!(
                "{}: {}",
                app.application_name(),
                window.title().unwrap_or_else(|| app.application_name())
            ),
            kind: "window".into(),
            thumbnail: String::new(),
        });
    }
    Ok(sources)
}

pub fn sounds() -> Result<Vec<SoundSource>, String> {
    supported(true)?;
    let content = SCShareableContent::get().map_err(|e| e.to_string())?;
    Ok(content
        .applications()
        .into_iter()
        .filter_map(|app| {
            let id = identity(&app)?.id();
            Some(SoundSource {
                id,
                name: app.application_name(),
            })
        })
        .collect())
}

#[derive(Clone, Default)]
struct Failure(Arc<Mutex<Option<String>>>);

impl Failure {
    fn set(&self, error: String) {
        if let Ok(mut slot) = self.0.lock() {
            if slot.is_none() {
                *slot = Some(error);
            }
        }
    }

    fn check(&self) -> Result<(), String> {
        match self.0.lock() {
            Ok(slot) => slot.clone().map_or(Ok(()), Err),
            Err(_) => Err("Capture error state was poisoned".into()),
        }
    }
}

// SCK can retain callbacks past stopCapture's completion. Closing this gate
// under its mutex waits out an in-flight sink call and prohibits later writes.
#[derive(Clone)]
struct Output {
    sinks: Sinks,
    active: Arc<Mutex<bool>>,
    failure: Failure,
}

impl Output {
    fn video(&self, sample: CMSampleBuffer) -> Result<(), String> {
        if sample.frame_status() == Some(SCFrameStatus::Stopped) {
            return Err("The shared video source stopped".into());
        }
        if !sample.frame_status().is_some_and(|s| s.has_content()) {
            return Ok(());
        }
        let pixel = sample.pixel_buffer().ok_or("Missing captured pixels")?;
        let guard = pixel
            .lock(CVPixelBufferLockFlags::READ_ONLY)
            .map_err(|e| format!("Could not lock captured pixels: {e:?}"))?;
        let width = u32::try_from(guard.width()).map_err(|_| "Invalid capture width")?;
        let height = u32::try_from(guard.height()).map_err(|_| "Invalid capture height")?;
        let stride = u32::try_from(guard.bytes_per_row()).map_err(|_| "Invalid capture stride")?;
        if width == 0 || height == 0 || stride < width.saturating_mul(4) {
            return Err("Invalid captured BGRA layout".into());
        }
        // Borrow only while the CVPixelBuffer is locked. Sinks consumes/copies
        // synchronously; no borrowed native memory is queued or retained here.
        let pixels = unsafe { guard.as_slice() }.ok_or("Missing captured pixel storage")?;
        let needed = (stride as usize)
            .checked_mul(height as usize)
            .ok_or("Captured frame is too large")?;
        if pixels.len() < needed {
            return Err("Truncated captured frame".into());
        }
        self.sinks.video(&pixels[..needed], width, height, stride);
        Ok(())
    }

    fn audio(&self, sample: CMSampleBuffer) -> Result<(), String> {
        let description = sample
            .format_description()
            .ok_or("Missing captured audio format")?;
        // CoreMedia owns this ASBD for the lifetime of description.
        let format = unsafe {
            CMAudioFormatDescriptionGetStreamBasicDescription(description.as_ptr()).as_ref()
        }
        .ok_or("Missing captured PCM format")?;
        let list = sample
            .audio_buffer_list()
            .map_err(|e| format!("Could not access captured audio: {e}"))?;
        let frames = usize::try_from(sample.num_samples()).map_err(|_| "Invalid audio length")?;
        // Bound allocation even if the provider returns an unexpected packet.
        if frames > 48_000 / 5 {
            return Err("Captured audio packet exceeded 200 ms".into());
        }
        let buffers: Vec<_> = list.iter().collect();
        let pcm = normalize_audio(
            *format,
            &buffers
                .iter()
                .map(|b| (b.number_channels, b.data()))
                .collect::<Vec<_>>(),
            frames,
        )?;
        // The parent sink has a bounded non-blocking PCM queue. It packetizes
        // these arbitrary stereo 48 kHz samples into WebRTC's 10 ms frames.
        self.sinks.audio(&pcm)
    }
}

impl SCStreamOutputTrait for Output {
    fn did_output_sample_buffer(&self, sample: CMSampleBuffer, kind: SCStreamOutputType) {
        let Ok(active) = self.active.lock() else {
            self.failure.set("Capture output state was poisoned".into());
            return;
        };
        if !*active {
            return;
        }
        let result = match kind {
            SCStreamOutputType::Screen => self.video(sample),
            SCStreamOutputType::Audio => self.audio(sample),
            SCStreamOutputType::Microphone => Err("Unexpected microphone capture".into()),
        };
        if let Err(error) = result {
            self.failure.set(error);
        }
    }
}

fn normalize_audio(
    format: AudioDescription,
    buffers: &[(u32, &[u8])],
    frames: usize,
) -> Result<Vec<i16>, String> {
    let channels = format.channels as usize;
    let planar = format.flags & 32 != 0;
    let float = format.flags & 1 != 0;
    let big_endian = format.flags & 2 != 0;
    let signed = format.flags & 4 != 0;
    let packed = format.flags & 8 != 0;
    let bytes = (format.bits / 8) as usize;
    // SCK is configured for 48 kHz. Refuse an unexpected rate rather than
    // mislabeling it as 48 kHz, which changes pitch and causes clock drift.
    if format.format != u32::from_be_bytes(*b"lpcm")
        || format.sample_rate != 48_000.0
        || !(1..=2).contains(&channels)
        || !packed
        || format.flags & 16 != 0 // aligned-high layouts need another decoder
        || !(if float {
            format.bits == 32 || format.bits == 64
        } else {
            signed && (format.bits == 16 || format.bits == 32)
        })
        || format.bytes_per_frame as usize != bytes * if planar { 1 } else { channels }
    {
        return Err("Unsupported captured audio format (expected packed 48 kHz PCM)".into());
    }
    let expected_buffers = if planar { channels } else { 1 };
    if buffers.len() != expected_buffers
        || buffers.iter().any(|(count, data)| {
            *count as usize != if planar { 1 } else { channels }
                || data.len() < frames * format.bytes_per_frame as usize
        })
    {
        return Err("Invalid captured audio buffer layout".into());
    }
    let mut pcm = Vec::with_capacity(frames * 2);
    for frame in 0..frames {
        for channel in 0..2 {
            let channel = channel.min(channels - 1); // mono becomes dual mono
            let (buffer, offset) = if planar {
                (channel, frame * bytes)
            } else {
                (0, (frame * channels + channel) * bytes)
            };
            let data = &buffers[buffer].1[offset..offset + bytes];
            let value = if float && bytes == 4 {
                let data = data.try_into().unwrap();
                if big_endian {
                    f32::from_be_bytes(data) as f64
                } else {
                    f32::from_le_bytes(data) as f64
                }
            } else if float {
                let data = data.try_into().unwrap();
                if big_endian {
                    f64::from_be_bytes(data)
                } else {
                    f64::from_le_bytes(data)
                }
            } else if bytes == 2 {
                let data = data.try_into().unwrap();
                f64::from(if big_endian {
                    i16::from_be_bytes(data)
                } else {
                    i16::from_le_bytes(data)
                }) / 32768.0
            } else {
                let data = data.try_into().unwrap();
                f64::from(if big_endian {
                    i32::from_be_bytes(data)
                } else {
                    i32::from_le_bytes(data)
                }) / 2_147_483_648.0
            };
            pcm.push(if value.is_finite() {
                (value.clamp(-1.0, 1.0) * 32768.0)
                    .round()
                    .clamp(-32768.0, 32767.0) as i16
            } else {
                0
            });
        }
    }
    Ok(pcm)
}

struct Session {
    streams: Vec<SCStream>,
    output: Output,
}

impl Session {
    fn add(
        &mut self,
        filter: &SCContentFilter,
        config: &SCStreamConfiguration,
        audio: bool,
    ) -> Result<(), String> {
        let failure = self.output.failure.clone();
        let stream = SCStream::new_with_delegate(
            filter,
            config,
            ErrorHandler::new(move |e| failure.set(format!("ScreenCaptureKit stopped: {e}"))),
        )
        .map_err(|e| e.to_string())?;
        // Own the stream before any fallible operation, including a start that
        // times out after the OS has already begun capture.
        self.streams.push(stream);
        let stream = self.streams.last_mut().unwrap();
        stream
            .add_output_handler(
                self.output.clone(),
                if audio {
                    SCStreamOutputType::Audio
                } else {
                    SCStreamOutputType::Screen
                },
            )
            .map_err(|e| e.to_string())?;
        stream.start_capture().map_err(|e| e.to_string())
    }

    fn close(&mut self) -> Result<(), String> {
        if let Ok(mut active) = self.output.active.lock() {
            *active = false;
        }
        let mut error = None;
        for stream in self.streams.drain(..).rev() {
            if let Err(e) = stream.stop_capture() {
                error.get_or_insert_with(|| e.to_string());
            }
        }
        error.map_or(Ok(()), Err)
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        if let Err(e) = self.close() {
            log::warn!("Native macOS capture cleanup: {e}");
        }
    }
}

pub fn run(config: CaptureConfig, sinks: Sinks, stop: Arc<AtomicBool>) -> Result<(), String> {
    // Do not invoke SCK at all on unsupported OS versions. Its audio setters
    // silently do nothing below 13, which is not successful sound capture.
    supported(config.sound_id.is_some())?;
    if config.width == 0
        || config.height == 0
        || config.width > 8192
        || config.height > 8192
        || !(1..=120).contains(&config.fps)
    {
        return Err("Invalid native capture dimensions or frame rate".into());
    }
    if stop.load(Ordering::Acquire) {
        return Ok(());
    }
    let content = SCShareableContent::get().map_err(|e| e.to_string())?;
    let applications = content.applications();
    let excluded: Vec<_> = applications.iter().filter(|app| own_app(app)).collect();
    let mut video_app = None;
    let filter = if let Some(display) = content
        .displays()
        .into_iter()
        .find(|display| config.source_id == format!("screen:{}", display.display_id()))
    {
        SCContentFilter::create()
            .with_display(&display)
            .with_excluding_applications(&excluded, &[])
            .build()
    } else {
        let selected = content.windows().into_iter().find_map(|window| {
            let app = window.owning_application()?;
            let app = identity(&app)?;
            (config.source_id == window_id(&window, &app)).then_some((window, app))
        });
        let (window, app) = selected.ok_or("The selected video source is no longer available")?;
        video_app = Some(app);
        SCContentFilter::create().with_window(&window).build()
    }
    .map_err(|e| e.to_string())?;

    // Even window video uses a separate app-allowlist audio filter. This permits
    // independent approval of a different app, and never enables display mix.
    let sound = if let Some(id) = &config.sound_id {
        let (app, identity) = applications
            .iter()
            .find_map(|app| {
                let identity = identity(app)?;
                (identity.id() == *id).then_some((app, identity))
            })
            .ok_or("The selected sound application is no longer available")?;
        let display = content
            .displays()
            .into_iter()
            .next()
            .ok_or("No display is available for application sound capture")?;
        let filter = SCContentFilter::create()
            .with_display(&display)
            .with_including_applications(&[app], &[])
            .build()
            .map_err(|e| e.to_string())?;
        Some((filter, identity))
    } else {
        None
    };
    let output = Output {
        sinks,
        active: Arc::new(Mutex::new(true)),
        failure: Failure::default(),
    };
    let mut session = Session {
        streams: Vec::new(),
        output,
    };
    let video_config = SCStreamConfiguration::new()
        .with_width(config.width)
        .with_height(config.height)
        .with_fps(config.fps)
        .with_pixel_format(PixelFormat::BGRA)
        .with_queue_depth(3)
        .with_shows_cursor(true)
        .with_scales_to_fit(true);
    // Default is off; explicitly set only on 13+ to respect the older API floor.
    session.add(&filter, &video_config, false)?;
    if let Some((filter, _)) = &sound {
        let audio_config = SCStreamConfiguration::new()
            .with_width(2)
            .with_height(2)
            .with_fps(1)
            .with_captures_audio(true)
            .with_excludes_current_process_audio(true)
            .with_sample_rate(48_000)
            .with_channel_count(2);
        session.add(filter, &audio_config, true)?;
    }
    session.output.failure.check()?;
    if stop.load(Ordering::Acquire) {
        return session.close();
    }
    session.output.sinks.ready()?;
    let mut checked = Instant::now();
    while !stop.load(Ordering::Acquire) {
        session.output.failure.check()?;
        if checked.elapsed() >= Duration::from_millis(500) {
            if video_app.as_ref().is_some_and(|app| !app.alive())
                || sound.as_ref().is_some_and(|(_, app)| !app.alive())
            {
                return Err("A selected capture application exited or changed identity".into());
            }
            checked = Instant::now();
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    let result = session.output.failure.check();
    let cleanup = session.close();
    result.and(cleanup)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn format(flags: u32, channels: u32, bits: u32) -> AudioDescription {
        AudioDescription {
            sample_rate: 48_000.0,
            format: u32::from_be_bytes(*b"lpcm"),
            flags,
            bytes_per_packet: 0,
            frames_per_packet: 1,
            bytes_per_frame: bits / 8 * if flags & 32 != 0 { 1 } else { channels },
            channels,
            bits,
            reserved: 0,
        }
    }

    #[test]
    fn planar_float_is_interleaved_and_clipped() {
        let left: Vec<_> = [0.5f32, -2.0, f32::NAN]
            .into_iter()
            .flat_map(f32::to_le_bytes)
            .collect();
        let right: Vec<_> = [-0.5f32, 2.0, f32::INFINITY]
            .into_iter()
            .flat_map(f32::to_le_bytes)
            .collect();
        assert_eq!(
            normalize_audio(format(1 | 8 | 32, 2, 32), &[(1, &left), (1, &right)], 3).unwrap(),
            [16384, -16384, -32768, 32767, 0, 0]
        );
    }

    #[test]
    fn interleaved_signed_pcm_preserves_channels() {
        let data: Vec<_> = [i16::MIN, i16::MAX, 123, -456]
            .into_iter()
            .flat_map(i16::to_le_bytes)
            .collect();
        assert_eq!(
            normalize_audio(format(4 | 8, 2, 16), &[(2, &data)], 2).unwrap(),
            [i16::MIN, i16::MAX, 123, -456]
        );
    }

    #[test]
    fn mono_big_endian_is_duplicated() {
        assert_eq!(
            normalize_audio(format(2 | 4 | 8, 1, 16), &[(1, &[0x40, 0])], 1).unwrap(),
            [16384, 16384]
        );
    }

    #[test]
    fn mismatched_rate_and_truncated_planes_are_rejected() {
        let mut f = format(1 | 8 | 32, 2, 32);
        assert!(normalize_audio(f, &[(1, &[0; 4])], 1).is_err());
        assert!(normalize_audio(f, &[(1, &[0; 4]), (1, &[])], 1).is_err());
        f.sample_rate = 44_100.0;
        assert!(normalize_audio(f, &[(1, &[0; 4]), (1, &[0; 4])], 1).is_err());
    }
}
