#include "../../main/cpp/viture/sdk_loader.h"
#include "../../main/cpp/viture/sdk_device_controls.h"
#include <dlfcn.h>
#include <array>
#include <cassert>
#include <cstdio>
#include <limits>
#include <string>
#include <vector>

namespace {
std::string missing_library;
std::string missing_symbol;
bool missing_optional = false;
const char* version = "2.4.0";
std::vector<std::string> opened;
std::vector<void*> closed;
std::vector<std::string> resolved;
std::array<int, 3> handles{};

const char* sdk_version() { return version; }
int unused_function() { return 0; }

void reset() {
    missing_library.clear();
    missing_symbol.clear();
    missing_optional = false;
    version = "2.4.0";
    opened.clear();
    closed.clear();
    resolved.clear();
}

const char* const functions[] = {
#define FUNCTION_NAME(result, name, arguments) #name,
    VITURE_SDK_FUNCTIONS(FUNCTION_NAME)
#undef FUNCTION_NAME
};

const char* const optional_functions[] = {
#define FUNCTION_NAME(result, name, arguments) #name,
    VITURE_SDK_OPTIONAL_FUNCTIONS(FUNCTION_NAME)
#undef FUNCTION_NAME
};

void expect_unloaded(const VitureSdkLoader& loader) {
    assert(!loader.loaded());
#define EXPECT_EMPTY(result, name, arguments) assert(!loader.api.name);
    VITURE_SDK_FUNCTIONS(EXPECT_EMPTY)
    VITURE_SDK_OPTIONAL_FUNCTIONS(EXPECT_EMPTY)
#undef EXPECT_EMPTY
}

void expect_closed(size_t count) {
    assert(closed.size() == count);
    for (size_t i = 0; i < count; ++i) assert(closed[i] == &handles[count - i - 1]);
}
}

extern "C" void* dlopen(const char* path, int flags) {
    assert(flags == (RTLD_NOW | RTLD_LOCAL));
    const size_t index = opened.size();
    assert(index < handles.size());
    opened.emplace_back(path);
    if (!missing_library.empty() && opened.back().find(missing_library) != std::string::npos) return nullptr;
    return &handles[index];
}

extern "C" void* dlsym(void* handle, const char* name) {
    assert(handle == &handles[2]);
    resolved.emplace_back(name);
    if (name == missing_symbol) return nullptr;
    if (missing_optional) for (const char* optional : optional_functions)
        if (std::string(optional) == name) return nullptr;
    if (resolved.back() == "GetVersionString") return reinterpret_cast<void*>(&sdk_version);
    return reinterpret_cast<void*>(&unused_function);
}

extern "C" int dlclose(void* handle) {
    closed.push_back(handle);
    return 0;
}

extern "C" char* dlerror() {
    static char error[] = "fixture library unavailable";
    return error;
}

int main() {
    const std::array<const char*, 3> libraries = {"libcloud_protocol.so", "libcarina_vio.so", "libglasses.so"};
    std::string error;
    for (size_t i = 0; i < libraries.size(); ++i) {
        reset();
        VitureSdkLoader loader;
        missing_library = libraries[i];
        assert(!loader.load("/private/sdk", error));
        assert(error.find(libraries[i]) != std::string::npos);
        expect_unloaded(loader);
        expect_closed(i);
        reset();
        assert(loader.load("/private/sdk", error));
        assert(error.empty());
        assert(loader.loaded());
    }
    for (const char* function : functions) {
        reset();
        VitureSdkLoader loader;
        missing_symbol = function;
        assert(!loader.load("/private/sdk", error));
        assert(error.find(function) != std::string::npos);
        expect_unloaded(loader);
        expect_closed(3);
        reset();
        assert(loader.load("/private/sdk", error));
        assert(error.empty());
    }
    for (const char* function : optional_functions) {
        reset();
        VitureSdkLoader loader;
        missing_symbol = function;
        assert(loader.load("/private/sdk", error));
        assert(error.empty());
        assert(closed.empty());
#define EXPECT_OPTIONAL(result, name, arguments) \
        assert((loader.api.name == nullptr) == (std::string(#name) == function));
        VITURE_SDK_OPTIONAL_FUNCTIONS(EXPECT_OPTIONAL)
#undef EXPECT_OPTIONAL
    }
    reset();
    {
        VitureSdkLoader legacy;
        missing_optional = true;
        assert(legacy.load("/private/sdk", error));
        assert(std::string(legacy.api.GetVersionString()) == "2.4.0");
#define EXPECT_EMPTY_OPTIONAL(result, name, arguments) assert(!legacy.api.name);
        VITURE_SDK_OPTIONAL_FUNCTIONS(EXPECT_EMPTY_OPTIONAL)
#undef EXPECT_EMPTY_OPTIONAL
        assert(closed.empty());
    }
    for (const char* unsupported : std::array<const char*, 7>{nullptr, "", "2.3.0", "3.4.0", "2.4.-1", "2.4.0-extra", "2.4"}) {
        reset();
        VitureSdkLoader loader;
        version = unsupported;
        assert(!loader.load("/private/sdk", error));
        assert(error.find("version") != std::string::npos);
        expect_unloaded(loader);
        expect_closed(3);
        reset();
        assert(loader.load("/private/sdk", error));
    }
    for (const char* supported : {"2.4.0", "2.4.2", "2.4.3"}) {
        reset();
        VitureSdkLoader loader;
        version = supported;
        assert(loader.load("/private/sdk", error));
        assert(std::string(loader.api.GetVersionString()) == supported);
        assert(resolved.size() == std::size(functions) + std::size(optional_functions));
        assert(closed.empty());
        for (size_t i = 0; i < libraries.size(); ++i)
            assert(opened[i] == std::string("/private/sdk/") + libraries[i]);
        const auto first_functions = loader.api;
        missing_library = "libglasses.so";
        assert(loader.load("/different/sdk", error));
        assert(opened.size() == 3);
        assert(closed.empty());
        assert(first_functions.xr_device_provider_create == loader.api.xr_device_provider_create);
    }
    reset();
    {
        VitureSdkLoader mixed;
        assert(!mixed.load("/private/sdk", error, "2.4.2"));
        assert(error.find("version header") != std::string::npos);
        expect_unloaded(mixed);
        expect_closed(3);
        reset();
        version = "2.4.2";
        assert(mixed.load("/private/sdk", error, "2.4.2"));
        assert(error.empty());
        assert(!mixed.load("/different/sdk", error, "2.4.0"));
        assert(mixed.loaded());
        assert(opened.size() == 3);
        assert(closed.empty());
        assert(mixed.load("/private/sdk", error, "2.4.2"));
        assert(error.empty());
    }
    reset();
    VitureSdkLoader bundled;
    assert(bundled.load("", error));
    for (size_t i = 0; i < libraries.size(); ++i) assert(opened[i] == libraries[i]);

    using droiddeck::viture::supports_film;
    assert(!supports_film("Lite"));
    assert(!supports_film("Pro 2"));
    for (const char* market_name : {"One", "Pro", "Luma", "Luma Pro", "Luma Cyber", "Luma Ultra", "Beast", "Phantom Beast"})
        assert(supports_film(market_name));

    using droiddeck::viture::valid_device_control;
    assert(valid_device_control(1, 0.125f, 0));
    assert(valid_device_control(1, 1, 0));
    assert(!valid_device_control(1, 1.01f, 0));
    assert(valid_device_control(2, 100, 0));
    assert(!valid_device_control(2, 101, 0));
    assert(!valid_device_control(2, 42.5f, 0));
    assert(valid_device_control(3, 0, 0x35));
    assert(!valid_device_control(3, 0, 0x33));
    assert(valid_device_control(3, 1, 0x3d));
    assert(!valid_device_control(3, 1, 0x38));
    assert(valid_device_control(4, 2, 0));
    assert(!valid_device_control(4, 3, 0));
    assert(valid_device_control(6, 0x40, 0));
    assert(!valid_device_control(6, 0x42, 0));
    assert(valid_device_control(7, 1, 0));
    assert(valid_device_control(7, 10, 0));
    assert(!valid_device_control(7, 0, 0));
    assert(!valid_device_control(7, 11, 0));
    assert(valid_device_control(8, 4, 0));
    assert(!valid_device_control(8, 5, 0));
    assert(valid_device_control(9, 1, 0));
    assert(!valid_device_control(9, 2, 0));
    assert(valid_device_control(10, 0x44, 0));
    assert(!valid_device_control(10, 0xff, 0));
    assert(valid_device_control(11, 1, 0));
    assert(!valid_device_control(11, 2, 0));
    assert(valid_device_control(13, 0.01f, 0));
    assert(valid_device_control(13, 8, 15));
    assert(!valid_device_control(13, 0, 0));
    assert(!valid_device_control(13, 8.1f, 15));
    assert(!valid_device_control(13, 1, 16));
    assert(valid_device_control(15, 5, 0));
    assert(!valid_device_control(15, 6, 0));
    for (int command : {5, 12, 14, 16}) {
        assert(valid_device_control(command, 0, 0));
        assert(!valid_device_control(command, 1, 0));
    }
    for (int command = 1; command <= 16; ++command) {
        assert(!valid_device_control(command, std::numeric_limits<float>::quiet_NaN(), 0));
        assert(!valid_device_control(command, std::numeric_limits<float>::infinity(), 0));
        assert(!valid_device_control(command, -1, 0));
    }
    assert(!valid_device_control(17, 0, 0));
    std::puts("VITURE loader and device controls: required/optional symbols, SDK 2.4.0/2.4.2, recovery and input validation passed");
}
