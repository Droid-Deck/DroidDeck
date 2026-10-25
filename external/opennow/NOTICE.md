# OpenNOW (vendored)

This folder holds parts of **OpenNOW**, an open-source GeForce NOW client by
**Zortos** ([zortos293](https://github.com/zortos293)) and the OpenNOW contributors:
<https://github.com/OpenCloudGaming/OpenNOW>, MIT License (see `LICENSE`).

Imported from OpenCloudGaming/OpenNOW commit `a49b124ce31e87113fd4961f6a321fbf0ad1fdc5`
(2026-10-18). Only the parts DroidDeck's GeForce NOW store uses are kept:

- `native/opennow-core`: accounts, sign-in, catalog and CloudMatch session services
  (newline-delimited JSON over stdin/stdout, `docs/core-protocol.md`).
- `native/opennow-streamer`: the native NVST streamer (protocol, transport, core,
  platform, FFI) plus its vendored `str0m` and `audiopus-sys`.

DroidDeck's own changes to these files are listed in `CHANGES.md`. NVIDIA and
GeForce NOW are trademarks of NVIDIA Corporation; neither OpenNOW nor DroidDeck is
affiliated with or endorsed by NVIDIA.
