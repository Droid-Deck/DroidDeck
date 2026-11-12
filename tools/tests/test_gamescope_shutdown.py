from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0134-vulkan-release-before-exit.patch"


def added_source():
    return "\n".join(line[1:] for line in PATCH.read_text().splitlines()
                     if line.startswith("+") and not line.startswith("+++"))


def function(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 0
    for end in range(opening, len(text)):
        if text[end] == "{":
            depth += 1
        elif text[end] == "}":
            depth -= 1
            if depth == 0:
                return text[start:end + 1]
    raise AssertionError(signature)


PREAMBLE = r'''
#include <atomic>
#include <cassert>
#include <cstdint>
#include <map>
#include <memory>
#include <stop_token>
#include <thread>
#include <vector>

using VkDevice = int;
using VkSemaphore = int;
using VkResult = int;
using VkStructureType = int;
constexpr int VK_SUCCESS = 0;
constexpr int VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO = 1;

struct VkSemaphoreWaitInfo {
    VkStructureType sType;
    uint32_t semaphoreCount;
    const VkSemaphore *pSemaphores;
    const uint64_t *pValues;
};

struct CVulkanCmdBuffer {
    static int destroyed;
    ~CVulkanCmdBuffer() { ++destroyed; }
};

int CVulkanCmdBuffer::destroyed = 0;

struct VkDispatch {
    bool idle = true;
    std::atomic<bool> *pipelineStopped = nullptr;
    int waits = 0;
    uint64_t timeout = 0;

    VkResult WaitSemaphores(VkDevice device, const VkSemaphoreWaitInfo *info, uint64_t waitTimeout) {
        assert(device == 1);
        assert(pipelineStopped && pipelineStopped->load());
        assert(info->sType == VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO);
        assert(info->semaphoreCount == 1);
        assert(*info->pSemaphores == 17);
        assert(*info->pValues == 29);
        ++waits;
        timeout = waitTimeout;
        return idle ? VK_SUCCESS : 1;
    }
};

class CVulkanDevice {
public:
    bool m_bInitialized = true;
    std::jthread m_pipelineThread;
    uint64_t m_submissionSeqNo = 29;
    VkSemaphore m_scratchTimelineSemaphore = 17;
    VkDispatch vk;
    std::map<uint64_t, std::unique_ptr<CVulkanCmdBuffer>> m_pendingCmdBufs;
    std::vector<std::unique_ptr<CVulkanCmdBuffer>> m_unusedCmdBufs;
    std::atomic<bool> pipelineStopped{false};

    CVulkanDevice() { vk.pipelineStopped = &pipelineStopped; }
    VkDevice device() const { return 1; }
    void startPipeline() {
        m_pipelineThread = std::jthread([this](std::stop_token stop) {
            while (!stop.stop_requested()) std::this_thread::yield();
            pipelineStopped.store(true);
        });
    }
    bool Shutdown();
};
'''

MAIN = r'''
int main() {
    CVulkanCmdBuffer::destroyed = 0;
    {
        CVulkanDevice device;
        device.startPipeline();
        device.m_pendingCmdBufs.emplace(1, std::make_unique<CVulkanCmdBuffer>());
        device.m_pendingCmdBufs.emplace(2, std::make_unique<CVulkanCmdBuffer>());
        device.m_unusedCmdBufs.push_back(std::make_unique<CVulkanCmdBuffer>());
        assert(device.Shutdown());
        assert(device.vk.waits == 1);
        assert(device.vk.timeout == 1'000'000'000ull);
        assert(device.m_pendingCmdBufs.empty());
        assert(device.m_unusedCmdBufs.empty());
        assert(CVulkanCmdBuffer::destroyed == 3);
    }

    CVulkanCmdBuffer::destroyed = 0;
    {
        CVulkanDevice device;
        device.startPipeline();
        device.vk.idle = false;
        auto *first = new CVulkanCmdBuffer();
        auto *second = new CVulkanCmdBuffer();
        device.m_pendingCmdBufs.emplace(1, std::unique_ptr<CVulkanCmdBuffer>(first));
        device.m_pendingCmdBufs.emplace(2, std::unique_ptr<CVulkanCmdBuffer>(second));
        device.m_unusedCmdBufs.push_back(std::make_unique<CVulkanCmdBuffer>());
        assert(!device.Shutdown());
        assert(device.vk.waits == 1);
        assert(device.m_pendingCmdBufs.size() == 2);
        assert(!device.m_pendingCmdBufs.at(1));
        assert(!device.m_pendingCmdBufs.at(2));
        assert(CVulkanCmdBuffer::destroyed == 1);
        delete first;
        delete second;
        assert(CVulkanCmdBuffer::destroyed == 3);
    }

    CVulkanDevice uninitialized;
    uninitialized.m_bInitialized = false;
    assert(uninitialized.Shutdown());
    assert(uninitialized.vk.waits == 0);
    return 0;
}
'''


class GamescopeShutdownTest(unittest.TestCase):
    def test_shutdown_waits_for_the_worker_and_handles_both_drain_paths(self):
        self.assertTrue(shutil.which("c++"), "needs a C++ compiler")
        shutdown = function(added_source(), "bool CVulkanDevice::Shutdown()")
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "shutdown.cpp"
            binary = Path(tmp) / "shutdown"
            source.write_text(PREAMBLE + shutdown + MAIN)
            subprocess.run(["c++", "-std=c++20", "-Wall", "-Wextra", "-Werror",
                            str(source), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)

    def test_shutdown_hook_runs_after_the_wayland_server_stops(self):
        patch = PATCH.read_text()
        exit_hunk = patch.split("diff --git a/src/steamcompmgr.cpp", 1)[1]
        self.assertLess(exit_hunk.index("wlserver_shutdown();"),
                        exit_hunk.index("+    vulkan_shutdown();"))
        cleanup = function(added_source(), "void vulkan_shutdown( void )")
        self.assertIn("g_device.Shutdown()", cleanup)
        self.assertIn("g_reshadeManager.clear()", cleanup)
        self.assertIn("g_reshadeManager.leak()", cleanup)
        self.assertIn("g_output.outputImages.clear()", cleanup)
        self.assertIn("g_output.outputImagesPartialOverlay.clear()", cleanup)
        self.assertIn("g_output.pScreenshotTextures = {}", cleanup)
        self.assertIn("g_output.pCaptureTextures = {}", cleanup)

    def test_patch_only_adds_exit_path_code(self):
        patch = PATCH.read_text()
        files = [line.split(" b/", 1)[1] for line in patch.splitlines()
                 if line.startswith("diff --git ")]
        self.assertEqual(files, ["src/rendervulkan.cpp", "src/rendervulkan.hpp",
                                 "src/reshade_effect_manager.cpp",
                                 "src/reshade_effect_manager.hpp",
                                 "src/steamcompmgr.cpp"])
        removed = [line for line in patch.splitlines()
                   if line.startswith("-") and not line.startswith("---")]
        self.assertEqual(removed, [])
        self.assertEqual(added_source().count("vulkan_shutdown();"), 1)
        self.assertNotIn("setenv", added_source())
        self.assertNotIn("getenv", added_source())


if __name__ == "__main__":
    unittest.main()
