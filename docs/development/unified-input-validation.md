# Unified input validation

Recorded on 2026-10-24. These results distinguish the device-tested candidate from
the later upstream rebase and host regression fixes. They do not establish zero
latency or complete coverage of every controller and session mode.

## Revisions and environment

The Thor candidate was `5021e7ac` on base `ac0909ea`, with the local temporary-file,
cursor visibility/handoff/frame cadence, scroll direction and button label fixes
subsequently committed on this branch. Its final APK SHA-256 was
`bb211095d6443e9f85ed1f5d8c5674f527df2937ac6b0efe7d6fc4adcb9dcf62`.

The branch was then rebased onto upstream `906e0d1f`. Review added regressions and
fixes for final touch-source release, concurrent native trackpad contact ownership,
and resetting the new preferences. Those final changes and the rebase have host
test coverage; the installed Thor APK was not silently replaced and those exact
changes have not yet been revalidated on the device.

Local Android builds use Java 17 and NDK `28.2.13676358`. The Thor test APK used a
local Gradle init script to install alongside the official app; no application ID
or app-name changes are part of this PR. Local builds use `-PskipRust=true`, so
Steam/Desktop checks do not establish the behavior of the Epic/GOG/Amazon store
engines in a complete release build.

## Automated checks

On the rebased implementation, the complete `:app:testDebugUnitTest` run passed:
**2,230 tests across 333 suites, zero failures/errors/skips**.

The supported `tools/build_local.sh` debug build also passed, including session
asset matching, runtime-input attestation, native export/dependency validation,
ZIP alignment and signature verification. The APK SHA-256 was
`a7d440b4029f9ad67ca6f44c92ccdd6b9eba9e9a758b28ab0e432256697d49b9`.
The only local environment repair was moving a Finder `.DS_Store` file out of the
NDK's host-toolchain directory; the upstream script counted it as another host.
No build checks were disabled to get past the stale-runtime check after rebasing.

Commands, with Java/SDK paths configured for the local machine:

```sh
./gradlew :app:testDebugUnitTest -PskipRust=true -PndkVersion=28.2.13676358
DROIDDECK_BUILD_VARIANT=debug DROIDDECK_SKIP_RUST=1 \
  DROIDDECK_NDK_VERSION=28.2.13676358 bash tools/build_local.sh
# Linux with gcc and g++ available:
python3 -m unittest tools.tests.test_fakeinput_open -v
```

Coverage includes profile persistence and migration, invalid bindings, shared
holds, key order, trigger/direction hysteresis, neutral gating on profile changes,
source disposal, raw BLE report preservation, physical key capture, global reset,
existing controller/gesture/keyboard paths, pointer handoff, and Compose settings
and layout editor behavior.

Two additional ownership regressions were demonstrated failing before their
fixes, then passed in the full suite:

- Removing the last local touch surface must send the final release to the legacy
  Epic mouse route, while removing only one of two holds must preserve the other.
- Concurrent native Deck pad surfaces must retain the first contact's coordinates
  until it ends, then transfer ownership and release correctly.

The native preload regression probe compiles the actual library and compares
`open`, `openat`, `open64` and `openat64` with libc. It covers ordinary file
creation, `O_TMPFILE` permissions, and opening a directory without a mode argument
(12 combinations). The temporary-file fix addresses the KDE cache permissions
problem encountered during the Desktop test; it does not change controller
transport or publication ordering.

All three test methods passed on Linux x86-64 after rebasing. The same 12
combinations also passed in the earlier i386 probe.

Earlier native testing also exposed three i386 ring-test failures, reproduced on
the unmodified pre-feature baseline. These are not presented as passing or fixed
by this PR. Physical BLE coverage is likewise not implied by synthetic tests.

### PR review follow-up

After the first CodeRabbit review of PR #1439, nine regression tests were added
for deferred pad-ring opening, releases to an already-open ring, system-owned
Steam/VR actions, and retained input when replacing a router. Seven of the new
scenarios reproduced failures before the fixes. The retained-input tests cover
physical and touch button ownership, native sticks, Deck grips and trackpad
contacts; a separate regression verifies return to the native identity fast path
without scheduling output after held controls reach neutral.

The full `:app:testDebugUnitTest` run now passes **2,239 tests across 334 suites,
zero failures/errors/skips**. `:app:assembleDebug` also passes with the existing
verified native inputs and the same Java/SDK/NDK and `-PskipRust=true` settings.
The resulting APK passes `check_session_assets.py` and `runtime_inputs.py apk`;
its SHA-256 is
`1c77c85d5d08c4a054db0528ade33d60eaf8755c09a56a598f342207b27c85d4`.

These follow-up changes have not been installed on Thor or benchmarked on the
device. Retained-state initialization runs when sources attach; ordinary input
keeps its existing synchronous routing and native identity path. The VR command
uses the existing chord handler for its release, retaining the 120 ms pulse.

### SELECT and panel ownership follow-up

The next review covered SELECT-to-GUIDE routing and child-view ownership. The
existing one-time Select-as-Steam migration is exercised through real controller
key events, including simultaneous physical GUIDE ownership. Explicit native,
disabled and local SELECT assignments, and resetting the Game profile, remain
authoritative. Removing the obsolete bridge flag keeps raw SELECT state stable
when a router is attached or removed while the button is held.

Panel regressions use an attached Android view hierarchy and a two-finger
trackpad contact. Switching the other stick to a trackpad, or removing the D-pad
or face buttons, must release only that child's controls and preserve the other
trackpad's contact, coordinates and click. Whole-panel removal still releases
the shared source. These three panel scenarios and the retained SELECT scenario
failed before the fixes; all seven new tests pass after them.

The complete JVM run passes **2,246 tests across 336 suites, zero failures/errors/
skips**. The debug APK build, session-asset check and runtime-input verification
pass with the same local build settings. APK SHA-256:
`0d85b4003cd6b31084e50d603e8b00f239775c9c97f3ec17327264db5dd50612`.
These changes have host coverage only; Thor validation and new latency
measurements remain pending.

### Panel tab teardown follow-up

Tab changes now remove the old child views before releasing the panel's touch
source. Child detach callbacks can publish button releases, so source cleanup
must run after those callbacks to avoid retaining a neutral source.

The new tab-switch regression fails before this fix and passes after it. It
checks held-button release, removal of the old source before later physical
input, and touch input after returning to the Gamepad tab. A second regression
confirms that whole-panel removal already releases the source correctly.
All **22 targeted tests** in the panel ownership, input ownership and second-screen
suites pass. The debug APK build, session-asset check and runtime-input
verification also pass. This follow-up has not been tested on Thor or benchmarked
on the device.

### Upstream integration (2026-10-27)

Merged upstream `43a0ce93` while preserving the published PR history. The merge
retains upstream screen keys, portrait controls and Home/Back shortcuts alongside
the input profiles and custom second-screen layouts. Screen keys have their own
held-key owner, and explicit physical Back bindings take precedence over the
default QAM shortcut.

Queued Home/Back chord reports now use current routed axes, including touch
sources, rather than raw physical axes. Router/profile changes and disabled
routing discard queued reports. Cancelling a chord or releasing all input keeps
held controls blocked until neutral, including Deck grips. Ordinary routed input
still publishes synchronously without waiting for the chord queue.

Eleven integration regressions cover these interactions. Four routed-axis and
cancellation scenarios failed before their fixes. The existing panel ownership
fixture now explicitly selects a Deck controller, matching upstream's restriction
of native trackpad controls to that controller type.

The complete `:app:testDebugUnitTest` run passes **2,632 tests across 385 suites,
zero failures/errors/skips**, using Java 17, NDK `28.2.13676358` and
`-PskipRust=true`. This compiles the Android Java/Kotlin code and resources; a new
APK build, Thor validation and device latency measurements were not performed
for this merge.

### QAM and repeated enable-state review (2026-10-27)

A routed QAM press during an existing emulated chord now publishes simultaneous
button and stick changes immediately. Repeated enable/disable requests no longer
republish unchanged pad or Deck reports; the router reports whether its state
changed, so the bridge does not keep a second copy of that state.

Both regressions failed before the fixes. Two additional tests cover replacing a
disabled router and enabling a router that was disabled before attachment. The
complete `:app:testDebugUnitTest` run passes **2,636 tests across 385 suites, zero
failures/errors/skips**, with the same Java/SDK/NDK settings and `-PskipRust=true`.
These fixes have not been tested or benchmarked on Thor.

## Manual AYN Thor results

The user performed the following checks in the installed candidate. The desktop
application was KWrite; native gamepad input was checked in Steam's controller
input test. A loaded game's input-to-display response was not measured.

| Area | Observed result |
| --- | --- |
| Native Game profile | Physical buttons, D-pad, both sticks/clicks and triggers work without observed missed input or stuck controls |
| Desktop defaults | Left stick moves pointer; A/B click; X/Y provide Enter/Escape; D-pad supplies arrows; right stick scrolls and stops at neutral |
| Scroll direction | Stick down advances to higher line numbers; stick up returns to earlier lines |
| Cursor visibility | Controller buttons and stick movement no longer hide the mapped pointer |
| Pointer handoff | Lower trackpad and stick can alternate without a jump; click position matches cursor |
| Cursor cadence | User confirmed smooth movement after the frame update fix; measurements below |
| Android keyboard | Text entry works on the lower screen, including while moving the pointer; controls continue after hiding the keyboard |
| Configured shortcut | R3 assigned to Ctrl+F through the settings UI repeatedly opens search; Y/Escape closes it; subsequent typing has no stuck Ctrl |
| Combined keyboard input | Physical modifier with Android keyboard supports Ctrl+A, Ctrl+X, Ctrl+V and Ctrl+S |
| Custom lower screen | Right pad moves pointer, left pad scrolls, A/B click, and View/Select opens the keyboard |
| Shared mouse hold | Physical A and screen A can hold together; releasing the physical button preserves selection until the screen button is released |
| Menu/held input | User reported successful menu/Ctrl release and ordinary typing afterward |
| Android Home/resume | Physical stick, lower trackpad, clicks and keyboard shortcuts work after returning |
| Layout persistence | Removing/readding a button through the editor persists across APK update and restart; defaults restored through the editor |
| Label rendering | Long View/Select and Menu/Start labels fit within their buttons |
| Native Deck lower screen | Both trackpads, clicks, L4/L5/R4/R5 and physical controls work in Steam's input test |

The editor's individual compatible-native and disabled options are host-tested,
not an exhaustive manual hardware matrix. Menu is currently an open command;
Back/B closes it. Multi-button activation chords are not implemented.

## Relative latency measurement

### What was measured

On the same Thor, timestamps from the built-in controller's evdev stream using
`CLOCK_MONOTONIC` were matched to a read-only guest observer of the published
controller ring. The observer polled with a requested 250 microsecond sleep,
roughly 0.31 ms in practice. Each observation bounds receipt between polls; the
table reports the **upper bound**, not an exact physical latency.

This path includes Android dispatch and publication. It excludes controller
firmware, Steam Input/game processing, rendering and display scanout. USB was
used for control and collection, not as the timed input transport. This is not a
button-to-photon test.

The public 0.6.0 build (`22eac083`) was sampled first. A locally instrumented
baseline at `ac0909ea` was then compared with the feature candidate; their relevant
baseline Java/native input implementation matched. Both used Steam Deck emulation,
1280×720 at 60 Hz, Steam's main screen, and the same disabled low-latency options.

The manual samples used physical B presses. Automatic samples injected B edges
into the built-in controller's evdev path, continuing through normal Android
input dispatch. Settled paired runs used a 150-press warmup before 150 measured
presses (300 edges) at approximately 33°C battery temperature. Earlier preliminary
runs were affected by background preparation and are kept separate.

### Results

All values below are milliseconds, kernel event to observer upper bound.

| Run | Matched edges | p50 | p95 | p99 | Max |
| --- | ---: | ---: | ---: | ---: | ---: |
| Public 0.6.0, manual | 84 | 4.379 | 8.155 | 9.334 | 9.729 |
| Local baseline, manual | 82 | 3.876 | 8.322 | 10.667 | 12.447 |
| Baseline automatic, preliminary | 300 | 3.213 | 6.774 | 7.960 | 8.688 |
| Candidate automatic, preliminary | 300 | 2.694 | 5.197 | 6.725 | 8.164 |
| Candidate automatic, settled 1 | 300 | 3.403 | 6.503 | 9.114 | 10.167 |
| Baseline automatic, settled | 300 | 3.061 | 6.269 | 7.160 | 7.850 |
| Candidate automatic, settled 2 | 300 | 2.999 | 5.620 | 8.171 | 13.653 |
| Final installed candidate, extra check | 300 | 3.039 | 5.905 | 7.470 | 11.684 |

The extra final-APK check matched all 300 edges without an observed overrun or
extra/missing edge. Its p50 interval was 2.692–3.039 ms. It ran at 32°C with internal
timing disabled, without another paired baseline or the explicit 150-press warmup;
it is supporting regression evidence, not a controlled speed comparison.

A separate internal sample of 600 dispatch-to-publication observations reported:

| Build | p50 (µs) | p95 (µs) | p99 (µs) |
| --- | ---: | ---: | ---: |
| Baseline | 112.6 | 274.2 | 442.2 |
| Candidate | 116.9 | 315.2 | 561.7 |

The 4.3 µs median difference is the difference between those runs, **not an
isolated measurement of router cost**. The tested typical native path remained
within baseline variation. Candidate tail values were higher in some runs and
were not isolated to a cause. No claim of a speedup, zero cost, or unchanged worst
case is supported by these samples.

### Cursor frame cadence

The initial stick-driven cursor updated at a median 33.333 ms interval, while
the lower trackpad updated at 16.667 ms. Publishing pending Compose snapshot
notifications before the frame's draw traversal changed the stick median to
16.667 ms. Of 119 intervals, 115 were 14–20 ms and none were 30–37 ms. The remaining
four included longer start/stop gaps, so these are not a continuous-load frame-time
distribution. The user confirmed the visible improvement. Speed, sensitivity and
the stick response curve were unchanged.

This is a rendering cadence check, separate from event propagation latency.

## Remaining validation

- Repeat the short Thor regression pass on the rebased final branch.
- Measure simultaneous BLE and touch input, contention and allocations on hardware.
- Compare Desktop key/pointer and native Deck touch latency quantitatively.
- Repeat under a loaded game/rendering workload and, if needed, measure display
  response with high-speed video.
- Exercise physical Bluetooth devices and other device-specific adapters.
- Let upstream CI build and test the complete release configuration, including
  store engines omitted from the local Steam/Desktop APK.

These limits should remain visible in the PR while maintainers review the
architecture. Host tests and a successful APK build do not replace those checks.
