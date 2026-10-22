# Experimental Adreno 850 detection and driver imports

Reports [#342](https://github.com/Droid-Deck/DroidDeck/issues/342) and
[#723](https://github.com/Droid-Deck/DroidDeck/issues/723) describe Turnip failing
on Adreno 850, including an SM8975 / Adreno850v2 device reporting
`VK_ERROR_INCOMPATIBLE_DRIVER`. These reports motivate the detection changes;
they are not proof that the application's existing Turnip builds support A850.

## Detection

The application parses a GPU model only from an Adreno device name. The raw
KGSL `gpu_chipid` is recorded separately in device diagnostics: a decimal or
hexadecimal chip ID is not an Adreno model number.

Detection prefers the KGSL name, then an already cached Vulkan device name,
then a platform fallback. An explicit `SM8975` or `QTI SM8975` SoC identifies
Adreno 850 when neither device name is available, including when the board
property still uses an older platform name. Driver auto selection uses the
same detection, with existing external-driver and Quest overrides retained.
This does not start a new Vulkan query during driver selection.

A850 is labelled experimental. The SM8975 platform identity retains that
classification even when the selected driver reports Adreno 840; the displayed
model still follows the KGSL/Vulkan name. Identifying the hardware does not add native
A850 support to Turnip or prove that Steam and games will run.

## Optional community driver bundle

The community attachments uploaded to #342 on 2026-10-12 contain experimental
Android display and Linux game drivers. Their metadata describes a binary
patch that treats A850 as A840 and reuses some Adreno X2 entries. These are
not native A850 drivers; they may fail to start, hang, or render incorrectly,
and have not been validated across A850 revisions.

Download the original attachments separately:

| Platform | Original attachment | SHA-256 |
| --- | --- | --- |
| Android | [AdrenoTools-A850-experimental.zip](https://github.com/user-attachments/files/33319268/AdrenoTools-A850-experimental.zip) | `0cf849d068c875f6c469380ca1f74da1f757575bce53d4ef9c1f6d38677cbe8b` |
| Linux | [A850-experimental.zip](https://github.com/user-attachments/files/33319267/A850-experimental.zip) | `40fc59506620001531b9bdd1c9152e104c6fb1f4c07773c4743b9d70deb87eec` |

Prepare a bundle accepted by DroidDeck's existing DriverBundle importer:

```sh
mkdir -p build/a850-experimental
python3 tools/prepare_a850_driver_bundle.py \
  --android /absolute/path/AdrenoTools-A850-experimental.zip \
  --linux /absolute/path/A850-experimental.zip \
  --output build/a850-experimental/A850-community-experimental.zip
```

The tool accepts only the pinned originals, validates their ZIP entries,
metadata and ELF platform, and preserves the original files byte for byte.
It does not download, execute or rebuild drivers. Neither binary is included
in the source tree or installed automatically by these changes.

To try the bundle, stop the session and wait for any automatic driver check
or download to finish. Note the existing driver selections, then use
**Components → GPU drivers → Mix and match → Import .zip**. This is a GPU
driver bundle, not a Decky plugin ZIP. Confirm that both the Android display
and Linux game drivers are selected, then fully exit and reopen the app.
Keep **Session → Performance → Session repair → Turnip: sysmem rendering**
enabled as advised by the original Linux driver metadata.

Test entering Steam separately from game rendering. If it fails, restore the
previous drivers and restart the app. Preserve the **Share logs** ZIP,
including `device.txt`, `session.log`, the exact chip ID and Android version.

## Validation

```sh
python3 -m unittest discover -s tools/tests -p test_a850_driver_bundle.py -v
./gradlew -PskipRust=true :app:testDebugUnitTest --tests 'com.droiddeck.launcher.gpu.*'
```

The tests cover detection priority, chip-ID rejection, SoC normalization,
experimental status, driver-generation selection and bundle integrity. They
do not replace Vulkan, Steam or game testing on the target phone. A user
confirmed that importing the experimental bundle succeeded; target-device
Steam and game compatibility remain unverified.
