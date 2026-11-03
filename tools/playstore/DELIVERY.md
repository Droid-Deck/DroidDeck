# Staged Play delivery review

Research date: 2026-11-03. Enforce [the distribution gate](POLICY.md) before implementation.

## Supported approach

A small initial app followed by a Play-hosted download is supported. Use Play Feature Delivery for executable runtime components and Play Asset Delivery for separately classified non-executable data. Both are published in one signed app bundle; Google serves the required pieces. Two user-visible stages need not mean two physical modules.

| Delivery | Appropriate contents | Current compressed limits |
| --- | --- | --- |
| Base and feature modules | App and runtime code with associated resources | 500 MB each; all modules plus install-time asset packs share 4 GB |
| Fast-follow or on-demand asset packs | Non-executable assets | 1.5 GB each; combined 30 GB |

Moving code modules to on-demand delivery reduces the initial install but does not increase their 4 GB allowance. The 34 GB overall allowance is not a native-code allowance. ZIP splitting, nested archives, and external component servers do not change these restrictions. Asset packs explicitly exclude executable code. Do not treat scripts, DLLs, ELF binaries, or a whole mixed runtime archive as asset-only data.

The current stager already divides verified archives into chunks across feature modules. Chunks can cross component boundaries. Merely changing their delivery declaration would leave runtime extraction without required inputs. Version 0.6.9.2 adds on-demand module and asset-pack request/recovery integration using the existing dialog controls.

## Measured candidates

A read-only scan of the pinned Ubuntu desktop, legacy KDE desktop, SteamVR, DroidDeck Proton, and Ubuntu runtime archives identified 764,233,949 uncompressed bytes with selected font, image, audio, and localization extensions. Executable/archive signatures were excluded. This is a preliminary classification, not proof of asset eligibility or compressed space savings. Nested payloads, content, licenses, paths, and extraction dependencies still require review. The FEX SquashFS image was not included in this scan.

| Existing archive | Candidate data, uncompressed bytes |
| --- | ---: |
| Ubuntu desktop | 195,752,173 |
| Legacy KDE desktop | 219,560,513 |
| SteamVR | 223,740,281 |
| DroidDeck Proton | 66,142,728 |
| Ubuntu runtime | 59,038,254 |

The existing bundletool reference-device estimate is approximately 3.939 GB. Further compression has already been applied to legacy KDE and Proton without changing their tar contents. Repartitioning only helps capacity if separately classified data leaves the code modules and the recompressed remainder actually fits. Do not promise the complete historical catalog will fit before measuring the resulting bundle.

## Implementation requirements

1. Inventory every payload by file identity, execution type, license, target path, and dependency. Preserve the complete required file set.
2. Separate verified data assets from code, retaining deterministic per-file hashes and metadata. Rebuild the affected frozen catalogs and extraction recipes rather than modifying pinned upstream files in place.
3. Keep code Play-delivered. Request required feature modules through Play Feature Delivery before extraction. Resolve asset locations through Play Asset Delivery; locations and availability can change after updates.
4. Reuse the existing setup/progress flow. Support cancellation, retries, process death, storage exhaustion, interrupted updates, version consistency, and installed/offline sessions. Never launch with partial components or block the UI.
5. Compare complete installed file inventories and behavior before/after, including controller navigation, first launch, upgrades, games, desktop, audio, VR, and each supported device/runtime path.
6. Measure actual per-device compressed delivery, verify module and total limits, test through a Play internal-testing installation, and obtain physical-device evidence before declaring readiness.

Native component downloads must not be restored as a fallback. Guest storefront downloads and payments require their own policy review; Steam is not a blanket exemption.

## Official references

- [Google Play size limits](https://support.google.com/googleplay/android-developer/answer/9859372?hl=en)
- [Asset delivery modes and executable-code exclusion](https://developer.android.com/guide/playcore/asset-delivery)
- [Feature delivery](https://developer.android.com/guide/playcore/feature-delivery)
- [Conditional feature delivery](https://developer.android.com/guide/playcore/feature-delivery/conditional)
- [External-code and self-update restrictions](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en)

## Version 0.6.9.2 packaging

The Ubuntu runtime, both desktop archives, SteamVR, and DroidDeck Proton are now split into code and classified data frames. Preparation verifies the complete installed file inventory and metadata against the pinned inputs. The app and guest installer concatenate verified frames without recompressing on the device.

The five code portions total 2,555,491,572 bytes; their separate data portions total 449,435,399 bytes. This frees 430,798,764 bytes in the code-delivery budget compared with their previous archives, while increasing their combined compressed size by 18,636,635 bytes. All existing component entries remain present. The staged output has eight on-demand feature modules and one on-demand asset pack. The complete pinned set is requested on first launch.

Fresh installation, interrupted delivery, upgrade, Play confirmation, offline reuse, cancellation, and device-specific behavior still require verification through Google Play internal testing. Packaging checks do not establish Google policy approval.
