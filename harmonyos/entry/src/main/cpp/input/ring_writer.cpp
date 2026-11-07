#include "input/ring_writer.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstring>

#include "ddk_log.h"

namespace ddk {
namespace {

// The event types, from linux/input-event-codes.h.
constexpr uint16_t kEvSyn = 0x00;
constexpr uint16_t kEvKey = 0x01;
constexpr uint16_t kEvAbs = 0x03;
constexpr uint16_t kEvMsc = 0x04;

constexpr uint16_t kSynReport = 0x00;
constexpr uint16_t kMscScan = 0x04;

// The absolute axes, from linux/input-event-codes.h.
constexpr uint16_t kAbsX = 0x00;
constexpr uint16_t kAbsY = 0x01;
constexpr uint16_t kAbsRx = 0x03;
constexpr uint16_t kAbsRy = 0x04;
constexpr uint16_t kAbsGas = 0x09;
constexpr uint16_t kAbsBrake = 0x0a;
constexpr uint16_t kAbsHat0X = 0x10;
constexpr uint16_t kAbsHat0Y = 0x11;

// The buttons, in the order upstream's snapshot numbers them.
constexpr uint16_t kButtonCodes[11] = {
    0x130,  // BTN_A
    0x131,  // BTN_B
    0x133,  // BTN_X
    0x134,  // BTN_Y
    0x136,  // BTN_L1
    0x137,  // BTN_R1
    0x13a,  // BTN_SELECT
    0x13b,  // BTN_START
    0x13d,  // BTN_THUMBL
    0x13e,  // BTN_THUMBR
    0x13c,  // BTN_MODE
};

// The offsets inside the file, which are upstream's.
constexpr size_t kOffsetMagic = 0;
constexpr size_t kOffsetVersion = 4;
constexpr size_t kOffsetEventSize = 8;
constexpr size_t kOffsetCapacity = 12;
constexpr size_t kOffsetWriteSeq = 16;
constexpr size_t kOffsetGeneration = 24;
constexpr size_t kOffsetSnapshotSeq = 32;
constexpr size_t kOffsetSnapshotButtons = 40;
constexpr size_t kOffsetSnapshotAxes = 44;

constexpr size_t kImuOffset = 98368;
constexpr size_t kImuMagicOffset = kImuOffset;
constexpr uint32_t kImuMagic = 0x31554D49;
constexpr size_t kImuPadTypeOffset = kImuOffset + 44;
constexpr uint32_t kPadTypeSteam = 1;
constexpr uint32_t kPadTypeSet = 0x80000000u;
constexpr size_t kImuLatencyOffset = kImuOffset + 48;

constexpr size_t kTritonOffset = 98368 + 64;
constexpr size_t kTritonMagicOffset = kTritonOffset;
constexpr uint32_t kTritonMagic = 0x4e525454;

template <typename T>
void Store(uint8_t *base, size_t offset, const T &value) {
  std::memcpy(base + offset, &value, sizeof(T));
}

template <typename T>
T Load(const uint8_t *base, size_t offset) {
  T value;
  std::memcpy(&value, base + offset, sizeof(T));
  return value;
}

int16_t ClampShort(int32_t value) {
  if (value > 32767) {
    return 32767;
  }
  if (value < -32768) {
    return -32768;
  }
  return static_cast<int16_t>(value);
}

}  // namespace

RingWriter &RingWriter::Instance() {
  static RingWriter instance;
  return instance;
}

bool RingWriter::Open(const std::string &path) {
  if (data_ != nullptr) {
    return true;
  }

  fd_ = open(path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0644);
  if (fd_ < 0) {
    lastError_ = std::string("cannot create the input ring: ") + std::strerror(errno);
    DDK_LOGW("%{public}s", lastError_.c_str());
    return false;
  }

  if (ftruncate(fd_, static_cast<off_t>(kFileSize)) != 0) {
    lastError_ = std::string("cannot size the input ring: ") + std::strerror(errno);
    DDK_LOGW("%{public}s", lastError_.c_str());
    close(fd_);
    fd_ = -1;
    return false;
  }

  void *mapping = mmap(nullptr, kFileSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
  if (mapping == MAP_FAILED) {
    lastError_ = std::string("cannot map the input ring: ") + std::strerror(errno);
    DDK_LOGW("%{public}s", lastError_.c_str());
    close(fd_);
    fd_ = -1;
    return false;
  }
  data_ = static_cast<uint8_t *>(mapping);

  // The header, the way the guest's library expects to find it.
  std::memset(data_, 0, kHeaderSize);
  Store<uint32_t>(data_, kOffsetMagic, kMagic);
  Store<uint32_t>(data_, kOffsetVersion, kVersion);
  Store<uint32_t>(data_, kOffsetEventSize, static_cast<uint32_t>(kEventSize));
  Store<uint32_t>(data_, kOffsetCapacity, kCapacity);
  Store<uint64_t>(data_, kOffsetWriteSeq, static_cast<uint64_t>(0));
  Store<uint64_t>(data_, kOffsetGeneration, static_cast<uint64_t>(0));
  Store<uint64_t>(data_, kOffsetSnapshotSeq, static_cast<uint64_t>(0));
  Store<uint32_t>(data_, kOffsetSnapshotButtons, static_cast<uint32_t>(0));
  for (size_t index = 0; index < 8; ++index) {
    Store<int16_t>(data_, kOffsetSnapshotAxes + index * 2, static_cast<int16_t>(0));
  }

  // The blocks after the events.
  Store<uint32_t>(data_, kImuMagicOffset, kImuMagic);
  Store<uint32_t>(data_, kImuPadTypeOffset, kPadTypeSet | kPadTypeSteam);
  Store<uint32_t>(data_, kImuLatencyOffset, static_cast<uint32_t>(0));
  Store<uint32_t>(data_, kTritonMagicOffset, kTritonMagic);

  path_ = path;
  lastError_.clear();
  DDK_LOGI("the input ring is ready: %{public}s", path.c_str());
  return true;
}

void RingWriter::Close() {
  if (data_ != nullptr) {
    munmap(data_, kFileSize);
    data_ = nullptr;
  }
  if (fd_ >= 0) {
    close(fd_);
    fd_ = -1;
  }
  staged_.clear();
}

bool RingWriter::IsOpen() const {
  return data_ != nullptr;
}

void RingWriter::Write(const std::string &kind, int code, int value) {
  if (data_ == nullptr) {
    return;
  }

  struct timeval now;
  gettimeofday(&now, nullptr);

  auto stage = [&](uint16_t type, uint16_t eventCode, int32_t eventValue) {
    Event event;
    event.sec = now.tv_sec;
    event.usec = now.tv_usec;
    event.type = type;
    event.code = eventCode;
    event.value = eventValue;
    staged_.push_back(event);
  };

  if (kind == "axis" || kind == "hat") {
    stage(kEvAbs, static_cast<uint16_t>(code), value);
    // The snapshot keeps the eight axes the guest reads the state from.
    switch (code) {
      case kAbsX:
        axes_[0] = value;
        break;
      case kAbsY:
        axes_[1] = value;
        break;
      case kAbsRx:
        axes_[2] = value;
        break;
      case kAbsRy:
        axes_[3] = value;
        break;
      case kAbsGas:
        axes_[4] = value;
        break;
      case kAbsBrake:
        axes_[5] = value;
        break;
      case kAbsHat0X:
        axes_[6] = value;
        break;
      case kAbsHat0Y:
        axes_[7] = value;
        break;
      default:
        break;
    }
  } else {
    // Upstream sends a scan code before the key, so the guest's client
    // notices the button that changed.
    stage(kEvMsc, kMscScan, code);
    stage(kEvKey, static_cast<uint16_t>(code), value);
    for (size_t index = 0; index < 11; ++index) {
      if (kButtonCodes[index] == code) {
        buttons_[index] = value != 0;
        break;
      }
    }
  }

  stage(kEvSyn, kSynReport, 0);
  Publish();
}

void RingWriter::Publish() {
  if (data_ == nullptr || staged_.empty()) {
    return;
  }

  uint64_t writeSeq = Load<uint64_t>(data_, kOffsetWriteSeq);

  for (const Event &event : staged_) {
    const size_t index = static_cast<size_t>(writeSeq % kCapacity);
    uint8_t *slot = data_ + kHeaderSize + index * kEventSize;
    Store<int64_t>(slot, 0, event.sec);
    Store<int64_t>(slot, 8, event.usec);
    Store<uint16_t>(slot, 16, event.type);
    Store<uint16_t>(slot, 18, event.code);
    Store<int32_t>(slot, 20, event.value);
    ++writeSeq;
  }
  staged_.clear();

  // The snapshot is the state the guest resynchronises from.
  uint32_t buttons = 0;
  for (size_t index = 0; index < 11; ++index) {
    if (buttons_[index]) {
      buttons |= (1u << index);
    }
  }
  const uint64_t snapshotSeq = Load<uint64_t>(data_, kOffsetSnapshotSeq);
  Store<uint64_t>(data_, kOffsetSnapshotSeq, snapshotSeq + 1);
  std::atomic_thread_fence(std::memory_order_release);
  Store<uint32_t>(data_, kOffsetSnapshotButtons, buttons);
  for (size_t index = 0; index < 8; ++index) {
    Store<int16_t>(data_, kOffsetSnapshotAxes + index * 2, ClampShort(axes_[index]));
  }
  std::atomic_thread_fence(std::memory_order_release);
  Store<uint64_t>(data_, kOffsetSnapshotSeq, snapshotSeq + 2);

  // The sequence is the last thing written: the guest only trusts the
  // events below it.
  std::atomic_thread_fence(std::memory_order_release);
  Store<uint64_t>(data_, kOffsetWriteSeq, writeSeq);
}

const std::string &RingWriter::Path() const {
  return path_;
}

const std::string &RingWriter::LastError() const {
  return lastError_;
}

}  // namespace ddk
