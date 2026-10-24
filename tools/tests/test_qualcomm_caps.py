from pathlib import Path
import runpy
import unittest

CAPS = runpy.run_path(str(Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-qualcomm-caps"))
decide = CAPS["decide"]
REQUIRED = set(CAPS["REQUIRED"])
CAPABLE = {"extensions": REQUIRED | {"VK_EXT_image_drm_format_modifier"}, "linear_export": True,
           "linear_import": True, "export_fd": True, "gamescope_dmabuf": True}


class QualcommCapsTest(unittest.TestCase):
    def test_a_driver_with_working_dma_buf_export_enables_zero_copy(self):
        self.assertEqual(decide(CAPABLE), (True, []))

    def test_adreno_740_without_dma_buf_keeps_shm(self):
        extensions = REQUIRED - {"VK_EXT_external_memory_dma_buf"}
        enabled, reasons = decide({"extensions": extensions, "gamescope_dmabuf": False})
        self.assertFalse(enabled)
        self.assertEqual(reasons, ["missing VK_EXT_external_memory_dma_buf", "Gamescope offers no linux-dmabuf"])

    def test_every_check_must_pass(self):
        for key, reason in (("linear_export", "LINEAR DMA-BUF images are not exportable"),
                            ("linear_import", "LINEAR DMA-BUF images are not importable"),
                            ("export_fd", "DMA-BUF export failed"),
                            ("gamescope_dmabuf", "Gamescope offers no linux-dmabuf")):
            with self.subTest(key=key):
                self.assertEqual(decide(dict(CAPABLE, **{key: False})), (False, [reason]))
        self.assertEqual(decide(dict(CAPABLE, export_fd=False, export_error="vkGetMemoryFdKHR failed (-1)")),
                         (False, ["vkGetMemoryFdKHR failed (-1)"]))

    def test_a_failed_probe_never_enables_zero_copy(self):
        self.assertEqual(decide({"error": "vkCreateInstance returned -9"}),
                         (False, ["probe failed: vkCreateInstance returned -9"]))
        self.assertFalse(decide({})[0])


if __name__ == "__main__":
    unittest.main()
