#pragma once
#if defined(__ANDROID__)
  #include <android/log.h>
  #define DDFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "dd-fg", __VA_ARGS__)
  #define DDFG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "dd-fg", __VA_ARGS__)
#else
  #include <cstdio>
  #define DDFG_LOGI(...) do { std::fprintf(stderr, "[dd-fg] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
  #define DDFG_LOGE(...) do { std::fprintf(stderr, "[dd-fg][E] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
#endif

#define DDFG_LOGD(cond, ...) do { if (cond) DDFG_LOGI(__VA_ARGS__); } while(0)
