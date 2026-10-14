// Ported to WinNative and DroidDeck by @maxjivi05 from Camille LaVey / the Eden Emulator Project (GPL-3.0-or-later).
// DXVK's translator is zlib licensed; vendored under cpp/thirdparty/dxbc.

#include "lsfg_dxbc.h"

#include <algorithm>
#include <cstring>

#include <dxbc_modinfo.h>
#include <dxbc_module.h>
#include <dxbc_reader.h>
#include <thirdparty/spirv.hpp>

namespace lsfg {
namespace {

struct BindingSlot {
    uint32_t bindingIndex  = 0;
    uint32_t bindingOffset = 0;
    uint32_t setIndex      = 0;
    uint32_t setOffset     = 0;
};

}

bool translateDxbc(const uint8_t* bytecode, uint32_t size, std::vector<uint32_t>& outWords) {
    outWords.clear();
    if (!bytecode || size < 4) return false;

    try {
        dxvk::DxbcReader reader(reinterpret_cast<const char*>(bytecode), size);
        dxvk::DxbcModule module(reader);
        const dxvk::DxbcModuleInfo info{};
        auto code = module.compile(info, "CS");

        std::vector<BindingSlot> slotsByVarId;
        std::vector<uint32_t>    varIdsInOrder;
        for (auto ins : code) {
            if (ins.opCode() == spv::OpDecorate) {
                const uint32_t varId = ins.arg(1);
                if (ins.arg(2) == spv::DecorationBinding) {
                    slotsByVarId.resize(std::max(slotsByVarId.size(), (size_t)varId + 1));
                    slotsByVarId[varId].bindingIndex  = ins.arg(3);
                    slotsByVarId[varId].bindingOffset = ins.offset() + 3;
                    varIdsInOrder.push_back(varId);
                } else if (ins.arg(2) == spv::DecorationDescriptorSet) {
                    slotsByVarId.resize(std::max(slotsByVarId.size(), (size_t)varId + 1));
                    slotsByVarId[varId].setIndex  = ins.arg(3);
                    slotsByVarId[varId].setOffset = ins.offset() + 3;
                }
            }
            if (ins.opCode() == spv::OpFunction) break;
        }

        uint32_t next = 0;
        for (const uint32_t varId : varIdsInOrder) {
            const BindingSlot& slot = slotsByVarId[varId];
            if (slot.bindingOffset == 0) continue;
            code.data()[slot.bindingOffset] = next++;
        }

        const size_t byteSize = code.size();
        if (byteSize == 0 || byteSize % sizeof(uint32_t) != 0) return false;

        outWords.resize(byteSize / sizeof(uint32_t));
        std::memcpy(outWords.data(), code.data(), byteSize);
        return true;
    } catch (...) {
        outWords.clear();
        return false;
    }
}

}
