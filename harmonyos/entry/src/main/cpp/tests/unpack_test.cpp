// End-to-end test of the runtime install path.
//
// The port's unpacker has to produce exactly the layout the supervisor
// expects, otherwise the launcher reports "runtime not installed" even
// though the download succeeded.
//
// The flow mirrors what a phone actually does:
//
//   1. download the published runtime image (which does NOT contain the
//      session script, because upstream bakes its overlay into the image at
//      build time and publishes the image without it);
//   2. check the download against the catalog's checksum;
//   3. unpack it;
//   4. apply the port's overlay, which adds /usr/local/bin/droiddeck-session;
//   5. write the version marker.
//
// Only after step 4 does the supervisor agree that the runtime is usable.
//
//   tools/test_native_host.sh

#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>

#include "runtime/install.h"
#include "runtime/runtime.h"
#include "runtime/unpack.h"

namespace {

int g_failures = 0;

void Check(bool condition, const std::string &what) {
  if (!condition) {
    std::printf("FAIL: %s\n", what.c_str());
    g_failures++;
  }
}

bool IsFile(const std::string &path) {
  struct stat info;
  return stat(path.c_str(), &info) == 0 && S_ISREG(info.st_mode);
}

bool IsDirectory(const std::string &path) {
  struct stat info;
  return stat(path.c_str(), &info) == 0 && S_ISDIR(info.st_mode);
}

bool IsExecutable(const std::string &path) {
  struct stat info;
  return stat(path.c_str(), &info) == 0 && (info.st_mode & S_IXUSR) != 0;
}

std::string ReadFile(const std::string &path) {
  std::ifstream file(path);
  if (!file.good()) {
    return std::string();
  }
  std::string content((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
  return content;
}

/** The status the supervisor reports for a rootfs. */
ddk::RuntimeStatus StatusFor(const std::string &root) {
  ddk::RuntimeOptions options;
  options.rootDir = root;
  options.command = "/usr/local/bin/droiddeck-session";
  return ddk::Runtime::Instance().StatusFor(options);
}

}  // namespace

int main(int argc, char **argv) {
  if (argc < 3) {
    std::printf("usage: unpack_test <runtime.tar.gz> <destination> [overlay.tar.gz] [smoke.tar.gz]\n");
    return 2;
  }
  const std::string archive = argv[1];
  const std::string destination = argv[2];
  const std::string overlay = argc > 3 ? std::string(argv[3]) : std::string();
  const std::string smoke = argc > 4 ? std::string(argv[4]) : std::string();

  // The checksum has to match the catalog's digest, and a wrong digest has
  // to be rejected.
  const std::string digest = ddk::Sha256File(archive);
  Check(digest.size() == 64, "the archive's checksum is computed");
  Check(ddk::VerifySha256(archive, digest), "the archive matches its checksum");
  Check(!ddk::VerifySha256(archive, std::string(64, 'a')), "a wrong checksum is rejected");
  Check(!ddk::VerifySha256(archive, digest.substr(0, 32)), "a truncated checksum is rejected");

  const ddk::UnpackResult result = ddk::UnpackArchive(archive, destination);
  Check(result.ok, "the archive unpacks");
  Check(result.error.empty(), "the unpack reports no error");
  Check(result.files > 0, "the unpack wrote files");

  // The layout the supervisor looks for.
  Check(IsFile(destination + "/usr/games/gamescope"), "gamescope is unpacked");
  Check(IsDirectory(destination + "/etc/droiddeck/empty"), "the empty stub directory is unpacked");
  Check(IsDirectory(destination + "/usr/share/vulkan/icd.d"), "the Vulkan ICD directory is unpacked");

  // The published image does not carry the session script: upstream bakes
  // its overlay into the image at build time, so the runtime the port
  // downloads is unusable until the overlay is applied.
  Check(!IsFile(destination + "/usr/local/bin/droiddeck-session"),
        "the published image has no session script yet");
  Check(!StatusFor(destination).installed,
        "the supervisor does not call the image installed before the overlay");

  // Symlinks have to survive the round trip, because the guest rootfs is
  // full of them. The published image contains 4640 of them.
  Check(IsFile(destination + "/usr/games/gamescope-link"), "a symlink is restored as a link target");
  // The image also contains 117 hard links, which the unpacker restores or
  // copies, depending on what the filesystem allows.
  Check(IsFile(destination + "/usr/games/gamescope-hard"), "a hard link is restored");

  // The fake procfs copies are seeded, so the guest can read the files the
  // app sandbox hides.
  Check(ddk::PrepareRootfs(destination), "the rootfs is prepared");
  Check(IsFile(destination + "/etc/droiddeck/proc/file-max"), "file-max is seeded");
  Check(IsFile(destination + "/etc/droiddeck/proc/overcommit_memory"), "overcommit_memory is seeded");
  Check(IsFile(destination + "/etc/droiddeck/proc/pci_devices"), "pci_devices is seeded");

  // The overlay adds the session script, which is what makes the runtime
  // usable.
  if (!overlay.empty()) {
    const ddk::UnpackResult applied = ddk::ApplyOverlay(overlay, destination);
    Check(applied.ok, "the overlay is applied");
    Check(IsFile(destination + "/usr/local/bin/droiddeck-session"),
          "the overlay brings the guest session script");
    Check(IsExecutable(destination + "/usr/local/bin/droiddeck-session"),
          "the session script stays executable");
    Check(StatusFor(destination).installed,
          "the supervisor considers the runtime installed once the overlay is applied");

    // The app asks about the runtime before it has ever started a session, so
    // the status has to be able to see an installed runtime from the root
    // directory alone. This is the check that says whether the runtime is
    // installed, and the session cannot start without it.
    Check(ddk::Runtime::Instance().StatusAt(destination, false).installed,
          "the status reports an installed runtime for a root it is given");
    Check(ddk::Runtime::Instance().StatusAt(destination, false).rootDir == destination,
          "the status reports the root it was given");
    Check(!ddk::Runtime::Instance().StatusAt("", false).installed,
          "the status reports nothing installed without a root");
  }

  // The version marker is written the way upstream writes it.
  Check(ddk::WriteVersionMarker(destination, "ubuntu-r1-rc4"), "the version marker is written");
  Check(ReadFile(destination + "/.version") == "ubuntu-r1-rc4\n", "the version marker has the version");
  Check(ddk::ReadVersionMarker(destination) == "ubuntu-r1-rc4", "the version marker is read back");
  Check(StatusFor(destination).version == "ubuntu-r1-rc4", "the supervisor reports the version");

  // The real smoke artifact, when it has been built, has to install the
  // same way: that artifact is what a phone downloads first.
  if (!smoke.empty()) {
    const std::string smokeDestination = destination + "-smoke";
    const ddk::UnpackResult smokeResult = ddk::UnpackArchive(smoke, smokeDestination);
    Check(smokeResult.ok, "the smoke artifact unpacks");
    Check(ddk::PrepareRootfs(smokeDestination), "the smoke artifact is prepared");
    if (!overlay.empty()) {
      Check(ddk::ApplyOverlay(overlay, smokeDestination).ok,
            "the overlay is applied to the smoke artifact");
    }
    Check(ddk::WriteVersionMarker(smokeDestination, "smoke"), "the smoke artifact gets a version");
    Check(StatusFor(smokeDestination).installed,
          "the supervisor considers the smoke artifact installed");
    Check(IsFile(smokeDestination + "/usr/local/bin/droiddeck-session"),
          "the smoke artifact carries the guest session");
  }

  if (g_failures == 0) {
    std::printf("unpack tests: all checks passed\n");
    return 0;
  }
  std::printf("unpack tests: %d check(s) failed\n", g_failures);
  return 1;
}
