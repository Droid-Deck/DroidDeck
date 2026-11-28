#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>
#include <android/dlext.h>
#include <android_linker_ns.h>

extern "C" {
uint32_t __wrap_dlopen[64];
}

static std::vector<uintptr_t> protected_pages;
static std::vector<int> protected_flags;
static std::vector<std::string> loaded;
static const void *loader_caller;
static uintptr_t locked_page;
static bool deny_write_exec;
static int failures;

static void check(bool ok, const char *what) {
    if (!ok) {
        std::printf("FAIL %s\n", what);
        failures++;
    }
}

static uintptr_t page_of(const void *p) {
    return reinterpret_cast<uintptr_t>(p) & ~static_cast<uintptr_t>(getpagesize() - 1);
}

extern "C" __attribute__((aligned(16), noinline)) void *fake_loader_dlopen(const char *name, int, const void *caller) {
    loaded.emplace_back(name);
    loader_caller = caller;
    return reinterpret_cast<void *>(0x1000);
}

static void fake_linker_entry() {}

extern "C" int __wrap_mprotect(void *addr, size_t, int prot) {
    protected_pages.push_back(reinterpret_cast<uintptr_t>(addr));
    protected_flags.push_back(prot);
    if (reinterpret_cast<uintptr_t>(addr) == locked_page)
        return -1;
    if (deny_write_exec && (prot & PROT_WRITE) && (prot & PROT_EXEC))
        return -1;
    return 0;
}

extern "C" void *__wrap_dlsym(void *, const char *name) {
    return std::strncmp(name, "__loader_", 9) == 0 ? reinterpret_cast<void *>(&fake_linker_entry) : nullptr;
}

extern "C" void *android_dlopen_ext(const char *, int, const android_dlextinfo *) {
    return nullptr;
}

extern "C" bool elf_soname_patch(const char *, int, const char *) {
    return false;
}

static void place_bl(int index) {
    for (auto &word : __wrap_dlopen)
        word = 0xd503201f;
    auto from{reinterpret_cast<intptr_t>(&__wrap_dlopen[index])};
    auto to{reinterpret_cast<intptr_t>(&fake_loader_dlopen)};
    __wrap_dlopen[index] = 0x94000000u | (static_cast<uint32_t>((to - from) / 4) & 0x3ffffffu);
}

static bool page_protected(const void *p) {
    for (auto page : protected_pages)
        if (page == page_of(p))
            return true;
    return false;
}

int main(int argc, char **argv) {
    if (argc != 2)
        return 2;
    std::string mode{argv[1]};

    check(protected_pages.empty() && loaded.empty(), "linker internals touched while the library loaded");

    if (mode == "lazy") {
        place_bl(6);
        check(linkernsbypass_load_status(), "status after resolving");
        check(loaded.size() == 2 && loaded[0] == "ld-android.so" && loaded[1] == "libdl_android.so", "loader calls");
        check(loader_caller == __wrap_dlopen, "caller address is dlopen");
        check(page_protected(__wrap_dlopen), "dlopen page unguarded");
        check(page_protected(reinterpret_cast<const void *>(&fake_loader_dlopen)), "loader target page unguarded");
        check(android_link_namespaces && android_link_namespaces_all_libs && android_get_exported_namespace, "symbols resolved");
        auto calls{protected_pages.size()};
        check(linkernsbypass_load_status(), "status on second call");
        check(loaded.size() == 2 && protected_pages.size() == calls, "resolved only once");
    } else if (mode == "far") {
        place_bl(40);
        check(!linkernsbypass_load_status(), "status without a nearby branch");
        check(loaded.empty(), "loader not called without a nearby branch");
    } else if (mode == "locked") {
        place_bl(6);
        locked_page = page_of(reinterpret_cast<const void *>(&fake_loader_dlopen));
        check(!linkernsbypass_load_status(), "status with a locked target page");
        check(loaded.empty(), "loader not called on a locked target page");
        int attempts{};
        for (auto page : protected_pages)
            attempts += page == locked_page;
        check(attempts == 2, "target page tried with and without write");
    } else if (mode == "no_write_exec") {
        place_bl(6);
        deny_write_exec = true;
        check(linkernsbypass_load_status(), "status when write and exec together are refused");
        check(loaded.size() == 2, "loader calls when write and exec together are refused");
        check(protected_flags.back() == (PROT_READ | PROT_EXEC), "read and exec fallback");
    } else {
        return 2;
    }

    if (failures)
        return 1;
    std::printf("PASS %s\n", mode.c_str());
    return 0;
}
