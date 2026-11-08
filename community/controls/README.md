# Community controls

Controller settings → Community controls browses this reviewed catalog and imports or exports `.ddcontrols.zip` packs. A starter gamepad is bundled for offline use. **Check for community packs** fetches the current catalog; downloads are checked against the catalog SHA-256 before import. The remote catalog and submission form become available when this change lands on main.

To share, export the profile and screen you edited, then use **Submit a community pack** and attach the ZIP plus a screenshot. A GitHub account is required. Sharing a ZIP directly also works. Submissions are reviewed before publication. Include the author, intended game (if any), orientation, license, and artwork credits. Do not submit assets you lack permission to redistribute.

Maintainers add approved packs to `packs/` and entries to `catalog.json`, with name, author, optional game, license, relative file path, SHA-256, optional `preview` path under `previews/` (PNG, at most 1024×1024 and 512 KiB), and screen (`MAIN`, `SECONDARY`, or `BOTH`). Keep bundled copies under `app/src/main/assets/community-controls` in sync when changing the starter. Imported controls are independent copies selected only for the chosen profile and screen; existing profiles and physical controller bindings are preserved.

For game-specific controls, create a named input profile and apply the pack to that profile. Select it in the game’s settings under **On-screen controls profile**. Automatic selection follows the focused Steam game, or the launched game for direct launches. Returning to Steam restores the general controls. Explicit quick-menu profile or screen choices take priority until Auto is restored. Physical controller bindings always follow the general selection. A missing/deleted profile falls back to general controls.

## Pack format

A ZIP contains `controls.json` and the exact `images/<filename>` PNGs referenced by the layout. The JSON has `format: "droiddeck-controls"`, `version: 1`, and `layout` / `bindings` objects using the input library serialization. All supported actions, stick clicks, geometry, style, and button artwork are retained. Local IDs are regenerated on import; images receive unique local names. Import validates the input library, image dimensions, file count, byte limits, and archive entry names before offering Apply. Applying saves through the input library recovery path. ARMSX2 / iOS skins and legacy `.icp` files are distinct formats; `.icp` import/export remains in Saved layouts.

## Upstream inspiration

The manifest-backed browse → download → existing installer flow and the separation of import, browse, and selection were researched directly from [ARMSX2](https://github.com/ARMSX2/ARMSX2) commit `9144e4ff1fed43756809d8e68b3efc2505a51078`, specifically `platforms/android/app/src/main/java/com/armsx2/SkinRepo.kt`, `ControllerSkinStore.kt`, and `ui/settings/SkinsTab.kt`. ARMSX2's `COPYING.GPLv3` and `pcsx2/Docs/License.txt` license the program under GPLv3 or later, compatible with DroidDeck's GPLv3. This implementation uses DroidDeck's existing input model and renderer. No upstream skin artwork or source code is copied. The starter pack is original DroidDeck data under GPL-3.0.
