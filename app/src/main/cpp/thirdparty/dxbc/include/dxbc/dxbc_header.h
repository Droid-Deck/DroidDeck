#pragma once

#include <vector>

#include "dxbc_reader.h"

namespace dxvk {
  
  class DxbcHeader {
    
  public:
    
    DxbcHeader(DxbcReader& reader);
    ~DxbcHeader();
    
    uint32_t numChunks() const {
      return m_chunkOffsets.size();
    }
    
    uint32_t chunkOffset(uint32_t chunkId) const {
      return m_chunkOffsets.at(chunkId);
    }
    
  private:
    
    std::vector<uint32_t> m_chunkOffsets;
    
  };
  
}