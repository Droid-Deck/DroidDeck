#ifndef DDK_DEVICE_H
#define DDK_DEVICE_H

#include <string>

namespace ddk {

/**
 * GPU identity read through EGL.
 *
 * Upstream DroidDeck probes Qualcomm's /sys/class/kgsl/kgsl-3d0 and the
 * Adreno vendor HAL. HarmonyOS exposes neither, so the port asks EGL for the
 * renderer string instead, which works for Adreno, Mali and PowerVR alike.
 */
struct GpuIdentity {
  std::string name;
  std::string vendor;
  int model = 0;
};

GpuIdentity DetectGpu();

/** Number of usable CPU cores. */
int DetectCpuCores();

/** Total physical RAM in MiB. */
int DetectRamMiB();

/** True when a Vulkan ICD is visible to the app sandbox. */
bool DetectVulkanBridge();

/** ABI string for the running process, e.g. "arm64-v8a". */
std::string DetectAbi();

}  // namespace ddk

#endif  // DDK_DEVICE_H
