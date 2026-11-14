# DiRT 3 / DiRT Showdown ws2_32 stand-in

DiRT 3 (`dirt3_game.exe`) and DiRT Showdown (`showdown.exe`, `showdown_avx.exe`) close their motion telemetry UDP
socket at the end of a race, and when leaving Joyride, but keep calling `sendto()` on it. `sendto()` fails with
WSAENOTSOCK and the game stops with "sendto failed with error 10038". The same happens on Windows.

`ws2_32.dll` here is a 32-bit stand-in: every export jumps to the same-ordinal export of Wine's own `ws2_32.dll`;
only a `sendto()` that fails with 10038 reports 0 bytes sent instead. Same behaviour as
[DiRTTelemetryErrorFix](https://github.com/RoccoC/DiRTTelemetryErrorFix) by RoccoC (MIT), based on the original fix
by Carsten Gottschling.

Wine loads `ws2_32` as a KnownDLL, so a copy beside the game is ignored. `droiddeck-game-env` puts the stand-in in
place of the prefix's `syswow64/ws2_32.dll` for these games while their telemetry output is on, adds
`WINEDLLOVERRIDES=ws2_32=n,b`, and puts Wine's file back when the game exits. It skips the swap when the game's
Proton already has the fix in its own `ws2_32.dll` (DroidDeck-Proton patch
`dirt-telemetry-sendto-closed-socket.patch`).

`build.sh OUT` builds it with clang, lld and llvm-dlltool and checks its exports against `ws2_32-exports.txt`
(taken from Wine 11's `ws2_32.dll`).
