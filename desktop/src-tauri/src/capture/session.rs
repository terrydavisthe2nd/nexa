//! Native capture sources feed a same-host DTLS/SRTP peer. No STUN/TURN or
//! network signaling service is involved; host candidates may use a LAN NIC.
use std::{
    borrow::Cow,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};

use libwebrtc::{
    audio_frame::AudioFrame,
    audio_source::{native::NativeAudioSource, AudioSourceOptions},
    native::yuv_helper::argb_to_i420,
    peer_connection::{AnswerOptions, IceGatheringState, PeerConnection},
    peer_connection_factory::{
        native::PeerConnectionFactoryExt, ContinualGatheringPolicy, PeerConnectionFactory,
        RtcConfiguration,
    },
    session_description::{SdpType, SessionDescription},
    video_frame::{I420Buffer, VideoFrame, VideoRotation},
    video_source::{native::NativeVideoSource, VideoResolution},
};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::{mpsc, watch};

#[derive(Clone, Serialize)]
pub struct Source {
    pub id: String,
    pub name: String,
    pub kind: String,
    pub thumbnail: String,
}

#[derive(Clone, Serialize)]
pub struct SoundSource {
    pub id: String,
    pub name: String,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CaptureConfig {
    pub source_id: String,
    pub sound_id: Option<String>,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Selection {
    pub request_id: String,
    pub sources: Vec<Source>,
    pub sounds: Vec<SoundSource>,
    pub audio: bool,
}

#[derive(Clone)]
pub struct Sinks {
    video: NativeVideoSource,
    audio: mpsc::Sender<Vec<i16>>,
    ready: std::sync::mpsc::SyncSender<Result<(), String>>,
    stop: Arc<AtomicBool>,
    clock: Instant,
}

impl Sinks {
    pub fn ready(&self) -> Result<(), String> {
        self.ready.try_send(Ok(())).map_err(|e| e.to_string())
    }

    pub fn video(&self, bgra: &[u8], width: u32, height: u32, stride: u32) {
        if self.stop.load(Ordering::Acquire)
            || width == 0
            || height == 0
            || width > 7680
            || height > 7680
            || stride < width * 4
            || bgra.len() < stride as usize * (height - 1) as usize + width as usize * 4
        {
            return;
        }
        let mut buffer = I420Buffer::new(width, height);
        let (sy, su, sv) = buffer.strides();
        let (y, u, v) = buffer.data_mut();
        argb_to_i420(
            bgra,
            stride,
            y,
            sy,
            u,
            su,
            v,
            sv,
            width as i32,
            height as i32,
        );
        self.video.capture_frame(&VideoFrame {
            rotation: VideoRotation::VideoRotation0,
            timestamp_us: self.clock.elapsed().as_micros() as i64,
            frame_metadata: None,
            buffer,
        });
    }

    /// Callbacks never wait on WebRTC. Overflow fails the share rather than
    /// accumulating old audio indefinitely or widening the capture scope.
    pub fn audio(&self, pcm: &[i16]) -> Result<(), String> {
        if self.stop.load(Ordering::Acquire) {
            return Err("capture stopped".into());
        }
        if pcm.len() > 9600 || pcm.len() % 2 != 0 {
            return Err("invalid stereo PCM packet".into());
        }
        self.audio
            .try_send(pcm.to_vec())
            .map_err(|e| format!("native audio queue: {e}"))
    }
}

struct Session {
    id: String,
    stop: Arc<AtomicBool>,
    peer: Option<PeerConnection>,
    sources: Vec<Source>,
    sounds: Vec<SoundSource>,
}

impl Drop for Session {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(pc) = &self.peer {
            pc.close();
        }
    }
}

#[derive(Default)]
pub struct CaptureState(Mutex<Option<Session>>);

impl CaptureState {
    pub fn stop(&self, id: Option<&str>) {
        let mut slot = self.0.lock().unwrap();
        if slot
            .as_ref()
            .is_some_and(|s| id.is_none() || id == Some(s.id.as_str()))
        {
            slot.take();
        }
    }
}

#[tauri::command]
pub async fn capture_sources(app: AppHandle, id: String) -> Result<Selection, String> {
    if id.len() > 80 {
        return Err("invalid capture id".into());
    }
    {
        let state = app.state::<CaptureState>();
        let mut slot = state.0.lock().unwrap();
        if slot.is_some() {
            return Err("capture already pending or active".into());
        }
        *slot = Some(Session {
            id: id.clone(),
            stop: Arc::new(AtomicBool::new(false)),
            peer: None,
            sources: vec![],
            sounds: vec![],
        });
    }
    let listed = tauri::async_runtime::spawn_blocking(|| {
        let sources = super::platform::sources()?;
        // Sound being unavailable must not prevent a video-only share.
        let sounds = super::platform::sounds().unwrap_or_else(|e| {
            log::warn!("capture sound list: {e}");
            vec![]
        });
        Ok::<_, String>((sources, sounds))
    })
    .await
    .map_err(|e| e.to_string())
    .and_then(|v| v);
    match listed {
        Ok((sources, sounds)) => {
            let state = app.state::<CaptureState>();
            let mut slot = state.0.lock().unwrap();
            let session = slot
                .as_mut()
                .filter(|s| s.id == id)
                .ok_or("capture cancelled")?;
            session.sources = sources.clone();
            session.sounds = sounds.clone();
            Ok(Selection {
                request_id: id,
                audio: !sounds.is_empty(),
                sources,
                sounds,
            })
        }
        Err(e) => {
            app.state::<CaptureState>().stop(Some(&id));
            Err(e)
        }
    }
}

#[tauri::command]
pub fn capture_stop(app: AppHandle, id: String) {
    app.state::<CaptureState>().stop(Some(&id));
}

#[tauri::command]
pub async fn capture_start(
    app: AppHandle,
    id: String,
    config: CaptureConfig,
    offer: String,
) -> Result<String, String> {
    let result = start(&app, &id, config, offer).await;
    if result.is_err() {
        app.state::<CaptureState>().stop(Some(&id));
    }
    result
}

async fn start(
    app: &AppHandle,
    id: &str,
    config: CaptureConfig,
    offer: String,
) -> Result<String, String> {
    if !(16..=7680).contains(&config.width)
        || !(16..=4320).contains(&config.height)
        || !(1..=60).contains(&config.fps)
        || offer.len() > 128 * 1024
    {
        return Err("invalid capture settings".into());
    }
    let factory = PeerConnectionFactory::with_zero_playout_delay();
    let mut rtc = RtcConfiguration::default();
    rtc.continual_gathering_policy = ContinualGatheringPolicy::GatherOnce;
    let pc = factory
        .create_peer_connection(rtc)
        .map_err(|e| e.to_string())?;
    let stop = {
        let state = app.state::<CaptureState>();
        let mut slot = state.0.lock().unwrap();
        let session = slot
            .as_mut()
            .filter(|s| s.id == id && s.peer.is_none())
            .ok_or("capture no longer pending")?;
        if !session.sources.iter().any(|s| s.id == config.source_id)
            || config
                .sound_id
                .as_ref()
                .is_some_and(|id| !session.sounds.iter().any(|s| &s.id == id))
        {
            pc.close();
            return Err("capture source was not offered".into());
        }
        session.peer = Some(pc.clone());
        session.stop.clone()
    };
    let video = NativeVideoSource::new(
        VideoResolution {
            width: config.width,
            height: config.height,
        },
        true,
    );
    pc.add_track(
        factory.create_video_track("screen", video.clone()).into(),
        &["capture"],
    )
    .map_err(|e| e.to_string())?;
    let (audio_tx, mut audio_rx) = mpsc::channel::<Vec<i16>>(8);
    if config.sound_id.is_some() {
        let audio = NativeAudioSource::new(AudioSourceOptions::default(), 48_000, 2, 0);
        pc.add_track(
            factory
                .create_audio_track("screen_audio", audio.clone())
                .into(),
            &["capture"],
        )
        .map_err(|e| e.to_string())?;
        let stop_audio = stop.clone();
        tauri::async_runtime::spawn(async move {
            let mut pending = Vec::with_capacity(10560);
            while let Some(pcm) = audio_rx.recv().await {
                if stop_audio.load(Ordering::Acquire) {
                    break;
                }
                pending.extend(pcm);
                let whole = pending.len() / 960 * 960;
                for chunk in pending[..whole].chunks_exact(960) {
                    let frame = AudioFrame {
                        data: Cow::Borrowed(chunk),
                        sample_rate: 48_000,
                        num_channels: 2,
                        samples_per_channel: 480,
                    };
                    if audio.capture_frame(&frame).await.is_err() {
                        stop_audio.store(true, Ordering::Release);
                        break;
                    }
                }
                pending.drain(..whole);
            }
            audio.clear_buffer();
        });
    }
    let (ready, receiver) = std::sync::mpsc::sync_channel(1);
    let sinks = Sinks {
        video,
        audio: audio_tx,
        ready: ready.clone(),
        stop: stop.clone(),
        clock: Instant::now(),
    };
    let worker_app = app.clone();
    let worker_id = id.to_owned();
    let worker_stop = stop.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let result = super::platform::run(config, sinks, worker_stop.clone());
        let error = result.err();
        if let Some(error) = &error {
            let _ = ready.try_send(Err(error.clone()));
        }
        worker_stop.store(true, Ordering::Release);
        worker_app.state::<CaptureState>().stop(Some(&worker_id));
        let _ = worker_app.emit(
            "desktop://capture-ended",
            serde_json::json!({ "id": worker_id, "error": error }),
        );
    });
    tauri::async_runtime::spawn_blocking(move || receiver.recv_timeout(Duration::from_secs(30)))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| format!("capture startup: {e}"))??;
    let (gathered, mut changes) = watch::channel(IceGatheringState::New);
    pc.on_ice_gathering_state_change(Some(Box::new(move |state| {
        gathered.send_replace(state);
    })));
    pc.set_remote_description(
        SessionDescription::parse(&offer, SdpType::Offer).map_err(|e| e.to_string())?,
    )
    .await
    .map_err(|e| e.to_string())?;
    let answer = pc
        .create_answer(AnswerOptions::default())
        .await
        .map_err(|e| e.to_string())?;
    pc.set_local_description(answer)
        .await
        .map_err(|e| e.to_string())?;
    tokio::time::timeout(Duration::from_secs(10), async {
        while *changes.borrow_and_update() != IceGatheringState::Complete {
            changes.changed().await.map_err(|e| e.to_string())?;
        }
        Ok::<_, String>(())
    })
    .await
    .map_err(|_| "native ICE gathering timed out")??;
    pc.on_ice_gathering_state_change(None);
    if stop.load(Ordering::Acquire) {
        return Err("capture ended during startup".into());
    }
    Ok(pc
        .current_local_description()
        .ok_or("missing native SDP answer")?
        .to_string())
}
