#pragma once

#include <vulkan/vulkan.h>

#include <cstdint>

#include "dxvk_hash.h"

#include "util_math.h"
#include "util_bit.h"
#include "util_flags.h"

namespace dxvk {

  class DxvkDevice;
  class DxvkPipelineManager;

  struct DxvkAccessOp {
    enum OpType : uint16_t {
      None      = 0x0u,
      Or        = 0x1u,
      And       = 0x2u,
      Xor       = 0x3u,
      Add       = 0x4u,
      IMin      = 0x5u,
      IMax      = 0x6u,
      UMin      = 0x7u,
      UMax      = 0x8u,

      StoreF    = 0xdu,
      StoreUi   = 0xeu,
      StoreSi   = 0xfu,
    };

    DxvkAccessOp() = default;
    DxvkAccessOp(OpType t)
    : op(uint16_t(t)) { }

    DxvkAccessOp(OpType t, uint16_t constant)
    : op(uint16_t(t) | (constant << 4u)) { }

    uint16_t op = 0u;

    bool operator == (const DxvkAccessOp& t) const { return op == t.op; }
    bool operator != (const DxvkAccessOp& t) const { return op != t.op; }

    template<typename T, std::enable_if_t<std::is_integral_v<T>, bool> = true>
    explicit operator T() const { return op; }
  };

  static_assert(sizeof(DxvkAccessOp) == sizeof(uint16_t));

  struct DxvkBindingInfo {
    VkDescriptorType      descriptorType  = VK_DESCRIPTOR_TYPE_MAX_ENUM;
    uint32_t              resourceBinding = 0u;
    VkImageViewType       viewType        = VK_IMAGE_VIEW_TYPE_MAX_ENUM;
    VkShaderStageFlagBits stage           = VK_SHADER_STAGE_FLAG_BITS_MAX_ENUM;
    VkAccessFlags         access          = 0u;
    DxvkAccessOp          accessOp        = DxvkAccessOp::None;
    bool                  uboSet          = false;
    bool                  isMultisampled  = false;

    uint32_t computeSetIndex() const;

    uint32_t value() const;

    bool eq(const DxvkBindingInfo& other) const;

    size_t hash() const;

  };

}
