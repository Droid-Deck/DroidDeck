#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""
Generates the Vulkan bridge's wire code from the pinned Khronos registry (vk.xml 1.4.341).

Output (into ../common/gen/):
  vkb_gen.h          command ids, protocol hash, dispatch table, prototypes
  vkb_gen_structs.c  struct encoders/decoders (both sides; client-only parts under VKB_CLIENT)
  vkb_gen_client.c   client RPC stubs ("wire" functions) + default entry points + name table
  vkb_gen_server.c   server handlers + dispatch table loaders

Wire rules (the C runtime in common/vkb_wire.[ch] implements the primitives):
  * A struct is sent as its raw bytes (8-byte aligned in the stream), followed by its "fixups":
    every pointer member's pointee, in member order. Non-dispatchable handles are u64 values that
    are valid on the server as they are; dispatchable handles are patched to the server's value by
    the client. The decoder reads the raw bytes back and replaces every pointer member.
  * pNext chains are sent element by element (u32 sType + struct), structs this generator does
    not know are skipped (logged once by the runtime), terminated by VKB_CHAIN_END.
  * Arrays always carry their element count explicitly; pointers carry a presence byte.
  * Output structs travel as a "template" (raw + chain of sType/raw) to the server, which fills
    them, and come back as raw + chain; the client copies them into the caller's memory keeping
    the caller's own pNext pointers.
"""
import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REG = os.path.join(HERE, '..', 'third_party', 'Vulkan-Headers-1.4.341', 'registry', 'vk.xml')
OUT = os.path.join(HERE, '..', 'common', 'gen')

# --------------------------------------------------------------------------------------------
# What is bridged.

# Newer registries split each version into BASE/COMPUTE/GRAPHICS parts as well.
CORE_FEATURES = [f'VK_{part}VERSION_1_{minor}' for minor in range(5) for part in ('', 'BASE_', 'COMPUTE_', 'GRAPHICS_')]

# Extensions whose name contains one of these are never bridged: platform-specific, display
# control, ray tracing, video, or designs that hand raw host pointers/callbacks to the driver.
EXT_BLOCK_SUBSTR = [
    'video', 'ray_tracing', 'acceleration_structure', 'ray_query', 'win32', 'android', 'fuchsia',
    'metal', '_ggp', 'GGP', 'screen', 'directfb', 'macos', 'ios_', 'MVK', 'NN_', '_vi_', 'display',
    'xlib_xrandr', 'descriptor_buffer', 'shader_object', 'micromap', 'deferred_host_operations',
    'performance_query', 'device_generated_commands', 'mesh_shader', 'device_memory_report',
    'device_address_binding_report', 'pipeline_properties', 'debug_report', 'debug_marker',
    'validation', 'portability', 'direct_driver_loading', 'full_screen_exclusive',
    'pipeline_executable_properties', 'external_memory_host', 'metal_objects',
    'present_wait', 'present_id', 'display_timing', 'shared_presentable_image',
    'swapchain_maintenance1', 'surface_maintenance1', 'hdr_metadata', 'present_barrier',
    'image_compression_control_swapchain', 'surface_protected_capabilities',
    'pipeline_library_group_handles', 'opacity', 'fragment_density_map_offset',
    'external_memory_rdma', 'external_memory_sci', 'external_sci', 'cluster_culling',
    'multiview_per_view', 'shader_enqueue', 'AMDX', 'cuda', 'CUDA', 'QNX', 'optical_flow',
    'low_latency', 'memory_decompression', 'copy_memory_indirect', 'frame_boundary',
    'device_fault', 'descriptor_set_host_mapping', 'descriptor_heap', 'present_timing',
    'pipeline_binary', 'data_graph', 'host_image_copy',
]
# Structs never sent (callbacks into the app, or meaningless across processes); a pNext chain
# carrying one is sent without it.
STRUCT_BLOCK = {'VkAllocationCallbacks', 'VkDebugUtilsMessengerCreateInfoEXT',
                'VkDebugReportCallbackCreateInfoEXT', 'VkValidationFeaturesEXT',
                'VkValidationFlagsEXT', 'VkLayerSettingsCreateInfoEXT',
                'VkDirectDriverLoadingListLUNARG', 'VkExportMetalObjectCreateInfoEXT'}
EXT_AUTHORS = ('KHR', 'EXT', 'ARM', 'VALVE', 'GOOGLE', 'MESA', 'IMG')
# Implemented by the client itself (no server round trip, or not on the wire at all).
CLIENT_EXTENSIONS = {
    'VK_KHR_surface', 'VK_KHR_swapchain', 'VK_KHR_wayland_surface', 'VK_KHR_xcb_surface',
    'VK_KHR_xlib_surface', 'VK_EXT_headless_surface', 'VK_KHR_get_surface_capabilities2',
    'VK_EXT_swapchain_colorspace', 'VK_KHR_incremental_present', 'VK_EXT_debug_utils',
    'VK_KHR_swapchain_mutable_format',
}
# Extension commands handled entirely client-side (no wire code generated).
LOCAL_COMMANDS_PREFIX = ('vkCreateDebugUtilsMessengerEXT', 'vkDestroyDebugUtilsMessengerEXT',
                         'vkSubmitDebugUtilsMessageEXT')

# Commands whose client entry point is hand-written (the generated wire stub may still be used).
CLIENT_CUSTOM = {
    'vkAllocateDescriptorSets', 'vkResetDescriptorPool', 'vkDestroyDescriptorPool', 'vkCreateDescriptorPool',
    'vkCreateDescriptorSetLayout', 'vkDestroyDescriptorSetLayout',
    'vkCreateInstance', 'vkDestroyInstance', 'vkEnumerateInstanceExtensionProperties',
    'vkEnumerateInstanceLayerProperties', 'vkEnumerateInstanceVersion', 'vkGetInstanceProcAddr',
    'vkGetDeviceProcAddr', 'vkEnumeratePhysicalDevices', 'vkEnumeratePhysicalDeviceGroups',
    'vkEnumerateDeviceExtensionProperties', 'vkEnumerateDeviceLayerProperties',
    'vkGetPhysicalDeviceFeatures', 'vkGetPhysicalDeviceFeatures2',
    'vkGetPhysicalDeviceProperties', 'vkGetPhysicalDeviceProperties2',
    'vkGetPhysicalDeviceFormatProperties', 'vkGetPhysicalDeviceFormatProperties2',
    'vkGetPhysicalDeviceImageFormatProperties', 'vkGetPhysicalDeviceImageFormatProperties2',
    'vkGetPhysicalDeviceMemoryProperties', 'vkGetPhysicalDeviceMemoryProperties2',
    'vkGetPhysicalDeviceSparseImageFormatProperties', 'vkGetPhysicalDeviceSparseImageFormatProperties2',
    'vkGetPhysicalDeviceToolProperties',
    'vkCreateDevice', 'vkDestroyDevice', 'vkGetDeviceQueue', 'vkGetDeviceQueue2',
    'vkAllocateCommandBuffers', 'vkFreeCommandBuffers', 'vkDestroyCommandPool', 'vkResetCommandPool',
    'vkBeginCommandBuffer', 'vkEndCommandBuffer', 'vkResetCommandBuffer',
    'vkAllocateMemory', 'vkFreeMemory', 'vkMapMemory', 'vkUnmapMemory', 'vkMapMemory2KHR',
    'vkUnmapMemory2KHR', 'vkFlushMappedMemoryRanges', 'vkInvalidateMappedMemoryRanges',
    'vkGetBufferMemoryRequirements', 'vkGetBufferMemoryRequirements2',
    'vkGetImageMemoryRequirements', 'vkGetImageMemoryRequirements2',
    'vkGetDeviceBufferMemoryRequirements', 'vkGetDeviceImageMemoryRequirements',
    'vkGetMemoryFdPropertiesKHR', 'vkCreateBuffer', 'vkCreateImage',
    'vkCreateDescriptorUpdateTemplate', 'vkDestroyDescriptorUpdateTemplate',
    'vkUpdateDescriptorSetWithTemplate', 'vkCmdPushDescriptorSetWithTemplateKHR',
    'vkCreatePrivateDataSlot', 'vkDestroyPrivateDataSlot', 'vkSetPrivateData', 'vkGetPrivateData',
    'vkQueueSubmit', 'vkQueueSubmit2', 'vkQueueWaitIdle', 'vkDeviceWaitIdle', 'vkWaitForFences',
    'vkGetFenceStatus', 'vkWaitSemaphores', 'vkGetSemaphoreCounterValue',
    'vkGetPhysicalDeviceExternalBufferProperties', 'vkGetPhysicalDeviceQueueFamilyProperties',
    'vkGetPhysicalDeviceQueueFamilyProperties2', 'vkSetDebugUtilsObjectNameEXT',
    'vkSetDebugUtilsObjectTagEXT', 'vkQueueBeginDebugUtilsLabelEXT', 'vkQueueEndDebugUtilsLabelEXT',
    'vkQueueInsertDebugUtilsLabelEXT', 'vkCmdBeginDebugUtilsLabelEXT', 'vkCmdEndDebugUtilsLabelEXT',
    'vkCmdInsertDebugUtilsLabelEXT',         'vkCmdExecuteCommands', 'vkGetImageSubresourceLayout',
    'vkGetPhysicalDeviceMultisamplePropertiesEXT', 'vkGetDeviceImageSparseMemoryRequirements',
    'vkGetImageSparseMemoryRequirements', 'vkGetImageSparseMemoryRequirements2',
    'vkBindBufferMemory', 'vkBindBufferMemory2', 'vkBindImageMemory', 'vkBindImageMemory2',
    'vkDestroyBuffer', 'vkDestroyImage', 'vkGetPhysicalDeviceExternalSemaphoreProperties',
    'vkGetPhysicalDeviceExternalFenceProperties',     }
# Commands whose server handler is hand-written (server/*.c defines vkb_sv_<name>).
SERVER_CUSTOM = {
    'vkCreateInstance', 'vkDestroyInstance', 'vkCreateDevice', 'vkDestroyDevice',
    'vkAllocateMemory', 'vkFreeMemory',
    'vkFlushMappedMemoryRanges', 'vkInvalidateMappedMemoryRanges', 'vkEndCommandBuffer',
}

# VkResult commands sent without waiting (VK_SUCCESS returned at once; a failure is logged by the
# server). What an app does next with their objects is ordered after them by the protocol.
ASYNC_RESULT = {'vkQueueSubmit', 'vkQueueSubmit2', 'vkResetDescriptorPool', 'vkResetCommandPool',
                'vkResetCommandBuffer', 'vkResetFences', 'vkResetEvent', 'vkSetEvent', 'vkFreeDescriptorSets',
                'vkBindBufferMemory', 'vkBindBufferMemory2', 'vkBindImageMemory', 'vkBindImageMemory2'}

# File descriptors: (struct, member) and (command, param).
FD_MEMBERS = {('VkImportMemoryFdInfoKHR', 'fd'), ('VkImportSemaphoreFdInfoKHR', 'fd'),
              ('VkImportFenceFdInfoKHR', 'fd')}
FD_PARAMS = {('vkGetMemoryFdKHR', 'pFd'), ('vkGetSemaphoreFdKHR', 'pFd'), ('vkGetFenceFdKHR', 'pFd'),
             ('vkGetMemoryFdPropertiesKHR', 'fd')}

# Pointer members whose validity depends on other members (vk.xml "noautovalidity"):
# a C condition (s = the struct) under which the pointer is read. Anything else marked
# noautovalidity is read whenever it is non-NULL.
PTR_CONDITIONS = {
    ('VkWriteDescriptorSet', 'pImageInfo'): 'vkb_desc_has_image(s->descriptorType)',
    ('VkWriteDescriptorSet', 'pBufferInfo'): 'vkb_desc_has_buffer(s->descriptorType)',
    ('VkWriteDescriptorSet', 'pTexelBufferView'): 'vkb_desc_has_texel(s->descriptorType)',
    ('VkBufferCreateInfo', 'pQueueFamilyIndices'): 's->sharingMode == VK_SHARING_MODE_CONCURRENT',
    ('VkImageCreateInfo', 'pQueueFamilyIndices'): 's->sharingMode == VK_SHARING_MODE_CONCURRENT',
    ('VkPhysicalDeviceImageDrmFormatModifierInfoEXT', 'pQueueFamilyIndices'): 's->sharingMode == VK_SHARING_MODE_CONCURRENT',
    ('VkDescriptorSetLayoutBinding', 'pImmutableSamplers'): '(s->descriptorType == VK_DESCRIPTOR_TYPE_SAMPLER || s->descriptorType == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)',
    ('VkGraphicsPipelineCreateInfo', 'pTessellationState'): 'vkb_gp_has_tess(s)',
    ('VkCommandBufferBeginInfo', 'pInheritanceInfo'): 'vkb_cb_begin_is_secondary(s)',
    ('VkFramebufferCreateInfo', 'pAttachments'): '!(s->flags & VK_FRAMEBUFFER_CREATE_IMAGELESS_BIT)',
    ('VkImageCompressionControlEXT', 'pFixedRateFlags'): 's->flags == VK_IMAGE_COMPRESSION_FIXED_RATE_EXPLICIT_EXT',
}

# Lengths written as LaTeX in vk.xml, as C over the struct.
ALTLEN = {
    ('VkShaderModuleCreateInfo', 'pCode'): ('bytes', 's->codeSize'),
    ('VkPipelineMultisampleStateCreateInfo', 'pSampleMask'): ('count', '(((uint32_t)s->rasterizationSamples + 31) / 32)'),
    ('VkAccelerationStructureVersionInfoKHR', 'pVersionData'): ('count', '2 * VK_UUID_SIZE'),
    ('vkCmdSetSampleMaskEXT', 'pSampleMask'): ('count', '(((uint32_t)s->samples + 31) / 32)'),
}

SCALARS = {
    'uint8_t': 1, 'int8_t': 1, 'uint16_t': 2, 'int16_t': 2, 'uint32_t': 4, 'int32_t': 4,
    'uint64_t': 8, 'int64_t': 8, 'float': 4, 'double': 8, 'size_t': 8, 'int': 4, 'char': 1,
    'VkBool32': 4, 'VkDeviceSize': 8, 'VkDeviceAddress': 8, 'VkFlags': 4, 'VkFlags64': 8,
    'VkSampleMask': 4,
}

# --------------------------------------------------------------------------------------------

def api_ok(e):
    a = e.get('api')
    return a is None or 'vulkan' in a.split(',')


class Member:
    def __init__(self, el, owner):
        self.owner = owner
        self.type = el.find('type').text
        self.name = el.find('name').text
        pre = (el.text or '')
        tail = el.find('type').tail or ''
        self.const = 'const' in pre
        self.ptr = tail.count('*')
        ntail = el.find('name').tail or ''
        self.fixed = ntail.strip().startswith('[')
        self.bitfield = ntail.strip().startswith(':')
        self.len = el.get('len')
        self.altlen = el.get('altlen')
        self.optional = el.get('optional')
        self.noauto = el.get('noautovalidity') == 'true'
        self.values = el.get('values')
        self.decl = ''.join(el.itertext()).strip()
        # e.g. "const VkFoo* pBar" - kept for the C prototypes.
        self.decl = re.sub(r'\s+', ' ', self.decl)


class Registry:
    def __init__(self, path):
        self.root = ET.parse(path).getroot()
        self.types = {}
        self.aliases = {}
        self.handles = {}
        self.structs = {}
        self.funcptrs = set()
        self.enums = set()
        self.bitmasks = {}
        self.basetypes = {}
        for t in self.root.find('types'):
            if t.tag != 'type' or not api_ok(t):
                continue
            cat = t.get('category')
            name = t.get('name') or (t.find('name').text if t.find('name') is not None else None)
            if name is None:
                continue
            if t.get('alias'):
                self.aliases[name] = t.get('alias')
                continue
            self.types[name] = (cat, t)
            if cat == 'handle':
                self.handles[name] = t.find('type').text == 'VK_DEFINE_HANDLE'
            elif cat in ('struct', 'union'):
                self.structs[name] = t
            elif cat == 'funcpointer':
                self.funcptrs.add(name)
            elif cat == 'enum':
                self.enums.add(name)
            elif cat == 'bitmask':
                bt = t.find('type').text
                self.bitmasks[name] = 8 if bt == 'VkFlags64' else 4
            elif cat == 'basetype':
                tt = t.find('type')
                self.basetypes[name] = tt.text if tt is not None else None
        self.commands = {}
        self.cmd_alias = {}
        for c in self.root.find('commands'):
            if not api_ok(c):
                continue
            if c.get('alias'):
                self.cmd_alias[c.get('name')] = c.get('alias')
                continue
            proto = c.find('proto')
            name = proto.find('name').text
            params = [Member(p, name) for p in c.findall('param') if api_ok(p)]
            self.commands[name] = {'ret': proto.find('type').text, 'params': params, 'el': c}
        self.struct_members = {}
        for n, t in self.structs.items():
            self.struct_members[n] = [Member(m, n) for m in t.findall('member') if api_ok(m)]

    def canon(self, name):
        while name in self.aliases:
            name = self.aliases[name]
        return name

    def canon_cmd(self, name):
        while name in self.cmd_alias:
            name = self.cmd_alias[name]
        return name


reg = Registry(REG)
# Hand-maintained name lists may use extension names that a newer registry made aliases of core.
CLIENT_CUSTOM = {reg.canon_cmd(c) for c in CLIENT_CUSTOM}
SERVER_CUSTOM = {reg.canon_cmd(c) for c in SERVER_CUSTOM}
FD_PARAMS = {(reg.canon_cmd(c), p) for c, p in FD_PARAMS}

# --------------------------------------------------------------------------------------------
# Which commands/structs are in.

included_cmds = set()
cmd_aliases_included = {}   # alias name -> canonical
ext_info = {}               # ext name -> (type, [commands])
cmd_origin = {}             # canonical cmd -> 'core' or ext name
cmd_version = {}            # canonical cmd -> core version (major, minor) that introduced it


def require_blocks(el):
    for req in el.findall('require'):
        if not api_ok(req):
            continue
        yield req


for f in reg.root.findall('feature'):
    if f.get('name') not in CORE_FEATURES or not api_ok(f):
        continue
    for req in require_blocks(f):
        ver = tuple(int(x) for x in f.get('number').split('.'))
        for c in req.findall('command'):
            n = reg.canon_cmd(c.get('name'))
            included_cmds.add(n)
            cmd_origin.setdefault(n, 'core')
            if c.get('name') == n:
                cmd_version[n] = min(cmd_version.get(n, ver), ver)
            if c.get('name') != n:
                cmd_aliases_included[c.get('name')] = n


def ext_allowed(e):
    name = e.get('name')
    if 'vulkan' not in (e.get('supported') or '').split(','):
        return False
    if e.get('platform') and name not in CLIENT_EXTENSIONS:
        return False
    if not name.startswith('VK_'):
        return False
    author = name.split('_')[1]
    if author not in EXT_AUTHORS:
        return False
    if name in CLIENT_EXTENSIONS:
        return True
    for b in EXT_BLOCK_SUBSTR:
        if b in name:
            return False
    return True


def dep_blocked(dep):
    for name in re.findall(r'VK_[A-Za-z0-9_]+', dep):
        if name.startswith('VK_VERSION_'):
            continue
        author = name.split('_')[1]
        if author not in EXT_AUTHORS or any(b in name for b in EXT_BLOCK_SUBSTR):
            if name not in CLIENT_EXTENSIONS:
                return True
    return False


bridged_exts = []
for e in reg.root.find('extensions'):
    if not ext_allowed(e):
        continue
    name = e.get('name')
    cmds = []
    for req in require_blocks(e):
        dep = req.get('depends') or req.get('feature') or req.get('extension') or ''
        # A require block that pulls in a blocked extension's commands is skipped.
        if dep_blocked(dep):
            continue
        for c in req.findall('command'):
            cmds.append(c.get('name'))
    ext_info[name] = (e.get('type'), cmds)
    bridged_exts.append(name)
    for cn in cmds:
        n = reg.canon_cmd(cn)
        included_cmds.add(n)
        cmd_origin.setdefault(n, name)
        if cn != n:
            cmd_aliases_included[cn] = n

# Every alias of an included command, wherever it was defined.
for a, c in reg.cmd_alias.items():
    cc = reg.canon_cmd(a)
    if cc in included_cmds:
        cmd_aliases_included[a] = cc

# WSI / surface commands are client-only: no wire code.
CLIENT_ONLY_EXT = CLIENT_EXTENSIONS
client_only_cmds = set()
for en in CLIENT_ONLY_EXT:
    if en in ext_info:
        for cn in ext_info[en][1]:
            client_only_cmds.add(reg.canon_cmd(cn))
# The device-group WSI commands from core 1.1 too.
client_only_cmds |= {'vkGetDeviceGroupPresentCapabilitiesKHR', 'vkGetDeviceGroupSurfacePresentModesKHR',
                     'vkGetPhysicalDevicePresentRectanglesKHR', 'vkAcquireNextImage2KHR'}
# Local-only (no wire): private data, debug utils.
client_only_cmds |= {'vkCreatePrivateDataSlot', 'vkDestroyPrivateDataSlot', 'vkSetPrivateData',
                     'vkGetPrivateData', 'vkSetDebugUtilsObjectNameEXT', 'vkSetDebugUtilsObjectTagEXT',
                     'vkQueueBeginDebugUtilsLabelEXT', 'vkQueueEndDebugUtilsLabelEXT',
                     'vkQueueInsertDebugUtilsLabelEXT', 'vkCmdBeginDebugUtilsLabelEXT',
                     'vkCmdEndDebugUtilsLabelEXT', 'vkCmdInsertDebugUtilsLabelEXT',
                     'vkGetInstanceProcAddr', 'vkGetDeviceProcAddr',
                     'vkUpdateDescriptorSetWithTemplate', 'vkCmdPushDescriptorSetWithTemplateKHR',
                     'vkMapMemory', 'vkUnmapMemory', 'vkMapMemory2KHR', 'vkUnmapMemory2KHR',
                     'vkEnumerateDeviceLayerProperties', 'vkCmdPushDescriptorSetWithTemplate2',
                     # Host image copy hands the driver raw host pointers; it is reported unsupported.
                     'vkCopyMemoryToImage', 'vkCopyImageToMemory', 'vkCopyImageToImage', 'vkTransitionImageLayout'}

client_only_cmds = {reg.canon_cmd(c) for c in client_only_cmds}
wire_cmds = sorted(c for c in included_cmds if c not in client_only_cmds)
# Server-only entries in the dispatch table (used by the server itself, never on the wire).
SERVER_DISPATCH_EXTRA = ['vkMapMemory', 'vkUnmapMemory', 'vkGetMemoryHostPointerPropertiesEXT',
                         'vkUpdateDescriptorSetWithTemplate', 'vkCmdPushDescriptorSetWithTemplateKHR']
dispatch_cmds = sorted(set(wire_cmds) | {reg.canon_cmd(c) for c in SERVER_DISPATCH_EXTRA})
# Wire-only helpers that are not Vulkan commands (handled by hand on both sides).
EXTRA_WIRE = ['vkbHello', 'vkbQueryServer', 'vkbDescUpdateRaw', 'vkbPushDescRaw', 'vkbAllocDescSets']

# --------------------------------------------------------------------------------------------
# Struct closure: every struct reachable from wire commands (params, members, pNext extenders).

def is_dhandle(t):
    t = reg.canon(t)
    return reg.handles.get(t) is True


def is_ndhandle(t):
    t = reg.canon(t)
    return reg.handles.get(t) is False


def is_struct(t):
    return reg.canon(t) in reg.structs


def struct_extenders():
    ext = {}
    for n, t in reg.structs.items():
        se = t.get('structextends')
        if se:
            for b in se.split(','):
                ext.setdefault(b, []).append(n)
    return ext


EXTENDERS = struct_extenders()

# Struct types that may appear in our builds (core or a bridged extension, incl. client ones).
avail_types = set()
for f in reg.root.findall('feature'):
    if f.get('name') not in CORE_FEATURES or not api_ok(f):
        continue
    for req in require_blocks(f):
        for t in req.findall('type'):
            avail_types.add(t.get('name'))
for e in reg.root.find('extensions'):
    if e.get('name') in ext_info:
        for req in require_blocks(e):
            dep = req.get('depends') or ''
            if dep_blocked(dep):
                continue
            for t in req.findall('type'):
                avail_types.add(t.get('name'))
avail_types = {reg.canon(t) for t in avail_types} | {t for t in avail_types}
# Base types every build has.
avail_types |= {'VkBaseInStructure', 'VkBaseOutStructure'}

needed_structs = set()


def stype_of(sname):
    for m in reg.struct_members[sname]:
        if m.name == 'sType' and m.values:
            return m.values
    return None


def need_struct(t):
    t = reg.canon(t)
    if t not in reg.structs or t in needed_structs or t in STRUCT_BLOCK:
        return
    if t not in avail_types:
        return
    needed_structs.add(t)
    for m in reg.struct_members[t]:
        if is_struct(m.type):
            need_struct(m.type)
    for x in EXTENDERS.get(t, []):
        if x in avail_types:
            need_struct(x)


for c in wire_cmds:
    for p in reg.commands[c]['params']:
        need_struct(p.type)
# Client WSI structs that the client encodes for nothing - still needed for chain skipping.
for x in list(EXTENDERS):
    pass

problems = []


def check_out_struct(t, seen=None):
    t = reg.canon(t)
    if t not in reg.structs:
        return
    seen = seen or set()
    if t in seen:
        return
    seen.add(t)
    for m in reg.struct_members[t]:
        if m.name != 'pNext' and (m.ptr or is_dhandle(m.type)):
            problems.append(f'out struct {t}.{m.name} has a pointer/dispatchable handle')
        if is_struct(m.type):
            check_out_struct(m.type, seen)
    for x in EXTENDERS.get(t, []):
        if x in needed_structs:
            check_out_struct(x, seen)

for c in wire_cmds:
    for p in reg.commands[c]['params']:
        if p.ptr and not p.const and reg.canon(p.type) in reg.structs:
            check_out_struct(p.type)

# --------------------------------------------------------------------------------------------
# Struct classification.

pod_cache = {}


def is_pod(t):
    """No pointer and no dispatchable handle anywhere inside (raw copy is the whole encoding)."""
    t = reg.canon(t)
    if t in pod_cache:
        return pod_cache[t]
    if t not in reg.structs:
        return True
    pod_cache[t] = True
    res = True
    for m in reg.struct_members[t]:
        if m.ptr or is_dhandle(m.type) or m.type in reg.funcptrs or m.bitfield:
            res = False
        elif is_struct(m.type) and not is_pod(m.type):
            res = False
    pod_cache[t] = res
    return res


def has_pnext(t):
    return any(m.name == 'pNext' for m in reg.struct_members[reg.canon(t)])


def elem_size_expr(t):
    return f'sizeof({t})'


def len_expr(owner, m, prefix):
    """C expression for element count (or byte count, for void) of member/param m."""
    key = (owner, m.name)
    if key in ALTLEN:
        kind, expr = ALTLEN[key]
        return kind, expr.replace('s->', prefix)
    ln = m.len
    if ln is None:
        return None, None
    parts = ln.split(',')
    first = parts[0]
    if first == 'null-terminated':
        return 'string', None
    if first.startswith('latexmath'):
        problems.append(f'{owner}.{m.name}: latexmath len {ln}')
        return None, None
    expr = first.replace('->', '->')
    # members of the same struct / params of the same command
    e = re.sub(r'\b([a-zA-Z_][a-zA-Z0-9_]*)\b', lambda mm: (prefix + mm.group(1)) if mm.group(1)[0].islower() or mm.group(1)[0] == 'p' else mm.group(1), expr, count=1)
    if len(parts) > 1 and parts[1] == 'null-terminated':
        return 'strarray', e
    return 'count', e


# --------------------------------------------------------------------------------------------
# Code emission helpers.

class W:
    def __init__(self):
        self.lines = []
        self.ind = 0

    def __call__(self, s=''):
        if s.startswith('}') and self.ind > 0:
            self.ind -= 1
        self.lines.append(('    ' * self.ind + s) if s else '')
        if s.endswith('{'):
            self.ind += 1
        elif s.startswith('{') and s.count('{') > s.count('}'):
            self.ind += 1

    def text(self):
        return '\n'.join(self.lines) + '\n'


HEADER = '/* Generated by tools/vkbridge/codegen/gen_vkbridge.py from vk.xml 1.4.341 - do not edit. */\n'

# Ordered list of structs (deterministic).
STRUCTS = sorted(needed_structs)
CHAIN_STRUCTS = [s for s in STRUCTS if stype_of(s) and has_pnext(s)]


def guard_for(t):
    return None


# Struct encoders -----------------------------------------------------------------------------

sw = W()
sw(HEADER)
sw('#include "vkb_gen.h"')
sw('#include "../vkb_wire.h"')
sw('#include <string.h>')
sw()


def fixups(sname):
    """Members needing work beyond the raw copy."""
    out = []
    for m in reg.struct_members[sname]:
        if m.name == 'pNext':
            out.append(('pnext', m))
        elif m.bitfield:
            problems.append(f'{sname}.{m.name}: bitfield')
        elif m.type in reg.funcptrs:
            out.append(('funcptr', m))
        elif m.ptr:
            out.append(('ptr', m))
        elif is_dhandle(m.type):
            out.append(('dhandle', m))
        elif is_struct(m.type) and not is_pod(m.type):
            out.append(('embed', m))
        elif (sname, m.name) in FD_MEMBERS:
            out.append(('fd', m))
    return out


def emit_ptr_enc(w, owner, m, src, prefix, side):
    """Encode pointee of pointer member/param m (expression src). side: 'in' only."""
    t = reg.canon(m.type)
    cond = PTR_CONDITIONS.get((owner, m.name))
    kind, cnt = len_expr(owner, m, prefix)
    guard = f'{src}' + (f' && ({cond.replace("s->", prefix)})' if cond else '')
    if m.ptr == 1 and t == 'char' and kind == 'string':
        w(f'vkb_enc_str(e, {guard} ? {src} : NULL);')
        return
    if m.ptr == 2 and t == 'char' and kind == 'strarray':
        w(f'vkb_enc_strarray(e, {guard} ? {src} : NULL, {guard} ? (uint32_t)({cnt}) : 0);')
        return
    if m.ptr == 2:
        problems.append(f'{owner}.{m.name}: double pointer')
        w(f'vkb_enc_u8(e, 0); /* unsupported double pointer {m.name} */')
        return
    if t == 'void':
        if kind == 'bytes' or (kind == 'count'):
            w(f'vkb_enc_blob(e, {guard} ? (const void *){src} : NULL, {guard} ? (size_t)({cnt}) : 0);')
        else:
            problems.append(f'{owner}.{m.name}: void* without length')
            w(f'vkb_enc_u8(e, 0); /* void* {m.name} not sent */')
        return
    if kind == 'bytes':
        w(f'vkb_enc_blob(e, {guard} ? (const void *){src} : NULL, {guard} ? (size_t)({cnt}) : 0);')
        return
    n = cnt if kind == 'count' else '1'
    w(f'if ({guard}) {{')
    w(f'uint32_t n_ = (uint32_t)({n});')
    w('vkb_enc_u8(e, 1); vkb_enc_u32(e, n_);')
    if is_dhandle(t):
        w(f'for (uint32_t i_ = 0; i_ < n_; i_++) vkb_enc_u64(e, vkb_remote((const void *){src}[i_]));')
    elif is_struct(t) and not is_pod(t):
        w(f'for (uint32_t i_ = 0; i_ < n_; i_++) vkb_enc_{t}(e, &{src}[i_]);')
    else:
        w(f'vkb_enc_raw(e, {src}, (size_t)n_ * sizeof(*{src}));')
    w('} else {')
    w('vkb_enc_u8(e, 0);')
    w('}')


def emit_ptr_dec(w, owner, m, dst, side):
    t = reg.canon(m.type)
    kind, cnt = len_expr(owner, m, 's->')
    ctype = m.type
    if m.ptr == 1 and t == 'char' and kind == 'string':
        w(f'{dst} = vkb_dec_str(d);')
        return
    if m.ptr == 2 and t == 'char' and kind == 'strarray':
        w(f'{dst} = vkb_dec_strarray(d);')
        return
    if m.ptr == 2:
        w(f'(void)vkb_dec_u8(d); {dst} = NULL;')
        return
    if t == 'void' or kind == 'bytes':
        if t == 'void' and kind not in ('bytes', 'count'):
            w(f'(void)vkb_dec_u8(d); {dst} = NULL;')
        else:
            w(f'{dst} = ({"const " if m.const else ""}{ctype} *)vkb_dec_blob(d, NULL);')
        return
    w('if (vkb_dec_u8(d)) {')
    w('uint32_t n_ = vkb_dec_u32(d);')
    if is_dhandle(t):
        w(f'{ctype} *a_ = ({ctype} *)vkb_dec_alloc(d, (size_t)n_ * sizeof({ctype}));')
        w(f'for (uint32_t i_ = 0; a_ && i_ < n_; i_++) a_[i_] = ({ctype})(uintptr_t)vkb_dec_u64(d);')
        w(f'{dst} = a_;')
    elif is_struct(t) and not is_pod(t):
        w(f'{ctype} *a_ = ({ctype} *)vkb_dec_alloc(d, (size_t)n_ * sizeof({ctype}));')
        w(f'for (uint32_t i_ = 0; a_ && i_ < n_; i_++) vkb_dec_{t}(d, &a_[i_]);')
        w(f'{dst} = a_;')
    else:
        w(f'{dst} = ({"const " if m.const else ""}{ctype} *)vkb_dec_raw_view(d, (size_t)n_ * sizeof({ctype}));')
    w('} else {')
    w(f'{dst} = NULL;')
    w('}')


# Prototypes for all non-POD structs.
proto = W()
for s in STRUCTS:
    if is_pod(s):
        continue
    proto(f'void vkb_enc_{s}(vkb_enc *e, const {s} *s);')
    proto(f'void vkb_encfix_{s}(vkb_enc *e, const {s} *s, size_t raw);')
    proto(f'void vkb_dec_{s}(vkb_dec *d, {s} *s);')
    proto(f'void vkb_decfix_{s}(vkb_dec *d, {s} *s);')

for s in STRUCTS:
    if is_pod(s):
        continue
    fx = fixups(s)
    # encode
    sw(f'void vkb_encfix_{s}(vkb_enc *e, const {s} *s, size_t raw)')
    sw('{')
    sw('(void)e; (void)s; (void)raw;')
    for kind, m in fx:
        if kind == 'pnext':
            sw('vkb_enc_chain(e, s->pNext);')
        elif kind == 'funcptr':
            sw(f'/* {m.name}: function pointer, never sent */')
        elif kind == 'dhandle':
            if m.fixed:
                sw(f'for (size_t i_ = 0; i_ < sizeof(s->{m.name}) / sizeof(s->{m.name}[0]); i_++) vkb_enc_patch_u64(e, raw + offsetof({s}, {m.name}) + i_ * 8, vkb_remote((const void *)s->{m.name}[i_]));')
            else:
                sw(f'vkb_enc_patch_u64(e, raw + offsetof({s}, {m.name}), vkb_remote((const void *)s->{m.name}));')
        elif kind == 'embed':
            mt = reg.canon(m.type)
            if m.fixed:
                sw(f'for (size_t i_ = 0; i_ < sizeof(s->{m.name}) / sizeof(s->{m.name}[0]); i_++) vkb_encfix_{mt}(e, &s->{m.name}[i_], raw + offsetof({s}, {m.name}) + i_ * sizeof({mt}));')
            else:
                sw(f'vkb_encfix_{mt}(e, &s->{m.name}, raw + offsetof({s}, {m.name}));')
        elif kind == 'fd':
            sw(f'vkb_enc_fd(e, s->{m.name});')
        elif kind == 'ptr':
            if m.noauto and (s, m.name) not in PTR_CONDITIONS and m.len is None and not m.optional:
                pass
            emit_ptr_enc(sw, s, m, f's->{m.name}', 's->', 'in')
    sw('}')
    sw()
    sw(f'void vkb_enc_{s}(vkb_enc *e, const {s} *s)')
    sw('{')
    sw(f'size_t raw = vkb_enc_raw(e, s, sizeof(*s));')
    sw(f'vkb_encfix_{s}(e, s, raw);')
    sw('}')
    sw()
    # decode
    sw(f'void vkb_decfix_{s}(vkb_dec *d, {s} *s)')
    sw('{')
    sw('(void)d; (void)s;')
    for kind, m in fx:
        if kind == 'pnext':
            sw('s->pNext = (void *)vkb_dec_chain(d);')
        elif kind == 'funcptr':
            sw(f's->{m.name} = NULL;')
        elif kind == 'embed':
            mt = reg.canon(m.type)
            if m.fixed:
                sw(f'for (size_t i_ = 0; i_ < sizeof(s->{m.name}) / sizeof(s->{m.name}[0]); i_++) vkb_decfix_{mt}(d, &s->{m.name}[i_]);')
            else:
                sw(f'vkb_decfix_{mt}(d, &s->{m.name});')
        elif kind == 'fd':
            sw(f's->{m.name} = vkb_dec_fd(d);')
        elif kind == 'ptr':
            emit_ptr_dec(sw, s, m, f's->{m.name}', 'in')
    sw('}')
    sw()
    sw(f'void vkb_dec_{s}(vkb_dec *d, {s} *s)')
    sw('{')
    sw('vkb_dec_raw_into(d, s, sizeof(*s));')
    sw(f'vkb_decfix_{s}(d, s);')
    sw('}')
    sw()

# Chain dispatch.
sw('size_t vkb_struct_size(VkStructureType t)')
sw('{')
sw('switch ((int)t) {')
for s in CHAIN_STRUCTS:
    sw(f'case {stype_of(s)}: return sizeof({s});')
sw('default: return 0;')
sw('}')
sw('}')
sw()
sw('void vkb_enc_chain_elem(vkb_enc *e, const VkBaseInStructure *b)')
sw('{')
sw('switch ((int)b->sType) {')
for s in CHAIN_STRUCTS:
    if is_pod(s):
        continue
    sw(f'case {stype_of(s)}: vkb_enc_{s}(e, (const {s} *)b); return;')
sw('default: vkb_enc_raw(e, b, vkb_struct_size(b->sType)); return;')
sw('}')
sw('}')
sw()
sw('void vkb_dec_chain_elem(vkb_dec *d, VkStructureType t, void *dst)')
sw('{')
sw('switch ((int)t) {')
for s in CHAIN_STRUCTS:
    if is_pod(s):
        continue
    sw(f'case {stype_of(s)}: vkb_dec_{s}(d, ({s} *)dst); return;')
sw('default: vkb_dec_raw_into(d, dst, vkb_struct_size(t)); return;')
sw('}')
sw('}')
sw()

# Dispatchable-handle out conversion helpers are in the runtime; nothing else per struct.

# --------------------------------------------------------------------------------------------
# Commands.

def cmd_level(c):
    ps = reg.commands[c]['params']
    if not ps or not is_dhandle(ps[0].type):
        return 'global'
    t = reg.canon(ps[0].type)
    if t in ('VkInstance', 'VkPhysicalDevice'):
        return 'instance'
    return 'device'


def objtype(t):
    t = reg.canon(t)
    return {
        'VkInstance': 'VK_OBJECT_TYPE_INSTANCE', 'VkPhysicalDevice': 'VK_OBJECT_TYPE_PHYSICAL_DEVICE',
        'VkDevice': 'VK_OBJECT_TYPE_DEVICE', 'VkQueue': 'VK_OBJECT_TYPE_QUEUE',
        'VkCommandBuffer': 'VK_OBJECT_TYPE_COMMAND_BUFFER',
    }[t]


def param_class(c, p):
    """in-value | in-ptr | out-ptr | inout-scalar | skip"""
    t = reg.canon(p.type)
    if t == 'VkAllocationCallbacks':
        return 'skip'
    if p.ptr == 0:
        if p.fixed:
            return 'in-fixed'
        return 'in-value'
    if p.const:
        return 'in-ptr'
    return 'out-ptr'


def scalar_size(t):
    t = reg.canon(t)
    if t in SCALARS:
        return SCALARS[t]
    if t in reg.enums:
        return 4
    if t in reg.bitmasks:
        return reg.bitmasks[t]
    if t in reg.handles:
        return 8
    if t in reg.basetypes:
        bt = reg.basetypes[t]
        if bt in SCALARS:
            return SCALARS[bt]
    return None


cw = W()   # client
vw = W()   # server
hw = W()   # header

cmd_ids = EXTRA_WIRE + wire_cmds
proto_hash = hashlib.sha256(('\n'.join(cmd_ids) + '\n'.join(STRUCTS)).encode()).hexdigest()[:16]

hw('/* Generated by tools/vkbridge/codegen/gen_vkbridge.py from vk.xml 1.4.341 - do not edit. */')
hw('#ifndef VKB_GEN_H')
hw('#define VKB_GEN_H')
hw('#define VK_NO_PROTOTYPES 1')
hw('#ifdef VKB_CLIENT')
hw('#define VK_USE_PLATFORM_WAYLAND_KHR 1')
hw('#define VK_USE_PLATFORM_XCB_KHR 1')
hw('#define VK_USE_PLATFORM_XLIB_KHR 1')
hw('#endif')
hw('#include <vulkan/vulkan.h>')
hw('#include <stddef.h>')
hw('#include <stdint.h>')
hw('typedef struct vkb_enc vkb_enc;')
hw('typedef struct vkb_dec vkb_dec;')
hw(f'#define VKB_PROTOCOL_HASH 0x{proto_hash}ULL')
hw('enum vkb_cmd_id {')
for i, c in enumerate(cmd_ids):
    hw(f'VKB_CMD_{c} = {i},')
hw(f'VKB_CMD_COUNT = {len(cmd_ids)}')
hw('};')
hw('extern const char *const vkb_cmd_names[VKB_CMD_COUNT];')
hw('extern const unsigned char vkb_cmd_result[VKB_CMD_COUNT]; /* returns VkResult (first in the reply) */')
hw()
hw('/* Every bridged command, by its canonical name (aliases resolve to these). */')
hw('typedef struct vkb_dispatch {')
for c in dispatch_cmds:
    hw(f'PFN_{c} {c};')
hw('} vkb_dispatch;')
hw('void vkb_dispatch_load_instance(vkb_dispatch *dt, PFN_vkGetInstanceProcAddr gipa, VkInstance instance);')
hw('void vkb_dispatch_load_device(vkb_dispatch *dt, PFN_vkGetDeviceProcAddr gdpa, VkDevice device);')
hw()
hw('size_t vkb_struct_size(VkStructureType t);')
hw('void vkb_enc_chain_elem(vkb_enc *e, const VkBaseInStructure *b);')
hw('void vkb_dec_chain_elem(vkb_dec *d, VkStructureType t, void *dst);')
for l in proto.lines:
    hw(l)
hw()
hw('/* Bridged extensions: name, 1 = device, client-implemented flag. */')
hw('typedef struct vkb_ext_desc { const char *name; uint8_t device; uint8_t client; } vkb_ext_desc;')
hw(f'#define VKB_EXT_COUNT {len(bridged_exts)}')
hw('extern const vkb_ext_desc vkb_exts[VKB_EXT_COUNT];')
hw()
hw('/* Client: name -> entry point (canonical names and aliases). */')
hw('typedef struct vkb_proc_desc { const char *name; PFN_vkVoidFunction fn; uint8_t level; const char *ext; uint32_t core; } vkb_proc_desc;')
hw('#define VKB_LEVEL_GLOBAL 0')
hw('#define VKB_LEVEL_INSTANCE 1')
hw('#define VKB_LEVEL_PHYSDEV 2')
hw('#define VKB_LEVEL_DEVICE 3')
hw('extern const vkb_proc_desc vkb_client_procs[];')
hw('extern const size_t vkb_client_proc_count;')
hw()

# Name table and extension table.
names = W()
names('const char *const vkb_cmd_names[VKB_CMD_COUNT] = {')
for c in cmd_ids:
    names(f'"{c}",')
names('};')
names('const unsigned char vkb_cmd_result[VKB_CMD_COUNT] = {')
for c in cmd_ids:
    names(f'{1 if c in reg.commands and reg.commands[c]["ret"] == "VkResult" else 0},')
names('};')
names('const vkb_ext_desc vkb_exts[VKB_EXT_COUNT] = {')
for e in bridged_exts:
    names(f'{{"{e}", {1 if ext_info[e][0] == "device" else 0}, {1 if e in CLIENT_EXTENSIONS else 0}}},')
names('};')
for l in names.lines:
    sw(l)

# Server dispatch loaders.
vw(HEADER)
vw('#include "vkb_gen.h"')
vw('#include "../vkb_wire.h"')
vw('#include "vkb_server_gen.h"')
vw('#include "../vkb_server_iface.h"')
vw('#include <string.h>')
vw()

aliases_of = {}
for a, c in cmd_aliases_included.items():
    aliases_of.setdefault(c, []).append(a)

vw('void vkb_dispatch_load_instance(vkb_dispatch *dt, PFN_vkGetInstanceProcAddr gipa, VkInstance instance)')
vw('{')
for c in dispatch_cmds:
    vw(f'dt->{c} = (PFN_{c})gipa(instance, "{c}");')
    for a in sorted(aliases_of.get(c, [])):
        vw(f'if (!dt->{c}) dt->{c} = (PFN_{c})gipa(instance, "{a}");')
vw('}')
vw()
vw('void vkb_dispatch_load_device(vkb_dispatch *dt, PFN_vkGetDeviceProcAddr gdpa, VkDevice device)')
vw('{')
for c in dispatch_cmds:
    if cmd_level(c) != 'device':
        continue
    vw('{')
    vw(f'PFN_{c} f_ = (PFN_{c})gdpa(device, "{c}");')
    for a in sorted(aliases_of.get(c, [])):
        vw(f'if (!f_) f_ = (PFN_{c})gdpa(device, "{a}");')
    # Device-level entries come only from the device: an instance-level trampoline would dispatch
    # through loader data the server's objects do not carry.
    vw(f'dt->{c} = f_;')
    vw('}')
vw('}')
vw()

# Server header (custom handlers' prototypes).
svh = W()
svh(HEADER)
svh('#ifndef VKB_SERVER_GEN_H')
svh('#define VKB_SERVER_GEN_H')
svh('#include "vkb_gen.h"')
svh('typedef struct vkb_srv_call vkb_srv_call;')
svh('typedef void (*vkb_srv_handler)(vkb_srv_call *c);')
svh('extern const vkb_srv_handler vkb_srv_handlers[VKB_CMD_COUNT];')
for c in wire_cmds:
    svh(f'void vkb_sv_{c}(vkb_srv_call *c);')
for c in EXTRA_WIRE:
    svh(f'void vkb_sv_{c}(vkb_srv_call *c);')
svh('#endif')

# Client header.
clh = W()
clh(HEADER)
clh('#ifndef VKB_CLIENT_GEN_H')
clh('#define VKB_CLIENT_GEN_H')
clh('#include "vkb_gen.h"')

cw(HEADER)
cw('#include "vkb_gen.h"')
cw('#include "vkb_client_gen.h"')
cw('#include "../vkb_wire.h"')
cw('#include "../../client/vkb_client.h"')
cw('#include <string.h>')
cw()


def c_param_decl(p):
    return p.decl


def is_out_struct_templ(t):
    t = reg.canon(t)
    return is_struct(t) and has_pnext(t)


for c in wire_cmds:
    cmd = reg.commands[c]
    ret = cmd['ret']
    params = cmd['params']
    level = cmd_level(c)
    is_cmdbuf = c.startswith('vkCmd')
    decl = ', '.join(c_param_decl(p) for p in params)
    wire_name = f'vkb_wire_{c}'
    ret_c = ret
    clh(f'{ret_c} {wire_name}({decl});')

    # ---------------- client wire stub
    cw(f'{ret_c} {wire_name}({decl})')
    cw('{')
    first = params[0].name if params and is_dhandle(params[0].type) else None
    if is_cmdbuf:
        cw(f'vkb_enc *e = vkb_cmd_record_begin({first}, VKB_CMD_{c});')
        cw('if (!e) return;')
    else:
        tbl = f'vkb_table_of((const void *){first})' if first else '0'
        cw('vkb_call call_;')
        cw(f'vkb_call_begin(&call_, VKB_CMD_{c}, {tbl});')
        cw('vkb_enc *e = &call_.e;')
    outs = []
    for p in params:
        pc = param_class(c, p)
        t = reg.canon(p.type)
        if pc == 'skip':
            cw(f'(void){p.name};')
            continue
        if (c, p.name) in FD_PARAMS:
            if pc == 'in-value':
                cw(f'vkb_enc_fd(e, {p.name});')
            else:
                cw(f'vkb_enc_u8(e, {p.name} != NULL);')
                outs.append(('fd', p))
            continue
        if pc == 'in-value':
            if is_dhandle(t):
                cw(f'vkb_enc_u64(e, vkb_remote((const void *){p.name}));')
            elif is_struct(t):
                if is_pod(t):
                    cw(f'vkb_enc_raw(e, &{p.name}, sizeof({p.name}));')
                else:
                    cw(f'vkb_enc_{t}(e, &{p.name});')
            else:
                sz = scalar_size(t)
                if sz is None:
                    problems.append(f'{c}.{p.name}: unknown scalar {t}')
                    sz = 8
                cw(f'vkb_enc_bytes(e, &{p.name}, sizeof({p.name}));')
        elif pc == 'in-fixed':
            cw(f'vkb_enc_raw(e, {p.name}, sizeof({p.type}) * {re.search(r"\[(.*)\]", p.decl).group(1)});')
        elif pc == 'in-ptr':
            emit_ptr_enc(cw, c, p, p.name, '', 'in')
        elif pc == 'out-ptr':
            kind, cnt = len_expr(c, p, '')
            if t == 'void' and p.ptr == 2:
                problems.append(f'{c}.{p.name}: void** out')
                cw(f'vkb_enc_u8(e, 0);')
                continue
            if t == 'void':
                # Out bytes: length is a size param (value) or a size_t* (two-call).
                if kind == 'count' and cnt and not cnt.startswith('p'):
                    cw(f'vkb_enc_u8(e, {p.name} != NULL); vkb_enc_u64(e, (uint64_t)({cnt}));')
                    outs.append(('bytes', p, cnt))
                elif kind == 'count':
                    cw(f'vkb_enc_u8(e, {p.name} != NULL); vkb_enc_u64(e, {p.name} ? (uint64_t)*{cnt} : 0);')
                    outs.append(('bytes2', p, cnt))
                else:
                    problems.append(f'{c}.{p.name}: void* out without len')
                    cw('vkb_enc_u8(e, 0);')
                continue
            if kind == 'count':
                twocall = cnt.startswith('p') and '->' not in cnt
                n = f'*{cnt}' if twocall else cnt
                cw(f'{{ uint32_t n_ = {p.name} ? (uint32_t)({n if not twocall else f"({cnt} ? {n} : 0)"}) : 0;')
                cw(f'vkb_enc_u8(e, {p.name} != NULL); vkb_enc_u32(e, n_);')
                if is_out_struct_templ(t):
                    cw(f'for (uint32_t i_ = 0; {p.name} && i_ < n_; i_++) vkb_enc_out_template(e, &{p.name}[i_], sizeof({t}));')
                cw('}')
                outs.append(('array', p, cnt, twocall))
            else:
                # single out (scalar, handle, struct), or in/out scalar (counts)
                cw(f'vkb_enc_u8(e, {p.name} != NULL);')
                if is_out_struct_templ(t):
                    cw(f'if ({p.name}) vkb_enc_out_template(e, {p.name}, sizeof({t}));')
                elif not is_struct(t) and not is_dhandle(t):
                    cw(f'if ({p.name}) vkb_enc_bytes(e, {p.name}, sizeof(*{p.name}));')
                outs.append(('single', p))
    if is_cmdbuf:
        cw(f'vkb_cmd_record_end({first});')
        cw('}')
        cw()
    elif ret == 'void' and not outs and not any((c, p.name) in FD_PARAMS for p in params):
        # Nothing comes back: sent without waiting (ordered by the protocol's sequence numbers).
        cw('vkb_call_exec_async(&call_);')
        cw('}')
        cw()
    elif c in ASYNC_RESULT and not outs:
        cw('if (vkb_async_results()) return vkb_call_exec_async(&call_) ? VK_SUCCESS : VK_ERROR_DEVICE_LOST;')
        cw('if (!vkb_call_exec(&call_)) return VK_ERROR_DEVICE_LOST;')
        cw('VkResult r_ = (VkResult)vkb_dec_u32(&call_.d);')
        cw('vkb_call_end(&call_);')
        cw('return r_;')
        cw('}')
        cw()
    else:
        cw('if (!vkb_call_exec(&call_)) {')
        if ret == 'VkResult':
            cw('return VK_ERROR_DEVICE_LOST;')
        elif ret == 'void':
            cw('return;')
        else:
            cw(f'return ({ret})0;')
        cw('}')
        cw('vkb_dec *d = &call_.d;')
        cw('(void)d;')
        if ret != 'void':
            cw(f'{ret} r_;')
            cw('vkb_dec_bytes(d, &r_, sizeof(r_));')
        for o in outs:
            p = o[1]
            t = reg.canon(p.type)
            if o[0] == 'fd':
                cw(f'{{ int fd_ = vkb_dec_fd(d); if ({p.name}) *{p.name} = fd_; else if (fd_ >= 0) vkb_close_fd(fd_); }}')
            elif o[0] == 'bytes':
                cw(f'vkb_dec_blob_into(d, {p.name}, (size_t)({o[2]}));')
            elif o[0] == 'bytes2':
                cw(f'{{ uint64_t sz_ = vkb_dec_u64(d); if ({o[2]}) {{ size_t cap_ = {p.name} ? *{o[2]} : 0; *{o[2]} = (size_t)sz_; vkb_dec_blob_into(d, {p.name}, cap_); }} else vkb_dec_blob_into(d, NULL, 0); }}')
            elif o[0] == 'array':
                cnt, twocall = o[2], o[3]
                cw('{')
                cw('uint32_t rn_ = vkb_dec_u32(d);')
                cw('(void)rn_;')
                if twocall:
                    cw(f'uint32_t cap_ = ({p.name} && {cnt}) ? *{cnt} : 0;')
                    cw(f'if ({cnt}) *{cnt} = rn_;')
                else:
                    cw(f'uint32_t cap_ = {p.name} ? (uint32_t)({cnt}) : 0;')
                cw('uint32_t has_ = vkb_dec_u32(d);')
                cw('for (uint32_t i_ = 0; i_ < has_; i_++) {')
                cw('if (i_ >= cap_) { d->err = 1; break; }')
                if is_dhandle(t):
                    cw(f'{p.name}[i_] = ({p.type})vkb_wrap({objtype(t)}, (const void *){first or 'NULL'}, vkb_dec_u64(d));')
                elif is_out_struct_templ(t):
                    cw(f'vkb_dec_out_struct(d, &{p.name}[i_], sizeof({t}));')
                else:
                    cw(f'vkb_dec_raw_into(d, &{p.name}[i_], sizeof({t}));')
                cw('}')
                cw('}')
            elif o[0] == 'single':
                cw(f'if (vkb_dec_u8(d)) {{')
                if is_dhandle(t):
                    cw(f'{p.type} h_ = ({p.type})vkb_wrap({objtype(t)}, (const void *){first or 'NULL'}, vkb_dec_u64(d));')
                    cw(f'if ({p.name}) *{p.name} = h_;')
                elif is_out_struct_templ(t):
                    cw(f'if ({p.name}) vkb_dec_out_struct(d, {p.name}, sizeof({t})); else d->err = 1;')
                else:
                    cw(f'if ({p.name}) vkb_dec_raw_into(d, {p.name}, sizeof(*{p.name})); else d->err = 1;')
                cw('}')
        cw('vkb_call_end(&call_);')
        if ret != 'void':
            cw('return r_;')
        cw('}')
        cw()

    # ---------------- server handler
    if c in SERVER_CUSTOM:
        continue
    vw(f'void vkb_sv_{c}(vkb_srv_call *c)')
    vw('{')
    vw('vkb_dec *d = &c->d;')
    vw('vkb_enc *e = &c->r;')
    vw('(void)e;')
    call_args = []
    post = []
    for p in params:
        pc = param_class(c, p)
        t = reg.canon(p.type)
        if pc == 'skip':
            call_args.append('NULL')
            continue
        if (c, p.name) in FD_PARAMS:
            if pc == 'in-value':
                vw(f'int {p.name} = vkb_dec_fd(d);')
                post.append(f'if ({p.name} >= 0) vkb_close_fd({p.name});')
                call_args.append(p.name)
            else:
                vw(f'int {p.name}_v = -1; int *{p.name} = vkb_dec_u8(d) ? &{p.name}_v : NULL;')
                call_args.append(p.name)
            continue
        if pc == 'in-value':
            if is_dhandle(t):
                vw(f'{p.type} {p.name} = ({p.type})(uintptr_t)vkb_dec_u64(d);')
            elif is_struct(t):
                vw(f'{p.type} {p.name};')
                if is_pod(t):
                    vw(f'vkb_dec_raw_into(d, &{p.name}, sizeof({p.name}));')
                else:
                    vw(f'vkb_dec_{t}(d, &{p.name});')
            else:
                vw(f'{p.type} {p.name};')
                vw(f'vkb_dec_bytes(d, &{p.name}, sizeof({p.name}));')
            call_args.append(p.name)
        elif pc == 'in-fixed':
            dim = re.search(r"\[(.*)\]", p.decl).group(1)
            vw(f'const {p.type} *{p.name} = (const {p.type} *)vkb_dec_raw_view(d, sizeof({p.type}) * {dim});')
            call_args.append(p.name)
        elif pc == 'in-ptr':
            vw(f'const {p.type} {"*" * p.ptr}{p.name};')
            if p.ptr == 2 and t == 'char':
                emit_ptr_dec(vw, c, p, p.name, 'in')
            else:
                # emit_ptr_dec writes with "s->" names for struct contexts; params use bare names.
                tmp = W()
                emit_ptr_dec(tmp, c, p, p.name, 'in')
                for l in tmp.lines:
                    vw(l.strip())
            call_args.append(f'({p.decl.rsplit(" ", 1)[0]}){p.name}' if p.ptr == 2 else p.name)
        elif pc == 'out-ptr':
            kind, cnt = len_expr(c, p, '')
            if t == 'void':
                if kind == 'count' and cnt and not cnt.startswith('p'):
                    vw(f'uint8_t has_{p.name} = vkb_dec_u8(d); uint64_t n_{p.name} = vkb_dec_u64(d);')
                    vw(f'void *{p.name} = has_{p.name} ? vkb_dec_alloc(d, (size_t)n_{p.name}) : NULL;')
                    post.append(f'vkb_enc_blob(e, {p.name}, (size_t)n_{p.name});')
                elif kind == 'count':
                    vw(f'uint8_t has_{p.name} = vkb_dec_u8(d); uint64_t n_{p.name} = vkb_dec_u64(d);')
                    vw(f'void *{p.name} = has_{p.name} ? vkb_dec_alloc(d, (size_t)n_{p.name}) : NULL;')
                    # the size param is handled as an in/out scalar (size_t*); patch it to our cap
                    post.append(f'vkb_enc_u64(e, {cnt} ? (uint64_t)*{cnt} : 0); vkb_enc_blob(e, {p.name}, {p.name} && {cnt} ? (size_t)*{cnt} : 0);')
                    vw(f'if ({cnt} && {p.name}) *{cnt} = (size_t)n_{p.name};')
                call_args.append(p.name)
                continue
            if kind == 'count':
                twocall = cnt.startswith('p') and '->' not in cnt
                vw(f'uint8_t has_{p.name} = vkb_dec_u8(d); uint32_t n_{p.name} = vkb_dec_u32(d);')
                vw(f'{p.type} *{p.name} = has_{p.name} ? ({p.type} *)vkb_dec_alloc_zero(d, (size_t)n_{p.name} * sizeof({p.type})) : NULL;')
                if is_out_struct_templ(t):
                    vw(f'for (uint32_t i_ = 0; {p.name} && i_ < n_{p.name}; i_++) vkb_dec_out_template(d, &{p.name}[i_], sizeof({t}));')
                if twocall:
                    vw(f'if ({p.name} && {cnt}) *{cnt} = n_{p.name};')
                    rn = f'({cnt} ? *{cnt} : 0)'
                else:
                    rn = f'n_{p.name}'
                post.append('{')
                post.append(f'uint32_t rn_ = {rn};')
                post.append(f'uint32_t has_ = {p.name} ? (rn_ < n_{p.name} ? rn_ : n_{p.name}) : 0;')
                post.append('vkb_enc_u32(e, rn_); vkb_enc_u32(e, has_);')
                if is_dhandle(t):
                    post.append(f'for (uint32_t i_ = 0; i_ < has_; i_++) vkb_enc_u64(e, (uint64_t)(uintptr_t){p.name}[i_]);')
                elif is_out_struct_templ(t):
                    post.append(f'for (uint32_t i_ = 0; i_ < has_; i_++) vkb_enc_out_struct(e, &{p.name}[i_], sizeof({t}));')
                else:
                    post.append(f'for (uint32_t i_ = 0; i_ < has_; i_++) vkb_enc_raw(e, &{p.name}[i_], sizeof({t}));')
                post.append('}')
            else:
                vw(f'uint8_t has_{p.name} = vkb_dec_u8(d);')
                vw(f'{p.type} {p.name}_v;')
                vw(f'memset(&{p.name}_v, 0, sizeof({p.name}_v));')
                if is_out_struct_templ(t):
                    vw(f'if (has_{p.name}) vkb_dec_out_template(d, &{p.name}_v, sizeof({t}));')
                elif not is_struct(t) and not is_dhandle(t):
                    vw(f'if (has_{p.name}) vkb_dec_bytes(d, &{p.name}_v, sizeof({p.name}_v));')
                vw(f'{p.type} *{p.name} = has_{p.name} ? &{p.name}_v : NULL;')
                post.append(f'vkb_enc_u8(e, {p.name} != NULL);')
                if is_dhandle(t):
                    post.append(f'if ({p.name}) vkb_enc_u64(e, (uint64_t)(uintptr_t)*{p.name});')
                elif is_out_struct_templ(t):
                    post.append(f'if ({p.name}) vkb_enc_out_struct(e, {p.name}, sizeof({t}));')
                else:
                    post.append(f'if ({p.name}) vkb_enc_raw(e, {p.name}, sizeof(*{p.name}));')
            call_args.append(p.name)
    vw('if (d->err) { vkb_srv_bad_message(c); return; }')
    fn = f'c->dt->{c}'
    vw(f'if (!{fn}) {{ vkb_srv_missing(c, "{c}"); return; }}')
    if ret != 'void':
        vw(f'{ret} r_ = {fn}({", ".join(call_args)});')
        vw('vkb_enc_bytes(e, &r_, sizeof(r_));')
    else:
        vw(f'{fn}({", ".join(call_args)});')
    # fds out after result
    for p in params:
        if (c, p.name) in FD_PARAMS and param_class(c, p) == 'out-ptr':
            vw(f'vkb_enc_fd(e, {p.name} ? *{p.name} : -1);')
            vw(f'if ({p.name} && *{p.name} >= 0) vkb_srv_close_after_send(c, *{p.name});')
    for l in post:
        vw(l)
    vw('}')
    vw()

# Handler table.
vw('const vkb_srv_handler vkb_srv_handlers[VKB_CMD_COUNT] = {')
for c in cmd_ids:
    vw(f'[VKB_CMD_{c}] = vkb_sv_{c},')
vw('};')

# Client default entry points: everything not custom forwards to its wire stub.
cw('/* Default entry points. */')
for c in wire_cmds:
    if c in CLIENT_CUSTOM:
        continue
    cmd = reg.commands[c]
    params = cmd['params']
    decl = ', '.join(c_param_decl(p) for p in params)
    args = ', '.join(p.name for p in params)
    ret = cmd['ret']
    cw(f'static VKAPI_ATTR {ret} VKAPI_CALL vkb_ep_{c}({decl})')
    cw('{')
    cw(f'{"return " if ret != "void" else ""}vkb_wire_{c}({args});')
    cw('}')
    cw()

# Prototypes of hand-written entry points (client/*.c define them).
for c in sorted(included_cmds):
    if c in CLIENT_CUSTOM or c in client_only_cmds:
        cmd = reg.commands[c]
        decl = ', '.join(c_param_decl(p) for p in cmd['params'])
        clh(f'VKAPI_ATTR {cmd["ret"]} VKAPI_CALL vkb_ep_{c}({decl});')
clh('#endif')


def level_of(c):
    lv = cmd_level(c)
    if lv == 'global':
        return 'VKB_LEVEL_GLOBAL'
    if lv == 'instance':
        t = reg.canon(reg.commands[c]['params'][0].type)
        return 'VKB_LEVEL_PHYSDEV' if t == 'VkPhysicalDevice' else 'VKB_LEVEL_INSTANCE'
    return 'VKB_LEVEL_DEVICE'


cw('const vkb_proc_desc vkb_client_procs[] = {')
entries = []
for c in sorted(included_cmds):
    origin = cmd_origin.get(c, 'core')
    entries.append((c, c, origin))
for a, c in sorted(cmd_aliases_included.items()):
    entries.append((a, c, None))
for name, c, origin in sorted(entries):
    ext = 'NULL'
    # aliases come from the extension that introduced them; find by registry lookup
    o = cmd_origin.get(c, 'core') if name == c else None
    if name != c:
        # find the extension listing this alias name
        for en, (_, cl) in ext_info.items():
            if name in cl:
                o = en
                break
        else:
            o = 'core'
    ext = 'NULL' if o in (None, 'core') else f'"{o}"'
    core = 0
    if name == c and o == 'core' and c in cmd_version:
        core = f'VK_MAKE_API_VERSION(0, {cmd_version[c][0]}, {cmd_version[c][1]}, 0)'
    cw(f'{{"{name}", (PFN_vkVoidFunction)vkb_ep_{c}, {level_of(c)}, {ext}, {core}}},')
cw('};')
cw('const size_t vkb_client_proc_count = sizeof(vkb_client_procs) / sizeof(vkb_client_procs[0]);')

hw('#endif')

os.makedirs(OUT, exist_ok=True)
files = {
    'vkb_gen.h': hw.text(),
    'vkb_gen_structs.c': sw.text(),
    'vkb_gen_client.c': cw.text(),
    'vkb_client_gen.h': clh.text(),
    'vkb_gen_server.c': vw.text(),
    'vkb_server_gen.h': svh.text(),
}
for n, txt in files.items():
    with open(os.path.join(OUT, n), 'w') as f:
        f.write(txt)

print(f'commands: {len(included_cmds)} bridged, {len(wire_cmds)} on the wire, '
      f'{len(client_only_cmds & included_cmds)} client-only; structs: {len(STRUCTS)} '
      f'({sum(1 for s in STRUCTS if not is_pod(s))} non-POD); extensions: {len(bridged_exts)}; '
      f'protocol {proto_hash}')
if problems:
    print('\n'.join(sorted(set(problems))), file=sys.stderr)
