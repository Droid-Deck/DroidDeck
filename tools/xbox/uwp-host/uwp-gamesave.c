/*
 * Windows.Gaming.XboxLive.Storage (Connected Storage) implementation backed by
 * persistent local package storage under %LOCALAPPDATA%\Packages\<PFN>\LocalState\GameSave.
 *
 * Implements:
 *   - Windows.Gaming.XboxLive.Storage.GameSaveProvider (+ IGameSaveProviderStatics)
 *   - Windows.Gaming.XboxLive.Storage.GameSaveProviderGetResult
 *   - Windows.Gaming.XboxLive.Storage.GameSaveContainer
 *   - Windows.Gaming.XboxLive.Storage.GameSaveOperationResult
 *   - Windows.Gaming.XboxLive.Storage.GameSaveBlobGetResult
 *   - Windows.Gaming.XboxLive.Storage.GameSaveContainerInfoQuery / GameSaveContainerInfoGetResult / GameSaveContainerInfo
 *   - Windows.Gaming.XboxLive.Storage.GameSaveBlobInfoQuery / GameSaveBlobInfoGetResult / GameSaveBlobInfo
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

typedef void *hstring_t;

extern void uwp_local_state_dir(WCHAR *out, size_t cap);

/* --------------------------------------------------------------- logging */

static void gs_log(const char *fmt, ...)
{
    static int enabled = -1;
    char buf[384];
    char line[420];
    va_list ap;
    if (enabled < 0) {
        char setting[8];
        DWORD len = GetEnvironmentVariableA("DROIDDECK_UWP_TRACE", setting, sizeof setting);
        enabled = (len > 0 && len < sizeof setting) ? 1 : 0;
    }
    if (!enabled) return;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    snprintf(line, sizeof(line), "%04x uwp-gamesave: %s", (unsigned)GetCurrentThreadId(), buf);
    OutputDebugStringA(line);
}

/* --------------------------------------------------------------- HSTRING */

typedef HRESULT(WINAPI *gs_create_hstring_fn)(const WCHAR *, UINT32, hstring_t *);
typedef const WCHAR *(WINAPI *gs_hstring_buf_fn)(hstring_t, UINT32 *);
typedef HRESULT(WINAPI *gs_delete_hstring_fn)(hstring_t);
typedef HRESULT(WINAPI *gs_dup_hstring_fn)(hstring_t, hstring_t *);

static gs_create_hstring_fn gs_fn_create;
static gs_hstring_buf_fn gs_fn_raw;
static gs_delete_hstring_fn gs_fn_delete;
static gs_dup_hstring_fn gs_fn_dup;

static void gs_load_hstring(void)
{
    HMODULE combase;
    if (gs_fn_create) return;
    combase = GetModuleHandleA("combase.dll");
    if (!combase) combase = LoadLibraryA("combase.dll");
    if (!combase) return;
    gs_fn_create = (gs_create_hstring_fn)(void *)GetProcAddress(combase, "WindowsCreateString");
    gs_fn_raw = (gs_hstring_buf_fn)(void *)GetProcAddress(combase, "WindowsGetStringRawBuffer");
    gs_fn_delete = (gs_delete_hstring_fn)(void *)GetProcAddress(combase, "WindowsDeleteString");
    gs_fn_dup = (gs_dup_hstring_fn)(void *)GetProcAddress(combase, "WindowsDuplicateString");
}

static hstring_t gs_hstr(const WCHAR *s)
{
    hstring_t out = NULL;
    gs_load_hstring();
    if (!gs_fn_create || !s || !s[0]) return NULL;
    gs_fn_create(s, (UINT32)wcslen(s), &out);
    return out;
}

static hstring_t gs_hstr_ascii(const char *s)
{
    WCHAR w[128];
    int i;
    if (!s) return NULL;
    for (i = 0; i < 127 && s[i]; i++) w[i] = (WCHAR)(unsigned char)s[i];
    w[i] = 0;
    return gs_hstr(w);
}

static const WCHAR *gs_hstr_raw(hstring_t h)
{
    const WCHAR *p;
    gs_load_hstring();
    if (!h || !gs_fn_raw) return L"";
    p = gs_fn_raw(h, NULL);
    return p ? p : L"";
}

static void gs_hstr_free(hstring_t h)
{
    gs_load_hstring();
    if (h && gs_fn_delete) gs_fn_delete(h);
}

static WCHAR *gs_wcsdup(const WCHAR *s)
{
    size_t n = s ? wcslen(s) : 0;
    WCHAR *p = (WCHAR *)malloc((n + 1) * sizeof(WCHAR));
    if (!p) return NULL;
    if (n) memcpy(p, s, n * sizeof(WCHAR));
    p[n] = 0;
    return p;
}

static void gs_unk_addref(void *p)
{
    if (!p) return;
    ((ULONG(__stdcall *)(void *))(*(void ***)p)[1])(p);
}

static void gs_unk_release(void *p)
{
    if (!p) return;
    ((ULONG(__stdcall *)(void *))(*(void ***)p)[2])(p);
}

static HRESULT gs_unk_qi(void *p, const GUID *iid, void **out)
{
    if (!p || !out) return E_POINTER;
    *out = NULL;
    return ((HRESULT(__stdcall *)(void *, const GUID *, void **))(*(void ***)p)[0])(p, iid, out);
}

/* --------------------------------------------------------------- GUIDs */

static const GUID gs_iid_unknown = {0x00000000, 0x0000, 0x0000,
                                    {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const GUID gs_iid_inspectable = {0xaf86e2e0, 0xb12d, 0x4c6a,
                                        {0x9c, 0x5a, 0xd7, 0xaa, 0x65, 0x10, 0x1e, 0x90}};
static const GUID gs_iid_factory = {0x00000035, 0x0000, 0x0000,
                                    {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const GUID gs_iid_async_info = {0x00000036, 0x0000, 0x0000,
                                       {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const GUID gs_iid_agile = {0x94ea2b94, 0xe9cc, 0x49e0,
                                  {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};
static const GUID gs_iid_ibuffer = {0x905a0fe0, 0xbc53, 0x11df,
                                    {0x8c, 0x49, 0x00, 0x1e, 0x4f, 0xc6, 0x86, 0xda}};
static const GUID gs_iid_ibuffer_bytes = {0x905a0fef, 0xbc53, 0x11df,
                                          {0x8c, 0x49, 0x00, 0x1e, 0x4f, 0xc6, 0x86, 0xda}};

/* Windows.Gaming.XboxLive.Storage interfaces */
static const GUID gs_iid_provider_statics = {0xd01d3ed0, 0x7b03, 0x449d,
                                             {0x8c, 0xbd, 0x34, 0x02, 0x84, 0x2a, 0x10, 0x48}};
static const GUID gs_iid_provider = {0x90a60394, 0x80fe, 0x4211,
                                     {0x97, 0xf8, 0xa5, 0xde, 0x14, 0xdd, 0x95, 0xd2}};
static const GUID gs_iid_provider_get = {0x3ab90816, 0xd393, 0x4d65,
                                         {0xac, 0x16, 0x41, 0xc3, 0xe6, 0x7a, 0xb9, 0x45}};
static const GUID gs_iid_container = {0xc3c08f89, 0x563f, 0x4ecd,
                                      {0x9c, 0x6f, 0x33, 0xfd, 0x0e, 0x32, 0x3d, 0x10}};
static const GUID gs_iid_op_result = {0xcf0f1a05, 0x24a0, 0x4582,
                                      {0x9a, 0x55, 0xb1, 0xbb, 0xbb, 0x93, 0x88, 0xd8}};
static const GUID gs_iid_blob_get = {0x917281e0, 0x7201, 0x4953,
                                     {0xaa, 0x2c, 0x40, 0x08, 0xf0, 0x3a, 0xef, 0x45}};
static const GUID gs_iid_blob_info = {0xadd38034, 0xbaf0, 0x4645,
                                      {0xb6, 0xd0, 0x46, 0xed, 0xaf, 0xfb, 0x3c, 0x2b}};
static const GUID gs_iid_blob_info_get = {0xc7578582, 0x3697, 0x42bf,
                                          {0x98, 0x9c, 0x66, 0x5d, 0x92, 0x3b, 0x52, 0x31}};
static const GUID gs_iid_blob_info_query = {0x9fdd74b2, 0xeeee, 0x447b,
                                            {0xa9, 0xd2, 0x7f, 0x96, 0xc0, 0xf8, 0x32, 0x08}};
static const GUID gs_iid_cont_info = {0xb7e27300, 0x155d, 0x4bb4,
                                      {0xb2, 0xba, 0x93, 0x03, 0x06, 0xf3, 0x91, 0xb5}};
static const GUID gs_iid_cont_info_get = {0xffc50d74, 0xc581, 0x4f9d,
                                          {0x9e, 0x39, 0x30, 0xa1, 0x0c, 0x1e, 0x4c, 0x50}};
static const GUID gs_iid_cont_info_query = {0x3c94e863, 0x6f80, 0x4327,
                                            {0x93, 0x27, 0xff, 0xc1, 0x1a, 0xfd, 0x42, 0xb3}};

/* Parameterized WinRT IIDs (computed via RFC 4122 WinRT SHA-1 type signatures) */
static const GUID gs_iid_async_provider_get = {0x3dc36085, 0x5fec, 0x541b,
                                               {0x96, 0xcf, 0x62, 0x7b, 0x2a, 0xd8, 0x0d, 0x36}};
static const GUID gs_iid_async_op_result = {0x1c27fb97, 0x1e1a, 0x516f,
                                            {0xab, 0xb2, 0x12, 0xc1, 0x8e, 0x18, 0x21, 0x8d}};
static const GUID gs_iid_async_blob_get = {0x7023b023, 0x7aed, 0x526c,
                                           {0xb3, 0xbc, 0xbe, 0x12, 0xe3, 0x5c, 0xe1, 0xcf}};
static const GUID gs_iid_async_blob_info_get = {0xd7b7f3b4, 0x6028, 0x522f,
                                                {0x84, 0x9d, 0xa6, 0x94, 0x95, 0xe4, 0xdc, 0xd0}};
static const GUID gs_iid_async_cont_info_get = {0xcff8afeb, 0x5a18, 0x5f51,
                                                {0xb6, 0x1b, 0x94, 0x38, 0x87, 0xf7, 0x29, 0xee}};
static const GUID gs_iid_async_i64 = {0xcc468085, 0x4bef, 0x5584,
                                      {0x90, 0x7c, 0x92, 0x23, 0xd2, 0x67, 0x90, 0x19}};
static const GUID gs_iid_async_u32 = {0xef60385f, 0xbe78, 0x584b,
                                      {0xaa, 0xef, 0x78, 0x29, 0xad, 0xa2, 0xb0, 0xde}};

static const GUID gs_iid_vecview_string = {0x2f13c006, 0xa03a, 0x5f69,
                                           {0xb0, 0x90, 0x75, 0xa4, 0x3e, 0x33, 0x42, 0x3e}};
static const GUID gs_iid_iterable_string = {0xe2fcc7c1, 0x3bfc, 0x5a0b,
                                            {0xb2, 0xb0, 0x72, 0xe7, 0x69, 0xd1, 0xcb, 0x7e}};
static const GUID gs_iid_iterator_string = {0x8c304ebb, 0x6615, 0x50a4,
                                            {0x88, 0x29, 0x87, 0x9e, 0xcd, 0x44, 0x32, 0x36}};

static const GUID gs_iid_vecview_cont_info = {0x9c490594, 0x0846, 0x50f5,
                                              {0xb2, 0xef, 0xc6, 0xf0, 0x3e, 0xe6, 0x86, 0x8a}};
static const GUID gs_iid_iterable_cont_info = {0x55e4d98f, 0x0889, 0x5c06,
                                               {0xa8, 0x57, 0x7d, 0xd1, 0x68, 0xc2, 0xd8, 0x52}};
static const GUID gs_iid_iterator_cont_info = {0x0ecd9756, 0x3e0d, 0x523f,
                                               {0xa5, 0x49, 0x2b, 0x65, 0x04, 0xdb, 0x52, 0x02}};

static const GUID gs_iid_vecview_blob_info = {0xb9c466a0, 0x2a3f, 0x5f28,
                                              {0xa1, 0xc1, 0x9c, 0xb1, 0x92, 0xf6, 0xc7, 0x86}};
static const GUID gs_iid_iterable_blob_info = {0xa7c456d7, 0xfa9f, 0x536f,
                                               {0x8e, 0xd2, 0x45, 0x95, 0x45, 0x81, 0x1e, 0xd4}};
static const GUID gs_iid_iterator_blob_info = {0xaaaf545b, 0xf5e9, 0x5da6,
                                               {0xaf, 0x70, 0x9d, 0x90, 0x4c, 0x7a, 0x4d, 0x37}};

static const GUID gs_iid_mapview_str_buf = {0x2cfeec4f, 0xe261, 0x5f4c,
                                            {0xae, 0xe1, 0xc7, 0x85, 0x18, 0xe9, 0xd5, 0xb9}};
static const GUID gs_iid_kvp_str_buf = {0x9114f794, 0x2ceb, 0x5b03,
                                        {0x9b, 0x22, 0x36, 0x88, 0x4e, 0x1f, 0x58, 0xb3}};
static const GUID gs_iid_iterable_kvp_str_buf = {0x3c9ffa92, 0x5123, 0x5ac4,
                                                 {0xb1, 0x11, 0x03, 0xc2, 0x15, 0xf0, 0xc5, 0x1c}};
static const GUID gs_iid_iterator_kvp_str_buf = {0x790acb62, 0xc4b3, 0x57ea,
                                                 {0xa1, 0x52, 0x9e, 0x21, 0x93, 0x71, 0xc6, 0xdc}};

/* GameSaveErrorStatus values */
#define GS_OK                      0
#define GS_INVALID_CONTAINER_NAME  (-2138898431)
#define GS_BUFFER_TOO_SMALL        (-2138898425)
#define GS_BLOB_NOT_FOUND          (-2138898424)
#define GS_E_BOUNDS                ((HRESULT)0x8000000BL)

/* --------------------------------------------------------------- filesystem helpers */

static void gs_ensure_dir(const WCHAR *path)
{
    WCHAR buf[MAX_PATH * 2];
    size_t i, len = wcslen(path);
    if (len >= sizeof(buf) / sizeof(buf[0])) return;
    memcpy(buf, path, (len + 1) * sizeof(WCHAR));
    for (i = 0; i < len; i++) {
        if ((buf[i] == L'\\' || buf[i] == L'/') && i > 2) {
            WCHAR saved = buf[i];
            buf[i] = 0;
            CreateDirectoryW(buf, NULL);
            buf[i] = saved;
        }
    }
    CreateDirectoryW(buf, NULL);
}

static void gs_encode_name(const WCHAR *src, WCHAR *dst, size_t cap)
{
    static const WCHAR hex[] = L"0123456789ABCDEF";
    size_t o = 0;
    size_t i;
    if (!src || !cap) {
        if (cap) dst[0] = 0;
        return;
    }
    for (i = 0; src[i] && o + 4 < cap; i++) {
        WCHAR c = src[i];
        int safe = ((c >= L'A' && c <= L'Z') || (c >= L'a' && c <= L'z') ||
                    (c >= L'0' && c <= L'9') || c == L'_' || c == L'-' ||
                    (c == L'.' && i > 0));
        if (safe) {
            dst[o++] = c;
        } else if (c < 0x100) {
            dst[o++] = L'%';
            dst[o++] = hex[(c >> 4) & 0xf];
            dst[o++] = hex[c & 0xf];
        } else if (o + 7 < cap) {
            dst[o++] = L'%';
            dst[o++] = L'u';
            dst[o++] = hex[(c >> 12) & 0xf];
            dst[o++] = hex[(c >> 8) & 0xf];
            dst[o++] = hex[(c >> 4) & 0xf];
            dst[o++] = hex[c & 0xf];
        }
    }
    dst[o] = 0;
}

static int gs_hexval(WCHAR c)
{
    if (c >= L'0' && c <= L'9') return c - L'0';
    if (c >= L'A' && c <= L'F') return 10 + (c - L'A');
    if (c >= L'a' && c <= L'f') return 10 + (c - L'a');
    return -1;
}

static void gs_decode_name(const WCHAR *src, WCHAR *dst, size_t cap)
{
    size_t o = 0, i = 0;
    if (!src || !cap) {
        if (cap) dst[0] = 0;
        return;
    }
    while (src[i] && o + 1 < cap) {
        if (src[i] == L'%' && src[i + 1] == L'u') {
            int a = gs_hexval(src[i + 2]), b = gs_hexval(src[i + 3]),
                c = gs_hexval(src[i + 4]), d = gs_hexval(src[i + 5]);
            if (a >= 0 && b >= 0 && c >= 0 && d >= 0) {
                dst[o++] = (WCHAR)((a << 12) | (b << 8) | (c << 4) | d);
                i += 6;
                continue;
            }
        } else if (src[i] == L'%') {
            int a = gs_hexval(src[i + 1]), b = gs_hexval(src[i + 2]);
            if (a >= 0 && b >= 0) {
                dst[o++] = (WCHAR)((a << 4) | b);
                i += 3;
                continue;
            }
        }
        dst[o++] = src[i++];
    }
    dst[o] = 0;
}

static int gs_starts_with(const WCHAR *str, const WCHAR *prefix)
{
    size_t plen;
    if (!prefix || !prefix[0]) return 1;
    plen = wcslen(prefix);
    return _wcsnicmp(str, prefix, plen) == 0;
}

static void gs_delete_tree(const WCHAR *dir)
{
    WCHAR pat[MAX_PATH * 2];
    WIN32_FIND_DATAW fd;
    HANDLE h;
    snprintf((char *)pat, 1, ""); /* keep warnings quiet */
    _snwprintf(pat, sizeof(pat) / sizeof(pat[0]), L"%ls\\*", dir);
    pat[sizeof(pat) / sizeof(pat[0]) - 1] = 0;
    h = FindFirstFileW(pat, &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            if (!wcscmp(fd.cFileName, L".") || !wcscmp(fd.cFileName, L"..")) continue;
            WCHAR sub[MAX_PATH * 2];
            _snwprintf(sub, sizeof(sub) / sizeof(sub[0]), L"%ls\\%ls", dir, fd.cFileName);
            sub[sizeof(sub) / sizeof(sub[0]) - 1] = 0;
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)
                gs_delete_tree(sub);
            else
                DeleteFileW(sub);
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }
    RemoveDirectoryW(dir);
}

static int gs_write_file_atomic(const WCHAR *path, const BYTE *data, DWORD len)
{
    WCHAR tmp[MAX_PATH * 2];
    HANDLE f;
    DWORD written = 0;
    _snwprintf(tmp, sizeof(tmp) / sizeof(tmp[0]), L"%ls.tmp", path);
    tmp[sizeof(tmp) / sizeof(tmp[0]) - 1] = 0;
    f = CreateFileW(tmp, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return 0;
    if (len > 0) {
        DWORD total = 0;
        while (total < len) {
            if (!WriteFile(f, data + total, len - total, &written, NULL) || written == 0) {
                CloseHandle(f);
                DeleteFileW(tmp);
                return 0;
            }
            total += written;
        }
    }
    CloseHandle(f);
    if (!MoveFileExW(tmp, path, MOVEFILE_REPLACE_EXISTING)) {
        DeleteFileW(tmp);
        return 0;
    }
    return 1;
}

static BYTE *gs_read_file_all(const WCHAR *path, DWORD *out_len)
{
    HANDLE f;
    LARGE_INTEGER sz;
    BYTE *buf;
    DWORD total = 0, got = 0;
    *out_len = 0;
    f = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING,
                    FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return NULL;
    if (!GetFileSizeEx(f, &sz) || sz.QuadPart < 0 || sz.QuadPart > 256LL * 1024 * 1024) {
        CloseHandle(f);
        return NULL;
    }
    buf = (BYTE *)malloc((size_t)(sz.QuadPart > 0 ? sz.QuadPart : 1));
    if (!buf) {
        CloseHandle(f);
        return NULL;
    }
    while (total < (DWORD)sz.QuadPart) {
        if (!ReadFile(f, buf + total, (DWORD)sz.QuadPart - total, &got, NULL) || got == 0) break;
        total += got;
    }
    CloseHandle(f);
    *out_len = total;
    return buf;
}

/* --------------------------------------------------------------- common IInspectable */

static HRESULT __stdcall gs_get_iids(void *self, ULONG *count, GUID **ids)
{
    (void)self;
    if (count) *count = 0;
    if (ids) *ids = NULL;
    return S_OK;
}

static HRESULT __stdcall gs_get_trust(void *self, INT32 *trust)
{
    (void)self;
    if (trust) *trust = 0;
    return S_OK;
}

/* --------------------------------------------------------------- IBuffer + IBufferByteAccess */

typedef struct {
    const void **vtbl;
    const void **bytes_vtbl;
    LONG refs;
    BYTE *data;
    UINT32 cap;
    UINT32 len;
} gs_buffer;

static gs_buffer *gs_buf_from_bytes(void *iface)
{
    return (gs_buffer *)((char *)iface - offsetof(gs_buffer, bytes_vtbl));
}

static ULONG __stdcall gs_buf_AddRef(gs_buffer *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}

static ULONG __stdcall gs_buf_Release(gs_buffer *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        free(self->data);
        free(self);
    }
    return (ULONG)r;
}

static HRESULT __stdcall gs_buf_QI(gs_buffer *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &gs_iid_ibuffer) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_buf_AddRef(self);
        return S_OK;
    }
    if (IsEqualIID(iid, &gs_iid_ibuffer_bytes)) {
        *out = &self->bytes_vtbl;
        gs_buf_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall gs_buf_name(gs_buffer *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Storage.Streams.Buffer");
    return S_OK;
}

static HRESULT __stdcall gs_buf_cap(gs_buffer *self, UINT32 *out)
{
    if (!out) return E_POINTER;
    *out = self->cap;
    return S_OK;
}

static HRESULT __stdcall gs_buf_len(gs_buffer *self, UINT32 *out)
{
    if (!out) return E_POINTER;
    *out = self->len;
    return S_OK;
}

static HRESULT __stdcall gs_buf_set_len(gs_buffer *self, UINT32 val)
{
    if (val > self->cap) return E_INVALIDARG;
    self->len = val;
    return S_OK;
}

static const void *gs_buf_vtable[] = {
    (const void *)gs_buf_QI, (const void *)gs_buf_AddRef, (const void *)gs_buf_Release,
    (const void *)gs_get_iids, (const void *)gs_buf_name, (const void *)gs_get_trust,
    (const void *)gs_buf_cap, (const void *)gs_buf_len, (const void *)gs_buf_set_len,
};

static HRESULT __stdcall gs_buf_b_QI(void *iface, const GUID *iid, void **out)
{
    return gs_buf_QI(gs_buf_from_bytes(iface), iid, out);
}
static ULONG __stdcall gs_buf_b_AddRef(void *iface)
{
    return gs_buf_AddRef(gs_buf_from_bytes(iface));
}
static ULONG __stdcall gs_buf_b_Release(void *iface)
{
    return gs_buf_Release(gs_buf_from_bytes(iface));
}
static HRESULT __stdcall gs_buf_b_Buffer(void *iface, BYTE **out)
{
    gs_buffer *self = gs_buf_from_bytes(iface);
    if (!out) return E_POINTER;
    *out = self->data;
    return S_OK;
}

static const void *gs_buf_bytes_vtable[] = {
    (const void *)gs_buf_b_QI, (const void *)gs_buf_b_AddRef, (const void *)gs_buf_b_Release,
    (const void *)gs_buf_b_Buffer,
};

static gs_buffer *gs_buf_create_owned(BYTE *data, UINT32 len)
{
    gs_buffer *b = (gs_buffer *)calloc(1, sizeof(gs_buffer));
    if (!b) {
        free(data);
        return NULL;
    }
    b->vtbl = gs_buf_vtable;
    b->bytes_vtbl = gs_buf_bytes_vtable;
    b->refs = 1;
    b->data = data ? data : (BYTE *)calloc(1, 1);
    b->cap = len > 0 ? len : 1;
    b->len = len;
    return b;
}

/* --------------------------------------------------------------- generic completed IAsyncOperation<T> */

enum gs_async_kind {
    GS_ASYNC_OBJ = 0,
    GS_ASYNC_I64 = 1,
    GS_ASYNC_U32 = 2,
};

typedef struct {
    const void **vtbl;
    const void **info_vtbl;
    LONG refs;
    UINT32 id;
    GUID op_iid;
    const char *class_name;
    enum gs_async_kind kind;
    void *obj_val;
    INT64 i64_val;
    UINT32 u32_val;
    void *handler;
    LONG fired;
} gs_async;

static LONG gs_next_async_id = 1;

static gs_async *gs_async_from_info(void *iface)
{
    return (gs_async *)((char *)iface - offsetof(gs_async, info_vtbl));
}

static ULONG __stdcall gs_async_AddRef(gs_async *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}

static ULONG __stdcall gs_async_Release(gs_async *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        gs_unk_release(self->obj_val);
        gs_unk_release(self->handler);
        free(self);
    }
    return (ULONG)r;
}

static HRESULT __stdcall gs_async_QI(gs_async *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &self->op_iid) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_async_AddRef(self);
        return S_OK;
    }
    if (IsEqualIID(iid, &gs_iid_async_info)) {
        *out = &self->info_vtbl;
        gs_async_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall gs_async_name(gs_async *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii(self->class_name ? self->class_name : "Windows.Foundation.IAsyncOperation");
    return S_OK;
}

static HRESULT __stdcall gs_async_put_Completed(gs_async *self, void *handler)
{
    void *old = self->handler;
    self->handler = handler;
    gs_unk_addref(handler);
    gs_unk_release(old);
    if (handler && InterlockedExchange(&self->fired, 1) == 0) {
        gs_async_AddRef(self);
        ((HRESULT(__stdcall *)(void *, void *, INT32))(*(void ***)handler)[3])(handler, self, 1);
        gs_async_Release(self);
    }
    return S_OK;
}

static HRESULT __stdcall gs_async_get_Completed(gs_async *self, void **out)
{
    if (!out) return E_POINTER;
    *out = self->handler;
    gs_unk_addref(self->handler);
    return S_OK;
}

static HRESULT __stdcall gs_async_GetResults(gs_async *self, void *out)
{
    if (!out) return E_POINTER;
    if (self->kind == GS_ASYNC_OBJ) {
        *(void **)out = self->obj_val;
        gs_unk_addref(self->obj_val);
    } else if (self->kind == GS_ASYNC_I64) {
        *(INT64 *)out = self->i64_val;
    } else {
        *(UINT32 *)out = self->u32_val;
    }
    return S_OK;
}

static const void *gs_async_vtable[] = {
    (const void *)gs_async_QI, (const void *)gs_async_AddRef, (const void *)gs_async_Release,
    (const void *)gs_get_iids, (const void *)gs_async_name, (const void *)gs_get_trust,
    (const void *)gs_async_put_Completed, (const void *)gs_async_get_Completed,
    (const void *)gs_async_GetResults,
};

static HRESULT __stdcall gs_ainfo_QI(void *iface, const GUID *iid, void **out)
{
    return gs_async_QI(gs_async_from_info(iface), iid, out);
}
static ULONG __stdcall gs_ainfo_AddRef(void *iface)
{
    return gs_async_AddRef(gs_async_from_info(iface));
}
static ULONG __stdcall gs_ainfo_Release(void *iface)
{
    return gs_async_Release(gs_async_from_info(iface));
}
static HRESULT __stdcall gs_ainfo_name(void *iface, hstring_t *out)
{
    return gs_async_name(gs_async_from_info(iface), out);
}
static HRESULT __stdcall gs_ainfo_Id(void *iface, UINT32 *out)
{
    if (!out) return E_POINTER;
    *out = gs_async_from_info(iface)->id;
    return S_OK;
}
static HRESULT __stdcall gs_ainfo_Status(void *iface, INT32 *out)
{
    (void)iface;
    if (!out) return E_POINTER;
    *out = 1; /* AsyncStatus_Completed */
    return S_OK;
}
static HRESULT __stdcall gs_ainfo_ErrorCode(void *iface, HRESULT *out)
{
    (void)iface;
    if (!out) return E_POINTER;
    *out = S_OK;
    return S_OK;
}
static HRESULT __stdcall gs_ainfo_Cancel(void *iface)
{
    (void)iface;
    return S_OK;
}
static HRESULT __stdcall gs_ainfo_Close(void *iface)
{
    (void)iface;
    return S_OK;
}

static const void *gs_ainfo_vtable[] = {
    (const void *)gs_ainfo_QI, (const void *)gs_ainfo_AddRef, (const void *)gs_ainfo_Release,
    (const void *)gs_get_iids, (const void *)gs_ainfo_name, (const void *)gs_get_trust,
    (const void *)gs_ainfo_Id, (const void *)gs_ainfo_Status, (const void *)gs_ainfo_ErrorCode,
    (const void *)gs_ainfo_Cancel, (const void *)gs_ainfo_Close,
};

static gs_async *gs_async_new_obj(const GUID *op_iid, const char *name, void *obj)
{
    gs_async *a = (gs_async *)calloc(1, sizeof(gs_async));
    if (!a) return NULL;
    a->vtbl = gs_async_vtable;
    a->info_vtbl = gs_ainfo_vtable;
    a->refs = 1;
    a->id = (UINT32)InterlockedIncrement(&gs_next_async_id);
    a->op_iid = *op_iid;
    a->class_name = name;
    a->kind = GS_ASYNC_OBJ;
    a->obj_val = obj;
    gs_unk_addref(obj);
    return a;
}

static gs_async *gs_async_new_i64(INT64 val)
{
    gs_async *a = (gs_async *)calloc(1, sizeof(gs_async));
    if (!a) return NULL;
    a->vtbl = gs_async_vtable;
    a->info_vtbl = gs_ainfo_vtable;
    a->refs = 1;
    a->id = (UINT32)InterlockedIncrement(&gs_next_async_id);
    a->op_iid = gs_iid_async_i64;
    a->class_name = "Windows.Foundation.IAsyncOperation`1<Int64>";
    a->kind = GS_ASYNC_I64;
    a->i64_val = val;
    return a;
}

static gs_async *gs_async_new_u32(UINT32 val)
{
    gs_async *a = (gs_async *)calloc(1, sizeof(gs_async));
    if (!a) return NULL;
    a->vtbl = gs_async_vtable;
    a->info_vtbl = gs_ainfo_vtable;
    a->refs = 1;
    a->id = (UINT32)InterlockedIncrement(&gs_next_async_id);
    a->op_iid = gs_iid_async_u32;
    a->class_name = "Windows.Foundation.IAsyncOperation`1<UInt32>";
    a->kind = GS_ASYNC_U32;
    a->u32_val = val;
    return a;
}

/* --------------------------------------------------------------- IVectorView<Object*> + IIterable + IIterator */

typedef struct {
    const void **vtbl;
    const void **iter_vtbl;
    LONG refs;
    GUID vec_iid;
    GUID iterable_iid;
    GUID iterator_iid;
    int is_string; /* 1 = HSTRING items (WCHAR*), 0 = COM IInspectable* items */
    void **items;
    UINT32 count;
} gs_vecview;

typedef struct {
    const void **vtbl;
    LONG refs;
    gs_vecview *owner;
    UINT32 pos;
} gs_veciter;

static gs_vecview *gs_vec_from_iter(void *iface)
{
    return (gs_vecview *)((char *)iface - offsetof(gs_vecview, iter_vtbl));
}

static ULONG __stdcall gs_vec_AddRef(gs_vecview *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}

static ULONG __stdcall gs_vec_Release(gs_vecview *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        UINT32 i;
        for (i = 0; i < self->count; i++) {
            if (self->is_string)
                free(self->items[i]);
            else
                gs_unk_release(self->items[i]);
        }
        free(self->items);
        free(self);
    }
    return (ULONG)r;
}

static HRESULT __stdcall gs_vec_QI(gs_vecview *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &self->vec_iid) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_vec_AddRef(self);
        return S_OK;
    }
    if (IsEqualIID(iid, &self->iterable_iid)) {
        *out = &self->iter_vtbl;
        gs_vec_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall gs_vec_name(gs_vecview *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Foundation.Collections.IVectorView");
    return S_OK;
}

static HRESULT __stdcall gs_vec_GetAt(gs_vecview *self, UINT32 index, void **out)
{
    if (!out) return E_POINTER;
    *out = NULL;
    if (index >= self->count) return GS_E_BOUNDS;
    if (self->is_string) {
        *out = gs_hstr((const WCHAR *)self->items[index]);
    } else {
        *out = self->items[index];
        gs_unk_addref(*out);
    }
    return S_OK;
}

static HRESULT __stdcall gs_vec_Size(gs_vecview *self, UINT32 *out)
{
    if (!out) return E_POINTER;
    *out = self->count;
    return S_OK;
}

static HRESULT __stdcall gs_vec_IndexOf(gs_vecview *self, void *val, UINT32 *idx, BYTE *found)
{
    UINT32 i;
    if (idx) *idx = 0;
    if (found) *found = 0;
    for (i = 0; i < self->count; i++) {
        int eq = self->is_string
                     ? (wcscmp((const WCHAR *)self->items[i], gs_hstr_raw((hstring_t)val)) == 0)
                     : (self->items[i] == val);
        if (eq) {
            if (idx) *idx = i;
            if (found) *found = 1;
            return S_OK;
        }
    }
    return S_OK;
}

static HRESULT __stdcall gs_vec_GetMany(gs_vecview *self, UINT32 start, UINT32 cap, void **items,
                                        UINT32 *actual)
{
    UINT32 n = 0;
    if (actual) *actual = 0;
    if (start >= self->count || !cap || !items) return S_OK;
    while (n < cap && start + n < self->count) {
        gs_vec_GetAt(self, start + n, &items[n]);
        n++;
    }
    if (actual) *actual = n;
    return S_OK;
}

static const void *gs_vec_vtable[] = {
    (const void *)gs_vec_QI, (const void *)gs_vec_AddRef, (const void *)gs_vec_Release,
    (const void *)gs_get_iids, (const void *)gs_vec_name, (const void *)gs_get_trust,
    (const void *)gs_vec_GetAt, (const void *)gs_vec_Size, (const void *)gs_vec_IndexOf,
    (const void *)gs_vec_GetMany,
};

static ULONG __stdcall gs_viter_AddRef(gs_veciter *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall gs_viter_Release(gs_veciter *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        gs_vec_Release(self->owner);
        free(self);
    }
    return (ULONG)r;
}
static HRESULT __stdcall gs_viter_QI(gs_veciter *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &self->owner->iterator_iid) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_viter_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall gs_viter_name(gs_veciter *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Foundation.Collections.IIterator");
    return S_OK;
}
static HRESULT __stdcall gs_viter_Current(gs_veciter *self, void **out)
{
    return gs_vec_GetAt(self->owner, self->pos, out);
}
static HRESULT __stdcall gs_viter_HasCurrent(gs_veciter *self, BYTE *out)
{
    if (!out) return E_POINTER;
    *out = (self->pos < self->owner->count) ? 1 : 0;
    return S_OK;
}
static HRESULT __stdcall gs_viter_MoveNext(gs_veciter *self, BYTE *out)
{
    if (self->pos < self->owner->count) self->pos++;
    if (out) *out = (self->pos < self->owner->count) ? 1 : 0;
    return S_OK;
}
static HRESULT __stdcall gs_viter_GetMany(gs_veciter *self, UINT32 cap, void **items, UINT32 *actual)
{
    UINT32 n = 0;
    gs_vec_GetMany(self->owner, self->pos, cap, items, &n);
    self->pos += n;
    if (actual) *actual = n;
    return S_OK;
}

static const void *gs_viter_vtable[] = {
    (const void *)gs_viter_QI, (const void *)gs_viter_AddRef, (const void *)gs_viter_Release,
    (const void *)gs_get_iids, (const void *)gs_viter_name, (const void *)gs_get_trust,
    (const void *)gs_viter_Current, (const void *)gs_viter_HasCurrent,
    (const void *)gs_viter_MoveNext, (const void *)gs_viter_GetMany,
};

static HRESULT __stdcall gs_viterable_QI(void *iface, const GUID *iid, void **out)
{
    return gs_vec_QI(gs_vec_from_iter(iface), iid, out);
}
static ULONG __stdcall gs_viterable_AddRef(void *iface)
{
    return gs_vec_AddRef(gs_vec_from_iter(iface));
}
static ULONG __stdcall gs_viterable_Release(void *iface)
{
    return gs_vec_Release(gs_vec_from_iter(iface));
}
static HRESULT __stdcall gs_viterable_name(void *iface, hstring_t *out)
{
    return gs_vec_name(gs_vec_from_iter(iface), out);
}
static HRESULT __stdcall gs_viterable_First(void *iface, void **out)
{
    gs_vecview *owner = gs_vec_from_iter(iface);
    gs_veciter *it;
    if (!out) return E_POINTER;
    it = (gs_veciter *)calloc(1, sizeof(gs_veciter));
    if (!it) return E_OUTOFMEMORY;
    it->vtbl = gs_viter_vtable;
    it->refs = 1;
    it->owner = owner;
    gs_vec_AddRef(owner);
    *out = it;
    return S_OK;
}

static const void *gs_viterable_vtable[] = {
    (const void *)gs_viterable_QI, (const void *)gs_viterable_AddRef,
    (const void *)gs_viterable_Release, (const void *)gs_get_iids,
    (const void *)gs_viterable_name, (const void *)gs_get_trust,
    (const void *)gs_viterable_First,
};

static gs_vecview *gs_vec_create(const GUID *vec_iid, const GUID *iterable_iid,
                                 const GUID *iterator_iid, int is_string)
{
    gs_vecview *v = (gs_vecview *)calloc(1, sizeof(gs_vecview));
    if (!v) return NULL;
    v->vtbl = gs_vec_vtable;
    v->iter_vtbl = gs_viterable_vtable;
    v->refs = 1;
    v->vec_iid = *vec_iid;
    v->iterable_iid = *iterable_iid;
    v->iterator_iid = *iterator_iid;
    v->is_string = is_string;
    return v;
}

static void gs_vec_push(gs_vecview *v, void *item)
{
    void **n = (void **)realloc(v->items, (v->count + 1) * sizeof(void *));
    if (!n) return;
    v->items = n;
    v->items[v->count++] = item;
}

/* --------------------------------------------------------------- IMapView<HSTRING, IBuffer*> */

typedef struct {
    const void **vtbl;
    LONG refs;
    WCHAR *key;
    void *val;
} gs_kvp;

static ULONG __stdcall gs_kvp_AddRef(gs_kvp *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall gs_kvp_Release(gs_kvp *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        free(self->key);
        gs_unk_release(self->val);
        free(self);
    }
    return (ULONG)r;
}
static HRESULT __stdcall gs_kvp_QI(gs_kvp *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &gs_iid_kvp_str_buf) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_kvp_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall gs_kvp_name(gs_kvp *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Foundation.Collections.IKeyValuePair");
    return S_OK;
}
static HRESULT __stdcall gs_kvp_Key(gs_kvp *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = gs_hstr(self->key);
    return S_OK;
}
static HRESULT __stdcall gs_kvp_Value(gs_kvp *self, void **out)
{
    if (!out) return E_POINTER;
    *out = self->val;
    gs_unk_addref(self->val);
    return S_OK;
}

static const void *gs_kvp_vtable[] = {
    (const void *)gs_kvp_QI, (const void *)gs_kvp_AddRef, (const void *)gs_kvp_Release,
    (const void *)gs_get_iids, (const void *)gs_kvp_name, (const void *)gs_get_trust,
    (const void *)gs_kvp_Key, (const void *)gs_kvp_Value,
};

typedef struct {
    const void **vtbl;
    const void **iter_vtbl;
    LONG refs;
    gs_kvp **entries;
    UINT32 count;
} gs_mapview;

typedef struct {
    const void **vtbl;
    LONG refs;
    gs_mapview *owner;
    UINT32 pos;
} gs_mapiter;

static gs_mapview *gs_map_from_iter(void *iface)
{
    return (gs_mapview *)((char *)iface - offsetof(gs_mapview, iter_vtbl));
}

static ULONG __stdcall gs_map_AddRef(gs_mapview *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall gs_map_Release(gs_mapview *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        UINT32 i;
        for (i = 0; i < self->count; i++) gs_kvp_Release(self->entries[i]);
        free(self->entries);
        free(self);
    }
    return (ULONG)r;
}
static HRESULT __stdcall gs_map_QI(gs_mapview *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &gs_iid_mapview_str_buf) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_map_AddRef(self);
        return S_OK;
    }
    if (IsEqualIID(iid, &gs_iid_iterable_kvp_str_buf)) {
        *out = &self->iter_vtbl;
        gs_map_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall gs_map_name(gs_mapview *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Foundation.Collections.IMapView");
    return S_OK;
}
static HRESULT __stdcall gs_map_Lookup(gs_mapview *self, hstring_t key, void **out)
{
    const WCHAR *k = gs_hstr_raw(key);
    UINT32 i;
    if (!out) return E_POINTER;
    *out = NULL;
    for (i = 0; i < self->count; i++) {
        if (wcscmp(self->entries[i]->key, k) == 0) {
            *out = self->entries[i]->val;
            gs_unk_addref(*out);
            return S_OK;
        }
    }
    return GS_E_BOUNDS;
}
static HRESULT __stdcall gs_map_Size(gs_mapview *self, UINT32 *out)
{
    if (!out) return E_POINTER;
    *out = self->count;
    return S_OK;
}
static HRESULT __stdcall gs_map_HasKey(gs_mapview *self, hstring_t key, BYTE *found)
{
    const WCHAR *k = gs_hstr_raw(key);
    UINT32 i;
    if (!found) return E_POINTER;
    *found = 0;
    for (i = 0; i < self->count; i++) {
        if (wcscmp(self->entries[i]->key, k) == 0) {
            *found = 1;
            return S_OK;
        }
    }
    return S_OK;
}
static HRESULT __stdcall gs_map_Split(gs_mapview *self, void **first, void **second)
{
    (void)self;
    if (first) *first = NULL;
    if (second) *second = NULL;
    return S_OK;
}

static const void *gs_map_vtable[] = {
    (const void *)gs_map_QI, (const void *)gs_map_AddRef, (const void *)gs_map_Release,
    (const void *)gs_get_iids, (const void *)gs_map_name, (const void *)gs_get_trust,
    (const void *)gs_map_Lookup, (const void *)gs_map_Size, (const void *)gs_map_HasKey,
    (const void *)gs_map_Split,
};

static ULONG __stdcall gs_miter_AddRef(gs_mapiter *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall gs_miter_Release(gs_mapiter *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        gs_map_Release(self->owner);
        free(self);
    }
    return (ULONG)r;
}
static HRESULT __stdcall gs_miter_QI(gs_mapiter *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &gs_iid_iterator_kvp_str_buf) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_miter_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall gs_miter_name(gs_mapiter *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Foundation.Collections.IIterator");
    return S_OK;
}
static HRESULT __stdcall gs_miter_Current(gs_mapiter *self, void **out)
{
    if (!out) return E_POINTER;
    *out = NULL;
    if (self->pos >= self->owner->count) return GS_E_BOUNDS;
    *out = self->owner->entries[self->pos];
    gs_kvp_AddRef(self->owner->entries[self->pos]);
    return S_OK;
}
static HRESULT __stdcall gs_miter_HasCurrent(gs_mapiter *self, BYTE *out)
{
    if (!out) return E_POINTER;
    *out = (self->pos < self->owner->count) ? 1 : 0;
    return S_OK;
}
static HRESULT __stdcall gs_miter_MoveNext(gs_mapiter *self, BYTE *out)
{
    if (self->pos < self->owner->count) self->pos++;
    if (out) *out = (self->pos < self->owner->count) ? 1 : 0;
    return S_OK;
}
static HRESULT __stdcall gs_miter_GetMany(gs_mapiter *self, UINT32 cap, void **items, UINT32 *actual)
{
    UINT32 n = 0;
    while (n < cap && self->pos < self->owner->count) {
        items[n] = self->owner->entries[self->pos];
        gs_kvp_AddRef(self->owner->entries[self->pos]);
        self->pos++;
        n++;
    }
    if (actual) *actual = n;
    return S_OK;
}

static const void *gs_miter_vtable[] = {
    (const void *)gs_miter_QI, (const void *)gs_miter_AddRef, (const void *)gs_miter_Release,
    (const void *)gs_get_iids, (const void *)gs_miter_name, (const void *)gs_get_trust,
    (const void *)gs_miter_Current, (const void *)gs_miter_HasCurrent,
    (const void *)gs_miter_MoveNext, (const void *)gs_miter_GetMany,
};

static HRESULT __stdcall gs_miterable_QI(void *iface, const GUID *iid, void **out)
{
    return gs_map_QI(gs_map_from_iter(iface), iid, out);
}
static ULONG __stdcall gs_miterable_AddRef(void *iface)
{
    return gs_map_AddRef(gs_map_from_iter(iface));
}
static ULONG __stdcall gs_miterable_Release(void *iface)
{
    return gs_map_Release(gs_map_from_iter(iface));
}
static HRESULT __stdcall gs_miterable_name(void *iface, hstring_t *out)
{
    return gs_map_name(gs_map_from_iter(iface), out);
}
static HRESULT __stdcall gs_miterable_First(void *iface, void **out)
{
    gs_mapview *owner = gs_map_from_iter(iface);
    gs_mapiter *it;
    if (!out) return E_POINTER;
    it = (gs_mapiter *)calloc(1, sizeof(gs_mapiter));
    if (!it) return E_OUTOFMEMORY;
    it->vtbl = gs_miter_vtable;
    it->refs = 1;
    it->owner = owner;
    gs_map_AddRef(owner);
    *out = it;
    return S_OK;
}

static const void *gs_miterable_vtable[] = {
    (const void *)gs_miterable_QI, (const void *)gs_miterable_AddRef,
    (const void *)gs_miterable_Release, (const void *)gs_get_iids,
    (const void *)gs_miterable_name, (const void *)gs_get_trust,
    (const void *)gs_miterable_First,
};

static gs_mapview *gs_map_create(void)
{
    gs_mapview *m = (gs_mapview *)calloc(1, sizeof(gs_mapview));
    if (!m) return NULL;
    m->vtbl = gs_map_vtable;
    m->iter_vtbl = gs_miterable_vtable;
    m->refs = 1;
    return m;
}

static void gs_map_put(gs_mapview *m, const WCHAR *key, void *buf_iface)
{
    gs_kvp *kv = (gs_kvp *)calloc(1, sizeof(gs_kvp));
    gs_kvp **n;
    if (!kv) return;
    kv->vtbl = gs_kvp_vtable;
    kv->refs = 1;
    kv->key = gs_wcsdup(key);
    kv->val = buf_iface;
    gs_unk_addref(buf_iface);
    n = (gs_kvp **)realloc(m->entries, (m->count + 1) * sizeof(gs_kvp *));
    if (!n) {
        gs_kvp_Release(kv);
        return;
    }
    m->entries = n;
    m->entries[m->count++] = kv;
}

/* --------------------------------------------------------------- Result objects */

typedef struct {
    const void **vtbl;
    LONG refs;
    GUID iid;
    const char *class_name;
    INT32 status;
    void *value; /* AddRef'd if non-NULL */
} gs_result;

static ULONG __stdcall gs_res_AddRef(gs_result *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall gs_res_Release(gs_result *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        gs_unk_release(self->value);
        free(self);
    }
    return (ULONG)r;
}
static HRESULT __stdcall gs_res_QI(gs_result *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &self->iid) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_res_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall gs_res_name(gs_result *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii(self->class_name);
    return S_OK;
}
static HRESULT __stdcall gs_res_Status(gs_result *self, INT32 *out)
{
    if (!out) return E_POINTER;
    *out = self->status;
    return S_OK;
}
static HRESULT __stdcall gs_res_Value(gs_result *self, void **out)
{
    if (!out) return E_POINTER;
    *out = self->value;
    gs_unk_addref(self->value);
    return S_OK;
}

static const void *gs_res_vtable[] = {
    (const void *)gs_res_QI, (const void *)gs_res_AddRef, (const void *)gs_res_Release,
    (const void *)gs_get_iids, (const void *)gs_res_name, (const void *)gs_get_trust,
    (const void *)gs_res_Status, (const void *)gs_res_Value,
};

static gs_result *gs_res_new(const GUID *iid, const char *name, INT32 status, void *value)
{
    gs_result *r = (gs_result *)calloc(1, sizeof(gs_result));
    if (!r) return NULL;
    r->vtbl = gs_res_vtable;
    r->refs = 1;
    r->iid = *iid;
    r->class_name = name;
    r->status = status;
    r->value = value;
    gs_unk_addref(value);
    return r;
}

/* --------------------------------------------------------------- GameSaveBlobInfo */

typedef struct {
    const void **vtbl;
    LONG refs;
    WCHAR *name;
    UINT32 size;
} gs_blob_info;

static ULONG __stdcall gs_binfo_AddRef(gs_blob_info *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall gs_binfo_Release(gs_blob_info *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        free(self->name);
        free(self);
    }
    return (ULONG)r;
}
static HRESULT __stdcall gs_binfo_QI(gs_blob_info *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &gs_iid_blob_info) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_binfo_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall gs_binfo_cls(gs_blob_info *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Gaming.XboxLive.Storage.GameSaveBlobInfo");
    return S_OK;
}
static HRESULT __stdcall gs_binfo_Name(gs_blob_info *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = gs_hstr(self->name);
    return S_OK;
}
static HRESULT __stdcall gs_binfo_Size(gs_blob_info *self, UINT32 *out)
{
    if (!out) return E_POINTER;
    *out = self->size;
    return S_OK;
}

static const void *gs_binfo_vtable[] = {
    (const void *)gs_binfo_QI, (const void *)gs_binfo_AddRef, (const void *)gs_binfo_Release,
    (const void *)gs_get_iids, (const void *)gs_binfo_cls, (const void *)gs_get_trust,
    (const void *)gs_binfo_Name, (const void *)gs_binfo_Size,
};

/* --------------------------------------------------------------- GameSaveBlobInfoQuery */

typedef struct {
    const void **vtbl;
    LONG refs;
    WCHAR *dir;
    WCHAR *prefix;
} gs_bquery;

static ULONG __stdcall gs_bq_AddRef(gs_bquery *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall gs_bq_Release(gs_bquery *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        free(self->dir);
        free(self->prefix);
        free(self);
    }
    return (ULONG)r;
}
static HRESULT __stdcall gs_bq_QI(gs_bquery *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &gs_iid_blob_info_query) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_bq_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall gs_bq_cls(gs_bquery *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Gaming.XboxLive.Storage.GameSaveBlobInfoQuery");
    return S_OK;
}

static gs_vecview *gs_scan_blobs(const WCHAR *dir, const WCHAR *prefix, UINT32 start, UINT32 max_n)
{
    gs_vecview *vec = gs_vec_create(&gs_iid_vecview_blob_info, &gs_iid_iterable_blob_info,
                                    &gs_iid_iterator_blob_info, 0);
    WCHAR pat[MAX_PATH * 2];
    WIN32_FIND_DATAW fd;
    HANDLE h;
    UINT32 idx = 0;

    if (!vec) return NULL;
    _snwprintf(pat, sizeof(pat) / sizeof(pat[0]), L"%ls\\*", dir);
    pat[sizeof(pat) / sizeof(pat[0]) - 1] = 0;
    h = FindFirstFileW(pat, &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            WCHAR decoded[260];
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
            if (fd.cFileName[0] == L'.') continue;
            if (wcsstr(fd.cFileName, L".tmp")) continue;
            gs_decode_name(fd.cFileName, decoded, sizeof(decoded) / sizeof(decoded[0]));
            if (!gs_starts_with(decoded, prefix)) continue;
            if (idx >= start && (max_n == 0 || vec->count < max_n)) {
                gs_blob_info *bi = (gs_blob_info *)calloc(1, sizeof(gs_blob_info));
                if (bi) {
                    bi->vtbl = gs_binfo_vtable;
                    bi->refs = 1;
                    bi->name = gs_wcsdup(decoded);
                    bi->size = fd.nFileSizeLow;
                    gs_vec_push(vec, bi);
                }
            }
            idx++;
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }
    return vec;
}

static HRESULT __stdcall gs_bq_GetWithRange(gs_bquery *self, UINT32 start, UINT32 max_n, void **out)
{
    gs_vecview *vec;
    gs_result *res;
    if (!out) return E_POINTER;
    vec = gs_scan_blobs(self->dir, self->prefix, start, max_n);
    res = gs_res_new(&gs_iid_blob_info_get,
                     "Windows.Gaming.XboxLive.Storage.GameSaveBlobInfoGetResult", GS_OK, vec);
    gs_vec_Release(vec);
    *out = gs_async_new_obj(&gs_iid_async_blob_info_get,
                            "Windows.Foundation.IAsyncOperation`1<GameSaveBlobInfoGetResult>", res);
    gs_res_Release(res);
    return *out ? S_OK : E_OUTOFMEMORY;
}

static HRESULT __stdcall gs_bq_GetAll(gs_bquery *self, void **out)
{
    return gs_bq_GetWithRange(self, 0, 0, out);
}

static HRESULT __stdcall gs_bq_GetCount(gs_bquery *self, void **out)
{
    gs_vecview *vec;
    UINT32 n;
    if (!out) return E_POINTER;
    vec = gs_scan_blobs(self->dir, self->prefix, 0, 0);
    n = vec ? vec->count : 0;
    if (vec) gs_vec_Release(vec);
    *out = gs_async_new_u32(n);
    return *out ? S_OK : E_OUTOFMEMORY;
}

static const void *gs_bq_vtable[] = {
    (const void *)gs_bq_QI, (const void *)gs_bq_AddRef, (const void *)gs_bq_Release,
    (const void *)gs_get_iids, (const void *)gs_bq_cls, (const void *)gs_get_trust,
    (const void *)gs_bq_GetAll, (const void *)gs_bq_GetWithRange, (const void *)gs_bq_GetCount,
};

/* --------------------------------------------------------------- GameSaveContainerInfo */

typedef struct {
    const void **vtbl;
    LONG refs;
    WCHAR *name;
    WCHAR *display_name;
    UINT64 total_size;
    INT64 last_modified;
} gs_cont_info;

static ULONG __stdcall gs_cinfo_AddRef(gs_cont_info *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall gs_cinfo_Release(gs_cont_info *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        free(self->name);
        free(self->display_name);
        free(self);
    }
    return (ULONG)r;
}
static HRESULT __stdcall gs_cinfo_QI(gs_cont_info *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &gs_iid_cont_info) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_cinfo_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall gs_cinfo_cls(gs_cont_info *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Gaming.XboxLive.Storage.GameSaveContainerInfo");
    return S_OK;
}
static HRESULT __stdcall gs_cinfo_Name(gs_cont_info *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = gs_hstr(self->name);
    return S_OK;
}
static HRESULT __stdcall gs_cinfo_TotalSize(gs_cont_info *self, UINT64 *out)
{
    if (!out) return E_POINTER;
    *out = self->total_size;
    return S_OK;
}
static HRESULT __stdcall gs_cinfo_DisplayName(gs_cont_info *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = gs_hstr(self->display_name ? self->display_name : self->name);
    return S_OK;
}
static HRESULT __stdcall gs_cinfo_LastMod(gs_cont_info *self, INT64 *out)
{
    if (!out) return E_POINTER;
    *out = self->last_modified;
    return S_OK;
}
static HRESULT __stdcall gs_cinfo_NeedsSync(gs_cont_info *self, BYTE *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

static const void *gs_cinfo_vtable[] = {
    (const void *)gs_cinfo_QI, (const void *)gs_cinfo_AddRef, (const void *)gs_cinfo_Release,
    (const void *)gs_get_iids, (const void *)gs_cinfo_cls, (const void *)gs_get_trust,
    (const void *)gs_cinfo_Name, (const void *)gs_cinfo_TotalSize,
    (const void *)gs_cinfo_DisplayName, (const void *)gs_cinfo_LastMod,
    (const void *)gs_cinfo_NeedsSync,
};

/* --------------------------------------------------------------- GameSaveContainerInfoQuery */

typedef struct {
    const void **vtbl;
    LONG refs;
    WCHAR *root;
    WCHAR *prefix;
} gs_cquery;

static ULONG __stdcall gs_cq_AddRef(gs_cquery *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall gs_cq_Release(gs_cquery *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        free(self->root);
        free(self->prefix);
        free(self);
    }
    return (ULONG)r;
}
static HRESULT __stdcall gs_cq_QI(gs_cquery *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &gs_iid_cont_info_query) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_cq_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall gs_cq_cls(gs_cquery *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Gaming.XboxLive.Storage.GameSaveContainerInfoQuery");
    return S_OK;
}

static UINT64 gs_dir_total_size(const WCHAR *dir, INT64 *latest_ft)
{
    WCHAR pat[MAX_PATH * 2];
    WIN32_FIND_DATAW fd;
    HANDLE h;
    UINT64 total = 0;
    _snwprintf(pat, sizeof(pat) / sizeof(pat[0]), L"%ls\\*", dir);
    pat[sizeof(pat) / sizeof(pat[0]) - 1] = 0;
    h = FindFirstFileW(pat, &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
            if (fd.cFileName[0] == L'.') continue;
            total += ((UINT64)fd.nFileSizeHigh << 32) | fd.nFileSizeLow;
            INT64 ft = ((INT64)fd.ftLastWriteTime.dwHighDateTime << 32) |
                       fd.ftLastWriteTime.dwLowDateTime;
            if (ft > *latest_ft) *latest_ft = ft;
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }
    return total;
}

static gs_vecview *gs_scan_containers(const WCHAR *root, const WCHAR *prefix, UINT32 start,
                                      UINT32 max_n)
{
    gs_vecview *vec = gs_vec_create(&gs_iid_vecview_cont_info, &gs_iid_iterable_cont_info,
                                    &gs_iid_iterator_cont_info, 0);
    WCHAR pat[MAX_PATH * 2];
    WIN32_FIND_DATAW fd;
    HANDLE h;
    UINT32 idx = 0;

    if (!vec) return NULL;
    _snwprintf(pat, sizeof(pat) / sizeof(pat[0]), L"%ls\\*", root);
    pat[sizeof(pat) / sizeof(pat[0]) - 1] = 0;
    h = FindFirstFileW(pat, &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            WCHAR decoded[260], sub[MAX_PATH * 2], dn_path[MAX_PATH * 2];
            if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
            if (!wcscmp(fd.cFileName, L".") || !wcscmp(fd.cFileName, L"..")) continue;
            if (fd.cFileName[0] == L'.') continue;
            gs_decode_name(fd.cFileName, decoded, sizeof(decoded) / sizeof(decoded[0]));
            if (!gs_starts_with(decoded, prefix)) continue;
            if (idx >= start && (max_n == 0 || vec->count < max_n)) {
                gs_cont_info *ci = (gs_cont_info *)calloc(1, sizeof(gs_cont_info));
                if (ci) {
                    DWORD dn_len = 0;
                    BYTE *dn_bytes;
                    _snwprintf(sub, sizeof(sub) / sizeof(sub[0]), L"%ls\\%ls", root, fd.cFileName);
                    sub[sizeof(sub) / sizeof(sub[0]) - 1] = 0;
                    ci->vtbl = gs_cinfo_vtable;
                    ci->refs = 1;
                    ci->name = gs_wcsdup(decoded);
                    ci->last_modified = ((INT64)fd.ftLastWriteTime.dwHighDateTime << 32) |
                                        fd.ftLastWriteTime.dwLowDateTime;
                    ci->total_size = gs_dir_total_size(sub, &ci->last_modified);
                    _snwprintf(dn_path, sizeof(dn_path) / sizeof(dn_path[0]), L"%ls\\.displayname",
                               sub);
                    dn_path[sizeof(dn_path) / sizeof(dn_path[0]) - 1] = 0;
                    dn_bytes = gs_read_file_all(dn_path, &dn_len);
                    if (dn_bytes && dn_len >= sizeof(WCHAR)) {
                        size_t wlen = dn_len / sizeof(WCHAR);
                        ci->display_name = (WCHAR *)malloc((wlen + 1) * sizeof(WCHAR));
                        if (ci->display_name) {
                            memcpy(ci->display_name, dn_bytes, wlen * sizeof(WCHAR));
                            ci->display_name[wlen] = 0;
                        }
                    }
                    free(dn_bytes);
                    gs_vec_push(vec, ci);
                }
            }
            idx++;
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }
    return vec;
}

static HRESULT __stdcall gs_cq_GetWithRange(gs_cquery *self, UINT32 start, UINT32 max_n, void **out)
{
    gs_vecview *vec;
    gs_result *res;
    if (!out) return E_POINTER;
    vec = gs_scan_containers(self->root, self->prefix, start, max_n);
    gs_log("ContainerInfoQuery::GetContainerInfoAsync -> count=%u", vec ? vec->count : 0);
    res = gs_res_new(&gs_iid_cont_info_get,
                     "Windows.Gaming.XboxLive.Storage.GameSaveContainerInfoGetResult", GS_OK, vec);
    gs_vec_Release(vec);
    *out = gs_async_new_obj(&gs_iid_async_cont_info_get,
                            "Windows.Foundation.IAsyncOperation`1<GameSaveContainerInfoGetResult>",
                            res);
    gs_res_Release(res);
    return *out ? S_OK : E_OUTOFMEMORY;
}

static HRESULT __stdcall gs_cq_GetAll(gs_cquery *self, void **out)
{
    return gs_cq_GetWithRange(self, 0, 0, out);
}

static HRESULT __stdcall gs_cq_GetCount(gs_cquery *self, void **out)
{
    gs_vecview *vec;
    UINT32 n;
    if (!out) return E_POINTER;
    vec = gs_scan_containers(self->root, self->prefix, 0, 0);
    n = vec ? vec->count : 0;
    if (vec) gs_vec_Release(vec);
    *out = gs_async_new_u32(n);
    return *out ? S_OK : E_OUTOFMEMORY;
}

static const void *gs_cq_vtable[] = {
    (const void *)gs_cq_QI, (const void *)gs_cq_AddRef, (const void *)gs_cq_Release,
    (const void *)gs_get_iids, (const void *)gs_cq_cls, (const void *)gs_get_trust,
    (const void *)gs_cq_GetAll, (const void *)gs_cq_GetWithRange, (const void *)gs_cq_GetCount,
};

/* --------------------------------------------------------------- GameSaveContainer */

typedef struct gs_provider gs_provider;

typedef struct {
    const void **vtbl;
    LONG refs;
    gs_provider *provider;
    WCHAR *name;
    WCHAR *dir;
} gs_container;

static ULONG __stdcall gs_prov_AddRef(gs_provider *self);
static ULONG __stdcall gs_prov_Release(gs_provider *self);

static ULONG __stdcall gs_cont_AddRef(gs_container *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall gs_cont_Release(gs_container *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        if (self->provider) gs_prov_Release(self->provider);
        free(self->name);
        free(self->dir);
        free(self);
    }
    return (ULONG)r;
}
static HRESULT __stdcall gs_cont_QI(gs_container *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &gs_iid_container) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_cont_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall gs_cont_cls(gs_container *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Gaming.XboxLive.Storage.GameSaveContainer");
    return S_OK;
}
static HRESULT __stdcall gs_cont_Name(gs_container *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = gs_hstr(self->name);
    return S_OK;
}
static HRESULT __stdcall gs_cont_Provider(gs_container *self, void **out)
{
    if (!out) return E_POINTER;
    *out = self->provider;
    if (self->provider) gs_prov_AddRef(self->provider);
    return S_OK;
}

static void gs_blob_path(const WCHAR *dir, const WCHAR *blob_name, WCHAR *out, size_t cap)
{
    WCHAR enc[260];
    gs_encode_name(blob_name, enc, sizeof(enc) / sizeof(enc[0]));
    _snwprintf(out, cap, L"%ls\\%ls", dir, enc);
    out[cap - 1] = 0;
}

static HRESULT __stdcall gs_cont_SubmitUpdatesAsync(gs_container *self, void *blobs_to_write,
                                                    void *blobs_to_delete, hstring_t display_name,
                                                    void **out)
{
    int wrote = 0, deleted = 0;
    gs_result *res;
    const WCHAR *dn = gs_hstr_raw(display_name);

    if (!out) return E_POINTER;
    gs_ensure_dir(self->dir);

    if (dn && dn[0]) {
        WCHAR dn_path[MAX_PATH * 2];
        _snwprintf(dn_path, sizeof(dn_path) / sizeof(dn_path[0]), L"%ls\\.displayname", self->dir);
        dn_path[sizeof(dn_path) / sizeof(dn_path[0]) - 1] = 0;
        gs_write_file_atomic(dn_path, (const BYTE *)dn, (DWORD)(wcslen(dn) * sizeof(WCHAR)));
    }

    if (blobs_to_write) {
        void *iterable = NULL;
        if (SUCCEEDED(gs_unk_qi(blobs_to_write, &gs_iid_iterable_kvp_str_buf, &iterable)) &&
            iterable) {
            void *iter = NULL;
            if (SUCCEEDED(((HRESULT(__stdcall *)(void *, void **))(*(void ***)iterable)[6])(
                    iterable, &iter)) &&
                iter) {
                BYTE has = 0;
                ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[7])(iter, &has);
                while (has) {
                    void *kvp = NULL;
                    if (SUCCEEDED(
                            ((HRESULT(__stdcall *)(void *, void **))(*(void ***)iter)[6])(iter, &kvp)) &&
                        kvp) {
                        hstring_t hkey = NULL;
                        void *ibuf = NULL;
                        ((HRESULT(__stdcall *)(void *, hstring_t *))(*(void ***)kvp)[6])(kvp, &hkey);
                        ((HRESULT(__stdcall *)(void *, void **))(*(void ***)kvp)[7])(kvp, &ibuf);
                        if (hkey && ibuf) {
                            void *bba = NULL;
                            UINT32 blen = 0;
                            BYTE *ptr = NULL;
                            ((HRESULT(__stdcall *)(void *, UINT32 *))(*(void ***)ibuf)[7])(ibuf, &blen);
                            if (SUCCEEDED(gs_unk_qi(ibuf, &gs_iid_ibuffer_bytes, &bba)) && bba) {
                                if (SUCCEEDED(((HRESULT(__stdcall *)(void *, BYTE **))(
                                        *(void ***)bba)[3])(bba, &ptr))) {
                                    WCHAR bpath[MAX_PATH * 2];
                                    gs_blob_path(self->dir, gs_hstr_raw(hkey), bpath,
                                                 sizeof(bpath) / sizeof(bpath[0]));
                                    gs_write_file_atomic(bpath, ptr ? ptr : (const BYTE *)"", blen);
                                    wrote++;
                                }
                                gs_unk_release(bba);
                            }
                        }
                        gs_hstr_free(hkey);
                        gs_unk_release(ibuf);
                        gs_unk_release(kvp);
                    }
                    has = 0;
                    ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[8])(iter, &has);
                }
                gs_unk_release(iter);
            }
            gs_unk_release(iterable);
        }
    }

    if (blobs_to_delete) {
        void *iterable = NULL;
        if (SUCCEEDED(gs_unk_qi(blobs_to_delete, &gs_iid_iterable_string, &iterable)) && iterable) {
            void *iter = NULL;
            if (SUCCEEDED(((HRESULT(__stdcall *)(void *, void **))(*(void ***)iterable)[6])(
                    iterable, &iter)) &&
                iter) {
                BYTE has = 0;
                ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[7])(iter, &has);
                while (has) {
                    hstring_t hkey = NULL;
                    if (SUCCEEDED(((HRESULT(__stdcall *)(void *, hstring_t *))(*(void ***)iter)[6])(
                            iter, &hkey)) &&
                        hkey) {
                        WCHAR bpath[MAX_PATH * 2];
                        gs_blob_path(self->dir, gs_hstr_raw(hkey), bpath,
                                     sizeof(bpath) / sizeof(bpath[0]));
                        DeleteFileW(bpath);
                        deleted++;
                        gs_hstr_free(hkey);
                    }
                    has = 0;
                    ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[8])(iter, &has);
                }
                gs_unk_release(iter);
            }
            gs_unk_release(iterable);
        }
    }

    gs_log("Container::SubmitUpdatesAsync(%ls) wrote=%d deleted=%d", self->name, wrote, deleted);
    res = gs_res_new(&gs_iid_op_result, "Windows.Gaming.XboxLive.Storage.GameSaveOperationResult",
                     GS_OK, NULL);
    *out = gs_async_new_obj(&gs_iid_async_op_result,
                            "Windows.Foundation.IAsyncOperation`1<GameSaveOperationResult>", res);
    gs_res_Release(res);
    return *out ? S_OK : E_OUTOFMEMORY;
}

static HRESULT __stdcall gs_cont_ReadAsync(gs_container *self, void *blobs_to_read, void **out)
{
    INT32 status = GS_OK;
    int read_n = 0;
    gs_result *res;

    if (!out) return E_POINTER;
    if (blobs_to_read) {
        void *iterable = NULL;
        if (SUCCEEDED(gs_unk_qi(blobs_to_read, &gs_iid_iterable_kvp_str_buf, &iterable)) &&
            iterable) {
            void *iter = NULL;
            if (SUCCEEDED(((HRESULT(__stdcall *)(void *, void **))(*(void ***)iterable)[6])(
                    iterable, &iter)) &&
                iter) {
                BYTE has = 0;
                ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[7])(iter, &has);
                while (has && status == GS_OK) {
                    void *kvp = NULL;
                    if (SUCCEEDED(
                            ((HRESULT(__stdcall *)(void *, void **))(*(void ***)iter)[6])(iter, &kvp)) &&
                        kvp) {
                        hstring_t hkey = NULL;
                        void *ibuf = NULL;
                        ((HRESULT(__stdcall *)(void *, hstring_t *))(*(void ***)kvp)[6])(kvp, &hkey);
                        ((HRESULT(__stdcall *)(void *, void **))(*(void ***)kvp)[7])(kvp, &ibuf);
                        if (hkey && ibuf) {
                            WCHAR bpath[MAX_PATH * 2];
                            DWORD flen = 0;
                            BYTE *fdata;
                            gs_blob_path(self->dir, gs_hstr_raw(hkey), bpath,
                                         sizeof(bpath) / sizeof(bpath[0]));
                            fdata = gs_read_file_all(bpath, &flen);
                            if (!fdata) {
                                status = GS_BLOB_NOT_FOUND;
                            } else {
                                UINT32 cap = 0;
                                ((HRESULT(__stdcall *)(void *, UINT32 *))(*(void ***)ibuf)[6])(
                                    ibuf, &cap);
                                if (flen > cap) {
                                    status = GS_BUFFER_TOO_SMALL;
                                } else {
                                    void *bba = NULL;
                                    BYTE *dst = NULL;
                                    if (SUCCEEDED(gs_unk_qi(ibuf, &gs_iid_ibuffer_bytes, &bba)) &&
                                        bba) {
                                        if (SUCCEEDED(((HRESULT(__stdcall *)(void *, BYTE **))(
                                                *(void ***)bba)[3])(bba, &dst)) &&
                                            dst && flen > 0) {
                                            memcpy(dst, fdata, flen);
                                        }
                                        gs_unk_release(bba);
                                    }
                                    ((HRESULT(__stdcall *)(void *, UINT32))(*(void ***)ibuf)[8])(
                                        ibuf, flen);
                                    read_n++;
                                }
                                free(fdata);
                            }
                        }
                        gs_hstr_free(hkey);
                        gs_unk_release(ibuf);
                        gs_unk_release(kvp);
                    }
                    has = 0;
                    ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[8])(iter, &has);
                }
                gs_unk_release(iter);
            }
            gs_unk_release(iterable);
        }
    }

    gs_log("Container::ReadAsync(%ls) count=%d status=%d", self->name, read_n, status);
    res = gs_res_new(&gs_iid_op_result, "Windows.Gaming.XboxLive.Storage.GameSaveOperationResult",
                     status, NULL);
    *out = gs_async_new_obj(&gs_iid_async_op_result,
                            "Windows.Foundation.IAsyncOperation`1<GameSaveOperationResult>", res);
    gs_res_Release(res);
    return *out ? S_OK : E_OUTOFMEMORY;
}

static HRESULT __stdcall gs_cont_GetAsync(gs_container *self, void *blobs_to_read, void **out)
{
    gs_mapview *map = gs_map_create();
    INT32 status = GS_OK;
    gs_result *res;

    if (!out) {
        if (map) gs_map_Release(map);
        return E_POINTER;
    }

    if (blobs_to_read) {
        void *iterable = NULL;
        if (SUCCEEDED(gs_unk_qi(blobs_to_read, &gs_iid_iterable_string, &iterable)) && iterable) {
            void *iter = NULL;
            if (SUCCEEDED(((HRESULT(__stdcall *)(void *, void **))(*(void ***)iterable)[6])(
                    iterable, &iter)) &&
                iter) {
                BYTE has = 0;
                ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[7])(iter, &has);
                while (has) {
                    hstring_t hkey = NULL;
                    if (SUCCEEDED(((HRESULT(__stdcall *)(void *, hstring_t *))(*(void ***)iter)[6])(
                            iter, &hkey)) &&
                        hkey) {
                        const WCHAR *bname = gs_hstr_raw(hkey);
                        WCHAR bpath[MAX_PATH * 2];
                        DWORD flen = 0;
                        BYTE *fdata;
                        gs_blob_path(self->dir, bname, bpath, sizeof(bpath) / sizeof(bpath[0]));
                        fdata = gs_read_file_all(bpath, &flen);
                        if (fdata) {
                            gs_buffer *buf = gs_buf_create_owned(fdata, flen);
                            if (buf) {
                                gs_map_put(map, bname, buf);
                                gs_buf_Release(buf);
                            }
                        } else {
                            status = GS_BLOB_NOT_FOUND;
                        }
                        gs_hstr_free(hkey);
                    }
                    has = 0;
                    ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[8])(iter, &has);
                }
                gs_unk_release(iter);
            }
            gs_unk_release(iterable);
        }
    } else {
        /* NULL blobs_to_read: load all blobs in container */
        WCHAR pat[MAX_PATH * 2];
        WIN32_FIND_DATAW fd;
        HANDLE h;
        _snwprintf(pat, sizeof(pat) / sizeof(pat[0]), L"%ls\\*", self->dir);
        pat[sizeof(pat) / sizeof(pat[0]) - 1] = 0;
        h = FindFirstFileW(pat, &fd);
        if (h != INVALID_HANDLE_VALUE) {
            do {
                WCHAR decoded[260], bpath[MAX_PATH * 2];
                DWORD flen = 0;
                BYTE *fdata;
                if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
                if (fd.cFileName[0] == L'.') continue;
                if (wcsstr(fd.cFileName, L".tmp")) continue;
                gs_decode_name(fd.cFileName, decoded, sizeof(decoded) / sizeof(decoded[0]));
                _snwprintf(bpath, sizeof(bpath) / sizeof(bpath[0]), L"%ls\\%ls", self->dir,
                           fd.cFileName);
                bpath[sizeof(bpath) / sizeof(bpath[0]) - 1] = 0;
                fdata = gs_read_file_all(bpath, &flen);
                if (fdata) {
                    gs_buffer *buf = gs_buf_create_owned(fdata, flen);
                    if (buf) {
                        gs_map_put(map, decoded, buf);
                        gs_buf_Release(buf);
                    }
                }
            } while (FindNextFileW(h, &fd));
            FindClose(h);
        }
    }

    gs_log("Container::GetAsync(%ls) found=%u status=%d", self->name, map ? map->count : 0, status);
    res = gs_res_new(&gs_iid_blob_get, "Windows.Gaming.XboxLive.Storage.GameSaveBlobGetResult",
                     status, map);
    gs_map_Release(map);
    *out = gs_async_new_obj(&gs_iid_async_blob_get,
                            "Windows.Foundation.IAsyncOperation`1<GameSaveBlobGetResult>", res);
    gs_res_Release(res);
    return *out ? S_OK : E_OUTOFMEMORY;
}

static HRESULT __stdcall gs_cont_SubmitPropUpdatesAsync(gs_container *self, void *props_to_write,
                                                        void *blobs_to_delete,
                                                        hstring_t display_name, void **out)
{
    (void)props_to_write;
    return gs_cont_SubmitUpdatesAsync(self, NULL, blobs_to_delete, display_name, out);
}

static HRESULT __stdcall gs_cont_CreateBlobInfoQuery(gs_container *self, hstring_t prefix,
                                                     void **out)
{
    gs_bquery *q;
    if (!out) return E_POINTER;
    q = (gs_bquery *)calloc(1, sizeof(gs_bquery));
    if (!q) return E_OUTOFMEMORY;
    q->vtbl = gs_bq_vtable;
    q->refs = 1;
    q->dir = gs_wcsdup(self->dir);
    q->prefix = gs_wcsdup(gs_hstr_raw(prefix));
    *out = q;
    return S_OK;
}

static const void *gs_cont_vtable[] = {
    (const void *)gs_cont_QI, (const void *)gs_cont_AddRef, (const void *)gs_cont_Release,
    (const void *)gs_get_iids, (const void *)gs_cont_cls, (const void *)gs_get_trust,
    (const void *)gs_cont_Name, (const void *)gs_cont_Provider,
    (const void *)gs_cont_SubmitUpdatesAsync, (const void *)gs_cont_ReadAsync,
    (const void *)gs_cont_GetAsync, (const void *)gs_cont_SubmitPropUpdatesAsync,
    (const void *)gs_cont_CreateBlobInfoQuery,
};

/* --------------------------------------------------------------- GameSaveProvider */

struct gs_provider {
    const void **vtbl;
    LONG refs;
    void *user;
    WCHAR *root;
};

static ULONG __stdcall gs_prov_AddRef(gs_provider *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall gs_prov_Release(gs_provider *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        gs_unk_release(self->user);
        free(self->root);
        free(self);
    }
    return (ULONG)r;
}
static HRESULT __stdcall gs_prov_QI(gs_provider *self, const GUID *iid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &gs_iid_provider) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = self;
        gs_prov_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall gs_prov_cls(gs_provider *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Gaming.XboxLive.Storage.GameSaveProvider");
    return S_OK;
}
static HRESULT __stdcall gs_prov_User(gs_provider *self, void **out)
{
    if (!out) return E_POINTER;
    *out = self->user;
    gs_unk_addref(self->user);
    return S_OK;
}
static HRESULT __stdcall gs_prov_CreateContainer(gs_provider *self, hstring_t name, void **out)
{
    const WCHAR *cname = gs_hstr_raw(name);
    WCHAR enc[260], dir[MAX_PATH * 2];
    gs_container *c;
    if (!out) return E_POINTER;
    *out = NULL;
    if (!cname || !cname[0]) return E_INVALIDARG;
    gs_encode_name(cname, enc, sizeof(enc) / sizeof(enc[0]));
    _snwprintf(dir, sizeof(dir) / sizeof(dir[0]), L"%ls\\%ls", self->root, enc);
    dir[sizeof(dir) / sizeof(dir[0]) - 1] = 0;
    c = (gs_container *)calloc(1, sizeof(gs_container));
    if (!c) return E_OUTOFMEMORY;
    c->vtbl = gs_cont_vtable;
    c->refs = 1;
    c->provider = self;
    gs_prov_AddRef(self);
    c->name = gs_wcsdup(cname);
    c->dir = gs_wcsdup(dir);
    gs_log("Provider::CreateContainer(%ls)", cname);
    *out = c;
    return S_OK;
}
static HRESULT __stdcall gs_prov_DeleteContainerAsync(gs_provider *self, hstring_t name, void **out)
{
    const WCHAR *cname = gs_hstr_raw(name);
    WCHAR enc[260], dir[MAX_PATH * 2];
    gs_result *res;
    if (!out) return E_POINTER;
    if (cname && cname[0]) {
        gs_encode_name(cname, enc, sizeof(enc) / sizeof(enc[0]));
        _snwprintf(dir, sizeof(dir) / sizeof(dir[0]), L"%ls\\%ls", self->root, enc);
        dir[sizeof(dir) / sizeof(dir[0]) - 1] = 0;
        gs_delete_tree(dir);
        gs_log("Provider::DeleteContainerAsync(%ls)", cname);
    }
    res = gs_res_new(&gs_iid_op_result, "Windows.Gaming.XboxLive.Storage.GameSaveOperationResult",
                     GS_OK, NULL);
    *out = gs_async_new_obj(&gs_iid_async_op_result,
                            "Windows.Foundation.IAsyncOperation`1<GameSaveOperationResult>", res);
    gs_res_Release(res);
    return *out ? S_OK : E_OUTOFMEMORY;
}
static HRESULT __stdcall gs_prov_CreateQueryWithName(gs_provider *self, hstring_t prefix,
                                                     void **out)
{
    gs_cquery *q;
    if (!out) return E_POINTER;
    q = (gs_cquery *)calloc(1, sizeof(gs_cquery));
    if (!q) return E_OUTOFMEMORY;
    q->vtbl = gs_cq_vtable;
    q->refs = 1;
    q->root = gs_wcsdup(self->root);
    q->prefix = gs_wcsdup(gs_hstr_raw(prefix));
    *out = q;
    return S_OK;
}
static HRESULT __stdcall gs_prov_CreateQuery(gs_provider *self, void **out)
{
    return gs_prov_CreateQueryWithName(self, NULL, out);
}
static HRESULT __stdcall gs_prov_RemainingQuota(gs_provider *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_async_new_i64(256LL * 1024 * 1024);
    return *out ? S_OK : E_OUTOFMEMORY;
}
static HRESULT __stdcall gs_prov_ChangedContainers(gs_provider *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_vec_create(&gs_iid_vecview_string, &gs_iid_iterable_string,
                         &gs_iid_iterator_string, 1);
    return *out ? S_OK : E_OUTOFMEMORY;
}

static const void *gs_prov_vtable[] = {
    (const void *)gs_prov_QI, (const void *)gs_prov_AddRef, (const void *)gs_prov_Release,
    (const void *)gs_get_iids, (const void *)gs_prov_cls, (const void *)gs_get_trust,
    (const void *)gs_prov_User, (const void *)gs_prov_CreateContainer,
    (const void *)gs_prov_DeleteContainerAsync, (const void *)gs_prov_CreateQuery,
    (const void *)gs_prov_CreateQueryWithName, (const void *)gs_prov_RemainingQuota,
    (const void *)gs_prov_ChangedContainers,
};

/* --------------------------------------------------------------- GameSaveProvider factory/statics */

typedef struct {
    const void **vtbl;
    LONG refs;
} gs_singleton;

static gs_singleton gs_provider_factory;
static gs_singleton gs_provider_statics;

static ULONG __stdcall gs_sing_AddRef(void *self)
{
    (void)self;
    return 1000;
}
static ULONG __stdcall gs_sing_Release(void *self)
{
    (void)self;
    return 1000;
}
static HRESULT __stdcall gs_sing_cls(void *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = gs_hstr_ascii("Windows.Gaming.XboxLive.Storage.GameSaveProvider");
    return S_OK;
}

static HRESULT gs_open_provider(void *user, hstring_t scid, int sync_on_demand, void **out)
{
    const WCHAR *sc = gs_hstr_raw(scid);
    WCHAR local_state[MAX_PATH], enc_sc[128], root[MAX_PATH * 2];
    gs_provider *prov;
    gs_result *res;

    if (!out) return E_POINTER;
    local_state[0] = 0;
    uwp_local_state_dir(local_state, sizeof(local_state) / sizeof(local_state[0]));
    gs_encode_name((sc && sc[0]) ? sc : L"default", enc_sc, sizeof(enc_sc) / sizeof(enc_sc[0]));
    _snwprintf(root, sizeof(root) / sizeof(root[0]), L"%ls\\GameSave\\%ls", local_state, enc_sc);
    root[sizeof(root) / sizeof(root[0]) - 1] = 0;
    gs_ensure_dir(root);

    prov = (gs_provider *)calloc(1, sizeof(gs_provider));
    if (!prov) return E_OUTOFMEMORY;
    prov->vtbl = gs_prov_vtable;
    prov->refs = 1;
    prov->user = user;
    gs_unk_addref(user);
    prov->root = gs_wcsdup(root);

    gs_log("GameSaveProvider::%s(scid=%ls) -> root=%ls",
           sync_on_demand ? "GetSyncOnDemandForUserAsync" : "GetForUserAsync",
           (sc && sc[0]) ? sc : L"<empty>", root);

    res = gs_res_new(&gs_iid_provider_get,
                     "Windows.Gaming.XboxLive.Storage.GameSaveProviderGetResult", GS_OK, prov);
    gs_prov_Release(prov);
    *out = gs_async_new_obj(&gs_iid_async_provider_get,
                            "Windows.Foundation.IAsyncOperation`1<GameSaveProviderGetResult>", res);
    gs_res_Release(res);
    return *out ? S_OK : E_OUTOFMEMORY;
}

static HRESULT __stdcall gs_stat_GetForUserAsync(void *self, void *user, hstring_t scid, void **out)
{
    (void)self;
    return gs_open_provider(user, scid, 0, out);
}

static HRESULT __stdcall gs_stat_GetSyncOnDemandForUserAsync(void *self, void *user, hstring_t scid,
                                                             void **out)
{
    (void)self;
    return gs_open_provider(user, scid, 1, out);
}

static HRESULT __stdcall gs_stat_QI(void *self, const GUID *iid, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (IsEqualIID(iid, &gs_iid_provider_statics) || IsEqualIID(iid, &gs_iid_inspectable) ||
        IsEqualIID(iid, &gs_iid_unknown) || IsEqualIID(iid, &gs_iid_agile)) {
        *out = &gs_provider_statics;
        return S_OK;
    }
    if (IsEqualIID(iid, &gs_iid_factory)) {
        *out = &gs_provider_factory;
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static const void *gs_stat_vtable[] = {
    (const void *)gs_stat_QI, (const void *)gs_sing_AddRef, (const void *)gs_sing_Release,
    (const void *)gs_get_iids, (const void *)gs_sing_cls, (const void *)gs_get_trust,
    (const void *)gs_stat_GetForUserAsync, (const void *)gs_stat_GetSyncOnDemandForUserAsync,
};

static HRESULT __stdcall gs_fact_QI(void *self, const GUID *iid, void **out)
{
    return gs_stat_QI(self, iid, out);
}
static HRESULT __stdcall gs_fact_Activate(void *self, void **out)
{
    (void)self;
    if (out) *out = NULL;
    return E_NOTIMPL;
}

static const void *gs_fact_vtable[] = {
    (const void *)gs_fact_QI, (const void *)gs_sing_AddRef, (const void *)gs_sing_Release,
    (const void *)gs_get_iids, (const void *)gs_sing_cls, (const void *)gs_get_trust,
    (const void *)gs_fact_Activate,
};

void uwp_gamesave_install(void);
void uwp_gamesave_install(void)
{
    gs_provider_factory.vtbl = gs_fact_vtable;
    gs_provider_factory.refs = 1000;
    gs_provider_statics.vtbl = gs_stat_vtable;
    gs_provider_statics.refs = 1000;
}

int uwp_gamesave_lookup(const WCHAR *class_name, void **factory);
int uwp_gamesave_lookup(const WCHAR *class_name, void **factory)
{
    if (!wcscmp(class_name, L"Windows.Gaming.XboxLive.Storage.GameSaveProvider")) {
        *factory = &gs_provider_factory;
        return 1;
    }
    return 0;
}
