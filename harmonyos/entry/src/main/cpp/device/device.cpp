#include "device/device.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <unistd.h>

#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>

#include "ddk_log.h"

namespace ddk {
namespace {

/**
 * Reads a numeric model out of a renderer string such as
 * "Adreno (TM) 740" or "Mali-G78".
 */
int ParseModel(const std::string &lower) {
  size_t start = lower.find_first_of("0123456789");
  if (start == std::string::npos) {
    return 0;
  }
  size_t end = start;
  while (end < lower.size() && std::isdigit(static_cast<unsigned char>(lower[end]))) {
    ++end;
  }
  return std::atoi(lower.substr(start, end - start).c_str());
}

}  // namespace

GpuIdentity DetectGpu() {
  GpuIdentity identity;

  EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (display == EGL_NO_DISPLAY) {
    DDK_LOGW("no EGL display, GPU unknown");
    return identity;
  }

  EGLint major = 0;
  EGLint minor = 0;
  if (!eglInitialize(display, &major, &minor)) {
    DDK_LOGW("eglInitialize failed, GPU unknown");
    return identity;
  }

  const EGLint configAttribs[] = {
      EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
      EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
      EGL_NONE
  };
  EGLConfig config = nullptr;
  EGLint configCount = 0;
  if (!eglChooseConfig(display, configAttribs, &config, 1, &configCount) || configCount == 0) {
    DDK_LOGW("no EGL config, GPU unknown");
    return identity;
  }

  const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
  if (context == EGL_NO_CONTEXT) {
    DDK_LOGW("no EGL context, GPU unknown");
    return identity;
  }

  if (eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context)) {
    const char *renderer = reinterpret_cast<const char *>(glGetString(GL_RENDERER));
    const char *vendor = reinterpret_cast<const char *>(glGetString(GL_VENDOR));
    const char *version = reinterpret_cast<const char *>(glGetString(GL_VERSION));
    if (renderer != nullptr) {
      identity.name = renderer;
    }
    if (vendor != nullptr) {
      identity.vendor = vendor;
    }
    if (version != nullptr) {
      identity.version = version;
    }
  }

  std::string lowered = identity.name;
  for (char &c : lowered) {
    c = static_cast<char>(::tolower(static_cast<unsigned char>(c)));
  }
  identity.model = ParseModel(lowered);

  eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  eglDestroyContext(display, context);
  // The display is not terminated here. It is the process-wide default
  // display, and the compositor's surface host keeps its own context on it;
  // terminating it would take that context down with it.

  DDK_LOGI("gpu renderer=%{public}s vendor=%{public}s model=%{public}d",
           identity.name.c_str(), identity.vendor.c_str(), identity.model);
  DDK_LOGI("gles version=%{public}s", identity.version.c_str());
  return identity;
}

int DetectCpuCores() {
  long cores = sysconf(_SC_NPROCESSORS_ONLN);
  return cores > 0 ? static_cast<int>(cores) : 1;
}

int DetectRamMiB() {
  std::ifstream meminfo("/proc/meminfo");
  std::string label;
  long value = 0;
  std::string unit;
  while (meminfo >> label >> value >> unit) {
    if (label == "MemTotal:") {
      return static_cast<int>(value / 1024);
    }
  }
  return 0;
}

bool DetectVulkanBridge() {
  // A Vulkan ICD in the sandbox means the guest can be pointed at the
  // phone's own driver, which is the only path for Mali parts that the open
  // PanVK builds do not cover.
  static const char *candidates[] = {
      "/vendor/lib64/hw/vulkan.adreno.so",
      "/vendor/lib64/hw/vulkan.mali.so",
      "/vendor/lib64/hw/vulkan.img.so",
      "/system/lib64/hw/vulkan.adreno.so",
      "/etc/vulkan/icd.d",
  };
  for (const char *path : candidates) {
    if (access(path, F_OK) == 0) {
      return true;
    }
  }
  return false;
}

std::string DetectAbi() {
#if defined(__aarch64__)
  return "arm64-v8a";
#elif defined(__arm__)
  return "armeabi-v7a";
#elif defined(__x86_64__)
  return "x86_64";
#else
  return "unknown";
#endif
}

}  // namespace ddk
