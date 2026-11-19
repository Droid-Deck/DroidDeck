/*
 * Ported from WinNative (maxjivi05, feature/wayland-gamescope, 2026-09-18/19), GPL-3.0.
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <linux/input.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <unistd.h>

#define RING_MAX 4
#define UINPUT_BASE 16
#define PAD_MAX 64
#define EVENT_MINOR_BASE 64

#define PAD_UEVENT_FORMAT                                                                          \
  "PRODUCT=6/28de/11ff/110\n"                                                                      \
  "NAME=\"Generic HID Gamepad %d\"\n"                                                              \
  "PHYS=\"usb-fakeinput/input%d\"\n"

#define PAD_PHYSICAL_UEVENT_FORMAT                                                                 \
  "PRODUCT=3/45e/28e/110\n"                                                                        \
  "NAME=\"Xbox 360 Controller (%d)\"\n"                                                            \
  "PHYS=\"usb-fakeinput/input%d\"\n"

#define PARENT_NAME "droiddeck-input"
#define PARENT_SYSPATH_FORMAT "/sys/devices/droiddeck%d/" PARENT_NAME "%d"
#define EVENT_SYSPATH_FORMAT PARENT_SYSPATH_FORMAT "/event%d"

struct pad {
  char syspath[80];
  char devnode[32];
  char sysname[24];
  char uevent[256];
  dev_t devnum;
  int node;
  int parent;
  int live;
};

static struct pad pads[PAD_MAX];
static struct pad parents[PAD_MAX];
static int pad_count;
static pthread_mutex_t virtual_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t enumerate_lock = PTHREAD_MUTEX_INITIALIZER;

/* The event device's parent has to answer as a separate device so that its syspath differs from
 * the child's: consumers compare parent syspaths to tell gamepads apart. */
static void make_parent(int node, const struct pad *pad, struct pad *parent) {
  snprintf(parent->syspath, sizeof(parent->syspath), PARENT_SYSPATH_FORMAT, node, node);
  snprintf(parent->sysname, sizeof(parent->sysname), PARENT_NAME "%d", node);
  parent->devnode[0] = '\0';
  memcpy(parent->uevent, pad->uevent, sizeof(parent->uevent));
  parent->devnum = makedev(0, 0);
  parent->node = node;
  parent->parent = 1;
  parent->live = pad->live;
}

static void sync_parent(int node) {
  make_parent(node, &pads[node], &parents[node]);
}

static void discover_pads(void) {
  const char *spec = getenv("FAKE_EVDEV_MEMFD_PATHS");
  const char *entry;

  if (spec == NULL) return;
  for (entry = spec; *entry != '\0';) {
    char *end;
    long slot = strtol(entry, &end, 10);

    if (end != entry && *end == '=' && slot >= 0 && slot < RING_MAX) {
      struct pad *pad = &pads[slot];

      if (pad->devnode[0] == '\0') {
        snprintf(pad->sysname, sizeof(pad->sysname), "event%ld", slot);
        snprintf(pad->syspath, sizeof(pad->syspath), EVENT_SYSPATH_FORMAT, (int)slot, (int)slot,
                 (int)slot);
        snprintf(pad->devnode, sizeof(pad->devnode), "/dev/input/event%ld", slot);
        snprintf(pad->uevent, sizeof(pad->uevent), PAD_UEVENT_FORMAT, (int)slot, (int)slot);
        pad->devnum = makedev(13, EVENT_MINOR_BASE + (unsigned int)slot);
        pad->node = (int)slot;
        pad->live = 1;
        if (slot >= pad_count) pad_count = (int)slot + 1;
        sync_parent((int)slot);
      }
    }
    entry = strchr(entry, ';');
    if (entry == NULL) break;
    entry++;
  }
}

static void ensure_pads(void) {
  static pthread_once_t once = PTHREAD_ONCE_INIT;
  pthread_once(&once, discover_pads);
}

static int uinput_enabled(void) {
  const char *enabled = getenv("FAKE_EVDEV_UINPUT");
  return enabled != NULL && atoi(enabled) != 0;
}

static int read_virtual_uevent(int node, char *buf, size_t size) {
  const char *dir = getenv("FAKE_EVDEV_DIR");
  char path[PATH_MAX];
  FILE *file;
  size_t length;

  if (dir == NULL) return 0;
  snprintf(path, sizeof(path), "%s/.uinput-event%d.uevent", dir, node);
  if ((file = fopen(path, "re")) == NULL) return 0;
  length = fread(buf, 1, size - 1, file);
  fclose(file);
  buf[length] = '\0';
  return length > 0;
}

static int any_virtual_pad(void) {
  char uevent[256];
  int node;

  for (node = UINPUT_BASE; node < PAD_MAX; node++)
    if (read_virtual_uevent(node, uevent, sizeof(uevent))) return 1;
  return 0;
}

static void refresh_locked(int node) {
  struct pad *pad = &pads[node];

  if (node >= UINPUT_BASE) {
    char uevent[sizeof(pad->uevent)];

    if (read_virtual_uevent(node, uevent, sizeof(uevent))) {
      snprintf(pad->sysname, sizeof(pad->sysname), "event%d", node);
      snprintf(pad->syspath, sizeof(pad->syspath), EVENT_SYSPATH_FORMAT, node, node, node);
      memcpy(pad->uevent, uevent, sizeof(uevent));
      pad->devnum = makedev(13, EVENT_MINOR_BASE + (unsigned int)node);
      snprintf(pad->devnode, sizeof(pad->devnode), "/dev/input/event%d", node);
      pad->node = node;
      pad->live = 1;
      if (node >= pad_count) pad_count = node + 1;
    } else {
      pad->devnode[0] = '\0';
      pad->live = 0;
    }
  } else if (pad->sysname[0] != '\0') {
    char path[PATH_MAX];
    const char *dir = getenv("FAKE_EVDEV_DIR");

    if (dir == NULL || snprintf(path, sizeof(path), "%s/event%d", dir, node) >= (int)sizeof(path) ||
        access(path, F_OK) != 0) {
      pad->devnode[0] = '\0';
      pad->live = 0;
    } else {
      snprintf(pad->devnode, sizeof(pad->devnode), "/dev/input/event%d", node);
      pad->live = 1;
      if (uinput_enabled())
        snprintf(pad->uevent, sizeof(pad->uevent),
                 any_virtual_pad() ? PAD_PHYSICAL_UEVENT_FORMAT : PAD_UEVENT_FORMAT, node, node);
    }
  }
  sync_parent(node);
}

static int snapshot_pad(int node, int parent, struct pad *out) {
  int found;

  if (node < 0 || node >= PAD_MAX) return 0;
  ensure_pads();
  pthread_mutex_lock(&virtual_lock);
  refresh_locked(node);
  found = parent ? parents[node].live : pads[node].devnode[0] != '\0';
  if (found) *out = parent ? parents[node] : pads[node];
  pthread_mutex_unlock(&virtual_lock);
  return found;
}

static int parse_number(const char **cursor) {
  int value = 0;

  if (**cursor < '0' || **cursor > '9') return -1;
  while (**cursor >= '0' && **cursor <= '9') {
    if (value < PAD_MAX) value = value * 10 + (*(*cursor) - '0');
    (*cursor)++;
  }
  return value;
}

static int match_numbered(const char **cursor, const char *literal, int node) {
  char expected[32];
  int length = snprintf(expected, sizeof(expected), "%s%d", literal, node);

  if (strncmp(*cursor, expected, (size_t)length) != 0) return 0;
  *cursor += length;
  return 1;
}

/* Returns the pad node behind one of our syspaths, -1 for anything else. */
static int node_from_syspath(const char *syspath, int *child) {
  static const char virtual_prefix[] = "/sys/devices/virtual/input/droiddeck";
  static const char child_prefix[] = "/sys/devices/droiddeck";
  const char *cursor;
  int node;

  if (syspath == NULL) return -1;
  if (strncmp(syspath, virtual_prefix, sizeof(virtual_prefix) - 1) == 0) {
    cursor = syspath + sizeof(virtual_prefix) - 1;
    *child = 0;
  } else if (strncmp(syspath, child_prefix, sizeof(child_prefix) - 1) == 0) {
    cursor = syspath + sizeof(child_prefix) - 1;
    *child = 1;
  } else {
    return -1;
  }
  node = parse_number(&cursor);
  if (node < 0 || node >= PAD_MAX) return -1;
  if (!*child) {
    if (*cursor == '\0') return node;
    return match_numbered(&cursor, "/event", node) && *cursor == '\0' ? node : -1;
  }
  if (!match_numbered(&cursor, "/" PARENT_NAME, node)) return -1;
  if (*cursor == '\0') return node;
  if (!match_numbered(&cursor, "/event", node) || *cursor != '\0') return -1;
  *child = 0;
  return node;
}

static int syspath_pad(const char *syspath, struct pad *out) {
  int child = 0;
  int node = node_from_syspath(syspath, &child);

  return node >= 0 && snapshot_pad(node, child, out);
}

static int sysname_pad(const char *sysname, struct pad *out) {
  static const char parent_name[] = PARENT_NAME;
  const char *cursor;
  int parent;
  int node;

  if (strncmp(sysname, "event", 5) == 0) {
    cursor = sysname + 5;
    parent = 0;
  } else if (strncmp(sysname, parent_name, sizeof(parent_name) - 1) == 0) {
    cursor = sysname + sizeof(parent_name) - 1;
    parent = 1;
  } else {
    return 0;
  }
  node = parse_number(&cursor);
  return node >= 0 && *cursor == '\0' && snapshot_pad(node, parent, out);
}

/* Each construction hands out its own handle bound to the context that built it, so one consumer
 * releasing its libudev context can never leave another consumer's device with a dangling one. */
#define HANDLE_MAX 256

static const char *const property_keys[] = {"DEVNAME", "DEVPATH", "ID_INPUT", "ID_INPUT_JOYSTICK"};

#define PROPERTY_COUNT (sizeof(property_keys) / sizeof(property_keys[0]))

struct handle {
  struct pad info;
  void *udev;
  struct handle *parent;
  int refs;
  char properties[PROPERTY_COUNT];
};

static struct handle handles[HANDLE_MAX];
static pthread_mutex_t handle_lock = PTHREAD_MUTEX_INITIALIZER;

static struct handle *live_handle(void *device) {
  uintptr_t offset = (uintptr_t)device - (uintptr_t)handles;
  struct handle *handle;

  if (offset >= sizeof(handles) || offset % sizeof(handles[0]) != 0) return NULL;
  handle = &handles[offset / sizeof(handles[0])];
  return handle->refs > 0 ? handle : NULL;
}

static struct handle *claim_slot(const struct pad *info, void *udev) {
  int i;

  for (i = 0; i < HANDLE_MAX; i++) {
    if (handles[i].refs == 0) {
      handles[i].refs = 1;
      handles[i].info = *info;
      handles[i].udev = udev;
      handles[i].parent = NULL;
      return &handles[i];
    }
  }
  return NULL;
}

static void *claim_handle(const struct pad *info, void *udev) {
  struct handle *handle;

  pthread_mutex_lock(&handle_lock);
  handle = claim_slot(info, udev);
  pthread_mutex_unlock(&handle_lock);
  return handle;
}

static int retain_handle(void *device) {
  struct handle *handle;

  pthread_mutex_lock(&handle_lock);
  handle = live_handle(device);
  if (handle != NULL) handle->refs++;
  pthread_mutex_unlock(&handle_lock);
  return handle != NULL;
}

static int release_handle(void *device) {
  struct handle *handle;
  int found;

  pthread_mutex_lock(&handle_lock);
  handle = live_handle(device);
  found = handle != NULL;
  while (handle != NULL && --handle->refs == 0) {
    struct handle *parent = handle->parent;

    handle->parent = NULL;
    handle = parent;
  }
  pthread_mutex_unlock(&handle_lock);
  return found;
}

static struct handle *device_handle(void *device) {
  struct handle *handle;

  if (device == NULL) return NULL;
  pthread_mutex_lock(&handle_lock);
  handle = live_handle(device);
  pthread_mutex_unlock(&handle_lock);
  return handle;
}

static struct pad *device_pad(void *device) {
  struct handle *handle = device_handle(device);

  return handle != NULL ? &handle->info : NULL;
}

/* The parent belongs to the child that handed it out and goes away with it, as in libudev. */
static void *parent_device(void *device, struct pad *pad) {
  struct handle *handle;
  struct handle *parent = NULL;
  struct pad info;

  if (pad->parent) return NULL;
  make_parent(pad->node, pad, &info);
  pthread_mutex_lock(&handle_lock);
  handle = live_handle(device);
  if (handle != NULL) {
    if (handle->parent == NULL) handle->parent = claim_slot(&info, handle->udev);
    parent = handle->parent;
  }
  pthread_mutex_unlock(&handle_lock);
  return parent;
}

static const char *pad_property(const struct pad *pad, const char *key) {
  if (key == NULL) return NULL;
  if (strcmp(key, "ID_INPUT") == 0 || strcmp(key, "ID_INPUT_JOYSTICK") == 0) return "1";
  if (strcmp(key, "DEVNAME") == 0) return !pad->parent && pad->devnode[0] != '\0' ? pad->devnode : NULL;
  if (strcmp(key, "DEVPATH") == 0) return pad->syspath + 4;
  return NULL;
}

static struct handle *property_handle(void *entry, size_t *index) {
  uintptr_t offset = (uintptr_t)entry - (uintptr_t)handles;
  size_t within;

  if (offset >= sizeof(handles)) return NULL;
  within = offset % sizeof(handles[0]);
  if (within < offsetof(struct handle, properties) ||
      within >= offsetof(struct handle, properties) + PROPERTY_COUNT)
    return NULL;
  *index = within - offsetof(struct handle, properties);
  return &handles[offset / sizeof(handles[0])];
}

static void *next_property(struct handle *handle, size_t from) {
  size_t i;

  for (i = from; i < PROPERTY_COUNT; i++)
    if (pad_property(&handle->info, property_keys[i]) != NULL) return &handle->properties[i];
  return NULL;
}

static int syslink_pad(const char *path, struct pad *out) {
  static const char prefix[] = "/sys/class/input/";

  if (path == NULL || strncmp(path, prefix, sizeof(prefix) - 1) != 0) return 0;
  return sysname_pad(path + sizeof(prefix) - 1, out);
}

static void *real_dlsym(void);

/* libudev is often opened with RTLD_LOCAL, which keeps it out of the global scope that RTLD_NEXT
 * searches, so the handle the program opened is the only way back to the real implementation. */
static _Atomic(void *) udev_handle;

__attribute__((visibility("hidden"))) void bl_inputudev_note_dlopen(const char *path, void *handle) {
  if (handle != NULL && path != NULL && strstr(path, "libudev") != NULL)
    atomic_store_explicit(&udev_handle, handle, memory_order_release);
}

static void *next_symbol(const char *name) {
  void *(*forward)(void *, const char *) = real_dlsym();
  void *symbol;

  if (forward == NULL) return NULL;
  if (strncmp(name, "udev_", 5) == 0) {
    symbol = atomic_load_explicit(&udev_handle, memory_order_acquire);
    if (symbol != NULL && (symbol = forward(symbol, name)) != NULL) return symbol;
  }
  return forward(RTLD_NEXT, name);
}

char *dd_cached_realpath(const char *path, char *resolved, char *(*real)(const char *, char *))
    __attribute__((visibility("hidden")));

static int caller_is_winebus(void *caller) {
  Dl_info info;

  if (dladdr(caller, &info) == 0 || info.dli_fname == NULL) return 0;
  return strstr(info.dli_fname, "winebus.so") != NULL;
}

__attribute__((visibility("hidden"))) int dd_winebus_rumble_ioctl(int (*real)(int, unsigned long, void *), int fd,
                                                                  unsigned long request, void *arg, void *caller,
                                                                  int *rc) {
  struct ff_effect *effect = arg;
  __u16 length;

  if (request != EVIOCSFF || effect == NULL || effect->type != FF_RUMBLE || !caller_is_winebus(caller)) return 0;
  length = effect->replay.length;
  effect->replay.length = 0;
  *rc = real(fd, request, arg);
  effect->replay.length = length;
  return 1;
}

char *realpath(const char *path, char *resolved) {
  static char *(*real_realpath)(const char *, char *);
  struct pad pad;

  if (syslink_pad(path, &pad)) {
    if (resolved == NULL) return strdup(pad.syspath);
    memcpy(resolved, pad.syspath, strlen(pad.syspath) + 1);
    return resolved;
  }
  if (real_realpath == NULL) real_realpath = next_symbol("realpath");
  if (real_realpath == NULL) return NULL;
  return dd_cached_realpath(path, resolved, real_realpath);
}

void *udev_device_new_from_syspath(void *udev, const char *syspath) {
  static void *(*real_new)(void *, const char *);
  struct pad pad;

  if (syspath_pad(syspath, &pad)) return claim_handle(&pad, udev);
  if (real_new == NULL) real_new = next_symbol("udev_device_new_from_syspath");
  if (real_new == NULL) return NULL;
  return real_new(udev, syspath);
}

void *udev_device_ref(void *device) {
  static void *(*real_ref)(void *);

  if (retain_handle(device)) return device;
  if (real_ref == NULL) real_ref = next_symbol("udev_device_ref");
  if (real_ref == NULL) return NULL;
  return real_ref(device);
}

void *udev_device_unref(void *device) {
  static void *(*real_unref)(void *);

  if (release_handle(device)) return NULL;
  if (real_unref == NULL) real_unref = next_symbol("udev_device_unref");
  if (real_unref == NULL) return NULL;
  return real_unref(device);
}

const char *udev_device_get_devnode(void *device) {
  static const char *(*real_get)(void *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) return pad->parent || pad->devnode[0] == '\0' ? NULL : pad->devnode;
  if (real_get == NULL) real_get = next_symbol("udev_device_get_devnode");
  if (real_get == NULL) return NULL;
  return real_get(device);
}

const char *udev_device_get_syspath(void *device) {
  static const char *(*real_get)(void *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) return pad->syspath;
  if (real_get == NULL) real_get = next_symbol("udev_device_get_syspath");
  if (real_get == NULL) return NULL;
  return real_get(device);
}

const char *udev_device_get_devpath(void *device) {
  static const char *(*real_get)(void *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) return pad->syspath + 4;
  if (real_get == NULL) real_get = next_symbol("udev_device_get_devpath");
  if (real_get == NULL) return NULL;
  return real_get(device);
}

const char *udev_device_get_sysname(void *device) {
  static const char *(*real_get)(void *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) return pad->sysname;
  if (real_get == NULL) real_get = next_symbol("udev_device_get_sysname");
  if (real_get == NULL) return NULL;
  return real_get(device);
}

const char *udev_device_get_sysnum(void *device) {
  static const char *(*real_get)(void *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) return pad->sysname + strcspn(pad->sysname, "0123456789");
  if (real_get == NULL) real_get = next_symbol("udev_device_get_sysnum");
  if (real_get == NULL) return NULL;
  return real_get(device);
}

const char *udev_device_get_subsystem(void *device) {
  static const char *(*real_get)(void *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) return "input";
  if (real_get == NULL) real_get = next_symbol("udev_device_get_subsystem");
  if (real_get == NULL) return NULL;
  return real_get(device);
}

const char *udev_device_get_devtype(void *device) {
  static const char *(*real_get)(void *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) return NULL;
  if (real_get == NULL) real_get = next_symbol("udev_device_get_devtype");
  if (real_get == NULL) return NULL;
  return real_get(device);
}

const char *udev_device_get_action(void *device) {
  static const char *(*real_get)(void *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) return NULL;
  if (real_get == NULL) real_get = next_symbol("udev_device_get_action");
  if (real_get == NULL) return NULL;
  return real_get(device);
}

dev_t udev_device_get_devnum(void *device) {
  static dev_t (*real_get)(void *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) return pad->devnum;
  if (real_get == NULL) real_get = next_symbol("udev_device_get_devnum");
  if (real_get == NULL) return makedev(0, 0);
  return real_get(device);
}

void *udev_device_get_udev(void *device) {
  static void *(*real_get)(void *);
  struct handle *handle = device_handle(device);

  if (handle != NULL) return handle->udev;
  if (real_get == NULL) real_get = next_symbol("udev_device_get_udev");
  return real_get != NULL ? real_get(device) : NULL;
}

int udev_device_get_is_initialized(void *device) {
  static int (*real_get)(void *);

  if (device_pad(device) != NULL) return 1;
  if (real_get == NULL) real_get = next_symbol("udev_device_get_is_initialized");
  return real_get != NULL ? real_get(device) : 0;
}

void *udev_device_get_parent_with_subsystem_devtype(void *device, const char *subsystem,
                                                    const char *devtype) {
  static void *(*real_get)(void *, const char *, const char *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) {
    if (subsystem != NULL && strcmp(subsystem, "input") == 0 && devtype == NULL)
      return parent_device(device, pad);
    return NULL;
  }
  if (real_get == NULL) real_get = next_symbol("udev_device_get_parent_with_subsystem_devtype");
  if (real_get == NULL) return NULL;
  return real_get(device, subsystem, devtype);
}

const char *udev_device_get_sysattr_value(void *device, const char *attr) {
  static const char *(*real_get)(void *, const char *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) return attr != NULL && strcmp(attr, "uevent") == 0 ? pad->uevent : NULL;
  if (real_get == NULL) real_get = next_symbol("udev_device_get_sysattr_value");
  if (real_get == NULL) return NULL;
  return real_get(device, attr);
}

const char *udev_device_get_property_value(void *device, const char *key) {
  static const char *(*real_get)(void *, const char *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) return pad_property(pad, key);
  if (real_get == NULL) real_get = next_symbol("udev_device_get_property_value");
  if (real_get == NULL) return NULL;
  return real_get(device, key);
}

void *udev_device_get_parent(void *device) {
  static void *(*real_get)(void *);
  struct pad *pad = device_pad(device);

  if (pad != NULL) return parent_device(device, pad);
  if (real_get == NULL) real_get = next_symbol("udev_device_get_parent");
  return real_get != NULL ? real_get(device) : NULL;
}

int udev_device_has_tag(void *device, const char *tag) {
  static int (*real_has)(void *, const char *);

  if (device_pad(device) != NULL) return 0;
  if (real_has == NULL) real_has = next_symbol("udev_device_has_tag");
  return real_has != NULL ? real_has(device, tag) : 0;
}

void *udev_device_get_properties_list_entry(void *device) {
  static void *(*real_get)(void *);
  struct handle *handle = device_handle(device);

  if (handle != NULL) return next_property(handle, 0);
  if (real_get == NULL) real_get = next_symbol("udev_device_get_properties_list_entry");
  return real_get != NULL ? real_get(device) : NULL;
}

void *udev_device_get_tags_list_entry(void *device) {
  static void *(*real_get)(void *);

  if (device_pad(device) != NULL) return NULL;
  if (real_get == NULL) real_get = next_symbol("udev_device_get_tags_list_entry");
  return real_get != NULL ? real_get(device) : NULL;
}

void *udev_device_get_sysattr_list_entry(void *device) {
  static void *(*real_get)(void *);

  if (device_pad(device) != NULL) return NULL;
  if (real_get == NULL) real_get = next_symbol("udev_device_get_sysattr_list_entry");
  return real_get != NULL ? real_get(device) : NULL;
}

void *udev_device_new_from_devnum(void *udev, char type, dev_t devnum) {
  static void *(*real_new)(void *, char, dev_t);
  struct pad pad;
  int node;

  for (node = 0; node < PAD_MAX; node++)
    if (snapshot_pad(node, 0, &pad) && pad.devnum == devnum) return claim_handle(&pad, udev);
  if (real_new == NULL) real_new = next_symbol("udev_device_new_from_devnum");
  return real_new != NULL ? real_new(udev, type, devnum) : NULL;
}

void *udev_device_new_from_subsystem_sysname(void *udev, const char *subsystem, const char *sysname) {
  static void *(*real_new)(void *, const char *, const char *);
  struct pad pad;

  if (subsystem != NULL && sysname != NULL && strcmp(subsystem, "input") == 0 && sysname_pad(sysname, &pad))
    return claim_handle(&pad, udev);
  if (real_new == NULL) real_new = next_symbol("udev_device_new_from_subsystem_sysname");
  return real_new != NULL ? real_new(udev, subsystem, sysname) : NULL;
}

#define ENTRY_MAX (PAD_MAX * 2)

struct enumerate {
  struct enumerate *next;
  void *real;
  void *real_head;
  int matched;
  int filtered;
  int parent_node;
  int real_parent;
  int real_listed;
  int scanned;
  int refs;
  char names[ENTRY_MAX][sizeof(pads[0].syspath)];
};

static struct enumerate *enumerates;

static struct enumerate *entry_state(void *entry, int *slot) {
  struct enumerate *state;

  pthread_mutex_lock(&enumerate_lock);
  for (state = enumerates; state != NULL; state = state->next) {
    uintptr_t offset = (uintptr_t)entry - (uintptr_t)state->names;

    if (offset < sizeof(state->names) && offset % sizeof(state->names[0]) == 0) {
      *slot = (int)(offset / sizeof(state->names[0]));
      break;
    }
  }
  pthread_mutex_unlock(&enumerate_lock);
  return state;
}

static struct enumerate *enumerate_state(void *enumerate) {
  struct enumerate *state;

  pthread_mutex_lock(&enumerate_lock);
  for (state = enumerates; state != NULL && state->real != enumerate; state = state->next) {
  }
  pthread_mutex_unlock(&enumerate_lock);
  return state;
}

void *udev_enumerate_new(void *udev) {
  static void *(*real_new)(void *);
  void *enumerate;
  struct enumerate *state;

  if (real_new == NULL) real_new = next_symbol("udev_enumerate_new");
  if (real_new == NULL) return NULL;
  enumerate = real_new(udev);
  if (enumerate == NULL) return NULL;
  state = calloc(1, sizeof(*state));
  if (state == NULL) return enumerate;
  state->real = enumerate;
  state->parent_node = -1;
  state->refs = 1;
  pthread_mutex_lock(&enumerate_lock);
  state->next = enumerates;
  enumerates = state;
  pthread_mutex_unlock(&enumerate_lock);
  return enumerate;
}

void *udev_enumerate_ref(void *enumerate) {
  static void *(*real_ref)(void *);
  struct enumerate *state = enumerate_state(enumerate);

  if (state != NULL) {
    pthread_mutex_lock(&enumerate_lock);
    state->refs++;
    pthread_mutex_unlock(&enumerate_lock);
  }
  if (real_ref == NULL) real_ref = next_symbol("udev_enumerate_ref");
  return real_ref != NULL ? real_ref(enumerate) : enumerate;
}

void *udev_enumerate_unref(void *enumerate) {
  static void *(*real_unref)(void *);
  struct enumerate *state = enumerate_state(enumerate);

  if (state != NULL) {
    pthread_mutex_lock(&enumerate_lock);
    if (--state->refs <= 0) {
      struct enumerate **link;

      for (link = &enumerates; *link != NULL && *link != state; link = &(*link)->next) {
      }
      if (*link != NULL) *link = state->next;
    } else {
      state = NULL;
    }
    pthread_mutex_unlock(&enumerate_lock);
    free(state);
  }
  if (real_unref == NULL) real_unref = next_symbol("udev_enumerate_unref");
  return real_unref != NULL ? real_unref(enumerate) : NULL;
}

int udev_enumerate_add_match_subsystem(void *enumerate, const char *subsystem) {
  static int (*real_add)(void *, const char *);
  struct enumerate *state = enumerate_state(enumerate);
  int rc;

  if (real_add == NULL) real_add = next_symbol("udev_enumerate_add_match_subsystem");
  rc = real_add != NULL ? real_add(enumerate, subsystem) : 0;
  if (rc == 0 && state != NULL && subsystem != NULL) {
    pthread_mutex_lock(&enumerate_lock);
    state->filtered = 1;
    state->matched = state->matched || strcmp(subsystem, "input") == 0;
    pthread_mutex_unlock(&enumerate_lock);
  }
  return rc;
}

int udev_enumerate_add_match_parent(void *enumerate, void *parent) {
  static int (*real_add)(void *, void *);
  struct enumerate *state = enumerate_state(enumerate);
  struct pad *pad = device_pad(parent);
  int rc;

  if (pad != NULL) {
    if (state == NULL) return -EINVAL;
    pthread_mutex_lock(&enumerate_lock);
    state->parent_node = pad->node;
    state->real_parent = 0;
    pthread_mutex_unlock(&enumerate_lock);
    return 0;
  }
  if (real_add == NULL) real_add = next_symbol("udev_enumerate_add_match_parent");
  rc = real_add != NULL ? real_add(enumerate, parent) : -ENOSYS;
  if (rc == 0 && state != NULL) {
    pthread_mutex_lock(&enumerate_lock);
    state->parent_node = -1;
    state->real_parent = 1;
    pthread_mutex_unlock(&enumerate_lock);
  }
  return rc;
}

static void *real_get_list_entry(void *enumerate) {
  static void *(*real_get)(void *);

  if (real_get == NULL) real_get = next_symbol("udev_enumerate_get_list_entry");
  return real_get != NULL ? real_get(enumerate) : NULL;
}

int udev_enumerate_scan_devices(void *enumerate) {
  static int (*real_scan)(void *);
  struct enumerate *state = enumerate_state(enumerate);
  char names[ENTRY_MAX][sizeof(pads[0].syspath)];
  int include;
  int parent_node;
  int added = 0;
  int node;
  int rc;

  if (real_scan == NULL) real_scan = next_symbol("udev_enumerate_scan_devices");
  rc = real_scan != NULL ? real_scan(enumerate) : -1;
  if (state == NULL) return rc;
  pthread_mutex_lock(&enumerate_lock);
  parent_node = state->parent_node;
  include = !state->real_parent && (state->filtered ? state->matched : parent_node >= 0);
  pthread_mutex_unlock(&enumerate_lock);
  for (node = 0; node < PAD_MAX; node++) {
    struct pad pad;

    names[2 * node][0] = '\0';
    names[2 * node + 1][0] = '\0';
    if (!include || (parent_node >= 0 && parent_node != node)) continue;
    if (parent_node >= 0 && snapshot_pad(node, 1, &pad)) {
      memcpy(names[2 * node], pad.syspath, sizeof(names[0]));
      added++;
    }
    if (snapshot_pad(node, 0, &pad)) {
      memcpy(names[2 * node + 1], pad.syspath, sizeof(names[0]));
      added++;
    }
  }
  pthread_mutex_lock(&enumerate_lock);
  memcpy(state->names, names, sizeof(names));
  state->scanned = 1;
  state->real_listed = rc == 0 && parent_node < 0;
  state->real_head = NULL;
  pthread_mutex_unlock(&enumerate_lock);
  /* Both browsers abandon the enumeration when the scan reports an error, and the real scan always
     fails here because Android hides /sys/class from readdir. */
  if (added > 0 && rc < 0) rc = 0;
  return rc;
}

static void *next_pad_entry(struct enumerate *state, int from) {
  void *entry;
  int slot;

  pthread_mutex_lock(&enumerate_lock);
  for (slot = from; slot < ENTRY_MAX && state->names[slot][0] == '\0'; slot++) {
  }
  entry = slot < ENTRY_MAX ? (void *)state->names[slot] : state->real_head;
  pthread_mutex_unlock(&enumerate_lock);
  return entry;
}

void *udev_enumerate_get_list_entry(void *enumerate) {
  struct enumerate *state = enumerate_state(enumerate);
  void *head = NULL;
  int own;
  int real_listed;

  if (state == NULL) return real_get_list_entry(enumerate);
  pthread_mutex_lock(&enumerate_lock);
  own = state->scanned && (state->matched || state->parent_node >= 0);
  real_listed = state->real_listed;
  pthread_mutex_unlock(&enumerate_lock);
  if (!own) return real_get_list_entry(enumerate);
  if (real_listed) head = real_get_list_entry(enumerate);
  pthread_mutex_lock(&enumerate_lock);
  state->real_head = head;
  pthread_mutex_unlock(&enumerate_lock);
  return next_pad_entry(state, 0);
}

void *udev_list_entry_get_next(void *entry) {
  static void *(*real_get)(void *);
  struct enumerate *state;
  struct handle *handle;
  size_t index;
  int slot;

  if ((state = entry_state(entry, &slot)) != NULL) return next_pad_entry(state, slot + 1);
  if ((handle = property_handle(entry, &index)) != NULL) return next_property(handle, index + 1);
  if (real_get == NULL) real_get = next_symbol("udev_list_entry_get_next");
  return real_get != NULL ? real_get(entry) : NULL;
}

void *udev_list_entry_get_by_name(void *entry, const char *name) {
  static void *(*real_get)(void *, const char *);
  struct enumerate *state;
  struct handle *handle;
  size_t index;
  int slot;

  if ((state = entry_state(entry, &slot)) != NULL) {
    if (name == NULL) return NULL;
    pthread_mutex_lock(&enumerate_lock);
    for (slot = 0; slot < ENTRY_MAX && (state->names[slot][0] == '\0' || strcmp(state->names[slot], name) != 0);
         slot++) {
    }
    entry = slot < ENTRY_MAX ? (void *)state->names[slot] : state->real_head;
    pthread_mutex_unlock(&enumerate_lock);
    if (entry == NULL || slot < ENTRY_MAX) return entry;
  } else if ((handle = property_handle(entry, &index)) != NULL) {
    for (index = 0; index < PROPERTY_COUNT; index++)
      if (name != NULL && strcmp(property_keys[index], name) == 0 && pad_property(&handle->info, name) != NULL)
        return &handle->properties[index];
    return NULL;
  }
  if (real_get == NULL) real_get = next_symbol("udev_list_entry_get_by_name");
  return real_get != NULL ? real_get(entry, name) : NULL;
}

const char *udev_list_entry_get_name(void *entry) {
  static const char *(*real_get)(void *);
  size_t index;
  int slot;

  if (entry_state(entry, &slot) != NULL) return entry;
  if (property_handle(entry, &index) != NULL) return property_keys[index];
  if (real_get == NULL) real_get = next_symbol("udev_list_entry_get_name");
  return real_get != NULL ? real_get(entry) : NULL;
}

const char *udev_list_entry_get_value(void *entry) {
  static const char *(*real_get)(void *);
  struct handle *handle;
  size_t index;
  int slot;

  if (entry_state(entry, &slot) != NULL) return "";
  if ((handle = property_handle(entry, &index)) != NULL) return pad_property(&handle->info, property_keys[index]);
  if (real_get == NULL) real_get = next_symbol("udev_list_entry_get_value");
  return real_get != NULL ? real_get(entry) : NULL;
}

struct udev_symbol {
  const char *name;
  void *address;
};

static const struct udev_symbol udev_symbols[] = {
    {"udev_device_new_from_syspath", (void *)udev_device_new_from_syspath},
    {"udev_device_new_from_devnum", (void *)udev_device_new_from_devnum},
    {"udev_device_new_from_subsystem_sysname", (void *)udev_device_new_from_subsystem_sysname},
    {"udev_device_ref", (void *)udev_device_ref},
    {"udev_device_unref", (void *)udev_device_unref},
    {"udev_device_get_devnode", (void *)udev_device_get_devnode},
    {"udev_device_get_syspath", (void *)udev_device_get_syspath},
    {"udev_device_get_devpath", (void *)udev_device_get_devpath},
    {"udev_device_get_sysname", (void *)udev_device_get_sysname},
    {"udev_device_get_sysnum", (void *)udev_device_get_sysnum},
    {"udev_device_get_subsystem", (void *)udev_device_get_subsystem},
    {"udev_device_get_devtype", (void *)udev_device_get_devtype},
    {"udev_device_get_action", (void *)udev_device_get_action},
    {"udev_device_get_devnum", (void *)udev_device_get_devnum},
    {"udev_device_get_sysattr_value", (void *)udev_device_get_sysattr_value},
    {"udev_device_get_property_value", (void *)udev_device_get_property_value},
    {"udev_device_get_parent_with_subsystem_devtype",
     (void *)udev_device_get_parent_with_subsystem_devtype},
    {"udev_device_get_parent", (void *)udev_device_get_parent},
    {"udev_device_get_udev", (void *)udev_device_get_udev},
    {"udev_device_get_is_initialized", (void *)udev_device_get_is_initialized},
    {"udev_device_has_tag", (void *)udev_device_has_tag},
    {"udev_device_get_properties_list_entry", (void *)udev_device_get_properties_list_entry},
    {"udev_device_get_tags_list_entry", (void *)udev_device_get_tags_list_entry},
    {"udev_device_get_sysattr_list_entry", (void *)udev_device_get_sysattr_list_entry},
    {"udev_enumerate_new", (void *)udev_enumerate_new},
    {"udev_enumerate_ref", (void *)udev_enumerate_ref},
    {"udev_enumerate_unref", (void *)udev_enumerate_unref},
    {"udev_enumerate_add_match_subsystem", (void *)udev_enumerate_add_match_subsystem},
    {"udev_enumerate_add_match_parent", (void *)udev_enumerate_add_match_parent},
    {"udev_enumerate_scan_devices", (void *)udev_enumerate_scan_devices},
    {"udev_enumerate_get_list_entry", (void *)udev_enumerate_get_list_entry},
    {"udev_list_entry_get_next", (void *)udev_list_entry_get_next},
    {"udev_list_entry_get_by_name", (void *)udev_list_entry_get_by_name},
    {"udev_list_entry_get_name", (void *)udev_list_entry_get_name},
    {"udev_list_entry_get_value", (void *)udev_list_entry_get_value},
};

static void *own_udev_symbol(const char *name) {
  size_t i;

  for (i = 0; i < sizeof(udev_symbols) / sizeof(udev_symbols[0]); i++)
    if (strcmp(udev_symbols[i].name, name) == 0) return udev_symbols[i].address;
  return NULL;
}

static void *dlvsym_any(const char *name) {
  static const char *versions[] = {"GLIBC_2.17", "GLIBC_2.2.5", "GLIBC_2.0", "GLIBC_2.34"};
  size_t i;
  void *resolved = NULL;

  for (i = 0; i < sizeof(versions) / sizeof(versions[0]) && resolved == NULL; i++)
    resolved = dlvsym(RTLD_NEXT, name, versions[i]);
  return resolved;
}

/* A versioned lookup only ever finds libc, so the next dlsym in line is asked for by plain name to
 * keep another preload's dlsym in the chain. */
static void *real_dlsym(void) {
  static void *resolved;

  if (resolved == NULL) {
    void *(*libc)(void *, const char *) = dlvsym_any("dlsym");
    void *next = libc != NULL ? libc(RTLD_NEXT, "dlsym") : NULL;

    resolved = next != NULL ? next : (void *)libc;
  }
  return resolved;
}

static int steam_runtime_path(const char *path) {
  static const char *const runtimes[] = {"steamrtarm64", "steamrt64"};
  const char *name = strrchr(path, '/');
  size_t i;

  if (name == NULL || strcmp(name + 1, "steam") != 0) return 0;
  for (i = 0; i < sizeof(runtimes) / sizeof(runtimes[0]); i++) {
    size_t length = strlen(runtimes[i]);

    if ((size_t)(name - path) > length && name[-(ptrdiff_t)length - 1] == '/' &&
        memcmp(name - length, runtimes[i], length) == 0)
      return 1;
  }
  return 0;
}

/* The Steam client reads the pads straight from /dev/input and hands whatever the udev enumeration
 * gives it back to the real libudev, which cannot read these synthetic devices. */
static int steam_client_process(void) {
  static _Atomic int cached = -1;
  char path[PATH_MAX];
  ssize_t length;
  int result = atomic_load_explicit(&cached, memory_order_acquire);

  if (result >= 0) return result;
  result = 0;
  length = readlink("/proc/self/exe", path, sizeof(path) - 1);
  if (length > 0) {
    path[length] = '\0';
    result = steam_runtime_path(path);
  }
  atomic_store_explicit(&cached, result, memory_order_release);
  return result;
}

static void *missing_dlsym(void *handle, const char *name) {
  (void)handle;
  (void)name;
  return NULL;
}

static void *own_dlsym(void *handle, const char *name) {
  (void)handle;
  return own_udev_symbol(name);
}

/* Returns what dlsym jumps to. Anything outside the udev_ names has to reach the real dlsym without
 * a single interposable call on the way, because other preloads resolve their own symbols from
 * inside one-time initialisers that those calls would re-enter. */
__attribute__((visibility("hidden"), used)) void *inputudev_dlsym_target(void *handle, const char *name) {
  void *forward = real_dlsym();

  (void)handle;
  if (name != NULL && strncmp(name, "udev_", 5) == 0 && own_udev_symbol(name) != NULL &&
      !steam_client_process())
    return (void *)own_dlsym;
  return forward != NULL ? forward : (void *)missing_dlsym;
}

/* Browsers resolve libudev through dlopen and dlsym, which skips symbol interposition, so the
 * devices have to be handed out from here as well. The real dlsym is entered with a jump so that it
 * still sees the original caller, which is what RTLD_NEXT and RTLD_DEFAULT lookups are relative to. */
#if defined(__aarch64__)
__asm__(".text\n"
        ".globl dlsym\n"
        ".type dlsym, %function\n"
        "dlsym:\n"
        ".cfi_startproc\n"
        "hint #34\n"
        "stp x29, x30, [sp, #-32]!\n"
        ".cfi_adjust_cfa_offset 32\n"
        ".cfi_offset x29, -32\n"
        ".cfi_offset x30, -24\n"
        "mov x29, sp\n"
        "stp x0, x1, [sp, #16]\n"
        "bl inputudev_dlsym_target\n"
        "mov x16, x0\n"
        "ldp x0, x1, [sp, #16]\n"
        "ldp x29, x30, [sp], #32\n"
        ".cfi_adjust_cfa_offset -32\n"
        ".cfi_restore x29\n"
        ".cfi_restore x30\n"
        "br x16\n"
        ".cfi_endproc\n"
        ".size dlsym, .-dlsym\n");
#elif defined(__x86_64__)
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
        "call inputudev_dlsym_target\n"
        "addq $8, %rsp\n"
        ".cfi_adjust_cfa_offset -8\n"
        "popq %rsi\n"
        ".cfi_adjust_cfa_offset -8\n"
        "popq %rdi\n"
        ".cfi_adjust_cfa_offset -8\n"
        "jmp *%rax\n"
        ".cfi_endproc\n"
        ".size dlsym, .-dlsym\n");
#elif defined(__i386__)
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
        "call inputudev_dlsym_target\n"
        "addl $12, %esp\n"
        ".cfi_adjust_cfa_offset -12\n"
        "jmp *%eax\n"
        ".cfi_endproc\n"
        ".size dlsym, .-dlsym\n");
#else
void *dlsym(void *handle, const char *name) {
  void *(*target)(void *, const char *) = inputudev_dlsym_target(handle, name);

  return target(handle, name);
}
#endif
