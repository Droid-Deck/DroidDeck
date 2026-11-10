/* SPDX-License-Identifier: MIT */
#include "droiddeck-ahb-protocol.h"
#include <stddef.h>
#include <sys/socket.h>
#include <sys/un.h>

static bool
droiddeck_image_released(struct wsi_wl_image *image)
{
   if (image->droiddeck_broker < 0 || !image->droiddeck_release_events)
      return false;
   bool completed = false;
   for (;;) {
      struct droiddeck_ahb_release release;
      ssize_t length = recv(image->droiddeck_broker, &release, sizeof(release),
                            MSG_DONTWAIT | MSG_TRUNC);
      if (length < 0 && errno == EINTR)
         continue;
      if (length < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
         break;
      if (length != sizeof(release) || release.magic != DROIDDECK_AHB_RELEASE_MAGIC ||
          release.reserved || !release.serial) {
         image->droiddeck_release_events = false;
         return false;
      }
      if (release.serial > image->droiddeck_release_serial) {
         if (!image->droiddeck_release_serial)
            fprintf(stderr, "DroidDeck AHB WSI: native release confirmed before image acquire\n");
         image->droiddeck_release_serial = release.serial;
         completed = true;
      }
   }
   return completed;
}

static VkResult
droiddeck_create_ahb_image(struct wsi_wl_swapchain *chain,
                         struct wsi_wl_image *image)
{
   if (!debug_get_bool_option("DROIDDECK_AHB", true) ||
       chain->buffer_type != WSI_WL_BUFFER_NATIVE ||
       chain->base.blit.type != WSI_SWAPCHAIN_NO_BLIT ||
       (chain->vk_format != VK_FORMAT_R8G8B8A8_UNORM &&
        chain->vk_format != VK_FORMAT_R8G8B8A8_SRGB &&
        chain->vk_format != VK_FORMAT_B8G8R8A8_UNORM &&
        chain->vk_format != VK_FORMAT_B8G8R8A8_SRGB &&
        chain->vk_format != VK_FORMAT_A2B10G10R10_UNORM_PACK32) ||
       chain->base.image_info.explicit_sync)
      return VK_ERROR_FEATURE_NOT_PRESENT;
   int broker = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
   if (broker < 0) return VK_ERROR_FEATURE_NOT_PRESENT;
   struct sockaddr_un address = {.sun_family = AF_UNIX};
   int length = snprintf(address.sun_path + 1, sizeof(address.sun_path) - 1,
                         "%s-%u", DROIDDECK_AHB_SOCKET, (unsigned)getuid());
   struct timeval timeout = {.tv_sec = 2};
   setsockopt(broker, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
   if (connect(broker, (struct sockaddr *)&address,
               offsetof(struct sockaddr_un, sun_path) + 1 + length) != 0) {
      close(broker);
      return VK_ERROR_FEATURE_NOT_PRESENT;
   }
   struct droiddeck_ahb_request request = {DROIDDECK_AHB_MAGIC,
      chain->extent.width, chain->extent.height, chain->drm_format};
   struct droiddeck_ahb_reply reply;
   struct iovec iov = {.iov_base = &reply, .iov_len = sizeof(reply)};
   char control[CMSG_SPACE(sizeof(int))] = {0};
   struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1,
      .msg_control = control, .msg_controllen = sizeof(control)};
   int fd = -1;
   if (send(broker, &request, sizeof(request), MSG_NOSIGNAL) == sizeof(request) &&
       recvmsg(broker, &message, MSG_CMSG_CLOEXEC) == sizeof(reply) &&
       !(message.msg_flags & (MSG_TRUNC | MSG_CTRUNC))) {
      for (struct cmsghdr *header = CMSG_FIRSTHDR(&message); header;
           header = CMSG_NXTHDR(&message, header)) {
         if (header->cmsg_level == SOL_SOCKET && header->cmsg_type == SCM_RIGHTS &&
             header->cmsg_len == CMSG_LEN(sizeof(int)))
            memcpy(&fd, CMSG_DATA(header), sizeof(fd));
      }
   }
   if (fd < 0 || reply.status || reply.modifier != DRM_FORMAT_MOD_LINEAR ||
       reply.offset || reply.stride < chain->extent.width * 4 ||
       reply.size < (uint64_t)reply.stride * chain->extent.height) {
      if (fd >= 0) close(fd);
      close(broker);
      return VK_ERROR_FEATURE_NOT_PRESENT;
   }
   const struct wsi_swapchain *base = &chain->base;
   const struct wsi_device *wsi = base->wsi;
   struct wsi_image *output = &image->base;
   memset(output, 0, sizeof(*output));
   output->dma_buf_fd = -1;
   for (uint32_t i = 0; i < WSI_ES_COUNT; ++i) output->explicit_sync[i].fd = -1;
   VkSubresourceLayout layout = {.offset = reply.offset, .rowPitch = reply.stride};
   VkImageDrmFormatModifierExplicitCreateInfoEXT modifier = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT,
      .drmFormatModifier = reply.modifier, .drmFormatModifierPlaneCount = 1,
      .pPlaneLayouts = &layout,
   };
   VkExternalMemoryImageCreateInfo external = {
      .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, .pNext = &modifier,
      .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
   };
   VkImageFormatListCreateInfo format_list = base->image_info.format_list;
   format_list.pNext = &external;
   VkImageCreateInfo create = base->image_info.create;
   create.pNext = (create.flags & VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT) ? (void *)&format_list : (void *)&external;
   create.tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT;
   VkResult result = wsi->CreateImage(base->device, &create, &base->alloc, &output->image);
   if (result != VK_SUCCESS) goto fail;
   VkMemoryRequirements requirements;
   wsi->GetImageMemoryRequirements(base->device, output->image, &requirements);
   if (requirements.size > reply.size) { result = VK_ERROR_INVALID_EXTERNAL_HANDLE; goto fail; }
   VkMemoryFdPropertiesKHR properties = {.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
   if (!wsi->GetMemoryFdPropertiesKHR) { result = VK_ERROR_FEATURE_NOT_PRESENT; goto fail; }
   result = wsi->GetMemoryFdPropertiesKHR(base->device, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, fd, &properties);
   if (result != VK_SUCCESS) goto fail;
   uint32_t memory_types = requirements.memoryTypeBits & properties.memoryTypeBits;
   if (!memory_types) { result = VK_ERROR_INVALID_EXTERNAL_HANDLE; goto fail; }
   const struct wsi_memory_allocate_info sync = {
      .sType = VK_STRUCTURE_TYPE_WSI_MEMORY_ALLOCATE_INFO_MESA,
      .implicit_sync = !base->dma_buf_semaphore, .dma_buf_sync_file = base->dma_buf_semaphore,
   };
   int import_fd = dup(fd);
   if (import_fd < 0) { result = VK_ERROR_OUT_OF_HOST_MEMORY; goto fail; }
   VkImportMemoryFdInfoKHR import = {
      .sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR, .pNext = &sync,
      .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, .fd = import_fd,
   };
   VkMemoryDedicatedAllocateInfo dedicated = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, .pNext = &import,
      .image = output->image,
   };
   VkMemoryAllocateInfo allocate = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &dedicated,
      .allocationSize = requirements.size,
      .memoryTypeIndex = wsi_select_device_memory_type(wsi, memory_types),
   };
   result = wsi->AllocateMemory(base->device, &allocate, &base->alloc, &output->memory);
   if (result != VK_SUCCESS) { close(import_fd); goto fail; }
   result = wsi->BindImageMemory(base->device, output->image, output->memory, 0);
   if (result != VK_SUCCESS) goto fail;
   output->dma_buf_fd = fd;
   output->num_planes = 1;
   output->drm_modifier = reply.modifier;
   output->row_pitches[0] = reply.stride;
   output->offsets[0] = reply.offset;
   output->sizes[0] = reply.size;
   image->droiddeck_broker = broker;
   image->droiddeck_release_serial = 0;
   image->droiddeck_release_events = (reply.reserved & DROIDDECK_AHB_RELEASE_EVENTS) != 0;
   fprintf(stderr, "DroidDeck AHB WSI: %ux%u vkFormat=%u stride=%u imported, no presentation blit\n",
           chain->extent.width, chain->extent.height, chain->vk_format, reply.stride);
   return VK_SUCCESS;
fail:
   wsi_destroy_image(base, output);
   close(fd);
   close(broker);
   return result;
}
