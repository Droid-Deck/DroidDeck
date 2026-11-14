/* Same standalone GPU-copy probe built as Linux AArch64 and Windows x86.
 * Tests actual Vulkan buffers, complete data comparison, noncoherent cache
 * operations, remapping with an offset and queue completion. No Steam API.
 */
#define VK_NO_PROTOTYPES
#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#define IFNS(X) X(DestroyInstance) X(EnumeratePhysicalDevices) X(GetPhysicalDeviceProperties) \
 X(GetPhysicalDeviceMemoryProperties) X(GetPhysicalDeviceQueueFamilyProperties) X(CreateDevice) X(GetDeviceProcAddr)
#define DFNS(X) X(DestroyDevice) X(GetDeviceQueue) X(CreateBuffer) X(DestroyBuffer) \
 X(GetBufferMemoryRequirements) X(AllocateMemory) X(FreeMemory) X(BindBufferMemory) \
 X(MapMemory) X(UnmapMemory) X(FlushMappedMemoryRanges) X(InvalidateMappedMemoryRanges) \
 X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) X(ResetCommandBuffer) \
 X(BeginCommandBuffer) X(EndCommandBuffer) X(CmdCopyBuffer) X(CmdPipelineBarrier) \
 X(CreateFence) X(DestroyFence) X(ResetFences) X(QueueSubmit) X(WaitForFences)
#define DECL(n) static PFN_vk##n p##n;
IFNS(DECL)
DFNS(DECL)
#undef DECL
#define CHECK(x) do { VkResult rc=(x); if(rc!=VK_SUCCESS){printf("PROBE_FAIL stage=%s vk_result=%d\n",#x,rc);goto cleanup;} }while(0)
#define REQUIRE(x,msg) do {if(!(x)){puts("PROBE_FAIL " msg);goto cleanup;}}while(0)

int main(int argc,char **argv)
{
#ifdef _WIN32
    /* Wine/Proton can detach the Windows console from the Linux pipe. */
    if(argc>3 && !freopen(argv[3],"w",stdout)) return 4;
#endif
    setvbuf(stdout,NULL,_IONBF,0);
    puts("PROBE_STAGE main");
    unsigned mib=argc>1?(unsigned)strtoul(argv[1],NULL,10):32;
    int require_low=argc>2&&!strcmp(argv[2],"--require-low32");
    if(mib<1||mib>256)return 2;
    const VkDeviceSize bytes=(VkDeviceSize)mib*1024*1024;
    VkInstance instance=VK_NULL_HANDLE;VkDevice device=VK_NULL_HANDLE;
    VkBuffer buffers[2]={0};VkDeviceMemory memories[2]={0};
    VkDeviceSize allocated[2]={0};void *mapped[2]={0};
    VkCommandPool pool=VK_NULL_HANDLE;VkFence fence=VK_NULL_HANDLE;
    int success=0,pending=0;uint64_t mismatches=0;unsigned iterations=0;
    int low_all=1;uint32_t memory_flags[2]={0};
    PFN_vkGetInstanceProcAddr gipa;
#ifdef _WIN32
    HMODULE loader=LoadLibraryA("vulkan-1.dll");
    REQUIRE(loader,"load_vulkan_failed");
    gipa=(PFN_vkGetInstanceProcAddr)(void *)GetProcAddress(loader,"vkGetInstanceProcAddr");
#else
    void *loader=dlopen("libvulkan.so.1",RTLD_NOW|RTLD_LOCAL);
    REQUIRE(loader,"load_vulkan_failed");
    gipa=(PFN_vkGetInstanceProcAddr)dlsym(loader,"vkGetInstanceProcAddr");
#endif
    REQUIRE(gipa,"missing_gipa");
    puts("PROBE_STAGE vulkan_loader");
    PFN_vkCreateInstance create=(PFN_vkCreateInstance)gipa(NULL,"vkCreateInstance");
    REQUIRE(create,"missing_create_instance");
    VkApplicationInfo app={.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO,
      .pApplicationName="DroidDeck low32 buffer integrity probe",.apiVersion=VK_API_VERSION_1_1};
    VkInstanceCreateInfo ici={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&app};
    CHECK(create(&ici,NULL,&instance));
    puts("PROBE_STAGE instance_created");
#define LOADI(n) p##n=(PFN_vk##n)gipa(instance,"vk" #n);REQUIRE(p##n,"missing_" #n);
    IFNS(LOADI)
#undef LOADI
    uint32_t count=0;CHECK(pEnumeratePhysicalDevices(instance,&count,NULL));
    puts("PROBE_STAGE physical_device_enumeration");
    REQUIRE(count&&count<=16,"physical_device_count");
    VkPhysicalDevice physicals[16],physical=VK_NULL_HANDLE;
    CHECK(pEnumeratePhysicalDevices(instance,&count,physicals));
    VkPhysicalDeviceProperties properties;
    for(uint32_t i=0;i<count;i++){
      pGetPhysicalDeviceProperties(physicals[i],&properties);
      if(properties.vendorID==0x1010&&properties.deviceType!=VK_PHYSICAL_DEVICE_TYPE_CPU){physical=physicals[i];break;}
    }
    REQUIRE(physical,"no_real_powervr");
    printf("PROBE_DEVICE vendor=%x device=%x api=%x pointer_bits=%zu\n",
           properties.vendorID,properties.deviceID,properties.apiVersion,sizeof(void *)*8);
    uint32_t qcount=0;pGetPhysicalDeviceQueueFamilyProperties(physical,&qcount,NULL);
    REQUIRE(qcount&&qcount<=64,"queue_count");
    VkQueueFamilyProperties qp[64];pGetPhysicalDeviceQueueFamilyProperties(physical,&qcount,qp);
    uint32_t family=UINT32_MAX;
    for(uint32_t i=0;i<qcount;i++)if(qp[i].queueCount&&(qp[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)){family=i;break;}
    REQUIRE(family!=UINT32_MAX,"no_graphics_queue");float priority=1.0f;
    VkDeviceQueueCreateInfo qci={.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,.queueFamilyIndex=family,.queueCount=1,.pQueuePriorities=&priority};
    VkDeviceCreateInfo dci={.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.queueCreateInfoCount=1,.pQueueCreateInfos=&qci};
    CHECK(pCreateDevice(physical,&dci,NULL,&device));
#define LOADD(n) p##n=(PFN_vk##n)pGetDeviceProcAddr(device,"vk" #n);REQUIRE(p##n,"missing_" #n);
    DFNS(LOADD)
#undef LOADD
    VkPhysicalDeviceMemoryProperties mp;pGetPhysicalDeviceMemoryProperties(physical,&mp);
    for(unsigned j=0;j<2;j++){
      VkBufferCreateInfo bci={.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,.size=bytes,
        .usage=VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT,.sharingMode=VK_SHARING_MODE_EXCLUSIVE};
      CHECK(pCreateBuffer(device,&bci,NULL,&buffers[j]));VkMemoryRequirements mr;
      pGetBufferMemoryRequirements(device,buffers[j],&mr);
      uint32_t type=UINT32_MAX;
      for(uint32_t i=0;i<mp.memoryTypeCount;i++)if((mr.memoryTypeBits&(1u<<i))&&(mp.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)){type=i;break;}
      REQUIRE(type!=UINT32_MAX,"no_host_visible_memory");memory_flags[j]=mp.memoryTypes[type].propertyFlags;
      VkMemoryAllocateInfo mai={.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,.allocationSize=mr.size,.memoryTypeIndex=type};
      allocated[j]=mr.size;CHECK(pAllocateMemory(device,&mai,NULL,&memories[j]));
      CHECK(pBindBufferMemory(device,buffers[j],memories[j],0));
    }
    VkCommandPoolCreateInfo pci={.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
      .flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,.queueFamilyIndex=family};
    CHECK(pCreateCommandPool(device,&pci,NULL,&pool));VkCommandBuffer command;
    VkCommandBufferAllocateInfo cai={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
      .commandPool=pool,.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY,.commandBufferCount=1};
    CHECK(pAllocateCommandBuffers(device,&cai,&command));
    VkFenceCreateInfo fci={.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    CHECK(pCreateFence(device,&fci,NULL,&fence));VkQueue queue;pGetDeviceQueue(device,family,0,&queue);
    for(unsigned k=0;k<4;k++){
      /* Alternate full mapping and a 64KiB aligned offset, preserving every word. */
      VkDeviceSize offset=(k&1)?65536:0,span=bytes-offset;
      for(unsigned j=0;j<2;j++){
        CHECK(pMapMemory(device,memories[j],offset,VK_WHOLE_SIZE,0,&mapped[j]));
        int low=(uint64_t)(uintptr_t)mapped[j]+(allocated[j]-offset)<=UINT64_C(0x100000000);
        low_all&=low;
        printf("PROBE_MAP iteration=%u buffer=%u offset=%" PRIu64 " bytes=%" PRIu64 " ptr=%p low32=%d flags=%x\n",
          k,j,(uint64_t)offset,(uint64_t)(allocated[j]-offset),mapped[j],low,memory_flags[j]);
        REQUIRE(!require_low||low,"mapping_not_low32");
      }
      uint32_t *src=mapped[0],*dst=mapped[1];size_t words=(size_t)(span/4);
      for(size_t i=0;i<words;i++){src[i]=((uint32_t)i*2654435761u)^(0xa5a50000u+k);dst[i]=0;}
      VkMappedMemoryRange ranges[2];memset(ranges,0,sizeof(ranges));
      for(unsigned j=0;j<2;j++)ranges[j]=(VkMappedMemoryRange){.sType=VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,.memory=memories[j],.offset=offset,.size=VK_WHOLE_SIZE};
      CHECK(pFlushMappedMemoryRanges(device,2,ranges));
      if(k){CHECK(pResetFences(device,1,&fence));CHECK(pResetCommandBuffer(command,0));}
      VkCommandBufferBeginInfo cbi={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
      CHECK(pBeginCommandBuffer(command,&cbi));
      VkMemoryBarrier before={.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER,.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT,.dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT};
      pCmdPipelineBarrier(command,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,1,&before,0,NULL,0,NULL);
      VkBufferCopy copy={.srcOffset=offset,.dstOffset=offset,.size=span};pCmdCopyBuffer(command,buffers[0],buffers[1],1,&copy);
      VkMemoryBarrier after={.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER,.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT,.dstAccessMask=VK_ACCESS_HOST_READ_BIT};
      pCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&after,0,NULL,0,NULL);
      CHECK(pEndCommandBuffer(command));VkSubmitInfo si={.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO,.commandBufferCount=1,.pCommandBuffers=&command};
      CHECK(pQueueSubmit(queue,1,&si,fence));pending=1;
      VkResult waited=pWaitForFences(device,1,&fence,VK_TRUE,UINT64_C(10000000000));
      if(waited!=VK_SUCCESS){printf("PROBE_FAIL GPU_COMPLETION_UNCONFIRMED result=%d\n",waited);goto cleanup;}
      pending=0;CHECK(pInvalidateMappedMemoryRanges(device,1,&ranges[1]));
      for(size_t i=0;i<words;i++)if(dst[i]!=(((uint32_t)i*2654435761u)^(0xa5a50000u+k)))mismatches++;
      for(unsigned j=0;j<2;j++){pUnmapMemory(device,memories[j]);mapped[j]=NULL;}
      iterations++;REQUIRE(!mismatches,"data_mismatch");
    }
    success=1;
cleanup:
    printf("PROBE_RESULT success=%d mib=%u iterations=%u mismatches=%" PRIu64 " all_mappings_low32=%d\n",success,mib,iterations,mismatches,low_all);
    if(pending)return 3; /* Do not explicitly destroy objects with unknown GPU completion. */
    if(device){
      if(pUnmapMemory)for(unsigned j=0;j<2;j++)if(mapped[j])pUnmapMemory(device,memories[j]);
      if(fence&&pDestroyFence)pDestroyFence(device,fence,NULL);
      if(pool&&pDestroyCommandPool)pDestroyCommandPool(device,pool,NULL);
      for(unsigned j=0;j<2;j++)if(buffers[j]&&pDestroyBuffer)pDestroyBuffer(device,buffers[j],NULL);
      for(unsigned j=0;j<2;j++)if(memories[j]&&pFreeMemory)pFreeMemory(device,memories[j],NULL);
      if(pDestroyDevice)pDestroyDevice(device,NULL);
    }
    if(instance&&pDestroyInstance)pDestroyInstance(instance,NULL);
    return success?0:1;
}
