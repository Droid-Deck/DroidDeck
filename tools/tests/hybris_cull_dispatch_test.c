#define _GNU_SOURCE
#define VK_NO_PROTOTYPES
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "shader_dispatch.c"

const struct hybris_scaled_format_pair hybris_scaled_formats[HYBRIS_SCALED_FORMAT_COUNT] = {{0}};
static unsigned downstream_calls;
static unsigned downstream_stage_count, downstream_geometry_count;
static unsigned backend_shader_creates, backend_shader_destroys;
static uintptr_t next_handle = 0x100;
static VkPipelineCreateFlags pipeline_flags;
static uint32_t rendering_view_mask;
static int rendering_view_mask_known = 1;

int hybris_mali_spec_enabled(void) { return 0; }
int hybris_mali_spec_active(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_polygon_emulated(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_dual_source_requested(const VkDeviceCreateInfo *i) { (void)i; return 0; }
int hybris_dual_source_active(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_multiviewport_requested(const VkDeviceCreateInfo *i) { (void)i; return 0; }
int hybris_multiviewport_active(VkPhysicalDevice p) { (void)p; return 0; }
uint32_t hybris_multiviewport_count(unsigned c, uint32_t f, uint32_t n)
{ (void)c; (void)f; return n; }
int hybris_stage_point_active(VkPhysicalDevice p) { (void)p; return 0; }
VkResult hybris_spirv_mali_spec_constants(uint32_t *c, size_t s,
    const VkAllocationCallbacks *a, unsigned *n)
{ (void)c; (void)s; (void)a; *n = 0; return VK_SUCCESS; }
VkBlendFactor hybris_dual_source_factor(VkBlendFactor f) { return f; }
int hybris_dual_source_outputs(const uint32_t *c, size_t s) { (void)c; (void)s; return 0; }
VkResult hybris_dual_source_spirv(uint32_t *c, size_t s, uint32_t a, uint32_t l)
{ (void)c; (void)s; (void)a; (void)l; return VK_SUCCESS; }
void hybris_polygon_note(const char *r) { (void)r; }
int hybris_scaled_enabled(void) { return 0; }
unsigned hybris_scaled_mask(VkDevice d, VkPhysicalDevice p, PFN_vkGetPhysicalDeviceFormatProperties q)
{ (void)d; (void)p; (void)q; return 0; }
int hybris_point_size_enabled(void) { return 0; }
int hybris_point_size_pipeline(const VkGraphicsPipelineCreateInfo *i) { (void)i; return 0; }
int hybris_inout_enabled(void) { return 0; }
int hybris_inout_pipeline(const VkGraphicsPipelineCreateInfo *i) { (void)i; return 0; }
int hybris_image_bounds_enabled(void) { return 0; }
int hybris_clip_enabled(void) { return 1; }
int hybris_clip_active(VkPhysicalDevice p) { (void)p; return 1; }
int hybris_vertex_stores_active(VkPhysicalDevice p) { (void)p; return 0; }
int hybris_shader_cleanup_enabled(void) { return 0; }
/* Upstream polygon-mode/cull emulation and BC layout identities: inactive here. */
int hybris_cull_active(VkPhysicalDevice p) { (void)p; return 0; }
struct hybris_bc_device *hybris_bc_device_find(VkDevice h) { (void)h; return NULL; }
VkResult hybris_bc_layout_acquire(struct hybris_bc_layouts *l, VkPipelineLayout h, struct hybris_bc_layout **o)
{ (void)l; (void)h; *o = NULL; return VK_SUCCESS; }
void hybris_bc_layout_release(struct hybris_bc_layouts *l, struct hybris_bc_layout *o) { (void)l; (void)o; }
int hybris_layer_lookup_physical(VkPhysicalDevice p, struct hybris_layer_physical *o) { (void)p; (void)o; return 0; }
const struct hybris_polygon_layout *hybris_polygon_layout_acquire(VkDevice d, VkPipelineLayout l)
{ (void)d; (void)l; return NULL; }
void hybris_polygon_layout_release(const struct hybris_polygon_layout *l) { (void)l; }
VkResult hybris_polygon_pipeline_create(VkDevice d, PFN_vkGetDeviceProcAddr r,
    const VkGraphicsPipelineCreateInfo *g, VkPolygonMode m,
    const VkAllocationCallbacks *a, struct hybris_polygon_pipeline **o)
{ (void)d; (void)r; (void)g; (void)m; (void)a; *o = NULL; return VK_ERROR_FEATURE_NOT_PRESENT; }
void hybris_polygon_pipeline_destroy(struct hybris_polygon_pipeline *p) { (void)p; }
void hybris_polygon_pipeline_publish(struct hybris_polygon_pipeline *p) { (void)p; }
int hybris_polygon_pipeline_remove(VkDevice d, VkPipeline h) { (void)d; (void)h; return 0; }
VkResult hybris_shader_cleanup_code(const uint32_t *c, size_t s,
    const VkAllocationCallbacks *a, uint32_t **o, size_t *os)
{ (void)c; (void)s; (void)a; *o = NULL; *os = 0; return VK_SUCCESS; }
int hybris_shader_command_allowed(const char *n) { (void)n; return 1; }
int hybris_clip_command_allowed(const char *n) { return strcmp(n, "vkCreateShadersEXT"); }
int hybris_clip_pipeline(const VkGraphicsPipelineCreateInfo *info)
{
    unsigned vertex = 0, fragment = 0;
    if (!info || (info->flags & VK_PIPELINE_CREATE_LIBRARY_BIT_KHR)) return 0;
    for (uint32_t i = 0; i < info->stageCount; ++i) {
        if (info->pStages[i].pNext) return 0;
        vertex += info->pStages[i].stage == VK_SHADER_STAGE_VERTEX_BIT;
        fragment += info->pStages[i].stage == VK_SHADER_STAGE_FRAGMENT_BIT;
        if (info->pStages[i].stage != VK_SHADER_STAGE_VERTEX_BIT &&
            info->pStages[i].stage != VK_SHADER_STAGE_FRAGMENT_BIT) return 0;
    }
    return vertex == 1 && fragment == 1;
}
VkResult hybris_inline_prepare(VkDevice d, VkGraphicsPipelineCreateInfo *i,
    const VkAllocationCallbacks *a, PFN_vkCreateShaderModule c,
    struct hybris_inline_stages *p, const char **r)
{ (void)d; (void)i; (void)a; (void)c; (void)r; *p = (struct hybris_inline_stages){0}; return VK_SUCCESS; }
void hybris_inline_release(VkDevice d, const VkAllocationCallbacks *a,
    PFN_vkDestroyShaderModule f, struct hybris_inline_stages *p)
{ (void)d; (void)a; (void)f; *p = (struct hybris_inline_stages){0}; }
VkResult VKAPI_CALL hybris_shader_cleanup_create(VkDevice d,
    const VkShaderModuleCreateInfo *i, const VkAllocationCallbacks *a, VkShaderModule *m)
{ (void)d; (void)i; (void)a; (void)m; return VK_ERROR_UNKNOWN; }
VkResult hybris_scaled_spirv(const uint32_t *c, size_t s, const char *e,
    const struct hybris_scaled_attribute *at, uint32_t n, const VkSpecializationInfo *sp,
    const VkAllocationCallbacks *a, uint32_t **o, size_t *os, const char **r)
{ (void)c;(void)s;(void)e;(void)at;(void)n;(void)sp;(void)a;(void)o;(void)os;(void)r; return VK_ERROR_UNKNOWN; }
void hybris_scaled_dump(const uint32_t *a,size_t b,const uint32_t *c,size_t d,
    const struct hybris_scaled_attribute *e,uint32_t f,const VkSpecializationInfo *g)
{ (void)a;(void)b;(void)c;(void)d;(void)e;(void)f;(void)g; }
VkResult hybris_spirv_point_size(const uint32_t *c,size_t s,const VkAllocationCallbacks *a,
    uint32_t **o,size_t *os,unsigned *r)
{ (void)c;(void)s;(void)a;*o=NULL;*os=0;*r=0;return VK_SUCCESS; }
VkResult hybris_spirv_inout(const uint32_t *v,size_t vs,const uint32_t *f,size_t fs,
    const VkAllocationCallbacks *a,uint32_t **o,size_t *os,unsigned *w)
{ (void)v;(void)vs;(void)f;(void)fs;(void)a;*o=NULL;*os=0;*w=0;return VK_SUCCESS; }
VkResult hybris_spirv_image_bounds(const uint32_t *c,size_t s,const VkAllocationCallbacks *a,
    uint32_t **o,size_t *os,unsigned *w)
{ (void)c;(void)s;(void)a;*o=NULL;*os=0;*w=0;return VK_SUCCESS; }
VkResult hybris_spirv_storage_writes(const uint32_t *c,size_t s,
    const VkAllocationCallbacks *a,int *w)
{ (void)c;(void)s;(void)a;*w=0;return VK_SUCCESS; }
VkResult hybris_spirv_discard(const uint32_t *c,size_t s,uint32_t m,const char *e,
    const VkAllocationCallbacks *a,uint32_t **o,size_t *os,const char **r)
{ (void)c;(void)s;(void)m;(void)e;(void)a;(void)o;(void)os;(void)r;return VK_ERROR_UNKNOWN; }
VkResult hybris_vertex_rendering_info(VkDevice d,const VkGraphicsPipelineCreateInfo *i,
    struct hybris_vertex_rendering *o)
{
    (void)d;(void)i;*o=(struct hybris_vertex_rendering){
        .samples=VK_SAMPLE_COUNT_1_BIT,.view_mask=rendering_view_mask,
        .view_mask_known=rendering_view_mask_known};return VK_SUCCESS;
}

static VkResult VKAPI_CALL backend_create_shader(VkDevice d,const VkShaderModuleCreateInfo *i,
    const VkAllocationCallbacks *a,VkShaderModule *m)
{ (void)d;(void)i;(void)a;++backend_shader_creates;*m=(VkShaderModule)next_handle++;return VK_SUCCESS; }
static void VKAPI_CALL backend_destroy_shader(VkDevice d,VkShaderModule m,const VkAllocationCallbacks *a)
{ (void)d;(void)m;(void)a;++backend_shader_destroys; }
static VkResult VKAPI_CALL backend_create_pipelines(VkDevice d,VkPipelineCache c,uint32_t n,
    const VkGraphicsPipelineCreateInfo *i,const VkAllocationCallbacks *a,VkPipeline *p)
{
    (void)d; (void)c; (void)a; ++downstream_calls;
    downstream_stage_count = i[0].stageCount; downstream_geometry_count = 0;
    for (uint32_t s = 0; s < i[0].stageCount; ++s)
        downstream_geometry_count += i[0].pStages[s].stage == VK_SHADER_STAGE_GEOMETRY_BIT;
    for(uint32_t x=0;x<n;++x)p[x]=(VkPipeline)next_handle++;
    return VK_SUCCESS;
}

static uint32_t *load(const char *path, size_t *size)
{
    FILE *f=fopen(path,"rb"); assert(f); assert(!fseek(f,0,SEEK_END)); long n=ftell(f); assert(n>=20 && !(n%4));
    assert(!fseek(f,0,SEEK_SET)); uint32_t *p=malloc((size_t)n); assert(p); assert(fread(p,1,(size_t)n,f)==(size_t)n); fclose(f); *size=(size_t)n; return p;
}
static VkShaderModule capture(struct shader_device *device,const uint32_t *code,size_t size)
{
    VkShaderModuleCreateInfo info={.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,.codeSize=size,.pCode=code};
    VkShaderModule module=VK_NULL_HANDLE; assert(create_shader(device->handle,&info,NULL,&module)==VK_SUCCESS); return module;
}
static VkResult run(struct shader_device *device,VkPipelineShaderStageCreateInfo *stages,uint32_t count)
{
    VkGraphicsPipelineCreateInfo info={.sType=VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .stageCount=count,.pStages=stages}; VkPipeline pipeline=VK_NULL_HANDLE;
    return create_pipelines(device->handle,VK_NULL_HANDLE,1,&info,NULL,&pipeline);
}
static VkResult run_cull(struct shader_device *device, VkPipelineShaderStageCreateInfo *stages,
    uint32_t count, VkPrimitiveTopology topology, int discard, const void *raster_next,
    const void *dynamic_next, VkDynamicState *states, uint32_t state_count,
    const void *pipeline_next)
{
    VkPipelineInputAssemblyStateCreateInfo assembly = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = topology};
    VkPipelineRasterizationStateCreateInfo raster = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .pNext = raster_next, .rasterizerDiscardEnable = discard,
        .polygonMode = VK_POLYGON_MODE_FILL, .lineWidth = 1.0f};
    VkPipelineDynamicStateCreateInfo dynamic = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
        .pNext = dynamic_next, .dynamicStateCount = state_count,
        .pDynamicStates = states};
    VkGraphicsPipelineCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .pNext = pipeline_next,
        .flags = pipeline_flags,
        .stageCount = count, .pStages = stages,
        .pInputAssemblyState = &assembly, .pRasterizationState = &raster,
        .pDynamicState = state_count || dynamic_next ? &dynamic : NULL};
    VkPipeline pipeline = VK_NULL_HANDLE;
    return create_pipelines(device->handle, VK_NULL_HANDLE, 1, &info, NULL, &pipeline);
}
int main(int argc,char **argv)
{
    VkPhysicalDeviceMultiviewFeatures multiview = {
        .sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MULTIVIEW_FEATURES,
        .multiview=VK_TRUE,.multiviewGeometryShader=VK_FALSE};
    VkDeviceCreateInfo requested = {.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.pNext=&multiview};
    assert(!multiview_geometry_requested(&requested));
    multiview.multiviewGeometryShader=VK_TRUE;
    assert(multiview_geometry_requested(&requested));
    multiview.multiview=VK_FALSE;
    assert(!multiview_geometry_requested(&requested));
    VkPhysicalDeviceVulkan11Features vulkan11 = {
        .sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES,
        .multiview=VK_TRUE,.multiviewGeometryShader=VK_TRUE};
    requested.pNext=&vulkan11;
    assert(multiview_geometry_requested(&requested));
    assert(argc==4); size_t vs,fs,gs; uint32_t *vertex=load(argv[1],&vs),
        *fragment=load(argv[2],&fs),*geometry=load(argv[3],&gs);
    struct shader_device device={.handle=(VkDevice)(uintptr_t)0x42,.create_shader=backend_create_shader,
        .destroy_shader=backend_destroy_shader,.create_pipelines=backend_create_pipelines,
        .cull=1,.cull_location_limit=16};
    devices=&device;
    assert(!hybris_shader_device_proc_allowed(device.handle,"vkCreateShadersEXT"));
    assert(hybris_shader_device_proc_allowed(device.handle,"vkCmdDraw"));
    VkPipelineShaderStageCreateInfo pair[2]={
        {.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,.stage=VK_SHADER_STAGE_VERTEX_BIT,.pName="main"},
        {.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,.stage=VK_SHADER_STAGE_FRAGMENT_BIT,.pName="main"}};
    pair[0].module=capture(&device,vertex,vs); pair[1].module=capture(&device,fragment,fs); downstream_calls=0;
    assert(device.shaders && device.shaders->next);
    assert((device.shaders->handle == pair[0].module || device.shaders->handle == pair[1].module));
    assert(run(&device,pair,2)==VK_ERROR_FEATURE_NOT_PRESENT); assert(downstream_calls==0);
    downstream_calls=0;
    unsigned creates_before=backend_shader_creates, destroys_before=backend_shader_destroys;
    assert(run_cull(&device,pair,2,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,NULL)==VK_SUCCESS);
    assert(downstream_calls==1 && downstream_stage_count==3 && downstream_geometry_count==1);
    assert(backend_shader_creates-creates_before==2 && backend_shader_destroys-destroys_before==2);
    rendering_view_mask=3; downstream_calls=0;
    assert(run_cull(&device,pair,2,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,NULL)==VK_ERROR_FEATURE_NOT_PRESENT);
    assert(!downstream_calls);
    device.cull_multiview_geometry=1; downstream_calls=0;
    assert(run_cull(&device,pair,2,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,NULL)==VK_SUCCESS);
    assert(downstream_calls==1 && downstream_stage_count==3);
    device.cull_multiview_geometry=0; rendering_view_mask=0; rendering_view_mask_known=0;
    downstream_calls=0;
    assert(run_cull(&device,pair,2,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,NULL)==VK_ERROR_FEATURE_NOT_PRESENT);
    assert(!downstream_calls);
    rendering_view_mask_known=1;
    device.cull=0; rendering_view_mask=7; rendering_view_mask_known=0; downstream_calls=0;
    assert(run_cull(&device,pair,2,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,NULL)==VK_SUCCESS);
    assert(downstream_calls==1); device.cull=1; rendering_view_mask=0; rendering_view_mask_known=1;
    VkPipelineCreationFeedback pipeline_feedback = {0}, stage_feedback[2] = {{0}};
    VkPipelineCreationFeedbackCreateInfo feedback = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_CREATION_FEEDBACK_CREATE_INFO,
        .pPipelineCreationFeedback = &pipeline_feedback};
    VkPipelineRenderingCreateInfo rendering = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO, .pNext = &feedback};
    downstream_calls=0;
    assert(run_cull(&device,pair,2,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,&rendering)==VK_SUCCESS);
    assert(downstream_calls==1 && downstream_stage_count==3);
    feedback.pipelineStageCreationFeedbackCount=2;
    feedback.pPipelineStageCreationFeedbacks=stage_feedback;
    downstream_calls=0;
    assert(run_cull(&device,pair,2,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,&feedback)==VK_ERROR_FEATURE_NOT_PRESENT);
    assert(!downstream_calls);
    VkPipelineRobustnessCreateInfoEXT robustness = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_ROBUSTNESS_CREATE_INFO_EXT};
    VkPipelineFragmentShadingRateStateCreateInfoKHR shading_rate = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_FRAGMENT_SHADING_RATE_STATE_CREATE_INFO_KHR,
        .pNext = &robustness};
    downstream_calls=0;
    assert(run_cull(&device,pair,2,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,
        &shading_rate)==VK_SUCCESS);
    assert(downstream_calls==1 && downstream_stage_count==3);
    VkPipelineCreateFlags2CreateInfo flags2 = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_CREATE_FLAGS_2_CREATE_INFO,
        .flags = VK_PIPELINE_CREATE_2_LIBRARY_BIT_KHR};
    VkPipelineBinaryInfoKHR binaries = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_BINARY_INFO_KHR,
        .binaryCount = 1, .pPipelineBinaries = (const VkPipelineBinaryKHR *)(uintptr_t)1};
    VkGraphicsPipelineShaderGroupsCreateInfoNV groups = {
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_SHADER_GROUPS_CREATE_INFO_NV,
        .groupCount = 1};
    const void *unsupported_chains[] = {&flags2, &binaries, &groups};
    for (unsigned i = 0; i < 3; ++i) {
        downstream_calls=0;
        assert(run_cull(&device,pair,2,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,
            unsupported_chains[i])==VK_ERROR_FEATURE_NOT_PRESENT);
        assert(!downstream_calls);
    }
    flags2.flags=VK_PIPELINE_CREATE_2_DISABLE_OPTIMIZATION_BIT;
    downstream_calls=0;
    assert(run_cull(&device,pair,2,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,&flags2)==VK_SUCCESS);
    assert(downstream_calls==1 && downstream_stage_count==3);
    pipeline_flags=VK_PIPELINE_CREATE_CAPTURE_STATISTICS_BIT_KHR; downstream_calls=0;
    assert(run_cull(&device,pair,2,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,NULL)==VK_ERROR_FEATURE_NOT_PRESENT);
    assert(!downstream_calls); pipeline_flags=0;
    const VkBaseInStructure extension = {.sType = (VkStructureType)0x7ffffff0};
    VkDynamicState dynamic = VK_DYNAMIC_STATE_PRIMITIVE_TOPOLOGY;
#define REJECT_CULL(topology, discard, raster_next, dynamic_next, states, state_count) do { \
    downstream_calls=0; \
    assert(run_cull(&device,pair,2,topology,discard,raster_next,dynamic_next,states,state_count,NULL)==VK_ERROR_FEATURE_NOT_PRESENT); \
    assert(!downstream_calls); \
} while (0)
    REJECT_CULL(VK_PRIMITIVE_TOPOLOGY_PATCH_LIST,0,NULL,NULL,NULL,0);
    REJECT_CULL(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,1,NULL,NULL,NULL,0);
    REJECT_CULL(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,&extension,NULL,NULL,0);
    REJECT_CULL(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,&extension,NULL,0);
    REJECT_CULL(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,&dynamic,1);
    device.cull_location_limit=1; downstream_calls=0;
    assert(run_cull(&device,pair,2,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,NULL)==VK_ERROR_FEATURE_NOT_PRESENT);
    assert(!downstream_calls); device.cull_location_limit=16;
    VkPipelineShaderStageCreateInfo triple[3] = {pair[0], pair[1], pair[0]};
    triple[2].stage = VK_SHADER_STAGE_GEOMETRY_BIT;
    triple[2].module = capture(&device,geometry,gs);
    downstream_calls=0;
    assert(run_cull(&device,triple,3,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,0,NULL,NULL,NULL,0,NULL)==VK_ERROR_FEATURE_NOT_PRESENT);
    assert(!downstream_calls);
    destroy_shader(device.handle,triple[2].module,NULL);
#undef REJECT_CULL
    destroy_shader(device.handle,pair[0].module,NULL); destroy_shader(device.handle,pair[1].module,NULL);
    assert(backend_shader_creates==backend_shader_destroys);
    assert(!device.shaders); devices=NULL; free(vertex);free(fragment);free(geometry);
    puts("hybris clip dispatch contract ok"); return 0;
}
