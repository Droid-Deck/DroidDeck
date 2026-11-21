from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0936-nested-swapchain-remake-retries.patch"
DESTROY = "g_device.vk.DestroyImage( g_device.device(), m_vkImage, nullptr );"
RESET = "m_vkImage = VK_NULL_HANDLE;"


def hunks():
    out = []
    for line in PATCH.read_text().splitlines():
        if line.startswith("@@"):
            out.append([])
        elif out:
            out[-1].append(line)
    return out


def hunk_after(anchor):
    for hunk in hunks():
        if any(anchor in line for line in hunk):
            return hunk
    raise AssertionError(anchor)


class SwapchainImageReleaseTest(unittest.TestCase):
    def test_failed_allocation_destroys_the_image(self):
        hunk = hunk_after(DESTROY)
        added = [line[1:].strip() for line in hunk if line.startswith("+")]
        self.assertEqual(added, [DESTROY, RESET])
        self.assertEqual(hunk[-1].strip(), "return false;")

    def test_failed_bind_destroys_only_an_image_the_destructor_would_skip(self):
        hunk = hunk_after("if ( m_vkImageMemory == VK_NULL_HANDLE )")
        added = [line[1:].strip() for line in hunk if line.startswith("+")]
        self.assertEqual(added, ["if ( m_vkImageMemory == VK_NULL_HANDLE )", "{", DESTROY, RESET, "}"])
        self.assertEqual(hunk[-1].strip(), "return false;")


if __name__ == "__main__":
    unittest.main()
