use opennow_streamer_platform::MediaStreamConfig;

/// NVST ANNOUNCE color encoding, matching the official wire format.
///
/// A vendor capture of a `10bit_420` session carries `bitDepth:10
/// chromaFormat:1`: depth is literal (8 or 10) and chroma follows
/// `chroma_format_idc` (1 = 4:2:0, 3 = 4:4:4). Both lines are always sent
/// explicitly; the seat never sees a lone `bitDepth` line. `dynamicRangeMode`
/// is sent only for HDR (`1`); SDR omits it, matching the captured baseline.
///
/// The internal 0/1 chroma enum from the client's app-to-NVST conversion must
/// not reach the wire: emitting it (or suppressing the chroma line for 4:2:0)
/// leaves seats unable to initialize the encoder, which surfaces as an
/// accepted session that never delivers video.
pub(super) fn announce_color_lines(stream: MediaStreamConfig) -> Vec<String> {
    let mut lines = vec![
        format!(
            "a=x-nv-video[0].bitDepth:{}",
            stream.color_quality.bit_depth()
        ),
        format!(
            "a=x-nv-video[0].chromaFormat:{}",
            if stream.color_quality.is_444() { 3 } else { 1 }
        ),
    ];
    if stream.hdr {
        lines.push("a=x-nv-video[0].dynamicRangeMode:1".to_owned());
    }
    lines
}

#[cfg(test)]
#[path = "nvst_rtsp_color_tests.rs"]
mod tests;
