use std::collections::BTreeMap;
use std::fmt;

const MAX_DESCRIPTOR_BYTES: usize = 4096;
const MAX_REPORT_BYTES: usize = 256;
const MAX_REPORT_IDS: usize = 64;
const MAX_FIELDS: usize = 1024;
const MAX_USAGES: usize = 1024;
const MAX_DEPTH: usize = 32;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DescriptorError {
    Malformed,
    Unsupported,
    Ambiguous,
    LimitExceeded,
    UnknownReportId(u8),
    ShortReport,
    ReportTooLong,
}

impl fmt::Display for DescriptorError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Malformed => formatter.write_str("malformed HID descriptor"),
            Self::Unsupported => formatter.write_str("unsupported HID descriptor item"),
            Self::Ambiguous => formatter.write_str("ambiguous HID report layout"),
            Self::LimitExceeded => formatter.write_str("HID descriptor exceeds bounded limits"),
            Self::UnknownReportId(id) => write!(formatter, "unknown HID input report ID {id}"),
            Self::ShortReport => formatter.write_str("HID input report is too short"),
            Self::ReportTooLong => formatter.write_str("HID input report exceeds 256 bytes"),
        }
    }
}

impl std::error::Error for DescriptorError {}

#[derive(Debug, Clone)]
pub struct ReportDescriptor {
    reports: BTreeMap<u8, ReportLayout>,
    numbered: bool,
}

#[derive(Debug, Clone, Default)]
struct ReportLayout {
    bits: [usize; 3],
    release_fields: Vec<ReleaseField>,
}

#[derive(Debug, Clone, Copy)]
struct ReleaseField {
    offset: usize,
    width: usize,
    value: u32,
}

#[derive(Clone, Copy, Default)]
struct Globals {
    usage_page: u16,
    logical_min: i64,
    logical_max: i64,
    size: usize,
    count: usize,
    report_id: u8,
}

#[derive(Default)]
struct Locals {
    usages: Vec<u32>,
    minimum: Option<u32>,
}

impl Locals {
    fn usage(&mut self, usage: u32) -> Result<(), DescriptorError> {
        if self.minimum.is_some() {
            return Err(DescriptorError::Ambiguous);
        }
        if self.usages.len() == MAX_USAGES {
            return Err(DescriptorError::LimitExceeded);
        }
        self.usages.push(usage);
        Ok(())
    }

    fn maximum(&mut self, maximum: u32) -> Result<(), DescriptorError> {
        let minimum = self.minimum.take().ok_or(DescriptorError::Malformed)?;
        if minimum > maximum || minimum >> 16 != maximum >> 16 {
            return Err(DescriptorError::Malformed);
        }
        let count = (maximum - minimum) as usize + 1;
        if count > MAX_USAGES - self.usages.len() {
            return Err(DescriptorError::LimitExceeded);
        }
        self.usages.extend(minimum..=maximum);
        Ok(())
    }
}

impl ReportDescriptor {
    pub fn parse(bytes: &[u8]) -> Result<Self, DescriptorError> {
        if bytes.len() > MAX_DESCRIPTOR_BYTES {
            return Err(DescriptorError::LimitExceeded);
        }
        let mut descriptor = Self {
            reports: BTreeMap::new(),
            numbered: false,
        };
        let mut globals = Globals::default();
        let mut stack = Vec::new();
        let mut locals = Locals::default();
        let mut depth = 0_usize;
        let mut field_count = 0_usize;
        let mut cursor = 0;
        while cursor < bytes.len() {
            let prefix = bytes[cursor];
            cursor += 1;
            if prefix == 0xfe {
                return Err(DescriptorError::Unsupported);
            }
            let length = match prefix & 3 {
                3 => 4,
                length => usize::from(length),
            };
            let data = bytes
                .get(cursor..cursor + length)
                .ok_or(DescriptorError::Malformed)?;
            cursor += length;
            let value = unsigned(data);
            let tag = prefix >> 4;
            match (prefix >> 2) & 3 {
                0 => {
                    if locals.minimum.is_some() {
                        return Err(DescriptorError::Malformed);
                    }
                    match tag {
                        8 | 9 | 11 => {
                            if length == 0 || depth == 0 {
                                return Err(DescriptorError::Malformed);
                            }
                            if value & !0x1ff != 0 || value & 0x100 != 0 {
                                return Err(DescriptorError::Unsupported);
                            }
                            let kind = match tag {
                                8 => 0,
                                9 => 1,
                                _ => 2,
                            };
                            descriptor.add_fields(
                                globals,
                                &locals,
                                kind,
                                value,
                                &mut field_count,
                            )?;
                        }
                        10 => {
                            if length != 1 || value > 6 {
                                return Err(DescriptorError::Unsupported);
                            }
                            if depth == MAX_DEPTH {
                                return Err(DescriptorError::LimitExceeded);
                            }
                            depth += 1;
                        }
                        12 => {
                            if length != 0 || depth == 0 {
                                return Err(DescriptorError::Malformed);
                            }
                            depth -= 1;
                        }
                        _ => return Err(DescriptorError::Unsupported),
                    }
                    locals = Locals::default();
                }
                1 => match tag {
                    10 => {
                        if length != 0 {
                            return Err(DescriptorError::Malformed);
                        }
                        if stack.len() == MAX_DEPTH {
                            return Err(DescriptorError::LimitExceeded);
                        }
                        stack.push(globals);
                    }
                    11 => {
                        if length != 0 {
                            return Err(DescriptorError::Malformed);
                        }
                        globals = stack.pop().ok_or(DescriptorError::Malformed)?;
                    }
                    0..=9 => {
                        if length == 0 {
                            return Err(DescriptorError::Malformed);
                        }
                        match tag {
                            0 => {
                                globals.usage_page = u16::try_from(value)
                                    .map_err(|_| DescriptorError::Unsupported)?;
                            }
                            1 => globals.logical_min = signed(data),
                            2 => {
                                globals.logical_max = if globals.logical_min < 0 {
                                    signed(data)
                                } else {
                                    i64::from(value)
                                };
                            }
                            3..=6 => {}
                            7 => globals.size = value as usize,
                            8 => {
                                if length != 1 || value == 0 {
                                    return Err(DescriptorError::Malformed);
                                }
                                globals.report_id = value as u8;
                                descriptor.numbered = true;
                                descriptor.layout(globals.report_id)?;
                            }
                            9 => globals.count = value as usize,
                            _ => unreachable!(),
                        }
                    }
                    _ => return Err(DescriptorError::Unsupported),
                },
                2 => {
                    if length == 0 {
                        return Err(DescriptorError::Malformed);
                    }
                    let usage = if length == 4 {
                        value
                    } else {
                        (u32::from(globals.usage_page) << 16) | value
                    };
                    match tag {
                        0 => locals.usage(usage)?,
                        1 => {
                            if locals.minimum.replace(usage).is_some() {
                                return Err(DescriptorError::Malformed);
                            }
                        }
                        2 => locals.maximum(usage)?,
                        3..=5 | 7..=9 => {}
                        _ => return Err(DescriptorError::Unsupported),
                    }
                }
                _ => return Err(DescriptorError::Unsupported),
            }
        }
        if depth != 0 || !stack.is_empty() || locals.minimum.is_some() || !locals.usages.is_empty()
        {
            return Err(DescriptorError::Malformed);
        }
        if descriptor.numbered && descriptor.reports.contains_key(&0) {
            return Err(DescriptorError::Ambiguous);
        }
        if !descriptor
            .reports
            .values()
            .any(|report| report.bits[0] != 0)
        {
            return Err(DescriptorError::Malformed);
        }
        Ok(descriptor)
    }

    pub fn report_id(&self, report: &[u8]) -> Result<u8, DescriptorError> {
        if report.len() > MAX_REPORT_BYTES {
            return Err(DescriptorError::ReportTooLong);
        }
        let id = if self.numbered {
            *report.first().ok_or(DescriptorError::ShortReport)?
        } else {
            0
        };
        let layout = self
            .reports
            .get(&id)
            .filter(|layout| layout.bits[0] != 0)
            .ok_or(DescriptorError::UnknownReportId(id))?;
        let required = layout.bits[0].div_ceil(8) + usize::from(self.numbered);
        if report.len() < required {
            return Err(DescriptorError::ShortReport);
        }
        Ok(id)
    }

    pub fn release(&self, report: &[u8]) -> Result<Vec<u8>, DescriptorError> {
        let id = self.report_id(report)?;
        let layout = &self.reports[&id];
        let mut released = report.to_vec();
        let payload_offset = usize::from(self.numbered) * 8;
        for field in &layout.release_fields {
            for bit in 0..field.width {
                let offset = payload_offset + field.offset + bit;
                let mask = 1 << (offset % 8);
                let byte = &mut released[offset / 8];
                if field.value & (1 << bit) != 0 {
                    *byte |= mask;
                } else {
                    *byte &= !mask;
                }
            }
        }
        Ok(released)
    }

    fn layout(&mut self, id: u8) -> Result<&mut ReportLayout, DescriptorError> {
        if !self.reports.contains_key(&id) && self.reports.len() == MAX_REPORT_IDS {
            return Err(DescriptorError::LimitExceeded);
        }
        Ok(self.reports.entry(id).or_default())
    }

    fn add_fields(
        &mut self,
        globals: Globals,
        locals: &Locals,
        kind: usize,
        flags: u32,
        field_count: &mut usize,
    ) -> Result<(), DescriptorError> {
        if globals.size == 0 || globals.count == 0 {
            return Err(DescriptorError::Malformed);
        }
        if globals.size > 32 || globals.count > MAX_FIELDS - *field_count {
            return Err(DescriptorError::LimitExceeded);
        }
        *field_count += globals.count;
        let numbered = self.numbered || globals.report_id != 0;
        let report = self.layout(globals.report_id)?;
        let offset = report.bits[kind];
        let bits = globals.size * globals.count;
        let next = offset + bits;
        if next.div_ceil(8) + usize::from(numbered) > MAX_REPORT_BYTES {
            return Err(DescriptorError::LimitExceeded);
        }
        report.bits[kind] = next;
        if flags & 1 != 0 {
            return Ok(());
        }
        if locals.usages.is_empty() || globals.logical_min > globals.logical_max {
            return Err(DescriptorError::Malformed);
        }
        let (minimum, maximum) = field_range(globals.size, globals.logical_min < 0);
        if globals.logical_min < minimum || globals.logical_max > maximum {
            return Err(DescriptorError::Malformed);
        }
        if kind != 0 {
            return Ok(());
        }
        let variable = flags & 2 != 0;
        if variable && locals.usages.len() > globals.count {
            return Err(DescriptorError::Ambiguous);
        }
        if !variable {
            let all_buttons = locals.usages.iter().all(|usage| usage >> 16 == 9);
            let any_buttons = locals.usages.iter().any(|usage| usage >> 16 == 9);
            let any_hats = locals.usages.contains(&0x0001_0039);
            if any_hats || any_buttons != all_buttons {
                return Err(DescriptorError::Ambiguous);
            }
        }
        for index in 0..globals.count {
            let usage = locals.usages[index.min(locals.usages.len() - 1)];
            let value = if usage >> 16 == 9 {
                Some(0)
            } else if variable && usage == 0x0001_0039 && flags & 0x40 != 0 {
                let null = if globals.logical_max < maximum {
                    globals.logical_max + 1
                } else if globals.logical_min > minimum {
                    globals.logical_min - 1
                } else {
                    return Err(DescriptorError::Unsupported);
                };
                Some(null as u32)
            } else {
                None
            };
            if let Some(value) = value {
                report.release_fields.push(ReleaseField {
                    offset: offset + index * globals.size,
                    width: globals.size,
                    value,
                });
            }
        }
        Ok(())
    }
}

fn unsigned(data: &[u8]) -> u32 {
    data.iter().enumerate().fold(0, |value, (index, byte)| {
        value | (u32::from(*byte) << (index * 8))
    })
}

fn signed(data: &[u8]) -> i64 {
    let value = i64::from(unsigned(data));
    let bits = data.len() * 8;
    if value & (1 << (bits - 1)) != 0 {
        value - (1 << bits)
    } else {
        value
    }
}

fn field_range(width: usize, signed: bool) -> (i64, i64) {
    if signed {
        (-(1_i64 << (width - 1)), (1_i64 << (width - 1)) - 1)
    } else {
        (0, (1_i64 << width) - 1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Synthetic fixtures are constructed here; report examples are synthetic even for real descriptors.
    const SYNTHETIC_PACKED: &[u8] = &[
        0x05, 0x01, 0x09, 0x04, 0xa1, 0x01, 0x85, 0x07, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15,
        0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02, 0x75, 0x02, 0x95, 0x01, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x39, 0x25, 0x07, 0x75, 0x04, 0x81, 0x42, 0x09, 0x30, 0x26, 0xff, 0x00,
        0x75, 0x08, 0x81, 0x02, 0x09, 0x39, 0x25, 0x07, 0x75, 0x04, 0x81, 0x02, 0x75, 0x03, 0x81,
        0x01, 0xc0,
    ];

    // Real 044f:b351 Cougar MFD descriptor published by Luiz Renault, March 28, 2022:
    // https://forum.dcs.world/topic/297091-in-game-thrustmaster-warthog-and-cougar-mfd-brightness-control-mod/
    const REAL_COUGAR_MFD: &[u8] = &[
        0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x05, 0x09, 0x19, 0x01, 0x29, 0x1c, 0x15, 0x00, 0x25,
        0x01, 0x35, 0x00, 0x45, 0x01, 0x75, 0x01, 0x95, 0x1c, 0x81, 0x02, 0x75, 0x04, 0x95, 0x01,
        0x81, 0x01, 0x06, 0x00, 0xff, 0x09, 0x01, 0x75, 0x10, 0x95, 0x01, 0xb1, 0x02, 0xc0,
    ];

    // Real 044f:b315 Dual Analog 4, USB Device Tree Viewer capture published January 4, 2014:
    // http://www.classiccomp.de/Reste/GamePads/GP3.html
    const REAL_DUAL_ANALOG_4: &[u8] = &[
        0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x05, 0x01, 0x09, 0x01, 0xa1, 0x00, 0x05, 0x09, 0x19,
        0x01, 0x29, 0x0c, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0c, 0x81, 0x02, 0x75, 0x08,
        0x95, 0x01, 0x81, 0x01, 0x05, 0x01, 0x09, 0x39, 0x25, 0x07, 0x35, 0x00, 0x46, 0x0e, 0x01,
        0x66, 0x40, 0x00, 0x75, 0x04, 0x81, 0x42, 0x09, 0x30, 0x09, 0x31, 0x15, 0x80, 0x25, 0x7f,
        0x46, 0xff, 0x00, 0x66, 0x00, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02, 0x09, 0x35, 0x95,
        0x01, 0x81, 0x02, 0x09, 0x36, 0x16, 0x00, 0x00, 0x26, 0xff, 0x00, 0x81, 0x02, 0x09, 0xbb,
        0x15, 0x00, 0x26, 0xff, 0x00, 0x35, 0x00, 0x46, 0xff, 0x00, 0x75, 0x08, 0x95, 0x04, 0x91,
        0x02, 0xc0, 0xc0,
    ];

    // Real allowlisted 044f:0402 Warthog stick, sysfs capture published by ho0ver, January 2024:
    // https://forums.x-plane.org/forums/topic/299033-xp12-linux-winwing-orion-2-throttle-has-too-many-buttons/
    // The posted lsusb line is a different device; the capture path and later correction identify the stick.
    const REAL_WARTHOG_STICK: &[u8] = &[
        0x05, 0x01, 0x09, 0x04, 0xa1, 0x01, 0x85, 0x01, 0x05, 0x09, 0x19, 0x01, 0x29, 0x13, 0x15,
        0x00, 0x25, 0x01, 0x35, 0x00, 0x45, 0x01, 0x75, 0x01, 0x95, 0x13, 0x81, 0x02, 0x75, 0x01,
        0x95, 0x01, 0x81, 0x01, 0x05, 0x01, 0x09, 0x39, 0x25, 0x07, 0x46, 0x3b, 0x01, 0x65, 0x14,
        0x75, 0x04, 0x81, 0x42, 0x09, 0x30, 0x09, 0x31, 0x65, 0x00, 0x27, 0xff, 0xff, 0x00, 0x00,
        0x47, 0xff, 0xff, 0x00, 0x00, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02, 0x06, 0xff, 0x00, 0x09,
        0x01, 0x09, 0x02, 0x81, 0x02, 0x09, 0xbb, 0x75, 0x08, 0x95, 0x0b, 0x26, 0xff, 0x00, 0x46,
        0xff, 0x00, 0x91, 0x02, 0x85, 0x02, 0x06, 0xff, 0x00, 0x09, 0x03, 0x81, 0x02, 0xc0,
    ];

    fn synthetic(fields: &[u8]) -> Vec<u8> {
        let mut bytes = vec![0x05, 0x01, 0x09, 0x04, 0xa1, 0x01];
        bytes.extend_from_slice(fields);
        bytes.push(0xc0);
        bytes
    }

    fn button_fields() -> Vec<u8> {
        vec![
            0x05, 0x09, 0x19, 0x01, 0x29, 0x08, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08,
            0x81, 0x02,
        ]
    }

    #[test]
    fn packed_release_preserves_axes_constants_non_nullable_hat_and_id() {
        let descriptor = ReportDescriptor::parse(SYNTHETIC_PACKED).unwrap();
        let original = [0x07, 0x7f, 0xaa, 0xaa];
        assert_eq!(descriptor.report_id(&original), Ok(7));
        assert_eq!(
            descriptor.release(&original).unwrap(),
            [0x07, 0x18, 0xab, 0xaa]
        );
        assert_eq!(original, [0x07, 0x7f, 0xaa, 0xaa]);
        let released = descriptor.release(&original).unwrap();
        assert_eq!(descriptor.release(&released).unwrap(), released);
    }

    #[test]
    fn published_real_descriptors_have_exact_release_bytes() {
        assert_eq!(REAL_COUGAR_MFD.len(), 44);
        let mfd = ReportDescriptor::parse(REAL_COUGAR_MFD).unwrap();
        assert_eq!(mfd.report_id(&[0xff; 4]), Ok(0));
        assert_eq!(mfd.release(&[0xff; 4]).unwrap(), [0, 0, 0, 0xf0]);
        assert_eq!(REAL_DUAL_ANALOG_4.len(), 108);
        let pad = ReportDescriptor::parse(REAL_DUAL_ANALOG_4).unwrap();
        assert_eq!(
            pad.release(&[0xff, 0xdf, 0x35, 0x80, 0x7f, 0xab, 0xcd, 0xee])
                .unwrap(),
            [0x00, 0xd0, 0x85, 0x80, 0x7f, 0xab, 0xcd, 0xee]
        );
        assert_eq!(REAL_WARTHOG_STICK.len(), 104);
        let stick = ReportDescriptor::parse(REAL_WARTHOG_STICK).unwrap();
        assert_eq!(
            stick
                .release(&[
                    1, 0xff, 0xff, 0x3f, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef
                ])
                .unwrap(),
            [
                1, 0, 0, 0x88, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef
            ]
        );
        assert_eq!(stick.release(&[2; 12]).unwrap(), [2; 12]);
    }

    #[test]
    fn push_pop_restores_report_id_usage_page_size_and_logical_bounds() {
        let mut fields = vec![0x85, 0x01];
        fields.extend(button_fields());
        fields.extend([
            0xa4, 0x85, 0x02, 0x05, 0x01, 0x09, 0x30, 0x15, 0x80, 0x25, 0x7f, 0x75, 0x08, 0x95,
            0x01, 0x81, 0x02, 0xb4, 0x09, 0x01, 0x81, 0x02,
        ]);
        let descriptor = ReportDescriptor::parse(&synthetic(&fields)).unwrap();
        assert_eq!(descriptor.release(&[1, 0xff, 0xff]).unwrap(), [1, 0, 0]);
        assert_eq!(descriptor.release(&[2, 0x81]).unwrap(), [2, 0x81]);
        assert_eq!(descriptor.report_id(&[2, 0x81]), Ok(2));
    }

    #[test]
    fn usage_lists_ranges_and_extended_usages_clear_only_button_fields() {
        let descriptor = ReportDescriptor::parse(&synthetic(&[
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x06, 0x0b, 0x01, 0x00, 0x09, 0x00, 0x1b,
            0x02, 0x00, 0x09, 0x00, 0x2b, 0x04, 0x00, 0x09, 0x00, 0x0b, 0x30, 0x00, 0x01, 0x00,
            0x0b, 0x05, 0x00, 0x09, 0x00, 0x81, 0x02, 0x75, 0x02, 0x95, 0x01, 0x81, 0x01,
        ]))
        .unwrap();
        assert_eq!(descriptor.release(&[0xff]).unwrap(), [0xd0]);
    }

    #[test]
    fn button_arrays_clear_each_slot_and_do_not_change_vendor_arrays() {
        let descriptor = ReportDescriptor::parse(&synthetic(&[
            0x05, 0x09, 0x19, 0x00, 0x29, 0x0f, 0x15, 0x00, 0x25, 0x0f, 0x75, 0x04, 0x95, 0x03,
            0x81, 0x00, 0x06, 0x00, 0xff, 0x19, 0x00, 0x29, 0x0f, 0x95, 0x01, 0x81, 0x00,
        ]))
        .unwrap();
        assert_eq!(descriptor.release(&[0x21, 0xb3]).unwrap(), [0, 0xb0]);
    }

    #[test]
    fn unsigned_and_signed_hat_nulls_choose_a_representable_outside_value() {
        let descriptor = ReportDescriptor::parse(&synthetic(&[
            0x09, 0x39, 0x15, 0x01, 0x25, 0x0f, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, 0x09, 0x39,
            0x15, 0xfe, 0x25, 0x07, 0x81, 0x42, 0x09, 0x39, 0x15, 0xfc, 0x25, 0x03, 0x81, 0x42,
            0x75, 0x04, 0x81, 0x01,
        ]))
        .unwrap();
        assert_eq!(descriptor.release(&[0x51, 0xa2]).unwrap(), [0xd0, 0xa4]);
        let full_range = synthetic(&[
            0x09, 0x39, 0x15, 0x00, 0x25, 0x0f, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
        ]);
        assert!(matches!(
            ReportDescriptor::parse(&full_range),
            Err(DescriptorError::Unsupported)
        ));
    }

    #[test]
    fn signed_16_and_32_bit_axes_and_32_bit_hat_are_not_truncated() {
        let descriptor = ReportDescriptor::parse(&synthetic(&[
            0x09, 0x30, 0x16, 0x00, 0x80, 0x26, 0xff, 0x7f, 0x75, 0x10, 0x95, 0x01, 0x81, 0x02,
            0x09, 0x31, 0x17, 0x00, 0x00, 0x00, 0x80, 0x27, 0xff, 0xff, 0xff, 0x7f, 0x75, 0x20,
            0x81, 0x02, 0x09, 0x39, 0x17, 0x00, 0x00, 0x00, 0x00, 0x27, 0xfe, 0xff, 0xff, 0xff,
            0x81, 0x42,
        ]))
        .unwrap();
        assert_eq!(
            descriptor
                .release(&[0x00, 0x80, 0xff, 0xff, 0xff, 0x7f, 0x42, 0, 0, 0])
                .unwrap(),
            [0x00, 0x80, 0xff, 0xff, 0xff, 0x7f, 0xff, 0xff, 0xff, 0xff]
        );
    }

    #[test]
    fn collection_boundaries_reset_locals_but_preserve_report_offsets() {
        let mut descriptor = synthetic(&button_fields());
        descriptor.extend(synthetic(&[
            0x09, 0x30, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02,
        ]));
        let parsed = ReportDescriptor::parse(&descriptor).unwrap();
        assert_eq!(parsed.release(&[0xff, 0x67]).unwrap(), [0, 0x67]);
        let missing_usage = synthetic(&[0x75, 0x01, 0x95, 0x01, 0x15, 0, 0x25, 1, 0x81, 2]);
        assert!(matches!(
            ReportDescriptor::parse(&missing_usage),
            Err(DescriptorError::Malformed)
        ));
        let mut missing_next_usage = button_fields();
        missing_next_usage.extend([0x81, 2]);
        assert!(ReportDescriptor::parse(&synthetic(&missing_next_usage)).is_err());
    }

    #[test]
    fn output_and_feature_layouts_do_not_shift_input_offsets_or_admit_output_only_ids() {
        let mut fields = vec![0x85, 0x01];
        fields.extend(button_fields());
        fields.extend([0x09, 0x01, 0x91, 0x02, 0x09, 0x01, 0xb1, 0x02]);
        fields.extend([0x85, 0x02, 0x09, 0x01, 0x91, 0x02]);
        let descriptor = ReportDescriptor::parse(&synthetic(&fields)).unwrap();
        assert_eq!(descriptor.release(&[1, 0xff]).unwrap(), [1, 0]);
        assert_eq!(
            descriptor.release(&[2, 0xff]),
            Err(DescriptorError::UnknownReportId(2))
        );
    }

    #[test]
    fn report_errors_fail_closed_and_padding_is_preserved() {
        let numbered = ReportDescriptor::parse(SYNTHETIC_PACKED).unwrap();
        for report in [&[][..], &[7][..], &[7, 0xff, 0xff][..]] {
            assert_eq!(numbered.release(report), Err(DescriptorError::ShortReport));
            assert_eq!(
                numbered.report_id(report),
                Err(DescriptorError::ShortReport)
            );
        }
        assert_eq!(
            numbered.release(&[8; 4]),
            Err(DescriptorError::UnknownReportId(8))
        );
        assert_eq!(
            numbered.release(&[0; 4]),
            Err(DescriptorError::UnknownReportId(0))
        );
        assert_eq!(
            numbered.release(&[7; 257]),
            Err(DescriptorError::ReportTooLong)
        );
        let implicit = ReportDescriptor::parse(&synthetic(&button_fields())).unwrap();
        assert_eq!(implicit.release(&[]), Err(DescriptorError::ShortReport));
        assert_eq!(implicit.release(&[0xff, 0xab]).unwrap(), [0, 0xab]);
        assert_eq!(implicit.report_id(&[0xff]), Ok(0));
    }

    #[test]
    fn malformed_unsupported_and_ambiguous_items_are_rejected() {
        let mut mixed_ids = button_fields();
        mixed_ids.extend([0x85, 0x01, 0x09, 0x01, 0x81, 0x02]);
        assert!(matches!(
            ReportDescriptor::parse(&synthetic(&mixed_ids)),
            Err(DescriptorError::Ambiguous)
        ));
        let cases: &[&[u8]] = &[
            &[],
            &[0x05],
            &[0x05, 1],
            &[0xfe, 0, 0],
            &[0x0c],
            &[0xc0],
            &[0xa1, 1],
            &[0xa4],
            &[0xb4],
            &[0x85, 0],
            &[0x86, 1, 0],
            &[0x75, 1, 0x95, 1, 0x81, 1],
        ];
        for bytes in cases {
            assert!(ReportDescriptor::parse(bytes).is_err(), "{bytes:02x?}");
        }
        for bad_fields in [
            vec![0xa4],
            vec![0xa1, 0],
            vec![0xc0],
            vec![0xa9, 1],
            vec![0x19, 2, 0x29, 1],
            vec![0x19, 1],
            vec![0x29, 1],
            vec![0x19, 1, 0x19, 2, 0x29, 3],
            vec![0x19, 1, 0x09, 2],
            vec![0x09, 0x30, 0x15, 2, 0x25, 1, 0x75, 1, 0x95, 1, 0x81, 2],
            vec![0x09, 0x30, 0x15, 0, 0x25, 4, 0x75, 1, 0x95, 1, 0x81, 2],
            vec![0x09, 0x39, 0x15, 0, 0x25, 7, 0x75, 4, 0x95, 1, 0x81, 0x40],
            vec![0x0b, 1, 0, 9, 0, 0x09, 0x30, 0x75, 1, 0x95, 2, 0x81, 0],
            vec![0x09, 0x30, 0x09, 0x31, 0x75, 1, 0x95, 1, 0x81, 2],
            vec![0x75, 1, 0x95, 1, 0x82, 1, 1],
        ] {
            assert!(
                ReportDescriptor::parse(&synthetic(&bad_fields)).is_err(),
                "{bad_fields:02x?}"
            );
        }
    }

    #[test]
    fn descriptor_report_field_usage_and_nesting_limits_are_bounded() {
        assert!(matches!(
            ReportDescriptor::parse(&[0; 4097]),
            Err(DescriptorError::LimitExceeded)
        ));
        let mut ids = Vec::new();
        for id in 1..=65 {
            ids.extend([0x85, id]);
        }
        assert!(matches!(
            ReportDescriptor::parse(&synthetic(&ids)),
            Err(DescriptorError::LimitExceeded)
        ));
        ids.truncate(64 * 2);
        ids.extend(button_fields());
        assert!(ReportDescriptor::parse(&synthetic(&ids)).is_ok());
        let payload = synthetic(&[0x75, 32, 0x95, 64, 0x81, 1]);
        let descriptor = ReportDescriptor::parse(&payload).unwrap();
        assert_eq!(descriptor.release(&[0xab; 256]).unwrap(), [0xab; 256]);
        let too_long = synthetic(&[0x85, 1, 0x75, 32, 0x95, 64, 0x81, 1]);
        assert!(matches!(
            ReportDescriptor::parse(&too_long),
            Err(DescriptorError::LimitExceeded)
        ));
        for fields in [
            vec![0x75, 33, 0x95, 1, 0x81, 1],
            vec![0x75, 1, 0x96, 1, 4, 0x81, 1],
            vec![0x19, 0, 0x2a, 0, 4],
            vec![
                0x77, 0xff, 0xff, 0xff, 0xff, 0x97, 0xff, 0xff, 0xff, 0xff, 0x81, 1,
            ],
        ] {
            assert!(matches!(
                ReportDescriptor::parse(&synthetic(&fields)),
                Err(DescriptorError::LimitExceeded)
            ));
        }
        let pushes = vec![0xa4; 33];
        assert!(matches!(
            ReportDescriptor::parse(&synthetic(&pushes)),
            Err(DescriptorError::LimitExceeded)
        ));
        let nested: Vec<u8> = [0xa1, 0].repeat(32);
        assert!(matches!(
            ReportDescriptor::parse(&synthetic(&nested)),
            Err(DescriptorError::LimitExceeded)
        ));
    }

    #[test]
    fn every_truncation_and_single_byte_input_is_safe() {
        for bytes in [
            SYNTHETIC_PACKED,
            REAL_COUGAR_MFD,
            REAL_DUAL_ANALOG_4,
            REAL_WARTHOG_STICK,
        ] {
            for length in 0..bytes.len() {
                assert!(ReportDescriptor::parse(&bytes[..length]).is_err());
            }
            for index in 0..bytes.len() {
                for value in [0, 0xff] {
                    let mut mutated = bytes.to_vec();
                    mutated[index] = value;
                    if let Ok(descriptor) = ReportDescriptor::parse(&mutated) {
                        let _ = descriptor.release(&[0xff; 256]);
                        let _ = descriptor.release(&[]);
                    }
                }
            }
        }
        for byte in 0..=255 {
            assert!(ReportDescriptor::parse(&[byte]).is_err());
        }
    }
}
