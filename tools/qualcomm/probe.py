"""Run a real Vulkan transfer and readback through the selected Linux ICD."""
import ctypes as c
import json


U = c.c_uint32
H = c.c_void_p
Q = c.c_uint64


def structure(name, fields):
    return type(name, (c.Structure,), {'_fields_': fields})


Application = structure('Application', [('type', U), ('next', H), ('name', c.c_char_p), ('version', U), ('engine', c.c_char_p), ('engineVersion', U), ('api', U)])
Instance = structure('Instance', [('type', U), ('next', H), ('flags', U), ('application', c.POINTER(Application)), ('layerCount', U), ('layers', H), ('extensionCount', U), ('extensions', H)])
QueueCreate = structure('QueueCreate', [('type', U), ('next', H), ('flags', U), ('family', U), ('count', U), ('priorities', c.POINTER(c.c_float))])
DeviceCreate = structure('DeviceCreate', [('type', U), ('next', H), ('flags', U), ('queueCount', U), ('queues', c.POINTER(QueueCreate)), ('layerCount', U), ('layers', H), ('extensionCount', U), ('extensions', H), ('features', H)])
BufferCreate = structure('BufferCreate', [('type', U), ('next', H), ('flags', U), ('size', Q), ('usage', U), ('sharing', U), ('familyCount', U), ('families', H)])
Requirements = structure('Requirements', [('size', Q), ('alignment', Q), ('types', U)])
MemoryType = structure('MemoryType', [('flags', U), ('heap', U)])
Heap = structure('Heap', [('size', Q), ('flags', U)])
MemoryProperties = structure('MemoryProperties', [('typeCount', U), ('types', MemoryType * 32), ('heapCount', U), ('heaps', Heap * 16)])
Allocate = structure('Allocate', [('type', U), ('next', H), ('size', Q), ('index', U)])
PoolCreate = structure('PoolCreate', [('type', U), ('next', H), ('flags', U), ('family', U)])
CommandAllocate = structure('CommandAllocate', [('type', U), ('next', H), ('pool', H), ('level', U), ('count', U)])
Begin = structure('Begin', [('type', U), ('next', H), ('flags', U), ('inheritance', H)])
Barrier = structure('Barrier', [('type', U), ('next', H), ('source', U), ('destination', U)])
Copy = structure('Copy', [('source', Q), ('destination', Q), ('size', Q)])
Submit = structure('Submit', [('type', U), ('next', H), ('waitCount', U), ('waits', H), ('stages', H), ('commandCount', U), ('commands', c.POINTER(H)), ('signalCount', U), ('signals', H)])


def run():
    vk = c.CDLL('libvulkan.so.1')
    def function(name, arguments, result=c.c_int):
        f = getattr(vk, name)
        f.argtypes, f.restype = arguments, result
        return f
    def check(result, operation):
        if result != 0:
            raise RuntimeError(f'{operation}: VkResult {result}')
    instance, device, pool = H(), H(), H()
    buffers, memories = [], []
    try:
        app = Application(type=0, name=b'DroidDeck Qualcomm probe', api=(1 << 22) | (1 << 12))
        info = Instance(type=1, application=c.pointer(app))
        check(function('vkCreateInstance', [c.POINTER(Instance), H, c.POINTER(H)])(c.byref(info), None, c.byref(instance)), 'instance')
        count = U()
        enumerate_devices = function('vkEnumeratePhysicalDevices', [H, c.POINTER(U), c.POINTER(H)])
        check(enumerate_devices(instance, c.byref(count), None), 'device count')
        if not count.value: raise RuntimeError('No Vulkan devices')
        devices = (H * count.value)()
        check(enumerate_devices(instance, c.byref(count), devices), 'devices')
        physical = H(devices[0])
        properties = c.create_string_buffer(4096)
        function('vkGetPhysicalDeviceProperties', [H, H], None)(physical, properties)
        name = properties.raw[20:276].split(b'\0')[0].decode()
        family_count = U()
        families_fn = function('vkGetPhysicalDeviceQueueFamilyProperties', [H, c.POINTER(U), H], None)
        families_fn(physical, c.byref(family_count), None)
        families = (U * (family_count.value * 6))()
        families_fn(physical, c.byref(family_count), families)
        family = next(i for i in range(family_count.value) if families[i * 6] & 7)
        priority = c.c_float(1)
        queue_info = QueueCreate(type=2, family=family, count=1, priorities=c.pointer(priority))
        device_info = DeviceCreate(type=3, queueCount=1, queues=c.pointer(queue_info))
        check(function('vkCreateDevice', [H, c.POINTER(DeviceCreate), H, c.POINTER(H)])(physical, c.byref(device_info), None, c.byref(device)), 'device')
        queue = H()
        function('vkGetDeviceQueue', [H, U, U, c.POINTER(H)], None)(device, family, 0, c.byref(queue))
        memory_props = MemoryProperties()
        function('vkGetPhysicalDeviceMemoryProperties', [H, c.POINTER(MemoryProperties)], None)(physical, c.byref(memory_props))
        for _ in range(2):
            buffer = H()
            buffer_info = BufferCreate(type=12, size=4096, usage=3)
            check(function('vkCreateBuffer', [H, c.POINTER(BufferCreate), H, c.POINTER(H)])(device, c.byref(buffer_info), None, c.byref(buffer)), 'buffer')
            buffers.append(buffer)
            requirements = Requirements()
            function('vkGetBufferMemoryRequirements', [H, H, c.POINTER(Requirements)], None)(device, buffer, c.byref(requirements))
            index = next(i for i in range(memory_props.typeCount) if requirements.types & (1 << i) and memory_props.types[i].flags & 6 == 6)
            allocation = Allocate(type=5, size=requirements.size, index=index)
            memory = H()
            check(function('vkAllocateMemory', [H, c.POINTER(Allocate), H, c.POINTER(H)])(device, c.byref(allocation), None, c.byref(memory)), 'memory')
            memories.append(memory)
            check(function('vkBindBufferMemory', [H, H, H, Q])(device, buffer, memory, 0), 'bind')
        pool_info = PoolCreate(type=39, family=family)
        check(function('vkCreateCommandPool', [H, c.POINTER(PoolCreate), H, c.POINTER(H)])(device, c.byref(pool_info), None, c.byref(pool)), 'pool')
        command = H()
        command_info = CommandAllocate(type=40, pool=pool, count=1)
        check(function('vkAllocateCommandBuffers', [H, c.POINTER(CommandAllocate), c.POINTER(H)])(device, c.byref(command_info), c.byref(command)), 'command')
        begin = Begin(type=42, flags=1)
        check(function('vkBeginCommandBuffer', [H, c.POINTER(Begin)])(command, c.byref(begin)), 'begin')
        pattern = 0x5A17C0DE
        function('vkCmdFillBuffer', [H, H, Q, Q, U], None)(command, buffers[0], 0, 4096, pattern)
        barrier_fn = function('vkCmdPipelineBarrier', [H, U, U, U, U, c.POINTER(Barrier), U, H, U, H], None)
        barrier = Barrier(type=46, source=0x1000, destination=0x800)
        barrier_fn(command, 0x1000, 0x1000, 0, 1, c.byref(barrier), 0, None, 0, None)
        region = Copy(size=4096)
        function('vkCmdCopyBuffer', [H, H, H, U, c.POINTER(Copy)], None)(command, buffers[0], buffers[1], 1, c.byref(region))
        barrier = Barrier(type=46, source=0x1000, destination=0x2000)
        barrier_fn(command, 0x1000, 0x4000, 0, 1, c.byref(barrier), 0, None, 0, None)
        check(function('vkEndCommandBuffer', [H])(command), 'end')
        submit = Submit(type=4, commandCount=1, commands=c.pointer(command))
        check(function('vkQueueSubmit', [H, U, c.POINTER(Submit), H])(queue, 1, c.byref(submit), None), 'submit')
        check(function('vkQueueWaitIdle', [H])(queue), 'wait')
        mapped = H()
        check(function('vkMapMemory', [H, H, Q, Q, U, c.POINTER(H)])(device, memories[1], 0, 4096, 0, c.byref(mapped)), 'map')
        words = list((U * 1024).from_address(mapped.value))
        function('vkUnmapMemory', [H, H], None)(device, memories[1])
        if words != [pattern] * 1024: raise RuntimeError('GPU transfer readback mismatch')
        print(json.dumps({'status': 'PASS', 'device': name, 'gpu_fill_copy_readback_bytes': 4096}))
    finally:
        if device:
            function('vkDeviceWaitIdle', [H])(device)
            if pool: function('vkDestroyCommandPool', [H, H, H], None)(device, pool, None)
            for buffer in buffers: function('vkDestroyBuffer', [H, H, H], None)(device, buffer, None)
            for memory in memories: function('vkFreeMemory', [H, H, H], None)(device, memory, None)
            function('vkDestroyDevice', [H, H], None)(device, None)
        if instance: function('vkDestroyInstance', [H, H], None)(instance, None)


if __name__ == '__main__':
    run()
