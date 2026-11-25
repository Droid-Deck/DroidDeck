/*
 * Operating-system services behind the Windows Runtime app model.
 *
 * Wine resolves an ActivatableClassId to a DLL, but the runtime ships nothing
 * behind the storage, package, cryptography, display, media and input classes a
 * packaged title reaches for on the way up.  The app-model host answers those
 * because on Windows they are the platform, not the title.
 *
 * The values are the real ones where the platform can answer:
 *
 *   - ApplicationData hands out the package's own LocalState directory under
 *     %LOCALAPPDATA%\Packages\<PackageFamilyName>, and Package derives its
 *     identity, family name and full name from the title's AppxManifest.
 *   - Hashing computes SHA-1, SHA-256 and MD5 and returns the digest.
 *   - DisplayInformation reports the display the process is on, MediaDevice the
 *     audio endpoint the runtime started with, and the input classes what the
 *     system reports.
 *
 * A call the host cannot answer fails with E_NOTIMPL and is named on stderr
 * when DROIDDECK_UWP_TRACE is set, so a gap is visible instead of covered.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <intrin.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

BOOLEAN WINAPI SystemFunction036(void *buffer, ULONG length);

/* --------------------------------------------------------------- plumbing */

typedef struct {
    const void **vtbl;
    LONG refs;
    const char *name;
    int permanent;
} svc_object;

typedef void *hstring_t;

static int svc_trace_on = -1;

/* The title's stderr is not reachable from a loaded DLL in this runtime, so the
   trace is appended to a file beside the prefix with the kernel32 calls only:
   no CRT file table, no stack walk, nothing that takes the loader lock the
   activation already holds. */
static void svc_sink(const char *line)
{
    HANDLE file;
    DWORD written;
    if (svc_trace_on < 0) {
        char setting[8];
        DWORD length = GetEnvironmentVariableA("DROIDDECK_UWP_TRACE", setting, sizeof setting);
        svc_trace_on = length > 0 && length < sizeof setting;
    }
    if (!svc_trace_on) return;
    file = CreateFileA("C:\\uwp-host-trace.log", FILE_APPEND_DATA,
                       FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS,
                       FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return;
    WriteFile(file, line, (DWORD)strlen(line), &written, NULL);
    WriteFile(file, "\r\n", 2, &written, NULL);
    CloseHandle(file);
}

static void svc_trace(const char *text)
{
    char line[384];
    if (svc_trace_on < 0) {
        char setting[8];
        DWORD length = GetEnvironmentVariableA("DROIDDECK_UWP_TRACE", setting, sizeof setting);
        svc_trace_on = length > 0 && length < sizeof setting;
    }
    snprintf(line, sizeof(line), "%04x %s", (unsigned)GetCurrentThreadId(), text);
    svc_sink(line);
}

/* A trace line that carries values, for the calls whose arguments
   say what the title is asking for (a requested size, a mode it wants set). */
static void svc_tracef(const char *format, ...)
{
    char text[320];
    char line[384];
    va_list args;
    if (svc_trace_on < 0) {
        char setting[8];
        DWORD length = GetEnvironmentVariableA("DROIDDECK_UWP_TRACE", setting, sizeof setting);
        svc_trace_on = length > 0 && length < sizeof setting;
    }
    if (!svc_trace_on) return;
    va_start(args, format);
    vsnprintf(text, sizeof(text), format, args);
    va_end(args);
    snprintf(line, sizeof(line), "%04x %s", (unsigned)GetCurrentThreadId(), text);
    svc_sink(line);
}

static void svc_trace_w(const char *tag, const WCHAR *text)
{
    char narrow[320];
    char line[384];
    int i;
    if (svc_trace_on < 0) svc_trace("");
    if (!svc_trace_on) return;
    for (i = 0; i < 319 && text && text[i]; i++) narrow[i] = (char)text[i];
    narrow[i] = 0;
    snprintf(line, sizeof(line), "%s %s", tag, narrow);
    svc_trace(line);
}

__attribute__((noinline)) static void svc_trace_iid(const char *tag, const GUID *id)
{
    char text[96];
    if (svc_trace_on < 0) svc_trace("");
    if (!svc_trace_on || !id) return;
    snprintf(text, sizeof(text), "%s %08lx-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x", tag,
             (unsigned long)id->Data1, (unsigned)id->Data2, (unsigned)id->Data3,
             id->Data4[0], id->Data4[1], id->Data4[2], id->Data4[3],
             id->Data4[4], id->Data4[5], id->Data4[6], id->Data4[7]);
    svc_trace(text);
}

static void svc_trace_ptr(const char *tag, const void *pointer)
{
    char text[96];
    if (svc_trace_on < 0) svc_trace("");
    if (!svc_trace_on) return;
    snprintf(text, sizeof(text), "%s %p", tag, pointer);
    svc_trace(text);
}

/* A title sizing its pools reads these numbers back into its own budget, so a
   run records the exact values handed over instead of only that the call
   happened. */
static void svc_trace_u64(const char *tag, unsigned long long value)
{
    char text[96];
    if (svc_trace_on < 0) svc_trace("");
    if (!svc_trace_on) return;
    snprintf(text, sizeof(text), "%s %llu", tag, value);
    svc_trace(text);
}

static void svc_trace_bytes(const char *tag, const unsigned char *data, int count)
{
    char text[256];
    int i, at;
    if (svc_trace_on < 0) svc_trace("");
    if (!svc_trace_on || !data) return;
    at = snprintf(text, sizeof(text), "%s", tag);
    for (i = 0; i < count && at < (int)sizeof(text) - 4; i++)
        at += snprintf(text + at, sizeof(text) - (size_t)at, " %02x", data[i]);
    svc_trace(text);
}

typedef HRESULT(WINAPI *create_hstring_fn)(const WCHAR *, UINT32, hstring_t *);
typedef const WCHAR *(WINAPI *hstring_buffer_fn)(hstring_t, UINT32 *);
typedef HRESULT(WINAPI *delete_hstring_fn)(hstring_t);

static create_hstring_fn svc_create_hstring;
static hstring_buffer_fn svc_hstring_buffer;
static delete_hstring_fn svc_delete_hstring;

static void svc_load_hstring(void)
{
    HMODULE combase;
    if (svc_create_hstring) return;
    combase = GetModuleHandleA("combase.dll");
    if (!combase) combase = LoadLibraryA("combase.dll");
    if (!combase) return;
    svc_create_hstring = (create_hstring_fn)(void *)GetProcAddress(combase, "WindowsCreateString");
    svc_hstring_buffer =
        (hstring_buffer_fn)(void *)GetProcAddress(combase, "WindowsGetStringRawBuffer");
    svc_delete_hstring = (delete_hstring_fn)(void *)GetProcAddress(combase, "WindowsDeleteString");
}

static hstring_t svc_string_len(const WCHAR *text, UINT32 length)
{
    hstring_t out = NULL;
    svc_load_hstring();
    if (!svc_create_hstring || !text || !length) return NULL;
    svc_create_hstring(text, length, &out);
    return out;
}

static hstring_t svc_string(const WCHAR *text)
{
    if (!text) return NULL;
    return svc_string_len(text, (UINT32)wcslen(text));
}

static const WCHAR *svc_string_raw(hstring_t text, UINT32 *length)
{
    UINT32 count = 0;
    const WCHAR *wide;
    svc_load_hstring();
    if (!text) {
        if (length) *length = 0;
        return L"";
    }
    if (!svc_hstring_buffer) {
        if (length) *length = 0;
        return NULL;
    }
    wide = svc_hstring_buffer(text, &count);
    if (length) *length = wide ? count : 0;
    return wide ? wide : L"";
}

static const WCHAR *svc_string_text(hstring_t text)
{
    svc_load_hstring();
    if (!svc_hstring_buffer || !text) return NULL;
    return svc_hstring_buffer(text, NULL);
}

static ULONG svc_AddRef(svc_object *self) { return (ULONG)InterlockedIncrement(&self->refs); }

static ULONG svc_Release(svc_object *self)
{
    LONG left = InterlockedDecrement(&self->refs);
    if (left <= 0 && !self->permanent) free(self);
    return (ULONG)left;
}

static const GUID svc_iid_unknown = {0x00000000, 0x0000, 0x0000,
                                     {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const GUID svc_iid_inspectable = {0xaf86e2e0, 0xb12d, 0x4c6a,
                                         {0x9c, 0x5a, 0xd7, 0xaa, 0x65, 0x10, 0x1e, 0x90}};
static const GUID svc_iid_agile_object = {0x94ea2b94, 0xe9cc, 0x49e0,
                                          {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};
static const GUID svc_iid_factory = {0x00000035, 0x0000, 0x0000,
                                     {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};

static HRESULT svc_get_iids(svc_object *self, ULONG *count, GUID **ids)
{
    (void)self;
    if (count) *count = 0;
    if (ids) *ids = NULL;
    return S_OK;
}

static HRESULT svc_get_class_name(svc_object *self, hstring_t *out)
{
    WCHAR name[128];
    int i;
    if (!out) return E_POINTER;
    for (i = 0; i < 127 && self->name && self->name[i]; i++) name[i] = (WCHAR)self->name[i];
    name[i] = 0;
    *out = svc_string(name);
    return S_OK;
}

static HRESULT svc_get_trust(svc_object *self, int *out)
{
    (void)self;
    if (out) *out = 2;
    return S_OK;
}

/* A slot the host does not implement: it reports the gap and leaves every
   argument alone, because writing an out parameter the caller did not fill in
   is how a miss turns into a crash. */
static HRESULT svc_unimplemented(void *self, void *a, void *b, void *c, void *d)
{
    char line[192];
    svc_object *object = (svc_object *)self;
    (void)a;
    (void)b;
    (void)c;
    (void)d;
    if (object && object->name)
        snprintf(line, sizeof line, "unimplemented slot on %s", object->name);
    else
        snprintf(line, sizeof line, "unimplemented slot");
    svc_trace(line);
    return E_NOTIMPL;
}

static int svc_readable(const void *pointer, size_t size)
{
    MEMORY_BASIC_INFORMATION info;
    if (!pointer) return 0;
    if (VirtualQuery(pointer, &info, sizeof(info)) != sizeof(info)) return 0;
    if (info.State != MEM_COMMIT) return 0;
    if (info.Protect & (PAGE_NOACCESS | PAGE_GUARD)) return 0;
    return (const char *)pointer + size <= (const char *)info.BaseAddress + info.RegionSize;
}

static int svc_writable(const void *pointer, size_t size)
{
    MEMORY_BASIC_INFORMATION info;
    DWORD write = PAGE_READWRITE | PAGE_WRITECOPY | PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY;
    if (!pointer) return 0;
    if (VirtualQuery(pointer, &info, sizeof(info)) != sizeof(info)) return 0;
    if (info.State != MEM_COMMIT || !(info.Protect & write)) return 0;
    if (info.Protect & (PAGE_NOACCESS | PAGE_GUARD)) return 0;
    return (const char *)pointer + size <= (const char *)info.BaseAddress + info.RegionSize;
}

/* ------------------------------------------------------- async operations */

static const GUID svc_iid_async_info = {0x00000036, 0x0000, 0x0000,
                                        {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const GUID svc_iid_async_action = {0x5a648006, 0x843a, 0x4da9,
                                          {0x86, 0x5b, 0x9d, 0x26, 0xe5, 0xdf, 0xad, 0x7b}};
static const GUID svc_iid_async_storage_file = {0x5e52f8ce, 0xaced, 0x5a42,
                                                {0x95, 0xb4, 0xf6, 0x74, 0xdd, 0x84, 0x88, 0x5e}};
static const GUID svc_iid_async_storage_folder = {0x6be9e7d7, 0xe83a, 0x5cbc,
                                                  {0x80, 0x2c, 0x17, 0x68, 0x96, 0x0b, 0x52, 0xc3}};
static const GUID svc_iid_async_storage_item = {0x5fc9c137, 0xebb7, 0x5e6c,
                                                {0x9c, 0xba, 0x68, 0x6f, 0x2e, 0xc2, 0xb0, 0xbb}};
static const GUID svc_iid_async_uicommand = {0xb8770535, 0x6a4b, 0x52b1,
                                             {0xb5, 0x78, 0xf3, 0xcd, 0xc5, 0x00, 0x7a, 0x1f}};
static const GUID svc_iid_async_devinfo = {0x07faa053, 0xeb2f, 0x5cba,
                                           {0xb2, 0x5b, 0xd9, 0xd5, 0x7b, 0xe6, 0x71, 0x5f}};
static const GUID svc_iid_async_devcoll = {0x45180254, 0x082e, 0x5274,
                                           {0xb2, 0xe7, 0xac, 0x05, 0x17, 0xf4, 0x4d, 0x07}};
static const GUID svc_iid_async_store_license = {0x3866370b, 0xafc6, 0x5d01,
                                                 {0x84, 0xc2, 0x45, 0x74, 0x62, 0x8d, 0xe5, 0x39}};
static const GUID svc_iid_async_store_product = {0x9e61e86b, 0x6afb, 0x50ae,
                                                 {0xaf, 0xc1, 0xc5, 0x9f, 0x54, 0x51, 0x08, 0xdd}};
static const GUID svc_iid_async_store_query = {0x9699e7bb, 0xea1f, 0x5e03,
                                               {0x94, 0x39, 0xc8, 0x0e, 0x69, 0x77, 0xb7, 0x11}};
static const GUID svc_iid_async_store_consum = {0x873c497b, 0xc3f7, 0x5657,
                                                {0xb9, 0x21, 0x3e, 0x58, 0xce, 0x48, 0xee, 0x50}};
static const GUID svc_iid_async_store_acqlic = {0xdd6c4705, 0xa76c, 0x528e,
                                                {0x99, 0xa5, 0xcd, 0xd1, 0x31, 0x97, 0xd4, 0xcf}};
static const GUID svc_iid_async_store_purchase = {0x33d8cc30, 0x78f5, 0x5f81,
                                                  {0xaa, 0x2d, 0xa4, 0xfa, 0x2a, 0x3b, 0x1c, 0x68}};
static const GUID svc_iid_async_store_updates = {0x0ac66c33, 0x45b8, 0x546b,
                                                 {0xaa, 0xaf, 0xd5, 0x8d, 0x62, 0xa4, 0xc5, 0xc5}};
static const GUID svc_iid_async_hstring = {0x3e1fe603, 0xf897, 0x5263,
                                           {0xb3, 0x28, 0x08, 0x06, 0x42, 0x6b, 0x8a, 0x79}};
static const GUID svc_iid_async_push_channel = {0x70945a09, 0x331a, 0x5e40,
                                                {0xb8, 0x54, 0x66, 0xb7, 0xa3, 0x23, 0x3b, 0xab}};
static const GUID svc_iid_async_ext_exec_result = {0x1dbb1bc9, 0x6cd7, 0x5947,
                                                   {0x8c, 0xd1, 0x29, 0x63, 0x2b, 0x5a, 0xa9, 0x50}};

typedef struct {
    svc_object base;
    /* IAsyncInfo has its own vtable at its own address: slot 6 on IAsyncInfo is
       get_Id while slot 6 on IAsyncOperation is put_Completed. */
    svc_object info;
    const GUID *op_iid;
    void *result;
    void *volatile handler;
    HRESULT error;
    UINT32 u32_result;
    volatile LONG status;
    volatile LONG fired;
} async_object;

#ifndef E_ILLEGAL_DELEGATE_ASSIGNMENT
#define E_ILLEGAL_DELEGATE_ASSIGNMENT ((HRESULT)0x80000018L)
#endif

/* AsyncStatus */
#define ASYNC_STARTED 0
#define ASYNC_COMPLETED 1
#define ASYNC_ERROR 3

static const void *async_info_vtable[];
static const void *async_action_vtable[];
static const void *async_operation_vtable[];

/* Defined with the storage objects: they know which interface pointer they were
   handed and reach the one reference count that owns the allocation. */
static void svc_hold(void *pointer);
static void svc_drop(void *pointer);

static async_object *async_from(void *self)
{
    svc_object *header = (svc_object *)self;
    if (header && header->vtbl == async_info_vtable)
        return (async_object *)((char *)self - offsetof(async_object, info));
    return (async_object *)self;
}

static ULONG async_AddRef(void *self) { return svc_AddRef(&async_from(self)->base); }
static ULONG async_Release(void *self)
{
    async_object *object = async_from(self);
    LONG left = InterlockedDecrement(&object->base.refs);
    if (left <= 0) {
        if (object->result) svc_drop(object->result);
        if (object->handler)
            ((ULONG(__stdcall *)(void *))(*(void ***)object->handler)[2])(object->handler);
        free(object);
    }
    return (ULONG)left;
}

static HRESULT async_QueryInterface(void *self, const GUID *id, void **out)
{
    async_object *object = async_from(self);
    if (!out) return E_POINTER;
    *out = NULL;
    if (!id) return E_INVALIDARG;
    if (IsEqualIID(id, &svc_iid_async_info)) {
        *out = &object->info;
    } else if (IsEqualIID(id, &svc_iid_unknown) || IsEqualIID(id, &svc_iid_inspectable) ||
               IsEqualIID(id, &svc_iid_agile_object) ||
               (object->op_iid && IsEqualIID(id, object->op_iid))) {
        *out = object;
    } else {
        return E_NOINTERFACE;
    }
    svc_AddRef(&object->base);
    return S_OK;
}

static HRESULT async_get_Id(async_object *self, unsigned *out)
{
    (void)self;
    if (out) *out = 1;
    return S_OK;
}

static HRESULT async_get_Status(void *self, int *out)
{
    if (out) *out = async_from(self)->status;
    return S_OK;
}

static HRESULT async_get_ErrorCode(void *self, HRESULT *out)
{
    if (out) *out = async_from(self)->error;
    return S_OK;
}

static HRESULT async_Cancel(async_object *self)
{
    (void)self;
    return S_OK;
}

static HRESULT async_Close(async_object *self)
{
    (void)self;
    return S_OK;
}

/* The Completed handler runs once, on whichever side comes second: assigning
   it to an operation that has finished runs it at once, which is what a
   filesystem call that completed inline looks like to a projection, and an
   operation still running runs it when it finishes. */
static void async_fire(async_object *object)
{
    typedef HRESULT(__stdcall * invoke_fn)(void *, void *, int);
    void *handler = object->handler;
    invoke_fn *slots;
    if (!handler || InterlockedExchange(&object->fired, 1)) return;
    slots = *(invoke_fn **)handler;
    if (slots && svc_readable(slots, 4 * sizeof(void *)) && slots[3])
        slots[3](handler, object, (int)object->status);
}

static HRESULT async_put_Completed(void *self, void *handler)
{
    async_object *object = async_from(self);
    if (!handler || !svc_readable(handler, sizeof(void *))) return E_INVALIDARG;
    ((ULONG(__stdcall *)(void *))(*(void ***)handler)[1])(handler);
    if (InterlockedCompareExchangePointer((PVOID volatile *)&object->handler, handler, NULL) !=
        NULL) {
        ((ULONG(__stdcall *)(void *))(*(void ***)handler)[2])(handler);
        return E_ILLEGAL_DELEGATE_ASSIGNMENT;
    }
    MemoryBarrier();
    if (object->status != ASYNC_STARTED) async_fire(object);
    return S_OK;
}

static HRESULT async_get_Completed(void *self, void **out)
{
    async_object *object = async_from(self);
    void *handler;
    if (!out) return E_POINTER;
    handler = InterlockedCompareExchangePointer((PVOID volatile *)&object->handler, NULL, NULL);
    if (handler) ((ULONG(__stdcall *)(void *))(*(void ***)handler)[1])(handler);
    *out = handler;
    return S_OK;
}

static HRESULT async_GetResults(void *self, void **out)
{
    async_object *object = async_from(self);
    if (!out) return E_POINTER;
    svc_trace(object->result ? "async results" : "async results empty");
    *out = object->result;
    if (object->result) svc_hold(object->result);
    return object->error;
}

static HRESULT async_action_GetResults(void *self) { return async_from(self)->error; }

static const void *async_info_vtable[] = {
    (const void *)async_QueryInterface, (const void *)async_AddRef, (const void *)async_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)async_get_Id, (const void *)async_get_Status,
    (const void *)async_get_ErrorCode, (const void *)async_Cancel, (const void *)async_Close,
};

static const void *async_action_vtable[] = {
    (const void *)async_QueryInterface, (const void *)async_AddRef, (const void *)async_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)async_put_Completed, (const void *)async_get_Completed,
    (const void *)async_action_GetResults,
};

static const void *async_operation_vtable[] = {
    (const void *)async_QueryInterface, (const void *)async_AddRef, (const void *)async_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)async_put_Completed, (const void *)async_get_Completed,
    (const void *)async_GetResults,
};

static async_object *async_new_iid(int operation, const GUID *op_iid, HRESULT error, void *result)
{
    async_object *object = (async_object *)calloc(1, sizeof(async_object));
    if (!object) return NULL;
    object->base.vtbl = operation ? async_operation_vtable : async_action_vtable;
    object->base.refs = 1;
    object->base.name =
        operation ? "Windows.Foundation.IAsyncOperation" : "Windows.Foundation.IAsyncAction";
    object->info.vtbl = async_info_vtable;
    object->info.refs = 1;
    object->info.permanent = 1;
    object->info.name = "Windows.Foundation.IAsyncInfo";
    object->op_iid = operation ? op_iid : &svc_iid_async_action;
    object->result = result;
    object->error = error;
    object->status = error ? ASYNC_ERROR : ASYNC_COMPLETED;
    return object;
}

static async_object *async_new(int operation, HRESULT error, void *result)
{
    return async_new_iid(operation, NULL, error, result);
}

/* An action that finishes later, on another thread: CoreDispatcher work stays
   Started until the view thread has run it. */
void *svc_action_started(void)
{
    async_object *object = async_new(0, S_OK, NULL);
    if (!object) return NULL;
    object->status = ASYNC_STARTED;
    return object;
}

void svc_action_finish(void *action, HRESULT error)
{
    async_object *object = async_from(action);
    object->error = error;
    MemoryBarrier();
    object->status = FAILED(error) ? ASYNC_ERROR : ASYNC_COMPLETED;
    MemoryBarrier();
    if (object->handler) async_fire(object);
}

static HRESULT async_action(HRESULT error, void **out)
{
    async_object *object = async_new(0, error, NULL);
    if (!object) return E_OUTOFMEMORY;
    svc_trace(error ? "async action error" : "async action");
    *out = object;
    return S_OK;
}

static HRESULT async_operation_iid(const GUID *op_iid, HRESULT error, void *result, void **out)
{
    async_object *object = async_new_iid(1, op_iid, error, result);
    if (!object) return E_OUTOFMEMORY;
    svc_trace(error ? "async operation error" : "async operation");
    *out = object;
    return S_OK;
}

static HRESULT __stdcall async_u32_GetResults(void *self, UINT32 *out)
{
    async_object *object = async_from(self);
    if (!out) return E_POINTER;
    *out = object->u32_result;
    return object->error;
}

static const void *async_u32_operation_vtable[] = {
    (const void *)async_QueryInterface, (const void *)async_AddRef, (const void *)async_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)async_put_Completed, (const void *)async_get_Completed,
    (const void *)async_u32_GetResults,
};

static HRESULT async_u32_operation_iid(const GUID *op_iid, HRESULT error, UINT32 value, void **out)
{
    async_object *object = async_new_iid(1, op_iid, error, NULL);
    if (!object) return E_OUTOFMEMORY;
    object->base.vtbl = async_u32_operation_vtable;
    object->u32_result = value;
    svc_trace(error ? "async u32 operation error" : "async u32 operation");
    *out = object;
    return S_OK;
}

/* ------------------------------------------------------------ IBuffer */

static const GUID svc_iid_buffer = {0x905a0fe0, 0xbc53, 0x11df,
                                    {0x8c, 0x49, 0x00, 0x1e, 0x4f, 0xc6, 0x86, 0xda}};
static const GUID svc_iid_buffer_bytes = {0x905a0fef, 0xbc53, 0x11df,
                                          {0x8c, 0x49, 0x00, 0x1e, 0x4f, 0xc6, 0x86, 0xda}};

typedef struct {
    svc_object base;
    svc_object bytes; /* IBufferByteAccess */
    unsigned capacity;
    unsigned length;
    unsigned char *data;
} winrt_buffer;

static const void *buffer_vtable[];
static const void *buffer_bytes_vtable[];

static winrt_buffer *buffer_from(void *self)
{
    svc_object *header = (svc_object *)self;
    if (header && header->vtbl == buffer_bytes_vtable)
        return (winrt_buffer *)((char *)self - offsetof(winrt_buffer, bytes));
    return (winrt_buffer *)self;
}

static ULONG buffer_AddRef(void *self) { return svc_AddRef(&buffer_from(self)->base); }

static ULONG buffer_Release(void *self)
{
    winrt_buffer *buffer = buffer_from(self);
    LONG left = InterlockedDecrement(&buffer->base.refs);
    if (left <= 0) {
        free(buffer->data);
        free(buffer);
    }
    return (ULONG)left;
}

static HRESULT buffer_QueryInterface(void *self, const GUID *id, void **out)
{
    winrt_buffer *buffer = buffer_from(self);
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_buffer_bytes))
        *out = &buffer->bytes;
    else if (IsEqualIID(id, &svc_iid_buffer) || IsEqualIID(id, &svc_iid_unknown) ||
             IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object))
        *out = buffer;
    else {
        *out = NULL;
        return E_NOINTERFACE;
    }
    buffer_AddRef(buffer);
    return S_OK;
}

static HRESULT buffer_get_Capacity(winrt_buffer *self, unsigned *out)
{
    if (!out) return E_POINTER;
    *out = self->capacity;
    return S_OK;
}

static HRESULT buffer_get_Length(winrt_buffer *self, unsigned *out)
{
    if (!out) return E_POINTER;
    *out = self->length;
    return S_OK;
}

static HRESULT buffer_put_Length(winrt_buffer *self, unsigned length)
{
    if (length > self->capacity) return E_INVALIDARG;
    self->length = length;
    return S_OK;
}

/* IBufferByteAccess::Buffer hands out the bytes themselves for a caller that
   marshals the buffer into its own code. IBufferByteAccess inherits from
   IUnknown, not IInspectable, so Buffer is at slot 3. */
static HRESULT buffer_get_Buffer(void *self, unsigned char **out)
{
    winrt_buffer *buffer = buffer_from(self);
    if (!out) return E_POINTER;
    *out = buffer->data;
    return S_OK;
}

static const void *buffer_vtable[] = {
    (const void *)buffer_QueryInterface, (const void *)buffer_AddRef, (const void *)buffer_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)buffer_get_Capacity, (const void *)buffer_get_Length,
    (const void *)buffer_put_Length,
};

static const void *buffer_bytes_vtable[] = {
    (const void *)buffer_QueryInterface, (const void *)buffer_AddRef, (const void *)buffer_Release,
    (const void *)buffer_get_Buffer,
};

static winrt_buffer *buffer_new(unsigned capacity)
{
    winrt_buffer *buffer = (winrt_buffer *)calloc(1, sizeof(winrt_buffer));
    if (!buffer) return NULL;
    buffer->base.vtbl = buffer_vtable;
    buffer->base.refs = 1;
    buffer->base.name = "Windows.Storage.Streams.Buffer";
    buffer->bytes.vtbl = buffer_bytes_vtable;
    buffer->bytes.refs = 1;
    buffer->bytes.permanent = 1;
    buffer->bytes.name = buffer->base.name;
    if (capacity) {
        buffer->data = (unsigned char *)calloc(capacity, 1);
        if (!buffer->data) {
            free(buffer);
            return NULL;
        }
    }
    buffer->capacity = capacity;
    return buffer;
}

static winrt_buffer *buffer_from_bytes(const unsigned char *data, unsigned length)
{
    winrt_buffer *buffer = buffer_new(length ? length : 1);
    if (!buffer) return NULL;
    if (length && data) memcpy(buffer->data, data, length);
    buffer->length = length;
    return buffer;
}

static const unsigned char *buffer_bytes(void *self)
{
    svc_object *header = (svc_object *)self;
    if (!header) return (const unsigned char *)"";
    if (header->vtbl == buffer_vtable || header->vtbl == buffer_bytes_vtable) {
        winrt_buffer *buffer = buffer_from(self);
        return buffer->data ? buffer->data : (const unsigned char *)"";
    }
    /* A foreign IBuffer (such as one from wintypes.dll) exposes its bytes
       through IBufferByteAccess::Buffer. */
    {
        typedef HRESULT(WINAPI *qi_fn)(void *, const GUID *, void **);
        typedef ULONG(WINAPI *release_fn)(void *);
        typedef HRESULT(WINAPI *bytes_fn)(void *, unsigned char **);
        void *access = NULL;
        unsigned char *ptr = NULL;
        const void *const *vtbl = *(const void *const *const *)self;
        if (SUCCEEDED(((qi_fn)vtbl[0])(self, &svc_iid_buffer_bytes, &access)) && access) {
            const void *const *avtbl = *(const void *const *const *)access;
            ((bytes_fn)avtbl[3])(access, &ptr);
            ((release_fn)avtbl[2])(access);
        }
        return ptr ? ptr : (const unsigned char *)"";
    }
}

static unsigned buffer_length(void *self)
{
    svc_object *header = (svc_object *)self;
    if (!header) return 0;
    if (header->vtbl == buffer_vtable || header->vtbl == buffer_bytes_vtable)
        return buffer_from(self)->length;
    {
        typedef HRESULT(WINAPI *get_length_fn)(void *, unsigned *);
        unsigned length = 0;
        const void *const *vtbl = *(const void *const *const *)self;
        ((get_length_fn)vtbl[7])(self, &length);
        return length;
    }
}

/* ------------------------------------------------------------- digests */

static unsigned rotl32(unsigned x, int n) { return (x << n) | (x >> (32 - n)); }
static unsigned rotr32(unsigned x, int n) { return (x >> n) | (x << (32 - n)); }

typedef struct {
    unsigned state[5];
    unsigned long long total;
    unsigned char block[64];
    unsigned block_length;
} sha1_ctx;

typedef struct {
    unsigned state[8];
    unsigned long long total;
    unsigned char block[64];
    unsigned block_length;
} sha256_ctx;

typedef struct {
    unsigned state[4];
    unsigned long long total;
    unsigned char block[64];
    unsigned block_length;
} md5_ctx;

static void sha1_compress(sha1_ctx *ctx, const unsigned char *block)
{
    unsigned w[80];
    unsigned a, b, c, d, e;
    int i;
    for (i = 0; i < 16; i++)
        w[i] = ((unsigned)block[i * 4] << 24) | ((unsigned)block[i * 4 + 1] << 16) |
               ((unsigned)block[i * 4 + 2] << 8) | (unsigned)block[i * 4 + 3];
    for (i = 16; i < 80; i++) w[i] = rotl32(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
    a = ctx->state[0]; b = ctx->state[1]; c = ctx->state[2]; d = ctx->state[3]; e = ctx->state[4];
    for (i = 0; i < 80; i++) {
        unsigned f, k, temp;
        if (i < 20) { f = (b & c) | (~b & d); k = 0x5a827999; }
        else if (i < 40) { f = b ^ c ^ d; k = 0x6ed9eba1; }
        else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8f1bbcdc; }
        else { f = b ^ c ^ d; k = 0xca62c1d6; }
        temp = rotl32(a, 5) + f + e + k + w[i];
        e = d; d = c; c = rotl32(b, 30); b = a; a = temp;
    }
    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
    ctx->state[4] += e;
}

static void sha1_init(sha1_ctx *ctx)
{
    ctx->state[0] = 0x67452301; ctx->state[1] = 0xefcdab89; ctx->state[2] = 0x98badcfe;
    ctx->state[3] = 0x10325476; ctx->state[4] = 0xc3d2e1f0;
    ctx->total = 0;
    ctx->block_length = 0;
}

static void sha1_update(sha1_ctx *ctx, const unsigned char *data, unsigned length)
{
    unsigned i;
    for (i = 0; i < length; i++) {
        ctx->block[ctx->block_length++] = data[i];
        if (ctx->block_length == 64) {
            sha1_compress(ctx, ctx->block);
            ctx->block_length = 0;
        }
    }
    ctx->total += length;
}

static void sha1_final(sha1_ctx *ctx, unsigned char out[20])
{
    unsigned long long bits = ctx->total * 8;
    unsigned char pad = 0x80, zero = 0, length_bytes[8];
    int i;
    sha1_update(ctx, &pad, 1);
    while (ctx->block_length != 56) sha1_update(ctx, &zero, 1);
    for (i = 0; i < 8; i++) length_bytes[i] = (unsigned char)(bits >> (56 - i * 8));
    sha1_update(ctx, length_bytes, 8);
    for (i = 0; i < 5; i++) {
        out[i * 4] = (unsigned char)(ctx->state[i] >> 24);
        out[i * 4 + 1] = (unsigned char)(ctx->state[i] >> 16);
        out[i * 4 + 2] = (unsigned char)(ctx->state[i] >> 8);
        out[i * 4 + 3] = (unsigned char)ctx->state[i];
    }
}

static const unsigned sha256_k[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

static void sha256_compress(sha256_ctx *ctx, const unsigned char *block)
{
    unsigned w[64];
    unsigned a, b, c, d, e, f, g, h, t1, t2;
    int i;
    for (i = 0; i < 16; i++)
        w[i] = ((unsigned)block[i * 4] << 24) | ((unsigned)block[i * 4 + 1] << 16) |
               ((unsigned)block[i * 4 + 2] << 8) | (unsigned)block[i * 4 + 3];
    for (i = 16; i < 64; i++) {
        unsigned s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
        unsigned s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    a = ctx->state[0]; b = ctx->state[1]; c = ctx->state[2]; d = ctx->state[3];
    e = ctx->state[4]; f = ctx->state[5]; g = ctx->state[6]; h = ctx->state[7];
    for (i = 0; i < 64; i++) {
        unsigned s1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
        unsigned ch = (e & f) ^ (~e & g);
        unsigned s0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
        unsigned maj = (a & b) ^ (a & c) ^ (b & c);
        t1 = h + s1 + ch + sha256_k[i] + w[i];
        t2 = s0 + maj;
        h = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }
    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
    ctx->state[4] += e; ctx->state[5] += f; ctx->state[6] += g; ctx->state[7] += h;
}

static void sha256_init(sha256_ctx *ctx)
{
    ctx->state[0] = 0x6a09e667; ctx->state[1] = 0xbb67ae85; ctx->state[2] = 0x3c6ef372;
    ctx->state[3] = 0xa54ff53a; ctx->state[4] = 0x510e527f; ctx->state[5] = 0x9b05688c;
    ctx->state[6] = 0x1f83d9ab; ctx->state[7] = 0x5be0cd19;
    ctx->total = 0;
    ctx->block_length = 0;
}

static void sha256_update(sha256_ctx *ctx, const unsigned char *data, unsigned length)
{
    unsigned i;
    for (i = 0; i < length; i++) {
        ctx->block[ctx->block_length++] = data[i];
        if (ctx->block_length == 64) {
            sha256_compress(ctx, ctx->block);
            ctx->block_length = 0;
        }
    }
    ctx->total += length;
}

static void sha256_final(sha256_ctx *ctx, unsigned char out[32])
{
    unsigned long long bits = ctx->total * 8;
    unsigned char pad = 0x80, zero = 0, length_bytes[8];
    int i;
    sha256_update(ctx, &pad, 1);
    while (ctx->block_length != 56) sha256_update(ctx, &zero, 1);
    for (i = 0; i < 8; i++) length_bytes[i] = (unsigned char)(bits >> (56 - i * 8));
    sha256_update(ctx, length_bytes, 8);
    for (i = 0; i < 8; i++) {
        out[i * 4] = (unsigned char)(ctx->state[i] >> 24);
        out[i * 4 + 1] = (unsigned char)(ctx->state[i] >> 16);
        out[i * 4 + 2] = (unsigned char)(ctx->state[i] >> 8);
        out[i * 4 + 3] = (unsigned char)ctx->state[i];
    }
}

static const unsigned md5_k[64] = {
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
};

static const unsigned md5_s[64] = {
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
};

static void md5_compress(md5_ctx *ctx, const unsigned char *block)
{
    unsigned m[16];
    unsigned a = ctx->state[0], b = ctx->state[1], c = ctx->state[2], d = ctx->state[3];
    int i;
    for (i = 0; i < 16; i++)
        m[i] = (unsigned)block[i * 4] | ((unsigned)block[i * 4 + 1] << 8) |
               ((unsigned)block[i * 4 + 2] << 16) | ((unsigned)block[i * 4 + 3] << 24);
    for (i = 0; i < 64; i++) {
        unsigned f, g, temp;
        if (i < 16) { f = (b & c) | (~b & d); g = (unsigned)i; }
        else if (i < 32) { f = (d & b) | (~d & c); g = (unsigned)(5 * i + 1) % 16; }
        else if (i < 48) { f = b ^ c ^ d; g = (unsigned)(3 * i + 5) % 16; }
        else { f = c ^ (b | ~d); g = (unsigned)(7 * i) % 16; }
        temp = d;
        d = c;
        c = b;
        b = b + rotl32(a + f + md5_k[i] + m[g], (int)md5_s[i]);
        a = temp;
    }
    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
}

static void md5_init(md5_ctx *ctx)
{
    ctx->state[0] = 0x67452301; ctx->state[1] = 0xefcdab89;
    ctx->state[2] = 0x98badcfe; ctx->state[3] = 0x10325476;
    ctx->total = 0;
    ctx->block_length = 0;
}

static void md5_update(md5_ctx *ctx, const unsigned char *data, unsigned length)
{
    unsigned i;
    for (i = 0; i < length; i++) {
        ctx->block[ctx->block_length++] = data[i];
        if (ctx->block_length == 64) {
            md5_compress(ctx, ctx->block);
            ctx->block_length = 0;
        }
    }
    ctx->total += length;
}

static void md5_final(md5_ctx *ctx, unsigned char out[16])
{
    unsigned long long bits = ctx->total * 8;
    unsigned char pad = 0x80, zero = 0, length_bytes[8];
    int i;
    md5_update(ctx, &pad, 1);
    while (ctx->block_length != 56) md5_update(ctx, &zero, 1);
    for (i = 0; i < 8; i++) length_bytes[i] = (unsigned char)(bits >> (i * 8));
    md5_update(ctx, length_bytes, 8);
    for (i = 0; i < 16; i++) out[i] = (unsigned char)(ctx->state[i / 4] >> ((i % 4) * 8));
}

static unsigned long long rotr64(unsigned long long x, int n) { return (x >> n) | (x << (64 - n)); }

typedef struct {
    unsigned long long state[8];
    unsigned long long total_low;
    unsigned long long total_high;
    unsigned char block[128];
    unsigned block_length;
} sha512_ctx;

static void sha512_compress(sha512_ctx *ctx, const unsigned char *block)
{
    static const unsigned long long k[80] = {
        0x428a2f98d728ae22ULL, 0x7137449123ef65cdULL, 0xb5c0fbcfec4d3b2fULL, 0xe9b5dba58189dbbcULL,
        0x3956c25bf348b538ULL, 0x59f111f1b605d019ULL, 0x923f82a4af194f9bULL, 0xab1c5ed5da6d8118ULL,
        0xd807aa98a3030242ULL, 0x12835b0145706fbeULL, 0x243185be4ee4b28cULL, 0x550c7dc3d5ffb4e2ULL,
        0x72be5d74f27b896fULL, 0x80deb1fe3b1696b1ULL, 0x9bdc06a725c71235ULL, 0xc19bf174cf692694ULL,
        0xe49b69c19ef14ad2ULL, 0xefbe4786384f25e3ULL, 0x0fc19dc68b8cd5b5ULL, 0x240ca1cc77ac9c65ULL,
        0x2de92c6f592b0275ULL, 0x4a7484aa6ea6e483ULL, 0x5cb0a9dcbd41fbd4ULL, 0x76f988da831153b5ULL,
        0x983e5152ee66dfabULL, 0xa831c66d2db43210ULL, 0xb00327c898fb213fULL, 0xbf597fc7beef0ee4ULL,
        0xc6e00bf33da88fc2ULL, 0xd5a79147930aa725ULL, 0x06ca6351e003826fULL, 0x142929670a0e6e70ULL,
        0x27b70a8546d22ffcULL, 0x2e1b21385c26c926ULL, 0x4d2c6dfc5ac42aedULL, 0x53380d139d95b3dfULL,
        0x650a73548baf63deULL, 0x766a0abb3c77b2a8ULL, 0x81c2c92e47edaee6ULL, 0x92722c851482353bULL,
        0xa2bfe8a14cf10364ULL, 0xa81a664bbc423001ULL, 0xc24b8b70d0f89791ULL, 0xc76c51a30654be30ULL,
        0xd192e819d6ef5218ULL, 0xd69906245565a910ULL, 0xf40e35855771202aULL, 0x106aa07032bbd1b8ULL,
        0x19a4c116b8d2d0c8ULL, 0x1e376c085141ab53ULL, 0x2748774cdf8eeb99ULL, 0x34b0bcb5e19b48a8ULL,
        0x391c0cb3c5c95a63ULL, 0x4ed8aa4ae3418acbULL, 0x5b9cca4f7763e373ULL, 0x682e6ff3d6b2b8a3ULL,
        0x748f82ee5defb2fcULL, 0x78a5636f43172f60ULL, 0x84c87814a1f0ab72ULL, 0x8cc702081a6439ecULL,
        0x90befffa23631e28ULL, 0xa4506cebde82bde9ULL, 0xbef9a3f7b2c67915ULL, 0xc67178f2e372532bULL,
        0xca273eceea26619cULL, 0xd186b8c721c0c207ULL, 0xeada7dd6cde0eb1eULL, 0xf57d4f7fee6ed178ULL,
        0x06f067aa72176fbaULL, 0x0a637dc5a2c898a6ULL, 0x113f9804bef90daeULL, 0x1b710b35131c471bULL,
        0x28db77f523047d84ULL, 0x32caab7b40c72493ULL, 0x3c9ebe0a15c9bebcULL, 0x431d67c49c100d4cULL,
        0x4cc5d4becb3e42b6ULL, 0x597f299cfc657e2aULL, 0x5fcb6fab3ad6faecULL, 0x6c44198c4a475817ULL,
    };
    unsigned long long w[80], a, b, c, d, e, f, g, h;
    int i;
    for (i = 0; i < 16; i++) {
        w[i] = ((unsigned long long)block[i * 8] << 56) | ((unsigned long long)block[i * 8 + 1] << 48) |
               ((unsigned long long)block[i * 8 + 2] << 40) | ((unsigned long long)block[i * 8 + 3] << 32) |
               ((unsigned long long)block[i * 8 + 4] << 24) | ((unsigned long long)block[i * 8 + 5] << 16) |
               ((unsigned long long)block[i * 8 + 6] << 8) | (unsigned long long)block[i * 8 + 7];
    }
    for (i = 16; i < 80; i++) {
        unsigned long long s0 = rotr64(w[i - 15], 1) ^ rotr64(w[i - 15], 8) ^ (w[i - 15] >> 7);
        unsigned long long s1 = rotr64(w[i - 2], 19) ^ rotr64(w[i - 2], 61) ^ (w[i - 2] >> 6);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    a = ctx->state[0]; b = ctx->state[1]; c = ctx->state[2]; d = ctx->state[3];
    e = ctx->state[4]; f = ctx->state[5]; g = ctx->state[6]; h = ctx->state[7];
    for (i = 0; i < 80; i++) {
        unsigned long long s1 = rotr64(e, 14) ^ rotr64(e, 18) ^ rotr64(e, 41);
        unsigned long long ch = (e & f) ^ ((~e) & g);
        unsigned long long t1 = h + s1 + ch + k[i] + w[i];
        unsigned long long s0 = rotr64(a, 28) ^ rotr64(a, 34) ^ rotr64(a, 39);
        unsigned long long maj = (a & b) ^ (a & c) ^ (b & c);
        unsigned long long t2 = s0 + maj;
        h = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }
    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
    ctx->state[4] += e; ctx->state[5] += f; ctx->state[6] += g; ctx->state[7] += h;
}

static void sha512_init(sha512_ctx *ctx, int is_384)
{
    if (is_384) {
        ctx->state[0] = 0xcbbb9d5dc1059ed8ULL; ctx->state[1] = 0x629a292a367cd507ULL;
        ctx->state[2] = 0x9159015a3070dd17ULL; ctx->state[3] = 0x152fecd8f70e5939ULL;
        ctx->state[4] = 0x67332667ffc00b31ULL; ctx->state[5] = 0x8eb44a8768581511ULL;
        ctx->state[6] = 0xdb0c2e0d64f98fa7ULL; ctx->state[7] = 0x47b5481dbefa4fa4ULL;
    } else {
        ctx->state[0] = 0x6a09e667f3bcc908ULL; ctx->state[1] = 0xbb67ae8584caa73bULL;
        ctx->state[2] = 0x3c6ef372fe94f82bULL; ctx->state[3] = 0xa54ff53a5f1d36f1ULL;
        ctx->state[4] = 0x510e527fade682d1ULL; ctx->state[5] = 0x9b05688c2b3e6c1fULL;
        ctx->state[6] = 0x1f83d9abfb41bd6bULL; ctx->state[7] = 0x5be0cd19137e2179ULL;
    }
    ctx->total_low = 0;
    ctx->total_high = 0;
    ctx->block_length = 0;
}

static void sha512_update(sha512_ctx *ctx, const unsigned char *data, unsigned length)
{
    unsigned i;
    for (i = 0; i < length; i++) {
        ctx->block[ctx->block_length++] = data[i];
        if (ctx->block_length == 128) {
            sha512_compress(ctx, ctx->block);
            ctx->block_length = 0;
        }
    }
    ctx->total_low += length;
    if (ctx->total_low < length) ctx->total_high++;
}

static void sha512_final(sha512_ctx *ctx, unsigned char *out, unsigned out_length)
{
    unsigned long long bits_high = (ctx->total_high << 3) | (ctx->total_low >> 61);
    unsigned long long bits_low = ctx->total_low << 3;
    unsigned char pad = 0x80, zero = 0, length_bytes[16];
    unsigned i;
    sha512_update(ctx, &pad, 1);
    while (ctx->block_length != 112) sha512_update(ctx, &zero, 1);
    for (i = 0; i < 8; i++) length_bytes[i] = (unsigned char)(bits_high >> (56 - i * 8));
    for (i = 0; i < 8; i++) length_bytes[8 + i] = (unsigned char)(bits_low >> (56 - i * 8));
    sha512_update(ctx, length_bytes, 16);
    for (i = 0; i < out_length; i++)
        out[i] = (unsigned char)(ctx->state[i / 8] >> (56 - (i % 8) * 8));
}

/* The name is what a title passes to OpenAlgorithm; digest and length follow
   from it. An unknown name reports failure rather than a hash of the wrong
   shape, which would make a signature check fail for the wrong reason. */
static int digest_of(const WCHAR *algorithm, const unsigned char *data, unsigned length,
                     unsigned char out[64], unsigned *out_length)
{
    if (!algorithm) return 0;
    if (!_wcsicmp(algorithm, L"SHA1") || !_wcsicmp(algorithm, L"SHA-1")) {
        sha1_ctx ctx;
        sha1_init(&ctx);
        sha1_update(&ctx, data, length);
        sha1_final(&ctx, out);
        *out_length = 20;
        return 1;
    }
    if (!_wcsicmp(algorithm, L"SHA256") || !_wcsicmp(algorithm, L"SHA-256")) {
        sha256_ctx ctx;
        sha256_init(&ctx);
        sha256_update(&ctx, data, length);
        sha256_final(&ctx, out);
        *out_length = 32;
        return 1;
    }
    if (!_wcsicmp(algorithm, L"SHA384") || !_wcsicmp(algorithm, L"SHA-384")) {
        sha512_ctx ctx;
        sha512_init(&ctx, 1);
        sha512_update(&ctx, data, length);
        sha512_final(&ctx, out, 48);
        *out_length = 48;
        return 1;
    }
    if (!_wcsicmp(algorithm, L"SHA512") || !_wcsicmp(algorithm, L"SHA-512")) {
        sha512_ctx ctx;
        sha512_init(&ctx, 0);
        sha512_update(&ctx, data, length);
        sha512_final(&ctx, out, 64);
        *out_length = 64;
        return 1;
    }
    if (!_wcsicmp(algorithm, L"MD5")) {
        md5_ctx ctx;
        md5_init(&ctx);
        md5_update(&ctx, data, length);
        md5_final(&ctx, out);
        *out_length = 16;
        return 1;
    }
    return 0;
}

static unsigned digest_length_of(const WCHAR *algorithm)
{
    if (!algorithm) return 0;
    if (!_wcsicmp(algorithm, L"SHA1") || !_wcsicmp(algorithm, L"SHA-1")) return 20;
    if (!_wcsicmp(algorithm, L"SHA256") || !_wcsicmp(algorithm, L"SHA-256")) return 32;
    if (!_wcsicmp(algorithm, L"SHA384") || !_wcsicmp(algorithm, L"SHA-384")) return 48;
    if (!_wcsicmp(algorithm, L"SHA512") || !_wcsicmp(algorithm, L"SHA-512")) return 64;
    if (!_wcsicmp(algorithm, L"MD5")) return 16;
    return 0;
}

/* ------------------------------------------------------ string encodings */

static const char base64_alphabet[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static unsigned base64_encode(const unsigned char *data, unsigned length, unsigned char *out)
{
    unsigned i, written = 0;
    for (i = 0; i + 2 < length; i += 3) {
        out[written++] = (unsigned char)base64_alphabet[data[i] >> 2];
        out[written++] = (unsigned char)base64_alphabet[((data[i] & 3) << 4) | (data[i + 1] >> 4)];
        out[written++] = (unsigned char)base64_alphabet[((data[i + 1] & 15) << 2) | (data[i + 2] >> 6)];
        out[written++] = (unsigned char)base64_alphabet[data[i + 2] & 63];
    }
    if (i < length) {
        out[written++] = (unsigned char)base64_alphabet[data[i] >> 2];
        if (i + 1 < length) {
            out[written++] = (unsigned char)base64_alphabet[((data[i] & 3) << 4) | (data[i + 1] >> 4)];
            out[written++] = (unsigned char)base64_alphabet[(data[i + 1] & 15) << 2];
        } else {
            out[written++] = (unsigned char)base64_alphabet[(data[i] & 3) << 4];
            out[written++] = '=';
        }
        out[written++] = '=';
    }
    out[written] = 0;
    return written;
}

static int base64_value(char c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

static unsigned base64_decode(const char *text, unsigned char *out)
{
    unsigned written = 0, bits = 0, value = 0;
    const char *at;
    for (at = text; *at; at++) {
        int digit;
        if (*at == '=') break;
        digit = base64_value(*at);
        if (digit < 0) continue;
        value = (value << 6) | (unsigned)digit;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out[written++] = (unsigned char)(value >> bits);
        }
    }
    return written;
}

static unsigned hex_encode(const unsigned char *data, unsigned length, unsigned char *out)
{
    static const char digits[] = "0123456789abcdef";
    unsigned i;
    for (i = 0; i < length; i++) {
        out[i * 2] = (unsigned char)digits[data[i] >> 4];
        out[i * 2 + 1] = (unsigned char)digits[data[i] & 15];
    }
    out[length * 2] = 0;
    return length * 2;
}

static unsigned hex_decode(const char *text, unsigned char *out)
{
    unsigned written = 0;
    int high = -1;
    const char *at;
    for (at = text; *at; at++) {
        int value;
        char c = *at;
        if (c >= '0' && c <= '9') value = c - '0';
        else if (c >= 'a' && c <= 'f') value = c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') value = c - 'A' + 10;
        else continue;
        if (high < 0) high = value;
        else {
            out[written++] = (unsigned char)((high << 4) | value);
            high = -1;
        }
    }
    return written;
}

/* ---------------------------------------------------------- hashing API */

static const GUID svc_iid_hash_provider = {0xbe9b3080, 0xb2c3, 0x422b,
                                           {0xbc, 0xe1, 0xec, 0x90, 0xef, 0xb5, 0xd7, 0xb5}};
static const GUID svc_iid_hash_statics = {0x9fac9741, 0x5cc4, 0x4336,
                                          {0xae, 0x38, 0x62, 0x12, 0xb7, 0x5a, 0x91, 0x5a}};
static const GUID svc_iid_hash_names = {0x6b5e0516, 0xde96, 0x4f0a,
                                        {0x8d, 0x57, 0xdc, 0xc9, 0xda, 0xe3, 0x6c, 0x76}};
static const GUID svc_iid_crypto_hash = {0x5904d1b6, 0xad31, 0x4603,
                                         {0xa3, 0xa4, 0xb1, 0xbd, 0xa9, 0x8e, 0x25, 0x62}};
static const GUID svc_iid_crypto_buffer = {0x320b7e22, 0x3cb0, 0x4cdf,
                                           {0x86, 0x63, 0x1d, 0x28, 0x91, 0x00, 0x65, 0xeb}};

enum hash_kind {
    HASH_NONE = 0,
    HASH_MD5,
    HASH_SHA1,
    HASH_SHA256,
    HASH_SHA384,
    HASH_SHA512,
};

static enum hash_kind hash_kind_of(const WCHAR *algorithm)
{
    if (!algorithm) return HASH_NONE;
    if (!_wcsicmp(algorithm, L"SHA1") || !_wcsicmp(algorithm, L"SHA-1")) return HASH_SHA1;
    if (!_wcsicmp(algorithm, L"SHA256") || !_wcsicmp(algorithm, L"SHA-256")) return HASH_SHA256;
    if (!_wcsicmp(algorithm, L"SHA384") || !_wcsicmp(algorithm, L"SHA-384")) return HASH_SHA384;
    if (!_wcsicmp(algorithm, L"SHA512") || !_wcsicmp(algorithm, L"SHA-512")) return HASH_SHA512;
    if (!_wcsicmp(algorithm, L"MD5")) return HASH_MD5;
    return HASH_NONE;
}

typedef struct {
    svc_object base;
    WCHAR algorithm[16];
} hash_provider;

typedef struct {
    svc_object base;
    enum hash_kind kind;
    union {
        md5_ctx md5;
        sha1_ctx sha1;
        sha256_ctx sha256;
        sha512_ctx sha512;
    } ctx;
} crypto_hash;

static svc_object hash_names_static;
static svc_object hash_provider_static;
static svc_object crypto_buffer_static;

static HRESULT simple_QI(void *self, const GUID *id, const GUID *own, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, own) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object))
        *out = self;
    else {
        *out = NULL;
        return E_NOINTERFACE;
    }
    svc_AddRef((svc_object *)self);
    return S_OK;
}

static HRESULT hp_QueryInterface(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_hash_provider, out);
}

static HRESULT hp_AlgorithmName(hash_provider *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = svc_string(self->algorithm);
    return S_OK;
}

static HRESULT hp_HashLength(hash_provider *self, unsigned *out)
{
    if (!out) return E_POINTER;
    *out = digest_length_of(self->algorithm);
    return S_OK;
}

static HRESULT hp_HashData(hash_provider *self, void *data, void **out)
{
    unsigned char digest[64];
    unsigned length = 0;
    winrt_buffer *result;
    if (!out) return E_POINTER;
    *out = NULL;
    if (!digest_of(self->algorithm, buffer_bytes(data), buffer_length(data), digest, &length))
        return HRESULT_FROM_WIN32(ERROR_NOT_SUPPORTED);
    result = buffer_from_bytes(digest, length);
    if (!result) return E_OUTOFMEMORY;
    *out = result;
    return S_OK;
}

static HRESULT ch_QueryInterface(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_crypto_hash, out);
}

static void ch_reset(crypto_hash *self)
{
    switch (self->kind) {
    case HASH_MD5: md5_init(&self->ctx.md5); break;
    case HASH_SHA1: sha1_init(&self->ctx.sha1); break;
    case HASH_SHA256: sha256_init(&self->ctx.sha256); break;
    case HASH_SHA384: sha512_init(&self->ctx.sha512, 1); break;
    case HASH_SHA512: sha512_init(&self->ctx.sha512, 0); break;
    default: break;
    }
}

static HRESULT ch_Append(crypto_hash *self, void *data)
{
    const unsigned char *bytes = buffer_bytes(data);
    unsigned length = buffer_length(data);
    switch (self->kind) {
    case HASH_MD5: md5_update(&self->ctx.md5, bytes, length); return S_OK;
    case HASH_SHA1: sha1_update(&self->ctx.sha1, bytes, length); return S_OK;
    case HASH_SHA256: sha256_update(&self->ctx.sha256, bytes, length); return S_OK;
    case HASH_SHA384:
    case HASH_SHA512: sha512_update(&self->ctx.sha512, bytes, length); return S_OK;
    default: return HRESULT_FROM_WIN32(ERROR_NOT_SUPPORTED);
    }
}

static HRESULT ch_GetValueAndReset(crypto_hash *self, void **out)
{
    unsigned char digest[64];
    unsigned length = 0;
    winrt_buffer *result;
    if (!out) return E_POINTER;
    *out = NULL;
    switch (self->kind) {
    case HASH_MD5:
        md5_final(&self->ctx.md5, digest);
        length = 16;
        break;
    case HASH_SHA1:
        sha1_final(&self->ctx.sha1, digest);
        length = 20;
        break;
    case HASH_SHA256:
        sha256_final(&self->ctx.sha256, digest);
        length = 32;
        break;
    case HASH_SHA384:
        sha512_final(&self->ctx.sha512, digest, 48);
        length = 48;
        break;
    case HASH_SHA512:
        sha512_final(&self->ctx.sha512, digest, 64);
        length = 64;
        break;
    default:
        return HRESULT_FROM_WIN32(ERROR_NOT_SUPPORTED);
    }
    ch_reset(self);
    result = buffer_from_bytes(digest, length);
    if (!result) return E_OUTOFMEMORY;
    *out = result;
    return S_OK;
}

static const void *crypto_hash_vtable[] = {
    (const void *)ch_QueryInterface, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)ch_Append, (const void *)ch_GetValueAndReset,
};

/* A CryptographicHash carries its own streaming state: Append feeds the
   digest state directly and GetValueAndReset finalizes and starts over. */
static HRESULT hp_CreateHash(hash_provider *self, void **out)
{
    crypto_hash *hash = (crypto_hash *)calloc(1, sizeof(crypto_hash));
    if (!out) return E_POINTER;
    if (!hash) return E_OUTOFMEMORY;
    hash->base.vtbl = crypto_hash_vtable;
    hash->base.refs = 1;
    hash->base.name = "Windows.Security.Cryptography.Core.CryptographicHash";
    hash->kind = hash_kind_of(self->algorithm);
    ch_reset(hash);
    *out = hash;
    return S_OK;
}

static const void *hash_provider_vtable[] = {
    (const void *)hp_QueryInterface, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)hp_AlgorithmName, (const void *)hp_HashLength, (const void *)hp_HashData,
    (const void *)hp_CreateHash,
};

/* A provider is opened by algorithm name, never constructed directly: the
   title queries the factory for IHashAlgorithmProviderStatics and calls
   OpenAlgorithm.  That is a different IID from the provider itself. */
static HRESULT hps_QueryInterface(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_hash_statics, out);
}

static HRESULT hps_OpenAlgorithm(svc_object *self, hstring_t algorithm, void **out)
{
    const WCHAR *name;
    hash_provider *provider;
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    name = svc_string_text(algorithm);
    if (!name) return E_INVALIDARG;
    provider = (hash_provider *)calloc(1, sizeof(hash_provider));
    if (!provider) return E_OUTOFMEMORY;
    provider->base.vtbl = hash_provider_vtable;
    provider->base.refs = 1;
    provider->base.name = "Windows.Security.Cryptography.Core.HashAlgorithmProvider";
    wcsncpy(provider->algorithm, name, 15);
    *out = provider;
    return S_OK;
}

static const void *hash_provider_statics_vtable[] = {
    (const void *)hps_QueryInterface, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)hps_OpenAlgorithm,
};

static HRESULT hn_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_hash_names, out);
}

static HRESULT hn_prop(const WCHAR *value, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = svc_string(value);
    return S_OK;
}

#define HN(name, value)                                                       \
    static HRESULT hn_##name(svc_object *self, hstring_t *out)                \
    {                                                                         \
        (void)self;                                                           \
        return hn_prop(value, out);                                           \
    }

HN(Md5, L"MD5")
HN(Sha1, L"SHA1")
HN(Sha256, L"SHA256")
HN(Sha384, L"SHA384")
HN(Sha512, L"SHA512")

static const void *hash_names_vtable[] = {
    (const void *)hn_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)hn_Md5, (const void *)hn_Sha1, (const void *)hn_Sha256,
    (const void *)hn_Sha384, (const void *)hn_Sha512,
};

/* CryptographicBuffer: byte arrays to buffers, buffers to text, and random. */
static HRESULT cb_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_crypto_buffer, out);
}

static HRESULT cb_Compare(svc_object *self, void *a, void *b, unsigned char *out)
{
    unsigned length;
    (void)self;
    if (!out) return E_POINTER;
    length = buffer_length(a);
    *out = (unsigned char)(length == buffer_length(b) &&
                           (length == 0 || memcmp(buffer_bytes(a), buffer_bytes(b), length) == 0));
    return S_OK;
}

static HRESULT cb_GenerateRandom(svc_object *self, unsigned length, void **out)
{
    winrt_buffer *result;
    (void)self;
    if (!out) return E_POINTER;
    result = buffer_new(length);
    if (!result) return E_OUTOFMEMORY;
    /* The platform CSPRNG, not a seeded generator: these bytes go into keys and
       tokens, and a guessable sequence would be worse than an error. */
    if (!SystemFunction036(result->data, length)) {
        DWORD err = GetLastError();
        buffer_Release(result);
        *out = NULL;
        return err ? HRESULT_FROM_WIN32(err) : E_FAIL;
    }
    result->length = length;
    *out = result;
    return S_OK;
}

static HRESULT cb_GenerateRandomNumber(svc_object *self, unsigned *out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (!SystemFunction036(out, sizeof(*out))) {
        DWORD err = GetLastError();
        return err ? HRESULT_FROM_WIN32(err) : E_FAIL;
    }
    return S_OK;
}

/* The metadata declares the array's size before the array, exactly as the
   header does: CreateFromByteArray(UINT32 value_size, BYTE *value, IBuffer **). */
static HRESULT cb_CreateFromByteArray(svc_object *self, unsigned length, const unsigned char *data,
                                      void **out)
{
    winrt_buffer *result;
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    if (length && !data) return E_POINTER;
    result = buffer_from_bytes(data, length);
    if (!result) return E_OUTOFMEMORY;
    *out = result;
    return S_OK;
}

/* CopyToByteArray(IBuffer *buffer, UINT32 *value_size, BYTE **value): the size
   comes back before the bytes. */
static HRESULT cb_CopyToByteArray(svc_object *self, void *buffer, unsigned *length,
                                  unsigned char **data)
{
    unsigned count;
    (void)self;
    if (!length || !data) return E_POINTER;
    count = buffer_length(buffer);
    *length = count;
    *data = (unsigned char *)CoTaskMemAlloc(count ? count : 1);
    if (!*data) return E_OUTOFMEMORY;
    if (count) memcpy(*data, buffer_bytes(buffer), count);
    return S_OK;
}

static HRESULT hstring_to_utf8(hstring_t text, char **out, unsigned *out_length)
{
    UINT32 wlen = 0;
    const WCHAR *wide = svc_string_raw(text, &wlen);
    int needed;
    char *narrow;
    if (!wide) return E_INVALIDARG;
    if (wlen == 0) {
        narrow = (char *)calloc(1, 1);
        if (!narrow) return E_OUTOFMEMORY;
        *out = narrow;
        *out_length = 0;
        return S_OK;
    }
    needed = WideCharToMultiByte(CP_UTF8, 0, wide, (int)wlen, NULL, 0, NULL, NULL);
    if (needed <= 0) return E_INVALIDARG;
    narrow = (char *)malloc((size_t)needed + 1);
    if (!narrow) return E_OUTOFMEMORY;
    if (WideCharToMultiByte(CP_UTF8, 0, wide, (int)wlen, narrow, needed, NULL, NULL) != needed) {
        free(narrow);
        return E_INVALIDARG;
    }
    narrow[needed] = 0;
    *out = narrow;
    *out_length = (unsigned)needed;
    return S_OK;
}

static HRESULT ascii_to_hstring(const unsigned char *text, unsigned length, hstring_t *out)
{
    WCHAR *wide;
    unsigned i;
    if (length == 0) {
        *out = NULL;
        return S_OK;
    }
    wide = (WCHAR *)malloc((size_t)length * sizeof(WCHAR));
    if (!wide) return E_OUTOFMEMORY;
    for (i = 0; i < length; i++) wide[i] = (WCHAR)text[i];
    *out = svc_string_len(wide, length);
    free(wide);
    return S_OK;
}

static HRESULT cb_DecodeFromHexString(svc_object *self, hstring_t text, void **out)
{
    char *narrow = NULL;
    unsigned narrow_len = 0;
    unsigned char *bytes;
    unsigned length;
    winrt_buffer *result;
    HRESULT hr;
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    hr = hstring_to_utf8(text, &narrow, &narrow_len);
    if (FAILED(hr)) return hr;
    bytes = (unsigned char *)malloc((size_t)(narrow_len / 2) + 1);
    if (!bytes) {
        free(narrow);
        return E_OUTOFMEMORY;
    }
    length = hex_decode(narrow, bytes);
    free(narrow);
    result = buffer_from_bytes(bytes, length);
    free(bytes);
    if (!result) return E_OUTOFMEMORY;
    *out = result;
    return S_OK;
}

static HRESULT cb_EncodeToHexString(svc_object *self, void *buffer, hstring_t *out)
{
    unsigned count = buffer_length(buffer);
    unsigned char *text;
    unsigned encoded;
    HRESULT hr;
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    text = (unsigned char *)malloc((size_t)count * 2 + 1);
    if (!text) return E_OUTOFMEMORY;
    encoded = hex_encode(buffer_bytes(buffer), count, text);
    hr = ascii_to_hstring(text, encoded, out);
    free(text);
    return hr;
}

static HRESULT cb_DecodeFromBase64String(svc_object *self, hstring_t text, void **out)
{
    char *narrow = NULL;
    unsigned narrow_len = 0;
    unsigned char *bytes;
    unsigned length;
    winrt_buffer *result;
    HRESULT hr;
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    hr = hstring_to_utf8(text, &narrow, &narrow_len);
    if (FAILED(hr)) return hr;
    bytes = (unsigned char *)malloc((size_t)((narrow_len / 4 + 1) * 3) + 1);
    if (!bytes) {
        free(narrow);
        return E_OUTOFMEMORY;
    }
    length = base64_decode(narrow, bytes);
    free(narrow);
    result = buffer_from_bytes(bytes, length);
    free(bytes);
    if (!result) return E_OUTOFMEMORY;
    *out = result;
    return S_OK;
}

static HRESULT cb_EncodeToBase64String(svc_object *self, void *buffer, hstring_t *out)
{
    unsigned count = buffer_length(buffer);
    unsigned char *text;
    unsigned encoded;
    HRESULT hr;
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    if (count > 0x3fffffffu) return E_OUTOFMEMORY;
    text = (unsigned char *)malloc(((((size_t)count + 2) / 3) * 4) + 1);
    if (!text) return E_OUTOFMEMORY;
    encoded = base64_encode(buffer_bytes(buffer), count, text);
    hr = ascii_to_hstring(text, encoded, out);
    free(text);
    return hr;
}

static HRESULT cb_ConvertStringToBinary(svc_object *self, hstring_t text, int encoding, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    if (encoding == 0) { /* BinaryStringEncoding::Utf8 */
        char *narrow = NULL;
        unsigned length = 0;
        winrt_buffer *result;
        HRESULT hr = hstring_to_utf8(text, &narrow, &length);
        if (FAILED(hr)) return hr;
        result = buffer_from_bytes((const unsigned char *)narrow, length);
        free(narrow);
        if (!result) return E_OUTOFMEMORY;
        *out = result;
        return S_OK;
    }
    if (encoding == 1 || encoding == 2) { /* Utf16LE / Utf16BE */
        UINT32 wlen = 0;
        const WCHAR *wide = svc_string_raw(text, &wlen);
        winrt_buffer *result;
        if (!wide) return E_INVALIDARG;
        if (encoding == 1) {
            result = buffer_from_bytes((const unsigned char *)wide, wlen * (unsigned)sizeof(WCHAR));
            if (!result) return E_OUTOFMEMORY;
        } else {
            UINT32 i;
            result = buffer_new(wlen ? wlen * 2 : 1);
            if (!result) return E_OUTOFMEMORY;
            for (i = 0; i < wlen; i++) {
                result->data[i * 2] = (unsigned char)(wide[i] >> 8);
                result->data[i * 2 + 1] = (unsigned char)(wide[i] & 0xff);
            }
            result->length = wlen * 2;
        }
        *out = result;
        return S_OK;
    }
    return E_INVALIDARG;
}

static HRESULT cb_ConvertBinaryToString(svc_object *self, int encoding, void *buffer, hstring_t *out)
{
    const unsigned char *bytes;
    unsigned count;
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    bytes = buffer_bytes(buffer);
    count = buffer_length(buffer);
    if (encoding == 0) { /* Utf8 */
        int needed;
        WCHAR *wide;
        if (count == 0) return S_OK;
        needed = MultiByteToWideChar(CP_UTF8, 0, (const char *)bytes, (int)count, NULL, 0);
        if (needed <= 0) return E_INVALIDARG;
        wide = (WCHAR *)malloc((size_t)needed * sizeof(WCHAR));
        if (!wide) return E_OUTOFMEMORY;
        if (MultiByteToWideChar(CP_UTF8, 0, (const char *)bytes, (int)count, wide, needed) !=
            needed) {
            free(wide);
            return E_INVALIDARG;
        }
        *out = svc_string_len(wide, (UINT32)needed);
        free(wide);
        return S_OK;
    }
    if (encoding == 1) { /* Utf16LE */
        unsigned wlen = count / (unsigned)sizeof(WCHAR);
        if (wlen) *out = svc_string_len((const WCHAR *)bytes, wlen);
        return S_OK;
    }
    if (encoding == 2) { /* Utf16BE */
        unsigned wlen = count / 2;
        unsigned i;
        WCHAR *wide;
        if (wlen == 0) return S_OK;
        wide = (WCHAR *)malloc((size_t)wlen * sizeof(WCHAR));
        if (!wide) return E_OUTOFMEMORY;
        for (i = 0; i < wlen; i++)
            wide[i] = (WCHAR)(((unsigned)bytes[i * 2] << 8) | bytes[i * 2 + 1]);
        *out = svc_string_len(wide, wlen);
        free(wide);
        return S_OK;
    }
    return E_INVALIDARG;
}

static const void *crypto_buffer_vtable[] = {
    (const void *)cb_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)cb_Compare, (const void *)cb_GenerateRandom, (const void *)cb_GenerateRandomNumber,
    (const void *)cb_CreateFromByteArray, (const void *)cb_CopyToByteArray,
    (const void *)cb_DecodeFromHexString, (const void *)cb_EncodeToHexString,
    (const void *)cb_DecodeFromBase64String, (const void *)cb_EncodeToBase64String,
    (const void *)cb_ConvertStringToBinary, (const void *)cb_ConvertBinaryToString,
};

/* --------------------------------------------------------------- storage */

static const GUID svc_iid_appdata = {0xc3da6fb7, 0xb744, 0x4b45,
                                     {0xb0, 0xb8, 0x22, 0x3a, 0x09, 0x38, 0xd0, 0xdc}};
static const GUID svc_iid_appdata_statics = {0x5612147b, 0xe843, 0x45e3,
                                             {0x94, 0xd8, 0x06, 0x16, 0x9e, 0x3c, 0x8e, 0x17}};
static const GUID svc_iid_storage_item = {0x4207a996, 0xca2f, 0x42f7,
                                          {0xbd, 0xe8, 0x8b, 0x10, 0x45, 0x7a, 0x7f, 0x30}};
static const GUID svc_iid_storage_folder = {0x72d1cb78, 0xb3ef, 0x4f75,
                                            {0xa8, 0x0b, 0x6f, 0xd9, 0xda, 0xe2, 0x94, 0x4b}};
static const GUID svc_iid_storage_folder2 = {0xe827e8b9, 0x08d9, 0x4a8e,
                                             {0xa0, 0xac, 0xfe, 0x5e, 0xd3, 0xcb, 0xbb, 0xd3}};
static const GUID svc_iid_storage_file = {0xfa3f6186, 0x4214, 0x428c,
                                          {0xa6, 0x4c, 0x14, 0xc9, 0xac, 0x73, 0x15, 0xea}};

/* WinRT interfaces do not inherit one another: IStorageItem, IStorageFolder,
   IStorageFolder2 and IStorageFile are four separate vtables, so an object that
   implements several of them hands out a different pointer for each. A title
   that receives a StorageFolder from ApplicationData calls slot 6 as
   IStorageFolder::CreateFileAsync, not slot 6 of IStorageItem. */
typedef struct {
    svc_object base;     /* IStorageItem */
    svc_object folder;   /* IStorageFolder */
    svc_object folder2;  /* IStorageFolder2 */
    svc_object file;     /* IStorageFile */
    int is_folder;
    WCHAR path[MAX_PATH];
} storage_item;

static const void *item_vtable[];
static const void *folder_vtable[];
static const void *file_vtable[];
static const void *folder2_vtable[];
static void *item_file_face(storage_item *item);
static void *item_folder_face(storage_item *item);

static storage_item *item_from(void *self)
{
    svc_object *header = (svc_object *)self;
    if (!header) return NULL;
    if (header->vtbl == folder2_vtable)
        return (storage_item *)((char *)self - offsetof(storage_item, folder2));
    if (header->vtbl == folder_vtable)
        return (storage_item *)((char *)self - offsetof(storage_item, folder));
    if (header->vtbl == file_vtable)
        return (storage_item *)((char *)self - offsetof(storage_item, file));
    return (storage_item *)self;
}

static ULONG item_AddRef(void *self) { return svc_AddRef(&item_from(self)->base); }
static ULONG item_Release(void *self)
{
    storage_item *item = item_from(self);
    LONG left = InterlockedDecrement(&item->base.refs);
    if (left <= 0) free(item);
    return (ULONG)left;
}

static HRESULT item_QueryInterface(void *self, const GUID *id, void **out)
{
    storage_item *item = item_from(self);
    if (!out) return E_POINTER;
    svc_trace_iid("storage QI", id);
    *out = NULL;
    if (IsEqualIID(id, &svc_iid_storage_item) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object))
        *out = &item->base;
    else if (IsEqualIID(id, &svc_iid_storage_folder2) && item->is_folder)
        *out = &item->folder2;
    else if (IsEqualIID(id, &svc_iid_storage_folder) && item->is_folder)
        *out = &item->folder;
    else if (IsEqualIID(id, &svc_iid_storage_file) && !item->is_folder)
        *out = &item->file;
    else
        return E_NOINTERFACE;
    svc_AddRef(&item->base);
    return S_OK;
}

static const WCHAR *path_name(const WCHAR *path, WCHAR *out, int capacity)
{
    const WCHAR *slash = wcsrchr(path, L'\\');
    slash = slash ? slash + 1 : path;
    wcsncpy(out, slash, capacity - 1);
    out[capacity - 1] = 0;
    return out;
}

static HRESULT item_Name(storage_item *self, hstring_t *out)
{
    WCHAR name[MAX_PATH];
    svc_trace_w("item name", self->path);
    if (!out) return E_POINTER;
    *out = svc_string(path_name(self->path, name, MAX_PATH));
    return S_OK;
}

static HRESULT item_Path(storage_item *self, hstring_t *out)
{
    svc_trace_w("item path", self->path);
    if (!out) return E_POINTER;
    *out = svc_string(self->path);
    return S_OK;
}

static HRESULT item_Attributes(storage_item *self, int *out)
{
    DWORD attributes = GetFileAttributesW(self->path);
    svc_trace_w("item attributes", self->path);
    if (!out) return E_POINTER;
    *out = attributes == INVALID_FILE_ATTRIBUTES ? 0 : (int)attributes;
    return S_OK;
}

static HRESULT item_DateCreated(storage_item *self, long long *out)
{
    WIN32_FILE_ATTRIBUTE_DATA data;
    if (!out) return E_POINTER;
    *out = 0;
    if (!GetFileAttributesExW(self->path, GetFileExInfoStandard, &data)) return S_OK;
    *out = ((long long)data.ftCreationTime.dwHighDateTime << 32) | data.ftCreationTime.dwLowDateTime;
    return S_OK;
}

static HRESULT item_IsOfType(storage_item *self, int type, unsigned char *out)
{
    if (!out) return E_POINTER;
    /* StorageItemTypes flags: None = 0, File = 1, Folder = 2. */
    *out = (unsigned char)((self->is_folder && (type & 2)) || (!self->is_folder && (type & 1)));
    return S_OK;
}

static HRESULT item_rename_impl(storage_item *self, hstring_t name, DWORD flags, void **out)
{
    const WCHAR *wide = svc_string_text(name);
    WCHAR target[MAX_PATH];
    WCHAR *folder;
    size_t prefix_len;
    size_t name_len;
    async_object *op;
    if (!out) return E_POINTER;
    *out = NULL;
    if (!wide || !wide[0]) return E_INVALIDARG;
    wcsncpy(target, self->path, MAX_PATH - 1);
    target[MAX_PATH - 1] = 0;
    folder = wcsrchr(target, L'\\');
    if (!folder) return E_INVALIDARG;
    prefix_len = (size_t)(folder - target) + 1;
    name_len = wcslen(wide);
    if (prefix_len + name_len >= MAX_PATH)
        return async_action(HRESULT_FROM_WIN32(ERROR_FILENAME_EXCED_RANGE), out);
    memcpy(folder + 1, wide, (name_len + 1) * sizeof(WCHAR));
    op = async_new_iid(0, &svc_iid_async_action, S_OK, NULL);
    if (!op) return E_OUTOFMEMORY;
    if (!MoveFileExW(self->path, target, flags)) {
        op->error = HRESULT_FROM_WIN32(GetLastError());
        op->status = ASYNC_ERROR;
        *out = op;
        return S_OK;
    }
    memcpy(self->path, target, (prefix_len + name_len + 1) * sizeof(WCHAR));
    *out = op;
    return S_OK;
}

static HRESULT item_Rename(storage_item *self, hstring_t name, void **out)
{
    svc_trace_w("item rename", self->path);
    return item_rename_impl(self, name, 0, out);
}

static HRESULT item_Rename2(storage_item *self, hstring_t name, int option, void **out)
{
    svc_trace_w("item rename2", self->path);
    return item_rename_impl(self, name, option == 1 ? MOVEFILE_REPLACE_EXISTING : 0, out);
}

static HRESULT item_Delete(storage_item *self, void **out)
{
    BOOL ok;
    const unsigned char *caller = (const unsigned char *)__builtin_return_address(0);
    svc_trace_w("delete", self->path);
    svc_trace_ptr("delete out", (const void *)out);
    svc_trace_ptr("delete caller", caller);
    if (svc_readable(caller - 16, 16)) svc_trace_bytes("delete code", caller - 16, 16);
    if (!out) return E_POINTER;
    if (!svc_writable(out, sizeof(void *))) {
        svc_trace("delete out is not writable");
        return E_POINTER;
    }
    ok = self->is_folder ? RemoveDirectoryW(self->path) : DeleteFileW(self->path);
    return async_action(ok ? S_OK : HRESULT_FROM_WIN32(GetLastError()), out);
}

static HRESULT item_Delete2(storage_item *self, int option, void **out)
{
    svc_trace_w("item delete2", self->path);
    svc_trace("delete2 slot");
    (void)option;
    return item_Delete(self, out);
}

static storage_item *item_new(const WCHAR *path, int folder)
{
    storage_item *item = (storage_item *)calloc(1, sizeof(storage_item));
    if (!item) return NULL;
    item->base.vtbl = item_vtable;
    item->base.refs = 1;
    item->base.name = folder ? "Windows.Storage.StorageFolder" : "Windows.Storage.StorageFile";
    item->folder.vtbl = folder_vtable;
    item->folder.refs = 1;
    item->folder.permanent = 1;
    item->folder.name = item->base.name;
    item->folder2.vtbl = folder2_vtable;
    item->folder2.refs = 1;
    item->folder2.permanent = 1;
    item->folder2.name = item->base.name;
    item->file.vtbl = file_vtable;
    item->file.refs = 1;
    item->file.permanent = 1;
    item->file.name = item->base.name;
    item->is_folder = folder;
    wcsncpy(item->path, path, MAX_PATH - 1);
    svc_trace_w(folder ? "folder new" : "file new", path);
    return item;
}

static int item_join(const WCHAR *folder, const WCHAR *name, WCHAR *out)
{
    int n;
    if (!folder || !name || !out) return 0;
    n = _snwprintf(out, MAX_PATH, L"%s\\%s", folder, name);
    if (n < 0 || n >= MAX_PATH) {
        out[0] = 0;
        return 0;
    }
    return 1;
}

/* CreationCollisionOption: 0 GenerateUniqueName, 1 ReplaceExisting,
   2 FailIfExists, 3 OpenIfExists. */
static int creation_name(const WCHAR *folder, const WCHAR *name, int option, WCHAR *out)
{
    WCHAR candidate[MAX_PATH];
    if (option != 0) return item_join(folder, name, out);
    if (!item_join(folder, name, candidate)) return 0;
    if (GetFileAttributesW(candidate) == INVALID_FILE_ATTRIBUTES) {
        wcscpy(out, candidate);
        return 1;
    }
    {
        const WCHAR *dot = wcsrchr(name, L'.');
        WCHAR stem[MAX_PATH];
        int attempt;
        if (dot) {
            size_t stem_len = (size_t)(dot - name);
            if (stem_len >= MAX_PATH) return 0;
            memcpy(stem, name, stem_len * sizeof(WCHAR));
            stem[stem_len] = 0;
        } else {
            size_t name_len = wcslen(name);
            if (name_len >= MAX_PATH) return 0;
            memcpy(stem, name, (name_len + 1) * sizeof(WCHAR));
        }
        for (attempt = 1; attempt < 1000; attempt++) {
            int n = dot ? _snwprintf(candidate, MAX_PATH, L"%s\\%s (%d)%s", folder, stem, attempt,
                                     dot)
                        : _snwprintf(candidate, MAX_PATH, L"%s\\%s (%d)", folder, stem, attempt);
            if (n < 0 || n >= MAX_PATH) return 0;
            if (GetFileAttributesW(candidate) == INVALID_FILE_ATTRIBUTES) {
                wcscpy(out, candidate);
                return 1;
            }
        }
        return 0;
    }
}

static HRESULT create_file_at(const WCHAR *folder, const WCHAR *name, int option, void **out);
static HRESULT create_folder_at(const WCHAR *folder, const WCHAR *name, int option, void **out);

static HRESULT folder_CreateFile1(storage_item *self, hstring_t name, void **out)
{
    self = item_from(self);
    svc_trace_w("folder createfile", self->path);
    const WCHAR *wide = svc_string_text(name);
    if (!out) return E_POINTER;
    *out = NULL;
    if (!wide) return E_INVALIDARG;
    /* The overload without an option is the FailIfExists one. */
    return create_file_at(self->path, wide, 2, out);
}

/* Each create overload settles on an item, and CreationCollisionOption says
   what to do when that name is already taken: OpenIfExists hands the existing
   item back, FailIfExists reports the collision, ReplaceExisting starts over,
   and GenerateUniqueName has already picked a name that is free. A title that
   keeps its scratch directory between launches asks for OpenIfExists, so
   treating the collision as a failure would break every run after the
   first. */
static HRESULT create_file_at(const WCHAR *folder, const WCHAR *name, int option, void **out)
{
    WCHAR path[MAX_PATH];
    HANDLE file;
    storage_item *item;
    async_object *op;
    if (!creation_name(folder, name, option, path))
        return async_operation_iid(&svc_iid_async_storage_file,
                                   HRESULT_FROM_WIN32(ERROR_FILENAME_EXCED_RANGE), NULL, out);
    item = item_new(path, 0);
    if (!item) return E_OUTOFMEMORY;
    op = async_new_iid(1, &svc_iid_async_storage_file, S_OK, NULL);
    if (!op) {
        item_Release(item);
        return E_OUTOFMEMORY;
    }
    file = CreateFileW(path, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                       option == 1 ? CREATE_ALWAYS
                       : option == 2 ? CREATE_NEW
                       : option == 3 ? OPEN_ALWAYS
                                     : CREATE_NEW,
                       FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) {
        op->error = HRESULT_FROM_WIN32(GetLastError());
        op->status = ASYNC_ERROR;
        item_Release(item);
        *out = op;
        return S_OK;
    }
    CloseHandle(file);
    op->result = item_file_face(item);
    *out = op;
    return S_OK;
}

static HRESULT create_folder_at(const WCHAR *folder, const WCHAR *name, int option, void **out)
{
    WCHAR path[MAX_PATH];
    DWORD attributes;
    storage_item *item;
    async_object *op;
    if (!creation_name(folder, name, option, path))
        return async_operation_iid(&svc_iid_async_storage_folder,
                                   HRESULT_FROM_WIN32(ERROR_FILENAME_EXCED_RANGE), NULL, out);
    item = item_new(path, 1);
    if (!item) return E_OUTOFMEMORY;
    op = async_new_iid(1, &svc_iid_async_storage_folder, S_OK, NULL);
    if (!op) {
        item_Release(item);
        return E_OUTOFMEMORY;
    }
    attributes = GetFileAttributesW(path);
    if (attributes != INVALID_FILE_ATTRIBUTES) {
        if (!(attributes & FILE_ATTRIBUTE_DIRECTORY) || option == 2) {
            op->error = HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS);
            op->status = ASYNC_ERROR;
            item_Release(item);
            *out = op;
            return S_OK;
        }
        if (option != 1) {
            op->result = item_folder_face(item);
            *out = op;
            return S_OK;
        }
        if (!RemoveDirectoryW(path)) {
            op->error = HRESULT_FROM_WIN32(GetLastError());
            op->status = ASYNC_ERROR;
            item_Release(item);
            *out = op;
            return S_OK;
        }
    }
    if (!CreateDirectoryW(path, NULL)) {
        DWORD err = GetLastError();
        if (err == ERROR_ALREADY_EXISTS && option == 3) {
            DWORD post_attr = GetFileAttributesW(path);
            if (post_attr != INVALID_FILE_ATTRIBUTES && (post_attr & FILE_ATTRIBUTE_DIRECTORY)) {
                op->result = item_folder_face(item);
                *out = op;
                return S_OK;
            }
        }
        op->error = HRESULT_FROM_WIN32(err);
        op->status = ASYNC_ERROR;
        item_Release(item);
        *out = op;
        return S_OK;
    }
    op->result = item_folder_face(item);
    *out = op;
    return S_OK;
}

static HRESULT folder_CreateFile2(storage_item *self, hstring_t name, int option, void **out)
{
    self = item_from(self);
    svc_trace_w("folder createfile2", self->path);
    const WCHAR *wide = svc_string_text(name);
    if (!out) return E_POINTER;
    *out = NULL;
    if (!wide) return E_INVALIDARG;
    return create_file_at(self->path, wide, option, out);
}

static HRESULT folder_CreateFolder1(storage_item *self, hstring_t name, void **out)
{
    self = item_from(self);
    svc_trace_w("folder createfolder", self->path);
    const WCHAR *wide = svc_string_text(name);
    if (!out) return E_POINTER;
    *out = NULL;
    if (!wide) return E_INVALIDARG;
    /* The overload without an option is the FailIfExists one. */
    return create_folder_at(self->path, wide, 2, out);
}

static HRESULT folder_CreateFolder2(storage_item *self, hstring_t name, int option, void **out)
{
    self = item_from(self);
    svc_trace_w("folder createfolder2", self->path);
    const WCHAR *wide = svc_string_text(name);
    if (!out) return E_POINTER;
    *out = NULL;
    if (!wide) return E_INVALIDARG;
    return create_folder_at(self->path, wide, option, out);
}

static HRESULT folder_GetFile1(storage_item *self, hstring_t name, void **out)
{
    self = item_from(self);
    svc_trace_w("folder getfile", self->path);
    const WCHAR *wide = svc_string_text(name);
    WCHAR path[MAX_PATH];
    DWORD attributes;
    storage_item *item;
    if (!out) return E_POINTER;
    *out = NULL;
    if (!wide) return E_INVALIDARG;
    if (!item_join(self->path, wide, path))
        return async_operation_iid(&svc_iid_async_storage_file,
                                   HRESULT_FROM_WIN32(ERROR_FILENAME_EXCED_RANGE), NULL, out);
    attributes = GetFileAttributesW(path);
    if (attributes == INVALID_FILE_ATTRIBUTES || (attributes & FILE_ATTRIBUTE_DIRECTORY))
        return async_operation_iid(&svc_iid_async_storage_file,
                                   HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND), NULL, out);
    item = item_new(path, 0);
    if (!item) return E_OUTOFMEMORY;
    return async_operation_iid(&svc_iid_async_storage_file, S_OK, item_file_face(item), out);
}

static HRESULT folder_GetFolder1(storage_item *self, hstring_t name, void **out)
{
    self = item_from(self);
    svc_trace_w("folder getfolder", self->path);
    const WCHAR *wide = svc_string_text(name);
    WCHAR path[MAX_PATH];
    DWORD attributes;
    storage_item *item;
    if (!out) return E_POINTER;
    *out = NULL;
    if (!wide) return E_INVALIDARG;
    if (!item_join(self->path, wide, path))
        return async_operation_iid(&svc_iid_async_storage_folder,
                                   HRESULT_FROM_WIN32(ERROR_FILENAME_EXCED_RANGE), NULL, out);
    attributes = GetFileAttributesW(path);
    if (attributes == INVALID_FILE_ATTRIBUTES || !(attributes & FILE_ATTRIBUTE_DIRECTORY))
        return async_operation_iid(&svc_iid_async_storage_folder,
                                   HRESULT_FROM_WIN32(ERROR_PATH_NOT_FOUND), NULL, out);
    item = item_new(path, 1);
    if (!item) return E_OUTOFMEMORY;
    return async_operation_iid(&svc_iid_async_storage_folder, S_OK, item_folder_face(item), out);
}

static HRESULT folder_GetItem1(storage_item *self, hstring_t name, void **out)
{
    self = item_from(self);
    svc_trace_w("folder getitem", self->path);
    const WCHAR *wide = svc_string_text(name);
    WCHAR path[MAX_PATH];
    DWORD attributes;
    storage_item *item;
    if (!out) return E_POINTER;
    *out = NULL;
    if (!wide) return E_INVALIDARG;
    if (!item_join(self->path, wide, path))
        return async_operation_iid(&svc_iid_async_storage_item,
                                   HRESULT_FROM_WIN32(ERROR_FILENAME_EXCED_RANGE), NULL, out);
    attributes = GetFileAttributesW(path);
    if (attributes == INVALID_FILE_ATTRIBUTES)
        return async_operation_iid(&svc_iid_async_storage_item,
                                   HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND), NULL, out);
    item = item_new(path, (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0);
    if (!item) return E_OUTOFMEMORY;
    return async_operation_iid(&svc_iid_async_storage_item, S_OK, &item->base, out);
}

/* IStorageFolder2::TryGetItemAsync answers with a null result when the item is
   absent, so a caller can test for a file without catching. */
static HRESULT folder_TryGetItem(storage_item *self, hstring_t name, void **out)
{
    self = item_from(self);
    svc_trace_w("folder trygetitem", self->path);
    const WCHAR *wide = svc_string_text(name);
    WCHAR path[MAX_PATH];
    DWORD attributes;
    storage_item *item;
    if (!out) return E_POINTER;
    *out = NULL;
    if (!wide) return E_INVALIDARG;
    if (!item_join(self->path, wide, path))
        return async_operation_iid(&svc_iid_async_storage_item,
                                   HRESULT_FROM_WIN32(ERROR_FILENAME_EXCED_RANGE), NULL, out);
    attributes = GetFileAttributesW(path);
    if (attributes == INVALID_FILE_ATTRIBUTES)
        return async_operation_iid(&svc_iid_async_storage_item, S_OK, NULL, out);
    item = item_new(path, (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0);
    if (!item) return E_OUTOFMEMORY;
    return async_operation_iid(&svc_iid_async_storage_item, S_OK, &item->base, out);
}

/* The ABI keeps every storage interface separate: after the six IInspectable
   slots, IStorageItem has its ten methods, IStorageFolder its ten, and
   IStorageFile its twelve. A title calling by index therefore reaches the
   method its metadata named, which is why the folder pointer it receives from
   ApplicationData must not carry the IStorageItem layout. */
static const void *item_vtable[] = {
    (const void *)item_QueryInterface, (const void *)item_AddRef, (const void *)item_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)item_Rename,          /* RenameAsync(desiredName) */
    (const void *)item_Rename2,         /* RenameAsync(desiredName, option) */
    (const void *)item_Delete,          /* DeleteAsync() */
    (const void *)item_Delete2,         /* DeleteAsync(option) */
    (const void *)svc_unimplemented,    /* BasicProperties */
    (const void *)item_Name,
    (const void *)item_Path,
    (const void *)item_Attributes,
    (const void *)item_DateCreated,
    (const void *)item_IsOfType,
};

static const void *folder_vtable[] = {
    (const void *)item_QueryInterface, (const void *)item_AddRef, (const void *)item_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)folder_CreateFile1,   /* CreateFileAsync(name) */
    (const void *)folder_CreateFile2,   /* CreateFileAsync(name, option) */
    (const void *)folder_CreateFolder1, /* CreateFolderAsync(name) */
    (const void *)folder_CreateFolder2, /* CreateFolderAsync(name, option) */
    (const void *)folder_GetFile1,      /* GetFileAsync */
    (const void *)folder_GetFolder1,    /* GetFolderAsync */
    (const void *)folder_GetItem1,      /* GetItemAsync */
    (const void *)svc_unimplemented,    /* GetFilesAsync() */
    (const void *)svc_unimplemented,    /* GetFoldersAsync() */
    (const void *)svc_unimplemented,    /* GetItemsAsync() */
};

static HRESULT file_FileType(storage_item *self, hstring_t *out)
{
    WCHAR name[MAX_PATH];
    const WCHAR *dot;
    self = item_from(self);
    if (!out) return E_POINTER;
    path_name(self->path, name, MAX_PATH);
    dot = wcsrchr(name, L'.');
    *out = svc_string(dot ? dot : L"");
    return S_OK;
}

static const void *file_vtable[] = {
    (const void *)item_QueryInterface, (const void *)item_AddRef, (const void *)item_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)file_FileType,        /* FileType */
    (const void *)svc_unimplemented,    /* ContentType */
    (const void *)svc_unimplemented,    /* OpenAsync */
    (const void *)svc_unimplemented,    /* OpenTransactedWriteAsync */
    (const void *)svc_unimplemented,    /* CopyAsync(folder) */
    (const void *)svc_unimplemented,    /* CopyAsync(folder, name) */
    (const void *)svc_unimplemented,    /* CopyAsync(folder, name, option) */
    (const void *)svc_unimplemented,    /* CopyAndReplaceAsync */
    (const void *)svc_unimplemented,    /* MoveAsync(folder) */
    (const void *)svc_unimplemented,    /* MoveAsync(folder, name) */
    (const void *)svc_unimplemented,    /* MoveAsync(folder, name, option) */
    (const void *)svc_unimplemented,    /* MoveAndReplaceAsync */
};

static const void *folder2_vtable[] = {
    (const void *)item_QueryInterface, (const void *)item_AddRef, (const void *)item_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)folder_TryGetItem,
};

static int svc_is_storage_face(const void *pointer)
{
    const void **vtbl = pointer ? *(const void ***)pointer : NULL;
    return vtbl == item_vtable || vtbl == folder_vtable || vtbl == file_vtable ||
           vtbl == folder2_vtable;
}

static void svc_hold(void *pointer)
{
    if (!pointer) return;
    if (svc_is_storage_face(pointer))
        item_AddRef(pointer);
    else
        ((ULONG(__stdcall *)(void *))(*(void ***)pointer)[1])(pointer);
}

static void svc_drop(void *pointer)
{
    if (!pointer) return;
    if (svc_is_storage_face(pointer))
        item_Release(pointer);
    else
        ((ULONG(__stdcall *)(void *))(*(void ***)pointer)[2])(pointer);
}

/* IStorageFile and IStorageFolder results are handed to the caller as the
   interface its method returns, not as the IStorageItem face. */
static void *item_file_face(storage_item *item)
{
    return item && !item->is_folder ? (void *)&item->file : (void *)&item->base;
}

static void *item_folder_face(storage_item *item)
{
    return item && item->is_folder ? (void *)&item->folder : (void *)&item->base;
}

/* ------------------------------------------------ package identity paths */

typedef struct {
    WCHAR name[128];
    WCHAR publisher[256];
    WCHAR version[32];
    WCHAR hash[16];
    WCHAR family[192];
    WCHAR full[384];
    WCHAR root[MAX_PATH];
    WCHAR display_name[128];
    WCHAR publisher_display_name[128];
    WCHAR description[256];
    /* When the package was installed, as a WinRT DateTime: the manifest's write time. */
    LONGLONG installed;
    int loaded;
} package_identity;

static package_identity identity;

static void identity_hash(const WCHAR *publisher, WCHAR *out)
{
    static const char alphabet[] = "0123456789abcdefghjkmnpqrstvwxyz";
    sha256_ctx ctx;
    unsigned char digest[32];
    unsigned long long bits = 0;
    int i;
    sha256_init(&ctx);
    sha256_update(&ctx, (const unsigned char *)publisher,
                  (unsigned)(wcslen(publisher) * sizeof(WCHAR)));
    sha256_final(&ctx, digest);
    /* The first 64 bits of the digest, most significant first, padded with
       one zero bit to 65 and written five bits to a character. */
    for (i = 0; i < 8; i++) bits = (bits << 8) | digest[i];
    for (i = 0; i < 12; i++) out[i] = alphabet[(bits >> (59 - i * 5)) & 31];
    out[12] = alphabet[(bits << 1) & 31];
    out[13] = 0;
}

extern int uwp_package_root(WCHAR *out, size_t cap);

static int xml_decode_to_wide(const char *src, size_t len, WCHAR *out, int capacity)
{
    char decoded[512];
    size_t i = 0, o = 0;
    int wlen;
    if (!out || capacity < 1) return 0;
    while (i < len && o + 4 < sizeof(decoded)) {
        if (src[i] == '&') {
            if (i + 5 <= len && memcmp(src + i, "&amp;", 5) == 0) {
                decoded[o++] = '&';
                i += 5;
                continue;
            }
            if (i + 4 <= len && memcmp(src + i, "&lt;", 4) == 0) {
                decoded[o++] = '<';
                i += 4;
                continue;
            }
            if (i + 4 <= len && memcmp(src + i, "&gt;", 4) == 0) {
                decoded[o++] = '>';
                i += 4;
                continue;
            }
            if (i + 6 <= len && memcmp(src + i, "&quot;", 6) == 0) {
                decoded[o++] = '"';
                i += 6;
                continue;
            }
            if (i + 6 <= len && memcmp(src + i, "&apos;", 6) == 0) {
                decoded[o++] = '\'';
                i += 6;
                continue;
            }
            if (i + 3 < len && src[i + 1] == '#') {
                size_t j = i + 2;
                unsigned code = 0;
                int hex = 0;
                if (j < len && (src[j] == 'x' || src[j] == 'X')) {
                    hex = 1;
                    j++;
                }
                while (j < len && src[j] != ';') {
                    char c = src[j++];
                    if (hex) {
                        code <<= 4;
                        if (c >= '0' && c <= '9') code |= (unsigned)(c - '0');
                        else if (c >= 'a' && c <= 'f') code |= (unsigned)(c - 'a' + 10);
                        else if (c >= 'A' && c <= 'F') code |= (unsigned)(c - 'A' + 10);
                        else { code = 0; break; }
                    } else if (c >= '0' && c <= '9') {
                        code = code * 10 + (unsigned)(c - '0');
                    } else {
                        code = 0;
                        break;
                    }
                }
                if (j < len && src[j] == ';' && code > 0 && code < 0x80) {
                    decoded[o++] = (char)code;
                    i = j + 1;
                    continue;
                }
            }
        }
        decoded[o++] = src[i++];
    }
    decoded[o] = 0;
    if (o == 0) {
        out[0] = 0;
        return 0;
    }
    wlen = MultiByteToWideChar(CP_UTF8, 0, decoded, (int)o, out, capacity - 1);
    if (wlen <= 0) {
        size_t k;
        for (k = 0; k < o && (int)k < capacity - 1; k++) out[k] = (WCHAR)(unsigned char)decoded[k];
        out[k] = 0;
        return k > 0;
    }
    out[wlen] = 0;
    return wlen > 0;
}

static int manifest_tag_attr(const char *manifest, const char *tag_name, const char *attr_name,
                             WCHAR *out, int capacity)
{
    const char *p = manifest;
    size_t tag_len = strlen(tag_name);
    size_t attr_len = strlen(attr_name);
    while (p && (p = strchr(p, '<')) != NULL) {
        p++;
        if (*p == '/' || *p == '!' || *p == '?') continue;
        /* Skip optional namespace prefix before ':' on the element name. */
        const char *name_start = p;
        while (*p && *p != '>' && *p != '/' && *p != ' ' && *p != '\t' && *p != '\r' &&
               *p != '\n') {
            if (*p == ':') name_start = p + 1;
            p++;
        }
        if ((size_t)(p - name_start) == tag_len && memcmp(name_start, tag_name, tag_len) == 0) {
            while (*p && *p != '>') {
                while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n' || *p == '/') p++;
                if (!*p || *p == '>') break;
                const char *a = p;
                while (*p && *p != '=' && *p != '>' && *p != ' ' && *p != '\t' && *p != '\r' &&
                       *p != '\n')
                    p++;
                size_t alen = (size_t)(p - a);
                while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
                if (*p != '=') continue;
                p++;
                while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
                if (*p != '"' && *p != '\'') continue;
                char q = *p++;
                const char *val = p;
                while (*p && *p != q) p++;
                size_t vlen = (size_t)(p - val);
                if (*p == q) p++;
                if (alen == attr_len && memcmp(a, attr_name, attr_len) == 0)
                    return xml_decode_to_wide(val, vlen, out, capacity);
            }
        }
    }
    return 0;
}

/* The text of the first <tag>text</tag>, for the Properties strings. */
static int manifest_element(const char *manifest, const char *tag_name, WCHAR *out, int capacity)
{
    const char *p = manifest;
    size_t tag_len = strlen(tag_name);
    while (p && (p = strchr(p, '<')) != NULL) {
        p++;
        if (*p == '/' || *p == '!' || *p == '?') continue;
        const char *name_start = p;
        while (*p && *p != '>' && *p != '/' && *p != ' ' && *p != '\t' && *p != '\r' &&
               *p != '\n') {
            if (*p == ':') name_start = p + 1;
            p++;
        }
        if ((size_t)(p - name_start) == tag_len && memcmp(name_start, tag_name, tag_len) == 0) {
            const char *gt = strchr(p, '>');
            const char *end;
            if (!gt || (gt > p && gt[-1] == '/')) continue;
            gt++;
            end = strchr(gt, '<');
            if (!end) return 0;
            return xml_decode_to_wide(gt, (size_t)(end - gt), out, capacity);
        }
    }
    return 0;
}

static INIT_ONCE package_load_once = INIT_ONCE_STATIC_INIT;

static BOOL CALLBACK package_load_once_fn(PINIT_ONCE once, PVOID param, PVOID *ctx)
{
    WCHAR module[MAX_PATH];
    WCHAR manifest_path[MAX_PATH];
    char *manifest = NULL;
    HANDLE file;
    DWORD size, read = 0;
    (void)once;
    (void)param;
    (void)ctx;

    if (!uwp_package_root(module, MAX_PATH)) {
        WCHAR *slash;
        GetModuleFileNameW(NULL, module, MAX_PATH);
        slash = wcsrchr(module, L'\\');
        if (slash) *slash = 0;
    }
    wcsncpy(identity.root, module, MAX_PATH - 1);
    identity.root[MAX_PATH - 1] = 0;
    _snwprintf(manifest_path, MAX_PATH, L"%s\\AppxManifest.xml", module);
    manifest_path[MAX_PATH - 1] = 0;

    file = CreateFileW(manifest_path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING,
                       FILE_ATTRIBUTE_NORMAL, NULL);
    if (file != INVALID_HANDLE_VALUE) {
        FILETIME written;
        if (GetFileTime(file, NULL, NULL, &written))
            identity.installed = ((LONGLONG)written.dwHighDateTime << 32) | written.dwLowDateTime;
        size = GetFileSize(file, NULL);
        if (size && size != INVALID_FILE_SIZE && size < (1u << 20)) {
            manifest = (char *)malloc(size + 1);
            if (manifest) {
                if (ReadFile(file, manifest, size, &read, NULL)) {
                    manifest[read] = 0;
                } else {
                    free(manifest);
                    manifest = NULL;
                }
            }
        }
        CloseHandle(file);
    }

    if (manifest) {
        manifest_tag_attr(manifest, "Identity", "Name", identity.name, 128);
        manifest_tag_attr(manifest, "Identity", "Publisher", identity.publisher, 256);
        manifest_tag_attr(manifest, "Identity", "Version", identity.version, 32);
        manifest_element(manifest, "DisplayName", identity.display_name, 128);
        manifest_element(manifest, "PublisherDisplayName", identity.publisher_display_name, 128);
        manifest_element(manifest, "Description", identity.description, 256);
    }
    if (!identity.name[0]) wcscpy(identity.name, L"Unknown.Package");
    if (!identity.version[0]) wcscpy(identity.version, L"1.0.0.0");
    identity_hash(identity.publisher, identity.hash);
    _snwprintf(identity.family, 192, L"%s_%s", identity.name, identity.hash);
    identity.family[191] = 0;
    /* Full name is name_version_architecture_resourceId_publisherId with an
       empty resource id, as PackageId.FullName reports it. */
    _snwprintf(identity.full, 384, L"%s_%s_x64__%s", identity.name, identity.version, identity.hash);
    identity.full[383] = 0;
    if (manifest) free(manifest);
    identity.loaded = 1;
    svc_trace_w("package family", identity.family);
    return TRUE;
}

static void package_load(void)
{
    InitOnceExecuteOnce(&package_load_once, package_load_once_fn, NULL, NULL);
}

/* The package's AppContainer SID: S-1-15-2 and seven sub-authorities read from
   the SHA-256 of the lowercased family name, as
   DeriveAppContainerSidFromAppContainerName makes it. Fills 40 bytes. */
DWORD uwp_package_sid(BYTE *out, DWORD capacity);
DWORD uwp_package_sid(BYTE *out, DWORD capacity)
{
    static const BYTE head[] = {1, 8, 0, 0, 0, 0, 0, 15, 2, 0, 0, 0};
    WCHAR lower[192];
    sha256_ctx ctx;
    unsigned char digest[32];
    size_t i;

    if (capacity < sizeof(head) + 28) return sizeof(head) + 28;
    package_load();
    for (i = 0; identity.family[i] && i < 191; i++) lower[i] = towlower(identity.family[i]);
    lower[i] = 0;
    sha256_init(&ctx);
    sha256_update(&ctx, (const unsigned char *)lower, (unsigned)(i * sizeof(WCHAR)));
    sha256_final(&ctx, digest);
    memcpy(out, head, sizeof(head));
    memcpy(out + sizeof(head), digest, 28);
    return sizeof(head) + 28;
}

static void package_data_path(const WCHAR *sub, WCHAR *out)
{
    WCHAR local[MAX_PATH];
    WCHAR profile[MAX_PATH];
    package_load();
    if (!GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH)) {
        if (!GetEnvironmentVariableW(L"USERPROFILE", profile, MAX_PATH))
            wcscpy(profile, L"C:\\users\\steamuser");
        _snwprintf(local, MAX_PATH, L"%s\\AppData\\Local", profile);
    }
    _snwprintf(out, MAX_PATH, L"%s\\Packages\\%s\\%s", local, identity.family, sub);
    out[MAX_PATH - 1] = 0;
}

/* ---------------------------------------------------------- ApplicationData */

static svc_object appdata_static;
static svc_object appdata_object;

static int ensure_directory(const WCHAR *path)
{
    WCHAR walk[MAX_PATH];
    WCHAR *at;
    DWORD attrs;
    if (!path || !path[0] || wcslen(path) >= MAX_PATH) return 0;
    wcsncpy(walk, path, MAX_PATH - 1);
    walk[MAX_PATH - 1] = 0;
    for (at = walk + 3; *at; at++) {
        if (*at == L'\\' || *at == L'/') {
            WCHAR slash = *at;
            *at = 0;
            if (!CreateDirectoryW(walk, NULL) && GetLastError() != ERROR_ALREADY_EXISTS)
                return 0;
            *at = slash;
        }
    }
    if (!CreateDirectoryW(walk, NULL) && GetLastError() != ERROR_ALREADY_EXISTS)
        return 0;
    attrs = GetFileAttributesW(walk);
    return attrs != INVALID_FILE_ATTRIBUTES && (attrs & FILE_ATTRIBUTE_DIRECTORY) != 0;
}

int uwp_local_state_dir(WCHAR *out, size_t cap);
int uwp_local_state_dir(WCHAR *out, size_t cap)
{
    WCHAR path[MAX_PATH];
    if (!out || cap == 0) return 0;
    out[0] = 0;
    package_data_path(L"LocalState", path);
    if (!ensure_directory(path)) return 0;
    if (wcslen(path) >= cap) return 0;
    wcscpy(out, path);
    return 1;
}

int uwp_package_family_name(char *out, size_t cap);
int uwp_package_family_name(char *out, size_t cap)
{
    int n;
    if (!out || cap == 0) return 0;
    out[0] = 0;
    package_load();
    if (!identity.family[0]) return 0;
    n = WideCharToMultiByte(CP_UTF8, 0, identity.family, -1, out, (int)cap, NULL, NULL);
    return n > 1;
}

static storage_item *package_folder(const WCHAR *sub)
{
    WCHAR path[MAX_PATH];
    package_data_path(sub, path);
    ensure_directory(path);
    return item_new(path, 1);
}

static HRESULT ad_ClearAsync(svc_object *self, void **out);

static HRESULT appdata_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    /* The projection reaches ApplicationData through IApplicationData and also
       asks the same object for the statics IID its metadata carries. */
    if (IsEqualIID(id, &svc_iid_appdata) || IsEqualIID(id, &svc_iid_appdata_statics) ||
        IsEqualIID(id, &svc_iid_unknown) || IsEqualIID(id, &svc_iid_inspectable) ||
        IsEqualIID(id, &svc_iid_agile_object))
        *out = self;
    else {
        *out = NULL;
        return E_NOINTERFACE;
    }
    svc_AddRef((svc_object *)self);
    return S_OK;
}

static HRESULT ad_statics_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_appdata_statics, out);
}

static HRESULT ad_LocalFolder(svc_object *self, void **out)
{
    storage_item *folder;
    (void)self;
    if (!out) return E_POINTER;
    folder = package_folder(L"LocalState");
    if (!folder) return E_OUTOFMEMORY;
    *out = item_folder_face(folder);
    return S_OK;
}

static HRESULT ad_LocalCacheFolder(svc_object *self, void **out)
{
    storage_item *folder;
    (void)self;
    if (!out) return E_POINTER;
    folder = package_folder(L"LocalCache");
    if (!folder) return E_OUTOFMEMORY;
    *out = item_folder_face(folder);
    return S_OK;
}

static HRESULT ad_RoamingFolder(svc_object *self, void **out)
{
    storage_item *folder;
    (void)self;
    if (!out) return E_POINTER;
    folder = package_folder(L"RoamingState");
    if (!folder) return E_OUTOFMEMORY;
    *out = item_folder_face(folder);
    return S_OK;
}

static HRESULT ad_TemporaryFolder(svc_object *self, void **out)
{
    storage_item *folder;
    (void)self;
    if (!out) return E_POINTER;
    folder = package_folder(L"TempState");
    if (!folder) return E_OUTOFMEMORY;
    *out = item_folder_face(folder);
    return S_OK;
}

static HRESULT ad_Settings(svc_object *self, void **out)
{
    (void)self;
    if (out) *out = NULL;
    svc_trace("ApplicationData settings containers are not hosted");
    return E_NOTIMPL;
}

static HRESULT ad_RoamingQuota(svc_object *self, UINT64 *out)
{
    (void)self;
    if (out) *out = 0;
    return S_OK;
}

static HRESULT ad_Version(svc_object *self, UINT32 *out)
{
    (void)self;
    if (out) *out = 0;
    return S_OK;
}

static HRESULT ad_SetVersionAsync(svc_object *self, UINT32 version, void *handler, void **out)
{
    (void)self;
    (void)version;
    (void)handler;
    if (!out) return E_POINTER;
    return async_action(S_OK, out);
}

static HRESULT clear_directory_contents(const WCHAR *dir)
{
    WCHAR pattern[MAX_PATH];
    WIN32_FIND_DATAW data;
    HANDLE find;
    HRESULT hr = S_OK;
    int n = _snwprintf(pattern, MAX_PATH, L"%s\\*", dir);
    if (n < 0 || n >= MAX_PATH) return HRESULT_FROM_WIN32(ERROR_FILENAME_EXCED_RANGE);
    find = FindFirstFileW(pattern, &data);
    if (find == INVALID_HANDLE_VALUE) {
        DWORD err = GetLastError();
        if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) return S_OK;
        return HRESULT_FROM_WIN32(err);
    }
    for (;;) {
        if (wcscmp(data.cFileName, L".") != 0 && wcscmp(data.cFileName, L"..") != 0) {
            WCHAR victim[MAX_PATH];
            int vn = _snwprintf(victim, MAX_PATH, L"%s\\%s", dir, data.cFileName);
            if (vn < 0 || vn >= MAX_PATH) {
                hr = HRESULT_FROM_WIN32(ERROR_FILENAME_EXCED_RANGE);
                break;
            }
            if (data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
                hr = clear_directory_contents(victim);
                if (FAILED(hr)) break;
                if (!RemoveDirectoryW(victim)) {
                    hr = HRESULT_FROM_WIN32(GetLastError());
                    break;
                }
            } else {
                if (!DeleteFileW(victim)) {
                    hr = HRESULT_FROM_WIN32(GetLastError());
                    break;
                }
            }
        }
        if (!FindNextFileW(find, &data)) {
            DWORD err = GetLastError();
            if (err != ERROR_NO_MORE_FILES) hr = HRESULT_FROM_WIN32(err);
            break;
        }
    }
    FindClose(find);
    return hr;
}

static HRESULT ad_ClearAsync(svc_object *self, void **out)
{
    static const WCHAR *const dirs[] = {L"LocalState", L"RoamingState", L"TempState",
                                        L"LocalCache"};
    size_t i;
    HRESULT hr = S_OK;
    (void)self;
    if (!out) return E_POINTER;
    for (i = 0; i < sizeof(dirs) / sizeof(dirs[0]); i++) {
        WCHAR path[MAX_PATH];
        package_data_path(dirs[i], path);
        hr = clear_directory_contents(path);
        if (FAILED(hr)) break;
    }
    return async_action(hr, out);
}

static HRESULT ad_ClearTask(svc_object *self, void **out)
{
    (void)self;
    if (out) *out = NULL;
    svc_trace("ApplicationData::ClearTask needs a task the app did not register");
    return E_NOTIMPL;
}

static HRESULT ad_SignalDataChanged(svc_object *self)
{
    (void)self;
    return S_OK;
}

static HRESULT ad_add_DataChanged(svc_object *self, void *handler, void *token)
{
    (void)self;
    (void)handler;
    if (token) *(long long *)token = 1;
    return S_OK;
}

static HRESULT ad_remove_DataChanged(svc_object *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

/* Slots follow IFrameworkView-era metadata: the folder slot is the one the
   projection reads, and the neighbouring folder slots answer the same real
   directory rather than a different one. */
static const void *appdata_vtable[] = {
    (const void *)appdata_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)ad_Version,
    (const void *)ad_SetVersionAsync,
    (const void *)ad_ClearAsync,
    (const void *)ad_ClearTask,
    (const void *)ad_Settings,          /* LocalSettings */
    (const void *)ad_Settings,          /* RoamingSettings */
    (const void *)ad_LocalFolder,
    (const void *)ad_RoamingFolder,
    (const void *)ad_TemporaryFolder,
    (const void *)ad_add_DataChanged,
    (const void *)ad_remove_DataChanged,
    (const void *)ad_SignalDataChanged,
    (const void *)ad_RoamingQuota,
    (const void *)ad_TemporaryFolder,
    (const void *)ad_LocalCacheFolder,
    (const void *)ad_RoamingFolder,
};

static HRESULT ads_Current(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &appdata_object;
    svc_AddRef(&appdata_object);
    return S_OK;
}

static const void *appdata_statics_vtable[] = {
    (const void *)ad_statics_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)ads_Current,
};

/* --------------------------------------------------------------- package */

static const GUID svc_iid_package_statics = {0x4e534bdf, 0x2960, 0x4878,
                                             {0x97, 0xa4, 0x96, 0x24, 0xde, 0xb7, 0x2f, 0x2d}};
static const GUID svc_iid_package = {0x163c792f, 0xbd75, 0x413c,
                                     {0xbf, 0x23, 0xb1, 0xfe, 0x7b, 0x95, 0xd8, 0x25}};
static const GUID svc_iid_package2 = {0xa6612fb6, 0x7688, 0x4ace,
                                      {0x95, 0xfb, 0x35, 0x95, 0x38, 0xe7, 0xaa, 0x01}};
static const GUID svc_iid_package_id = {0x1adb665e, 0x37c7, 0x4790,
                                        {0x99, 0x80, 0xdd, 0x7a, 0xe7, 0x4e, 0x8b, 0xb2}};

static const GUID svc_iid_package3 = {0x5f738b61, 0xf86a, 0x4917,
                                      {0x93, 0xd1, 0xf1, 0xee, 0x9d, 0x3b, 0x35, 0xd9}};
static const GUID svc_iid_package4 = {0x65aed1ae, 0xb95b, 0x450c,
                                      {0x88, 0x2b, 0x62, 0x55, 0x18, 0x7f, 0x39, 0x7e}};
static const GUID svc_iid_package_status = {0x5fe74f71, 0xa365, 0x4c09,
                                            {0xa0, 0x2d, 0x04, 0x6d, 0x52, 0x5e, 0xa1, 0xda}};
static const GUID svc_iid_package_vector = {0x0263c4d4, 0x195c, 0x5dc5,
                                            {0xa7, 0xca, 0x68, 0x06, 0xce, 0xca, 0x42, 0x0b}};
static const GUID svc_iid_package_iterable = {0x69ad6aa7, 0x0c49, 0x5f27,
                                              {0xa5, 0xeb, 0xef, 0x4d, 0x59, 0x46, 0x7b, 0x6d}};
static const GUID svc_iid_package_iterator = {0x0217f069, 0x025c, 0x5ee6,
                                              {0xa8, 0x7f, 0xe7, 0x82, 0xe3, 0xb6, 0x23, 0xae}};

static svc_object package_static;
static svc_object package_object;
static svc_object package_id_object;
/* Package answers IPackage2..4 from their own pointers, as Windows' object
   does; package_object is IPackage and the object's identity. */
static svc_object package2_face;
static svc_object package3_face;
static svc_object package4_face;
static svc_object package_status_object;
static svc_object package_deps;
static svc_object package_deps_iterable;
static svc_object package_deps_iterator;

static HRESULT pkg_QI(void *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_package) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object))
        *out = &package_object;
    else if (IsEqualIID(id, &svc_iid_package2))
        *out = &package2_face;
    else if (IsEqualIID(id, &svc_iid_package3))
        *out = &package3_face;
    else if (IsEqualIID(id, &svc_iid_package4))
        *out = &package4_face;
    else {
        *out = NULL;
        return E_NOINTERFACE;
    }
    svc_AddRef((svc_object *)*out);
    return S_OK;
}

static HRESULT pkg_status_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_package_status, out);
}

static HRESULT svc_true(void *self, unsigned char *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 1;
    return S_OK;
}

static HRESULT svc_false(void *self, unsigned char *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

static HRESULT pkg_static_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_package_statics, out);
}

static HRESULT pid_QueryInterface(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_package_id, out);
}

static HRESULT pkg_Id(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &package_id_object;
    svc_AddRef(&package_id_object);
    return S_OK;
}

static HRESULT pkg_InstalledLocation(svc_object *self, void **out)
{
    storage_item *folder;
    (void)self;
    if (!out) return E_POINTER;
    package_load();
    folder = item_new(identity.root, 1);
    if (!folder) return E_OUTOFMEMORY;
    *out = item_folder_face(folder);
    return S_OK;
}

static HRESULT pkg_IsFramework(svc_object *self, unsigned char *out)
{
    (void)self;
    if (out) *out = 0;
    return S_OK;
}

static HRESULT pkg_DisplayName(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    package_load();
    *out = svc_string(identity.display_name);
    return S_OK;
}

static HRESULT pkg_PublisherDisplayName(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    package_load();
    *out = svc_string(identity.publisher_display_name);
    return S_OK;
}

static HRESULT pkg_Description(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    package_load();
    *out = svc_string(identity.description);
    return S_OK;
}

static HRESULT pkg_Status(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &package_status_object;
    svc_AddRef(&package_status_object);
    return S_OK;
}

static HRESULT pkg_InstalledDate(svc_object *self, LONGLONG *out)
{
    (void)self;
    if (!out) return E_POINTER;
    package_load();
    *out = identity.installed;
    return S_OK;
}

/* PackageSignatureKind::Store: the package came from the Store. */
static HRESULT pkg_SignatureKind(svc_object *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 3;
    return S_OK;
}

#ifndef E_BOUNDS
#define E_BOUNDS ((HRESULT)0x8000000BL)
#endif

static HRESULT pkg_deps_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_package_vector) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object))
        *out = &package_deps;
    else if (IsEqualIID(id, &svc_iid_package_iterable))
        *out = &package_deps_iterable;
    else {
        *out = NULL;
        return E_NOINTERFACE;
    }
    svc_AddRef((svc_object *)*out);
    return S_OK;
}

static HRESULT pkg_deps_GetAt(svc_object *self, unsigned index, void **out)
{
    (void)self;
    (void)index;
    if (out) *out = NULL;
    return E_BOUNDS;
}

static HRESULT pkg_deps_get_Size(svc_object *self, unsigned *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

static HRESULT pkg_deps_IndexOf(svc_object *self, void *item, unsigned *index, boolean *found)
{
    (void)self;
    (void)item;
    if (!index || !found) return E_POINTER;
    *index = 0;
    *found = FALSE;
    return S_OK;
}

static HRESULT pkg_deps_GetMany(svc_object *self, unsigned start, unsigned capacity,
                                void **items, unsigned *actual)
{
    (void)self;
    (void)start;
    (void)capacity;
    (void)items;
    if (!actual) return E_POINTER;
    *actual = 0;
    return S_OK;
}

static HRESULT pkg_deps_First(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &package_deps_iterator;
    svc_AddRef(&package_deps_iterator);
    return S_OK;
}

static HRESULT pkg_deps_iter_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_package_iterator, out);
}

static HRESULT pkg_deps_iter_Current(svc_object *self, void **out)
{
    (void)self;
    if (out) *out = NULL;
    return E_BOUNDS;
}

static HRESULT pkg_deps_iter_flag(svc_object *self, boolean *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = FALSE;
    return S_OK;
}

static HRESULT pkg_deps_iter_GetMany(svc_object *self, unsigned capacity, void **items, unsigned *actual)
{
    (void)self;
    (void)capacity;
    (void)items;
    if (!actual) return E_POINTER;
    *actual = 0;
    return S_OK;
}

static const void *package_deps_vtable[] = {
    (const void *)pkg_deps_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)pkg_deps_GetAt, (const void *)pkg_deps_get_Size,
    (const void *)pkg_deps_IndexOf, (const void *)pkg_deps_GetMany,
};

static const void *package_deps_iterable_vtable[] = {
    (const void *)pkg_deps_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)pkg_deps_First,
};

static const void *package_deps_iterator_vtable[] = {
    (const void *)pkg_deps_iter_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)pkg_deps_iter_Current, (const void *)pkg_deps_iter_flag,
    (const void *)pkg_deps_iter_flag, (const void *)pkg_deps_iter_GetMany,
};

static HRESULT pkg_Dependencies(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &package_deps;
    svc_AddRef(&package_deps);
    return S_OK;
}

static const void *package_vtable[] = {
    (const void *)pkg_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)pkg_Id, (const void *)pkg_InstalledLocation, (const void *)pkg_IsFramework,
    (const void *)pkg_Dependencies,
};

static const void *package2_vtable[] = {
    (const void *)pkg_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)pkg_DisplayName, (const void *)pkg_PublisherDisplayName,
    (const void *)pkg_Description,
    (const void *)svc_unimplemented,    /* Logo */
    (const void *)svc_false,            /* IsResourcePackage */
    (const void *)svc_false,            /* IsBundle */
    (const void *)svc_false,            /* IsDevelopmentMode */
};

static const void *package3_vtable[] = {
    (const void *)pkg_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)pkg_Status, (const void *)pkg_InstalledDate,
    (const void *)svc_unimplemented,    /* GetAppListEntriesAsync */
};

static const void *package4_vtable[] = {
    (const void *)pkg_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)pkg_SignatureKind,
    (const void *)svc_false,            /* IsOptional */
    (const void *)svc_unimplemented,    /* VerifyContentIntegrityAsync */
};

/* VerifyIsOK, then every problem flag clear. */
static const void *package_status_vtable[] = {
    (const void *)pkg_status_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)svc_true,
    (const void *)svc_false, (const void *)svc_false, (const void *)svc_false,
    (const void *)svc_false, (const void *)svc_false, (const void *)svc_false,
    (const void *)svc_false, (const void *)svc_false, (const void *)svc_false,
    (const void *)svc_false, (const void *)svc_false,
};

static HRESULT pid_Name(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    package_load();
    *out = svc_string(identity.name);
    return S_OK;
}

static HRESULT pid_Version(svc_object *self, unsigned long long *out)
{
    unsigned a = 0, b = 0, c = 0, d = 0;
    (void)self;
    if (!out) return E_POINTER;
    package_load();
    swscanf(identity.version, L"%u.%u.%u.%u", &a, &b, &c, &d);
    *out = ((unsigned long long)a << 48) | ((unsigned long long)b << 32) |
           ((unsigned long long)c << 16) | d;
    return S_OK;
}

static HRESULT pid_Architecture(svc_object *self, int *out)
{
    (void)self;
    if (out) *out = 9; /* ProcessorArchitecture::X64 */
    return S_OK;
}

static HRESULT pid_ResourceId(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = svc_string(L"");
    return S_OK;
}

static HRESULT pid_Publisher(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    package_load();
    *out = svc_string(identity.publisher);
    return S_OK;
}

static HRESULT pid_PublisherId(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    package_load();
    *out = svc_string(identity.hash);
    return S_OK;
}

static HRESULT pid_FullName(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    package_load();
    *out = svc_string(identity.full);
    return S_OK;
}

static HRESULT pid_FamilyName(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    package_load();
    *out = svc_string(identity.family);
    return S_OK;
}

static const void *package_id_vtable[] = {
    (const void *)pid_QueryInterface, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)pid_Name, (const void *)pid_Version, (const void *)pid_Architecture,
    (const void *)pid_ResourceId, (const void *)pid_Publisher, (const void *)pid_PublisherId,
    (const void *)pid_FullName, (const void *)pid_FamilyName,
};

static HRESULT pkg_static_Current(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &package_object;
    svc_AddRef(&package_object);
    return S_OK;
}

static const void *package_static_vtable[] = {
    (const void *)pkg_static_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)pkg_static_Current,
};

/* -------------------------------------------------------- PackageCatalog */

static const GUID svc_iid_package_catalog = {0x230a3751, 0x9de3, 0x4445,
                                             {0xbe, 0x74, 0x91, 0xfb, 0x32, 0x5a, 0xbe, 0xfe}};
static const GUID svc_iid_package_catalog_statics = {
    0xa18c9696, 0xe65b, 0x4634, {0xba, 0x21, 0x5e, 0x63, 0xeb, 0x72, 0x44, 0xa7}};

static svc_object package_catalog_object;
static svc_object package_catalog_static;

/* Events on this catalog carry a delegate the title owns, so its reference
   count lives in its own vtable rather than in ours. */
typedef unsigned long(__stdcall *catalog_ref_fn)(void *);

static void catalog_hold(void *object)
{
    if (object && *(void ***)object && (*(void ***)object)[1])
        ((catalog_ref_fn)(*(void ***)object)[1])(object);
}

static void catalog_drop(void *object)
{
    if (object && *(void ***)object && (*(void ***)object)[2])
        ((catalog_ref_fn)(*(void ***)object)[2])(object);
}

static HRESULT pc_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_package_catalog, out);
}

static HRESULT pc_static_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_package_catalog_statics, out);
}

enum {
    PC_EV_STAGING,
    PC_EV_INSTALLING,
    PC_EV_UPDATING,
    PC_EV_UNINSTALLING,
    PC_EV_STATUS_CHANGED,
    PC_EV_COUNT
};

static void *pc_event_handler[PC_EV_COUNT];
static long long pc_event_token[PC_EV_COUNT];

static HRESULT pc_event_add(svc_object *self, int slot, void *handler, long long *token)
{
    (void)self;
    if (!token) return E_POINTER;
    catalog_drop(pc_event_handler[slot]);
    pc_event_handler[slot] = handler;
    catalog_hold(handler);
    pc_event_token[slot]++;
    *token = pc_event_token[slot];
    return S_OK;
}

static HRESULT pc_event_remove(svc_object *self, int slot, long long token)
{
    (void)self;
    if (token != pc_event_token[slot]) return S_OK;
    catalog_drop(pc_event_handler[slot]);
    pc_event_handler[slot] = NULL;
    return S_OK;
}

#define PC_EVENT(name, index)                                                    \
    static HRESULT pc_##name##_add(svc_object *self, void *handler,              \
                                   long long *token)                             \
    {                                                                            \
        return pc_event_add(self, index, handler, token);                        \
    }                                                                            \
    static HRESULT pc_##name##_remove(svc_object *self, long long token)         \
    {                                                                            \
        return pc_event_remove(self, index, token);                              \
    }

PC_EVENT(staging, PC_EV_STAGING)
PC_EVENT(installing, PC_EV_INSTALLING)
PC_EVENT(updating, PC_EV_UPDATING)
PC_EVENT(uninstalling, PC_EV_UNINSTALLING)
PC_EVENT(status_changed, PC_EV_STATUS_CHANGED)

static const void *package_catalog_vtable[] = {
    (const void *)pc_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)pc_staging_add, (const void *)pc_staging_remove,
    (const void *)pc_installing_add, (const void *)pc_installing_remove,
    (const void *)pc_updating_add, (const void *)pc_updating_remove,
    (const void *)pc_uninstalling_add, (const void *)pc_uninstalling_remove,
    (const void *)pc_status_changed_add, (const void *)pc_status_changed_remove,
};

/* Both statics entries hand back the same catalog: this title asks for its own
   package's catalog, and the machine has exactly one. */
static HRESULT pc_open(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    svc_trace("package catalog opened");
    *out = &package_catalog_object;
    svc_AddRef(&package_catalog_object);
    return S_OK;
}

static const void *package_catalog_statics_vtable[] = {
    (const void *)pc_static_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)pc_open, /* OpenForCurrentPackage */
    (const void *)pc_open, /* OpenForCurrentUser */
};

/* --------------------------------------------------- display information */

static const GUID svc_iid_display = {0xbed112ae, 0xadc3, 0x4dc9,
                                     {0xae, 0x65, 0x85, 0x1f, 0x4d, 0x7d, 0x47, 0x99}};
static const GUID svc_iid_display2 = {0x4dcd0021, 0xfad1, 0x4b8e,
                                      {0x8e, 0xdf, 0x77, 0x58, 0x87, 0xb8, 0xbf, 0x19}};
static const GUID svc_iid_display3 = {0xdb15011d, 0x0f09, 0x4466,
                                      {0x8f, 0xf3, 0x11, 0xde, 0x9a, 0x3c, 0x92, 0x9a}};
static const GUID svc_iid_display4 = {0xc972ce2f, 0x1242, 0x46be,
                                      {0xb5, 0x36, 0xe1, 0xaa, 0xfe, 0x9e, 0x7a, 0xcf}};
static const GUID svc_iid_display_statics = {0xc6a02a6c, 0xd452, 0x44dc,
                                             {0xba, 0x07, 0x96, 0xf3, 0xc6, 0xad, 0xf9, 0xd1}};

static svc_object display_info;
/* DisplayInformation answers IDisplayInformation2..4 from their own pointers,
   as Windows' object does; display_info is IDisplayInformation and the
   object's identity. */
static svc_object display2_face;
static svc_object display3_face;
static svc_object display4_face;
static svc_object display_static;
static int display_rotation_preferences;

static HRESULT di_QI(void *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_display) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object))
        *out = &display_info;
    else if (IsEqualIID(id, &svc_iid_display2))
        *out = &display2_face;
    else if (IsEqualIID(id, &svc_iid_display3))
        *out = &display3_face;
    else if (IsEqualIID(id, &svc_iid_display4))
        *out = &display4_face;
    else {
        *out = NULL;
        return E_NOINTERFACE;
    }
    svc_AddRef((svc_object *)*out);
    return S_OK;
}

static HRESULT di_statics_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_display_statics, out);
}

static int display_caps(int index, int fallback)
{
    HDC dc = GetDC(NULL);
    int value = fallback;
    if (dc) {
        value = GetDeviceCaps(dc, index);
        ReleaseDC(NULL, dc);
    }
    return value > 0 ? value : fallback;
}

static int display_dpi(void)
{
    return display_caps(LOGPIXELSX, 96);
}

static HRESULT di_Orientation(svc_object *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    /* DisplayOrientations::Landscape */
    *out = 1;
    return S_OK;
}

/* The display never rotates or changes scale under a running title, so a
   change handler is kept nowhere and never called. */
static HRESULT di_add_event(svc_object *self, void *handler, long long *token)
{
    (void)self;
    if (!handler || !token) return E_INVALIDARG;
    *token = 1;
    return S_OK;
}

static HRESULT di_remove_event(svc_object *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

static HRESULT di_ResolutionScale(svc_object *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    /* ResolutionScale is a percentage: 100 at 96 DPI, 125 at 120, 200 at 192. */
    *out = (display_dpi() * 100) / 96;
    return S_OK;
}

static HRESULT di_LogicalDpi(svc_object *self, float *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = (float)display_dpi();
    return S_OK;
}

/* Raw DPI is the panel's physical density. Windows reports 0 when the display
   does not give its size, and so does this. */
static float display_raw_dpi(int pixels_index, int millimetres_index)
{
    int pixels = display_caps(pixels_index, 0);
    int millimetres = display_caps(millimetres_index, 0);
    return pixels && millimetres ? pixels * 25.4f / millimetres : 0.0f;
}

static HRESULT di_RawDpiX(svc_object *self, float *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = display_raw_dpi(HORZRES, HORZSIZE);
    return S_OK;
}

static HRESULT di_RawDpiY(svc_object *self, float *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = display_raw_dpi(VERTRES, VERTSIZE);
    return S_OK;
}

static HRESULT di_StereoEnabled(svc_object *self, unsigned char *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

static HRESULT di_RawPixelsPerViewPixel(svc_object *self, double *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = display_dpi() / 96.0;
    return S_OK;
}

static HRESULT di_DiagonalSizeInInches(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    /* A null reference: the display did not report its size. */
    *out = NULL;
    return S_OK;
}

static HRESULT di_ScreenWidthInRawPixels(svc_object *self, unsigned *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = (unsigned)GetSystemMetrics(SM_CXSCREEN);
    return S_OK;
}

static HRESULT di_ScreenHeightInRawPixels(svc_object *self, unsigned *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = (unsigned)GetSystemMetrics(SM_CYSCREEN);
    return S_OK;
}

#define DISPLAY_HEADER                                                                   \
    (const void *)di_QI, (const void *)svc_AddRef, (const void *)svc_Release,          \
        (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust

/* Order follows Windows.Graphics.Display.IDisplayInformation. */
static const void *display_vtable[] = {
    DISPLAY_HEADER,
    (const void *)di_Orientation,          /* CurrentOrientation */
    (const void *)di_Orientation,          /* NativeOrientation */
    (const void *)di_add_event,            /* add_OrientationChanged */
    (const void *)di_remove_event,         /* remove_OrientationChanged */
    (const void *)di_ResolutionScale,
    (const void *)di_LogicalDpi,
    (const void *)di_RawDpiX,
    (const void *)di_RawDpiY,
    (const void *)di_add_event,            /* add_DpiChanged */
    (const void *)di_remove_event,         /* remove_DpiChanged */
    (const void *)di_StereoEnabled,
    (const void *)di_add_event,            /* add_StereoEnabledChanged */
    (const void *)di_remove_event,         /* remove_StereoEnabledChanged */
    (const void *)svc_unimplemented,       /* GetColorProfileAsync */
    (const void *)di_add_event,            /* add_ColorProfileChanged */
    (const void *)di_remove_event,         /* remove_ColorProfileChanged */
};

static const void *display2_vtable[] = {
    DISPLAY_HEADER,
    (const void *)di_RawPixelsPerViewPixel,
};

static const void *display3_vtable[] = {
    DISPLAY_HEADER,
    (const void *)di_DiagonalSizeInInches,
};

static const void *display4_vtable[] = {
    DISPLAY_HEADER,
    (const void *)di_ScreenWidthInRawPixels,
    (const void *)di_ScreenHeightInRawPixels,
};

#undef DISPLAY_HEADER

static HRESULT ds_GetForCurrentView(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &display_info;
    svc_AddRef(&display_info);
    return S_OK;
}

static HRESULT ds_get_AutoRotationPreferences(svc_object *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = display_rotation_preferences;
    return S_OK;
}

static HRESULT ds_put_AutoRotationPreferences(svc_object *self, int value)
{
    (void)self;
    display_rotation_preferences = value;
    return S_OK;
}

/* Order follows Windows.Graphics.Display.IDisplayInformationStatics. */
static const void *display_static_vtable[] = {
    (const void *)di_statics_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)ds_GetForCurrentView,
    (const void *)ds_get_AutoRotationPreferences,
    (const void *)ds_put_AutoRotationPreferences,
    (const void *)di_add_event,            /* add_DisplayContentsInvalidated */
    (const void *)di_remove_event,         /* remove_DisplayContentsInvalidated */
};

/* ----------------------------------------------------------- media devices */

static const GUID svc_iid_media_statics = {0xaa2d9a40, 0x909f, 0x4bba,
                                           {0xbf, 0x8b, 0x0c, 0x0d, 0x29, 0x6f, 0x14, 0xf0}};

static svc_object media_static;

/* The AQS selectors Windows returns for each media device class. A title that
   lists devices builds the query from these, so they are the published ones. */
static const WCHAR selector_audio_capture[] =
    L"System.Devices.InterfaceClassGuid:=\"{2eef81be-33fa-4800-9670-1cd474972c3f}\""
    L" AND System.Devices.InterfaceEnabled:=System.StructuredQueryType.Boolean#True";
static const WCHAR selector_audio_render[] =
    L"System.Devices.InterfaceClassGuid:=\"{e6327cad-dcec-4949-ae8a-991e976a79d2}\""
    L" AND System.Devices.InterfaceEnabled:=System.StructuredQueryType.Boolean#True";
static const WCHAR selector_video_capture[] =
    L"System.Devices.InterfaceClassGuid:=\"{65e8773d-8f56-11d0-a3b7-00a0c9223196}\""
    L" AND System.Devices.InterfaceEnabled:=System.StructuredQueryType.Boolean#True";

static HRESULT md_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_media_statics, out);
}


/* The endpoint ID is what a title stores and compares. Windows answers it from
   the audio service without touching the caller's thread, but here the query
   runs inside the caller's process: the enumerator blocks on a device the
   title has not opened yet, and a worker thread that made that call held
   ntdll's FLS section while it blocked, which then timed out on every other
   thread that needed FLS. So the getter answers the way a machine with no
   output device answers: the empty id, which a title passes through to the
   default voice. The runtime's own audio init still runs on its own thread. */
static int endpoint_role_stamp(HKEY item, const WCHAR *val_name, ULONGLONG *stamp)
{
    BYTE buf[16];
    DWORD sz = sizeof(buf), type = 0;
    if (RegQueryValueExW(item, val_name, NULL, &type, buf, &sz) != ERROR_SUCCESS) return 0;
    if (sz == sizeof(SYSTEMTIME)) {
        FILETIME ft;
        if (SystemTimeToFileTime((const SYSTEMTIME *)buf, &ft)) {
            *stamp = ((ULONGLONG)ft.dwHighDateTime << 32) | ft.dwLowDateTime;
            return 1;
        }
    } else if (sz == sizeof(ULONGLONG)) {
        memcpy(stamp, buf, sizeof(ULONGLONG));
        return 1;
    }
    return 0;
}

static void default_endpoint_id(int capture, int role, WCHAR *out, int capacity)
{
    HKEY root = NULL;
    const WCHAR *subkey = capture
        ? L"Software\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Capture"
        : L"Software\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Render";
    const WCHAR *iface_guid = capture
        ? L"{2EEF81BE-33FA-4800-9670-1CD474972C3F}"
        : L"{E6327CAD-DCEC-4949-AE8A-991E976A79D2}";
    const WCHAR *flow_prefix = capture ? L"{0.0.1.00000000}" : L"{0.0.0.00000000}";
    /* AudioDeviceRole: 0 Default -> Role:0 (eConsole), 1 Communications -> Role:2. */
    const WCHAR *role_val = (role == 1) ? L"Role:2" : L"Role:0";
    WCHAR role_chosen[128] = {0};
    WCHAR fallback_chosen[128] = {0};
    WCHAR first_active[128] = {0};
    ULONGLONG best_role_stamp = 0;
    ULONGLONG best_fallback_stamp = 0;
    int has_role = 0, has_fallback = 0;
    DWORD index = 0;

    if (!out || capacity < 1) return;
    out[0] = 0;

    /* Read the active MMDevice endpoint GUID straight from the registry (no COM / FLS
     * deadlock on worker threads), honoring role-specific Role:0 / Role:2 timestamps. */
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, subkey, 0, KEY_READ, &root) == ERROR_SUCCESS) {
        for (;;) {
            WCHAR name[128];
            DWORD name_len = 128;
            HKEY item = NULL;
            DWORD state = 0, state_sz = sizeof state, type = 0;
            LONG rc = RegEnumKeyExW(root, index++, name, &name_len, NULL, NULL, NULL, NULL);
            if (rc != ERROR_SUCCESS) break;
            if (name[0] != L'{') continue;
            if (RegOpenKeyExW(root, name, 0, KEY_READ, &item) == ERROR_SUCCESS) {
                if (RegQueryValueExW(item, L"DeviceState", NULL, &type, (LPBYTE)&state, &state_sz) == ERROR_SUCCESS &&
                    state == 1) {
                    ULONGLONG stamp = 0;
                    if (!first_active[0]) lstrcpynW(first_active, name, 128);
                    if (endpoint_role_stamp(item, role_val, &stamp) &&
                        (!has_role || stamp >= best_role_stamp)) {
                        has_role = 1;
                        best_role_stamp = stamp;
                        lstrcpynW(role_chosen, name, 128);
                    }
                    if (role == 1 && endpoint_role_stamp(item, L"Role:0", &stamp) &&
                        (!has_fallback || stamp >= best_fallback_stamp)) {
                        has_fallback = 1;
                        best_fallback_stamp = stamp;
                        lstrcpynW(fallback_chosen, name, 128);
                    }
                }
                RegCloseKey(item);
            }
        }
        RegCloseKey(root);
    }

    {
        const WCHAR *chosen = has_role ? role_chosen : (has_fallback ? fallback_chosen : first_active);
        if (chosen[0]) {
            _snwprintf(out, (size_t)capacity, L"\\\\?\\SWD#MMDEVAPI#%ls.%ls#%ls",
                       flow_prefix, chosen, iface_guid);
            out[capacity - 1] = 0;
        } else {
            lstrcpynW(out, iface_guid, capacity);
        }
    }
}

static HRESULT md_capture_selector(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = svc_string(selector_audio_capture);
    return S_OK;
}

static HRESULT md_render_selector(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = svc_string(selector_audio_render);
    return S_OK;
}

static HRESULT md_video_selector(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = svc_string(selector_video_capture);
    return S_OK;
}

static HRESULT md_GetDefaultAudioRenderId(svc_object *self, int role, hstring_t *out)
{
    WCHAR id[512];
    (void)self;
    if (!out) return E_POINTER;
    default_endpoint_id(0, role, id, 512);
    *out = svc_string(id);
    return S_OK;
}

static HRESULT md_GetDefaultAudioCaptureId(svc_object *self, int role, hstring_t *out)
{
    WCHAR id[512];
    (void)self;
    if (!out) return E_POINTER;
    default_endpoint_id(1, role, id, 512);
    *out = svc_string(id);
    return S_OK;
}

/* A title subscribes here to hear that the default endpoint changed. This
   runtime presents one fixed set of endpoints, so the subscription is accepted
   and simply never fires. */
static HRESULT md_add_device_changed(svc_object *self, void *handler, void *token)
{
    (void)self;
    (void)handler;
    if (token) *(long long *)token = 1;
    return S_OK;
}

static HRESULT md_remove_device_changed(svc_object *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

/* IMediaDeviceStatics order, from the Windows metadata: the three class
   selectors, the two default endpoint IDs, then an add/remove pair for each
   device-changed event. */
static const void *media_static_vtable[] = {
    (const void *)md_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)md_capture_selector, (const void *)md_render_selector,
    (const void *)md_video_selector,
    (const void *)md_GetDefaultAudioCaptureId, (const void *)md_GetDefaultAudioRenderId,
    (const void *)md_add_device_changed, (const void *)md_remove_device_changed,
    (const void *)md_add_device_changed, (const void *)md_remove_device_changed,
};

/* ----------------------------------------------------------- input devices */

static const GUID svc_iid_mouse_statics = {0x484a9045, 0x6d70, 0x49db,
                                           {0x8e, 0x68, 0x46, 0xff, 0xbd, 0x17, 0xd3, 0x8d}};
static const GUID svc_iid_mouse = {0x88edf458, 0xf2c8, 0x49f4,
                                   {0xbe, 0x1f, 0xc2, 0x56, 0xb3, 0x88, 0xbc, 0x11}};
static const GUID svc_iid_mouse_caps = {0xbca5e023, 0x7dd9, 0x4b6b,
                                        {0x9a, 0x92, 0x55, 0xd4, 0x3c, 0xb3, 0x8f, 0x73}};
static const GUID svc_iid_keyboard_caps = {0x3a3f9b56, 0x6798, 0x4bbc,
                                           {0x83, 0x3e, 0x0f, 0x34, 0xb1, 0x7c, 0x65, 0xff}};

static svc_object mouse_static;
static svc_object mouse_object;

static HRESULT mouse_static_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_mouse_statics, out);
}

static HRESULT mouse_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_mouse, out);
}

/* The registration succeeds and the token is handed back. Mouse input reaches
   the title through the runtime's input stack, not through this event. */
static HRESULT mouse_add_Moved(svc_object *self, void *handler, void *token)
{
    (void)self;
    (void)handler;
    if (token) *(long long *)token = 2;
    return S_OK;
}

static HRESULT mouse_remove_Moved(svc_object *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

static const void *mouse_vtable[] = {
    (const void *)mouse_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)mouse_add_Moved, (const void *)mouse_remove_Moved,
};

static HRESULT mouse_static_GetForCurrentView(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &mouse_object;
    svc_AddRef(&mouse_object);
    return S_OK;
}

static const void *mouse_static_vtable[] = {
    (const void *)mouse_static_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)mouse_static_GetForCurrentView,
};

static HRESULT mc_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_mouse_caps, out);
}

static HRESULT kc_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_keyboard_caps, out);
}

static HRESULT mc_MousePresent(svc_object *self, int *out)
{
    (void)self;
    if (out) *out = GetSystemMetrics(SM_MOUSEPRESENT) ? 1 : 0;
    return S_OK;
}

static HRESULT mc_SwapButtons(svc_object *self, int *out)
{
    (void)self;
    if (out) *out = GetSystemMetrics(SM_SWAPBUTTON) ? 1 : 0;
    return S_OK;
}

static HRESULT mc_NumberOfButtons(svc_object *self, unsigned *out)
{
    (void)self;
    if (out) *out = (unsigned)GetSystemMetrics(SM_CMOUSEBUTTONS);
    return S_OK;
}

static HRESULT mc_WheelPresent(svc_object *self, int *out)
{
    (void)self;
    if (out) *out = GetSystemMetrics(SM_MOUSEWHEELPRESENT) ? 1 : 0;
    return S_OK;
}

static HRESULT mc_WheelType(svc_object *self, int *out)
{
    (void)self;
    /* MouseWheelParameters::WheelType, 0 when the wheel is vertical. */
    if (out) *out = 0;
    return S_OK;
}

static const void *mouse_caps_vtable[] = {
    (const void *)mc_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)mc_MousePresent, (const void *)mc_SwapButtons, (const void *)mc_NumberOfButtons,
    (const void *)mc_WheelPresent, (const void *)mc_WheelType,
};

static HRESULT kc_KeyboardPresent(svc_object *self, int *out)
{
    (void)self;
    if (out) *out = GetKeyboardType(0) != 0 ? 1 : 0;
    return S_OK;
}

static const void *keyboard_caps_vtable[] = {
    (const void *)kc_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)kc_KeyboardPresent,
};

/* ------------------------------------------------------ memory manager */

/* Windows.System.MemoryManager tells a packaged title how much memory the app
   is using and how much it may grow into. A title reads it while sizing its
   streaming and texture budgets, so the numbers are the ones Windows reports:
   the process working set and commit against the machine's commit limit. */
static const GUID svc_iid_memory_statics = {0x5c6c279c, 0xd7ca, 0x4779,
                                            {0x91, 0x88, 0x40, 0x57, 0x21, 0x9c, 0xe6, 0x4c}};
/* The second statics interface carries the memory reports, which is the one a
   title asks for first. */
static const GUID svc_iid_memory_statics2 = {0x6eee351f, 0x6d62, 0x423f,
                                             {0x94, 0x79, 0xb0, 0x1f, 0x9c, 0x9f, 0x76, 0x69}};

typedef struct {
    DWORD cb;
    DWORD page_faults;
    SIZE_T peak_working_set;
    SIZE_T working_set;
    SIZE_T quota_peak_paged;
    SIZE_T quota_paged;
    SIZE_T quota_peak_nonpaged;
    SIZE_T quota_nonpaged;
    SIZE_T pagefile;
    SIZE_T peak_pagefile;
} process_memory_counters;

extern BOOL WINAPI K32GetProcessMemoryInfo(HANDLE, process_memory_counters *, DWORD);

static svc_object memory_static;
static svc_object memory_static2;
static svc_object memory_report;

static process_memory_counters memory_counters(void)
{
    process_memory_counters counters = {0};
    counters.cb = sizeof counters;
    K32GetProcessMemoryInfo(GetCurrentProcess(), &counters, sizeof counters);
    return counters;
}

static unsigned long long memory_app_usage(void)
{
    return (unsigned long long)memory_counters().working_set;
}

static unsigned long long memory_app_limit(void)
{
    MEMORYSTATUSEX status;
    static int reported;
    status.dwLength = sizeof status;
    if (!GlobalMemoryStatusEx(&status)) return 0;
    /* Logged once per process: a title's budget is only meaningful next to the
       machine's physical and commit totals, and those differ sharply between a
       Windows PC and this runtime. */
    if (!reported) {
        reported = 1;
        svc_trace_u64("memory system physical", status.ullTotalPhys);
        svc_trace_u64("memory system available", status.ullAvailPhys);
        svc_trace_u64("memory system commit limit", status.ullTotalPageFile);
        svc_trace_u64("memory system commit available", status.ullAvailPageFile);
        svc_trace_u64("memory process working set", (unsigned long long)memory_counters().working_set);
        svc_trace_u64("memory process commit", (unsigned long long)memory_counters().pagefile);
    }
    return status.ullTotalPhys;
}

static HRESULT mr_QI(void *self, const GUID *id, void **out)
{
    if (!IsEqualIID(id, &svc_iid_unknown) && !IsEqualIID(id, &svc_iid_inspectable) &&
        !IsEqualIID(id, &svc_iid_agile_object))
        svc_trace_iid("memory report QI", id);
    if (IsEqualIID(id, &svc_iid_unknown) || IsEqualIID(id, &svc_iid_inspectable) ||
        IsEqualIID(id, &svc_iid_agile_object)) {
        if (!out) return E_POINTER;
        *out = self;
        svc_AddRef((svc_object *)self);
        return S_OK;
    }
    if (out) *out = NULL;
    return E_NOINTERFACE;
}

/* IAppMemoryReport order, from the Windows metadata: private commit usage,
   peak private commit usage, total commit usage, total commit limit. */
static HRESULT mr_PrivateCommitUsage(svc_object *self, unsigned long long *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = (unsigned long long)memory_counters().pagefile;
    svc_trace_u64("memory report private", *out);
    return S_OK;
}

static HRESULT mr_PeakPrivateCommitUsage(svc_object *self, unsigned long long *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = (unsigned long long)memory_counters().peak_pagefile;
    svc_trace_u64("memory report peak private", *out);
    return S_OK;
}

static HRESULT mr_TotalCommitUsage(svc_object *self, unsigned long long *out)
{
    MEMORYSTATUSEX status;
    (void)self;
    if (!out) return E_POINTER;
    status.dwLength = sizeof status;
    if (!GlobalMemoryStatusEx(&status)) {
        *out = 0;
        return S_OK;
    }
    *out = (unsigned long long)(status.ullTotalPageFile - status.ullAvailPageFile);
    svc_trace_u64("memory report total usage", *out);
    return S_OK;
}

static HRESULT mr_TotalCommitLimit(svc_object *self, unsigned long long *out)
{
    MEMORYSTATUSEX status;
    (void)self;
    if (!out) return E_POINTER;
    status.dwLength = sizeof status;
    *out = GlobalMemoryStatusEx(&status) ? status.ullTotalPageFile : 0;
    svc_trace_u64("memory report limit", *out);
    return S_OK;
}

static const void *memory_report_vtable[] = {
    (const void *)mr_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)mr_PrivateCommitUsage, (const void *)mr_PeakPrivateCommitUsage,
    (const void *)mr_TotalCommitUsage, (const void *)mr_TotalCommitLimit,
};

static svc_object process_report;

/* IProcessMemoryReport: the title's own working set and the working set of
   everything it launched. The title is the only process this runtime starts on
   its behalf, so both report the same set. */
static HRESULT pr_PrivateWorkingSetUsage(svc_object *self, unsigned long long *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = (unsigned long long)memory_counters().working_set;
    svc_trace_u64("process report private", *out);
    return S_OK;
}

static HRESULT pr_TotalWorkingSetUsage(svc_object *self, unsigned long long *out)
{
    return pr_PrivateWorkingSetUsage(self, out);
}

static const void *process_report_vtable[] = {
    (const void *)mr_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)pr_PrivateWorkingSetUsage, (const void *)pr_TotalWorkingSetUsage,
};

static HRESULT ms2_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_memory_statics2, out);
}

static HRESULT ms2_GetAppMemoryReport(svc_object *self, void **out)
{
    (void)self;
    svc_trace("memory: get app report");
    if (!out) return E_POINTER;
    *out = &memory_report;
    svc_AddRef(&memory_report);
    return S_OK;
}

static HRESULT ms2_GetProcessMemoryReport(svc_object *self, void **out)
{
    (void)self;
    svc_trace("memory: get process report");
    if (!out) return E_POINTER;
    *out = &process_report;
    svc_AddRef(&process_report);
    return S_OK;
}

/* IMemoryManagerStatics2 order: the app report, then the process report. */
static const void *memory_static2_vtable[] = {
    (const void *)ms2_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)ms2_GetAppMemoryReport, (const void *)ms2_GetProcessMemoryReport,
};

static HRESULT mm_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_memory_statics2)) {
        *out = &memory_static2;
        svc_AddRef(&memory_static2);
        return S_OK;
    }
    return simple_QI(self, id, &svc_iid_memory_statics, out);
}

static HRESULT mm_AppMemoryUsage(svc_object *self, unsigned long long *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = memory_app_usage();
    svc_trace_u64("memory app usage", *out);
    return S_OK;
}

static HRESULT mm_AppMemoryUsageLimit(svc_object *self, unsigned long long *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = memory_app_limit();
    svc_trace_u64("memory app limit", *out);
    return S_OK;
}

static HRESULT mm_AppMemoryUsageLevel(svc_object *self, int *out)
{
    unsigned long long usage = memory_app_usage();
    unsigned long long limit = memory_app_limit();
    (void)self;
    if (!out) return E_POINTER;
    /* AppMemoryUsageLevel: 0 low, 1 medium, 2 high. Windows raises it at 70%
       and 90% of the limit. */
    if (!limit) *out = 0;
    else if (usage * 10 >= limit * 9) *out = 2;
    else if (usage * 10 >= limit * 7) *out = 1;
    else *out = 0;
    return S_OK;
}

static HRESULT mm_add_event(svc_object *self, void *handler, void *token)
{
    (void)self;
    (void)handler;
    if (token) *(long long *)token = 1;
    return S_OK;
}

static HRESULT mm_remove_event(svc_object *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

/* IMemoryManagerStatics order: usage, limit, level, then the three change
   events, each an add/remove pair. */
static const void *memory_static_vtable[] = {
    (const void *)mm_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)mm_AppMemoryUsage, (const void *)mm_AppMemoryUsageLimit,
    (const void *)mm_AppMemoryUsageLevel,
    (const void *)mm_add_event, (const void *)mm_remove_event,
    (const void *)mm_add_event, (const void *)mm_remove_event,
    (const void *)mm_add_event, (const void *)mm_remove_event,
};

/* ------------------------------------------------------ application view */

/* A packaged title asks Windows for the view it is already running in and reads
   back what that view is: fullscreen, which way round, how much of the screen
   it owns. CoreWindow already owns the window here, so the answer comes from
   that window instead of from a desktop window title bar that does not exist.
   Wine's twinapi.appcore answers this class with E_NOTIMPL, which a title that
   queries it as it comes up reads as "this Windows cannot run me". */
static const GUID svc_iid_appview_statics2 = {0xaf338ae5, 0xcf64, 0x423c,
                                              {0x85, 0xe5, 0xf3, 0xe7, 0x24, 0x48, 0xfb, 0x23}};
static const GUID svc_iid_appview_statics3 = {0xa28d7594, 0x8c41, 0x4e13,
                                              {0x97, 0x19, 0x51, 0x64, 0x79, 0x6f, 0xe4, 0xc7}};
static const GUID svc_iid_appview_fullscreen = {0xbc792ebd, 0x64fe, 0x4b65,
                                                {0xa0, 0xc0, 0x90, 0x1c, 0xe2, 0xb6, 0x86, 0x36}};
static const GUID svc_iid_appview = {0xd222d519, 0x4361, 0x451e,
                                     {0x96, 0xc4, 0x60, 0xf4, 0xf9, 0x74, 0x2d, 0xb0}};
static const GUID svc_iid_appview2 = {0xe876b196, 0xa545, 0x40dc,
                                      {0xb5, 0x94, 0x45, 0x0c, 0xba, 0x68, 0xcc, 0x00}};
static const GUID svc_iid_appview3 = {0x903c9ce5, 0x793a, 0x4fdf,
                                      {0xa2, 0xb2, 0xaf, 0x1a, 0xc2, 0x1e, 0x31, 0x08}};
static const GUID svc_iid_appview_titlebar = {0x00924ac0, 0x932b, 0x4a6b,
                                              {0x9c, 0x4b, 0xdc, 0x38, 0xc8, 0x24, 0x78, 0xce}};

static svc_object appview_static;
static svc_object appview_static3;
static svc_object appview_fullscreen;
static svc_object appview_object;
static svc_object appview2_object;
static svc_object appview3_object;
static svc_object appview_titlebar;

/* What the title set, kept the way Windows keeps it: a title that colours its
   title bar and reads the colour back sees the value it set. The handheld view
   starts fullscreen and landscape, because that is the window it was given. */
static int appview_fullscreen_on = 1;
static int appview_suppress_overlays;
static int appview_capture_enabled = 1;
static int appview_overlay_mode;
static int appview_windowing_mode;
static float appview_launch_size[2];
static float appview_min_size[2];
static WCHAR appview_title_text[128];
static void *appview_colors[12];

typedef struct {
    float width;
    float height;
} svc_size;

static ULONG __stdcall svc_ref(void *object)
{
    return ((ULONG(__stdcall *)(void *))(*(const void ***)object)[1])(object);
}

static ULONG __stdcall svc_unref(void *object)
{
    return ((ULONG(__stdcall *)(void *))(*(const void ***)object)[2])(object);
}

/* Windows.Foundation.Rect: X, Y, Width, Height, in DIPs. */
static void appview_bounds(float *rect)
{
    float scale = (float)display_dpi() / 96.0f;
    rect[0] = 0.0f;
    rect[1] = 0.0f;
    rect[2] = (float)GetSystemMetrics(SM_CXSCREEN) / scale;
    rect[3] = (float)GetSystemMetrics(SM_CYSCREEN) / scale;
}

static HRESULT av_statics_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_appview_statics3)) {
        *out = &appview_static3;
        svc_AddRef(&appview_static3);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_appview_fullscreen)) {
        *out = &appview_fullscreen;
        svc_AddRef(&appview_fullscreen);
        return S_OK;
    }
    return simple_QI(self, id, &svc_iid_appview_statics2, out);
}

static HRESULT avs2_GetForCurrentView(svc_object *self, void **out)
{
    static int traced;
    (void)self;
    /* A title polls this from its frame loop, so it is traced once: a trace
       line per call would put a file write in the middle of every frame. */
    if (!traced) {
        traced = 1;
        svc_trace("appview get for current view");
    }
    if (!out) return E_POINTER;
    *out = &appview_object;
    svc_AddRef(&appview_object);
    return S_OK;
}

static HRESULT avs2_TerminateOnClose(svc_object *self, int *out)
{
    (void)self;
    svc_trace("appview read terminate on close");
    if (out) *out = 0;
    return S_OK;
}

static HRESULT avs2_put_TerminateOnClose(svc_object *self, int value)
{
    (void)self;
    svc_tracef("appview set terminate on close %d", value);
    (void)value;
    return S_OK;
}

/* IApplicationViewStatics2 order: the current view, then
   TerminateAppOnFinalViewClose, a get and a set. */
static const void *appview_statics_vtable[] = {
    (const void *)av_statics_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)avs2_GetForCurrentView, (const void *)avs2_TerminateOnClose,
    (const void *)avs2_put_TerminateOnClose,
};

static HRESULT avs3_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_appview_statics2)) {
        *out = &appview_static;
        svc_AddRef(&appview_static);
        return S_OK;
    }
    return simple_QI(self, id, &svc_iid_appview_statics3, out);
}

static HRESULT avs3_LaunchMode(svc_object *self, int *out)
{
    (void)self;
    svc_trace("appview read launch mode");
    if (out) *out = appview_windowing_mode;
    return S_OK;
}

static HRESULT avs3_put_LaunchMode(svc_object *self, int value)
{
    (void)self;
    appview_windowing_mode = value;
    return S_OK;
}

static HRESULT avs3_LaunchSize(svc_object *self, float *out)
{
    float scale;
    (void)self;
    svc_trace("appview read launch size");
    if (!out) return E_POINTER;
    if (!appview_launch_size[0] || !appview_launch_size[1]) {
        scale = (float)display_dpi() / 96.0f;
        appview_launch_size[0] = (float)GetSystemMetrics(SM_CXSCREEN) / scale;
        appview_launch_size[1] = (float)GetSystemMetrics(SM_CYSCREEN) / scale;
    }
    out[0] = appview_launch_size[0];
    out[1] = appview_launch_size[1];
    return S_OK;
}

static HRESULT avs3_put_LaunchSize(svc_object *self, svc_size size)
{
    (void)self;
    appview_launch_size[0] = size.width;
    appview_launch_size[1] = size.height;
    return S_OK;
}

/* IApplicationViewStatics3 order: launch windowing mode, then launch size. */
static const void *appview_statics3_vtable[] = {
    (const void *)avs3_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)avs3_LaunchMode, (const void *)avs3_put_LaunchMode,
    (const void *)avs3_LaunchSize, (const void *)avs3_put_LaunchSize,
};

static HRESULT avfs_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_appview_statics2)) {
        *out = &appview_static;
        svc_AddRef(&appview_static);
        return S_OK;
    }
    return simple_QI(self, id, &svc_iid_appview_fullscreen, out);
}

static HRESULT avfs_Unsnap(svc_object *self, int *out)
{
    (void)self;
    if (out) *out = appview_fullscreen_on;
    return S_OK;
}

/* IApplicationViewFullscreenStatics: TryUnsnapToFullscreen. */
static const void *appview_fullscreen_vtable[] = {
    (const void *)avfs_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)avfs_Unsnap,
};

static HRESULT av_QI(void *self, const GUID *id, void **out)
{
    /* The view is polled from the title's frame loop, so a miss is reported by
       the first few distinct interfaces asked for rather than per call. */
    static GUID missing[8];
    static int missing_count;
    int i;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_appview2)) {
        *out = &appview2_object;
        svc_AddRef(&appview2_object);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_appview3)) {
        *out = &appview3_object;
        svc_AddRef(&appview3_object);
        return S_OK;
    }
    if (!IsEqualIID(id, &svc_iid_appview) && !IsEqualIID(id, &svc_iid_unknown) &&
        !IsEqualIID(id, &svc_iid_inspectable) && !IsEqualIID(id, &svc_iid_agile_object)) {
        for (i = 0; i < missing_count; i++)
            if (IsEqualIID(id, &missing[i])) break;
        if (i == missing_count && missing_count < 8) {
            missing[missing_count++] = *id;
            svc_trace_iid("appview QI miss", id);
        }
    }
    return simple_QI(self, id, &svc_iid_appview, out);
}

static HRESULT av_Orientation(svc_object *self, int *out)
{
    (void)self;
    svc_trace("appview read orientation");
    if (!out) return E_POINTER;
    /* ApplicationViewOrientation: 0 landscape, 1 portrait. */
    *out = GetSystemMetrics(SM_CXSCREEN) >= GetSystemMetrics(SM_CYSCREEN) ? 0 : 1;
    return S_OK;
}

static HRESULT av_AdjacentToLeft(svc_object *self, unsigned char *out)
{
    (void)self;
    svc_trace("appview poll adjacent left");
    if (out) *out = 1;
    return S_OK;
}

static HRESULT av_AdjacentToRight(svc_object *self, unsigned char *out)
{
    (void)self;
    svc_trace("appview poll adjacent right");
    if (out) *out = 1;
    return S_OK;
}

static HRESULT av_IsFullScreen(svc_object *self, unsigned char *out)
{
    (void)self;
    svc_trace("appview read fullscreen");
    if (out) *out = appview_fullscreen_on ? 1 : 0;
    return S_OK;
}

static HRESULT av_IsOnLockScreen(svc_object *self, unsigned char *out)
{
    (void)self;
    svc_trace("appview poll lock screen");
    if (out) *out = 0;
    return S_OK;
}

static HRESULT av_CaptureEnabled(svc_object *self, unsigned char *out)
{
    (void)self;
    svc_trace("appview poll capture enabled");
    if (out) *out = appview_capture_enabled ? 1 : 0;
    return S_OK;
}

static HRESULT av_put_CaptureEnabled(svc_object *self, unsigned char value)
{
    (void)self;
    appview_capture_enabled = value ? 1 : 0;
    return S_OK;
}

static HRESULT av_put_Title(svc_object *self, hstring_t value)
{
    const WCHAR *raw;
    int i;
    (void)self;
    raw = svc_string_text(value);
    for (i = 0; i < 127 && raw && raw[i]; i++) appview_title_text[i] = raw[i];
    appview_title_text[i] = 0;
    return S_OK;
}

static HRESULT av_Title(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = svc_string(appview_title_text);
    return S_OK;
}

static HRESULT av_Id(svc_object *self, int *out)
{
    (void)self;
    svc_trace("appview read id");
    if (out) *out = 1;
    return S_OK;
}

static HRESULT av_add_Consolidated(svc_object *self, void *handler, void *token)
{
    (void)self;
    svc_trace("appview add consolidated");
    (void)handler;
    if (token) *(long long *)token = 1;
    return S_OK;
}

static HRESULT av_remove_Consolidated(svc_object *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

/* IApplicationView order: orientation, the two display edges, fullscreen, lock
   screen, screen capture (get and set), title (set then get), id, then the
   consolidated event. */
static const void *appview_vtable[] = {
    (const void *)av_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)av_Orientation,
    (const void *)av_AdjacentToLeft,
    (const void *)av_AdjacentToRight,
    (const void *)av_IsFullScreen,
    (const void *)av_IsOnLockScreen,
    (const void *)av_CaptureEnabled,
    (const void *)av_put_CaptureEnabled,
    (const void *)av_put_Title,
    (const void *)av_Title,
    (const void *)av_Id,
    (const void *)av_add_Consolidated,
    (const void *)av_remove_Consolidated,
};

static HRESULT av2_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_appview)) {
        *out = &appview_object;
        svc_AddRef(&appview_object);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_appview3)) {
        *out = &appview3_object;
        svc_AddRef(&appview3_object);
        return S_OK;
    }
    return simple_QI(self, id, &svc_iid_appview2, out);
}

static HRESULT av2_SuppressOverlays(svc_object *self, unsigned char *out)
{
    (void)self;
    svc_trace("appview read suppress overlays");
    if (out) *out = appview_suppress_overlays ? 1 : 0;
    return S_OK;
}

static HRESULT av2_put_SuppressOverlays(svc_object *self, unsigned char value)
{
    (void)self;
    svc_tracef("appview set suppress overlays %d", (int)value);
    appview_suppress_overlays = value ? 1 : 0;
    return S_OK;
}

static HRESULT av2_VisibleBounds(svc_object *self, float *out)
{
    (void)self;
    svc_trace("appview read visible bounds");
    if (!out) return E_POINTER;
    appview_bounds(out);
    return S_OK;
}

static HRESULT av2_add_BoundsChanged(svc_object *self, void *handler, void *token)
{
    (void)self;
    svc_trace("appview add bounds changed");
    (void)handler;
    if (token) *(long long *)token = 1;
    return S_OK;
}

static HRESULT av2_remove_BoundsChanged(svc_object *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

static HRESULT av2_SetDesiredBoundsMode(svc_object *self, int mode, unsigned char *success)
{
    (void)self;
    svc_tracef("appview set bounds mode %d", mode);
    (void)mode;
    if (success) *success = 1;
    return S_OK;
}

static HRESULT av2_DesiredBoundsMode(svc_object *self, int *out)
{
    (void)self;
    svc_trace("appview poll bounds mode");
    /* ApplicationViewBoundsMode: 0 use visible bounds, 1 use the CoreWindow. */
    if (out) *out = 1;
    return S_OK;
}

/* IApplicationView2 order: system overlays (get and set), visible bounds, the
   bounds-changed event, desired bounds mode (set then get). */
static const void *appview2_vtable[] = {
    (const void *)av2_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)av2_SuppressOverlays,
    (const void *)av2_put_SuppressOverlays,
    (const void *)av2_VisibleBounds,
    (const void *)av2_add_BoundsChanged,
    (const void *)av2_remove_BoundsChanged,
    (const void *)av2_SetDesiredBoundsMode,
    (const void *)av2_DesiredBoundsMode,
};

static HRESULT av3_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_appview)) {
        *out = &appview_object;
        svc_AddRef(&appview_object);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_appview2)) {
        *out = &appview2_object;
        svc_AddRef(&appview2_object);
        return S_OK;
    }
    return simple_QI(self, id, &svc_iid_appview3, out);
}

static HRESULT av3_TitleBar(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &appview_titlebar;
    svc_AddRef(&appview_titlebar);
    return S_OK;
}

static HRESULT av3_OverlayMode(svc_object *self, int *out)
{
    (void)self;
    svc_trace("appview poll overlay mode");
    /* FullScreenSystemOverlayMode: 0 default, 1 minimal, 2 standard. */
    if (out) *out = appview_overlay_mode;
    return S_OK;
}

static HRESULT av3_put_OverlayMode(svc_object *self, int value)
{
    (void)self;
    appview_overlay_mode = value;
    return S_OK;
}

static HRESULT av3_IsFullScreenMode(svc_object *self, unsigned char *out)
{
    (void)self;
    if (out) *out = appview_fullscreen_on ? 1 : 0;
    return S_OK;
}

static HRESULT av3_TryEnterFullScreen(svc_object *self, unsigned char *success)
{
    (void)self;
    svc_trace("appview enter fullscreen");
    appview_fullscreen_on = 1;
    if (success) *success = 1;
    return S_OK;
}

static HRESULT av3_ExitFullScreen(svc_object *self)
{
    (void)self;
    svc_trace("appview exit fullscreen");
    appview_fullscreen_on = 0;
    return S_OK;
}

static HRESULT av3_ShowOverlays(svc_object *self)
{
    (void)self;
    svc_trace("appview show overlays");
    return S_OK;
}

static HRESULT av3_TryResizeView(svc_object *self, svc_size size, unsigned char *success)
{
    (void)self;
    svc_tracef("appview try resize %ldx%ld", (long)size.width, (long)size.height);
    /* A fullscreen handheld view has no window to resize. */
    if (success) *success = 0;
    return S_OK;
}

static HRESULT av3_SetPreferredMinSize(svc_object *self, svc_size size)
{
    (void)self;
    svc_tracef("appview min size %ldx%ld", (long)size.width, (long)size.height);
    appview_min_size[0] = size.width;
    appview_min_size[1] = size.height;
    return S_OK;
}

/* IApplicationView3 order: title bar, fullscreen overlay mode (get and set),
   fullscreen mode, enter fullscreen, exit fullscreen, show overlays, try to
   resize, preferred minimum size. */
static const void *appview3_vtable[] = {
    (const void *)av3_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)av3_TitleBar,
    (const void *)av3_OverlayMode,
    (const void *)av3_put_OverlayMode,
    (const void *)av3_IsFullScreenMode,
    (const void *)av3_TryEnterFullScreen,
    (const void *)av3_ExitFullScreen,
    (const void *)av3_ShowOverlays,
    (const void *)av3_TryResizeView,
    (const void *)av3_SetPreferredMinSize,
};

/* An IReference<Color> handed to a title bar property: the host keeps the one
   the title set and hands the same one back, which is what Windows does. */
static HRESULT avt_put_color(int slot, void *value)
{
    void *old = appview_colors[slot];
    appview_colors[slot] = value;
    if (value) svc_ref(value);
    if (old) svc_unref(old);
    return S_OK;
}

static HRESULT avt_get_color(int slot, void **out)
{
    if (!out) return E_POINTER;
    *out = appview_colors[slot];
    if (*out) svc_ref(*out);
    return S_OK;
}

static HRESULT avt_QI(void *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_appview_titlebar, out);
}

#define APPVIEW_COLOR_PAIR(name, slot)                                                       \
    static HRESULT avt_put_##name(svc_object *self, void *value)                             \
    {                                                                                        \
        (void)self;                                                                          \
        return avt_put_color(slot, value);                                                   \
    }                                                                                        \
    static HRESULT avt_get_##name(svc_object *self, void **out)                              \
    {                                                                                        \
        (void)self;                                                                          \
        return avt_get_color(slot, out);                                                     \
    }

APPVIEW_COLOR_PAIR(Foreground, 0)
APPVIEW_COLOR_PAIR(Background, 1)
APPVIEW_COLOR_PAIR(ButtonForeground, 2)
APPVIEW_COLOR_PAIR(ButtonBackground, 3)
APPVIEW_COLOR_PAIR(ButtonHoverForeground, 4)
APPVIEW_COLOR_PAIR(ButtonHoverBackground, 5)
APPVIEW_COLOR_PAIR(ButtonPressedForeground, 6)
APPVIEW_COLOR_PAIR(ButtonPressedBackground, 7)
APPVIEW_COLOR_PAIR(InactiveForeground, 8)
APPVIEW_COLOR_PAIR(InactiveBackground, 9)
APPVIEW_COLOR_PAIR(ButtonInactiveForeground, 10)
APPVIEW_COLOR_PAIR(ButtonInactiveBackground, 11)
#undef APPVIEW_COLOR_PAIR

/* IApplicationViewTitleBar order: each colour is a set then a get. */
static const void *appview_titlebar_vtable[] = {
    (const void *)avt_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)avt_put_Foreground, (const void *)avt_get_Foreground,
    (const void *)avt_put_Background, (const void *)avt_get_Background,
    (const void *)avt_put_ButtonForeground, (const void *)avt_get_ButtonForeground,
    (const void *)avt_put_ButtonBackground, (const void *)avt_get_ButtonBackground,
    (const void *)avt_put_ButtonHoverForeground, (const void *)avt_get_ButtonHoverForeground,
    (const void *)avt_put_ButtonHoverBackground, (const void *)avt_get_ButtonHoverBackground,
    (const void *)avt_put_ButtonPressedForeground, (const void *)avt_get_ButtonPressedForeground,
    (const void *)avt_put_ButtonPressedBackground, (const void *)avt_get_ButtonPressedBackground,
    (const void *)avt_put_InactiveForeground, (const void *)avt_get_InactiveForeground,
    (const void *)avt_put_InactiveBackground, (const void *)avt_get_InactiveBackground,
    (const void *)avt_put_ButtonInactiveForeground, (const void *)avt_get_ButtonInactiveForeground,
    (const void *)avt_put_ButtonInactiveBackground, (const void *)avt_get_ButtonInactiveBackground,
};

/* --------------------------------------------------------- MessageDialog */

static const GUID svc_iid_message_dialog_factory = {0x2d161777, 0xa66f, 0x4ea5,
                                                    {0xbb, 0x87, 0x79, 0x3f, 0xfa, 0x49, 0x41, 0xf2}};
static const GUID svc_iid_message_dialog = {0x33f59b01, 0x5325, 0x43ab,
                                            {0x9a, 0xb3, 0xbd, 0xae, 0x44, 0x0e, 0x41, 0x21}};

typedef struct {
    svc_object base;
    WCHAR *title;
    WCHAR *content;
    unsigned default_index;
    unsigned cancel_index;
    int options;
} message_dialog;

static svc_object message_dialog_static;
static const void *message_dialog_vtable[];

static WCHAR *dialog_copy(hstring_t text)
{
    const WCHAR *source = svc_string_text(text);
    size_t length = source ? wcslen(source) : 0;
    WCHAR *copy = (WCHAR *)calloc(length + 1, sizeof(WCHAR));
    if (copy && length) memcpy(copy, source, length * sizeof(WCHAR));
    return copy;
}

static HRESULT dialog_QI(message_dialog *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_message_dialog) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object)) {
        *out = self;
        svc_AddRef(&self->base);
        return S_OK;
    }
    svc_trace_iid("message dialog QI", id);
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG dialog_Release(message_dialog *self)
{
    LONG left = InterlockedDecrement(&self->base.refs);
    if (left <= 0) {
        free(self->title);
        free(self->content);
        free(self);
    }
    return (ULONG)left;
}

static HRESULT dialog_get_Title(message_dialog *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = svc_string(self->title);
    return S_OK;
}

static HRESULT dialog_put_Title(message_dialog *self, hstring_t value)
{
    free(self->title);
    self->title = dialog_copy(value);
    svc_trace_w("message dialog title", self->title);
    return S_OK;
}

static HRESULT dialog_get_Content(message_dialog *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = svc_string(self->content);
    return S_OK;
}

static HRESULT dialog_put_Content(message_dialog *self, hstring_t value)
{
    free(self->content);
    self->content = dialog_copy(value);
    svc_trace_w("message dialog content", self->content);
    return S_OK;
}

static HRESULT dialog_get_DefaultCommandIndex(message_dialog *self, unsigned *out)
{
    if (!out) return E_POINTER;
    *out = self->default_index;
    return S_OK;
}

static HRESULT dialog_put_DefaultCommandIndex(message_dialog *self, unsigned value)
{
    self->default_index = value;
    return S_OK;
}

static HRESULT dialog_get_CancelCommandIndex(message_dialog *self, unsigned *out)
{
    if (!out) return E_POINTER;
    *out = self->cancel_index;
    return S_OK;
}

static HRESULT dialog_put_CancelCommandIndex(message_dialog *self, unsigned value)
{
    self->cancel_index = value;
    return S_OK;
}

static HRESULT dialog_get_Options(message_dialog *self, int *out)
{
    if (!out) return E_POINTER;
    *out = self->options;
    return S_OK;
}

static HRESULT dialog_put_Options(message_dialog *self, int value)
{
    self->options = value;
    return S_OK;
}

typedef struct {
    message_dialog *dialog;
    async_object *operation;
    WCHAR *title;
    WCHAR *content;
} dialog_show;

/* A dialog with no commands of its own shows a single Close button, and
   ShowAsync completes when the user dismisses it. */
static DWORD WINAPI dialog_show_thread(void *parameter)
{
    dialog_show *show = (dialog_show *)parameter;
    MessageBoxW(NULL, show->content ? show->content : L"", show->title ? show->title : L"",
                MB_OK | MB_TOPMOST);
    svc_trace("message dialog dismissed");
    svc_action_finish(show->operation, S_OK);
    async_Release(show->operation);
    dialog_Release(show->dialog);
    free(show->title);
    free(show->content);
    free(show);
    return 0;
}

static HRESULT dialog_ShowAsync(message_dialog *self, void **out)
{
    dialog_show *show;
    async_object *op;
    HANDLE thread;
    if (!out) return E_POINTER;
    *out = NULL;
    show = (dialog_show *)calloc(1, sizeof(dialog_show));
    if (!show) return E_OUTOFMEMORY;
    op = async_new_iid(1, &svc_iid_async_uicommand, S_OK, NULL);
    if (!op) {
        free(show);
        return E_OUTOFMEMORY;
    }
    show->title = _wcsdup(self->title ? self->title : L"");
    show->content = _wcsdup(self->content ? self->content : L"");
    if (!show->title || !show->content) {
        free(show->title);
        free(show->content);
        async_Release(op);
        free(show);
        return E_OUTOFMEMORY;
    }
    op->status = ASYNC_STARTED;
    show->operation = op;
    show->dialog = self;
    svc_AddRef(&self->base);
    svc_AddRef(&op->base);
    svc_trace("message dialog show");
    thread = CreateThread(NULL, 0, dialog_show_thread, show, 0, NULL);
    if (!thread) {
        DWORD err = GetLastError();
        async_Release(op);
        async_Release(op);
        dialog_Release(self);
        free(show->title);
        free(show->content);
        free(show);
        return HRESULT_FROM_WIN32(err);
    }
    CloseHandle(thread);
    *out = op;
    return S_OK;
}

static const void *message_dialog_vtable[] = {
    (const void *)dialog_QI, (const void *)svc_AddRef, (const void *)dialog_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)dialog_get_Title, (const void *)dialog_put_Title,
    (const void *)svc_unimplemented, /* get_Commands */
    (const void *)dialog_get_DefaultCommandIndex, (const void *)dialog_put_DefaultCommandIndex,
    (const void *)dialog_get_CancelCommandIndex, (const void *)dialog_put_CancelCommandIndex,
    (const void *)dialog_get_Content, (const void *)dialog_put_Content,
    (const void *)dialog_ShowAsync,
    (const void *)dialog_get_Options, (const void *)dialog_put_Options,
};

static HRESULT dialog_create(hstring_t content, hstring_t title, void **out)
{
    message_dialog *dialog;
    if (!out) return E_POINTER;
    dialog = (message_dialog *)calloc(1, sizeof(message_dialog));
    if (!dialog) return E_OUTOFMEMORY;
    dialog->base.vtbl = message_dialog_vtable;
    dialog->base.refs = 1;
    dialog->base.name = "Windows.UI.Popups.MessageDialog";
    dialog->content = dialog_copy(content);
    dialog->title = dialog_copy(title);
    svc_trace_w("message dialog title", dialog->title);
    svc_trace_w("message dialog content", dialog->content);
    *out = dialog;
    return S_OK;
}

static HRESULT dialog_factory_QI(svc_object *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_message_dialog_factory)) {
        *out = self;
        svc_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT dialog_factory_Create(svc_object *self, hstring_t content, void **out)
{
    (void)self;
    return dialog_create(content, NULL, out);
}

static HRESULT dialog_factory_CreateWithTitle(svc_object *self, hstring_t content, hstring_t title,
                                              void **out)
{
    (void)self;
    return dialog_create(content, title, out);
}

static const void *message_dialog_factory_vtable[] = {
    (const void *)dialog_factory_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)dialog_factory_Create, (const void *)dialog_factory_CreateWithTitle,
};

/* ----------------------------------------------------- DeviceInformation */

/* Real audio endpoint enumeration backed by HKLM\...\MMDevices\Audio\{Render,Capture}
   so UWP audio engines (FMOD, XAudio2/WASAPI) discover the active Wine/DirectAudio
   endpoints through Windows.Devices.Enumeration.DeviceInformation::FindAllAsync /
   CreateFromIdAsync / CreateWatcher. Non-audio AQS queries fall through to Wine's
   windows.devices.enumeration.dll or return an empty collection. */

static const GUID svc_iid_device_info = {0xaba0fb95, 0x4398, 0x489d,
                                         {0x8e, 0x44, 0xe6, 0x13, 0x09, 0x27, 0x01, 0x1f}};
static const GUID svc_iid_device_statics = {0xc17f100e, 0x3a46, 0x4a78,
                                            {0x80, 0x13, 0x76, 0x9d, 0xc9, 0xb9, 0x73, 0x90}};
static const GUID svc_iid_device_statics2 = {0x493b4f34, 0xa84f, 0x45fd,
                                             {0x91, 0x67, 0x15, 0xd1, 0xcb, 0x1b, 0xd1, 0xf9}};
static const GUID svc_iid_device_watcher = {0xc9eab97d, 0x8f6b, 0x4f96,
                                            {0xa9, 0xf4, 0xab, 0xc8, 0x14, 0xe2, 0x22, 0x71}};
static const GUID svc_iid_device_vector = {0xe170688f, 0x3495, 0x5bf6,
                                           {0xaa, 0xb5, 0x9c, 0xac, 0x17, 0xe0, 0xf1, 0x0f}};
static const GUID svc_iid_device_iterable = {0xdd9f8a5d, 0xec98, 0x5f4b,
                                             {0xa3, 0xea, 0x9c, 0x8b, 0x5a, 0xd5, 0x3c, 0x4b}};
static const GUID svc_iid_device_iterator = {0x6f85d843, 0xe8ab, 0x5b46,
                                             {0x85, 0xd7, 0x32, 0x7c, 0x58, 0xd1, 0x87, 0x12}};
static const GUID svc_iid_device_props_map = {0xbb78502a, 0xf79d, 0x54fa,
                                              {0x92, 0xc9, 0x90, 0xc5, 0x03, 0x9f, 0xdf, 0x7e}};
static const GUID svc_iid_device_agile = {0x94ea2b94, 0xe9cc, 0x49e0,
                                          {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};

#ifndef E_BOUNDS
#define E_BOUNDS ((HRESULT)0x8000000BL)
#endif

static svc_object device_static;
static svc_object device_static1;
static svc_object device_static2;
static svc_object device_collection;
static svc_object device_collection_iterable;
static svc_object device_iterator;
static svc_object device_empty_props;

static void dev_dbg(const char *fmt, ...)
{
    static int enabled = -1;
    char buf[512];
    va_list ap;
    int n;
    if (enabled < 0) {
        char setting[8];
        DWORD len = GetEnvironmentVariableA("DROIDDECK_UWP_TRACE", setting, sizeof setting);
        enabled = (len > 0 && len < sizeof setting) ? 1 : 0;
    }
    if (!enabled) return;
    n = snprintf(buf, sizeof buf, "%04lx uwp-devenum: ", (unsigned long)GetCurrentThreadId());
    if (n < 0) n = 0;
    va_start(ap, fmt);
    vsnprintf(buf + n, sizeof buf - (size_t)n, fmt, ap);
    va_end(ap);
    OutputDebugStringA(buf);
}

static int wstr_contains_ci(const WCHAR *hay, const WCHAR *needle)
{
    size_t nlen;
    if (!hay || !needle) return 0;
    nlen = wcslen(needle);
    if (nlen == 0) return 1;
    for (; *hay; hay++) {
        size_t i;
        for (i = 0; i < nlen; i++) {
            WCHAR a = hay[i], b = needle[i];
            if (!a) return 0;
            if (a >= L'A' && a <= L'Z') a = (WCHAR)(a + 32);
            if (b >= L'A' && b <= L'Z') b = (WCHAR)(b + 32);
            if (a != b) break;
        }
        if (i == nlen) return 1;
    }
    return 0;
}

/* ---- Empty IMapView<HSTRING, IInspectable*> for IDeviceInformation::get_Properties ---- */
static HRESULT devprops_QI(svc_object *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_device_props_map) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT devprops_Lookup(svc_object *self, hstring_t key, void **out)
{
    (void)self; (void)key;
    if (out) *out = NULL;
    return E_BOUNDS;
}
static HRESULT devprops_get_Size(svc_object *self, unsigned *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}
static HRESULT devprops_HasKey(svc_object *self, hstring_t key, boolean *out)
{
    (void)self; (void)key;
    if (!out) return E_POINTER;
    *out = FALSE;
    return S_OK;
}
static HRESULT devprops_Split(svc_object *self, void **first, void **second)
{
    (void)self;
    if (first) *first = NULL;
    if (second) *second = NULL;
    return S_OK;
}
static const void *device_empty_props_vtable[] = {
    (const void *)devprops_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)devprops_Lookup, (const void *)devprops_get_Size,
    (const void *)devprops_HasKey, (const void *)devprops_Split,
};

/* ---- Heap-allocated IDeviceInformation ---- */
typedef struct {
    svc_object base;
    WCHAR id[256];
    WCHAR name[256];
    boolean is_enabled;
    boolean is_default;
} audio_devinfo;

static HRESULT audio_devinfo_QI(audio_devinfo *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_device_info) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(&self->base);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static ULONG audio_devinfo_Release(audio_devinfo *self)
{
    LONG left = InterlockedDecrement(&self->base.refs);
    if (left <= 0) free(self);
    return (ULONG)(left < 0 ? 0 : left);
}
static HRESULT audio_devinfo_get_Id(audio_devinfo *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = svc_string(self->id);
    return S_OK;
}
static HRESULT audio_devinfo_get_Name(audio_devinfo *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = svc_string(self->name);
    return S_OK;
}
static HRESULT audio_devinfo_get_IsEnabled(audio_devinfo *self, boolean *out)
{
    if (!out) return E_POINTER;
    *out = self->is_enabled;
    return S_OK;
}
static HRESULT audio_devinfo_get_IsDefault(audio_devinfo *self, boolean *out)
{
    if (!out) return E_POINTER;
    *out = self->is_default;
    return S_OK;
}
static HRESULT audio_devinfo_get_EnclosureLocation(audio_devinfo *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    return S_OK;
}
static HRESULT audio_devinfo_get_Properties(audio_devinfo *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (!device_empty_props.vtbl) {
        device_empty_props.vtbl = device_empty_props_vtable;
        device_empty_props.refs = 1000;
        device_empty_props.permanent = 1;
        device_empty_props.name = "Windows.Foundation.Collections.IMapView";
    }
    svc_AddRef(&device_empty_props);
    *out = &device_empty_props;
    return S_OK;
}
static HRESULT audio_devinfo_Update(audio_devinfo *self, void *upd)
{
    (void)self; (void)upd;
    return S_OK;
}
static HRESULT audio_devinfo_GetThumb(audio_devinfo *self, void **out)
{
    (void)self;
    if (out) *out = NULL;
    return E_NOTIMPL;
}
static const void *audio_devinfo_vtable[] = {
    (const void *)audio_devinfo_QI, (const void *)svc_AddRef, (const void *)audio_devinfo_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)audio_devinfo_get_Id, (const void *)audio_devinfo_get_Name,
    (const void *)audio_devinfo_get_IsEnabled, (const void *)audio_devinfo_get_IsDefault,
    (const void *)audio_devinfo_get_EnclosureLocation, (const void *)audio_devinfo_get_Properties,
    (const void *)audio_devinfo_Update, (const void *)audio_devinfo_GetThumb,
    (const void *)audio_devinfo_GetThumb,
};

static audio_devinfo *audio_devinfo_new(const WCHAR *id, const WCHAR *name, boolean enabled, boolean is_def)
{
    audio_devinfo *obj = (audio_devinfo *)calloc(1, sizeof *obj);
    if (!obj) return NULL;
    obj->base.vtbl = audio_devinfo_vtable;
    obj->base.refs = 1;
    obj->base.name = "Windows.Devices.Enumeration.DeviceInformation";
    lstrcpynW(obj->id, id ? id : L"", 256);
    lstrcpynW(obj->name, name ? name : L"Audio Device", 256);
    obj->is_enabled = enabled;
    obj->is_default = is_def;
    return obj;
}

/* ---- Heap-allocated DeviceInformationCollection (IVectorView + IIterable) & IIterator ---- */
#define MAX_AUDIO_DEVS 16

typedef struct audio_devcoll {
    svc_object base;
    svc_object iterable;
    audio_devinfo *items[MAX_AUDIO_DEVS];
    unsigned count;
} audio_devcoll;

typedef struct {
    svc_object base;
    audio_devcoll *coll;
    unsigned index;
} audio_deviter;

static const void *audio_devcoll_vtable[];
static const void *audio_devcoll_iterable_vtable[];
static const void *audio_deviter_vtable[];

static audio_devcoll *audio_devcoll_from(void *self)
{
    svc_object *h = (svc_object *)self;
    if (h && h->vtbl == audio_devcoll_iterable_vtable)
        return (audio_devcoll *)((char *)self - offsetof(audio_devcoll, iterable));
    return (audio_devcoll *)self;
}
static ULONG audio_devcoll_AddRef(void *self)
{
    return svc_AddRef(&audio_devcoll_from(self)->base);
}
static ULONG audio_devcoll_Release(void *self)
{
    audio_devcoll *c = audio_devcoll_from(self);
    LONG left = InterlockedDecrement(&c->base.refs);
    if (left <= 0) {
        unsigned i;
        for (i = 0; i < c->count; i++)
            if (c->items[i]) audio_devinfo_Release(c->items[i]);
        free(c);
    }
    return (ULONG)(left < 0 ? 0 : left);
}
static HRESULT audio_devcoll_QI(void *self, const GUID *id, void **out)
{
    audio_devcoll *c = audio_devcoll_from(self);
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_device_vector) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = &c->base;
        svc_AddRef(&c->base);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_device_iterable)) {
        *out = &c->iterable;
        svc_AddRef(&c->base);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT audio_devcoll_GetAt(void *self, unsigned index, void **out)
{
    audio_devcoll *c = audio_devcoll_from(self);
    if (!out) return E_POINTER;
    if (index >= c->count) { *out = NULL; return E_BOUNDS; }
    svc_AddRef(&c->items[index]->base);
    *out = c->items[index];
    return S_OK;
}
static HRESULT audio_devcoll_get_Size(void *self, unsigned *out)
{
    audio_devcoll *c = audio_devcoll_from(self);
    if (!out) return E_POINTER;
    *out = c->count;
    return S_OK;
}
static HRESULT audio_devcoll_IndexOf(void *self, void *item, unsigned *index, boolean *found)
{
    audio_devcoll *c = audio_devcoll_from(self);
    unsigned i;
    if (!index || !found) return E_POINTER;
    for (i = 0; i < c->count; i++) {
        if ((void *)c->items[i] == item) {
            *index = i; *found = TRUE; return S_OK;
        }
    }
    *index = 0; *found = FALSE;
    return S_OK;
}
static HRESULT audio_devcoll_GetMany(void *self, unsigned start, unsigned cap, void **items, unsigned *actual)
{
    audio_devcoll *c = audio_devcoll_from(self);
    unsigned n = 0;
    if (!actual) return E_POINTER;
    while (start + n < c->count && n < cap) {
        svc_AddRef(&c->items[start + n]->base);
        items[n] = c->items[start + n];
        n++;
    }
    *actual = n;
    return S_OK;
}
static HRESULT audio_devcoll_First(void *self, void **out)
{
    audio_devcoll *c = audio_devcoll_from(self);
    audio_deviter *it;
    if (!out) return E_POINTER;
    it = (audio_deviter *)calloc(1, sizeof *it);
    if (!it) return E_OUTOFMEMORY;
    it->base.vtbl = audio_deviter_vtable;
    it->base.refs = 1;
    it->base.name = "Windows.Foundation.Collections.IIterator";
    it->coll = c;
    svc_AddRef(&c->base);
    it->index = 0;
    *out = it;
    return S_OK;
}
static const void *audio_devcoll_vtable[] = {
    (const void *)audio_devcoll_QI, (const void *)audio_devcoll_AddRef, (const void *)audio_devcoll_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)audio_devcoll_GetAt, (const void *)audio_devcoll_get_Size,
    (const void *)audio_devcoll_IndexOf, (const void *)audio_devcoll_GetMany,
};
static const void *audio_devcoll_iterable_vtable[] = {
    (const void *)audio_devcoll_QI, (const void *)audio_devcoll_AddRef, (const void *)audio_devcoll_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)audio_devcoll_First,
};

static HRESULT audio_deviter_QI(audio_deviter *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_device_iterator) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(&self->base);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static ULONG audio_deviter_Release(audio_deviter *self)
{
    LONG left = InterlockedDecrement(&self->base.refs);
    if (left <= 0) {
        if (self->coll) audio_devcoll_Release(self->coll);
        free(self);
    }
    return (ULONG)(left < 0 ? 0 : left);
}
static HRESULT audio_deviter_get_Current(audio_deviter *self, void **out)
{
    if (!out) return E_POINTER;
    if (!self->coll || self->index >= self->coll->count) { *out = NULL; return E_BOUNDS; }
    svc_AddRef(&self->coll->items[self->index]->base);
    *out = self->coll->items[self->index];
    return S_OK;
}
static HRESULT audio_deviter_get_HasCurrent(audio_deviter *self, boolean *out)
{
    if (!out) return E_POINTER;
    *out = (self->coll && self->index < self->coll->count) ? TRUE : FALSE;
    return S_OK;
}
static HRESULT audio_deviter_MoveNext(audio_deviter *self, boolean *out)
{
    if (!out) return E_POINTER;
    if (self->coll && self->index < self->coll->count) self->index++;
    *out = (self->coll && self->index < self->coll->count) ? TRUE : FALSE;
    return S_OK;
}
static HRESULT audio_deviter_GetMany(audio_deviter *self, unsigned cap, void **items, unsigned *actual)
{
    unsigned n = 0;
    if (!actual) return E_POINTER;
    while (self->coll && self->index < self->coll->count && n < cap) {
        svc_AddRef(&self->coll->items[self->index]->base);
        items[n++] = self->coll->items[self->index++];
    }
    *actual = n;
    return S_OK;
}
static const void *audio_deviter_vtable[] = {
    (const void *)audio_deviter_QI, (const void *)svc_AddRef, (const void *)audio_deviter_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)audio_deviter_get_Current, (const void *)audio_deviter_get_HasCurrent,
    (const void *)audio_deviter_MoveNext, (const void *)audio_deviter_GetMany,
};

static void audio_append_flow(audio_devcoll *coll, int capture)
{
    HKEY root = NULL;
    const WCHAR *subkey = capture
        ? L"Software\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Capture"
        : L"Software\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Render";
    const WCHAR *iface_guid = capture
        ? L"{2EEF81BE-33FA-4800-9670-1CD474972C3F}"
        : L"{E6327CAD-DCEC-4949-AE8A-991E976A79D2}";
    const WCHAR *flow_prefix = capture ? L"{0.0.1.00000000}" : L"{0.0.0.00000000}";
    DWORD index = 0;
    unsigned added = 0;

    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, subkey, 0, KEY_READ, &root) == ERROR_SUCCESS) {
        for (; coll->count < MAX_AUDIO_DEVS;) {
            WCHAR guid[128];
            DWORD guid_len = 128;
            HKEY item = NULL, props = NULL;
            DWORD state = 0, state_sz = sizeof state, type = 0;
            LONG rc = RegEnumKeyExW(root, index++, guid, &guid_len, NULL, NULL, NULL, NULL);
            if (rc != ERROR_SUCCESS) break;
            if (guid[0] != L'{') continue;
            if (RegOpenKeyExW(root, guid, 0, KEY_READ, &item) == ERROR_SUCCESS) {
                if (RegQueryValueExW(item, L"DeviceState", NULL, &type, (LPBYTE)&state, &state_sz) == ERROR_SUCCESS &&
                    state == 1) {
                    WCHAR full_id[256];
                    WCHAR friendly[256] = {0};
                    DWORD fn_sz = sizeof friendly;
                    swprintf(full_id, 256, L"\\\\?\\SWD#MMDEVAPI#%ls.%ls#%ls", flow_prefix, guid, iface_guid);
                    if (RegOpenKeyExW(item, L"Properties", 0, KEY_READ, &props) == ERROR_SUCCESS) {
                        if (RegQueryValueExW(props, L"{a45c254e-df1c-4efd-8020-67d146a850e0},14",
                                             NULL, &type, (LPBYTE)friendly, &fn_sz) != ERROR_SUCCESS || !friendly[0]) {
                            fn_sz = sizeof friendly;
                            RegQueryValueExW(props, L"{a45c254e-df1c-4efd-8020-67d146a850e0},2",
                                             NULL, &type, (LPBYTE)friendly, &fn_sz);
                        }
                        RegCloseKey(props);
                    }
                    if (!friendly[0]) lstrcpynW(friendly, capture ? L"Microphone" : L"Speakers", 256);
                    coll->items[coll->count] = audio_devinfo_new(full_id, friendly, TRUE, added == 0 ? TRUE : FALSE);
                    if (coll->items[coll->count]) {
                        coll->count++;
                        added++;
                    }
                }
                RegCloseKey(item);
            }
        }
        RegCloseKey(root);
    }

    /* Fallback if no active render endpoint was found in registry yet: expose the
       default audio render interface GUID which Wine's ActivateAudioInterfaceAsync
       resolves via GetDefaultAudioEndpoint(eRender, eMultimedia). */
    if (!capture && added == 0 && coll->count < MAX_AUDIO_DEVS) {
        coll->items[coll->count] = audio_devinfo_new(iface_guid, L"Speakers", TRUE, TRUE);
        if (coll->items[coll->count]) coll->count++;
    }
}

static audio_devcoll *audio_devcoll_enum(int want_render, int want_capture)
{
    audio_devcoll *c = (audio_devcoll *)calloc(1, sizeof *c);
    if (!c) return NULL;
    c->base.vtbl = audio_devcoll_vtable;
    c->base.refs = 1;
    c->base.name = "Windows.Devices.Enumeration.DeviceInformationCollection";
    c->iterable.vtbl = audio_devcoll_iterable_vtable;
    c->iterable.refs = 1;
    c->iterable.name = "Windows.Devices.Enumeration.DeviceInformationCollection";
    if (want_render) audio_append_flow(c, 0);
    if (want_capture) audio_append_flow(c, 1);
    return c;
}

/* ---- Lightweight IDeviceWatcher for audio endpoints ---- */
typedef struct {
    svc_object base;
    int want_render;
    int want_capture;
    int status; /* 0=Created, 1=Started, 2=EnumerationCompleted, 3=Stopping, 4=Stopped */
    void *on_added;
    void *on_enum_done;
} audio_watcher;

static HRESULT audio_watcher_QI(audio_watcher *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_device_watcher) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(&self->base);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static ULONG audio_watcher_Release(audio_watcher *self)
{
    LONG left = InterlockedDecrement(&self->base.refs);
    if (left <= 0) {
        if (self->on_added) ((ULONG(__stdcall *)(void *))(*(void ***)self->on_added)[2])(self->on_added);
        if (self->on_enum_done) ((ULONG(__stdcall *)(void *))(*(void ***)self->on_enum_done)[2])(self->on_enum_done);
        free(self);
    }
    return (ULONG)(left < 0 ? 0 : left);
}
static HRESULT audio_watcher_add_Added(audio_watcher *self, void *handler, int64_t *token)
{
    if (token) *token = 1;
    if (self->on_added) ((ULONG(__stdcall *)(void *))(*(void ***)self->on_added)[2])(self->on_added);
    self->on_added = handler;
    if (handler) ((ULONG(__stdcall *)(void *))(*(void ***)handler)[1])(handler);
    return S_OK;
}
static HRESULT audio_watcher_remove_Added(audio_watcher *self, int64_t token)
{
    (void)token;
    if (self->on_added) {
        ((ULONG(__stdcall *)(void *))(*(void ***)self->on_added)[2])(self->on_added);
        self->on_added = NULL;
    }
    return S_OK;
}
static HRESULT audio_watcher_add_noop(audio_watcher *self, void *handler, int64_t *token)
{
    (void)self; (void)handler;
    if (token) *token = 2;
    return S_OK;
}
static HRESULT audio_watcher_remove_noop(audio_watcher *self, int64_t token)
{
    (void)self; (void)token;
    return S_OK;
}
static HRESULT audio_watcher_add_EnumDone(audio_watcher *self, void *handler, int64_t *token)
{
    if (token) *token = 3;
    if (self->on_enum_done) ((ULONG(__stdcall *)(void *))(*(void ***)self->on_enum_done)[2])(self->on_enum_done);
    self->on_enum_done = handler;
    if (handler) ((ULONG(__stdcall *)(void *))(*(void ***)handler)[1])(handler);
    return S_OK;
}
static HRESULT audio_watcher_remove_EnumDone(audio_watcher *self, int64_t token)
{
    (void)token;
    if (self->on_enum_done) {
        ((ULONG(__stdcall *)(void *))(*(void ***)self->on_enum_done)[2])(self->on_enum_done);
        self->on_enum_done = NULL;
    }
    return S_OK;
}
static HRESULT audio_watcher_get_Status(audio_watcher *self, int *out)
{
    if (!out) return E_POINTER;
    *out = self->status;
    return S_OK;
}
static HRESULT audio_watcher_Start(audio_watcher *self)
{
    typedef HRESULT(__stdcall *invoke_fn)(void *, void *, void *);
    audio_devcoll *coll = audio_devcoll_enum(self->want_render, self->want_capture);
    self->status = 1;
    dev_dbg("Watcher::Start render=%d capture=%d count=%u", self->want_render, self->want_capture, coll ? coll->count : 0);
    if (coll) {
        unsigned i;
        for (i = 0; i < coll->count && self->status == 1; i++) {
            void *handler = self->on_added;
            if (!handler) break;
            ((ULONG(__stdcall *)(void *))(*(void ***)handler)[1])(handler);
            {
                invoke_fn inv = ((invoke_fn *)(*(void ***)handler))[3];
                inv(handler, self, coll->items[i]);
            }
            ((ULONG(__stdcall *)(void *))(*(void ***)handler)[2])(handler);
        }
        audio_devcoll_Release(coll);
    }
    if (self->status == 1) {
        void *done = self->on_enum_done;
        self->status = 2;
        if (done) {
            ((ULONG(__stdcall *)(void *))(*(void ***)done)[1])(done);
            {
                invoke_fn inv = ((invoke_fn *)(*(void ***)done))[3];
                inv(done, self, NULL);
            }
            ((ULONG(__stdcall *)(void *))(*(void ***)done)[2])(done);
        }
    }
    return S_OK;
}
static HRESULT audio_watcher_Stop(audio_watcher *self)
{
    self->status = 4;
    return S_OK;
}
static const void *audio_watcher_vtable[] = {
    (const void *)audio_watcher_QI, (const void *)svc_AddRef, (const void *)audio_watcher_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)audio_watcher_add_Added, (const void *)audio_watcher_remove_Added,
    (const void *)audio_watcher_add_noop, (const void *)audio_watcher_remove_noop,
    (const void *)audio_watcher_add_noop, (const void *)audio_watcher_remove_noop,
    (const void *)audio_watcher_add_EnumDone, (const void *)audio_watcher_remove_EnumDone,
    (const void *)audio_watcher_add_noop, (const void *)audio_watcher_remove_noop,
    (const void *)audio_watcher_get_Status, (const void *)audio_watcher_Start, (const void *)audio_watcher_Stop,
};

static HRESULT audio_watcher_create(int want_render, int want_capture, void **out)
{
    audio_watcher *w;
    if (!out) return E_POINTER;
    w = (audio_watcher *)calloc(1, sizeof *w);
    if (!w) return E_OUTOFMEMORY;
    w->base.vtbl = audio_watcher_vtable;
    w->base.refs = 1;
    w->base.name = "Windows.Devices.Enumeration.DeviceWatcher";
    w->want_render = want_render;
    w->want_capture = want_capture;
    w->status = 0;
    *out = w;
    return S_OK;
}

/* ---- Legacy empty singleton collection for non-audio queries ---- */
static HRESULT device_collection_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_device_vector) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile))
        *out = &device_collection;
    else if (IsEqualIID(id, &svc_iid_device_iterable))
        *out = &device_collection_iterable;
    else {
        svc_trace_iid("device collection QI", id);
        *out = NULL;
        return E_NOINTERFACE;
    }
    svc_AddRef((svc_object *)*out);
    return S_OK;
}

static HRESULT device_collection_GetAt(svc_object *self, unsigned index, void **out)
{
    (void)self; (void)index;
    if (out) *out = NULL;
    return E_BOUNDS;
}
static HRESULT device_collection_get_Size(svc_object *self, unsigned *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}
static HRESULT device_collection_IndexOf(svc_object *self, void *item, unsigned *index, boolean *found)
{
    (void)self; (void)item;
    if (!index || !found) return E_POINTER;
    *index = 0; *found = FALSE;
    return S_OK;
}
static HRESULT device_collection_GetMany(svc_object *self, unsigned start, unsigned capacity,
                                         void **items, unsigned *actual)
{
    (void)self; (void)start; (void)capacity; (void)items;
    if (!actual) return E_POINTER;
    *actual = 0;
    return S_OK;
}
static HRESULT device_collection_First(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &device_iterator;
    svc_AddRef(&device_iterator);
    return S_OK;
}
static HRESULT device_iterator_QI(svc_object *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_device_iterator) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT device_iterator_get_Current(svc_object *self, void **out)
{
    (void)self;
    if (out) *out = NULL;
    return E_BOUNDS;
}
static HRESULT device_iterator_flag(svc_object *self, boolean *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = FALSE;
    return S_OK;
}
static HRESULT device_iterator_GetMany(svc_object *self, unsigned capacity, void **items, unsigned *actual)
{
    (void)self; (void)capacity; (void)items;
    if (!actual) return E_POINTER;
    *actual = 0;
    return S_OK;
}

static const void *device_collection_vtable[] = {
    (const void *)device_collection_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)device_collection_GetAt, (const void *)device_collection_get_Size,
    (const void *)device_collection_IndexOf, (const void *)device_collection_GetMany,
};
static const void *device_collection_iterable_vtable[] = {
    (const void *)device_collection_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)device_collection_First,
};
static const void *device_iterator_vtable[] = {
    (const void *)device_iterator_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)device_iterator_get_Current, (const void *)device_iterator_flag,
    (const void *)device_iterator_flag, (const void *)device_iterator_GetMany,
};

/* ---- Helpers to classify AQS filter & answer FindAll / CreateFromId ---- */
static void classify_aqs(const WCHAR *aqs, int *want_render, int *want_capture)
{
    *want_render = 0;
    *want_capture = 0;
    if (!aqs || !aqs[0]) {
        *want_render = 1;
        *want_capture = 1;
        return;
    }
    if (wstr_contains_ci(aqs, L"e6327cad-dcec-4949-ae8a-991e976a79d2"))
        *want_render = 1;
    if (wstr_contains_ci(aqs, L"2eef81be-33fa-4800-9670-1cd474972c3f"))
        *want_capture = 1;
}

static HRESULT device_find_by_aqs(const WCHAR *aqs, void **out)
{
    int wr = 0, wc = 0;
    classify_aqs(aqs, &wr, &wc);
    if (wr || wc) {
        audio_devcoll *coll = audio_devcoll_enum(wr, wc);
        if (!coll) return E_OUTOFMEMORY;
        dev_dbg("FindAllAsync(aqs) render=%d capture=%d -> %u devices", wr, wc, coll->count);
        return async_operation_iid(&svc_iid_async_devcoll, S_OK, coll, out);
    }
    dev_dbg("FindAllAsync(non-audio aqs) -> 0 devices");
    svc_AddRef(&device_collection);
    return async_operation_iid(&svc_iid_async_devcoll, S_OK, &device_collection, out);
}

static HRESULT device_create_from_id(hstring_t id_h, void **out)
{
    const WCHAR *id = svc_string_text(id_h);
    audio_devcoll *coll;
    audio_devinfo *match = NULL;
    unsigned i;
    if (!id || !id[0])
        return async_operation_iid(&svc_iid_async_devinfo, E_INVALIDARG, NULL, out);
    coll = audio_devcoll_enum(1, 1);
    if (coll) {
        for (i = 0; i < coll->count; i++) {
            if (lstrcmpiW(coll->items[i]->id, id) == 0) {
                match = coll->items[i];
                svc_AddRef(&match->base);
                break;
            }
        }
        audio_devcoll_Release(coll);
    }
    if (!match) {
        dev_dbg("CreateFromIdAsync -> not found for %ls", id);
        return async_operation_iid(&svc_iid_async_devinfo, HRESULT_FROM_WIN32(ERROR_NOT_FOUND),
                                   NULL, out);
    }
    dev_dbg("CreateFromIdAsync -> name=%ls", match->name);
    return async_operation_iid(&svc_iid_async_devinfo, S_OK, match, out);
}

/* ---- IDeviceInformationStatics ---- */
static HRESULT device_static_QI(svc_object *self, const GUID *id, void **out);

static HRESULT device_static1_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    return device_static_QI(&device_static, id, out);
}
static HRESULT device_s1_CreateFromIdAsync(svc_object *self, hstring_t id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    return device_create_from_id(id, out);
}
static HRESULT device_s1_CreateFromIdAsyncProps(svc_object *self, hstring_t id, void *props, void **out)
{
    (void)self; (void)props;
    if (!out) return E_POINTER;
    return device_create_from_id(id, out);
}
static HRESULT device_s1_FindAllAsync(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    return device_find_by_aqs(NULL, out);
}
static HRESULT device_s1_FindAllAsyncDeviceClass(svc_object *self, int dev_class, void **out)
{
    int wr = (dev_class == 0 || dev_class == 2);
    int wc = (dev_class == 0 || dev_class == 1);
    (void)self;
    if (!out) return E_POINTER;
    if (wr || wc) {
        audio_devcoll *coll = audio_devcoll_enum(wr, wc);
        if (!coll) return E_OUTOFMEMORY;
        dev_dbg("FindAllAsync(class=%d) -> %u devices", dev_class, coll->count);
        return async_operation_iid(&svc_iid_async_devcoll, S_OK, coll, out);
    }
    dev_dbg("FindAllAsync(class=%d) -> 0 devices", dev_class);
    svc_AddRef(&device_collection);
    return async_operation_iid(&svc_iid_async_devcoll, S_OK, &device_collection, out);
}
static HRESULT device_s1_FindAllAsyncAqs(svc_object *self, hstring_t filter, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    return device_find_by_aqs(svc_string_text(filter), out);
}
static HRESULT device_s1_FindAllAsyncAqsProps(svc_object *self, hstring_t filter, void *props, void **out)
{
    (void)self; (void)props;
    if (!out) return E_POINTER;
    return device_find_by_aqs(svc_string_text(filter), out);
}
static HRESULT device_s1_CreateWatcher(svc_object *self, void **out)
{
    (void)self;
    dev_dbg("CreateWatcher()");
    return audio_watcher_create(1, 1, out);
}
static HRESULT device_s1_CreateWatcherClass(svc_object *self, int dev_class, void **out)
{
    (void)self;
    dev_dbg("CreateWatcher(class=%d)", dev_class);
    return audio_watcher_create(dev_class == 0 || dev_class == 2, dev_class == 0 || dev_class == 1, out);
}
static HRESULT device_s1_CreateWatcherAqs(svc_object *self, hstring_t filter, void **out)
{
    int wr = 0, wc = 0;
    (void)self;
    classify_aqs(svc_string_text(filter), &wr, &wc);
    dev_dbg("CreateWatcher(aqs) render=%d capture=%d", wr, wc);
    return audio_watcher_create(wr, wc, out);
}
static HRESULT device_s1_CreateWatcherAqsProps(svc_object *self, hstring_t filter, void *props, void **out)
{
    int wr = 0, wc = 0;
    (void)self; (void)props;
    classify_aqs(svc_string_text(filter), &wr, &wc);
    dev_dbg("CreateWatcher(aqs,props) render=%d capture=%d", wr, wc);
    return audio_watcher_create(wr, wc, out);
}

static const void *device_statics1_vtable[] = {
    (const void *)device_static1_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)device_s1_CreateFromIdAsync, (const void *)device_s1_CreateFromIdAsyncProps,
    (const void *)device_s1_FindAllAsync, (const void *)device_s1_FindAllAsyncDeviceClass,
    (const void *)device_s1_FindAllAsyncAqs, (const void *)device_s1_FindAllAsyncAqsProps,
    (const void *)device_s1_CreateWatcher, (const void *)device_s1_CreateWatcherClass,
    (const void *)device_s1_CreateWatcherAqs, (const void *)device_s1_CreateWatcherAqsProps,
};

/* ---- IDeviceInformationStatics2 ---- */
static HRESULT device_static2_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    return device_static_QI(&device_static, id, out);
}
static HRESULT device_s2_GetAqsFilter(svc_object *self, int dev_class, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (dev_class == 1) *out = svc_string(selector_audio_capture);
    else if (dev_class == 2) *out = svc_string(selector_audio_render);
    else if (dev_class == 4) *out = svc_string(selector_video_capture);
    else *out = svc_string(L"");
    return S_OK;
}
static HRESULT device_s2_CreateFromIdAsync(svc_object *self, hstring_t id, void *props, int kind, void **out)
{
    (void)self; (void)props; (void)kind;
    if (!out) return E_POINTER;
    return device_create_from_id(id, out);
}
static HRESULT device_FindAllAsync(svc_object *self, hstring_t filter, void *properties, int kind,
                                   void **out)
{
    (void)self; (void)properties; (void)kind;
    if (!out) return E_POINTER;
    svc_trace_w("device find all", svc_string_text(filter));
    return device_find_by_aqs(svc_string_text(filter), out);
}
static HRESULT device_s2_CreateWatcher(svc_object *self, hstring_t filter, void *props, int kind, void **out)
{
    int wr = 0, wc = 0;
    (void)self; (void)props; (void)kind;
    classify_aqs(svc_string_text(filter), &wr, &wc);
    dev_dbg("Statics2::CreateWatcher render=%d capture=%d", wr, wc);
    return audio_watcher_create(wr, wc, out);
}

static const void *device_statics2_vtable[] = {
    (const void *)device_static2_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)device_s2_GetAqsFilter, (const void *)device_s2_CreateFromIdAsync,
    (const void *)device_FindAllAsync, (const void *)device_s2_CreateWatcher,
};

static HRESULT device_static_QI(svc_object *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_device_statics)) {
        if (!device_static1.vtbl) {
            device_static1.vtbl = device_statics1_vtable;
            device_static1.refs = 1000;
            device_static1.permanent = 1;
            device_static1.name = "Windows.Devices.Enumeration.DeviceInformation";
        }
        *out = &device_static1;
        svc_AddRef(&device_static1);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_device_statics2)) {
        *out = &device_static2;
        svc_AddRef(&device_static2);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_unknown) || IsEqualIID(id, &svc_iid_inspectable) ||
        IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static const void *device_static_vtable[] = {
    (const void *)device_static_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
};

/* ------------------------------------------- Windows.Services.Store.StoreContext */

/* UWP Store licensing and catalog queries. A title installed from the user's
   Xbox library runs under an active, non-trial StoreAppLicense; DLC, add-on,
   consumable, and package-update queries return empty results cleanly rather
   than failing activation with REGDB_E_CLASSNOTREG. */

static const GUID svc_iid_closable = {0x30d5a829, 0x7fa4, 0x4026,
                                      {0x83, 0xbb, 0xd7, 0x5b, 0xae, 0x4e, 0xa9, 0x9e}};
static const GUID svc_iid_store_context_statics = {0x9c06ee5f, 0x15c0, 0x4e72,
                                                   {0x93, 0x30, 0xd6, 0x19, 0x1c, 0xeb, 0xd1, 0x9c}};
static const GUID svc_iid_store_context = {0xac98b6be, 0xf4fd, 0x4912,
                                           {0xba, 0xbd, 0x50, 0x35, 0xe5, 0xe8, 0xbc, 0xab}};
static const GUID svc_iid_store_context2 = {0x18bc54da, 0x7bd9, 0x452c,
                                            {0x91, 0x16, 0x3b, 0xbd, 0x06, 0xff, 0xc6, 0x3a}};
static const GUID svc_iid_store_app_license = {0xf389f9de, 0x73c0, 0x45ce,
                                               {0x9b, 0xab, 0xb2, 0xfe, 0x3e, 0x5e, 0xaf, 0xd3}};
static const GUID svc_iid_store_addon_map = {0x7d0d74f5, 0x4020, 0x54aa,
                                             {0x9f, 0x3d, 0x0f, 0x17, 0x12, 0x7a, 0xcd, 0xdf}};
static const GUID svc_iid_store_addon_iterable = {0xca8ba445, 0x6f4d, 0x5da9,
                                                  {0x95, 0xee, 0x42, 0xcf, 0x11, 0x8d, 0xef, 0x63}};
static const GUID svc_iid_store_addon_iterator = {0xa1509348, 0x6522, 0x5062,
                                                  {0xae, 0x86, 0xcf, 0x59, 0x54, 0x75, 0x92, 0x6d}};
static const GUID svc_iid_store_product_query_result = {
    0xd805e6c5, 0xd456, 0x4ff6, {0x80, 0x49, 0x90, 0x76, 0xd5, 0x16, 0x5f, 0x73}};
static const GUID svc_iid_store_product_map = {0xdbaac6e5, 0x61a4, 0x5c88,
                                               {0xb5, 0xd8, 0x3a, 0x3e, 0x16, 0x1c, 0x3e, 0x4a}};
static const GUID svc_iid_store_product_iterable = {
    0x78a33722, 0xabfb, 0x57c0, {0x85, 0x3f, 0x56, 0x16, 0xa3, 0xab, 0x8d, 0x57}};
static const GUID svc_iid_store_product_iterator = {
    0x0edb142c, 0x9d04, 0x532b, {0x81, 0xe9, 0x4c, 0x84, 0xab, 0x09, 0xb3, 0x4b}};
static const GUID svc_iid_store_product_result = {0xb7674f73, 0x3c87, 0x4ee1,
                                                  {0x82, 0x01, 0xf4, 0x28, 0x35, 0x9b, 0xd3, 0xaf}};
static const GUID svc_iid_store_consumable_result = {
    0xea5dab72, 0x6a00, 0x4052, {0xbe, 0x5b, 0xbf, 0xda, 0xb4, 0x43, 0x33, 0x52}};
static const GUID svc_iid_store_purchase_result = {
    0xadd28552, 0xf96a, 0x463d, {0xa7, 0xbb, 0xc2, 0x0b, 0x4f, 0xca, 0x69, 0x52}};
static const GUID svc_iid_store_acquire_license_result = {
    0xfbd7946d, 0xf040, 0x4cb3, {0x9a, 0x39, 0x29, 0xbc, 0xec, 0xdb, 0xe2, 0x2d}};
static const GUID svc_iid_store_package_license = {
    0x0c465714, 0x14e1, 0x4973, {0xbd, 0x14, 0xf7, 0x77, 0x24, 0x27, 0x1e, 0x99}};
static const GUID svc_iid_store_updates_vector = {
    0x971c3ea6, 0x4388, 0x5a38, {0xae, 0x13, 0x49, 0x29, 0xb6, 0xd6, 0xd7, 0x80}};
static const GUID svc_iid_store_updates_iterable = {
    0x6b076c51, 0x849e, 0x5ec5, {0xae, 0xd5, 0x9b, 0x05, 0x85, 0x59, 0x19, 0x02}};
static const GUID svc_iid_store_updates_iterator = {
    0xb75dd77b, 0x87ca, 0x5956, {0x89, 0x02, 0x84, 0xe9, 0xff, 0xc9, 0x7d, 0x83}};

static svc_object store_context_static;
static svc_object store_context_object;
static svc_object store_context2_face;
static svc_object store_app_license_object;
static svc_object store_addon_map;
static svc_object store_addon_iterable;
static svc_object store_addon_iterator;
static svc_object store_product_query_result;
static svc_object store_product_map;
static svc_object store_product_iterable;
static svc_object store_product_iterator;
static svc_object store_product_result;
static svc_object store_consumable_result;
static svc_object store_purchase_result;
static svc_object store_acquire_license_result;
static svc_object store_package_license;
static svc_object store_package_license_closable;
static svc_object store_updates_vector;
static svc_object store_updates_iterable;
static svc_object store_updates_iterator;

/* Shared empty IMapView<HSTRING, T*> methods */

static HRESULT store_empty_map_Lookup(svc_object *self, hstring_t key, void **out)
{
    (void)self;
    (void)key;
    if (out) *out = NULL;
    return E_BOUNDS;
}

static HRESULT store_empty_map_get_Size(svc_object *self, unsigned *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

static HRESULT store_empty_map_HasKey(svc_object *self, hstring_t key, boolean *out)
{
    (void)self;
    (void)key;
    if (!out) return E_POINTER;
    *out = FALSE;
    return S_OK;
}

static HRESULT store_empty_map_Split(svc_object *self, void **first, void **second)
{
    (void)self;
    if (first) *first = NULL;
    if (second) *second = NULL;
    return S_OK;
}

static HRESULT store_empty_iter_Current(svc_object *self, void **out)
{
    (void)self;
    if (out) *out = NULL;
    return E_BOUNDS;
}

static HRESULT store_empty_iter_flag(svc_object *self, boolean *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = FALSE;
    return S_OK;
}

static HRESULT store_empty_iter_GetMany(svc_object *self, unsigned capacity, void **items,
                                        unsigned *actual)
{
    (void)self;
    (void)capacity;
    (void)items;
    if (!actual) return E_POINTER;
    *actual = 0;
    return S_OK;
}

/* IMapView<HSTRING, StoreLicense*> */

static HRESULT store_addon_map_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_store_addon_iterable)) {
        *out = &store_addon_iterable;
        svc_AddRef(&store_addon_iterable);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_store_addon_map) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object)) {
        *out = &store_addon_map;
        svc_AddRef(&store_addon_map);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT store_addon_First(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &store_addon_iterator;
    svc_AddRef(&store_addon_iterator);
    return S_OK;
}

static HRESULT store_addon_iter_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_store_addon_iterator, out);
}

static const void *store_addon_map_vtable[] = {
    (const void *)store_addon_map_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_empty_map_Lookup, (const void *)store_empty_map_get_Size,
    (const void *)store_empty_map_HasKey, (const void *)store_empty_map_Split,
};

static const void *store_addon_iterable_vtable[] = {
    (const void *)store_addon_map_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_addon_First,
};

static const void *store_addon_iterator_vtable[] = {
    (const void *)store_addon_iter_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_empty_iter_Current, (const void *)store_empty_iter_flag,
    (const void *)store_empty_iter_flag, (const void *)store_empty_iter_GetMany,
};

/* IMapView<HSTRING, StoreProduct*> */

static HRESULT store_product_map_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_store_product_iterable)) {
        *out = &store_product_iterable;
        svc_AddRef(&store_product_iterable);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_store_product_map) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object)) {
        *out = &store_product_map;
        svc_AddRef(&store_product_map);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT store_product_First(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &store_product_iterator;
    svc_AddRef(&store_product_iterator);
    return S_OK;
}

static HRESULT store_product_iter_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_store_product_iterator, out);
}

static const void *store_product_map_vtable[] = {
    (const void *)store_product_map_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_empty_map_Lookup, (const void *)store_empty_map_get_Size,
    (const void *)store_empty_map_HasKey, (const void *)store_empty_map_Split,
};

static const void *store_product_iterable_vtable[] = {
    (const void *)store_product_map_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_product_First,
};

static const void *store_product_iterator_vtable[] = {
    (const void *)store_product_iter_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_empty_iter_Current, (const void *)store_empty_iter_flag,
    (const void *)store_empty_iter_flag, (const void *)store_empty_iter_GetMany,
};

/* IVectorView<StorePackageUpdate*> */

static HRESULT store_updates_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_store_updates_iterable)) {
        *out = &store_updates_iterable;
        svc_AddRef(&store_updates_iterable);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_store_updates_vector) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object)) {
        *out = &store_updates_vector;
        svc_AddRef(&store_updates_vector);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT store_updates_GetAt(svc_object *self, unsigned index, void **out)
{
    (void)self;
    (void)index;
    if (out) *out = NULL;
    return E_BOUNDS;
}

static HRESULT store_updates_get_Size(svc_object *self, unsigned *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

static HRESULT store_updates_IndexOf(svc_object *self, void *item, unsigned *index, boolean *found)
{
    (void)self;
    (void)item;
    if (index) *index = 0;
    if (found) *found = FALSE;
    return S_OK;
}

static HRESULT store_updates_GetMany(svc_object *self, unsigned start, unsigned capacity,
                                     void **items, unsigned *actual)
{
    (void)self;
    (void)start;
    (void)capacity;
    (void)items;
    if (!actual) return E_POINTER;
    *actual = 0;
    return S_OK;
}

static HRESULT store_updates_First(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &store_updates_iterator;
    svc_AddRef(&store_updates_iterator);
    return S_OK;
}

static HRESULT store_updates_iter_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_store_updates_iterator, out);
}

static const void *store_updates_vector_vtable[] = {
    (const void *)store_updates_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_updates_GetAt, (const void *)store_updates_get_Size,
    (const void *)store_updates_IndexOf, (const void *)store_updates_GetMany,
};

static const void *store_updates_iterable_vtable[] = {
    (const void *)store_updates_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_updates_First,
};

static const void *store_updates_iterator_vtable[] = {
    (const void *)store_updates_iter_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_empty_iter_Current, (const void *)store_empty_iter_flag,
    (const void *)store_empty_iter_flag, (const void *)store_empty_iter_GetMany,
};

/* StoreAppLicense */

static HRESULT store_app_license_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_store_app_license, out);
}

static HRESULT store_app_license_get_SkuStoreId(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    return S_OK;
}

static HRESULT store_app_license_get_IsActive(svc_object *self, boolean *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = TRUE;
    return S_OK;
}

static HRESULT store_app_license_get_IsTrial(svc_object *self, boolean *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = FALSE;
    return S_OK;
}

static HRESULT store_app_license_get_ExpirationDate(svc_object *self, long long *out)
{
    (void)self;
    if (!out) return E_POINTER;
    /* 2100-01-01T00:00:00Z in 100-ns intervals since 1601-01-01 */
    *out = 157469184000000000LL;
    return S_OK;
}

static HRESULT store_app_license_get_ExtendedJsonData(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = svc_string(L"{}");
    return S_OK;
}

static HRESULT store_app_license_get_AddOnLicenses(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &store_addon_map;
    svc_AddRef(&store_addon_map);
    return S_OK;
}

static HRESULT store_app_license_get_TrialTimeRemaining(svc_object *self, long long *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

static HRESULT store_app_license_get_IsTrialOwnedByThisUser(svc_object *self, boolean *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = FALSE;
    return S_OK;
}

static HRESULT store_app_license_get_TrialUniqueId(svc_object *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    return S_OK;
}

static const void *store_app_license_vtable[] = {
    (const void *)store_app_license_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_app_license_get_SkuStoreId, (const void *)store_app_license_get_IsActive,
    (const void *)store_app_license_get_IsTrial, (const void *)store_app_license_get_ExpirationDate,
    (const void *)store_app_license_get_ExtendedJsonData,
    (const void *)store_app_license_get_AddOnLicenses,
    (const void *)store_app_license_get_TrialTimeRemaining,
    (const void *)store_app_license_get_IsTrialOwnedByThisUser,
    (const void *)store_app_license_get_TrialUniqueId,
};

/* StoreProductQueryResult */

static HRESULT store_product_query_result_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_store_product_query_result, out);
}

static HRESULT store_product_query_result_get_Products(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &store_product_map;
    svc_AddRef(&store_product_map);
    return S_OK;
}

static HRESULT store_get_ExtendedError_ok(svc_object *self, HRESULT *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = S_OK;
    return S_OK;
}

static const void *store_product_query_result_vtable[] = {
    (const void *)store_product_query_result_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_product_query_result_get_Products,
    (const void *)store_get_ExtendedError_ok,
};

/* StoreProductResult */

static HRESULT store_product_result_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_store_product_result, out);
}

static HRESULT store_product_result_get_Product(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    return S_OK;
}

static const void *store_product_result_vtable[] = {
    (const void *)store_product_result_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_product_result_get_Product, (const void *)store_get_ExtendedError_ok,
};

/* StoreConsumableResult */

static HRESULT store_consumable_result_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_store_consumable_result, out);
}

static HRESULT store_consumable_result_get_Status(svc_object *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0; /* StoreConsumableStatus_Succeeded */
    return S_OK;
}

static HRESULT store_consumable_result_get_TrackingId(svc_object *self, GUID *out)
{
    (void)self;
    if (!out) return E_POINTER;
    memset(out, 0, sizeof(*out));
    return S_OK;
}

static HRESULT store_consumable_result_get_BalanceRemaining(svc_object *self, unsigned *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

static const void *store_consumable_result_vtable[] = {
    (const void *)store_consumable_result_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_consumable_result_get_Status,
    (const void *)store_consumable_result_get_TrackingId,
    (const void *)store_consumable_result_get_BalanceRemaining,
    (const void *)store_get_ExtendedError_ok,
};

/* StorePurchaseResult */

static HRESULT store_purchase_result_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_store_purchase_result, out);
}

static HRESULT store_purchase_result_get_Status(svc_object *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 2; /* StorePurchaseStatus_NotPurchased */
    return S_OK;
}

static const void *store_purchase_result_vtable[] = {
    (const void *)store_purchase_result_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_purchase_result_get_Status, (const void *)store_get_ExtendedError_ok,
};

/* StorePackageLicense & StoreAcquireLicenseResult */

static HRESULT store_package_license_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_closable)) {
        *out = &store_package_license_closable;
        svc_AddRef(&store_package_license_closable);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_store_package_license) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object)) {
        *out = &store_package_license;
        svc_AddRef(&store_package_license);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT store_package_license_add_LicenseLost(svc_object *self, void *handler,
                                                     long long *token)
{
    (void)self;
    (void)handler;
    if (token) *token = 1;
    return S_OK;
}

static HRESULT store_package_license_remove_LicenseLost(svc_object *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

static HRESULT store_package_license_get_Package(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &package_object;
    svc_AddRef(&package_object);
    return S_OK;
}

static HRESULT store_package_license_get_IsValid(svc_object *self, boolean *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = TRUE;
    return S_OK;
}

static HRESULT store_package_license_nop(svc_object *self)
{
    (void)self;
    return S_OK;
}

static const void *store_package_license_vtable[] = {
    (const void *)store_package_license_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_package_license_add_LicenseLost,
    (const void *)store_package_license_remove_LicenseLost,
    (const void *)store_package_license_get_Package,
    (const void *)store_package_license_get_IsValid,
    (const void *)store_package_license_nop,
};

static const void *store_package_license_closable_vtable[] = {
    (const void *)store_package_license_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_package_license_nop,
};

static HRESULT store_acquire_license_result_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_store_acquire_license_result, out);
}

static HRESULT store_acquire_license_result_get_License(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &store_package_license;
    svc_AddRef(&store_package_license);
    return S_OK;
}

static const void *store_acquire_license_result_vtable[] = {
    (const void *)store_acquire_license_result_QI, (const void *)svc_AddRef,
    (const void *)svc_Release, (const void *)svc_get_iids, (const void *)svc_get_class_name,
    (const void *)svc_get_trust, (const void *)store_acquire_license_result_get_License,
    (const void *)store_get_ExtendedError_ok,
};

/* StoreContext */

static HRESULT store_context_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_store_context2)) {
        *out = &store_context2_face;
        svc_AddRef(&store_context2_face);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_store_context) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object)) {
        *out = &store_context_object;
        svc_AddRef(&store_context_object);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT store_context_get_User(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    return S_OK;
}

static HRESULT store_context_add_OfflineLicensesChanged(svc_object *self, void *handler,
                                                        long long *token)
{
    (void)self;
    (void)handler;
    if (token) *token = 1;
    return S_OK;
}

static HRESULT store_context_remove_OfflineLicensesChanged(svc_object *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

static HRESULT store_context_GetCustomerIdAsync(svc_object *self, hstring_t ticket,
                                                hstring_t publisher_user_id, void **out)
{
    (void)self;
    (void)ticket;
    (void)publisher_user_id;
    return async_operation_iid(&svc_iid_async_hstring, S_OK, NULL, out);
}

static HRESULT store_context_GetAppLicenseAsync(svc_object *self, void **out)
{
    (void)self;
    svc_trace("StoreContext::GetAppLicenseAsync");
    return async_operation_iid(&svc_iid_async_store_license, S_OK, &store_app_license_object, out);
}

static HRESULT store_context_GetStoreProductForCurrentAppAsync(svc_object *self, void **out)
{
    (void)self;
    return async_operation_iid(&svc_iid_async_store_product, S_OK, &store_product_result, out);
}

static HRESULT store_context_GetStoreProductsAsync(svc_object *self, void *kinds, void *store_ids,
                                                   void **out)
{
    (void)self;
    (void)kinds;
    (void)store_ids;
    return async_operation_iid(&svc_iid_async_store_query, S_OK, &store_product_query_result, out);
}

static HRESULT store_context_GetAssociatedOrCollectionAsync(svc_object *self, void *kinds,
                                                            void **out)
{
    (void)self;
    (void)kinds;
    return async_operation_iid(&svc_iid_async_store_query, S_OK, &store_product_query_result, out);
}

static HRESULT store_context_GetAssociatedOrCollectionPagedAsync(svc_object *self, void *kinds,
                                                                 unsigned max_items, void **out)
{
    (void)self;
    (void)kinds;
    (void)max_items;
    return async_operation_iid(&svc_iid_async_store_query, S_OK, &store_product_query_result, out);
}

static HRESULT store_context_ReportConsumableFulfillmentAsync(svc_object *self, hstring_t store_id,
                                                              unsigned quantity, GUID tracking_id,
                                                              void **out)
{
    (void)self;
    (void)store_id;
    (void)quantity;
    (void)tracking_id;
    return async_operation_iid(&svc_iid_async_store_consum, S_OK, &store_consumable_result, out);
}

static HRESULT store_context_GetConsumableBalanceRemainingAsync(svc_object *self,
                                                                hstring_t store_id, void **out)
{
    (void)self;
    (void)store_id;
    return async_operation_iid(&svc_iid_async_store_consum, S_OK, &store_consumable_result, out);
}

static HRESULT store_context_AcquireStoreLicenseForOptionalPackageAsync(svc_object *self,
                                                                        void *optional_pkg,
                                                                        void **out)
{
    (void)self;
    (void)optional_pkg;
    return async_operation_iid(&svc_iid_async_store_acqlic, S_OK, &store_acquire_license_result,
                               out);
}

static HRESULT store_context_RequestPurchaseAsync(svc_object *self, hstring_t store_id, void **out)
{
    (void)self;
    (void)store_id;
    return async_operation_iid(&svc_iid_async_store_purchase, S_OK, &store_purchase_result, out);
}

static HRESULT store_context_RequestPurchaseWithPropsAsync(svc_object *self, hstring_t store_id,
                                                           void *props, void **out)
{
    (void)self;
    (void)store_id;
    (void)props;
    return async_operation_iid(&svc_iid_async_store_purchase, S_OK, &store_purchase_result, out);
}

static HRESULT store_context_GetAppAndOptionalStorePackageUpdatesAsync(svc_object *self, void **out)
{
    (void)self;
    return async_operation_iid(&svc_iid_async_store_updates, S_OK, &store_updates_vector, out);
}

static HRESULT store_context2_FindStoreProductForPackageAsync(svc_object *self, void *kinds,
                                                              void *pkg, void **out)
{
    (void)self;
    (void)kinds;
    (void)pkg;
    return async_operation_iid(&svc_iid_async_store_product, S_OK, &store_product_result, out);
}

static const void *store_context_vtable[] = {
    (const void *)store_context_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_context_get_User,
    (const void *)store_context_add_OfflineLicensesChanged,
    (const void *)store_context_remove_OfflineLicensesChanged,
    (const void *)store_context_GetCustomerIdAsync,
    (const void *)store_context_GetCustomerIdAsync,
    (const void *)store_context_GetAppLicenseAsync,
    (const void *)store_context_GetStoreProductForCurrentAppAsync,
    (const void *)store_context_GetStoreProductsAsync,
    (const void *)store_context_GetAssociatedOrCollectionAsync,
    (const void *)store_context_GetAssociatedOrCollectionPagedAsync,
    (const void *)store_context_GetAssociatedOrCollectionAsync,
    (const void *)store_context_GetAssociatedOrCollectionPagedAsync,
    (const void *)store_context_ReportConsumableFulfillmentAsync,
    (const void *)store_context_GetConsumableBalanceRemainingAsync,
    (const void *)store_context_AcquireStoreLicenseForOptionalPackageAsync,
    (const void *)store_context_RequestPurchaseAsync,
    (const void *)store_context_RequestPurchaseWithPropsAsync,
    (const void *)store_context_GetAppAndOptionalStorePackageUpdatesAsync,
    (const void *)svc_unimplemented,
    (const void *)svc_unimplemented,
    (const void *)svc_unimplemented,
};

static const void *store_context2_vtable[] = {
    (const void *)store_context_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_context2_FindStoreProductForPackageAsync,
};

static HRESULT store_context_statics_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_store_context_statics, out);
}

static HRESULT store_context_GetDefault(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    svc_trace("StoreContext::GetDefault");
    *out = &store_context_object;
    svc_AddRef(&store_context_object);
    return S_OK;
}

static HRESULT store_context_GetForUser(svc_object *self, void *user, void **out)
{
    (void)user;
    return store_context_GetDefault(self, out);
}

static const void *store_context_statics_vtable[] = {
    (const void *)store_context_statics_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)store_context_GetDefault, (const void *)store_context_GetForUser,
};

/* ------------------------- Windows.System.Profile.{Hardware,System}Identification */

/* Deterministic per-package hardware and system identifiers matching the
   Windows ASHWID (4-byte {uint16 type, uint16 value} pairs) and SystemId
   shapes. */

static const GUID svc_iid_hardware_id_statics = {
    0x971260e0, 0xf170, 0x4a42, {0xbd, 0x55, 0xa9, 0x00, 0xb2, 0x12, 0xda, 0xe2}};
static const GUID svc_iid_hardware_token = {
    0x28f6d4c0, 0xfb12, 0x40a4, {0x81, 0x67, 0x7f, 0x4e, 0x03, 0xd2, 0x72, 0x4c}};
static const GUID svc_iid_system_id_statics = {
    0x5581f42a, 0xd3df, 0x4d93, {0xa3, 0x7d, 0xc4, 0x1a, 0x61, 0x6c, 0x6d, 0x01}};
static const GUID svc_iid_system_id_info = {
    0x0c659e7d, 0xc3c2, 0x4d33, {0xa2, 0xdf, 0x21, 0xbc, 0x41, 0x91, 0x6e, 0xb3}};

static svc_object hardware_id_static;
static svc_object hardware_token_object;
static svc_object system_id_static;
static svc_object system_id_info_object;

static void profile_hash_bytes(const char *tag, unsigned char out[32])
{
    sha256_ctx ctx;
    package_load();
    sha256_init(&ctx);
    sha256_update(&ctx, (const unsigned char *)tag, (unsigned)strlen(tag));
    sha256_update(&ctx, (const unsigned char *)identity.family,
                  (unsigned)(wcslen(identity.family) * sizeof(WCHAR)));
    sha256_final(&ctx, out);
}

static HRESULT hardware_token_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_hardware_token, out);
}

static HRESULT hardware_token_get_Id(svc_object *self, void **out)
{
    unsigned char digest[32];
    unsigned char ashwid[36];
    unsigned i;
    winrt_buffer *buf;
    (void)self;
    if (!out) return E_POINTER;
    profile_hash_bytes("ASHWID:", digest);
    /* Nine 4-byte ASHWID component entries: {uint16 type (1..9), uint16 value}. */
    for (i = 0; i < 9; i++) {
        ashwid[i * 4 + 0] = (unsigned char)(i + 1);
        ashwid[i * 4 + 1] = 0;
        ashwid[i * 4 + 2] = digest[i * 2 + 0];
        ashwid[i * 4 + 3] = digest[i * 2 + 1];
    }
    buf = buffer_from_bytes(ashwid, sizeof(ashwid));
    if (!buf) return E_OUTOFMEMORY;
    *out = buf;
    return S_OK;
}

static HRESULT hardware_token_get_Signature(svc_object *self, void **out)
{
    unsigned char digest[32];
    winrt_buffer *buf;
    (void)self;
    if (!out) return E_POINTER;
    profile_hash_bytes("ASHWID-SIG:", digest);
    buf = buffer_from_bytes(digest, sizeof(digest));
    if (!buf) return E_OUTOFMEMORY;
    *out = buf;
    return S_OK;
}

static HRESULT hardware_token_get_Certificate(svc_object *self, void **out)
{
    unsigned char digest[32];
    winrt_buffer *buf;
    (void)self;
    if (!out) return E_POINTER;
    profile_hash_bytes("ASHWID-CERT:", digest);
    buf = buffer_from_bytes(digest, sizeof(digest));
    if (!buf) return E_OUTOFMEMORY;
    *out = buf;
    return S_OK;
}

static const void *hardware_token_vtable[] = {
    (const void *)hardware_token_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)hardware_token_get_Id, (const void *)hardware_token_get_Signature,
    (const void *)hardware_token_get_Certificate,
};

static HRESULT hardware_id_statics_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_hardware_id_statics, out);
}

static HRESULT hardware_id_GetPackageSpecificToken(svc_object *self, void *nonce, void **out)
{
    (void)self;
    (void)nonce;
    if (!out) return E_POINTER;
    svc_trace("HardwareIdentification::GetPackageSpecificToken");
    *out = &hardware_token_object;
    svc_AddRef(&hardware_token_object);
    return S_OK;
}

static const void *hardware_id_statics_vtable[] = {
    (const void *)hardware_id_statics_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)hardware_id_GetPackageSpecificToken,
};

static HRESULT system_id_info_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_system_id_info, out);
}

static HRESULT system_id_info_get_Id(svc_object *self, void **out)
{
    unsigned char digest[32];
    winrt_buffer *buf;
    (void)self;
    if (!out) return E_POINTER;
    profile_hash_bytes("SystemId:", digest);
    buf = buffer_from_bytes(digest, sizeof(digest));
    if (!buf) return E_OUTOFMEMORY;
    *out = buf;
    return S_OK;
}

static HRESULT system_id_info_get_Source(svc_object *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 2; /* SystemIdentificationSource_Uefi */
    return S_OK;
}

static const void *system_id_info_vtable[] = {
    (const void *)system_id_info_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)system_id_info_get_Id, (const void *)system_id_info_get_Source,
};

static HRESULT system_id_statics_QI(svc_object *self, const GUID *id, void **out)
{
    return simple_QI(self, id, &svc_iid_system_id_statics, out);
}

static HRESULT system_id_GetForPublisher(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    svc_trace("SystemIdentification::GetSystemIdForPublisher");
    *out = &system_id_info_object;
    svc_AddRef(&system_id_info_object);
    return S_OK;
}

static HRESULT system_id_GetForUser(svc_object *self, void *user, void **out)
{
    (void)user;
    return system_id_GetForPublisher(self, out);
}

static const void *system_id_statics_vtable[] = {
    (const void *)system_id_statics_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)system_id_GetForPublisher, (const void *)system_id_GetForUser,
};

/* -------------------------------------- Windows.System.Threading.ThreadPoolTimer */

/* WinRT ThreadPoolTimer backed by the native Win32 threadpool timer API
   (CreateThreadpoolTimer / SetThreadpoolTimer / CloseThreadpoolTimer).
   Xbox Live Services (Microsoft.Xbox.Services.dll) schedules presence
   heartbeat and retry timers with ThreadPoolTimer::CreateTimer immediately
   after userpresence.xboxlive.com responds with X-Heartbeat-After. */

static const GUID tpt_iid_timer = {
    0x594ebe78, 0x55ea, 0x4a88, {0xa5, 0x0d, 0x34, 0x02, 0xae, 0x1f, 0x9c, 0xf2}};
static const GUID tpt_iid_statics = {
    0x1a8a9d02, 0xe482, 0x461b, {0xb8, 0xc7, 0x8e, 0xfa, 0xd1, 0xcc, 0xe5, 0x90}};
static const GUID tpt_iid_agile = {
    0x94ea2b94, 0xe9cc, 0x49e0, {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};

typedef struct {
    svc_object base;
    INT64 period;
    INT64 delay;
    int periodic;
    CRITICAL_SECTION lock;
    LONG active_callbacks;
    DWORD callback_tid;
    LONG canceled;
    LONG destroyed_fired;
    LONG sched_ref;
    void *elapsed_handler;
    void *destroyed_handler;
    PTP_TIMER tp_timer;
} tpt_timer;

static void tpt_unk_addref(void *unk)
{
    if (!unk) return;
    ((ULONG(__stdcall *)(void *))(*(void ***)unk)[1])(unk);
}

static void tpt_unk_release(void *unk)
{
    if (!unk) return;
    ((ULONG(__stdcall *)(void *))(*(void ***)unk)[2])(unk);
}

static void tpt_invoke_handler(void *handler, void *timer)
{
    if (!handler) return;
    ((HRESULT(__stdcall *)(void *, void *))(*(void ***)handler)[3])(handler, timer);
}

static ULONG __stdcall tpt_timer_AddRef(tpt_timer *self)
{
    return (ULONG)InterlockedIncrement(&self->base.refs);
}

static ULONG __stdcall tpt_timer_Release(tpt_timer *self)
{
    LONG left = InterlockedDecrement(&self->base.refs);
    if (left <= 0) {
        if (self->tp_timer) {
            CloseThreadpoolTimer(self->tp_timer);
            self->tp_timer = NULL;
        }
        tpt_unk_release(self->elapsed_handler);
        tpt_unk_release(self->destroyed_handler);
        DeleteCriticalSection(&self->lock);
        free(self);
    }
    return (ULONG)left;
}

static HRESULT __stdcall tpt_timer_QI(tpt_timer *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &tpt_iid_timer) || IsEqualIID(id, &svc_iid_inspectable) ||
        IsEqualIID(id, &svc_iid_unknown) || IsEqualIID(id, &tpt_iid_agile)) {
        *out = self;
        tpt_timer_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall tpt_timer_get_Period(tpt_timer *self, INT64 *value)
{
    if (!value) return E_POINTER;
    *value = self->period;
    return S_OK;
}

static HRESULT __stdcall tpt_timer_get_Delay(tpt_timer *self, INT64 *value)
{
    if (!value) return E_POINTER;
    *value = self->delay;
    return S_OK;
}

static HRESULT __stdcall tpt_timer_Cancel(tpt_timer *self)
{
    PTP_TIMER tp;
    int in_callback;
    int fire_destroyed = 0;
    int drop_sched = 0;

    tpt_timer_AddRef(self);
    EnterCriticalSection(&self->lock);
    self->canceled = 1;
    tp = self->tp_timer;
    in_callback = (self->active_callbacks > 0 && self->callback_tid == GetCurrentThreadId());
    LeaveCriticalSection(&self->lock);

    if (tp) {
        SetThreadpoolTimer(tp, NULL, 0, 0);
        if (!in_callback) WaitForThreadpoolTimerCallbacks(tp, TRUE);
    }

    EnterCriticalSection(&self->lock);
    if (self->active_callbacks == 0 && !self->destroyed_fired) {
        self->destroyed_fired = 1;
        fire_destroyed = 1;
        if (self->sched_ref) {
            self->sched_ref = 0;
            drop_sched = 1;
        }
    }
    LeaveCriticalSection(&self->lock);

    if (fire_destroyed && self->destroyed_handler)
        tpt_invoke_handler(self->destroyed_handler, self);
    if (drop_sched) tpt_timer_Release(self);
    tpt_timer_Release(self);
    return S_OK;
}

static const void *tpt_timer_vtable[] = {
    (const void *)tpt_timer_QI, (const void *)tpt_timer_AddRef, (const void *)tpt_timer_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)tpt_timer_get_Period, (const void *)tpt_timer_get_Delay,
    (const void *)tpt_timer_Cancel,
};

static VOID CALLBACK tpt_timer_callback(PTP_CALLBACK_INSTANCE inst, PVOID ctx, PTP_TIMER tp)
{
    tpt_timer *obj = (tpt_timer *)ctx;
    int fire_destroyed = 0;
    int drop_sched = 0;
    (void)inst;
    (void)tp;
    if (!obj) return;
    tpt_timer_AddRef(obj);
    EnterCriticalSection(&obj->lock);
    if (obj->canceled) {
        LeaveCriticalSection(&obj->lock);
        tpt_timer_Release(obj);
        return;
    }
    obj->active_callbacks++;
    obj->callback_tid = GetCurrentThreadId();
    LeaveCriticalSection(&obj->lock);

    tpt_invoke_handler(obj->elapsed_handler, obj);

    EnterCriticalSection(&obj->lock);
    obj->active_callbacks--;
    if (obj->active_callbacks == 0) obj->callback_tid = 0;
    if (!obj->periodic) obj->canceled = 1;
    if (obj->canceled && obj->active_callbacks == 0 && !obj->destroyed_fired) {
        obj->destroyed_fired = 1;
        fire_destroyed = 1;
        if (obj->sched_ref) {
            obj->sched_ref = 0;
            drop_sched = 1;
        }
    }
    LeaveCriticalSection(&obj->lock);

    if (fire_destroyed && obj->destroyed_handler)
        tpt_invoke_handler(obj->destroyed_handler, obj);
    if (drop_sched) tpt_timer_Release(obj);
    tpt_timer_Release(obj);
}

static HRESULT tpt_create_impl(void *handler, INT64 duration, void *destroyed, int periodic,
                               void **out)
{
    tpt_timer *obj;
    LONGLONG rel;
    FILETIME ft;
    DWORD period_ms = 0;

    if (!out) return E_POINTER;
    *out = NULL;
    if (!handler) return E_INVALIDARG;
    if (duration < 0 || (periodic && duration == 0)) return E_INVALIDARG;

    obj = (tpt_timer *)calloc(1, sizeof(tpt_timer));
    if (!obj) return E_OUTOFMEMORY;
    obj->base.vtbl = tpt_timer_vtable;
    obj->base.refs = 2; /* 1 for caller, 1 for active scheduled timer */
    obj->base.name = "Windows.System.Threading.ThreadPoolTimer";
    InitializeCriticalSection(&obj->lock);
    obj->periodic = periodic;
    obj->period = periodic ? duration : 0;
    obj->delay = periodic ? 0 : duration;
    obj->sched_ref = 1;
    obj->elapsed_handler = handler;
    obj->destroyed_handler = destroyed;
    tpt_unk_addref(handler);
    tpt_unk_addref(destroyed);

    obj->tp_timer = CreateThreadpoolTimer(tpt_timer_callback, obj, NULL);
    if (!obj->tp_timer) {
        obj->sched_ref = 0;
        obj->base.refs = 1;
        tpt_timer_Release(obj);
        return HRESULT_FROM_WIN32(GetLastError());
    }

    rel = (duration == 0) ? -1LL : -duration;
    ft.dwLowDateTime = (DWORD)((ULONGLONG)rel & 0xffffffffULL);
    ft.dwHighDateTime = (DWORD)((ULONGLONG)rel >> 32);
    if (periodic) {
        ULONGLONG ms = (ULONGLONG)duration / 10000ULL;
        if (ms == 0) ms = 1;
        if (ms > 0xffffffffULL) ms = 0xffffffffULL;
        period_ms = (DWORD)ms;
    }
    SetThreadpoolTimer(obj->tp_timer, &ft, period_ms, 0);
    svc_tracef("ThreadPoolTimer %s duration_ms=%lld", periodic ? "periodic" : "oneshot",
               (long long)(duration / 10000LL));
    *out = obj;
    return S_OK;
}

static svc_object tpt_static;

static HRESULT __stdcall tpt_statics_QI(svc_object *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &tpt_iid_statics) || IsEqualIID(id, &svc_iid_inspectable) ||
        IsEqualIID(id, &svc_iid_unknown) || IsEqualIID(id, &tpt_iid_agile)) {
        *out = self;
        svc_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall tpt_CreatePeriodicTimer(svc_object *self, void *handler, INT64 period,
                                                 void **timer)
{
    (void)self;
    return tpt_create_impl(handler, period, NULL, 1, timer);
}

static HRESULT __stdcall tpt_CreateTimer(svc_object *self, void *handler, INT64 delay,
                                         void **timer)
{
    (void)self;
    return tpt_create_impl(handler, delay, NULL, 0, timer);
}

static HRESULT __stdcall tpt_CreatePeriodicTimerWithCompletion(svc_object *self, void *handler,
                                                               INT64 period, void *destroyed,
                                                               void **timer)
{
    (void)self;
    return tpt_create_impl(handler, period, destroyed, 1, timer);
}

static HRESULT __stdcall tpt_CreateTimerWithCompletion(svc_object *self, void *handler,
                                                       INT64 delay, void *destroyed, void **timer)
{
    (void)self;
    return tpt_create_impl(handler, delay, destroyed, 0, timer);
}

static const void *tpt_statics_vtable[] = {
    (const void *)tpt_statics_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)tpt_CreatePeriodicTimer, (const void *)tpt_CreateTimer,
    (const void *)tpt_CreatePeriodicTimerWithCompletion,
    (const void *)tpt_CreateTimerWithCompletion,
};


/* ------------------------------------ Windows.Foundation.Diagnostics.Logging* */

static const GUID svc_iid_logging_options_factory = {
    0xd713c6cb, 0x98ab, 0x464b, {0x9f, 0x22, 0xa3, 0x26, 0x84, 0x78, 0x36, 0x8a}};
static const GUID svc_iid_logging_options = {
    0x39b595d6, 0x789c, 0x4274, {0xb4, 0x64, 0x63, 0x54, 0x71, 0xa3, 0x9f, 0x5c}};
static const GUID svc_iid_logging_channel_options_factory = {
    0xa93151da, 0x7faf, 0x4191, {0x87, 0x55, 0x5e, 0x86, 0xdc, 0x65, 0xd8, 0x96}};
static const GUID svc_iid_logging_channel_options = {
    0x3924a5e4, 0xc779, 0x4249, {0x90, 0x7e, 0x67, 0x74, 0xd1, 0x91, 0x10, 0x35}};
static const GUID svc_iid_logging_channel_factory = {
    0x4edc5b9c, 0xfa3d, 0x4fd2, {0xb0, 0x92, 0x81, 0xda, 0x3a, 0xce, 0x79, 0x45}};
static const GUID svc_iid_logging_channel_factory2 = {
    0x4c6ef5dd, 0x3b27, 0x4dc9, {0x99, 0xf0, 0x29, 0x9c, 0x6e, 0x46, 0x03, 0xa1}};
static const GUID svc_iid_logging_channel = {
    0xeb6a9f06, 0xe7c9, 0x4e64, {0xa3, 0x67, 0xf1, 0xa4, 0x6a, 0x51, 0x80, 0x69}};
static const GUID svc_iid_logging_channel2 = {
    0x38598e40, 0x8b2f, 0x4e92, {0x88, 0xa6, 0x7c, 0x65, 0xf3, 0x00, 0x60, 0x46}};
static const GUID svc_iid_logging_target = {
    0x65f16c35, 0xe388, 0x4e26, {0xb1, 0x7a, 0xf5, 0x1c, 0xd3, 0xa8, 0x39, 0x16}};
static const GUID svc_iid_logging_fields = {
    0xd7f6b7af, 0x762d, 0x4579, {0x83, 0xbd, 0x52, 0xc2, 0x3b, 0xc3, 0x33, 0xbc}};
static const GUID svc_iid_diag_closable = {
    0x30d5a829, 0x7fa4, 0x4026, {0x83, 0xbb, 0xd7, 0x5b, 0xae, 0x4e, 0xa9, 0x9e}};

/* ---- LoggingOptions ---- */
typedef struct {
    const void **vtbl;
    LONG refs;
    long long keywords;
    int tags;
    short task;
    int opcode;
    GUID activity_id;
    GUID related_activity_id;
} diag_logging_options;

static ULONG __stdcall dlo_AddRef(diag_logging_options *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall dlo_Release(diag_logging_options *self)
{
    LONG left = InterlockedDecrement(&self->refs);
    if (left == 0) free(self);
    return (ULONG)left;
}
static HRESULT __stdcall dlo_QI(diag_logging_options *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_logging_options) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        dlo_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall dlo_get_iids(diag_logging_options *self, ULONG *count, GUID **ids)
{
    (void)self;
    if (count) *count = 0;
    if (ids) *ids = NULL;
    return S_OK;
}
static HRESULT __stdcall dlo_get_name(diag_logging_options *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = svc_string(L"Windows.Foundation.Diagnostics.LoggingOptions");
    return S_OK;
}
static HRESULT __stdcall dlo_get_trust(diag_logging_options *self, int *out)
{
    (void)self;
    if (out) *out = 0;
    return S_OK;
}
static HRESULT __stdcall dlo_get_Keywords(diag_logging_options *self, long long *v)
{
    if (!v) return E_POINTER;
    *v = self->keywords;
    return S_OK;
}
static HRESULT __stdcall dlo_put_Keywords(diag_logging_options *self, long long v)
{
    self->keywords = v;
    return S_OK;
}
static HRESULT __stdcall dlo_get_Tags(diag_logging_options *self, int *v)
{
    if (!v) return E_POINTER;
    *v = self->tags;
    return S_OK;
}
static HRESULT __stdcall dlo_put_Tags(diag_logging_options *self, int v)
{
    self->tags = v;
    return S_OK;
}
static HRESULT __stdcall dlo_get_Task(diag_logging_options *self, short *v)
{
    if (!v) return E_POINTER;
    *v = self->task;
    return S_OK;
}
static HRESULT __stdcall dlo_put_Task(diag_logging_options *self, short v)
{
    self->task = v;
    return S_OK;
}
static HRESULT __stdcall dlo_get_Opcode(diag_logging_options *self, int *v)
{
    if (!v) return E_POINTER;
    *v = self->opcode;
    return S_OK;
}
static HRESULT __stdcall dlo_put_Opcode(diag_logging_options *self, int v)
{
    self->opcode = v;
    return S_OK;
}
static HRESULT __stdcall dlo_get_ActivityId(diag_logging_options *self, GUID *v)
{
    if (!v) return E_POINTER;
    *v = self->activity_id;
    return S_OK;
}
static HRESULT __stdcall dlo_put_ActivityId(diag_logging_options *self, GUID v)
{
    self->activity_id = v;
    return S_OK;
}
static HRESULT __stdcall dlo_get_RelatedActivityId(diag_logging_options *self, GUID *v)
{
    if (!v) return E_POINTER;
    *v = self->related_activity_id;
    return S_OK;
}
static HRESULT __stdcall dlo_put_RelatedActivityId(diag_logging_options *self, GUID v)
{
    self->related_activity_id = v;
    return S_OK;
}
static const void *diag_logging_options_vtable[] = {
    (const void *)dlo_QI, (const void *)dlo_AddRef, (const void *)dlo_Release,
    (const void *)dlo_get_iids, (const void *)dlo_get_name, (const void *)dlo_get_trust,
    (const void *)dlo_get_Keywords, (const void *)dlo_put_Keywords,
    (const void *)dlo_get_Tags, (const void *)dlo_put_Tags,
    (const void *)dlo_get_Task, (const void *)dlo_put_Task,
    (const void *)dlo_get_Opcode, (const void *)dlo_put_Opcode,
    (const void *)dlo_get_ActivityId, (const void *)dlo_put_ActivityId,
    (const void *)dlo_get_RelatedActivityId, (const void *)dlo_put_RelatedActivityId,
};

static HRESULT diag_logging_options_new(long long keywords, void **out)
{
    diag_logging_options *o;
    if (!out) return E_POINTER;
    o = (diag_logging_options *)calloc(1, sizeof(*o));
    if (!o) return E_OUTOFMEMORY;
    o->vtbl = diag_logging_options_vtable;
    o->refs = 1;
    o->keywords = keywords;
    *out = o;
    return S_OK;
}

static svc_object diag_logging_options_static;
static HRESULT __stdcall dlo_factory_QI(svc_object *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_logging_options_factory) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall dlo_factory_CreateWithKeywords(svc_object *self, long long keywords,
                                                        void **out)
{
    (void)self;
    return diag_logging_options_new(keywords, out);
}
static const void *diag_logging_options_factory_vtable[] = {
    (const void *)dlo_factory_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)dlo_factory_CreateWithKeywords,
};

/* ---- LoggingChannelOptions ---- */
typedef struct {
    const void **vtbl;
    LONG refs;
    GUID group;
} diag_logging_channel_options;

static ULONG __stdcall dlco_AddRef(diag_logging_channel_options *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall dlco_Release(diag_logging_channel_options *self)
{
    LONG left = InterlockedDecrement(&self->refs);
    if (left == 0) free(self);
    return (ULONG)left;
}
static HRESULT __stdcall dlco_QI(diag_logging_channel_options *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_logging_channel_options) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        dlco_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall dlco_get_iids(diag_logging_channel_options *self, ULONG *count, GUID **ids)
{
    (void)self;
    if (count) *count = 0;
    if (ids) *ids = NULL;
    return S_OK;
}
static HRESULT __stdcall dlco_get_name(diag_logging_channel_options *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = svc_string(L"Windows.Foundation.Diagnostics.LoggingChannelOptions");
    return S_OK;
}
static HRESULT __stdcall dlco_get_trust(diag_logging_channel_options *self, int *out)
{
    (void)self;
    if (out) *out = 0;
    return S_OK;
}
static HRESULT __stdcall dlco_get_Group(diag_logging_channel_options *self, GUID *v)
{
    if (!v) return E_POINTER;
    *v = self->group;
    return S_OK;
}
static HRESULT __stdcall dlco_put_Group(diag_logging_channel_options *self, GUID v)
{
    self->group = v;
    return S_OK;
}
static const void *diag_logging_channel_options_vtable[] = {
    (const void *)dlco_QI, (const void *)dlco_AddRef, (const void *)dlco_Release,
    (const void *)dlco_get_iids, (const void *)dlco_get_name, (const void *)dlco_get_trust,
    (const void *)dlco_get_Group, (const void *)dlco_put_Group,
};

static HRESULT diag_logging_channel_options_new(const GUID *group, void **out)
{
    diag_logging_channel_options *o;
    if (!out) return E_POINTER;
    o = (diag_logging_channel_options *)calloc(1, sizeof(*o));
    if (!o) return E_OUTOFMEMORY;
    o->vtbl = diag_logging_channel_options_vtable;
    o->refs = 1;
    if (group) o->group = *group;
    *out = o;
    return S_OK;
}

static svc_object diag_logging_channel_options_static;
static HRESULT __stdcall dlco_factory_QI(svc_object *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_logging_channel_options_factory) ||
        IsEqualIID(id, &svc_iid_unknown) || IsEqualIID(id, &svc_iid_inspectable) ||
        IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall dlco_factory_Create(svc_object *self, GUID group, void **out)
{
    (void)self;
    return diag_logging_channel_options_new(&group, out);
}
static const void *diag_logging_channel_options_factory_vtable[] = {
    (const void *)dlco_factory_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)dlco_factory_Create,
};

/* ---- LoggingChannel (ILoggingChannel, ILoggingChannel2, ILoggingTarget, IClosable) ---- */
typedef struct diag_logging_channel diag_logging_channel;
typedef struct {
    const void **vtbl;
    diag_logging_channel *owner;
} diag_logging_subface;

struct diag_logging_channel {
    const void **vtbl; /* ILoggingChannel */
    LONG refs;
    diag_logging_subface ch2;
    diag_logging_subface target;
    diag_logging_subface closable;
    WCHAR name[128];
    GUID id;
};

static ULONG __stdcall dlc_AddRef(diag_logging_channel *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall dlc_Release(diag_logging_channel *self)
{
    LONG left = InterlockedDecrement(&self->refs);
    if (left == 0) free(self);
    return (ULONG)left;
}
static HRESULT __stdcall dlc_QI(diag_logging_channel *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_logging_channel) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        dlc_AddRef(self);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_logging_channel2)) {
        *out = &self->ch2;
        dlc_AddRef(self);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_logging_target)) {
        *out = &self->target;
        dlc_AddRef(self);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_diag_closable)) {
        *out = &self->closable;
        dlc_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall dlc_sub_QI(diag_logging_subface *sub, const GUID *id, void **out)
{
    return dlc_QI(sub->owner, id, out);
}
static ULONG __stdcall dlc_sub_AddRef(diag_logging_subface *sub) { return dlc_AddRef(sub->owner); }
static ULONG __stdcall dlc_sub_Release(diag_logging_subface *sub) { return dlc_Release(sub->owner); }
static HRESULT __stdcall dlc_get_iids(void *self, ULONG *count, GUID **ids)
{
    (void)self;
    if (count) *count = 0;
    if (ids) *ids = NULL;
    return S_OK;
}
static HRESULT __stdcall dlc_get_name(void *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = svc_string(L"Windows.Foundation.Diagnostics.LoggingChannel");
    return S_OK;
}
static HRESULT __stdcall dlc_get_trust(void *self, int *out)
{
    (void)self;
    if (out) *out = 0;
    return S_OK;
}
static HRESULT __stdcall dlc_get_Name(diag_logging_channel *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = svc_string(self->name);
    return S_OK;
}
static HRESULT __stdcall dlc_get_Enabled(diag_logging_channel *self, unsigned char *out)
{
    (void)self;
    if (out) *out = 0;
    return S_OK;
}
static HRESULT __stdcall dlc_get_Level(diag_logging_channel *self, int *out)
{
    (void)self;
    if (out) *out = 0;
    return S_OK;
}
static HRESULT __stdcall dlc_noop_ok(void *self)
{
    (void)self;
    return S_OK;
}
static HRESULT __stdcall dlc_add_LoggingEnabled(diag_logging_channel *self, void *handler,
                                                long long *token)
{
    (void)self;
    (void)handler;
    if (token) *token = 1;
    return S_OK;
}
static HRESULT __stdcall dlc_remove_LoggingEnabled(diag_logging_channel *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}
static const void *diag_logging_channel_vtable[] = {
    (const void *)dlc_QI, (const void *)dlc_AddRef, (const void *)dlc_Release,
    (const void *)dlc_get_iids, (const void *)dlc_get_name, (const void *)dlc_get_trust,
    (const void *)dlc_get_Name, (const void *)dlc_get_Enabled, (const void *)dlc_get_Level,
    (const void *)dlc_noop_ok, (const void *)dlc_noop_ok,
    (const void *)dlc_noop_ok, (const void *)dlc_noop_ok,
    (const void *)dlc_add_LoggingEnabled, (const void *)dlc_remove_LoggingEnabled,
};

static HRESULT __stdcall dlc2_get_Id(diag_logging_subface *sub, GUID *out)
{
    if (!out) return E_POINTER;
    *out = sub->owner->id;
    return S_OK;
}
static const void *diag_logging_channel2_vtable[] = {
    (const void *)dlc_sub_QI, (const void *)dlc_sub_AddRef, (const void *)dlc_sub_Release,
    (const void *)dlc_get_iids, (const void *)dlc_get_name, (const void *)dlc_get_trust,
    (const void *)dlc2_get_Id,
};

static HRESULT __stdcall dlt_IsEnabled(diag_logging_subface *sub, unsigned char *out)
{
    (void)sub;
    if (out) *out = 0;
    return S_OK;
}
static HRESULT __stdcall dlt_IsEnabledWithLevel(diag_logging_subface *sub, int level,
                                                unsigned char *out)
{
    (void)sub;
    (void)level;
    if (out) *out = 0;
    return S_OK;
}
static HRESULT __stdcall dlt_IsEnabledWithLevelAndKeywords(diag_logging_subface *sub, int level,
                                                           long long keywords, unsigned char *out)
{
    (void)sub;
    (void)level;
    (void)keywords;
    if (out) *out = 0;
    return S_OK;
}
static HRESULT __stdcall dlt_StartActivity_notimpl(diag_logging_subface *sub, void *a, void *b,
                                                   void *c, void *d)
{
    (void)sub; (void)a; (void)b; (void)c; (void)d;
    return E_NOTIMPL;
}
static const void *diag_logging_target_vtable[] = {
    (const void *)dlc_sub_QI, (const void *)dlc_sub_AddRef, (const void *)dlc_sub_Release,
    (const void *)dlc_get_iids, (const void *)dlc_get_name, (const void *)dlc_get_trust,
    (const void *)dlt_IsEnabled,
    (const void *)dlt_IsEnabledWithLevel,
    (const void *)dlt_IsEnabledWithLevelAndKeywords,
    (const void *)dlc_noop_ok, /* LogEvent */
    (const void *)dlc_noop_ok, /* LogEventWithFields */
    (const void *)dlc_noop_ok, /* LogEventWithFieldsAndLevel */
    (const void *)dlc_noop_ok, /* LogEventWithFieldsAndLevelAndOptions */
    (const void *)dlt_StartActivity_notimpl,
    (const void *)dlt_StartActivity_notimpl,
    (const void *)dlt_StartActivity_notimpl,
    (const void *)dlt_StartActivity_notimpl,
};

static const void *diag_logging_closable_vtable[] = {
    (const void *)dlc_sub_QI, (const void *)dlc_sub_AddRef, (const void *)dlc_sub_Release,
    (const void *)dlc_noop_ok, /* Close */
};

static HRESULT diag_logging_channel_new(hstring_t name, const GUID *id, void **out)
{
    diag_logging_channel *c;
    const WCHAR *txt;
    if (!out) return E_POINTER;
    c = (diag_logging_channel *)calloc(1, sizeof(*c));
    if (!c) return E_OUTOFMEMORY;
    c->vtbl = diag_logging_channel_vtable;
    c->refs = 1;
    c->ch2.vtbl = diag_logging_channel2_vtable;
    c->ch2.owner = c;
    c->target.vtbl = diag_logging_target_vtable;
    c->target.owner = c;
    c->closable.vtbl = diag_logging_closable_vtable;
    c->closable.owner = c;
    txt = svc_string_text(name);
    if (txt) {
        wcsncpy(c->name, txt, 127);
        c->name[127] = 0;
    }
    if (id) c->id = *id;
    *out = c;
    return S_OK;
}

static svc_object diag_logging_channel_static;
static svc_object diag_logging_channel_static2;
static HRESULT __stdcall dlc_factory_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_logging_channel_factory) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = &diag_logging_channel_static;
        svc_AddRef(&diag_logging_channel_static);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_logging_channel_factory2)) {
        *out = &diag_logging_channel_static2;
        svc_AddRef(&diag_logging_channel_static2);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall dlc_factory_Create(svc_object *self, hstring_t name, void **out)
{
    (void)self;
    return diag_logging_channel_new(name, NULL, out);
}
static const void *diag_logging_channel_factory_vtable[] = {
    (const void *)dlc_factory_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)dlc_factory_Create,
};
static HRESULT __stdcall dlc_factory2_CreateWithOptions(svc_object *self, hstring_t name,
                                                        void *options, void **out)
{
    (void)self;
    (void)options;
    return diag_logging_channel_new(name, NULL, out);
}
static HRESULT __stdcall dlc_factory2_CreateWithOptionsAndId(svc_object *self, hstring_t name,
                                                             void *options, GUID id, void **out)
{
    (void)self;
    (void)options;
    return diag_logging_channel_new(name, &id, out);
}
static const void *diag_logging_channel_factory2_vtable[] = {
    (const void *)dlc_factory_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)dlc_factory2_CreateWithOptions,
    (const void *)dlc_factory2_CreateWithOptionsAndId,
};

/* ---- LoggingFields (ILoggingFields: slots 6..155 are all input-only Add* / Clear / Struct) ---- */
typedef struct {
    const void **vtbl;
    LONG refs;
} diag_logging_fields;

static ULONG __stdcall dlf_AddRef(diag_logging_fields *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall dlf_Release(diag_logging_fields *self)
{
    LONG left = InterlockedDecrement(&self->refs);
    if (left == 0) free(self);
    return (ULONG)left;
}
static HRESULT __stdcall dlf_QI(diag_logging_fields *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_logging_fields) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        dlf_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall dlf_get_iids(diag_logging_fields *self, ULONG *count, GUID **ids)
{
    (void)self;
    if (count) *count = 0;
    if (ids) *ids = NULL;
    return S_OK;
}
static HRESULT __stdcall dlf_get_name(diag_logging_fields *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = svc_string(L"Windows.Foundation.Diagnostics.LoggingFields");
    return S_OK;
}
static HRESULT __stdcall dlf_get_trust(diag_logging_fields *self, int *out)
{
    (void)self;
    if (out) *out = 0;
    return S_OK;
}
static HRESULT __stdcall dlf_noop_ok(void *self)
{
    (void)self;
    return S_OK;
}
#define DLF_OK10 \
    (const void *)dlf_noop_ok, (const void *)dlf_noop_ok, (const void *)dlf_noop_ok, \
    (const void *)dlf_noop_ok, (const void *)dlf_noop_ok, (const void *)dlf_noop_ok, \
    (const void *)dlf_noop_ok, (const void *)dlf_noop_ok, (const void *)dlf_noop_ok, \
    (const void *)dlf_noop_ok
static const void *diag_logging_fields_vtable[] = {
    (const void *)dlf_QI, (const void *)dlf_AddRef, (const void *)dlf_Release,
    (const void *)dlf_get_iids, (const void *)dlf_get_name, (const void *)dlf_get_trust,
    DLF_OK10, DLF_OK10, DLF_OK10, DLF_OK10, DLF_OK10,
    DLF_OK10, DLF_OK10, DLF_OK10, DLF_OK10, DLF_OK10,
    DLF_OK10, DLF_OK10, DLF_OK10, DLF_OK10, DLF_OK10,
};
#undef DLF_OK10

static HRESULT diag_logging_fields_new(void **out)
{
    diag_logging_fields *f;
    if (!out) return E_POINTER;
    f = (diag_logging_fields *)calloc(1, sizeof(*f));
    if (!f) return E_OUTOFMEMORY;
    f->vtbl = diag_logging_fields_vtable;
    f->refs = 1;
    *out = f;
    return S_OK;
}


/* ----------------------- Windows.Networking.PushNotifications.PushNotificationChannelManager */

static const GUID svc_iid_push_mgr_statics = {
    0x8baf9b65, 0x77a1, 0x4588, {0xbd, 0x19, 0x86, 0x15, 0x29, 0xa9, 0xdc, 0xf0}};

static svc_object push_mgr_static;

static HRESULT __stdcall push_mgr_QI(svc_object *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_push_mgr_statics) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall push_mgr_CreateForAppAsync(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    return async_operation_iid(&svc_iid_async_push_channel, E_FAIL, NULL, out);
}

static HRESULT __stdcall push_mgr_CreateForAppWithIdAsync(svc_object *self, hstring_t app_id,
                                                          void **out)
{
    (void)self;
    (void)app_id;
    if (!out) return E_POINTER;
    return async_operation_iid(&svc_iid_async_push_channel, E_FAIL, NULL, out);
}

static HRESULT __stdcall push_mgr_CreateForTileAsync(svc_object *self, hstring_t tile_id,
                                                     void **out)
{
    (void)self;
    (void)tile_id;
    if (!out) return E_POINTER;
    return async_operation_iid(&svc_iid_async_push_channel, E_FAIL, NULL, out);
}

static const void *push_mgr_statics_vtable[] = {
    (const void *)push_mgr_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)push_mgr_CreateForAppAsync,
    (const void *)push_mgr_CreateForAppWithIdAsync,
    (const void *)push_mgr_CreateForTileAsync,
};

/* ----------------------- Windows.Networking.XboxLive.XboxLiveDeviceAddress */

static const GUID svc_iid_xbl_devaddr_statics = {
    0x5954a819, 0x4a79, 0x4931, {0x82, 0x7c, 0x7f, 0x50, 0x3e, 0x96, 0x32, 0x63}};
static const GUID svc_iid_xbl_devaddr = {
    0xf5bbd279, 0x3c86, 0x4b57, {0xa3, 0x1a, 0xb9, 0x46, 0x24, 0x08, 0xfd, 0x01}};

typedef struct {
    svc_object base;
    unsigned char snapshot[1024];
    UINT32 snapshot_len;
    BOOLEAN is_valid;
    BOOLEAN is_local;
} xbl_devaddr_object;

static svc_object xbl_devaddr_static;
static xbl_devaddr_object xbl_devaddr_local;

static HRESULT __stdcall xbl_devaddr_QI(xbl_devaddr_object *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_xbl_devaddr) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(&self->base);
        return S_OK;
    }
    svc_trace_iid("xbl_devaddr QI miss", id);
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall xbl_devaddr_add_SnapshotChanged(xbl_devaddr_object *self, void *handler,
                                                         long long *token)
{
    (void)self;
    (void)handler;
    if (token) *token = 1;
    return S_OK;
}

static HRESULT __stdcall xbl_devaddr_remove_SnapshotChanged(xbl_devaddr_object *self,
                                                            long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

static HRESULT __stdcall xbl_devaddr_GetSnapshotAsBase64(xbl_devaddr_object *self, hstring_t *out)
{
    unsigned char encoded[1400];
    WCHAR wide[1400];
    unsigned len, i;
    if (!out) return E_POINTER;
    if (!self->snapshot_len) {
        *out = svc_string(L"");
        return S_OK;
    }
    len = base64_encode(self->snapshot, self->snapshot_len, encoded);
    for (i = 0; i < len && i < 1399; i++) wide[i] = (WCHAR)encoded[i];
    wide[i] = 0;
    *out = svc_string_len(wide, i);
    return S_OK;
}

static HRESULT __stdcall xbl_devaddr_GetSnapshotAsBuffer(xbl_devaddr_object *self, void **out)
{
    winrt_buffer *buf;
    if (!out) return E_POINTER;
    buf = buffer_from_bytes(self->snapshot, self->snapshot_len);
    if (!buf) return E_OUTOFMEMORY;
    *out = buf;
    return S_OK;
}

static HRESULT __stdcall xbl_devaddr_GetSnapshotAsBytes(xbl_devaddr_object *self, UINT32 cap,
                                                        unsigned char *buf, UINT32 *written)
{
    if (written) *written = self->snapshot_len;
    if (buf && cap > 0 && self->snapshot_len > 0) {
        UINT32 n = cap < self->snapshot_len ? cap : self->snapshot_len;
        memcpy(buf, self->snapshot, n);
        if (written) *written = n;
    }
    return S_OK;
}

static HRESULT __stdcall xbl_devaddr_Compare(xbl_devaddr_object *self, xbl_devaddr_object *other,
                                             INT32 *out)
{
    if (!out) return E_POINTER;
    if (!other) {
        *out = 1;
        return S_OK;
    }
    if (self == other) {
        *out = 0;
        return S_OK;
    }
    if (self->snapshot_len != other->snapshot_len) {
        *out = self->snapshot_len < other->snapshot_len ? -1 : 1;
        return S_OK;
    }
    *out = memcmp(self->snapshot, other->snapshot, self->snapshot_len);
    return S_OK;
}

static HRESULT __stdcall xbl_devaddr_get_IsValid(xbl_devaddr_object *self, BOOLEAN *out)
{
    if (!out) return E_POINTER;
    *out = self->is_valid;
    return S_OK;
}

static HRESULT __stdcall xbl_devaddr_get_IsLocal(xbl_devaddr_object *self, BOOLEAN *out)
{
    if (!out) return E_POINTER;
    *out = self->is_local;
    return S_OK;
}

static HRESULT __stdcall xbl_devaddr_get_NetworkAccessKind(xbl_devaddr_object *self, INT32 *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0; /* XboxLiveNetworkAccessKind_Open */
    return S_OK;
}

static const void *xbl_devaddr_vtable[] = {
    (const void *)xbl_devaddr_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)xbl_devaddr_add_SnapshotChanged,
    (const void *)xbl_devaddr_remove_SnapshotChanged,
    (const void *)xbl_devaddr_GetSnapshotAsBase64,
    (const void *)xbl_devaddr_GetSnapshotAsBuffer,
    (const void *)xbl_devaddr_GetSnapshotAsBytes,
    (const void *)xbl_devaddr_Compare,
    (const void *)xbl_devaddr_get_IsValid,
    (const void *)xbl_devaddr_get_IsLocal,
    (const void *)xbl_devaddr_get_NetworkAccessKind,
};

static HRESULT xbl_devaddr_from_bytes(const unsigned char *data, UINT32 len, void **out)
{
    xbl_devaddr_object *obj;
    if (!out) return E_POINTER;
    obj = (xbl_devaddr_object *)calloc(1, sizeof(*obj));
    if (!obj) return E_OUTOFMEMORY;
    obj->base.vtbl = xbl_devaddr_vtable;
    obj->base.refs = 1;
    obj->base.name = "Windows.Networking.XboxLive.XboxLiveDeviceAddress";
    if (data && len > 0) {
        if (len > sizeof(obj->snapshot)) len = (UINT32)sizeof(obj->snapshot);
        memcpy(obj->snapshot, data, len);
        obj->snapshot_len = len;
        obj->is_valid = TRUE;
    }
    *out = obj;
    return S_OK;
}

static HRESULT __stdcall xbl_devaddr_statics_QI(svc_object *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_xbl_devaddr_statics) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(self);
        return S_OK;
    }
    svc_trace_iid("xbl_devaddr_statics QI miss", id);
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall xbl_devaddr_CreateFromSnapshotBase64(svc_object *self, hstring_t base64,
                                                              void **out)
{
    const WCHAR *wide = svc_string_text(base64);
    char narrow[1400];
    unsigned char decoded[1024];
    unsigned i, len;
    (void)self;
    if (!out) return E_POINTER;
    for (i = 0; i < 1399 && wide && wide[i]; i++) narrow[i] = (char)wide[i];
    narrow[i] = 0;
    len = base64_decode(narrow, decoded);
    return xbl_devaddr_from_bytes(decoded, len, out);
}

static HRESULT __stdcall xbl_devaddr_CreateFromSnapshotBuffer(svc_object *self, void *buf,
                                                              void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (!buf) return xbl_devaddr_from_bytes(NULL, 0, out);
    return xbl_devaddr_from_bytes(buffer_bytes(buf), buffer_length(buf), out);
}

static HRESULT __stdcall xbl_devaddr_CreateFromSnapshotBytes(svc_object *self, UINT32 len,
                                                             const unsigned char *buf, void **out)
{
    (void)self;
    return xbl_devaddr_from_bytes(buf, len, out);
}

static HRESULT __stdcall xbl_devaddr_GetLocal(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    svc_AddRef(&xbl_devaddr_local.base);
    *out = &xbl_devaddr_local;
    return S_OK;
}

static HRESULT __stdcall xbl_devaddr_get_MaxSnapshotBytesSize(svc_object *self, UINT32 *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 1024;
    return S_OK;
}

static const void *xbl_devaddr_statics_vtable[] = {
    (const void *)xbl_devaddr_statics_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)xbl_devaddr_CreateFromSnapshotBase64,
    (const void *)xbl_devaddr_CreateFromSnapshotBuffer,
    (const void *)xbl_devaddr_CreateFromSnapshotBytes,
    (const void *)xbl_devaddr_GetLocal,
    (const void *)xbl_devaddr_get_MaxSnapshotBytesSize,
};


/* ----------------------- Windows.ApplicationModel.Resources.Core.ResourceContext */

static const GUID svc_iid_resctx_statics = {
    0x98be9d6c, 0x6338, 0x4b31, {0x99, 0xdf, 0xb2, 0xb4, 0x42, 0xf1, 0x71, 0x49}};
static const GUID svc_iid_resctx_statics2 = {
    0x41f752ef, 0x12af, 0x41b9, {0xab, 0x36, 0xb1, 0xeb, 0x4b, 0x51, 0x24, 0x60}};
static const GUID svc_iid_resctx_statics3 = {
    0x20cf492c, 0xaf0f, 0x450b, {0x9d, 0xa6, 0x10, 0x6d, 0xd0, 0xc2, 0x9a, 0x39}};
static const GUID svc_iid_resctx = {
    0x2fa22f4b, 0x707e, 0x4b27, {0xad, 0x0d, 0xd0, 0xd8, 0xcd, 0x46, 0x8f, 0xd2}};
static const GUID svc_iid_map_str_str = {
    0xf6d1f700, 0x49c2, 0x52ae, {0x81, 0x54, 0x82, 0x6f, 0x99, 0x08, 0x77, 0x3c}};
static const GUID svc_iid_obsmap_str_str = {
    0x1e036276, 0x2f60, 0x55f6, {0xb7, 0xf3, 0xf8, 0x60, 0x79, 0xe6, 0x90, 0x0b}};
static const GUID svc_iid_mapview_str_str = {
    0xac7f26f2, 0xfeb7, 0x5b2a, {0x8a, 0xc4, 0x34, 0x5b, 0xc6, 0x2c, 0xae, 0xde}};
static const GUID svc_iid_vecview_str = {
    0x2f13c006, 0xa03a, 0x5f69, {0xb0, 0x90, 0x75, 0xa4, 0x3e, 0x33, 0x42, 0x3e}};
static const GUID svc_iid_iterable_str = {
    0xe2fcc7c1, 0x3bfc, 0x5a0b, {0xb2, 0xb0, 0x72, 0xe7, 0x69, 0xd1, 0xcb, 0x7e}};
static const GUID svc_iid_iterator_str = {
    0x8c304ebb, 0x6615, 0x50a4, {0x88, 0x29, 0x87, 0x9e, 0xcd, 0x44, 0x32, 0x36}};

static svc_object resctx_static;
static svc_object resctx_static2;
static svc_object resctx_static3;
static svc_object resctx_singleton;
static svc_object resctx_qual_map;
static svc_object resctx_qual_obs;
static svc_object resctx_qual_view;
static svc_object resctx_lang_vec;
static svc_object resctx_lang_iter;

typedef struct {
    svc_object base;
    UINT32 index;
} resctx_str_iterator;

static HRESULT __stdcall resctx_str_it_QI(resctx_str_iterator *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_iterator_str) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(&self->base);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall resctx_str_it_Current(resctx_str_iterator *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    if (self->index != 0) {
        *out = NULL;
        return E_BOUNDS;
    }
    *out = svc_string(L"en-US");
    return S_OK;
}

static HRESULT __stdcall resctx_str_it_HasCurrent(resctx_str_iterator *self, BOOLEAN *out)
{
    if (!out) return E_POINTER;
    *out = self->index == 0 ? TRUE : FALSE;
    return S_OK;
}

static HRESULT __stdcall resctx_str_it_MoveNext(resctx_str_iterator *self, BOOLEAN *out)
{
    if (self->index == 0) self->index = 1;
    if (out) *out = FALSE;
    return S_OK;
}

static HRESULT __stdcall resctx_str_it_GetMany(resctx_str_iterator *self, UINT32 cap,
                                               hstring_t *items, UINT32 *actual)
{
    if (self->index == 0 && cap > 0 && items) {
        items[0] = svc_string(L"en-US");
        self->index = 1;
        if (actual) *actual = 1;
        return S_OK;
    }
    if (actual) *actual = 0;
    return S_OK;
}

static const void *resctx_str_it_vtable[] = {
    (const void *)resctx_str_it_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)resctx_str_it_Current, (const void *)resctx_str_it_HasCurrent,
    (const void *)resctx_str_it_MoveNext, (const void *)resctx_str_it_GetMany,
};

static HRESULT __stdcall resctx_lang_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_iterable_str)) {
        *out = &resctx_lang_iter;
        svc_AddRef(&resctx_lang_iter);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_vecview_str) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = &resctx_lang_vec;
        svc_AddRef(&resctx_lang_vec);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall resctx_lang_GetAt(svc_object *self, UINT32 index, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (index != 0) {
        *out = NULL;
        return E_BOUNDS;
    }
    *out = svc_string(L"en-US");
    return S_OK;
}

static HRESULT __stdcall resctx_lang_get_Size(svc_object *self, UINT32 *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 1;
    return S_OK;
}

static HRESULT __stdcall resctx_lang_IndexOf(svc_object *self, hstring_t val, UINT32 *index,
                                             BOOLEAN *found)
{
    const WCHAR *text = svc_string_text(val);
    (void)self;
    if (text && _wcsicmp(text, L"en-US") == 0) {
        if (index) *index = 0;
        if (found) *found = TRUE;
    } else {
        if (index) *index = 0;
        if (found) *found = FALSE;
    }
    return S_OK;
}

static HRESULT __stdcall resctx_lang_GetMany(svc_object *self, UINT32 start, UINT32 cap,
                                             hstring_t *items, UINT32 *actual)
{
    (void)self;
    if (start == 0 && cap > 0 && items) {
        items[0] = svc_string(L"en-US");
        if (actual) *actual = 1;
        return S_OK;
    }
    if (actual) *actual = 0;
    return S_OK;
}

static HRESULT __stdcall resctx_lang_First(svc_object *self, void **out)
{
    resctx_str_iterator *it;
    (void)self;
    if (!out) return E_POINTER;
    it = (resctx_str_iterator *)calloc(1, sizeof(*it));
    if (!it) return E_OUTOFMEMORY;
    it->base.vtbl = resctx_str_it_vtable;
    it->base.refs = 1;
    it->base.name = "Windows.Foundation.Collections.IIterator";
    *out = it;
    return S_OK;
}

static const void *resctx_lang_vec_vtable[] = {
    (const void *)resctx_lang_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)resctx_lang_GetAt, (const void *)resctx_lang_get_Size,
    (const void *)resctx_lang_IndexOf, (const void *)resctx_lang_GetMany,
};

static const void *resctx_lang_iter_vtable[] = {
    (const void *)resctx_lang_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)resctx_lang_First,
};

static const WCHAR *resctx_lookup_value(const WCHAR *key)
{
    if (!key) return NULL;
    if (_wcsicmp(key, L"Language") == 0) return L"en-US";
    if (_wcsicmp(key, L"HomeRegion") == 0) return L"US";
    if (_wcsicmp(key, L"Scale") == 0) return L"100";
    if (_wcsicmp(key, L"Contrast") == 0) return L"standard";
    if (_wcsicmp(key, L"LayoutDirection") == 0) return L"LTR";
    return NULL;
}

static HRESULT __stdcall resctx_qual_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_obsmap_str_str)) {
        *out = &resctx_qual_obs;
        svc_AddRef(&resctx_qual_obs);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_mapview_str_str)) {
        *out = &resctx_qual_view;
        svc_AddRef(&resctx_qual_view);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_map_str_str) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = &resctx_qual_map;
        svc_AddRef(&resctx_qual_map);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall resctx_qual_Lookup(svc_object *self, hstring_t key, hstring_t *out)
{
    const WCHAR *val = resctx_lookup_value(svc_string_text(key));
    (void)self;
    if (!out) return E_POINTER;
    if (!val) {
        *out = NULL;
        return E_BOUNDS;
    }
    *out = svc_string(val);
    return S_OK;
}

static HRESULT __stdcall resctx_qual_get_Size(svc_object *self, UINT32 *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 5;
    return S_OK;
}

static HRESULT __stdcall resctx_qual_HasKey(svc_object *self, hstring_t key, BOOLEAN *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = resctx_lookup_value(svc_string_text(key)) ? TRUE : FALSE;
    return S_OK;
}

static HRESULT __stdcall resctx_qual_GetView(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    svc_AddRef(&resctx_qual_view);
    *out = &resctx_qual_view;
    return S_OK;
}

static HRESULT __stdcall resctx_qual_Insert(svc_object *self, hstring_t key, hstring_t val,
                                            BOOLEAN *replaced)
{
    (void)self;
    (void)key;
    (void)val;
    if (replaced) *replaced = FALSE;
    return S_OK;
}

static HRESULT __stdcall resctx_qual_Remove(svc_object *self, hstring_t key)
{
    (void)self;
    (void)key;
    return S_OK;
}

static HRESULT __stdcall resctx_qual_Clear(svc_object *self)
{
    (void)self;
    return S_OK;
}

static HRESULT __stdcall resctx_qual_Split(svc_object *self, void **first, void **second)
{
    (void)self;
    if (first) *first = NULL;
    if (second) *second = NULL;
    return S_OK;
}

static HRESULT __stdcall resctx_qual_add_MapChanged(svc_object *self, void *handler,
                                                    long long *token)
{
    (void)self;
    (void)handler;
    if (token) *token = 1;
    return S_OK;
}

static HRESULT __stdcall resctx_qual_remove_MapChanged(svc_object *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

static const void *resctx_qual_map_vtable[] = {
    (const void *)resctx_qual_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)resctx_qual_Lookup, (const void *)resctx_qual_get_Size,
    (const void *)resctx_qual_HasKey, (const void *)resctx_qual_GetView,
    (const void *)resctx_qual_Insert, (const void *)resctx_qual_Remove,
    (const void *)resctx_qual_Clear,
};

static const void *resctx_qual_obs_vtable[] = {
    (const void *)resctx_qual_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)resctx_qual_add_MapChanged, (const void *)resctx_qual_remove_MapChanged,
};

static const void *resctx_qual_view_vtable[] = {
    (const void *)resctx_qual_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)resctx_qual_Lookup, (const void *)resctx_qual_get_Size,
    (const void *)resctx_qual_HasKey, (const void *)resctx_qual_Split,
};

static HRESULT __stdcall resctx_QI(svc_object *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_resctx) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef(self);
        return S_OK;
    }
    svc_trace_iid("resctx QI miss", id);
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall resctx_get_QualifierValues(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    svc_AddRef(&resctx_qual_obs);
    *out = &resctx_qual_obs;
    return S_OK;
}

static HRESULT __stdcall resctx_Reset(svc_object *self)
{
    (void)self;
    return S_OK;
}

static HRESULT __stdcall resctx_ResetByNames(svc_object *self, void *names)
{
    (void)self;
    (void)names;
    return S_OK;
}

static HRESULT __stdcall resctx_OverrideToMatch(svc_object *self, void *result)
{
    (void)self;
    (void)result;
    return S_OK;
}

static HRESULT __stdcall resctx_Clone(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    svc_AddRef(&resctx_singleton);
    *out = &resctx_singleton;
    return S_OK;
}

static HRESULT __stdcall resctx_get_Languages(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    svc_AddRef(&resctx_lang_vec);
    *out = &resctx_lang_vec;
    return S_OK;
}

static HRESULT __stdcall resctx_put_Languages(svc_object *self, void *langs)
{
    (void)self;
    (void)langs;
    return S_OK;
}

static const void *resctx_vtable[] = {
    (const void *)resctx_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)resctx_get_QualifierValues,
    (const void *)resctx_Reset,
    (const void *)resctx_ResetByNames,
    (const void *)resctx_OverrideToMatch,
    (const void *)resctx_Clone,
    (const void *)resctx_get_Languages,
    (const void *)resctx_put_Languages,
};

static HRESULT __stdcall resctx_statics_QI(svc_object *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_resctx_statics2)) {
        *out = &resctx_static2;
        svc_AddRef(&resctx_static2);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_resctx_statics3)) {
        *out = &resctx_static3;
        svc_AddRef(&resctx_static3);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_resctx_statics) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = &resctx_static;
        svc_AddRef(&resctx_static);
        return S_OK;
    }
    svc_trace_iid("resctx_statics QI miss", id);
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall resctx_CreateMatchingContext(svc_object *self, void *result, void **out)
{
    (void)self;
    (void)result;
    if (!out) return E_POINTER;
    svc_AddRef(&resctx_singleton);
    *out = &resctx_singleton;
    return S_OK;
}

static HRESULT __stdcall resctx_GetForCurrentView(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    svc_AddRef(&resctx_singleton);
    *out = &resctx_singleton;
    return S_OK;
}

static HRESULT __stdcall resctx_SetGlobalQualifierValue(svc_object *self, hstring_t key,
                                                        hstring_t val)
{
    (void)self;
    (void)key;
    (void)val;
    return S_OK;
}

static HRESULT __stdcall resctx_ResetGlobalQualifierValues(svc_object *self)
{
    (void)self;
    return S_OK;
}

static HRESULT __stdcall resctx_ResetGlobalQualifierValuesByNames(svc_object *self, void *names)
{
    (void)self;
    (void)names;
    return S_OK;
}

static HRESULT __stdcall resctx_GetForViewIndependentUse(svc_object *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    svc_AddRef(&resctx_singleton);
    *out = &resctx_singleton;
    return S_OK;
}

static HRESULT __stdcall resctx_SetGlobalQualifierValueWithPersistence(svc_object *self,
                                                                       hstring_t key, hstring_t val,
                                                                       INT32 persistence)
{
    (void)self;
    (void)key;
    (void)val;
    (void)persistence;
    return S_OK;
}

static const void *resctx_statics_vtable[] = {
    (const void *)resctx_statics_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)resctx_CreateMatchingContext,
};

static const void *resctx_statics2_vtable[] = {
    (const void *)resctx_statics_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)resctx_GetForCurrentView,
    (const void *)resctx_SetGlobalQualifierValue,
    (const void *)resctx_ResetGlobalQualifierValues,
    (const void *)resctx_ResetGlobalQualifierValuesByNames,
    (const void *)resctx_GetForViewIndependentUse,
};

static const void *resctx_statics3_vtable[] = {
    (const void *)resctx_statics_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)resctx_SetGlobalQualifierValueWithPersistence,
};


/* ----------------------- Windows.ApplicationModel.ExtendedExecution.ExtendedExecutionSession */

static const GUID svc_iid_ext_exec_session = {
    0xaf908a2d, 0x118b, 0x48f1, {0x93, 0x08, 0x0c, 0x4f, 0xc4, 0x1e, 0x20, 0x0f}};

typedef struct {
    svc_object base;
    svc_object closable;
    INT32 reason;
    UINT32 percent;
    WCHAR description[128];
} ext_exec_object;

static const void *ext_exec_closable_vtable[];

static ext_exec_object *ext_exec_from(void *self)
{
    svc_object *hdr = (svc_object *)self;
    if (hdr && hdr->vtbl == ext_exec_closable_vtable)
        return (ext_exec_object *)((char *)self - offsetof(ext_exec_object, closable));
    return (ext_exec_object *)self;
}

static ULONG __stdcall ext_exec_AddRef(void *self)
{
    return svc_AddRef(&ext_exec_from(self)->base);
}

static ULONG __stdcall ext_exec_Release(void *self)
{
    return svc_Release(&ext_exec_from(self)->base);
}

static HRESULT __stdcall ext_exec_QI(void *self, const GUID *id, void **out)
{
    ext_exec_object *obj = ext_exec_from(self);
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_closable)) {
        *out = &obj->closable;
        svc_AddRef(&obj->base);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_ext_exec_session) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = &obj->base;
        svc_AddRef(&obj->base);
        return S_OK;
    }
    svc_trace_iid("ext_exec QI miss", id);
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall ext_exec_get_Reason(void *self, INT32 *out)
{
    if (!out) return E_POINTER;
    *out = ext_exec_from(self)->reason;
    return S_OK;
}

static HRESULT __stdcall ext_exec_put_Reason(void *self, INT32 val)
{
    ext_exec_from(self)->reason = val;
    return S_OK;
}

static HRESULT __stdcall ext_exec_get_Description(void *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = svc_string(ext_exec_from(self)->description);
    return S_OK;
}

static HRESULT __stdcall ext_exec_put_Description(void *self, hstring_t val)
{
    ext_exec_object *obj = ext_exec_from(self);
    const WCHAR *w = svc_string_text(val);
    if (w) {
        wcsncpy(obj->description, w, 127);
        obj->description[127] = 0;
    } else {
        obj->description[0] = 0;
    }
    return S_OK;
}

static HRESULT __stdcall ext_exec_get_PercentProgress(void *self, UINT32 *out)
{
    if (!out) return E_POINTER;
    *out = ext_exec_from(self)->percent;
    return S_OK;
}

static HRESULT __stdcall ext_exec_put_PercentProgress(void *self, UINT32 val)
{
    ext_exec_from(self)->percent = val;
    return S_OK;
}

static HRESULT __stdcall ext_exec_add_Revoked(void *self, void *handler, long long *token)
{
    (void)self;
    (void)handler;
    if (token) *token = 1;
    return S_OK;
}

static HRESULT __stdcall ext_exec_remove_Revoked(void *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

static HRESULT __stdcall ext_exec_RequestExtensionAsync(void *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    return async_u32_operation_iid(&svc_iid_async_ext_exec_result, S_OK, 0 /* ExtendedExecutionResult_Allowed */, out);
}

static HRESULT __stdcall ext_exec_Close(void *self)
{
    (void)self;
    return S_OK;
}

static const void *ext_exec_vtable[] = {
    (const void *)ext_exec_QI, (const void *)ext_exec_AddRef, (const void *)ext_exec_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)ext_exec_get_Reason, (const void *)ext_exec_put_Reason,
    (const void *)ext_exec_get_Description, (const void *)ext_exec_put_Description,
    (const void *)ext_exec_get_PercentProgress, (const void *)ext_exec_put_PercentProgress,
    (const void *)ext_exec_add_Revoked, (const void *)ext_exec_remove_Revoked,
    (const void *)ext_exec_RequestExtensionAsync,
};

static const void *ext_exec_closable_vtable[] = {
    (const void *)ext_exec_QI, (const void *)ext_exec_AddRef, (const void *)ext_exec_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)ext_exec_Close,
};

static HRESULT ext_exec_new(void **out)
{
    ext_exec_object *obj;
    if (!out) return E_POINTER;
    obj = (ext_exec_object *)calloc(1, sizeof(*obj));
    if (!obj) return E_OUTOFMEMORY;
    obj->base.vtbl = ext_exec_vtable;
    obj->base.refs = 1;
    obj->base.name = "Windows.ApplicationModel.ExtendedExecution.ExtendedExecutionSession";
    obj->closable.vtbl = ext_exec_closable_vtable;
    obj->closable.refs = 1;
    obj->closable.permanent = 1;
    obj->closable.name = obj->base.name;
    *out = &obj->base;
    return S_OK;
}


/* ----------------------- Windows.Networking.Sockets.MessageWebSocket */

static const GUID svc_iid_msg_ws = {
    0x33727d08, 0x34d5, 0x4746, {0xad, 0x7b, 0x8d, 0xde, 0x5b, 0xc2, 0xef, 0x88}};
static const GUID svc_iid_msg_ws2 = {
    0xbed0cee7, 0xf9c8, 0x440a, {0x9a, 0xd5, 0x73, 0x72, 0x81, 0xd9, 0x74, 0x2e}};
static const GUID svc_iid_ws = {
    0xf877396f, 0x99b1, 0x4e18, {0xbc, 0x08, 0x85, 0x0c, 0x9a, 0xdf, 0x15, 0x6e}};
static const GUID svc_iid_msg_ws_ctrl = {
    0x8118388a, 0xc629, 0x4f0a, {0x80, 0xfb, 0x81, 0xfc, 0x05, 0x53, 0x88, 0x62}};
static const GUID svc_iid_ws_ctrl = {
    0x2ec4bdc3, 0xd9a5, 0x455a, {0x98, 0x11, 0xde, 0x24, 0xd4, 0x53, 0x37, 0xe9}};

typedef struct {
    svc_object base;
    svc_object ws_ctrl;
    UINT32 max_msg_size;
    INT32 msg_type;
    UINT32 outbound_buf_size;
} msg_ws_ctrl_object;

static const void *msg_ws_ctrl_base_vtable[];

static msg_ws_ctrl_object *msg_ws_ctrl_from(void *self)
{
    svc_object *hdr = (svc_object *)self;
    if (hdr && hdr->vtbl == msg_ws_ctrl_base_vtable)
        return (msg_ws_ctrl_object *)((char *)self - offsetof(msg_ws_ctrl_object, ws_ctrl));
    return (msg_ws_ctrl_object *)self;
}

static ULONG __stdcall msg_ws_ctrl_AddRef(void *self)
{
    return svc_AddRef(&msg_ws_ctrl_from(self)->base);
}

static ULONG __stdcall msg_ws_ctrl_Release(void *self)
{
    return svc_Release(&msg_ws_ctrl_from(self)->base);
}

static HRESULT __stdcall msg_ws_ctrl_QI(void *self, const GUID *id, void **out)
{
    msg_ws_ctrl_object *obj = msg_ws_ctrl_from(self);
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_ws_ctrl)) {
        *out = &obj->ws_ctrl;
        svc_AddRef(&obj->base);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_msg_ws_ctrl) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = &obj->base;
        svc_AddRef(&obj->base);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall msg_ws_ctrl_get_MaxMessageSize(void *self, UINT32 *out)
{
    if (!out) return E_POINTER;
    *out = msg_ws_ctrl_from(self)->max_msg_size;
    return S_OK;
}

static HRESULT __stdcall msg_ws_ctrl_put_MaxMessageSize(void *self, UINT32 val)
{
    msg_ws_ctrl_from(self)->max_msg_size = val;
    return S_OK;
}

static HRESULT __stdcall msg_ws_ctrl_get_MessageType(void *self, INT32 *out)
{
    if (!out) return E_POINTER;
    *out = msg_ws_ctrl_from(self)->msg_type;
    return S_OK;
}

static HRESULT __stdcall msg_ws_ctrl_put_MessageType(void *self, INT32 val)
{
    msg_ws_ctrl_from(self)->msg_type = val;
    return S_OK;
}

static HRESULT __stdcall msg_ws_ctrl_get_OutboundBuf(void *self, UINT32 *out)
{
    if (!out) return E_POINTER;
    *out = msg_ws_ctrl_from(self)->outbound_buf_size;
    return S_OK;
}

static HRESULT __stdcall msg_ws_ctrl_put_OutboundBuf(void *self, UINT32 val)
{
    msg_ws_ctrl_from(self)->outbound_buf_size = val;
    return S_OK;
}

static HRESULT __stdcall msg_ws_ctrl_get_Null(void *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    return S_OK;
}

static HRESULT __stdcall msg_ws_ctrl_put_Nop(void *self, void *val)
{
    (void)self;
    (void)val;
    return S_OK;
}

/* Minimal IVector<HSTRING> for IWebSocketControl::SupportedProtocols */
static const GUID svc_iid_ivector_hstring = {
    0x98b9acc1, 0x4b56, 0x532e, {0xac, 0x73, 0x03, 0xd5, 0x29, 0x1c, 0xca, 0x90}};

static HRESULT __stdcall ws_proto_vec_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_ivector_hstring) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef((svc_object *)self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall ws_proto_vec_GetAt(void *self, UINT32 index, hstring_t *out)
{
    (void)self;
    (void)index;
    if (out) *out = NULL;
    return E_BOUNDS;
}

static HRESULT __stdcall ws_proto_vec_get_Size(void *self, UINT32 *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

static const GUID svc_iid_ivectorview_hstring = {
    0x2f13c006, 0xa03a, 0x5f69, {0xb0, 0x90, 0x75, 0xa4, 0x3e, 0x33, 0x42, 0x3e}};

static HRESULT __stdcall ws_proto_view_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_ivectorview_hstring) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = self;
        svc_AddRef((svc_object *)self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall ws_proto_vec_IndexOf(void *self, hstring_t val, UINT32 *idx, BOOLEAN *found);
static HRESULT __stdcall ws_proto_vec_GetMany(void *self, UINT32 start, UINT32 cap, hstring_t *items, UINT32 *actual);

static const void *ws_proto_view_vtable[] = {
    (const void *)ws_proto_view_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)ws_proto_vec_GetAt, (const void *)ws_proto_vec_get_Size,
    (const void *)ws_proto_vec_IndexOf, (const void *)ws_proto_vec_GetMany,
};

static svc_object ws_proto_view_singleton = {
    ws_proto_view_vtable, 1, "Windows.Foundation.Collections.IVectorView`1<String>", 1};

static HRESULT __stdcall ws_proto_vec_GetView(void *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    svc_AddRef(&ws_proto_view_singleton);
    *out = &ws_proto_view_singleton;
    return S_OK;
}

static HRESULT __stdcall ws_proto_vec_IndexOf(void *self, hstring_t val, UINT32 *idx, BOOLEAN *found)
{
    (void)self;
    (void)val;
    if (idx) *idx = 0;
    if (found) *found = 0;
    return S_OK;
}

static HRESULT __stdcall ws_proto_vec_SetAt(void *self, UINT32 index, hstring_t val)
{
    (void)self;
    (void)index;
    (void)val;
    return E_BOUNDS;
}

static HRESULT __stdcall ws_proto_vec_InsertAt(void *self, UINT32 index, hstring_t val)
{
    (void)self;
    (void)index;
    (void)val;
    return S_OK;
}

static HRESULT __stdcall ws_proto_vec_RemoveAt(void *self, UINT32 index)
{
    (void)self;
    (void)index;
    return E_BOUNDS;
}

static HRESULT __stdcall ws_proto_vec_Append(void *self, hstring_t val)
{
    (void)self;
    (void)val;
    return S_OK;
}

static HRESULT __stdcall ws_proto_vec_Nop(void *self)
{
    (void)self;
    return S_OK;
}

static HRESULT __stdcall ws_proto_vec_GetMany(void *self, UINT32 start, UINT32 cap, hstring_t *items, UINT32 *actual)
{
    (void)self;
    (void)start;
    (void)cap;
    (void)items;
    if (actual) *actual = 0;
    return S_OK;
}

static HRESULT __stdcall ws_proto_vec_ReplaceAll(void *self, UINT32 count, hstring_t *items)
{
    (void)self;
    (void)count;
    (void)items;
    return S_OK;
}

static const void *ws_proto_vec_vtable[] = {
    (const void *)ws_proto_vec_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)ws_proto_vec_GetAt, (const void *)ws_proto_vec_get_Size,
    (const void *)ws_proto_vec_GetView, (const void *)ws_proto_vec_IndexOf,
    (const void *)ws_proto_vec_SetAt, (const void *)ws_proto_vec_InsertAt,
    (const void *)ws_proto_vec_RemoveAt, (const void *)ws_proto_vec_Append,
    (const void *)ws_proto_vec_Nop, (const void *)ws_proto_vec_Nop,
    (const void *)ws_proto_vec_GetMany, (const void *)ws_proto_vec_ReplaceAll,
};

static svc_object ws_proto_vec_singleton = {
    ws_proto_vec_vtable, 1, "Windows.Foundation.Collections.IVector`1<String>", 1};

static HRESULT __stdcall msg_ws_ctrl_get_SupportedProtocols(void *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    svc_AddRef(&ws_proto_vec_singleton);
    *out = &ws_proto_vec_singleton;
    return S_OK;
}

static const void *msg_ws_ctrl_vtable[] = {
    (const void *)msg_ws_ctrl_QI, (const void *)msg_ws_ctrl_AddRef,
    (const void *)msg_ws_ctrl_Release, (const void *)svc_get_iids,
    (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)msg_ws_ctrl_get_MaxMessageSize, (const void *)msg_ws_ctrl_put_MaxMessageSize,
    (const void *)msg_ws_ctrl_get_MessageType, (const void *)msg_ws_ctrl_put_MessageType,
};

static const void *msg_ws_ctrl_base_vtable[] = {
    (const void *)msg_ws_ctrl_QI, (const void *)msg_ws_ctrl_AddRef,
    (const void *)msg_ws_ctrl_Release, (const void *)svc_get_iids,
    (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)msg_ws_ctrl_get_OutboundBuf, (const void *)msg_ws_ctrl_put_OutboundBuf,
    (const void *)msg_ws_ctrl_get_Null, (const void *)msg_ws_ctrl_put_Nop,
    (const void *)msg_ws_ctrl_get_Null, (const void *)msg_ws_ctrl_put_Nop,
    (const void *)msg_ws_ctrl_get_SupportedProtocols,
};

typedef struct {
    svc_object base;
    svc_object msg_ws2;
    svc_object ws;
    svc_object closable;
    msg_ws_ctrl_object *ctrl;
} msg_ws_object;

static const void *msg_ws2_vtable[];
static const void *msg_ws_base_vtable[];
static const void *msg_ws_closable_vtable[];

static msg_ws_object *msg_ws_from(void *self)
{
    svc_object *hdr = (svc_object *)self;
    if (hdr && hdr->vtbl == msg_ws2_vtable)
        return (msg_ws_object *)((char *)self - offsetof(msg_ws_object, msg_ws2));
    if (hdr && hdr->vtbl == msg_ws_base_vtable)
        return (msg_ws_object *)((char *)self - offsetof(msg_ws_object, ws));
    if (hdr && hdr->vtbl == msg_ws_closable_vtable)
        return (msg_ws_object *)((char *)self - offsetof(msg_ws_object, closable));
    return (msg_ws_object *)self;
}

static ULONG __stdcall msg_ws_AddRef(void *self)
{
    return svc_AddRef(&msg_ws_from(self)->base);
}

static ULONG __stdcall msg_ws_Release(void *self)
{
    msg_ws_object *obj = msg_ws_from(self);
    LONG left = InterlockedDecrement(&obj->base.refs);
    if (left <= 0) {
        if (obj->ctrl) svc_Release(&obj->ctrl->base);
        free(obj);
    }
    return (ULONG)left;
}

static HRESULT __stdcall msg_ws_QI(void *self, const GUID *id, void **out)
{
    msg_ws_object *obj = msg_ws_from(self);
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_msg_ws2)) {
        *out = &obj->msg_ws2;
        svc_AddRef(&obj->base);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_ws)) {
        *out = &obj->ws;
        svc_AddRef(&obj->base);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_closable)) {
        *out = &obj->closable;
        svc_AddRef(&obj->base);
        return S_OK;
    }
    if (IsEqualIID(id, &svc_iid_msg_ws) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_device_agile)) {
        *out = &obj->base;
        svc_AddRef(&obj->base);
        return S_OK;
    }
    svc_trace_iid("msg_ws QI miss", id);
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall msg_ws_get_Control(void *self, void **out)
{
    msg_ws_object *obj = msg_ws_from(self);
    if (!out) return E_POINTER;
    if (!obj->ctrl) {
        obj->ctrl = (msg_ws_ctrl_object *)calloc(1, sizeof(*obj->ctrl));
        if (!obj->ctrl) return E_OUTOFMEMORY;
        obj->ctrl->base.vtbl = msg_ws_ctrl_vtable;
        obj->ctrl->base.refs = 1;
        obj->ctrl->base.name = "Windows.Networking.Sockets.MessageWebSocketControl";
        obj->ctrl->ws_ctrl.vtbl = msg_ws_ctrl_base_vtable;
        obj->ctrl->ws_ctrl.refs = 1;
        obj->ctrl->ws_ctrl.permanent = 1;
        obj->ctrl->ws_ctrl.name = obj->ctrl->base.name;
        obj->ctrl->max_msg_size = 65536;
        obj->ctrl->outbound_buf_size = 65536;
    }
    svc_AddRef(&obj->ctrl->base);
    *out = &obj->ctrl->base;
    return S_OK;
}

static HRESULT __stdcall msg_ws_get_Information(void *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    return E_NOTIMPL;
}

static HRESULT __stdcall msg_ws_add_Event(void *self, void *handler, long long *token)
{
    (void)self;
    (void)handler;
    if (token) *token = 1;
    return S_OK;
}

static HRESULT __stdcall msg_ws_remove_Event(void *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

static HRESULT __stdcall msg_ws_get_OutputStream(void *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    return E_FAIL;
}

static HRESULT __stdcall msg_ws_ConnectAsync(void *self, void *uri, void **out)
{
    (void)self;
    (void)uri;
    if (!out) return E_POINTER;
    return async_action(E_FAIL, out);
}

static HRESULT __stdcall msg_ws_SetRequestHeader(void *self, hstring_t name, hstring_t val)
{
    (void)self;
    (void)name;
    (void)val;
    return S_OK;
}

static HRESULT __stdcall msg_ws_CloseWithStatus(void *self, UINT16 code, hstring_t reason)
{
    (void)self;
    (void)code;
    (void)reason;
    return S_OK;
}

static HRESULT __stdcall msg_ws_Close(void *self)
{
    (void)self;
    return S_OK;
}

static const void *msg_ws_vtable[] = {
    (const void *)msg_ws_QI, (const void *)msg_ws_AddRef, (const void *)msg_ws_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)msg_ws_get_Control, (const void *)msg_ws_get_Information,
    (const void *)msg_ws_add_Event, (const void *)msg_ws_remove_Event,
};

static const void *msg_ws2_vtable[] = {
    (const void *)msg_ws_QI, (const void *)msg_ws_AddRef, (const void *)msg_ws_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)msg_ws_add_Event, (const void *)msg_ws_remove_Event,
};

static const void *msg_ws_base_vtable[] = {
    (const void *)msg_ws_QI, (const void *)msg_ws_AddRef, (const void *)msg_ws_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)msg_ws_get_OutputStream, (const void *)msg_ws_ConnectAsync,
    (const void *)msg_ws_SetRequestHeader, (const void *)msg_ws_add_Event,
    (const void *)msg_ws_remove_Event, (const void *)msg_ws_CloseWithStatus,
};

static const void *msg_ws_closable_vtable[] = {
    (const void *)msg_ws_QI, (const void *)msg_ws_AddRef, (const void *)msg_ws_Release,
    (const void *)svc_get_iids, (const void *)svc_get_class_name, (const void *)svc_get_trust,
    (const void *)msg_ws_Close,
};

static HRESULT msg_ws_new(void **out)
{
    msg_ws_object *obj;
    if (!out) return E_POINTER;
    obj = (msg_ws_object *)calloc(1, sizeof(*obj));
    if (!obj) return E_OUTOFMEMORY;
    obj->base.vtbl = msg_ws_vtable;
    obj->base.refs = 1;
    obj->base.name = "Windows.Networking.Sockets.MessageWebSocket";
    obj->msg_ws2.vtbl = msg_ws2_vtable;
    obj->msg_ws2.refs = 1;
    obj->msg_ws2.permanent = 1;
    obj->msg_ws2.name = obj->base.name;
    obj->ws.vtbl = msg_ws_base_vtable;
    obj->ws.refs = 1;
    obj->ws.permanent = 1;
    obj->ws.name = obj->base.name;
    obj->closable.vtbl = msg_ws_closable_vtable;
    obj->closable.refs = 1;
    obj->closable.permanent = 1;
    obj->closable.name = obj->base.name;
    *out = &obj->base;
    return S_OK;
}

/* ------------------------------------------------------ class registration */

typedef struct {
    const WCHAR *class_name;
    svc_object *statics;
    const void *instance_vtable;
    int activatable;
    svc_object factory;
} svc_class;

static svc_class service_classes[] = {
    {L"Windows.Storage.ApplicationData", &appdata_static, NULL, 0, {0}},
    {L"Windows.ApplicationModel.Package", &package_static, NULL, 0, {0}},
    {L"Windows.ApplicationModel.PackageCatalog", &package_catalog_static, NULL, 0, {0}},
    {L"Windows.Security.Cryptography.Core.HashAlgorithmProvider", &hash_provider_static, NULL, 0, {0}},
    {L"Windows.Security.Cryptography.Core.HashAlgorithmNames", &hash_names_static, NULL, 0, {0}},
    {L"Windows.Security.Cryptography.CryptographicBuffer", &crypto_buffer_static, NULL, 0, {0}},
    {L"Windows.Graphics.Display.DisplayInformation", &display_static, NULL, 0, {0}},
    {L"Windows.Media.Devices.MediaDevice", &media_static, NULL, 0, {0}},
    {L"Windows.Devices.Input.MouseDevice", &mouse_static, NULL, 0, {0}},
    {L"Windows.Devices.Input.MouseCapabilities", NULL, mouse_caps_vtable, 1, {0}},
    {L"Windows.Devices.Input.KeyboardCapabilities", NULL, keyboard_caps_vtable, 1, {0}},
    {L"Windows.System.MemoryManager", &memory_static, NULL, 0, {0}},
    {L"Windows.UI.ViewManagement.ApplicationView", &appview_static, NULL, 0, {0}},
    {L"Windows.UI.Popups.MessageDialog", &message_dialog_static, NULL, 0, {0}},
    {L"Windows.Devices.Enumeration.DeviceInformation", &device_static, NULL, 0, {0}},
    {L"Windows.Services.Store.StoreContext", &store_context_static, NULL, 0, {0}},
    {L"Windows.System.Profile.HardwareIdentification", &hardware_id_static, NULL, 0, {0}},
    {L"Windows.System.Profile.SystemIdentification", &system_id_static, NULL, 0, {0}},
    {L"Windows.System.Threading.ThreadPoolTimer", &tpt_static, NULL, 0, {0}},
    {L"Windows.Foundation.Diagnostics.LoggingOptions", &diag_logging_options_static, diag_logging_options_vtable, 1, {0}},
    {L"Windows.Foundation.Diagnostics.LoggingChannelOptions", &diag_logging_channel_options_static, diag_logging_channel_options_vtable, 1, {0}},
    {L"Windows.Foundation.Diagnostics.LoggingChannel", &diag_logging_channel_static, NULL, 0, {0}},
    {L"Windows.Foundation.Diagnostics.LoggingFields", NULL, diag_logging_fields_vtable, 1, {0}},
    {L"Windows.Networking.PushNotifications.PushNotificationChannelManager", &push_mgr_static, NULL, 0, {0}},
    {L"Windows.Networking.XboxLive.XboxLiveDeviceAddress", &xbl_devaddr_static, NULL, 0, {0}},
    {L"Windows.ApplicationModel.Resources.Core.ResourceContext", &resctx_static, resctx_vtable, 1, {0}},
    {L"Windows.ApplicationModel.ExtendedExecution.ExtendedExecutionSession", NULL, ext_exec_vtable, 1, {0}},
    {L"Windows.Networking.Sockets.MessageWebSocket", NULL, msg_ws_vtable, 1, {0}},
};

static HRESULT svc_factory_QI(svc_object *self, const GUID *id, void **out)
{
    svc_class *entry = (svc_class *)self->name;
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &svc_iid_factory) || IsEqualIID(id, &svc_iid_unknown) ||
        IsEqualIID(id, &svc_iid_inspectable) || IsEqualIID(id, &svc_iid_agile_object)) {
        *out = self;
        svc_AddRef(self);
        return S_OK;
    }
    if (entry && entry->statics && entry->statics->vtbl && entry->statics->vtbl[0]) {
        svc_trace_iid("statics QI", id);
        return ((HRESULT(__stdcall *)(void *, const GUID *, void **))entry->statics->vtbl[0])(
            entry->statics, id, out);
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT svc_factory_get_name(svc_object *self, hstring_t *out)
{
    svc_class *entry = (svc_class *)self->name;
    if (!out) return E_POINTER;
    *out = svc_string(entry->class_name);
    return S_OK;
}

static HRESULT svc_factory_activate(svc_object *self, void **out)
{
    svc_class *entry = (svc_class *)self->name;
    svc_object *object;
    if (!out) return E_POINTER;
    if (entry && entry->class_name) svc_trace_w("activate instance", entry->class_name);
    if (!entry->activatable || !entry->instance_vtable) {
        *out = NULL;
        return E_NOTIMPL;
    }
    if (entry->instance_vtable == diag_logging_options_vtable)
        return diag_logging_options_new(0, out);
    if (entry->instance_vtable == diag_logging_channel_options_vtable)
        return diag_logging_channel_options_new(NULL, out);
    if (entry->instance_vtable == diag_logging_fields_vtable)
        return diag_logging_fields_new(out);
    if (entry->instance_vtable == resctx_vtable) {
        svc_AddRef(&resctx_singleton);
        *out = &resctx_singleton;
        return S_OK;
    }
    if (entry->instance_vtable == ext_exec_vtable)
        return ext_exec_new(out);
    if (entry->instance_vtable == msg_ws_vtable)
        return msg_ws_new(out);
    object = (svc_object *)calloc(1, sizeof(svc_object));
    if (!object) return E_OUTOFMEMORY;
    object->vtbl = (const void **)entry->instance_vtable;
    object->refs = 1;
    object->name = entry->instance_vtable == keyboard_caps_vtable
                       ? "Windows.Devices.Input.KeyboardCapabilities"
                       : "Windows.Devices.Input.MouseCapabilities";
    *out = object;
    return S_OK;
}

static const void *svc_factory_vtable[] = {
    (const void *)svc_factory_QI, (const void *)svc_AddRef, (const void *)svc_Release,
    (const void *)svc_get_iids, (const void *)svc_factory_get_name, (const void *)svc_get_trust,
    (const void *)svc_factory_activate,
};

void uwp_services_install(void);
void uwp_services_install(void)
{
    size_t i;

#define INSTALL(object, table, runtime_name)                                  \
    do {                                                                      \
        (object).vtbl = (table);                                              \
        (object).refs = 1000;                                                 \
        (object).permanent = 1;                                               \
        (object).name = (runtime_name);                                       \
    } while (0)

    INSTALL(hash_names_static, hash_names_vtable,
            "Windows.Security.Cryptography.Core.HashAlgorithmNames");
    INSTALL(hash_provider_static, hash_provider_statics_vtable,
            "Windows.Security.Cryptography.Core.HashAlgorithmProvider");
    INSTALL(crypto_buffer_static, crypto_buffer_vtable,
            "Windows.Security.Cryptography.CryptographicBuffer");
    INSTALL(appdata_static, appdata_statics_vtable, "Windows.Storage.ApplicationData");
    INSTALL(appdata_object, appdata_vtable, "Windows.Storage.ApplicationData");
    INSTALL(package_static, package_static_vtable, "Windows.ApplicationModel.Package");
    INSTALL(package_object, package_vtable, "Windows.ApplicationModel.Package");
    INSTALL(package2_face, package2_vtable, "Windows.ApplicationModel.Package");
    INSTALL(package3_face, package3_vtable, "Windows.ApplicationModel.Package");
    INSTALL(package4_face, package4_vtable, "Windows.ApplicationModel.Package");
    INSTALL(package_status_object, package_status_vtable, "Windows.ApplicationModel.PackageStatus");
    INSTALL(package_deps, package_deps_vtable, "Windows.Foundation.Collections.IVectorView");
    INSTALL(package_deps_iterable, package_deps_iterable_vtable,
            "Windows.Foundation.Collections.IVectorView");
    INSTALL(package_deps_iterator, package_deps_iterator_vtable,
            "Windows.Foundation.Collections.IIterator");
    INSTALL(package_id_object, package_id_vtable, "Windows.ApplicationModel.PackageId");
    INSTALL(package_catalog_static, package_catalog_statics_vtable,
            "Windows.ApplicationModel.PackageCatalog");
    INSTALL(package_catalog_object, package_catalog_vtable,
            "Windows.ApplicationModel.PackageCatalog");
    INSTALL(display_static, display_static_vtable,
            "Windows.Graphics.Display.DisplayInformation");
    INSTALL(display_info, display_vtable, "Windows.Graphics.Display.DisplayInformation");
    INSTALL(display2_face, display2_vtable, "Windows.Graphics.Display.DisplayInformation");
    INSTALL(display3_face, display3_vtable, "Windows.Graphics.Display.DisplayInformation");
    INSTALL(display4_face, display4_vtable, "Windows.Graphics.Display.DisplayInformation");
    INSTALL(media_static, media_static_vtable, "Windows.Media.Devices.MediaDevice");
    INSTALL(memory_static, memory_static_vtable, "Windows.System.MemoryManager");
    INSTALL(memory_static2, memory_static2_vtable, "Windows.System.MemoryManager");
    INSTALL(memory_report, memory_report_vtable, "Windows.System.AppMemoryReport");
    INSTALL(process_report, process_report_vtable, "Windows.System.ProcessMemoryReport");
    INSTALL(mouse_static, mouse_static_vtable, "Windows.Devices.Input.MouseDevice");
    INSTALL(mouse_object, mouse_vtable, "Windows.Devices.Input.MouseDevice");
    INSTALL(appview_static, appview_statics_vtable, "Windows.UI.ViewManagement.ApplicationView");
    INSTALL(appview_static3, appview_statics3_vtable, "Windows.UI.ViewManagement.ApplicationView");
    INSTALL(appview_fullscreen, appview_fullscreen_vtable, "Windows.UI.ViewManagement.ApplicationView");
    INSTALL(appview_object, appview_vtable, "Windows.UI.ViewManagement.ApplicationView");
    INSTALL(appview2_object, appview2_vtable, "Windows.UI.ViewManagement.ApplicationView");
    INSTALL(appview3_object, appview3_vtable, "Windows.UI.ViewManagement.ApplicationView");
    INSTALL(appview_titlebar, appview_titlebar_vtable, "Windows.UI.ViewManagement.ApplicationViewTitleBar");
    INSTALL(message_dialog_static, message_dialog_factory_vtable, "Windows.UI.Popups.MessageDialog");
    INSTALL(device_static, device_static_vtable, "Windows.Devices.Enumeration.DeviceInformation");
    INSTALL(device_static2, device_statics2_vtable, "Windows.Devices.Enumeration.DeviceInformation");
    INSTALL(device_collection, device_collection_vtable,
            "Windows.Devices.Enumeration.DeviceInformationCollection");
    INSTALL(device_collection_iterable, device_collection_iterable_vtable,
            "Windows.Devices.Enumeration.DeviceInformationCollection");
    INSTALL(device_iterator, device_iterator_vtable, "Windows.Foundation.Collections.IIterator");
    INSTALL(store_context_static, store_context_statics_vtable,
            "Windows.Services.Store.StoreContext");
    INSTALL(store_context_object, store_context_vtable, "Windows.Services.Store.StoreContext");
    INSTALL(store_context2_face, store_context2_vtable, "Windows.Services.Store.StoreContext");
    INSTALL(store_app_license_object, store_app_license_vtable,
            "Windows.Services.Store.StoreAppLicense");
    INSTALL(store_addon_map, store_addon_map_vtable, "Windows.Foundation.Collections.IMapView");
    INSTALL(store_addon_iterable, store_addon_iterable_vtable,
            "Windows.Foundation.Collections.IMapView");
    INSTALL(store_addon_iterator, store_addon_iterator_vtable,
            "Windows.Foundation.Collections.IIterator");
    INSTALL(store_product_query_result, store_product_query_result_vtable,
            "Windows.Services.Store.StoreProductQueryResult");
    INSTALL(store_product_map, store_product_map_vtable, "Windows.Foundation.Collections.IMapView");
    INSTALL(store_product_iterable, store_product_iterable_vtable,
            "Windows.Foundation.Collections.IMapView");
    INSTALL(store_product_iterator, store_product_iterator_vtable,
            "Windows.Foundation.Collections.IIterator");
    INSTALL(store_product_result, store_product_result_vtable,
            "Windows.Services.Store.StoreProductResult");
    INSTALL(store_consumable_result, store_consumable_result_vtable,
            "Windows.Services.Store.StoreConsumableResult");
    INSTALL(store_purchase_result, store_purchase_result_vtable,
            "Windows.Services.Store.StorePurchaseResult");
    INSTALL(store_acquire_license_result, store_acquire_license_result_vtable,
            "Windows.Services.Store.StoreAcquireLicenseResult");
    INSTALL(store_package_license, store_package_license_vtable,
            "Windows.Services.Store.StorePackageLicense");
    INSTALL(store_package_license_closable, store_package_license_closable_vtable,
            "Windows.Services.Store.StorePackageLicense");
    INSTALL(store_updates_vector, store_updates_vector_vtable,
            "Windows.Foundation.Collections.IVectorView");
    INSTALL(store_updates_iterable, store_updates_iterable_vtable,
            "Windows.Foundation.Collections.IVectorView");
    INSTALL(store_updates_iterator, store_updates_iterator_vtable,
            "Windows.Foundation.Collections.IIterator");
    INSTALL(hardware_id_static, hardware_id_statics_vtable,
            "Windows.System.Profile.HardwareIdentification");
    INSTALL(hardware_token_object, hardware_token_vtable,
            "Windows.System.Profile.HardwareToken");
    INSTALL(system_id_static, system_id_statics_vtable,
            "Windows.System.Profile.SystemIdentification");
    INSTALL(system_id_info_object, system_id_info_vtable,
            "Windows.System.Profile.SystemIdentificationInfo");
    INSTALL(tpt_static, tpt_statics_vtable,
            "Windows.System.Threading.ThreadPoolTimer");
    INSTALL(diag_logging_options_static, diag_logging_options_factory_vtable,
            "Windows.Foundation.Diagnostics.LoggingOptions");
    INSTALL(diag_logging_channel_options_static, diag_logging_channel_options_factory_vtable,
            "Windows.Foundation.Diagnostics.LoggingChannelOptions");
    INSTALL(diag_logging_channel_static, diag_logging_channel_factory_vtable,
            "Windows.Foundation.Diagnostics.LoggingChannel");
    INSTALL(diag_logging_channel_static2, diag_logging_channel_factory2_vtable,
            "Windows.Foundation.Diagnostics.LoggingChannel");
    INSTALL(push_mgr_static, push_mgr_statics_vtable,
            "Windows.Networking.PushNotifications.PushNotificationChannelManager");
    INSTALL(xbl_devaddr_static, xbl_devaddr_statics_vtable,
            "Windows.Networking.XboxLive.XboxLiveDeviceAddress");
    INSTALL(xbl_devaddr_local.base, xbl_devaddr_vtable,
            "Windows.Networking.XboxLive.XboxLiveDeviceAddress");
    xbl_devaddr_local.is_local = TRUE;
    xbl_devaddr_local.is_valid = FALSE;
    INSTALL(resctx_static, resctx_statics_vtable,
            "Windows.ApplicationModel.Resources.Core.ResourceContext");
    INSTALL(resctx_static2, resctx_statics2_vtable,
            "Windows.ApplicationModel.Resources.Core.ResourceContext");
    INSTALL(resctx_static3, resctx_statics3_vtable,
            "Windows.ApplicationModel.Resources.Core.ResourceContext");
    INSTALL(resctx_singleton, resctx_vtable,
            "Windows.ApplicationModel.Resources.Core.ResourceContext");
    INSTALL(resctx_qual_map, resctx_qual_map_vtable,
            "Windows.Foundation.Collections.IMap");
    INSTALL(resctx_qual_obs, resctx_qual_obs_vtable,
            "Windows.Foundation.Collections.IObservableMap");
    INSTALL(resctx_qual_view, resctx_qual_view_vtable,
            "Windows.Foundation.Collections.IMapView");
    INSTALL(resctx_lang_vec, resctx_lang_vec_vtable,
            "Windows.Foundation.Collections.IVectorView");
    INSTALL(resctx_lang_iter, resctx_lang_iter_vtable,
            "Windows.Foundation.Collections.IIterable");
#undef INSTALL

    for (i = 0; i < sizeof(service_classes) / sizeof(service_classes[0]); i++) {
        svc_class *entry = &service_classes[i];
        entry->factory.vtbl = svc_factory_vtable;
        entry->factory.refs = 1000;
        entry->factory.permanent = 1;
        entry->factory.name = (const char *)entry;
    }
}

/* Returns 1 when this unit owns the class and fills the factory. */
int uwp_services_lookup(const WCHAR *class_name, void **factory)
{
    size_t i;
    for (i = 0; i < sizeof(service_classes) / sizeof(service_classes[0]); i++) {
        if (!wcscmp(class_name, service_classes[i].class_name)) {
            *factory = &service_classes[i].factory;
            return 1;
        }
    }
    return 0;
}
