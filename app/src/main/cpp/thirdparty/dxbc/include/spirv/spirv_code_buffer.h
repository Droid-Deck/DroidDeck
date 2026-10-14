#pragma once

#include <iostream>
#include <utility>
#include <vector>

#include "spirv_instruction.h"

namespace dxvk {
  
  class SpirvCodeBuffer {
    
  public:
    
    SpirvCodeBuffer();
    explicit SpirvCodeBuffer(uint32_t size);
    SpirvCodeBuffer(const SpirvCodeBuffer &) = default;
    SpirvCodeBuffer(SpirvCodeBuffer &&) = default;
    SpirvCodeBuffer(uint32_t size, const uint32_t* data);
    SpirvCodeBuffer(std::istream& stream);
    
    template<size_t N>
    SpirvCodeBuffer(const uint32_t (&data)[N])
    : SpirvCodeBuffer(N, data) { }
    
    ~SpirvCodeBuffer();

    SpirvCodeBuffer &operator=(const SpirvCodeBuffer &) = default;
    SpirvCodeBuffer &operator=(SpirvCodeBuffer &&) = default;
    
    const uint32_t* data() const { return m_code.data(); }
          uint32_t* data()       { return m_code.data(); }
    
    uint32_t dwords() const {
      return m_code.size();
    }
    
    size_t size() const {
      return m_code.size() * sizeof(uint32_t);
    }
    
    SpirvInstructionIterator begin() {
      return SpirvInstructionIterator(
        m_code.data(), 0, m_code.size());
    }
    
    SpirvInstructionIterator end() {
      return SpirvInstructionIterator(nullptr, 0, 0);
    }
    
    uint32_t allocId();
    
    void append(const SpirvInstruction& ins);

    void append(const SpirvCodeBuffer& other);
    
    void putWord(uint32_t word);
    
    void putIns(spv::Op opCode, uint16_t wordCount);

    void putInt32(uint32_t word);
    
    void putInt64(uint64_t value);
    
    void putFloat32(float value);
    
    void putFloat64(double value);
    
    void putStr(const char* str);
    
    void putHeader(uint32_t version, uint32_t boundIds);

    void erase(size_t size);
    
    uint32_t strLen(const char* str);
    
    void store(std::ostream& stream) const;
    
    size_t getInsertionPtr() const {
      return m_ptr;
    }
    
    void beginInsertion(size_t ptr) {
      m_ptr = ptr;
    }
    
    size_t endInsertion() {
      return std::exchange(m_ptr, m_code.size());
    }
    
  private:
    
    std::vector<uint32_t> m_code;
    size_t m_ptr = 0;
    
  };
  
}
