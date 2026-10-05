#if !defined(__x86_64__)
#error thunkaudit is built for x86_64 guests only
#endif

#define LA_SER_ORIG 0x01

static const char vulkan_thunk[] = "/usr/lib/x86_64-linux-gnu/libvulkan.so.1";
static const char vulkan_pin[] = "/usr/lib/x86_64-linux-gnu/droiddeck/libvulkan-thunk.so";

static int same(const char *a, const char *b)
{
    while (*a && *a == *b) {
        a++;
        b++;
    }
    return *a == *b;
}

static const char *base_name(const char *path)
{
    const char *name = path;
    for (; *path; path++)
        if (*path == '/')
            name = path + 1;
    return name;
}

__attribute__((visibility("default"))) unsigned int la_version(unsigned int version)
{
    return version;
}

__attribute__((visibility("default"))) char *la_objsearch(const char *name, unsigned long *cookie, unsigned int flag)
{
    const char *file = base_name(name);
    (void)cookie;
    if (flag == LA_SER_ORIG && !same(name, vulkan_thunk) && (same(file, "libvulkan.so.1") || same(file, "libvulkan.so")))
        return (char *)vulkan_pin;
    return (char *)name;
}
