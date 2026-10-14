#pragma once
// DXVK's dxbc subset is zlib licensed; vendored under cpp/thirdparty/dxbc.

#include <cstdint>
#include <vector>

namespace lsfg {

bool translateDxbc(const uint8_t* bytecode, uint32_t size, std::vector<uint32_t>& outWords);

}
