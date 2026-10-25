use super::{NvstRtspError, sdp_attribute};
use opennow_streamer_transport::NvstRecoveryPolicy;
use serde_json::{Value, json};
use std::ops::RangeInclusive;

fn ignore_offer(name: &str, value: &str) {
    opennow_streamer_protocol::log::log_line(
        "WARN",
        "transport",
        &format!("ignored-server-offer name={name} value={value}"),
    );
}

pub(super) fn offered_bool(sdp: &str, name: &str) -> Option<bool> {
    let value = sdp_attribute(sdp, name)?;
    match value.as_str() {
        "0" => Some(false),
        "1" => Some(true),
        _ => {
            ignore_offer(name, &value);
            None
        }
    }
}

fn offered_u32(sdp: &str, name: &str) -> Option<u32> {
    let value = sdp_attribute(sdp, name)?;
    value
        .parse()
        .inspect_err(|_| ignore_offer(name, &value))
        .ok()
}

fn offered_in(sdp: &str, name: &str, range: RangeInclusive<u32>) -> Option<u32> {
    let value = offered_u32(sdp, name)?;
    if range.contains(&value) {
        return Some(value);
    }
    ignore_offer(name, &value.to_string());
    None
}

pub(super) fn recovery_policy(sdp: &str) -> NvstRecoveryPolicy {
    NvstRecoveryPolicy {
        ref_pic_invalidation: offered_bool(sdp, "video[0].refPicInvalidation"),
        stream_recovery_type: offered_u32(sdp, "video[0].streamRecoveryType"),
        frames_with_invalid_ref_threshold: offered_u32(
            sdp,
            "video[0].framesWithInvalidRefThreshold",
        ),
        enable_recovery_mode: offered_bool(sdp, "general.enableRecoveryMode"),
        recovery_mode_trigger_threshold_ms: offered_u32(
            sdp,
            "general.recoveryModeTriggerThresholdMs",
        ),
        enable_intra_refresh_in_recovery_mode: offered_bool(
            sdp,
            "general.enableIntraRefreshInRecoveryMode",
        ),
        encoder_ltr_feature_setting: offered_u32(sdp, "video[0].encoderLtrFeatureSetting"),
    }
}

pub(super) fn negotiated_announce(
    announce: &str,
    describe: &str,
    handoff: &mut Value,
) -> Result<String, NvstRtspError> {
    let mut values = serde_json::Map::new();
    for (name, minimum, maximum) in [
        ("vqos[0].fec.enable", 0, 1),
        ("vqos[0].fec.rateDropWindow", 0, u32::MAX),
        ("vqos[0].fec.minRequiredFecPackets", 0, u32::MAX),
        ("vqos[0].fec.repairPercent", 0, 100),
        ("vqos[0].fec.repairMinPercent", 0, 100),
        ("vqos[0].fec.repairMaxPercent", 0, 100),
        ("vqos[0].bllFec.enable", 0, 1),
        ("packetPacing.version", 3, 3),
        ("packetPacing.mode", 1, 1),
        ("packetPacing.numGroups", 1, u32::MAX),
        ("packetPacing.maxDelayUs", 0, u32::MAX),
        ("packetPacing.minNumPacketsFrame", 0, u32::MAX),
        ("packetPacing.minNumPacketsPerGroup", 0, u32::MAX),
        ("packetPacing.enableAccurateSleep", 0, 1),
        ("packetPacing.enableSmoothTransition", 0, 1),
        ("packetPacing.allowFpsBasedToggle", 0, 1),
    ] {
        let value = offered_in(describe, name, minimum..=maximum)
            .or(offered_u32(announce, name))
            .unwrap();
        values.insert(name.to_owned(), json!(value));
    }
    let mut min = values["vqos[0].fec.repairMinPercent"].as_u64().unwrap();
    let mut max = values["vqos[0].fec.repairMaxPercent"].as_u64().unwrap();
    if min > max {
        ignore_offer("vqos[0].fec.repairMinPercent", &format!("{min}>{max}"));
        min = u64::from(offered_u32(announce, "vqos[0].fec.repairMinPercent").unwrap());
        max = u64::from(offered_u32(announce, "vqos[0].fec.repairMaxPercent").unwrap());
        values["vqos[0].fec.repairMinPercent"] = json!(min);
        values["vqos[0].fec.repairMaxPercent"] = json!(max);
    }
    let repair = values["vqos[0].fec.repairPercent"].as_u64().unwrap();
    values["vqos[0].fec.repairPercent"] = json!(repair.clamp(min, max));
    let ceiling = offered_u32(announce, "vqos[0].bw.maximumBitrateKbps").unwrap();
    let initial = offered_in(describe, "video[0].initialBitrateKbps", 1..=u32::MAX)
        .unwrap_or(ceiling)
        .min(ceiling);
    let peak = offered_u32(describe, "video[0].initialPeakBitrateKbps")
        .unwrap_or(ceiling)
        .clamp(initial, ceiling);
    values.insert("video[0].initialBitrateKbps".to_owned(), json!(initial));
    values.insert("video[0].initialPeakBitrateKbps".to_owned(), json!(peak));
    values.insert("vqos[0].bw.maximumBitrateKbps".to_owned(), json!(ceiling));
    opennow_streamer_protocol::log::log_line(
        "INFO",
        "transport",
        &format!("negotiated-encoding={}", Value::Object(values.clone())),
    );
    let mut result = String::with_capacity(announce.len());
    for line in announce.split_inclusive("\r\n") {
        let attribute = line
            .strip_prefix("a=x-nv-")
            .and_then(|line| line.split_once(':'));
        if let Some((name, _)) = attribute
            && let Some(value) = values.get(name)
        {
            result.push_str(&format!("a=x-nv-{name}:{value}\r\n"));
        } else {
            result.push_str(line);
        }
    }
    handoff["framePacingFeedbackMode"] =
        json!(super::video_qos_offers(describe).pacing_feedback_mode(&result)?);
    Ok(result)
}

pub(super) fn microphone_handoff(sdp: &str, handoff: &mut Value) {
    let enabled = offered_bool(sdp, "aqos.enableRedundancyForMic").unwrap_or(false);
    let level = if enabled {
        offered_in(sdp, "aqos.redundancyLevelForMic", 0..=3).unwrap_or(0)
    } else {
        0
    };
    handoff["microphoneRedundancyLevel"] = json!(level);
    let mut in_mic = false;
    let mut payloads = Vec::new();
    for line in sdp
        .split("||")
        .next()
        .unwrap_or_default()
        .split(";;")
        .next()
        .unwrap_or_default()
        .lines()
        .map(str::trim)
    {
        if let Some(media) = line.strip_prefix("m=") {
            let mut parts = media.split_whitespace();
            in_mic = parts
                .next()
                .is_some_and(|kind| kind.eq_ignore_ascii_case("mic"));
            payloads = if in_mic {
                parts.skip(2).collect()
            } else {
                Vec::new()
            };
        } else if in_mic
            && let Some(mapping) = line.strip_prefix("a=rtpmap:")
            && let Some((payload, encoding)) = mapping.split_once(char::is_whitespace)
        {
            if !payloads.contains(&payload) {
                continue;
            }
            let Some(codec) = encoding.split('/').next() else {
                continue;
            };
            let field = if codec.eq_ignore_ascii_case("opus") {
                "microphoneOpusPayloadType"
            } else if codec.eq_ignore_ascii_case("red") {
                "microphoneRedPayloadType"
            } else {
                continue;
            };
            let Some(payload) = payload.parse::<u8>().ok().filter(|value| *value <= 127) else {
                ignore_offer(field, payload);
                continue;
            };
            handoff[field] = json!(payload);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recovery_describe_is_typed_and_does_not_guess_defaults() {
        let policy = recovery_policy(
            "a=x-nv-video[0].refPicInvalidation:1\r\na=x-nv-video[0].streamRecoveryType:2\r\na=x-nv-video[0].framesWithInvalidRefThreshold:4\r\na=x-nv-general.enableRecoveryMode:1\r\na=x-nv-general.recoveryModeTriggerThresholdMs:6000\r\na=x-nv-general.enableIntraRefreshInRecoveryMode:1\r\na=x-nv-video[0].encoderLtrFeatureSetting:2\r\n",
        );
        assert_eq!(policy.ref_pic_invalidation, Some(true));
        assert_eq!(policy.stream_recovery_type, Some(2));
        assert_eq!(policy.frames_with_invalid_ref_threshold, Some(4));
        assert_eq!(policy.recovery_mode_trigger_threshold_ms, Some(6000));
        assert_eq!(policy.encoder_ltr_feature_setting, Some(2));
        assert_eq!(recovery_policy(""), NvstRecoveryPolicy::default());
        assert_eq!(
            recovery_policy(
                "a=x-nv-video[0].refPicInvalidation:2\r\na=x-nv-video[0].framesWithInvalidRefThreshold:-1\r\n"
            ),
            NvstRecoveryPolicy::default()
        );
    }

    #[test]
    fn mic_handoff_uses_only_its_offered_payloads_and_red_depth() {
        let mut handoff = json!({});
        microphone_handoff(
            "a=x-nv-aqos.enableRedundancyForMic:1\r\na=x-nv-aqos.redundancyLevelForMic:3\r\nm=audio 5000 RTP/AVP 111 112\r\na=rtpmap:111 opus/48000/2\r\na=rtpmap:112 red/48000/2\r\nm=mic 5002 RTP/AVP 113 114\r\na=rtpmap:113 opus/48000/1\r\na=rtpmap:114 red/48000/1\r\na=rtpmap:115 red/48000/1\r\n",
            &mut handoff,
        );
        assert_eq!(
            handoff,
            json!({"microphoneOpusPayloadType":113,"microphoneRedPayloadType":114,"microphoneRedundancyLevel":3})
        );
        for sdp in [
            "",
            "a=x-nv-aqos.enableRedundancyForMic:0\r\na=x-nv-aqos.redundancyLevelForMic:3\r\nm=audio 5000 RTP/AVP 111\r\na=rtpmap:111 opus/48000/2\r\n",
        ] {
            let mut handoff = json!({});
            microphone_handoff(sdp, &mut handoff);
            assert_eq!(handoff, json!({"microphoneRedundancyLevel":0}));
        }
        for sdp in [
            "m=mic 5002 RTP/AVP 128\r\na=rtpmap:128 opus/48000/1\r\n",
            "a=x-nv-aqos.enableRedundancyForMic:1\r\na=x-nv-aqos.redundancyLevelForMic:4",
        ] {
            let mut handoff = json!({});
            microphone_handoff(sdp, &mut handoff);
            assert_eq!(handoff, json!({"microphoneRedundancyLevel":0}));
        }
    }

    #[test]
    fn mic_describe_handoff_reaches_the_live_transport_parser() {
        use opennow_streamer_transport::{NvstConfigError, NvstVideoConfig};

        let mut handoff = json!({
            "clientUdpPort":49005,"videoPeerIp":"192.0.2.1","videoPeerPort":5004,
            "srtpAesKeyHex":"01".repeat(32),"srtpSaltHex":"02".repeat(12),"codec":"H264",
            "microphoneOnBundle":true,"pingVersion":6,
            "localIceUsernameFragment":"loc1","localIcePassword":"local-password-value-01",
            "remoteIceUsernameFragment":"remote01","remoteIcePassword":"remote-password-value-01",
            "remoteDtlsFingerprint":"AA:BB"
        });
        microphone_handoff(
            "a=x-nv-aqos.enableRedundancyForMic:1\r\na=x-nv-aqos.redundancyLevelForMic:3\r\nm=mic 5002 RTP/AVP 113 114\r\na=rtpmap:113 opus/48000/1\r\na=rtpmap:114 red/48000/1\r\n",
            &mut handoff,
        );
        assert_eq!(handoff["microphoneOpusPayloadType"], 113);
        assert_eq!(handoff["microphoneRedPayloadType"], 114);
        assert_eq!(handoff["microphoneRedundancyLevel"], 3);
        assert!(
            NvstVideoConfig::from_legacy_handoff(&handoff, None)
                .unwrap()
                .microphone_available()
        );

        for (field, invalid) in [
            ("microphoneOpusPayloadType", 78),
            ("microphoneRedPayloadType", 128),
            ("microphoneRedPayloadType", 113),
            ("microphoneRedundancyLevel", 4),
        ] {
            let mut invalid_handoff = handoff.clone();
            invalid_handoff[field] = json!(invalid);
            assert!(
                matches!(NvstVideoConfig::from_legacy_handoff(&invalid_handoff, None),
                Err(NvstConfigError::OutOfRange {field: rejected}) if rejected == field),
                "the live transport parser must consume and validate {field}"
            );
        }
        microphone_handoff(
            "a=x-nv-aqos.enableRedundancyForMic:0\r\na=x-nv-aqos.redundancyLevelForMic:3\r\n",
            &mut handoff,
        );
        assert_eq!(handoff["microphoneRedundancyLevel"], 0);
        assert!(
            NvstVideoConfig::from_legacy_handoff(&handoff, None)
                .unwrap()
                .microphone_available()
        );
    }
}
