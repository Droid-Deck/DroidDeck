#ifndef DDK_UNPACK_H
#define DDK_UNPACK_H

#include <string>

namespace ddk {

struct UnpackResult {
  bool ok = false;
  std::string error;
  long long files = 0;
  long long bytes = 0;
};

/**
 * Extracts a tar archive into a destination directory.
 *
 * The compression is detected from the file's magic number, so zstd
 * (`.tar.zst`, upstream's format), gzip (`.tar.gz`) and plain tar all work.
 * Upstream Android ships the Linux runtime as a zstd-compressed tar and
 * unpacks it with a Java helper; the OHOS NDK has no zstd, so the port
 * vendors zstd and decompresses it here.
 *
 * The archive is decompressed to a plain tar on disk first, so a
 * multi-gigabyte rootfs never has to fit in memory.
 *
 * Only the subset the runtime needs is handled: regular files, directories,
 * symlinks and hard links.
 */
UnpackResult UnpackArchive(const std::string &archivePath, const std::string &destination);

}  // namespace ddk

#endif  // DDK_UNPACK_H
