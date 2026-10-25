# DroidDeck changes to the vendored OpenNOW code

Upstream: OpenCloudGaming/OpenNOW a49b124c. Each change keeps upstream behaviour on
Windows, macOS and Linux.

- `opennow-core/src/update_apply/{process,security,mod,managed,recovery}.rs`: the
  Linux-only process, ACL and boot-id helpers also cover Android (`target_os =
  "android"`), so the core compiles for aarch64-linux-android. DroidDeck never runs
  the self-updater.
