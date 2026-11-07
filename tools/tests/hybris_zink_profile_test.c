/* SPDX-License-Identifier: Apache-2.0 */
#define VK_NO_PROTOTYPES
#include "zink_profile.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void)
{
    VkApplicationInfo zink = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "glxgears",
        .pEngineName = "mesa zink", .apiVersion = VK_API_VERSION_1_3};
    const char *engine = hybris_zink_profile_engine(&zink);
    printf("renamed=%d\n", engine != NULL);
    if (engine) {
        /* the blob matches "mesa zink" case-sensitively, anywhere in the name */
        assert(!strstr(engine, "mesa zink"));
        assert(strstr(engine, "zink") || strstr(engine, "Zink"));
    }

    /* Only that exact engine name. */
    VkApplicationInfo other = zink;
    other.pEngineName = "mesa zinkish";
    assert(!hybris_zink_profile_engine(&other));
    other.pEngineName = "DXVK";
    assert(!hybris_zink_profile_engine(&other));
    other.pEngineName = NULL;
    assert(!hybris_zink_profile_engine(&other));
    assert(!hybris_zink_profile_engine(NULL));

    /* Everything the driver's Zink profile hides stays hidden; dynamic vertex input does not. */
    VkPhysicalDeviceVertexInputDynamicStateFeaturesEXT vi = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_INPUT_DYNAMIC_STATE_FEATURES_EXT, .vertexInputDynamicState = VK_TRUE};
    VkPhysicalDeviceExtendedDynamicState3FeaturesEXT eds3;
    memset(&eds3, 0xff, sizeof(eds3));
    eds3.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_3_FEATURES_EXT;
    eds3.pNext = &vi;
    VkPhysicalDeviceGraphicsPipelineLibraryFeaturesEXT gpl = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_GRAPHICS_PIPELINE_LIBRARY_FEATURES_EXT, .pNext = &eds3,
        .graphicsPipelineLibrary = VK_TRUE};
    VkPhysicalDeviceFeatures2 features = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, .pNext = &gpl};
    memset(&features.features, 0xff, sizeof(features.features));
    hybris_zink_profile_features2(&features);

    assert(!features.features.sparseBinding && !features.features.sparseResidencyBuffer &&
        !features.features.sparseResidencyImage2D && !features.features.sparseResidencyImage3D &&
        !features.features.sparseResidency2Samples && !features.features.sparseResidency4Samples &&
        !features.features.sparseResidency8Samples && !features.features.sparseResidency16Samples &&
        !features.features.sparseResidencyAliased);
    assert(features.features.shaderClipDistance && features.features.geometryShader);
    assert(!gpl.graphicsPipelineLibrary);
    assert(!eds3.extendedDynamicState3TessellationDomainOrigin && !eds3.extendedDynamicState3DepthClampEnable &&
        !eds3.extendedDynamicState3PolygonMode && !eds3.extendedDynamicState3SampleMask &&
        !eds3.extendedDynamicState3AlphaToOneEnable && !eds3.extendedDynamicState3DepthClipEnable &&
        !eds3.extendedDynamicState3ProvokingVertexMode && !eds3.extendedDynamicState3LineRasterizationMode);
    assert(eds3.extendedDynamicState3ColorBlendEnable && eds3.extendedDynamicState3ColorWriteMask &&
        eds3.extendedDynamicState3RasterizationSamples && eds3.extendedDynamicState3LogicOpEnable);
    assert(vi.vertexInputDynamicState);

    VkPhysicalDeviceFeatures core;
    memset(&core, 0xff, sizeof(core));
    hybris_zink_profile_features(&core);
    assert(!core.sparseBinding && !core.sparseResidencyAliased && core.samplerAnisotropy);

    puts("hybris zink profile ok");
    return 0;
}
