// Backend/adapter fixture for the verbatim DXVK functions in d3d9_production.inc.
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

using HRESULT = int32_t;
using DWORD = uint32_t;
using UINT = uint32_t;
using VkSampleCountFlagBits = uint32_t;
constexpr HRESULT D3D_OK = 0, D3DERR_INVALIDCALL = -1,
                  D3DERR_NOTAVAILABLE = -2, D3DERR_OUTOFVIDEOMEMORY = -3;
constexpr uint32_t VK_SAMPLE_COUNT_1_BIT = 1, VK_IMAGE_ASPECT_COLOR_BIT = 1;
constexpr bool FALSE = false, TRUE = true;
#define FAILED(x) ((x) < 0)
#define unlikely(x) (x)
enum D3DMULTISAMPLE_TYPE { D3DMULTISAMPLE_NONE, D3DMULTISAMPLE_NONMASKABLE,
                         D3DMULTISAMPLE_16_SAMPLES = 16 };
enum D3DRESOURCETYPE { D3DRTYPE_SURFACE, D3DRTYPE_CUBETEXTURE, D3DRTYPE_VOLUMETEXTURE };
enum D3DPOOL { D3DPOOL_DEFAULT, D3DPOOL_MANAGED, D3DPOOL_SYSTEMMEM, D3DPOOL_SCRATCH };
enum class D3DCompatibility { D3D9Ex };
enum class D3D9Format { X8R8G8B8, D24S8, A8, NULL_FORMAT, ATI2,
                      D32_LOCKABLE, D32F_LOCKABLE, D16_LOCKABLE, S8_LOCKABLE };
constexpr DWORD D3DUSAGE_RENDERTARGET = 1, D3DUSAGE_DEPTHSTENCIL = 2,
                D3DUSAGE_DYNAMIC = 4, D3DUSAGE_WRITEONLY = 8, D3DUSAGE_AUTOGENMIPMAP = 16;
constexpr DWORD D3DPRESENTFLAG_LOCKABLE_BACKBUFFER = 1, D3DPRESENTFLAG_DISCARD_DEPTHSTENCIL = 2;
constexpr int D3DDEVTYPE_HAL = 1, VK_IMAGE_LAYOUT_UNDEFINED = 0;
struct Extent { uint32_t width, height, depth = 1; };
namespace util {
uint32_t computeMipLevelCount(Extent e) {
  uint32_t n = std::max({e.width, e.height, e.depth}), levels = 0;
  do { ++levels; n >>= 1; } while (n);
  return levels;
}
}
bool IsPoolManaged(D3DPOOL pool) { return pool == D3DPOOL_MANAGED; }
bool IsDXTFormat(D3D9Format) { return false; }
bool IsDepthStencilFormat(D3D9Format format) { return format == D3D9Format::D24S8; }
bool IsLockableDepthStencilFormat(D3D9Format) { return false; }
struct D3D9_FORMAT_BLOCK_SIZE { uint32_t Width, Height; };
D3D9_FORMAT_BLOCK_SIZE GetFormatAlignedBlockSize(D3D9Format) { return {1, 1}; }
D3D9Format EnumerateFormat(D3D9Format format) { return format; }
struct D3D9_COMMON_TEXTURE_DESC {
  uint32_t Width = 1280, Height = 720, Depth = 1, MipLevels = 1, ArraySize = 1;
  D3D9Format Format = D3D9Format::X8R8G8B8;
  D3DMULTISAMPLE_TYPE MultiSample = D3DMULTISAMPLE_NONE;
  DWORD MultisampleQuality = 0;
  D3DPOOL Pool = D3DPOOL_DEFAULT;
  DWORD Usage = D3DUSAGE_RENDERTARGET;
  bool Discard = false, IsBackBuffer = false, IsAttachmentOnly = false, IsLockable = false;
};
struct D3DPRESENT_PARAMETERS {
  uint32_t BackBufferWidth = 1280, BackBufferHeight = 720;
  D3D9Format BackBufferFormat = D3D9Format::X8R8G8B8;
  D3DMULTISAMPLE_TYPE MultiSampleType = D3DMULTISAMPLE_TYPE(4);
  DWORD MultiSampleQuality = 0, Flags = 0;
  D3D9Format AutoDepthStencilFormat = D3D9Format::D24S8;
};
struct Options {
  bool forceSingleSample = false, extraFrontbuffer = false,
       disableA8RT = false, supportCubeDepthFormats = false;
};
struct FormatMapping {
  uint32_t Aspect;
  bool IsValid() const { return true; }
};
struct Adapter {
  bool supported = true;
  std::vector<D3DMULTISAMPLE_TYPE> checked;
  HRESULT CheckDeviceMultiSampleType(int, D3D9Format, bool, D3DMULTISAMPLE_TYPE samples, void*) {
    checked.push_back(samples);
    return supported ? D3D_OK : D3DERR_NOTAVAILABLE;
  }
};
struct ImageInfo { Extent extent; VkSampleCountFlagBits sampleCount; };
struct DxvkImage { ImageInfo info; };
template<typename T> using Rc = std::shared_ptr<T>;
template<typename T, unsigned N> using small_vector = std::vector<T>;
struct DxvkContext {
  unsigned initialized = 0;
  void initImage(const Rc<DxvkImage>&, int) { ++initialized; }
};
struct D3D9DeviceEx {
  Options options;
  Adapter adapter;
  DxvkContext context;
  unsigned attempts = 0, failAt = 0, losable = 0, live = 0, injected = 0;
  std::vector<D3D9_COMMON_TEXTURE_DESC> created;
  Options* GetOptions() { return &options; }
  Adapter* GetAdapter() { return &adapter; }
  FormatMapping LookupFormat(D3D9Format format) { return {IsDepthStencilFormat(format) ? 2u : 1u}; }
  struct Unsupported { unsigned elementSize = 0; };
  Unsupported unsupported;
  const Unsupported* UnsupportedFormatInfo(D3D9Format) { return &unsupported; }
  bool IsD3DCompatibile(D3DCompatibility) { return false; }
  void IncrementLosableCounter() { ++losable; }
  template<typename F> void InjectCs(F function) { ++injected; function(&context); }
};
HRESULT DecodeMultiSampleType(D3DMULTISAMPLE_TYPE, DWORD, VkSampleCountFlagBits*);
struct D3D9CommonTexture {
  Rc<DxvkImage> image;
  const Rc<DxvkImage>& GetImage() const { return image; }
  static HRESULT NormalizeTextureProperties(D3D9DeviceEx*, D3DRESOURCETYPE, D3D9_COMMON_TEXTURE_DESC*);
  /* PRODUCTION_USES_IMAGE */
};
class DxvkError : public std::runtime_error {
public:
  using std::runtime_error::runtime_error;
  std::string message() const { return what(); }
};
struct Logger { static void err(const std::string&) {} };
struct D3D9SwapChainEx;
struct D3D9Surface {
  D3D9CommonTexture texture;
  D3D9DeviceEx* device;
  D3D9Surface(D3D9DeviceEx* d, D3D9_COMMON_TEXTURE_DESC* desc, bool, D3D9SwapChainEx*, void*) : device(d) {
    if (++d->attempts == d->failAt) throw DxvkError("controlled allocation failure");
    VkSampleCountFlagBits samples;
    if (FAILED(DecodeMultiSampleType(desc->MultiSample, desc->MultisampleQuality, &samples)))
      throw DxvkError("invalid backend sample count");
    texture.image = std::make_shared<DxvkImage>(DxvkImage{{{desc->Width, desc->Height}, samples}});
    d->created.push_back(*desc); ++d->live;
  }
  ~D3D9Surface() { --device->live; }
  D3D9CommonTexture* GetCommonTexture() { return &texture; }
};
struct D3D9SwapChainEx {
  D3D9DeviceEx* m_parent;
  D3DPRESENT_PARAMETERS m_presentParams;
  std::vector<std::unique_ptr<D3D9Surface>> m_backBuffers;
  bool flip = false;
  unsigned destroys = 0;
  bool SwapWithFrontBuffer() { return flip; }
  void DestroyBackBuffers() { ++destroys; m_backBuffers.clear(); }
  HRESULT CreateBackBuffers(uint32_t, DWORD);
};
#include "d3d9_production.inc"

void paired(bool enabled, D3DMULTISAMPLE_TYPE type, DWORD quality) {
  D3D9DeviceEx device; device.options.forceSingleSample = enabled;
  device.options.extraFrontbuffer = true;
  D3D9SwapChainEx chain{&device, {}, {}};
  chain.m_presentParams.MultiSampleType = type; chain.m_presentParams.MultiSampleQuality = quality;
  assert(chain.CreateBackBuffers(2, 0) == D3D_OK);
  D3D9_COMMON_TEXTURE_DESC depth;
  assert(auto_depth(&device, &chain.m_presentParams, &depth) == D3D_OK);
  VkSampleCountFlagBits requested, dsSamples;
  assert(DecodeMultiSampleType(type, quality, &requested) == D3D_OK);
  assert(DecodeMultiSampleType(depth.MultiSample, depth.MultisampleQuality, &dsSamples) == D3D_OK);
  assert(chain.m_backBuffers.size() == 3 && device.context.initialized == 3);
  for (const auto& surface : chain.m_backBuffers) {
    const auto& image = surface->GetCommonTexture()->GetImage()->info;
    assert(image.sampleCount == (enabled ? 1u : requested));
    assert(depth_binds(image, {{depth.Width, depth.Height}, dsSamples}));
  }
  for (const auto& desc : device.created) {
    assert(desc.IsLockable && desc.IsBackBuffer && !desc.IsAttachmentOnly);
    assert(desc.MultiSample == (enabled ? D3DMULTISAMPLE_NONE : type));
    assert(desc.MultisampleQuality == (enabled ? 0u : quality));
  }
  // Original presentation parameters remain available for reset/auto-depth validation.
  assert(chain.m_presentParams.MultiSampleType == type && chain.m_presentParams.MultiSampleQuality == quality);
  if (enabled && requested > 1) assert(device.adapter.checked.front() == D3DMULTISAMPLE_TYPE(requested));
}

int main(int argc, char** argv) {
  assert(argc == 2); std::string name = argv[1];
  if (name == "forced_msaa_depth") paired(true, D3DMULTISAMPLE_TYPE(4), 0);
  else if (name == "default_msaa_depth") paired(false, D3DMULTISAMPLE_TYPE(4), 0);
  else if (name == "forced_nonmaskable") paired(true, D3DMULTISAMPLE_NONMASKABLE, 2);
  else if (name == "default_nonmaskable") paired(false, D3DMULTISAMPLE_NONMASKABLE, 2);
  else if (name == "forced_already_single") paired(true, D3DMULTISAMPLE_NONE, 0);
  else if (name == "forced_no_depth" || name == "forced_lockable_single") {
    D3D9DeviceEx d; d.options.forceSingleSample = true;
    D3D9SwapChainEx c{&d, {}, {}};
    if (name == "forced_lockable_single") c.m_presentParams.MultiSampleType = D3DMULTISAMPLE_NONE;
    assert(c.CreateBackBuffers(1, name == "forced_lockable_single" ? D3DPRESENTFLAG_LOCKABLE_BACKBUFFER : 0) == D3D_OK);
    assert(c.m_backBuffers.size() == 1 && d.created[0].MultiSample == D3DMULTISAMPLE_NONE);
    assert(d.created[0].IsLockable && d.context.initialized == 1);
  } else if (name == "forced_unsupported" || name == "forced_invalid_type" || name == "forced_lockable_msaa") {
    D3D9DeviceEx d; d.options.forceSingleSample = true;
    D3D9SwapChainEx c{&d, {}, {}};
    if (name == "forced_unsupported") d.adapter.supported = false;
    if (name == "forced_invalid_type") c.m_presentParams.MultiSampleType = D3DMULTISAMPLE_TYPE(17);
    HRESULT expected = name == "forced_unsupported" ? D3DERR_NOTAVAILABLE : D3DERR_INVALIDCALL;
    assert(c.CreateBackBuffers(2, name == "forced_lockable_msaa" ? D3DPRESENTFLAG_LOCKABLE_BACKBUFFER : 0) == expected);
    assert(d.attempts == 0 && d.losable == 0 && d.injected == 0 && c.m_backBuffers.empty());
  } else if (name == "forced_allocation_failure" || name == "default_allocation_failure") {
    D3D9DeviceEx d; d.options.forceSingleSample = name == "forced_allocation_failure"; d.failAt = 2;
    D3D9SwapChainEx c{&d, {}, {}};
    assert(c.CreateBackBuffers(3, 0) == D3DERR_OUTOFVIDEOMEMORY);
    assert(d.attempts == 2 && d.losable == 1 && d.live == 0 && d.injected == 0 && c.m_backBuffers.empty());
  } else if (name == "forced_recreate") {
    D3D9DeviceEx d; d.options.forceSingleSample = true;
    D3D9SwapChainEx c{&d, {}, {}};
    assert(c.CreateBackBuffers(3, 0) == D3D_OK);
    c.m_presentParams.MultiSampleType = D3DMULTISAMPLE_TYPE(8);
    assert(c.CreateBackBuffers(1, 0) == D3D_OK);
    assert(d.live == 1 && c.m_backBuffers.size() == 1 && c.destroys == 2);
    assert(d.adapter.checked.size() == 2 && d.adapter.checked.back() == D3DMULTISAMPLE_TYPE(8));
    assert(c.m_backBuffers[0]->GetCommonTexture()->GetImage()->info.sampleCount == 1);
  } else if (name == "ordinary_surface" || name == "ordinary_invalid_lockable") {
    D3D9DeviceEx d; d.options.forceSingleSample = true;
    D3D9_COMMON_TEXTURE_DESC desc; desc.MultiSample = D3DMULTISAMPLE_TYPE(4);
    desc.IsLockable = name == "ordinary_invalid_lockable";
    assert(D3D9CommonTexture::NormalizeTextureProperties(&d, D3DRTYPE_SURFACE, &desc)
           == (desc.IsLockable ? D3DERR_INVALIDCALL : D3D_OK));
    if (!desc.IsLockable) assert(desc.MultiSample == D3DMULTISAMPLE_NONE && desc.MultisampleQuality == 0);
  } else assert(false);
  return 0;
}
