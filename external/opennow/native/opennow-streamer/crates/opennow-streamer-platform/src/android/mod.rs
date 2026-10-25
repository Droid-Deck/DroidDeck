//! DroidDeck's Android media backend for OpenNOW's native streamer.
//!
//! The same public types as the desktop backends, so `opennow-streamer-core` runs
//! unchanged. Video goes to MediaCodec, which renders straight onto the Surface the
//! app hands over with [`set_surface`]. Opus audio is decoded here and played through
//! AAudio. Recording, replay and the microphone are not offered on Android yet.

mod audio;
mod surface;
mod video;

pub use crate::media_types::*;
pub use surface::{clear_surface, set_surface};

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use opennow_streamer_protocol::{
    AudioDevice, AudioOutputDevice, CodecCapability, GraphicsAdapterCapability,
    RecordingCompletion, RecordingCutReason, RenderSurface, ReplayBufferConfig,
    VideoBackendCapability,
};

use crate::queue::{BoundedQueue, PushResult};
use crate::video_queue::VideoQueue;

pub const MICROPHONE_SAMPLE_RATE: u32 = 48_000;
pub const MICROPHONE_FRAME_SAMPLES: usize = 480;

pub(crate) const BACKEND: &str = "mediacodec";
const AUDIO_QUEUE_CAPACITY: usize = 10;
const VIDEO_QUEUE_CAPACITY: usize = 2;
const VIDEO_QUEUE_MAX_CAPACITY: usize = 60;

fn video_queue_capacity(fps: u32) -> usize {
    let frames_for_recovery_burst = fps.max(1).div_ceil(4);
    usize::try_from(frames_for_recovery_burst)
        .unwrap_or(VIDEO_QUEUE_MAX_CAPACITY)
        .clamp(VIDEO_QUEUE_CAPACITY, VIDEO_QUEUE_MAX_CAPACITY)
}

pub const fn supports_audio_decode() -> bool {
    true
}

pub const fn supports_audio_output() -> bool {
    true
}

pub fn video_backends() -> Vec<VideoBackendCapability> {
    let codec = |codec: &'static str, available: bool| CodecCapability {
        codec,
        available,
        color_qualities: Some(vec!["8bit_420"]),
        hdr_color_qualities: None,
        hdr_supported: Some(false),
        max_decode_dimensions: None,
        reason: (!available).then_some("This phone has no hardware decoder for this codec"),
    };
    vec![VideoBackendCapability {
        backend: BACKEND,
        platform: "android",
        codecs: vec![
            codec("h264", video::hardware_decoder_available("video/avc")),
            codec("h265", video::hardware_decoder_available("video/hevc")),
            codec("av1", video::hardware_decoder_available("video/av01")),
        ],
        zero_copy_modes: vec!["surface"],
        available: true,
        reason: None,
    }]
}

pub enum MediaRuntimeControl {
    PointerLock,
}

struct RuntimeState {
    audio_muted: Arc<AtomicBool>,
    paused: AtomicBool,
    captured_input: Arc<CapturedInputQueue>,
    published_frames: Arc<AtomicU64>,
}

#[derive(Clone)]
pub struct MediaRuntime {
    state: Arc<RuntimeState>,
}

/// The runtime DroidDeck's streamer host passes to `Engine`.
pub fn create_android_runtime(captured_input: Arc<CapturedInputQueue>) -> MediaRuntime {
    MediaRuntime {
        state: Arc::new(RuntimeState {
            audio_muted: Arc::new(AtomicBool::new(false)),
            paused: AtomicBool::new(false),
            captured_input,
            published_frames: Arc::new(AtomicU64::new(0)),
        }),
    }
}

impl MediaRuntime {
    pub fn published_video_frames(&self) -> Option<u64> {
        Some(self.state.published_frames.load(Ordering::Relaxed))
    }

    pub fn set_audio_muted(&self, muted: bool) {
        self.state.audio_muted.store(muted, Ordering::Release);
    }

    pub const fn is_embedded(&self) -> bool {
        true
    }

    pub fn video_backends(&self) -> Vec<VideoBackendCapability> {
        video_backends()
    }

    pub fn graphics_adapters(&self) -> Vec<GraphicsAdapterCapability> {
        Vec::new()
    }

    pub fn captured_input(&self) -> Arc<CapturedInputQueue> {
        Arc::clone(&self.state.captured_input)
    }

    pub fn start_microphone(&self, _device_id: &str) -> Result<MicrophoneSession, String> {
        Err("The microphone is not available in DroidDeck's GeForce NOW player yet".to_owned())
    }

    pub fn audio_devices(&self) -> Result<Vec<AudioDevice>, String> {
        Ok(vec![AudioDevice {
            id: "default".to_owned(),
            name: "Android audio".to_owned(),
        }])
    }

    pub fn validate_backend(&self, requested: &str) -> Result<(), String> {
        match requested {
            "" | "auto" | BACKEND => Ok(()),
            other => Err(format!("Video backend '{other}' is not available on Android")),
        }
    }

    pub fn start_with_audio_device(
        &self,
        feedback: Sender<MediaFeedback>,
        stream: MediaStreamConfig,
        requested: &str,
        _audio_device: AudioOutputDevice,
    ) -> Result<MediaSession, String> {
        self.validate_backend(requested)?;
        MediaSession::spawn(self, feedback, stream)
    }

    pub fn update_surface(&self, _surface: RenderSurface) -> Result<(), String> {
        // The app supplies the Surface through set_surface(); nothing to do here.
        Ok(())
    }

    pub fn set_paused(&self, paused: bool) -> Result<(), String> {
        self.state.paused.store(paused, Ordering::Release);
        Ok(())
    }

    pub fn control(&self, _control: MediaRuntimeControl) -> Result<(), String> {
        Ok(())
    }

    pub fn shutdown(&self) {}
}

pub(crate) struct SharedPipeline {
    pub(crate) video: Arc<VideoQueue>,
    pub(crate) audio: Arc<BoundedQueue<EncodedFrame>>,
    pub(crate) feedback: Mutex<Sender<MediaFeedback>>,
    pub(crate) paused: AtomicBool,
    pub(crate) stopped: AtomicBool,
    pub(crate) stream: MediaStreamConfig,
    pub(crate) audio_muted: Arc<AtomicBool>,
    pub(crate) published_frames: Arc<AtomicU64>,
}

impl SharedPipeline {
    pub(crate) fn send(&self, feedback: MediaFeedback) {
        if let Ok(sender) = self.feedback.lock() {
            let _ = sender.send(feedback);
        }
    }
}

#[derive(Clone)]
pub struct MediaSink {
    shared: Arc<SharedPipeline>,
    captured_input: Arc<CapturedInputQueue>,
}

impl MediaSink {
    pub fn push(&self, frame: EncodedFrame) -> PushOutcome {
        if self.shared.stopped.load(Ordering::Acquire) {
            return PushOutcome::Closed;
        }
        if self.shared.paused.load(Ordering::Acquire) {
            return PushOutcome::Paused;
        }
        match frame.codec {
            MediaCodec::H264 | MediaCodec::H265 | MediaCodec::Av1 => self.push_video(frame),
            MediaCodec::Opus { .. } => self.push_audio(frame),
            MediaCodec::Unsupported(_) => PushOutcome::Unsupported,
        }
    }

    fn push_video(&self, frame: EncodedFrame) -> PushOutcome {
        let mid = frame.mid.clone();
        let Ok(result) = self.shared.video.push(frame) else {
            return PushOutcome::Closed;
        };
        if result.request_keyframe {
            self.shared.send(MediaFeedback::RequestKeyframe {
                mid,
                reason: "compressed video discontinuity or queue overflow".to_owned(),
            });
        }
        if result.dropped > 0 {
            self.shared.send(MediaFeedback::QueueDropped {
                media: "video",
                count: result.dropped,
            });
            PushOutcome::DroppedOldest
        } else {
            PushOutcome::Queued
        }
    }

    fn push_audio(&self, frame: EncodedFrame) -> PushOutcome {
        match self.shared.audio.push(frame) {
            PushResult::Queued => PushOutcome::Queued,
            PushResult::DroppedOldest => {
                self.shared.send(MediaFeedback::QueueDropped {
                    media: "audio",
                    count: 1,
                });
                PushOutcome::DroppedOldest
            }
            PushResult::Closed => PushOutcome::Closed,
        }
    }
}

pub struct MediaSession {
    sink: MediaSink,
    video_worker: Option<JoinHandle<()>>,
    audio_worker: Option<JoinHandle<()>>,
}

#[derive(Clone)]
pub struct MediaControl {
    shared: Arc<SharedPipeline>,
}

impl MediaSession {
    fn spawn(
        runtime: &MediaRuntime,
        feedback: Sender<MediaFeedback>,
        stream: MediaStreamConfig,
    ) -> Result<Self, String> {
        let shared = Arc::new(SharedPipeline {
            video: Arc::new(VideoQueue::new(video_queue_capacity(stream.fps))),
            audio: Arc::new(BoundedQueue::new(AUDIO_QUEUE_CAPACITY)),
            feedback: Mutex::new(feedback),
            paused: AtomicBool::new(runtime.state.paused.load(Ordering::Acquire)),
            stopped: AtomicBool::new(false),
            stream,
            audio_muted: Arc::clone(&runtime.state.audio_muted),
            published_frames: Arc::clone(&runtime.state.published_frames),
        });
        let video_worker = video::spawn(Arc::clone(&shared))?;
        let audio_worker = match audio::spawn(Arc::clone(&shared)) {
            Ok(worker) => Some(worker),
            Err(reason) => {
                shared.send(MediaFeedback::AudioUnavailable {
                    backend: "aaudio",
                    reason,
                    rejected: 0,
                });
                None
            }
        };
        Ok(Self {
            sink: MediaSink {
                shared,
                captured_input: runtime.captured_input(),
            },
            video_worker: Some(video_worker),
            audio_worker,
        })
    }

    pub fn captured_input(&self) -> Arc<CapturedInputQueue> {
        Arc::clone(&self.sink.captured_input)
    }

    pub fn sink(&self) -> MediaSink {
        self.sink.clone()
    }

    pub fn control(&self) -> MediaControl {
        MediaControl {
            shared: Arc::clone(&self.sink.shared),
        }
    }

    pub fn set_paused(&self, paused: bool) {
        self.sink.shared.paused.store(paused, Ordering::Release);
        if paused {
            self.sink.shared.video.clear();
            self.sink.shared.audio.clear();
        }
    }

    pub fn stop(mut self) {
        self.shutdown();
    }

    fn shutdown(&mut self) {
        self.control().stop();
        if let Some(worker) = self.video_worker.take() {
            let _ = worker.join();
        }
        if let Some(worker) = self.audio_worker.take() {
            let _ = worker.join();
        }
    }
}

impl Drop for MediaSession {
    fn drop(&mut self) {
        self.shutdown();
    }
}

impl MediaControl {
    pub fn start_replay(&self, _config: ReplayBufferConfig, _reserved: Arc<AtomicUsize>) {}

    pub fn stop_replay(&self) {}

    pub fn replay_snapshot(&self) -> Result<(MediaStreamConfig, ReplaySnapshot), &'static str> {
        Err("Instant replay is not available on Android")
    }

    pub fn subscribe_recording(
        &self,
    ) -> Result<(MediaStreamConfig, EncodedRecordingReceiver), String> {
        Err("Recording is not available on Android".to_owned())
    }

    pub fn unsubscribe_recording(&self) {}

    pub fn cut_recording(&self, _reason: RecordingCutReason) {}

    pub fn update_cursor(&self, _bytes: Vec<u8>) {}

    pub fn stop(&self) {
        if self.shared.stopped.swap(true, Ordering::AcqRel) {
            return;
        }
        self.shared.video.close();
        self.shared.audio.close();
    }
}

/// Recording is not offered on Android; these keep the core's recording paths compiling.
pub struct EncodedRecordingReceiver {
    _private: (),
}

pub struct ReplaySnapshot {
    _private: (),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RecordingSummary {
    pub path: PathBuf,
    pub video_packets: u64,
    pub audio_packets: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManualRecordingSummary {
    pub media: RecordingSummary,
    pub completion: RecordingCompletion,
}

pub fn record_matroska(
    _output_path: impl AsRef<Path>,
    _stream: MediaStreamConfig,
    _receiver: EncodedRecordingReceiver,
) -> Result<ManualRecordingSummary, String> {
    Err("Recording is not available on Android".to_owned())
}

pub fn record_replay_matroska(
    _output_path: impl AsRef<Path>,
    _stream: MediaStreamConfig,
    _snapshot: ReplaySnapshot,
    _cancelled: &AtomicBool,
) -> Result<RecordingSummary, String> {
    Err("Instant replay is not available on Android".to_owned())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncodedMicrophoneFrame {
    pub opus: Vec<u8>,
    pub timestamp: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MicrophoneStatus {
    pub enabled: bool,
    pub error: Option<String>,
    pub dropped_pcm_frames: u64,
    pub dropped_encoded_frames: u64,
}

/// Never constructed on Android: start_microphone() always declines.
pub struct MicrophoneSession {
    _private: (),
}

pub struct MicrophoneReceiver {
    _private: (),
}

impl MicrophoneSession {
    pub fn receiver(&self) -> MicrophoneReceiver {
        MicrophoneReceiver { _private: () }
    }

    pub fn stop(&mut self) {}
}

impl MicrophoneReceiver {
    pub fn try_recv(&self) -> Result<Option<EncodedMicrophoneFrame>, String> {
        Err("The microphone is not available on Android".to_owned())
    }

    pub fn status(&self) -> MicrophoneStatus {
        MicrophoneStatus {
            enabled: false,
            error: Some("The microphone is not available on Android".to_owned()),
            dropped_pcm_frames: 0,
            dropped_encoded_frames: 0,
        }
    }

    pub fn stop(&self) {}
}
