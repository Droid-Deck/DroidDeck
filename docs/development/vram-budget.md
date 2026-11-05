# Reported video memory budget

The Performance VRAM setting enables `VK_LAYER_DROIDDECK_vram_cap` for games and reports smaller device-local `VK_EXT_memory_budget` budgets. DXVK and vkd3d-proton can use these reports when choosing memory pools. The driver still supplies real heap sizes, usage figures, memory types, and actual allocation results. DXVK also receives its existing `dxgi.maxDeviceMemory` setting. A lower driver budget remains lower; the layer never increases it.

This is a budget hint, not a physical reservation or a GPU paging implementation. Games may exceed it. On UMA hardware, host-visible Vulkan allocations can share the same device-local heap as textures. Rejecting every allocation above a fixed total can therefore break required upload buffers and other allocations, rather than make a game reduce its texture pool. The layer does not intercept `vkAllocateMemory` or `vkFreeMemory`, and preserves the real heap size so individual allocations larger than the budget remain Vulkan-valid.

The layer activates only with `DROIDDECK_VRAM_CAP=1` and a positive `DROIDDECK_VRAM_CAP_MB`. With the Performance setting on Auto, the game environment does not enable it. Explicit DXVK configuration remains available, while the Vulkan layer also covers D3D12 and games that replace their DXVK configuration.

## Physical investigation

On an 8 GB Pocket FIT Elite with DD Turnip 0.4.0, Proton 11-12.1, and Caravan SandWitch build 25301828:

- A native Vulkan probe reported a device-local heap of 5,650,776,064 bytes. All exposed memory types used the same device-local UMA heap. The initial implementation clamped both heap and budget to 2048 MiB; the revised layer preserves heap capacity and clamps only the budget.
- The initial hard allocation cap caused the existing save to fail a required 603,979,776-byte D3D12 allocation. Android survived, but the game crashed. Discord testers also reported crashes after reaching the cap in Fallout 76.
- In the initial test with allocation rejection disabled, vkd3d-proton logged the smaller 2048 MiB heap and budget. Caravan still exhausted system memory while loading the save. A reported budget alone does not solve this workload.
- DX11 loaded the same save and supported movement around the station at approximately 60 FPS for more than five minutes, both with and without the reported budget layer. Graphics settings were unchanged. This establishes a renderer workaround, not proof that the layer solved Caravan's OOM.

Native regression coverage exercises core and KHR memory-property queries, chained budget structures, unchanged real usage above the budget, separate heap types, disabled behavior, device lifecycle dispatch, and successful driver allocations whose individual and combined sizes exceed the reported budget.

The Vulkan specification describes [`heapBudget` as a guideline](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceMemoryBudgetPropertiesEXT.html). Transparent movement between device-local and host-local memory requires driver and operating-system support for [pageable device-local memory](https://docs.vulkan.org/refpages/latest/refpages/source/vkAllocateMemory.html).
