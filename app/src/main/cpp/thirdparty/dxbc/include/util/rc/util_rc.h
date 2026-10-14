#pragma once

#include <atomic>

#include "../util_likely.h"

namespace dxvk {
  
  class RcObject {
    
  public:
    
    force_inline uint32_t incRef() {
      return ++m_refCount;
    }
    
    force_inline uint32_t decRef() {
      return --m_refCount;
    }
    
  private:
    
    std::atomic<uint32_t> m_refCount = { 0u };
    
  };
  
}