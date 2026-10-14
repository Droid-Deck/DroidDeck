#pragma once

#include <string>

namespace dxvk {
  
  class DxvkError {
    
  public:
    
    DxvkError() { }
    DxvkError(std::string&& message)
    : m_message(std::move(message)) { }
    
    const std::string& message() const {
      return m_message;
    }
    
  private:
    
    std::string m_message;
    
  };
  
}