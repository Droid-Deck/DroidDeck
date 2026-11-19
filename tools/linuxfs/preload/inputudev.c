/*
 * Ported from WinNative (maxjivi05, feature/wayland-gamescope, 2026-09-18/19), GPL-3.0.
 */

#define _GNU_SOURCE
#include <dlfcn.h>
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

struct pad {
  char syspath[64];
  char devnode[32];
  char sysname[16];
  char uevent[256];
  dev_t devnum;
  void *udev;
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
static void sync_parent(int node) {
  struct pad *parent = &parents[node];

  snprintf(parent->syspath, sizeof(parent->syspath), "/sys/devices/droiddeck%d/input%d", node, node);
  snprintf(parent->sysname, sizeof(parent->sysname), "input%d", node);
  memcpy(parent->uevent, pads[node].uevent, sizeof(parent->uevent));
  parent->devnum = makedev(0, 0);
  parent->udev = pads[node].udev;
  parent->node = node;
  parent->parent = 1;
  parent->live = pads[node].live;
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
        snprintf(pad->syspath, sizeof(pad->syspath), "/sys/devices/virtual/input/droiddeck%ld",
                 slot);
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

static void refresh_pad(int node) {
  struct pad *pad;

  if (node < 0 || node >= PAD_MAX) return;
  pad = &pads[node];
  pthread_mutex_lock(&virtual_lock);
  if (node >= UINPUT_BASE) {
    char uevent[sizeof(pad->uevent)];

    if (read_virtual_uevent(node, uevent, sizeof(uevent))) {
      snprintf(pad->sysname, sizeof(pad->sysname), "event%d", node);
      snprintf(pad->syspath, sizeof(pad->syspath), "/sys/devices/virtual/input/droiddeck%d", node);
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
  pthread_mutex_unlock(&virtual_lock);
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
  if (!match_numbered(&cursor, "/input", node)) return -1;
  if (*cursor == '\0') return node;
  if (!match_numbered(&cursor, "/event", node) || *cursor != '\0') return -1;
  *child = 0;
  return node;
}

static struct pad *pad_for_syspath(const char *syspath) {
  int child = 0;
  int node = node_from_syspath(syspath, &child);

  ensure_pads();
  if (node < 0) return NULL;
  refresh_pad(node);
  if (child) return parents[node].live ? &parents[node] : NULL;
  return pads[node].devnode[0] != '\0' ? &pads[node] : NULL;
}

/* A pad that went away keeps its address, so identity cannot depend on it still being live. */
static struct pad *pad_for_device(void *device) {
  int i;

  if (device == NULL) return NULL;
  ensure_pads();
  for (i = 0; i < pad_count; i++) {
    if (&pads[i] == device) return &pads[i];
    if (&parents[i] == device) return &parents[i];
  }
  return NULL;
}

/* Each construction hands out its own handle bound to the context that built it, so one consumer
 * releasing its libudev context can never leave another consumer's device with a dangling one. */
#define HANDLE_MAX 256

struct handle {
  struct pad *pad;
  void *udev;
  struct handle *parent;
  int refs;
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

static struct handle *claim_slot(struct pad *pad, void *udev) {
  int i;

  for (i = 0; i < HANDLE_MAX; i++) {
    if (handles[i].refs == 0) {
      handles[i].refs = 1;
      handles[i].pad = pad;
      handles[i].udev = udev;
      handles[i].parent = NULL;
      return &handles[i];
    }
  }
  return NULL;
}

static void *claim_handle(struct pad *pad, void *udev) {
  struct handle *handle;

  pthread_mutex_lock(&handle_lock);
  handle = claim_slot(pad, udev);
  if (handle == NULL) pad->udev = udev;
  pthread_mutex_unlock(&handle_lock);
  return handle != NULL ? (void *)handle : (void *)pad;
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

static struct pad *device_pad(void *device) {
  struct handle *handle;
  struct pad *pad = NULL;

  if (device == NULL) return NULL;
  pthread_mutex_lock(&handle_lock);
  handle = live_handle(device);
  if (handle != NULL) pad = handle->pad;
  pthread_mutex_unlock(&handle_lock);
  return handle != NULL ? pad : pad_for_device(device);
}

static void *device_udev(void *device) {
  struct handle *handle;
  void *udev = NULL;

  pthread_mutex_lock(&handle_lock);
  handle = live_handle(device);
  if (handle != NULL) udev = handle->udev;
  pthread_mutex_unlock(&handle_lock);
  return udev;
}

/* The parent belongs to the child that handed it out and goes away with it, as in libudev. */
static void *parent_device(void *device, struct pad *pad) {
  struct handle *handle;
  struct handle *parent = NULL;

  if (pad->parent) return NULL;
  pthread_mutex_lock(&handle_lock);
  handle = live_handle(device);
  if (handle != NULL) {
    if (handle->parent == NULL) handle->parent = claim_slot(&parents[pad->node], handle->udev);
    parent = handle->parent;
  }
  pthread_mutex_unlock(&handle_lock);
  return parent != NULL ? (void *)parent : (void *)&parents[pad->node];
}

static struct pad *pad_for_syslink(const char *path) {
  static const char prefix[] = "/sys/class/input/";
  const char *name;
  int i;

  if (path == NULL || strncmp(path, prefix, sizeof(prefix) - 1) != 0) return NULL;
  name = path + sizeof(prefix) - 1;
  ensure_pads();
  if (strncmp(name, "event", 5) == 0) {
    const char *cursor = name + 5;
    int node = parse_number(&cursor);

    if (node >= 0 && node < PAD_MAX && *cursor == '\0') {
      refresh_pad(node);
      if (pads[node].devnode[0] != '\0') return &pads[node];
    }
  }
  for (i = 0; i < pad_count; i++) {
    if (pads[i].devnode[0] != '\0' && strcmp(pads[i].sysname, name) == 0) return &pads[i];
    if (parents[i].live && strcmp(parents[i].sysname, name) == 0) return &parents[i];
  }
  return NULL;
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
  symbol = atomic_load_explicit(&udev_handle, memory_order_acquire);
  if (symbol != NULL && (symbol = forward(symbol, name)) != NULL) return symbol;
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
  struct pad *pad = pad_for_syslink(path);

  if (pad != NULL) {
    if (resolved == NULL) return strdup(pad->syspath);
    memcpy(resolved, pad->syspath, strlen(pad->syspath) + 1);
    return resolved;
  }
  if (real_realpath == NULL) real_realpath = next_symbol("realpath");
  if (real_realpath == NULL) return NULL;
  return dd_cached_realpath(path, resolved, real_realpath);
}

void *udev_device_new_from_syspath(void *udev, const char *syspath) {
  static void *(*real_new)(void *, const char *);
  struct pad *pad = pad_for_syspath(syspath);

  if (pad != NULL) {
    sync_parent(pad->node);
    return claim_handle(pad, udev);
  }
  if (real_new == NULL) real_new = next_symbol("udev_device_new_from_syspath");
  if (real_new == NULL) return NULL;
  return real_new(udev, syspath);
}

void *udev_device_ref(void *device) {
  static void *(*real_ref)(void *);

  if (retain_handle(device) || device_pad(device) != NULL) return device;
  if (real_ref == NULL) real_ref = next_symbol("udev_device_ref");
  if (real_ref == NULL) return NULL;
  return real_ref(device);
}

void *udev_device_unref(void *device) {
  static void *(*real_unref)(void *);

  if (release_handle(device) || device_pad(device) != NULL) return NULL;
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
  struct pad *pad = device_pad(device);

  if (pad != NULL) {
    void *udev = device_udev(device);
    return udev != NULL ? udev : pad->udev;
  }
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

  if (pad != NULL) {
    if (key == NULL) return NULL;
    if (strcmp(key, "ID_INPUT") == 0 || strcmp(key, "ID_INPUT_JOYSTICK") == 0) return "1";
    if (strcmp(key, "DEVNAME") == 0 && !pad->parent && pad->devnode[0] != '\0')
      return pad->devnode;
    if (strcmp(key, "DEVPATH") == 0) return pad->syspath + 4;
    return NULL;
  }
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

  if (device_pad(device) != NULL) return NULL;
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
  int node;

  ensure_pads();
  for (node = 0; node < PAD_MAX; node++) {
    refresh_pad(node);
    if (pads[node].devnode[0] == '\0' || pads[node].devnum != devnum) continue;
    sync_parent(node);
    return claim_handle(&pads[node], udev);
  }
  if (real_new == NULL) real_new = next_symbol("udev_device_new_from_devnum");
  return real_new != NULL ? real_new(udev, type, devnum) : NULL;
}

void *udev_device_new_from_subsystem_sysname(void *udev, const char *subsystem, const char *sysname) {
  static void *(*real_new)(void *, const char *, const char *);
  int node;

  ensure_pads();
  if (subsystem != NULL && sysname != NULL && strcmp(subsystem, "input") == 0) {
    if (strncmp(sysname, "event", 5) == 0) {
      const char *cursor = sysname + 5;

      node = parse_number(&cursor);
      if (node >= 0 && node < PAD_MAX && *cursor == '\0') {
        refresh_pad(node);
        if (pads[node].devnode[0] != '\0') {
          sync_parent(node);
          return claim_handle(&pads[node], udev);
        }
      }
    }
    if (strncmp(sysname, "input", 5) == 0) {
      const char *cursor = sysname + 5;

      node = parse_number(&cursor);
      if (node >= 0 && node < PAD_MAX && *cursor == '\0') {
        refresh_pad(node);
        if (parents[node].live) {
          sync_parent(node);
          return claim_handle(&parents[node], udev);
        }
      }
    }
  }
  if (real_new == NULL) real_new = next_symbol("udev_device_new_from_subsystem_sysname");
  return real_new != NULL ? real_new(udev, subsystem, sysname) : NULL;
}

struct enumerate {
  void *real;
  void *real_head;
  int matched;
  int scanned;
  int refs;
  unsigned seq;
  char entries[PAD_MAX];
};

#define ENUMERATE_MAX 16

static struct enumerate enumerates[ENUMERATE_MAX];
static unsigned enumerate_seq;

static struct enumerate *entry_state(void *entry, int *node) {
  uintptr_t offset = (uintptr_t)entry - (uintptr_t)enumerates;
  size_t within;

  if (offset >= sizeof(enumerates)) return NULL;
  within = offset % sizeof(enumerates[0]);
  if (within < offsetof(struct enumerate, entries) ||
      within >= offsetof(struct enumerate, entries) + PAD_MAX)
    return NULL;
  *node = (int)(within - offsetof(struct enumerate, entries));
  return &enumerates[offset / sizeof(enumerates[0])];
}

static void *real_tail(struct enumerate *state) {
  void *tail;

  pthread_mutex_lock(&enumerate_lock);
  tail = state->real != NULL ? state->real_head : NULL;
  pthread_mutex_unlock(&enumerate_lock);
  return tail;
}

static struct enumerate *enumerate_state(void *enumerate) {
  int i;

  pthread_mutex_lock(&enumerate_lock);
  for (i = 0; i < ENUMERATE_MAX; i++)
    if (enumerates[i].real == enumerate) {
      pthread_mutex_unlock(&enumerate_lock);
      return &enumerates[i];
    }
  pthread_mutex_unlock(&enumerate_lock);
  return NULL;
}

/* A caller that walks more than ENUMERATE_MAX enumerates without unrefing would silently lose the
   pads, so the oldest slot is recycled instead of the new state being dropped. */
static struct enumerate *enumerate_claim(void) {
  struct enumerate *oldest = &enumerates[0];
  int i;

  pthread_mutex_lock(&enumerate_lock);
  for (i = 0; i < ENUMERATE_MAX; i++) {
    if (enumerates[i].real == NULL) {
      pthread_mutex_unlock(&enumerate_lock);
      return &enumerates[i];
    }
    if (enumerates[i].seq < oldest->seq) oldest = &enumerates[i];
  }
  pthread_mutex_unlock(&enumerate_lock);
  return oldest;
}

void *udev_enumerate_new(void *udev) {
  static void *(*real_new)(void *);
  void *enumerate;
  struct enumerate *state;

  if (real_new == NULL) real_new = next_symbol("udev_enumerate_new");
  if (real_new == NULL) return NULL;
  enumerate = real_new(udev);
  if (enumerate == NULL) return NULL;
  state = enumerate_claim();
  pthread_mutex_lock(&enumerate_lock);
  state->real = enumerate;
  state->real_head = NULL;
  state->matched = 0;
  state->scanned = 0;
  state->refs = 1;
  state->seq = ++enumerate_seq;
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
  int last = 1;

  if (state != NULL) {
    pthread_mutex_lock(&enumerate_lock);
    last = --state->refs <= 0;
    if (last) {
      state->real = NULL;
      state->matched = 0;
      state->scanned = 0;
    }
    pthread_mutex_unlock(&enumerate_lock);
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
  if (rc == 0 && state != NULL && subsystem != NULL)
    state->matched = state->matched || strcmp(subsystem, "input") == 0;
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
  int rc;

  if (real_scan == NULL) real_scan = next_symbol("udev_enumerate_scan_devices");
  rc = real_scan != NULL ? real_scan(enumerate) : -1;
  if (state == NULL) return rc;
  pthread_mutex_lock(&enumerate_lock);
  state->scanned = 1;
  state->real_head = rc == 0 ? real_get_list_entry(enumerate) : NULL;
  pthread_mutex_unlock(&enumerate_lock);
  /* Both browsers abandon the enumeration when the scan reports an error, and the real scan always
     fails here because Android hides /sys/class from readdir. */
  if (state->matched && rc < 0) rc = 0;
  return rc;
}

static void *next_pad_entry(struct enumerate *state, int from) {
  int node;

  ensure_pads();
  for (node = from; node < PAD_MAX; node++) {
    refresh_pad(node);
    if (pads[node].devnode[0] != '\0') return &state->entries[node];
  }
  return real_tail(state);
}

void *udev_enumerate_get_list_entry(void *enumerate) {
  struct enumerate *state = enumerate_state(enumerate);

  if (state == NULL || !state->matched || !state->scanned) return real_get_list_entry(enumerate);
  return next_pad_entry(state, 0);
}

void *udev_list_entry_get_next(void *entry) {
  static void *(*real_get)(void *);
  int node;
  struct enumerate *state = entry_state(entry, &node);

  if (state != NULL) return next_pad_entry(state, node + 1);
  if (real_get == NULL) real_get = next_symbol("udev_list_entry_get_next");
  return real_get != NULL ? real_get(entry) : NULL;
}

void *udev_list_entry_get_by_name(void *entry, const char *name) {
  static void *(*real_get)(void *, const char *);
  int node;
  struct enumerate *state = entry_state(entry, &node);

  if (state != NULL) {
    if (name == NULL) return NULL;
    for (node = 0; node < PAD_MAX; node++)
      if (pads[node].devnode[0] != '\0' && strcmp(pads[node].syspath, name) == 0)
        return &state->entries[node];
    if ((entry = real_tail(state)) == NULL) return NULL;
  }
  if (real_get == NULL) real_get = next_symbol("udev_list_entry_get_by_name");
  return real_get != NULL ? real_get(entry, name) : NULL;
}

const char *udev_list_entry_get_name(void *entry) {
  static const char *(*real_get)(void *);
  int node;

  if (entry_state(entry, &node) != NULL) return pads[node].syspath;
  if (real_get == NULL) real_get = next_symbol("udev_list_entry_get_name");
  return real_get != NULL ? real_get(entry) : NULL;
}

const char *udev_list_entry_get_value(void *entry) {
  static const char *(*real_get)(void *);
  int node;

  if (entry_state(entry, &node) != NULL) return "";
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

/* The Steam client reads the pads straight from /dev/input and hands whatever the udev enumeration
 * gives it back to the real libudev, which cannot read these synthetic devices. */
static int steam_client_process(void) {
  static int cached = -1;
  char path[PATH_MAX];
  ssize_t length;

  if (cached >= 0) return cached;
  cached = 0;
  length = readlink("/proc/self/exe", path, sizeof(path) - 1);
  if (length > 0) {
    path[length] = '\0';
    cached = strstr(path, "steamrtarm64/steam") != NULL || strstr(path, "steamrt64/steam") != NULL;
  }
  return cached;
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
