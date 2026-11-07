#ifndef DDK_COMPAT_ANDROID_LOG_H
#define DDK_COMPAT_ANDROID_LOG_H

/**
 * AOSP's <android/log.h> on top of the OpenHarmony hilog.
 *
 * The ported compositor comes from upstream DroidDeck, which logs through
 * liblog. HarmonyOS has hilog instead, so this header maps the handful of
 * liblog entry points the compositor uses onto it. The log level constants
 * keep their liblog values so the ported source needs no changes.
 */

#include <stdarg.h>
#include <stdio.h>

#include <hilog/log.h>

enum android_log_priority {
  ANDROID_LOG_UNKNOWN = 0,
  ANDROID_LOG_DEFAULT = 1,
  ANDROID_LOG_VERBOSE = 2,
  ANDROID_LOG_DEBUG = 3,
  ANDROID_LOG_INFO = 4,
  ANDROID_LOG_WARN = 5,
  ANDROID_LOG_ERROR = 6,
  ANDROID_LOG_FATAL = 7,
  ANDROID_LOG_SILENT = 8,
};

#ifndef DDK_COMPAT_LOG_DOMAIN
#define DDK_COMPAT_LOG_DOMAIN 0xF002
#endif

/** Maps a liblog priority onto a hilog level. */
static inline LogLevel DdkCompatLevel(int priority) {
  switch (priority) {
    case ANDROID_LOG_VERBOSE:
      return LOG_DEBUG;
    case ANDROID_LOG_DEBUG:
      return LOG_DEBUG;
    case ANDROID_LOG_INFO:
      return LOG_INFO;
    case ANDROID_LOG_WARN:
      return LOG_WARN;
    case ANDROID_LOG_ERROR:
      return LOG_ERROR;
    case ANDROID_LOG_FATAL:
      return LOG_FATAL;
    default:
      return LOG_INFO;
  }
}

static inline int __android_log_print(int priority, const char *tag, const char *format, ...) {
  va_list args;
  va_start(args, format);
  OH_LOG_Print(LOG_APP, DdkCompatLevel(priority), DDK_COMPAT_LOG_DOMAIN, tag, format, args);
  va_end(args);
  return 0;
}

#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "DroidDeckWayland", __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "DroidDeckWayland", __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "DroidDeckWayland", __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, "DroidDeckWayland", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DroidDeckWayland", __VA_ARGS__)

#endif  // DDK_COMPAT_ANDROID_LOG_H
