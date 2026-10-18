import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "linuxfs/fex/vulkanpin.c"
THUNK = r"""
#include <string.h>
typedef struct { char extensionName[256]; unsigned int specVersion; } VkExtensionProperties;
typedef void (*Function)(void);
static const char *names[] = {"VK_KHR_surface", "VK_KHR_display", "VK_KHR_xlib_surface", "VK_EXT_direct_mode_display",
                              "VK_KHR_get_display_properties2", "VK_EXT_swapchain_colorspace"};

int vkEnumerateInstanceExtensionProperties(const char *layer, unsigned int *count, VkExtensionProperties *properties)
{
    unsigned int total = layer ? 0 : sizeof(names) / sizeof(names[0]);
    if (!properties) {
        *count = total;
        return 0;
    }
    unsigned int n = *count < total ? *count : total;
    for (unsigned int i = 0; i < n; i++) {
        memset(&properties[i], 0, sizeof(properties[i]));
        strcpy(properties[i].extensionName, names[i]);
        properties[i].specVersion = i + 1;
    }
    *count = n;
    return n < total ? 5 : 0;
}

int vkCreateInstance(void) { return 42; }

Function vkGetInstanceProcAddr(void *instance, const char *name)
{
    (void)instance;
    if (!strcmp(name, "vkEnumerateInstanceExtensionProperties"))
        return (Function)vkEnumerateInstanceExtensionProperties;
    if (!strcmp(name, "vkCreateInstance"))
        return (Function)vkCreateInstance;
    if (!strcmp(name, "vkGetInstanceProcAddr"))
        return (Function)vkGetInstanceProcAddr;
    return 0;
}
"""
GAME = r"""
#include <dlfcn.h>
#include <stdio.h>
typedef struct { char extensionName[256]; unsigned int specVersion; } VkExtensionProperties;
typedef void (*Function)(void);
typedef int (*Enumerate)(const char *, unsigned int *, VkExtensionProperties *);
typedef Function (*ProcAddr)(void *, const char *);
#ifdef LINKED
int vkCreateInstance(void);
Function vkGetInstanceProcAddr(void *, const char *);
#endif

static void list(Enumerate enumerate)
{
    VkExtensionProperties found[8];
    unsigned int count = 0;
    int result = enumerate(0, &count, 0);
    printf("count %u %d\n", count, result);
    enumerate(0, &count, found);
    for (unsigned int i = 0; i < count; i++)
        printf("%s %u\n", found[i].extensionName, found[i].specVersion);
    count = 1;
    result = enumerate(0, &count, found);
    printf("short %u %d %s\n", count, result, found[0].extensionName);
}

int main(void)
{
#ifdef LINKED
    ProcAddr proc = vkGetInstanceProcAddr;
    printf("linked %d\n", vkCreateInstance());
#else
    void *vulkan = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!vulkan)
        return 1;
    ProcAddr proc = (ProcAddr)dlsym(vulkan, "vkGetInstanceProcAddr");
    printf("same handle %d\n", dlopen("libvulkan.so", RTLD_NOW | RTLD_NOLOAD) == vulkan);
    printf("create %d\n", ((int (*)(void))dlsym(vulkan, "vkCreateInstance"))());
    list((Enumerate)dlsym(vulkan, "vkEnumerateInstanceExtensionProperties"));
#endif
    printf("same proc %d create %d missing %d\n", proc(0, "vkGetInstanceProcAddr") == (Function)proc,
           ((int (*)(void))proc(0, "vkCreateInstance"))(), proc(0, "vkNothing") == 0);
    list((Enumerate)proc(0, "vkEnumerateInstanceExtensionProperties"));
    return 0;
}
"""
LISTED = """count 3 0
VK_KHR_surface 1
VK_KHR_xlib_surface 3
VK_EXT_swapchain_colorspace 6
short 1 5 VK_KHR_surface
"""


@unittest.skipUnless(shutil.which("gcc") and os.uname().machine == "x86_64", "needs gcc on an x86-64 host")
class VulkanPinTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def gcc(self, *args):
        made = subprocess.run(["gcc"] + [str(a) for a in args], capture_output=True, text=True)
        self.assertEqual(made.returncode, 0, made.stderr)

    def test_display_extensions_are_hidden_in_front_of_the_thunk(self):
        thunk = self.tmp / "usr/lib/libvulkan.so.1"
        thunk.parent.mkdir(parents=True)
        (self.tmp / "thunk.c").write_text(THUNK)
        self.gcc("-shared", "-fPIC", "-Wl,-soname,libvulkan.so.1", "-o", thunk, self.tmp / "thunk.c")
        stub = self.tmp / "stub/libvulkan.so.1"
        stub.parent.mkdir()
        self.gcc("-shared", "-nostdlib", "-Wl,-soname," + str(thunk), "-o", stub, "-x", "c", "/dev/null")
        pinned = self.tmp / "pin"
        pinned.mkdir()
        self.gcc("-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-Werror", "-fvisibility=hidden", '-DVULKAN_THUNK="%s"' % thunk,
                 "-Wl,-soname,libvulkan.so.1", "-Wl,-z,nodelete", "-Wl,--no-as-needed", "-o", pinned / "libvulkan.so.1",
                 SOURCE, stub, "-ldl")
        os.symlink("libvulkan.so.1", pinned / "libvulkan.so")
        exported = subprocess.run(["nm", "-D", "--defined-only", pinned / "libvulkan.so.1"], capture_output=True, text=True)
        self.assertEqual(sorted(line.split()[-1] for line in exported.stdout.splitlines()),
                         ["vkEnumerateInstanceExtensionProperties", "vkGetInstanceProcAddr"])
        (self.tmp / "game.c").write_text(GAME)
        env = dict(os.environ, LD_LIBRARY_PATH=str(pinned))
        self.gcc("-o", self.tmp / "loader", self.tmp / "game.c", "-ldl")
        run = subprocess.run([self.tmp / "loader"], capture_output=True, text=True, env=env)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "same handle 1\ncreate 42\n" + LISTED + "same proc 1 create 42 missing 1\n" + LISTED)
        self.gcc("-DLINKED", "-o", self.tmp / "linked", self.tmp / "game.c", "-L", thunk.parent, "-l:libvulkan.so.1", "-ldl")
        run = subprocess.run([self.tmp / "linked"], capture_output=True, text=True, env=env)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout, "linked 42\nsame proc 1 create 42 missing 1\n" + LISTED)


if __name__ == "__main__":
    unittest.main()
