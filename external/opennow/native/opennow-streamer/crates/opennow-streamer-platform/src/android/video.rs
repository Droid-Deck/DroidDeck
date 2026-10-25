//! Hardware video decoding with MediaCodec, rendered straight onto the app's Surface.
//! The codec lives on this thread only (MediaCodec is not Send).

use std::collections::HashMap;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use ndk::media::media_codec::{
    DequeuedInputBufferResult, DequeuedOutputBufferInfoResult, MediaCodec, MediaCodecDirection,
};
use ndk::media::media_format::MediaFormat;
use ndk::native_window::NativeWindow;

use super::{SharedPipeline, surface};
use crate::media_types::{EncodedFrame, MediaFeedback, MediaStreamConfig, MediaVideoCodec};

const BUFFER_FLAG_KEY_FRAME: u32 = 1;
const INPUT_WAIT: Duration = Duration::from_millis(20);
const INPUT_ATTEMPTS: usize = 25;
// Low-latency decoders usually finish a frame in a few milliseconds; wait that long for
// it so it is shown now instead of when the next compressed frame arrives.
const FIRST_OUTPUT_WAIT: Duration = Duration::from_millis(6);

pub(super) fn hardware_decoder_available(mime: &'static str) -> bool {
    static CACHE: OnceLock<Mutex<HashMap<&'static str, bool>>> = OnceLock::new();
    let cache = CACHE.get_or_init(|| Mutex::new(HashMap::new()));
    if let Some(known) = cache.lock().ok().and_then(|cache| cache.get(mime).copied()) {
        return known;
    }
    let available = MediaCodec::from_decoder_type(mime).is_some();
    if let Ok(mut cache) = cache.lock() {
        cache.insert(mime, available);
    }
    available
}

const fn mime(codec: MediaVideoCodec) -> &'static str {
    match codec {
        MediaVideoCodec::H264 => "video/avc",
        MediaVideoCodec::H265 => "video/hevc",
        MediaVideoCodec::Av1 => "video/av01",
    }
}

pub(super) fn spawn(shared: Arc<SharedPipeline>) -> Result<JoinHandle<()>, String> {
    let mime = mime(shared.stream.codec);
    if !hardware_decoder_available(mime) {
        return Err(format!("This phone has no hardware decoder for {mime}"));
    }
    thread::Builder::new()
        .name("gfn-video".to_owned())
        .spawn(move || run(&shared, mime))
        .map_err(|error| format!("could not start the video thread: {error}"))
}

struct Decoder {
    codec: MediaCodec,
    surface_generation: u64,
}

impl Decoder {
    fn open(
        mime: &str,
        stream: &MediaStreamConfig,
        window: &NativeWindow,
        surface_generation: u64,
    ) -> Result<Self, String> {
        // Low-latency keys first (Android 11+ and Qualcomm's own), then a plain format
        // for decoders that refuse them, the same order as the Xbox Cloud player.
        let mut last_error = String::new();
        for low_latency in [true, false] {
            let codec = MediaCodec::from_decoder_type(mime)
                .ok_or_else(|| format!("no decoder for {mime}"))?;
            let mut format = MediaFormat::new();
            format.set_str("mime", mime);
            format.set_i32("width", stream.width as i32);
            format.set_i32("height", stream.height as i32);
            if low_latency {
                format.set_i32("low-latency", 1);
                format.set_i32("priority", 0);
                format.set_i32("vendor.qti-ext-dec-low-latency.enable", 1);
                format.set_i32("vendor.qti-ext-dec-picture-order.enable", 1);
            }
            let started = codec
                .configure(&format, Some(window), MediaCodecDirection::Decoder)
                .and_then(|()| codec.start());
            match started {
                Ok(()) => {
                    return Ok(Self {
                        codec,
                        surface_generation,
                    });
                }
                Err(error) => last_error = format!("{error:?}"),
            }
        }
        Err(format!("{mime} decoder did not start: {last_error}"))
    }

    /// Queue one compressed frame. Returns false when the decoder would not take it.
    fn queue(&self, shared: &SharedPipeline, frame: &EncodedFrame) -> Result<bool, String> {
        for _ in 0..INPUT_ATTEMPTS {
            match self
                .codec
                .dequeue_input_buffer(INPUT_WAIT)
                .map_err(|error| format!("dequeue input: {error:?}"))?
            {
                DequeuedInputBufferResult::Buffer(mut buffer) => {
                    let data = &frame.data;
                    let destination = buffer.buffer_mut();
                    if data.len() > destination.len() {
                        return Err(format!(
                            "frame of {} bytes does not fit the decoder's {}-byte buffer",
                            data.len(),
                            destination.len()
                        ));
                    }
                    for (slot, byte) in destination.iter_mut().zip(data.iter()) {
                        slot.write(*byte);
                    }
                    let flags = if frame.keyframe { BUFFER_FLAG_KEY_FRAME } else { 0 };
                    self.codec
                        .queue_input_buffer(buffer, 0, data.len(), presentation_us(frame), flags)
                        .map_err(|error| format!("queue input: {error:?}"))?;
                    return Ok(true);
                }
                DequeuedInputBufferResult::TryAgainLater => {
                    // A full decoder drains by rendering; make room and retry.
                    self.drain(shared, Duration::ZERO)?;
                }
            }
            if shared.stopped.load(Ordering::Acquire) {
                return Ok(false);
            }
        }
        Ok(false)
    }

    /// Render every decoded frame that is ready. Returns how many were shown.
    fn drain(&self, shared: &SharedPipeline, first_wait: Duration) -> Result<u64, String> {
        let mut rendered = 0;
        let mut wait = first_wait;
        loop {
            let result = self.codec.dequeue_output_buffer(wait);
            wait = Duration::ZERO;
            match result
                .map_err(|error| format!("dequeue output: {error:?}"))?
            {
                DequeuedOutputBufferInfoResult::Buffer(buffer) => {
                    self.codec
                        .release_output_buffer(buffer, true)
                        .map_err(|error| format!("render: {error:?}"))?;
                    rendered += 1;
                    shared.published_frames.fetch_add(1, Ordering::Relaxed);
                }
                DequeuedOutputBufferInfoResult::TryAgainLater => return Ok(rendered),
                DequeuedOutputBufferInfoResult::OutputFormatChanged
                | DequeuedOutputBufferInfoResult::OutputBuffersChanged => {}
            }
        }
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        let _ = self.codec.stop();
    }
}

fn presentation_us(frame: &EncodedFrame) -> u64 {
    let rate = u64::from(frame.clock_rate_hz.max(1));
    (u128::from(frame.timestamp) * 1_000_000 / u128::from(rate)) as u64
}

fn run(shared: &SharedPipeline, mime: &str) {
    let mut decoder: Option<Decoder> = None;
    let mut playback_started = false;
    while let Some(packet) = shared.video.pop_packet() {
        let frame = packet.frame;
        let generation = surface::generation();
        if decoder
            .as_ref()
            .is_some_and(|open| open.surface_generation != generation)
        {
            // The view was recreated: a fresh decoder on the new Surface, from a keyframe.
            decoder = None;
        }
        if decoder.is_none() {
            let Some(window) = surface::current() else {
                // No Surface yet (or the view is gone): drop until one exists.
                shared.video.clear();
                continue;
            };
            if !frame.keyframe {
                shared.video.clear();
                shared.send(MediaFeedback::RequestKeyframe {
                    mid: frame.mid.clone(),
                    reason: "the decoder needs a keyframe to start".to_owned(),
                });
                continue;
            }
            match Decoder::open(mime, &shared.stream, &window, generation) {
                Ok(open) => decoder = Some(open),
                Err(message) => {
                    shared.send(MediaFeedback::DecoderError {
                        codec: shared.stream.codec.label(),
                        message,
                    });
                    shared.video.clear();
                    continue;
                }
            }
        } else if packet.reset_decoder {
            if let Some(open) = &decoder {
                let _ = open.codec.flush();
            }
        }
        let Some(open) = decoder.as_ref() else {
            continue;
        };
        match open.queue(shared, &frame) {
            Ok(true) => {
                shared.send(MediaFeedback::VideoFrameAccepted {
                    frame_index: frame.frame_index,
                    timestamp: frame.timestamp,
                    bytes: u32::try_from(frame.data.len()).unwrap_or(u32::MAX),
                    keyframe: frame.keyframe,
                });
            }
            Ok(false) => {
                shared.video.clear();
                shared.send(MediaFeedback::RequestKeyframe {
                    mid: frame.mid.clone(),
                    reason: "the decoder did not accept a frame in time".to_owned(),
                });
                continue;
            }
            Err(message) => {
                shared.send(MediaFeedback::DecoderError {
                    codec: shared.stream.codec.label(),
                    message,
                });
                decoder = None;
                shared.video.clear();
                continue;
            }
        }
        match open.drain(shared, FIRST_OUTPUT_WAIT) {
            Ok(rendered) if rendered > 0 && !playback_started => {
                playback_started = true;
                shared.send(MediaFeedback::PlaybackStarted {
                    backend: "MediaCodec (Android)",
                });
            }
            Ok(_) => {}
            Err(message) => {
                shared.send(MediaFeedback::DecoderError {
                    codec: shared.stream.codec.label(),
                    message,
                });
                decoder = None;
                shared.video.clear();
            }
        }
    }
}
