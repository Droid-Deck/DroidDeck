# Direct-launch exit investigation

Investigation recorded on 2026-11-16 for the OnePlus Pad 2 report in
[Discord thread 1570658271612764210](https://discord.com/channels/1552707793247539220/1570658271612764210).
The original direct launch ended with program status 53. Later launches through
Steam rendered frames until the user explicitly stopped them. These are not
interchangeable reproductions of one crash.

## Status propagation

`tools/linuxfs/overlay/usr/local/bin/droiddeck-session:634-650` waits for the
launched subprocess, writes its shell exit status to `run-status`, and exits
with the same status. `app/src/main/java/com/droiddeck/launcher/session/SessionService.kt:823-830`
reads that value separately from the session-wrapper result, and
`app/src/main/java/com/droiddeck/launcher/session/SessionService.kt:1833-1834`
parses it from the status file.

The reported 53 is therefore the launched subprocess's shell status, not a
DroidDeck error enum or a signal number. A shell retains only the low eight
bits of a native status, so `0xc0000135` would also appear as 53. The supplied
logs do not establish that full status or identify a missing library. Status 53
alone is not evidence for a runtime-dependency fix.

`tools/linuxfs/overlay/usr/local/bin/droiddeck-proton-run:529-534` replaces itself
with the selected compatibility tool. That launcher does not define a special
exit status 53.

## Direct-launch evidence

In the original report, `app.log:160` records wrapper status 0 and program
status 53. The corresponding `steam-1343240.log` shows:

- Line 4668: the Steam connection fails.
- Line 4721: the shipping executable loads, so the launch gets farther than an
  early executable-not-found failure.
- Lines 4928-4942: forwarding `steam://run/1343240` to native Steam ends in a
  native null access violation, `0xc0000005`.

The failed native URL hand-off is proven. Its faulting instruction and its
relationship to the child's eventual status 53 are not. The hand-off error must
not be presented as the complete root cause without that connection.

## Later launches through Steam

The later logs use Proton 11-12.8 and a newer graphics driver. Both runs show
sustained rendering before an explicit notification Stop:

- In the app 1343240 capture, process `0098` is `winedevice.exe` at
  `steam-1343240.log:129`, while the shipping executable is process `0168` at
  line 536. The final nested exception at line 10336 belongs to `0098`, not the
  game process. `app.log:443` records 278 game frames in the last ten seconds,
  line 453 records the Stop request, and line 558 records session status 0.
- In the app 219150 capture, `steam-219150.log:60` identifies process `003c` as
  `winedevice.exe`; the nested exceptions at lines 15322-15323 belong to that
  service process. `app.log:327` records 594 game frames in ten seconds, line
  370 records the Stop request, and line 460 records session status 0.

These runs contradict the earlier interpretation that the final nested
exceptions were automatic game-process launch crashes. Rendering alone does not
prove correct gameplay or visible content. Because the compatibility tool and
driver also changed, these captures cannot isolate Steam's presence as the
reason for the difference.

## Research boundary

Searches covered current and historical DroidDeck pull requests and issues,
runtime launch helpers, public forks, upstream Proton, FEX, Box64, Winlator,
DXVK, vkd3d-proton and web results for the status, forwarding and nested
exception signatures.

The following superficially similar reports do not establish an applicable fix:

- [FEX-Emu/FEX#6078](https://github.com/FEX-Emu/FEX/issues/6078) and
  [Droid-Deck/DroidDeck#1946](https://github.com/Droid-Deck/DroidDeck/issues/1946)
  share an address suffix but identify neither this reporter's game process nor
  the native URL-forwarding fault.
- [Droid-Deck/DroidDeck#1206](https://github.com/Droid-Deck/DroidDeck/issues/1206)
  concerns a different 32-bit OpenGL null fault.
- [Droid-Deck/DroidDeck-Proton#12](https://github.com/Droid-Deck/DroidDeck-Proton/pull/12)
  and [#16](https://github.com/Droid-Deck/DroidDeck-Proton/pull/16) address
  directory access and process breakpoints in 11-12.4; neither matches the
  supplied forwarding failure closely enough to claim a fix.

No verified upstream patch was found to port. A common exception suffix or the
shared use of a signal stack is insufficient grounds for a runtime patch.

## Verification and next step

Five session-status unit tests passed with
`python3 -m unittest tools.tests.test_session_run_status`. They verify existing
status persistence and signal forwarding, not a game fix.

The connected Retroid Pocket Duo ran DroidDeck 0.7.1 at
`77aebcf900bb718dd9b056ae4ca54039100af4e5` with runtime `ubuntu-r1-rc5`. Neither
affected title was installed, so before/after reproduction was unavailable. The
application was closed after inspection. No APK was built or installed for this
documentation-only change.

The next useful capture needs a current direct launch and an in-Steam launch
using the same Proton, graphics driver, prefix and settings. Process
creation/exit tracing should retain the full child status, and the native fault
needs module mappings and a build offset. The newer runs also need a specific
remaining symptom because they already render until Stop.

Until that evidence exists, do not add a status-53 fallback, a per-title rule or
a speculative signal-stack change. The native forwarding fault remains the
strongest direct-launch lead; the complete origin of status 53 remains unknown.
