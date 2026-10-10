/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef VKB_LOG_H
#define VKB_LOG_H

#include <stdarg.h>

#define VKB_LOG_ERROR 0
#define VKB_LOG_WARN 1
#define VKB_LOG_INFO 2
#define VKB_LOG_DEBUG 3

/* Each side provides the sink (client: stderr + optional file; server: logcat + file). */
void vkb_log(int level, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
int vkb_log_level(void);

#define VKB_ERR(...) vkb_log(VKB_LOG_ERROR, __VA_ARGS__)
#define VKB_WARN(...) vkb_log(VKB_LOG_WARN, __VA_ARGS__)
#define VKB_INFO(...) vkb_log(VKB_LOG_INFO, __VA_ARGS__)
#define VKB_DBG(...) do { if (vkb_log_level() >= VKB_LOG_DEBUG) vkb_log(VKB_LOG_DEBUG, __VA_ARGS__); } while (0)

/* Logs a message only the first time this call site is reached. */
#define VKB_ONCE(level, ...) do { static int once_; if (!__atomic_exchange_n(&once_, 1, __ATOMIC_RELAXED)) vkb_log(level, __VA_ARGS__); } while (0)

#endif
