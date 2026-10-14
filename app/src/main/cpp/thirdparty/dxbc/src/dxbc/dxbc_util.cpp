#include "dxbc_util.h"

namespace dxvk {
  
  uint32_t primitiveVertexCount(DxbcPrimitive primitive) {
    static const std::array<uint32_t, 8> s_vertexCounts = {
       0,
       1,
       2,
       3,
       0,
       0,
       4,
       6,
    };
    
    if (primitive >= DxbcPrimitive::Patch1) {
      return uint32_t(primitive)
           - uint32_t(DxbcPrimitive::Patch1)
           + 1u;
    } else {
      return s_vertexCounts.at(uint32_t(primitive));
    }
  }
  
}