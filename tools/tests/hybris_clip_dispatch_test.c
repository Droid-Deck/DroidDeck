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
static uintptr_t next_handle = 0x100;

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
int hybris_shader_command_allowed(const char *n) { (void)n; return 1; }
int hybris_clip_command_allowed(const char *n) { (void)n; return 1; }
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
{ (void)d;(void)i;*o=(struct hybris_vertex_rendering){0,VK_SAMPLE_COUNT_1_BIT};return VK_SUCCESS; }

static VkResult VKAPI_CALL backend_create_shader(VkDevice d,const VkShaderModuleCreateInfo *i,
    const VkAllocationCallbacks *a,VkShaderModule *m)
{ (void)d;(void)i;(void)a;*m=(VkShaderModule)next_handle++;return VK_SUCCESS; }
static void VKAPI_CALL backend_destroy_shader(VkDevice d,VkShaderModule m,const VkAllocationCallbacks *a)
{ (void)d;(void)m;(void)a; }
static VkResult VKAPI_CALL backend_create_pipelines(VkDevice d,VkPipelineCache c,uint32_t n,
    const VkGraphicsPipelineCreateInfo *i,const VkAllocationCallbacks *a,VkPipeline *p)
{ (void)d;(void)c;(void)i;(void)a;++downstream_calls;for(uint32_t x=0;x<n;++x)p[x]=(VkPipeline)next_handle++;return VK_SUCCESS; }

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
int main(int argc,char **argv)
{
    assert(argc==5); size_t as,us,vs,fs; uint32_t *active=load(argv[1],&as),*unused=load(argv[2],&us),
        *vertex=load(argv[3],&vs),*fragment=load(argv[4],&fs);
    struct shader_device device={.handle=(VkDevice)(uintptr_t)0x42,.create_shader=backend_create_shader,
        .destroy_shader=backend_destroy_shader,.create_pipelines=backend_create_pipelines,.clip=1};
    devices=&device;
    VkPipelineShaderStageCreateInfo stage={.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
        .stage=VK_SHADER_STAGE_GEOMETRY_BIT,.pName="main"};
    stage.module=capture(&device,active,as); downstream_calls=0;
    assert(run(&device,&stage,1)==VK_ERROR_FEATURE_NOT_PRESENT); assert(downstream_calls==0);
    destroy_shader(device.handle,stage.module,NULL);
    stage.module=capture(&device,unused,us); downstream_calls=0;
    assert(run(&device,&stage,1)==VK_SUCCESS); assert(downstream_calls==1);
    destroy_shader(device.handle,stage.module,NULL);
    VkPipelineShaderStageCreateInfo pair[2]={
        {.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,.stage=VK_SHADER_STAGE_VERTEX_BIT,.pName="main"},
        {.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,.stage=VK_SHADER_STAGE_FRAGMENT_BIT,.pName="main"}};
    pair[0].module=capture(&device,vertex,vs); pair[1].module=capture(&device,fragment,fs); downstream_calls=0;
    assert(run(&device,pair,2)==VK_SUCCESS); assert(downstream_calls==1);
    destroy_shader(device.handle,pair[0].module,NULL); destroy_shader(device.handle,pair[1].module,NULL);
    assert(!device.shaders); devices=NULL; free(active);free(unused);free(vertex);free(fragment);
    puts("hybris clip dispatch contract ok"); return 0;
}
