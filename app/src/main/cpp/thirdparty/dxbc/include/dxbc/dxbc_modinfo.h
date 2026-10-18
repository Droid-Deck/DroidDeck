#pragma once

#include "dxbc_options.h"

namespace dxvk {

  struct DxbcTessInfo {
    float maxTessFactor;
  };

  struct DxbcXfbEntry {
    const char* semanticName;
    uint32_t    semanticIndex;
    uint32_t    componentIndex;
    uint32_t    componentCount;
    uint32_t    streamId;
    uint32_t    bufferId;
    uint32_t    offset;
  };

  struct DxbcXfbInfo {
    uint32_t      entryCount;
    DxbcXfbEntry  entries[128];
    uint32_t      strides[4];
    int32_t       rasterizedStream;
  };

  struct DxbcModuleInfo {
    DxbcOptions   options;
    DxbcTessInfo* tess;
    DxbcXfbInfo*  xfb;
  };

}