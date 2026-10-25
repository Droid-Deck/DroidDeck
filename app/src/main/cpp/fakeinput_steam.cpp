/*
 * Taken from WinNative (maxjivi05, main 0cfc070b + feature/wayland-gamescope
 * through f1f34cd7, 2026-09-14..19), GPL-3.0.
 */

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <climits>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include <deque>
#include <condition_variable>
#include <sys/resource.h>

#include <dirent.h>
#include <dlfcn.h>
#ifdef __GLIBC__
#include <gnu/lib-names.h>
#endif
#include <fcntl.h>
#include <linux/input.h>
#include <linux/joystick.h>
#include <linux/uinput.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <time.h>
#include <unistd.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>

#define EXPORT extern "C" __attribute__((visibility("default")))

#ifdef __GLIBC__
typedef unsigned long ioctl_request_t;
#else
typedef int ioctl_request_t;
#endif

static constexpr uint16_t GAMEPAD_VENDOR_ID_BASE = 0x1234;
static constexpr uint16_t GAMEPAD_PRODUCT_ID_BASE = 0x5678;
static constexpr uint16_t GAMEPAD_VERSION = 0x0110;
static constexpr const char *GAMEPAD_NAME_TEMPLATE = "Generic HID Gamepad %d";
static constexpr uint16_t STEAM_VIRTUAL_VENDOR_ID = 0x28de;
static constexpr uint16_t STEAM_VIRTUAL_PRODUCT_ID = 0x11ff;
static constexpr const char *STEAM_VIRTUAL_NAME_TEMPLATE = "Microsoft X-Box 360 pad %d";
static constexpr uint16_t X360_VENDOR_ID = 0x045E;
static constexpr uint16_t X360_PRODUCT_ID = 0x028E;
static constexpr const char *X360_NAME_TEMPLATE = "Xbox 360 Controller (%d)";
static constexpr const char *GAMEPAD_PHYS_TEMPLATE = "usb-fakeinput/input%d";
static constexpr const char *GAMEPAD_UNIQ_TEMPLATE = "0000000000%02d";
static constexpr uint8_t GAMEPAD_AXIS_COUNT = 8;
static constexpr uint8_t GAMEPAD_BUTTON_COUNT = 11;
static constexpr uint32_t FAKE_INPUT_RING_MAGIC = 0x46494252;
static constexpr uint32_t FAKE_INPUT_RING_VERSION = 2;
// Rings hold the 64-bit input_event layout that the arm64 writers use; 32-bit guests convert on read.
struct RingEvent {
  int64_t sec;
  int64_t usec;
  uint16_t type;
  uint16_t code;
  int32_t value;
};
static_assert(sizeof(RingEvent) == 24, "ring events must stay ABI-stable");
static constexpr uint32_t FAKE_INPUT_EVENT_SIZE = sizeof(RingEvent);
static constexpr uint32_t FAKE_INPUT_RING_CAPACITY = 4096;
static constexpr unsigned int FAKE_INPUT_MAJOR = 13;
static constexpr unsigned int FAKE_INPUT_EVENT_MINOR_BASE = 64;
static constexpr unsigned int FAKE_INPUT_JS_MINOR_BASE = 0;
static constexpr int UINPUT_EVENT_BASE = 16;
static constexpr int UINPUT_EVENT_LIMIT = 64;
static constexpr uint32_t UINPUT_DESCRIPTION_MAGIC = 0x46495544;

struct FakeInputRingHeader {
  uint32_t magic;
  uint32_t version;
  uint32_t event_size;
  uint32_t capacity;
  uint64_t write_seq;
  uint64_t generation;




  uint64_t snapshot_seq;
  uint32_t snapshot_buttons;
  int16_t snapshot_axes[8];
  uint32_t resync_seq;
};

static_assert(sizeof(FakeInputRingHeader) == 64,
              "fake input ring header must stay ABI-stable");

static constexpr size_t FAKE_INPUT_RING_HEADER_SIZE =
    sizeof(FakeInputRingHeader);
static constexpr size_t FAKE_INPUT_RING_SIZE =
    FAKE_INPUT_RING_HEADER_SIZE +
    (FAKE_INPUT_RING_CAPACITY * FAKE_INPUT_EVENT_SIZE);

struct UinputDescription {
  uint32_t magic;
  int32_t owner_pid;
  struct input_id id;
  uint32_t ff_effects_max;
  char name[UINPUT_MAX_NAME_SIZE];
  char phys[64];
  uint8_t evbits[(EV_MAX + 8) / 8];
  uint8_t keybits[(KEY_MAX + 8) / 8];
  uint8_t absbits[(ABS_MAX + 8) / 8];
  uint8_t ffbits[(FF_MAX + 8) / 8];
  struct input_absinfo absinfo[ABS_CNT];
};

static constexpr size_t UINPUT_FILE_SIZE = FAKE_INPUT_RING_SIZE + sizeof(UinputDescription);

struct FakeController {
  char *event = nullptr;
  const UinputDescription *uinput = nullptr;
  int slot = -1;
  FakeInputRingHeader *ring = nullptr;
  uint64_t read_seq = 0;
  uint64_t generation = 0;
  uint32_t resync_seq = 0;
  size_t mapping_size = 0;
  bool closed = false;
  bool needs_keyframe = true;

  ~FakeController() {
    if (ring) munmap(ring, mapping_size);
    free(event);
  }
  size_t keyframe_remaining = 0;
  int32_t keyframe_axes[8] = {0, 0, 0, 0, 0, 0, 0, 0};
  uint32_t keyframe_buttons = 0;
};

struct NeutralEventSpec {
  uint16_t type;
  uint16_t code;
};

static const NeutralEventSpec kNeutralEvents[] = {
    {EV_KEY, BTN_A},      {EV_KEY, BTN_B},      {EV_KEY, BTN_X},
    {EV_KEY, BTN_Y},      {EV_KEY, BTN_TL},     {EV_KEY, BTN_TR},
    {EV_KEY, BTN_SELECT}, {EV_KEY, BTN_START},  {EV_KEY, BTN_MODE},
    {EV_KEY, BTN_THUMBL}, {EV_KEY, BTN_THUMBR}, {EV_ABS, ABS_X},
    {EV_ABS, ABS_Y},      {EV_ABS, ABS_RX},     {EV_ABS, ABS_RY},
    {EV_ABS, ABS_GAS},    {EV_ABS, ABS_BRAKE},  {EV_ABS, ABS_HAT0X},
    {EV_ABS, ABS_HAT0Y},  {EV_SYN, SYN_REPORT},
};
static constexpr size_t kNeutralEventCount =
    sizeof(kNeutralEvents) / sizeof(kNeutralEvents[0]);

static const uint16_t kSnapshotAxisCodes[8] = {
    ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y};
static const uint16_t kSnapshotButtons[11] = {
    BTN_A,      BTN_B,      BTN_X,     BTN_Y,      BTN_TL,    BTN_TR,
    BTN_SELECT, BTN_START,  BTN_THUMBL, BTN_THUMBR,
    BTN_MODE};
static constexpr size_t kSnapshotButtonCount =
    sizeof(kSnapshotButtons) / sizeof(kSnapshotButtons[0]);

namespace Logger {
void init();
}

static void controller_fork_prepare();
static void controller_fork_parent();
static void controller_fork_child();

struct FakeConfig {
  const char *hook_dir;
  const char *udev_data_dir;
  bool vibration_enabled;
  bool xbox360_identity;
  bool uinput_enabled;
};

static FakeConfig &config() {
  static FakeConfig *cfg = [] {
    auto *c = new FakeConfig();
    const char *dir = getenv("FAKE_EVDEV_DIR");
    c->hook_dir = dir ? dir : "/data/data/com.termux/files/home/fake-input";
    c->udev_data_dir = getenv("FAKE_UDEV_DATA_DIR");
    c->vibration_enabled =
        getenv("FAKE_EVDEV_VIBRATION") && atoi(getenv("FAKE_EVDEV_VIBRATION"));
    const char *identity = getenv("FAKE_EVDEV_IDENTITY");
    c->xbox360_identity = identity && !strcmp(identity, "xbox360");
    c->uinput_enabled = getenv("FAKE_EVDEV_UINPUT") && atoi(getenv("FAKE_EVDEV_UINPUT"));
    pthread_atfork(controller_fork_prepare, controller_fork_parent,
                   controller_fork_child);
    Logger::init();
    return c;
  }();
  return *cfg;
}

static std::recursive_mutex &controller_mutex() {
  static auto *mutex = new std::recursive_mutex();
  return *mutex;
}

static std::unordered_map<int, std::shared_ptr<FakeController>> &controller_map() {
  static auto *map = new std::unordered_map<int, std::shared_ptr<FakeController>>();
  return *map;
}

static std::unordered_map<int, std::string> &ring_paths() {
  static auto *map = new std::unordered_map<int, std::string>();
  return *map;
}

static std::unordered_map<int, struct ff_effect> &ff_effects() {
  static auto *map = new std::unordered_map<int, struct ff_effect>();
  return *map;
}

static constexpr int kWatchedFds = 1 << 16;
static constexpr int kWatchedBits = sizeof(unsigned long) * 8;
static std::atomic<unsigned long> watched_fds[kWatchedFds / kWatchedBits];
static std::atomic<bool> watched_beyond{false};

static void watch_fd(int fd) {
  if (fd < 0) return;
  if (fd >= kWatchedFds) watched_beyond.store(true, std::memory_order_release);
  else watched_fds[fd / kWatchedBits].fetch_or(1UL << (fd % kWatchedBits), std::memory_order_release);
}

static void unwatch_fd(int fd) {
  if (fd >= 0 && fd < kWatchedFds)
    watched_fds[fd / kWatchedBits].fetch_and(~(1UL << (fd % kWatchedBits)), std::memory_order_release);
}

static bool watched(int fd) {
  if (fd < 0) return false;
  if (fd >= kWatchedFds) return watched_beyond.load(std::memory_order_acquire);
  return (watched_fds[fd / kWatchedBits].load(std::memory_order_acquire) >> (fd % kWatchedBits)) & 1;
}

static const char *fake_hook_dir() { return config().hook_dir; }
static const char *fake_udev_data_dir() { return config().udev_data_dir; }
static bool fake_vibration_enabled() { return config().vibration_enabled; }
static bool fake_xbox360_identity() { return config().xbox360_identity; }
static bool fake_uinput_enabled() { return config().uinput_enabled; }

static bool ring_paths_loaded = false;
static int next_ff_id = 0;

static void controller_fork_prepare() { controller_mutex().lock(); }
static void controller_fork_parent() { controller_mutex().unlock(); }

static void controller_fork_child() {
  pthread_mutexattr_t attr;

  pthread_mutexattr_init(&attr);
  pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
  pthread_mutex_init(controller_mutex().native_handle(), &attr);
  pthread_mutexattr_destroy(&attr);
}

namespace Logger {
int log_enabled;
static std::atomic<int> log_fd{-1};
static std::atomic<bool> log_unusable{false};
static char log_path[PATH_MAX];
static char log_name[17];

void init() {
  log_enabled = getenv("FAKE_EVDEV_LOG") && atoi(getenv("FAKE_EVDEV_LOG"));
  const char *path = getenv("FAKE_EVDEV_LOG_FILE");
  if (log_enabled && path && *path && strlen(path) < sizeof(log_path)) strcpy(log_path, path);
  if (prctl(PR_GET_NAME, log_name) != 0) strcpy(log_name, "?");
}

static int log_file() {
  int fd = log_fd.load(std::memory_order_acquire);
  if (fd >= 0 || !log_path[0] || log_unusable.load(std::memory_order_relaxed)) return fd;
  fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, log_path, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0644));
  if (fd < 0) {
    log_unusable.store(true, std::memory_order_relaxed);
    return -1;
  }
  int expected = -1;
  if (!log_fd.compare_exchange_strong(expected, fd, std::memory_order_acq_rel)) {
    syscall(SYS_close, fd);
    fd = expected;
  }
  return fd;
}

void log(const char *message, ...) {
  if (!log_enabled)
    return;

  int fd = log_file();
  if (fd < 0) {
    va_list args;
    va_start(args, message);
    vfprintf(stderr, message, args);
    va_end(args);
    std::cerr.flush();
    return;
  }
  char line[512];
  struct timespec now;
  clock_gettime(CLOCK_REALTIME, &now);
  struct tm local;
  localtime_r(&now.tv_sec, &local);
  int n = snprintf(line, sizeof(line), "%02d:%02d:%02d.%03ld %d %s: ", local.tm_hour, local.tm_min,
                   local.tm_sec, now.tv_nsec / 1000000, getpid(), log_name);
  va_list args;
  va_start(args, message);
  if (n > 0 && n < static_cast<int>(sizeof(line)))
    n += vsnprintf(line + n, sizeof(line) - n, message, args);
  va_end(args);
  if (n <= 0) return;
  if (n >= static_cast<int>(sizeof(line))) {
    n = sizeof(line) - 1;
    line[n - 1] = '\n';
  }
  ssize_t ignored = write(fd, line, n);
  (void)ignored;
}
}

__attribute__((constructor)) static void library_init() {
  config();
  controller_mutex();
  controller_map();
}

static void hookup_ff_from_game(uint16_t slot, int strong, int weak, uint16_t length);

__attribute__((visibility("hidden"))) static void
send_vibration(int strong, int weak, uint16_t duration_ms, uint16_t slot) {
  hookup_ff_from_game(slot, strong, weak, duration_ms);
  if (!fake_vibration_enabled())
    return;

  int sock = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
  if (sock < 0)
    return;

  struct sockaddr_un addr = {};
  addr.sun_family = AF_UNIX;
  const char *name = "droiddeck-rumble";
  memcpy(addr.sun_path + 1, name, strlen(name));
  socklen_t addrlen = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name);

  if (connect(sock, reinterpret_cast<struct sockaddr *>(&addr), addrlen) < 0) {
    syscall(SYS_close, sock);
    return;
  }

  uint16_t data[4];
  data[0] = static_cast<uint16_t>(strong);
  data[1] = static_cast<uint16_t>(weak);
  data[2] = duration_ms;
  data[3] = slot;
  send(sock, data, sizeof(data), MSG_DONTWAIT | MSG_NOSIGNAL);
  syscall(SYS_close, sock);
}

// Must match RumbleComponent.HOLD_MS: play until the slot's next effect or stop.
static constexpr uint16_t RUMBLE_HOLD_MS = 0xffff;

__attribute__((visibility("hidden"))) static void send_ff_effect(const struct ff_effect &effect, uint16_t slot) {
  uint16_t duration = effect.replay.length ? effect.replay.length : RUMBLE_HOLD_MS;
  if (effect.type == FF_RUMBLE)
    send_vibration(effect.u.rumble.strong_magnitude, effect.u.rumble.weak_magnitude, duration, slot);
  else if (effect.type == FF_PERIODIC)
    send_vibration(effect.u.periodic.magnitude, effect.u.periodic.magnitude, duration, slot);
}

__attribute__((visibility("hidden"))) static void
check_ff_event(const struct input_event *ev, uint16_t slot) {
  if (ev->type != EV_FF)
    return;

  int id = ev->code;
  if (ev->value > 0) {
    auto it = ff_effects().find(id);
    if (it == ff_effects().end())
      return;
    send_ff_effect(it->second, slot);
  } else {
    send_vibration(0, 0, 0, slot);
  }
}

__attribute__((visibility("hidden"))) char *
from_real_to_fake_path(const char *pathname) {
  const char *event = strrchr(pathname, '/') + 1;
  char *fake_path = nullptr;
  if (asprintf(&fake_path, "%s/%s", fake_hook_dir(), event) < 0)
    fake_path = nullptr;
  return fake_path;
}

__attribute__((visibility("hidden"))) static bool path_exists(const char *path) {
  return path && faccessat(AT_FDCWD, path, F_OK, 0) == 0;
}

__attribute__((visibility("hidden"))) static bool
is_fake_input_node_path(const char *pathname) {
  return pathname && (!strncmp(pathname, "/dev/input/event", 16) ||
                      !strncmp(pathname, "/dev/input/js", 13));
}

__attribute__((visibility("hidden"))) static bool
is_fake_udev_data_path(const char *pathname) {
  return pathname && fake_udev_data_dir() && *fake_udev_data_dir() &&
         !strncmp(pathname, "/run/udev/data/c13:", 19);
}

__attribute__((visibility("hidden"))) char *
from_real_to_fake_udev_data_path(const char *pathname) {
  const char *name = strrchr(pathname, '/') + 1;
  char *fake_path = nullptr;
  if (asprintf(&fake_path, "%s/%s", fake_udev_data_dir(), name) < 0)
    fake_path = nullptr;
  return fake_path;
}

__attribute__((visibility("hidden"))) const char *
get_event(const char *pathname) {
  const char *event = strrchr(pathname, '/') + 1;
  return event;
}

__attribute__((visibility("hidden"))) int get_event_number(const char *event) {
  if (!event)
    return -1;

  const char *digits = event;
  while (*digits && (*digits < '0' || *digits > '9'))
    digits++;

  return *digits ? atoi(digits) : -1;
}

__attribute__((visibility("hidden"))) static dev_t
get_fake_input_rdev(const char *event) {
  int event_number = get_event_number(event);
  if (event_number < 0)
    return makedev(FAKE_INPUT_MAJOR, 0);

  if (!strncmp(event, "event", 5))
    return makedev(FAKE_INPUT_MAJOR, FAKE_INPUT_EVENT_MINOR_BASE + event_number);
  if (!strncmp(event, "js", 2))
    return makedev(FAKE_INPUT_MAJOR, FAKE_INPUT_JS_MINOR_BASE + event_number);

  return makedev(FAKE_INPUT_MAJOR, event_number);
}

__attribute__((visibility("hidden"))) static void load_ring_paths() {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  if (ring_paths_loaded)
    return;

  const char *spec = getenv("FAKE_EVDEV_MEMFD_PATHS");
  if (!spec || !*spec) {
    ring_paths_loaded = true;
    return;
  }

  char *copy = strdup(spec);
  if (!copy)
    return;

  char *saveptr = nullptr;
  for (char *token = strtok_r(copy, ";", &saveptr); token;
       token = strtok_r(nullptr, ";", &saveptr)) {
    char *equals = strchr(token, '=');
    if (!equals)
      continue;
    *equals = '\0';
    int slot = atoi(token);
    const char *path = equals + 1;
    if (slot >= 0 && *path)
      ring_paths()[slot] = path;
  }

  free(copy);
  ring_paths_loaded = true;
}

__attribute__((visibility("hidden"))) static std::string
get_ring_path_for_slot(int slot) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  load_ring_paths();
  auto it = ring_paths().find(slot);
  return it == ring_paths().end() ? std::string() : it->second;
}

__attribute__((visibility("hidden"))) static uint64_t
ring_write_seq(const FakeInputRingHeader *ring) {
  return __atomic_load_n(&ring->write_seq, __ATOMIC_ACQUIRE);
}

__attribute__((visibility("hidden"))) static uint64_t
ring_generation(const FakeInputRingHeader *ring) {
  return __atomic_load_n(&ring->generation, __ATOMIC_ACQUIRE);
}

__attribute__((visibility("hidden"))) static uint32_t
ring_resync_seq(const FakeInputRingHeader *ring) {
  return __atomic_load_n(&ring->resync_seq, __ATOMIC_ACQUIRE);
}

__attribute__((visibility("hidden"))) static bool
ring_header_is_valid(const FakeInputRingHeader *ring) {
  return ring && ring->magic == FAKE_INPUT_RING_MAGIC &&
         ring->version == FAKE_INPUT_RING_VERSION &&
         ring->event_size == FAKE_INPUT_EVENT_SIZE &&
         ring->capacity == FAKE_INPUT_RING_CAPACITY;
}

struct SnapshotState {
  uint64_t sequence = 0;
  uint64_t write_seq = 0;
  uint64_t generation = 0;
  uint32_t resync_seq = 0;
  uint32_t buttons = 0;
  int32_t axes[8] = {};
};

static long long monotonic_ms();

__attribute__((visibility("hidden"))) static bool
read_snapshot(const FakeInputRingHeader *ring, SnapshotState &out) {
  for (int attempt = 0; attempt < 8; attempt++) {
    uint64_t sequence = __atomic_load_n(&ring->snapshot_seq, __ATOMIC_ACQUIRE);
    if (sequence & 1ULL) continue;
    out.sequence = sequence;
    out.write_seq = ring_write_seq(ring);
    out.generation = ring_generation(ring);
    out.resync_seq = ring_resync_seq(ring);
    out.buttons = __atomic_load_n(&ring->snapshot_buttons, __ATOMIC_RELAXED);
    for (int i = 0; i < 8; i++)
      out.axes[i] = __atomic_load_n(&ring->snapshot_axes[i], __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_ACQUIRE);
    if (sequence == __atomic_load_n(&ring->snapshot_seq, __ATOMIC_RELAXED))
      return true;
  }
  return false;
}

__attribute__((visibility("hidden"))) static void
capture_keyframe(FakeController &fake, const SnapshotState &snap) {
  fake.keyframe_buttons = snap.buttons;
  for (int i = 0; i < 8; i++) fake.keyframe_axes[i] = snap.axes[i];
  fake.keyframe_remaining = kNeutralEventCount;
  fake.read_seq = snap.write_seq;
  fake.resync_seq = snap.resync_seq;
  fake.needs_keyframe = false;
}

__attribute__((visibility("hidden"))) static int32_t
keyframe_value(const FakeController &fake, uint16_t type, uint16_t code) {
  if (type == EV_KEY) {
    for (size_t i = 0; i < kSnapshotButtonCount; i++)
      if (kSnapshotButtons[i] == code)
        return (fake.keyframe_buttons >> i) & 1u;
    return 0;
  }
  if (type == EV_ABS) {
    for (int i = 0; i < 8; i++)
      if (kSnapshotAxisCodes[i] == code)
        return fake.keyframe_axes[i];
  }
  return 0;
}

__attribute__((visibility("hidden"))) static std::string uinput_ring_path(int node) {
  return std::string(fake_hook_dir()) + "/.uinput-event" + std::to_string(node);
}

__attribute__((visibility("hidden"))) static bool process_alive(pid_t pid) {
  return pid > 0 && (kill(pid, 0) == 0 || errno == EPERM);
}

__attribute__((visibility("hidden"))) static int
open_fake_input_ring(const char *event, int flags) {
  int slot = get_event_number(event);
  bool virtual_pad = slot >= UINPUT_EVENT_BASE;
  std::string ring_path = virtual_pad ? uinput_ring_path(slot) : get_ring_path_for_slot(slot);
  size_t mapping_size = virtual_pad ? UINPUT_FILE_SIZE : FAKE_INPUT_RING_SIZE;
  if (ring_path.empty()) {
    Logger::log("No input ring is configured for %s (slot %d)\n", event, slot);
    errno = ENODEV;
    return -1;
  }

  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));

  int fd = my_open(ring_path.c_str(), O_RDWR | (flags & (O_NONBLOCK | O_CLOEXEC)));
  if (fd < 0)
    return -1;

  void *mapping = mmap(nullptr, mapping_size, PROT_READ, MAP_SHARED, fd, 0);
  if (mapping == MAP_FAILED) {
    int saved_errno = errno;
    syscall(SYS_close, fd);
    errno = saved_errno;
    return -1;
  }

  FakeInputRingHeader *ring =
      reinterpret_cast<FakeInputRingHeader *>(mapping);
  const UinputDescription *description =
      virtual_pad ? reinterpret_cast<const UinputDescription *>(
                        static_cast<const uint8_t *>(mapping) + FAKE_INPUT_RING_SIZE)
                  : nullptr;
  if (!ring_header_is_valid(ring) ||
      (description && (description->magic != UINPUT_DESCRIPTION_MAGIC ||
                       !process_alive(description->owner_pid)))) {
    Logger::log("Input ring %s has no valid header\n", ring_path.c_str());
    munmap(mapping, mapping_size);
    syscall(SYS_close, fd);
    errno = ENODEV;
    return -1;
  }

  auto controller = std::make_shared<FakeController>();
  controller->event = strdup(event);
  controller->slot = slot;
  controller->ring = ring;
  controller->uinput = description;
  controller->mapping_size = mapping_size;
  controller->generation = ring_generation(ring);
  SnapshotState snap;
  if (read_snapshot(ring, snap) && snap.generation == controller->generation)
    capture_keyframe(*controller, snap);
  {
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    controller_map()[fd] = controller;
    watch_fd(fd);
  }

  Logger::log("Adding ring-backed controller, fd %d event %s slot %d\n", fd,
              event, slot);
  return fd;
}

__attribute__((visibility("hidden"))) static bool is_steam_client() {
  static int known;
  if (known == 0) {
    char exe[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    bool client = false;
    if (length > 0) {
      exe[length] = '\0';
      const char *name = strrchr(exe, '/');
      client = !strncmp(name ? name + 1 : exe, "steam", 5);
    }
    known = client ? 1 : -1;
  }
  return known == 1;
}

__attribute__((visibility("hidden"))) static bool presents_steam_virtual() {
  static int known;
  if (known == 0) {
    const char *enabled = getenv("FAKE_EVDEV_STEAM_VIRTUAL");
    known = enabled && atoi(enabled) && !is_steam_client() ? 1 : -1;
  }
  return known == 1;
}

__attribute__((visibility("hidden"))) static bool
presents_xbox_triggers(const FakeController &fake) {
  return fake.uinput || !is_steam_client();
}

__attribute__((visibility("hidden"))) static uint16_t
presented_abs_code(const FakeController &fake, uint16_t code) {
  if (!presents_xbox_triggers(fake)) return code;
  if (code == ABS_BRAKE) return ABS_Z;
  if (code == ABS_GAS) return ABS_RZ;
  return code;
}

__attribute__((visibility("hidden"))) static uint16_t snapshot_abs_code(uint16_t code) {
  if (code == ABS_Z) return ABS_BRAKE;
  if (code == ABS_RZ) return ABS_GAS;
  return code;
}

__attribute__((visibility("hidden"))) static uint16_t
ring_abs_code(const FakeController &fake, uint16_t code) {
  return presents_xbox_triggers(fake) ? snapshot_abs_code(code) : code;
}

__attribute__((visibility("hidden"))) static void
copy_slot_ioctl_string(int op, void *argp, const char *format, int event_number) {
  size_t size = _IOC_SIZE(op);
  if (!argp || size == 0)
    return;

  snprintf(static_cast<char *>(argp), size, format, event_number);
}

__attribute__((visibility("hidden"))) static bool is_fake_input_fd(int fd) {
  if (!watched(fd)) return false;
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  return controller_map().find(fd) != controller_map().end();
}

static bool fake_pad_withdrawn(const FakeController &fake);

static bool controller_gone(const FakeController &fake) {
  return ring_generation(fake.ring) != fake.generation || fake_pad_withdrawn(fake);
}

__attribute__((visibility("hidden"))) static bool fake_fd_is_stale(int fd) {
  if (!watched(fd)) return false;
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  auto controller = controller_map().find(fd);
  return controller != controller_map().end() && controller_gone(*controller->second);
}

static bool fake_has_unread_data(const FakeController &fake) {
  if (controller_gone(fake))
    return false;
  if (fake.keyframe_remaining > 0) return true;
  uint64_t sequence = __atomic_load_n(&fake.ring->snapshot_seq, __ATOMIC_ACQUIRE);
  if (sequence & 1ULL) return false;
  bool ready = fake.needs_keyframe ||
               ring_resync_seq(fake.ring) != fake.resync_seq ||
               ring_write_seq(fake.ring) != fake.read_seq;
  __atomic_thread_fence(__ATOMIC_ACQUIRE);
  return ready && sequence == __atomic_load_n(&fake.ring->snapshot_seq, __ATOMIC_RELAXED);
}

static bool fake_fd_has_unread_data(int fd) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  auto it = controller_map().find(fd);
  return it != controller_map().end() && fake_has_unread_data(*it->second);
}

static short fake_poll_revents(const std::shared_ptr<FakeController> &fake, short events) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  if (fake->closed) return POLLNVAL;
  if (controller_gone(*fake)) return POLLHUP;
  short ready = events & (POLLOUT | POLLWRNORM);
  if ((events & (POLLIN | POLLRDNORM)) && fake_has_unread_data(*fake))
    ready |= events & (POLLIN | POLLRDNORM);
  return ready;
}

__attribute__((visibility("hidden"))) static long long
timespec_to_ms(const struct timespec *timeout) {
  if (!timeout)
    return -1;
  return static_cast<long long>(timeout->tv_sec) * 1000LL +
         timeout->tv_nsec / 1000000LL;
}

__attribute__((visibility("hidden"))) static long long
timeval_to_ms(const struct timeval *timeout) {
  if (!timeout)
    return -1;
  return static_cast<long long>(timeout->tv_sec) * 1000LL +
         timeout->tv_usec / 1000LL;
}

__attribute__((visibility("hidden"))) static long long monotonic_ms() {
  struct timespec now = {};
  clock_gettime(CLOCK_MONOTONIC, &now);
  return static_cast<long long>(now.tv_sec) * 1000LL + now.tv_nsec / 1000000LL;
}

struct UinputWriter {
  UinputDescription description{};
  bool created = false;
  bool published = false;
  int node = -1;
  pid_t owner = 0;
  FakeInputRingHeader *ring = nullptr;
  UinputDescription *shared = nullptr;
  std::vector<struct input_event> pending;
  uint32_t buttons = 0;
  int16_t axes[8] = {};
  std::deque<struct input_event> ff_queue;  // sdl3bridge: game rumble handed to Steam
  uint64_t ff_seen = 0;
  uint32_t ff_request = 0;
  struct ff_effect ff_effect = {};
  uint64_t paced_us = 0;  // sdl3bridge: when the game last got a frame (virtual pad pacing)
  bool held = false, flush_now = false;
};

static std::unordered_map<int, std::shared_ptr<UinputWriter>> &uinput_map() {
  static auto *map = new std::unordered_map<int, std::shared_ptr<UinputWriter>>();
  return *map;
}

__attribute__((visibility("hidden"))) static bool is_uinput_path(const char *pathname) {
  return pathname && (!strcmp(pathname, "/dev/uinput") || !strcmp(pathname, "/dev/input/uinput"));
}

__attribute__((visibility("hidden"))) static void set_bit(uint8_t *bits, size_t size, int bit) {
  if (bit >= 0 && static_cast<size_t>(bit) / 8 < size) bits[bit / 8] |= 1u << (bit % 8);
}

__attribute__((visibility("hidden"))) static bool test_bit(const uint8_t *bits, size_t size, int bit) {
  return bit >= 0 && static_cast<size_t>(bit) / 8 < size && (bits[bit / 8] >> (bit % 8)) & 1u;
}

__attribute__((visibility("hidden"))) static int open_uinput(int flags) {
  int fd = eventfd(0, ((flags & O_NONBLOCK) ? EFD_NONBLOCK : 0) | ((flags & O_CLOEXEC) ? EFD_CLOEXEC : 0));
  if (fd < 0) return -1;
  auto writer = std::make_shared<UinputWriter>();
  writer->owner = getpid();
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  uinput_map()[fd] = writer;
  watch_fd(fd);
  Logger::log("uinput: opened as fd %d\n", fd);
  return fd;
}

__attribute__((visibility("hidden"))) static bool is_gamepad(const UinputDescription &d) {
  return test_bit(d.evbits, sizeof(d.evbits), EV_KEY) && test_bit(d.keybits, sizeof(d.keybits), BTN_SOUTH) &&
         test_bit(d.evbits, sizeof(d.evbits), EV_ABS) && test_bit(d.absbits, sizeof(d.absbits), ABS_X);
}

__attribute__((visibility("hidden"))) static void unpublish_uinput(UinputWriter &w) {
  if (!w.published) return;
  __atomic_add_fetch(&w.ring->generation, 1, __ATOMIC_RELEASE);
  std::string node = std::string(fake_hook_dir()) + "/event" + std::to_string(w.node);
  unlink(node.c_str());
  unlink((uinput_ring_path(w.node) + ".uevent").c_str());
  unlink(uinput_ring_path(w.node).c_str());
  munmap(w.ring, UINPUT_FILE_SIZE);
  Logger::log("uinput: removed event%d\n", w.node);
  w.ring = nullptr;
  w.shared = nullptr;
  w.published = false;
}

__attribute__((visibility("hidden"))) static int claim_uinput_node(int *fd_out) {
  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));
  for (int node = UINPUT_EVENT_BASE; node < UINPUT_EVENT_LIMIT; node++) {
    std::string path = uinput_ring_path(node);
    int fd = my_open(path.c_str(), O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0644);
    if (fd >= 0) {
      *fd_out = fd;
      return node;
    }
    if (errno != EEXIST) return -1;
    int existing = my_open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (existing < 0) continue;
    UinputDescription old{};
    bool stale = pread(existing, &old, sizeof(old), FAKE_INPUT_RING_SIZE) == sizeof(old) &&
                 old.magic == UINPUT_DESCRIPTION_MAGIC && !process_alive(old.owner_pid);
    syscall(SYS_close, existing);
    if (stale) {
      unlink((std::string(fake_hook_dir()) + "/event" + std::to_string(node)).c_str());
      unlink((path + ".uevent").c_str());
      unlink(path.c_str());
      node--;
    }
  }
  errno = ENOSPC;
  return -1;
}

__attribute__((visibility("hidden"))) static int publish_uinput(UinputWriter &w) {
  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));
  int fd = -1;
  int node = claim_uinput_node(&fd);
  if (node < 0) return -1;
  void *mapping = MAP_FAILED;
  if (ftruncate(fd, UINPUT_FILE_SIZE) == 0)
    mapping = mmap(nullptr, UINPUT_FILE_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  syscall(SYS_close, fd);
  std::string ring_path = uinput_ring_path(node);
  if (mapping == MAP_FAILED) {
    unlink(ring_path.c_str());
    return -1;
  }
  auto *ring = static_cast<FakeInputRingHeader *>(mapping);
  ring->magic = FAKE_INPUT_RING_MAGIC;
  ring->version = FAKE_INPUT_RING_VERSION;
  ring->event_size = FAKE_INPUT_EVENT_SIZE;
  ring->capacity = FAKE_INPUT_RING_CAPACITY;
  ring->generation = 1;
  auto *shared = reinterpret_cast<UinputDescription *>(static_cast<uint8_t *>(mapping) + FAKE_INPUT_RING_SIZE);
  *shared = w.description;
  shared->magic = UINPUT_DESCRIPTION_MAGIC;
  shared->owner_pid = w.owner;
  __atomic_thread_fence(__ATOMIC_RELEASE);

  char uevent[256];
  int length = snprintf(uevent, sizeof(uevent), "PRODUCT=%x/%x/%x/%x\nNAME=\"%s\"\nPHYS=\"%s\"\n",
                        shared->id.bustype, shared->id.vendor, shared->id.product, shared->id.version,
                        shared->name, shared->phys);
  int uevent_fd = my_open((ring_path + ".uevent").c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
  if (uevent_fd >= 0) {
    if (write(uevent_fd, uevent, std::min<size_t>(length, sizeof(uevent) - 1)) < 0) {}
    syscall(SYS_close, uevent_fd);
  }
  std::string node_path = std::string(fake_hook_dir()) + "/event" + std::to_string(node);
  int node_fd = my_open(node_path.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, 0644);
  if (node_fd < 0) {
    munmap(mapping, UINPUT_FILE_SIZE);
    unlink((ring_path + ".uevent").c_str());
    unlink(ring_path.c_str());
    return -1;
  }
  syscall(SYS_close, node_fd);
  w.node = node;
  w.ring = ring;
  w.shared = shared;
  w.published = true;
  Logger::log("uinput: \"%s\" %04x:%04x is /dev/input/event%d\n", shared->name, shared->id.vendor,
              shared->id.product, node);
  return 0;
}

static void hookup_meter_virtual_pad(const std::vector<struct input_event> &events);
static bool virtual_pad_hold(UinputWriter &w);

__attribute__((visibility("hidden"))) static void flush_uinput(UinputWriter &w) {
  if (w.published && !w.flush_now) hookup_meter_virtual_pad(w.pending);
  if (w.published && virtual_pad_hold(w)) return;  // sdl3bridge: stick / trigger moves paced for the game
  if (!w.published || w.pending.empty()) {
    w.pending.clear();
    return;
  }
  FakeInputRingHeader *ring = w.ring;
  auto *events = reinterpret_cast<RingEvent *>(reinterpret_cast<uint8_t *>(ring) +
                                               FAKE_INPUT_RING_HEADER_SIZE);
  uint64_t write_seq = __atomic_load_n(&ring->write_seq, __ATOMIC_RELAXED);
  struct timeval now = {};
  gettimeofday(&now, nullptr);
  for (const struct input_event &ev : w.pending) {
    events[write_seq % FAKE_INPUT_RING_CAPACITY] = {now.tv_sec, now.tv_usec, ev.type, ev.code, ev.value};
    write_seq++;
    if (ev.type == EV_KEY) {
      for (size_t i = 0; i < kSnapshotButtonCount; i++)
        if (kSnapshotButtons[i] == ev.code)
          w.buttons = ev.value ? (w.buttons | (1u << i)) : (w.buttons & ~(1u << i));
    } else if (ev.type == EV_ABS) {
      uint16_t code = snapshot_abs_code(ev.code);
      for (int i = 0; i < 8; i++)
        if (kSnapshotAxisCodes[i] == code)
          w.axes[i] = static_cast<int16_t>(std::max(-32768, std::min(32767, ev.value)));
    }
  }
  w.pending.clear();
  uint64_t sequence = __atomic_load_n(&ring->snapshot_seq, __ATOMIC_RELAXED);
  __atomic_store_n(&ring->snapshot_seq, sequence + 1, __ATOMIC_RELAXED);
  __atomic_thread_fence(__ATOMIC_RELEASE);
  __atomic_store_n(&ring->snapshot_buttons, w.buttons, __ATOMIC_RELAXED);
  for (int i = 0; i < 8; i++) __atomic_store_n(&ring->snapshot_axes[i], w.axes[i], __ATOMIC_RELAXED);
  __atomic_thread_fence(__ATOMIC_RELEASE);
  __atomic_store_n(&ring->snapshot_seq, sequence + 2, __ATOMIC_RELEASE);
  __atomic_store_n(&ring->write_seq, write_seq, __ATOMIC_RELEASE);
}

__attribute__((visibility("hidden"))) static ssize_t
write_uinput(UinputWriter &w, const void *buf, size_t count) {
  if (!w.created) {
    if (count != sizeof(struct uinput_user_dev)) {
      errno = EINVAL;
      return -1;
    }
    const auto *legacy = static_cast<const struct uinput_user_dev *>(buf);
    memcpy(w.description.name, legacy->name, sizeof(w.description.name));
    w.description.name[sizeof(w.description.name) - 1] = '\0';
    w.description.id = legacy->id;
    w.description.ff_effects_max = legacy->ff_effects_max;
    for (int i = 0; i < ABS_CNT; i++) {
      w.description.absinfo[i].minimum = legacy->absmin[i];
      w.description.absinfo[i].maximum = legacy->absmax[i];
      w.description.absinfo[i].fuzz = legacy->absfuzz[i];
      w.description.absinfo[i].flat = legacy->absflat[i];
    }
    return static_cast<ssize_t>(count);
  }
  if (count % sizeof(struct input_event)) {
    errno = EINVAL;
    return -1;
  }
  const auto *events = static_cast<const struct input_event *>(buf);
  for (size_t i = 0; i < count / sizeof(struct input_event); i++) {
    w.pending.push_back(events[i]);
    if ((events[i].type == EV_SYN && events[i].code == SYN_REPORT) || w.pending.size() >= 64)
      flush_uinput(w);
  }
  return static_cast<ssize_t>(count);
}

__attribute__((visibility("hidden"))) static int
ioctl_uinput(UinputWriter &w, ioctl_request_t op, void *argp) {
  if (_IOC_TYPE(op) != UINPUT_IOCTL_BASE) {
    errno = ENOTTY;
    return -1;
  }
  int number = _IOC_NR(op);
  int value = static_cast<int>(reinterpret_cast<intptr_t>(argp));
  UinputDescription &d = w.description;
  if (number == _IOC_NR(UI_GET_VERSION)) {
    *static_cast<unsigned int *>(argp) = 5;
    return 0;
  }
  if (number == _IOC_NR(UI_GET_SYSNAME(0))) {
    if (!w.created) {
      errno = ENOENT;
      return -1;
    }
    snprintf(static_cast<char *>(argp), _IOC_SIZE(op), "input%d", w.published ? w.node : 0);
    return 0;
  }
  if (number == _IOC_NR(UI_DEV_DESTROY)) {
    if (getpid() == w.owner) unpublish_uinput(w);
    w.created = false;
    return 0;
  }
  if (w.created && number != _IOC_NR(UI_BEGIN_FF_UPLOAD) && number != _IOC_NR(UI_END_FF_UPLOAD) &&
      number != _IOC_NR(UI_BEGIN_FF_ERASE) && number != _IOC_NR(UI_END_FF_ERASE)) {
    errno = EINVAL;
    return -1;
  }
  switch (number) {
  case _IOC_NR(UI_SET_EVBIT): set_bit(d.evbits, sizeof(d.evbits), value); return 0;
  case _IOC_NR(UI_SET_KEYBIT): set_bit(d.keybits, sizeof(d.keybits), value); return 0;
  case _IOC_NR(UI_SET_ABSBIT): set_bit(d.absbits, sizeof(d.absbits), value); return 0;
  case _IOC_NR(UI_SET_FFBIT): set_bit(d.ffbits, sizeof(d.ffbits), value); return 0;
  case _IOC_NR(UI_SET_RELBIT):
  case _IOC_NR(UI_SET_MSCBIT):
  case _IOC_NR(UI_SET_LEDBIT):
  case _IOC_NR(UI_SET_SNDBIT):
  case _IOC_NR(UI_SET_SWBIT):
  case _IOC_NR(UI_SET_PROPBIT):
    return 0;
  case _IOC_NR(UI_SET_PHYS):
    snprintf(d.phys, sizeof(d.phys), "%s", static_cast<const char *>(argp));
    return 0;
  case _IOC_NR(UI_DEV_SETUP): {
    const auto *setup = static_cast<const struct uinput_setup *>(argp);
    d.id = setup->id;
    memcpy(d.name, setup->name, sizeof(d.name));
    d.name[sizeof(d.name) - 1] = '\0';
    d.ff_effects_max = setup->ff_effects_max;
    return 0;
  }
  case _IOC_NR(UI_ABS_SETUP): {
    const auto *setup = static_cast<const struct uinput_abs_setup *>(argp);
    if (setup->code >= ABS_CNT) {
      errno = EINVAL;
      return -1;
    }
    d.absinfo[setup->code] = setup->absinfo;
    return 0;
  }
  case _IOC_NR(UI_DEV_CREATE):
    w.created = true;
    if (!is_gamepad(d)) {
      Logger::log("uinput: \"%s\" %04x:%04x is not a gamepad; its events are dropped\n", d.name,
                  d.id.vendor, d.id.product);
      return 0;
    }
    if (publish_uinput(w) < 0) {
      Logger::log("uinput: could not publish \"%s\": %s\n", d.name, strerror(errno));
      w.created = false;
      errno = ENOMEM;
      return -1;
    }
    return 0;
  case _IOC_NR(UI_BEGIN_FF_UPLOAD): {
    auto *upload = static_cast<struct uinput_ff_upload *>(argp);
    if (!upload || upload->request_id != w.ff_request) {
      errno = EINVAL;
      return -1;
    }
    upload->retval = 0;
    upload->effect = w.ff_effect;
    memset(&upload->old, 0, sizeof(upload->old));
    return 0;
  }
  case _IOC_NR(UI_END_FF_UPLOAD):
  case _IOC_NR(UI_END_FF_ERASE):
    return 0;
  case _IOC_NR(UI_BEGIN_FF_ERASE): {
    auto *erase = static_cast<struct uinput_ff_erase *>(argp);
    if (erase) erase->retval = 0;
    return 0;
  }
  default:
    Logger::log("uinput: unhandled ioctl %d\n", number);
    errno = EINVAL;
    return -1;
  }
}

__attribute__((visibility("hidden"))) static void close_uinput(int fd) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  auto it = uinput_map().find(fd);
  if (it == uinput_map().end()) return;
  if (getpid() == it->second->owner) unpublish_uinput(*it->second);
  uinput_map().erase(it);
}

static constexpr const char *DECK_HIDRAW_PATH = "/dev/hidraw16";
static constexpr unsigned int DECK_HIDRAW_MAJOR = 240;
static constexpr unsigned int DECK_HIDRAW_MINOR = 16;
static constexpr int DECK_REPORT_BYTES = 64;
static constexpr int DECK_REPORT_INTERVAL_US = 4000;
static constexpr const char *DECK_NAME = "Valve Software Steam Deck Controller";
static constexpr const char *DECK_SERIAL = "DROIDDECK0001";

static const uint8_t kDeckReportDescriptor[] = {
    0x06, 0xff, 0xff, 0x09, 0x01, 0xa1, 0x01, 0x09, 0x02, 0x09, 0x03, 0x15, 0x00,
    0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x81, 0x02, 0x09, 0x06, 0x09, 0x07,
    0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0xb1, 0x02, 0xc0};

static constexpr uint8_t DECK_GET_ATTRIBUTES_VALUES = 0x83;
static constexpr uint8_t DECK_GET_STRING_ATTRIBUTE = 0xAE;
static constexpr uint8_t DECK_GET_CHIP_ID = 0xBA;
static constexpr uint8_t DECK_TRIGGER_RUMBLE_CMD = 0xEB;

static constexpr uint32_t DECK_L_R2 = 0x00000001, DECK_L_L2 = 0x00000002, DECK_L_R1 = 0x00000004,
                          DECK_L_L1 = 0x00000008, DECK_L_Y = 0x00000010, DECK_L_B = 0x00000020,
                          DECK_L_X = 0x00000040, DECK_L_A = 0x00000080, DECK_L_UP = 0x00000100,
                          DECK_L_RIGHT = 0x00000200, DECK_L_LEFT = 0x00000400,
                          DECK_L_DOWN = 0x00000800, DECK_L_VIEW = 0x00001000,
                          DECK_L_STEAM = 0x00002000, DECK_L_MENU = 0x00004000,
                          DECK_L_L3 = 0x00400000, DECK_L_R3 = 0x04000000;
static constexpr uint32_t DECK_L_L5 = 0x00008000, DECK_L_R5 = 0x00010000,
                          DECK_L_LEFT_PAD = 0x00020000, DECK_L_RIGHT_PAD = 0x00040000,
                          DECK_L_LEFT_TOUCH = 0x00080000, DECK_L_RIGHT_TOUCH = 0x00100000;
static constexpr uint32_t DECK_H_L4 = 0x00000200, DECK_H_R4 = 0x00000400;
static constexpr uint32_t DECK_H_QAM = 0x00040000;
static constexpr uint32_t SNAPSHOT_QAM_BIT = 1u << 11;

struct DeckHidraw {
  int peer = -1;
  FakeInputRingHeader *ring = nullptr;
  size_t mapping_size = 0;
  uint8_t pending_feature = 0;
  bool touch = false;
  bool triton = false;
  bool emulated = false;
  uint8_t sony = 0;
  bool sc1 = false;  // sdl3bridge: Steam Controller (2015)
  int8_t hookup = -1;  // sdl3bridge: Steam Native Hook-up slot
  uint32_t hookup_generation = 0;
  std::shared_ptr<struct HookupQueue> hookup_in;  // sdl3bridge: reports waiting for Steam (mailbox)
};

struct DeckImu {
  uint32_t magic;
  uint32_t imu_mode;
  uint64_t seq;
  int16_t accel[3];
  int16_t gyro[3];
  int16_t pads[4];
  uint16_t pressure[2];
  uint32_t controls;
  uint32_t pad_type;
  uint32_t latency;
};
static_assert(offsetof(DeckImu, pad_type) == 44, "DeckImu::pad_type matches FakeInputWriter");
static_assert(offsetof(DeckImu, latency) == 48, "DeckImu::latency matches FakeInputWriter");
static constexpr uint32_t LATENCY_CONTROLLER = 1, LATENCY_TOUCH = 2;
static constexpr int LOW_LATENCY_REPORT_INTERVAL_US = 1000;
static constexpr uint32_t DECK_EXTRA_L4 = 1, DECK_EXTRA_R4 = 2, DECK_EXTRA_L5 = 4,
                          DECK_EXTRA_R5 = 8, DECK_EXTRA_LPAD_TOUCH = 16,
                          DECK_EXTRA_RPAD_TOUCH = 32, DECK_EXTRA_LPAD_CLICK = 64,
                          DECK_EXTRA_RPAD_CLICK = 128;

struct DeckExtras {
  int16_t accel[3] = {};
  int16_t gyro[3] = {};
  int16_t pads[4] = {};
  uint16_t pressure[2] = {};
  uint32_t controls = 0;
};
static constexpr uint32_t DECK_IMU_MAGIC = 0x31554D49;
static constexpr size_t DECK_IMU_BLOCK_SIZE = 64;
static_assert(sizeof(DeckImu) <= DECK_IMU_BLOCK_SIZE, "the IMU block is 64 bytes in the ring file");

struct TritonSharedData {
  uint32_t magic;
  uint32_t connected;
  uint64_t input_seq;
  uint32_t report_id;
  uint32_t input_length;
  uint8_t input[64];
  uint64_t output_seq;
  uint16_t output_feature;
  uint16_t output_length;
  uint32_t output_ack;
  uint8_t output[64];
  uint64_t get_request;
  uint64_t get_reply;
  uint32_t get_report_id;
  int32_t get_length;
  uint8_t get[64];
};
static constexpr uint32_t TRITON_SHARED_MAGIC = 0x4e525454;
static constexpr size_t TRITON_SHARED_BLOCK_SIZE = 256;
static constexpr size_t TRITON_SHARED_OFFSET = FAKE_INPUT_RING_SIZE + DECK_IMU_BLOCK_SIZE;
static_assert(offsetof(TritonSharedData, connected) == 4, "Triton connected offset");
static_assert(offsetof(TritonSharedData, input_seq) == 8, "Triton input sequence offset");
static_assert(offsetof(TritonSharedData, report_id) == 16, "Triton report ID offset");
static_assert(offsetof(TritonSharedData, input) == 24, "Triton input offset");
static_assert(offsetof(TritonSharedData, output_seq) == 88, "Triton output sequence offset");
static_assert(offsetof(TritonSharedData, output_feature) == 96, "Triton output feature offset");
static_assert(offsetof(TritonSharedData, output_length) == 98, "Triton output length offset");
static_assert(offsetof(TritonSharedData, output_ack) == 100, "Triton output ack offset");
static_assert(offsetof(TritonSharedData, output) == 104, "Triton output offset");
static_assert(offsetof(TritonSharedData, get_request) == 168, "Triton feature request offset");
static_assert(offsetof(TritonSharedData, get_reply) == 176, "Triton feature reply offset");
static_assert(offsetof(TritonSharedData, get_report_id) == 184, "Triton feature report ID offset");
static_assert(offsetof(TritonSharedData, get_length) == 188, "Triton feature length offset");
static_assert(offsetof(TritonSharedData, get) == 192, "Triton feature data offset");
static_assert(sizeof(TritonSharedData) <= TRITON_SHARED_BLOCK_SIZE, "Triton ring block size");

static constexpr const char *TRITON_HIDRAW_PATH = "/dev/hidraw18";
static constexpr unsigned int TRITON_HIDRAW_MAJOR = 240;
static constexpr unsigned int TRITON_HIDRAW_MINOR = 18;
static constexpr const char *TRITON_PAD_HIDRAW_PATH = "/dev/hidraw19";
static constexpr unsigned int TRITON_PAD_HIDRAW_MINOR = 19;
static constexpr int TRITON_REPORT_ID_BYTES = 1;
static constexpr int TRITON_STATE_BYTES = 45;
static constexpr int TRITON_REPORT_INTERVAL_US = 4000;
static constexpr int TRITON_FEATURE_TIMEOUT_MS = 500;
// The app clears output_length once it has taken a report; a writer waits this long for that before overwriting.
static constexpr int TRITON_OUTPUT_TIMEOUT_MS = 50;
static constexpr const char *TRITON_NAME = "Steam Controller";
static constexpr const char *TRITON_SERIAL = "DROIDDECKSC01";
static const uint8_t kTritonReportDescriptor[] = {
    0x06, 0x00, 0xff, 0x09, 0x01, 0xa1, 0x01, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08,
    0x85, 0x45, 0x09, 0x02, 0x95, 0x2d, 0x81, 0x02, 0x85, 0x47, 0x09, 0x03, 0x95, 0x2d, 0x81, 0x02,
    0x85, 0x80, 0x09, 0x04, 0x95, 0x09, 0x91, 0x02, 0x85, 0x81, 0x09, 0x05, 0x95, 0x07, 0x91, 0x02,
    0x85, 0x82, 0x09, 0x06, 0x95, 0x03, 0x91, 0x02, 0x85, 0x83, 0x09, 0x07, 0x95, 0x09, 0x91, 0x02,
    0x85, 0x84, 0x09, 0x08, 0x95, 0x08, 0x91, 0x02, 0x85, 0x85, 0x09, 0x09, 0x95, 0x03, 0x91, 0x02,
    0x85, 0x01, 0x09, 0x0a, 0x95, 0x3f, 0xb1, 0x02, 0xc0};

__attribute__((visibility("hidden"))) static bool read_deck_extras(const DeckHidraw &deck, DeckExtras &out) {
  if (deck.mapping_size < FAKE_INPUT_RING_SIZE + DECK_IMU_BLOCK_SIZE) return false;
  const auto *imu = reinterpret_cast<const DeckImu *>(reinterpret_cast<const uint8_t *>(deck.ring) +
                                                      FAKE_INPUT_RING_SIZE);
  for (int attempt = 0; attempt < 4; attempt++) {
    uint64_t seq = __atomic_load_n(&imu->seq, __ATOMIC_ACQUIRE);
    if (seq & 1) continue;
    if (__atomic_load_n(&imu->magic, __ATOMIC_RELAXED) != DECK_IMU_MAGIC) return false;
    DeckExtras read;
    for (int i = 0; i < 3; i++) {
      read.accel[i] = __atomic_load_n(&imu->accel[i], __ATOMIC_RELAXED);
      read.gyro[i] = __atomic_load_n(&imu->gyro[i], __ATOMIC_RELAXED);
    }
    for (int i = 0; i < 4; i++) read.pads[i] = __atomic_load_n(&imu->pads[i], __ATOMIC_RELAXED);
    for (int i = 0; i < 2; i++) read.pressure[i] = __atomic_load_n(&imu->pressure[i], __ATOMIC_RELAXED);
    read.controls = __atomic_load_n(&imu->controls, __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_ACQUIRE);
    if (seq == __atomic_load_n(&imu->seq, __ATOMIC_RELAXED)) {
      out = read;
      return true;
    }
  }
  return false;
}

static std::unordered_map<int, std::shared_ptr<DeckHidraw>> &deck_map() {
  static auto *map = new std::unordered_map<int, std::shared_ptr<DeckHidraw>>();
  return *map;
}

static bool fake_deck_enabled() {
  static int enabled = -1;
  if (enabled < 0) enabled = getenv("FAKE_EVDEV_DECK") && atoi(getenv("FAKE_EVDEV_DECK")) ? 1 : 0;
  return enabled == 1;
}

static constexpr uint32_t PAD_TYPE_DECK = 0, PAD_TYPE_STEAM = 1, PAD_TYPE_XBOX = 2, PAD_TYPE_DS4 = 3,
                          PAD_TYPE_DUALSENSE = 4, PAD_TYPE_DUALSENSE_EDGE = 5,
                          PAD_TYPE_STEAM_2015 = 6,
                          PAD_TYPE_NONE = 7, PAD_TYPE_LAST = PAD_TYPE_NONE;
static constexpr uint32_t PAD_TYPE_SET = 0x80000000u;

__attribute__((visibility("hidden"))) static uint32_t session_pad_type() {
  static int type = -1;
  if (type < 0) {
    const char *env = getenv("FAKE_EVDEV_PAD_TYPE");
    type = env && !strcmp(env, "steamcontroller") ? PAD_TYPE_STEAM
         : env && !strcmp(env, "xbox360") ? PAD_TYPE_XBOX
         : env && !strcmp(env, "dualshock4") ? PAD_TYPE_DS4
         : env && !strcmp(env, "dualsense") ? PAD_TYPE_DUALSENSE : PAD_TYPE_DECK;
  }
  return static_cast<uint32_t>(type);
}

__attribute__((visibility("hidden"))) static const uint8_t *ring_blocks() {
  static std::atomic<const uint8_t *> block{nullptr};
  static std::atomic<int> tries{0};
  const uint8_t *mapped = block.load(std::memory_order_acquire);
  if (mapped || tries.load(std::memory_order_relaxed) > 200) return mapped;
  static std::mutex lock;
  std::lock_guard<std::mutex> guard(lock);
  if ((mapped = block.load(std::memory_order_acquire))) return mapped;
  tries.fetch_add(1, std::memory_order_relaxed);
  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));
  std::string path = get_ring_path_for_slot(0);
  int fd = path.empty() ? -1 : my_open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) return nullptr;
  struct stat st;
  void *mapping = MAP_FAILED;
  const size_t size = TRITON_SHARED_OFFSET + TRITON_SHARED_BLOCK_SIZE;
  if (fstat(fd, &st) == 0 && static_cast<size_t>(st.st_size) >= size)
    mapping = mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0);
  syscall(SYS_close, fd);
  if (mapping == MAP_FAILED) return nullptr;
  mapped = static_cast<const uint8_t *>(mapping);
  block.store(mapped, std::memory_order_release);
  return mapped;
}

__attribute__((visibility("hidden"))) static bool triton_connected() {
  const uint8_t *blocks = ring_blocks();
  if (!blocks) return false;
  const auto *shared = reinterpret_cast<const TritonSharedData *>(blocks + TRITON_SHARED_OFFSET);
  return __atomic_load_n(&shared->magic, __ATOMIC_ACQUIRE) == TRITON_SHARED_MAGIC &&
         __atomic_load_n(&shared->connected, __ATOMIC_ACQUIRE) != 0;
}

__attribute__((visibility("hidden"))) static uint32_t live_pad_type() {
  const uint8_t *blocks = ring_blocks();
  const DeckImu *imu = blocks ? reinterpret_cast<const DeckImu *>(blocks + FAKE_INPUT_RING_SIZE) : nullptr;
  uint32_t value = imu ? __atomic_load_n(&imu->pad_type, __ATOMIC_ACQUIRE) : 0;
  return value & PAD_TYPE_SET ? value & 0xff : session_pad_type();
}

__attribute__((visibility("hidden"))) static bool low_latency(uint32_t which) {
  const uint8_t *blocks = ring_blocks();
  const DeckImu *imu = blocks ? reinterpret_cast<const DeckImu *>(blocks + FAKE_INPUT_RING_SIZE) : nullptr;
  return imu && (__atomic_load_n(&imu->latency, __ATOMIC_ACQUIRE) & which);
}

__attribute__((visibility("hidden"))) static int controller_report_interval_us(int normal_us) {
  return low_latency(LATENCY_CONTROLLER) ? LOW_LATENCY_REPORT_INTERVAL_US : normal_us;
}

__attribute__((visibility("hidden"))) static void sleep_us(int us) {
  struct timespec interval = {0, us * 1000L};
  nanosleep(&interval, nullptr);
}

__attribute__((visibility("hidden"))) static uint64_t monotonic_us() {
  struct timespec now;
  clock_gettime(CLOCK_MONOTONIC, &now);
  return static_cast<uint64_t>(now.tv_sec) * 1000000ULL + static_cast<uint64_t>(now.tv_nsec) / 1000ULL;
}

static bool fake_triton_enabled() {
  static int enabled = -1;
  if (enabled < 0)
    enabled = fake_deck_enabled() && getenv("FAKE_EVDEV_TRITON") && atoi(getenv("FAKE_EVDEV_TRITON")) ? 1 : 0;
  return enabled == 1;
}

__attribute__((visibility("hidden"))) static bool process_is_steam_client() {
  static int known;
  if (known == 0) {
    char exe[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    bool client = false;
    if (length > 0) {
      exe[length] = '\0';
      const char *name = strrchr(exe, '/');
      client = !strcmp(name ? name + 1 : exe, "steam");
    }
    known = client ? 1 : -1;
  }
  return known == 1;
}

__attribute__((constructor)) static void log_client_start() {
  config();
  if (process_is_steam_client())
    Logger::log("loaded in the Steam client: deck %s, ring slot 0 %s\n", fake_deck_enabled() ? "on" : "off",
                get_ring_path_for_slot(0).empty() ? "(none configured)" : get_ring_path_for_slot(0).c_str());
}

__attribute__((visibility("hidden"))) static bool is_deck_hidraw_path(const char *pathname) {
  return pathname && fake_deck_enabled() && !strcmp(pathname, DECK_HIDRAW_PATH) && live_pad_type() == PAD_TYPE_DECK;
}

static constexpr const char *DS4_HIDRAW_PATH = "/dev/hidraw20";
static constexpr const char *DUALSENSE_HIDRAW_PATH = "/dev/hidraw21";
static constexpr unsigned int DS4_HIDRAW_MINOR = 20, DUALSENSE_HIDRAW_MINOR = 21, DUALSENSE_EDGE_HIDRAW_MINOR = 22;
static constexpr const char *DUALSENSE_EDGE_HIDRAW_PATH = "/dev/hidraw22";
static constexpr const char *SC1_HIDRAW_PATH = "/dev/hidraw23";
static constexpr unsigned int SC1_HIDRAW_MINOR = 23;
static constexpr const char *SC1_NAME = "Valve Software Steam Controller";
static constexpr const char *SC1_SERIAL = "DROIDDECKSC15";

__attribute__((visibility("hidden"))) static bool is_sc1_hidraw_path(const char *pathname) {
  return pathname && fake_deck_enabled() && !strcmp(pathname, SC1_HIDRAW_PATH) &&
         live_pad_type() == PAD_TYPE_STEAM_2015;
}

__attribute__((visibility("hidden"))) static int sony_pad_path(const char *pathname) {
  if (!pathname || !fake_deck_enabled() || strncmp(pathname, "/dev/hidraw2", 12)) return 0;
  if (!strcmp(pathname, DS4_HIDRAW_PATH)) return live_pad_type() == PAD_TYPE_DS4 ? 4 : 0;
  if (!strcmp(pathname, DUALSENSE_HIDRAW_PATH)) return live_pad_type() == PAD_TYPE_DUALSENSE ? 5 : 0;
  if (!strcmp(pathname, DUALSENSE_EDGE_HIDRAW_PATH)) return live_pad_type() == PAD_TYPE_DUALSENSE_EDGE ? 6 : 0;
  return 0;
}

__attribute__((visibility("hidden"))) static bool is_triton_pad_path(const char *pathname) {
  return pathname && fake_deck_enabled() && !strcmp(pathname, TRITON_PAD_HIDRAW_PATH) &&
         live_pad_type() == PAD_TYPE_STEAM;
}

__attribute__((visibility("hidden"))) static bool is_triton_hidraw_path(const char *pathname) {
  return pathname && fake_triton_enabled() && !strcmp(pathname, TRITON_HIDRAW_PATH) && triton_connected();
}

// ===================== sdl3bridge: DroidDeck's desktop session =====================
// On the desktop (FAKE_EVDEV_DESKTOP=1: the deck pad is on there for the SDL3 bridge) Steam is one program among
// others, like on a PC:
//  - the Steam client gets the bridge's controller as its own node (the "present as" type, or the real controller
//    with Steam Native Hook-up), so DroidDeck's first pad (the same controller) is not shown to it; the other pads
//    are, as in the Steam session;
//  - games Steam starts get Steam's virtual pads, as in the Steam session (none of DroidDeck's own pads);
//  - every other program keeps DroidDeck's pads (Xbox 360 controllers) and does not see the hidraw nodes meant for
//    Steam, so no controller shows up twice.
__attribute__((visibility("hidden"))) static bool desktop_session() {
  static int on = -1;
  if (on < 0) on = getenv("FAKE_EVDEV_DESKTOP") && atoi(getenv("FAKE_EVDEV_DESKTOP")) ? 1 : 0;
  return on == 1;
}

__attribute__((visibility("hidden"))) static bool desktop_word_is_steam(const char *word, size_t length) {
  const char *name = word;
  for (size_t i = 0; i < length && word[i]; i++)
    if (word[i] == '/') name = word + i + 1;
  return name + 5 == word + length && !strncmp(name, "steam", 5);
}

/** Is process [pid] the Steam client? Its name, or -- FEX running the x86-64 client -- the program it runs. */
__attribute__((visibility("hidden"))) static bool desktop_pid_is_steam(pid_t pid, pid_t *parent) {
  char path[64], buf[1024];
  *parent = 0;
  snprintf(path, sizeof(path), "/proc/%d/stat", static_cast<int>(pid));
  int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC));
  if (fd < 0) return false;
  ssize_t n = syscall(SYS_read, fd, buf, sizeof(buf) - 1);
  syscall(SYS_close, fd);
  if (n <= 0) return false;
  buf[n] = 0;
  char *open_paren = strchr(buf, '('), *close_paren = strrchr(buf, ')');
  if (!open_paren || !close_paren || close_paren < open_paren) return false;
  int ppid = 0;
  if (sscanf(close_paren + 1, " %*c %d", &ppid) == 1) *parent = static_cast<pid_t>(ppid);
  if (desktop_word_is_steam(open_paren + 1, static_cast<size_t>(close_paren - open_paren - 1))) return true;
  snprintf(path, sizeof(path), "/proc/%d/cmdline", static_cast<int>(pid));
  fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC));
  if (fd < 0) return false;
  n = syscall(SYS_read, fd, buf, sizeof(buf) - 1);
  syscall(SYS_close, fd);
  if (n <= 0) return false;
  buf[n] = 0;
  const char *word = buf, *end = buf + n;
  for (int i = 0; i < 2 && word < end; i++) {   // argv[0], and argv[1] for FEX ("FEX /.../steamrt64/steam")
    size_t length = strnlen(word, static_cast<size_t>(end - word));
    if (desktop_word_is_steam(word, length)) return true;
    word += length + 1;
  }
  return false;
}

/** Started by the Steam client (a game, or anything it runs): looked up once, through the parent processes. */
__attribute__((visibility("hidden"))) static bool started_by_steam() {
  static int known = 0;
  if (known) return known > 0;
  bool found = false;
  pid_t pid = getppid();
  for (int depth = 0; pid > 1 && depth < 64 && !found; depth++) {
    pid_t parent = 0;
    found = desktop_pid_is_steam(pid, &parent);
    pid = parent;
  }
  known = found ? 1 : -1;
  return found;
}

/** A program started on the desktop: neither the Steam client nor something Steam started. */
__attribute__((visibility("hidden"))) static bool desktop_program() {
  return desktop_session() && !process_is_steam_client() && !started_by_steam();
}

/** /dev/hidraw16..27 (DroidDeck's nodes for Steam) are not there for a desktop program. */
__attribute__((visibility("hidden"))) static bool desktop_hidden_hidraw(const char *pathname) {
  if (!pathname || strncmp(pathname, "/dev/hidraw", 11) || !desktop_session()) return false;
  char *end = nullptr;
  long minor = strtol(pathname + 11, &end, 10);
  return end != pathname + 11 && !*end && minor >= 16 && minor <= 27 && desktop_program();
}

__attribute__((visibility("hidden"))) static bool is_withdrawn_pad_path(const char *pathname) {
  if (!fake_deck_enabled() || !pathname || strncmp(pathname, "/dev/input/", 11)) return false;
  if (live_pad_type() == PAD_TYPE_XBOX) return false;
  const char *event = strrchr(pathname, '/') + 1;
  if (strncmp(event, "event", 5) && strncmp(event, "js", 2)) return false;
  int number = get_event_number(event);
  if (number < 0 || number >= UINPUT_EVENT_BASE) return false;
  if (desktop_session()) return process_is_steam_client() ? number == 0 : started_by_steam();  // sdl3bridge
  return number == 0 || !process_is_steam_client();
}

static bool fake_pad_withdrawn(const FakeController &fake) {
  if (fake.uinput || !fake.event) return false;
  char path[64];
  snprintf(path, sizeof(path), "/dev/input/%s", fake.event);
  return is_withdrawn_pad_path(path);
}

static inline void put16(uint8_t *at, int value) {
  at[0] = static_cast<uint8_t>(value & 0xff);
  at[1] = static_cast<uint8_t>((value >> 8) & 0xff);
}

static inline void put32(uint8_t *at, uint32_t value) {
  for (int i = 0; i < 4; i++) at[i] = static_cast<uint8_t>(value >> (8 * i));
}

__attribute__((visibility("hidden"))) static void
build_deck_report(uint8_t *report, uint32_t packet, uint32_t buttons, const int32_t *axes,
                  const DeckExtras &extras) {
  memset(report, 0, DECK_REPORT_BYTES);
  report[0] = 0x01;
  report[2] = 0x09;
  report[3] = DECK_REPORT_BYTES;
  put32(report + 4, packet);
  static const uint32_t kButtonBits[11] = {DECK_L_A,    DECK_L_B,    DECK_L_X,    DECK_L_Y,
                                           DECK_L_L1,   DECK_L_R1,   DECK_L_VIEW, DECK_L_MENU,
                                           DECK_L_L3,   DECK_L_R3,   DECK_L_STEAM};
  uint32_t low = 0, high = 0;
  for (int i = 0; i < 11; i++)
    if (buttons & (1u << i)) low |= kButtonBits[i];
  if (buttons & SNAPSHOT_QAM_BIT) high |= DECK_H_QAM;
  int32_t hat_x = axes[6], hat_y = axes[7];
  if (hat_y < 0) low |= DECK_L_UP;
  if (hat_y > 0) low |= DECK_L_DOWN;
  if (hat_x < 0) low |= DECK_L_LEFT;
  if (hat_x > 0) low |= DECK_L_RIGHT;
  int32_t left_trigger = axes[5], right_trigger = axes[4];
  if (left_trigger > 127) low |= DECK_L_L2;
  if (right_trigger > 127) low |= DECK_L_R2;
  uint32_t controls = extras.controls;
  if (controls & DECK_EXTRA_L4) high |= DECK_H_L4;
  if (controls & DECK_EXTRA_R4) high |= DECK_H_R4;
  if (controls & DECK_EXTRA_L5) low |= DECK_L_L5;
  if (controls & DECK_EXTRA_R5) low |= DECK_L_R5;
  if (controls & DECK_EXTRA_LPAD_TOUCH) low |= DECK_L_LEFT_TOUCH;
  if (controls & DECK_EXTRA_RPAD_TOUCH) low |= DECK_L_RIGHT_TOUCH;
  if (controls & DECK_EXTRA_LPAD_CLICK) low |= DECK_L_LEFT_PAD;
  if (controls & DECK_EXTRA_RPAD_CLICK) low |= DECK_L_RIGHT_PAD;
  put32(report + 8, low);
  put32(report + 12, high);
  for (int i = 0; i < 4; i++) put16(report + 16 + i * 2, extras.pads[i]);
  for (int i = 0; i < 3; i++) {
    put16(report + 24 + i * 2, extras.accel[i]);
    put16(report + 30 + i * 2, extras.gyro[i]);
  }
  put16(report + 56, extras.pressure[0]);
  put16(report + 58, extras.pressure[1]);
  put16(report + 44, std::min(32767, left_trigger * 32767 / 255));
  put16(report + 46, std::min(32767, right_trigger * 32767 / 255));
  auto stick = [](int32_t value) { return std::max(-32767, std::min(32767, value)); };
  put16(report + 48, stick(axes[0]));
  put16(report + 50, stick(-axes[1]));
  put16(report + 52, stick(axes[2]));
  put16(report + 54, stick(-axes[3]));
}

// sdl3bridge: Steam reads the newest reports of every controller (mailbox, see HookupQueue)
static void pad_mailbox_attach(DeckHidraw &pad);
static int pad_mailbox_offer(DeckHidraw &pad, const uint8_t *data, uint32_t length);

__attribute__((visibility("hidden"))) static void *deck_report_thread(void *arg) {
  auto *deck = static_cast<std::shared_ptr<DeckHidraw> *>(arg);
  std::shared_ptr<DeckHidraw> self = *deck;
  delete deck;
  uint32_t packet = 0;
  SnapshotState snap;
  uint32_t buttons = 0;
  int32_t axes[8] = {};
  DeckExtras extras;
  while (live_pad_type() == PAD_TYPE_DECK) {
    if (read_snapshot(self->ring, snap) && snap.generation == ring_generation(self->ring)) {
      buttons = snap.buttons;
      memcpy(axes, snap.axes, sizeof(axes));
    }
    read_deck_extras(*self, extras);
    uint8_t report[DECK_REPORT_BYTES];
    build_deck_report(report, ++packet, buttons, axes, extras);
    if (pad_mailbox_offer(*self, report, sizeof(report)) < 0) break;
    sleep_us(controller_report_interval_us(DECK_REPORT_INTERVAL_US));
  }
  syscall(SYS_close, self->peer);
  munmap(self->ring, self->mapping_size);
  return nullptr;
}

static constexpr int SONY_REPORT_BYTES = 64;
static constexpr int SONY_TOUCH_WIDTH = 1920;
static constexpr int SONY_TOUCH_HEIGHT_DS4 = 942, SONY_TOUCH_HEIGHT_DUALSENSE = 1080;
static constexpr const uint8_t SONY_MAC[6] = {0x5e, 0x43, 0x4b, 0x44, 0x44, 0x02};

struct SonyTouch {
  bool down[2] = {};
  uint8_t id[2] = {};
  uint8_t next_id = 0;
};

__attribute__((visibility("hidden"))) static void put_sony_touch(uint8_t *at, SonyTouch &touch, int side,
                                                                    bool down, int16_t x, int16_t y, int height) {
  if (down && !touch.down[side]) touch.id[side] = touch.next_id++ & 0x7f;
  touch.down[side] = down;
  int px = (static_cast<int>(x) + 32768) * (SONY_TOUCH_WIDTH - 1) / 65535;
  int py = (32767 - static_cast<int>(y)) * (height - 1) / 65535;
  at[0] = static_cast<uint8_t>(touch.id[side] | (down ? 0 : 0x80));
  at[1] = static_cast<uint8_t>(px & 0xff);
  at[2] = static_cast<uint8_t>(((px >> 8) & 0x0f) | ((py & 0x0f) << 4));
  at[3] = static_cast<uint8_t>(py >> 4);
}

__attribute__((visibility("hidden"))) static void
build_sony_report(uint8_t *report, uint8_t model, uint32_t packet, uint64_t elapsed_us, uint32_t buttons, const int32_t *axes,
                  const DeckExtras &extras, SonyTouch &touch) {
  memset(report, 0, SONY_REPORT_BYTES);
  report[0] = 0x01;
  uint8_t *p = report + 1;
  auto stick = [](int32_t value) { return static_cast<uint8_t>(std::max(0, std::min(255, (value + 32768) >> 8))); };
  p[0] = stick(axes[0]);
  p[1] = stick(axes[1]);
  p[2] = stick(axes[2]);
  p[3] = stick(axes[3]);
  int32_t hat_x = axes[6], hat_y = axes[7];
  static const uint8_t kHat[3][3] = {{7, 0, 1}, {6, 8, 2}, {5, 4, 3}};
  uint8_t hat = kHat[(hat_y > 0) - (hat_y < 0) + 1][(hat_x > 0) - (hat_x < 0) + 1];
  uint8_t face = (buttons & 4 ? 0x10 : 0) | (buttons & 1 ? 0x20 : 0) | (buttons & 2 ? 0x40 : 0) | (buttons & 8 ? 0x80 : 0);
  uint8_t shoulders = (buttons & 16 ? 0x01 : 0) | (buttons & 32 ? 0x02 : 0) | (axes[5] > 0 ? 0x04 : 0) |
                      (axes[4] > 0 ? 0x08 : 0) | (buttons & 64 ? 0x10 : 0) | (buttons & 128 ? 0x20 : 0) |
                      (buttons & 256 ? 0x40 : 0) | (buttons & 512 ? 0x80 : 0);
  bool click = extras.controls & (DECK_EXTRA_LPAD_CLICK | DECK_EXTRA_RPAD_CLICK);
  uint8_t system = (buttons & 1024 ? 0x01 : 0) | (click ? 0x02 : 0);
  // DualSense / Edge mute (microphone) button -> bit 0x04 of this byte. The bridge maps the mute
  // button to the Deck QAM bit; DualShock 4 (model 4) has no mute and reuses 0x04 for its counter.
  if (model != 4 && (buttons & SNAPSHOT_QAM_BIT)) system |= 0x04;
  // DualSense Edge extra buttons: SDL/Steam read all four from this byte on PID 0x0df2 --
  //   0x10 left Fn, 0x20 right Fn, 0x40 left back paddle, 0x80 right back paddle.
  // model 6 == the Edge node; plain DualSense (5) and DS4 (4) are left untouched. Deck grips map 1:1:
  //   L4 -> left paddle, R4 -> right paddle, L5 -> left Fn, R5 -> right Fn.
  if (model == 6) {
    if (extras.controls & DECK_EXTRA_L4) system |= 0x40;
    if (extras.controls & DECK_EXTRA_R4) system |= 0x80;
    if (extras.controls & DECK_EXTRA_L5) system |= 0x10;
    if (extras.controls & DECK_EXTRA_R5) system |= 0x20;
  }
  uint8_t left_trigger = static_cast<uint8_t>(std::max(0, std::min(255, axes[5])));
  uint8_t right_trigger = static_cast<uint8_t>(std::max(0, std::min(255, axes[4])));
  int16_t gyro[3] = {extras.gyro[0], extras.gyro[2], static_cast<int16_t>(-extras.gyro[1])};
  int16_t accel[3] = {static_cast<int16_t>(extras.accel[0] / 2), static_cast<int16_t>(extras.accel[2] / 2),
                      static_cast<int16_t>(-extras.accel[1] / 2)};
  bool left = extras.controls & DECK_EXTRA_LPAD_TOUCH, right = extras.controls & DECK_EXTRA_RPAD_TOUCH;
  if (model == 4) {
    p[4] = static_cast<uint8_t>(hat | face);
    p[5] = shoulders;
    p[6] = static_cast<uint8_t>(system | ((packet & 0x3f) << 2));
    p[7] = left_trigger;
    p[8] = right_trigger;
    put16(p + 9, static_cast<int>(elapsed_us * 3 / 16));
    for (int i = 0; i < 3; i++) {
      put16(p + 12 + i * 2, gyro[i]);
      put16(p + 18 + i * 2, accel[i]);
    }
    p[29] = 0x1b;
    p[32] = 1;
    p[33] = static_cast<uint8_t>(packet);
    put_sony_touch(p + 34, touch, 0, left, extras.pads[0], extras.pads[1], SONY_TOUCH_HEIGHT_DS4);
    put_sony_touch(p + 38, touch, 1, right, extras.pads[2], extras.pads[3], SONY_TOUCH_HEIGHT_DS4);
  } else {
    p[4] = left_trigger;
    p[5] = right_trigger;
    p[6] = static_cast<uint8_t>(packet);
    p[7] = static_cast<uint8_t>(hat | face);
    p[8] = shoulders;
    p[9] = system;
    put32(p + 11, packet);
    for (int i = 0; i < 3; i++) {
      put16(p + 15 + i * 2, gyro[i]);
      put16(p + 21 + i * 2, accel[i]);
    }
    put32(p + 27, static_cast<uint32_t>(elapsed_us * 3));
    put_sony_touch(p + 32, touch, 0, left, extras.pads[0], extras.pads[1], SONY_TOUCH_HEIGHT_DUALSENSE);
    put_sony_touch(p + 36, touch, 1, right, extras.pads[2], extras.pads[3], SONY_TOUCH_HEIGHT_DUALSENSE);
    p[52] = 0x2a;
    p[53] = 0x08;
  }
}

__attribute__((visibility("hidden"))) static void *sony_report_thread(void *arg) {
  auto *holder = static_cast<std::shared_ptr<DeckHidraw> *>(arg);
  std::shared_ptr<DeckHidraw> self = *holder;
  delete holder;
  uint32_t type = self->sony == 4 ? PAD_TYPE_DS4 : self->sony == 6 ? PAD_TYPE_DUALSENSE_EDGE : PAD_TYPE_DUALSENSE;
  uint32_t packet = 0;
  SnapshotState snap;
  uint32_t buttons = 0;
  int32_t axes[8] = {};
  DeckExtras extras;
  SonyTouch touch;
  const uint64_t start_us = monotonic_us();
  while (live_pad_type() == type) {
    if (read_snapshot(self->ring, snap) && snap.generation == ring_generation(self->ring)) {
      buttons = snap.buttons;
      memcpy(axes, snap.axes, sizeof(axes));
    }
    read_deck_extras(*self, extras);
    uint8_t report[SONY_REPORT_BYTES];
    build_sony_report(report, self->sony, ++packet, monotonic_us() - start_us, buttons, axes, extras, touch);
    if (pad_mailbox_offer(*self, report, sizeof(report)) < 0) break;
    sleep_us(controller_report_interval_us(DECK_REPORT_INTERVAL_US));
  }
  syscall(SYS_close, self->peer);
  munmap(self->ring, self->mapping_size);
  return nullptr;
}

// sdl3bridge: Steam Controller (2015) state packet -- ValveInReport_t type 1 (ID_CONTROLLER_STATE),
// laid out as SDL's ValveControllerStatePacket_t: buttons in bytes 8..10, analog triggers in bytes 11/12,
// left axes 16/18 (stick OR left pad, see SC1_FINGER_LEFT), right pad 20/22, triggers 24/26, accel 28..,
// gyro 34.., orientation quaternion 40...
static constexpr uint32_t SC1_RT = 0x000001, SC1_LT = 0x000002, SC1_RB = 0x000004, SC1_LB = 0x000008,
                          SC1_Y = 0x000010, SC1_B = 0x000020, SC1_X = 0x000040, SC1_A = 0x000080,
                          SC1_UP = 0x000100, SC1_RIGHT = 0x000200, SC1_LEFT = 0x000400, SC1_DOWN = 0x000800,
                          SC1_BACK = 0x001000, SC1_STEAM = 0x002000, SC1_START = 0x004000,
                          SC1_LGRIP = 0x008000, SC1_RGRIP = 0x010000, SC1_LPAD_CLICK = 0x020000,
                          SC1_RPAD_CLICK = 0x040000, SC1_FINGER_LEFT = 0x080000, SC1_FINGER_RIGHT = 0x100000,
                          SC1_STICK_CLICK = 0x400000, SC1_PAD_AND_STICK = 0x800000;
static constexpr int32_t SC1_STICK_ACTIVE = 2000;
static constexpr int16_t SC1_DPAD_EDGE = 24000;

__attribute__((visibility("hidden"))) static void
build_sc1_report(uint8_t *report, uint32_t packet, uint32_t buttons, const int32_t *axes, const DeckExtras &extras) {
  memset(report, 0, DECK_REPORT_BYTES);
  report[0] = 0x01;  // report version 1
  report[2] = 0x01;  // ID_CONTROLLER_STATE
  report[3] = 0x3c;
  put32(report + 4, packet);
  // Snapshot order: A B X Y L1 R1 View Menu L3 R3 Steam. No right stick on this controller, so no R3.
  static const uint32_t kButtonBits[11] = {SC1_A,  SC1_B,     SC1_X,          SC1_Y, SC1_LB,   SC1_RB,
                                           SC1_BACK, SC1_START, SC1_STICK_CLICK, 0,     SC1_STEAM};
  uint32_t bits = 0;
  for (int i = 0; i < 11; i++)
    if (buttons & (1u << i)) bits |= kButtonBits[i];
  int32_t left_trigger = std::max(0, std::min(255, axes[5])), right_trigger = std::max(0, std::min(255, axes[4]));
  if (left_trigger >= 250) bits |= SC1_LT;
  if (right_trigger >= 250) bits |= SC1_RT;
  uint32_t controls = extras.controls;
  if (controls & (DECK_EXTRA_L4 | DECK_EXTRA_L5)) bits |= SC1_LGRIP;
  if (controls & (DECK_EXTRA_R4 | DECK_EXTRA_R5)) bits |= SC1_RGRIP;
  bool left_pad = controls & DECK_EXTRA_LPAD_TOUCH, right_pad = controls & DECK_EXTRA_RPAD_TOUCH;
  if (controls & DECK_EXTRA_LPAD_CLICK) bits |= SC1_LPAD_CLICK;
  if (controls & DECK_EXTRA_RPAD_CLICK) bits |= SC1_RPAD_CLICK;
  int32_t left_x = extras.pads[0], left_y = extras.pads[1];
  // The real controller has no D-pad: its D-pad is a click on the left trackpad's edge.
  int32_t hat_x = axes[6], hat_y = axes[7];
  if (hat_x || hat_y) {
    if (hat_y < 0) bits |= SC1_UP;
    if (hat_y > 0) bits |= SC1_DOWN;
    if (hat_x < 0) bits |= SC1_LEFT;
    if (hat_x > 0) bits |= SC1_RIGHT;
    left_pad = true;
    left_x = hat_x < 0 ? -SC1_DPAD_EDGE : hat_x > 0 ? SC1_DPAD_EDGE : 0;
    left_y = hat_y < 0 ? SC1_DPAD_EDGE : hat_y > 0 ? -SC1_DPAD_EDGE : 0;
    bits |= SC1_LPAD_CLICK;
  }
  auto clamp16 = [](int32_t value) { return std::max(-32767, std::min(32767, value)); };
  int32_t stick_x = clamp16(axes[0]), stick_y = clamp16(-axes[1]);
  bool stick = abs(stick_x) > SC1_STICK_ACTIVE || abs(stick_y) > SC1_STICK_ACTIVE;
  // The stick and the left pad share one axis pair; SC1_FINGER_LEFT says which one this packet carries,
  // and SC1_PAD_AND_STICK says both are in use (the firmware then alternates them packet by packet).
  int32_t axis_x = stick_x, axis_y = stick_y;
  if (left_pad && stick) {
    bits |= SC1_PAD_AND_STICK;
    if (packet & 1) {
      bits |= SC1_FINGER_LEFT;
      axis_x = left_x;
      axis_y = left_y;
    }
  } else if (left_pad) {
    bits |= SC1_FINGER_LEFT;
    axis_x = left_x;
    axis_y = left_y;
  }
  if (right_pad) bits |= SC1_FINGER_RIGHT;
  report[8] = static_cast<uint8_t>(bits);
  report[9] = static_cast<uint8_t>(bits >> 8);
  report[10] = static_cast<uint8_t>(bits >> 16);
  report[11] = static_cast<uint8_t>(left_trigger);
  report[12] = static_cast<uint8_t>(right_trigger);
  put16(report + 16, clamp16(axis_x));
  put16(report + 18, clamp16(axis_y));
  put16(report + 20, right_pad ? extras.pads[2] : 0);
  put16(report + 22, right_pad ? extras.pads[3] : 0);
  put16(report + 24, left_trigger * 32767 / 255);
  put16(report + 26, right_trigger * 32767 / 255);
  for (int i = 0; i < 3; i++) {
    put16(report + 28 + i * 2, extras.accel[i]);
    put16(report + 34 + i * 2, extras.gyro[i]);
  }
  put16(report + 40, 32767);  // orientation quaternion: identity (Steam uses the raw gyro)
}

__attribute__((visibility("hidden"))) static void *sc1_report_thread(void *arg) {
  auto *holder = static_cast<std::shared_ptr<DeckHidraw> *>(arg);
  std::shared_ptr<DeckHidraw> self = *holder;
  delete holder;
  uint32_t packet = 0;
  SnapshotState snap;
  uint32_t buttons = 0;
  int32_t axes[8] = {};
  DeckExtras extras;
  while (live_pad_type() == PAD_TYPE_STEAM_2015) {
    if (read_snapshot(self->ring, snap) && snap.generation == ring_generation(self->ring)) {
      buttons = snap.buttons;
      memcpy(axes, snap.axes, sizeof(axes));
    }
    read_deck_extras(*self, extras);
    uint8_t report[DECK_REPORT_BYTES];
    build_sc1_report(report, ++packet, buttons, axes, extras);
    if (pad_mailbox_offer(*self, report, sizeof(report)) < 0) break;
    sleep_us(controller_report_interval_us(DECK_REPORT_INTERVAL_US));
  }
  syscall(SYS_close, self->peer);
  munmap(self->ring, self->mapping_size);
  return nullptr;
}

__attribute__((visibility("hidden"))) static int open_deck_hidraw(int flags, uint8_t sony = 0, bool sc1 = false) {
  const char *path = sc1 ? SC1_HIDRAW_PATH : sony == 4 ? DS4_HIDRAW_PATH : sony == 5 ? DUALSENSE_HIDRAW_PATH : sony == 6 ? DUALSENSE_EDGE_HIDRAW_PATH : DECK_HIDRAW_PATH;
  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));
  if (!process_is_steam_client()) {
    Logger::log("deck: %s refused: not the Steam client\n", path);
    errno = ENOENT;
    return -1;
  }
  std::string ring_path = get_ring_path_for_slot(0);
  int ring_fd = ring_path.empty() ? -1 : my_open(ring_path.c_str(), O_RDONLY | O_CLOEXEC);
  if (ring_fd < 0) {
    Logger::log("deck: %s refused: ring %s not openable (%s)\n", path,
                ring_path.empty() ? "(none configured)" : ring_path.c_str(), strerror(errno));
    errno = ENODEV;
    return -1;
  }
  struct stat ring_stat;
  size_t mapping_size = FAKE_INPUT_RING_SIZE;
  if (fstat(ring_fd, &ring_stat) == 0 &&
      static_cast<size_t>(ring_stat.st_size) >= FAKE_INPUT_RING_SIZE + DECK_IMU_BLOCK_SIZE)
    mapping_size = FAKE_INPUT_RING_SIZE + DECK_IMU_BLOCK_SIZE;
  void *mapping = mmap(nullptr, mapping_size, PROT_READ, MAP_SHARED, ring_fd, 0);
  syscall(SYS_close, ring_fd);
  if (mapping == MAP_FAILED || !ring_header_is_valid(static_cast<FakeInputRingHeader *>(mapping))) {
    Logger::log("deck: %s refused: ring %s %s\n", path, ring_path.c_str(),
                mapping == MAP_FAILED ? "could not be mapped" : "has no valid header");
    if (mapping != MAP_FAILED) munmap(mapping, mapping_size);
    errno = ENODEV;
    return -1;
  }
  int pair[2];
  if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair) < 0) {
    Logger::log("deck: %s refused: socketpair failed (%s)\n", path, strerror(errno));
    munmap(mapping, mapping_size);
    return -1;
  }
  int buffer = DECK_REPORT_BYTES * 4;
  setsockopt(pair[1], SOL_SOCKET, SO_SNDBUF, &buffer, sizeof(buffer));
  if (!(flags & O_CLOEXEC)) fcntl(pair[0], F_SETFD, 0);
  if (flags & O_NONBLOCK) fcntl(pair[0], F_SETFL, fcntl(pair[0], F_GETFL) | O_NONBLOCK);
  auto deck = std::make_shared<DeckHidraw>();
  deck->peer = pair[1];
  deck->ring = static_cast<FakeInputRingHeader *>(mapping);
  deck->mapping_size = mapping_size;
  deck->sony = sony;
  deck->sc1 = sc1;
  pad_mailbox_attach(*deck);
  {
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    deck_map()[pair[0]] = deck;
    watch_fd(pair[0]);
  }
  pthread_t thread;
  auto *arg = new std::shared_ptr<DeckHidraw>(deck);
  if (pthread_create(&thread, nullptr, sony ? sony_report_thread : sc1 ? sc1_report_thread : deck_report_thread, arg) != 0) {
    delete arg;
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    deck_map().erase(pair[0]);
    unwatch_fd(pair[0]);
    syscall(SYS_close, pair[0]);
    syscall(SYS_close, pair[1]);
    munmap(mapping, mapping_size);
    errno = ENOMEM;
    return -1;
  }
  pthread_detach(thread);
  Logger::log("deck: %s opened as fd %d%s\n", path, pair[0],
              sony == 4 ? " (a DualShock 4)" : sony == 5 ? " (a DualSense)" : sony == 6 ? " (a DualSense Edge)" : sc1 ? " (a Steam Controller 2015)" : "");
  return pair[0];
}

__attribute__((visibility("hidden"))) static bool read_triton_state(const DeckHidraw &triton, uint8_t &report_id,
                                                                         uint8_t *report, size_t &length) {
  if (triton.mapping_size < TRITON_SHARED_OFFSET + TRITON_SHARED_BLOCK_SIZE) return false;
  const auto *shared = reinterpret_cast<const TritonSharedData *>(reinterpret_cast<const uint8_t *>(triton.ring) +
                                                                  TRITON_SHARED_OFFSET);
  for (int attempt = 0; attempt < 8; attempt++) {
    uint64_t seq = __atomic_load_n(&shared->input_seq, __ATOMIC_ACQUIRE);
    if (seq & 1) continue;
    if (__atomic_load_n(&shared->magic, __ATOMIC_RELAXED) != TRITON_SHARED_MAGIC) return false;
    uint32_t id = __atomic_load_n(&shared->report_id, __ATOMIC_RELAXED);
    uint32_t size = __atomic_load_n(&shared->input_length, __ATOMIC_RELAXED);
    if (id > 0xff || size > sizeof(shared->input)) return false;
    if (size) memcpy(report, shared->input, size);
    __atomic_thread_fence(__ATOMIC_ACQUIRE);
    if (seq == __atomic_load_n(&shared->input_seq, __ATOMIC_RELAXED)) {
      report_id = size ? static_cast<uint8_t>(id) : 0x45;
      length = size ? size : TRITON_STATE_BYTES;
      if (!size) memset(report, 0, length);
      return true;
    }
  }
  return false;
}

__attribute__((visibility("hidden"))) static void *triton_report_thread(void *arg) {
  auto *holder = static_cast<std::shared_ptr<DeckHidraw> *>(arg);
  std::shared_ptr<DeckHidraw> self = *holder;
  delete holder;
  uint8_t report[TRITON_REPORT_ID_BYTES + 64] = {};
  uint8_t report_id = 0x45;
  size_t length = TRITON_STATE_BYTES;
  while (triton_connected()) {
    if (read_triton_state(*self, report_id, report + 1, length)) {
      report[0] = report_id;
      if (pad_mailbox_offer(*self, report, static_cast<uint32_t>(length + 1)) < 0) break;
    }
    sleep_us(controller_report_interval_us(TRITON_REPORT_INTERVAL_US));
  }
  syscall(SYS_close, self->peer);
  munmap(self->ring, self->mapping_size);
  return nullptr;
}

static constexpr uint32_t TRITON_A = 0x00000001, TRITON_B = 0x00000002, TRITON_X = 0x00000004,
                          TRITON_Y = 0x00000008, TRITON_QAM = 0x00000010, TRITON_R3 = 0x00000020,
                          TRITON_VIEW = 0x00000040, TRITON_R4 = 0x00000080, TRITON_R5 = 0x00000100,
                          TRITON_R = 0x00000200, TRITON_DOWN = 0x00000400, TRITON_RIGHT = 0x00000800,
                          TRITON_LEFT = 0x00001000, TRITON_UP = 0x00002000, TRITON_MENU = 0x00004000,
                          TRITON_L3 = 0x00008000, TRITON_STEAM = 0x00010000, TRITON_L4 = 0x00020000,
                          TRITON_L5 = 0x00040000, TRITON_L = 0x00080000,
                          TRITON_RSTICK_TOUCH = 0x00100000, TRITON_RPAD_TOUCH = 0x00200000,
                          TRITON_RPAD_CLICK = 0x00400000, TRITON_RTRIGGER_CLICK = 0x00800000,
                          TRITON_LSTICK_TOUCH = 0x01000000, TRITON_LPAD_TOUCH = 0x02000000,
                          TRITON_LPAD_CLICK = 0x04000000, TRITON_LTRIGGER_CLICK = 0x08000000,
                          TRITON_RGRIP_TOUCH = 0x10000000, TRITON_LGRIP_TOUCH = 0x20000000;
static constexpr uint8_t TRITON_STATE_WIRED = 0x42;
static constexpr int16_t TRITON_WIRED_PRODUCT = 0x1302;
static constexpr int32_t TRITON_STICK_TOUCH = 2000;

__attribute__((visibility("hidden"))) static void
build_triton_report(uint8_t *report, uint32_t packet, uint64_t elapsed_us, uint32_t buttons, const int32_t *axes,
                    const DeckExtras &extras) {
  memset(report, 0, TRITON_STATE_BYTES);
  report[0] = static_cast<uint8_t>(packet);
  static const uint32_t kButtonBits[11] = {TRITON_A, TRITON_B,    TRITON_X,    TRITON_Y,
                                           TRITON_L, TRITON_R,    TRITON_MENU, TRITON_VIEW,
                                           TRITON_L3, TRITON_R3,  TRITON_STEAM};
  uint32_t bits = TRITON_LGRIP_TOUCH | TRITON_RGRIP_TOUCH;
  for (int i = 0; i < 11; i++)
    if (buttons & (1u << i)) bits |= kButtonBits[i];
  if (buttons & SNAPSHOT_QAM_BIT) bits |= TRITON_QAM;
  if (axes[7] < 0) bits |= TRITON_UP;
  if (axes[7] > 0) bits |= TRITON_DOWN;
  if (axes[6] < 0) bits |= TRITON_LEFT;
  if (axes[6] > 0) bits |= TRITON_RIGHT;
  int32_t left_trigger = axes[5], right_trigger = axes[4];
  if (left_trigger >= 250) bits |= TRITON_LTRIGGER_CLICK;
  if (right_trigger >= 250) bits |= TRITON_RTRIGGER_CLICK;
  if (abs(axes[0]) > TRITON_STICK_TOUCH || abs(axes[1]) > TRITON_STICK_TOUCH) bits |= TRITON_LSTICK_TOUCH;
  if (abs(axes[2]) > TRITON_STICK_TOUCH || abs(axes[3]) > TRITON_STICK_TOUCH) bits |= TRITON_RSTICK_TOUCH;
  uint32_t controls = extras.controls;
  if (controls & DECK_EXTRA_L4) bits |= TRITON_L4;
  if (controls & DECK_EXTRA_R4) bits |= TRITON_R4;
  if (controls & DECK_EXTRA_L5) bits |= TRITON_L5;
  if (controls & DECK_EXTRA_R5) bits |= TRITON_R5;
  if (controls & DECK_EXTRA_LPAD_TOUCH) bits |= TRITON_LPAD_TOUCH;
  if (controls & DECK_EXTRA_RPAD_TOUCH) bits |= TRITON_RPAD_TOUCH;
  if (controls & DECK_EXTRA_LPAD_CLICK) bits |= TRITON_LPAD_CLICK;
  if (controls & DECK_EXTRA_RPAD_CLICK) bits |= TRITON_RPAD_CLICK;
  put32(report + 1, bits);
  put16(report + 5, std::min(32767, left_trigger * 32767 / 255));
  put16(report + 7, std::min(32767, right_trigger * 32767 / 255));
  auto stick = [](int32_t value) { return std::max(-32767, std::min(32767, value)); };
  put16(report + 9, stick(axes[0]));
  put16(report + 11, stick(-axes[1]));
  put16(report + 13, stick(axes[2]));
  put16(report + 15, stick(-axes[3]));
  put16(report + 17, extras.pads[0]);
  put16(report + 19, extras.pads[1]);
  put16(report + 21, extras.pressure[0]);
  put16(report + 23, extras.pads[2]);
  put16(report + 25, extras.pads[3]);
  put16(report + 27, extras.pressure[1]);
  put32(report + 29, static_cast<uint32_t>(elapsed_us));
  for (int i = 0; i < 3; i++) {
    put16(report + 33 + i * 2, extras.accel[i]);
    put16(report + 39 + i * 2, extras.gyro[i]);
  }
}

__attribute__((visibility("hidden"))) static void *triton_pad_thread(void *arg) {
  auto *holder = static_cast<std::shared_ptr<DeckHidraw> *>(arg);
  std::shared_ptr<DeckHidraw> self = *holder;
  delete holder;
  uint32_t packet = 0;
  SnapshotState snap;
  uint32_t buttons = 0;
  int32_t axes[8] = {};
  DeckExtras extras;
  const uint64_t start_us = monotonic_us();
  while (live_pad_type() == PAD_TYPE_STEAM) {
    if (read_snapshot(self->ring, snap) && snap.generation == ring_generation(self->ring)) {
      buttons = snap.buttons;
      memcpy(axes, snap.axes, sizeof(axes));
    }
    read_deck_extras(*self, extras);
    uint8_t report[TRITON_REPORT_ID_BYTES + TRITON_STATE_BYTES];
    report[0] = TRITON_STATE_WIRED;
    build_triton_report(report + 1, ++packet, monotonic_us() - start_us, buttons, axes, extras);
    if (pad_mailbox_offer(*self, report, sizeof(report)) < 0) break;
    sleep_us(controller_report_interval_us(TRITON_REPORT_INTERVAL_US));
  }
  syscall(SYS_close, self->peer);
  munmap(self->ring, self->mapping_size);
  return nullptr;
}

__attribute__((visibility("hidden"))) static int open_triton_hidraw(int flags, bool emulated) {
  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));
  if (!process_is_steam_client()) {
    errno = ENOENT;
    return -1;
  }
  std::string ring_path = get_ring_path_for_slot(0);
  int ring_fd = ring_path.empty() ? -1 : my_open(ring_path.c_str(), O_RDWR | O_CLOEXEC);
  if (ring_fd < 0) {
    Logger::log("triton: %s refused: ring %s not openable (%s)\n", TRITON_HIDRAW_PATH,
                ring_path.empty() ? "(none configured)" : ring_path.c_str(), strerror(errno));
    errno = ENODEV;
    return -1;
  }
  struct stat ring_stat;
  const size_t mapping_size = TRITON_SHARED_OFFSET + TRITON_SHARED_BLOCK_SIZE;
  if (fstat(ring_fd, &ring_stat) < 0 || static_cast<size_t>(ring_stat.st_size) < mapping_size) {
    syscall(SYS_close, ring_fd);
    errno = ENODEV;
    return -1;
  }
  void *mapping = mmap(nullptr, mapping_size, PROT_READ | PROT_WRITE, MAP_SHARED, ring_fd, 0);
  syscall(SYS_close, ring_fd);
  if (mapping == MAP_FAILED || !ring_header_is_valid(static_cast<FakeInputRingHeader *>(mapping)) ||
      reinterpret_cast<TritonSharedData *>(static_cast<uint8_t *>(mapping) + TRITON_SHARED_OFFSET)->magic != TRITON_SHARED_MAGIC) {
    if (mapping != MAP_FAILED) munmap(mapping, mapping_size);
    errno = ENODEV;
    return -1;
  }
  int pair[2];
  if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair) < 0) {
    munmap(mapping, mapping_size);
    return -1;
  }
  int buffer = (TRITON_STATE_BYTES + TRITON_REPORT_ID_BYTES) * 4;
  setsockopt(pair[1], SOL_SOCKET, SO_SNDBUF, &buffer, sizeof(buffer));
  if (!(flags & O_CLOEXEC)) fcntl(pair[0], F_SETFD, 0);
  if (flags & O_NONBLOCK) fcntl(pair[0], F_SETFL, fcntl(pair[0], F_GETFL) | O_NONBLOCK);
  auto triton = std::make_shared<DeckHidraw>();
  triton->peer = pair[1];
  triton->ring = static_cast<FakeInputRingHeader *>(mapping);
  triton->mapping_size = mapping_size;
  triton->triton = true;
  triton->emulated = emulated;
  pad_mailbox_attach(*triton);
  {
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    deck_map()[pair[0]] = triton;
    watch_fd(pair[0]);
  }
  pthread_t thread;
  auto *thread_arg = new std::shared_ptr<DeckHidraw>(triton);
  if (pthread_create(&thread, nullptr, triton->emulated ? triton_pad_thread : triton_report_thread, thread_arg) != 0) {
    delete thread_arg;
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    deck_map().erase(pair[0]);
    unwatch_fd(pair[0]);
    syscall(SYS_close, pair[0]);
    syscall(SYS_close, pair[1]);
    munmap(mapping, mapping_size);
    errno = ENOMEM;
    return -1;
  }
  pthread_detach(thread);
  Logger::log("triton: %s opened as fd %d (%s)\n", emulated ? TRITON_PAD_HIDRAW_PATH : TRITON_HIDRAW_PATH, pair[0],
              emulated ? "28de:1302, the app's pad" : "28de:1303");
  return pair[0];
}


static constexpr const char *TOUCH_HIDRAW_PATH = "/dev/hidraw17";
static constexpr unsigned int TOUCH_HIDRAW_MINOR = 17;
static constexpr int TOUCH_REPORT_BYTES = 40;
static constexpr int TOUCH_REPORT_INTERVAL_US = 8000;
static constexpr const char *TOUCH_NAME = "Mobile Touch Control";
static constexpr const char *TOUCH_SERIAL = "DROIDDECK0001";
static constexpr const char *TOUCH_DEVPATH = "/devices/droiddeck/usb2/2-1:1.0/0003:0000:11FB.0002/hidraw/hidraw17";
static const uint8_t kTouchReportDescriptor[] = {
    0x05, 0x01, 0x09, 0x05, 0xa1, 0x01,
    0x09, 0x02, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x28, 0x81, 0x02,
    0x09, 0x03, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x91, 0x02,
    0x09, 0x04, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0xb1, 0x02, 0xc0};

struct TouchRingFile {
  uint32_t magic;
  uint32_t version;
  uint64_t report_seq;
  uint8_t report[TOUCH_REPORT_BYTES];
  uint32_t plugged;
  uint32_t battery;

  uint64_t state_seq;
  uint32_t opened;
  uint32_t appid;
  uint32_t action_set;
  uint32_t layer_count;
  uint32_t layers[8];
  uint32_t rumble_seq;
  uint16_t rumble_low;
  uint16_t rumble_high;
  uint32_t rumble_ms;
  uint32_t setting30;
  uint32_t action_seq;
};
static constexpr uint32_t TOUCH_RING_MAGIC = 0x31484354;
static constexpr size_t TOUCH_RING_SIZE = 256;
static_assert(offsetof(TouchRingFile, report) == 16, "TouchRingFile layout");
static_assert(offsetof(TouchRingFile, plugged) == 56, "TouchRingFile layout");
static_assert(offsetof(TouchRingFile, state_seq) == 64, "TouchRingFile layout");
static_assert(offsetof(TouchRingFile, layers) == 88, "TouchRingFile layout");
static_assert(offsetof(TouchRingFile, rumble_seq) == 120, "TouchRingFile layout");
static_assert(offsetof(TouchRingFile, action_seq) == 136, "TouchRingFile layout");
static_assert(sizeof(TouchRingFile) <= TOUCH_RING_SIZE, "TouchRingFile layout");

__attribute__((visibility("hidden"))) static TouchRingFile *touch_ring() {
  static TouchRingFile *ring = nullptr;
  static std::once_flag once;
  std::call_once(once, [] {
    const char *path = getenv("FAKE_TOUCHCTL_RING");
    if (!path || !*path || !fake_deck_enabled()) return;
    static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));
    int fd = my_open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
      Logger::log("touch: %s not openable (%s)\n", path, strerror(errno));
      return;
    }
    void *mapping = mmap(nullptr, TOUCH_RING_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    syscall(SYS_close, fd);
    if (mapping == MAP_FAILED || static_cast<TouchRingFile *>(mapping)->magic != TOUCH_RING_MAGIC) {
      Logger::log("touch: %s %s\n", path, mapping == MAP_FAILED ? "could not be mapped" : "has no valid header");
      if (mapping != MAP_FAILED) munmap(mapping, TOUCH_RING_SIZE);
      return;
    }
    ring = static_cast<TouchRingFile *>(mapping);
  });
  return ring;
}

__attribute__((visibility("hidden"))) static bool touch_plugged() {
  TouchRingFile *ring = touch_ring();
  return ring && __atomic_load_n(&ring->plugged, __ATOMIC_ACQUIRE) != 0;
}

__attribute__((visibility("hidden"))) static bool is_touch_hidraw_path(const char *pathname) {
  return pathname && !strcmp(pathname, TOUCH_HIDRAW_PATH) && process_is_steam_client() && touch_ring();
}

template <typename F>
__attribute__((visibility("hidden"))) static void touch_state(F &&write) {
  TouchRingFile *ring = touch_ring();
  if (!ring) return;
  static std::mutex mutex;
  std::lock_guard<std::mutex> guard(mutex);
  __atomic_fetch_add(&ring->state_seq, 1, __ATOMIC_ACQ_REL);
  write(*ring);
  __atomic_fetch_add(&ring->state_seq, 1, __ATOMIC_ACQ_REL);
}

static inline uint32_t get32(const uint8_t *at) {
  return static_cast<uint32_t>(at[0]) | static_cast<uint32_t>(at[1]) << 8 | static_cast<uint32_t>(at[2]) << 16 |
         static_cast<uint32_t>(at[3]) << 24;
}

__attribute__((visibility("hidden"))) static void touch_output(const uint8_t *data, size_t size) {
  if (size < 9) return;
  static std::atomic<unsigned> seen[8];
  uint8_t type = data[0];
  unsigned n = type < 8 ? ++seen[type] : 0;
  if (n && (n & (n - 1)) == 0)
    Logger::log("touch: output report %u (x%u): app %u, %u\n", type, n, get32(data + 1), get32(data + 5));
  switch (type) {
  case 1: {
    uint16_t low = data[1] | data[2] << 8, high = data[3] | data[4] << 8;
    uint32_t ms = get32(data + 5);
    touch_state([&](TouchRingFile &r) {
      r.rumble_low = low;
      r.rumble_high = high;
      r.rumble_ms = ms;
      r.rumble_seq++;
    });
    send_vibration(low, high, static_cast<uint16_t>(std::min<uint32_t>(ms, 0xffff)), 0);
    break;
  }
  case 3:
    if (data[1] == 0x30) touch_state([&](TouchRingFile &r) { r.setting30 = data[2] | data[3] << 8; });
    break;
  case 4:
    touch_state([&](TouchRingFile &r) {
      r.appid = get32(data + 1);
      r.action_set = get32(data + 5);
      r.layer_count = 0;
      r.action_seq++;
    });
    break;
  case 5:
  case 6:
    touch_state([&](TouchRingFile &r) {
      uint32_t layer = get32(data + 5);
      uint32_t count = std::min<uint32_t>(r.layer_count, 8);
      uint32_t kept = 0;
      for (uint32_t i = 0; i < count; i++)
        if (r.layers[i] != layer) r.layers[kept++] = r.layers[i];
      if (type == 5 && kept < 8) r.layers[kept++] = layer;
      r.layer_count = kept;
      r.action_seq++;
    });
    break;
  }
}

__attribute__((visibility("hidden"))) static bool read_touch_report(TouchRingFile *ring, uint8_t *out) {
  for (int attempt = 0; attempt < 8; attempt++) {
    uint64_t seq = __atomic_load_n(&ring->report_seq, __ATOMIC_ACQUIRE);
    if (seq & 1) continue;
    memcpy(out, ring->report, TOUCH_REPORT_BYTES);
    __atomic_thread_fence(__ATOMIC_ACQUIRE);
    if (seq == __atomic_load_n(&ring->report_seq, __ATOMIC_RELAXED)) return true;
  }
  return false;
}

__attribute__((visibility("hidden"))) static void *touch_report_thread(void *arg) {
  auto *holder = static_cast<std::shared_ptr<DeckHidraw> *>(arg);
  std::shared_ptr<DeckHidraw> self = *holder;
  delete holder;
  TouchRingFile *ring = touch_ring();
  uint8_t report[TOUCH_REPORT_BYTES] = {};
  while (touch_plugged()) {
    read_touch_report(ring, report);
    if (send(self->peer, report, sizeof(report), MSG_DONTWAIT | MSG_NOSIGNAL) < 0 &&
        errno != EAGAIN && errno != EWOULDBLOCK)
      break;
    sleep_us(low_latency(LATENCY_TOUCH) ? LOW_LATENCY_REPORT_INTERVAL_US : TOUCH_REPORT_INTERVAL_US);
  }
  syscall(SYS_close, self->peer);
  touch_state([](TouchRingFile &r) { if (r.opened) r.opened--; });
  return nullptr;
}

__attribute__((visibility("hidden"))) static int open_touch_hidraw(int flags) {
  if (!touch_plugged()) {
    errno = ENOENT;
    return -1;
  }
  int pair[2];
  if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair) < 0) return -1;
  int buffer = TOUCH_REPORT_BYTES * 4;
  setsockopt(pair[1], SOL_SOCKET, SO_SNDBUF, &buffer, sizeof(buffer));
  if (!(flags & O_CLOEXEC)) fcntl(pair[0], F_SETFD, 0);
  if (flags & O_NONBLOCK) fcntl(pair[0], F_SETFL, fcntl(pair[0], F_GETFL) | O_NONBLOCK);
  auto touch = std::make_shared<DeckHidraw>();
  touch->peer = pair[1];
  touch->touch = true;
  {
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    deck_map()[pair[0]] = touch;
    watch_fd(pair[0]);
  }
  touch_state([](TouchRingFile &r) { r.opened++; });
  pthread_t thread;
  auto *arg = new std::shared_ptr<DeckHidraw>(touch);
  if (pthread_create(&thread, nullptr, touch_report_thread, arg) != 0) {
    delete arg;
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    deck_map().erase(pair[0]);
    unwatch_fd(pair[0]);
    syscall(SYS_close, pair[0]);
    syscall(SYS_close, pair[1]);
    touch_state([](TouchRingFile &r) { if (r.opened) r.opened--; });
    errno = ENOMEM;
    return -1;
  }
  pthread_detach(thread);
  Logger::log("touch: %s opened as fd %d\n", TOUCH_HIDRAW_PATH, pair[0]);
  return pair[0];
}

static int copy_ioctl_string(ioctl_request_t op, void *argp, const char *value);

__attribute__((visibility("hidden"))) static int ioctl_touch(DeckHidraw &touch, ioctl_request_t op, void *argp) {
  if (_IOC_TYPE(op) != 'H') {
    errno = ENOTTY;
    return -1;
  }
  size_t size = _IOC_SIZE(op);
  switch (_IOC_NR(op)) {
  case 0x01:
    *static_cast<int *>(argp) = sizeof(kTouchReportDescriptor);
    return 0;
  case 0x02: {
    auto *descriptor = static_cast<uint8_t *>(argp);
    uint32_t want;
    memcpy(&want, descriptor, sizeof(want));
    memcpy(descriptor + 4, kTouchReportDescriptor, std::min<size_t>(want, sizeof(kTouchReportDescriptor)));
    return 0;
  }
  case 0x03: {
    struct {
      uint32_t bustype;
      int16_t vendor;
      int16_t product;
    } info = {BUS_USB, 0x0000, static_cast<int16_t>(0x11fb)};
    memcpy(argp, &info, sizeof(info));
    return 0;
  }
  case 0x04: return copy_ioctl_string(op, argp, TOUCH_NAME);
  case 0x05: return copy_ioctl_string(op, argp, "usb-droiddeck-2/input0");
  case 0x08: return copy_ioctl_string(op, argp, TOUCH_SERIAL);
  case 0x06:
    touch.pending_feature = size > 0 ? static_cast<uint8_t *>(argp)[0] : 0;
    return static_cast<int>(size);
  case 0x07: {
    auto *buf = static_cast<uint8_t *>(argp);
    uint8_t id = size ? buf[0] : 0;
    memset(buf, 0, size);
    if (size) buf[0] = id;
    if (size > 2) {
      TouchRingFile *ring = touch_ring();
      buf[1] = 2;
      buf[2] = static_cast<uint8_t>(ring && ring->battery ? std::min<uint32_t>(ring->battery, 100) : 100);
    }
    return static_cast<int>(std::min<size_t>(size, 18));
  }
  default:
    errno = EINVAL;
    return -1;
  }
}

__attribute__((visibility("hidden"))) static int inject_uevent(const char *action, const char *devpath,
                                                               const char *subsystem, const char *devname,
                                                               unsigned major, unsigned minor) {
  using inject_fn = int (*)(const char *, const char *, const char *, const char *, unsigned, unsigned);
  static auto inject = reinterpret_cast<inject_fn>(dlsym(RTLD_DEFAULT, "bl_udevmon_inject"));
  return inject ? inject(action, devpath, subsystem, devname, major, minor) : -1;
}

__attribute__((visibility("hidden"))) static void touch_uevent(const char *action) {
  int sent = inject_uevent(action, TOUCH_DEVPATH, "hidraw", TOUCH_HIDRAW_PATH, DECK_HIDRAW_MAJOR, TOUCH_HIDRAW_MINOR);
  Logger::log("touch: %s (udev event to %d monitor%s)\n", action, sent, sent == 1 ? "" : "s");
}

static constexpr const char *DECK_DEVPATH = "/devices/droiddeck/usb1/1-1:1.2/0003:28DE:1205.0001/hidraw/hidraw16";
static constexpr const char *TRITON_PAD_DEVPATH = "/devices/droiddeck/usb3/3-1:1.0/0003:28DE:1302.0004/hidraw/hidraw19";
static constexpr const char *SC1_DEVPATH = "/devices/droiddeck/usb7/7-1:1.2/0003:28DE:1102.0008/hidraw/hidraw23";
static constexpr const char *PAD_EVDEV_DEVPATH = "/devices/virtual/input/droiddeck0/event0";
static constexpr const char *DS4_DEVPATH = "/devices/droiddeck/usb4/4-1:1.3/0003:054C:09CC.0005/hidraw/hidraw20";
static constexpr const char *DUALSENSE_DEVPATH = "/devices/droiddeck/usb5/5-1:1.3/0003:054C:0CE6.0006/hidraw/hidraw21";
static constexpr const char *DUALSENSE_EDGE_DEVPATH = "/devices/droiddeck/usb6/6-1:1.3/0003:054C:0DF2.0007/hidraw/hidraw22";
static constexpr const char *TRITON_DEVPATH = "/devices/droiddeck/bluetooth/0005:28DE:1303.0003/hidraw/hidraw18";

__attribute__((visibility("hidden"))) static int pad_type_uevent(uint32_t type, const char *action) {
  if (type == PAD_TYPE_NONE) return 0;  // sdl3bridge: no pad (real controllers handed to Steam)
  if (type == PAD_TYPE_DECK)
    return inject_uevent(action, DECK_DEVPATH, "hidraw", DECK_HIDRAW_PATH, DECK_HIDRAW_MAJOR, DECK_HIDRAW_MINOR);
  if (type == PAD_TYPE_DS4)
    return inject_uevent(action, DS4_DEVPATH, "hidraw", DS4_HIDRAW_PATH, DECK_HIDRAW_MAJOR, DS4_HIDRAW_MINOR);
  if (type == PAD_TYPE_DUALSENSE)
    return inject_uevent(action, DUALSENSE_DEVPATH, "hidraw", DUALSENSE_HIDRAW_PATH, DECK_HIDRAW_MAJOR,
                         DUALSENSE_HIDRAW_MINOR);
  if (type == PAD_TYPE_DUALSENSE_EDGE)
    return inject_uevent(action, DUALSENSE_EDGE_DEVPATH, "hidraw", DUALSENSE_EDGE_HIDRAW_PATH, DECK_HIDRAW_MAJOR,
                         DUALSENSE_EDGE_HIDRAW_MINOR);
  if (type == PAD_TYPE_STEAM_2015)
    return inject_uevent(action, SC1_DEVPATH, "hidraw", SC1_HIDRAW_PATH, DECK_HIDRAW_MAJOR, SC1_HIDRAW_MINOR);
  if (type == PAD_TYPE_STEAM)
    return inject_uevent(action, TRITON_PAD_DEVPATH, "hidraw", TRITON_PAD_HIDRAW_PATH, TRITON_HIDRAW_MAJOR,
                         TRITON_PAD_HIDRAW_MINOR);
  dev_t rdev = get_fake_input_rdev("event0");
  return inject_uevent(action, PAD_EVDEV_DEVPATH, "input", "/dev/input/event0", major(rdev), minor(rdev));
}

__attribute__((visibility("hidden"))) static std::string hidraw_event_dir() {
  return std::string(fake_hook_dir()) + "/.hidraw-events";
}

__attribute__((visibility("hidden"))) static void poke_hidraw_watchers(unsigned minor) {
  std::string path = hidraw_event_dir() + "/hidraw" + std::to_string(minor);
  int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, path.c_str(), O_CREAT | O_WRONLY | O_CLOEXEC, 0600));
  if (fd >= 0) syscall(SYS_close, fd);
  unlink(path.c_str());
}

__attribute__((visibility("hidden"))) static unsigned pad_type_minor(uint32_t type) {
  return type == PAD_TYPE_DS4 ? DS4_HIDRAW_MINOR
       : type == PAD_TYPE_DUALSENSE ? DUALSENSE_HIDRAW_MINOR
       : type == PAD_TYPE_DUALSENSE_EDGE ? DUALSENSE_EDGE_HIDRAW_MINOR
       : type == PAD_TYPE_STEAM ? TRITON_PAD_HIDRAW_MINOR
       : type == PAD_TYPE_STEAM_2015 ? SC1_HIDRAW_MINOR : DECK_HIDRAW_MINOR;
}

__attribute__((visibility("hidden"))) static void *pad_type_thread(void *) {
  static const char *kNames[] = {"Steam Deck controller", "Steam Controller", "Xbox 360 controller",
                                 "DualShock 4", "DualSense", "DualSense Edge",
                                 "Steam Controller (2015)", "no pad"};
  uint32_t was = live_pad_type();
  bool paired = fake_triton_enabled() && triton_connected();
  Logger::log("pad: a %s at start%s\n", kNames[was % 8], paired ? ", the paired Steam Controller connected" : "");
  for (;;) {
    struct timespec interval = {0, 50 * 1000 * 1000};
    nanosleep(&interval, nullptr);
    bool connected = fake_triton_enabled() && triton_connected();
    if (connected != paired) {
      paired = connected;
      int sent = inject_uevent(connected ? "add" : "remove", TRITON_DEVPATH, "hidraw", TRITON_HIDRAW_PATH,
                               TRITON_HIDRAW_MAJOR, TRITON_HIDRAW_MINOR);
      Logger::log("pad: the paired Steam Controller %s (udev event to %d monitors)\n",
                  connected ? "connected" : "disconnected", sent);
    }
    uint32_t now = live_pad_type();
    if (now == was || now > PAD_TYPE_LAST) continue;
    int removed = pad_type_uevent(was, "remove");
    struct timespec settle = {0, 300 * 1000 * 1000};
    nanosleep(&settle, nullptr);
    int added = pad_type_uevent(now, "add");
    if (was != PAD_TYPE_XBOX) poke_hidraw_watchers(pad_type_minor(was));
    if (now != PAD_TYPE_XBOX) poke_hidraw_watchers(pad_type_minor(now));
    if (was == PAD_TYPE_XBOX || now == PAD_TYPE_XBOX) {
      std::string node = std::string(fake_hook_dir()) + "/event0";
      std::string aside = std::string(fake_hook_dir()) + "/.event0-switching";
      if (rename(node.c_str(), aside.c_str()) == 0) {
        nanosleep(&settle, nullptr);
        if (rename(aside.c_str(), node.c_str()) != 0)
          Logger::log("pad: event0 not put back (%s)\n", strerror(errno));
      } else {
        Logger::log("pad: event0 not moved (%s)\n", strerror(errno));
      }
    }
    Logger::log("pad: now a %s (udev events to %d/%d monitors)\n", kNames[now], removed, added);
    was = now;
  }
  return nullptr;
}

__attribute__((constructor)) static void start_pad_type_watch() {
  config();
  if (!process_is_steam_client() || !fake_deck_enabled()) return;
  pthread_t thread;
  if (pthread_create(&thread, nullptr, pad_type_thread, nullptr) == 0) pthread_detach(thread);
}

__attribute__((visibility("hidden"))) static void *touch_plug_thread(void *) {
  bool was = touch_plugged();
  Logger::log("touch: %s at start\n", was ? "plugged in" : "unplugged");
  for (;;) {
    struct timespec interval = {0, 50 * 1000 * 1000};
    nanosleep(&interval, nullptr);
    bool now = touch_plugged();
    if (now == was) continue;
    was = now;
    touch_uevent(now ? "add" : "remove");
  }
  return nullptr;
}

__attribute__((constructor)) static void start_touch_controller() {
  config();
  if (!process_is_steam_client() || !touch_ring()) return;
  pthread_t thread;
  if (pthread_create(&thread, nullptr, touch_plug_thread, nullptr) == 0) pthread_detach(thread);
}

static constexpr long SYSFS_FS_MAGIC = 0x62656572;

__attribute__((visibility("hidden"))) static const char *deck_listing_dir() {
  static const char *dir = getenv("FAKE_DECK_SYSFS_LISTING");
  return dir && *dir ? dir : nullptr;
}

__attribute__((visibility("hidden"))) static bool is_deck_sysfs_path(const char *path) {
  if (!path) return false;
  if (!strncmp(path, "/sys/devices/droiddeck", 22) || !strncmp(path, "/sys/class/hidraw", 17)) return true;
  const char *listing = deck_listing_dir();
  return listing && !strncmp(path, listing, strlen(listing));
}

__attribute__((visibility("hidden"))) static bool is_deck_sysfs_fd(int fd) {
  char link[64], path[PATH_MAX];
  snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
  ssize_t length = readlink(link, path, sizeof(path) - 1);
  if (length <= 0) return false;
  path[length] = '\0';
  return is_deck_sysfs_path(path);
}

__attribute__((visibility("hidden"))) static void note_deck_sysfs(const char *how, int result) {
  static std::atomic<bool> noted{false};
  if (!noted.exchange(true))
    Logger::log("deck: sysfs checked (%s%s)\n", how, result == 0 ? "" : ", failed");
}

EXPORT int fstatfs(int fd, struct statfs *buf) {
  static auto real = reinterpret_cast<int (*)(int, struct statfs *)>(dlsym(RTLD_NEXT, "fstatfs"));
  int result = real(fd, buf);
  if (fake_deck_enabled() && is_deck_sysfs_fd(fd)) {
    note_deck_sysfs("fstatfs", result);
    if (result == 0) buf->f_type = SYSFS_FS_MAGIC;
  }
  return result;
}

EXPORT int statfs(const char *path, struct statfs *buf) {
  static auto real = reinterpret_cast<int (*)(const char *, struct statfs *)>(dlsym(RTLD_NEXT, "statfs"));
  int result = real(path, buf);
  if (fake_deck_enabled() && is_deck_sysfs_path(path)) {
    note_deck_sysfs(path, result);
    if (result == 0) buf->f_type = SYSFS_FS_MAGIC;
  }
  return result;
}

#ifdef __GLIBC__
EXPORT int fstatfs64(int fd, struct statfs64 *buf) {
  return fstatfs(fd, reinterpret_cast<struct statfs *>(buf));
}

EXPORT int statfs64(const char *path, struct statfs64 *buf) {
  return statfs(path, reinterpret_cast<struct statfs *>(buf));
}
#endif

__attribute__((visibility("hidden"))) static int copy_ioctl_string(ioctl_request_t op, void *argp, const char *value) {
  size_t size = _IOC_SIZE(op);
  if (!argp || !size) return 0;
  snprintf(static_cast<char *>(argp), size, "%s", value);
  return static_cast<int>(std::min(size, strlen(value) + 1));
}

__attribute__((visibility("hidden"))) static void log_deck_feature(const char *what, uint8_t feature) {
  static std::atomic<unsigned> counts[2][256];
  unsigned n = ++counts[what[0] == 's'][feature];
  if ((n & (n - 1)) == 0) Logger::log("deck: feature 0x%02x %s (x%u)\n", feature, what, n);
}

static constexpr uint8_t DECK_SET_SETTINGS_VALUES = 0x87;
static constexpr uint8_t DECK_SETTING_IMU_MODE = 48;

__attribute__((visibility("hidden"))) static int deck_imu_mode_change(const uint8_t *buf, size_t size) {
  static std::atomic<int32_t> last{-1};
  size_t end = std::min(size, static_cast<size_t>(3) + buf[2]);
  for (size_t i = 3; i + 3 <= end; i += 3) {
    if (buf[i] != DECK_SETTING_IMU_MODE) continue;
    int32_t value = buf[i + 1] | buf[i + 2] << 8;
    if (last.exchange(value) == value) return -1;
    Logger::log("deck: imu mode 0x%04x\n", value);
    return value;
  }
  return -1;
}

__attribute__((visibility("hidden"))) static void publish_imu_mode(const DeckHidraw &deck, int mode) {
  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));
  if (deck.mapping_size < FAKE_INPUT_RING_SIZE + DECK_IMU_BLOCK_SIZE) return;
  std::string path = get_ring_path_for_slot(0);
  int fd = path.empty() ? -1 : my_open(path.c_str(), O_WRONLY | O_CLOEXEC);
  if (fd < 0) return;
  uint32_t value = 0x80000000u | static_cast<uint32_t>(mode);
  if (pwrite(fd, &value, sizeof(value), FAKE_INPUT_RING_SIZE + offsetof(DeckImu, imu_mode)) != sizeof(value))
    Logger::log("deck: imu mode not published (%s)\n", strerror(errno));
  syscall(SYS_close, fd);
}

__attribute__((visibility("hidden"))) static int
deck_feature(DeckHidraw &deck, ioctl_request_t op, uint8_t *buf, bool set) {
  size_t size = _IOC_SIZE(op);
  if (!buf || size < 2) {
    errno = EINVAL;
    return -1;
  }
  if (set) {
    deck.pending_feature = buf[1];
    if (buf[1] == DECK_TRIGGER_RUMBLE_CMD && size >= 10) {
      int left = buf[6] | buf[7] << 8, right = buf[8] | buf[9] << 8;
      send_vibration(left, right, left || right ? 1000 : 0, 0);
    }
    // Steam rumbles / identifies a 2015 Steam Controller with trackpad haptic pulses (0x8f: side,
    // on-us, off-us, repeat). Forward the long ones as rumble; short ones are trackpad ticks.
    if (deck.sc1 && buf[1] == 0x8f && size >= 10) {
      int on_us = buf[4] | buf[5] << 8, off_us = buf[6] | buf[7] << 8, repeat = buf[8] | buf[9] << 8;
      long total_ms = static_cast<long>(on_us + off_us) * std::max(1, repeat) / 1000;
      if (total_ms >= 20) send_vibration(0, 40000, static_cast<uint16_t>(std::min(1000L, total_ms)), 0);
    }
    if (buf[1] == DECK_SET_SETTINGS_VALUES && size >= 3) {
      int imu_mode = deck_imu_mode_change(buf, size);
      if (imu_mode >= 0) publish_imu_mode(deck, imu_mode);
    }
    log_deck_feature("set", buf[1]);
    return static_cast<int>(size);
  }
  uint8_t reply[DECK_REPORT_BYTES + 1] = {};
  switch (deck.pending_feature) {
  case DECK_GET_ATTRIBUTES_VALUES: {
    static const uint8_t kAttributes[] = {0x00, 0x83, 0x2d, 0x01, 0x05, 0x12, 0x00, 0x00, 0x02,
                                          0x00, 0x00, 0x00, 0x00, 0x0a, 0x2b, 0x12, 0xa9, 0x62,
                                          0x04, 0xad, 0xf1, 0xe4, 0x65, 0x09, 0x2e, 0x00, 0x00,
                                          0x00, 0x0b, 0xa0, 0x0f, 0x00, 0x00, 0x0d, 0x00, 0x00,
                                          0x00, 0x00, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x0e};
    memcpy(reply, kAttributes, sizeof(kAttributes));
    if (deck.sc1) {  // ATTRIB_PRODUCT_ID = 0x1102
      reply[4] = 0x02;
      reply[5] = 0x11;
    }
    break;
  }
  case DECK_GET_STRING_ATTRIBUTE:
    reply[1] = DECK_GET_STRING_ATTRIBUTE;
    reply[2] = 0x14;
    reply[3] = 0x01;
    memcpy(reply + 4, deck.sc1 ? SC1_SERIAL : DECK_SERIAL, strlen(deck.sc1 ? SC1_SERIAL : DECK_SERIAL));
    break;
  case DECK_GET_CHIP_ID:
    reply[1] = DECK_GET_CHIP_ID;
    reply[2] = 0x11;
    memcpy(reply + 4, "DROIDDECKCHIP01", 15);
    break;
  default:
    reply[1] = deck.pending_feature;
    break;
  }
  log_deck_feature("read", deck.pending_feature);
  size_t length = std::min(size, sizeof(reply));
  memcpy(buf, reply, length);
  return static_cast<int>(length);
}

static const uint8_t kDs4ReportDescriptor[] = {
    0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x06, 0x00, 0xff, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08,
    0x85, 0x01, 0x09, 0x20, 0x95, 0x3f, 0x81, 0x02, 0x85, 0x05, 0x09, 0x21, 0x95, 0x1f, 0x91, 0x02,
    0x85, 0x02, 0x09, 0x24, 0x95, 0x24, 0xb1, 0x02, 0x85, 0x12, 0x09, 0x25, 0x95, 0x0f, 0xb1, 0x02,
    0x85, 0xa3, 0x09, 0x26, 0x95, 0x30, 0xb1, 0x02, 0x85, 0x81, 0x09, 0x27, 0x95, 0x3f, 0xb1, 0x02,
    0xc0};
static const uint8_t kDualSenseReportDescriptor[] = {
    0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x06, 0x00, 0xff, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08,
    0x85, 0x01, 0x09, 0x20, 0x95, 0x3f, 0x81, 0x02, 0x85, 0x02, 0x09, 0x21, 0x95, 0x2f, 0x91, 0x02,
    0x85, 0x05, 0x09, 0x22, 0x95, 0x28, 0xb1, 0x02, 0x85, 0x09, 0x09, 0x23, 0x95, 0x13, 0xb1, 0x02,
    0x85, 0x20, 0x09, 0x24, 0x95, 0x3f, 0xb1, 0x02, 0xc0};

__attribute__((visibility("hidden"))) static void put_sony_calibration(uint8_t *reply) {
  for (int axis = 0; axis < 3; axis++) {
    put16(reply + 7 + axis * 4, 8640);
    put16(reply + 9 + axis * 4, -8640);
    put16(reply + 23 + axis * 4, 8192);
    put16(reply + 25 + axis * 4, -8192);
  }
  put16(reply + 19, 540);
  put16(reply + 21, 540);
}

__attribute__((visibility("hidden"))) static int sony_feature(const DeckHidraw &pad, uint8_t *buf, size_t size) {
  if (!buf || size < 1) {
    errno = EINVAL;
    return -1;
  }
  uint8_t id = buf[0];
  uint8_t reply[64] = {};
  reply[0] = id;
  size_t length = size;
  if ((pad.sony == 4 && id == 0x02) || ((pad.sony == 5 || pad.sony == 6) && id == 0x05)) {
    put_sony_calibration(reply);
  } else if ((pad.sony == 4 && (id == 0x12 || id == 0x81)) || ((pad.sony == 5 || pad.sony == 6) && id == 0x09)) {
    for (int i = 0; i < 6; i++) reply[1 + i] = SONY_MAC[5 - i];
  } else if ((pad.sony == 5 || pad.sony == 6) && id == 0x20) {
    reply[44] = 0x30;
    reply[45] = 0x04;
  }
  log_deck_feature("read", id);
  length = std::min(length, sizeof(reply));
  memcpy(buf, reply, length);
  return static_cast<int>(length);
}

__attribute__((visibility("hidden"))) static void sony_output(const DeckHidraw &pad, const uint8_t *data, size_t size) {
  if (!data || size < 6) return;
  // sdl3bridge: sony rumble v2
  static int last_strong = -1, last_weak = -1;
  static unsigned logged = 0;
  int weak = 0, strong = 0;
  if (pad.sony == 4 && data[0] == 0x05) {
    if (!(data[1] & 0x01)) return;  // DS4: motors untouched by this report
    weak = data[4];
    strong = data[5];
  } else if ((pad.sony == 5 || pad.sony == 6) && data[0] == 0x02) {
    // [1] enable bits1 (0x01 rumble emulation, 0x02 haptics off), [3] right/weak, [4] left/strong,
    // [39] enable bits3 (0x04 = improved rumble, fw >= 2.24 / Edge). Full state is re-sent every
    // report, and the bits are cleared once the motors stop: no bits = motors off.
    bool rumble = (data[1] & 0x03) || (size > 39 && (data[39] & 0x04));
    if (rumble) {
      weak = data[3];
      strong = data[4];
    }
  } else {
    return;
  }
  if (strong == last_strong && weak == last_weak) return;  // LED / repeat report: nothing changed
  if (logged < 40) {
    logged++;
    Logger::log("sony: out 0x%02x bits %02x/%02x motors %d/%d\n", data[0], data[1],
                size > 39 ? data[39] : 0, strong, weak);
  }
  last_strong = strong;
  last_weak = weak;
  send_vibration(strong * 257, weak * 257, strong || weak ? RUMBLE_HOLD_MS : 0, 0);
}

__attribute__((visibility("hidden"))) static int ioctl_sony(DeckHidraw &pad, ioctl_request_t op, void *argp) {
  if (_IOC_TYPE(op) != 'H') {
    errno = ENOTTY;
    return -1;
  }
  const uint8_t *descriptor = pad.sony == 4 ? kDs4ReportDescriptor : kDualSenseReportDescriptor;
  size_t descriptor_size = pad.sony == 4 ? sizeof(kDs4ReportDescriptor) : sizeof(kDualSenseReportDescriptor);
  switch (_IOC_NR(op)) {
  case 0x01:
    *static_cast<int *>(argp) = static_cast<int>(descriptor_size);
    return 0;
  case 0x02: {
    auto *out = static_cast<uint8_t *>(argp);
    uint32_t size;
    memcpy(&size, out, sizeof(size));
    memcpy(out + 4, descriptor, std::min<size_t>(size, descriptor_size));
    return 0;
  }
  case 0x03: {
    struct {
      uint32_t bustype;
      int16_t vendor;
      int16_t product;
    } info = {BUS_USB, static_cast<int16_t>(0x054c), static_cast<int16_t>(pad.sony == 4 ? 0x09cc : pad.sony == 6 ? 0x0df2 : 0x0ce6)};
    memcpy(argp, &info, sizeof(info));
    return 0;
  }
  case 0x04:
    return copy_ioctl_string(op, argp, pad.sony == 4 ? "Sony Interactive Entertainment Wireless Controller"
                                                     : pad.sony == 6 ? "Sony Interactive Entertainment DualSense Edge Wireless Controller" : "Sony Interactive Entertainment DualSense Wireless Controller");
  case 0x05: return copy_ioctl_string(op, argp, pad.sony == 4 ? "usb-droiddeck-4/input3" : pad.sony == 6 ? "usb-droiddeck-6/input3" : "usb-droiddeck-5/input3");
  case 0x08: return copy_ioctl_string(op, argp, "5e:43:4b:44:44:02");
  case 0x06:
    return static_cast<int>(_IOC_SIZE(op));
  case 0x07: return sony_feature(pad, static_cast<uint8_t *>(argp), _IOC_SIZE(op));
  default:
    errno = EINVAL;
    return -1;
  }
}

__attribute__((visibility("hidden"))) static int
ioctl_deck(DeckHidraw &deck, ioctl_request_t op, void *argp) {
  if (_IOC_TYPE(op) != 'H') {
    errno = ENOTTY;
    return -1;
  }
  switch (_IOC_NR(op)) {
  case 0x01:
    *static_cast<int *>(argp) = sizeof(kDeckReportDescriptor);
    return 0;
  case 0x02: {
    auto *descriptor = static_cast<uint8_t *>(argp);
    uint32_t size;
    memcpy(&size, descriptor, sizeof(size));
    memcpy(descriptor + 4, kDeckReportDescriptor, std::min<size_t>(size, sizeof(kDeckReportDescriptor)));
    return 0;
  }
  case 0x03: {
    struct {
      uint32_t bustype;
      int16_t vendor;
      int16_t product;
    } info = {BUS_USB, static_cast<int16_t>(0x28de), static_cast<int16_t>(deck.sc1 ? 0x1102 : 0x1205)};
    memcpy(argp, &info, sizeof(info));
    return 0;
  }
  case 0x04: return copy_ioctl_string(op, argp, deck.sc1 ? SC1_NAME : DECK_NAME);
  case 0x05: return copy_ioctl_string(op, argp, deck.sc1 ? "usb-droiddeck-7/input2" : "usb-droiddeck-1/input2");
  case 0x08: return copy_ioctl_string(op, argp, deck.sc1 ? SC1_SERIAL : DECK_SERIAL);
  case 0x06: return deck_feature(deck, op, static_cast<uint8_t *>(argp), true);
  case 0x07: return deck_feature(deck, op, static_cast<uint8_t *>(argp), false);
  default:
    Logger::log("deck: unhandled hidraw ioctl 0x%02x\n", _IOC_NR(op));
    errno = EINVAL;
    return -1;
  }
}

__attribute__((visibility("hidden"))) static void triton_queue_output(DeckHidraw &triton, const uint8_t *data,
                                                                         size_t size, bool feature) {
  if (!data || !size || size > 64 || triton.mapping_size < TRITON_SHARED_OFFSET + TRITON_SHARED_BLOCK_SIZE) return;
  auto *shared = reinterpret_cast<TritonSharedData *>(reinterpret_cast<uint8_t *>(triton.ring) + TRITON_SHARED_OFFSET);
  if (__atomic_load_n(&shared->magic, __ATOMIC_ACQUIRE) != TRITON_SHARED_MAGIC) return;
  static std::mutex one_at_a_time;
  std::lock_guard<std::mutex> guard(one_at_a_time);
  for (int waited = 0;
       static_cast<uint32_t>(__atomic_load_n(&shared->output_seq, __ATOMIC_ACQUIRE)) !=
           __atomic_load_n(&shared->output_ack, __ATOMIC_ACQUIRE) &&
       __atomic_load_n(&shared->connected, __ATOMIC_ACQUIRE) != 0 && waited < TRITON_OUTPUT_TIMEOUT_MS * 4;
       waited++) {
    struct timespec interval = {0, 250 * 1000L};
    nanosleep(&interval, nullptr);
  }
  uint64_t seq = __atomic_load_n(&shared->output_seq, __ATOMIC_RELAXED);
  if (seq & 1) seq++;
  __atomic_store_n(&shared->output_seq, seq + 1, __ATOMIC_RELEASE);
  shared->output_feature = feature ? 1 : 0;
  shared->output_length = static_cast<uint16_t>(size);
  memcpy(shared->output, data, size);
  if (size < sizeof(shared->output)) memset(shared->output + size, 0, sizeof(shared->output) - size);
  __atomic_thread_fence(__ATOMIC_RELEASE);
  __atomic_store_n(&shared->output_seq, seq + 2, __ATOMIC_RELEASE);
  static std::atomic<unsigned> counts[2][256];
  uint8_t report_id = feature ? (size > 1 ? data[1] : 0) : data[0];
  unsigned n = ++counts[feature ? 1 : 0][report_id];
  if ((n & (n - 1)) == 0)
    Logger::log("triton: %s report 0x%02x (x%u, %zu bytes)\n", feature ? "feature" : "output", report_id, n, size);
}

__attribute__((visibility("hidden"))) static int triton_get_feature(DeckHidraw &triton, uint8_t *report, size_t size) {
  if (!report || size < 2 || triton.mapping_size < TRITON_SHARED_OFFSET + TRITON_SHARED_BLOCK_SIZE) {
    errno = EINVAL;
    return -1;
  }
  auto *shared = reinterpret_cast<TritonSharedData *>(reinterpret_cast<uint8_t *>(triton.ring) + TRITON_SHARED_OFFSET);
  static std::mutex one_at_a_time;
  std::lock_guard<std::mutex> guard(one_at_a_time);
  uint8_t report_id = report[0];
  __atomic_store_n(&shared->get_report_id, report_id, __ATOMIC_RELAXED);
  uint64_t request = __atomic_add_fetch(&shared->get_request, 1, __ATOMIC_RELEASE);
  for (int waited = 0; waited < TRITON_FEATURE_TIMEOUT_MS * 4; waited++) {
    if (__atomic_load_n(&shared->get_reply, __ATOMIC_ACQUIRE) == request) {
      int32_t length = __atomic_load_n(&shared->get_length, __ATOMIC_RELAXED);
      if (length <= 0 || length > static_cast<int32_t>(sizeof(shared->get))) break;
      size_t copied;
      memset(report, 0, size);
      if (shared->get[0] == report_id) {
        copied = std::min(size, static_cast<size_t>(length));
        memcpy(report, shared->get, copied);
      } else {
        report[0] = report_id;
        copied = 1 + std::min(size - 1, static_cast<size_t>(length));
        memcpy(report + 1, shared->get, copied - 1);
      }
      static std::atomic<unsigned> reads[256];
      unsigned n = ++reads[report_id];
      if ((n & (n - 1)) == 0)
        Logger::log("triton: feature 0x%02x read, %d bytes from the controller [%02x %02x %02x %02x] (x%u)\n", report_id,
                    length, shared->get[0], shared->get[1], shared->get[2], shared->get[3], n);
      return static_cast<int>(copied);
    }
    struct timespec interval = {0, 250 * 1000L};
    nanosleep(&interval, nullptr);
  }
  static std::atomic<unsigned> failures;
  unsigned n = ++failures;
  if ((n & (n - 1)) == 0) Logger::log("triton: feature 0x%02x read got no answer from the controller (x%u)\n", report_id, n);
  errno = EIO;
  return -1;
}

static constexpr uint32_t TRITON_HWID = 72;
static constexpr uint32_t TRITON_FW_TIMESTAMP = 0x6AC686B3;

// Steam compares the controller's firmware with TRITON_FW_TS in its bundled hardwareupdater.cfg (and blocks
// with a required update below MUST_UPDATE_TRITON_FW_TS), so the emulated pad reports at least the build
// Steam ships.
static uint32_t triton_fw_timestamp() {
  static const uint32_t timestamp = [] {
    std::vector<std::string> roots;
    char exe[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n > 0) {
      std::string path(exe, static_cast<size_t>(n));
      size_t bin = path.rfind('/');
      size_t root = bin == std::string::npos || bin == 0 ? std::string::npos : path.rfind('/', bin - 1);
      if (bin != std::string::npos && bin > 0) roots.push_back(path.substr(0, bin));
      if (root != std::string::npos) roots.push_back(path.substr(0, root));
    }
    if (const char *home = getenv("HOME")) roots.push_back(std::string(home) + "/.local/share/Steam");
    uint32_t newest = TRITON_FW_TIMESTAMP;
    for (const std::string &root : roots) {
      std::ifstream cfg(root + "/bin/hardwareupdater/hardwareupdater.cfg");
      std::string line;
      while (std::getline(cfg, line)) {
        if (line.rfind("TRITON_FW_TS:", 0) != 0) continue;
        uint32_t shipped = static_cast<uint32_t>(strtoul(line.c_str() + 13, nullptr, 16));
        if (shipped > newest) newest = shipped;
      }
    }
    if (newest != TRITON_FW_TIMESTAMP) Logger::log("triton: reporting firmware %08x, the build Steam ships\n", newest);
    return newest;
  }();
  return timestamp;
}

__attribute__((visibility("hidden"))) static int triton_pad_feature(DeckHidraw &triton, uint8_t *buf, size_t size, bool set) {
  if (!buf || size < 2) {
    errno = EINVAL;
    return -1;
  }
  if (set) {
    triton.pending_feature = buf[1];
    if (buf[1] == DECK_SET_SETTINGS_VALUES && size >= 3) {
      int imu_mode = deck_imu_mode_change(buf, size);
      if (imu_mode >= 0) publish_imu_mode(triton, imu_mode);
    }
    log_deck_feature("set", buf[1]);
    return static_cast<int>(size);
  }
  uint8_t reply[65] = {};
  reply[0] = buf[0];
  reply[1] = triton.pending_feature;
  switch (triton.pending_feature) {
  case DECK_GET_ATTRIBUTES_VALUES: {
    static const uint8_t kIds[] = {0x01, 0x02, 0x04, 0x09, 0x0a, 0x0b};
    const uint32_t values[] = {static_cast<uint32_t>(TRITON_WIRED_PRODUCT), 0, triton_fw_timestamp(), TRITON_HWID, 0, 4000};
    reply[2] = sizeof(kIds) * 5;
    for (size_t i = 0; i < sizeof(kIds); i++) {
      reply[3 + i * 5] = kIds[i];
      put32(reply + 4 + i * 5, values[i]);
    }
    break;
  }
  case DECK_GET_STRING_ATTRIBUTE:
    reply[2] = 0x14;
    reply[3] = 0x01;
    memcpy(reply + 4, TRITON_SERIAL, strlen(TRITON_SERIAL));
    break;
  case DECK_GET_CHIP_ID:
    reply[2] = 0x11;
    memcpy(reply + 4, "DROIDDECKCHIP02", 15);
    break;
  default:
    break;
  }
  log_deck_feature("read", triton.pending_feature);
  size_t length = std::min(size, sizeof(reply));
  memcpy(buf, reply, length);
  return static_cast<int>(length);
}

__attribute__((visibility("hidden"))) static void triton_pad_output(const uint8_t *data, size_t size) {
  if (!data || size < 2) return;
  switch (data[0]) {
  case 0x80:
    if (size >= 10) {
      int left = data[4] | data[5] << 8, right = data[7] | data[8] << 8;
      send_vibration(left, right, left || right ? 100 : 0, 0);
    }
    break;
  case 0x81:
    if (size >= 8) {
      int on_ms = (data[2] | data[3] << 8) / 1000, repeat = data[6] | data[7] << 8;
      send_vibration(0, 40000, std::max(8, std::min(200, on_ms * std::max(1, repeat))), 0);
    }
    break;
  case 0x82: case 0x83: case 0x84: case 0x85:
    send_vibration(0, 30000, 15, 0);
    break;
  default:
    break;
  }
}

__attribute__((visibility("hidden"))) static int ioctl_triton(DeckHidraw &triton, ioctl_request_t op, void *argp) {
  if (_IOC_TYPE(op) != 'H') {
    errno = ENOTTY;
    return -1;
  }
  switch (_IOC_NR(op)) {
  case 0x01:
    *static_cast<int *>(argp) = sizeof(kTritonReportDescriptor);
    return 0;
  case 0x02: {
    auto *descriptor = static_cast<uint8_t *>(argp);
    uint32_t size;
    memcpy(&size, descriptor, sizeof(size));
    memcpy(descriptor + 4, kTritonReportDescriptor, std::min<size_t>(size, sizeof(kTritonReportDescriptor)));
    if (triton.emulated && size > 15) descriptor[4 + 15] = TRITON_STATE_WIRED;
    return 0;
  }
  case 0x03: {
    struct {
      uint32_t bustype;
      int16_t vendor;
      int16_t product;
    } info = {triton.emulated ? static_cast<uint32_t>(BUS_USB) : static_cast<uint32_t>(BUS_BLUETOOTH),
              static_cast<int16_t>(0x28de), triton.emulated ? TRITON_WIRED_PRODUCT : static_cast<int16_t>(0x1303)};
    memcpy(argp, &info, sizeof(info));
    return 0;
  }
  case 0x04: return copy_ioctl_string(op, argp, TRITON_NAME);
  case 0x05: return copy_ioctl_string(op, argp, triton.emulated ? "usb-droiddeck-3/input0" : "bluetooth-droiddeck-1");
  case 0x08: return copy_ioctl_string(op, argp, TRITON_SERIAL);
  case 0x06: {
    size_t size = _IOC_SIZE(op);
    if (!argp || size < 2 || size > 65) {
      errno = EINVAL;
      return -1;
    }
    if (triton.emulated) return triton_pad_feature(triton, static_cast<uint8_t *>(argp), size, true);
    triton_queue_output(triton, static_cast<const uint8_t *>(argp), std::min<size_t>(size, 64), true);
    return static_cast<int>(size);
  }
  case 0x07:
    if (triton.emulated) return triton_pad_feature(triton, static_cast<uint8_t *>(argp), _IOC_SIZE(op), false);
    return triton_get_feature(triton, static_cast<uint8_t *>(argp), _IOC_SIZE(op));
  default:
    errno = EINVAL;
    return -1;
  }
}

// ===================== sdl3bridge: Steam Native Hook-up =====================
// The SDL3 bridge app passes a USB controller through raw: its real USB identity (vendor/product id,
// name, serial, interface, HID report descriptor) and every HID report, both ways. Here each such
// controller becomes a real-looking /dev/hidraw24..27 node, so Steam's own drivers recognise it exactly
// as on a PC (DualSense / Edge, DualShock 4, Switch / Switch 2, Xbox, Flydigi, 8BitDo, Steam Controller,
// ...) and use all of its features. DroidDeck's app side (HookupBridge.kt) fills the shared file named by
// FAKE_HOOKUP_SHM; this side serves Steam's open/read/write/ioctl calls and announces hot-plug to udev.
//
// Game rumble: games rumble Steam's virtual pad; DroidDeck normally handles that itself. While a hooked-up
// controller is present (ff_to_steam), the effect is handed to Steam instead -- through the virtual pad's
// uinput force-feedback protocol -- so Steam rumbles the real controller with its own driver, like on a PC.
static constexpr uint32_t HOOKUP_MAGIC = 0x4b4f4f48;  // "HOOK"
static constexpr int HOOKUP_SLOTS = 4;
static constexpr int HOOKUP_IN_FIFO = 256;
static constexpr int HOOKUP_OUT_FIFO = 64;
static constexpr int HOOKUP_REPORT_MAX = 128;
static constexpr int HOOKUP_DESC_MAX = 4096;
static constexpr int HOOKUP_FF_NODES = UINPUT_EVENT_LIMIT - UINPUT_EVENT_BASE;
static constexpr unsigned HOOKUP_MINOR_BASE = 24;
static constexpr size_t HOOKUP_HEADER_SIZE = 4096;
static constexpr size_t HOOKUP_SLOT_SIZE = 53248;
static constexpr size_t HOOKUP_SIZE = HOOKUP_HEADER_SIZE + HOOKUP_SLOTS * HOOKUP_SLOT_SIZE;
static constexpr int HOOKUP_FEATURE_TIMEOUT_MS = 1000;
static constexpr int HOOKUP_OUTPUT_WAIT_MS = 50;   // feature writes (settings): wait this long for room
static constexpr int HOOKUP_REPORT_WAIT_MS = 4;    // output reports (rumble, lights): newer ones follow, so drop
static constexpr int HOOKUP_FUTEX_WAIT = 0;  // FUTEX_WAIT, shared: the writer is the SDL3 bridge, another process

struct HookupReport {
  uint64_t seq;
  uint32_t length;
  uint32_t kind;  // output fifo: 0 output report, 1 feature write; input fifo: SDL3Bridge's read time (us, 0 = none)
  uint8_t data[HOOKUP_REPORT_MAX];
};
struct HookupSlot {
  uint32_t present;
  uint32_t generation;
  uint16_t vendor, product, version, bus;
  int32_t iface;
  uint32_t desc_len;
  char name[128];
  char serial[64];
  char manufacturer[64];
  char product_name[64];
  uint64_t in_write;
  uint64_t out_write;
  uint64_t out_read;
  uint64_t get_request;
  uint64_t get_reply;
  uint32_t get_report_id;  // low byte: report id; bit 8: input report (HIDIOCGINPUT) instead of feature
  int32_t get_length;
  uint8_t get[HOOKUP_REPORT_MAX];
  uint64_t direct_ms;  // SDL3 bridge heartbeat (CLOCK_MONOTONIC ms) while it writes this slot itself (fast path)
  uint32_t rate_hz;    // the bridge's Poll rate: hand Steam at most this many reports a second (0 = every one)
  uint8_t reserved[1024 - 532];
  uint8_t desc[HOOKUP_DESC_MAX];
  HookupReport in[HOOKUP_IN_FIFO];
  HookupReport out[HOOKUP_OUT_FIFO];
};
struct HookupFF {
  uint64_t seq;
  int32_t strong;
  int32_t weak;
  uint32_t length;
  uint32_t play;
};
struct HookupHeader {
  uint32_t magic;
  uint32_t version;
  uint32_t ff_to_steam;
  uint32_t reserved0;
  uint64_t steam_ms;  // the Steam client's heartbeat (CLOCK_MONOTONIC ms): DroidDeck's desktop knows Steam runs
  uint32_t reserved[10];
  HookupFF ff[HOOKUP_FF_NODES];
};
static_assert(sizeof(HookupReport) == 144, "HookupReport layout");
static_assert(offsetof(HookupSlot, name) == 24, "hookup name offset");
static_assert(offsetof(HookupSlot, serial) == 152, "hookup serial offset");
static_assert(offsetof(HookupSlot, manufacturer) == 216, "hookup manufacturer offset");
static_assert(offsetof(HookupSlot, product_name) == 280, "hookup product offset");
static_assert(offsetof(HookupSlot, in_write) == 344, "hookup in_write offset");
static_assert(offsetof(HookupSlot, get_report_id) == 384, "hookup get_report_id offset");
static_assert(offsetof(HookupSlot, get) == 392, "hookup get offset");
static_assert(offsetof(HookupSlot, direct_ms) == 520, "hookup direct_ms offset");
static_assert(offsetof(HookupSlot, rate_hz) == 528, "hookup rate_hz offset");
static_assert(offsetof(HookupSlot, desc) == 1024, "hookup desc offset");
static_assert(offsetof(HookupSlot, in) == 5120, "hookup in offset");
static_assert(offsetof(HookupSlot, out) == 41984, "hookup out offset");
static_assert(sizeof(HookupSlot) <= HOOKUP_SLOT_SIZE, "hookup slot size");
static_assert(offsetof(HookupHeader, steam_ms) == 16, "hookup steam_ms offset");
static_assert(offsetof(HookupHeader, ff) == 64, "hookup ff offset");
static_assert(sizeof(HookupFF) == 24, "hookup ff entry");
static_assert(sizeof(HookupHeader) <= HOOKUP_HEADER_SIZE, "hookup header size");

__attribute__((visibility("hidden"))) static uint8_t *hookup_shm() {
  static uint8_t *mapped = nullptr;
  static std::once_flag once;
  std::call_once(once, [] {
    const char *path = getenv("FAKE_HOOKUP_SHM");
    if (!path || !*path) return;
    static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));
    int fd = my_open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
      Logger::log("hookup: %s not openable (%s)\n", path, strerror(errno));
      return;
    }
    struct stat st;
    void *mapping = MAP_FAILED;
    if (fstat(fd, &st) == 0 && static_cast<size_t>(st.st_size) >= HOOKUP_SIZE)
      mapping = mmap(nullptr, HOOKUP_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    syscall(SYS_close, fd);
    if (mapping == MAP_FAILED || static_cast<HookupHeader *>(mapping)->magic != HOOKUP_MAGIC) {
      Logger::log("hookup: %s %s\n", path, mapping == MAP_FAILED ? "could not be mapped" : "has no valid header");
      if (mapping != MAP_FAILED) munmap(mapping, HOOKUP_SIZE);
      return;
    }
    mapped = static_cast<uint8_t *>(mapping);
  });
  return mapped;
}

__attribute__((visibility("hidden"))) static HookupHeader *hookup_header() {
  return reinterpret_cast<HookupHeader *>(hookup_shm());
}

__attribute__((visibility("hidden"))) static HookupSlot *hookup_slot(int i) {
  uint8_t *base = hookup_shm();
  if (!base || i < 0 || i >= HOOKUP_SLOTS) return nullptr;
  return reinterpret_cast<HookupSlot *>(base + HOOKUP_HEADER_SIZE + static_cast<size_t>(i) * HOOKUP_SLOT_SIZE);
}

__attribute__((visibility("hidden"))) static bool hookup_present(int i) {
  HookupSlot *s = hookup_slot(i);
  return s && __atomic_load_n(&s->present, __ATOMIC_ACQUIRE) != 0;
}

/** /dev/hidraw24..27 -> slot 0..3 when that slot holds a controller, else -1. */
__attribute__((visibility("hidden"))) static int hookup_path_slot(const char *pathname) {
  if (!pathname || !fake_deck_enabled() || strncmp(pathname, "/dev/hidraw2", 12)) return -1;
  for (int i = 0; i < HOOKUP_SLOTS; i++) {
    char path[32];
    snprintf(path, sizeof(path), "/dev/hidraw%u", HOOKUP_MINOR_BASE + i);
    if (!strcmp(pathname, path)) return hookup_present(i) ? i : -1;
  }
  return -1;
}

/** Must match HookupBridge.kt's sysfs layout. */
__attribute__((visibility("hidden"))) static void hookup_devpath(int i, const HookupSlot &s, char *out, size_t size) {
  int iface = s.iface < 0 ? 0 : s.iface;
  snprintf(out, size, "/devices/droiddeck/usb%d/%d-1:1.%d/0003:%04X:%04X.%04X/hidraw/hidraw%u", 10 + i, 10 + i, iface,
           s.vendor, s.product, 0x10 + i, HOOKUP_MINOR_BASE + i);
}

// ---- latency meter (logged to pad.log every 10 s while it has samples) ----
// Where a button press of a hooked-up controller spends its time, in three steps:
//   1. waiting in Steam's queue: from the report being handed to Steam until Steam reads it,
//   2. Steam's own processing: from Steam reading the press until Steam writes the mapped button on its virtual pad,
//   3. the game: from Steam's virtual pad until the game reads it.
// Mouse / keyboard output from Steam does not go through the virtual pad, so it is in step 1 only.
struct HookupLatency {
  std::mutex lock;
  uint32_t n = 0, missed = 0;
  uint64_t sum_us = 0, max_us = 0, since_us = 0;
  void add(const char *what, uint64_t us, uint64_t now_us, bool hit = true) {
    std::lock_guard<std::mutex> guard(lock);
    if (!since_us) since_us = now_us;
    if (hit) {
      n++;
      sum_us += us;
      if (us > max_us) max_us = us;
    } else {
      missed++;
    }
    if (now_us - since_us < 10000000ULL) return;
    if (n)
      Logger::log("latency: %s: %u presses, avg %.1f ms, max %.1f ms%s\n", what, n, sum_us / 1000.0 / n, max_us / 1000.0,
                  missed ? " (plus presses that never reached the pad: mapped to mouse / keyboard or nothing)" : "");
    n = missed = 0;
    sum_us = max_us = 0;
    since_us = now_us;
  }
};

static std::atomic<uint64_t> g_hookup_press_us{0};   // Steam process: a press Steam has read, not yet on its pad

/**
 * Steam's controller work runs at a higher priority, like Android's own input threads (nice -8): the threads that
 * read controllers (hooked-up ones and DroidDeck's own pads -- every controller), the one that writes Steam's
 * virtual pad, and ours that hand reports over. On a phone the game and the emulation keep the CPU busy, and
 * Steam's controller loop otherwise waits its turn behind them -- that is when it reads late. Once per thread; if
 * Android doesn't allow it, nothing changes (logged once).
 */
__attribute__((visibility("hidden"))) static void hookup_boost_this_thread(const char *what) {
  static thread_local bool done;
  if (done) return;
  done = true;
  if (!process_is_steam_client()) return;
  pid_t tid = static_cast<pid_t>(syscall(SYS_gettid));
  errno = 0;
  int was = getpriority(PRIO_PROCESS, static_cast<id_t>(tid));
  if (errno == 0 && was <= -8) return;
  if (setpriority(PRIO_PROCESS, static_cast<id_t>(tid), -8) == 0) {
    Logger::log("thread %d (%s) now runs at higher priority (nice %d -> -8)\n", tid, what, was);
  } else {
    static std::atomic<bool> told{false};
    if (!told.exchange(true))
      Logger::log("could not raise the priority of thread %d (%s): %s\n", tid, what, strerror(errno));
  }
}
static std::atomic<int> g_hookup_samples{0};
static std::atomic<uint32_t> g_vpad_passed{0};   // frames of Steam's virtual pad handed to the game (pacing below)
static uint64_t virtual_pad_interval_us();

/** Steam process, as Steam writes its virtual pad: the end of step 2. */
__attribute__((visibility("hidden"))) static void hookup_meter_virtual_pad(const std::vector<struct input_event> &events) {
  hookup_boost_this_thread("writes Steam's virtual controller");
  {  // how often Steam updates its virtual pad: every update is passed on to the game, one by one
    static std::atomic<uint32_t> updates{0}, logged{0};
    static std::atomic<uint64_t> since{0};
    uint64_t t = monotonic_us(), was = since.load(std::memory_order_relaxed);
    if (!was) since.compare_exchange_strong(was, t);
    if (!events.empty()) updates.fetch_add(1, std::memory_order_relaxed);
    if (was && t - was >= 10000000ULL && since.compare_exchange_strong(was, t)) {
      uint32_t n = updates.exchange(0), passed = g_vpad_passed.exchange(0);
      double secs = (t - was) / 1e6;
      if (n && (logged.fetch_add(1) < 3 || n > 4000))
        Logger::log("latency: Steam updates its virtual pad %.0f times a second; the game gets %.0f%s\n", n / secs,
                    passed / secs, virtual_pad_interval_us() ? " (stick / trigger moves paced, buttons at once)" : "");
    }
  }
  uint64_t press = g_hookup_press_us.load(std::memory_order_acquire);
  if (!press) return;
  static HookupLatency stat;
  uint64_t now = monotonic_us();
  if (now - press > 250000ULL) {  // no gamepad button followed: mapped to mouse / keyboard, or nothing
    if (g_hookup_press_us.compare_exchange_strong(press, 0)) stat.add("button press -> Steam's virtual pad", 0, now, false);
    return;
  }
  for (const struct input_event &ev : events) {
    if (ev.type != EV_KEY || ev.value != 1) continue;
    if (!g_hookup_press_us.compare_exchange_strong(press, 0)) return;
    stat.add("Steam read a button press -> on Steam's virtual pad (Steam's own processing)", now - press, now);
    int sample = g_hookup_samples.fetch_add(1, std::memory_order_relaxed);
    if (sample < 12) Logger::log("latency: press %d: on Steam's virtual pad %.1f ms after Steam read it\n", sample + 1, (now - press) / 1000.0);
    return;
  }
}

// ---- Steam's virtual pad, paced for the game (every controller, every mode) ----
// Steam writes its virtual pad each time a controller report changes its state: with a controller that sends 1000
// reports a second (a DualSense over USB in Steam Native Hook-up) about that often. A Windows game gets each update
// passed along one by one inside Wine, and under x86 emulation that falls behind: the game lags -- unplayable at
// 1000 Hz, fine at 250 Hz, the rate DroidDeck's own pads send. So stick / trigger movement reaches the game at most
// 250 times a second, merged to the newest values; buttons are never held: a frame with a button change goes out
// at once (after the newest axis values). Steam itself still reads the controller at full rate, so gyro-to-mouse
// and mouse / keyboard bindings (not through the virtual pad) keep it. FAKE_VIRTUAL_PAD_HZ sets another rate
// (0 = every update, as before).
__attribute__((visibility("hidden"))) static uint64_t virtual_pad_interval_us() {
  static const uint64_t interval = [] {
    const char *v = getenv("FAKE_VIRTUAL_PAD_HZ");
    long hz = v && *v ? strtol(v, nullptr, 10) : 250;
    return hz > 0 && hz < 1000000 ? static_cast<uint64_t>(1000000 / hz) : 0;
  }();
  return interval;
}

/** Only the newest value of each axis, then one SYN_REPORT. */
__attribute__((visibility("hidden"))) static void virtual_pad_merge(std::vector<struct input_event> &events) {
  std::vector<struct input_event> merged;
  merged.reserve(events.size());
  struct input_event syn = {};
  bool has_syn = false;
  for (const struct input_event &ev : events) {
    if (ev.type == EV_SYN) {
      if (ev.code == SYN_REPORT) {
        syn = ev;
        has_syn = true;
      }
      continue;
    }
    bool replaced = false;
    for (struct input_event &m : merged)
      if (m.type == ev.type && m.code == ev.code) {
        m = ev;
        replaced = true;
        break;
      }
    if (!replaced) merged.push_back(ev);
  }
  if (has_syn) merged.push_back(syn);
  events.swap(merged);
}

// Never destroyed (allocated once): at exit the pacer thread is still waiting on them, and destroying a condition
// variable with a waiter blocks forever -- the process would hang on quit.
static std::mutex &g_vpad_lock = *new std::mutex();
static std::condition_variable &g_vpad_cv = *new std::condition_variable();
static uint64_t g_vpad_due = 0;
static pid_t g_vpad_thread_pid = 0;

__attribute__((visibility("hidden"))) static void *virtual_pad_pacer(void *);

/** Frames are held: hand them to the game at `due` (the pacer thread), unless Steam's next frame comes first. */
__attribute__((visibility("hidden"))) static void virtual_pad_wake(uint64_t due) {
  std::lock_guard<std::mutex> guard(g_vpad_lock);
  if (!g_vpad_due || due < g_vpad_due) g_vpad_due = due;
  if (g_vpad_thread_pid != getpid()) {  // once per process (not inherited by a fork)
    pthread_t thread;
    if (pthread_create(&thread, nullptr, virtual_pad_pacer, nullptr) == 0) {
      pthread_detach(thread);
      g_vpad_thread_pid = getpid();
    }
  }
  g_vpad_cv.notify_one();
}

__attribute__((visibility("hidden"))) static void *virtual_pad_pacer(void *) {
  hookup_boost_this_thread("passes Steam's virtual controller on to games");
  std::unique_lock<std::mutex> lock(g_vpad_lock);
  for (;;) {
    if (!g_vpad_due) {
      g_vpad_cv.wait(lock);
      continue;
    }
    uint64_t now = monotonic_us();
    if (now < g_vpad_due) {
      g_vpad_cv.wait_for(lock, std::chrono::microseconds(g_vpad_due - now));
      continue;
    }
    g_vpad_due = 0;
    lock.unlock();
    {
      std::lock_guard<std::recursive_mutex> guard(controller_mutex());
      uint64_t interval = virtual_pad_interval_us();
      for (auto &entry : uinput_map()) {
        UinputWriter &w = *entry.second;
        if (!w.held) continue;
        if (monotonic_us() - w.paced_us + 200 >= interval) {  // due (within 0.2 ms)
          w.flush_now = true;
          flush_uinput(w);
          w.flush_now = false;
        } else {
          virtual_pad_wake(w.paced_us + interval);
        }
      }
    }
    lock.lock();
  }
  return nullptr;
}

/** flush_uinput: true = hold these events for now (they stay in w.pending, merged); false = write them. */
__attribute__((visibility("hidden"))) static bool virtual_pad_hold(UinputWriter &w) {
  uint64_t interval = virtual_pad_interval_us();
  if (!interval || w.pending.empty()) {
    w.held = false;
    return false;
  }
  const struct input_event &last = w.pending.back();
  if (last.type != EV_SYN || last.code != SYN_REPORT) return false;  // a partial frame (64 events): as before
  bool only_axes = true;
  for (const struct input_event &ev : w.pending)
    if (ev.type != EV_ABS && ev.type != EV_SYN) {
      only_axes = false;
      break;
    }
  uint64_t now = monotonic_us();
  if (!only_axes || w.flush_now || now - w.paced_us >= interval) {
    if (only_axes) virtual_pad_merge(w.pending);  // held frames and this one: one frame, the newest values
    w.paced_us = now;
    w.held = false;
    g_vpad_passed.fetch_add(1, std::memory_order_relaxed);
    return false;
  }
  virtual_pad_merge(w.pending);
  w.held = true;
  virtual_pad_wake(w.paced_us + interval);
  return true;
}

/** Game process, as it reads Steam's virtual pad: step 3 (stamped when Steam wrote it). */
__attribute__((visibility("hidden"))) static void hookup_meter_game_read(const struct input_event *events, size_t count) {
  if (process_is_steam_client()) return;
  for (size_t i = 0; i < count; i++) {
    if (events[i].type != EV_KEY || events[i].value != 1) continue;
    struct timeval now = {};
    gettimeofday(&now, nullptr);
    int64_t us = (static_cast<int64_t>(now.tv_sec) - events[i].time.tv_sec) * 1000000LL + (now.tv_usec - events[i].time.tv_usec);
    if (us < 0 || us > 1000000LL) continue;
    static HookupLatency stat;
    stat.add("Steam's virtual pad -> game read", static_cast<uint64_t>(us), monotonic_us());
  }
}

/** Steam's output reports / feature writes to a hooked-up controller, logged every 10 s when Steam had to wait. */
struct HookupOutputStat {
  uint32_t reports = 0, features = 0, dropped = 0;
  uint64_t waited_us = 0, since_us = 0;
};

/**
 * Buttons and hat switches the controller's own HID report descriptor declares for input report `id` (0 = the
 * controller uses no report IDs), as a bit mask over the report bytes (byte 0 = the report ID when IDs are used).
 * Controllers with a standard descriptor get their buttons protected exactly; vendor-defined reports (Sony's extra
 * area, Switch, Steam Controller, Xbox) declare none, and rely on what is learned from the reports.
 */
__attribute__((visibility("hidden"))) static void hookup_descriptor_buttons(const HookupSlot &s, uint8_t id, uint8_t *mask,
                                                                            uint32_t length) {
  const uint8_t *d = s.desc;
  uint32_t n = std::min<uint32_t>(s.desc_len, HOOKUP_DESC_MAX);
  struct Globals { uint32_t page = 0, size = 0, count = 0, id = 0; } g, stack[4];
  int depth = 0;
  uint32_t usages[64];
  int nusages = 0;
  uint32_t umin = 0, umax = 0;
  bool range = false, ids = false;
  uint32_t bitpos[256] = {};
  for (uint32_t pos = 0; pos < n;) {
    uint8_t prefix = d[pos++];
    if (prefix == 0xfe) {  // long item
      if (pos + 1 >= n) break;
      pos += 2 + d[pos];
      continue;
    }
    uint32_t size = prefix & 3;
    if (size == 3) size = 4;
    if (pos + size > n) break;
    uint32_t v = 0;
    for (uint32_t k = 0; k < size; k++) v |= static_cast<uint32_t>(d[pos + k]) << (8 * k);
    pos += size;
    uint32_t type = (prefix >> 2) & 3, tag = prefix >> 4;
    if (type == 1) {  // global
      if (tag == 0) g.page = v;
      else if (tag == 7) g.size = v;
      else if (tag == 8) { g.id = v & 0xff; ids = true; }
      else if (tag == 9) g.count = v;
      else if (tag == 10 && depth < 4) stack[depth++] = g;
      else if (tag == 11 && depth > 0) g = stack[--depth];
    } else if (type == 2) {  // local
      if (tag == 0 && nusages < 64) usages[nusages++] = size == 4 ? v : (g.page << 16) | v;
      else if (tag == 1) { umin = size == 4 ? v : (g.page << 16) | v; range = true; }
      else if (tag == 2) umax = size == 4 ? v : (g.page << 16) | v;
    } else if (type == 0) {  // main
      if (tag == 8) {  // Input
        uint32_t start = bitpos[g.id];
        uint32_t bits = g.size * g.count;
        bitpos[g.id] += bits;
        if (!(v & 1) && g.id == id && g.size && g.size <= 32) {
          for (uint32_t j = 0; j < g.count; j++) {
            uint32_t usage = j < static_cast<uint32_t>(nusages) ? usages[j]
                           : range ? std::min(umin + j, umax ? umax : umin + j)
                           : nusages ? usages[nusages - 1] : (g.page << 16);
            uint32_t page = usage >> 16, u = usage & 0xffff;
            if (page != 0x09 && !(page == 0x01 && u == 0x39)) continue;
            for (uint32_t b = start + j * g.size; b < start + (j + 1) * g.size; b++) {
              uint32_t byte = (ids ? 1 : 0) + b / 8;
              if (byte < length) mask[byte] |= 1u << (b % 8);
            }
          }
        }
      }
      nusages = 0;
      range = false;
      umin = umax = 0;
    }
  }
  (void)ids;
}

/**
 * Which bits of a controller's reports are "moving", learned from the reports themselves for ANY controller (no
 * report layout needed): bytes that change in more than a quarter of the last 256 reports of that kind (sticks in
 * use, gyro, accelerometer, touch position, noise) and single bits that do; and counters / timestamps (a byte that
 * goes up in nearly every report, and the bytes above it that only change when it wraps around). A button is never
 * pressed that fast. Buttons the HID descriptor declares are never "moving". Until the first 256 reports are seen
 * (and for kinds that don't stream) nothing counts as moving.
 */
struct HookupReportKind {
  uint32_t length = 0;
  uint8_t id = 0;              // first byte (the report ID on controllers that use them)
  bool learned = false;
  uint32_t count = 0;
  uint8_t prev[HOOKUP_REPORT_MAX] = {};
  bool has_prev = false;
  uint16_t changes[HOOKUP_REPORT_MAX * 8] = {};
  uint16_t byte_changes[HOOKUP_REPORT_MAX] = {};
  uint16_t ups[HOOKUP_REPORT_MAX] = {};        // byte went up by 1..127 (mod 256)
  uint16_t carried[HOOKUP_REPORT_MAX] = {};    // byte changed while the byte below it wrapped around
  uint16_t uncarried[HOOKUP_REPORT_MAX] = {};  // byte changed while the byte below it did not wrap
  uint8_t moving[HOOKUP_REPORT_MAX] = {};      // 1 bits = moving (or part of a counter)
  bool analog[HOOKUP_REPORT_MAX] = {};         // the whole byte is moving, and it is not a counter
  uint8_t buttons[HOOKUP_REPORT_MAX] = {};     // declared buttons / hat switches (HID descriptor)
  uint8_t noisy[HOOKUP_REPORT_MAX] = {};       // bits that change in over a quarter of reports, declared or not
  uint8_t given[HOOKUP_REPORT_MAX] = {};       // the last report of this kind Steam got
  bool has_given = false;
  uint64_t window_us = 0;
  void learn(const uint8_t *d, uint64_t now_us) {
    if (!window_us) window_us = now_us;
    if (has_prev) {
      for (uint32_t i = 0; i < length; i++) {
        uint8_t x = d[i] ^ prev[i];
        if (!x) continue;
        byte_changes[i]++;
        for (uint32_t b = x; b; b &= b - 1) changes[i * 8 + __builtin_ctz(b)]++;
        if (static_cast<int8_t>(d[i] - prev[i]) > 0) ups[i]++;
        if (i > 0) {
          if (d[i - 1] < prev[i - 1]) carried[i]++; else uncarried[i]++;
        }
      }
    }
    memcpy(prev, d, length);
    has_prev = true;
    if (++count < 256) return;
    bool counter[HOOKUP_REPORT_MAX] = {};
    for (uint32_t i = 0; i < length; i++) {
      if (ups[i] >= 200) counter[i] = true;                                   // goes up in ~80 % of reports
      else if (i > 0 && counter[i - 1] && carried[i] >= 2 && !uncarried[i]) counter[i] = true;  // its upper part
      analog[i] = !counter[i] && byte_changes[i] > 64 && !buttons[i];
      uint8_t m = counter[i] || byte_changes[i] > 64 ? 0xff : 0;
      for (int b = 0; b < 8; b++)
        if (changes[i * 8 + b] > 64) m |= 1u << b;
      moving[i] = m & ~buttons[i];
      noisy[i] = m;
    }
    memset(changes, 0, sizeof(changes));
    memset(byte_changes, 0, sizeof(byte_changes));
    memset(ups, 0, sizeof(ups));
    memset(carried, 0, sizeof(carried));
    memset(uncarried, 0, sizeof(uncarried));
    count = 0;
    // Only a kind that streams (256 reports within 5 s) is ever replaced: rarer kinds -- replies to Steam's
    // commands, status reports -- always reach Steam one by one.
    learned = now_us - window_us < 5000000ULL;
    window_us = now_us;
  }
  /**
   * Would replacing `older` by `newer` lose a change Steam has not seen -- a button-like bit that changed (compared
   * with `base`, what Steam has or will get) and changed back? That is a short press-and-release (or the reverse)
   * that must reach Steam. Not counted: moving bits (their next value carries them), and the carry of an analog
   * value crossing a boundary: a bit that changed together with lower bits of its byte, or a byte that moved by
   * 1..3 right above an analog byte (the upper half of a 16-bit value). A declared button always counts.
   */
  bool loses_change(const uint8_t *older, const uint8_t *newer, const uint8_t *base) const {
    for (uint32_t i = 0; i < length; i++) {
      uint8_t changed = older[i] ^ base[i];
      uint8_t reverted = changed & ~(newer[i] ^ base[i]) & ~noisy[i];  // noisy: moving bits, and declared ones that act so
      for (uint32_t r = reverted; r; r &= r - 1) {
        int b = __builtin_ctz(r);
        if (buttons[i] & (1u << b)) return true;
        if (changed & ((1u << b) - 1)) continue;
        int delta = static_cast<int8_t>(older[i] - base[i]);
        if (i > 0 && analog[i - 1] && delta >= -3 && delta <= 3) continue;
        return true;
      }
    }
    return false;
  }
  /**
   * For the latency meter, any controller: a button press since `ref` -- a declared button (HID descriptor) that
   * turned on, or, for controllers that declare none, a learned non-moving bit that turned on (not the carry of an
   * analog value crossing a boundary, as in loses_change). Bits that change in over a quarter of reports are never
   * a press, even when the descriptor calls them buttons (some controllers, e.g. a DualSense Edge, declare bits
   * that change constantly): nobody presses a button that fast, and counting them buried the real presses.
   */
  bool press(const uint8_t *d, const uint8_t *ref) const {
    bool declared = false;
    for (uint32_t i = 0; i < length; i++) {
      if (!buttons[i]) continue;
      declared = true;
      if (d[i] & ~ref[i] & buttons[i] & ~noisy[i]) return true;
    }
    if (declared || !learned) return false;
    for (uint32_t i = 0; i < length; i++) {
      uint8_t changed = d[i] ^ ref[i];
      uint8_t rises = d[i] & changed & ~moving[i];
      if (!rises) continue;
      int delta = static_cast<int8_t>(d[i] - ref[i]);
      if (i > 0 && analog[i - 1] && delta >= -3 && delta <= 3) continue;
      for (uint32_t r = rises; r; r &= r - 1)
        if (!(changed & ((1u << __builtin_ctz(r)) - 1))) return true;
    }
    return false;
  }
};

/**
 * Controller reports -> Steam, for every controller: the hooked-up ones (/dev/hidraw24-27) and DroidDeck's own pads
 * (Steam Deck, DualSense / Edge, DualShock 4, Steam Controller 2015 / 2026). A mailbox read at the moment Steam
 * reads: the report thread puts reports here, Steam's read() of the node is answered from here (hookup_read_steam),
 * and the node's socket only carries a 1-byte "something to read" token, so poll() works as on a real node.
 *
 * How the reports are handed over follows how Steam reads, learned while it runs:
 *  - Steam takes everything waiting each time (like SDL's update loop): it gets every report, in order, the newest
 *    last -- the newest state is in that same read, and gyro / touch keep all their samples.
 *  - Steam takes one report and goes away (busy): it gets the newest one. Older ones are skipped, unless skipping
 *    one would lose a change: a button-like bit that changed and changed back before Steam read it, like a press
 *    shorter than Steam's read cycle (HookupReportKind::loses_change). Those reach Steam, in order.
 * At most 8 reports wait; beyond that the oldest skippable one goes. Reports of kinds that don't stream (replies to
 * Steam's commands, status) are never skipped.
 * (Before, reports went into a small socket queue: when Steam read slowly it filled up, the NEWEST reports were the
 * ones dropped and Steam read old ones -- a press could reach it one or more of its read cycles late.)
 */
struct HookupQueue {
  struct Entry {
    int kind;            // index in kinds, -1 = beyond the 4 kinds tracked (never skipped)
    uint32_t length;
    uint64_t arrived_us;
    uint8_t data[HOOKUP_REPORT_MAX];
  };
  static constexpr int MAX = 8;
  std::mutex lock;
  Entry entries[MAX + 1];
  int n = 0;
  HookupReportKind kinds[4];
  int nkinds = 0;
  bool token = false;          // the "something to read" byte is in Steam's socket
  bool closed = false;         // the report thread ended (controller gone)
  bool drains = false;         // learned: Steam takes everything waiting each time
  uint64_t last_read_us = 0;   // Steam's last read that got a report
  // Paced (Hook-up, the bridge's Poll rate): Steam is told "something to read" at most once per interval and then
  // gets the newest report (short presses still reach it in order) -- like DroidDeck's own pads, which make
  // reports at their own rate.
  uint32_t interval_us = 0;
  uint64_t last_token_us = 0;
  uint64_t peer_check_us = 0;
  uint32_t mode_logs = 0;
  char name[64] = {};
  // 10 s summary
  uint64_t meter_us = 0;
  uint32_t arrived = 0, handed = 0, skipped = 0, lost = 0;
  uint64_t handed_ever = 0;   // a node Steam keeps open only to send it settings is never read
  bool told_unread = false;
  uint64_t wait_sum_us = 0, wait_max_us = 0;
};

/** Reads this close together are one read cycle of Steam's. */
static constexpr uint64_t HOOKUP_CYCLE_GAP_US = 1000;

/** What Steam has, or will have, before entry i of its kind: the previous one waiting, or the last one it got. */
__attribute__((visibility("hidden"))) static const uint8_t *hookup_base(const HookupQueue &q, int i) {
  int kind = q.entries[i].kind;
  for (int j = i - 1; j >= 0; j--)
    if (q.entries[j].kind == kind) return q.entries[j].data;
  return q.kinds[kind].has_given ? q.kinds[kind].given : nullptr;
}

/** Entry i can be skipped: a newer report of its kind waits behind it, and skipping i loses no change. */
__attribute__((visibility("hidden"))) static bool hookup_skippable(const HookupQueue &q, int i) {
  const HookupQueue::Entry &e = q.entries[i];
  if (e.kind < 0 || !q.kinds[e.kind].learned) return false;
  for (int j = i + 1; j < q.n; j++) {
    if (q.entries[j].kind != e.kind) continue;
    const uint8_t *base = hookup_base(q, i);
    return !base || !q.kinds[e.kind].loses_change(e.data, q.entries[j].data, base);
  }
  return false;
}

__attribute__((visibility("hidden"))) static void hookup_remove(HookupQueue &q, int i) {
  for (int j = i; j < q.n - 1; j++) q.entries[j] = q.entries[j + 1];
  q.n--;
}

__attribute__((visibility("hidden"))) static void hookup_set_mode(HookupQueue &q, bool drains) {
  q.drains = drains;
  if (q.mode_logs++ < 4)
    Logger::log("%s: %s\n", q.name,
                drains ? "Steam takes every report waiting each time: it gets them all, in order, the newest last"
                       : "Steam reads one report at a time: it gets the newest (older ones are skipped unless that "
                         "would lose a press)");
}

/**
 * A new report for Steam, from a report thread. `slot` = the hooked-up controller (its HID report descriptor marks
 * the buttons), or null for DroidDeck's own pads. Returns -1 once Steam has closed the node (the thread ends).
 */
__attribute__((visibility("hidden"))) static int hookup_offer(HookupQueue &q, int peer, const HookupSlot *slot,
                                                              const uint8_t *data, uint32_t length) {
  if (length > HOOKUP_REPORT_MAX) length = HOOKUP_REPORT_MAX;
  if (!length) return 0;
  uint64_t now = monotonic_us();
  std::lock_guard<std::mutex> guard(q.lock);
  q.arrived++;
  int kind = -1;
  for (int i = 0; i < q.nkinds && kind < 0; i++)
    if (q.kinds[i].length == length && q.kinds[i].id == data[0]) kind = i;
  if (kind < 0 && q.nkinds < 4) {
    HookupReportKind &k = q.kinds[q.nkinds];
    k.length = length;
    k.id = data[0];
    if (slot) {
      hookup_descriptor_buttons(*slot, data[0], k.buttons, length);  // report IDs: byte 0 is the ID
      uint8_t no_ids[HOOKUP_REPORT_MAX] = {};
      hookup_descriptor_buttons(*slot, 0, no_ids, length);           // no IDs: the report starts at once
      for (uint32_t i = 0; i < length; i++) k.buttons[i] |= no_ids[i];
    }
    kind = q.nkinds++;
  }
  if (kind >= 0) q.kinds[kind].learn(data, now);
  HookupQueue::Entry &e = q.entries[q.n++];
  e.kind = kind;
  e.length = length;
  e.arrived_us = now;
  memcpy(e.data, data, length);
  if (q.n > HookupQueue::MAX) {  // Steam hasn't read for a while: make room, the oldest skippable report first
    int drop = -1;
    for (int i = 0; i < q.n - 1 && drop < 0; i++)
      if (hookup_skippable(q, i)) drop = i;
    if (drop >= 0) {
      q.skipped++;
    } else {
      drop = 0;
      if (q.entries[0].kind >= 0 && q.kinds[q.entries[0].kind].learned) q.lost++;
    }
    hookup_remove(q, drop);
  }
  if (!q.token && (!q.interval_us || now - q.last_token_us >= q.interval_us)) {  // tell Steam's poll() there is something
    uint8_t token = 1;
    if (send(peer, &token, 1, MSG_DONTWAIT | MSG_NOSIGNAL) == 1) {
      q.token = true;
      q.last_token_us = now;
    } else if (errno != EAGAIN && errno != EWOULDBLOCK) {
      return -1;  // Steam closed the node
    }
  } else if (q.token && now - q.peer_check_us > 100000) {  // the token is waiting: did Steam close the node meanwhile?
    q.peer_check_us = now;
    struct pollfd p = {peer, 0, 0};
    struct timespec zero = {0, 0};
    if (syscall(SYS_ppoll, &p, 1, &zero, nullptr, 0) > 0 && (p.revents & (POLLHUP | POLLERR))) return -1;
  }
  // 10 s summary, logged when Steam was slow to read (skipping reports, or reading them late)
  if (!q.meter_us) q.meter_us = now;
  if (now - q.meter_us >= 10000000ULL) {
    double secs = (now - q.meter_us) / 1e6;
    if (!q.handed_ever) {  // opened only to send it settings (Steam reads another copy): not slow reading
      if (!q.told_unread)
        Logger::log("%s: Steam keeps this copy of the node open without reading it (it only sends it settings)\n", q.name);
      q.told_unread = true;
    } else if ((q.skipped && !q.interval_us) || q.lost ||
               (q.handed && q.wait_sum_us / q.handed > 2000 + q.interval_us))
      Logger::log("%s: %.0f reports/s, Steam read %.0f/s (%s), avg %.1f ms after they arrived (max %.1f ms); %u older "
                  "ones skipped%s%s\n",
                  q.name, q.arrived / secs, q.handed / secs,
                  q.interval_us ? "paced: the newest" : q.drains ? "all waiting each time" : "the newest each time",
                  q.handed ? q.wait_sum_us / 1000.0 / q.handed : 0.0, q.wait_max_us / 1000.0, q.skipped,
                  q.interval_us ? "" : " while Steam was busy",
                  q.lost ? " -- Steam stopped reading for a while, some short presses were dropped" : "");
    q.arrived = q.handed = q.skipped = q.lost = 0;
    q.wait_sum_us = q.wait_max_us = 0;
    q.meter_us = now;
  }
  return 0;
}

/**
 * Paced mailbox: the "something to read" token held back by hookup_offer (interval not over yet) goes out once the
 * interval is over. Returns how many microseconds until then (0 = nothing waiting). -1 = Steam closed the node.
 */
__attribute__((visibility("hidden"))) static int64_t hookup_token_due(HookupQueue &q, int peer, uint64_t now) {
  std::lock_guard<std::mutex> guard(q.lock);
  if (q.token || !q.n || !q.interval_us) return 0;
  uint64_t since = now - q.last_token_us;
  if (since < q.interval_us) return static_cast<int64_t>(q.interval_us - since);
  uint8_t token = 1;
  if (send(peer, &token, 1, MSG_DONTWAIT | MSG_NOSIGNAL) == 1) {
    q.token = true;
    q.last_token_us = now;
  } else if (errno != EAGAIN && errno != EWOULDBLOCK) {
    return -1;
  }
  return 0;
}

/** Steam process, as Steam reads a report from the mailbox: step 1 of the latency meter, and the start of step 2. */
__attribute__((visibility("hidden"))) static void hookup_meter_handed(HookupQueue &q, const HookupQueue::Entry &e,
                                                                      uint64_t now) {
  uint64_t wait = now - e.arrived_us;
  q.handed++;
  q.handed_ever++;
  q.wait_sum_us += wait;
  if (wait > q.wait_max_us) q.wait_max_us = wait;
  HookupReportKind *k = e.kind >= 0 ? &q.kinds[e.kind] : nullptr;
  if (!k || !k->has_given || !k->press(e.data, k->given)) return;
  static HookupLatency stat;
  stat.add("button press waited for Steam to read it", wait, now);
  g_hookup_press_us.store(now, std::memory_order_release);
  static std::atomic<int> samples{0};
  int sample = samples.fetch_add(1, std::memory_order_relaxed);
  if (sample < 12) Logger::log("latency: press %d: Steam read it %.1f ms after it arrived\n", sample + 1, wait / 1000.0);
}

/** Steam's read() of a controller node with a mailbox: the next report (see HookupQueue). */
__attribute__((visibility("hidden"))) static ssize_t hookup_read_steam(int fd, HookupQueue &q, void *buf, size_t count) {
  hookup_boost_this_thread("reads a controller");
  for (;;) {
    {
      std::lock_guard<std::mutex> guard(q.lock);
      uint64_t now = monotonic_us();
      bool new_cycle = !q.last_read_us || now - q.last_read_us >= HOOKUP_CYCLE_GAP_US;
      if (q.n) {
        // back after a while, and a report it left waiting last time is still here: it reads one at a time
        if (new_cycle && q.drains && q.entries[0].arrived_us < q.last_read_us) hookup_set_mode(q, false);
        if (!q.drains || q.interval_us)  // the newest, minus what can't be skipped (always when paced)
          for (int i = 0; i < q.n - 1;) {
            if (hookup_skippable(q, i)) {
              hookup_remove(q, i);
              q.skipped++;
            } else {
              i++;
            }
          }
        HookupQueue::Entry e = q.entries[0];
        hookup_remove(q, 0);
        if (!q.n && q.token) {  // nothing left: take the token back so poll() reports "nothing to read"
          uint8_t token;
          if (recv(fd, &token, 1, MSG_DONTWAIT) < 0) {}
          q.token = false;
        }
        size_t copied = std::min<size_t>(count, e.length);
        memcpy(buf, e.data, copied);
        hookup_meter_handed(q, e, now);
        if (e.kind >= 0) {
          memcpy(q.kinds[e.kind].given, e.data, e.length);
          q.kinds[e.kind].has_given = true;
        }
        q.last_read_us = now;
        return static_cast<ssize_t>(copied);
      }
      if (!new_cycle && !q.drains) hookup_set_mode(q, true);  // came straight back for more: it takes all waiting
      if (q.closed) return 0;  // the controller is gone: end of file, as on a real node
      uint8_t peek;
      if (recv(fd, &peek, 1, MSG_PEEK | MSG_DONTWAIT) == 0) return 0;  // the report thread ended: end of file
    }
    int flags = fcntl(fd, F_GETFL);
    if (flags >= 0 && (flags & O_NONBLOCK)) {
      errno = EAGAIN;
      return -1;
    }
    struct pollfd p = {fd, POLLIN, 0};
    if (syscall(SYS_ppoll, &p, 1, nullptr, nullptr, 0) < 0 && errno != EINTR) return -1;  // until there is something
    if (p.revents & (POLLHUP | POLLERR)) {
      std::lock_guard<std::mutex> guard(q.lock);
      if (!q.n) return 0;
    }
  }
}

/** readv() of a node with a mailbox: one report, as read() gives it, spread over the buffers. -2 = not one. */
__attribute__((visibility("hidden"))) static ssize_t hookup_readv_mailbox(int fd, const struct iovec *iov, int iovcnt) {
  if (!watched(fd)) return -2;
  std::shared_ptr<HookupQueue> q;
  {
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    auto deck = deck_map().find(fd);
    if (deck == deck_map().end() || !deck->second->hookup_in) return -2;
    q = deck->second->hookup_in;
  }
  if (iovcnt < 0 || iovcnt > IOV_MAX || (iovcnt && !iov)) {
    errno = EINVAL;
    return -1;
  }
  size_t total = 0;
  for (int i = 0; i < iovcnt; i++) total += iov[i].iov_len;
  uint8_t report[HOOKUP_REPORT_MAX];
  ssize_t got = hookup_read_steam(fd, *q, report, std::min<size_t>(total, sizeof(report)));
  size_t done = 0;
  for (int i = 0; i < iovcnt && got > 0 && done < static_cast<size_t>(got); i++) {
    size_t part = std::min(iov[i].iov_len, static_cast<size_t>(got) - done);
    memcpy(iov[i].iov_base, report + done, part);
    done += part;
  }
  return got;
}

// ---- DroidDeck's own controller nodes (Steam Deck pad, DualSense / Edge, DualShock 4, Steam Controller 2015 and
// 2026): the same mailbox, so Steam reads the newest state of every controller, not only hooked-up ones ----
__attribute__((visibility("hidden"))) static void pad_mailbox_attach(DeckHidraw &pad) {
  pad.hookup_in = std::make_shared<HookupQueue>();
  const char *what = pad.triton ? (pad.emulated ? "Steam Controller (2026) pad" : "Steam Controller (2026)")
                   : pad.sc1 ? "Steam Controller (2015) pad"
                   : pad.sony == 4 ? "DualShock 4 pad"
                   : pad.sony == 6 ? "DualSense Edge pad"
                   : pad.sony ? "DualSense pad"
                   : "Steam Deck pad";
  snprintf(pad.hookup_in->name, sizeof(pad.hookup_in->name), "deck: %s", what);
}

/** A report of one of DroidDeck's own pads for Steam. -1 = Steam closed the node (the report thread ends). */
__attribute__((visibility("hidden"))) static int pad_mailbox_offer(DeckHidraw &pad, const uint8_t *data,
                                                                   uint32_t length) {
  hookup_boost_this_thread("hands DroidDeck's controller reports to Steam");
  if (!pad.hookup_in) {  // no mailbox: straight into the node's socket, as DroidDeck does
    if (send(pad.peer, data, length, MSG_DONTWAIT | MSG_NOSIGNAL) < 0 && errno != EAGAIN && errno != EWOULDBLOCK)
      return -1;
    return 0;
  }
  return hookup_offer(*pad.hookup_in, pad.peer, nullptr, data, length);
}

__attribute__((visibility("hidden"))) static void *hookup_report_thread(void *arg) {
  auto *holder = static_cast<std::shared_ptr<DeckHidraw> *>(arg);
  std::shared_ptr<DeckHidraw> self = *holder;
  delete holder;
  HookupSlot *s = hookup_slot(self->hookup);
  std::shared_ptr<HookupQueue> qp = self->hookup_in;
  HookupQueue &q = *qp;
  hookup_boost_this_thread("hands controller reports to Steam");
  uint64_t next = s ? __atomic_load_n(&s->in_write, __ATOMIC_ACQUIRE) : 0;
  bool peer_gone = false;
  // the hop before every other meter: SDL3Bridge reading the report from USB -> picked up here (Hook-up only)
  uint32_t hop_n = 0, hop_logs = 0;
  uint64_t hop_sum = 0, hop_max = 0, hop_since = monotonic_us();
  while (s && __atomic_load_n(&s->present, __ATOMIC_ACQUIRE) &&
         __atomic_load_n(&s->generation, __ATOMIC_ACQUIRE) == self->hookup_generation) {
    uint64_t written = __atomic_load_n(&s->in_write, __ATOMIC_ACQUIRE);
    if (written - next > HOOKUP_IN_FIFO) next = written - HOOKUP_IN_FIFO;  // fell behind: skip the oldest
    while (next < written && !peer_gone) {
      const HookupReport &r = s->in[next % HOOKUP_IN_FIFO];
      uint8_t data[HOOKUP_REPORT_MAX];
      uint32_t length = __atomic_load_n(&r.length, __ATOMIC_RELAXED);
      if (length > HOOKUP_REPORT_MAX) length = HOOKUP_REPORT_MAX;
      memcpy(data, r.data, length);
      uint32_t stamp = __atomic_load_n(&r.kind, __ATOMIC_RELAXED);
      __atomic_thread_fence(__ATOMIC_ACQUIRE);
      bool intact = __atomic_load_n(&r.seq, __ATOMIC_ACQUIRE) == next + 1;
      next++;
      if (!intact || !length) continue;
      if (stamp) {  // same clock in both processes (CLOCK_MONOTONIC); 32-bit microseconds, wraps every 71 min
        uint32_t hop = static_cast<uint32_t>(monotonic_us()) - stamp;
        if (hop < 1000000u) {
          hop_n++;
          hop_sum += hop;
          if (hop > hop_max) hop_max = hop;
        }
      }
      if (hookup_offer(q, self->peer, s, data, length) < 0) peer_gone = true;
    }
    if (peer_gone) break;  // Steam closed the node
    // the bridge's Poll rate paces what Steam is handed (fast path only; 0 / relayed by DroidDeck's app = every report)
    {
      uint64_t beat_ms = __atomic_load_n(&s->direct_ms, __ATOMIC_ACQUIRE);
      uint32_t hz = beat_ms && monotonic_us() / 1000 - beat_ms < 1500 ? __atomic_load_n(&s->rate_hz, __ATOMIC_ACQUIRE) : 0;
      uint32_t interval = hz >= 20 && hz < 1000 ? 1000000u / hz : 0;  // 1000 Hz and up: every report, as before
      std::lock_guard<std::mutex> guard(q.lock);
      if (interval != q.interval_us) {
        if (interval)
          Logger::log("hookup: /dev/hidraw%u: Steam gets the newest report %u times a second (the bridge's Poll rate)\n",
                      HOOKUP_MINOR_BASE + self->hookup, hz);
        else
          Logger::log("hookup: /dev/hidraw%u: every report goes to Steam (Poll rate 1000 Hz)\n",
                      HOOKUP_MINOR_BASE + self->hookup);
        q.interval_us = interval;
      }
    }
    int64_t due = hookup_token_due(q, self->peer, monotonic_us());
    if (due < 0) break;  // Steam closed the node
    uint64_t hop_now = monotonic_us();
    if (hop_now - hop_since >= 10000000ULL) {  // first one always, then when the hop is slow
      if (hop_n && (hop_logs++ == 0 || hop_sum / hop_n > 1000 || hop_max > 8000))
        Logger::log("latency: SDL3Bridge read a report -> it reached Steam's side (/dev/hidraw%u): %u reports, avg %.2f "
                    "ms, max %.1f ms\n", HOOKUP_MINOR_BASE + self->hookup, hop_n, hop_sum / 1000.0 / hop_n,
                    hop_max / 1000.0);
      hop_n = 0;
      hop_sum = hop_max = 0;
      hop_since = hop_now;
    }
    // Sleep until the next report. The SDL3 bridge's fast path wakes this futex the moment it writes one (the
    // low half of in_write changes); reports relayed by DroidDeck's app are picked up by the timeout.
    uint64_t beat = __atomic_load_n(&s->direct_ms, __ATOMIC_ACQUIRE);
    bool fast = beat && monotonic_us() / 1000 - beat < 1500;
    long wait_us = fast ? 4000L : 500L;
    if (due > 0 && due < wait_us) wait_us = static_cast<long>(due);  // a held-back token is due sooner
    struct timespec wait = {0, wait_us * 1000L};
    syscall(SYS_futex, reinterpret_cast<uint32_t *>(&s->in_write), HOOKUP_FUTEX_WAIT, static_cast<uint32_t>(written),
            &wait, nullptr, 0);
  }
  {
    std::lock_guard<std::mutex> guard(q.lock);
    q.closed = true;
  }
  syscall(SYS_close, self->peer);
  return nullptr;
}

__attribute__((visibility("hidden"))) static int open_hookup_hidraw(int flags, int slot) {
  HookupSlot *s = hookup_slot(slot);
  if (!s || !process_is_steam_client()) {
    errno = ENOENT;
    return -1;
  }
  int pair[2];
  if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair) < 0) return -1;
  // The socket only carries the mailbox's 1-byte "something to read" token (see HookupQueue): small is plenty.
  int buffer = HOOKUP_REPORT_MAX * 4;
  setsockopt(pair[1], SOL_SOCKET, SO_SNDBUF, &buffer, sizeof(buffer));
  if (!(flags & O_CLOEXEC)) fcntl(pair[0], F_SETFD, 0);
  if (flags & O_NONBLOCK) fcntl(pair[0], F_SETFL, fcntl(pair[0], F_GETFL) | O_NONBLOCK);
  auto pad = std::make_shared<DeckHidraw>();
  pad->peer = pair[1];
  pad->hookup = static_cast<int8_t>(slot);
  pad->hookup_generation = __atomic_load_n(&s->generation, __ATOMIC_ACQUIRE);
  pad->hookup_in = std::make_shared<HookupQueue>();
  snprintf(pad->hookup_in->name, sizeof(pad->hookup_in->name), "hookup: /dev/hidraw%u", HOOKUP_MINOR_BASE + slot);
  {
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    deck_map()[pair[0]] = pad;
    watch_fd(pair[0]);
  }
  pthread_t thread;
  auto *arg = new std::shared_ptr<DeckHidraw>(pad);
  if (pthread_create(&thread, nullptr, hookup_report_thread, arg) != 0) {
    delete arg;
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    deck_map().erase(pair[0]);
    unwatch_fd(pair[0]);
    syscall(SYS_close, pair[0]);
    syscall(SYS_close, pair[1]);
    errno = ENOMEM;
    return -1;
  }
  pthread_detach(thread);
  Logger::log("hookup: /dev/hidraw%u opened as fd %d (%04x:%04x \"%s\")\n", HOOKUP_MINOR_BASE + slot, pair[0], s->vendor,
              s->product, s->name);
  return pair[0];
}

/**
 * Steam -> controller: an output report (kind 0) or a feature-report write (kind 1). Called without the hook's
 * controller lock (see the write() patch), so a wait here never holds up Steam's other controller calls -- its
 * virtual pad above all. Output reports (rumble, lights) wait at most a few ms for room: when the controller can't
 * take them that fast the newest is dropped, as the next one carries the current state again.
 */
__attribute__((visibility("hidden"))) static void hookup_queue(const DeckHidraw &pad, const uint8_t *data, size_t size,
                                                               uint32_t kind) {
  HookupSlot *s = hookup_slot(pad.hookup);
  if (!s || !data || !size || !__atomic_load_n(&s->present, __ATOMIC_ACQUIRE)) return;
  if (size > HOOKUP_REPORT_MAX) size = HOOKUP_REPORT_MAX;
  static std::mutex one_at_a_time;
  std::lock_guard<std::mutex> guard(one_at_a_time);
  static HookupOutputStat stats[HOOKUP_SLOTS];
  HookupOutputStat &st = stats[pad.hookup];
  uint64_t t0 = monotonic_us();
  uint64_t idx = __atomic_load_n(&s->out_write, __ATOMIC_RELAXED);
  int limit = (kind ? HOOKUP_OUTPUT_WAIT_MS : HOOKUP_REPORT_WAIT_MS) * 4;
  int waited = 0;
  for (; idx - __atomic_load_n(&s->out_read, __ATOMIC_ACQUIRE) >= HOOKUP_OUT_FIFO && waited < limit; waited++)
    sleep_us(250);
  uint64_t t1 = monotonic_us();
  if (waited) st.waited_us += t1 - t0;
  if (!st.since_us) st.since_us = t1;
  if (kind) st.features++; else st.reports++;
  bool full = idx - __atomic_load_n(&s->out_read, __ATOMIC_ACQUIRE) >= HOOKUP_OUT_FIFO;
  if (full && !kind) st.dropped++;
  if (t1 - st.since_us >= 10000000ULL) {
    if (st.waited_us || st.dropped)
      Logger::log("hookup: /dev/hidraw%u: Steam sent %u output reports + %u feature writes in 10 s; the controller "
                  "fell behind: waited %.1f ms for room, dropped %u output reports\n",
                  HOOKUP_MINOR_BASE + pad.hookup, st.reports, st.features, st.waited_us / 1000.0, st.dropped);
    st = HookupOutputStat{};
    st.since_us = t1;
  }
  if (full && !kind) return;
  HookupReport &r = s->out[idx % HOOKUP_OUT_FIFO];
  __atomic_store_n(&r.seq, 0, __ATOMIC_RELAXED);
  __atomic_thread_fence(__ATOMIC_RELEASE);
  r.length = static_cast<uint32_t>(size);
  r.kind = kind;
  memcpy(r.data, data, size);
  __atomic_thread_fence(__ATOMIC_RELEASE);
  __atomic_store_n(&r.seq, idx + 1, __ATOMIC_RELEASE);
  __atomic_store_n(&s->out_write, idx + 1, __ATOMIC_RELEASE);
}

/** Feature (or input) report read: asks the controller through the app and waits for the answer. */
__attribute__((visibility("hidden"))) static int hookup_get(const DeckHidraw &pad, uint8_t *buf, size_t size, bool input) {
  HookupSlot *s = hookup_slot(pad.hookup);
  if (!s || !buf || size < 1) {
    errno = EINVAL;
    return -1;
  }
  static std::mutex one_at_a_time[HOOKUP_SLOTS];
  std::lock_guard<std::mutex> guard(one_at_a_time[pad.hookup]);
  uint8_t report_id = buf[0];
  __atomic_store_n(&s->get_report_id, report_id | (input ? 0x100u : 0u), __ATOMIC_RELAXED);
  __atomic_store_n(&s->get_length, static_cast<int32_t>(std::min<size_t>(size, HOOKUP_REPORT_MAX)), __ATOMIC_RELAXED);
  uint64_t request = __atomic_add_fetch(&s->get_request, 1, __ATOMIC_RELEASE);
  uint64_t t0 = monotonic_us();
  for (int waited = 0; waited < HOOKUP_FEATURE_TIMEOUT_MS * 4; waited++) {
    if (!__atomic_load_n(&s->present, __ATOMIC_ACQUIRE)) break;
    if (__atomic_load_n(&s->get_reply, __ATOMIC_ACQUIRE) == request) {
      int32_t length = __atomic_load_n(&s->get_length, __ATOMIC_RELAXED);
      if (length <= 0 || length > HOOKUP_REPORT_MAX) break;
      size_t copied = std::min(size, static_cast<size_t>(length));
      memcpy(buf, s->get, copied);
      uint64_t took = monotonic_us() - t0;
      static std::atomic<unsigned> slow;
      if (took > 20000) {  // Steam waits for these: a slow one holds up that Steam thread
        unsigned n = ++slow;
        if ((n & (n - 1)) == 0)
          Logger::log("hookup: report 0x%02x read took %.1f ms (slow reads so far: %u)\n", report_id, took / 1000.0, n);
      }
      return static_cast<int>(copied);
    }
    sleep_us(250);
  }
  static std::atomic<unsigned> failures;
  unsigned n = ++failures;
  if ((n & (n - 1)) == 0) Logger::log("hookup: report 0x%02x read got no answer (x%u)\n", report_id, n);
  errno = EIO;
  return -1;
}

__attribute__((visibility("hidden"))) static int ioctl_hookup(DeckHidraw &pad, ioctl_request_t op, void *argp) {
  HookupSlot *s = hookup_slot(pad.hookup);
  if (_IOC_TYPE(op) != 'H' || !s) {
    errno = ENOTTY;
    return -1;
  }
  switch (_IOC_NR(op)) {
  case 0x01:
    *static_cast<int *>(argp) = static_cast<int>(std::min<uint32_t>(s->desc_len, HOOKUP_DESC_MAX));
    return 0;
  case 0x02: {
    auto *out = static_cast<uint8_t *>(argp);
    uint32_t size;
    memcpy(&size, out, sizeof(size));
    memcpy(out + 4, s->desc, std::min<size_t>(size, std::min<uint32_t>(s->desc_len, HOOKUP_DESC_MAX)));
    return 0;
  }
  case 0x03: {
    struct {
      uint32_t bustype;
      int16_t vendor;
      int16_t product;
    } info = {s->bus ? s->bus : static_cast<uint32_t>(BUS_USB), static_cast<int16_t>(s->vendor), static_cast<int16_t>(s->product)};
    memcpy(argp, &info, sizeof(info));
    return 0;
  }
  case 0x04: return copy_ioctl_string(op, argp, s->name);
  case 0x05: {
    char phys[48];
    snprintf(phys, sizeof(phys), "usb-droiddeck-%d/input%d", 10 + pad.hookup, s->iface < 0 ? 0 : s->iface);
    return copy_ioctl_string(op, argp, phys);
  }
  case 0x08: return copy_ioctl_string(op, argp, s->serial);
  case 0x06:  // HIDIOCSFEATURE
    hookup_queue(pad, static_cast<const uint8_t *>(argp), _IOC_SIZE(op), 1);
    return static_cast<int>(_IOC_SIZE(op));
  case 0x07:  // HIDIOCGFEATURE
    return hookup_get(pad, static_cast<uint8_t *>(argp), _IOC_SIZE(op), false);
  case 0x0A:  // HIDIOCGINPUT
    return hookup_get(pad, static_cast<uint8_t *>(argp), _IOC_SIZE(op), true);
  case 0x0B:  // HIDIOCSOUTPUT
    hookup_queue(pad, static_cast<const uint8_t *>(argp), _IOC_SIZE(op), 0);
    return static_cast<int>(_IOC_SIZE(op));
  default:
    errno = EINVAL;
    return -1;
  }
}

// ---- game rumble -> Steam (uinput force feedback) ----
__attribute__((visibility("hidden"))) static void hookup_ff_from_game(uint16_t slot, int strong, int weak, uint16_t length) {
  HookupHeader *h = hookup_header();
  if (!h || slot < UINPUT_EVENT_BASE || slot >= UINPUT_EVENT_LIMIT) return;
  if (!__atomic_load_n(&h->ff_to_steam, __ATOMIC_ACQUIRE)) return;
  HookupFF &f = h->ff[slot - UINPUT_EVENT_BASE];
  f.strong = strong;
  f.weak = weak;
  f.length = length;
  f.play = (strong || weak) && length ? 1 : 0;
  __atomic_add_fetch(&f.seq, 1, __ATOMIC_RELEASE);
}

/** Runs in the Steam client: turns game rumble into uinput force-feedback requests for Steam's virtual pad. */
__attribute__((visibility("hidden"))) static void hookup_ff_to_steam() {
  HookupHeader *h = hookup_header();
  if (!h) return;
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  for (auto &entry : uinput_map()) {
    UinputWriter &w = *entry.second;
    if (!w.published || w.owner != getpid() || w.node < UINPUT_EVENT_BASE || w.node >= UINPUT_EVENT_LIMIT) continue;
    if (!test_bit(w.description.ffbits, sizeof(w.description.ffbits), FF_RUMBLE)) continue;
    HookupFF &f = h->ff[w.node - UINPUT_EVENT_BASE];
    uint64_t seq = __atomic_load_n(&f.seq, __ATOMIC_ACQUIRE);
    if (seq == w.ff_seen) continue;
    w.ff_seen = seq;
    struct timeval now = {};
    gettimeofday(&now, nullptr);
    auto event = [&](uint16_t type, uint16_t code, int32_t value) {
      struct input_event ev = {};
      ev.time = now;
      ev.type = type;
      ev.code = code;
      ev.value = value;
      w.ff_queue.push_back(ev);
    };
    if (f.play) {
      w.ff_request++;
      memset(&w.ff_effect, 0, sizeof(w.ff_effect));
      w.ff_effect.type = FF_RUMBLE;
      w.ff_effect.id = 0;
      w.ff_effect.replay.length = f.length == 0xffff ? 0 : static_cast<uint16_t>(f.length);
      w.ff_effect.u.rumble.strong_magnitude = static_cast<uint16_t>(std::max(0, std::min(65535, f.strong)));
      w.ff_effect.u.rumble.weak_magnitude = static_cast<uint16_t>(std::max(0, std::min(65535, f.weak)));
      event(EV_UINPUT, UI_FF_UPLOAD, static_cast<int32_t>(w.ff_request));
      event(EV_FF, 0, 1);
    } else {
      event(EV_FF, 0, 0);
    }
    uint64_t one = 1;
    if (syscall(SYS_write, entry.first, &one, sizeof(one)) < 0) {}
  }
}

/** Serves Steam's read() of its uinput fd: the queued force-feedback requests. -2 = not handled. */
__attribute__((visibility("hidden"))) static ssize_t hookup_read_uinput(int fd, void *buf, size_t count) {
  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  auto made = uinput_map().find(fd);
  if (made == uinput_map().end()) {
    // Steam reading a controller node with a mailbox (HookupQueue): answered from it, unlocked
    auto deck = deck_map().find(fd);
    if (deck == deck_map().end()) return -2;
    if (!deck->second->hookup_in) {  // a node without one (touch screen, or the pad mailbox not patched in)
      if (!deck->second->touch) hookup_boost_this_thread("reads DroidDeck's controller");
      return -2;
    }
    std::shared_ptr<HookupQueue> q = deck->second->hookup_in;
    guard.unlock();
    return hookup_read_steam(fd, *q, buf, count);
  }
  std::shared_ptr<UinputWriter> w = made->second;
  if (count < sizeof(struct input_event)) {
    errno = EINVAL;
    return -1;
  }
  for (;;) {
    if (!w->ff_queue.empty()) {
      size_t n = std::min(count / sizeof(struct input_event), w->ff_queue.size());
      for (size_t i = 0; i < n; i++) {
        memcpy(static_cast<uint8_t *>(buf) + i * sizeof(struct input_event), &w->ff_queue.front(), sizeof(struct input_event));
        w->ff_queue.pop_front();
      }
      // The eventfd counter mirrors "events pending": drained when the queue empties, kept >0 otherwise,
      // so Steam's poll() on the fd stays accurate.
      int flags = fcntl(fd, F_GETFL);
      if (w->ff_queue.empty()) {
        uint64_t counter;
        if (flags >= 0 && !(flags & O_NONBLOCK)) fcntl(fd, F_SETFL, flags | O_NONBLOCK);
        if (syscall(SYS_read, fd, &counter, sizeof(counter)) < 0) {}
        if (flags >= 0 && !(flags & O_NONBLOCK)) fcntl(fd, F_SETFL, flags);
      } else {
        uint64_t one = 1;
        if (syscall(SYS_write, fd, &one, sizeof(one)) < 0) {}
      }
      return static_cast<ssize_t>(n * sizeof(struct input_event));
    }
    int flags = fcntl(fd, F_GETFL);
    if (flags >= 0 && (flags & O_NONBLOCK)) {
      errno = EAGAIN;
      return -1;
    }
    guard.unlock();
    uint64_t counter;
    if (syscall(SYS_read, fd, &counter, sizeof(counter)) < 0 && errno != EINTR) return -1;  // blocks until queued
    guard.lock();
  }
}

// ---- hot-plug: udev add/remove for the hooked-up nodes, plus the force-feedback pump ----
__attribute__((visibility("hidden"))) static void *hookup_watch_thread(void *) {
  uint32_t seen_gen[HOOKUP_SLOTS] = {};
  bool seen[HOOKUP_SLOTS] = {};
  char devpath[HOOKUP_SLOTS][160] = {};
  for (unsigned tick = 0;; tick++) {
    // Steam runs: on DroidDeck's desktop (where Steam is one program among others) the SDL3 bridge hands
    // controllers to Steam only while this heartbeat is fresh, and they are ordinary gamepads otherwise
    if (tick % 50 == 0)
      if (HookupHeader *h = hookup_header()) __atomic_store_n(&h->steam_ms, monotonic_us() / 1000, __ATOMIC_RELEASE);
    sleep_us(2000);
    hookup_ff_to_steam();
    if (tick % 10) continue;
    for (int i = 0; i < HOOKUP_SLOTS; i++) {
      HookupSlot *s = hookup_slot(i);
      if (!s) continue;
      bool now = __atomic_load_n(&s->present, __ATOMIC_ACQUIRE) != 0;
      uint32_t gen = __atomic_load_n(&s->generation, __ATOMIC_ACQUIRE);
      if (now == seen[i] && (!now || gen == seen_gen[i])) continue;
      char path[32];
      snprintf(path, sizeof(path), "/dev/hidraw%u", HOOKUP_MINOR_BASE + i);
      if (seen[i]) {
        int sent = inject_uevent("remove", devpath[i], "hidraw", path, DECK_HIDRAW_MAJOR, HOOKUP_MINOR_BASE + i);
        Logger::log("hookup: %s removed (udev event to %d monitors)\n", path, sent);
      }
      seen[i] = now;
      seen_gen[i] = gen;
      if (now) {
        hookup_devpath(i, *s, devpath[i], sizeof(devpath[i]));
        if (seen_gen[i] != 0) {
          struct timespec settle = {0, 200 * 1000 * 1000};
          nanosleep(&settle, nullptr);
        }
        int sent = inject_uevent("add", devpath[i], "hidraw", path, DECK_HIDRAW_MAJOR, HOOKUP_MINOR_BASE + i);
        Logger::log("hookup: %s is %04x:%04x \"%s\" (udev event to %d monitors)\n", path, s->vendor, s->product, s->name, sent);
      }
      poke_hidraw_watchers(HOOKUP_MINOR_BASE + i);
    }
  }
  return nullptr;
}

__attribute__((constructor)) static void start_hookup_watch() {
  config();
  if (!process_is_steam_client() || !fake_deck_enabled()) return;
  const char *path = getenv("FAKE_HOOKUP_SHM");
  if (!path || !*path) {
    Logger::log("hookup: off (no FAKE_HOOKUP_SHM: the SDL3 bridge app is not installed, or an older DroidDeck build)\n");
    return;
  }
  if (!hookup_shm()) return;
  Logger::log("hookup: watching %s for controllers handed over by the SDL3 bridge\n", path);
  pthread_t thread;
  if (pthread_create(&thread, nullptr, hookup_watch_thread, nullptr) == 0) pthread_detach(thread);
}
// ---- Steam Input's mouse / keyboard output, traced (pad.log "mouse: ...") ----
// A Steam binding to a key, mouse movement or a mouse button reaches a game in three steps: Steam sends an XTest
// event to the game's X server (Xwayland; Steam does send clicks there), Xwayland hands it to gamescope over libei,
// and gamescope (libeis) delivers it to the game's window. Steps 2 and 3 are logged here, in the Xwayland and
// gamescope processes (this library is loaded in all of them), plus the input device each side sets up, and
// Steam's own XTest calls (step 1) -- so a
// click that goes missing shows where. Buttons are logged one by one at first (then every 100th), keys a few times
// to compare. Every call is passed on unchanged. The real function is looked up in the library itself if it is not
// next in line (a program that loaded it privately), so a call is never lost here.
namespace input_trace {
static std::atomic<uint32_t> buttons{0}, keys{0}, setup{0};

static bool first(std::atomic<uint32_t> &n, uint32_t count) {
  uint32_t i = n.fetch_add(1, std::memory_order_relaxed);
  return i < count || i % 100 == 0;
}

/** Which X server this is (Xwayland ":0" / ":1" from its command line), or "" in other processes. */
static const char *display() {
  static char name[16] = {0};
  static std::once_flag once;
  std::call_once(once, [] {
    char args[4096];
    int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, "/proc/self/cmdline", O_RDONLY | O_CLOEXEC));
    if (fd < 0) return;
    ssize_t n = syscall(SYS_read, fd, args, sizeof(args) - 1);
    syscall(SYS_close, fd);
    if (n <= 0) return;
    args[n] = 0;
    for (ssize_t i = 0; i < n; i += static_cast<ssize_t>(strlen(args + i)) + 1)
      if (args[i] == ':' && args[i + 1] >= '0' && args[i + 1] <= '9') {
        snprintf(name, sizeof(name), " %s", args + i);
        break;
      }
  });
  return name;
}

static void log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void log(const char *fmt, ...) {
  config();
  char text[256];
  va_list args;
  va_start(args, fmt);
  vsnprintf(text, sizeof(text), fmt, args);
  va_end(args);
  Logger::log("mouse:%s %s\n", display(), text);
}

/** Each libei / libeis event type once per process (the connection's life: added, resumed, paused, ...). */
static void event_type_once(const char *side, uint32_t type) {
  static std::atomic<uint64_t> seen[4];
  uint32_t slot = type % 256;
  uint64_t bit = 1ULL << (slot % 64);
  if (seen[slot / 64].fetch_or(bit) & bit) return;
  log("%s event type %u", side, type);
}

static const char *x_display(void *display) {
  using Fn = char *(*)(void *);
  static Fn get = [] {
    void *fn = dlsym(RTLD_DEFAULT, "XDisplayString");
    if (!fn)
      if (void *x11 = dlopen("libX11.so.6", RTLD_LAZY | RTLD_NOLOAD)) fn = dlsym(x11, "XDisplayString");
    return reinterpret_cast<Fn>(fn);
  }();
  const char *name = display && get ? get(display) : nullptr;
  return name ? name : "?";
}

template <typename Fn> static Fn real(const char *name, const char *library) {
  void *fn = dlsym(RTLD_NEXT, name);
  if (!fn) {
    void *handle = dlopen(library, RTLD_LAZY | RTLD_NOLOAD);
    if (!handle) handle = dlopen(library, RTLD_LAZY);
    if (handle) fn = dlsym(handle, name);  // the library's own definition, not this one
  }
  if (!fn) log("could not find the real %s in %s: this call is dropped", name, library);
  return reinterpret_cast<Fn>(fn);
}

static const char *caps(uint32_t c) {
  static thread_local char text[64];
  // libei / libeis capability bits: pointer 1, pointer absolute 2, keyboard 4, touch 8, scroll 16, button 32
  snprintf(text, sizeof(text), "%s%s%s%s%s%s", c & 1 ? " pointer" : "", c & 2 ? " absolute" : "",
           c & 4 ? " keyboard" : "", c & 8 ? " touch" : "", c & 16 ? " scroll" : "", c & 32 ? " button" : "");
  return text;
}
}  // namespace input_trace

// step 1 (Steam): XTest sent to the game's X server. Steam's client library links libXtst privately, so the real
// function is not "next" from here: it is looked up in libXtst itself (input_trace::real).
EXPORT int XTestFakeButtonEvent(void *display, unsigned int button, int is_press, unsigned long delay) {
  static auto real = input_trace::real<int (*)(void *, unsigned int, int, unsigned long)>("XTestFakeButtonEvent", "libXtst.so.6");
  int result = real ? real(display, button, is_press, delay) : 0;
  if (input_trace::first(input_trace::buttons, 40))
    input_trace::log("Steam sent XTest mouse button %u %s to X display %s (%s)", button, is_press ? "press" : "release",
                     input_trace::x_display(display), result ? "sent" : "NOT sent: no XTest on that display");
  return result;
}

EXPORT int XTestFakeKeyEvent(void *display, unsigned int keycode, int is_press, unsigned long delay) {
  static auto real = input_trace::real<int (*)(void *, unsigned int, int, unsigned long)>("XTestFakeKeyEvent", "libXtst.so.6");
  int result = real ? real(display, keycode, is_press, delay) : 0;
  if (input_trace::first(input_trace::keys, 40))
    input_trace::log("Steam sent XTest key %u %s to X display %s (%s)", keycode, is_press ? "press" : "release",
                     input_trace::x_display(display), result ? "sent" : "NOT sent: no XTest on that display");
  return result;
}

EXPORT int XTestFakeRelativeMotionEvent(void *display, int dx, int dy, unsigned long delay) {
  static auto real = input_trace::real<int (*)(void *, int, int, unsigned long)>("XTestFakeRelativeMotionEvent", "libXtst.so.6");
  return real ? real(display, dx, dy, delay) : 0;
}

EXPORT int XTestFakeMotionEvent(void *display, int screen, int x, int y, unsigned long delay) {
  static auto real = input_trace::real<int (*)(void *, int, int, int, unsigned long)>("XTestFakeMotionEvent", "libXtst.so.6");
  static std::atomic<uint32_t> n{0};
  if (n.fetch_add(1) < 3) input_trace::log("Steam moved the X pointer to %d,%d on X display %s", x, y, input_trace::x_display(display));
  return real ? real(display, screen, x, y, delay) : 0;
}

// step 2 (Xwayland): the X server hands XTest input to gamescope over libei
EXPORT void ei_device_button_button(void *device, uint32_t button, bool is_press) {
  static auto real = input_trace::real<void (*)(void *, uint32_t, bool)>("ei_device_button_button", "libei.so.1");
  if (input_trace::first(input_trace::buttons, 24))
    input_trace::log("X server handed mouse button 0x%x %s to gamescope (libei)", button, is_press ? "press" : "release");
  if (real) real(device, button, is_press);
}

EXPORT void ei_device_keyboard_key(void *device, uint32_t keycode, bool is_press) {
  static auto real = input_trace::real<void (*)(void *, uint32_t, bool)>("ei_device_keyboard_key", "libei.so.1");
  if (input_trace::first(input_trace::keys, 40))
    input_trace::log("X server handed key %u %s to gamescope (libei)", keycode, is_press ? "press" : "release");
  if (real) real(device, keycode, is_press);
}

EXPORT bool ei_device_has_capability(void *device, uint32_t capability) {
  static auto real = input_trace::real<bool (*)(void *, uint32_t)>("ei_device_has_capability", "libei.so.1");
  bool has = real && real(device, capability);
  if (input_trace::first(input_trace::setup, 24))
    input_trace::log("X server: gamescope's input device %p %s%s", device, has ? "can send" : "can NOT send",
                     input_trace::caps(capability));
  return has;
}

EXPORT void ei_device_start_emulating(void *device, uint32_t sequence) {
  static auto real = input_trace::real<void (*)(void *, uint32_t)>("ei_device_start_emulating", "libei.so.1");
  if (input_trace::first(input_trace::setup, 24)) input_trace::log("X server: input device %p starts sending", device);
  if (real) real(device, sequence);
}

EXPORT int ei_setup_backend_socket(void *ei, const char *socketpath) {
  static auto real = input_trace::real<int (*)(void *, const char *)>("ei_setup_backend_socket", "libei.so.1");
  int result = real ? real(ei, socketpath) : -ENOSYS;
  input_trace::log("X server connects to gamescope's input socket %s: %s", socketpath ? socketpath : "(LIBEI_SOCKET)",
                   result == 0 ? "ok" : strerror(result < 0 ? -result : result));
  return result;
}

EXPORT void ei_device_pointer_motion(void *device, double x, double y) {
  static auto real = input_trace::real<void (*)(void *, double, double)>("ei_device_pointer_motion", "libei.so.1");
  static std::atomic<uint32_t> n{0};
  uint32_t i = n.fetch_add(1, std::memory_order_relaxed);
  if (i < 3 || i % 500 == 0) input_trace::log("X server handed mouse motion #%u to gamescope (libei)", i + 1);
  if (real) real(device, x, y);
}

EXPORT uint32_t ei_event_get_type(void *event) {
  static auto real = input_trace::real<uint32_t (*)(void *)>("ei_event_get_type", "libei.so.1");
  uint32_t type = real ? real(event) : 0;
  input_trace::event_type_once("X server: libei", type);
  return type;
}

// step 3 (gamescope): received over libeis, then delivered to the window that has the pointer
EXPORT uint32_t eis_event_button_get_button(void *event) {
  static auto real = input_trace::real<uint32_t (*)(void *)>("eis_event_button_get_button", "libeis.so.1");
  static auto pressed = input_trace::real<bool (*)(void *)>("eis_event_button_get_is_press", "libeis.so.1");
  uint32_t button = real ? real(event) : 0;
  if (input_trace::first(input_trace::buttons, 24))
    input_trace::log("gamescope received mouse button 0x%x %s (libeis)", button,
                     pressed && pressed(event) ? "press" : "release");
  return button;
}

EXPORT uint32_t eis_event_keyboard_get_key(void *event) {
  static auto real = input_trace::real<uint32_t (*)(void *)>("eis_event_keyboard_get_key", "libeis.so.1");
  static auto pressed = input_trace::real<bool (*)(void *)>("eis_event_keyboard_get_key_is_press", "libeis.so.1");
  uint32_t key = real ? real(event) : 0;
  if (input_trace::first(input_trace::keys, 40))
    input_trace::log("gamescope received key %u %s (libeis)", key, pressed && pressed(event) ? "press" : "release");
  return key;
}

EXPORT void eis_client_connect(void *client) {
  static auto real = input_trace::real<void (*)(void *)>("eis_client_connect", "libeis.so.1");
  static std::atomic<uint32_t> n{0};
  input_trace::log("gamescope: X server #%u connected for input (libeis client %p)", n.fetch_add(1) + 1, client);
  if (real) real(client);
}

EXPORT uint32_t eis_event_get_type(void *event) {
  static auto real = input_trace::real<uint32_t (*)(void *)>("eis_event_get_type", "libeis.so.1");
  uint32_t type = real ? real(event) : 0;
  input_trace::event_type_once("gamescope: libeis", type);
  return type;
}

EXPORT void eis_device_configure_capability(void *device, uint32_t capability) {
  static auto real = input_trace::real<void (*)(void *, uint32_t)>("eis_device_configure_capability", "libeis.so.1");
  if (input_trace::first(input_trace::setup, 24))
    input_trace::log("gamescope: its input device %p for the X server can send%s", device, input_trace::caps(capability));
  if (real) real(device, capability);
}
// =================== end sdl3bridge: Steam Native Hook-up ===================

__attribute__((visibility("hidden"))) static std::string note_sys_failure(int dirfd, const char *pathname) {
  int saved_errno = errno;
  std::string path;
  if (saved_errno == ENOENT || !pathname || !process_is_steam_client()) {
    errno = saved_errno;
    return path;
  }
  if (pathname[0] == '/' || dirfd == AT_FDCWD) {
    path = pathname;
  } else {
    char link[64], dir[PATH_MAX];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", dirfd);
    ssize_t length = readlink(link, dir, sizeof(dir) - 1);
    if (length <= 0) {
      errno = saved_errno;
      return path;
    }
    path.assign(dir, length);
    path += '/';
    path += pathname;
  }
  if (path.compare(0, 5, "/sys/") != 0 && path != "/sys") {
    errno = saved_errno;
    return {};
  }
  static std::mutex mutex;
  static std::vector<std::string> *seen = new std::vector<std::string>();
  bool first = false;
  {
    std::lock_guard<std::mutex> guard(mutex);
    if (seen->size() < 64 && std::find(seen->begin(), seen->end(), path) == seen->end()) {
      seen->push_back(path);
      first = true;
    }
  }
  if (first) Logger::log("sysfs: %s not readable (%s)\n", path.c_str(), strerror(saved_errno));
  errno = saved_errno;
  return path;
}

__attribute__((visibility("hidden"))) static std::string deck_listing_for(const char *path) {
  const char *listing = deck_listing_dir();
  if (!path || !listing || errno != EACCES || !fake_deck_enabled() || !process_is_steam_client()) return {};
  std::string p(path);
  while (p.size() > 1 && p.back() == '/') p.pop_back();
  int which = p == "/sys" ? 0 : p == "/sys/class" ? 1 : p == "/sys/bus" ? 2 : -1;
  if (which < 0) return {};
  static const char *const names[] = {"sys", "class", "bus"};
  static std::atomic<int> logged{0};
  if (!(logged.fetch_or(1 << which) & (1 << which)))
    Logger::log("sysfs: %s listed from the Deck's stand-in\n", p.c_str());
  return std::string(listing) + "/" + names[which];
}

#ifdef __GLIBC__
__attribute__((visibility("hidden"))) static bool deck_device_library(void *caller) {
  Dl_info info;
  if (!dladdr(caller, &info) || !info.dli_fname) return false;
  const char *name = strrchr(info.dli_fname, '/');
  name = name ? name + 1 : info.dli_fname;
  return !strncmp(name, "libudev.so", 10) || !strncmp(name, "libsystemd.so", 13);
}

static int real_fstat(int fd, struct stat *buf);

__attribute__((visibility("hidden"))) static bool deck_mount_id_from_fdinfo(
    int dirfd, const char *path, int flags, struct statx *buf) {
  static auto real_openat = reinterpret_cast<decltype(&::openat)>(dlsym(RTLD_NEXT, "openat"));
  int target;
  if (!path || !*path) {
    if (!(flags & AT_EMPTY_PATH)) return false;
    target = dirfd == AT_FDCWD ? real_openat(AT_FDCWD, ".", O_PATH | O_CLOEXEC) :
                                syscall(SYS_fcntl, dirfd, F_DUPFD_CLOEXEC, 0);
  } else {
    target = real_openat(dirfd, path, O_PATH | O_CLOEXEC |
                        ((flags & AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0));
  }
  if (target < 0) return false;
  struct stat st;
  bool same = real_fstat(target, &st) == 0 && (buf->stx_mask & STATX_INO) &&
              st.st_ino == buf->stx_ino && major(st.st_dev) == buf->stx_dev_major &&
              minor(st.st_dev) == buf->stx_dev_minor;
  char name[64];
  snprintf(name, sizeof(name), "/proc/self/fdinfo/%d", target);
  int info = same ? real_openat(AT_FDCWD, name, O_RDONLY | O_CLOEXEC) : -1;
  char value[512];
  ssize_t length = info >= 0 ? syscall(SYS_read, info, value, sizeof(value) - 1) : -1;
  if (info >= 0) syscall(SYS_close, info);
  syscall(SYS_close, target);
  if (length < 0) return false;
  value[length] = '\0';
  for (const char *line = value; line && *line;) {
    unsigned long long id;
    if (sscanf(line, "mnt_id: %llu", &id) == 1 && id > 0) {
      buf->stx_mnt_id = id;
      buf->stx_mask |= STATX_MNT_ID;
      return true;
    }
    const char *next = strchr(line, '\n');
    line = next ? next + 1 : nullptr;
  }
  return false;
}

EXPORT int statx(int dirfd, const char *path, int flags, unsigned mask, struct statx *buf) {
  static auto real = reinterpret_cast<decltype(&::statx)>(dlsym(RTLD_NEXT, "statx"));
  int result = real(dirfd, path, flags, mask, buf);
  int saved_errno = errno;
  constexpr unsigned unique_mount_id = 0x4000;
  if (result == 0 && (mask & STATX_MNT_ID) &&
      !(buf->stx_mask & (STATX_MNT_ID | unique_mount_id)) && fake_deck_enabled() &&
      process_is_steam_client() && deck_device_library(__builtin_return_address(0))) {
    bool recovered = deck_mount_id_from_fdinfo(dirfd, path, flags, buf);
    static std::atomic<bool> noted_success{false}, noted_failure{false};
    if (!(recovered ? noted_success : noted_failure).exchange(true))
      Logger::log("deck discovery: statx mount id %s for %s (returned mask=0x%x id=%llu)\n",
                  recovered ? "recovered from fdinfo" : "unavailable in fdinfo",
                  path && *path ? path : "(fd)", buf->stx_mask,
                  static_cast<unsigned long long>(buf->stx_mnt_id));
  }
  errno = saved_errno;
  return result;
}
#endif

EXPORT DIR *opendir(const char *name) {
  static auto real = reinterpret_cast<DIR *(*)(const char *)>(dlsym(RTLD_NEXT, "opendir"));
  DIR *dir = real(name);
  if (!dir) {
    note_sys_failure(AT_FDCWD, name);
    std::string listing = deck_listing_for(name);
    if (!listing.empty()) dir = real(listing.c_str());
  }
  return dir;
}

EXPORT int open(const char *pathname, int flags, ...) {
  va_list va;
  mode_t mode;
  int fd;
  bool hasMode;

  va_start(va, flags);

  hasMode = flags & O_CREAT;

  if (hasMode) {
    mode = va_arg(va, mode_t);
  }

  va_end(va);

  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));

  if (fake_uinput_enabled() && is_uinput_path(pathname)) return open_uinput(flags);
  if (desktop_hidden_hidraw(pathname)) {  // sdl3bridge: meant for Steam, not for desktop programs
    errno = ENOENT;
    return -1;
  }
  if (is_deck_hidraw_path(pathname)) return open_deck_hidraw(flags);
  if (int hk = hookup_path_slot(pathname); hk >= 0) return open_hookup_hidraw(flags, hk);
  if (is_sc1_hidraw_path(pathname)) return open_deck_hidraw(flags, 0, true);
  if (is_triton_hidraw_path(pathname)) return open_triton_hidraw(flags, false);
  if (is_triton_pad_path(pathname)) return open_triton_hidraw(flags, true);
  if (int sony = sony_pad_path(pathname)) return open_deck_hidraw(flags, static_cast<uint8_t>(sony));
  if (is_touch_hidraw_path(pathname)) return open_touch_hidraw(flags);
  if (is_withdrawn_pad_path(pathname)) {
    errno = ENOENT;
    return -1;
  }

  char *fake_path = nullptr;
  const char *event = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      event = get_event(pathname);
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      if (path_exists(fake_path)) {
        fd = open_fake_input_ring(event, flags);
        if (fd >= 0) {
          free(fake_path);
          return fd;
        }
        int saved_errno = errno;
        free(fake_path);
        errno = saved_errno;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = fake_hook_dir();
    }
  }

  if (hasMode)
    fd = my_open(pathname, flags, mode);
  else
    fd = my_open(pathname, flags);
  if (fd < 0) {
    std::string listing = deck_listing_for(note_sys_failure(AT_FDCWD, pathname).c_str());
    if (!listing.empty()) fd = my_open(listing.c_str(), flags);
  }

  if (fake_path)
    free(fake_path);

  return fd;
}

EXPORT int openat(int dirfd, const char *pathname, int flags, ...) {
  va_list va;
  mode_t mode;
  int fd;
  bool hasMode;

  va_start(va, flags);

  hasMode = flags & O_CREAT;

  if (hasMode) {
    mode = va_arg(va, mode_t);
  }

  va_end(va);

  static auto my_openat = reinterpret_cast<int (*)(int, const char *, int, ...)>(dlsym(RTLD_NEXT, "openat"));

  if (fake_uinput_enabled() && is_uinput_path(pathname)) return open_uinput(flags);
  if (desktop_hidden_hidraw(pathname)) {  // sdl3bridge: meant for Steam, not for desktop programs
    errno = ENOENT;
    return -1;
  }
  if (is_deck_hidraw_path(pathname)) return open_deck_hidraw(flags);
  if (int hk = hookup_path_slot(pathname); hk >= 0) return open_hookup_hidraw(flags, hk);
  if (is_sc1_hidraw_path(pathname)) return open_deck_hidraw(flags, 0, true);
  if (is_triton_hidraw_path(pathname)) return open_triton_hidraw(flags, false);
  if (is_triton_pad_path(pathname)) return open_triton_hidraw(flags, true);
  if (int sony = sony_pad_path(pathname)) return open_deck_hidraw(flags, static_cast<uint8_t>(sony));
  if (is_touch_hidraw_path(pathname)) return open_touch_hidraw(flags);
  if (is_withdrawn_pad_path(pathname)) {
    errno = ENOENT;
    return -1;
  }

  char *fake_path = nullptr;
  const char *event = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      event = get_event(pathname);
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      if (path_exists(fake_path)) {
        fd = open_fake_input_ring(event, flags);
        if (fd >= 0) {
          free(fake_path);
          return fd;
        }
        int saved_errno = errno;
        free(fake_path);
        errno = saved_errno;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = fake_hook_dir();
    }
  }

  if (hasMode)
    fd = my_openat(dirfd, pathname, flags, mode);
  else
    fd = my_openat(dirfd, pathname, flags);
  if (fd < 0) {
    std::string listing = deck_listing_for(note_sys_failure(dirfd, pathname).c_str());
    if (!listing.empty()) fd = my_openat(AT_FDCWD, listing.c_str(), flags);
  }

  if (fake_path)
    free(fake_path);

  return fd;
}

template <typename S, typename Real>
static int fake_stat_path(const char *pathname, S *statbuf, Real real) {
  if (is_withdrawn_pad_path(pathname) ||
      ((is_deck_hidraw_path(pathname) || is_triton_hidraw_path(pathname) || is_triton_pad_path(pathname) ||
        sony_pad_path(pathname) || is_sc1_hidraw_path(pathname)) && !process_is_steam_client())) {
    errno = ENOENT;
    return -1;
  }
  if (hookup_path_slot(pathname) >= 0 && !process_is_steam_client()) {
    errno = ENOENT;
    return -1;
  }
  if (is_touch_hidraw_path(pathname)) {
    memset(statbuf, 0, sizeof(*statbuf));
    statbuf->st_mode = S_IFCHR | 0666;
    statbuf->st_rdev = makedev(DECK_HIDRAW_MAJOR, TOUCH_HIDRAW_MINOR);
    return 0;
  }
  if (is_deck_hidraw_path(pathname)) {
    memset(statbuf, 0, sizeof(*statbuf));
    statbuf->st_mode = S_IFCHR | 0666;
    statbuf->st_rdev = makedev(DECK_HIDRAW_MAJOR, DECK_HIDRAW_MINOR);
    return 0;
  }
  if (int hk = hookup_path_slot(pathname); hk >= 0) {
    memset(statbuf, 0, sizeof(*statbuf));
    statbuf->st_mode = S_IFCHR | 0666;
    statbuf->st_rdev = makedev(DECK_HIDRAW_MAJOR, HOOKUP_MINOR_BASE + hk);
    return 0;
  }
  if (is_sc1_hidraw_path(pathname)) {
    memset(statbuf, 0, sizeof(*statbuf));
    statbuf->st_mode = S_IFCHR | 0666;
    statbuf->st_rdev = makedev(DECK_HIDRAW_MAJOR, SC1_HIDRAW_MINOR);
    return 0;
  }
  if (int sony = sony_pad_path(pathname)) {
    memset(statbuf, 0, sizeof(*statbuf));
    statbuf->st_mode = S_IFCHR | 0666;
    statbuf->st_rdev = makedev(DECK_HIDRAW_MAJOR, sony == 4 ? DS4_HIDRAW_MINOR : sony == 6 ? DUALSENSE_EDGE_HIDRAW_MINOR : DUALSENSE_HIDRAW_MINOR);
    return 0;
  }
  if (is_triton_hidraw_path(pathname) || is_triton_pad_path(pathname)) {
    memset(statbuf, 0, sizeof(*statbuf));
    statbuf->st_mode = S_IFCHR | 0666;
    statbuf->st_rdev = makedev(TRITON_HIDRAW_MAJOR, is_triton_pad_path(pathname) ? TRITON_PAD_HIDRAW_MINOR : TRITON_HIDRAW_MINOR);
    return 0;
  }

  const char *event = nullptr;
  char *fake_path = nullptr;

  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      event = get_event(pathname);
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = fake_hook_dir();
    }
  }

  int ret = real(pathname, statbuf);

  if (ret == 0 && event && get_event_number(event) >= 0) {
    statbuf->st_mode = (statbuf->st_mode & ~S_IFMT) | S_IFCHR;
    statbuf->st_rdev = get_fake_input_rdev(event);
  }

  if (fake_path)
    free(fake_path);

  return ret;
}

template <typename S, typename Real>
static int fake_stat_fd(int fd, S *buf, Real real) {
  int ret = real(fd, buf);
  if (ret != 0 || !watched(fd)) return ret;

  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  if (ret == 0 && deck_map().count(fd)) {
    buf->st_mode = (buf->st_mode & ~S_IFMT) | S_IFCHR;
    const auto &device = *deck_map()[fd];
    unsigned int minor = device.hookup >= 0 ? HOOKUP_MINOR_BASE + device.hookup
                       : device.touch ? TOUCH_HIDRAW_MINOR
                       : device.sony == 4 ? DS4_HIDRAW_MINOR
                       : device.sony == 6 ? DUALSENSE_EDGE_HIDRAW_MINOR
                       : device.sony == 5 ? DUALSENSE_HIDRAW_MINOR
                       : device.emulated ? TRITON_PAD_HIDRAW_MINOR
                       : device.triton ? TRITON_HIDRAW_MINOR
                       : device.sc1 ? SC1_HIDRAW_MINOR : DECK_HIDRAW_MINOR;
    buf->st_rdev = makedev(DECK_HIDRAW_MAJOR, minor);
    return ret;
  }
  auto controller = controller_map().find(fd);
  if (ret == 0 && controller != controller_map().end()) {
    buf->st_mode = (buf->st_mode & ~S_IFMT) | S_IFCHR;
    buf->st_rdev = get_fake_input_rdev(controller->second->event);
  }

  return ret;
}

static int real_stat(const char *pathname, struct stat *statbuf) {
  static auto fn = reinterpret_cast<int (*)(const char *, struct stat *)>(dlsym(RTLD_NEXT, "stat"));
  if (fn) return fn(pathname, statbuf);
#ifdef _STAT_VER
  static auto xfn = reinterpret_cast<int (*)(int, const char *, struct stat *)>(dlsym(RTLD_NEXT, "__xstat"));
  if (xfn) return xfn(_STAT_VER, pathname, statbuf);
#endif
  errno = ENOSYS;
  return -1;
}

static int real_fstat(int fd, struct stat *buf) {
  static auto fn = reinterpret_cast<int (*)(int, struct stat *)>(dlsym(RTLD_NEXT, "fstat"));
  if (fn) return fn(fd, buf);
#ifdef _STAT_VER
  static auto xfn = reinterpret_cast<int (*)(int, int, struct stat *)>(dlsym(RTLD_NEXT, "__fxstat"));
  if (xfn) return xfn(_STAT_VER, fd, buf);
#endif
  errno = ENOSYS;
  return -1;
}

EXPORT int stat(const char *pathname, struct stat *statbuf) {
  return fake_stat_path(pathname, statbuf, real_stat);
}

EXPORT int fstat(int fd, struct stat *buf) {
  return fake_stat_fd(fd, buf, real_fstat);
}

EXPORT int access(const char *pathname, int mode) {
  static auto my_access = reinterpret_cast<decltype(&::access)>(dlsym(RTLD_NEXT, "access"));
  if (fake_uinput_enabled() && is_uinput_path(pathname)) return 0;
  if (is_deck_hidraw_path(pathname) && process_is_steam_client()) return 0;
  if (is_sc1_hidraw_path(pathname) && process_is_steam_client()) return 0;
  if (hookup_path_slot(pathname) >= 0 && process_is_steam_client()) return 0;
  if ((is_triton_hidraw_path(pathname) || is_triton_pad_path(pathname) || sony_pad_path(pathname)) &&
      process_is_steam_client())
    return 0;
  if (is_touch_hidraw_path(pathname)) return 0;
  if (is_withdrawn_pad_path(pathname) || is_deck_hidraw_path(pathname) || is_sc1_hidraw_path(pathname) ||
      hookup_path_slot(pathname) >= 0 ||
      is_triton_hidraw_path(pathname) ||
      is_triton_pad_path(pathname) || sony_pad_path(pathname)) {
    errno = ENOENT;
    return -1;
  }

  char *fake_path = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = fake_hook_dir();
    }
  }

  int ret = my_access(pathname, mode);
  if (fake_path)
    free(fake_path);
  return ret;
}

EXPORT int faccessat(int dirfd, const char *pathname, int mode, int flags) {
  static auto my_faccessat = reinterpret_cast<decltype(&::faccessat)>(dlsym(RTLD_NEXT, "faccessat"));
  if (fake_uinput_enabled() && is_uinput_path(pathname)) return 0;
  if (is_deck_hidraw_path(pathname) && process_is_steam_client()) return 0;
  if (is_sc1_hidraw_path(pathname) && process_is_steam_client()) return 0;
  if (hookup_path_slot(pathname) >= 0 && process_is_steam_client()) return 0;
  if ((is_triton_hidraw_path(pathname) || is_triton_pad_path(pathname) || sony_pad_path(pathname)) &&
      process_is_steam_client())
    return 0;
  if (is_touch_hidraw_path(pathname)) return 0;
  if (is_withdrawn_pad_path(pathname) || is_deck_hidraw_path(pathname) || is_sc1_hidraw_path(pathname) ||
      hookup_path_slot(pathname) >= 0 ||
      is_triton_hidraw_path(pathname) ||
      is_triton_pad_path(pathname) || sony_pad_path(pathname)) {
    errno = ENOENT;
    return -1;
  }

  char *fake_path = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = fake_hook_dir();
    }
  }

  int ret = my_faccessat(dirfd, pathname, mode, flags);
  if (fake_path)
    free(fake_path);
  return ret;
}

EXPORT int scandir(const char *dirp, struct dirent ***namelist,
                   int (*filter)(const struct dirent *),
                   int (*compar)(const struct dirent **,
                                 const struct dirent **)) {
  static auto my_scandir = reinterpret_cast<decltype(&::scandir)>(dlsym(RTLD_NEXT, "scandir"));

  if (dirp) {
    if (!strcmp(dirp, "/dev/input")) {
      dirp = fake_hook_dir();
    } else if (fake_udev_data_dir() && !strcmp(dirp, "/run/udev/data")) {
      dirp = fake_udev_data_dir();
    }
  }

  return my_scandir(dirp, namelist, filter, compar);
}

EXPORT int inotify_add_watch(int fd, const char *pathname, uint32_t mask) {
  static auto my_inotify_add_watch = reinterpret_cast<decltype(&::inotify_add_watch)>(dlsym(RTLD_NEXT, "inotify_add_watch"));

  char *fake_path = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = fake_hook_dir();
    } else if (!strcmp(pathname, "/dev") && fake_deck_enabled() && process_is_steam_client()) {
      std::string events = hidraw_event_dir();
      mkdir(events.c_str(), 0700);
      my_inotify_add_watch(fd, events.c_str(), mask);
    }
  }

  int ret = my_inotify_add_watch(fd, pathname, mask);
  if (fake_path)
    free(fake_path);
  return ret;
}

template <size_t N>
static int copy_ioctl_bits(int op, void *destination, const unsigned char (&bits)[N]) {
  size_t size = std::min<size_t>(_IOC_SIZE(op), N);
  if (size) memcpy(destination, bits, size);
  return static_cast<int>(size);
}

static bool wait_snapshot(std::shared_ptr<FakeController> fake,
                          std::unique_lock<std::recursive_mutex> &guard,
                          SnapshotState &snap) {
  for (;;) {
    if (fake->closed) {
      errno = EBADF;
      return false;
    }
    if (controller_gone(*fake)) {
      errno = ENODEV;
      return false;
    }
    if (read_snapshot(fake->ring, snap)) return true;
    guard.unlock();
    struct timespec wait = {0, 1000000};
    int result = nanosleep(&wait, nullptr);
    int saved_errno = errno;
    guard.lock();
    if (result < 0) {
      errno = saved_errno;
      return false;
    }
  }
}

static int forward_ioctl(int fd, ioctl_request_t op, void *argp) {
  using ioctl_fn = int (*)(int, ioctl_request_t, ...);
  static const auto next = reinterpret_cast<ioctl_fn>(dlsym(RTLD_NEXT, "ioctl"));
  return next ? next(fd, op, argp) : static_cast<int>(syscall(SYS_ioctl, fd, op, argp));
}

EXPORT int ioctl(int fd, ioctl_request_t op, ...) {
  va_list va;
  void *argp;

  va_start(va, op);
  argp = va_arg(va, void *);
  va_end(va);

  if (!watched(fd)) return forward_ioctl(fd, op, argp);
  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  auto maker = uinput_map().find(fd);
  if (maker != uinput_map().end()) return ioctl_uinput(*maker->second, op, argp);
  auto deck = deck_map().find(fd);
  if (deck != deck_map().end()) {
    if (deck->second->hookup >= 0) {
      std::shared_ptr<DeckHidraw> pad = deck->second;
      guard.unlock();
      return ioctl_hookup(*pad, op, argp);
    }
    if (deck->second->touch) return ioctl_touch(*deck->second, op, argp);
    if (deck->second->sony) return ioctl_sony(*deck->second, op, argp);
    if (deck->second->triton) {
      std::shared_ptr<DeckHidraw> triton = deck->second;
      guard.unlock();
      return ioctl_triton(*triton, op, argp);
    }
    return ioctl_deck(*deck->second, op, argp);
  }
  auto controller = controller_map().find(fd);
  if (controller == controller_map().end()) {
    guard.unlock();
    return forward_ioctl(fd, op, argp);
  }

  int type = (op >> 8 & 0xFF);
  int number = (op >> 0 & 0xFF);
  const char *event = controller->second->event ? controller->second->event : "event0";
  int event_number = controller->second->slot;
  const UinputDescription *made = controller->second->uinput;

  if (type == 0x45 && number == 0x1) {
    Logger::log("Hooking ioctl EVIOCGVERSION for event %s\n", event);
    int version = 65536;
    memcpy(argp, (void *)&version, sizeof(int));
    return 0;
  } else if (type == 0x45 && number == 0x2) {
    Logger::log("Hooking ioctl EVIOCGID for event %s\n", event);
    struct input_id id;
    memset(&id, 0, sizeof(id));
    id.bustype = 0x03;
    if (made) {
      id = made->id;
    } else if (presents_steam_virtual()) {
      id.vendor = STEAM_VIRTUAL_VENDOR_ID;
      id.product = STEAM_VIRTUAL_PRODUCT_ID;
    } else if (fake_xbox360_identity()) {
      id.vendor = X360_VENDOR_ID;
      id.product = X360_PRODUCT_ID;
    } else {
      id.vendor = static_cast<uint16_t>(GAMEPAD_VENDOR_ID_BASE + event_number);
      id.product = static_cast<uint16_t>(GAMEPAD_PRODUCT_ID_BASE + event_number);
    }
    if (!made) id.version = GAMEPAD_VERSION;
    memcpy(argp, (void *)&id, sizeof(id));
    return 0;
  } else if (type == 0x45 && number == 0x6) {
    Logger::log("Hooking ioctl EVIOCGNAME for event %s\n", event);
    if (made) {
      if (argp && _IOC_SIZE(op)) snprintf(static_cast<char *>(argp), _IOC_SIZE(op), "%s", made->name);
      return 0;
    }
    copy_slot_ioctl_string(op, argp,
                           presents_steam_virtual() ? STEAM_VIRTUAL_NAME_TEMPLATE
                           : (fake_xbox360_identity() ? X360_NAME_TEMPLATE : GAMEPAD_NAME_TEMPLATE),
                           event_number);
    return 0;
  } else if (type == 0x45 && number == 0x7) {
    Logger::log("Hooking ioctl EVIOCGPHYS for event %s\n", event);
    if (made) {
      if (argp && _IOC_SIZE(op)) snprintf(static_cast<char *>(argp), _IOC_SIZE(op), "%s", made->phys);
      return 0;
    }
    copy_slot_ioctl_string(op, argp, GAMEPAD_PHYS_TEMPLATE, event_number);
    return 0;
  } else if (type == 0x45 && number == 0x8) {
    Logger::log("Hooking ioctl EVIOCGUNIQ for event %s\n", event);
    if (made) {
      if (argp && _IOC_SIZE(op)) static_cast<char *>(argp)[0] = '\0';
      return 0;
    }
    copy_slot_ioctl_string(op, argp, GAMEPAD_UNIQ_TEMPLATE, event_number);
    return 0;
  } else if (type == 0x45 && number == 0x9) {
    unsigned char bitmask[(INPUT_PROP_MAX + 8) / 8] = {};
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x18) {
    SnapshotState snap;
    if (!wait_snapshot(controller->second, guard, snap)) return -1;
    unsigned char bitmask[(KEY_MAX + 8) / 8] = {};
    for (size_t i = 0; i < kSnapshotButtonCount; ++i) {
      if (snap.buttons & (1u << i))
        bitmask[kSnapshotButtons[i] / 8] |= 1u << (kSnapshotButtons[i] % 8);
    }
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x20) {
    Logger::log("Hooking ioctl EVIOCGBIT(0, len) for event %s\n", event);
    if (made) return copy_ioctl_bits(op, argp, made->evbits);
    unsigned char bitmask[(EV_MAX + 8) / 8] = {};
    bitmask[EV_SYN / 8] |= (1 << (EV_SYN % 8));
    bitmask[EV_KEY / 8] |= (1 << (EV_KEY % 8));
    bitmask[EV_ABS / 8] |= (1 << (EV_ABS % 8));
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x21) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_KEY, len) for event %s\n", event);
    if (made) return copy_ioctl_bits(op, argp, made->keybits);
    unsigned char bitmask[(KEY_MAX + 8) / 8] = {};
    const int xbox_buttons[] = {BTN_A,    BTN_B,      BTN_X,      BTN_Y,
                                BTN_TL,   BTN_TR,     BTN_SELECT, BTN_START,
                                BTN_MODE, BTN_THUMBL, BTN_THUMBR};
    for (int button : xbox_buttons)
      bitmask[button / 8] |= (1 << (button % 8));
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x22) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_REL, len) for event %s\n", event);
    unsigned char bitmask[(REL_MAX + 8) / 8] = {};
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x23) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_ABS, len) for event %s\n", event);
    if (made) return copy_ioctl_bits(op, argp, made->absbits);
    unsigned char bitmask[(ABS_MAX + 8) / 8] = {};
    bitmask[ABS_X / 8] |= (1 << (ABS_X % 8));
    bitmask[ABS_Y / 8] |= (1 << (ABS_Y % 8));
    bitmask[ABS_RX / 8] |= (1 << (ABS_RX % 8));
    bitmask[ABS_RY / 8] |= (1 << (ABS_RY % 8));
    for (uint16_t trigger : {ABS_GAS, ABS_BRAKE}) {
      uint16_t code = presented_abs_code(*controller->second, trigger);
      bitmask[code / 8] |= (1 << (code % 8));
    }
    bitmask[ABS_HAT0X / 8] |= (1 << (ABS_HAT0X % 8));
    bitmask[ABS_HAT0Y / 8] |= (1 << (ABS_HAT0Y % 8));
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x35) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_FF, len) for event %s\n", event);
    if (made) return copy_ioctl_bits(op, argp, made->ffbits);
    unsigned char bitmask[(FF_MAX + 8) / 8] = {};
    bitmask[FF_RUMBLE / 8] |= (1 << (FF_RUMBLE % 8));
    bitmask[FF_PERIODIC / 8] |= (1 << (FF_PERIODIC % 8));
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x80) {
    struct ff_effect *effect = static_cast<struct ff_effect *>(argp);
    if (effect->id == -1)
      effect->id = next_ff_id++;
    ff_effects()[effect->id] = *effect;
    send_ff_effect(*effect, static_cast<uint16_t>(event_number));
    return 0;
  } else if (type == 0x45 && number == 0x81) {
    int id = (intptr_t)argp;
    ff_effects().erase(id);
    return 0;
  } else if (type == 0x45 && number == 0x84) {
    int max_effects = 16;
    memcpy(argp, &max_effects, sizeof(int));
    return 0;
  } else if (type == 0x45 && number >= 0x40 && number <= 0x51) {
    Logger::log("Hooking ioctl EVIOCGABS(ABS) for event %s\n", event);
    struct input_absinfo abs_info;
    memset(&abs_info, 0, sizeof(abs_info));
    uint16_t code = ring_abs_code(*controller->second, number - 0x40);
    if (made) {
      abs_info = made->absinfo[number - 0x40];
    } else if (code == ABS_GAS || code == ABS_BRAKE) {
      abs_info.value = 0;
      abs_info.minimum = 0;
      abs_info.maximum = 255;
    } else if (code <= ABS_RY) {
      abs_info.value = 0;
      abs_info.minimum = -32768;
      abs_info.maximum = 32767;
    } else if (code == ABS_HAT0X || code == ABS_HAT0Y) {
      abs_info.value = 0;
      abs_info.minimum = -1;
      abs_info.maximum = 1;
    }
    SnapshotState snap;
    if (!wait_snapshot(controller->second, guard, snap)) return -1;
    for (int i = 0; i < 8; ++i)
      if (kSnapshotAxisCodes[i] == code) abs_info.value = snap.axes[i];
    memcpy(argp, &abs_info, std::min<size_t>(_IOC_SIZE(op), sizeof(abs_info)));
    return 0;
  } else if (type == 0x45 && number == 0x90) {
    Logger::log("Hooking ioctl EVIOCGRAB for event %s\n", event);
    return 0;
  } else if (type == 0x6A && !strncmp(event, "event", 5)) {
    // Older SDL2 builds treat any JSIOCGNAME answer as a joydev node and skip EVIOCGID, then drop the pad as 0000:0000.
    Logger::log("Rejecting joydev ioctl %d for event %s\n", number, event);
    guard.unlock();
    errno = EINVAL;
    return -1;
  } else if (type == 0x6A && number == 0x1) {
    Logger::log("Hooking ioctl JSIOCGVERSION for event %s\n", event);
    int version = JS_VERSION;
    memcpy(argp, (void *)&version, sizeof(version));
    return 0;
  } else if (type == 0x6A && number == 0x11) {
    Logger::log("Hooking ioctl JSIOCGAXES for event %s\n", event);
    uint8_t axes = GAMEPAD_AXIS_COUNT;
    memcpy(argp, (void *)&axes, sizeof(axes));
    return 0;
  } else if (type == 0x6A && number == 0x12) {
    Logger::log("Hooking ioctl JSIOCGBUTTONS for event %s\n", event);
    uint8_t buttons = GAMEPAD_BUTTON_COUNT;
    memcpy(argp, (void *)&buttons, sizeof(buttons));
    return 0;
  } else if (type == 0x6A && number == 0x13) {
    Logger::log("Hooking ioctl JSIOCGNAME(len) for event %s\n", event);
    copy_slot_ioctl_string(op, argp,
                           presents_steam_virtual() ? STEAM_VIRTUAL_NAME_TEMPLATE
                           : (fake_xbox360_identity() ? X360_NAME_TEMPLATE : GAMEPAD_NAME_TEMPLATE),
                           event_number);
    return 0;
  } else {
    Logger::log("Unhandled evdev ioctl, type %d number %d\n", type, number);
    guard.unlock();
    return forward_ioctl(fd, op, argp);
  }
}

EXPORT int close(int fd) {
  static auto my_close = reinterpret_cast<decltype(&::close)>(dlsym(RTLD_NEXT, "close"));

  if (!watched(fd)) return my_close(fd);
  close_uinput(fd);
  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  deck_map().erase(fd);
  auto controller = controller_map().find(fd);
  if (controller != controller_map().end()) {
    Logger::log("Removing controller, fd %d event %s\n", controller->first,
                controller->second->event ? controller->second->event : "(unknown)");
    controller->second->closed = true;
    controller_map().erase(fd);
  }
  unwatch_fd(fd);
  guard.unlock();

  return my_close(fd);
}

static constexpr int kMaxRingWaitMs = 2;
static constexpr int kLowLatencyRingWaitUs = 250;

static ssize_t copy_input_events(void *buf, const struct input_event *events, size_t count) {
  size_t bytes = count * sizeof(struct input_event);
  struct iovec local = {const_cast<struct input_event *>(events), bytes};
  struct iovec remote = {buf, bytes};
  ssize_t copied = syscall(SYS_process_vm_writev, getpid(), &local, 1, &remote, 1, 0);
  if (copied < 0) return -1;
  if (copied != static_cast<ssize_t>(bytes)) {
    errno = EFAULT;
    copied -= copied % sizeof(struct input_event);
    if (!copied) return -1;
  }
  return copied;
}

static ssize_t read_input(int fd, void *buf, size_t count) {
  if (!watched(fd)) return syscall(SYS_read, fd, buf, count);
  if (ssize_t ff = hookup_read_uinput(fd, buf, count); ff != -2) return ff;
  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  auto controller = controller_map().find(fd);
  if (controller == controller_map().end()) {
    guard.unlock();
    return syscall(SYS_read, fd, buf, count);
  }
  auto handle = controller->second;
  FakeController &fake = *handle;
  if (count < sizeof(struct input_event)) {
    errno = EINVAL;
    return -1;
  }
  int flags = fcntl(fd, F_GETFL);
  bool nonblock = flags >= 0 && (flags & O_NONBLOCK);
  long backoff_ns = 1000 * 1000;

  for (;;) {
    if (fake.closed) {
      errno = EBADF;
      return -1;
    }
    if (controller_gone(fake)) {
      errno = ENODEV;
      return -1;
    }
    for (int attempt = 0; attempt < 8; ++attempt) {
      size_t requested = count / sizeof(struct input_event);
      if (fake.keyframe_remaining == 0) {
        SnapshotState snap;
        if (!read_snapshot(fake.ring, snap)) break;
        if (snap.generation != fake.generation) {
          errno = ENODEV;
          return -1;
        }
        if (fake.needs_keyframe || snap.resync_seq != fake.resync_seq ||
            snap.write_seq < fake.read_seq ||
            snap.write_seq - fake.read_seq > FAKE_INPUT_RING_CAPACITY) {
          capture_keyframe(fake, snap);
        } else {
          size_t events = std::min<uint64_t>(requested, snap.write_seq - fake.read_seq);
          if (events == 0) break;
          const uint8_t *ring_events = reinterpret_cast<const uint8_t *>(fake.ring) +
                                       FAKE_INPUT_RING_HEADER_SIZE;
          std::vector<struct input_event> input_events(events);
          for (size_t i = 0; i < events; ++i) {
            size_t index = (fake.read_seq + i) % FAKE_INPUT_RING_CAPACITY;
            RingEvent stored;
            memcpy(&stored, ring_events + index * FAKE_INPUT_EVENT_SIZE, FAKE_INPUT_EVENT_SIZE);
            struct input_event &ev = input_events[i];
            ev.time.tv_sec = stored.sec;
            ev.time.tv_usec = stored.usec;
            ev.type = stored.type;
            ev.code = stored.code;
            ev.value = stored.value;
            if (ev.type == EV_ABS) ev.code = presented_abs_code(fake, ev.code);
          }
          __atomic_thread_fence(__ATOMIC_ACQUIRE);
          if (snap.sequence != __atomic_load_n(&fake.ring->snapshot_seq, __ATOMIC_RELAXED))
            continue;
          ssize_t copied = copy_input_events(buf, input_events.data(), events);
          if (copied > 0) fake.read_seq += copied / sizeof(struct input_event);
          if (copied > 0 && fake.uinput) hookup_meter_game_read(input_events.data(), copied / sizeof(struct input_event));
          return copied;
        }
      }

      size_t events = std::min(requested, fake.keyframe_remaining);
      struct input_event input_events[kNeutralEventCount] = {};
      struct timeval now = {};
      gettimeofday(&now, nullptr);
      for (size_t i = 0; i < events; ++i) {
        size_t index = kNeutralEventCount - fake.keyframe_remaining + i;
        struct input_event &ev = input_events[i];
        ev.time = now;
        ev.type = kNeutralEvents[index].type;
        ev.code = kNeutralEvents[index].code;
        ev.value = keyframe_value(fake, ev.type, ev.code);
        if (ev.type == EV_ABS) ev.code = presented_abs_code(fake, ev.code);
      }
      ssize_t copied = copy_input_events(buf, input_events, events);
      if (copied > 0) fake.keyframe_remaining -= copied / sizeof(struct input_event);
      return copied;
    }
    if (nonblock) {
      errno = EAGAIN;
      return -1;
    }
    guard.unlock();
    struct timespec sleep_time = {0, backoff_ns};
    int result = nanosleep(&sleep_time, nullptr);
    guard.lock();
    if (result < 0) return -1;
    if (low_latency(LATENCY_CONTROLLER)) backoff_ns = kLowLatencyRingWaitUs * 1000;
    else if (backoff_ns < kMaxRingWaitMs * 1000 * 1000) backoff_ns *= 2;
  }
}

EXPORT ssize_t read(int fd, void *buf, size_t count) {
  return read_input(fd, buf, count);
}

// sdl3bridge: read() as programs built with _FORTIFY_SOURCE call it
EXPORT ssize_t __read_chk(int fd, void *buf, size_t count, size_t buflen) {
  if (count > buflen) {  // a buffer overflow: the C library reports it, as it would without the hook
    static auto real = reinterpret_cast<ssize_t (*)(int, void *, size_t, size_t)>(dlsym(RTLD_NEXT, "__read_chk"));
    if (real) return real(fd, buf, count, buflen);
    abort();
  }
  return read_input(fd, buf, count);
}

EXPORT ssize_t readv(int fd, const struct iovec *iov, int iovcnt) {
  if (ssize_t mailbox = hookup_readv_mailbox(fd, iov, iovcnt); mailbox != -2) return mailbox;
  if (!is_fake_input_fd(fd)) return syscall(SYS_readv, fd, iov, iovcnt);
  if (iovcnt < 0 || iovcnt > IOV_MAX) {
    errno = EINVAL;
    return -1;
  }
  if (iovcnt && !iov) {
    errno = EFAULT;
    return -1;
  }
  std::vector<struct iovec> copied_iov(static_cast<size_t>(iovcnt));
  if (iovcnt) {
    size_t bytes = copied_iov.size() * sizeof(struct iovec);
    struct iovec local = {copied_iov.data(), bytes};
    struct iovec remote = {const_cast<struct iovec *>(iov), bytes};
    ssize_t copied = syscall(SYS_process_vm_readv, getpid(), &local, 1, &remote, 1, 0);
    if (copied != static_cast<ssize_t>(bytes)) {
      errno = EFAULT;
      return -1;
    }
  }
  iov = copied_iov.data();
  size_t size = 0;
  for (int i = 0; i < iovcnt; ++i) {
    if (iov[i].iov_len > static_cast<size_t>(SSIZE_MAX) - size) {
      errno = EINVAL;
      return -1;
    }
    size += iov[i].iov_len;
  }

  ssize_t total = 0;
  for (int i = 0; i < iovcnt; ++i) {
    if (!iov[i].iov_len) continue;
    ssize_t got = read_input(fd, iov[i].iov_base, iov[i].iov_len);
    if (got < 0) return total ? total : -1;
    total += got;
    if (static_cast<size_t>(got) != iov[i].iov_len) break;
  }
  return total;
}

EXPORT ssize_t write(int fd, const void *buf, size_t count) {
  static auto my_write = reinterpret_cast<decltype(&::write)>(dlsym(RTLD_NEXT, "write"));

  if (!watched(fd)) return my_write(fd, buf, count);
  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  auto made = uinput_map().find(fd);
  if (made != uinput_map().end()) return write_uinput(*made->second, buf, count);
  if (deck_map().count(fd)) {
    if (deck_map()[fd]->hookup >= 0) {
      std::shared_ptr<DeckHidraw> pad = deck_map()[fd];
      guard.unlock();
      hookup_queue(*pad, static_cast<const uint8_t *>(buf), count, 0);
      return static_cast<ssize_t>(count);
    }
    if (deck_map()[fd]->touch) touch_output(static_cast<const uint8_t *>(buf), count);
    else if (deck_map()[fd]->sony) sony_output(*deck_map()[fd], static_cast<const uint8_t *>(buf), count);
    else if (deck_map()[fd]->emulated) triton_pad_output(static_cast<const uint8_t *>(buf), count);
    else if (deck_map()[fd]->triton) triton_queue_output(*deck_map()[fd], static_cast<const uint8_t *>(buf), count, false);
    return static_cast<ssize_t>(count);
  }
  auto controller = controller_map().find(fd);
  if (controller != controller_map().end()) {
    if (fake_fd_is_stale(fd)) {
      errno = ENODEV;
      return -1;
    }

    const struct input_event *ev = nullptr;
    uint16_t slot = static_cast<uint16_t>(controller->second->slot);
    if (count == sizeof(struct input_event)) {
      ev = static_cast<const struct input_event *>(buf);
      check_ff_event(ev, slot);
    }

    return static_cast<ssize_t>(count);
  }
  guard.unlock();
  return my_write(fd, buf, count);
}

EXPORT ssize_t writev(int fd, const struct iovec *iov, int iovcnt) {
  if (!watched(fd)) return syscall(SYS_writev, fd, iov, iovcnt);
  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  if (deck_map().count(fd)) {
    ssize_t total = 0;
    auto device = deck_map()[fd];
    if (device->hookup >= 0) {
      guard.unlock();
      for (int i = 0; i < iovcnt; i++) {
        hookup_queue(*device, static_cast<const uint8_t *>(iov[i].iov_base), iov[i].iov_len, 0);
        total += static_cast<ssize_t>(iov[i].iov_len);
      }
      return total;
    }
    for (int i = 0; i < iovcnt; i++) {
      if (device->touch) touch_output(static_cast<const uint8_t *>(iov[i].iov_base), iov[i].iov_len);
      else if (device->sony) sony_output(*device, static_cast<const uint8_t *>(iov[i].iov_base), iov[i].iov_len);
      else if (device->emulated) triton_pad_output(static_cast<const uint8_t *>(iov[i].iov_base), iov[i].iov_len);
      else if (device->triton) triton_queue_output(*device, static_cast<const uint8_t *>(iov[i].iov_base), iov[i].iov_len, false);
      total += static_cast<ssize_t>(iov[i].iov_len);
    }
    return total;
  }
  auto made = uinput_map().find(fd);
  if (made != uinput_map().end()) {
    ssize_t total = 0;
    for (int i = 0; i < iovcnt; i++) {
      ssize_t written = write_uinput(*made->second, iov[i].iov_base, iov[i].iov_len);
      if (written < 0) return total ? total : -1;
      total += written;
    }
    return total;
  }
  auto controller = controller_map().find(fd);
  if (controller != controller_map().end()) {
    if (fake_fd_is_stale(fd)) {
      errno = ENODEV;
      return -1;
    }

    uint16_t slot = static_cast<uint16_t>(controller->second->slot);
    ssize_t total = 0;
    for (int i = 0; i < iovcnt; i++) {
      if (iov[i].iov_len == sizeof(struct input_event)) {
        const struct input_event *ev =
            static_cast<const struct input_event *>(iov[i].iov_base);
        check_ff_event(ev, slot);
      }
      total += static_cast<ssize_t>(iov[i].iov_len);
    }
    return total;
  }
  guard.unlock();
  return syscall(SYS_writev, fd, iov, iovcnt);
}

static int poll_fake(struct pollfd *fds, nfds_t nfds, int timeout,
                     const sigset_t *sigmask) {
  static auto my_poll = reinterpret_cast<decltype(&::poll)>(dlsym(RTLD_NEXT, "poll"));
  static auto my_ppoll = reinterpret_cast<decltype(&::ppoll)>(dlsym(RTLD_NEXT, "ppoll"));

  nfds_t first = 0;
  while (first < nfds && !watched(fds[first].fd)) first++;
  bool has_fake_fds = false;
  std::vector<struct pollfd> real_fds;
  std::vector<std::shared_ptr<FakeController>> fake_fds;
  if (first < nfds) {
    real_fds.reserve(nfds);
    fake_fds.resize(nfds);
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    for (nfds_t i = 0; i < nfds; i++) {
      real_fds.push_back(fds[i]);
      auto it = controller_map().find(fds[i].fd);
      if (it != controller_map().end()) {
        fake_fds[i] = it->second;
        has_fake_fds = true;
        real_fds[i].fd = -1;
        real_fds[i].revents = 0;
      }
    }
  }

  if (!has_fake_fds) {
    if (sigmask) {
      struct timespec wait = {timeout / 1000, (timeout % 1000) * 1000000L};
      return my_ppoll(fds, nfds, timeout < 0 ? nullptr : &wait, sigmask);
    }
    return my_poll(fds, nfds, timeout);
  }

  const long long deadline_ms = timeout < 0 ? -1 : monotonic_ms() + timeout;
  int backoff_ms = 1;

  while (true) {
    int ready = 0;

    for (nfds_t i = 0; i < nfds; i++)
      fds[i].revents = 0;

    for (nfds_t i = 0; i < nfds; i++) {
      if (!fake_fds[i]) continue;
      short revents = fake_poll_revents(fake_fds[i], fds[i].events);
      fds[i].revents = revents;
      if (revents)
        ready++;
    }

    int real_timeout = ready > 0 ? 0 : [&] {
      if (timeout == 0) return 0;
      int remaining = deadline_ms < 0
                          ? backoff_ms
                          : std::min(backoff_ms, (int)(deadline_ms - monotonic_ms()));
      return std::max(remaining, 0);
    }();

    int real_ready;
    if (sigmask) {
      struct timespec wait = {real_timeout / 1000, (real_timeout % 1000) * 1000000L};
      real_ready = my_ppoll(real_fds.data(), nfds, &wait, sigmask);
    } else {
      real_ready = my_poll(real_fds.data(), nfds, real_timeout);
    }
    if (real_ready < 0) return -1;
    if (real_ready > 0) {
      for (nfds_t i = 0; i < nfds; i++) {
        if (!fake_fds[i]) {
          fds[i].revents = real_fds[i].revents;
          if (fds[i].revents)
            ready++;
        }
      }
    }

    if (ready == 0 && real_timeout > 0) {
      for (nfds_t i = 0; i < nfds; i++) {
        if (!fake_fds[i]) continue;
        short revents = fake_poll_revents(fake_fds[i], fds[i].events);
        fds[i].revents = revents;
        if (revents)
          ready++;
      }
    }

    if (ready > 0)
      return ready;

    if (timeout == 0)
      return 0;

    if (deadline_ms >= 0 && monotonic_ms() >= deadline_ms)
      return 0;

    if (backoff_ms < kMaxRingWaitMs && !low_latency(LATENCY_CONTROLLER))
      backoff_ms *= 2;
  }
}

EXPORT int poll(struct pollfd *fds, nfds_t nfds, int timeout) {
  return poll_fake(fds, nfds, timeout, nullptr);
}

EXPORT int ppoll(struct pollfd *fds, nfds_t nfds,
                 const struct timespec *timeout, const sigset_t *sigmask) {
  static auto my_ppoll = reinterpret_cast<decltype(&::ppoll)>(dlsym(RTLD_NEXT, "ppoll"));
  if (timeout && (timeout->tv_sec < 0 || timeout->tv_nsec < 0 || timeout->tv_nsec >= 1000000000L)) {
    errno = EINVAL;
    return -1;
  }
  for (nfds_t i = 0; i < nfds; ++i) {
    if (!is_fake_input_fd(fds[i].fd)) continue;
    if (!sigmask)
      return poll_fake(fds, nfds, static_cast<int>(timespec_to_ms(timeout)), nullptr);
    sigset_t all_signals, original_mask;
    sigfillset(&all_signals);
    int error = pthread_sigmask(SIG_SETMASK, &all_signals, &original_mask);
    if (error) {
      errno = error;
      return -1;
    }
    int result = poll_fake(fds, nfds, static_cast<int>(timespec_to_ms(timeout)), sigmask);
    int saved_errno = errno;
    pthread_sigmask(SIG_SETMASK, &original_mask, nullptr);
    errno = saved_errno;
    return result;
  }
  return my_ppoll(fds, nfds, timeout, sigmask);
}

EXPORT int select(int nfds, fd_set *readfds, fd_set *writefds,
                  fd_set *exceptfds, struct timeval *timeout) {
  static auto my_select = reinterpret_cast<decltype(&::select)>(dlsym(RTLD_NEXT, "select"));

  fd_set original_readfds;
  fd_set original_writefds;
  fd_set original_exceptfds;
  fd_set real_readfds;
  fd_set real_writefds;
  fd_set real_exceptfds;
  bool has_fake_fds = false;

  if (readfds) {
    original_readfds = *readfds;
    real_readfds = *readfds;
  } else {
    FD_ZERO(&original_readfds);
    FD_ZERO(&real_readfds);
  }
  if (writefds) {
    original_writefds = *writefds;
    real_writefds = *writefds;
  } else {
    FD_ZERO(&original_writefds);
    FD_ZERO(&real_writefds);
  }
  if (exceptfds) {
    original_exceptfds = *exceptfds;
    real_exceptfds = *exceptfds;
  } else {
    FD_ZERO(&original_exceptfds);
    FD_ZERO(&real_exceptfds);
  }

  for (int fd = 0; fd < nfds; fd++) {
    if (!is_fake_input_fd(fd))
      continue;
    has_fake_fds = true;
    FD_CLR(fd, &real_readfds);
    FD_CLR(fd, &real_writefds);
    FD_CLR(fd, &real_exceptfds);
  }

  if (!has_fake_fds)
    return my_select ? my_select(nfds, readfds, writefds, exceptfds, timeout)
                     : -1;

  const long long timeout_ms = timeval_to_ms(timeout);
  const long long deadline_ms =
      timeout_ms < 0 ? -1 : monotonic_ms() + timeout_ms;
  int backoff_ms = 1;

  while (true) {
    int ready = 0;

    if (readfds)
      FD_ZERO(readfds);
    if (writefds)
      FD_ZERO(writefds);
    if (exceptfds)
      FD_ZERO(exceptfds);

    for (int fd = 0; fd < nfds; fd++) {
      if (!is_fake_input_fd(fd))
        continue;
      if (readfds && FD_ISSET(fd, &original_readfds) && fake_fd_is_stale(fd)) {
        FD_SET(fd, readfds);
        ready++;
      } else if (readfds && FD_ISSET(fd, &original_readfds) &&
                 fake_fd_has_unread_data(fd)) {
        FD_SET(fd, readfds);
        ready++;
      }
    }

    int wait_ms = ready > 0 ? 0 : [&] {
      if (timeout_ms == 0) return 0;
      int remaining = deadline_ms < 0
                          ? backoff_ms
                          : std::min(backoff_ms, (int)(deadline_ms - monotonic_ms()));
      return std::max(remaining, 0);
    }();
    struct timeval wait_tv = {wait_ms / 1000, (wait_ms % 1000) * 1000};

    fd_set iter_readfds = real_readfds;
    fd_set iter_writefds = real_writefds;
    fd_set iter_exceptfds = real_exceptfds;

    int real_ready =
        my_select
            ? my_select(nfds, readfds ? &iter_readfds : nullptr,
                        writefds ? &iter_writefds : nullptr,
                        exceptfds ? &iter_exceptfds : nullptr, &wait_tv)
            : 0;

    if (real_ready < 0) return -1;
    if (real_ready > 0) {
      for (int fd = 0; fd < nfds; fd++) {
        if (readfds && FD_ISSET(fd, &iter_readfds)) {
          FD_SET(fd, readfds);
          ready++;
        }
        if (writefds && FD_ISSET(fd, &iter_writefds)) {
          FD_SET(fd, writefds);
          ready++;
        }
        if (exceptfds && FD_ISSET(fd, &iter_exceptfds)) {
          FD_SET(fd, exceptfds);
          ready++;
        }
      }
    }

    if (ready == 0 && wait_ms > 0) {
      for (int fd = 0; fd < nfds; fd++) {
        if (!is_fake_input_fd(fd))
          continue;
        if (readfds && FD_ISSET(fd, &original_readfds) && fake_fd_is_stale(fd)) {
          FD_SET(fd, readfds);
          ready++;
        } else if (readfds && FD_ISSET(fd, &original_readfds) &&
                   fake_fd_has_unread_data(fd)) {
          FD_SET(fd, readfds);
          ready++;
        }
      }
    }

    if (ready > 0)
      return ready;

    if (timeout_ms == 0)
      return 0;

    if (deadline_ms >= 0 && monotonic_ms() >= deadline_ms)
      return 0;

    if (backoff_ms < kMaxRingWaitMs && !low_latency(LATENCY_CONTROLLER))
      backoff_ms *= 2;
  }
}

#ifdef __GLIBC__
static mode_t open_mode(int flags, va_list va) {
  return (flags & (O_CREAT | O_TMPFILE)) ? va_arg(va, mode_t) : 0;
}

EXPORT int open64(const char *pathname, int flags, ...) {
  va_list va;
  va_start(va, flags);
  mode_t mode = open_mode(flags, va);
  va_end(va);
  return open(pathname, flags, mode);
}

EXPORT int openat64(int dirfd, const char *pathname, int flags, ...) {
  va_list va;
  va_start(va, flags);
  mode_t mode = open_mode(flags, va);
  va_end(va);
  return openat(dirfd, pathname, flags, mode);
}

static int real_stat64(const char *pathname, struct stat64 *statbuf) {
  static auto fn = reinterpret_cast<int (*)(const char *, struct stat64 *)>(dlsym(RTLD_NEXT, "stat64"));
  if (fn) return fn(pathname, statbuf);
#ifdef _STAT_VER
  static auto xfn = reinterpret_cast<int (*)(int, const char *, struct stat64 *)>(dlsym(RTLD_NEXT, "__xstat64"));
  if (xfn) return xfn(_STAT_VER, pathname, statbuf);
#endif
  errno = ENOSYS;
  return -1;
}

static int real_fstat64(int fd, struct stat64 *buf) {
  static auto fn = reinterpret_cast<int (*)(int, struct stat64 *)>(dlsym(RTLD_NEXT, "fstat64"));
  if (fn) return fn(fd, buf);
#ifdef _STAT_VER
  static auto xfn = reinterpret_cast<int (*)(int, int, struct stat64 *)>(dlsym(RTLD_NEXT, "__fxstat64"));
  if (xfn) return xfn(_STAT_VER, fd, buf);
#endif
  errno = ENOSYS;
  return -1;
}

EXPORT int stat64(const char *pathname, struct stat64 *statbuf) {
  return fake_stat_path(pathname, statbuf, real_stat64);
}

EXPORT int fstat64(int fd, struct stat64 *buf) {
  return fake_stat_fd(fd, buf, real_fstat64);
}

EXPORT int __xstat(int version, const char *pathname, struct stat *statbuf) {
  static auto real = reinterpret_cast<int (*)(int, const char *, struct stat *)>(dlsym(RTLD_NEXT, "__xstat"));
  if (!real) return stat(pathname, statbuf);
  return fake_stat_path(pathname, statbuf, [version](const char *path, struct stat *buf) { return real(version, path, buf); });
}

EXPORT int __xstat64(int version, const char *pathname, struct stat64 *statbuf) {
  static auto real = reinterpret_cast<int (*)(int, const char *, struct stat64 *)>(dlsym(RTLD_NEXT, "__xstat64"));
  if (!real) return stat64(pathname, statbuf);
  return fake_stat_path(pathname, statbuf, [version](const char *path, struct stat64 *buf) { return real(version, path, buf); });
}

EXPORT int __fxstat(int version, int fd, struct stat *buf) {
  static auto real = reinterpret_cast<int (*)(int, int, struct stat *)>(dlsym(RTLD_NEXT, "__fxstat"));
  if (!real) return fstat(fd, buf);
  return fake_stat_fd(fd, buf, [version](int f, struct stat *b) { return real(version, f, b); });
}

EXPORT int __fxstat64(int version, int fd, struct stat64 *buf) {
  static auto real = reinterpret_cast<int (*)(int, int, struct stat64 *)>(dlsym(RTLD_NEXT, "__fxstat64"));
  if (!real) return fstat64(fd, buf);
  return fake_stat_fd(fd, buf, [version](int f, struct stat64 *b) { return real(version, f, b); });
}

#if defined(__x86_64__) || defined(__i386__)
using dlsym_fn = void *(*)(void *, const char *);

static const char *const interposed_names[] = {
    "open", "open64", "openat", "openat64", "close", "read", "readv", "write", "writev", "ioctl",
    "poll", "ppoll", "select", "stat", "stat64", "fstat", "fstat64", "__xstat", "__xstat64",
    "__fxstat", "__fxstat64", "statx", "statfs", "statfs64", "fstatfs", "fstatfs64", "access",
    "faccessat", "opendir", "scandir", "inotify_add_watch",
};

static dlsym_fn next_dlsym() {
  static const dlsym_fn fn = [] {
#ifdef __x86_64__
    const char *base = "GLIBC_2.2.5";
#else
    const char *base = "GLIBC_2.0";
#endif
    for (const char *version : {base, "GLIBC_2.34"}) {
      if (auto found = reinterpret_cast<dlsym_fn>(dlvsym(RTLD_NEXT, "dlsym", version))) return found;
    }
    return dlsym_fn{};
  }();
  return fn;
}

static void *global_dlsym(void *, const char *name) { return next_dlsym()(RTLD_DEFAULT, name); }

static void *missing_dlsym(void *, const char *) { return nullptr; }

static bool interposed(const char *name) {
  for (const char *interposed_name : interposed_names) {
    if (strcmp(interposed_name, name) == 0) return true;
  }
  return false;
}

extern "C" __attribute__((visibility("hidden"), used)) void *fakeinput_dlsym_target(void *handle, const char *name) {
  dlsym_fn next = next_dlsym();
  if (!next) return reinterpret_cast<void *>(missing_dlsym);
  if (handle == RTLD_DEFAULT || handle == RTLD_NEXT || name == nullptr) return reinterpret_cast<void *>(next);
  static void *const libc = dlopen(LIBC_SO, RTLD_LAZY | RTLD_NOLOAD);
  if (handle != libc || !interposed(name)) return reinterpret_cast<void *>(next);
  return reinterpret_cast<void *>(global_dlsym);
}

#ifdef __x86_64__
__asm__(".text\n"
        ".globl dlsym\n"
        ".type dlsym, @function\n"
        "dlsym:\n"
        ".cfi_startproc\n"
        "pushq %rdi\n"
        ".cfi_adjust_cfa_offset 8\n"
        "pushq %rsi\n"
        ".cfi_adjust_cfa_offset 8\n"
        "subq $8, %rsp\n"
        ".cfi_adjust_cfa_offset 8\n"
        "call fakeinput_dlsym_target\n"
        "addq $8, %rsp\n"
        ".cfi_adjust_cfa_offset -8\n"
        "popq %rsi\n"
        ".cfi_adjust_cfa_offset -8\n"
        "popq %rdi\n"
        ".cfi_adjust_cfa_offset -8\n"
        "jmp *%rax\n"
        ".cfi_endproc\n"
        ".size dlsym, .-dlsym\n");
#else
__asm__(".text\n"
        ".globl dlsym\n"
        ".type dlsym, @function\n"
        "dlsym:\n"
        ".cfi_startproc\n"
        "subl $12, %esp\n"
        ".cfi_adjust_cfa_offset 12\n"
        "movl 20(%esp), %eax\n"
        "movl %eax, 4(%esp)\n"
        "movl 16(%esp), %eax\n"
        "movl %eax, (%esp)\n"
        "call fakeinput_dlsym_target\n"
        "addl $12, %esp\n"
        ".cfi_adjust_cfa_offset -12\n"
        "jmp *%eax\n"
        ".cfi_endproc\n"
        ".size dlsym, .-dlsym\n");
#endif
#endif
#endif
