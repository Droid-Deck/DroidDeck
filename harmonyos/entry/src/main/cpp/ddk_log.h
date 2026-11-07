/*
 * The port's logging.
 *
 * Upstream's tree carries a staging copy of this header, which the Android
 * build fills in with logcat. On HarmonyOS the native module logs through
 * hilog, with the same domain and tag the ArkTS side uses
 * (entry/src/main/ets/common/Logger.ets), so one hilog stream carries both
 * halves of the port:
 *
 *     hdc shell hilog -T DroidDeck
 *
 * The host build (tools/test_native_host.sh) compiles a few of these sources
 * with the build machine's compiler, where hilog does not exist, so the
 * macros fall back to stderr there.
 */
#ifndef DDK_LOG_H
#define DDK_LOG_H

/* The domain and the tag the ArkTS side logs under. */
#define DDK_LOG_DOMAIN 0xF001
#define DDK_LOG_TAG "DroidDeck"

#if defined(__OHOS__)

#include <hilog/log.h>

#define DDK_LOGI(...) OH_LOG_Print(LOG_APP, LOG_INFO, DDK_LOG_DOMAIN, DDK_LOG_TAG, __VA_ARGS__)
#define DDK_LOGW(...) OH_LOG_Print(LOG_APP, LOG_WARN, DDK_LOG_DOMAIN, DDK_LOG_TAG, __VA_ARGS__)
#define DDK_LOGE(...) OH_LOG_Print(LOG_APP, LOG_ERROR, DDK_LOG_DOMAIN, DDK_LOG_TAG, __VA_ARGS__)

#else /* the host build */

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

/*
 * hilog's privacy markers ("%{public}s") are not printf specifiers, so they
 * are removed before the message is formatted for stderr.
 */
static inline void DdkLogToStderr(const char *level, const char *format, ...) {
  char stripped[512];
  size_t out = 0;
  size_t in = 0;

  while (format[in] != '\0' && out + 1 < sizeof(stripped)) {
    if (strncmp(format + in, "%{public}", 9) == 0) {
      stripped[out++] = '%';
      in += 9;
    } else if (strncmp(format + in, "%{private}", 10) == 0) {
      stripped[out++] = '%';
      in += 10;
    } else {
      stripped[out++] = format[in++];
    }
  }
  stripped[out] = '\0';

  va_list arguments;
  va_start(arguments, format);
  fprintf(stderr, "[%s] %s: ", DDK_LOG_TAG, level);
  vfprintf(stderr, stripped, arguments);
  fprintf(stderr, "\n");
  va_end(arguments);
}

#define DDK_LOGI(...) DdkLogToStderr("info", __VA_ARGS__)
#define DDK_LOGW(...) DdkLogToStderr("warn", __VA_ARGS__)
#define DDK_LOGE(...) DdkLogToStderr("error", __VA_ARGS__)

#endif /* __OHOS__ */

#endif /* DDK_LOG_H */
