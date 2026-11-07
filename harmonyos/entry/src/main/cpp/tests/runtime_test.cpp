// Host-side tests for the ported guest-launch logic.
//
// The supervisor's argv is the part of the port that has to match upstream
// DroidDeck exactly: upstream's LinuxRuntime.prootPrefix and
// LinuxRuntime.binds decide how the guest sees the filesystem. These tests
// pin that behaviour so a refactor cannot quietly change it.
//
// They are plain C++ and run on the build machine, so no device is needed:
//
//   tools/test_native_host.sh

#include <sys/stat.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "runtime/runtime.h"

namespace {

int g_failures = 0;

void Check(bool condition, const std::string &what) {
  if (!condition) {
    std::printf("FAIL: %s\n", what.c_str());
    g_failures++;
  }
}

void CheckEquals(const std::string &actual, const std::string &expected, const std::string &what) {
  if (actual != expected) {
    std::printf("FAIL: %s (expected '%s', got '%s')\n", what.c_str(), expected.c_str(), actual.c_str());
    g_failures++;
  }
}

void CheckEqualsInt(int actual, int expected, const std::string &what) {
  if (actual != expected) {
    std::printf("FAIL: %s (expected %d, got %d)\n", what.c_str(), expected, actual);
    g_failures++;
  }
}

bool Contains(const std::vector<std::string> &items, const std::string &needle) {
  for (const std::string &item : items) {
    if (item == needle) {
      return true;
    }
  }
  return false;
}

bool ContainsSubstring(const std::string &haystack, const std::string &needle) {
  return haystack.find(needle) != std::string::npos;
}

int IndexOf(const std::vector<std::string> &items, const std::string &needle) {
  for (size_t index = 0; index < items.size(); ++index) {
    if (items[index] == needle) {
      return static_cast<int>(index);
    }
  }
  return -1;
}

int Count(const std::vector<std::string> &items, const std::string &needle) {
  int total = 0;
  for (const std::string &item : items) {
    if (item == needle) {
      total++;
    }
  }
  return total;
}

ddk::RuntimeOptions MakeOptions() {
  ddk::RuntimeOptions options;
  options.rootDir = "/data/storage/el2/base/haps/entry/files/runtime";
  options.command = "/usr/local/bin/droiddeck-session";
  options.args = {"steam", "440"};
  options.cwd = "/root";
  options.vulkanDriver = "/usr/share/vulkan/icd.d";
  options.vulkanIcd = "/usr/share/vulkan/icd.d/lvp_icd.json";
  options.env = {{"STEAM_APPID", "440"}};
  // The app's own directories, as the launcher passes them.
  options.hostFilesDir = "/data/storage/el2/base/haps/entry/files";
  options.hostCacheDir = "/data/storage/el2/base/haps/entry/cache";
  options.hostRuntimeDir = "/data/storage/el2/base/haps/entry/cache/xdg";
  return options;
}

void TestProotArgvMatchesUpstream() {
  const ddk::RuntimeOptions options = MakeOptions();
  const std::vector<std::string> argv =
      ddk::Runtime::Instance().BuildArgv(options, ddk::RuntimeMode::Proot);

  // Upstream builds: <proot> --kill-on-exit --kernel-release=... -0
  //                  -r <root> -w <cwd> -b <bind> ... <command> <args>
  CheckEquals(argv[0], options.rootDir + "/host/proot", "proot binary path");
  CheckEquals(argv[1], "--kill-on-exit", "upstream's --kill-on-exit flag");
  Check(Contains(argv, "-0"), "fake root flag (-0), as upstream's RootSpawner uses");
  Check(Contains(argv, "-r"), "root flag (-r)");
  Check(Contains(argv, "-w"), "working directory flag (-w)");
  CheckEquals(argv[IndexOf(argv, "-r") + 1], options.rootDir, "root argument");
  CheckEquals(argv[IndexOf(argv, "-w") + 1], "/root", "working directory argument");

  // The guest command is the last argument, followed by its arguments.
  CheckEquals(argv[argv.size() - 3], options.command, "guest command");
  CheckEquals(argv[argv.size() - 2], "steam", "first guest argument");
  CheckEquals(argv[argv.size() - 1], "440", "second guest argument");

  // Upstream runs the guest through `env -i` so the guest environment is
  // built from scratch.
  Check(Contains(argv, "/usr/bin/env"), "env reset");
  Check(Contains(argv, "-i"), "env -i flag");
}

void TestBindsMatchUpstream() {
  const ddk::RuntimeOptions options = MakeOptions();
  const std::vector<std::string> argv =
      ddk::Runtime::Instance().BuildArgv(options, ddk::RuntimeMode::Proot);

  // Every bind is passed as "-b <spec>".
  CheckEqualsInt(Count(argv, "-b"), 13, "number of binds");

  // The three device filesystems upstream always binds.
  Check(Contains(argv, "/dev"), "bind /dev");
  Check(Contains(argv, "/proc"), "bind /proc");
  Check(Contains(argv, "/sys"), "bind /sys");

  // The stdio descriptors, so the guest sees the same pipes as the app.
  Check(Contains(argv, "/proc/self/fd:/dev/fd"), "bind /dev/fd");
  Check(Contains(argv, "/proc/self/fd/0:/dev/stdin"), "bind /dev/stdin");
  Check(Contains(argv, "/proc/self/fd/1:/dev/stdout"), "bind /dev/stdout");
  Check(Contains(argv, "/proc/self/fd/2:/dev/stderr"), "bind /dev/stderr");

  // The empty directories that keep the guest's SELinux and thermal probes
  // from aborting, exactly as upstream does.
  Check(Contains(argv, options.rootDir + "/etc/droiddeck/empty:/sys/fs/selinux"), "selinux stub");
  Check(Contains(argv, options.rootDir + "/etc/droiddeck/empty:/sys/class/thermal"), "thermal stub");

  // The rootfs and the runtime directory: the guest sees the host's runtime
  // directory at /run/droiddeck, the path its session expects.
  Check(Contains(argv, options.rootDir), "rootfs bind");
  Check(Contains(argv, options.hostRuntimeDir + ":/run/droiddeck"), "runtime bind");

  // The app's own directories are bound at the same absolute path, the way
  // upstream binds them, so the session log the guest writes is readable.
  Check(Contains(argv, options.hostFilesDir), "the app's files directory is bound");
  Check(Contains(argv, options.hostCacheDir), "the app's cache directory is bound");

  // The Vulkan ICD directory is inside the rootfs, which is the guest's /,
  // so it needs no bind: the loader finds it there.
  Check(!Contains(argv, options.vulkanDriver), "the Vulkan ICD directory is not bound");
}

void TestTheGuestIsPinnedToAVulkanManifest() {
  // `VK_ICD_FILENAMES` names manifest files. Passing the directory would
  // be ignored by the loader, and the guest would be left to pick a driver
  // on its own — which on HarmonyOS means a driver that needs a GPU device
  // node the guest cannot have.
  const ddk::RuntimeOptions options = MakeOptions();
  const std::vector<std::string> argv =
      ddk::Runtime::Instance().BuildArgv(options, ddk::RuntimeMode::Proot);

  Check(Contains(argv, "VK_ICD_FILENAMES=" + options.vulkanIcd),
        "the guest is pinned to a Vulkan manifest");
  Check(Contains(argv, "VK_DRIVER_FILES=" + options.vulkanIcd),
        "the newer driver variable names the same manifest");
  Check(!Contains(argv, "VK_ICD_FILENAMES=" + options.vulkanDriver),
        "the ICD directory is not passed as a manifest");
}

void TestDirectModeUsesTheGuestLoader() {
  const ddk::RuntimeOptions options = MakeOptions();
  const std::vector<std::string> argv =
      ddk::Runtime::Instance().BuildArgv(options, ddk::RuntimeMode::Direct);

  // Without proot there is no path remapping, so the guest's own loader is
  // invoked with the rootfs on its library path.
  CheckEquals(argv[0], options.rootDir + "/lib/ld-linux-aarch64.so.1", "guest loader");
  CheckEquals(argv[1], "--library-path", "library path flag");
  Check(ContainsSubstring(argv[2], options.rootDir + "/lib:"), "library path points into the rootfs");
  CheckEquals(argv[3], options.rootDir + options.command, "guest command");
  CheckEquals(argv[argv.size() - 2], "steam", "first guest argument");
  CheckEquals(argv[argv.size() - 1], "440", "second guest argument");

  // Direct mode must not pretend to have proot's path rewriting.
  Check(!Contains(argv, "-b"), "direct mode has no binds");
  Check(!Contains(argv, "-r"), "direct mode has no root flag");
}

/**
 * A rootfs with a session script in it, for the direct-mode test.
 *
 * The published runtime carries no proot, so direct mode is the path the
 * port actually takes, and the guest's command is a shell script. This
 * rootfs merges /usr the way the published one does, so the interpreter
 * the script names only exists at /usr/bin/bash.
 */
std::string MakeScriptRoot() {
  const std::string root = "/tmp/droiddeck-runtime-test-root";
  mkdir(root.c_str(), 0755);
  mkdir((root + "/usr").c_str(), 0755);
  mkdir((root + "/usr/bin").c_str(), 0755);
  mkdir((root + "/usr/local").c_str(), 0755);
  mkdir((root + "/usr/local/bin").c_str(), 0755);

  FILE *file = fopen((root + "/usr/local/bin/droiddeck-session").c_str(), "w");
  if (file != nullptr) {
    fputs("#!/bin/bash\n", file);
    fclose(file);
  }
  file = fopen((root + "/usr/bin/bash").c_str(), "w");
  if (file != nullptr) {
    fputs("#!/bin/sh\n", file);
    fclose(file);
  }
  return root;
}

void TestDirectModeRunsAScriptWithItsInterpreter() {
  const std::string root = MakeScriptRoot();
  ddk::RuntimeOptions options = MakeOptions();
  options.rootDir = root;
  options.command = "/usr/local/bin/droiddeck-session";
  options.args = {"desktop"};

  const std::vector<std::string> argv =
      ddk::Runtime::Instance().BuildArgv(options, ddk::RuntimeMode::Direct);

  // The loader can only run ELF files, so the script is run by the
  // interpreter its "#!" line names, taken out of the rootfs.
  CheckEquals(argv[3], root + "/usr/bin/bash", "the interpreter from the shebang");
  CheckEquals(argv[4], root + "/usr/local/bin/droiddeck-session", "the script itself");  CheckEquals(argv[5], "desktop", "the first guest argument");
}

void TestModeNames() {
  CheckEquals(ddk::RuntimeModeName(ddk::RuntimeMode::Proot), "proot", "proot mode name");
  CheckEquals(ddk::RuntimeModeName(ddk::RuntimeMode::Direct), "direct", "direct mode name");
  CheckEquals(ddk::RuntimeModeName(ddk::RuntimeMode::Unsupported), "unsupported", "unsupported mode name");
}

}  // namespace

int main() {
  TestProotArgvMatchesUpstream();
  TestBindsMatchUpstream();
  TestTheGuestIsPinnedToAVulkanManifest();
  TestDirectModeUsesTheGuestLoader();
  TestDirectModeRunsAScriptWithItsInterpreter();
  TestModeNames();

  if (g_failures == 0) {
    std::printf("runtime tests: all checks passed\n");
    return 0;
  }
  std::printf("runtime tests: %d check(s) failed\n", g_failures);
  return 1;
}
