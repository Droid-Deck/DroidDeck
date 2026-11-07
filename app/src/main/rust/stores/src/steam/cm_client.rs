//! CM session state and message builders: logon with a refresh token, heartbeat, log-off, and the
//! requests a download store needs (PICS, depot keys, manifest request codes, CDN server list,
//! free licenses). Social, stats, cloud, lobby and ticket messages are not part of this port.

use crate::steam::cmsg_protobuf_header::{CMsgProtoBufHeader, INVALID_JOB_ID};
use crate::steam::crypto::generate_session_key;
use crate::steam::emsg::EMsg;
use crate::steam::job_manager::JobResult;
use crate::steam::pb::ccontentserverdirectory::{
    CContentServerDirectoryGetManifestRequestCodeRequest,
    CContentServerDirectoryGetServersForSteamPipeRequest,
};
use crate::steam::pb::cmsg_client_get_depot_decryption_key::CMsgClientGetDepotDecryptionKey;
use crate::steam::pb::cmsg_client_license_list::{CMsgClientLicenseList, License};
use crate::steam::pb::cmsg_client_pics::{
    CMsgClientPICSAccessTokenRequest, CMsgClientPICSProductInfoRequest, PicsAppInfoReq,
    PicsPackageInfoReq,
};
use crate::steam::pb::cmsg_client_request_free_license::CMsgClientRequestFreeLicense;
use crate::steam::pb::cmsg_clientserver_login::{
    CMsgClientHeartBeat, CMsgClientHello, CMsgClientLogOff, CMsgClientLogon,
    CMsgClientLogonResponse,
};
use crate::steam::proto_envelope::encode_proto_envelope;
use flate2::read::{GzDecoder, ZlibDecoder};
use std::io::Read;
use std::sync::atomic::{AtomicI32, AtomicU32, AtomicU64, AtomicU8, Ordering};
use std::sync::Mutex;

/// Sent as `machine_id` in the logon.
pub const MACHINE_ID: &[u8] = b"DroidDeck-Steam";
const LOGON_PROTOCOL_VERSION: u32 = 65580;
const LOGON_CLIENT_OS_TYPE: u32 = 16;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum ClientState {
    Disconnected,
    Connecting,
    Connected,
    LoggedOn,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum InboundAction {
    DeliverJob(JobResult),
    Multi,
    PicsProductInfo,
    LogonOk,
    LoggedOff,
    LicenseList(usize),
    ClientMessage,
    Ignored,
    ParseFailed(&'static str),
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgMultiBody {
    pub size_unzipped: u32,
    pub message_body: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OutboundProtoMessage {
    pub emsg: EMsg,
    pub routing_appid: u32,
    pub body: Vec<u8>,
    pub wire: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OutboundServiceCall {
    pub method_name: String,
    pub authed: bool,
    pub job_id: u64,
    pub request_body: Vec<u8>,
    pub wire: Vec<u8>,
}

pub struct CMClientCore {
    state: AtomicU8,
    steam_id: AtomicU64,
    session_id: AtomicI32,
    cell_id: AtomicU32,
    /// EResult of the last logon response or log-off (0 = none yet).
    last_logon_eresult: AtomicI32,
    license_list: Mutex<Option<Vec<License>>>,
    outbound_wires: Mutex<Vec<Vec<u8>>>,
}

impl Default for CMClientCore {
    fn default() -> Self {
        Self {
            state: AtomicU8::new(ClientState::Disconnected as u8),
            steam_id: AtomicU64::new(0),
            session_id: AtomicI32::new(0),
            cell_id: AtomicU32::new(0),
            last_logon_eresult: AtomicI32::new(0),
            license_list: Mutex::new(None),
            outbound_wires: Mutex::new(Vec::new()),
        }
    }
}

impl CMClientCore {
    pub fn state(&self) -> ClientState {
        match self.state.load(Ordering::Relaxed) {
            1 => ClientState::Connecting,
            2 => ClientState::Connected,
            3 => ClientState::LoggedOn,
            _ => ClientState::Disconnected,
        }
    }

    pub fn set_state(&self, state: ClientState) {
        self.state.store(state as u8, Ordering::Relaxed);
    }

    pub fn reset_session_identity(&self) {
        self.steam_id.store(0, Ordering::Relaxed);
        self.session_id.store(0, Ordering::Relaxed);
        self.outbound_wires
            .lock()
            .expect("outbound queue poisoned")
            .clear();
    }

    pub fn steam_id(&self) -> u64 {
        self.steam_id.load(Ordering::Relaxed)
    }

    pub fn session_id(&self) -> i32 {
        self.session_id.load(Ordering::Relaxed)
    }

    pub fn cell_id(&self) -> u32 {
        self.cell_id.load(Ordering::Relaxed)
    }

    pub fn last_logon_eresult(&self) -> i32 {
        self.last_logon_eresult.load(Ordering::Relaxed)
    }

    pub fn clear_last_logon_eresult(&self) {
        self.last_logon_eresult.store(0, Ordering::Relaxed);
    }

    /// The license list Steam pushed after logon; `None` until it arrived.
    pub fn license_list(&self) -> Option<Vec<License>> {
        self.license_list
            .lock()
            .expect("license list poisoned")
            .clone()
    }

    pub fn enqueue_proto_message(&self, message: Option<OutboundProtoMessage>) -> bool {
        let Some(message) = message else {
            return false;
        };
        self.enqueue_wire(message.wire)
    }

    pub fn enqueue_wire(&self, wire: Vec<u8>) -> bool {
        if wire.is_empty() {
            return false;
        }
        self.outbound_wires
            .lock()
            .expect("outbound queue poisoned")
            .push(wire);
        true
    }

    pub fn restore_outbound_wires_front(&self, mut wires: Vec<Vec<u8>>) {
        if wires.is_empty() {
            return;
        }
        let mut queue = self.outbound_wires.lock().expect("outbound queue poisoned");
        if queue.is_empty() {
            *queue = wires;
            return;
        }
        let mut restored = Vec::with_capacity(wires.len() + queue.len());
        restored.append(&mut wires);
        restored.append(&mut queue);
        *queue = restored;
    }

    pub fn take_outbound_wires(&self) -> Vec<Vec<u8>> {
        std::mem::take(&mut *self.outbound_wires.lock().expect("outbound queue poisoned"))
    }

    pub fn build_proto_message(&self, emsg: EMsg, body: &[u8], routing_appid: u32) -> Vec<u8> {
        let mut header = CMsgProtoBufHeader {
            steamid: self.steam_id(),
            client_sessionid: self.session_id(),
            routing_appid,
            ..Default::default()
        };
        if emsg == EMsg::CLIENT_LOGON && header.steamid == 0 {
            header.steamid = 0x0110_0001_0000_0000;
        }
        encode_proto_envelope(emsg, &header, body)
    }

    pub fn build_client_hello(&self) -> OutboundProtoMessage {
        let body = CMsgClientHello::default().serialize();
        let wire = self.build_proto_message(EMsg::CLIENT_HELLO, &body, 0);
        OutboundProtoMessage {
            emsg: EMsg::CLIENT_HELLO,
            routing_appid: 0,
            body,
            wire,
        }
    }

    pub fn build_heartbeat(&self) -> OutboundProtoMessage {
        let body = CMsgClientHeartBeat::default().serialize();
        let wire = self.build_proto_message(EMsg::CLIENT_HEART_BEAT, &body, 0);
        OutboundProtoMessage {
            emsg: EMsg::CLIENT_HEART_BEAT,
            routing_appid: 0,
            body,
            wire,
        }
    }

    pub fn build_logoff(&self) -> Option<OutboundProtoMessage> {
        if self.state() != ClientState::LoggedOn {
            return None;
        }
        self.build_outbound_proto_message(EMsg::CLIENT_LOG_OFF, CMsgClientLogOff.serialize(), 0)
    }

    /// `ClientLogon` carrying the saved refresh token as `access_token`. The returned wire holds
    /// the token; drop it as soon as it is queued.
    pub fn build_logon_with_refresh_token(
        &self,
        refresh_token: &str,
        account_name: &str,
        client_supplied_steam_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if self.state() != ClientState::Connected || refresh_token.is_empty() {
            return None;
        }
        let mut msg = CMsgClientLogon {
            access_token: refresh_token.to_string(),
            account_name: account_name.to_string(),
            client_supplied_steam_id,
            machine_id: MACHINE_ID.to_vec(),
            protocol_version: LOGON_PROTOCOL_VERSION,
            client_os_type: LOGON_CLIENT_OS_TYPE,
            supports_rate_limit_response: true,
            ..Default::default()
        };
        if let Some(key) = generate_session_key() {
            let mut client_instance_id = 0u64;
            for (idx, byte) in key.bytes.iter().take(8).enumerate() {
                client_instance_id |= (*byte as u64) << (idx * 8);
            }
            msg.client_instance_id = client_instance_id.max(1);
            let mut login_id = 0u32;
            for (idx, byte) in key.bytes.iter().skip(8).take(4).enumerate() {
                login_id |= (*byte as u32) << (idx * 8);
            }
            msg.obfuscated_private_ip = login_id.max(1);
        }
        let body = msg.serialize();
        zeroize::Zeroize::zeroize(&mut msg.access_token);
        let wire = self.build_proto_message(EMsg::CLIENT_LOGON, &body, 0);
        let mut body = body;
        zeroize::Zeroize::zeroize(&mut body);
        Some(OutboundProtoMessage {
            emsg: EMsg::CLIENT_LOGON,
            routing_appid: 0,
            body: Vec::new(),
            wire,
        })
    }

    pub fn build_service_method_call(
        &self,
        method_name: &str,
        authed: bool,
        job_id: u64,
        request_body: &[u8],
    ) -> Vec<u8> {
        let header = CMsgProtoBufHeader {
            steamid: self.steam_id(),
            client_sessionid: self.session_id(),
            jobid_source: job_id,
            jobid_target: INVALID_JOB_ID,
            target_job_name: method_name.to_string(),
            ..Default::default()
        };
        let emsg = if authed {
            EMsg::SERVICE_METHOD_CALL_FROM_CLIENT
        } else {
            EMsg::SERVICE_METHOD_CALL_FROM_CLIENT_NON_AUTHED
        };
        encode_proto_envelope(emsg, &header, request_body)
    }

    pub fn build_outbound_proto_message(
        &self,
        emsg: EMsg,
        body: Vec<u8>,
        routing_appid: u32,
    ) -> Option<OutboundProtoMessage> {
        if self.state() != ClientState::LoggedOn {
            return None;
        }
        let wire = self.build_proto_message(emsg, &body, routing_appid);
        Some(OutboundProtoMessage {
            emsg,
            routing_appid,
            body,
            wire,
        })
    }

    pub fn build_job_proto_message(
        &self,
        emsg: EMsg,
        job_id: u64,
        body: Vec<u8>,
        routing_appid: u32,
    ) -> Option<OutboundProtoMessage> {
        if self.state() != ClientState::LoggedOn {
            return None;
        }
        let header = CMsgProtoBufHeader {
            steamid: self.steam_id(),
            client_sessionid: self.session_id(),
            routing_appid,
            jobid_source: job_id,
            jobid_target: INVALID_JOB_ID,
            ..Default::default()
        };
        let wire = encode_proto_envelope(emsg, &header, &body);
        Some(OutboundProtoMessage {
            emsg,
            routing_appid,
            body,
            wire,
        })
    }

    pub fn build_authed_service_call(
        &self,
        method_name: &str,
        job_id: u64,
        request_body: Vec<u8>,
    ) -> Option<OutboundServiceCall> {
        if self.state() != ClientState::LoggedOn {
            return None;
        }
        let wire = self.build_service_method_call(method_name, true, job_id, &request_body);
        Some(OutboundServiceCall {
            method_name: method_name.to_string(),
            authed: true,
            job_id,
            request_body,
            wire,
        })
    }

    pub fn build_get_depot_decryption_key(
        &self,
        depot_id: u32,
        app_id: u32,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if depot_id == 0 || app_id == 0 {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_GET_DEPOT_DECRYPTION_KEY,
            job_id,
            CMsgClientGetDepotDecryptionKey { depot_id, app_id }.serialize(),
            0,
        )
    }

    pub fn build_manifest_request_code_call(
        &self,
        app_id: u32,
        depot_id: u32,
        manifest_id: u64,
        branch: &str,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        if app_id == 0 || depot_id == 0 || manifest_id == 0 {
            return None;
        }
        let lower = branch.to_ascii_lowercase();
        let app_branch = if lower.is_empty() || lower == "public" {
            String::new()
        } else {
            branch.to_string()
        };
        self.build_authed_service_call(
            "ContentServerDirectory.GetManifestRequestCode#1",
            job_id,
            CContentServerDirectoryGetManifestRequestCodeRequest {
                app_id,
                depot_id,
                manifest_id,
                app_branch,
                branch_password_hash: String::new(),
            }
            .serialize(),
        )
    }

    pub fn build_get_cdn_servers_call(
        &self,
        cell_id: u32,
        job_id: u64,
    ) -> Option<OutboundServiceCall> {
        self.build_authed_service_call(
            "ContentServerDirectory.GetServersForSteamPipe#1",
            job_id,
            CContentServerDirectoryGetServersForSteamPipeRequest {
                cell_id,
                ..Default::default()
            }
            .serialize(),
        )
    }

    pub fn build_pics_access_tokens(
        &self,
        packageids: Vec<u32>,
        appids: Vec<u32>,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        self.build_job_proto_message(
            EMsg::CLIENT_PICS_ACCESS_TOKEN_REQUEST,
            job_id,
            CMsgClientPICSAccessTokenRequest { packageids, appids }.serialize(),
            0,
        )
    }

    pub fn build_pics_product_info(
        &self,
        packages: Vec<PicsPackageInfoReq>,
        apps: Vec<PicsAppInfoReq>,
        meta_data_only: bool,
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        self.build_job_proto_message(
            EMsg::CLIENT_PICS_PRODUCT_INFO_REQUEST,
            job_id,
            CMsgClientPICSProductInfoRequest {
                packages,
                apps,
                meta_data_only,
                single_response: false,
                ..Default::default()
            }
            .serialize(),
            0,
        )
    }

    /// Succeeds only for apps Steam gives away; a paid unowned app comes back with a non-OK
    /// EResult in the response body.
    pub fn build_request_free_license(
        &self,
        app_ids: &[u32],
        job_id: u64,
    ) -> Option<OutboundProtoMessage> {
        if app_ids.is_empty() {
            return None;
        }
        self.build_job_proto_message(
            EMsg::CLIENT_REQUEST_FREE_LICENSE,
            job_id,
            CMsgClientRequestFreeLicense {
                appids: app_ids.to_vec(),
            }
            .serialize(),
            0,
        )
    }

    pub fn route_inbound(
        &self,
        emsg: EMsg,
        header: &CMsgProtoBufHeader,
        body: &[u8],
    ) -> InboundAction {
        match emsg {
            EMsg::SERVICE_METHOD_RESPONSE
            | EMsg::CLIENT_PICS_ACCESS_TOKEN_RESPONSE
            | EMsg::CLIENT_GET_DEPOT_DECRYPTION_KEY_RESPONSE
            // 5573 carries its own EResult in the body; the job only reports transport health.
            | EMsg::CLIENT_REQUEST_FREE_LICENSE_RESPONSE => {
                if header.jobid_target == INVALID_JOB_ID {
                    InboundAction::Ignored
                } else {
                    let eresult = if header.eresult == -1 {
                        1
                    } else {
                        header.eresult
                    };
                    InboundAction::DeliverJob(JobResult {
                        eresult,
                        error_message: header.error_message.clone(),
                        body: body.to_vec(),
                        synthetic_failure: false,
                    })
                }
            }
            EMsg::CLIENT_PICS_PRODUCT_INFO_RESPONSE => InboundAction::PicsProductInfo,
            EMsg::MULTI => InboundAction::Multi,
            EMsg::CLIENT_LOGON_RESPONSE => {
                let Some(resp) = CMsgClientLogonResponse::deserialize(body) else {
                    return InboundAction::ParseFailed("ClientLogonResponse");
                };
                self.last_logon_eresult
                    .store(resp.eresult, Ordering::Relaxed);
                if resp.eresult == 1 {
                    self.state
                        .store(ClientState::LoggedOn as u8, Ordering::Relaxed);
                    self.steam_id
                        .store(resp.client_supplied_steamid, Ordering::Relaxed);
                    self.session_id
                        .store(header.client_sessionid, Ordering::Relaxed);
                    self.cell_id.store(resp.cell_id, Ordering::Relaxed);
                    InboundAction::LogonOk
                } else {
                    InboundAction::LoggedOff
                }
            }
            EMsg::CLIENT_LOGGED_OFF | EMsg::CLIENT_SERVER_UNAVAILABLE => {
                if self.state() == ClientState::LoggedOn {
                    self.state
                        .store(ClientState::Connected as u8, Ordering::Relaxed);
                }
                if emsg == EMsg::CLIENT_LOGGED_OFF {
                    let eresult = crate::steam::pb::cmsg_clientserver_login::CMsgClientLoggedOff::deserialize(body)
                        .map(|m| m.eresult)
                        .unwrap_or(2);
                    self.last_logon_eresult.store(eresult, Ordering::Relaxed);
                }
                self.steam_id.store(0, Ordering::Relaxed);
                self.session_id.store(0, Ordering::Relaxed);
                InboundAction::LoggedOff
            }
            EMsg::CLIENT_LICENSE_LIST => {
                let Some(msg) = CMsgClientLicenseList::deserialize(body) else {
                    return InboundAction::ParseFailed("ClientLicenseList");
                };
                let count = msg.licenses.len();
                *self.license_list.lock().expect("license list poisoned") = Some(msg.licenses);
                InboundAction::LicenseList(count)
            }
            _ => InboundAction::ClientMessage,
        }
    }
}

pub fn parse_cmsg_multi(body: &[u8]) -> Option<CMsgMultiBody> {
    let mut reader = crate::steam::proto_wire::Reader::new(body);
    let mut multi = CMsgMultiBody::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(multi);
        };
        match tag.field_number {
            1 => multi.size_unzipped = reader.u32()?,
            2 => multi.message_body = reader.bytes()?.to_vec(),
            _ => {
                if !reader.skip(tag.wire_type) {
                    return None;
                }
            }
        }
    }
    Some(multi)
}

pub fn decode_multi_records(body: &[u8]) -> Option<Vec<Vec<u8>>> {
    let multi = parse_cmsg_multi(body)?;
    let records = if multi.size_unzipped > 0 {
        gunzip_or_zlib(&multi.message_body, multi.size_unzipped as usize)?
    } else {
        multi.message_body
    };

    let mut out = Vec::new();
    let mut offset = 0usize;
    while offset + 4 <= records.len() {
        let inner_len = crate::steam::wire_format::read_u32_le(&records[offset..]) as usize;
        offset += 4;
        if inner_len == 0 || offset + inner_len > records.len() {
            return None;
        }
        out.push(records[offset..offset + inner_len].to_vec());
        offset += inner_len;
    }
    (offset == records.len()).then_some(out)
}

fn gunzip_or_zlib(compressed: &[u8], expected_size: usize) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(expected_size);
    if GzDecoder::new(compressed).read_to_end(&mut out).is_ok() && !out.is_empty() {
        return Some(out);
    }
    out.clear();
    if ZlibDecoder::new(compressed).read_to_end(&mut out).is_ok() && !out.is_empty() {
        return Some(out);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::steam::proto_envelope::decode_proto_envelope;
    use crate::steam::proto_wire::{WireType, Writer};
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::io::Write;

    #[test]
    fn service_method_call_sets_authed_and_non_authed_emsgs() {
        let core = CMClientCore::default();
        core.steam_id.store(123, Ordering::Relaxed);
        core.session_id.store(7, Ordering::Relaxed);
        let authed = decode_proto_envelope(&core.build_service_method_call(
            "ContentServerDirectory.GetServersForSteamPipe#1",
            true,
            55,
            b"req",
        ))
        .unwrap();
        assert_eq!(authed.emsg, EMsg::SERVICE_METHOD_CALL_FROM_CLIENT);
        assert_eq!(authed.header.steamid, 123);
        assert_eq!(authed.header.client_sessionid, 7);
        assert_eq!(authed.header.jobid_source, 55);
        assert_eq!(authed.body, b"req");
        let non = decode_proto_envelope(&core.build_service_method_call("X.Y#1", false, 56, b""))
            .unwrap();
        assert_eq!(non.emsg, EMsg::SERVICE_METHOD_CALL_FROM_CLIENT_NON_AUTHED);
    }

    #[test]
    fn cmsg_multi_decodes_plain_and_gzip_records() {
        let first = encode_proto_envelope(EMsg::CLIENT_HELLO, &CMsgProtoBufHeader::default(), b"a");
        let second = encode_proto_envelope(
            EMsg::CLIENT_LICENSE_LIST,
            &CMsgProtoBufHeader::default(),
            b"b",
        );
        let mut records = Vec::new();
        records.extend_from_slice(&(first.len() as u32).to_le_bytes());
        records.extend_from_slice(&first);
        records.extend_from_slice(&(second.len() as u32).to_le_bytes());
        records.extend_from_slice(&second);

        let mut body = Vec::new();
        Writer::new(&mut body).bytes_field(2, &records);
        assert_eq!(
            decode_multi_records(&body).unwrap(),
            vec![first.clone(), second.clone()]
        );

        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(&records).unwrap();
        let zipped = encoder.finish().unwrap();
        let mut zipped_body = Vec::new();
        {
            let mut writer = Writer::new(&mut zipped_body);
            writer.uint32_field(1, records.len() as u32);
            writer.bytes_field(2, &zipped);
        }
        assert_eq!(
            decode_multi_records(&zipped_body).unwrap(),
            vec![first, second]
        );
    }

    #[test]
    fn logon_needs_connected_state_and_carries_token_and_machine_id() {
        let core = CMClientCore::default();
        assert!(core
            .build_logon_with_refresh_token("refresh", "ada", 0)
            .is_none());
        core.set_state(ClientState::Connected);
        let logon = core
            .build_logon_with_refresh_token("refresh", "ada", 123)
            .unwrap();
        assert_eq!(logon.emsg, EMsg::CLIENT_LOGON);
        let decoded = decode_proto_envelope(&logon.wire).unwrap();
        assert_eq!(decoded.header.steamid, 0x0110_0001_0000_0000);
        assert!(decoded.body.windows(7).any(|w| w == b"refresh"));
        assert!(decoded
            .body
            .windows(MACHINE_ID.len())
            .any(|w| w == MACHINE_ID));
        assert!(logon.body.is_empty());
        assert!(core.build_logon_with_refresh_token("", "ada", 1).is_none());

        assert!(core.build_logoff().is_none());
        core.set_state(ClientState::LoggedOn);
        assert_eq!(core.build_logoff().unwrap().emsg, EMsg::CLIENT_LOG_OFF);
    }

    #[test]
    fn logon_response_updates_session_identity_and_eresult() {
        let core = CMClientCore::default();
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.int32_field(1, 1);
            w.tag(20, WireType::Fixed64);
            w.raw_bytes(&765u64.to_le_bytes());
        }
        let header = CMsgProtoBufHeader {
            client_sessionid: 42,
            ..Default::default()
        };
        assert_eq!(
            core.route_inbound(EMsg::CLIENT_LOGON_RESPONSE, &header, &body),
            InboundAction::LogonOk
        );
        assert_eq!(core.state(), ClientState::LoggedOn);
        assert_eq!(core.steam_id(), 765);
        assert_eq!(core.session_id(), 42);
        assert_eq!(core.last_logon_eresult(), 1);

        assert_eq!(
            core.route_inbound(EMsg::CLIENT_LOGGED_OFF, &CMsgProtoBufHeader::default(), &[]),
            InboundAction::LoggedOff
        );
        assert_eq!(core.state(), ClientState::Connected);
        assert_eq!(core.steam_id(), 0);

        let mut refused = Vec::new();
        Writer::new(&mut refused).int32_field(1, 5);
        let fresh = CMClientCore::default();
        assert_eq!(
            fresh.route_inbound(EMsg::CLIENT_LOGON_RESPONSE, &header, &refused),
            InboundAction::LoggedOff
        );
        assert_eq!(fresh.last_logon_eresult(), 5);
        assert_eq!(fresh.state(), ClientState::Disconnected);
    }

    #[test]
    fn license_list_push_is_stored() {
        let core = CMClientCore::default();
        assert!(core.license_list().is_none());
        assert_eq!(
            core.route_inbound(
                EMsg::CLIENT_LICENSE_LIST,
                &CMsgProtoBufHeader::default(),
                &[]
            ),
            InboundAction::LicenseList(0)
        );
        assert_eq!(core.license_list(), Some(Vec::new()));
    }

    #[test]
    fn downloader_builders_use_the_expected_messages() {
        let core = logged_on_core();
        let depot_key = core.build_get_depot_decryption_key(11, 22, 33).unwrap();
        let decoded = decode_proto_envelope(&depot_key.wire).unwrap();
        assert_eq!(decoded.emsg, EMsg::CLIENT_GET_DEPOT_DECRYPTION_KEY);
        assert_eq!(decoded.header.jobid_source, 33);

        let public = core
            .build_manifest_request_code_call(480, 100, 555, "public", 44)
            .unwrap();
        assert_eq!(
            public.method_name,
            "ContentServerDirectory.GetManifestRequestCode#1"
        );
        assert!(!public.request_body.windows(6).any(|w| w == b"public"));
        let beta = core
            .build_manifest_request_code_call(480, 100, 555, "Beta", 45)
            .unwrap();
        assert!(beta.request_body.windows(4).any(|w| w == b"Beta"));

        let servers = core.build_get_cdn_servers_call(7, 46).unwrap();
        assert_eq!(
            servers.method_name,
            "ContentServerDirectory.GetServersForSteamPipe#1"
        );

        let product = core
            .build_pics_product_info(
                vec![PicsPackageInfoReq {
                    packageid: 100,
                    access_token: 7,
                }],
                vec![PicsAppInfoReq {
                    appid: 480,
                    access_token: 8,
                    only_public_obsolete: false,
                }],
                true,
                3,
            )
            .unwrap();
        assert_eq!(product.emsg, EMsg::CLIENT_PICS_PRODUCT_INFO_REQUEST);
        assert!(product.body.windows(2).any(|w| w == [0x18, 1]));

        let free = core.build_request_free_license(&[440], 50).unwrap();
        assert_eq!(free.emsg, EMsg::CLIENT_REQUEST_FREE_LICENSE);
        assert_eq!(free.body, [0x10, 0xb8, 0x03]);
        assert!(core.build_request_free_license(&[], 51).is_none());
    }

    #[test]
    fn job_responses_are_delivered_and_builders_need_logon() {
        let core = logged_on_core();
        let header = CMsgProtoBufHeader {
            jobid_target: 50,
            ..Default::default()
        };
        assert!(matches!(
            core.route_inbound(EMsg::CLIENT_REQUEST_FREE_LICENSE_RESPONSE, &header, &[]),
            InboundAction::DeliverJob(_)
        ));
        assert_eq!(
            core.route_inbound(
                EMsg::SERVICE_METHOD_RESPONSE,
                &CMsgProtoBufHeader::default(),
                &[]
            ),
            InboundAction::Ignored
        );
        let offline = CMClientCore::default();
        assert!(offline.build_get_depot_decryption_key(1, 2, 3).is_none());
        assert!(offline.build_get_cdn_servers_call(0, 1).is_none());
    }

    fn logged_on_core() -> CMClientCore {
        let core = CMClientCore::default();
        core.state
            .store(ClientState::LoggedOn as u8, Ordering::Relaxed);
        core.steam_id.store(7656119, Ordering::Relaxed);
        core.session_id.store(42, Ordering::Relaxed);
        core
    }
}
