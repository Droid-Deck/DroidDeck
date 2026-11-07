// Verifies the port's install path against the real published runtime image.
//
// The fixture in unpack_test.cpp proves the logic works on a small image.
// This test proves it works on the image a phone actually downloads: the
// upstream linuxfs release, which is a 291 MB zstd-compressed tar with 26,022
// entries, 4,640 symlinks and 117 hard links.
//
// It also pins the fact that the published image does NOT contain the guest
// session script, because upstream bakes its overlay into the image at build
// time. The port applies that overlay itself, and this is the test that
// proves the ported runtime is actually usable.
//
//   tools/verify_upstream_runtime.sh

#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
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

}  // namespace

int main(int argc, char **argv) {
  if (argc < 4) {
    std::printf("usage: real_runtime_test <runtime.tar.zst> <destination> <overlay.tar.gz> [sha256]\n");
    return 2;
  }
  const std::string archive = argv[1];
  const std::string destination = argv[2];
  const std::string overlay = argv[3];
  const std::string expected = argc > 4 ? std::string(argv[4]) : std::string();

  // The catalog publishes a checksum, and the port refuses to install an
  // archive that does not match it.
  if (!expected.empty()) {
    const std::string digest = ddk::Sha256File(archive);
    Check(digest == expected, "the published image matches the catalog's checksum");
    Check(ddk::VerifySha256(archive, expected), "the checksum is accepted");
    Check(!ddk::VerifySha256(archive, std::string(64, '0')), "a wrong checksum is rejected");
  }

  const ddk::UnpackResult unpacked = ddk::UnpackArchive(archive, destination);
  Check(unpacked.ok, "the published image unpacks");
  Check(unpacked.error.empty(), "the unpack reports no error");
  Check(unpacked.files > 20000, "every entry of the published image is restored");

  // The markers the supervisor looks for.
  Check(IsFile(destination + "/usr/games/gamescope"), "gamescope is unpacked");
  Check(IsDirectory(destination + "/etc/droiddeck/empty"), "the empty stub is unpacked");
  Check(IsFile(destination + "/etc/droiddeck/proc/version"), "the procfs copies are unpacked");
  Check(IsDirectory(destination + "/usr/share/vulkan/icd.d"), "the Vulkan ICD directory is unpacked");

  // The published image has no session script: upstream bakes its overlay
  // into the image at build time and publishes the image without it.
  Check(!IsFile(destination + "/usr/local/bin/droiddeck-session"),
        "the published image carries no session script");

  ddk::RuntimeOptions options;
  options.rootDir = destination;
  options.command = "/usr/local/bin/droiddeck-session";
  Check(!ddk::Runtime::Instance().StatusFor(options).installed,
        "the image is not usable before the overlay is applied");

  // The overlay is what makes the runtime usable.
  const ddk::UnpackResult applied = ddk::ApplyOverlay(overlay, destination);
  Check(applied.ok, "the overlay is applied to the published image");
  Check(IsFile(destination + "/usr/local/bin/droiddeck-session"),
        "the overlay brings the guest session script");
  Check(IsExecutable(destination + "/usr/local/bin/droiddeck-session"),
        "the session script stays executable");

  Check(ddk::PrepareRootfs(destination), "the rootfs is prepared");
  Check(IsFile(destination + "/etc/droiddeck/proc/file-max"), "file-max is seeded");

  Check(ddk::WriteVersionMarker(destination, "ubuntu-r1-rc4"), "the version marker is written");
  Check(ddk::ReadVersionMarker(destination) == "ubuntu-r1-rc4", "the version marker is read back");

  const ddk::RuntimeStatus status = ddk::Runtime::Instance().StatusFor(options);
  Check(status.installed, "the supervisor considers the runtime installed");
  Check(status.version == "ubuntu-r1-rc4", "the supervisor reports the version");
  Check(status.sizeBytes > 0, "the supervisor measures the runtime");

  if (g_failures == 0) {
    std::printf("real runtime tests: all checks passed\n");
    return 0;
  }
  std::printf("real runtime tests: %d check(s) failed\n", g_failures);
  return 1;
}
