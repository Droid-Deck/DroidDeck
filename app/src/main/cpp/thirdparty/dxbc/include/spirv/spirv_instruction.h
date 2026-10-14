#pragma once

#include "spirv_include.h"

namespace dxvk {
  
  class SpirvInstruction {
    
  public:
    
    SpirvInstruction() { }
    SpirvInstruction(uint32_t* code, uint32_t offset, uint32_t length)
    : m_code(code), m_offset(offset), m_length(length) { }
    
    spv::Op opCode() const {
      return static_cast<spv::Op>(
        this->arg(0) & spv::OpCodeMask);
    }
    
    uint32_t length() const {
      return this->arg(0) >> spv::WordCountShift;
    }
    
    uint32_t offset() const {
      return m_offset;
    }
    
    uint32_t arg(uint32_t idx) const {
      const uint32_t index = m_offset + idx;
      return index < m_length ? m_code[index] : 0;
    }

    const char* chr(uint32_t idx) const {
      const uint32_t index = m_offset + idx;
      return index < m_length ? reinterpret_cast<const char*>(&m_code[index]) : nullptr;
    }
    
    void setArg(uint32_t idx, uint32_t word) const {
      if (m_offset + idx < m_length)
        m_code[m_offset + idx] = word;
    }
    
  private:
    
    uint32_t* m_code   = nullptr;
    uint32_t  m_offset = 0;
    uint32_t  m_length = 0;
    
  };
  
  
  class SpirvInstructionIterator {
    
  public:
    
    SpirvInstructionIterator() { }
    SpirvInstructionIterator(uint32_t* code, uint32_t offset, uint32_t length)
    : m_code  (length != 0 ? code   : nullptr),
      m_offset(length != 0 ? offset : 0),
      m_length(length) {
      if ((length >= 5) && (offset == 0) && (m_code[0] == spv::MagicNumber))
        this->advance(5);
    }
    
    SpirvInstructionIterator& operator ++ () {
      this->advance(SpirvInstruction(m_code, m_offset, m_length).length());
      return *this;
    }
    
    SpirvInstructionIterator operator ++ (int) {
      SpirvInstructionIterator result = *this;
      this->advance(SpirvInstruction(m_code, m_offset, m_length).length());
      return result;
    }
    
    SpirvInstruction operator * () const {
      return SpirvInstruction(m_code, m_offset, m_length);
    }
    
    bool operator == (const SpirvInstructionIterator& other) const {
      return this->m_code   == other.m_code
          && this->m_offset == other.m_offset
          && this->m_length == other.m_length;
    }
    
    bool operator != (const SpirvInstructionIterator& other) const {
      return this->m_code   != other.m_code
          || this->m_offset != other.m_offset
          || this->m_length != other.m_length;
    }
    
  private:
    
    uint32_t* m_code   = nullptr;
    uint32_t  m_offset = 0;
    uint32_t  m_length = 0;
    
    void advance(uint32_t n) {
      if (m_offset + n < m_length) {
        m_offset += n;
      } else {
        m_code   = nullptr;
        m_offset = 0;
        m_length = 0;
      }
    }
    
  };
  
}