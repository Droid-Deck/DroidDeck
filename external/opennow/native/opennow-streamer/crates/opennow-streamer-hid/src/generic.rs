use std::collections::{BTreeMap, BTreeSet};
use std::fmt;

use crate::descriptor::ReportDescriptor;

pub const MAX_GENERIC_SOURCES: usize = 8;
pub const MAX_GENERIC_INTERFACES: usize = 16;
pub const MAX_REPORT_BYTES: usize = 256;
pub const MAX_DESCRIPTOR_BYTES: usize = 4096;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GenericDevice {
    pub slot: u8,
    pub incarnation: u64,
    pub vendor: u16,
    pub product: u16,
    pub revision: u16,
    pub interfaces: Vec<GenericInterface>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GenericInterface {
    pub number: u8,
    pub descriptor: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GenericReport {
    pub slot: u8,
    pub incarnation: u64,
    pub interface: u8,
    pub operation: u8,
    pub error: bool,
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GenericOutput {
    pub slot: u8,
    pub incarnation: u64,
    pub interface: u8,
    pub operation: u8,
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GenericError {
    InvalidSlot,
    InvalidIncarnation,
    DuplicateSlot,
    DuplicateInterface,
    TooManyInterfaces,
    DescriptorTooLarge,
    NotAllowlisted,
    StaleSource,
    UnknownInterface,
    InvalidOperation,
    InvalidPayload,
    Unbound,
    Inactive,
    Overflow,
    Faulted,
}

impl fmt::Display for GenericError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "generic HID: {self:?}")
    }
}

impl std::error::Error for GenericError {}

pub(crate) fn validate_inventory(devices: &[GenericDevice]) -> Result<(), GenericError> {
    let mut slots = BTreeSet::new();
    for device in devices {
        if usize::from(device.slot) >= MAX_GENERIC_SOURCES {
            return Err(GenericError::InvalidSlot);
        }
        if device.incarnation == 0 {
            return Err(GenericError::InvalidIncarnation);
        }
        if !slots.insert(device.slot) {
            return Err(GenericError::DuplicateSlot);
        }
        if !crate::allowlist::generic_hid_allowed(device.vendor, device.product) {
            return Err(GenericError::NotAllowlisted);
        }
        if device.interfaces.is_empty() || device.interfaces.len() > MAX_GENERIC_INTERFACES {
            return Err(GenericError::TooManyInterfaces);
        }
        let mut interfaces = BTreeSet::new();
        for interface in &device.interfaces {
            if interface.number == u8::MAX {
                return Err(GenericError::UnknownInterface);
            }
            if !interfaces.insert(interface.number) {
                return Err(GenericError::DuplicateInterface);
            }
            if interface.descriptor.len() > MAX_DESCRIPTOR_BYTES {
                return Err(GenericError::DescriptorTooLarge);
            }
        }
    }
    Ok(())
}

pub(crate) fn validate_operation(
    operation: u8,
    bytes: &[u8],
    output: bool,
) -> Result<(), GenericError> {
    if operation > 4 {
        return Err(GenericError::InvalidOperation);
    }
    if bytes.is_empty() || bytes.len() > MAX_REPORT_BYTES {
        return Err(GenericError::InvalidPayload);
    }
    if output && matches!(operation, 2 | 3) {
        if bytes.len() != 3 {
            return Err(GenericError::InvalidPayload);
        }
        let requested = usize::from(u16::from_le_bytes([bytes[1], bytes[2]]));
        if requested == 0 || requested > MAX_REPORT_BYTES {
            return Err(GenericError::InvalidPayload);
        }
    }
    Ok(())
}

pub(crate) fn validate_report(report: &GenericReport) -> Result<(), GenericError> {
    if report.error && matches!(report.operation, 1..=4) && report.bytes.is_empty() {
        return Ok(());
    }
    validate_operation(report.operation, &report.bytes, false)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GenericOutbound {
    Attach {
        request_id: u8,
        device: GenericDevice,
    },
    Detach {
        device_id: u8,
    },
    Report {
        device_id: u8,
        interface: u8,
        operation: u8,
        error: bool,
        bytes: Vec<u8>,
        slot: u8,
        incarnation: u64,
        release: bool,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Admission {
    Dormant,
    AwaitingDetach(u8),
    Pending(u8),
    Admitted(u8),
    Refused,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GenericAdmission {
    Pending,
    Admitted,
    Declined,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GenericAdmissionStatus {
    pub slot: u8,
    pub incarnation: u64,
    pub admission: GenericAdmission,
}

#[derive(Debug)]
struct Source {
    device: GenericDevice,
    admission: Admission,
    descriptors: BTreeMap<u8, ReportDescriptor>,
    forwarded: BTreeMap<(u8, u8), Vec<u8>>,
    detach_fallback: bool,
}

impl Source {
    fn new(device: GenericDevice) -> Self {
        let descriptors: BTreeMap<_, _> = device
            .interfaces
            .iter()
            .filter_map(|interface| {
                ReportDescriptor::parse(&interface.descriptor)
                    .ok()
                    .map(|parsed| (interface.number, parsed))
            })
            .collect();
        let detach_fallback = descriptors.len() != device.interfaces.len();
        if detach_fallback {
            eprintln!(
                "generic HID {:04x}:{:04x} slot {}: descriptor unavailable or unsafe; capture release will detach",
                device.vendor, device.product, device.slot
            );
        }
        Self {
            device,
            admission: Admission::Dormant,
            descriptors,
            forwarded: BTreeMap::new(),
            detach_fallback,
        }
    }

    fn release(&mut self) -> Vec<GenericOutbound> {
        let Admission::Admitted(device_id) = self.admission else {
            return Vec::new();
        };
        let mut releases = Vec::with_capacity(self.forwarded.len());
        if !self.detach_fallback {
            for ((interface, _), bytes) in &self.forwarded {
                let released = self
                    .descriptors
                    .get(interface)
                    .and_then(|descriptor| descriptor.release(bytes).ok());
                let Some(bytes) = released else {
                    self.detach_fallback = true;
                    eprintln!(
                        "generic HID slot {}: report cannot be safely released; detaching",
                        self.device.slot
                    );
                    break;
                };
                releases.push(GenericOutbound::Report {
                    device_id,
                    interface: *interface,
                    operation: 0,
                    error: false,
                    bytes,
                    slot: self.device.slot,
                    incarnation: self.device.incarnation,
                    release: true,
                });
            }
        }
        if self.detach_fallback {
            releases.clear();
            releases.push(GenericOutbound::Detach { device_id });
            self.admission = Admission::AwaitingDetach(device_id);
            self.forwarded.clear();
        }
        releases
    }
}

#[derive(Debug, Clone, Copy)]
enum Request {
    Pending {
        slot: u8,
        incarnation: u64,
        sent: Option<u64>,
    },
    RetiredPending,
    Tombstone,
}

#[derive(Debug)]
pub struct GenericSession {
    generation: u64,
    sources: BTreeMap<u8, Source>,
    requests: BTreeMap<u8, Request>,
    used_device_ids: BTreeSet<u8>,
    retired_server_ids_eligible: BTreeMap<u8, u64>,
    send_order: u64,
    next_request: u16,
    capture: bool,
    cleanup: BTreeSet<u8>,
}

impl Default for GenericSession {
    fn default() -> Self {
        Self {
            generation: 0,
            sources: BTreeMap::new(),
            requests: BTreeMap::new(),
            used_device_ids: BTreeSet::new(),
            retired_server_ids_eligible: BTreeMap::new(),
            send_order: 0,
            next_request: 10,
            capture: false,
            cleanup: BTreeSet::new(),
        }
    }
}

impl GenericSession {
    pub fn new(generation: u64) -> Self {
        Self {
            generation,
            ..Self::default()
        }
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    pub fn admission(&self, device: &GenericDevice) -> GenericAdmission {
        match self.sources.get(&device.slot) {
            Some(source) if source.device == *device => match source.admission {
                Admission::Admitted(_) => GenericAdmission::Admitted,
                Admission::Refused => GenericAdmission::Declined,
                _ => GenericAdmission::Pending,
            },
            _ => GenericAdmission::Pending,
        }
    }

    fn attach(&mut self, slot: u8) -> Option<GenericOutbound> {
        let source = self.sources.get_mut(&slot)?;
        if source.admission != Admission::Dormant || !self.capture {
            return None;
        }
        if self.next_request > 254 {
            source.admission = Admission::Refused;
            eprintln!(
                "generic HID slot {slot}: registration IDs exhausted; a new session is required"
            );
            return None;
        }
        let request_id = self.next_request as u8;
        self.next_request += 1;
        source.admission = Admission::Pending(request_id);
        self.requests.insert(
            request_id,
            Request::Pending {
                slot,
                incarnation: source.device.incarnation,
                sent: None,
            },
        );
        Some(GenericOutbound::Attach {
            request_id,
            device: source.device.clone(),
        })
    }

    fn retire(&mut self, slot: u8) -> Vec<GenericOutbound> {
        let Some(mut source) = self.sources.remove(&slot) else {
            return Vec::new();
        };
        if let Admission::Pending(request_id) = source.admission {
            let sent = matches!(
                self.requests.get(&request_id),
                Some(Request::Pending { sent: Some(_), .. })
            );
            self.requests.insert(
                request_id,
                if sent {
                    Request::RetiredPending
                } else {
                    Request::Tombstone
                },
            );
        }
        let mut result = source.release();
        if let Admission::Admitted(device_id) = source.admission {
            result.push(GenericOutbound::Detach { device_id });
        }
        result
    }

    pub fn reconcile(
        &mut self,
        inventory: &[GenericDevice],
        capture_active: bool,
    ) -> Vec<GenericOutbound> {
        let mut result = self
            .cleanup
            .iter()
            .map(|device_id| GenericOutbound::Detach {
                device_id: *device_id,
            })
            .collect::<Vec<_>>();
        self.cleanup.clear();
        if validate_inventory(inventory).is_err() {
            result.extend(self.retire_all());
            return result;
        }
        let removed = self
            .sources
            .iter()
            .filter_map(|(slot, source)| (!inventory.contains(&source.device)).then_some(*slot))
            .collect::<Vec<_>>();
        for slot in removed {
            result.extend(self.retire(slot));
        }
        result.extend(self.set_capture(capture_active));
        for device in inventory {
            self.sources
                .entry(device.slot)
                .or_insert_with(|| Source::new(device.clone()));
            if let Some(attach) = self.attach(device.slot) {
                result.push(attach);
            }
        }
        result
    }

    pub fn release_all(&mut self) -> Vec<GenericOutbound> {
        self.sources
            .values_mut()
            .flat_map(Source::release)
            .collect()
    }

    pub fn set_capture(&mut self, active: bool) -> Vec<GenericOutbound> {
        if self.capture == active {
            return Vec::new();
        }
        self.capture = active;
        if !active {
            return self.release_all();
        }
        let slots = self.sources.keys().copied().collect::<Vec<_>>();
        slots
            .into_iter()
            .filter_map(|slot| self.attach(slot))
            .collect()
    }

    pub fn handle_response(&mut self, request_id: u8, control: u8, _opaque: u32, device_id: u8) {
        let Some(request) = self.requests.get(&request_id).copied() else {
            return;
        };
        if matches!(
            request,
            Request::Tombstone | Request::Pending { sent: None, .. }
        ) {
            return;
        }
        self.requests.insert(request_id, Request::Tombstone);
        let valid = control == 1 && (10..=254).contains(&device_id);
        if let Request::RetiredPending = request {
            if valid
                && !self
                    .sources
                    .values()
                    .any(|source| source.admission == Admission::Admitted(device_id))
            {
                self.used_device_ids.insert(device_id);
                self.cleanup.insert(device_id);
            }
            return;
        }
        let Request::Pending {
            slot,
            incarnation,
            sent: Some(sent_order),
        } = request
        else {
            return;
        };
        let active_id = self
            .sources
            .values()
            .any(|source| source.admission == Admission::Admitted(device_id));
        let reused_id = (self.used_device_ids.contains(&device_id)
            && self
                .retired_server_ids_eligible
                .get(&device_id)
                .is_none_or(|detach_order| *detach_order > sent_order))
            || self.cleanup.contains(&device_id);
        let Some(source) = self.sources.get_mut(&slot) else {
            return;
        };
        if source.device.incarnation != incarnation
            || source.admission != Admission::Pending(request_id)
        {
            return;
        }
        source.admission = if valid && !active_id && !reused_id {
            self.used_device_ids.insert(device_id);
            self.retired_server_ids_eligible.remove(&device_id);
            Admission::Admitted(device_id)
        } else {
            if valid && !active_id {
                if self.retired_server_ids_eligible.contains_key(&device_id)
                    && !self.cleanup.contains(&device_id)
                {
                    Admission::Dormant
                } else {
                    self.cleanup.insert(device_id);
                    Admission::AwaitingDetach(device_id)
                }
            } else {
                Admission::Refused
            }
        };
        eprintln!(
            "generic HID {:04x}:{:04x} slot {slot}: registration {}",
            source.device.vendor,
            source.device.product,
            if source.admission == Admission::Admitted(device_id) {
                "accepted"
            } else {
                "refused"
            }
        );
        if !self.capture
            && source.detach_fallback
            && source.admission == Admission::Admitted(device_id)
        {
            self.cleanup.insert(device_id);
            source.admission = Admission::AwaitingDetach(device_id);
        }
    }

    pub fn report(&self, report: GenericReport) -> Option<GenericOutbound> {
        validate_report(&report).ok()?;
        if !self.capture {
            return None;
        }
        let source = self.sources.get(&report.slot)?;
        let Admission::Admitted(device_id) = source.admission else {
            return None;
        };
        if source.device.incarnation != report.incarnation
            || !source
                .device
                .interfaces
                .iter()
                .any(|interface| interface.number == report.interface)
        {
            return None;
        }
        Some(GenericOutbound::Report {
            device_id,
            interface: report.interface,
            operation: report.operation,
            error: report.error,
            bytes: report.bytes,
            slot: report.slot,
            incarnation: report.incarnation,
            release: false,
        })
    }

    pub fn sent(&mut self, outbound: &GenericOutbound) {
        match outbound {
            GenericOutbound::Detach { device_id } => {
                if self
                    .sources
                    .values()
                    .any(|source| source.admission == Admission::Admitted(*device_id))
                {
                    return;
                }
                if self.used_device_ids.contains(device_id) {
                    self.send_order += 1;
                    self.retired_server_ids_eligible
                        .insert(*device_id, self.send_order);
                }
                self.cleanup.remove(device_id);
                for source in self.sources.values_mut() {
                    if source.admission == Admission::AwaitingDetach(*device_id) {
                        source.admission = Admission::Dormant;
                    }
                }
            }
            GenericOutbound::Attach { request_id, device } => {
                if let Some(Request::Pending {
                    slot,
                    incarnation,
                    sent,
                }) = self.requests.get_mut(request_id)
                    && *slot == device.slot
                    && *incarnation == device.incarnation
                    && sent.is_none()
                {
                    self.send_order += 1;
                    *sent = Some(self.send_order);
                }
            }
            GenericOutbound::Report {
                device_id,
                interface,
                operation: 0,
                error: false,
                bytes,
                slot,
                incarnation,
                ..
            } => {
                let Some(source) = self.sources.get_mut(slot) else {
                    return;
                };
                if source.device.incarnation != *incarnation
                    || source.admission != Admission::Admitted(*device_id)
                {
                    return;
                }
                let id = source
                    .descriptors
                    .get(interface)
                    .and_then(|descriptor| descriptor.report_id(bytes).ok());
                if let Some(id) = id {
                    source.forwarded.insert((*interface, id), bytes.clone());
                } else if !source.detach_fallback {
                    source.detach_fallback = true;
                    eprintln!(
                        "generic HID slot {}: report does not match its descriptor; capture release will detach",
                        source.device.slot
                    );
                }
            }
            _ => {}
        }
    }

    pub fn fault(&mut self, slot: u8, incarnation: u64) -> Vec<GenericOutbound> {
        let Some(source) = self.sources.get(&slot) else {
            return Vec::new();
        };
        if source.device.incarnation != incarnation {
            return Vec::new();
        }
        eprintln!("generic HID slot {slot}: retiring source after a bounded input/output failure");
        let device = source.device.clone();
        let result = self.retire(slot);
        let mut refused = Source::new(device);
        refused.admission = Admission::Refused;
        self.sources.insert(slot, refused);
        result
    }

    pub fn output(
        &self,
        device_id: u8,
        interface: u8,
        operation: u8,
        bytes: &[u8],
    ) -> Option<GenericOutput> {
        validate_operation(operation, bytes, true).ok()?;
        if !self.capture {
            return None;
        }
        let source = self
            .sources
            .values()
            .find(|source| source.admission == Admission::Admitted(device_id))?;
        if !source
            .device
            .interfaces
            .iter()
            .any(|entry| entry.number == interface)
        {
            return None;
        }
        Some(GenericOutput {
            slot: source.device.slot,
            incarnation: source.device.incarnation,
            interface,
            operation,
            bytes: bytes.to_vec(),
        })
    }

    pub fn retire_all(&mut self) -> Vec<GenericOutbound> {
        let slots = self.sources.keys().copied().collect::<Vec<_>>();
        let mut result = self
            .cleanup
            .iter()
            .map(|device_id| GenericOutbound::Detach {
                device_id: *device_id,
            })
            .collect::<Vec<_>>();
        self.cleanup.clear();
        for slot in slots {
            result.extend(self.retire(slot));
        }
        result
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub(crate) fn device(slot: u8, incarnation: u64) -> GenericDevice {
        GenericDevice {
            slot,
            incarnation,
            vendor: 0x044f,
            product: 0x0402,
            revision: 0x0100,
            interfaces: vec![GenericInterface {
                number: 2,
                descriptor: vec![
                    0x05, 0x01, 0x09, 0x04, 0xa1, 0x01, 0x05, 0x09, 0x19, 0x01, 0x29, 0x08, 0x15,
                    0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02, 0x05, 0x01, 0x09, 0x30,
                    0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02, 0x09, 0x39,
                    0x15, 0x00, 0x25, 0x07, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, 0x75, 0x04, 0x95,
                    0x01, 0x81, 0x03, 0xc0,
                ],
            }],
        }
    }

    pub(crate) fn report(slot: u8, incarnation: u64) -> GenericReport {
        GenericReport {
            slot,
            incarnation,
            interface: 2,
            operation: 0,
            error: false,
            bytes: vec![0xff, 0xa5, 0xb3],
        }
    }

    fn attach(session: &mut GenericSession, devices: &[GenericDevice], server_id: u8) -> u8 {
        let outgoing = session.reconcile(devices, true);
        let GenericOutbound::Attach { request_id, .. } = &outgoing[0] else {
            panic!("expected attach");
        };
        let request_id = *request_id;
        session.sent(&outgoing[0]);
        session.handle_response(request_id, 1, 0, server_id);
        request_id
    }

    #[test]
    fn admission_requires_a_sent_attach_and_verified_response() {
        let mut session = GenericSession::new(1);
        let outgoing = session.reconcile(&[device(0, 1)], true);
        assert!(session.report(report(0, 1)).is_none());
        session.handle_response(10, 1, 0, 20);
        assert!(session.report(report(0, 1)).is_none());
        session.sent(&outgoing[0]);
        session.handle_response(10, 1, 0, 20);
        assert!(matches!(
            session.report(report(0, 1)),
            Some(GenericOutbound::Report { device_id: 20, .. })
        ));
        session.handle_response(10, 1, 1, 255);
        assert!(session.report(report(0, 1)).is_some());
    }

    #[test]
    fn control_one_admits_regardless_of_the_opaque_response_dword() {
        for opaque in [0, 0xf, u32::MAX] {
            let mut session = GenericSession::new(1);
            let outgoing = session.reconcile(&[device(0, 1)], true);
            session.sent(&outgoing[0]);
            session.handle_response(10, 1, opaque, 20);
            assert!(matches!(
                session.report(report(0, 1)),
                Some(GenericOutbound::Report { device_id: 20, .. })
            ));
        }
    }

    #[test]
    fn pause_releases_only_forwarded_reports_and_resume_uses_next_poll() {
        let mut session = GenericSession::new(1);
        attach(&mut session, &[device(0, 1)], 21);
        let outgoing = session.report(report(0, 1)).unwrap();
        assert!(session.set_capture(false).is_empty());
        assert!(session.set_capture(true).is_empty());
        session.sent(&outgoing);
        let release = session.set_capture(false);
        assert!(
            matches!(&release[0], GenericOutbound::Report { bytes, release: true, .. } if bytes == &[0, 0xa5, 0xb8])
        );
        assert!(session.report(report(0, 1)).is_none());
        session.sent(&release[0]);
        assert!(session.set_capture(true).is_empty());
        assert!(
            matches!(session.report(report(0, 1)), Some(GenericOutbound::Report { bytes, release: false, .. }) if bytes == vec![0xff, 0xa5, 0xb3])
        );
    }

    #[test]
    fn release_tracks_each_forwarded_report_id_and_interface_separately() {
        let mut source = device(0, 1);
        let descriptor = source.interfaces[0].descriptor.clone();
        let fields = &descriptor[6..descriptor.len() - 1];
        let mut numbered = descriptor[..6].to_vec();
        numbered.extend_from_slice(&[0x85, 1]);
        numbered.extend_from_slice(fields);
        numbered.extend_from_slice(&[0x85, 2]);
        numbered.extend_from_slice(fields);
        numbered.push(0xc0);
        source.interfaces[0].descriptor = numbered.clone();
        source.interfaces.push(GenericInterface {
            number: 3,
            descriptor: numbered,
        });
        let mut session = GenericSession::new(1);
        attach(&mut session, &[source], 22);
        for (interface, bytes) in [
            (2, vec![1, 0xff, 0x11, 0x33]),
            (2, vec![2, 0xaa, 0x22, 0x43]),
            (3, vec![1, 0xbb, 0x33, 0x53]),
        ] {
            let mut input = report(0, 1);
            input.interface = interface;
            input.bytes = bytes;
            let outgoing = session.report(input).unwrap();
            session.sent(&outgoing);
        }
        let released = session.set_capture(false);
        let states = released
            .iter()
            .map(|record| {
                let GenericOutbound::Report {
                    interface,
                    bytes,
                    release: true,
                    ..
                } = record
                else {
                    panic!("expected descriptor release");
                };
                (*interface, bytes.clone())
            })
            .collect::<Vec<_>>();
        assert_eq!(
            states,
            vec![
                (2, vec![1, 0, 0x11, 0x38]),
                (2, vec![2, 0, 0x22, 0x48]),
                (3, vec![1, 0, 0x33, 0x58])
            ]
        );
    }

    #[test]
    fn descriptor_mismatched_report_is_forwarded_but_release_detaches() {
        let mut session = GenericSession::new(1);
        attach(&mut session, &[device(0, 1)], 22);
        let mut input = report(0, 1);
        input.bytes = vec![1];
        let outgoing = session.report(input).unwrap();
        session.sent(&outgoing);
        assert_eq!(
            session.set_capture(false),
            vec![GenericOutbound::Detach { device_id: 22 }]
        );
    }

    #[test]
    fn invalid_descriptor_passes_through_then_detaches_and_reattaches() {
        let mut source = device(0, 1);
        source.interfaces[0].descriptor.clear();
        let mut session = GenericSession::new(1);
        attach(&mut session, &[source], 21);
        let outgoing = session.report(report(0, 1)).unwrap();
        session.sent(&outgoing);
        let paused = session.set_capture(false);
        assert_eq!(paused, vec![GenericOutbound::Detach { device_id: 21 }]);
        session.sent(&paused[0]);
        let resumed = session.set_capture(true);
        assert!(matches!(
            &resumed[0],
            GenericOutbound::Attach { request_id: 11, .. }
        ));
        assert!(session.report(report(0, 1)).is_none());
        session.sent(&resumed[0]);
        session.handle_response(10, 1, 0, 22);
        assert!(session.report(report(0, 1)).is_none());
        session.handle_response(11, 1, 0, 22);
        assert!(session.report(report(0, 1)).is_some());
    }

    #[test]
    fn fallback_resume_may_reuse_server_id_for_the_same_source_incarnation() {
        let mut source = device(0, 1);
        source.interfaces[0].descriptor.clear();
        let mut session = GenericSession::new(1);
        attach(&mut session, &[source.clone()], 22);
        let paused = session.set_capture(false);
        assert_eq!(paused, vec![GenericOutbound::Detach { device_id: 22 }]);
        assert!(session.set_capture(true).is_empty());
        session.sent(&paused[0]);
        let resumed = session.reconcile(&[source], true);
        session.sent(&resumed[0]);
        session.handle_response(11, 1, 0xf, 22);
        assert!(session.report(report(0, 1)).is_some());
        assert_eq!(session.output(22, 2, 0, &[1]).unwrap().incarnation, 1);
    }

    #[test]
    fn removal_releases_before_detach_and_old_incarnations_do_not_resurrect() {
        let mut session = GenericSession::new(1);
        attach(&mut session, &[device(0, 1)], 21);
        let outgoing = session.report(report(0, 1)).unwrap();
        session.sent(&outgoing);
        let retired = session.reconcile(&[device(0, 2)], true);
        assert!(matches!(
            retired[0],
            GenericOutbound::Report { release: true, .. }
        ));
        assert_eq!(retired[1], GenericOutbound::Detach { device_id: 21 });
        assert!(matches!(
            retired[2],
            GenericOutbound::Attach { request_id: 11, .. }
        ));
        session.sent(&retired[2]);
        session.handle_response(10, 1, 0, 30);
        assert!(session.report(report(0, 1)).is_none());
        assert!(session.report(report(0, 2)).is_none());
        session.handle_response(11, 1, 0, 22);
        assert!(session.report(report(0, 2)).is_some());
        let ended = session.retire_all();
        assert_eq!(ended, vec![GenericOutbound::Detach { device_id: 22 }]);
        assert!(session.report(report(0, 2)).is_none());
        let mut restarted = GenericSession::new(1);
        assert_eq!(attach(&mut restarted, &[device(0, 2)], 22), 10);
    }

    #[test]
    fn late_response_to_retired_pending_attach_only_schedules_cleanup() {
        let mut session = GenericSession::new(1);
        let outgoing = session.reconcile(&[device(0, 1)], true);
        session.sent(&outgoing[0]);
        assert!(session.reconcile(&[], false).is_empty());
        session.handle_response(10, 1, 0, 22);
        assert_eq!(
            session.reconcile(&[], false),
            vec![GenericOutbound::Detach { device_id: 22 }]
        );
        assert!(session.report(report(0, 1)).is_none());
    }

    #[test]
    fn retired_server_ids_cannot_route_stale_output_to_a_replacement() {
        let mut session = GenericSession::new(1);
        attach(&mut session, &[device(0, 1)], 22);
        let outgoing = session.reconcile(&[device(0, 2)], true);
        session.sent(&outgoing[1]);
        session.handle_response(11, 1, 0, 22);
        assert!(session.output(22, 2, 0, &[1]).is_none());
        assert!(session.report(report(0, 2)).is_none());
        assert_eq!(
            session.reconcile(&[device(0, 2)], true),
            vec![GenericOutbound::Detach { device_id: 22 }]
        );
    }

    #[test]
    fn queued_detach_then_fresh_attach_allows_assigned_id_reuse_on_reconnect() {
        let mut session = GenericSession::new(1);
        attach(&mut session, &[device(0, 1)], 10);
        let stale_output = session.output(10, 2, 0, &[1]).unwrap();
        let outgoing = session.reconcile(&[device(0, 2)], true);
        assert_eq!(outgoing[0], GenericOutbound::Detach { device_id: 10 });
        assert!(matches!(
            outgoing[1],
            GenericOutbound::Attach { request_id: 11, .. }
        ));
        assert!(session.output(10, 2, 0, &[1]).is_none());
        session.sent(&outgoing[0]);
        assert!(session.output(10, 2, 0, &[1]).is_none());
        session.sent(&outgoing[1]);
        assert!(session.output(10, 2, 0, &[1]).is_none());
        session.handle_response(11, 1, 0xf, 10);
        assert_eq!(session.output(10, 2, 0, &[1]).unwrap().incarnation, 2);
        assert!(session.report(report(0, 1)).is_none());
        assert!(session.report(report(0, 2)).is_some());
        let runtime = crate::generic_runtime::GenericRuntime::new();
        runtime.replace_inventory(vec![device(0, 2)]).unwrap();
        runtime.bind_session(1);
        runtime.set_capture(true);
        assert_eq!(
            runtime.push_output(stale_output),
            Err(GenericError::StaleSource)
        );
    }

    #[test]
    fn deferred_pending_cleanup_cannot_detach_a_newly_admitted_replacement() {
        let mut session = GenericSession::new(1);
        let first = session.reconcile(&[device(0, 1)], true);
        session.sent(&first[0]);
        let second = session.reconcile(&[device(0, 2)], true);
        session.sent(&second[0]);
        session.handle_response(10, 1, 0, 22);
        session.handle_response(11, 1, 0, 22);
        assert!(session.report(report(0, 2)).is_none());
        let cleanup = session.reconcile(&[device(0, 2)], true);
        assert_eq!(cleanup, vec![GenericOutbound::Detach { device_id: 22 }]);
        assert!(session.reconcile(&[device(0, 2)], true).is_empty());
        session.sent(&cleanup[0]);
        let retry = session.reconcile(&[device(0, 2)], true);
        assert!(matches!(
            retry[0],
            GenericOutbound::Attach { request_id: 12, .. }
        ));
        session.sent(&retry[0]);
        assert!(session.output(22, 2, 0, &[1]).is_none());
        session.handle_response(11, 1, 0, 22);
        assert!(session.output(22, 2, 0, &[1]).is_none());
        session.handle_response(12, 1, 0xf, 22);
        assert_eq!(session.output(22, 2, 0, &[1]).unwrap().incarnation, 2);
        assert!(session.reconcile(&[device(0, 2)], true).is_empty());
    }

    #[test]
    fn acknowledgement_for_attach_queued_before_cleanup_requires_a_fresh_retry() {
        let mut session = GenericSession::new(1);
        let first = session.reconcile(&[device(0, 1)], true);
        session.sent(&first[0]);
        let second = session.reconcile(&[device(0, 2)], true);
        session.sent(&second[0]);
        session.handle_response(10, 1, 0, 22);
        let cleanup = session.reconcile(&[device(0, 2)], true);
        assert_eq!(cleanup, vec![GenericOutbound::Detach { device_id: 22 }]);
        session.sent(&cleanup[0]);
        session.handle_response(11, 1, 0, 22);
        assert!(session.output(22, 2, 0, &[1]).is_none());
        let retry = session.reconcile(&[device(0, 2)], true);
        assert_eq!(retry.len(), 1);
        assert!(matches!(
            retry[0],
            GenericOutbound::Attach { request_id: 12, .. }
        ));
        session.sent(&retry[0]);
        session.handle_response(12, 1, 0, 22);
        assert_eq!(session.output(22, 2, 0, &[1]).unwrap().incarnation, 2);
        assert!(session.reconcile(&[device(0, 2)], true).is_empty());
    }

    #[test]
    fn refusal_invalid_ids_and_duplicate_server_ids_are_fail_closed() {
        for (control, id) in [(0, 22), (1, 9), (1, 255)] {
            let mut session = GenericSession::new(1);
            let outgoing = session.reconcile(&[device(0, 1)], true);
            session.sent(&outgoing[0]);
            session.handle_response(10, control, 0, id);
            assert!(session.report(report(0, 1)).is_none());
            session.handle_response(10, 1, 0, 22);
            assert!(session.reconcile(&[device(0, 1)], true).is_empty());
            assert!(session.report(report(0, 1)).is_none());
        }
        let mut session = GenericSession::new(1);
        let outgoing = session.reconcile(&[device(0, 1), device(1, 2)], true);
        for entry in &outgoing {
            session.sent(entry);
        }
        session.handle_response(10, 1, 0, 22);
        session.handle_response(11, 1, 0, 22);
        assert!(session.report(report(0, 1)).is_some());
        assert!(session.report(report(1, 2)).is_none());
    }

    #[test]
    fn request_ids_never_reuse_and_exhaust_at_254() {
        let mut session = GenericSession::default();
        for incarnation in 1..=245 {
            let outgoing = session.reconcile(&[device(0, incarnation)], true);
            assert!(
                matches!(&outgoing[0], GenericOutbound::Attach { request_id, .. } if u64::from(*request_id) == incarnation + 9)
            );
            session.sent(&outgoing[0]);
            session.handle_response((incarnation + 9) as u8, 0, 0, 255);
        }
        assert!(session.reconcile(&[device(0, 246)], true).is_empty());
        assert!(session.report(report(0, 246)).is_none());
    }

    #[test]
    fn fault_is_sticky_until_inventory_incarnation_changes() {
        let mut session = GenericSession::new(1);
        attach(&mut session, &[device(0, 1)], 22);
        let outgoing = session.report(report(0, 1)).unwrap();
        session.sent(&outgoing);
        assert_eq!(session.fault(0, 99), Vec::new());
        let fault = session.fault(0, 1);
        assert!(matches!(
            fault[0],
            GenericOutbound::Report { release: true, .. }
        ));
        assert_eq!(fault[1], GenericOutbound::Detach { device_id: 22 });
        assert!(session.reconcile(&[device(0, 1)], true).is_empty());
        assert!(session.report(report(0, 1)).is_none());
        assert!(matches!(
            &session.reconcile(&[device(0, 2)], true)[0],
            GenericOutbound::Attach { request_id: 11, .. }
        ));
    }

    #[test]
    fn outputs_require_admission_capture_interface_and_valid_get_length() {
        let mut session = GenericSession::new(1);
        attach(&mut session, &[device(0, 7)], 22);
        for operation in 0..=4 {
            let bytes = if matches!(operation, 2 | 3) {
                vec![0, 0, 1]
            } else {
                vec![0, 1]
            };
            let output = session.output(22, 2, operation, &bytes).unwrap();
            assert_eq!(output.incarnation, 7);
        }
        for bytes in [&[][..], &[0, 0][..], &[0, 0, 0][..], &[0, 1, 1][..]] {
            assert!(session.output(22, 2, 2, bytes).is_none());
        }
        assert!(session.output(22, 2, 5, &[1]).is_none());
        assert!(session.output(22, 3, 0, &[1]).is_none());
        assert!(session.output(23, 2, 0, &[1]).is_none());
        assert!(session.output(22, 2, 0, &[0; 257]).is_none());
        session.set_capture(false);
        assert!(session.output(22, 2, 0, &[1]).is_none());
    }

    #[test]
    fn empty_error_control_replies_pass_through_without_becoming_release_state() {
        let mut session = GenericSession::new(1);
        attach(&mut session, &[device(0, 1)], 22);
        for operation in 0..=5 {
            let mut input = report(0, 1);
            input.operation = operation;
            input.error = true;
            input.bytes.clear();
            let outgoing = session.report(input);
            assert_eq!(outgoing.is_some(), (1..=4).contains(&operation));
            if let Some(outgoing) = outgoing {
                session.sent(&outgoing);
            }
        }
        assert!(session.set_capture(false).is_empty());
    }
}
