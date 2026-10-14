#pragma once

#include "dxbc_chunk_isgn.h"
#include "dxbc_decoder.h"
#include "dxbc_defs.h"
#include "dxbc_names.h"
#include "dxbc_modinfo.h"
#include "dxbc_util.h"

namespace dxvk {
  
  struct DxbcUavInfo {
    bool accessTypedLoad    = false;
    bool accessAtomicOp     = false;
    bool sparseFeedback     = false;
    bool nonInvariantAccess = false;
    DxvkAccessOp accessOp   = DxvkAccessOp::None;
    VkAccessFlags accessFlags = 0;
  };
  
  struct DxbcSrvInfo {
    bool sparseFeedback  = false;
  };

  struct DxbcClipCullInfo {
    uint32_t numClipPlanes = 0;
    uint32_t numCullPlanes = 0;
  };
  
  struct DxbcAnalysisInfo {
    std::array<DxbcUavInfo, 64>   uavInfos;
    std::array<DxbcSrvInfo, 128>  srvInfos;
    std::array<DxbcRegMask, 4096> xRegMasks;
    
    DxbcClipCullInfo clipCullIn;
    DxbcClipCullInfo clipCullOut;

    DxbcBindingMask bindings = { };
    
    bool usesDerivatives  = false;
    bool usesKill         = false;
  };
  
  class DxbcAnalyzer {
    
  public:
    
    DxbcAnalyzer(
      const DxbcModuleInfo&     moduleInfo,
      const DxbcProgramInfo&    programInfo,
      const Rc<DxbcIsgn>&       isgn,
      const Rc<DxbcIsgn>&       osgn,
      const Rc<DxbcIsgn>&       psgn,
            DxbcAnalysisInfo&   analysis);
    
    ~DxbcAnalyzer();
    
    void processInstruction(
      const DxbcShaderInstruction&  ins);
    
  private:
    
    Rc<DxbcIsgn> m_isgn;
    Rc<DxbcIsgn> m_osgn;
    Rc<DxbcIsgn> m_psgn;
    
    DxbcAnalysisInfo* m_analysis = nullptr;
    
    DxbcClipCullInfo getClipCullInfo(
      const Rc<DxbcIsgn>& sgn) const;

    void setUavAccessOp(uint32_t uav, DxvkAccessOp op);

    static DxvkAccessOp getStoreAccessOp(DxbcRegMask writeMask, const DxbcRegister& src);

    static DxvkAccessOp getConstantStoreOp(uint32_t value);

  };
  
}
