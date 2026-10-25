FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN dpkg --add-architecture arm64 \
    && sed -i '/^Types:/a Architectures: amd64' /etc/apt/sources.list.d/ubuntu.sources \
    && printf '%s\n' \
        'Types: deb' \
        'URIs: http://ports.ubuntu.com/ubuntu-ports/' \
        'Suites: noble noble-updates noble-security' \
        'Components: main universe restricted multiverse' \
        'Architectures: arm64' \
        'Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg' \
        > /etc/apt/sources.list.d/ubuntu-ports.sources \
    && apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends \
        build-essential pkg-config protobuf-compiler cmake curl git ca-certificates xz-utils python3 \
        gcc-aarch64-linux-gnu g++-aarch64-linux-gnu qemu-user \
        libssl-dev:arm64 libstdc++6:arm64 \
    && rm -rf /var/lib/apt/lists/*

ENV CC_aarch64_unknown_linux_gnu=aarch64-linux-gnu-gcc \
    CXX_aarch64_unknown_linux_gnu=aarch64-linux-gnu-g++ \
    AR_aarch64_unknown_linux_gnu=aarch64-linux-gnu-ar \
    PKG_CONFIG_ALLOW_CROSS=1 \
    PKG_CONFIG_LIBDIR_aarch64_unknown_linux_gnu=/usr/lib/aarch64-linux-gnu/pkgconfig:/usr/share/pkgconfig \
    CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=aarch64-linux-gnu-gcc \
    CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_RUNNER=qemu-aarch64
