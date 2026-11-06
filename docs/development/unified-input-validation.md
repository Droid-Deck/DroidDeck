# Unified input validation

Recorded on 2026-10-24. These results distinguish the device-tested candidate from
the later upstream rebase and host regression fixes. They do not establish zero
latency or complete coverage of every controller and session mode.

## UI publication candidate, 2026-11-06

The implementation was updated through upstream `a1bc8467f` (merge `aa3757eff`).
The generic action selector now says **Scroll**; binding summaries still state
the configured direction. Closing the docked session drawer now invokes its
ordinary close action instead of disabling the second-screen presentation.
The global-reset regression compares against a pristine compiled library, which
includes the editable gamepad's keyboard element.

- All **944 input/UI tests in 125 suites passed**. The final full JVM run completed
  **3,598 tests in 493 suites: 3,573 passed, 25 failed, no errors or skips**.
  The failures are the same unchanged upstream log-export tests (24) and
  case-sensitive WoW add-on import test (1) described below. Input/UI and global
  settings reset passed in this full run.
- Initial full runs exhausted this Mac's free disk and produced secondary native
  runtime/idle failures. Obsolete local test APKs were removed. The completed run
  used a local Gradle init script with one worker and a fresh test JVM every 25
  classes to bound temporary-file use. No repository test configuration was
  weakened and no tests were excluded from the completed run.
- The APK passed runtime-input, session-asset, native dependency, alignment and
  v1/v2/v3 signature checks. SHA-256:
  `611814f11cbac4bab7f97dac5c0b0657d85d8dfbd6b40b741bda1fc13efa6f83`.
  It was installed over the separate Input Test app with its environment intact;
  as with earlier local candidates, Rust store engines were omitted.
- On AYN Thor, closing the lower-screen menu with its X restored the existing
  controls instead of revealing Android. The live Steam editor, separate lower
  canvas and upper tools/properties, profile previews, saved layouts, and action
  pages were exercised. A single Trigger selection added a centered, selected
  element; the reported occasional extra tap was not reproduced. The temporary
  editor change was discarded.
- The user also verified native controls, profile switching, trackpad/pointer
  and scrolling behavior on the immediately preceding UI candidate. This is
  distinct from automated UI navigation and the historical latency measurements.

Actual display captures and test reports are retained locally under
`app/build/reports/input-publish/`. Screenshot publication is subject to the
user's review. No new latency measurement was made for this UI revision.

The follow-up router-ownership fix passed **47 tests** in `InputSourceOwnershipTest`,
`InputSurfaceSourceTest`, and `InputRouterTest`, including two new regressions.
An obsolete activity (or one without a router) cannot detach the replacement
activity's Deck routes; the current owner still releases its held keys on cleanup.
GitHub's complete Build APK workflow passed for `7b7722bd2`.

### Review follow-up

- Upgrade binding IDs are deterministic across unsuccessful reads. Editor lookup
  from an earlier read remains valid when legacy screen-key import fails.
- An edit of malformed supported-version data commits both the restored library
  and a recovery copy of the original text. Reads alone preserve the original;
  newer unsupported versions remain protected against overwrite. Settings show
  that recovery data was retained.
- Focus/capture loss releases hardware, Android keyboard and PC-keyboard owners
  without releasing another source's hold. Secondary settings handle an invalid
  presentation display at initial show and resume.
- Physical-button capture waits for release before confirmation/navigation.
  Back and B remain assignable; another Back/B press cancels, A confirms, and
  Retry starts a new capture. The existing dialog-window key hook is reused.
- Steam Controller BLE teardown synchronously closes its routed source and
  gates state/feature publication. Queued callbacks and starts cannot reactivate
  it after stop returns; disconnect work remains on the existing BLE thread.

The input/UI/global-reset run passed **959 tests in 127 suites**. After the
additional BLE finding, **5 BLE/routing tests passed**, including two regressions
for queued callbacks, synchronous release, source detachment and shared holds.
The final APK passed packaging, runtime/native dependency and signature checks:
`a5ec9120be120b829748f8489782a0c7158360d7f8deb61777fe75d601708d20`.
It was installed over Input Test; saved profile preferences remained byte-for-byte
identical and the existing environment was retained.
The button-capture dialog opened and cancelled on Thor without a crash. No
physical BLE-controller check or new latency measurement was made for these
follow-ups.

### Replacement-controller and mouse-backend follow-up

The Triton slot now has a single owner across activity instances. Late reports,
subscription callbacks, feature replies and teardown from a replaced controller
cannot overwrite the current controller's report or connection flag. Every
instance still closes its own routed source. Claiming the slot happens once at
start; a queued or repeated old start cannot take it back.

Legacy Select-to-Steam also migrates to the Desktop physical profile when there
is no saved Desktop profile. An existing Desktop assignment is preserved.
Mouse backend selection and publication share the button-owner lock with raw
channel open, soft release and destruction. Horizontal and vertical scrolling
use the same path and fall back to Wayland after leaving raw mode.

The complete input/UI/global-reset run passed **967 tests in 128 suites**.
The focused run passed **28 tests**, including replacement subscription before
old teardown, unstarted-instance teardown, saved/default Desktop migration, and
concurrent scroll versus raw-channel release/destruction. These changes add no
input queue or timer. Physical BLE hardware and latency remain unmeasured for
this follow-up.

### Upstream integration, 2026-11-07

Merged upstream through `542bbf89c`. The shared stick renderer retains the new
fast-double-tap rule from #2173, including cancelled, dragged, long-held and slow
second taps; the six upstream regressions were adapted to the profile-backed
renderer. WoW chat uses the existing custom lower-screen presentation and shared
key ownership, preserving Ctrl held by another input source. The new platform
pointer, PiP, component-update and store-launch changes are retained.

The merged candidate passed **1017 tests in 133 suites**, with no failures,
errors or skips: all input/UI tests, global reset, component updates, PiP policy,
runtime auto-update and Proton menu auto-selection. The gamescope p22 archive
was downloaded from the newly pinned component repository and verified against
upstream's SHA-256 before refreshing the local runtime inputs.

## Input settings polish, 2026-11-06

The final input/UI host run passed **941 tests in 124 suites, zero failures**.
New coverage includes retained route/action drafts, centered element creation
without a placement tap, collapsed quick-profile settings, schema-4 migration
with a layout shared across both screens, target/scroll serialization, and
horizontal scrolling through both guest mouse backends. Existing physical native
fast-path and ownership tests remain green.

The settings use stable DroidDeck Input labels, contextual axis/contact hints,
color swatches, opacity sliders and rendered layout thumbnails. Diagnostics now
open a result dialog and explain how to record a sample and what it measures.
Shortcut rows show the actual assignment and offer Restore to default.

No new latency comparison was taken for this UI revision. The normal native
controller path is unchanged. New horizontal wheel work happens only for a local
scroll action. The user's environment installation is retained. Device verification is recorded below.

### Screen-key removal and Thor verification

The old screen-key overlay, standalone editor, layout panel and separate runtime
key sender are removed. Schema 6 imports the old active set and every named set
as editable profiles. Current defaults and selections are kept. Tests cover
idempotent import, chords, normalized geometry, per-element opacity, shapes,
image references, symbols, custom diagonals, and retaining the original data
when migration cannot complete. A backup and the converted library are committed
together before the old preferences are removed.

New shared element tests exercise latched keyboard and native controller buttons,
shared ownership, cancellation, menu suspension and profile replacement. Separate
diagonal bindings release previous keys and preserve neighboring-direction
fallbacks. No additional input queue or thread is used.

Final APK installed over `com.droiddeck.launcher.inputtest` on AYN Thor:
`6618be55c28a52746b6504656a480845d25d8a34114daa1829aad5dbb5b5e729`.
Packaging checked runtime inputs, session assets, native library dependencies and
v1/v2/v3 signatures. As with the earlier test packages, Rust store engines are
omitted; the Steam/Desktop environment is retained.

On-device checks:

- The expanded Profile has readable text and one visibly grouped block.
- Profile source rows and saved-layout rows show bounded thumbnails. The previous
  black rectangle overflowing the list no longer appears.
- The original anchored color menu shows its color swatches.
- Steam → Controller → Edit opens settings on the spare display.
- Main-screen editing opens on the activity display over live Steam. A second
  on-device crash was found and fixed: returning from lower-display settings must
  use the existing activity dialog, not a Presentation on the default display.
- Adding a button immediately centers and selects it. Its properties and symbol
  picker open on the main display.
- Secondary-screen editing leaves only the canvas below and tools above. Adding
  a trackpad centers and selects it without requiring another placement touch.
- Temporary edits were discarded. Comparing the before/after saved libraries,
  excluding new default schema fields, found no changes to the user's profiles,
  geometry, bindings or selections. Schema changed from 5 to 6.
- Steam remained READY after closing the editors; the final device log contained
  no new fatal exception.

The test device had no old screen-key sets to import. Legacy content migration
and toggle ownership were verified by host regression tests, not by a new
physical-input latency or end-to-end game test. Evidence is under
`app/build/reports/input-polish/`; the final installable copy is
`app/build/input-ui/DroidDeck-Input-Test-unified-screenkeys-2026-11-06.apk`.

## Unified editor and session profile composition, 2026-11-06

The fixed gamepad and its separate editor are replaced with a saved editable
layout rendered by `InputSurfaceView` on either display. The old fixed lower
panel, tab switches and separate mouse trackpad view are removed. Deck pads,
grips, single pads, the mouse trackpad and a compact gamepad are editable presets;
their menu, keyboard and click buttons are normal elements. The compact preset
reuses the previous panel geometry calculation.

Schema 4 migrates version-2 overlay references, mappings, saved positions,
appearance and stick-click preferences, and version-3 fixed lower layouts.
Other resolution-specific positions remain saved layouts. Future or invalid
libraries are not overwritten. Physical mappings retain their identities and
assignments. Existing custom layouts remain intact.

On two displays, the activity hosts Add, Undo, Snap, Done and full-size properties;
the other display contains only the canvas, without a reserved toolbar strip.
On the main display a draggable Tools bubble expands into the same tools,
including Properties. The editor snaps centers and edges while moving and
resizing. Both source pages list their actual elements. Device settings are
inline below the profiles.

The quick menu selects a whole profile or overrides Physical controller, Main
screen and Second screen independently. Game and Desktop selections are separate.
Changes do not mutate the source profiles; saving a combination creates independent
copies. Returning to Auto clears the overrides. Tests cover serialization,
reference validation, deletion protection and preservation of source profiles.

The complete input/UI host run passed **931 tests in 124 suites, zero failures**.
Coverage includes full-size shared editing, floating tools, display removal,
geometry migration, source ownership during detach/layout replacement, quick-menu
composition and saving, native Deck clicks and mouse gestures. Tap and hold use
the configured element click; ordinary taps do not reposition the cursor. Profile
changes and menu suspension suppress delayed gesture presses until neutral.
The neutral-stick/physical-input regression remains covered.

No new end-to-end latency measurement was performed for this revision. The
physical controller path remains unchanged. Touch output uses the existing
owned router and guest transports without an additional event queue. Mouse
movement is delivered synchronously; tap/hold recognition reuses the existing
`TouchpadGestures` timings. The local APK omits the Rust store engines and uses
the separate Input Test application ID.

Device verification on AYN Thor confirmed the four quick selectors and saved
second-screen presets, draggable/expandable tools, full-screen properties and a
canvas-only secondary display. WindowManager reports the main editor at
`[0,0][1920,1080]`; the secondary canvas uses the full `1240×1080` display.
The installed library migrated to schema 4 with existing profile references,
bindings and custom layouts preserved. A previous test migration's unchanged
main overlay geometry was corrected from its window-size copy to its saved
1920×1080 version. No user-edited geometry was replaced.

Final APK SHA-256: `c13326d11f5e8de3b878d83b296ba2f77495baf5c35d1d00dbb0327333c0277d`.
Runtime assets, native library dependencies and v1/v2/v3 APK signatures passed
verification. Focused editor/input checks passed again after fixing Compose 1.6
fullscreen dialog sizing. Live game/desktop input and perceived latency with this
final build still need user testing; the device checks above cover UI and migration.

## Controller integration and upstream update, 2026-11-06

Merged upstream `bf693d7f7` into the working branch as `ba5876bf6` before the UI
integration. This includes 90 upstream commits, including radial stick dead zones
and mouse-button handling. The merge keeps routed source ownership and the new
upstream raw-stick behavior; the targeted input merge checks passed.

Controller now contains two default-profile selectors, one profile list and a
Device settings entry. Source-specific action editing and saved layouts are
inside each profile. New profiles are independent by default, with an explicit
option to share physical controls. Direct editing and Hide tools / Show tools
are retained. No input-event queue or preference lookup was added by this UI
reorganization.

The first full host run executed 3,580 tests in 490 suites: 3,555 passed and 25
failed, with no skips. All 926 input and UI tests in that run passed. The failures
are in unchanged upstream log-export tests (24) and WoW add-on import tests (1).
The log fixtures require `SecureDirectoryStream`, unavailable in this macOS JDK;
the WoW test assumes `Foo` and `foo` refer to different paths, while this host
filesystem is case insensitive. A direct Java filesystem probe confirmed both
conditions. The log-export failures also reproduce when run separately. The
affected production and test sources match `origin/main`. These host limitations
have not been hidden by disabling tests or changing production code.

The final input/UI-only run passed all 927 tests in 122 suites.

Additional final checks cover integration in the existing Setup tabs, the lack
of duplicate Layout/Configure entries, back navigation, independent creation,
explicit physical sharing, and preserving elements while hiding editor tools.
Rendered UI checks cover 360×800, 640×360 and 960×600 dp. Earlier latency results
below are not a fresh measurement of this revision.

The final 26 profile/library/editor checks passed again after the source-specific
control lists and sharing labels were finalized. The APK passed session-asset
matching, runtime-input attestation, native dependency/export checks, alignment
and signing validation. It uses the same local HTTPS/direct-release download
adaptations as the previous build and still omits the store Rust engines.

Installed the update over `com.droiddeck.launcher.inputtest` on AYN Thor without
clearing data. Verified Setup → Controller → Desktop → Second screen → Edit on
screen; the canvas opened on the lower display, Hide tools / Show tools worked,
and selecting the right trackpad opened large properties on the main display.
Returned without saving; the stored input-profile preferences matched the backup
exactly. Device settings had no persisted overrides before or after this check.
No fresh gameplay or latency measurement was performed in this UI pass.

Final APK SHA-256:
`f1d16a503661055a0abe8cc954d3fc94f6b9a1c1c626ac38b6c88dba478810d1`.

## Local UI revision, 2026-11-05

This revision adds the Profiles / Layouts / Bindings libraries, migration of the
previous settings, independently identified touch elements, and a direct editor
on either display with properties on the activity display.

- The complete JVM/Compose run passed 3,306 tests across 467 suites, with no
  failures, errors or skipped tests. The native identity fast-path checks remain
  in that suite. After the final editor-window adjustment, all nine editor and
  adaptive-layout UI tests passed again.
- New coverage includes legacy mapping/geometry/selection migration, shared
  physical presets with separate touch actions, explicit private copies, safe
  deletion/reset, unsupported schema preservation, independent element actions,
  overlapping held outputs, suppression across profile/menu changes, and inert
  editor previews, independent stick/trackpad click bindings, same-view
  detach/reattach, and saving a draft after the auxiliary display disappears.
- Both Steam and Desktop quick settings expose the same input library entry.
- Compose navigation was exercised at 360×800, 640×360 and 960×600 dp. Rendered
  images are generated under `app/build/reports/input-ui/`. The properties Save
  control stays outside the scrollable content on the short landscape screen.
- Updated the existing `com.droiddeck.launcher.inputtest` installation on AYN
  Thor from 0.6.0 to the local 0.6.9 build without clearing its data. The official
  package was not replaced. A local backup of the two input preference files was
  taken before migration; all 61 saved Desktop bindings matched after migration.
- On Thor, exercised the quick-settings entry, library navigation, full-size
  editing on both displays, selection on the lower display with properties on
  the main display, moving an element, Undo and Done. After undoing the move and
  saving, the complete stored library matched the pre-edit data.
- The earlier Thor latency measurements below apply to the earlier candidate,
  not to this library/editor revision. No new end-to-end or dispatch latency
  claim is made.

The complete local runtime build passed session-asset matching, runtime-input
attestation, native export/dependency validation, alignment and signature checks.
The local build-script copy used HTTPS for the Debian archive and direct GitHub
release downloads when the unauthenticated API was rate limited; checksum and
revocation checks remained enabled. No build-script changes are included in the
source diff. As before, the test APK excludes the Epic/GOG/Amazon Rust engines.
The installed APK SHA-256 is
`3e3f41589d45b70294f2d59404f591ae76f2ceba8488657e8821aab1bca224b3`.

Before publishing, also test on Thor: create two Desktop profiles sharing physical
bindings; give them different touch actions; edit and resize on the actual lower
display; open properties on the top display; save/cancel; swap screens; return
from Android Home; disconnect/reconnect the auxiliary display; and repeat the
paired physical-button latency measurement. Verify primary custom overlays with
on-screen controls enabled and secondary layouts in Custom controls mode.

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

### Upstream Home/player-slot integration (2026-10-28)

Merged upstream `213c2da5` without rewriting the published branch. Home/Guide now
follows upstream's primary-player slot selection when the built-in pad is off,
while its report retains routed buttons and axes. The input-settings overlay
continues to gate Home shortcuts and now also gates upstream's Steam volume-key
handling.

A new regression combines Home on player slot 1 with button/stick remapping and
disabled routing. The complete `:app:testDebugUnitTest` run passes **2,707 tests
across 389 suites, zero failures/errors/skips**, using the same Java/SDK/NDK
settings and `-PskipRust=true`. A new APK build, Thor validation and latency
measurement were not performed for this merge.

### Upstream shortcuts and dual-screen integration (2026-10-30)

Merged upstream `6ffe675a` without rewriting the published branch. The merge
preserves upstream's docked second-screen menu, keyboard routing, remembered
panel selection, touch routing and physical shortcuts from #1202, together with
the active input profiles and custom lower-screen layouts.

Captured profile keys take precedence over global physical shortcuts, which in
turn precede default Back/QAM handling. Both use the original device/key identity
before Joy-Con normalization. Input-profile editing blocks shortcut execution;
consumed releases remain consumed when a shortcut opens or closes the menu.
The upstream shortcut configuration remains accessible from the input settings.

Mapped mouse output uses upstream's existing Wayland/raw-mouse helpers, retaining
shared button ownership. Changing the raw-mouse backend releases held buttons on
the outgoing backend. Touchpad zoom has an independent Ctrl owner, so it cannot
release Ctrl held by the profile or screen keys. Mapped pointer movement retains
the frame-callback rendering update and cursor visibility behavior.

Eight new regressions cover these interactions and reopening a custom panel with
either controller type. The upstream shortcut-page UI test now exercises the
replacement Input profiles page. The complete `:app:testDebugUnitTest` run passes
**2,918 tests across 413 suites, zero failures/errors/skips**, using Java 17,
NDK `28.2.13676358` and `-PskipRust=true`. The PR diff against upstream also passes
`git diff --check origin/main`.

This run compiles Android Java/Kotlin and resources. A new APK build, Thor test
and latency measurement were not performed for this merge; the full release
build remains an upstream CI check.

### Upstream PC input and bottom-screen integration (2026-11-04 UTC)

Merged upstream `f0052415` without rewriting the published branch. The merge
retains the common bottom-screen bar, dashboard, KDE desktop panel, game-screen
swap, hardware mouse/keyboard fixes and on-screen stick redraw optimization.
Custom controls use the same bar and preserve their secondary-source bindings
when the game and panel swap displays. Pointer handoff now lives in upstream's
shared `SessionTrackpadView`, used in either display arrangement.

Physical/IME key translation reuses `GuestKeys`, including keyboard Escape scan
codes and the Desktop right-Ctrl alias, while preserving shared key ownership.
The input-profile editor releases upstream's hardware mouse capture. Routed
on-screen stick movement uses the same redraw throttling as the native overlay;
pad publication remains synchronous, and mapped cursor frame updates keep their
existing cadence mechanism.

The pointer-handoff regression now exercises the extracted trackpad view. Menu
tests cover the custom panel alongside upstream's panel choices. A new ownership
regression checks that releasing a hardware right-Ctrl alias cannot release Ctrl
still held by the active profile. Panel teardown tests now use `ComponentActivity`
to supply the lifecycle required by upstream's Compose bar.

The complete `:app:testDebugUnitTest` run passes **3,281 tests across 464 suites,
zero failures/errors/skips**, using Java 17, NDK `28.2.13676358` and
`-PskipRust=true`. Upstream's pinned MaliBridge submodule is initialized and its
required Android server build also succeeds. The PR diff passes
`git diff --check origin/main`.

No new APK installation, Thor regression pass or latency measurement was performed
for this merge. The earlier device measurements do not validate this exact tree.

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
