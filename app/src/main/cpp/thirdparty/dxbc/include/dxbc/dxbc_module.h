#pragma once

#include "dxbc_chunk_isgn.h"
#include "dxbc_chunk_shex.h"
#include "dxbc_header.h"
#include "dxbc_modinfo.h"
#include "dxbc_reader.h"
#include "dxbc_util.h"

#include "spirv_code_buffer.h"

#include <optional>


namespace dxvk {
  
  class DxbcAnalyzer;
  class DxbcCompiler;
  
  struct DxbcIcbInfo {
    size_t      size = 0u;
    const void* data = nullptr;
  };


  class DxbcModule {
    
  public:
    
    DxbcModule(DxbcReader& reader);
    ~DxbcModule();
    
    std::optional<DxbcProgramInfo> programInfo() const {
      if (m_shexChunk == nullptr)
        return std::nullopt;

      return m_shexChunk->programInfo();
    }

    std::optional<DxbcBindingMask> bindings() const {
      return m_bindings;
    }

    DxbcIcbInfo icbInfo() const {
      DxbcIcbInfo result = { };
      result.size = m_icb.size() * sizeof(uint32_t);
      result.data = m_icb.data();
      return result;
    }

    Rc<DxbcIsgn> isgn() const { return m_isgnChunk; }
    Rc<DxbcIsgn> osgn() const { return m_osgnChunk; }

    SpirvCodeBuffer compile(
      const DxbcModuleInfo& moduleInfo,
      const std::string&    fileName);
    
    SpirvCodeBuffer compilePassthroughShader(
      const DxbcModuleInfo& moduleInfo,
      const std::string&    fileName) const;

  private:
    
    DxbcHeader   m_header;
    
    Rc<DxbcIsgn> m_isgnChunk;
    Rc<DxbcIsgn> m_osgnChunk;
    Rc<DxbcIsgn> m_psgnChunk;
    Rc<DxbcShex> m_shexChunk;

    std::vector<uint32_t> m_icb;

    std::optional<DxbcBindingMask> m_bindings;
    
    void runAnalyzer(
            DxbcAnalyzer&       analyzer,
            DxbcCodeSlice       slice) const;
    
    void runCompiler(
            DxbcCompiler&       compiler,
            DxbcCodeSlice       slice) const;
    
  };
  
}
