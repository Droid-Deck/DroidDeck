from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / 'tools/venus/virglrenderer-android.patch'


class VenusAndroidMemoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = PATCH.read_text()

    def test_fallback_is_selected_only_when_export_capability_is_missing(self):
        self.assertIn('!physical_dev->is_dma_buf_fd_export_supported &&', self.source)
        self.assertIn('!physical_dev->is_opaque_fd_export_supported &&', self.source)
        self.assertIn('physical_dev->EXT_external_memory_dma_buf', self.source)
        self.assertNotRegex(self.source, r'Mali-G|G610|G615|G68|G715|Tensor|Dimensity|Exynos|RG557')

    def test_allocations_reuse_bounded_dma_buf_pool(self):
        self.assertIn('#define VKR_ANDROID_DMA_BUF_POOL_MAX 16', self.source)
        self.assertIn('#define VKR_ANDROID_DMA_BUF_POOL_BYTES (64ULL * 1024ULL * 1024ULL)', self.source)
        self.assertIn('vkr_android_take_dma_buf(size, cached)', self.source)
        self.assertIn('vkr_android_put_dma_buf(fd, align(size, getpagesize()),', self.source)
        self.assertIn('vkr_android_release_dma_buf(mem->udmabuf_fd, mem->allocation_size, mem->property_flags);', self.source)

    def test_export_properties_are_fixed_up_for_importable_android_memory(self):
        self.assertIn('vkr_android_dma_buf_export_fixup(', self.source)
        self.assertIn('props->externalMemoryFeatures |= VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT;', self.source)
        self.assertIn('props->exportFromImportedHandleTypes |= VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;', self.source)
        self.assertIn('props->compatibleHandleTypes |= VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;', self.source)


if __name__ == '__main__':
    unittest.main()
