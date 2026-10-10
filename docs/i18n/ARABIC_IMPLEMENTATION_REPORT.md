# DroidDeck Arabic Localization — implementation and verification report

**Working branch:** `feat/arabic-localization-complete-rtl`  
**Base:** `main` at `c875ceda4d100c96e0466e2ab0826856a9da2dd7`  
**Tracking:** Issue #1, draft PR #2  
**Status:** Implemented and undergoing CI/hardware acceptance; do **not** equate translation-key coverage with a fully device-verified release.

## Delivered
1. Registered Arabic (`ar`) in `core/AppLanguage.kt`, the app language picker, and `res/xml/locales_config.xml`; included Arabic as a system/regional locale fallback and enabled `android:supportsRtl="true"`.
2. Added `res/values-ar/strings.xml` with **1,880/1,880** English baseline `<string>` keys, **21/21** plural resources with the six Arabic CLDR categories (`zero`, `one`, `two`, `few`, `many`, `other`), and **2/2** string-array resources. No English resource keys were deleted, and brand, keyboard-button and protocol identifiers were retained where needed.
3. Updated `ui/SettingsWidgets.kt`: popup alignment, position clamping, animation origin, and heading tracking now respect RTL.
4. Updated `input/SteamTouchControls.kt`: the Canvas-rendered Trackpad label is fetched from the localized resource rather than a hardcoded English literal.
5. Mirrored the semantic **Back** navigation arrows in `ui/FrontEndWidgets.kt` and `files/FileManagerScreen.kt`. This does not remap or mirror gamepad D-pad inputs or physical game controls.
6. Added the Arabic `Noto Naskh Arabic` preference to Android-provided guest fonts in `tools/linuxfs/overlay/usr/local/bin/droiddeck-session`. The default Linux font aliases remain unchanged, and the actual installed font availability depends on the device.
7. Added locale, Steam-Arabic mapping, Arabic plural, and Linux font regression tests, plus high-confidence static checks for hardcoded visible English text.
8. Added `.github/workflows/i18n-ar.yml` to run resource/RTL and JVM tests in CI.

## Coverage criteria
- 100% presence and uniqueness of 1,880 default resource keys.
- 100% presence of 21 plural names and all Arabic grammatical forms.
- 100% presence of 2 string arrays and their ordered items.
- Positional format placeholders and escaped percent tokens must agree with the English baseline.
- All non-translatable identifiers must retain exact values.
- Resource XML must parse cleanly; strings must not be empty.
- No newly introduced direct hardcoded Latin UI strings in production Kotlin/Java (high-confidence scanner; dynamic expressions require review).
- `ar` / `ar-SA` / `ar-EG` must map to Steam's `arabic`.

## Automated verification
- Python: `python3 -m unittest tools/tests/test_arabic_localization.py tools/tests/test_no_hardcoded_ui.py tools/tests/test_droiddeck_fonts.py -v`
- Android JVM: `./gradlew -PskipRust=true :app:testDebugUnitTest --stacktrace`
- Native packaging only with verified bundled runtime, per `README.md` and `tools/build_local.sh`. The JVM command does not prove the APK can be installed or Steam launched.

Latest per-commit CI outcomes are authoritative and can be inspected at:
`https://github.com/Alaa91H/DroidDeck/actions/workflows/i18n-ar.yml`

## Manual / device acceptance matrix (NOT YET CLAIMED PASSED)
- [ ] Install a complete APK on supported Adreno handheld and run the onboarding.
- [ ] In **Setup → Language**, select العربية and verify persistence, system default, Android per-app language, English switching.
- [ ] Inspect every page, dialog, notification, file-manager list, stores flow, dynamic error message, and screen-reader announcement for untranslated labels, truncation and Bidi ordering.
- [ ] Repeat with 16:9, 4:3 and square landscapes, text/font scales and app zoom 75%, 100%, 150%.
- [ ] Verify navigation rail, popup anchoring, Back arrows, keyboard/gamepad focus order, and D-pad behavior.
- [ ] Launch Steam through the Linux guest; confirm Arabic selection, glyph joining and font fallback. Steam itself, games, embedded store web pages, and third-party desktop apps have their **own** localization coverage.
- [ ] Verify Surface/game frame coordinates, touch/grip overlays, controllers, session suspend/resume, live downloads, notifications and persistent sign-in remain unchanged.
- [ ] Build and install a complete test APK and attach user-device logs/screenshots to PR #2.

## Safety constraints
Do not translate or mutate game filenames, executable paths, environment variable names, Steam/Proton identifiers, protocol values, internal log records, or game-controller physical directions. All tests or screenshots must be reported truthfully: code coverage is not a substitute for an actual handheld test.
