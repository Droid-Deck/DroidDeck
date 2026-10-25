use std::collections::VecDeque;
use std::time::{Duration, Instant};

use crate::nvst::NvstUdpReceiverError;

pub(crate) const MICROPHONE_QUEUE_CAPACITY: usize = 5;
const MAX_OPUS_BYTES: usize = 1275;
const MAX_FRAME_AGE: Duration = Duration::from_millis(100);

#[derive(Debug, Clone, Copy)]
pub(crate) struct MicrophoneRtpConfig {
    pub opus_payload_type: u8,
    pub red_payload_type: Option<u8>,
    pub redundancy_level: u8,
}

impl Default for MicrophoneRtpConfig {
    fn default() -> Self {
        Self {
            opus_payload_type: 111,
            red_payload_type: None,
            redundancy_level: 0,
        }
    }
}

pub(crate) struct MicrophonePacketizer {
    config: MicrophoneRtpConfig,
    history: VecDeque<(u32, Vec<u8>)>,
}

impl MicrophonePacketizer {
    pub fn new(config: MicrophoneRtpConfig) -> Self {
        Self {
            config,
            history: VecDeque::with_capacity(3),
        }
    }

    pub fn clear(&mut self) {
        self.history.clear();
    }

    pub fn packetize(&mut self, frame: MicrophoneFrame) -> (u8, Vec<u8>) {
        let Some(red_payload_type) = self
            .config
            .red_payload_type
            .filter(|_| self.config.redundancy_level > 0)
        else {
            return (self.config.opus_payload_type, frame.payload);
        };
        self.history.retain(|(timestamp, payload)| {
            let offset = frame.timestamp.wrapping_sub(*timestamp);
            (1..=0x3fff).contains(&offset) && payload.len() <= 0x3ff
        });
        while 1
            + frame.payload.len()
            + self
                .history
                .iter()
                .map(|(_, bytes)| 4 + bytes.len())
                .sum::<usize>()
            > MAX_OPUS_BYTES + 1
        {
            self.history.pop_front();
        }
        let mut payload = Vec::with_capacity(
            1 + frame.payload.len()
                + self
                    .history
                    .iter()
                    .map(|(_, bytes)| 4 + bytes.len())
                    .sum::<usize>(),
        );
        for (timestamp, bytes) in &self.history {
            let offset = frame.timestamp.wrapping_sub(*timestamp) as u16;
            let length = bytes.len() as u16;
            payload.push(0x80 | self.config.opus_payload_type);
            payload.push((offset >> 6) as u8);
            payload.push(((offset & 0x3f) << 2) as u8 | (length >> 8) as u8);
            payload.push(length as u8);
        }
        payload.push(self.config.opus_payload_type);
        for (_, bytes) in &self.history {
            payload.extend_from_slice(bytes);
        }
        payload.extend_from_slice(&frame.payload);
        if frame.payload.len() <= 0x3ff {
            self.history.push_back((frame.timestamp, frame.payload));
            while self.history.len() > usize::from(self.config.redundancy_level) {
                self.history.pop_front();
            }
        }
        (red_payload_type, payload)
    }
}

pub(crate) struct MicrophoneFrame {
    pub payload: Vec<u8>,
    pub timestamp: u32,
    pub queued_at: Instant,
}

pub(crate) struct MicrophoneQueue {
    available: bool,
    enabled: bool,
    closed: bool,
    pub generation: u64,
    frames: VecDeque<MicrophoneFrame>,
    dropped: u64,
}

impl MicrophoneQueue {
    pub fn new(available: bool) -> Self {
        Self {
            available,
            enabled: false,
            closed: false,
            generation: 0,
            frames: VecDeque::with_capacity(MICROPHONE_QUEUE_CAPACITY),
            dropped: 0,
        }
    }

    fn clear(&mut self) {
        self.dropped += self.frames.len() as u64;
        self.frames.clear();
    }

    pub fn set_enabled(&mut self, enabled: bool) -> Result<(), NvstUdpReceiverError> {
        self.clear();
        self.generation = self.generation.wrapping_add(1);
        if self.closed {
            return Err(NvstUdpReceiverError::Closed);
        }
        if enabled && !self.available {
            return Err(NvstUdpReceiverError::MicrophoneUnavailable);
        }
        self.enabled = enabled;
        Ok(())
    }

    pub fn push(
        &mut self,
        payload: Vec<u8>,
        timestamp: u32,
        now: Instant,
    ) -> Result<(), NvstUdpReceiverError> {
        if self.closed {
            return Err(NvstUdpReceiverError::Closed);
        }
        if !self.available {
            return Err(NvstUdpReceiverError::MicrophoneUnavailable);
        }
        if !self.enabled {
            return Err(NvstUdpReceiverError::MicrophoneDisabled);
        }
        if payload.is_empty() || payload.len() > MAX_OPUS_BYTES {
            return Err(NvstUdpReceiverError::InvalidMicrophoneFrame);
        }
        if self.frames.len() == MICROPHONE_QUEUE_CAPACITY {
            self.frames.pop_front();
            self.dropped += 1;
        }
        self.frames.push_back(MicrophoneFrame {
            payload,
            timestamp,
            queued_at: now,
        });
        Ok(())
    }

    pub fn pop(&mut self, now: Instant) -> Option<MicrophoneFrame> {
        while let Some(frame) = self.frames.pop_front() {
            if now.saturating_duration_since(frame.queued_at) < MAX_FRAME_AGE {
                return Some(frame);
            }
            self.dropped += 1;
        }
        None
    }

    pub fn dropped(&self) -> u64 {
        self.dropped
    }

    pub fn close(&mut self) {
        self.clear();
        self.enabled = false;
        self.closed = true;
        self.generation = self.generation.wrapping_add(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(timestamp: u32, value: u8) -> MicrophoneFrame {
        MicrophoneFrame {
            payload: vec![value],
            timestamp,
            queued_at: Instant::now(),
        }
    }

    #[test]
    fn red_encodes_three_prior_frames_with_rfc2198_headers_and_timestamp_wrap() {
        let mut sender = MicrophonePacketizer::new(MicrophoneRtpConfig {
            opus_payload_type: 111,
            red_payload_type: Some(63),
            redundancy_level: 3,
        });
        let first = u32::MAX - 479;
        assert_eq!(sender.packetize(frame(first, 1)), (63, vec![111, 1]));
        sender.packetize(frame(0, 2));
        sender.packetize(frame(480, 3));
        let (pt, payload) = sender.packetize(frame(960, 4));
        assert_eq!(pt, 63);
        assert_eq!(
            payload,
            [
                239, 22, 128, 1, 239, 15, 0, 1, 239, 7, 128, 1, 111, 1, 2, 3, 4
            ]
        );
        assert_eq!(sender.history.len(), 3);
        sender.clear();
        assert_eq!(sender.packetize(frame(1440, 5)), (63, vec![111, 5]));
    }

    #[test]
    fn negotiated_payload_types_and_disabled_red_are_honored() {
        for config in [
            MicrophoneRtpConfig::default(),
            MicrophoneRtpConfig {
                opus_payload_type: 109,
                red_payload_type: Some(112),
                redundancy_level: 0,
            },
        ] {
            let mut sender = MicrophonePacketizer::new(config);
            assert_eq!(
                sender.packetize(frame(0, 1)),
                (config.opus_payload_type, vec![1])
            );
            assert!(sender.history.is_empty());
        }
        let mut sender = MicrophonePacketizer::new(MicrophoneRtpConfig {
            opus_payload_type: 109,
            red_payload_type: Some(112),
            redundancy_level: 3,
        });
        assert_eq!(sender.packetize(frame(0, 1)), (112, vec![109, 1]));
        assert_eq!(
            sender.packetize(frame(480, 2)),
            (112, vec![237, 7, 128, 1, 109, 1, 2])
        );
    }

    #[test]
    fn red_history_excludes_unrepresentable_lengths_offsets_and_timestamp_restarts() {
        let mut sender = MicrophonePacketizer::new(MicrophoneRtpConfig {
            opus_payload_type: 111,
            red_payload_type: Some(63),
            redundancy_level: 3,
        });
        let mut oversized = frame(0, 1);
        oversized.payload = vec![1; 1275];
        assert_eq!(sender.packetize(oversized).1.len(), 1276);
        assert!(sender.history.is_empty());
        sender.packetize(frame(480, 2));
        assert_eq!(sender.packetize(frame(480 + 0x4000, 3)).1, [111, 3]);
        assert_eq!(sender.packetize(frame(0, 4)).1, [111, 4]);
    }

    #[test]
    fn red_payload_budget_drops_oldest_redundancy_not_primary_audio() {
        let mut sender = MicrophonePacketizer::new(MicrophoneRtpConfig {
            opus_payload_type: 111,
            red_payload_type: Some(63),
            redundancy_level: 3,
        });
        for timestamp in [0, 480, 960, 1440] {
            let mut frame = frame(timestamp, (timestamp / 480) as u8);
            frame.payload.resize(500, frame.payload[0]);
            let expected = frame.payload.clone();
            let (_, payload) = sender.packetize(frame);
            assert!(payload.len() <= MAX_OPUS_BYTES + 1);
            assert_eq!(&payload[payload.len() - 500..], &expected);
        }
    }

    #[test]
    fn microphone_queue_bounds_payload_and_drops_oldest() {
        let mut queue = MicrophoneQueue::new(true);
        queue.set_enabled(true).unwrap();
        let now = Instant::now();
        assert!(queue.push(vec![], 0, now).is_err());
        assert!(queue.push(vec![0; MAX_OPUS_BYTES + 1], 0, now).is_err());
        for timestamp in 0..8 {
            queue.push(vec![1], timestamp, now).unwrap();
        }
        assert_eq!(queue.frames.len(), MICROPHONE_QUEUE_CAPACITY);
        assert_eq!(queue.dropped(), 3);
        assert_eq!(queue.pop(now).unwrap().timestamp, 3);
        assert!(queue.pop(now + MAX_FRAME_AGE).is_none());
        assert_eq!(queue.dropped(), 7);
    }

    #[test]
    fn microphone_queue_flushes_every_explicit_toggle_and_shutdown() {
        let mut queue = MicrophoneQueue::new(true);
        let now = Instant::now();
        assert!(matches!(
            queue.push(vec![1], 0, now),
            Err(NvstUdpReceiverError::MicrophoneDisabled)
        ));
        queue.set_enabled(true).unwrap();
        queue.push(vec![1], u32::MAX, now).unwrap();
        queue.set_enabled(true).unwrap();
        assert!(queue.pop(now).is_none());
        queue.push(vec![1], 0, now).unwrap();
        queue.set_enabled(false).unwrap();
        queue.set_enabled(true).unwrap();
        assert!(queue.pop(now).is_none());
        queue.close();
        assert!(matches!(
            queue.set_enabled(true),
            Err(NvstUdpReceiverError::Closed)
        ));
        assert!(matches!(
            queue.push(vec![1], 0, now),
            Err(NvstUdpReceiverError::Closed)
        ));
    }

    #[test]
    fn microphone_frames_expire_at_one_hundred_milliseconds() {
        let mut queue = MicrophoneQueue::new(true);
        queue.set_enabled(true).unwrap();
        let now = Instant::now();
        queue.push(vec![1], 0, now).unwrap();
        assert!(queue.pop(now + Duration::from_millis(99)).is_some());
        queue.push(vec![1], 960, now).unwrap();
        assert!(queue.pop(now + Duration::from_millis(100)).is_none());
        assert_eq!(queue.dropped(), 1);
    }

    #[test]
    fn unsupported_microphone_remains_disabled() {
        let mut queue = MicrophoneQueue::new(false);
        assert!(matches!(
            queue.set_enabled(true),
            Err(NvstUdpReceiverError::MicrophoneUnavailable)
        ));
        queue.set_enabled(false).unwrap();
        assert!(matches!(
            queue.push(vec![1], 0, Instant::now()),
            Err(NvstUdpReceiverError::MicrophoneUnavailable)
        ));
    }
}
