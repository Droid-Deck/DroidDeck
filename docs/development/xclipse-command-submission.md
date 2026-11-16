# Xclipse command submission investigation

Status: root cause unknown. The reported log establishes a Vulkan submission failure, but not the failing driver operation. No production change or device-specific workaround is justified yet.

## Report and failure boundary

A [public bug report](https://discord.com/channels/1552707793247539220/1571669212861038754/1571669212861038754) describes games freezing on a Galaxy S23 FE with an Exynos 2200 and Xclipse 920 while Steam and the compositor remain responsive. The report covers application 0.7.1 (19) and says RADV-Xclipse 1.8.0 and 1.9.0 behave identically.

The supplied session archive identifies DroidDeck-Proton 11-12.8 and DXVK v3.1.1-221-g71bf4bad. RADV VANGOGHLITE enumerates successfully. Descriptor indexing and graphics pipeline library support are enabled, but their presence alone does not establish either feature as the cause.

Within `games/entry-4369402112cd3c37/steam-554620.log`:

- Lines 5250 and 5251 identify the DXVK revision and architecture.
- Line 5279 identifies RADV VANGOGHLITE.
- Lines 5516 and 5569 report device fault and AMD buffer marker support as unavailable.
- Line 10242 records `DxvkSubmissionQueue: Command submission failed: -13`.
- Subsequent Wine directory polling establishes continued process activity, not successful rendering.

`session.log` lines 7 and 85 show selection of the imported upstream RADV Linux driver. The archive does not identify the exact source revision of the loaded library, so its directory name cannot serve as a revision fingerprint.

`-13` is `VK_ERROR_UNKNOWN`, not `VK_ERROR_DEVICE_LOST` (`-4`), as defined by [VkResult](https://registry.khronos.org/vulkan/specs/latest/html/vkspec.html). A GPU hang remains possible but is not established. The bundle contains no underlying sync-object error, command rejection, kernel GPU fault, or hang dump.

## Submission paths examined

At the reported revision, DXVK logs the result returned by command-list submission. The relevant paths include graphics and transfer `vkQueueSubmit2` calls and sparse submissions. Presentation errors are handled separately. The single result cannot establish which operation failed, and DXVK's subsequent wait for idle does not identify the original cause.

- [DXVK submission queue](https://github.com/doitsujin/dxvk/blob/71bf4bad/src/dxvk/dxvk_queue.cpp#L148-L187)
- [DXVK command-list submissions](https://github.com/doitsujin/dxvk/blob/71bf4bad/src/dxvk/dxvk_cmdlist.cpp#L248-L375)

RADV-Xclipse 1.8.0 has several sync paths that can return `VK_ERROR_UNKNOWN`, including sync-object reset, wait and transfer, sync-file import and export, and fence merging. Sync-file export can reject a fence that is not materialized within five seconds, but no accompanying diagnostic proves that this path failed here.

- [RADV-Xclipse sync-object handling](https://github.com/JimVulkan/radv-xclipse/blob/v1.8.0/src/vulkan/runtime/vk_drm_syncobj.c#L430-L462)
- [RADV-Xclipse sync-file handling](https://github.com/JimVulkan/radv-xclipse/blob/v1.8.0/src/vulkan/runtime/vk_drm_syncobj.c#L510-L642)

## Related work and rejected hypotheses

Pull requests #2337 and #2581 concern automatic driver selection. The report already uses a manually selected upstream driver, so those changes do not establish a remedy. Issue #290 is a general Xclipse support request and remains unresolved by this record. Steam drawing changes are not applicable because Steam remains responsive in the report.

Searches covered DroidDeck history and forks, RADV-Xclipse, DXVK, Proton, vkd3d-proton, Mesa, FEX, Box64, Winlator, and GameNative using submission failure, device loss, freeze, and Xclipse terms. No verified patch matching this failure boundary was found. Similar rendering issues on other Xclipse models or workloads do not establish the same mechanism.

Blanket descriptor-indexing, graphics-pipeline-library, or asynchronous-compilation disabling would be speculative. No per-title or per-device override has been added.

## Device verification and required next experiment

The available Retroid Pocket Duo cannot reproduce an Xclipse driver fault. No Xclipse device was available, and no before-or-after behavior claim is made.

Reproduce on the affected Xclipse device with driver diagnostics that expose the failing sync or submission operation and its operating-system error. Capture the exact driver revision, standard error output, Android driver and kernel diagnostics, and any GPU hang report. Confirm the logging controls against the installed driver before requesting that capture.

Use the first failing operation to select a controlled comparison between graphics or transfer submission, sync-file materialization, and sparse binding. Change one supported setting at a time and preserve explicit user settings. A subsequent correction must be checked against both the failing workload and a second D3D11 workload on Xclipse. A matching upstream correction should be ported with contributor credit only after its applicability is demonstrated.
