/* wsialloc without a dma-buf heap (true in WSL/CI, and for Android apps): the ordinary constructor must preserve
 * its failure behavior. X11 alone uses the layout-only constructor: NO_MEMORY requests return the LINEAR layout the
 * swapchain images are created with, while real allocations still fail. Run by test_vulkan_wsi_patches.py. */
#include <assert.h>
#include <stdio.h>
#include <unistd.h>
#include "wsialloc.h"

int main(void)
{
    assert(access("/dev/dma_heap/system", R_OK) != 0);
    wsialloc_allocator *ordinary = NULL;
    assert(wsialloc_new(&ordinary) == WSIALLOC_ERROR_NO_RESOURCE);
    assert(ordinary == NULL);

    wsialloc_allocator *allocator = NULL;
    assert(wsialloc_new_layout_only(&allocator) == WSIALLOC_ERROR_NONE);

    wsialloc_format formats[] = { { DRM_FORMAT_XRGB8888, DRM_FORMAT_MOD_LINEAR, 0 } };
    wsialloc_allocate_info info = { formats, 1, 1364, 768, WSIALLOC_ALLOCATE_NO_MEMORY };
    wsialloc_allocate_result result = { { 0 }, { 0 }, { 0 }, { -1, -1, -1, -1 }, false };
    assert(wsialloc_alloc(allocator, &info, &result) == WSIALLOC_ERROR_NONE);
    assert(result.average_row_strides[0] == 5504 && result.offsets[0] == 0); /* 1364 * 4 rounded up to 64 bytes */
    assert(result.buffer_fds[0] == -1 && result.format.fourcc == DRM_FORMAT_XRGB8888);

    info.flags = 0;
    assert(wsialloc_alloc(allocator, &info, &result) == WSIALLOC_ERROR_NO_RESOURCE);
    info.flags = WSIALLOC_ALLOCATE_PROTECTED;
    assert(wsialloc_alloc(allocator, &info, &result) == WSIALLOC_ERROR_NO_RESOURCE);

    wsialloc_delete(allocator);
    puts("wsialloc without heap: PASS");
    return 0;
}
