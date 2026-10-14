// LSFG from Camille LaVey / the Eden Emulator Project, following upstream lsfg-vk (PancakeTAS).
// Ported to WinNative and DroidDeck by @maxjivi05. GPL-3.0-or-later.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace lsfg {

enum class DllStatus {
    Ok = 0,
    NotInstalled,
    UnreadableFile,
    NotPortableExecutable,
    MissingShaders,
    TranslationFailed,
    CacheUnusable
};

constexpr uint32_t kSpirv14 = 0x00010400u;
constexpr uint32_t kSpirv15 = 0x00010500u;
constexpr uint32_t kSpirv16 = 0x00010600u;

enum class Variant {
    None = 0,
    SpirvFp16,
    SpirvFp32,
    DxbcTranslated
};

constexpr uint32_t kShaderMipmaps   = 255u;
constexpr uint32_t kShaderGenerate  = 256u;
constexpr uint32_t kShaderChainFirst= 280u;
constexpr uint32_t kShaderChainLast = 302u;
constexpr uint32_t kShaderCount     = 25u;

constexpr uint32_t LSFG_SHADER_MIPMAPS  = kShaderMipmaps;
constexpr uint32_t LSFG_SHADER_GENERATE = kShaderGenerate;

struct Module {
    uint32_t              id = 0;
    std::vector<uint32_t> words;
};

struct ModuleSet {
    std::vector<Module> modules;
    Variant             variant = Variant::None;

    const std::vector<uint32_t>* find(uint32_t id) const;
    bool complete() const;
};

const std::vector<uint32_t>& shaderIds();

const char* statusName(DllStatus s);
const char* variantName(Variant v);

DllStatus validateDll(const std::string& dllPath);

DllStatus buildCache(const std::string& dllPath, const std::string& cachePath, bool preferFp16);

DllStatus loadModules(const std::string& cachePath, ModuleSet& outSet);

DllStatus cacheVariant(const std::string& cachePath, Variant& outVariant);

bool downgradeSpirv(std::vector<uint32_t>& words, uint32_t targetVersion);

}
