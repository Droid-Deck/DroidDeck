#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct ControlKeepaliveHealth {
    pub(crate) failures: u64,
    pub(crate) consecutive_failures: u32,
    pub(crate) recoveries: u64,
}

impl ControlKeepaliveHealth {
    pub(crate) fn record(&mut self, admitted: bool) {
        if !admitted {
            self.failures = self.failures.saturating_add(1);
            self.consecutive_failures = self.consecutive_failures.saturating_add(1);
            return;
        }
        if self.consecutive_failures != 0 {
            self.recoveries = self.recoveries.saturating_add(1);
            self.consecutive_failures = 0;
        }
    }
}

pub(crate) fn session_resumable(reason: Option<u32>, extended_code: Option<u32>) -> Option<bool> {
    match reason? {
        0x37 => Some(!matches!(
            extended_code?,
            0x8004_0003 | 0x8004_000b..=0x8004_000f | 0x8004_0011..=0x8004_0016
        )),
        0x3b => Some(matches!(
            extended_code?,
            0x8004_0004 | 0x8004_0007 | 0x8004_0010
        )),
        _ => Some(false),
    }
}

pub(crate) const MAX_SERVER_CONTROL_BYTES: usize = 4 + u16::MAX as usize;
pub(crate) const MAX_SERVER_CONTROL_COMMANDS: usize = 256;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ServerControlParseError {
    Oversized,
    TooManyCommands,
    Truncated,
    InvalidLength,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NvstSessionTimer {
    pub timer_type: u8,
    pub seconds_left: u32,
    pub active: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NvstServerTermination {
    pub wire_code: u32,
    pub reason: Option<u32>,
    pub extended_code: Option<u32>,
}

impl NvstServerTermination {
    pub(crate) fn from_wire(wire_code: u32, _extra: &[u8]) -> Self {
        let (reason, extended_code) = match wire_code {
            0x8003_0023 => (Some(0x38), Some(0x8003_0007)),
            0x8004_0010 => (Some(0x37), Some(0x8004_0010)),
            _ => (None, None),
        };
        Self {
            wire_code,
            reason,
            extended_code,
        }
    }

    pub fn resumable(self) -> Option<bool> {
        session_resumable(self.reason, self.extended_code)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum FramePacingFeedbackMode {
    Never,
    Interval,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ServerControlMessage<'a> {
    Timer(NvstSessionTimer),
    Termination {
        wire_code: u32,
        extra: &'a [u8],
    },
    FramePacingMode {
        stream_index: u32,
        mode: FramePacingFeedbackMode,
    },
    Unhandled,
    Unknown(u16),
}

pub(crate) struct ServerControlMessages<'a> {
    bytes: &'a [u8],
    remaining: usize,
    error: Option<ServerControlParseError>,
}

pub(crate) fn server_control_messages(bytes: &[u8]) -> ServerControlMessages<'_> {
    ServerControlMessages {
        bytes,
        remaining: MAX_SERVER_CONTROL_COMMANDS,
        error: (bytes.len() > MAX_SERVER_CONTROL_BYTES)
            .then_some(ServerControlParseError::Oversized),
    }
}

impl<'a> Iterator for ServerControlMessages<'a> {
    type Item = Result<ServerControlMessage<'a>, ServerControlParseError>;

    fn next(&mut self) -> Option<Self::Item> {
        if let Some(error) = self.error.take() {
            self.bytes = &[];
            return Some(Err(error));
        }
        if self.bytes.is_empty() {
            return None;
        }
        let error = if self.remaining == 0 {
            Some(ServerControlParseError::TooManyCommands)
        } else if self.bytes.len() < 4 {
            Some(ServerControlParseError::Truncated)
        } else {
            None
        };
        if let Some(error) = error {
            self.bytes = &[];
            return Some(Err(error));
        }
        let code = u16::from_le_bytes([self.bytes[0], self.bytes[1]]);
        let length = usize::from(u16::from_le_bytes([self.bytes[2], self.bytes[3]]));
        let Some(payload) = self.bytes.get(4..4 + length) else {
            self.bytes = &[];
            return Some(Err(ServerControlParseError::Truncated));
        };
        self.bytes = &self.bytes[4 + length..];
        self.remaining -= 1;
        Some(decode_control_message(code, payload))
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct ServerControlDiagnostics {
    pub(crate) unknown: u64,
    pub(crate) last_unknown: Option<u16>,
    pub(crate) malformed: u64,
    pub(crate) invalid_stream: u64,
}

fn decode_control_message(
    code: u16,
    payload: &[u8],
) -> Result<ServerControlMessage<'_>, ServerControlParseError> {
    let word = |offset| {
        payload
            .get(offset..offset + 4)
            .and_then(|bytes| bytes.try_into().ok())
            .map(u32::from_le_bytes)
            .ok_or(ServerControlParseError::Truncated)
    };
    Ok(match code {
        0x104 | 0x105 => ServerControlMessage::Timer(NvstSessionTimer {
            timer_type: if code == 0x104 { 4 } else { 5 },
            seconds_left: word(0)?,
            active: code == 0x104,
        }),
        0x109 => ServerControlMessage::Termination {
            wire_code: u32::from_be_bytes(
                payload
                    .get(..4)
                    .and_then(|bytes| bytes.try_into().ok())
                    .ok_or(ServerControlParseError::Truncated)?,
            ),
            extra: &payload[4..],
        },
        0x112 => {
            if payload.len() != 8 {
                return Err(ServerControlParseError::InvalidLength);
            }
            ServerControlMessage::FramePacingMode {
                stream_index: word(0)?,
                mode: if word(4)? == 1 {
                    FramePacingFeedbackMode::Interval
                } else {
                    FramePacingFeedbackMode::Never
                },
            }
        }
        0x101..=0x103 | 0x106 | 0x10a..=0x111 | 0x113 | 0x206 | 0x325 | 0x408 => {
            ServerControlMessage::Unhandled
        }
        _ => ServerControlMessage::Unknown(code),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn server_control_envelopes_are_walked_without_searching_payload_bytes() {
        let bytes = [
            0x99, 0x01, 4, 0, 0x09, 0x01, 0, 0, 0x05, 0x01, 4, 0, 0, 0, 0, 0,
        ];
        assert_eq!(
            server_control_messages(&bytes).collect::<Vec<_>>(),
            vec![
                Ok(ServerControlMessage::Unknown(0x199)),
                Ok(ServerControlMessage::Timer(NvstSessionTimer {
                    timer_type: 5,
                    seconds_left: 0,
                    active: false,
                }))
            ]
        );
    }

    #[test]
    fn server_control_envelopes_reject_truncation_without_partial_payloads() {
        for bytes in [&[4_u8, 1][..], &[4, 1, 4, 0, 60][..]] {
            let mut messages = server_control_messages(bytes);
            assert_eq!(
                messages.next(),
                Some(Err(ServerControlParseError::Truncated))
            );
            assert_eq!(messages.next(), None);
        }
    }

    #[test]
    fn server_control_work_and_bytes_are_bounded() {
        let maximum = command(0x199, &vec![0; usize::from(u16::MAX)]);
        assert_eq!(
            server_control_messages(&maximum).next(),
            Some(Ok(ServerControlMessage::Unknown(0x199)))
        );
        assert_eq!(
            server_control_messages(&vec![0; MAX_SERVER_CONTROL_BYTES + 1]).next(),
            Some(Err(ServerControlParseError::Oversized))
        );
        let bytes = [0x00, 0x01, 0, 0].repeat(MAX_SERVER_CONTROL_COMMANDS + 1);
        let messages = server_control_messages(&bytes).collect::<Vec<_>>();
        assert_eq!(messages.len(), MAX_SERVER_CONTROL_COMMANDS + 1);
        assert_eq!(
            messages.last(),
            Some(&Err(ServerControlParseError::TooManyCommands))
        );
        assert!(
            messages[..MAX_SERVER_CONTROL_COMMANDS]
                .iter()
                .all(Result::is_ok)
        );
    }

    fn command(code: u16, payload: &[u8]) -> Vec<u8> {
        let mut bytes = code.to_le_bytes().to_vec();
        bytes.extend_from_slice(&(payload.len() as u16).to_le_bytes());
        bytes.extend_from_slice(payload);
        bytes
    }

    #[test]
    fn idle_timers_preserve_little_endian_seconds_and_timer_type() {
        for (code, timer_type) in [(0x104, 4), (0x105, 5)] {
            let bytes = command(code, &60_u32.to_le_bytes());
            assert_eq!(
                server_control_messages(&bytes).next(),
                Some(Ok(ServerControlMessage::Timer(NvstSessionTimer {
                    timer_type,
                    seconds_left: 60,
                    active: timer_type == 4,
                })))
            );
            for length in 0..4 {
                assert_eq!(
                    server_control_messages(&command(code, &vec![0; length])).next(),
                    Some(Err(ServerControlParseError::Truncated))
                );
            }
        }
    }

    #[test]
    fn termination_preserves_network_order_result_and_opaque_tail() {
        let bytes = command(0x109, &[0x80, 0x03, 0x00, 0x23, 1, 2, 3]);
        assert_eq!(
            server_control_messages(&bytes).next(),
            Some(Ok(ServerControlMessage::Termination {
                wire_code: 0x8003_0023,
                extra: &bytes[8..],
            }))
        );
        assert_eq!(
            server_control_messages(&command(0x109, &[0; 3])).next(),
            Some(Err(ServerControlParseError::Truncated))
        );
    }

    #[test]
    fn server_feedback_mode_requires_exact_payload_and_normalizes_invalid_modes() {
        for (raw, mode) in [
            (0_u32, FramePacingFeedbackMode::Never),
            (1, FramePacingFeedbackMode::Interval),
            (2, FramePacingFeedbackMode::Never),
            (u32::MAX, FramePacingFeedbackMode::Never),
        ] {
            let payload = [2_u32.to_le_bytes(), raw.to_le_bytes()].concat();
            assert_eq!(
                server_control_messages(&command(0x112, &payload)).next(),
                Some(Ok(ServerControlMessage::FramePacingMode {
                    stream_index: 2,
                    mode
                }))
            );
        }
        for length in [0, 4, 7, 9, 12] {
            assert_eq!(
                server_control_messages(&command(0x112, &vec![0; length])).next(),
                Some(Err(ServerControlParseError::InvalidLength))
            );
        }
    }

    #[test]
    fn control_keepalive_failures_are_counted_and_reset_on_admission() {
        let mut health = ControlKeepaliveHealth::default();
        health.record(false);
        health.record(false);
        assert_eq!(health.failures, 2);
        assert_eq!(health.consecutive_failures, 2);
        health.record(true);
        assert_eq!(health.failures, 2);
        assert_eq!(health.consecutive_failures, 0);
        assert_eq!(health.recoveries, 1);
        health.record(true);
        assert_eq!(health.recoveries, 1);
    }

    #[test]
    fn control_keepalive_counters_saturate() {
        let mut health = ControlKeepaliveHealth {
            failures: u64::MAX,
            consecutive_failures: u32::MAX,
            recoveries: u64::MAX,
        };
        health.record(false);
        assert_eq!(health.failures, u64::MAX);
        assert_eq!(health.consecutive_failures, u32::MAX);
        health.record(true);
        assert_eq!(health.recoveries, u64::MAX);
        assert_eq!(health.consecutive_failures, 0);
    }

    #[test]
    fn geronimo_resumability_matches_reason_and_extended_code_predicate() {
        for code in 0x8004_0000..=0x8004_0017 {
            assert_eq!(
                session_resumable(Some(0x37), Some(code)),
                Some(!matches!(
                    code,
                    0x8004_0003 | 0x8004_000b..=0x8004_000f | 0x8004_0011..=0x8004_0016
                ))
            );
            assert_eq!(
                session_resumable(Some(0x3b), Some(code)),
                Some(matches!(code, 0x8004_0004 | 0x8004_0007 | 0x8004_0010))
            );
        }
        assert_eq!(
            session_resumable(Some(0x38), Some(0x8003_0007)),
            Some(false)
        );
        assert_eq!(session_resumable(None, Some(0x8004_0010)), None);
        assert_eq!(session_resumable(Some(0x37), None), None);
        assert_eq!(session_resumable(Some(0x3b), None), None);
    }
}
