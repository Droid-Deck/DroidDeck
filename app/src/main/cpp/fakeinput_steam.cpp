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

#include <dirent.h>
#include <dlfcn.h>
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
static constexpr uint32_t FAKE_INPUT_EVENT_SIZE = sizeof(struct input_event);
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

__attribute__((visibility("hidden"))) static void
send_vibration(int strong, int weak, uint16_t duration_ms, uint16_t slot) {
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

__attribute__((visibility("hidden"))) static void
check_ff_event(const struct input_event *ev, uint16_t slot) {
  if (ev->type != EV_FF)
    return;

  int id = ev->code;
  if (ev->value > 0) {
    auto it = ff_effects().find(id);
    if (it == ff_effects().end())
      return;

    uint16_t duration = it->second.replay.length;
    if (it->second.type == FF_RUMBLE) {
      send_vibration(it->second.u.rumble.strong_magnitude,
                     it->second.u.rumble.weak_magnitude, duration, slot);
    } else if (it->second.type == FF_PERIODIC) {
      send_vibration(it->second.u.periodic.magnitude,
                     it->second.u.periodic.magnitude, duration, slot);
    }
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

__attribute__((visibility("hidden"))) static void flush_uinput(UinputWriter &w) {
  if (!w.published || w.pending.empty()) {
    w.pending.clear();
    return;
  }
  FakeInputRingHeader *ring = w.ring;
  auto *events = reinterpret_cast<struct input_event *>(reinterpret_cast<uint8_t *>(ring) +
                                                        FAKE_INPUT_RING_HEADER_SIZE);
  uint64_t write_seq = __atomic_load_n(&ring->write_seq, __ATOMIC_RELAXED);
  struct timeval now = {};
  gettimeofday(&now, nullptr);
  for (struct input_event ev : w.pending) {
    ev.time = now;
    events[write_seq % FAKE_INPUT_RING_CAPACITY] = ev;
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
  case _IOC_NR(UI_BEGIN_FF_UPLOAD):
  case _IOC_NR(UI_END_FF_UPLOAD):
  case _IOC_NR(UI_BEGIN_FF_ERASE):
  case _IOC_NR(UI_END_FF_ERASE):
    errno = EINVAL;
    return -1;
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
};
static_assert(offsetof(DeckImu, pad_type) == 44, "DeckImu::pad_type matches FakeInputWriter");
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
  uint32_t output_feature;
  uint32_t output_length;
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
                          PAD_TYPE_DUALSENSE = 4, PAD_TYPE_LAST = PAD_TYPE_DUALSENSE;
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
static constexpr unsigned int DS4_HIDRAW_MINOR = 20, DUALSENSE_HIDRAW_MINOR = 21;

__attribute__((visibility("hidden"))) static int sony_pad_path(const char *pathname) {
  if (!pathname || !fake_deck_enabled() || strncmp(pathname, "/dev/hidraw2", 12)) return 0;
  if (!strcmp(pathname, DS4_HIDRAW_PATH)) return live_pad_type() == PAD_TYPE_DS4 ? 4 : 0;
  if (!strcmp(pathname, DUALSENSE_HIDRAW_PATH)) return live_pad_type() == PAD_TYPE_DUALSENSE ? 5 : 0;
  return 0;
}

__attribute__((visibility("hidden"))) static bool is_triton_pad_path(const char *pathname) {
  return pathname && fake_deck_enabled() && !strcmp(pathname, TRITON_PAD_HIDRAW_PATH) &&
         live_pad_type() == PAD_TYPE_STEAM;
}

__attribute__((visibility("hidden"))) static bool is_triton_hidraw_path(const char *pathname) {
  return pathname && fake_triton_enabled() && !strcmp(pathname, TRITON_HIDRAW_PATH) && triton_connected();
}

__attribute__((visibility("hidden"))) static bool is_withdrawn_pad_path(const char *pathname) {
  if (!fake_deck_enabled() || !pathname || strncmp(pathname, "/dev/input/", 11)) return false;
  if (live_pad_type() == PAD_TYPE_XBOX) return false;
  const char *event = strrchr(pathname, '/') + 1;
  if (strncmp(event, "event", 5) && strncmp(event, "js", 2)) return false;
  int number = get_event_number(event);
  if (number < 0 || number >= UINPUT_EVENT_BASE) return false;
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
    if (send(self->peer, report, sizeof(report), MSG_DONTWAIT | MSG_NOSIGNAL) < 0 &&
        errno != EAGAIN && errno != EWOULDBLOCK)
      break;
    struct timespec interval = {0, DECK_REPORT_INTERVAL_US * 1000L};
    nanosleep(&interval, nullptr);
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
  int half = SONY_TOUCH_WIDTH / 2;
  int px = side * half + (static_cast<int>(x) + 32768) * (half - 1) / 65535;
  int py = (32767 - static_cast<int>(y)) * (height - 1) / 65535;
  at[0] = static_cast<uint8_t>(touch.id[side] | (down ? 0 : 0x80));
  at[1] = static_cast<uint8_t>(px & 0xff);
  at[2] = static_cast<uint8_t>(((px >> 8) & 0x0f) | ((py & 0x0f) << 4));
  at[3] = static_cast<uint8_t>(py >> 4);
}

__attribute__((visibility("hidden"))) static void
build_sony_report(uint8_t *report, uint8_t model, uint32_t packet, uint32_t buttons, const int32_t *axes,
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
    put16(p + 9, static_cast<int>(packet * 750));
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
    put32(p + 27, packet * 12000);
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
  uint32_t type = self->sony == 4 ? PAD_TYPE_DS4 : PAD_TYPE_DUALSENSE;
  uint32_t packet = 0;
  SnapshotState snap;
  uint32_t buttons = 0;
  int32_t axes[8] = {};
  DeckExtras extras;
  SonyTouch touch;
  while (live_pad_type() == type) {
    if (read_snapshot(self->ring, snap) && snap.generation == ring_generation(self->ring)) {
      buttons = snap.buttons;
      memcpy(axes, snap.axes, sizeof(axes));
    }
    read_deck_extras(*self, extras);
    uint8_t report[SONY_REPORT_BYTES];
    build_sony_report(report, self->sony, ++packet, buttons, axes, extras, touch);
    if (send(self->peer, report, sizeof(report), MSG_DONTWAIT | MSG_NOSIGNAL) < 0 &&
        errno != EAGAIN && errno != EWOULDBLOCK)
      break;
    struct timespec interval = {0, DECK_REPORT_INTERVAL_US * 1000L};
    nanosleep(&interval, nullptr);
  }
  syscall(SYS_close, self->peer);
  munmap(self->ring, self->mapping_size);
  return nullptr;
}

__attribute__((visibility("hidden"))) static int open_deck_hidraw(int flags, uint8_t sony = 0) {
  const char *path = sony == 4 ? DS4_HIDRAW_PATH : sony == 5 ? DUALSENSE_HIDRAW_PATH : DECK_HIDRAW_PATH;
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
  {
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    deck_map()[pair[0]] = deck;
    watch_fd(pair[0]);
  }
  pthread_t thread;
  auto *arg = new std::shared_ptr<DeckHidraw>(deck);
  if (pthread_create(&thread, nullptr, sony ? sony_report_thread : deck_report_thread, arg) != 0) {
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
              sony == 4 ? " (a DualShock 4)" : sony == 5 ? " (a DualSense)" : "");
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
      if (send(self->peer, report, length + 1, MSG_DONTWAIT | MSG_NOSIGNAL) < 0 &&
          errno != EAGAIN && errno != EWOULDBLOCK)
        break;
    }
    struct timespec interval = {0, TRITON_REPORT_INTERVAL_US * 1000L};
    nanosleep(&interval, nullptr);
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
build_triton_report(uint8_t *report, uint32_t packet, uint32_t buttons, const int32_t *axes,
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
  put32(report + 29, packet * TRITON_REPORT_INTERVAL_US);
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
  while (live_pad_type() == PAD_TYPE_STEAM) {
    if (read_snapshot(self->ring, snap) && snap.generation == ring_generation(self->ring)) {
      buttons = snap.buttons;
      memcpy(axes, snap.axes, sizeof(axes));
    }
    read_deck_extras(*self, extras);
    uint8_t report[TRITON_REPORT_ID_BYTES + TRITON_STATE_BYTES];
    report[0] = TRITON_STATE_WIRED;
    build_triton_report(report + 1, ++packet, buttons, axes, extras);
    if (send(self->peer, report, sizeof(report), MSG_DONTWAIT | MSG_NOSIGNAL) < 0 &&
        errno != EAGAIN && errno != EWOULDBLOCK)
      break;
    struct timespec interval = {0, TRITON_REPORT_INTERVAL_US * 1000L};
    nanosleep(&interval, nullptr);
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
    struct timespec interval = {0, TOUCH_REPORT_INTERVAL_US * 1000L};
    nanosleep(&interval, nullptr);
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
static constexpr const char *PAD_EVDEV_DEVPATH = "/devices/virtual/input/droiddeck0/event0";
static constexpr const char *DS4_DEVPATH = "/devices/droiddeck/usb4/4-1:1.3/0003:054C:09CC.0005/hidraw/hidraw20";
static constexpr const char *DUALSENSE_DEVPATH = "/devices/droiddeck/usb5/5-1:1.3/0003:054C:0CE6.0006/hidraw/hidraw21";
static constexpr const char *TRITON_DEVPATH = "/devices/droiddeck/bluetooth/0005:28DE:1303.0003/hidraw/hidraw18";

__attribute__((visibility("hidden"))) static int pad_type_uevent(uint32_t type, const char *action) {
  if (type == PAD_TYPE_DECK)
    return inject_uevent(action, DECK_DEVPATH, "hidraw", DECK_HIDRAW_PATH, DECK_HIDRAW_MAJOR, DECK_HIDRAW_MINOR);
  if (type == PAD_TYPE_DS4)
    return inject_uevent(action, DS4_DEVPATH, "hidraw", DS4_HIDRAW_PATH, DECK_HIDRAW_MAJOR, DS4_HIDRAW_MINOR);
  if (type == PAD_TYPE_DUALSENSE)
    return inject_uevent(action, DUALSENSE_DEVPATH, "hidraw", DUALSENSE_HIDRAW_PATH, DECK_HIDRAW_MAJOR,
                         DUALSENSE_HIDRAW_MINOR);
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
       : type == PAD_TYPE_STEAM ? TRITON_PAD_HIDRAW_MINOR : DECK_HIDRAW_MINOR;
}

__attribute__((visibility("hidden"))) static void *pad_type_thread(void *) {
  static const char *kNames[] = {"Steam Deck controller", "Steam Controller", "Xbox 360 controller",
                                 "DualShock 4", "DualSense"};
  uint32_t was = live_pad_type();
  bool paired = fake_triton_enabled() && triton_connected();
  Logger::log("pad: a %s at start%s\n", kNames[was % 5], paired ? ", the paired Steam Controller connected" : "");
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
    break;
  }
  case DECK_GET_STRING_ATTRIBUTE:
    reply[1] = DECK_GET_STRING_ATTRIBUTE;
    reply[2] = 0x14;
    reply[3] = 0x01;
    memcpy(reply + 4, DECK_SERIAL, strlen(DECK_SERIAL));
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
  if ((pad.sony == 4 && id == 0x02) || (pad.sony == 5 && id == 0x05)) {
    put_sony_calibration(reply);
  } else if ((pad.sony == 4 && (id == 0x12 || id == 0x81)) || (pad.sony == 5 && id == 0x09)) {
    for (int i = 0; i < 6; i++) reply[1 + i] = SONY_MAC[5 - i];
  } else if (pad.sony == 5 && id == 0x20) {
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
  int weak, strong;
  if (pad.sony == 4 && data[0] == 0x05) {
    if (!(data[1] & 0x01)) return;
    weak = data[4];
    strong = data[5];
  } else if (pad.sony == 5 && data[0] == 0x02) {
    if (!(data[1] & 0x03)) return;
    weak = data[3];
    strong = data[4];
  } else {
    return;
  }
  send_vibration(strong * 257, weak * 257, strong || weak ? 2000 : 0, 0);
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
    } info = {BUS_USB, static_cast<int16_t>(0x054c), static_cast<int16_t>(pad.sony == 4 ? 0x09cc : 0x0ce6)};
    memcpy(argp, &info, sizeof(info));
    return 0;
  }
  case 0x04:
    return copy_ioctl_string(op, argp, pad.sony == 4 ? "Sony Interactive Entertainment Wireless Controller"
                                                     : "Sony Interactive Entertainment DualSense Wireless Controller");
  case 0x05: return copy_ioctl_string(op, argp, pad.sony == 4 ? "usb-droiddeck-4/input3" : "usb-droiddeck-5/input3");
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
    } info = {BUS_USB, static_cast<int16_t>(0x28de), static_cast<int16_t>(0x1205)};
    memcpy(argp, &info, sizeof(info));
    return 0;
  }
  case 0x04: return copy_ioctl_string(op, argp, DECK_NAME);
  case 0x05: return copy_ioctl_string(op, argp, "usb-droiddeck-1/input2");
  case 0x08: return copy_ioctl_string(op, argp, DECK_SERIAL);
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
  uint64_t seq = __atomic_load_n(&shared->output_seq, __ATOMIC_RELAXED);
  if (seq & 1) seq++;
  __atomic_store_n(&shared->output_seq, seq + 1, __ATOMIC_RELEASE);
  shared->output_feature = feature ? 1 : 0;
  shared->output_length = static_cast<uint32_t>(size);
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
    const uint32_t values[] = {static_cast<uint32_t>(TRITON_WIRED_PRODUCT), 0, TRITON_FW_TIMESTAMP, TRITON_HWID, 0, 4000};
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
  if (is_deck_hidraw_path(pathname)) return open_deck_hidraw(flags);
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
  if (is_deck_hidraw_path(pathname)) return open_deck_hidraw(flags);
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
        sony_pad_path(pathname)) && !process_is_steam_client())) {
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
  if (int sony = sony_pad_path(pathname)) {
    memset(statbuf, 0, sizeof(*statbuf));
    statbuf->st_mode = S_IFCHR | 0666;
    statbuf->st_rdev = makedev(DECK_HIDRAW_MAJOR, sony == 4 ? DS4_HIDRAW_MINOR : DUALSENSE_HIDRAW_MINOR);
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
    unsigned int minor = device.touch ? TOUCH_HIDRAW_MINOR
                       : device.sony == 4 ? DS4_HIDRAW_MINOR
                       : device.sony == 5 ? DUALSENSE_HIDRAW_MINOR
                       : device.emulated ? TRITON_PAD_HIDRAW_MINOR
                       : device.triton ? TRITON_HIDRAW_MINOR : DECK_HIDRAW_MINOR;
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
  if ((is_triton_hidraw_path(pathname) || is_triton_pad_path(pathname) || sony_pad_path(pathname)) &&
      process_is_steam_client())
    return 0;
  if (is_touch_hidraw_path(pathname)) return 0;
  if (is_withdrawn_pad_path(pathname) || is_deck_hidraw_path(pathname) || is_triton_hidraw_path(pathname) ||
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
  if ((is_triton_hidraw_path(pathname) || is_triton_pad_path(pathname) || sony_pad_path(pathname)) &&
      process_is_steam_client())
    return 0;
  if (is_touch_hidraw_path(pathname)) return 0;
  if (is_withdrawn_pad_path(pathname) || is_deck_hidraw_path(pathname) || is_triton_hidraw_path(pathname) ||
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

    uint16_t duration = effect->replay.length;
    uint16_t slot = static_cast<uint16_t>(event_number);
    if (effect->type == FF_RUMBLE) {
      send_vibration(effect->u.rumble.strong_magnitude,
                     effect->u.rumble.weak_magnitude, duration, slot);
    } else if (effect->type == FF_PERIODIC) {
      send_vibration(effect->u.periodic.magnitude, effect->u.periodic.magnitude,
                     duration, slot);
    }
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

EXPORT ssize_t read(int fd, void *buf, size_t count) {
  if (!watched(fd)) return syscall(SYS_read, fd, buf, count);
  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  auto controller = controller_map().find(fd);
  if (controller == controller_map().end()) {
    guard.unlock();
    return syscall(SYS_read, fd, buf, count);
  }
  auto handle = controller->second;
  FakeController &fake = *handle;
  if (count < FAKE_INPUT_EVENT_SIZE) {
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
      size_t requested = count / FAKE_INPUT_EVENT_SIZE;
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
          for (size_t i = 0; i < events; ++i) {
            size_t index = (fake.read_seq + i) % FAKE_INPUT_RING_CAPACITY;
            struct input_event ev;
            memcpy(&ev, ring_events + index * FAKE_INPUT_EVENT_SIZE, FAKE_INPUT_EVENT_SIZE);
            if (ev.type == EV_ABS) ev.code = presented_abs_code(fake, ev.code);
            memcpy(static_cast<uint8_t *>(buf) + i * FAKE_INPUT_EVENT_SIZE, &ev,
                   FAKE_INPUT_EVENT_SIZE);
          }
          __atomic_thread_fence(__ATOMIC_ACQUIRE);
          if (snap.sequence != __atomic_load_n(&fake.ring->snapshot_seq, __ATOMIC_RELAXED))
            continue;
          fake.read_seq += events;
          return static_cast<ssize_t>(events * FAKE_INPUT_EVENT_SIZE);
        }
      }

      size_t events = std::min(requested, fake.keyframe_remaining);
      struct timeval now = {};
      gettimeofday(&now, nullptr);
      for (size_t i = 0; i < events; ++i) {
        size_t index = kNeutralEventCount - fake.keyframe_remaining;
        struct input_event ev = {};
        ev.time = now;
        ev.type = kNeutralEvents[index].type;
        ev.code = kNeutralEvents[index].code;
        ev.value = keyframe_value(fake, ev.type, ev.code);
        if (ev.type == EV_ABS) ev.code = presented_abs_code(fake, ev.code);
        memcpy(static_cast<uint8_t *>(buf) + i * FAKE_INPUT_EVENT_SIZE,
               &ev, FAKE_INPUT_EVENT_SIZE);
        --fake.keyframe_remaining;
      }
      return static_cast<ssize_t>(events * FAKE_INPUT_EVENT_SIZE);
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
    if (backoff_ns < kMaxRingWaitMs * 1000 * 1000) backoff_ns *= 2;
  }
}

EXPORT ssize_t write(int fd, const void *buf, size_t count) {
  static auto my_write = reinterpret_cast<decltype(&::write)>(dlsym(RTLD_NEXT, "write"));

  if (!watched(fd)) return my_write(fd, buf, count);
  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  auto made = uinput_map().find(fd);
  if (made != uinput_map().end()) return write_uinput(*made->second, buf, count);
  if (deck_map().count(fd)) {
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

    if (backoff_ms < kMaxRingWaitMs)
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

    if (backoff_ms < kMaxRingWaitMs)
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
#endif
