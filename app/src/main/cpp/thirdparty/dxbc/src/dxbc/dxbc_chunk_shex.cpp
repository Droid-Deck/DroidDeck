#include "dxbc_chunk_shex.h"

namespace dxvk {
  
  DxbcShex::DxbcShex(DxbcReader reader) {
    reader.skip(2);
    auto pType    = reader.readEnum<DxbcProgramType>();
    m_programInfo = DxbcProgramInfo(pType);
    
    auto codeLength = reader.readu32() - 2;
    m_code.resize(codeLength);
    reader.read(m_code.data(), codeLength * sizeof(uint32_t));
  }
  
  
  DxbcShex::~DxbcShex() {
    
  }
  
}
