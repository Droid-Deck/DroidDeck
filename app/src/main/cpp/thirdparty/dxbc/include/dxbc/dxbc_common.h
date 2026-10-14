#pragma once

#include "dxbc_include.h"

namespace dxvk {
  
  enum class DxbcProgramType : uint16_t {
    PixelShader     = 0,
    VertexShader    = 1,
    GeometryShader  = 2,
    HullShader      = 3,
    DomainShader    = 4,
    ComputeShader   = 5,

    Count
  };

  using DxbcProgramTypeFlags = Flags<DxbcProgramType>;
  
  
  class DxbcProgramInfo {
    
  public:
    
    DxbcProgramInfo() { }
    DxbcProgramInfo(DxbcProgramType type)
    : m_type(type) { }
    
    DxbcProgramType type() const {
      return m_type;
    }
    
    VkShaderStageFlagBits shaderStage() const;
    
    spv::ExecutionModel executionModel() const;
    
  private:
    
    DxbcProgramType m_type  = DxbcProgramType::PixelShader;
    
  };
  
}
