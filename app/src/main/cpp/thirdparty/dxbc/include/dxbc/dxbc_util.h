#pragma once

#include "dxbc_common.h"
#include "dxbc_enums.h"

namespace dxvk {

  struct DxbcPushConstants {
    uint32_t rasterizerSampleCount;
  };


  enum DxbcBindingProperties : uint32_t {
    DxbcConstBufBindingIndex  = 0,
    DxbcConstBufBindingCount  = 16,
    DxbcSamplerBindingIndex   = DxbcConstBufBindingIndex
                              + DxbcConstBufBindingCount,
    DxbcSamplerBindingCount   = 16,
    DxbcResourceBindingIndex  = DxbcSamplerBindingIndex
                              + DxbcSamplerBindingCount,
    DxbcResourceBindingCount  = 128,
    DxbcStageBindingCount     = DxbcConstBufBindingCount
                              + DxbcSamplerBindingCount
                              + DxbcResourceBindingCount,
    DxbcUavBindingIndex       = DxbcStageBindingCount * 6,
    DxbcUavBindingCount       = 64,
  };


  struct DxbcBindingMask {
    uint32_t cbvMask      = 0u;
    uint32_t samplerMask  = 0u;
    uint64_t uavMask      = 0u;
    std::array<uint64_t, 2> srvMask = { };

    void reset() {
      cbvMask = 0u;
      samplerMask = 0u;
      uavMask = 0u;
      srvMask = { };
    }

    bool empty() const {
      uint64_t mask = (uint64_t(cbvMask) | uint64_t(samplerMask) << 32u)
                    | (uavMask | srvMask[0] | srvMask[1]);
      return !mask;
    }

    DxbcBindingMask operator & (const DxbcBindingMask& other) const {
      DxbcBindingMask result = *this;
      result.cbvMask      &= other.cbvMask;
      result.samplerMask  &= other.samplerMask;
      result.uavMask      &= other.uavMask;
      result.srvMask[0]   &= other.srvMask[0];
      result.srvMask[1]   &= other.srvMask[1];
      return result;
    }
  };


  inline uint32_t computeStageBindingOffset(DxbcProgramType stage) {
    return DxbcStageBindingCount * uint32_t(stage);
  }


  inline uint32_t computeStageUavBindingOffset(DxbcProgramType stage) {
    return DxbcUavBindingIndex
         + DxbcUavBindingCount * (stage == DxbcProgramType::ComputeShader ? 2 : 0);
  }


  inline uint32_t computeConstantBufferBinding(DxbcProgramType stage, uint32_t index) {
    return computeStageBindingOffset(stage) + DxbcConstBufBindingIndex + index;
  }


  inline uint32_t computeSamplerBinding(DxbcProgramType stage, uint32_t index) {
    return computeStageBindingOffset(stage) + DxbcSamplerBindingIndex + index;
  }


  inline uint32_t computeSrvBinding(DxbcProgramType stage, uint32_t index) {
    return computeStageBindingOffset(stage) + DxbcResourceBindingIndex + index;
  }


  inline uint32_t computeUavBinding(DxbcProgramType stage, uint32_t index) {
    return computeStageUavBindingOffset(stage) + index;
  }
  
  
  inline uint32_t computeUavCounterBinding(DxbcProgramType stage, uint32_t index) {
    return computeStageUavBindingOffset(stage) + DxbcUavBindingCount + index;
  }
  
  uint32_t primitiveVertexCount(
          DxbcPrimitive   primitive);
  
}
