#pragma once
#if defined(__ANDROID__)
  #include <android/log.h>
  #define WFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "win-fg", __VA_ARGS__)
  #define WFG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "win-fg", __VA_ARGS__)
#else
  #include <cstdio>
  #define WFG_LOGI(...) do { std::fprintf(stderr, "[win-fg] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
  #define WFG_LOGE(...) do { std::fprintf(stderr, "[win-fg][E] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
#endif

#define WFG_LOGD(cond, ...) do { if (cond) WFG_LOGI(__VA_ARGS__); } while(0)
