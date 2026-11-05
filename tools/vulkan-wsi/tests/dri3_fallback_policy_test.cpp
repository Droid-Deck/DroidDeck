#include <cassert>

#include "wsi/dri3_fallback_policy.hpp"

int main()
{
   using wsi::x11::can_use_no_heap_driver_export;
   using wsi::x11::preserves_swapchain_aliasing;
   using wsi::x11::should_allocate_from_driver_after_heap_failure;

   // Normal non-X backends and heap-backed X11 images do not opt into the fallback.
   assert(!can_use_no_heap_driver_export(false, true, false, false, true));

   // A no-heap image with no exportable modifier must select the existing SHM path.
   assert(!can_use_no_heap_driver_export(true, false, false, false, true));

   // Dedicated-only external memory cannot preserve VkBindImageMemorySwapchainInfoKHR aliasing.
   assert(!can_use_no_heap_driver_export(true, true, true, false, true));
   assert(!preserves_swapchain_aliasing(false, true));
   assert(!can_use_no_heap_driver_export(true, true, false, true, true));
   assert(!can_use_no_heap_driver_export(true, true, false, false, false));

   // An ordinary exportable one-plane image can use the driver-export fallback.
   assert(can_use_no_heap_driver_export(true, true, false, false, true));

   // A late heap allocation failure uses that pre-approved fallback only.
   assert(should_allocate_from_driver_after_heap_failure(true));
   assert(!should_allocate_from_driver_after_heap_failure(false));
   return 0;
}
