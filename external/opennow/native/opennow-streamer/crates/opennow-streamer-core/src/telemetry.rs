use super::*;

pub(super) const TELEMETRY_INTERVAL: Duration = Duration::from_secs(1);

pub(super) fn media_telemetry(
    elapsed: Duration,
    frames: u64,
    bytes: u64,
    socket_bytes: Option<u64>,
    previous_socket_bytes: &mut u64,
    peak_bitrate_mbps: &mut f64,
) -> Value {
    let seconds = elapsed.as_secs_f64();
    let bitrate_mbps = bytes as f64 * 8.0 / seconds / 1_000_000.0;
    let receive_bitrate_mbps = socket_bytes.and_then(|bytes| {
        bytes
            .checked_sub(*previous_socket_bytes)
            .map(|delta| delta as f64 * 8.0 / seconds / 1_000_000.0)
    });
    if let Some(bytes) = socket_bytes {
        *previous_socket_bytes = bytes;
    }
    *peak_bitrate_mbps = peak_bitrate_mbps.max(bitrate_mbps);
    json!({
        "framesPerSecond": frames as f64 / seconds,
        "bitrateMbps": bitrate_mbps,
        "receiveBitrateMbps": receive_bitrate_mbps,
        "peakBitrateMbps": peak_bitrate_mbps,
    })
}
