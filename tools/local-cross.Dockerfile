FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y \
        binutils \
        binutils-aarch64-linux-gnu \
        gcc \
        g++ \
        gcc-aarch64-linux-gnu \
        g++-aarch64-linux-gnu \
        python3 \
        zstd \
        unzip \
    && mkdir -p /src

WORKDIR /src
