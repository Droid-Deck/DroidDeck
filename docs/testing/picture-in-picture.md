# Picture in picture

PiP keeps the same Steam, game or desktop session visible over another Android
app. The session drawer offers Picture in picture and a global Automatically
enter PiP toggle, off by default. Background bypasses automatic PiP.

Android owns window placement, resizing, expansion and dismissal. PiP has no
custom actions. Entering and leaving PiP does not change audio state.

Guest input is released and disabled while floating. DroidDeck overlays and
Thor's second-screen tools are hidden; expansion restores normal controls and
the selected second-screen mode. Session resolution stays fixed and the image
is fitted without cropping.

Closing PiP follows the existing background/suspend preference and does not stop
the session. Screen-off and explicit Steam sleep retain their normal semantics.
Automatic entry requires a ready, unsuspended session with a presented frame;
manual entry can also show a suspended session. Internal pickers and share sheets
must not trigger automatic entry.

## Validation

Build the release APK with `tools/build_local.sh`, release-sign it and update the
existing app in place. Run `app:testReleaseUnitTest`; `PipPolicyTest` covers
entry eligibility and Android's aspect-ratio limits.

Device checks:

- Enter manually from Steam, a game and desktop; retain the guest PID, session
  and output resolution, with no drawer, controller overlay or HUD.
- Open Android's PiP controls; check expansion, closing and resizing, with no
  custom audio or suspend actions.
- Enable automatic entry and press Home. Explicit Background, settings, file
  pickers and share sheets must bypass it. Restore the automatic-entry setting.
- Select a Thor second-screen mode; check hiding on PiP entry and restoration
  on expansion. Restore the selected mode afterward.
- Check ordinary audio and existing suspend behavior through entry, expansion
  and closing; stopping a floating session must still finish normally.
- Check launcher/notification Resume returns to the same session.

Platform references:

- https://developer.android.com/develop/ui/views/picture-in-picture
- https://developer.android.com/reference/android/app/PictureInPictureParams.Builder

Validated on AYN Thor (Android 13), 2026-10-03:

- The signed release build and all 121 release unit tests passed.
- Steam and LXQt desktop entered PiP manually and expanded without replacing
  their guest processes. The floating image hid the normal session overlays.
- Android's menu showed only settings, expand and close; no custom actions.
- Automatic Home entry worked. Explicit Background bypassed it while enabled.
- The Thor keyboard/trackpad presentation disappeared in PiP and returned on
  expansion. Closing PiP retained Steam, and launcher Resume returned to the
  same guest processes. Both sessions stopped cleanly after testing.
- Automatic entry was restored to off and the second-screen mode to None.
- Audio components, the relay binary and their build configuration are identical
  to main. Running-game audio, pinch resizing and picker/share-sheet behavior
  were not exercised in this pass.
