FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN dpkg --add-architecture arm64 \
    && sed -i '/^Types: deb$/a Architectures: amd64' /etc/apt/sources.list.d/ubuntu.sources \
    && printf '%s\n' \
        'deb [arch=arm64 signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] http://ports.ubuntu.com/ubuntu-ports/ noble main universe restricted multiverse' \
        'deb [arch=arm64 signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] http://ports.ubuntu.com/ubuntu-ports/ noble-updates main universe restricted multiverse' \
        'deb [arch=arm64 signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] http://ports.ubuntu.com/ubuntu-ports/ noble-backports main universe restricted multiverse' \
        'deb [arch=arm64 signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] http://ports.ubuntu.com/ubuntu-ports/ noble-security main universe restricted multiverse' \
        > /etc/apt/sources.list.d/arm64.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        binutils \
        binutils-aarch64-linux-gnu \
        gcc \
        g++ \
        gcc-aarch64-linux-gnu \
        g++-aarch64-linux-gnu \
        ca-certificates \
        cmake \
        curl \
        git \
        libdbus-1-dev:arm64 \
        libssl-dev:arm64 \
        pkg-config \
        python3 \
        protobuf-compiler \
        zstd \
        unzip \
        xz-utils \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /src

WORKDIR /src
