# Mandatory Play distribution gate

Applies to the `playstore` branch and every change intended for Google Play, including incoming main commits, dependencies, guest integrations, build inputs, workflows, and release artifacts. Last researched: 2026-11-03. Refresh the relevant official requirements before changing affected behavior.

## Decisions

Refuse known violations. Hold uncertain changes until official documentation or a relevant written Google decision establishes the allowed implementation. Record the behavior, source, reasoning, and remaining verification. Public download availability, redistribution rights, open-source status, user acceptance, or another app's behavior does not establish Google Play compliance. Do not describe an unreviewed baseline as compliant.

Review every incoming commit for external executable downloads, storefront payments and links, permissions, updates, privacy, SDK behavior, native-library compatibility, and delivery limits. Preserve contributor credit. Keep unrelated main development and its latest release untouched. Do not bypass this gate to make a build pass.

## Delivery and executable code

- App self-updates must use Google Play. Do not add APK installation or `REQUEST_INSTALL_PACKAGES` to this distribution.
- Native components must remain Play-delivered, with verified inventory and no external native-code fallback. Use only DroidDeck Proton in the bundled Proton catalog. Do not add extra desktop emulators.
- The virtual-machine/interpreter exception requires a specific analysis of actual Android API access and execution. Steam or another guest storefront is not an automatic exemption. Guest package managers, downloaded scripts, plugins, Windows prerequisites, and purchases need their own review.
- Asset packs contain data, not executable code. Never disguise executable files in archives or chunks to place them in asset packs. Classify extracted payload contents before separating data from runtime code.
- Feature modules can deliver code on demand but do not increase the cumulative code-delivery budget. Current limits: base 500 MB, each feature 500 MB, each asset pack 1.5 GB, modules plus install-time asset packs 4 GB, fast-follow/on-demand asset packs 30 GB. Measure compressed per-device delivery with bundletool and confirm in Play Console.
- On-demand delivery requires explicit missing-module handling, progress, retry, cancellation, version consistency, verified extraction, offline behavior, and recovery after interrupted downloads. Never launch a partial runtime or block the UI while downloading.

## Other mandatory reviews

Purchases and purchase links require the applicable Google payments rules and regional/program eligibility. PC-game branding alone does not exempt a purchase. Privacy disclosures and Data safety must reflect actual app, SDK, guest, and third-party behavior. Review sensitive permissions, foreground-service declarations, accessibility consent, account flows, content ratings, security protections, target SDK, and 16 KB compatibility where relevant.

Signing must use the protected workflow environment and the established certificate. Never track keys, passwords, or aliases, print them, pass them as command arguments, or include them in artifacts. Preserve repository-controlled version codes; never substitute timestamps or workflow counters. Play releases must not replace main's latest release.

Build, test, policy review, redistribution rights, and physical-device verification are separate gates. Report unmet gates explicitly. Obtain actual Play review where documentation does not resolve a distribution mechanism. Existing legacy driver alignment and guest-store policy questions remain unresolved until supported by evidence.

## Reviewed main changes (0.6.9.2, code 20)

- Game Jolt store (main #1986): a guest storefront that installs free Windows builds into the guest, the same mechanism as the existing itch.io and GOG stores. Shipped. Its storefront review stays open with the others.
- Desktop language packs (main #2096): `.mo`/`.qm` catalogs, locale `.desktop` metadata and KDE KI18n translation scripts, checked against the release index by SHA-256 and extracted into the guest. The scripts are interpreted by KI18n inside the guest and cannot reach Android APIs. Shipped.
- Not reachable on Play: the PowerVR add-on pack, the non-Adreno driver pair source and the Windows redistributable downloads. They go through the existing `AddonPack` and `ComponentDownloads` gates. PRoot stays bundled in the APK.
- The accessibility service now also keeps Alt+Tab, Win and Alt+Esc from a hardware keyboard inside a focused session. Its description, which is also the consent text, was rewritten in every locale to say so.

## Official sources

- [Size limits](https://support.google.com/googleplay/android-developer/answer/9859372?hl=en)
- [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery)
- [Play Feature Delivery](https://developer.android.com/guide/playcore/feature-delivery)
- [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en)
- [Payments](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en)
- [User Data](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
- [Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/9888170?hl=en)
- [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
