Wayland client headers (MIT, wayland 1.26.0, from the runtime's Arch Linux ARM package) for the SteamVR
driver's presenter (tools/vr/src/presenter.cpp). `src/wayland_client_stub.c` is only linked against at
build time, so the driver gets a `libwayland-client.so.0` dependency without the build needing the guest's
library; the runtime's real one is used on the device.
