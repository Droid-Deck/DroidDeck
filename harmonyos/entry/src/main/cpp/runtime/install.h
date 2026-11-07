#ifndef DDK_INSTALL_H
#define DDK_INSTALL_H

#include <string>

#include "runtime/unpack.h"

namespace ddk {

/**
 * Computes the SHA-256 of a file as lowercase hex.
 *
 * Returns an empty string when the file cannot be read.
 */
std::string Sha256File(const std::string &path);

/**
 * Whether a file's SHA-256 matches the expected digest.
 *
 * The expected digest is compared case-insensitively and may be empty, in
 * which case the file is accepted: upstream's catalog always publishes one,
 * but a hand-installed runtime may not have a known digest.
 */
bool VerifySha256(const std::string &path, const std::string &expected);

/**
 * Extracts an overlay archive over a runtime rootfs.
 *
 * Upstream bakes tools/linuxfs/overlay/ into the published runtime image at
 * build time, so the image a phone downloads does not contain
 * /usr/local/bin/droiddeck-session. The port applies the same overlay after
 * unpacking, which is what makes the runtime usable.
 */
UnpackResult ApplyOverlay(const std::string &archivePath, const std::string &rootDir);

/**
 * Prepares a freshly unpacked rootfs for the guest.
 *
 * Creates the empty directory and the fake procfs files that upstream's bind
 * list expects, and makes sure the directories the session script writes to
 * exist.
 */
bool PrepareRootfs(const std::string &rootDir);

/**
 * Writes the runtime version marker.
 *
 * Upstream stores it in `/.version` at the root of the rootfs and writes it
 * after the archive has been extracted.
 */
bool WriteVersionMarker(const std::string &rootDir, const std::string &version);

/**
 * Reads the runtime version marker.
 *
 * Falls back to the legacy `/etc/droiddeck/version` location.
 */
std::string ReadVersionMarker(const std::string &rootDir);

}  // namespace ddk

#endif  // DDK_INSTALL_H
