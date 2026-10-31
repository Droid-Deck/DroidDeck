# EA app installation for Steam games

Titanfall 2 (Steam app 1237970) starts through
`steam2ea://launchgame/1237970?platform=steam&theme=tf2`. Its game-bundled installer is
`__Installer/Origin/redist/internal/EAappInstaller.exe`.

On the affected DroidDeck 0.6.0 installation, the game prefix had an
`EADesktopSetup=1` Steam install-script marker and a matching `.droiddeck-redists`
ledger entry, but no EA app or `steam2ea` protocol handler. Proton failed to open
the URI with `ret 31`. The installer had been treated as a supplied redistributable.

## Installation flow

- `droiddeck-seed-redists` leaves `EAappInstaller.exe` to Steam. It removes only
  EA markers recorded in its own older ledger, including its copied registry view.
  Unrelated values, real installation markers outside the ledger, and saves remain.
- Before Steam evaluates a script containing an EA installer process,
  `droiddeck-ea` checks for the EA app. If it is missing, the helper runs the
  game-bundled installer with `/quiet /norestart EAX_LAUNCH_CLIENT=0
  IGNORE_INSTALLED=1`, through the selected Proton and the same compatibility
  prefix. Steam then receives its original evaluator command and environment.
  Neither game-owned install scripts nor the generated evaluator are changed:
  Steam validates install-script signatures, and privileged registry writes
  require a valid signature. See the
  [Steamworks install-script documentation](https://partner.steamgames.com/doc/sdk/installscripts?l=english).
- The direct EA installer uses `STEAM_GAME_DISPLAY_0` when Steam provides it.
  Otherwise it keeps the inherited display. On the reported phone, installation on the
  Steam display `:0` failed with X11 `BadWindow`; quiet installation on game display
  `:1` returned zero and registered `steam2ea` successfully.
- An older Steam client may go straight to the URI without evaluating scripts
  again. For that path, missing EA app executables trigger the same bundled
  installer before continuing with the original URI launch.
  Both paths check the installation again under a per-prefix lock, and require
  both a successful installer exit and the EA executables before continuing.
  The helper neither downloads a replacement installer nor creates a false
  success marker. Steam retains responsibility for its install-script markers.

The helper recognizes EA's stable `EA Desktop/EA Desktop` directory and its
versioned `<version>/EA Desktop` directories. An existing installation is reused.
Failed or incomplete installations can be retried by starting the game again.
Executable checks detect a missing or partial app installation; they do not
independently verify that the `steam2ea` protocol is correctly registered.

## Verification

Run the focused regression suites in the Linux development environment:

```sh
python3 -m unittest discover -s tools/tests -p 'test_seed_redists.py'
python3 -m unittest discover -s tools/tests -p 'test_ea_launcher.py'
python3 -m unittest discover -s tools/tests -p 'test_game_environment.py'
```

The phone diagnosis validated the bundled installer, silent arguments, game
display and successful EA authorization. The automated source integration still
needs an APK/device check with a fresh EA prefix and a previously seeded prefix.
The APK installed on the reported phone can launch Titanfall 2; font rendering
is still incorrect. This PR covers EA installation, not the remaining font issue,
and does not change the selected Proton, GPU settings or cloud-save preferences.
