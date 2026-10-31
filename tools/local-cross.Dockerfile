FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y binutils gcc g++ python3 zstd unzip \
    && if [ "$(uname -m)" = aarch64 ]; then \
        for tool in gcc g++ strip readelf objdump ar; do ln -s "$(command -v $tool)" /usr/local/bin/aarch64-linux-gnu-$tool; done; \
    else \
        apt-get install -y binutils-aarch64-linux-gnu gcc-aarch64-linux-gnu g++-aarch64-linux-gnu; \
    fi \
    && mkdir -p /src

WORKDIR /src
