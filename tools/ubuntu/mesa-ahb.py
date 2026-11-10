"""Add the Android allocation broker to the matching Mesa Wayland WSI."""
from pathlib import Path
import sys


def apply(root):
    source = Path(root) / "src/vulkan/wsi"
    text = (source / "wsi_common_wayland.c").read_text()
    if '#include "droiddeck-ahb-image.c"' not in text:
        def replace(old, new):
            nonlocal text
            if text.count(old) != 1:
                raise ValueError(f"Incompatible Mesa source: {old[:80]}")
            text = text.replace(old, new)
        replace("struct wsi_wl_image {\n   struct wsi_image base;",
                "struct wsi_wl_image {\n   struct wsi_image base;\n   int droiddeck_broker;\n"
                "   uint64_t droiddeck_release_serial;\n   bool droiddeck_release_events;")
        replace("static VkResult\nwsi_wl_image_init(",
                '#include "droiddeck-ahb-image.c"\n\nstatic VkResult\nwsi_wl_image_init(')
        replace("   result = wsi_create_image(&chain->base, &chain->base.image_info,\n"
                "                             &image->base);",
                "   image->droiddeck_broker = -1;\n"
                "   result = droiddeck_create_ahb_image(chain, image);\n"
                "   if (result != VK_SUCCESS)\n"
                "      result = wsi_create_image(&chain->base, &chain->base.image_info,\n"
                "                                &image->base);")
        replace("   wsi_destroy_image(&chain->base, &image->base);\n",
                "   wsi_destroy_image(&chain->base, &image->base);\n"
                "   if (image->droiddeck_broker >= 0) {\n"
                "      close(image->droiddeck_broker);\n"
                "      image->droiddeck_broker = -1;\n"
                "   }\n")
        replace("         wsi_destroy_image(&chain->base, &chain->images[i].base);",
                "         wsi_destroy_image(&chain->base, &chain->images[i].base);\n"
                "         if (chain->images[i].droiddeck_broker >= 0) {\n"
                "            close(chain->images[i].droiddeck_broker);\n"
                "            chain->images[i].droiddeck_broker = -1;\n"
                "         }")
        (source / "wsi_common_wayland.c").write_text(text)
    elif "struct wsi_wl_image {\n   int droiddeck_broker;" in text:
        text = text.replace("struct wsi_wl_image {\n   int droiddeck_broker;\n   struct wsi_image base;",
                            "struct wsi_wl_image {\n   struct wsi_image base;\n   int droiddeck_broker;")
        text = text.replace("         wsi_destroy_image(&chain->base, &chain->images[i].base);",
                            "         wsi_destroy_image(&chain->base, &chain->images[i].base);\n"
                            "         if (chain->images[i].droiddeck_broker >= 0)\n"
                            "            close(chain->images[i].droiddeck_broker);")
        (source / "wsi_common_wayland.c").write_text(text)
    text = (source / "wsi_common_wayland.c").read_text()
    if "   uint64_t droiddeck_release_serial;" not in text:
        text = text.replace("   int droiddeck_broker;\n",
                            "   int droiddeck_broker;\n   uint64_t droiddeck_release_serial;\n"
                            "   bool droiddeck_release_events;\n", 1)
    declaration = "static bool droiddeck_image_released(struct wsi_wl_image *image);\n\n"
    if declaration not in text:
        old = "static VkResult\nwsi_wl_swapchain_acquire_next_image_implicit("
        if text.count(old) != 1:
            raise ValueError("Incompatible Mesa implicit acquire")
        text = text.replace(old, declaration + old)
    old = "            chain->images[i].busy = true;\n"
    new = old + ("            chain->images[i].base.droiddeck_display_released =\n"
                 "               droiddeck_image_released(&chain->images[i]);\n")
    if new not in text:
        if text.count(old) != 1:
            raise ValueError("Incompatible Mesa free image")
        text = text.replace(old, new)
    (source / "wsi_common_wayland.c").write_text(text)
    (source / "droiddeck-ahb-image.c").write_bytes(Path(__file__).with_name("mesa-ahb-image.c").read_bytes())
    (source / "droiddeck-ahb-protocol.h").write_bytes(
        Path(__file__).parents[2].joinpath("app/src/main/cpp/waylandcomp/src/ahb_broker_protocol.h").read_bytes())
    for name, old, new in [
        ("wsi_common.h", "   WSI_CB(GetMemoryFdKHR);",
         "   WSI_CB(GetMemoryFdKHR);\n   WSI_CB(GetMemoryFdPropertiesKHR);"),
        ("wsi_common.c", "      WSI_GET_CB(GetMemoryFdKHR);",
         "      WSI_GET_CB(GetMemoryFdKHR);\n   WSI_GET_CB(GetMemoryFdPropertiesKHR);"),
    ]:
        path = source / name
        content = path.read_text()
        if new not in content:
            if content.count(old) != 1:
                raise ValueError(f"Incompatible Mesa dispatch: {name}")
            path.write_text(content.replace(old, new))
    path = source / "wsi_common_private.h"
    text = path.read_text()
    member = "   bool droiddeck_display_released;\n"
    if member not in text:
        old = "   bool acquired;\n"
        if text.count(old) != 1:
            raise ValueError("Incompatible Mesa image layout")
        path.write_text(text.replace(old, old + member))
    path = source / "wsi_common.c"
    text = path.read_text()
    old = "   VkResult result = chain->image_info.explicit_sync ?\n"
    new = ("   VkResult result = image->droiddeck_display_released ?\n"
           "      VK_ERROR_FEATURE_NOT_PRESENT : chain->image_info.explicit_sync ?\n")
    if new not in text:
        if text.count(old) != 2:
            raise ValueError("Incompatible Mesa acquire sync imports")
        path.write_text(text.replace(old, new))
    apply_kgsl(root)


def apply_kgsl(root):
    source = Path(root) / "src/freedreno/vulkan"
    path = source / "tu_knl_kgsl.cc"
    text = path.read_text()
    if '#include "droiddeck-kgsl-scheduler.h"' not in text:
        def replace(old, new):
            nonlocal text
            if text.count(old) != 1:
                raise ValueError(f"Incompatible Mesa KGSL source: {old[:80]}")
            text = text.replace(old, new)
        replace("static int\nkgsl_submitqueue_new(",
                '#include "droiddeck-kgsl-scheduler.h"\n\nstatic int\nkgsl_submitqueue_new(')
        replace("   queue->msm_queue_id = req.drawctxt_id;\n",
                "   queue->msm_queue_id = req.drawctxt_id;\n"
                "   queue->droiddeck_scheduler = dd_kgsl_register(dev->physical_device->local_fd, req.drawctxt_id);\n")
        replace("kgsl_submitqueue_close(struct tu_device *dev, struct tu_queue *queue)\n{\n",
                "kgsl_submitqueue_close(struct tu_device *dev, struct tu_queue *queue)\n{\n"
                "   dd_kgsl_unregister(queue->droiddeck_scheduler);\n"
                "   queue->droiddeck_scheduler = nullptr;\n")
        path.write_text(text)
    text = path.read_text()
    if "static bool\nkgsl_syncobj_fd_ordered(" in text:
        start = text.index("static bool\nkgsl_syncobj_fd_ordered(")
        end = text.index("static struct kgsl_syncobj\nkgsl_syncobj_merge(", start)
        text = text[:start] + text[end:]
    text = text.replace(
        "kgsl_syncobj_merge(const struct kgsl_syncobj **syncobjs, uint32_t count,\n"
        "                   const struct tu_queue *ordered_queue = nullptr)",
        "kgsl_syncobj_merge(const struct kgsl_syncobj **syncobjs, uint32_t count)")
    text = text.replace("         if (sync->queue == ordered_queue)\n            break;\n", "")
    text = text.replace("         if (sync_wait(sync->fd, 0) == 0 ||\n"
                        "             kgsl_syncobj_fd_ordered(sync->fd, ordered_queue))\n"
                        "            break;\n", "")
    text = text.replace("         if (sync_wait(sync->fd, 0) == 0)\n            break;\n", "")
    text = text.replace("kgsl_syncobj_merge(wait_semaphores, wait_count, queue);",
                        "kgsl_syncobj_merge(wait_semaphores, wait_count);")
    text = text.replace("   dd_kgsl_submitted(queue->droiddeck_scheduler, timestamp);\n", "")
    if "dd_kgsl_begin_submit(queue->droiddeck_scheduler);" not in text:
        old = "   uint64_t gpu_offset = 0;\n"
        if text.count(old) != 1:
            raise ValueError("Incompatible KGSL submission entry")
        text = text.replace(old, old + "\n   dd_kgsl_begin_submit(queue->droiddeck_scheduler);\n")
        old = "fail_submit:\n"
        if text.count(old) != 1:
            raise ValueError("Incompatible KGSL submission cleanup")
        text = text.replace(old, old +
            "   dd_kgsl_end_submit(queue->droiddeck_scheduler, timestamp, result == VK_SUCCESS || timestamp != 0);\n")
    path.write_text(text)
    path = source / "tu_queue.h"
    text = path.read_text()
    member = "   struct droiddeck_kgsl_queue *droiddeck_scheduler;\n"
    if member not in text:
        old = "   uint32_t msm_queue_id;\n"
        if text.count(old) != 1:
            raise ValueError("Incompatible Mesa queue layout")
        path.write_text(text.replace(old, old + member))
    (source / "droiddeck-kgsl-scheduler.h").write_bytes(
        Path(__file__).with_name("mesa-kgsl-scheduler.h").read_bytes())


if __name__ == "__main__":
    apply(sys.argv[1])
