# Rockstar launcher service

`Launcher.exe` starts the selected installed game and serves its launcher pipe. The Android app checks ownership and retrieves the launch response from Rockstar. Account credentials and ROS tickets stay in the app.

The handoff is deleted before starting the game. It expires after 90 seconds. The pipe accepts the child process's greeting and returns the server-issued launch material. No game files are patched.

GTA III's generated launch script defaults to full x87 precision for FEX. Reduced precision crashed new-game initialization on the test device. An explicit game environment override takes precedence.

`build.sh` builds 32-bit and 64-bit executables with the GNU Windows targets and stages them as APK resources. `legacy-cipher.bin` contains the fixed client protocol tables, not account credentials or APK signing keys.

Run the protocol tests with:

```sh
cargo test --locked --manifest-path tools/rockstar-launcher/Cargo.toml
```

The optional `capture` example records a first-party launcher reply to a new local file. Run it on Windows or Wine while launching the corresponding game in Rockstar's launcher:

```sh
cargo run --release --example capture --manifest-path tools/rockstar-launcher/Cargo.toml -- --pipe GTA-VC_Pipe --output /path/outside/the/repository/reply.bin
```

Captures contain live launch material. Keep them outside source control; the tool prints only the message tag and payload length.
