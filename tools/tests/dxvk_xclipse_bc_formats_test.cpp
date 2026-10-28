#include "dxvk_bc_formats.h"

#include <array>
#include <cassert>
#include <cstddef>
#include <iostream>

int main() {
  using namespace dxvk::bc;

  std::array<unsigned, D3dFormats.size()> queries = {};
  auto all_formats = [&] (VkFormat format) {
    for (std::size_t i = 0; i < D3dFormats.size(); i++) {
      if (D3dFormats[i] == format) {
        queries[i] += 1;
        return D3dFormatFeatures;
      }
    }
    return VkFormatFeatureFlags2(0);
  };

  // The aggregate core feature may remain false when a translation layer
  // implements the exact formats. The format predicate is authoritative.
  VkBool32 nativeBcFeature = VK_FALSE;
  assert(!nativeBcFeature);
  assert(supportsD3dFormats(all_formats));
  for (unsigned count : queries)
    assert(count == 1);

  // Every one of the 14 Direct3D formats is required.
  for (VkFormat missing : D3dFormats) {
    assert(!supportsD3dFormats([&] (VkFormat format) {
      return format == missing ? VkFormatFeatureFlags2(0) : D3dFormatFeatures;
    }));
  }

  // Each usage advertised by the emulation layer is required. BLIT_SRC is
  // intentionally absent and exact required flags must still pass.
  constexpr std::array<VkFormatFeatureFlags2, 4> required = {{
    VK_FORMAT_FEATURE_2_SAMPLED_IMAGE_BIT,
    VK_FORMAT_FEATURE_2_SAMPLED_IMAGE_FILTER_LINEAR_BIT,
    VK_FORMAT_FEATURE_2_TRANSFER_SRC_BIT,
    VK_FORMAT_FEATURE_2_TRANSFER_DST_BIT,
  }};
  for (VkFormatFeatureFlags2 missing : required) {
    assert(!supportsD3dFormats([&] (VkFormat format) {
      return format == D3dFormats.front() ? D3dFormatFeatures & ~missing : D3dFormatFeatures;
    }));
  }
  assert((D3dFormatFeatures & VK_FORMAT_FEATURE_2_BLIT_SRC_BIT) == 0);
  assert(supportsD3dFormats([] (VkFormat) { return D3dFormatFeatures; }));

  std::cout << "DXVK Xclipse BC format checks ok\n";
  return 0;
}
