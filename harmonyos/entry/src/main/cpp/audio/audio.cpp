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

  // The read must never block. It runs on the thread that pumps the audio
  // into the platform's renderer, and a guest that is not playing right now
  // would park that thread in the socket forever.
  const int flags = fcntl(fd_, F_GETFL, 0);
  if (flags >= 0) {
    fcntl(fd_, F_SETFL, flags | O_NONBLOCK);
  }

  buffer_.assign(kCapacityFrames * 2, 0.0f);
  readIndex_ = 0;
  writeIndex_ = 0;
  count_ = 0;
  stagingStart_ = 0;
  stagingEnd_ = 0;
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
  stagingStart_ = 0;
  stagingEnd_ = 0;
  DDK_LOGI("guest audio socket closed");
}

bool AudioSink::IsOpen() const {
  return fd_ >= 0;
}

size_t AudioSink::Read(float *frames, size_t maxFrames) {
  if (frames == nullptr || maxFrames == 0) {
    return 0;
  }

  // Top the buffer up from the guest socket. Once the guest has closed the
  // stream there is nothing left to read, but the frames that are already in
  // the ring still have to be handed out.
  while (fd_ >= 0 && count_ < kCapacityFrames / 2) {
    // The room left at the write position, and the room left in total: the
    // ring is only contiguous up to the end of the buffer, and a read that
    // ran past it would write outside the allocation.
    size_t space = kCapacityFrames - writeIndex_;
    const size_t free = kCapacityFrames - count_;
    if (space > free) {
      space = free;
    }
    // One frame is enough to move the write position on. Stopping earlier
    // would strand it on the last slot of the buffer: it could never reach
    // the end again, and the guest would wait forever for a reader.
    if (space == 0) {
      break;
    }

    // The socket is a byte stream, so a read can end in the middle of a
    // frame. The bytes of the unfinished frame are kept and the next read
    // starts after them, so the samples stay aligned.
    const size_t staged = stagingEnd_ - stagingStart_;
    if (staged < kFrameBytes) {
      if (stagingEnd_ == staging_.size()) {
        if (stagingStart_ > 0) {
          std::memmove(staging_.data(), staging_.data() + stagingStart_, staged);
          stagingEnd_ = staged;
          stagingStart_ = 0;
        } else {
          staging_.resize(kStagingBytes);
        }
      }
      const ssize_t got = read(fd_, staging_.data() + stagingEnd_, staging_.size() - stagingEnd_);
      if (got <= 0) {
        if (got < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
          break;
        }
        DDK_LOGW("guest audio socket closed by the peer");
        // The guest is done, so the socket goes away. The ring keeps what it
        // has: throwing those frames away would cut the sound off.
        close(fd_);
        fd_ = -1;
        break;
      }
      stagingEnd_ += static_cast<size_t>(got);
      continue;
    }

    const size_t whole = staged / kFrameBytes;
    const size_t frames = space < whole ? space : whole;
    std::memcpy(&buffer_[writeIndex_ * 2], staging_.data() + stagingStart_,
                frames * kFrameBytes);
    stagingStart_ += frames * kFrameBytes;
    if (stagingStart_ == stagingEnd_) {
      stagingStart_ = 0;
      stagingEnd_ = 0;
    }
    writeIndex_ = (writeIndex_ + frames) % kCapacityFrames;
    count_ += frames;
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
