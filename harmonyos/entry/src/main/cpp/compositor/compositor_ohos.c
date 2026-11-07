/**
 * The HarmonyOS entry point for the Wayland compositor.
 *
 * Upstream DroidDeck drives the compositor through JNI
 * (`waylandcomp_jni.c`): the app starts a compositor thread, hands it the
 * output window and receives events such as the first presented frame, the
 * focused game's surface and the clipboard contents.
 *
 * The port keeps the compositor's own code untouched and replaces only the
 * JNI plumbing with Node-API, so the same compositor runs inside the
 * HarmonyOS app. Events reach ArkTS through a thread-safe function, because
 * the compositor runs on its own thread.
 */

#include <napi/native_api.h>

#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <unistd.h>

#include "ahb_swapchain.h"
#include "cardboard.h"
#include "compositor_ohos.h"
#include "compositor_internal.h"
#include "droiddeck_color.h"
#include "droiddeck_ext.h"
#include "effects_chain.h"
#include "sc_layer.h"
#include "vk_present.h"
#include <stddef.h>

// The compositor's own entry points, from compositor.c.
extern int droiddeck_wayland_run(void);
extern void droiddeck_wayland_send_pointer(int action, int x, int y);
extern void droiddeck_wayland_send_touch(int action, int pointer_id, int x, int y);
extern void droiddeck_wayland_send_key(int evdev, int state);
extern void droiddeck_wayland_output_resized(void);
extern int droiddeck_cursor_snapshot(int *out, int cap);
extern int droiddeck_hud_frame_times(float *out, int len, int64_t *total);
extern const char *vkp_fault(void);
extern const char *vkp_display_driver(void);

extern volatile int g_output_w, g_output_h;

#define TAG "DroidDeckWayland"
#define COMPOSITOR_NICE (-8)

static napi_threadsafe_function g_events = NULL;
/**
 * Guards the threadsafe function.
 *
 * The compositor thread queues events into it and the JS thread releases it
 * when the compositor is stopped, so the pointer is read and written under
 * this lock: without it the compositor thread could call into a function
 * that has already been released.
 */
static pthread_mutex_t g_events_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile pid_t g_comp_tid = 0;
static volatile int g_running = 0;

/** The payload handed to the JS thread for one compositor event. */
typedef struct {
  char name[32];
  char *detail;
} EventPayload;

/** Queues an event for the ArkTS side. */
static void EmitEvent(const char *name, const char *detail) {
  EventPayload *payload = (EventPayload *)calloc(1, sizeof(EventPayload));
  if (payload == NULL) {
    return;
  }
  snprintf(payload->name, sizeof(payload->name), "%s", name);
  if (detail != NULL) {
    payload->detail = strdup(detail);
  }
  pthread_mutex_lock(&g_events_lock);
  const napi_threadsafe_function events = g_events;
  const int queued = events != NULL &&
                     napi_call_threadsafe_function(events, payload, napi_tsfn_nonblocking) == napi_ok;
  pthread_mutex_unlock(&g_events_lock);
  if (!queued) {
    free(payload->detail);
    free(payload);
  }
}

/** Runs on the JS thread when a queued event arrives. */
static void CallJsEvent(napi_env env, napi_value js_callback, void *context, void *data) {
  (void)context;
  EventPayload *payload = (EventPayload *)data;
  if (payload == NULL) {
    return;
  }
  if (js_callback != NULL) {
    napi_value nameValue = NULL;
    napi_value detailValue = NULL;
    napi_create_string_utf8(env, payload->name, NAPI_AUTO_LENGTH, &nameValue);
    napi_create_string_utf8(env, payload->detail ? payload->detail : "", NAPI_AUTO_LENGTH, &detailValue);
    napi_value arguments[2] = {nameValue, detailValue};
    napi_value undefined = NULL;
    napi_get_undefined(env, &undefined);
    napi_value result = NULL;
    napi_call_function(env, undefined, js_callback, 2, arguments, &result);
  }
  free(payload->detail);
  free(payload);
}

static void *comp_thread(void *arg) {
  (void)arg;
  ALOGI("compositor thread starting");
  pthread_setname_np(pthread_self(), "wl-compositor");

  const id_t tid = (id_t)gettid();
  g_comp_tid = (pid_t)tid;

  errno = 0;
  const int before = getpriority(PRIO_PROCESS, tid);
  int refused = 0;
  if (before > COMPOSITOR_NICE && setpriority(PRIO_PROCESS, tid, COMPOSITOR_NICE) != 0) {
    refused = errno ? errno : -1;
  }
  const int after = getpriority(PRIO_PROCESS, tid);
  if (refused) {
    droiddeck_log("perf", "compositor thread %d \"wl-compositor\": stays at nice %d, a higher priority was refused (%s)",
                  (int)tid, after, refused > 0 ? strerror(refused) : "?");
  } else {
    droiddeck_log("perf", "compositor thread %d \"wl-compositor\": nice %d -> %d", (int)tid, before, after);
  }

  g_running = 1;
  droiddeck_wayland_run();
  g_running = 0;
  ALOGI("compositor thread exited");
  return NULL;
}

// --- The callbacks the compositor makes ----------------------------------

void droiddeck_on_first_frame(void) {
  EmitEvent("firstFrame", "");
}

void droiddeck_on_game_surface(const char *window, const char *gpu) {
  char detail[512];
  snprintf(detail, sizeof(detail), "%s|%s", window ? window : "", gpu ? gpu : "");
  EmitEvent("gameSurface", detail);
}

void droiddeck_on_game_program(int pid, const char *program) {
  char detail[512];
  snprintf(detail, sizeof(detail), "%d|%s", pid, program ? program : "");
  EmitEvent("gameProgram", detail);
}

void droiddeck_on_game_frame(void) {
  EmitEvent("gameFrame", "");
}

void droiddeck_on_pointer_lock(int locked, int x, int y) {
  char detail[64];
  snprintf(detail, sizeof(detail), "%d|%d|%d", locked, x, y);
  EmitEvent("pointerLock", detail);
}

void droiddeck_on_clipboard_text(const char *utf8, int len) {
  if (utf8 == NULL || len <= 0) {
    return;
  }
  char *detail = (char *)malloc((size_t)len + 1);
  if (detail == NULL) {
    return;
  }
  memcpy(detail, utf8, (size_t)len);
  detail[len] = '\0';
  EmitEvent("clipboardText", detail);
  free(detail);
}

void droiddeck_on_text_input(int enabled, const char *program, int x, int y, int w, int h) {
  char detail[512];
  snprintf(detail, sizeof(detail), "%d|%s|%d|%d|%d|%d", enabled, program ? program : "", x, y, w, h);
  EmitEvent("textInput", detail);
}

// --- The ArkTS facing functions -------------------------------------------

/** Starts the compositor thread. */
static napi_value CompositorStart(napi_env env, napi_callback_info info) {
  size_t argc = 2;
  napi_value argv[2] = {NULL, NULL};
  napi_get_cb_info(env, info, &argc, argv, NULL, NULL);

  if (g_running) {
    napi_value result = NULL;
    napi_get_boolean(env, true, &result);
    return result;
  }

  if (argc >= 1) {
    size_t length = 0;
    napi_get_value_string_utf8(env, argv[0], NULL, 0, &length);
    char *runtimeDir = (char *)malloc(length + 1);
    if (runtimeDir != NULL) {
      napi_get_value_string_utf8(env, argv[0], runtimeDir, length + 1, &length);
      setenv("XDG_RUNTIME_DIR", runtimeDir, 1);
      free(runtimeDir);
    }
  }

  if (argc >= 2) {
    napi_valuetype type = napi_undefined;
    napi_typeof(env, argv[1], &type);
    if (type == napi_function) {
      napi_value resourceName = NULL;
      napi_create_string_utf8(env, "DroidDeckWaylandEvent", NAPI_AUTO_LENGTH, &resourceName);
      napi_threadsafe_function events = NULL;
      napi_create_threadsafe_function(env, argv[1], NULL, resourceName, 0, 1, NULL, NULL, NULL,
                                      CallJsEvent, &events);
      napi_unref_threadsafe_function(env, events);
      // A second start replaces the callback of the first one. The old one is
      // released under the lock, so the compositor thread cannot be using it
      // at that moment.
      pthread_mutex_lock(&g_events_lock);
      if (g_events != NULL) {
        napi_release_threadsafe_function(g_events, napi_tsfn_release);
      }
      g_events = events;
      pthread_mutex_unlock(&g_events_lock);
    }
  }

  pthread_t thread;
  if (pthread_create(&thread, NULL, comp_thread, NULL) == 0) {
    pthread_detach(thread);
  } else {
    ALOGE("the compositor thread could not be started");
    // The caller has to be told: without a compositor there is nothing to
    // draw the guest's windows into.
    napi_value failure = NULL;
    napi_get_boolean(env, false, &failure);
    return failure;
  }

  napi_value result = NULL;
  napi_get_boolean(env, true, &result);
  return result;
}

/** Stops the compositor thread. */
static napi_value CompositorStop(napi_env env, napi_callback_info info) {
  (void)info;
  g_running = 0;
  pthread_mutex_lock(&g_events_lock);
  if (g_events != NULL) {
    napi_release_threadsafe_function(g_events, napi_tsfn_release);
    g_events = NULL;
  }
  pthread_mutex_unlock(&g_events_lock);
  napi_value result = NULL;
  napi_get_boolean(env, true, &result);
  return result;
}

/** The compositor thread's id, or 0. */
static napi_value CompositorTid(napi_env env, napi_callback_info info) {
  (void)info;
  napi_value result = NULL;
  napi_create_int32(env, (int32_t)g_comp_tid, &result);
  return result;
}

/** Why presenting failed, empty while it works. */
static napi_value CompositorFault(napi_env env, napi_callback_info info) {
  (void)info;
  const char *fault = vkp_fault();
  napi_value result = NULL;
  napi_create_string_utf8(env, fault ? fault : "", NAPI_AUTO_LENGTH, &result);
  return result;
}

/** The display driver as the compositor reports it. */
static napi_value DisplayDriver(napi_env env, napi_callback_info info) {
  (void)info;
  const char *driver = vkp_display_driver();
  napi_value result = NULL;
  napi_create_string_utf8(env, driver ? driver : "", NAPI_AUTO_LENGTH, &result);
  return result;
}

/** Feeds a pointer event into the guest. */
static napi_value CompositorSendPointer(napi_env env, napi_callback_info info) {
  size_t argc = 3;
  napi_value argv[3] = {NULL, NULL, NULL};
  napi_get_cb_info(env, info, &argc, argv, NULL, NULL);
  if (argc >= 3) {
    int32_t action = 0, x = 0, y = 0;
    napi_get_value_int32(env, argv[0], &action);
    napi_get_value_int32(env, argv[1], &x);
    napi_get_value_int32(env, argv[2], &y);
    droiddeck_wayland_send_pointer(action, x, y);
  }
  napi_value result = NULL;
  napi_get_undefined(env, &result);
  return result;
}

/** Feeds a touch event into the guest. */
static napi_value CompositorSendTouch(napi_env env, napi_callback_info info) {
  size_t argc = 4;
  napi_value argv[4] = {NULL, NULL, NULL, NULL};
  napi_get_cb_info(env, info, &argc, argv, NULL, NULL);
  if (argc >= 4) {
    int32_t action = 0, pointerId = 0, x = 0, y = 0;
    napi_get_value_int32(env, argv[0], &action);
    napi_get_value_int32(env, argv[1], &pointerId);
    napi_get_value_int32(env, argv[2], &x);
    napi_get_value_int32(env, argv[3], &y);
    droiddeck_wayland_send_touch(action, pointerId, x, y);
  }
  napi_value result = NULL;
  napi_get_undefined(env, &result);
  return result;
}

/** Feeds a key event into the guest. */
static napi_value CompositorSendKey(napi_env env, napi_callback_info info) {
  size_t argc = 2;
  napi_value argv[2] = {NULL, NULL};
  napi_get_cb_info(env, info, &argc, argv, NULL, NULL);
  if (argc >= 2) {
    int32_t evdev = 0, state = 0;
    napi_get_value_int32(env, argv[0], &evdev);
    napi_get_value_int32(env, argv[1], &state);
    droiddeck_wayland_send_key((uint32_t)evdev, state);
  }
  napi_value result = NULL;
  napi_get_undefined(env, &result);
  return result;
}

/** Tells the compositor how large the output surface is. */
static napi_value CompositorSetOutputSize(napi_env env, napi_callback_info info) {
  size_t argc = 2;
  napi_value argv[2] = {NULL, NULL};
  napi_get_cb_info(env, info, &argc, argv, NULL, NULL);
  if (argc >= 2) {
    int32_t width = 0, height = 0;
    napi_get_value_int32(env, argv[0], &width);
    napi_get_value_int32(env, argv[1], &height);
    g_output_w = width;
    g_output_h = height;
    droiddeck_wayland_output_resized();
  }
  napi_value result = NULL;
  napi_get_undefined(env, &result);
  return result;
}

/** A snapshot of the cursor. */
static napi_value CursorSnapshot(napi_env env, napi_callback_info info) {
  (void)info;
  int values[8] = {0, 0, 0, 0, 0, 0, 0, 0};
  const int count = droiddeck_cursor_snapshot(values, 8);
  napi_value result = NULL;
  napi_create_array_with_length(env, count > 0 ? (size_t)count : 0, &result);
  for (int index = 0; index < count && index < 8; ++index) {
    napi_value item = NULL;
    napi_create_int32(env, values[index], &item);
    napi_set_element(env, result, (uint32_t)index, item);
  }
  return result;
}

/** The HUD's frame times. */
static napi_value HudFrameTimes(napi_env env, napi_callback_info info) {
  (void)info;
  float times[64] = {0};
  int64_t total = 0;
  const int count = droiddeck_hud_frame_times(times, 64, &total);
  const size_t length = count > 0 ? (size_t)count : 0;

  void *data = NULL;
  napi_value result = NULL;
  napi_create_arraybuffer(env, length * sizeof(float), &data, &result);
  if (data != NULL && length > 0) {
    memcpy(data, times, length * sizeof(float));
  }
  return result;
}

/** Registers the compositor functions on the Node-API module. */
napi_value CompositorRegister(napi_env env, napi_value exports) {
  const napi_property_descriptor descriptors[] = {
      {"compositorStart", NULL, CompositorStart, NULL, NULL, NULL, napi_default, NULL},
      {"compositorStop", NULL, CompositorStop, NULL, NULL, NULL, napi_default, NULL},
      {"compositorTid", NULL, CompositorTid, NULL, NULL, NULL, napi_default, NULL},
      {"compositorFault", NULL, CompositorFault, NULL, NULL, NULL, napi_default, NULL},
      {"displayDriver", NULL, DisplayDriver, NULL, NULL, NULL, napi_default, NULL},
      {"compositorSendPointer", NULL, CompositorSendPointer, NULL, NULL, NULL, napi_default, NULL},
      {"compositorSendTouch", NULL, CompositorSendTouch, NULL, NULL, NULL, napi_default, NULL},
      {"compositorSendKey", NULL, CompositorSendKey, NULL, NULL, NULL, napi_default, NULL},
      {"compositorSetOutputSize", NULL, CompositorSetOutputSize, NULL, NULL, NULL, napi_default, NULL},
      {"cursorSnapshot", NULL, CursorSnapshot, NULL, NULL, NULL, napi_default, NULL},
      {"hudFrameTimes", NULL, HudFrameTimes, NULL, NULL, NULL, napi_default, NULL},
  };
  napi_define_properties(env, exports, sizeof(descriptors) / sizeof(descriptors[0]), descriptors);
  return exports;
}
