#include "sdk_loader.h"
#include <dlfcn.h>
#include <cstdio>
#include <cstring>

bool VitureSdkLoader::load(const std::string& directory, std::string& error, const std::string& expected_version) {
    if (loaded()) {
        if (!expected_version.empty() && expected_version != api.GetVersionString()) {
            error = "The active VITURE SDK does not match the imported version";
            return false;
        }
        if (directory != loaded_directory) {
            error = "A different VITURE SDK is already loaded; restart the app before loading another SDK directory";
            return false;
        }
        error.clear();
        return true;
    }
    VitureSdkApi candidate;
    std::vector<void*> pending;
    auto fail = [&](const std::string& reason) {
        error = reason;
        for (auto it = pending.rbegin(); it != pending.rend(); ++it) dlclose(*it);
        return false;
    };
    for (const char* name : {"libcloud_protocol.so", "libcarina_vio.so", "libglasses.so"}) {
        const auto path = directory.empty() ? std::string(name) : directory + "/" + name;
        auto handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!handle) {
            const char* detail = dlerror();
            return fail("Cannot load " + std::string(name) + ": " + (detail ? detail : "unknown error"));
        }
        pending.push_back(handle);
    }
    auto provider = pending.back();
#define VITURE_RESOLVE(result, name, arguments) \
    candidate.name = reinterpret_cast<decltype(candidate.name)>(dlsym(provider, #name)); \
    if (!candidate.name) return fail("Missing VITURE SDK function: " #name);
    VITURE_SDK_FUNCTIONS(VITURE_RESOLVE)
#undef VITURE_RESOLVE
#define VITURE_RESOLVE_OPTIONAL(result, name, arguments) \
    candidate.name = reinterpret_cast<decltype(candidate.name)>(dlsym(provider, #name));
    VITURE_SDK_OPTIONAL_FUNCTIONS(VITURE_RESOLVE_OPTIONAL)
#undef VITURE_RESOLVE_OPTIONAL
    const char* version = candidate.GetVersionString();
    int major = -1, minor = -1, patch = -1, length = 0;
    if (!version || std::sscanf(version, "%d.%d.%d%n", &major, &minor, &patch, &length) != 3 ||
        version[length] != '\0' || major != 2 || minor != 4 || patch < 0)
        return fail("Unsupported VITURE SDK version; expected 2.4.x");
    if (!expected_version.empty() && expected_version != version)
        return fail("The VITURE SDK libraries do not match the imported version header");
    api = candidate;
    // Vendor callbacks may outlive a session; loaded code stays resident for this process.
    handles = std::move(pending);
    loaded_directory = directory;
    error.clear();
    return true;
}
