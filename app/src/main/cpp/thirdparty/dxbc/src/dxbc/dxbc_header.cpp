#include "dxbc_header.h"

namespace dxvk {
  
  DxbcHeader::DxbcHeader(DxbcReader& reader) {
    DxbcTag fourcc = reader.readTag();
    
    if (fourcc != "DXBC")
      throw DxvkError("DxbcHeader::DxbcHeader: Invalid fourcc, expected 'DXBC'");
    

    reader.skip(4 * sizeof(uint32_t));
    reader.skip(1 * sizeof(uint32_t));
    reader.skip(1 * sizeof(uint32_t));
    
    uint32_t chunkCount = reader.readu32();
    
    for (uint32_t i = 0; i < chunkCount; i++)
      m_chunkOffsets.push_back(reader.readu32());
  }
  
  
  DxbcHeader::~DxbcHeader() {
    
  }
  
}