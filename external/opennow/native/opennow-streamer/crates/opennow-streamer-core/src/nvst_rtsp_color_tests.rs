use super::*;
use opennow_streamer_platform::{MediaColorQuality, MediaVideoCodec};

fn stream(color_quality: MediaColorQuality, hdr: bool) -> MediaStreamConfig {
    MediaStreamConfig {
        codec: MediaVideoCodec::H265,
        color_quality,
        hdr,
        width: 2560,
        height: 1440,
        fps: 120,
        ..MediaStreamConfig::default()
    }
}

#[test]
fn wire_values_use_literal_depth_and_chroma_format_idc() {
    for (quality, depth, chroma) in [
        (MediaColorQuality::EightBit420, 8, 1),
        (MediaColorQuality::EightBit444, 8, 3),
        (MediaColorQuality::TenBit420, 10, 1),
        (MediaColorQuality::TenBit444, 10, 3),
    ] {
        assert_eq!(
            announce_color_lines(stream(quality, false)),
            vec![
                format!("a=x-nv-video[0].bitDepth:{depth}"),
                format!("a=x-nv-video[0].chromaFormat:{chroma}"),
            ]
        );
    }
}

#[test]
fn dynamic_range_mode_is_hdr_only() {
    assert_eq!(
        announce_color_lines(stream(MediaColorQuality::TenBit420, true)),
        vec![
            "a=x-nv-video[0].bitDepth:10",
            "a=x-nv-video[0].chromaFormat:1",
            "a=x-nv-video[0].dynamicRangeMode:1",
        ]
    );
    assert_eq!(
        announce_color_lines(stream(MediaColorQuality::TenBit444, true)),
        vec![
            "a=x-nv-video[0].bitDepth:10",
            "a=x-nv-video[0].chromaFormat:3",
            "a=x-nv-video[0].dynamicRangeMode:1",
        ]
    );
    for quality in [
        MediaColorQuality::EightBit420,
        MediaColorQuality::EightBit444,
        MediaColorQuality::TenBit420,
        MediaColorQuality::TenBit444,
    ] {
        assert!(
            announce_color_lines(stream(quality, false))
                .iter()
                .all(|line| !line.contains("dynamicRangeMode")),
            "{quality:?}"
        );
    }
}

#[test]
fn ten_bit_420_matches_the_vendor_capture() {
    // The only vendor-observed 10-bit 4:2:0 value on the wire.
    assert_eq!(
        announce_color_lines(stream(MediaColorQuality::TenBit420, false)),
        vec![
            "a=x-nv-video[0].bitDepth:10",
            "a=x-nv-video[0].chromaFormat:1",
        ]
    );
}
