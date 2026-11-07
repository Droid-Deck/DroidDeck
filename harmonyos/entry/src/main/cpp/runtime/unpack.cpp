#include "runtime/unpack.h"

#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>
#include <zstd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "ddk_log.h"

namespace {

constexpr size_t kBlockSize = 512;

/** The zstd frame magic number, little endian. */
constexpr unsigned char kZstdMagic[4] = {0x28, 0xB5, 0x2F, 0xFD};
/** The gzip magic number. */
constexpr unsigned char kGzipMagic[2] = {0x1F, 0x8B};

/** Parses an octal tar header field. */
long long ParseOctal(const char *field, size_t length) {
  long long value = 0;
  size_t index = 0;
  while (index < length && (field[index] == ' ' || field[index] == '\0')) {
    ++index;
  }
  for (; index < length; ++index) {
    const char c = field[index];
    if (c < '0' || c > '7') {
      break;
    }
    value = value * 8 + (c - '0');
  }
  return value;
}

bool MakeDirectories(const std::string &path) {
  std::string current;
  size_t index = 0;
  while (index <= path.size()) {
    const size_t slash = path.find('/', index);
    const std::string part = path.substr(0, slash == std::string::npos ? path.size() : slash);
    if (!part.empty()) {
      if (mkdir(part.c_str(), 0755) != 0 && errno != EEXIST) {
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

bool WriteFile(const std::string &path, const char *data, size_t length, mode_t mode) {
  std::ofstream file(path, std::ios::binary | std::ios::trunc);
  if (!file.good()) {
    return false;
  }
  if (length > 0) {
    file.write(data, static_cast<std::streamsize>(length));
  }
  file.close();
  chmod(path.c_str(), mode);
  return true;
}

/** Reads the first bytes of a file to work out how it is compressed. */
bool DetectFormat(const std::string &path, bool *zstd, bool *gzip) {
  *zstd = false;
  *gzip = false;
  std::ifstream file(path, std::ios::binary);
  if (!file.good()) {
    return false;
  }
  unsigned char magic[4] = {0, 0, 0, 0};
  file.read(reinterpret_cast<char *>(magic), sizeof(magic));
  const std::streamsize read = file.gcount();
  if (read >= 4 && memcmp(magic, kZstdMagic, sizeof(kZstdMagic)) == 0) {
    *zstd = true;
    return true;
  }
  if (read >= 2 && memcmp(magic, kGzipMagic, sizeof(kGzipMagic)) == 0) {
    *gzip = true;
    return true;
  }
  return true;
}

/** Decompresses a zstd archive into a plain tar file. */
bool DecompressZstd(const std::string &source, const std::string &destination) {
  std::ifstream input(source, std::ios::binary);
  if (!input.good()) {
    return false;
  }
  std::ofstream output(destination, std::ios::binary | std::ios::trunc);
  if (!output.good()) {
    return false;
  }

  ZSTD_DStream *stream = ZSTD_createDStream();
  if (stream == nullptr) {
    return false;
  }
  ZSTD_initDStream(stream);

  std::vector<char> in(ZSTD_DStreamInSize());
  std::vector<char> out(ZSTD_DStreamOutSize());
  ZSTD_inBuffer inputBuffer{in.data(), in.size(), 0};
  ZSTD_outBuffer outputBuffer{out.data(), out.size(), 0};

  bool ok = true;
  while (true) {
    inputBuffer.src = in.data();
    inputBuffer.size = in.size();
    inputBuffer.pos = 0;
    if (input.eof()) {
      break;
    }
    input.read(in.data(), in.size());
    inputBuffer.size = input.gcount();
    if (inputBuffer.size == 0) {
      break;
    }

    while (inputBuffer.pos < inputBuffer.size) {
      outputBuffer.dst = out.data();
      outputBuffer.size = out.size();
      outputBuffer.pos = 0;
      const size_t result = ZSTD_decompressStream(stream, &outputBuffer, &inputBuffer);
      if (ZSTD_isError(result)) {
        DDK_LOGE("zstd: %s", ZSTD_getErrorName(result));
        ok = false;
        break;
      }
      if (outputBuffer.pos > 0) {
        output.write(out.data(), outputBuffer.pos);
      }
      if (result == 0) {
        break;
      }
    }
    if (!ok) {
      break;
    }
  }

  ZSTD_freeDStream(stream);
  output.close();
  return ok;
}

/** Decompresses a gzip archive into a plain tar file. */
bool DecompressGzip(const std::string &source, const std::string &destination) {
  gzFile input = gzopen(source.c_str(), "rb");
  if (input == nullptr) {
    return false;
  }
  // The output is a plain tar, so it is written uncompressed.
  FILE *output = fopen(destination.c_str(), "wb");
  if (output == nullptr) {
    gzclose(input);
    return false;
  }

  std::vector<char> buffer(1 << 16);
  bool ok = true;
  int read = 0;
  while ((read = gzread(input, buffer.data(), static_cast<unsigned>(buffer.size()))) > 0) {
    if (fwrite(buffer.data(), 1, static_cast<size_t>(read), output) != static_cast<size_t>(read)) {
      ok = false;
      break;
    }
  }
  if (read < 0) {
    ok = false;
  }

  fclose(output);
  gzclose(input);
  if (!ok) {
    DDK_LOGE("gzip: the archive could not be decompressed");
  }
  return ok;
}

/** Extracts a plain tar file into the destination directory. */
bool ExtractTar(const std::string &archive, const std::string &destination, long long *fileCount) {
  FILE *file = fopen(archive.c_str(), "rb");
  if (file == nullptr) {
    return false;
  }

  std::vector<char> header(kBlockSize);
  bool ok = true;
  // GNU tar stores names that do not fit in the 100 byte header in a
  // separate entry that names the entry that follows it.
  std::string longName;
  std::string longLink;

  while (true) {
    const size_t read = fread(header.data(), 1, kBlockSize, file);
    if (read == 0) {
      break;
    }
    if (read != kBlockSize) {
      DDK_LOGE("the archive is truncated");
      ok = false;
      break;
    }
    // Two consecutive zero blocks mark the end of the archive.
    if (header[0] == '\0') {
      break;
    }

    const long long size = ParseOctal(header.data() + 124, 12);
    const char type = header[156];
    char nameBuffer[101];
    memcpy(nameBuffer, header.data(), 100);
    nameBuffer[100] = '\0';
    char linkBuffer[101];
    memcpy(linkBuffer, header.data() + 157, 100);
    linkBuffer[100] = '\0';

    // The payload of an entry is rounded up to a 512 byte boundary.
    const size_t padding = (kBlockSize - (static_cast<size_t>(size) % kBlockSize)) % kBlockSize;

    if (type == 'L' || type == 'K') {
      // GNU long name and long link name: the payload is the string that
      // belongs to the next entry.
      std::string text;
      if (size > 0) {
        std::vector<char> payload(static_cast<size_t>(size));
        const size_t filled = fread(payload.data(), 1, payload.size(), file);
        text.assign(payload.data(), filled);
        while (!text.empty() && text.back() == '\0') {
          text.pop_back();
        }
      }
      if (padding > 0) {
        std::vector<char> skip(padding);
        fread(skip.data(), 1, padding, file);
      }
      if (type == 'L') {
        longName = text;
      } else {
        longLink = text;
      }
      continue;
    }

    if (type == 'x' || type == 'g') {
      // pax extended headers. The reader does not interpret them, but the
      // payload still has to be consumed, or the stream would desynchronise
      // and every later entry would be read from the wrong offset.
      std::vector<char> skip(static_cast<size_t>(size) + padding);
      if (!skip.empty()) {
        fread(skip.data(), 1, skip.size(), file);
      }
      continue;
    }

    std::string name = longName.empty() ? std::string(nameBuffer) : longName;
    longName.clear();
    std::string linkName = longLink.empty() ? std::string(linkBuffer) : longLink;
    longLink.clear();

    if (name.empty()) {
      continue;
    }
    // Archive paths are relative, but "./" prefixes and stray leading
    // slashes are both harmless once they are normalised away.
    while (name.size() > 1 && name[0] == '.' && name[1] == '/') {
      name.erase(0, 2);
    }
    if (name[0] == '/') {
      name.erase(0, 1);
    }
    const std::string path = destination + "/" + name;

    if (type == '5') {
      MakeDirectories(path);
      if (size > 0) {
        std::vector<char> skip(static_cast<size_t>(size) + padding);
        fread(skip.data(), 1, skip.size(), file);
      }
      continue;
    }

    if (type == '2') {
      // Symbolic link: the target lives in the header's linkname field.
      const size_t slash = path.find_last_of('/');
      if (slash != std::string::npos) {
        MakeDirectories(path.substr(0, slash));
      }
      unlink(path.c_str());
      if (symlink(linkName.c_str(), path.c_str()) != 0) {
        DDK_LOGW("symlink failed: %s", path.c_str());
      }
      if (fileCount != nullptr) {
        ++(*fileCount);
      }
      continue;
    }

    if (type == '1') {
      // Hard link: the linkname names another entry in the same archive.
      std::string target = linkName;
      while (target.size() > 1 && target[0] == '.' && target[1] == '/') {
        target.erase(0, 2);
      }
      if (target[0] == '/') {
        target.erase(0, 1);
      }
      const size_t slash = path.find_last_of('/');
      if (slash != std::string::npos) {
        MakeDirectories(path.substr(0, slash));
      }
      unlink(path.c_str());
      const std::string targetPath = destination + "/" + target;
      if (link(targetPath.c_str(), path.c_str()) != 0) {
        // A hard link across devices cannot be made, so the content is
        // copied instead.
        DDK_LOGW("hard link failed, copying instead: %s", path.c_str());
        std::vector<char> payload(1 << 16);
        FILE *source = fopen(targetPath.c_str(), "rb");
        if (source != nullptr) {
          FILE *sink = fopen(path.c_str(), "wb");
          if (sink != nullptr) {
            while (true) {
              const size_t chunk = fread(payload.data(), 1, payload.size(), source);
              if (chunk == 0) {
                break;
              }
              fwrite(payload.data(), 1, chunk, sink);
            }
            fclose(sink);
          }
          fclose(source);
        }
      }
      if (fileCount != nullptr) {
        ++(*fileCount);
      }
      continue;
    }

    if (type != '0' && type != '\0') {
      // An entry this reader does not understand still has a payload, which
      // has to be skipped to keep the stream aligned.
      std::vector<char> skip(static_cast<size_t>(size) + padding);
      if (!skip.empty()) {
        fread(skip.data(), 1, skip.size(), file);
      }
      continue;
    }

    const size_t slash = path.find_last_of('/');
    if (slash != std::string::npos) {
      MakeDirectories(path.substr(0, slash));
    }

    const long long mode = ParseOctal(header.data() + 100, 8);
    const mode_t fileMode = mode > 0 ? static_cast<mode_t>(mode) : 0644;

    std::vector<char> payload(static_cast<size_t>(size));
    const size_t filled = payload.empty() ? 0 : fread(payload.data(), 1, payload.size(), file);
    if (!WriteFile(path, payload.data(), filled, fileMode)) {
      DDK_LOGW("write failed: %s", path.c_str());
    }
    if (fileCount != nullptr) {
      ++(*fileCount);
    }

    if (padding > 0) {
      std::vector<char> skip(padding);
      fread(skip.data(), 1, padding, file);
    }
  }

  fclose(file);
  return ok;
}

}  // namespace

namespace ddk {

UnpackResult UnpackArchive(const std::string &archivePath, const std::string &destination) {
  UnpackResult result;

  if (!MakeDirectories(destination)) {
    result.error = "cannot create " + destination;
    return result;
  }

  bool zstd = false;
  bool gzip = false;
  if (!DetectFormat(archivePath, &zstd, &gzip)) {
    result.error = "cannot read " + archivePath;
    return result;
  }

  // The archive is decompressed to a plain tar beside the destination first,
  // so a multi-gigabyte rootfs never has to fit in memory.
  std::string tarPath = destination + "/.droiddeck-archive.tar";
  bool ok = true;

  if (zstd) {
    ok = DecompressZstd(archivePath, tarPath);
  } else if (gzip) {
    ok = DecompressGzip(archivePath, tarPath);
  } else {
    // Already a plain tar.
    tarPath = archivePath;
  }

  if (!ok) {
    unlink(tarPath.c_str());
    result.error = "the archive could not be decompressed";
    return result;
  }

  ok = ExtractTar(tarPath, destination, &result.files);
  if (zstd || gzip) {
    unlink(tarPath.c_str());
  }

  if (!ok) {
    result.error = "the archive could not be extracted";
    return result;
  }

  result.ok = true;
  DDK_LOGI("unpacked %lld files into %s", result.files, destination.c_str());
  return result;
}

}  // namespace ddk
