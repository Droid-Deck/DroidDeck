#include "audio/audio.h"

#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <vector>

#include "ddk_log.h"

namespace ddk {

AudioSink &AudioSink::Instance() {
  static AudioSink instance;
  return instance;
}

bool AudioSink::Open(const std::string &socketPath) {
  if (fd_ >= 0) {
    return true;
  }

  fd_ = socket(AF_UNIX, SOCK_STREAM, 0);
  if (fd_ < 0) {
    lastError_ = std::string("cannot create the audio socket: ") + std::strerror(errno);
    DDK_LOGW("%{public}s", lastError_.c_str());
    return false;
  }

  struct sockaddr_un address;
  std::memset(&address, 0, sizeof(address));
  address.sun_family = AF_UNIX;
  if (socketPath.size() >= sizeof(address.sun_path)) {
    lastError_ = "the audio socket path is too long";
    close(fd_);
    fd_ = -1;
    return false;
  }
  std::strncpy(address.sun_path, socketPath.c_str(), sizeof(address.sun_path) - 1);

  if (connect(fd_, reinterpret_cast<struct sockaddr *>(&address), sizeof(address)) != 0) {
    lastError_ = std::string("cannot reach the guest audio socket: ") + std::strerror(errno);
    DDK_LOGW("%{public}s", lastError_.c_str());
    close(fd_);
    fd_ = -1;
    return false;
  }

  buffer_.assign(kCapacityFrames * 2, 0.0f);
  readIndex_ = 0;
  writeIndex_ = 0;
  count_ = 0;
  lastError_.clear();
  DDK_LOGI("guest audio socket connected: %{public}s", socketPath.c_str());
  return true;
}

void AudioSink::Close() {
  if (fd_ >= 0) {
    close(fd_);
    fd_ = -1;
  }
  readIndex_ = 0;
  writeIndex_ = 0;
  count_ = 0;
  DDK_LOGI("guest audio socket closed");
}

bool AudioSink::IsOpen() const {
  return fd_ >= 0;
}

size_t AudioSink::Read(float *frames, size_t maxFrames) {
  if (fd_ < 0 || frames == nullptr || maxFrames == 0) {
    return 0;
  }

  // Top the buffer up from the guest socket.
  while (count_ < kCapacityFrames / 2) {
    const size_t space = kCapacityFrames - count_;
    if (space < 2) {
      break;
    }
    const ssize_t got = read(fd_, buffer_.data() + writeIndex_ * 2, space * 2 * sizeof(float));
    if (got <= 0) {
      if (got < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        break;
      }
      DDK_LOGW("guest audio socket closed by the peer");
      Close();
      break;
    }
    const size_t framesRead = static_cast<size_t>(got) / (2 * sizeof(float));
    writeIndex_ = (writeIndex_ + framesRead) % kCapacityFrames;
    count_ += framesRead;
  }

  const size_t toCopy = count_ < maxFrames ? count_ : maxFrames;
  for (size_t index = 0; index < toCopy; ++index) {
    frames[index * 2] = buffer_[readIndex_ * 2];
    frames[index * 2 + 1] = buffer_[readIndex_ * 2 + 1];
    readIndex_ = (readIndex_ + 1) % kCapacityFrames;
  }
  count_ -= toCopy;
  return toCopy;
}

size_t AudioSink::Available() const {
  return count_;
}

const std::string &AudioSink::LastError() const {
  return lastError_;
}

}  // namespace ddk
