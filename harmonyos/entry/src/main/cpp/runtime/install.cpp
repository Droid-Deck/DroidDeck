#include "runtime/install.h"

#include <sys/stat.h>
#include <unistd.h>

#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "ddk_log.h"

namespace ddk {
namespace {

/* -------------------------------------------------------------------------- */
/* SHA-256                                                                     */
/* -------------------------------------------------------------------------- */

constexpr uint32_t kSha256K[64] = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u,
    0x923f82a4u, 0xab1c5ed5u, 0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
    0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u, 0xe49b69c1u, 0xefbe4786u,
    0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u,
    0x06ca6351u, 0x14292967u, 0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
    0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u, 0xa2bfe8a1u, 0xa81a664bu,
    0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au,
    0x5b9cca4fu, 0x682e6ff3u, 0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
    0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u};

inline uint32_t RotateRight(uint32_t value, uint32_t bits) {
  return (value >> bits) | (value << (32 - bits));
}

/** The running SHA-256 state. */
struct Sha256 {
  uint32_t state[8] = {0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
                        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u};
  uint64_t length = 0;
  uint8_t buffer[64] = {0};
  size_t buffered = 0;

  void Block(const uint8_t *chunk) {
    uint32_t w[64];
    for (int i = 0; i < 16; ++i) {
      w[i] = (static_cast<uint32_t>(chunk[i * 4]) << 24) |
             (static_cast<uint32_t>(chunk[i * 4 + 1]) << 16) |
             (static_cast<uint32_t>(chunk[i * 4 + 2]) << 8) |
             static_cast<uint32_t>(chunk[i * 4 + 3]);
    }
    for (int i = 16; i < 64; ++i) {
      const uint32_t s0 = RotateRight(w[i - 15], 7) ^ RotateRight(w[i - 15], 18) ^ (w[i - 15] >> 3);
      const uint32_t s1 = RotateRight(w[i - 2], 17) ^ RotateRight(w[i - 2], 19) ^ (w[i - 2] >> 10);
      w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }

    uint32_t a = state[0];
    uint32_t b = state[1];
    uint32_t c = state[2];
    uint32_t d = state[3];
    uint32_t e = state[4];
    uint32_t f = state[5];
    uint32_t g = state[6];
    uint32_t h = state[7];

    for (int i = 0; i < 64; ++i) {
      const uint32_t s1 = RotateRight(e, 6) ^ RotateRight(e, 11) ^ RotateRight(e, 25);
      const uint32_t ch = (e & f) ^ ((~e) & g);
      const uint32_t temp1 = h + s1 + ch + kSha256K[i] + w[i];
      const uint32_t s0 = RotateRight(a, 2) ^ RotateRight(a, 13) ^ RotateRight(a, 22);
      const uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
      const uint32_t temp2 = s0 + maj;

      h = g;
      g = f;
      f = e;
      e = d + temp1;
      d = c;
      c = b;
      b = a;
      a = temp1 + temp2;
    }

    state[0] += a;
    state[1] += b;
    state[2] += c;
    state[3] += d;
    state[4] += e;
    state[5] += f;
    state[6] += g;
    state[7] += h;
  }

  void Update(const uint8_t *data, size_t size) {
    length += static_cast<uint64_t>(size) * 8;
    while (size > 0) {
      const size_t room = 64 - buffered;
      const size_t take = size < room ? size : room;
      memcpy(buffer + buffered, data, take);
      buffered += take;
      data += take;
      size -= take;
      if (buffered == 64) {
        Block(buffer);
        buffered = 0;
      }
    }
  }

  std::string Hex() {
    // Padding: a single 1 bit, then zeros, then the 64 bit length.
    const uint8_t one = 0x80;
    Update(&one, 1);
    length -= 8;  // The padding is not part of the message length.
    const uint8_t zero = 0x00;
    while (buffered != 56) {
      Update(&zero, 1);
      length -= 8;
    }
    uint8_t tail[8];
    for (int i = 0; i < 8; ++i) {
      tail[i] = static_cast<uint8_t>((length >> (56 - i * 8)) & 0xFF);
    }
    Update(tail, 8);

    static const char kHex[] = "0123456789abcdef";
    std::string out;
    out.reserve(64);
    for (int i = 0; i < 8; ++i) {
      for (int shift = 24; shift >= 0; shift -= 8) {
        const uint8_t byte = static_cast<uint8_t>((state[i] >> shift) & 0xFF);
        out.push_back(kHex[byte >> 4]);
        out.push_back(kHex[byte & 0x0F]);
      }
    }
    return out;
  }
};

/** Removes trailing whitespace from a line. */
std::string Trim(const std::string &text) {
  size_t end = text.size();
  while (end > 0 && std::isspace(static_cast<unsigned char>(text[end - 1]))) {
    --end;
  }
  return text.substr(0, end);
}

/** Creates a directory and its parents. */
bool MakeDirectories(const std::string &path) {
  std::string current;
  size_t index = 0;
  if (!path.empty() && path[0] == '/') {
    current = "/";
    index = 1;
  }
  while (index <= path.size()) {
    const size_t slash = path.find('/', index);
    const std::string part =
        path.substr(index, slash == std::string::npos ? std::string::npos : slash - index);
    if (!part.empty()) {
      if (current.empty() || current.back() != '/') {
        current += "/";
      }
      current += part;
      if (mkdir(current.c_str(), 0755) != 0 && errno != EEXIST) {
        return false;
      }
    }
    if (slash == std::string::npos) {
      break;
    }
    index = slash + 1;
  }
  return true;
}

/** Writes a file, creating its parent directories. */
bool WriteTextFile(const std::string &path, const std::string &content) {
  const size_t slash = path.find_last_of('/');
  if (slash != std::string::npos) {
    MakeDirectories(path.substr(0, slash));
  }
  std::ofstream file(path, std::ios::binary | std::ios::trunc);
  if (!file.good()) {
    return false;
  }
  file.write(content.data(), static_cast<std::streamsize>(content.size()));
  file.close();
  return true;
}

/** Whether a path exists and can be read. */
bool IsReadableFile(const std::string &path) {
  return access(path.c_str(), R_OK) == 0;
}

}  // namespace

std::string Sha256File(const std::string &path) {
  std::ifstream file(path, std::ios::binary);
  if (!file.good()) {
    return std::string();
  }

  Sha256 hash;
  std::vector<char> buffer(1 << 16);
  while (file.good()) {
    file.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
    const std::streamsize read = file.gcount();
    if (read > 0) {
      hash.Update(reinterpret_cast<const uint8_t *>(buffer.data()), static_cast<size_t>(read));
    }
  }
  return hash.Hex();
}

bool VerifySha256(const std::string &path, const std::string &expected) {
  const std::string wanted = Trim(expected);
  if (wanted.empty()) {
    // Nothing to compare against: the file is accepted as it is.
    return IsReadableFile(path);
  }
  const std::string actual = Sha256File(path);
  if (actual.empty()) {
    return false;
  }
  std::string lowered;
  lowered.reserve(actual.size());
  for (const char c : actual) {
    lowered.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
  }
  std::string target;
  target.reserve(wanted.size());
  for (const char c : wanted) {
    target.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
  }
  return lowered == target;
}

UnpackResult ApplyOverlay(const std::string &archivePath, const std::string &rootDir) {
  UnpackResult result;
  if (!IsReadableFile(archivePath)) {
    result.error = "cannot read the overlay archive " + archivePath;
    return result;
  }
  if (rootDir.empty()) {
    result.error = "no runtime root given";
    return result;
  }
  // The overlay is extracted straight over the rootfs, so the files it
  // carries replace the ones the runtime image shipped.
  result = UnpackArchive(archivePath, rootDir);
  if (!result.ok) {
    return result;
  }
  DDK_LOGI("applied the overlay: %lld files", result.files);
  return result;
}

bool PrepareRootfs(const std::string &rootDir) {
  if (rootDir.empty()) {
    return false;
  }

  // Upstream binds this empty directory over /sys/fs/selinux, because the
  // guest's kernel interface refuses to list it.
  if (!MakeDirectories(rootDir + "/etc/droiddeck/empty")) {
    DDK_LOGE("cannot create the empty directory in %s", rootDir.c_str());
    return false;
  }

  // The fake procfs files upstream seeds. The runtime image already carries
  // most of them; these three are created because the guest cannot read the
  // host's copies inside the app sandbox.
  const std::string fakeProc = rootDir + "/etc/droiddeck/proc";
  if (!MakeDirectories(fakeProc)) {
    return false;
  }
  const char *seeded[][2] = {
      {"file-max", "9223372036854775807\n"},
      {"overcommit_memory", "0\n"},
  };
  for (const auto &entry : seeded) {
    const std::string path = fakeProc + "/" + entry[0];
    if (access(path.c_str(), F_OK) != 0) {
      WriteTextFile(path, entry[1]);
    }
  }
  const std::string pci = fakeProc + "/pci_devices";
  if (access(pci.c_str(), F_OK) != 0) {
    WriteTextFile(pci, "");
  }

  // The session script writes into these directories.
  MakeDirectories(rootDir + "/run");
  MakeDirectories(rootDir + "/tmp");
  MakeDirectories(rootDir + "/var/log");
  return true;
}

bool WriteVersionMarker(const std::string &rootDir, const std::string &version) {
  if (rootDir.empty()) {
    return false;
  }
  // Upstream keeps the marker free of characters that would be awkward in a
  // path or a shell word.
  std::string safe;
  safe.reserve(version.size());
  for (const char c : version) {
    const bool ok = std::isalnum(static_cast<unsigned char>(c)) || c == '.' || c == '_' || c == '-';
    safe.push_back(ok ? c : '_');
  }
  if (!WriteTextFile(rootDir + "/.version", safe + "\n")) {
    DDK_LOGE("cannot write the version marker in %s", rootDir.c_str());
    return false;
  }
  return true;
}

std::string ReadVersionMarker(const std::string &rootDir) {
  const char *paths[] = {"/.version", "/etc/droiddeck/version"};
  for (const char *path : paths) {
    std::ifstream file(rootDir + path);
    if (!file.good()) {
      continue;
    }
    std::ostringstream buffer;
    buffer << file.rdbuf();
    const std::string value = Trim(buffer.str());
    if (!value.empty()) {
      return value;
    }
  }
  return std::string();
}

}  // namespace ddk
