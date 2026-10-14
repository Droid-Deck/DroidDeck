#pragma once

#include <vulkan/vulkan.h>

#include "util_flags.h"

namespace dxvk {

  struct D3D11Options;

  enum class DxbcFloatControlFlag : uint32_t {
    DenormFlushToZero32,
    DenormPreserve64,
    PreserveNan32,
    PreserveNan64,
  };

  using DxbcFloatControlFlags = Flags<DxbcFloatControlFlag>;

  struct DxbcOptions {
    DxbcOptions() {}

    bool useDepthClipWorkaround = false;

    bool supportsTypedUavLoadR32 = false;

    bool supportsRawAccessChains = false;

    bool zeroInitWorkgroupMemory = false;

    bool invariantPosition = false;

    bool forceVolatileTgsmAccess = false;

    bool forceComputeUavBarriers = false;

    bool disableMsaa = false;

    bool forceSampleRateShading = false;

    bool enableSampleShadingInterlock = false;

    bool supportsTightIcbPacking = false;

    bool needsPointSizeExport = true;

    bool sincosEmulation = false;

    DxbcFloatControlFlags floatControl;

    VkDeviceSize minSsboAlignment = 0;
  };
  
}
