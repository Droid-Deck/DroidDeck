use crate::{SonyContact, SonySnapshot};

pub const DS4_REPORT_ID: u8 = 0x01;
pub const DS4_REPORT_BYTES: usize = 64;
pub const DS4_INTERFACE: u8 = 4;
pub const DS4_LOW_ID_BASE: u8 = 6;
pub const DS4_TOUCH_SAMPLE_COUNT: u8 = 1;
pub const DS4_STATUS_BYTE: u8 = 0x1b;

pub const DS4_BUTTON_SQUARE: u16 = 0x4000;
pub const DS4_BUTTON_CROSS: u16 = 0x1000;
pub const DS4_BUTTON_CIRCLE: u16 = 0x2000;
pub const DS4_BUTTON_TRIANGLE: u16 = 0x8000;
pub const DS4_BUTTON_L1: u16 = 0x0100;
pub const DS4_BUTTON_R1: u16 = 0x0200;
pub const DS4_BUTTON_SHARE: u16 = 0x0020;
pub const DS4_BUTTON_OPTIONS: u16 = 0x0010;
pub const DS4_BUTTON_L3: u16 = 0x0040;
pub const DS4_BUTTON_R3: u16 = 0x0080;
pub const DS4_BUTTON_DPAD_UP: u16 = 0x0001;
pub const DS4_BUTTON_DPAD_DOWN: u16 = 0x0002;
pub const DS4_BUTTON_DPAD_LEFT: u16 = 0x0004;
pub const DS4_BUTTON_DPAD_RIGHT: u16 = 0x0008;

const HAT_TABLE: [[u8; 3]; 3] = [[0x07, 0x06, 0x05], [0x00, 0x08, 0x04], [0x01, 0x02, 0x03]];

pub fn low_id_for_slot(slot: u8) -> Option<u8> {
    (usize::from(slot) < crate::MAX_SOURCES).then_some(slot + DS4_LOW_ID_BASE)
}

pub fn slot_for_low_id(low_id: u8) -> Option<u8> {
    low_id
        .checked_sub(DS4_LOW_ID_BASE)
        .filter(|slot| usize::from(*slot) < crate::MAX_SOURCES)
}

pub fn normalize_touch_axis(value: f32) -> i16 {
    if value.is_nan() {
        return 0;
    }
    if value <= 0.0 {
        return i16::MIN;
    }
    if value >= 1.0 {
        return i16::MAX;
    }
    let scaled = ((2.0_f64 * f64::from(value)) - 1.0) as f32;
    if scaled < 0.0 {
        (scaled * 32768.0).round().clamp(-32768.0, 32767.0) as i16
    } else if scaled > 0.0 {
        (scaled * 32767.0).round().clamp(-32768.0, 32767.0) as i16
    } else {
        0
    }
}

pub fn encode_contact(tracking_id: u8, contact: SonyContact) -> [u8; 4] {
    let x = scale_axis(contact.x, 1919.0);
    let y = scale_axis(contact.y, 942.0);
    let mut bytes = [0_u8; 4];
    bytes[0] = (tracking_id & 0x7f) | if contact.active { 0 } else { 0x80 };
    bytes[1] = (x & 0xff) as u8;
    bytes[2] = (((x >> 8) & 0x0f) as u8) | (((y & 0x0f) as u8) << 4);
    bytes[3] = ((y >> 4) & 0xff) as u8;
    bytes
}

fn scale_axis(raw: i16, extent: f32) -> u16 {
    let value = ((f32::from(raw) + 32768.0) / 65535.0) * extent;
    value.clamp(0.0, extent) as u16
}

fn centered_axis(raw: i16) -> u8 {
    ((i32::from(raw) + 32768) >> 8) as u8
}

fn hat_byte(buttons: u16) -> u8 {
    let x = i32::from(buttons & DS4_BUTTON_DPAD_RIGHT != 0)
        - i32::from(buttons & DS4_BUTTON_DPAD_LEFT != 0);
    let y = i32::from(buttons & DS4_BUTTON_DPAD_DOWN != 0)
        - i32::from(buttons & DS4_BUTTON_DPAD_UP != 0);
    HAT_TABLE[(x + 1) as usize][(y + 1) as usize]
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ds4ReportState {
    contacts: [SonyContact; crate::CONTACTS_PER_SOURCE],
    tracking_ids: [u8; crate::CONTACTS_PER_SOURCE],
    touch_timestamp_byte: u8,
    last_observed_at_us: u64,
    seeded: bool,
}

impl Default for Ds4ReportState {
    fn default() -> Self {
        Self {
            contacts: [SonyContact::default(); crate::CONTACTS_PER_SOURCE],
            tracking_ids: [0; crate::CONTACTS_PER_SOURCE],
            touch_timestamp_byte: 0,
            last_observed_at_us: 0,
            seeded: false,
        }
    }
}

impl Ds4ReportState {
    pub fn tracking_id(&self, contact: usize) -> u8 {
        self.tracking_ids[contact]
    }

    pub fn touch_timestamp_byte(&self) -> u8 {
        self.touch_timestamp_byte
    }

    pub fn contacts(&self) -> &[SonyContact; crate::CONTACTS_PER_SOURCE] {
        &self.contacts
    }

    pub fn observe(&mut self, observed_at_us: u64) {
        self.last_observed_at_us = self.last_observed_at_us.max(observed_at_us);
    }

    pub fn last_observed_at_us(&self) -> u64 {
        self.last_observed_at_us
    }

    pub fn build(&mut self, snapshot: &SonySnapshot) -> [u8; DS4_REPORT_BYTES] {
        self.observe(snapshot.observed_at_us);
        let next = snapshot.contacts;
        for (index, contact) in next.iter().enumerate() {
            if contact.active && !self.contacts[index].active {
                self.tracking_ids[index] = self.tracking_ids[index].wrapping_add(1) & 0x7f;
            }
        }
        if !self.seeded || next != self.contacts {
            self.touch_timestamp_byte = (snapshot.observed_at_us / 1000) as u8;
            self.seeded = true;
        }
        self.contacts = next;

        let mut report = [0_u8; DS4_REPORT_BYTES];
        report[0] = DS4_REPORT_ID;
        report[1] = centered_axis(snapshot.left_stick_x);
        report[2] = centered_axis(snapshot.left_stick_y);
        report[3] = centered_axis(snapshot.right_stick_x);
        report[4] = centered_axis(snapshot.right_stick_y);
        report[5] = hat_byte(snapshot.buttons)
            | if snapshot.buttons & DS4_BUTTON_SQUARE != 0 {
                0x10
            } else {
                0
            }
            | if snapshot.buttons & DS4_BUTTON_CROSS != 0 {
                0x20
            } else {
                0
            }
            | if snapshot.buttons & DS4_BUTTON_CIRCLE != 0 {
                0x40
            } else {
                0
            }
            | if snapshot.buttons & DS4_BUTTON_TRIANGLE != 0 {
                0x80
            } else {
                0
            };
        report[6] = if snapshot.buttons & DS4_BUTTON_L1 != 0 {
            0x01
        } else {
            0
        } | if snapshot.buttons & DS4_BUTTON_R1 != 0 {
            0x02
        } else {
            0
        } | if snapshot.left_trigger != 0 { 0x04 } else { 0 }
            | if snapshot.right_trigger != 0 { 0x08 } else { 0 }
            | if snapshot.buttons & DS4_BUTTON_SHARE != 0 {
                0x10
            } else {
                0
            }
            | if snapshot.buttons & DS4_BUTTON_OPTIONS != 0 {
                0x20
            } else {
                0
            }
            | if snapshot.buttons & DS4_BUTTON_L3 != 0 {
                0x40
            } else {
                0
            }
            | if snapshot.buttons & DS4_BUTTON_R3 != 0 {
                0x80
            } else {
                0
            };
        report[7] = if snapshot.touchpad_click { 0x02 } else { 0 };
        report[8] = snapshot.left_trigger;
        report[9] = snapshot.right_trigger;
        let counter = snapshot
            .observed_at_us
            .wrapping_mul(3)
            .wrapping_shr(4)
            .to_le_bytes();
        report[10] = counter[0];
        report[11] = counter[1];
        report[30] = DS4_STATUS_BYTE;
        report[33] = DS4_TOUCH_SAMPLE_COUNT;
        report[34] = self.touch_timestamp_byte;
        report[35..39].copy_from_slice(&encode_contact(self.tracking_ids[0], next[0]));
        report[39..43].copy_from_slice(&encode_contact(self.tracking_ids[1], next[1]));
        report
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::MAX_SOURCES;

    fn contact(active: bool, x: i16, y: i16) -> SonyContact {
        SonyContact { active, x, y }
    }

    fn snapshot_with(contacts: [SonyContact; 2]) -> SonySnapshot {
        let mut snapshot = SonySnapshot::neutral(0, 1, 0);
        snapshot.contacts = contacts;
        snapshot
    }

    #[test]
    fn normalization_matches_the_proved_vectors() {
        assert_eq!(normalize_touch_axis(0.0), -32768);
        assert_eq!(normalize_touch_axis(0.25), -16384);
        assert_eq!(normalize_touch_axis(0.5), 0);
        assert_eq!(normalize_touch_axis(0.75), 16384);
        assert_eq!(normalize_touch_axis(1.0), 32767);
        assert_eq!(normalize_touch_axis(-0.25), -32768);
        assert_eq!(normalize_touch_axis(1.5), 32767);
        assert_eq!(normalize_touch_axis(f32::NAN), 0);
        assert_eq!(normalize_touch_axis(f32::INFINITY), 32767);
        assert_eq!(normalize_touch_axis(f32::NEG_INFINITY), -32768);
    }

    #[test]
    fn normalization_keeps_the_float32_intermediate() {
        assert_eq!(normalize_touch_axis(f32::from_bits(0x3e7f_fe01)), -16385);
    }

    #[test]
    fn contact_encoding_matches_the_proved_vectors_byte_for_byte() {
        let cases: [(i16, i16, bool, [u8; 4]); 6] = [
            (-32768, -32768, true, [0x01, 0x00, 0x00, 0x00]),
            (0, 0, false, [0x81, 0xbf, 0x73, 0x1d]),
            (0, 1, true, [0x01, 0xbf, 0x73, 0x1d]),
            (1, 0, true, [0x01, 0xbf, 0x73, 0x1d]),
            (16384, -16384, true, [0x01, 0x9f, 0xb5, 0x0e]),
            (32767, 32767, true, [0x01, 0x7f, 0xe7, 0x3a]),
        ];
        for (x, y, active, expected) in cases {
            assert_eq!(
                encode_contact(1, contact(active, x, y)),
                expected,
                "contact vector ({x},{y}) active={active}"
            );
        }
    }

    #[test]
    fn tracking_ids_increment_only_on_activation_and_start_at_one() {
        let mut state = Ds4ReportState::default();
        let _ = state.build(&snapshot_with([SonyContact::default(); 2]));
        assert_eq!(state.tracking_id(0), 0);
        let center = contact(true, 0, 0);
        let _ = state.build(&snapshot_with([center, SonyContact::default()]));
        assert_eq!(state.tracking_id(0), 1);
        let _ = state.build(&snapshot_with([
            contact(true, 100, 100),
            SonyContact::default(),
        ]));
        assert_eq!(state.tracking_id(0), 1);
        let _ = state.build(&snapshot_with([
            contact(true, 100, 100),
            contact(true, -100, -100),
        ]));
        assert_eq!(state.tracking_id(0), 1);
        assert_eq!(state.tracking_id(1), 1);
        let _ = state.build(&snapshot_with([center, contact(true, -100, -100)]));
        assert_eq!(state.tracking_id(0), 1);
        let _ = state.build(&snapshot_with([
            SonyContact::default(),
            contact(true, -100, -100),
        ]));
        let _ = state.build(&snapshot_with([center, contact(true, -100, -100)]));
        assert_eq!(state.tracking_id(0), 2);
    }

    #[test]
    fn tracking_ids_wrap_modulo_one_twenty_eight() {
        let mut state = Ds4ReportState::default();
        let up = SonyContact::default();
        let down = contact(true, 0, 0);
        let _ = state.build(&snapshot_with([up, up]));
        for _ in 0..127 {
            let _ = state.build(&snapshot_with([down, up]));
            let _ = state.build(&snapshot_with([up, up]));
        }
        assert_eq!(state.tracking_id(0), 127);
        let _ = state.build(&snapshot_with([down, up]));
        assert_eq!(state.tracking_id(0), 0);
    }

    #[test]
    fn first_build_always_latches_a_deterministic_touch_timestamp() {
        let mut state = Ds4ReportState::default();
        let mut snapshot = SonySnapshot::neutral(0, 1, 1_500_000);
        let report = state.build(&snapshot);
        assert_eq!(state.touch_timestamp_byte(), (1_500_000_u64 / 1000) as u8);
        assert_eq!(report[34], state.touch_timestamp_byte());
        let latched = state.touch_timestamp_byte();
        snapshot.observed_at_us = 9_999_000;
        let _ = state.build(&snapshot);
        assert_eq!(state.touch_timestamp_byte(), latched);
    }

    #[test]
    fn touch_timestamp_refreshes_on_every_contact_tuple_change() {
        let mut state = Ds4ReportState::default();
        let mut snapshot = SonySnapshot::neutral(0, 1, 1_000_000);
        let _ = state.build(&snapshot);
        assert_eq!(state.touch_timestamp_byte(), (1_000_000_u64 / 1000) as u8);
        snapshot.observed_at_us = 2_000_000;
        snapshot.contacts[0] = contact(true, 0, 0);
        let _ = state.build(&snapshot);
        assert_eq!(state.touch_timestamp_byte(), (2_000_000_u64 / 1000) as u8);
        snapshot.observed_at_us = 3_000_000;
        snapshot.contacts[0] = contact(false, 0, 0);
        let _ = state.build(&snapshot);
        assert_eq!(state.touch_timestamp_byte(), (3_000_000_u64 / 1000) as u8);
        snapshot.observed_at_us = 4_000_000;
        snapshot.contacts[1] = contact(true, 1, 2);
        let _ = state.build(&snapshot);
        assert_eq!(state.touch_timestamp_byte(), (4_000_000_u64 / 1000) as u8);
    }

    #[test]
    fn every_report_owns_all_sixty_four_bytes_with_fixed_values() {
        let mut state = Ds4ReportState::default();
        let mut snapshot = SonySnapshot::neutral(0, 1, 2_500);
        snapshot.buttons = DS4_BUTTON_SQUARE | DS4_BUTTON_DPAD_UP;
        snapshot.left_trigger = 0x40;
        snapshot.right_trigger = 0x80;
        snapshot.touchpad_click = true;
        snapshot.contacts[0] = contact(true, 16384, -16384);
        let report = state.build(&snapshot);
        assert_eq!(report.len(), DS4_REPORT_BYTES);
        assert_eq!(
            &report[0..10],
            &[0x01, 128, 128, 128, 128, 0x10, 0x0c, 0x02, 0x40, 0x80]
        );
        assert_eq!(report[10], ((2_500_u64 * 3) >> 4) as u8);
        assert_eq!(report[11], ((2_500_u64 * 3) >> 12) as u8);
        assert_eq!(report[12], 0);
        assert!(report[13..30].iter().all(|byte| *byte == 0));
        assert_eq!(report[30], DS4_STATUS_BYTE);
        assert_eq!(report[31], 0);
        assert_eq!(report[32], 0);
        assert_eq!(report[33], DS4_TOUCH_SAMPLE_COUNT);
        assert_eq!(&report[35..39], &[0x01, 0x9f, 0xb5, 0x0e]);
        assert_eq!(&report[39..43], &[0x80, 0xbf, 0x73, 0x1d]);
        assert!(report[43..].iter().all(|byte| *byte == 0));
    }

    #[test]
    fn ps_bit_stays_clear_even_when_every_button_bit_is_set() {
        let mut state = Ds4ReportState::default();
        let mut snapshot = SonySnapshot::neutral(0, 1, 0);
        snapshot.buttons = 0xffff;
        let report = state.build(&snapshot);
        assert_eq!(report[7] & 0x01, 0);
        assert_eq!(report[7] & 0x02, 0);
        assert_eq!(report[7], 0);
    }

    #[test]
    fn capture_loss_neutral_releases_contacts_at_their_last_positions() {
        let mut state = Ds4ReportState::default();
        let mut snapshot = SonySnapshot::neutral(0, 1, 0);
        snapshot.contacts[0] = contact(true, 32767, 32767);
        snapshot.contacts[1] = contact(true, -32768, -32768);
        let _ = state.build(&snapshot);
        let mut release = SonySnapshot::neutral(0, 1, 1000);
        release.contacts[0] = contact(false, 32767, 32767);
        release.contacts[1] = contact(false, -32768, -32768);
        let report = state.build(&release);
        assert_eq!(&report[1..5], &[128, 128, 128, 128]);
        assert_eq!(report[5], 0x08);
        assert_eq!(report[6], 0);
        assert_eq!(report[7], 0);
        assert_eq!(&report[35..39], &[0x81, 0x7f, 0xe7, 0x3a]);
        assert_eq!(&report[39..43], &[0x81, 0x00, 0x00, 0x00]);
    }

    #[test]
    fn two_active_center_contacts_remain_independently_active() {
        let mut state = Ds4ReportState::default();
        let center = contact(true, 0, 0);
        let report = state.build(&snapshot_with([center, center]));
        assert_eq!(report[35], 0x01);
        assert_eq!(report[39], 0x01);
        assert_eq!(&report[35..39], &report[39..43]);
        assert_eq!(state.tracking_id(0), 1);
        assert_eq!(state.tracking_id(1), 1);
    }

    #[test]
    fn hat_table_covers_the_full_grid() {
        assert_eq!(hat_byte(0), 0x08);
        assert_eq!(hat_byte(DS4_BUTTON_DPAD_UP), 0x00);
        assert_eq!(hat_byte(DS4_BUTTON_DPAD_RIGHT), 0x02);
        assert_eq!(hat_byte(DS4_BUTTON_DPAD_DOWN), 0x04);
        assert_eq!(hat_byte(DS4_BUTTON_DPAD_LEFT), 0x06);
        assert_eq!(hat_byte(DS4_BUTTON_DPAD_UP | DS4_BUTTON_DPAD_RIGHT), 0x01);
        assert_eq!(hat_byte(DS4_BUTTON_DPAD_DOWN | DS4_BUTTON_DPAD_LEFT), 0x05);
        assert_eq!(hat_byte(DS4_BUTTON_DPAD_UP | DS4_BUTTON_DPAD_LEFT), 0x07);
        assert_eq!(hat_byte(DS4_BUTTON_DPAD_DOWN | DS4_BUTTON_DPAD_RIGHT), 0x03);
    }

    #[test]
    fn low_id_mapping_round_trips_within_the_reserved_domain() {
        for slot in 0..MAX_SOURCES as u8 {
            let low_id = low_id_for_slot(slot).unwrap();
            assert_eq!(slot_for_low_id(low_id), Some(slot));
        }
        assert_eq!(low_id_for_slot(MAX_SOURCES as u8), None);
        assert_eq!(slot_for_low_id(5), None);
        assert_eq!(slot_for_low_id(10), None);
    }
}
