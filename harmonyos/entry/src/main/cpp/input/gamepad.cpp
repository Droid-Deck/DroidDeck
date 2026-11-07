#include "input/gamepad.h"

#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstring>

#include "ddk_log.h"

namespace ddk {
namespace {

// The Linux uinput ABI is declared here rather than pulled in from
// linux/uinput.h, because the OHOS NDK does not ship that header and the
// ioctl numbers are part of the stable kernel ABI.
//
// Values match include/uapi/linux/uinput.h and
// include/uapi/linux/input-event-codes.h.

constexpr uintptr_t kUinputIoctlBase = 'U';

constexpr uintptr_t kUiDevCreate = 1;
constexpr uintptr_t kUiDevDestroy = 2;
constexpr uintptr_t kUiDevSetup = 3;

#define DDK_UI_DEV_CREATE _IO(kUinputIoctlBase, kUiDevCreate)
#define DDK_UI_DEV_DESTROY _IO(kUinputIoctlBase, kUiDevDestroy)
#define DDK_UI_DEV_SETUP _IOW(kUinputIoctlBase, kUiDevSetup, struct ddk_uinput_setup)
#define DDK_UI_SET_EVBIT _IOW(kUinputIoctlBase, 100, int)
#define DDK_UI_SET_KEYBIT _IOW(kUinputIoctlBase, 101, int)
#define DDK_UI_SET_ABSBIT _IOW(kUinputIoctlBase, 103, int)

/** Mirrors struct input_event. */
struct ddk_input_event {
  struct timeval time;
  uint16_t type;
  uint16_t code;
  int32_t value;
};

/** Mirrors struct uinput_setup. */
struct ddk_uinput_setup {
  struct ddk_input_id {
    uint16_t bustype;
    uint16_t vendor;
    uint16_t product;
    uint16_t version;
  } id;
  char name[80];
  uint32_t ff_effects_max;
};

constexpr uint16_t kEvKey = 0x01;
constexpr uint16_t kEvAbs = 0x03;
constexpr uint16_t kEvSyn = 0x00;
constexpr uint16_t kSynReport = 0x00;
constexpr uint16_t kBusVirtual = 0x06;

constexpr uint16_t kBtnSouth = 0x130;
constexpr uint16_t kBtnEast = 0x131;
constexpr uint16_t kBtnNorth = 0x133;
constexpr uint16_t kBtnWest = 0x134;
constexpr uint16_t kBtnTl = 0x136;
constexpr uint16_t kBtnTr = 0x137;
constexpr uint16_t kBtnSelect = 0x13a;
constexpr uint16_t kBtnStart = 0x13b;
constexpr uint16_t kBtnMode = 0x13c;
constexpr uint16_t kBtnThumbL = 0x13d;
constexpr uint16_t kBtnThumbR = 0x13e;
constexpr uint16_t kBtnDpadUp = 0x220;
constexpr uint16_t kBtnDpadDown = 0x221;
constexpr uint16_t kBtnDpadLeft = 0x222;
constexpr uint16_t kBtnDpadRight = 0x223;

constexpr uint16_t kAbsX = 0x00;
constexpr uint16_t kAbsY = 0x01;
constexpr uint16_t kAbsZ = 0x02;
constexpr uint16_t kAbsRx = 0x03;
constexpr uint16_t kAbsRy = 0x04;
constexpr uint16_t kAbsRz = 0x05;
constexpr uint16_t kAbsHat0X = 0x10;
constexpr uint16_t kAbsHat0Y = 0x11;

/** Buttons the guest expects a handheld pad to expose. */
const uint16_t kButtons[] = {
    kBtnSouth, kBtnEast, kBtnNorth, kBtnWest,
    kBtnTl, kBtnTr,
    kBtnSelect, kBtnStart, kBtnMode,
    kBtnThumbL, kBtnThumbR,
    kBtnDpadUp, kBtnDpadDown, kBtnDpadLeft, kBtnDpadRight
};

/** Axes the guest expects. */
const uint16_t kAxes[] = {
    kAbsX, kAbsY, kAbsZ, kAbsRx, kAbsRy, kAbsRz, kAbsHat0X, kAbsHat0Y
};

}  // namespace

Gamepad &Gamepad::Instance() {
  static Gamepad instance;
  return instance;
}

bool Gamepad::Open() {
  if (fd_ >= 0) {
    return true;
  }

  fd_ = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
  if (fd_ < 0) {
    lastError_ = std::string("cannot open /dev/uinput: ") + std::strerror(errno);
    DDK_LOGW("%{public}s", lastError_.c_str());
    return false;
  }

  if (ioctl(fd_, DDK_UI_SET_EVBIT, kEvKey) < 0 ||
      ioctl(fd_, DDK_UI_SET_EVBIT, kEvAbs) < 0 ||
      ioctl(fd_, DDK_UI_SET_EVBIT, kEvSyn) < 0) {
    lastError_ = std::string("cannot configure uinput: ") + std::strerror(errno);
    DDK_LOGW("%{public}s", lastError_.c_str());
    close(fd_);
    fd_ = -1;
    return false;
  }

  for (const uint16_t button : kButtons) {
    ioctl(fd_, DDK_UI_SET_KEYBIT, button);
  }
  for (const uint16_t axis : kAxes) {
    ioctl(fd_, DDK_UI_SET_ABSBIT, axis);
  }

  ddk_uinput_setup setup;
  std::memset(&setup, 0, sizeof(setup));
  setup.id.bustype = kBusVirtual;
  setup.id.vendor = 0x1D6B;  // Linux Foundation
  setup.id.product = 0x0001;
  setup.id.version = 1;
  std::strncpy(setup.name, "DroidDeck Gamepad", sizeof(setup.name) - 1);

  if (ioctl(fd_, DDK_UI_DEV_SETUP, &setup) < 0 || ioctl(fd_, DDK_UI_DEV_CREATE) < 0) {
    lastError_ = std::string("cannot create uinput device: ") + std::strerror(errno);
    DDK_LOGW("%{public}s", lastError_.c_str());
    close(fd_);
    fd_ = -1;
    return false;
  }

  lastError_.clear();
  DDK_LOGI("uinput gamepad created");
  return true;
}

void Gamepad::Close() {
  if (fd_ < 0) {
    return;
  }
  ioctl(fd_, DDK_UI_DEV_DESTROY);
  close(fd_);
  fd_ = -1;
  DDK_LOGI("uinput gamepad closed");
}

bool Gamepad::IsOpen() const {
  return fd_ >= 0;
}

void Gamepad::Event(const std::string &kind, int code, int value) {
  if (fd_ < 0) {
    return;
  }

  ddk_input_event event;
  std::memset(&event, 0, sizeof(event));
  event.type = kEvKey;
  event.code = static_cast<uint16_t>(code);
  event.value = value;

  if (kind == "axis" || kind == "hat") {
    event.type = kEvAbs;
  }

  if (write(fd_, &event, sizeof(event)) != sizeof(event)) {
    DDK_LOGW("uinput write failed: %{public}s", std::strerror(errno));
    return;
  }

  ddk_input_event sync;
  std::memset(&sync, 0, sizeof(sync));
  sync.type = kEvSyn;
  sync.code = kSynReport;
  write(fd_, &sync, sizeof(sync));
}

const std::string &Gamepad::LastError() const {
  return lastError_;
}

}  // namespace ddk
