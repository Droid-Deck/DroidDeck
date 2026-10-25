//! Opus decoding into a small buffer that an AAudio low-latency stream plays from.

use std::collections::VecDeque;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use ndk::audio::{
    AudioCallbackResult, AudioDirection, AudioFormat, AudioPerformanceMode, AudioSharingMode,
    AudioStream, AudioStreamBuilder,
};
use opus::{Channels, Decoder};

use super::SharedPipeline;
use crate::media_types::{MediaCodec, MediaFeedback};

const SAMPLE_RATE: i32 = 48_000;
const CHANNELS: usize = 2;
const MAX_FRAME_SAMPLES_PER_CHANNEL: usize = 5_760;
// Keep at most ~80 ms queued: older audio is dropped so sound stays in step with the picture.
const MAX_BUFFERED_SAMPLES: usize = (SAMPLE_RATE as usize / 1000) * 80 * CHANNELS;

type Pcm = Arc<Mutex<VecDeque<f32>>>;

pub(super) fn spawn(shared: Arc<SharedPipeline>) -> Result<JoinHandle<()>, String> {
    let decoder = Decoder::new(SAMPLE_RATE as u32, Channels::Stereo)
        .map_err(|error| format!("Opus decoder: {error}"))?;
    // AAudio streams stay on the thread that opened them; it reports whether that worked.
    let (opened_tx, opened_rx) = std::sync::mpsc::channel();
    let worker = thread::Builder::new()
        .name("gfn-audio".to_owned())
        .spawn(move || {
            let pcm: Pcm = Arc::new(Mutex::new(VecDeque::with_capacity(MAX_BUFFERED_SAMPLES)));
            match open_output(Arc::clone(&pcm), Arc::clone(&shared)) {
                Ok(stream) => {
                    let _ = opened_tx.send(Ok(()));
                    run(&shared, decoder, &pcm, stream);
                }
                Err(reason) => {
                    let _ = opened_tx.send(Err(reason));
                }
            }
        })
        .map_err(|error| format!("could not start the audio thread: {error}"))?;
    match opened_rx.recv() {
        Ok(Ok(())) => Ok(worker),
        Ok(Err(reason)) => {
            let _ = worker.join();
            Err(reason)
        }
        Err(_) => Err("the audio thread ended before opening AAudio".to_owned()),
    }
}

fn open_output(pcm: Pcm, shared: Arc<SharedPipeline>) -> Result<AudioStream, String> {
    let stream = AudioStreamBuilder::new()
        .map_err(|error| format!("AAudio: {error:?}"))?
        .direction(AudioDirection::Output)
        .format(AudioFormat::PCM_Float)
        .sample_rate(SAMPLE_RATE)
        .channel_count(CHANNELS as i32)
        .performance_mode(AudioPerformanceMode::LowLatency)
        .sharing_mode(AudioSharingMode::Shared)
        .data_callback(Box::new(move |_stream, data, frames| {
            let samples = usize::try_from(frames).unwrap_or(0) * CHANNELS;
            let output = unsafe { std::slice::from_raw_parts_mut(data.cast::<f32>(), samples) };
            let muted = shared.audio_muted.load(Ordering::Acquire);
            let mut queued = pcm.lock().unwrap_or_else(|error| error.into_inner());
            for sample in output.iter_mut() {
                let next = queued.pop_front().unwrap_or(0.0);
                *sample = if muted { 0.0 } else { next };
            }
            AudioCallbackResult::Continue
        }))
        .open_stream()
        .map_err(|error| format!("AAudio could not open an output stream: {error:?}"))?;
    stream
        .request_start()
        .map_err(|error| format!("AAudio could not start: {error:?}"))?;
    Ok(stream)
}

fn run(shared: &SharedPipeline, mut decoder: Decoder, pcm: &Pcm, stream: AudioStream) {
    let mut output = vec![0.0_f32; MAX_FRAME_SAMPLES_PER_CHANNEL * CHANNELS];
    let mut consecutive_errors = 0_u32;
    while let Some(frame) = shared.audio.pop() {
        if !matches!(frame.codec, MediaCodec::Opus { .. }) {
            continue;
        }
        match decoder.decode_float(&frame.data, &mut output, false) {
            Ok(per_channel) => {
                consecutive_errors = 0;
                let decoded = &output[..per_channel * CHANNELS];
                let mut queued = pcm.lock().unwrap_or_else(|error| error.into_inner());
                queued.extend(decoded.iter().copied());
                let excess = queued.len().saturating_sub(MAX_BUFFERED_SAMPLES);
                if excess > 0 {
                    queued.drain(..excess);
                }
            }
            Err(error) => {
                consecutive_errors = consecutive_errors.saturating_add(1);
                shared.send(MediaFeedback::AudioDecoderError {
                    message: error.to_string(),
                    consecutive: consecutive_errors,
                });
            }
        }
    }
    let _ = stream.request_stop();
}
