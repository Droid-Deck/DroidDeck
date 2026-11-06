#!/usr/bin/env python3
"""Replay asynchronous WSI ownership using the pinned presenter's actual selections.

This checks semaphore selection, not the full Vulkan presenter. Real Vulkan
validation and device testing remain separate gates. Requires a native C++ compiler.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile


def selection_code(source):
    match = re.search(
        r"// Return relevant Vulkan objects for the acquired image\s*"
        r"(?P<acquire>.*?)\s*m_presentPending = true;", source, re.S)
    wait = re.search(r"info\.pWaitSemaphores\s*=.*?;", source, re.S)
    if not match or not wait:
        raise ValueError("unsupported pinned presenter layout")
    return match["acquire"], wait[0]


HARNESS = r'''
#include <array>
#include <cstdint>
#include <cstdio>
#include <vector>
using VkSemaphore = uint64_t;
struct Sync { VkSemaphore acquire, present; };
struct Info { const VkSemaphore* pWaitSemaphores; };

struct Presenter {
  std::vector<Sync> m_semaphores;
  std::vector<unsigned> m_images = {0, 1, 2, 3};
  unsigned m_frameIndex = 0, m_imageIndex = 0;
  bool m_hasSwapchainMaintenance1;
  explicit Presenter(bool maintenance): m_hasSwapchainMaintenance1(maintenance) {
    for (unsigned i = 0; i < (maintenance ? 4u : 8u); ++i)
      m_semaphores.push_back({100 + i, 1 + i});
  }
  Sync acquire() {
    Sync sync{}; unsigned image;
    ACQUIRE_SELECTION
    (void)image;
    return sync;
  }
  VkSemaphore present() {
    auto& currSync = m_semaphores.at(m_frameIndex);
    Info info{};
    PRESENT_SELECTION
    return *info.pWaitSemaphores;
  }
};

int replay(bool maintenance) {
  Presenter p(maintenance);
  std::array<VkSemaphore, 4> outstandingByImage{};
  std::array<VkSemaphore, 4> outstandingByFence{};
  // Deliberately delay reacquisition of image 2 beyond a full frame-ring cycle.
  const std::array<unsigned, 21> imageOrder = {
    2,0,3,0,1,3,0,1,1,2,3,1,0,3,2,2,0,1,3,0,2
  };
  for (unsigned iteration = 0; iteration < 200; ++iteration) {
    p.m_imageIndex = imageOrder[iteration % imageOrder.size()];
    if (maintenance) {
      // The existing fence path waits this frame's previous present fence.
      const auto completed = outstandingByFence.at(p.m_frameIndex);
      for (auto& pending : outstandingByImage)
        if (pending == completed) pending = 0;
    }
    // Acquisition and its GPU wait complete the last present of THIS image.
    outstandingByImage.at(p.m_imageIndex) = 0;
    const auto signal = p.acquire().present;
    const auto wait = p.present();
    if (signal != wait) {
      std::fprintf(stderr, "FAIL: render signal differs from present wait\n");
      return 2;
    }
    for (auto pending : outstandingByImage) {
      if (pending == signal) {
        std::fprintf(stderr, "FAIL: semaphore reused before presentation completion at frame %u\n", iteration);
        return 2;
      }
    }
    outstandingByImage.at(p.m_imageIndex) = signal;
    if (maintenance) outstandingByFence.at(p.m_frameIndex) = signal;
    p.m_frameIndex = (p.m_frameIndex + 1) % p.m_semaphores.size();
  }
  std::printf("PASS: %s, 200 out-of-order acquisitions\n", maintenance ? "present-fence path" : "no-present-fence path");
  return 0;
}
int main() {
  if (int status = replay(false)) return status;
  return replay(true);
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="pinned dxvk_presenter.cpp, after patching")
    args = parser.parse_args()
    acquire, present = selection_code(args.source.read_text())
    code = HARNESS.replace("ACQUIRE_SELECTION", acquire).replace("PRESENT_SELECTION", present)
    with tempfile.TemporaryDirectory(prefix="dxvk-present-sync-") as directory:
        cpp = Path(directory) / "replay.cpp"
        exe = Path(directory) / "replay"
        cpp.write_text(code)
        subprocess.run([os.environ.get("HOST_CXX", "c++"), "-std=c++17", "-Wall", "-Wextra", str(cpp), "-o", str(exe)], check=True)
        return subprocess.run([str(exe)]).returncode


if __name__ == "__main__":
    raise SystemExit(main())
