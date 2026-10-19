# VITURE protocol V2 attribution and scope

The frame builder, parser, pose/raw layouts, and commands in
`VitureV2Protocol.kt` are adapted from [elasticjava/viture-v2](https://github.com/elasticjava/viture-v2)
at commit `ece8c30485f7d357fefa362ae74f3eabac9b3899` (MIT).
Copyright (c) 2026 Holger Bartnick. The full license is in `LICENSE` here.

Sources used are upstream `PROTOCOL.md`, `src/lib.rs`, and `src/xr.rs`.
Upstream developed and measured its driver on VITURE Pro 2 XR `35ca:1301`.
Its research used captures of the proprietary SDK's USB traffic and logging;
the resulting implementation communicates directly over USB without that SDK.
This is an SDK-free implementation, not a claim of SDK-independent protocol research.
Our earlier local proprietary SDK trace was not used for this adaptation.

The Android transport and application integration are implemented in Kotlin.
Building this path requires no Rust toolchain, vendor SDK, vendor binary,
developer account, private download, or repository secret. It uses Android's
USB host permission and `UsbRequest` APIs. To explicitly build without the
optional vendor driver, pass the Gradle property `-PvitureSdkDir=`.

The application enables this experimental transport only for `35ca:1301`.
It requires valid pose samples before advertising 3DoF, uses the upstream
120 Hz pose mode, and treats the device timestamp as an uninterpreted counter.
Delivered timestamps use host `System.nanoTime()` receipt times. Bootstrap and
resume reject samples collected before the enable write completes. This host-time
cutoff does not establish when hardware produced a sample or purge device/kernel
buffers. The pose
basis conversion follows upstream's Y-up rendering convention. Hardware captures
verify changing, normalized poses during head motion; the relative rotation mapping
is unit-tested. The user also reported a successful Pro 2 game test; see the
validation notes for the scope of that report.

Raw gyro and accelerometer packets can be decoded with their complete payload
preserved, but raw data is not fused or used by the application. Upstream reports
that enabling pose and raw streams together can ACK successfully while sending
no samples, so the application requests pose alone. Accelerometer values remain
in g; gyroscope values are in radians/second according to upstream.

Brightness, volume, and display queries are supported. Setters, display switching,
6DoF, cameras, and hand tracking are not implemented by this path. Upstream's
README/protocol document and source disagree about setter coverage; this
adaptation does not use those setters without device validation.

The standalone `tools/viture/viture-v2-probe` captures descriptors, commands,
replies, and pose/raw packets for device compatibility testing. Its provenance
and license are included beside its source. Pro 2 hardware checks and their
limitations are recorded in [VALIDATION.md](VALIDATION.md).
