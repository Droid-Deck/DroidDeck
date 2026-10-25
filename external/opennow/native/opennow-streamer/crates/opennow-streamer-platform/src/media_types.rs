// Media types shared by every platform backend (moved out of media.rs so the
// Android backend can use them without the desktop decode pipeline).
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MediaVideoCodec {
    H264,
    H265,
    Av1,
}

impl MediaVideoCodec {
    pub const fn label(self) -> &'static str {
        match self {
            Self::H264 => "h264",
            Self::H265 => "h265",
            Self::Av1 => "av1",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ShortcutChord {
    pub virtual_key: u16,
    pub modifiers: u16,
}

impl ShortcutChord {
    pub fn parse(value: &str) -> Option<Self> {
        let mut modifiers = 0_u16;
        let mut virtual_key = None;
        for part in value.split('+').map(str::trim) {
            if part.is_empty() {
                return None;
            }
            let normalized = part.to_ascii_lowercase();
            let modifier = match normalized.as_str() {
                "shift" => Some(0x01),
                "ctrl" | "control" => Some(0x02),
                "alt" | "option" => Some(0x04),
                "meta" | "command" | "cmd" | "super" | "win" => Some(0x08),
                key if virtual_key.is_none() => {
                    virtual_key = shortcut_virtual_key(key);
                    None
                }
                _ => return None,
            };
            if let Some(modifier) = modifier {
                if modifiers & modifier != 0 {
                    return None;
                }
                modifiers |= modifier;
            }
        }
        virtual_key.map(|virtual_key| Self {
            virtual_key,
            modifiers,
        })
    }
}

fn shortcut_virtual_key(value: &str) -> Option<u16> {
    if value.len() == 1 {
        let byte = value.as_bytes()[0].to_ascii_uppercase();
        if byte.is_ascii_alphanumeric() {
            return Some(u16::from(byte));
        }
    }
    if let Some(number) = value
        .strip_prefix('f')
        .and_then(|number| number.parse::<u16>().ok())
        && (1..=24).contains(&number)
    {
        return Some(0x6f + number);
    }
    Some(match value {
        "enter" | "return" => 0x0d,
        "escape" | "esc" => 0x1b,
        "backspace" => 0x08,
        "tab" => 0x09,
        "space" => 0x20,
        "left" => 0x25,
        "up" => 0x26,
        "right" => 0x27,
        "down" => 0x28,
        "insert" => 0x2d,
        "delete" | "del" => 0x2e,
        "home" => 0x24,
        "end" => 0x23,
        "pageup" | "pgup" => 0x21,
        "pagedown" | "pgdown" => 0x22,
        "printscreen" | "print" => 0x2a,
        "pause" => 0x13,
        _ => return None,
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StreamShortcutAction {
    ToggleStats,
    TogglePointerLock,
    ToggleFullscreen,
    StopStream,
    ToggleAntiAfk,
    Screenshot,
    ToggleRecording,
    SaveClip,
}

impl StreamShortcutAction {
    pub const fn protocol_name(self) -> &'static str {
        match self {
            Self::ToggleStats => "toggle-stats",
            Self::TogglePointerLock => "toggle-pointer-lock",
            Self::ToggleFullscreen => "toggle-fullscreen",
            Self::StopStream => "stop-stream",
            Self::ToggleAntiAfk => "toggle-anti-afk",
            Self::Screenshot => "screenshot",
            Self::ToggleRecording => "toggle-recording",
            Self::SaveClip => "save-clip",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StreamShortcutBindings {
    bindings: [(StreamShortcutAction, Option<ShortcutChord>); 8],
}

impl StreamShortcutBindings {
    pub fn from_json(value: &serde_json::Value) -> Self {
        let read = |key: &str, fallback: &str| match value
            .get(key)
            .and_then(serde_json::Value::as_str)
        {
            Some("") => None,
            Some(chord) => ShortcutChord::parse(chord).or_else(|| ShortcutChord::parse(fallback)),
            None => ShortcutChord::parse(fallback),
        };
        Self {
            bindings: [
                (
                    StreamShortcutAction::ToggleStats,
                    read("toggleStats", "Ctrl+N"),
                ),
                (
                    StreamShortcutAction::TogglePointerLock,
                    read("togglePointerLock", "F8"),
                ),
                (
                    StreamShortcutAction::ToggleFullscreen,
                    read("toggleFullscreen", "F11"),
                ),
                (
                    StreamShortcutAction::StopStream,
                    read("stopStream", "Ctrl+Shift+Q"),
                ),
                (
                    StreamShortcutAction::ToggleAntiAfk,
                    read("toggleAntiAfk", "Ctrl+Shift+K"),
                ),
                (
                    StreamShortcutAction::Screenshot,
                    read("screenshot", "Ctrl+F11"),
                ),
                (
                    StreamShortcutAction::ToggleRecording,
                    read("toggleRecording", "F12"),
                ),
                (StreamShortcutAction::SaveClip, read("saveClip", "Ctrl+F12")),
            ],
        }
    }

    pub const fn action(self, virtual_key: u16, modifiers: u16) -> Option<StreamShortcutAction> {
        let mut index = 0;
        while index < self.bindings.len() {
            let (action, chord) = self.bindings[index];
            if let Some(chord) = chord
                && chord.virtual_key == virtual_key
                && chord.modifiers == modifiers
            {
                return Some(action);
            }
            index += 1;
        }
        None
    }
}

impl Default for StreamShortcutBindings {
    fn default() -> Self {
        Self::from_json(&serde_json::Value::Null)
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub enum MediaColorQuality {
    #[default]
    EightBit420,
    EightBit444,
    TenBit420,
    TenBit444,
}

impl MediaColorQuality {
    pub const fn protocol_name(self) -> &'static str {
        match self {
            Self::EightBit420 => "8bit_420",
            Self::EightBit444 => "8bit_444",
            Self::TenBit420 => "10bit_420",
            Self::TenBit444 => "10bit_444",
        }
    }

    pub const fn bit_depth(self) -> u8 {
        match self {
            Self::EightBit420 | Self::EightBit444 => 8,
            Self::TenBit420 | Self::TenBit444 => 10,
        }
    }

    pub const fn is_444(self) -> bool {
        matches!(self, Self::EightBit444 | Self::TenBit444)
    }

    #[cfg(target_os = "linux")]
    pub(crate) const fn linux_pixel_format(self) -> opennow_streamer_platform_linux::PixelFormat {
        use opennow_streamer_platform_linux::PixelFormat;
        match self {
            Self::EightBit420 => PixelFormat::Nv12,
            Self::EightBit444 => PixelFormat::Nv24,
            Self::TenBit420 => PixelFormat::P010,
            Self::TenBit444 => PixelFormat::P410,
        }
    }

    #[cfg(target_os = "linux")]
    pub(crate) const fn from_linux_pixel_format(
        format: opennow_streamer_platform_linux::PixelFormat,
    ) -> Option<Self> {
        use opennow_streamer_platform_linux::PixelFormat;
        match format {
            PixelFormat::Nv12 | PixelFormat::I420 => Some(Self::EightBit420),
            PixelFormat::Nv24 => Some(Self::EightBit444),
            PixelFormat::P010 => Some(Self::TenBit420),
            PixelFormat::P410 => Some(Self::TenBit444),
            PixelFormat::Bgra8 | PixelFormat::Rgba8 => None,
        }
    }

    #[cfg(target_os = "macos")]
    const fn macos_bit_depth(self) -> opennow_streamer_platform_macos::VideoBitDepth {
        use opennow_streamer_platform_macos::VideoBitDepth;
        match self {
            Self::EightBit420 | Self::EightBit444 => VideoBitDepth::Eight,
            Self::TenBit420 | Self::TenBit444 => VideoBitDepth::Ten,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MediaStreamConfig {
    pub codec: MediaVideoCodec,
    /// Color class accepted by CloudMatch and requested again during NVST setup.
    pub color_quality: MediaColorQuality,
    pub hdr: bool,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub bitrate_bps: u32,
    /// CloudMatch accepted Cloud G-SYNC and the host presentation path is VRR-capable.
    pub cloud_gsync: bool,
    pub shortcuts: StreamShortcutBindings,
}

impl Default for MediaStreamConfig {
    fn default() -> Self {
        Self {
            codec: MediaVideoCodec::H264,
            color_quality: MediaColorQuality::default(),
            hdr: false,
            width: 1920,
            height: 1080,
            fps: 60,
            bitrate_bps: 75_000_000,
            cloud_gsync: false,
            shortcuts: StreamShortcutBindings::default(),
        }
    }
}

#[cfg(target_os = "macos")]
impl MediaStreamConfig {
    fn macos_color_space(self) -> opennow_streamer_platform_macos::VideoColorSpace {
        use opennow_streamer_platform_macos::VideoColorSpace;
        if self.hdr {
            VideoColorSpace::Bt2020
        } else {
            VideoColorSpace::Bt709
        }
    }

    fn macos_transfer(self) -> opennow_streamer_platform_macos::VideoTransfer {
        use opennow_streamer_platform_macos::VideoTransfer;
        if self.hdr {
            VideoTransfer::Pq
        } else {
            VideoTransfer::Sdr
        }
    }

    fn macos_h265_format(
        self,
        parameter_sets: opennow_streamer_platform_macos::H265ParameterSets,
    ) -> opennow_streamer_platform_macos::H265Format {
        use opennow_streamer_platform_macos::{H265Format, VideoChroma};
        H265Format::new(parameter_sets, self.macos_color_space())
            .with_bit_depth(self.color_quality.macos_bit_depth())
            .with_chroma(if self.color_quality.is_444() {
                VideoChroma::Yuv444
            } else {
                VideoChroma::Yuv420
            })
            .with_transfer(self.macos_transfer())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MediaCodec {
    H264,
    H265,
    Av1,
    Opus { channels: u8 },
    Unsupported(String),
}

#[derive(Debug, Clone)]
pub struct EncodedFrame {
    pub mid: String,
    pub codec: MediaCodec,
    pub data: Arc<[u8]>,
    /// Sender-authored NVST video frame index used by the feedback protocol.
    pub frame_index: Option<u32>,
    pub timestamp: u64,
    pub clock_rate_hz: u32,
    pub keyframe: bool,
    pub contiguous: bool,
    pub ssrc: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CapturedInput {
    Text(opennow_streamer_protocol::text_input::UnicodeText),
    Key {
        virtual_key: u16,
        modifiers: u16,
        pressed: bool,
    },
    MouseMove {
        delta_x: i16,
        delta_y: i16,
    },
    MouseAbsolute {
        x: u16,
        y: u16,
        width: u16,
        height: u16,
    },
    MouseButton {
        button: u8,
        pressed: bool,
    },
    MouseWheel {
        delta_x: i16,
        delta_y: i16,
    },
    Gamepad {
        controller_id: u8,
        bitmap: u16,
        buttons: u16,
        left_trigger: u8,
        right_trigger: u8,
        left_stick_x: i16,
        left_stick_y: i16,
        right_stick_x: i16,
        right_stick_y: i16,
    },
    Guide,
    Screenshot,
    RecordingToggle,
    Shortcut(StreamShortcutAction),
}

const CAPTURED_INPUT_CAPACITY: usize = 256;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapturedInputSample {
    pub input: CapturedInput,
    pub captured_at: Instant,
}

#[derive(Debug, Default)]
pub struct CapturedInputQueue {
    pending: Mutex<VecDeque<CapturedInputSample>>,
    overflowed: AtomicBool,
    text_ready: AtomicBool,
    text_generation: AtomicU64,
    text_slot: opennow_streamer_protocol::text_input::TextInputSlot,
}

impl CapturedInputQueue {
    pub fn set_text_ready(&self, generation: u64, ready: bool) {
        let mut pending = self
            .pending
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let previous = self.text_generation.load(Ordering::Relaxed);
        if generation.wrapping_sub(previous) > u64::MAX / 2 {
            return;
        }
        self.text_generation.store(generation, Ordering::Relaxed);
        self.text_ready.store(ready, Ordering::Release);
        if !ready || generation != previous {
            self.text_slot.cancel();
            pending.retain(|sample| !matches!(sample.input, CapturedInput::Text(_)));
        }
    }

    pub(crate) fn submit_text(
        &self,
        bytes: &[u8],
    ) -> Result<(), opennow_streamer_protocol::text_input::TextInputError> {
        use opennow_streamer_protocol::text_input::TextInputError;
        let mut pending = self
            .pending
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if !self.text_ready.load(Ordering::Acquire) {
            return Err(TextInputError::Unavailable);
        }
        if pending.len() >= CAPTURED_INPUT_CAPACITY {
            return Err(TextInputError::Busy);
        }
        let text = self.text_slot.submit(bytes)?;
        pending.push_back(CapturedInputSample {
            input: CapturedInput::Text(text),
            captured_at: Instant::now(),
        });
        Ok(())
    }

    pub(crate) fn discard_text(&self) {
        self.text_slot.cancel();
        self.pending
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .retain(|sample| !matches!(sample.input, CapturedInput::Text(_)));
    }

    pub fn push(&self, input: CapturedInput) {
        self.push_sample(CapturedInputSample {
            input,
            captured_at: Instant::now(),
        });
    }

    pub fn push_sample(&self, sample: CapturedInputSample) {
        let mut pending = self
            .pending
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if matches!(&sample.input, CapturedInput::MouseAbsolute { .. })
            && matches!(
                pending.back(),
                Some(CapturedInputSample {
                    input: CapturedInput::MouseAbsolute { .. },
                    ..
                })
            )
        {
            pending.pop_back();
        }
        if pending.len() >= CAPTURED_INPUT_CAPACITY {
            if let Some(index) = pending.iter().position(|event| {
                matches!(
                    &event.input,
                    CapturedInput::MouseMove { .. } | CapturedInput::MouseAbsolute { .. }
                )
            }) {
                pending.remove(index);
            } else {
                self.overflowed.store(true, Ordering::Release);
                return;
            }
        }
        pending.push_back(sample);
    }

    pub(crate) fn release_gamepad(&self, controller_id: u8, bitmap: u16) {
        assert!(controller_id < 4);
        let mut pending = self
            .pending
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        pending.retain(|sample| {
            !matches!(sample.input,
            CapturedInput::Gamepad { controller_id: id, .. } if id == controller_id)
        });
        pending.push_back(CapturedInputSample {
            input: CapturedInput::Gamepad {
                controller_id,
                bitmap,
                buttons: 0,
                left_trigger: 0,
                right_trigger: 0,
                left_stick_x: 0,
                left_stick_y: 0,
                right_stick_x: 0,
                right_stick_y: 0,
            },
            captured_at: Instant::now(),
        });
        debug_assert!(pending.len() <= CAPTURED_INPUT_CAPACITY + 4);
    }

    pub fn take(&self) -> Option<CapturedInput> {
        self.take_sample().map(|sample| sample.input)
    }

    pub fn take_sample(&self) -> Option<CapturedInputSample> {
        self.pending
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .pop_front()
    }

    pub fn take_overflowed(&self) -> bool {
        self.overflowed.swap(false, Ordering::AcqRel)
    }

    pub fn clear(&self) {
        self.pending
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clear();
        self.overflowed.store(false, Ordering::Release);
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DecodeStageTimings {
    pub p50_us: u64,
    pub p95_us: u64,
    pub max_us: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DecodeTimingsReport {
    pub call: Option<DecodeStageTimings>,
    pub residence: Option<DecodeStageTimings>,
    pub call_window_samples: usize,
    pub residence_window_samples: usize,
    pub submissions_total: u64,
    pub outputs_total: u64,
    pub output_calls_total: u64,
    pub last_submission_at: Option<Instant>,
    pub last_output_at: Option<Instant>,
    pub in_flight: usize,
    pub oldest_in_flight_at: Option<Instant>,
    pub epoch: u64,
    pub epoch_started_at: Option<Instant>,
    pub unmatched_outputs: u64,
    pub unmatched_submissions: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MediaFeedback {
    VideoFrameAccepted {
        frame_index: Option<u32>,
        timestamp: u64,
        bytes: u32,
        keyframe: bool,
    },
    PlaybackStarted {
        backend: &'static str,
    },
    BackendFallback {
        from: &'static str,
        to: &'static str,
        reason: String,
    },
    ColorFormatChanged {
        requested: MediaColorQuality,
        actual: MediaColorQuality,
    },
    RequestKeyframe {
        mid: String,
        reason: String,
    },
    DecoderError {
        codec: &'static str,
        message: String,
    },
    AudioDecoderError {
        message: String,
        consecutive: u32,
    },
    AudioUnavailable {
        backend: &'static str,
        reason: String,
        rejected: u64,
    },
    QueueDropped {
        media: &'static str,
        count: usize,
    },
    OutputError {
        message: String,
    },
    DeviceLost {
        subsystem: &'static str,
        recovered: bool,
        message: Option<String>,
    },
    DecodeTimings(DecodeTimingsReport),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PushOutcome {
    Queued,
    DroppedOldest,
    Paused,
    Unsupported,
    Closed,
}
