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
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

typedef void *hstring_t;

extern int uwp_local_state_dir(WCHAR *out, size_t cap);
extern int uwp_package_family_name(char *out, size_t cap);
extern int uwp_auth_get_cached_xuid(char *out, size_t cap);
extern int uwp_auth_get_xbl_session(int force_refresh, char *auth_out, size_t auth_cap,
                                    char *xuid_out, size_t xuid_cap);
extern int uwp_auth_xbl_request(const char *method, const WCHAR *host, const char *path_and_query,
                                const char *auth_header, const char *extra_headers,
                                const void *body, DWORD body_len,
                                DWORD *status_out, BYTE **resp_out, DWORD *resp_len_out);
extern int uwp_auth_direct_request(const char *method, const char *url, const char *extra_headers,
                                   const void *body, DWORD body_len,
                                   DWORD *status_out, BYTE **resp_out, DWORD *resp_len_out);

/* --------------------------------------------------------------- logging */

static void gs_log(const char *fmt, ...)
{
    static int enabled = -1;
    char buf[384];
    char line[420];
    va_list ap;
    HANDLE file;
    DWORD written = 0;
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
    file = CreateFileA("C:\\uwp-host-trace.log", FILE_APPEND_DATA,
                       FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS,
                       FILE_ATTRIBUTE_NORMAL, NULL);
    if (file != INVALID_HANDLE_VALUE) {
        WriteFile(file, line, (DWORD)strlen(line), &written, NULL);
        WriteFile(file, "\r\n", 2, &written, NULL);
        CloseHandle(file);
    }
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
static const GUID gs_iid_iterable_kvp_str_obj = {0xfe2f3d47, 0x5d47, 0x5499,
                                                 {0x83, 0x74, 0x43, 0x0c, 0x7c, 0xda, 0x02, 0x04}};

/* GameSaveErrorStatus values */
#define GS_OK                      0
#define GS_ABORT                   (-2138898432)
#define GS_INVALID_CONTAINER_NAME  (-2138898431)
#define GS_OUT_OF_LOCAL_STORAGE    (-2138898426)
#define GS_BUFFER_TOO_SMALL        (-2138898425)
#define GS_BLOB_NOT_FOUND          (-2138898424)
#define GS_E_BOUNDS                ((HRESULT)0x8000000BL)

/* --------------------------------------------------------------- filesystem helpers */

static int gs_ensure_dir(const WCHAR *path)
{
    WCHAR buf[MAX_PATH * 2];
    size_t i, len;
    DWORD attrs;
    if (!path || !path[0]) return 0;
    len = wcslen(path);
    if (len >= sizeof(buf) / sizeof(buf[0])) return 0;
    memcpy(buf, path, (len + 1) * sizeof(WCHAR));
    for (i = 0; i < len; i++) {
        if ((buf[i] == L'\\' || buf[i] == L'/') && i > 2) {
            WCHAR saved = buf[i];
            buf[i] = 0;
            if (!CreateDirectoryW(buf, NULL) && GetLastError() != ERROR_ALREADY_EXISTS)
                return 0;
            buf[i] = saved;
        }
    }
    if (!CreateDirectoryW(buf, NULL) && GetLastError() != ERROR_ALREADY_EXISTS)
        return 0;
    attrs = GetFileAttributesW(buf);
    return attrs != INVALID_FILE_ATTRIBUTES && (attrs & FILE_ATTRIBUTE_DIRECTORY) != 0;
}

static int gs_has_suffix(const WCHAR *str, const WCHAR *suffix)
{
    size_t slen, suflen;
    if (!str || !suffix) return 0;
    slen = wcslen(str);
    suflen = wcslen(suffix);
    return slen >= suflen && wcscmp(str + slen - suflen, suffix) == 0;
}

/* '~' is never emitted by gs_encode_name, so '~tmp', '~stage', and '~old'
 * cannot collide with any real encoded blob or container name. */
#define GS_TMP_SUFFIX   L"~tmp"
#define GS_STAGE_SUFFIX L"~stage"
#define GS_OLD_SUFFIX   L"~old"

static int gs_encode_name(const WCHAR *src, WCHAR *dst, size_t cap)
{
    static const WCHAR hex[] = L"0123456789ABCDEF";
    size_t o = 0;
    size_t i;
    if (!src || !cap) {
        if (cap) dst[0] = 0;
        return 0;
    }
    for (i = 0; src[i]; i++) {
        WCHAR c = src[i];
        int safe = ((c >= L'A' && c <= L'Z') || (c >= L'a' && c <= L'z') ||
                    (c >= L'0' && c <= L'9') || c == L'_' || c == L'-' ||
                    (c == L'.' && i > 0));
        if (safe) {
            if (o + 1 >= cap) { dst[0] = 0; return 0; }
            dst[o++] = c;
        } else if (c < 0x100) {
            if (o + 3 >= cap) { dst[0] = 0; return 0; }
            dst[o++] = L'%';
            dst[o++] = hex[(c >> 4) & 0xf];
            dst[o++] = hex[c & 0xf];
        } else {
            if (o + 6 >= cap) { dst[0] = 0; return 0; }
            dst[o++] = L'%';
            dst[o++] = L'u';
            dst[o++] = hex[(c >> 12) & 0xf];
            dst[o++] = hex[(c >> 8) & 0xf];
            dst[o++] = hex[(c >> 4) & 0xf];
            dst[o++] = hex[c & 0xf];
        }
    }
    dst[o] = 0;
    return o > 0;
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
    (void)pat;
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
    int n = _snwprintf(tmp, sizeof(tmp) / sizeof(tmp[0]), L"%ls%ls", path, GS_TMP_SUFFIX);
    if (n < 0 || (size_t)n >= sizeof(tmp) / sizeof(tmp[0])) return 0;
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
    LONG status; /* 0 = Started, 1 = Completed */
    HANDLE done_ev;
    CRITICAL_SECTION lock;
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
        if (self->done_ev) {
            CloseHandle(self->done_ev);
            DeleteCriticalSection(&self->lock);
        }
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
    void *old = NULL;
    void *invoke = NULL;

    if (self->done_ev) {
        EnterCriticalSection(&self->lock);
        old = self->handler;
        self->handler = handler;
        gs_unk_addref(handler);
        if (handler && self->status == 1 && InterlockedExchange(&self->fired, 1) == 0) {
            invoke = handler;
            gs_unk_addref(invoke);
        }
        LeaveCriticalSection(&self->lock);
        gs_unk_release(old);
    } else {
        old = self->handler;
        self->handler = handler;
        gs_unk_addref(handler);
        gs_unk_release(old);
        if (handler && InterlockedExchange(&self->fired, 1) == 0) {
            invoke = handler;
            gs_unk_addref(invoke);
        }
    }
    if (invoke) {
        gs_async_AddRef(self);
        ((HRESULT(__stdcall *)(void *, void *, INT32))(*(void ***)invoke)[3])(invoke, self, 1);
        gs_async_Release(self);
        gs_unk_release(invoke);
    }
    return S_OK;
}

static HRESULT __stdcall gs_async_get_Completed(gs_async *self, void **out)
{
    if (!out) return E_POINTER;
    if (self->done_ev) {
        EnterCriticalSection(&self->lock);
        *out = self->handler;
        gs_unk_addref(*out);
        LeaveCriticalSection(&self->lock);
    } else {
        *out = self->handler;
        gs_unk_addref(self->handler);
    }
    return S_OK;
}

static HRESULT __stdcall gs_async_GetResults(gs_async *self, void *out)
{
    if (!out) return E_POINTER;
    if (self->done_ev && InterlockedCompareExchange(&self->status, 0, 0) == 0)
        WaitForSingleObject(self->done_ev, INFINITE);
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
    gs_async *a = gs_async_from_info(iface);
    if (!out) return E_POINTER;
    *out = a->done_ev ? (INT32)InterlockedCompareExchange(&a->status, 0, 0) : 1;
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
    a->status = 1;
    a->obj_val = obj;
    gs_unk_addref(obj);
    return a;
}

static gs_async *gs_async_new_pending(const GUID *op_iid, const char *name)
{
    gs_async *a = (gs_async *)calloc(1, sizeof(gs_async));
    if (!a) return NULL;
    a->done_ev = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!a->done_ev) {
        free(a);
        return NULL;
    }
    InitializeCriticalSection(&a->lock);
    a->vtbl = gs_async_vtable;
    a->info_vtbl = gs_ainfo_vtable;
    a->refs = 1;
    a->id = (UINT32)InterlockedIncrement(&gs_next_async_id);
    a->op_iid = *op_iid;
    a->class_name = name;
    a->kind = GS_ASYNC_OBJ;
    a->status = 0;
    return a;
}

static void gs_async_complete_obj(gs_async *a, void *obj)
{
    void *invoke = NULL;
    EnterCriticalSection(&a->lock);
    a->obj_val = obj;
    gs_unk_addref(obj);
    InterlockedExchange(&a->status, 1);
    if (a->handler && InterlockedExchange(&a->fired, 1) == 0) {
        invoke = a->handler;
        gs_unk_addref(invoke);
    }
    SetEvent(a->done_ev);
    LeaveCriticalSection(&a->lock);
    if (invoke) {
        gs_async_AddRef(a);
        ((HRESULT(__stdcall *)(void *, void *, INT32))(*(void ***)invoke)[3])(invoke, a, 1);
        gs_async_Release(a);
        gs_unk_release(invoke);
    }
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
            if (gs_has_suffix(fd.cFileName, GS_TMP_SUFFIX)) continue;
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
            if (gs_has_suffix(fd.cFileName, GS_TMP_SUFFIX)) continue;
            total += ((UINT64)fd.nFileSizeHigh << 32) | fd.nFileSizeLow;
            INT64 ft = ((INT64)fd.ftLastWriteTime.dwHighDateTime << 32) |
                       fd.ftLastWriteTime.dwLowDateTime;
            if (ft > *latest_ft) *latest_ft = ft;
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }
    return total;
}

static void gs_recover_container(const WCHAR *dir)
{
    WCHAR stage[MAX_PATH * 2], old_dir[MAX_PATH * 2];
    int ns = _snwprintf(stage, sizeof(stage) / sizeof(stage[0]), L"%ls%ls", dir, GS_STAGE_SUFFIX);
    int no = _snwprintf(old_dir, sizeof(old_dir) / sizeof(old_dir[0]), L"%ls%ls", dir, GS_OLD_SUFFIX);
    if (ns > 0 && (size_t)ns < sizeof(stage) / sizeof(stage[0]) &&
        GetFileAttributesW(stage) != INVALID_FILE_ATTRIBUTES) {
        gs_delete_tree(stage);
    }
    if (no > 0 && (size_t)no < sizeof(old_dir) / sizeof(old_dir[0]) &&
        GetFileAttributesW(old_dir) != INVALID_FILE_ATTRIBUTES) {
        if (GetFileAttributesW(dir) == INVALID_FILE_ATTRIBUTES)
            MoveFileW(old_dir, dir);
        else
            gs_delete_tree(old_dir);
    }
}

static void gs_recover_root(const WCHAR *root)
{
    WCHAR pat[MAX_PATH * 2];
    WIN32_FIND_DATAW fd;
    HANDLE h;
    int n = _snwprintf(pat, sizeof(pat) / sizeof(pat[0]), L"%ls\\*", root);
    if (n < 0 || (size_t)n >= sizeof(pat) / sizeof(pat[0])) return;
    h = FindFirstFileW(pat, &fd);
    if (h == INVALID_HANDLE_VALUE) return;
    do {
        WCHAR sub[MAX_PATH * 2];
        if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
        if (!wcscmp(fd.cFileName, L".") || !wcscmp(fd.cFileName, L"..")) continue;
        if (gs_has_suffix(fd.cFileName, GS_STAGE_SUFFIX)) {
            int k = _snwprintf(sub, sizeof(sub) / sizeof(sub[0]), L"%ls\\%ls", root, fd.cFileName);
            if (k > 0 && (size_t)k < sizeof(sub) / sizeof(sub[0]))
                gs_delete_tree(sub);
        } else if (gs_has_suffix(fd.cFileName, GS_OLD_SUFFIX)) {
            size_t base_len = wcslen(fd.cFileName) - wcslen(GS_OLD_SUFFIX);
            WCHAR base[MAX_PATH * 2];
            if (base_len > 0 && base_len < MAX_PATH) {
                int k1 = _snwprintf(sub, sizeof(sub) / sizeof(sub[0]), L"%ls\\%ls", root, fd.cFileName);
                int k2 = _snwprintf(base, sizeof(base) / sizeof(base[0]), L"%ls\\%.*ls", root,
                                    (int)base_len, fd.cFileName);
                if (k1 > 0 && (size_t)k1 < sizeof(sub) / sizeof(sub[0]) &&
                    k2 > 0 && (size_t)k2 < sizeof(base) / sizeof(base[0])) {
                    if (GetFileAttributesW(base) == INVALID_FILE_ATTRIBUTES)
                        MoveFileW(sub, base);
                    else
                        gs_delete_tree(sub);
                }
            }
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
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
    gs_recover_root(root);
    _snwprintf(pat, sizeof(pat) / sizeof(pat[0]), L"%ls\\*", root);
    pat[sizeof(pat) / sizeof(pat[0]) - 1] = 0;
    h = FindFirstFileW(pat, &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            WCHAR decoded[260], sub[MAX_PATH * 2], dn_path[MAX_PATH * 2];
            if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
            if (!wcscmp(fd.cFileName, L".") || !wcscmp(fd.cFileName, L"..")) continue;
            if (fd.cFileName[0] == L'.') continue;
            if (gs_has_suffix(fd.cFileName, GS_STAGE_SUFFIX) ||
                gs_has_suffix(fd.cFileName, GS_OLD_SUFFIX))
                continue;
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

/* --------------------------------------------------------------- Connected Storage cloud sync */

enum gs_sync_mode {
    GS_SYNC_AUTO = 0,
    GS_SYNC_NONE = 1,
    GS_SYNC_DOWNLOAD = 2,
    GS_SYNC_UPLOAD = 3,
};

#define GS_LARGE_BLOB_SIZE (4u * 1024u * 1024u)
#define GS_CLOUD_META_FILE L".cloud"

typedef struct {
    char *name; /* UTF-8 blob name */
    char atom[64];
} gs_cloud_atom;

typedef struct {
    char remote[64];
    int dirty;
    gs_cloud_atom *atoms;
    size_t count;
    size_t cap;
} gs_cloud_meta;

typedef struct gs_upload_item {
    struct gs_upload_item *next;
    WCHAR *name;
    WCHAR *dir;
} gs_upload_item;

typedef struct gs_provider gs_provider;

/* Forward declarations for provider + container metadata */
static void gs_cloud_meta_free(gs_cloud_meta *m);
static int gs_cloud_meta_load(const WCHAR *dir, gs_cloud_meta *m);
static int gs_cloud_meta_save(const WCHAR *dir, const gs_cloud_meta *m);
static void gs_cloud_meta_clear_atom(gs_cloud_meta *m, const char *blob_name);
static void gs_cloud_queue_upload(gs_provider *prov, const WCHAR *name, const WCHAR *dir);
static int gs_cloud_delete_container(gs_provider *prov, const WCHAR *name);

static enum gs_sync_mode gs_get_sync_mode(void)
{
    char val[32];
    DWORD n = GetEnvironmentVariableA("DROIDDECK_UWP_SAVE_SYNC", val, sizeof val);
    if (n == 0 || n >= sizeof val)
        n = GetEnvironmentVariableA("XODUS_SAVE_SYNC", val, sizeof val);
    if (n == 0 || n >= sizeof val) return GS_SYNC_AUTO;
    if (_stricmp(val, "none") == 0 || _stricmp(val, "off") == 0 || strcmp(val, "0") == 0)
        return GS_SYNC_NONE;
    if (_stricmp(val, "download") == 0) return GS_SYNC_DOWNLOAD;
    if (_stricmp(val, "upload") == 0) return GS_SYNC_UPLOAD;
    return GS_SYNC_AUTO;
}

static char *gs_w2u8(const WCHAR *w)
{
    int n;
    char *out;
    if (!w) w = L"";
    n = WideCharToMultiByte(CP_UTF8, 0, w, -1, NULL, 0, NULL, NULL);
    if (n <= 0) return NULL;
    out = (char *)malloc((size_t)n);
    if (!out) return NULL;
    if (!WideCharToMultiByte(CP_UTF8, 0, w, -1, out, n, NULL, NULL)) {
        free(out);
        return NULL;
    }
    return out;
}

static WCHAR *gs_u82w(const char *s)
{
    int n;
    WCHAR *out;
    if (!s) s = "";
    n = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
    if (n <= 0) return NULL;
    out = (WCHAR *)malloc((size_t)n * sizeof(WCHAR));
    if (!out) return NULL;
    if (!MultiByteToWideChar(CP_UTF8, 0, s, -1, out, n)) {
        free(out);
        return NULL;
    }
    return out;
}

static char *gs_url_encode(const char *s)
{
    static const char hex[] = "0123456789ABCDEF";
    size_t len, i, o = 0;
    char *out;
    if (!s) s = "";
    len = strlen(s);
    out = (char *)malloc(len * 3 + 1);
    if (!out) return NULL;
    for (i = 0; i < len; i++) {
        unsigned char c = (unsigned char)s[i];
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
            c == '-' || c == '_' || c == '.' || c == '~') {
            out[o++] = (char)c;
        } else {
            out[o++] = '%';
            out[o++] = hex[c >> 4];
            out[o++] = hex[c & 0xf];
        }
    }
    out[o] = '\0';
    return out;
}

static char *gs_json_escape_str(const char *s)
{
    size_t len, i, o = 0;
    char *out;
    if (!s) s = "";
    len = strlen(s);
    out = (char *)malloc(len * 6 + 1);
    if (!out) return NULL;
    for (i = 0; i < len; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c == '"' || c == '\\') {
            out[o++] = '\\';
            out[o++] = (char)c;
        } else if (c == '\b') {
            out[o++] = '\\'; out[o++] = 'b';
        } else if (c == '\f') {
            out[o++] = '\\'; out[o++] = 'f';
        } else if (c == '\n') {
            out[o++] = '\\'; out[o++] = 'n';
        } else if (c == '\r') {
            out[o++] = '\\'; out[o++] = 'r';
        } else if (c == '\t') {
            out[o++] = '\\'; out[o++] = 't';
        } else if (c < 0x20) {
            o += (size_t)sprintf(out + o, "\\u%04x", (unsigned)c);
        } else {
            out[o++] = (char)c;
        }
    }
    out[o] = '\0';
    return out;
}

static void gs_b64_encode(const BYTE *in, size_t len, char *out, size_t cap)
{
    static const char tbl[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t i = 0, o = 0;
    while (i < len && o + 4 < cap) {
        uint32_t v = ((uint32_t)in[i]) << 16;
        if (i + 1 < len) v |= ((uint32_t)in[i + 1]) << 8;
        if (i + 2 < len) v |= (uint32_t)in[i + 2];
        out[o++] = tbl[(v >> 18) & 63];
        out[o++] = tbl[(v >> 12) & 63];
        out[o++] = (i + 1 < len) ? tbl[(v >> 6) & 63] : '=';
        out[o++] = (i + 2 < len) ? tbl[v & 63] : '=';
        i += 3;
    }
    if (cap) out[o < cap ? o : cap - 1] = '\0';
}

/* Minimal strict JSON scanner */
static const char *gs_jws(const char *p)
{
    while (p && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) p++;
    return p;
}

static int gs_hex4(const char *p, unsigned *out)
{
    unsigned v = 0;
    int i;
    for (i = 0; i < 4; i++) {
        char c = p[i];
        v <<= 4;
        if (c >= '0' && c <= '9') v |= (unsigned)(c - '0');
        else if (c >= 'a' && c <= 'f') v |= (unsigned)(c - 'a' + 10);
        else if (c >= 'A' && c <= 'F') v |= (unsigned)(c - 'A' + 10);
        else return 0;
    }
    *out = v;
    return 1;
}

static size_t gs_utf8_put(char *dst, unsigned cp)
{
    if (cp < 0x80) {
        dst[0] = (char)cp;
        return 1;
    } else if (cp < 0x800) {
        dst[0] = (char)(0xC0 | (cp >> 6));
        dst[1] = (char)(0x80 | (cp & 0x3F));
        return 2;
    } else if (cp < 0x10000) {
        dst[0] = (char)(0xE0 | (cp >> 12));
        dst[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
        dst[2] = (char)(0x80 | (cp & 0x3F));
        return 3;
    } else {
        dst[0] = (char)(0xF0 | (cp >> 18));
        dst[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
        dst[2] = (char)(0x80 | ((cp >> 6) & 0x3F));
        dst[3] = (char)(0x80 | (cp & 0x3F));
        return 4;
    }
}

static const char *gs_jstr(const char *p, char **out)
{
    const char *start;
    char *buf;
    size_t cap, o = 0;

    if (out) *out = NULL;
    if (!p || *p != '"') return NULL;
    p++;
    start = p;
    while (*p && *p != '"') {
        if (*p == '\\') {
            p++;
            if (!*p) return NULL;
        }
        p++;
    }
    if (*p != '"') return NULL;
    if (!out) return p + 1;

    cap = (size_t)(p - start) + 8;
    buf = (char *)malloc(cap);
    if (!buf) return NULL;
    p = start;
    while (*p && *p != '"') {
        if (*p == '\\') {
            p++;
            switch (*p) {
            case '"': case '\\': case '/': buf[o++] = *p++; break;
            case 'b': buf[o++] = '\b'; p++; break;
            case 'f': buf[o++] = '\f'; p++; break;
            case 'n': buf[o++] = '\n'; p++; break;
            case 'r': buf[o++] = '\r'; p++; break;
            case 't': buf[o++] = '\t'; p++; break;
            case 'u': {
                unsigned cp = 0;
                p++;
                if (!gs_hex4(p, &cp)) { free(buf); return NULL; }
                p += 4;
                if (cp >= 0xD800 && cp <= 0xDBFF && p[0] == '\\' && p[1] == 'u') {
                    unsigned lo = 0;
                    if (gs_hex4(p + 2, &lo) && lo >= 0xDC00 && lo <= 0xDFFF) {
                        cp = 0x10000 + (((cp - 0xD800) << 10) | (lo - 0xDC00));
                        p += 6;
                    }
                }
                o += gs_utf8_put(buf + o, cp);
                break;
            }
            default:
                buf[o++] = *p++;
                break;
            }
        } else {
            buf[o++] = *p++;
        }
    }
    buf[o] = '\0';
    *out = buf;
    return p + 1;
}

static const char *gs_jskip(const char *p)
{
    int depth = 0;
    p = gs_jws(p);
    if (!p || !*p) return NULL;
    if (*p == '"') return gs_jstr(p, NULL);
    if (*p == '{' || *p == '[') {
        for (;;) {
            if (*p == '{' || *p == '[') depth++;
            else if (*p == '}' || *p == ']') {
                depth--;
                if (depth == 0) return p + 1;
            } else if (*p == '"') {
                p = gs_jstr(p, NULL);
                if (!p) return NULL;
                continue;
            } else if (!*p) {
                return NULL;
            }
            p++;
        }
    }
    while (*p && *p != ',' && *p != '}' && *p != ']' && *p != ' ' && *p != '\t' && *p != '\r' && *p != '\n')
        p++;
    return p;
}

/* Iterate top-level key/value pairs inside a JSON object starting at '{'.
 * Advance *cursor past '{' on first call; returns 1 on entry, 0 on '}' or error. */
static int gs_jobj_next(const char **cursor, char **key_out, const char **val_out)
{
    const char *p = gs_jws(*cursor);
    const char *val, *after;
    char *k = NULL;

    if (!p) return 0;
    if (*p == '{') p = gs_jws(p + 1);
    else if (*p == ',') p = gs_jws(p + 1);
    if (*p == '}' || *p != '"') {
        *cursor = p;
        return 0;
    }
    p = gs_jstr(p, &k);
    if (!p) return 0;
    p = gs_jws(p);
    if (*p != ':') {
        free(k);
        return 0;
    }
    val = gs_jws(p + 1);
    after = gs_jskip(val);
    if (!after) {
        free(k);
        return 0;
    }
    *key_out = k;
    *val_out = val;
    *cursor = after;
    return 1;
}

/* Find a direct child property of the JSON object starting at obj ('{'). */
static const char *gs_jfind(const char *obj, const char *wanted)
{
    const char *cur = obj;
    char *k = NULL;
    const char *v = NULL;
    while (gs_jobj_next(&cur, &k, &v)) {
        int match = (strcmp(k, wanted) == 0);
        free(k);
        if (match) return v;
    }
    return NULL;
}

static char *gs_jfind_str(const char *obj, const char *wanted)
{
    const char *v = gs_jfind(obj, wanted);
    char *s = NULL;
    if (!v || *v != '"') return NULL;
    gs_jstr(v, &s);
    return s;
}

static INT64 gs_iso_to_filetime(const char *iso)
{
    SYSTEMTIME st;
    FILETIME ft;
    int y = 0, mo = 0, d = 0, h = 0, mi = 0, s = 0;
    if (!iso || !iso[0]) return 0;
    if (sscanf(iso, "%4d-%2d-%2dT%2d:%2d:%2d", &y, &mo, &d, &h, &mi, &s) < 6)
        return 0;
    memset(&st, 0, sizeof st);
    st.wYear = (WORD)y;
    st.wMonth = (WORD)mo;
    st.wDay = (WORD)d;
    st.wHour = (WORD)h;
    st.wMinute = (WORD)mi;
    st.wSecond = (WORD)s;
    if (!SystemTimeToFileTime(&st, &ft)) return 0;
    return ((INT64)ft.dwHighDateTime << 32) | ft.dwLowDateTime;
}

static void gs_filetime_to_iso(INT64 ft64, char out[48])
{
    FILETIME ft;
    SYSTEMTIME st;
    if (ft64 <= 0) {
        GetSystemTimeAsFileTime(&ft);
    } else {
        ft.dwLowDateTime = (DWORD)(ft64 & 0xFFFFFFFFULL);
        ft.dwHighDateTime = (DWORD)((UINT64)ft64 >> 32);
    }
    if (!FileTimeToSystemTime(&ft, &st)) GetSystemTime(&st);
    snprintf(out, 48, "%04u-%02u-%02uT%02u:%02u:%02u.%03uZ",
             (unsigned)st.wYear, (unsigned)st.wMonth, (unsigned)st.wDay,
             (unsigned)st.wHour, (unsigned)st.wMinute, (unsigned)st.wSecond,
             (unsigned)st.wMilliseconds);
}

static void gs_set_file_time(const WCHAR *path, INT64 ft64)
{
    HANDLE f;
    FILETIME ft;
    if (ft64 <= 0) return;
    ft.dwLowDateTime = (DWORD)(ft64 & 0xFFFFFFFFULL);
    ft.dwHighDateTime = (DWORD)((UINT64)ft64 >> 32);
    f = CreateFileW(path, FILE_WRITE_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE,
                    NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f != INVALID_HANDLE_VALUE) {
        SetFileTime(f, NULL, NULL, &ft);
        CloseHandle(f);
    }
}

/* .cloud metadata persistence */
static void gs_cloud_meta_free(gs_cloud_meta *m)
{
    size_t i;
    if (!m) return;
    for (i = 0; i < m->count; i++) free(m->atoms[i].name);
    free(m->atoms);
    memset(m, 0, sizeof *m);
}

static const char *gs_cloud_meta_get_atom(const gs_cloud_meta *m, const char *blob_name)
{
    size_t i;
    for (i = 0; i < m->count; i++) {
        if (strcmp(m->atoms[i].name, blob_name) == 0)
            return m->atoms[i].atom[0] ? m->atoms[i].atom : NULL;
    }
    return NULL;
}

static int gs_cloud_meta_set_atom(gs_cloud_meta *m, const char *blob_name, const char *atom)
{
    size_t i;
    for (i = 0; i < m->count; i++) {
        if (strcmp(m->atoms[i].name, blob_name) == 0) {
            snprintf(m->atoms[i].atom, sizeof m->atoms[i].atom, "%s", atom ? atom : "");
            return 1;
        }
    }
    if (m->count == m->cap) {
        size_t ncap = m->cap ? m->cap * 2 : 8;
        gs_cloud_atom *na = (gs_cloud_atom *)realloc(m->atoms, ncap * sizeof *na);
        if (!na) return 0;
        m->atoms = na;
        m->cap = ncap;
    }
    m->atoms[m->count].name = strdup(blob_name);
    if (!m->atoms[m->count].name) return 0;
    snprintf(m->atoms[m->count].atom, sizeof m->atoms[m->count].atom, "%s", atom ? atom : "");
    m->count++;
    return 1;
}

static void gs_cloud_meta_clear_atom(gs_cloud_meta *m, const char *blob_name)
{
    size_t i;
    if (!m || !blob_name) return;
    for (i = 0; i < m->count; i++) {
        if (strcmp(m->atoms[i].name, blob_name) == 0) {
            m->atoms[i].atom[0] = '\0';
            return;
        }
    }
}

static int gs_cloud_meta_load(const WCHAR *dir, gs_cloud_meta *m)
{
    WCHAR path[MAX_PATH * 2];
    DWORD len = 0;
    BYTE *raw;
    char *text, *line, *nl;
    int n;

    memset(m, 0, sizeof *m);
    n = _snwprintf(path, sizeof(path) / sizeof(path[0]), L"%ls\\%ls", dir, GS_CLOUD_META_FILE);
    if (n < 0 || (size_t)n >= sizeof(path) / sizeof(path[0])) return 0;
    raw = gs_read_file_all(path, &len);
    if (!raw) return 0;
    text = (char *)malloc((size_t)len + 1);
    if (!text) { free(raw); return 0; }
    memcpy(text, raw, len);
    text[len] = '\0';
    free(raw);

    line = text;
    while (*line) {
        nl = strchr(line, '\n');
        if (nl) *nl = '\0';
        {
            size_t l = strlen(line);
            while (l > 0 && (line[l - 1] == '\r' || line[l - 1] == '\n')) line[--l] = '\0';
        }
        if (strncmp(line, "remote=", 7) == 0) {
            snprintf(m->remote, sizeof m->remote, "%s", line + 7);
        } else if (strncmp(line, "dirty=", 6) == 0) {
            m->dirty = (line[6] == '1') ? 1 : 0;
        } else if (strncmp(line, "atom:", 5) == 0) {
            char *eq = strrchr(line + 5, '=');
            if (eq && eq > line + 5) {
                *eq = '\0';
                if (eq[1]) gs_cloud_meta_set_atom(m, line + 5, eq + 1);
            }
        }
        if (!nl) break;
        line = nl + 1;
    }
    free(text);
    return 1;
}

static int gs_cloud_meta_save(const WCHAR *dir, const gs_cloud_meta *m)
{
    WCHAR path[MAX_PATH * 2];
    size_t cap = 256, len = 0, i;
    char *buf;
    int n, ok;

    n = _snwprintf(path, sizeof(path) / sizeof(path[0]), L"%ls\\%ls", dir, GS_CLOUD_META_FILE);
    if (n < 0 || (size_t)n >= sizeof(path) / sizeof(path[0])) return 0;
    for (i = 0; i < m->count; i++)
        cap += strlen(m->atoms[i].name) + strlen(m->atoms[i].atom) + 16;
    buf = (char *)malloc(cap);
    if (!buf) return 0;
    len += (size_t)snprintf(buf + len, cap - len, "remote=%s\ndirty=%d\n", m->remote, m->dirty ? 1 : 0);
    for (i = 0; i < m->count; i++) {
        if (!m->atoms[i].atom[0]) continue;
        len += (size_t)snprintf(buf + len, cap - len, "atom:%s=%s\n", m->atoms[i].name, m->atoms[i].atom);
    }
    ok = gs_write_file_atomic(path, (const BYTE *)buf, (DWORD)len);
    free(buf);
    return ok;
}

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

static int gs_blob_path(const WCHAR *dir, const WCHAR *blob_name, WCHAR *out, size_t cap)
{
    WCHAR enc[260];
    int n;
    if (!dir || !blob_name || !out || !cap) return 0;
    if (!gs_encode_name(blob_name, enc, sizeof(enc) / sizeof(enc[0]))) return 0;
    n = _snwprintf(out, cap, L"%ls\\%ls", dir, enc);
    if (n < 0 || (size_t)n >= cap) {
        out[0] = 0;
        return 0;
    }
    return 1;
}

static int gs_copy_existing_files(const WCHAR *src_dir, const WCHAR *dst_dir)
{
    WCHAR pat[MAX_PATH * 2];
    WIN32_FIND_DATAW fd;
    HANDLE h;
    int n;
    if (GetFileAttributesW(src_dir) == INVALID_FILE_ATTRIBUTES) return 1;
    n = _snwprintf(pat, sizeof(pat) / sizeof(pat[0]), L"%ls\\*", src_dir);
    if (n < 0 || (size_t)n >= sizeof(pat) / sizeof(pat[0])) return 0;
    h = FindFirstFileW(pat, &fd);
    if (h == INVALID_HANDLE_VALUE) return 1;
    do {
        WCHAR sfile[MAX_PATH * 2], dfile[MAX_PATH * 2];
        int ns, nd;
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
        if (gs_has_suffix(fd.cFileName, GS_TMP_SUFFIX)) continue;
        ns = _snwprintf(sfile, sizeof(sfile) / sizeof(sfile[0]), L"%ls\\%ls", src_dir, fd.cFileName);
        nd = _snwprintf(dfile, sizeof(dfile) / sizeof(dfile[0]), L"%ls\\%ls", dst_dir, fd.cFileName);
        if (ns < 0 || (size_t)ns >= sizeof(sfile) / sizeof(sfile[0]) ||
            nd < 0 || (size_t)nd >= sizeof(dfile) / sizeof(dfile[0]) ||
            !CopyFileW(sfile, dfile, FALSE)) {
            FindClose(h);
            return 0;
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return 1;
}

static INT32 gs_write_ibuffer_blob(const WCHAR *dir, const WCHAR *bname, void *ibuf, int *wrote)
{
    void *bba = NULL;
    UINT32 blen = 0;
    BYTE *ptr = NULL;
    WCHAR bpath[MAX_PATH * 2];
    INT32 status = GS_ABORT;

    if (!bname || !bname[0] || !gs_blob_path(dir, bname, bpath, sizeof(bpath) / sizeof(bpath[0])))
        return GS_INVALID_CONTAINER_NAME;
    ((HRESULT(__stdcall *)(void *, UINT32 *))(*(void ***)ibuf)[7])(ibuf, &blen);
    if (SUCCEEDED(gs_unk_qi(ibuf, &gs_iid_ibuffer_bytes, &bba)) && bba) {
        if (SUCCEEDED(((HRESULT(__stdcall *)(void *, BYTE **))(*(void ***)bba)[3])(bba, &ptr))) {
            if (gs_write_file_atomic(bpath, ptr ? ptr : (const BYTE *)"", blen)) {
                (*wrote)++;
                status = GS_OK;
            } else {
                status = GS_OUT_OF_LOCAL_STORAGE;
            }
        }
        gs_unk_release(bba);
    }
    return status;
}

static HRESULT gs_submit_atomic(gs_container *self, void *blobs_to_write, void *props_to_write,
                                void *blobs_to_delete, hstring_t display_name, void **out)
{
    int wrote = 0, deleted = 0;
    INT32 status = GS_OK;
    gs_result *res;
    const WCHAR *dn = gs_hstr_raw(display_name);
    WCHAR stage_dir[MAX_PATH * 2], old_dir[MAX_PATH * 2];
    int ns, no, had_existing;

    if (!out) return E_POINTER;
    gs_recover_container(self->dir);

    ns = _snwprintf(stage_dir, sizeof(stage_dir) / sizeof(stage_dir[0]), L"%ls%ls", self->dir, GS_STAGE_SUFFIX);
    no = _snwprintf(old_dir, sizeof(old_dir) / sizeof(old_dir[0]), L"%ls%ls", self->dir, GS_OLD_SUFFIX);
    if (ns < 0 || (size_t)ns >= sizeof(stage_dir) / sizeof(stage_dir[0]) ||
        no < 0 || (size_t)no >= sizeof(old_dir) / sizeof(old_dir[0])) {
        status = GS_INVALID_CONTAINER_NAME;
        goto finish;
    }

    gs_cloud_meta cmeta;
    memset(&cmeta, 0, sizeof cmeta);

    gs_delete_tree(stage_dir);
    if (!gs_ensure_dir(stage_dir) || !gs_copy_existing_files(self->dir, stage_dir)) {
        status = GS_OUT_OF_LOCAL_STORAGE;
        goto cleanup;
    }
    gs_cloud_meta_load(stage_dir, &cmeta);
    cmeta.dirty = 1;

    if (dn && dn[0]) {
        WCHAR dn_path[MAX_PATH * 2];
        int ndn = _snwprintf(dn_path, sizeof(dn_path) / sizeof(dn_path[0]), L"%ls\\.displayname", stage_dir);
        if (ndn < 0 || (size_t)ndn >= sizeof(dn_path) / sizeof(dn_path[0])) {
            status = GS_INVALID_CONTAINER_NAME;
            goto cleanup;
        }
        if (!gs_write_file_atomic(dn_path, (const BYTE *)dn, (DWORD)(wcslen(dn) * sizeof(WCHAR)))) {
            status = GS_OUT_OF_LOCAL_STORAGE;
            goto cleanup;
        }
    }

    if (blobs_to_write && status == GS_OK) {
        void *iterable = NULL;
        if (SUCCEEDED(gs_unk_qi(blobs_to_write, &gs_iid_iterable_kvp_str_buf, &iterable)) && iterable) {
            void *iter = NULL;
            if (SUCCEEDED(((HRESULT(__stdcall *)(void *, void **))(*(void ***)iterable)[6])(iterable, &iter)) && iter) {
                BYTE has = 0;
                ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[7])(iter, &has);
                while (has && status == GS_OK) {
                    void *kvp = NULL;
                    if (SUCCEEDED(((HRESULT(__stdcall *)(void *, void **))(*(void ***)iter)[6])(iter, &kvp)) && kvp) {
                        hstring_t hkey = NULL;
                        void *ibuf = NULL;
                        ((HRESULT(__stdcall *)(void *, hstring_t *))(*(void ***)kvp)[6])(kvp, &hkey);
                        ((HRESULT(__stdcall *)(void *, void **))(*(void ***)kvp)[7])(kvp, &ibuf);
                        if (!hkey || !ibuf) {
                            status = GS_ABORT;
                        } else {
                            const WCHAR *bk = gs_hstr_raw(hkey);
                            status = gs_write_ibuffer_blob(stage_dir, bk, ibuf, &wrote);
                            if (status == GS_OK) {
                                char *bu8 = gs_w2u8(bk);
                                if (bu8) { gs_cloud_meta_clear_atom(&cmeta, bu8); free(bu8); }
                            }
                        }
                        gs_hstr_free(hkey);
                        gs_unk_release(ibuf);
                        gs_unk_release(kvp);
                    } else {
                        status = GS_ABORT;
                    }
                    has = 0;
                    ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[8])(iter, &has);
                }
                gs_unk_release(iter);
            } else {
                status = GS_ABORT;
            }
            gs_unk_release(iterable);
        } else {
            status = GS_ABORT;
        }
    }

    if (props_to_write && status == GS_OK) {
        void *iterable = NULL;
        if (SUCCEEDED(gs_unk_qi(props_to_write, &gs_iid_iterable_kvp_str_obj, &iterable)) && iterable) {
            void *iter = NULL;
            if (SUCCEEDED(((HRESULT(__stdcall *)(void *, void **))(*(void ***)iterable)[6])(iterable, &iter)) && iter) {
                BYTE has = 0;
                ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[7])(iter, &has);
                while (has && status == GS_OK) {
                    void *kvp = NULL;
                    if (SUCCEEDED(((HRESULT(__stdcall *)(void *, void **))(*(void ***)iter)[6])(iter, &kvp)) && kvp) {
                        hstring_t hkey = NULL;
                        void *val = NULL;
                        ((HRESULT(__stdcall *)(void *, hstring_t *))(*(void ***)kvp)[6])(kvp, &hkey);
                        ((HRESULT(__stdcall *)(void *, void **))(*(void ***)kvp)[7])(kvp, &val);
                        if (!hkey || !val) {
                            status = GS_ABORT;
                        } else {
                            void *ibuf = NULL;
                            if (SUCCEEDED(gs_unk_qi(val, &gs_iid_ibuffer, &ibuf)) && ibuf) {
                                const WCHAR *bk = gs_hstr_raw(hkey);
                                status = gs_write_ibuffer_blob(stage_dir, bk, ibuf, &wrote);
                                if (status == GS_OK) {
                                    char *bu8 = gs_w2u8(bk);
                                    if (bu8) { gs_cloud_meta_clear_atom(&cmeta, bu8); free(bu8); }
                                }
                                gs_unk_release(ibuf);
                            } else {
                                status = GS_ABORT;
                            }
                        }
                        gs_hstr_free(hkey);
                        gs_unk_release(val);
                        gs_unk_release(kvp);
                    } else {
                        status = GS_ABORT;
                    }
                    has = 0;
                    ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[8])(iter, &has);
                }
                gs_unk_release(iter);
            } else {
                status = GS_ABORT;
            }
            gs_unk_release(iterable);
        } else {
            status = GS_ABORT;
        }
    }

    if (blobs_to_delete && status == GS_OK) {
        void *iterable = NULL;
        if (SUCCEEDED(gs_unk_qi(blobs_to_delete, &gs_iid_iterable_string, &iterable)) && iterable) {
            void *iter = NULL;
            if (SUCCEEDED(((HRESULT(__stdcall *)(void *, void **))(*(void ***)iterable)[6])(iterable, &iter)) && iter) {
                BYTE has = 0;
                ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[7])(iter, &has);
                while (has && status == GS_OK) {
                    hstring_t hkey = NULL;
                    if (SUCCEEDED(((HRESULT(__stdcall *)(void *, hstring_t *))(*(void ***)iter)[6])(iter, &hkey)) && hkey) {
                        WCHAR bpath[MAX_PATH * 2];
                        if (!gs_blob_path(stage_dir, gs_hstr_raw(hkey), bpath, sizeof(bpath) / sizeof(bpath[0]))) {
                            status = GS_INVALID_CONTAINER_NAME;
                        } else {
                            if (DeleteFileW(bpath) || GetLastError() == ERROR_FILE_NOT_FOUND) {
                                char *bu8 = gs_w2u8(gs_hstr_raw(hkey));
                                if (bu8) { gs_cloud_meta_clear_atom(&cmeta, bu8); free(bu8); }
                                deleted++;
                            } else {
                                status = GS_ABORT;
                            }
                        }
                        gs_hstr_free(hkey);
                    }
                    has = 0;
                    ((HRESULT(__stdcall *)(void *, BYTE *))(*(void ***)iter)[8])(iter, &has);
                }
                gs_unk_release(iter);
            } else {
                status = GS_ABORT;
            }
            gs_unk_release(iterable);
        } else {
            status = GS_ABORT;
        }
    }

    if (status == GS_OK) {
        gs_cloud_meta_save(stage_dir, &cmeta);
        had_existing = (GetFileAttributesW(self->dir) != INVALID_FILE_ATTRIBUTES);
        if (had_existing) {
            gs_delete_tree(old_dir);
            if (!MoveFileW(self->dir, old_dir)) {
                status = GS_ABORT;
                goto cleanup;
            }
        }
        if (!MoveFileW(stage_dir, self->dir)) {
            if (had_existing) MoveFileW(old_dir, self->dir);
            status = GS_ABORT;
            goto cleanup;
        }
        if (had_existing) gs_delete_tree(old_dir);
        gs_cloud_queue_upload(self->provider, self->name, self->dir);
    }

cleanup:
    gs_cloud_meta_free(&cmeta);
    if (status != GS_OK) gs_delete_tree(stage_dir);

finish:
    gs_log("Container::SubmitUpdatesAsync(%ls) wrote=%d deleted=%d status=%d", self->name, wrote, deleted, status);
    res = gs_res_new(&gs_iid_op_result, "Windows.Gaming.XboxLive.Storage.GameSaveOperationResult",
                     status, NULL);
    *out = gs_async_new_obj(&gs_iid_async_op_result,
                            "Windows.Foundation.IAsyncOperation`1<GameSaveOperationResult>", res);
    gs_res_Release(res);
    return *out ? S_OK : E_OUTOFMEMORY;
}

static HRESULT __stdcall gs_cont_SubmitUpdatesAsync(gs_container *self, void *blobs_to_write,
                                                    void *blobs_to_delete, hstring_t display_name,
                                                    void **out)
{
    return gs_submit_atomic(self, blobs_to_write, NULL, blobs_to_delete, display_name, out);
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
                            if (!gs_blob_path(self->dir, gs_hstr_raw(hkey), bpath,
                                              sizeof(bpath) / sizeof(bpath[0]))) {
                                status = GS_BLOB_NOT_FOUND;
                                fdata = NULL;
                            } else {
                                fdata = gs_read_file_all(bpath, &flen);
                            }
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
                        if (!gs_blob_path(self->dir, bname, bpath, sizeof(bpath) / sizeof(bpath[0]))) {
                            fdata = NULL;
                        } else {
                            fdata = gs_read_file_all(bpath, &flen);
                        }
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
                if (gs_has_suffix(fd.cFileName, GS_TMP_SUFFIX)) continue;
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
    return gs_submit_atomic(self, NULL, props_to_write, blobs_to_delete, display_name, out);
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
    WCHAR local_state[MAX_PATH];
    WCHAR enc_sc[128];
    int root_scoped_xuid;
    char scid[96];
    char pfn[256];
    char xuid[64];
    char auth[8400];
    char base_path[512];
    enum gs_sync_mode sync_mode;
    int cloud_enabled;
    int locked;
    INT64 quota;
    CRITICAL_SECTION cloud_cs;
    CRITICAL_SECTION upload_cs;
    HANDLE upload_ev;
    HANDLE upload_thread;
    int upload_stopping;
    gs_upload_item *upload_head;
};

static void gs_cloud_shutdown(gs_provider *prov);

static ULONG __stdcall gs_prov_AddRef(gs_provider *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG __stdcall gs_prov_Release(gs_provider *self)
{
    LONG r = InterlockedDecrement(&self->refs);
    if (r <= 0) {
        gs_cloud_shutdown(self);
        DeleteCriticalSection(&self->cloud_cs);
        DeleteCriticalSection(&self->upload_cs);
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
    if (!gs_encode_name(cname, enc, sizeof(enc) / sizeof(enc[0]))) return E_INVALIDARG;
    {
        int n = _snwprintf(dir, sizeof(dir) / sizeof(dir[0]), L"%ls\\%ls", self->root, enc);
        if (n < 0 || (size_t)n >= sizeof(dir) / sizeof(dir[0])) return E_INVALIDARG;
    }
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
    INT32 status = GS_OK;
    if (cname && cname[0]) {
        int n;
        if (!gs_encode_name(cname, enc, sizeof(enc) / sizeof(enc[0])) ||
            (n = _snwprintf(dir, sizeof(dir) / sizeof(dir[0]), L"%ls\\%ls", self->root, enc)) < 0 ||
            (size_t)n >= sizeof(dir) / sizeof(dir[0])) {
            status = GS_INVALID_CONTAINER_NAME;
        } else {
            gs_recover_container(dir);
            gs_delete_tree(dir);
            gs_cloud_delete_container(self, cname);
            gs_log("Provider::DeleteContainerAsync(%ls)", cname);
        }
    }
    res = gs_res_new(&gs_iid_op_result, "Windows.Gaming.XboxLive.Storage.GameSaveOperationResult",
                     status, NULL);
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
    INT64 q;
    if (!out) return E_POINTER;
    q = (self && self->quota > 0) ? self->quota : (256LL * 1024 * 1024);
    *out = gs_async_new_i64(q);
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

/* --------------------------------------------------------------- Connected Storage HTTP & sync */

static int gs_cloud_req(gs_provider *prov, const char *method, const char *subpath,
                        const char *extra_headers, const void *body, DWORD body_len,
                        DWORD *status_out, BYTE **resp_out, DWORD *resp_len_out)
{
    char path[2048];
    char headers[1024];
    int n, retried = 0;

    *status_out = 0;
    *resp_out = NULL;
    *resp_len_out = 0;
    if (!prov || !prov->cloud_enabled || !prov->base_path[0] || !prov->pfn[0]) return 0;

    n = snprintf(path, sizeof path, "%s%s", prov->base_path, subpath ? subpath : "");
    if (n < 0 || (size_t)n >= sizeof path) return 0;

    n = snprintf(headers, sizeof headers, "x-xbl-contract-version: 107\r\nx-xbl-pfn: %s\r\n%s",
                 prov->pfn, extra_headers ? extra_headers : "");
    if (n < 0 || (size_t)n >= sizeof headers) return 0;

    for (;;) {
        DWORD st = 0, rlen = 0;
        BYTE *rbuf = NULL;
        if (!uwp_auth_xbl_request(method, L"titlestorage.xboxlive.com", path, prov->auth, headers,
                                  body, body_len, &st, &rbuf, &rlen)) {
            gs_log("cloud_req %s %s transport failed", method, subpath ? subpath : "");
            return 0;
        }
        if (st == 401 && !retried) {
            char new_auth[8400], new_xuid[64];
            free(rbuf);
            retried = 1;
            gs_log("cloud_req %s %s got HTTP 401; refreshing XSTS", method, subpath ? subpath : "");
            if (uwp_auth_get_xbl_session(1, new_auth, sizeof new_auth, new_xuid, sizeof new_xuid)) {
                snprintf(prov->auth, sizeof prov->auth, "%s", new_auth);
                continue;
            }
            return 0;
        }
        *status_out = st;
        *resp_out = rbuf;
        *resp_len_out = rlen;
        return 1;
    }
}

static int gs_cloud_lock(gs_provider *prov)
{
    DWORD st = 0, rlen = 0;
    BYTE *rbuf = NULL;
    if (prov->locked) return 1;
    if (!gs_cloud_req(prov, "PUT", "/lock?friendlyName=DroidDeck",
                      "x-xbl-lock-ver: 1\r\nContent-Type: application/json\r\n",
                      NULL, 0, &st, &rbuf, &rlen)) {
        return 0;
    }
    if (st == 200 || st == 201) {
        const char *q = rbuf ? gs_jfind((const char *)rbuf, "quotaBytes") : NULL;
        if (q) {
            INT64 v = _strtoi64(q, NULL, 10);
            if (v > 0) prov->quota = v;
        }
        prov->locked = 1;
        gs_log("cloud lock acquired (status=%lu quota=%lld)", (unsigned long)st, (long long)prov->quota);
    } else {
        gs_log("cloud lock failed status=%lu", (unsigned long)st);
    }
    free(rbuf);
    return prov->locked;
}

static void gs_cloud_unlock(gs_provider *prov, int uploaded)
{
    DWORD st = 0, rlen = 0;
    BYTE *rbuf = NULL;
    if (!prov->locked) return;
    if (gs_cloud_req(prov, "DELETE",
                     uploaded ? "/lock?newSavesUploaded=true" : "/lock?newSavesUploaded=false",
                     NULL, NULL, 0, &st, &rbuf, &rlen)) {
        gs_log("cloud unlock uploaded=%d status=%lu", uploaded, (unsigned long)st);
        if (st >= 200 && st < 300) prov->locked = 0;
    }
    free(rbuf);
}

static int gs_cloud_download_container(gs_provider *prov, const char *name_u8,
                                       const char *display_u8, const char *remote_time)
{
    char *enc_url = NULL;
    char subpath[1024];
    DWORD st = 0, rlen = 0;
    BYTE *rbuf = NULL;
    const char *atoms_obj, *cur;
    WCHAR *name_w = NULL, *disp_w = NULL;
    WCHAR enc_dir[260], target_dir[MAX_PATH * 2], stage_dir[MAX_PATH * 2], old_dir[MAX_PATH * 2];
    gs_cloud_meta existing_meta, new_meta;
    INT64 remote_ft;
    int ok = 0, blobs_dl = 0, blobs_reused = 0, had_existing = 0;

    memset(&existing_meta, 0, sizeof existing_meta);
    memset(&new_meta, 0, sizeof new_meta);

    name_w = gs_u82w(name_u8);
    if (!name_w || !gs_encode_name(name_w, enc_dir, sizeof(enc_dir) / sizeof(enc_dir[0])))
        goto done;
    if (_snwprintf(target_dir, sizeof(target_dir) / sizeof(target_dir[0]), L"%ls\\%ls", prov->root, enc_dir) < 0 ||
        _snwprintf(stage_dir, sizeof(stage_dir) / sizeof(stage_dir[0]), L"%ls%ls", target_dir, GS_STAGE_SUFFIX) < 0 ||
        _snwprintf(old_dir, sizeof(old_dir) / sizeof(old_dir[0]), L"%ls%ls", target_dir, GS_OLD_SUFFIX) < 0)
        goto done;

    enc_url = gs_url_encode(name_u8);
    if (!enc_url) goto done;
    if (snprintf(subpath, sizeof subpath, "/%s,savedgame", enc_url) >= (int)sizeof subpath)
        goto done;

    if (!gs_cloud_req(prov, "GET", subpath, NULL, NULL, 0, &st, &rbuf, &rlen) || st != 200 || !rbuf) {
        gs_log("cloud download container '%s' GET failed status=%lu", name_u8, (unsigned long)st);
        goto done;
    }

    atoms_obj = gs_jfind((const char *)rbuf, "atoms");
    if (!atoms_obj || *atoms_obj != '{') {
        gs_log("cloud download container '%s' missing atoms object", name_u8);
        goto done;
    }

    gs_recover_container(target_dir);
    gs_cloud_meta_load(target_dir, &existing_meta);
    gs_delete_tree(stage_dir);
    if (!gs_ensure_dir(stage_dir)) goto done;

    snprintf(new_meta.remote, sizeof new_meta.remote, "%s", remote_time ? remote_time : "");
    new_meta.dirty = 0;
    remote_ft = gs_iso_to_filetime(remote_time);

    cur = atoms_obj;
    {
        char *bname_u8 = NULL;
        const char *val = NULL;
        int parse_ok = 1;
        while (gs_jobj_next(&cur, &bname_u8, &val)) {
            char *atom = NULL;
            WCHAR *bname_w = NULL;
            WCHAR dst_blob[MAX_PATH * 2];
            const char *prev_atom;
            int copied = 0;

            if (*val == '"') gs_jstr(val, &atom);
            if (!atom || !atom[0] || !bname_u8[0] || strlen(atom) >= 64) {
                free(atom);
                free(bname_u8);
                parse_ok = 0;
                break;
            }
            bname_w = gs_u82w(bname_u8);
            if (!bname_w || !gs_blob_path(stage_dir, bname_w, dst_blob, sizeof(dst_blob) / sizeof(dst_blob[0]))) {
                free(bname_w);
                free(atom);
                free(bname_u8);
                parse_ok = 0;
                break;
            }

            prev_atom = gs_cloud_meta_get_atom(&existing_meta, bname_u8);
            if (prev_atom && strcmp(prev_atom, atom) == 0) {
                WCHAR src_blob[MAX_PATH * 2];
                if (gs_blob_path(target_dir, bname_w, src_blob, sizeof(src_blob) / sizeof(src_blob[0])) &&
                    CopyFileW(src_blob, dst_blob, FALSE)) {
                    copied = 1;
                    blobs_reused++;
                }
            }

            if (!copied) {
                char apath[128];
                DWORD ast = 0, alen = 0;
                BYTE *adata = NULL;
                snprintf(apath, sizeof apath, "/%s", atom);
                if (!gs_cloud_req(prov, "GET", apath, NULL, NULL, 0, &ast, &adata, &alen) ||
                    ast != 200 || !adata) {
                    gs_log("cloud download blob '%s' atom=%s failed status=%lu",
                           bname_u8, atom, (unsigned long)ast);
                    free(adata);
                    free(bname_w);
                    free(atom);
                    free(bname_u8);
                    parse_ok = 0;
                    break;
                }
                if (!gs_write_file_atomic(dst_blob, adata, alen)) {
                    free(adata);
                    free(bname_w);
                    free(atom);
                    free(bname_u8);
                    parse_ok = 0;
                    break;
                }
                free(adata);
                blobs_dl++;
            }

            if (remote_ft > 0) gs_set_file_time(dst_blob, remote_ft);
            gs_cloud_meta_set_atom(&new_meta, bname_u8, atom);
            free(bname_w);
            free(atom);
            free(bname_u8);
        }
        cur = gs_jws(cur);
        if (*cur == '{') cur = gs_jws(cur + 1);
        if (!parse_ok || *cur != '}') {
            gs_log("cloud download container '%s' incomplete/corrupt atoms JSON", name_u8);
            goto done;
        }
    }

    if (display_u8 && display_u8[0]) {
        disp_w = gs_u82w(display_u8);
        if (disp_w && disp_w[0]) {
            WCHAR dn_path[MAX_PATH * 2];
            if (_snwprintf(dn_path, sizeof(dn_path) / sizeof(dn_path[0]), L"%ls\\.displayname", stage_dir) > 0)
                gs_write_file_atomic(dn_path, (const BYTE *)disp_w, (DWORD)(wcslen(disp_w) * sizeof(WCHAR)));
        }
    }

    if (!gs_cloud_meta_save(stage_dir, &new_meta)) goto done;

    had_existing = (GetFileAttributesW(target_dir) != INVALID_FILE_ATTRIBUTES);
    if (had_existing) {
        gs_delete_tree(old_dir);
        if (!MoveFileW(target_dir, old_dir)) goto done;
    }
    if (!MoveFileW(stage_dir, target_dir)) {
        if (had_existing) MoveFileW(old_dir, target_dir);
        goto done;
    }
    if (had_existing) gs_delete_tree(old_dir);
    ok = 1;
    gs_log("cloud download container '%s' ok (downloaded=%d reused=%d time=%s)",
           name_u8, blobs_dl, blobs_reused, remote_time ? remote_time : "");

done:
    if (!ok) gs_delete_tree(stage_dir);
    gs_cloud_meta_free(&existing_meta);
    gs_cloud_meta_free(&new_meta);
    free(rbuf);
    free(enc_url);
    free(name_w);
    free(disp_w);
    return ok;
}

static int gs_cloud_upload_large_atom(gs_provider *prov, const char *atom_guid36,
                                      const BYTE *data, DWORD size)
{
    char path[128], body[64];
    DWORD st = 0, rlen = 0, blocks, i;
    BYTE *rbuf = NULL;
    char *blob_uri = NULL;
    char *commit = NULL, *cp;
    size_t commit_cap;
    int ok = 0;

    snprintf(path, sizeof path, "/atoms/%.36s", atom_guid36);
    snprintf(body, sizeof body, "{\"size\":%lu}", (unsigned long)size);
    if (!gs_cloud_req(prov, "POST", path,
                      "Content-Type: application/json\r\nAccept: application/json\r\n",
                      body, (DWORD)strlen(body), &st, &rbuf, &rlen)) {
        return 0;
    }
    if (st == 200 && rbuf) blob_uri = gs_jfind_str((const char *)rbuf, "blobUri");
    free(rbuf);
    if (!blob_uri || !blob_uri[0]) {
        gs_log("cloud large atom init failed status=%lu", (unsigned long)st);
        free(blob_uri);
        return 0;
    }

    blocks = (size + GS_LARGE_BLOB_SIZE - 1u) / GS_LARGE_BLOB_SIZE;
    commit_cap = 64 + (size_t)blocks * 24;
    commit = (char *)malloc(commit_cap);
    if (!commit) {
        free(blob_uri);
        return 0;
    }
    cp = commit + sprintf(commit, "{\"blockIds\":[");

    ok = 1;
    for (i = 0; i < blocks; i++) {
        char id[16], b64[32], *url, *q;
        DWORD chunk = size - i * GS_LARGE_BLOB_SIZE;
        DWORD bst = 0, brlen = 0;
        BYTE *brbuf = NULL;
        const char *c;
        if (chunk > GS_LARGE_BLOB_SIZE) chunk = GS_LARGE_BLOB_SIZE;

        snprintf(id, sizeof id, "%08lu", (unsigned long)i);
        gs_b64_encode((const BYTE *)id, 8, b64, sizeof b64);
        cp += sprintf(cp, "%s\"%s\"", i ? "," : "", b64);

        url = (char *)malloc(strlen(blob_uri) + 80);
        if (!url) { ok = 0; break; }
        q = url + sprintf(url, "%s&comp=block&blockid=", blob_uri);
        for (c = b64; *c; c++) {
            if (*c == '=') { memcpy(q, "%3D", 3); q += 3; }
            else *q++ = *c;
        }
        *q = '\0';

        if (!uwp_auth_direct_request("PUT", url, "x-ms-version: 2015-04-05\r\n",
                                     data + (size_t)i * GS_LARGE_BLOB_SIZE, chunk,
                                     &bst, &brbuf, &brlen) ||
            (bst != 201 && bst != 200)) {
            gs_log("cloud large atom block %lu/%lu PUT failed status=%lu",
                   (unsigned long)(i + 1), (unsigned long)blocks, (unsigned long)bst);
            ok = 0;
        }
        free(brbuf);
        free(url);
        if (!ok) break;
    }
    free(blob_uri);

    if (ok) {
        sprintf(cp, "],\"size\":%lu}", (unsigned long)size);
        snprintf(path, sizeof path, "/atoms/%.36s?commit=true", atom_guid36);
        rbuf = NULL; rlen = 0; st = 0;
        if (!gs_cloud_req(prov, "POST", path,
                          "Content-Type: application/json\r\nAccept: application/json\r\n",
                          commit, (DWORD)strlen(commit), &st, &rbuf, &rlen) ||
            st < 200 || st >= 300) {
            gs_log("cloud large atom commit failed status=%lu", (unsigned long)st);
            ok = 0;
        }
        free(rbuf);
    }
    free(commit);
    return ok;
}

static int gs_cloud_upload_container(gs_provider *prov, const WCHAR *name_w, const WCHAR *dir_w,
                                     int force_all_atoms)
{
    char *name_u8 = NULL, *disp_u8 = NULL, *enc_name = NULL, *enc_disp = NULL;
    gs_cloud_meta old_meta, new_meta;
    WCHAR pat[MAX_PATH * 2], dn_path[MAX_PATH * 2];
    WIN32_FIND_DATAW fd;
    HANDLE h = INVALID_HANDLE_VALUE;
    INT64 latest_ft = 0;
    char timestamp[48];
    char *json = NULL, *jp;
    size_t json_cap = 64, i;
    char subpath[2048];
    DWORD st = 0, rlen = 0;
    BYTE *rbuf = NULL;
    int ok = 0, uploaded_blobs = 0;

    if (!prov || !prov->cloud_enabled || prov->sync_mode == GS_SYNC_DOWNLOAD) return 0;

    memset(&old_meta, 0, sizeof old_meta);
    memset(&new_meta, 0, sizeof new_meta);
    gs_cloud_meta_load(dir_w, &old_meta);

    name_u8 = gs_w2u8(name_w);
    if (!name_u8) goto done;

    if (!gs_cloud_lock(prov)) goto done;

    _snwprintf(pat, sizeof(pat) / sizeof(pat[0]), L"%ls\\*", dir_w);
    pat[sizeof(pat) / sizeof(pat[0]) - 1] = 0;
    h = FindFirstFileW(pat, &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            WCHAR decoded[260], bpath[MAX_PATH * 2];
            char *bname_u8 = NULL;
            const char *existing_atom = NULL;
            char atom[64];
            INT64 ft;

            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
            if (fd.cFileName[0] == L'.') continue;
            if (gs_has_suffix(fd.cFileName, GS_TMP_SUFFIX)) continue;

            ft = ((INT64)fd.ftLastWriteTime.dwHighDateTime << 32) | fd.ftLastWriteTime.dwLowDateTime;
            if (ft > latest_ft) latest_ft = ft;

            gs_decode_name(fd.cFileName, decoded, sizeof(decoded) / sizeof(decoded[0]));
            bname_u8 = gs_w2u8(decoded);
            if (!bname_u8) { FindClose(h); goto done_unlock; }

            if (!force_all_atoms) existing_atom = gs_cloud_meta_get_atom(&old_meta, bname_u8);
            if (existing_atom && existing_atom[0]) {
                snprintf(atom, sizeof atom, "%s", existing_atom);
            } else {
                GUID guid;
                DWORD flen = 0;
                BYTE *fdata;
                _snwprintf(bpath, sizeof(bpath) / sizeof(bpath[0]), L"%ls\\%ls", dir_w, fd.cFileName);
                bpath[sizeof(bpath) / sizeof(bpath[0]) - 1] = 0;
                fdata = gs_read_file_all(bpath, &flen);
                if (!fdata) {
                    free(bname_u8);
                    FindClose(h);
                    goto done_unlock;
                }
                CoCreateGuid(&guid);
                snprintf(atom, sizeof atom, "%08lX-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X,binary",
                         (unsigned long)guid.Data1, (unsigned)guid.Data2, (unsigned)guid.Data3,
                         (unsigned)guid.Data4[0], (unsigned)guid.Data4[1], (unsigned)guid.Data4[2],
                         (unsigned)guid.Data4[3], (unsigned)guid.Data4[4], (unsigned)guid.Data4[5],
                         (unsigned)guid.Data4[6], (unsigned)guid.Data4[7]);
                if (flen > GS_LARGE_BLOB_SIZE) {
                    if (!gs_cloud_upload_large_atom(prov, atom, fdata, flen)) {
                        free(fdata);
                        free(bname_u8);
                        FindClose(h);
                        goto done_unlock;
                    }
                } else {
                    char apath[128];
                    DWORD ast = 0, arlen = 0;
                    BYTE *arbuf = NULL;
                    snprintf(apath, sizeof apath, "/%s", atom);
                    if (!gs_cloud_req(prov, "PUT", apath, "Content-Type: application/octet-stream\r\n",
                                      fdata, flen, &ast, &arbuf, &arlen) ||
                        ast < 200 || ast >= 300) {
                        gs_log("cloud upload small blob '%s' (%lu bytes) failed status=%lu",
                               bname_u8, (unsigned long)flen, (unsigned long)ast);
                        free(arbuf);
                        free(fdata);
                        free(bname_u8);
                        FindClose(h);
                        goto done_unlock;
                    }
                    free(arbuf);
                }
                free(fdata);
                uploaded_blobs++;
            }

            gs_cloud_meta_set_atom(&new_meta, bname_u8, atom);
            free(bname_u8);
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }

    for (i = 0; i < new_meta.count; i++)
        json_cap += strlen(new_meta.atoms[i].name) * 6 + strlen(new_meta.atoms[i].atom) + 12;
    json = (char *)malloc(json_cap);
    if (!json) goto done_unlock;
    jp = json + sprintf(json, "{\"atoms\":{");
    for (i = 0; i < new_meta.count; i++) {
        char *esc = gs_json_escape_str(new_meta.atoms[i].name);
        if (!esc) goto done_unlock;
        jp += sprintf(jp, "%s\"%s\":\"%s\"", i ? "," : "", esc, new_meta.atoms[i].atom);
        free(esc);
    }
    strcpy(jp, "}}");

    /* Read optional .displayname */
    _snwprintf(dn_path, sizeof(dn_path) / sizeof(dn_path[0]), L"%ls\\.displayname", dir_w);
    dn_path[sizeof(dn_path) / sizeof(dn_path[0]) - 1] = 0;
    {
        DWORD dn_len = 0;
        BYTE *dn_raw = gs_read_file_all(dn_path, &dn_len);
        if (dn_raw && dn_len >= sizeof(WCHAR)) {
            size_t wl = dn_len / sizeof(WCHAR);
            WCHAR *dw = (WCHAR *)malloc((wl + 1) * sizeof(WCHAR));
            if (dw) {
                memcpy(dw, dn_raw, wl * sizeof(WCHAR));
                dw[wl] = 0;
                disp_u8 = gs_w2u8(dw);
                free(dw);
            }
        }
        free(dn_raw);
    }

    gs_filetime_to_iso(latest_ft, timestamp);
    enc_name = gs_url_encode(name_u8);
    enc_disp = gs_url_encode(disp_u8 && disp_u8[0] ? disp_u8 : name_u8);
    if (!enc_name || !enc_disp) goto done_unlock;
    if (snprintf(subpath, sizeof subpath, "/%s,savedgame?clientFileTime=%s&displayName=%s",
                 enc_name, timestamp, enc_disp) >= (int)sizeof subpath)
        goto done_unlock;

    if (!gs_cloud_req(prov, "PUT", subpath, "Content-Type: application/json\r\n",
                      json, (DWORD)strlen(json), &st, &rbuf, &rlen) ||
        st < 200 || st >= 300) {
        gs_log("cloud commit container '%s' failed status=%lu", name_u8, (unsigned long)st);
        goto done_unlock;
    }

    snprintf(new_meta.remote, sizeof new_meta.remote, "%s", timestamp);
    new_meta.dirty = 0;
    gs_cloud_meta_save(dir_w, &new_meta);
    gs_cloud_unlock(prov, 1);
    ok = 1;
    gs_log("cloud upload container '%s' ok (blobs=%u uploaded=%d time=%s)",
           name_u8, (unsigned)new_meta.count, uploaded_blobs, timestamp);
    goto done;

done_unlock:
    gs_cloud_unlock(prov, 0);
done:
    free(rbuf);
    free(json);
    free(enc_name);
    free(enc_disp);
    free(disp_u8);
    free(name_u8);
    gs_cloud_meta_free(&old_meta);
    gs_cloud_meta_free(&new_meta);
    return ok;
}

static int gs_cloud_delete_container(gs_provider *prov, const WCHAR *name_w)
{
    char *name_u8 = NULL, *enc_name = NULL;
    char subpath[1024];
    DWORD st = 0, rlen = 0;
    BYTE *rbuf = NULL;
    int ok = 0;

    if (!prov || !prov->cloud_enabled || prov->sync_mode == GS_SYNC_DOWNLOAD) return 0;
    name_u8 = gs_w2u8(name_w);
    if (!name_u8) return 0;
    enc_name = gs_url_encode(name_u8);
    if (!enc_name) { free(name_u8); return 0; }
    if (snprintf(subpath, sizeof subpath, "/%s,savedgame", enc_name) >= (int)sizeof subpath) {
        free(enc_name); free(name_u8); return 0;
    }

    EnterCriticalSection(&prov->cloud_cs);
    if (gs_cloud_lock(prov)) {
        if (gs_cloud_req(prov, "DELETE", subpath, NULL, NULL, 0, &st, &rbuf, &rlen) &&
            (st == 200 || st == 204 || st == 404)) {
            ok = 1;
        }
        gs_cloud_unlock(prov, ok);
    }
    LeaveCriticalSection(&prov->cloud_cs);
    gs_log("cloud delete container '%s' status=%lu ok=%d", name_u8, (unsigned long)st, ok);
    free(rbuf);
    free(enc_name);
    free(name_u8);
    return ok;
}

static DWORD WINAPI gs_cloud_upload_thread(LPVOID arg)
{
    gs_provider *prov = (gs_provider *)arg;
    HRESULT hr_com = CoInitializeEx(NULL, COINIT_MULTITHREADED);

    for (;;) {
        WaitForSingleObject(prov->upload_ev, INFINITE);
        for (;;) {
            gs_upload_item *item = NULL;
            int stopping = 0;

            EnterCriticalSection(&prov->upload_cs);
            item = prov->upload_head;
            if (item) prov->upload_head = item->next;
            stopping = prov->upload_stopping;
            LeaveCriticalSection(&prov->upload_cs);

            if (!item) {
                if (stopping) goto done;
                break;
            }

            EnterCriticalSection(&prov->cloud_cs);
            gs_cloud_upload_container(prov, item->name, item->dir, 0);
            LeaveCriticalSection(&prov->cloud_cs);

            free(item->name);
            free(item->dir);
            free(item);
        }
    }

done:
    if (SUCCEEDED(hr_com)) CoUninitialize();
    return 0;
}

static void gs_cloud_queue_upload(gs_provider *prov, const WCHAR *name, const WCHAR *dir)
{
    gs_upload_item *it, *cur, *prev = NULL, **tail;
    if (!prov || !prov->cloud_enabled || prov->sync_mode == GS_SYNC_DOWNLOAD) return;
    if (!prov->upload_ev) return;

    it = (gs_upload_item *)calloc(1, sizeof *it);
    if (!it) return;
    it->name = gs_wcsdup(name);
    it->dir = gs_wcsdup(dir);
    if (!it->name || !it->dir) {
        free(it->name); free(it->dir); free(it);
        return;
    }

    EnterCriticalSection(&prov->upload_cs);
    if (prov->upload_stopping) {
        LeaveCriticalSection(&prov->upload_cs);
        free(it->name); free(it->dir); free(it);
        return;
    }
    /* Deduplicate pending upload for the same container */
    cur = prov->upload_head;
    while (cur) {
        if (wcscmp(cur->name, name) == 0) {
            gs_upload_item *rm = cur;
            cur = cur->next;
            if (prev) prev->next = cur;
            else prov->upload_head = cur;
            free(rm->name); free(rm->dir); free(rm);
        } else {
            prev = cur;
            cur = cur->next;
        }
    }
    tail = &prov->upload_head;
    while (*tail) tail = &(*tail)->next;
    *tail = it;
    SetEvent(prov->upload_ev);
    LeaveCriticalSection(&prov->upload_cs);
}

static void gs_cloud_shutdown(gs_provider *prov)
{
    gs_upload_item *cur;
    if (!prov) return;
    if (prov->upload_thread) {
        EnterCriticalSection(&prov->upload_cs);
        prov->upload_stopping = 1;
        if (prov->upload_ev) SetEvent(prov->upload_ev);
        LeaveCriticalSection(&prov->upload_cs);
        WaitForSingleObject(prov->upload_thread, 30000);
        CloseHandle(prov->upload_thread);
        prov->upload_thread = NULL;
    }
    EnterCriticalSection(&prov->upload_cs);
    cur = prov->upload_head;
    prov->upload_head = NULL;
    LeaveCriticalSection(&prov->upload_cs);
    while (cur) {
        gs_upload_item *next = cur->next;
        free(cur->name); free(cur->dir); free(cur);
        cur = next;
    }
    if (prov->upload_ev) {
        CloseHandle(prov->upload_ev);
        prov->upload_ev = NULL;
    }
    if (prov->locked) {
        EnterCriticalSection(&prov->cloud_cs);
        gs_cloud_unlock(prov, 0);
        LeaveCriticalSection(&prov->cloud_cs);
    }
}

typedef struct {
    char *name;
    char *display;
    char *time;
} gs_remote_cont;

static void gs_backup_local_container(const WCHAR *root, const WCHAR *enc_name,
                                      const WCHAR *backup_dir)
{
    WCHAR src[MAX_PATH * 2], dst[MAX_PATH * 2];
    if (!gs_ensure_dir(backup_dir)) return;
    if (_snwprintf(src, sizeof(src) / sizeof(src[0]), L"%ls\\%ls", root, enc_name) < 0 ||
        _snwprintf(dst, sizeof(dst) / sizeof(dst[0]), L"%ls\\%ls", backup_dir, enc_name) < 0)
        return;
    if (!MoveFileW(src, dst)) {
        gs_delete_tree(dst);
        MoveFileW(src, dst);
    }
}

static int gs_valid_xuid(const char *xuid)
{
    const char *p;
    if (!xuid || !xuid[0]) return 0;
    for (p = xuid; *p; p++) {
        if (*p < '0' || *p > '9') return 0;
    }
    return (size_t)(p - xuid) <= 32;
}

static int gs_build_root(const WCHAR *local_state, const char *xuid, const WCHAR *enc_sc,
                         WCHAR *out, size_t cap, int *scoped_out)
{
    int n;
    if (scoped_out) *scoped_out = 0;
    if (gs_valid_xuid(xuid)) {
        WCHAR xw[40];
        size_t i;
        for (i = 0; xuid[i] && i + 1 < sizeof(xw) / sizeof(xw[0]); i++) xw[i] = (WCHAR)xuid[i];
        xw[i] = 0;
        n = _snwprintf(out, cap, L"%ls\\GameSave\\xuid_%ls\\%ls", local_state, xw, enc_sc);
        if (n < 0 || (size_t)n >= cap) return 0;
        if (scoped_out) *scoped_out = 1;
        return 1;
    }
    n = _snwprintf(out, cap, L"%ls\\GameSave\\%ls", local_state, enc_sc);
    return (n >= 0 && (size_t)n < cap);
}

static int gs_dir_has_containers(const WCHAR *root)
{
    WCHAR pat[MAX_PATH * 2];
    WIN32_FIND_DATAW fd;
    HANDLE h;
    int found = 0;
    if (_snwprintf(pat, sizeof(pat) / sizeof(pat[0]), L"%ls\\*", root) < 0) return 0;
    h = FindFirstFileW(pat, &fd);
    if (h == INVALID_HANDLE_VALUE) return 0;
    do {
        if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
        if (fd.cFileName[0] == L'.') continue;
        if (gs_has_suffix(fd.cFileName, GS_STAGE_SUFFIX) ||
            gs_has_suffix(fd.cFileName, GS_OLD_SUFFIX))
            continue;
        found = 1;
        break;
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return found;
}

/* Migrate pre-cloud offline saves from legacy GameSave\<scid> into
   GameSave\xuid_<xuid>\<scid> once, only if the destination is empty and the
   legacy directory has no cloud-sync metadata from another account. */
static void gs_maybe_migrate_legacy_root(const WCHAR *local_state, const WCHAR *enc_sc,
                                         const WCHAR *xuid_root)
{
    WCHAR legacy[MAX_PATH * 2], marker[MAX_PATH * 2], pat[MAX_PATH * 2];
    WIN32_FIND_DATAW fd;
    HANDLE h;
    int any_cont = 0, any_cloud = 0;

    if (gs_dir_has_containers(xuid_root)) return;
    if (_snwprintf(legacy, sizeof(legacy) / sizeof(legacy[0]), L"%ls\\GameSave\\%ls",
                   local_state, enc_sc) < 0)
        return;
    if (_snwprintf(marker, sizeof(marker) / sizeof(marker[0]), L"%ls\\.legacy-migrated",
                   legacy) < 0)
        return;
    if (GetFileAttributesW(marker) != INVALID_FILE_ATTRIBUTES) return;
    if (_snwprintf(pat, sizeof(pat) / sizeof(pat[0]), L"%ls\\*", legacy) < 0) return;

    h = FindFirstFileW(pat, &fd);
    if (h == INVALID_HANDLE_VALUE) return;
    do {
        WCHAR cpath[MAX_PATH * 2];
        if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
        if (fd.cFileName[0] == L'.') continue;
        if (gs_has_suffix(fd.cFileName, GS_STAGE_SUFFIX) ||
            gs_has_suffix(fd.cFileName, GS_OLD_SUFFIX))
            continue;
        any_cont = 1;
        if (_snwprintf(cpath, sizeof(cpath) / sizeof(cpath[0]), L"%ls\\%ls\\.cloud",
                       legacy, fd.cFileName) > 0 &&
            GetFileAttributesW(cpath) != INVALID_FILE_ATTRIBUTES) {
            any_cloud = 1;
            break;
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);

    if (!any_cont || any_cloud) return;
    if (!gs_ensure_dir(xuid_root)) return;

    h = FindFirstFileW(pat, &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            WCHAR src[MAX_PATH * 2], dst[MAX_PATH * 2];
            if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
            if (fd.cFileName[0] == L'.') continue;
            if (gs_has_suffix(fd.cFileName, GS_STAGE_SUFFIX) ||
                gs_has_suffix(fd.cFileName, GS_OLD_SUFFIX))
                continue;
            if (_snwprintf(src, sizeof(src) / sizeof(src[0]), L"%ls\\%ls", legacy, fd.cFileName) > 0 &&
                _snwprintf(dst, sizeof(dst) / sizeof(dst[0]), L"%ls\\%ls", xuid_root, fd.cFileName) > 0) {
                MoveFileW(src, dst);
            }
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }
    h = CreateFileW(marker, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h != INVALID_HANDLE_VALUE) CloseHandle(h);
    gs_log("migrated pre-cloud local saves into per-account root %ls", xuid_root);
}

static void gs_cloud_sync(gs_provider *prov)
{
    gs_remote_cont *remotes = NULL;
    size_t rcount = 0, rcap = 0, i;
    char *cont_token = NULL;
    int page;
    int any_local = 0, any_baseline = 0;
    WCHAR pat[MAX_PATH * 2];
    WIN32_FIND_DATAW fd;
    HANDLE h;
    WCHAR backup_dir[MAX_PATH * 2];
    int backup_made = 0;
    int dl_count = 0, ul_count = 0, skip_count = 0;

    if (prov->sync_mode == GS_SYNC_NONE) {
        gs_log("cloud sync disabled (XODUS_SAVE_SYNC=none)");
        return;
    }
    if (!prov->scid[0]) {
        gs_log("cloud sync skipped: empty SCID");
        return;
    }
    if (!uwp_package_family_name(prov->pfn, sizeof prov->pfn)) {
        gs_log("cloud sync skipped: package family name unavailable");
        return;
    }
    if (!uwp_auth_get_xbl_session(0, prov->auth, sizeof prov->auth, prov->xuid, sizeof prov->xuid)) {
        gs_log("cloud sync skipped: Xbox Live session unavailable (offline)");
        return;
    }
    if (!prov->root_scoped_xuid && gs_valid_xuid(prov->xuid)) {
        WCHAR new_root[MAX_PATH * 2];
        int scoped = 0;
        if (gs_build_root(prov->local_state, prov->xuid, prov->enc_sc,
                          new_root, sizeof(new_root) / sizeof(new_root[0]), &scoped) &&
            scoped && gs_ensure_dir(new_root)) {
            gs_maybe_migrate_legacy_root(prov->local_state, prov->enc_sc, new_root);
            gs_recover_root(new_root);
            free(prov->root);
            prov->root = gs_wcsdup(new_root);
            prov->root_scoped_xuid = 1;
            gs_log("scoped GameSave root to signed-in account: %ls", prov->root);
        }
    }
    snprintf(prov->base_path, sizeof prov->base_path,
             "/connectedstorage/users/xuid(%s)/scids/%s", prov->xuid, prov->scid);
    prov->cloud_enabled = 1;
    gs_log("cloud sync starting: pfn=%s scid=%s mode=%d", prov->pfn, prov->scid, (int)prov->sync_mode);

    /* Fetch full paginated container index */
    for (page = 0; page < 64; page++) {
        char query[512];
        DWORD st = 0, rlen = 0;
        BYTE *rbuf = NULL;
        const char *blobs, *item, *paging;

        if (cont_token && cont_token[0]) {
            char *enc_tok = gs_url_encode(cont_token);
            if (!enc_tok) goto fail_list;
            snprintf(query, sizeof query, "?continuationToken=%s", enc_tok);
            free(enc_tok);
        } else {
            query[0] = '\0';
        }
        free(cont_token);
        cont_token = NULL;

        if (!gs_cloud_req(prov, "GET", query, NULL, NULL, 0, &st, &rbuf, &rlen)) {
            gs_log("cloud sync listing failed (network); staying local-only");
            prov->cloud_enabled = 0;
            goto cleanup;
        }
        if (st == 404 && page == 0) {
            gs_log("cloud sync: no cloud containers yet (HTTP 404)");
            free(rbuf);
            break;
        }
        if (st != 200 || !rbuf) {
            gs_log("cloud sync listing HTTP %lu; disabling cloud sync for session", (unsigned long)st);
            free(rbuf);
            prov->cloud_enabled = 0;
            goto cleanup;
        }

        blobs = gs_jfind((const char *)rbuf, "blobs");
        if (!blobs || *blobs != '[') {
            gs_log("cloud sync listing missing blobs array");
            free(rbuf);
            prov->cloud_enabled = 0;
            goto cleanup;
        }

        item = gs_jws(blobs + 1);
        while (*item && *item != ']') {
            const char *end = gs_jskip(item);
            char *fname = NULL, *comma;
            if (*item != '{' || !end || end <= item || end[-1] != '}') {
                free(rbuf);
                prov->cloud_enabled = 0;
                goto cleanup;
            }
            fname = gs_jfind_str(item, "fileName");
            if (fname) {
                comma = strstr(fname, ",savedgame");
                if (!comma) comma = strstr(fname, ",savedGame");
                if (comma && comma[10] == '\0' && comma > fname) {
                    *comma = '\0';
                    if (rcount == rcap) {
                        size_t ncap = rcap ? rcap * 2 : 16;
                        gs_remote_cont *nr = (gs_remote_cont *)realloc(remotes, ncap * sizeof *nr);
                        if (!nr) { free(fname); free(rbuf); prov->cloud_enabled = 0; goto cleanup; }
                        remotes = nr;
                        rcap = ncap;
                    }
                    remotes[rcount].name = fname;
                    remotes[rcount].display = gs_jfind_str(item, "displayName");
                    remotes[rcount].time = gs_jfind_str(item, "clientFileTime");
                    rcount++;
                    fname = NULL;
                }
            }
            free(fname);
            item = gs_jws(end);
            if (*item == ',') item = gs_jws(item + 1);
        }

        paging = gs_jfind((const char *)rbuf, "pagingInfo");
        if (paging && *paging == '{')
            cont_token = gs_jfind_str(paging, "continuationToken");
        free(rbuf);
        if (!cont_token || !cont_token[0]) break;
    }

    gs_log("cloud sync listed %u remote containers", (unsigned)rcount);

    /* Inspect local root for first-sync safety guard */
    _snwprintf(pat, sizeof(pat) / sizeof(pat[0]), L"%ls\\*", prov->root);
    pat[sizeof(pat) / sizeof(pat[0]) - 1] = 0;
    h = FindFirstFileW(pat, &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            WCHAR sub[MAX_PATH * 2];
            gs_cloud_meta m;
            if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
            if (fd.cFileName[0] == L'.') continue;
            if (gs_has_suffix(fd.cFileName, GS_STAGE_SUFFIX) ||
                gs_has_suffix(fd.cFileName, GS_OLD_SUFFIX))
                continue;
            any_local = 1;
            _snwprintf(sub, sizeof(sub) / sizeof(sub[0]), L"%ls\\%ls", prov->root, fd.cFileName);
            sub[sizeof(sub) / sizeof(sub[0]) - 1] = 0;
            if (gs_cloud_meta_load(sub, &m) && m.remote[0]) any_baseline = 1;
            gs_cloud_meta_free(&m);
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }

    _snwprintf(backup_dir, sizeof(backup_dir) / sizeof(backup_dir[0]),
               L"%ls\\.local-backup-%llu", prov->root, (unsigned long long)GetTickCount64());
    backup_dir[sizeof(backup_dir) / sizeof(backup_dir[0]) - 1] = 0;

    /* First-sync safety guard: if cloud already has saves and local has only unsynced saves
     * (created before cloud sync existed), back up local and pull the full consistent cloud set
     * so we never corrupt or overwrite the user's real Xbox/PC save! */
    if (rcount > 0 && any_local && !any_baseline && prov->sync_mode != GS_SYNC_UPLOAD) {
        gs_log("cloud sync: unsynced local save detected alongside %u cloud containers; backing up local",
               (unsigned)rcount);
        h = FindFirstFileW(pat, &fd);
        if (h != INVALID_HANDLE_VALUE) {
            do {
                if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
                if (fd.cFileName[0] == L'.') continue;
                if (gs_has_suffix(fd.cFileName, GS_STAGE_SUFFIX) ||
                    gs_has_suffix(fd.cFileName, GS_OLD_SUFFIX))
                    continue;
                gs_backup_local_container(prov->root, fd.cFileName, backup_dir);
                backup_made = 1;
            } while (FindNextFileW(h, &fd));
            FindClose(h);
        }
    }

    /* Reconcile every remote container */
    for (i = 0; i < rcount; i++) {
        WCHAR *nw = gs_u82w(remotes[i].name);
        WCHAR enc[260], ldir[MAX_PATH * 2];
        gs_cloud_meta lm;
        int exists;

        if (!nw || !gs_encode_name(nw, enc, sizeof(enc) / sizeof(enc[0]))) {
            free(nw);
            continue;
        }
        _snwprintf(ldir, sizeof(ldir) / sizeof(ldir[0]), L"%ls\\%ls", prov->root, enc);
        ldir[sizeof(ldir) / sizeof(ldir[0]) - 1] = 0;
        exists = (GetFileAttributesW(ldir) != INVALID_FILE_ATTRIBUTES);
        memset(&lm, 0, sizeof lm);
        if (exists) gs_cloud_meta_load(ldir, &lm);

        if (prov->sync_mode == GS_SYNC_UPLOAD && exists) {
            if (gs_cloud_upload_container(prov, nw, ldir, 1)) ul_count++;
        } else if (!exists) {
            if (gs_cloud_download_container(prov, remotes[i].name, remotes[i].display, remotes[i].time))
                dl_count++;
        } else if (prov->sync_mode == GS_SYNC_DOWNLOAD) {
            if (!lm.dirty && lm.remote[0] && remotes[i].time && strcmp(lm.remote, remotes[i].time) == 0) {
                skip_count++;
            } else if (gs_cloud_download_container(prov, remotes[i].name, remotes[i].display, remotes[i].time)) {
                dl_count++;
            }
        } else if (!lm.dirty) {
            if (lm.remote[0] && remotes[i].time && strcmp(lm.remote, remotes[i].time) == 0) {
                skip_count++;
            } else if (gs_cloud_download_container(prov, remotes[i].name, remotes[i].display, remotes[i].time)) {
                dl_count++;
            }
        } else {
            /* Local is dirty */
            if (lm.remote[0] && remotes[i].time && strcmp(lm.remote, remotes[i].time) == 0) {
                /* Clean local edit on top of current cloud baseline -> upload */
                if (gs_cloud_upload_container(prov, nw, ldir, 0)) ul_count++;
            } else {
                /* Conflict: compare timestamps; prefer cloud if newer/equal (back up local first) */
                INT64 rft = gs_iso_to_filetime(remotes[i].time);
                INT64 lft = 0;
                gs_dir_total_size(ldir, &lft);
                if (!lm.remote[0] || rft >= lft) {
                    gs_backup_local_container(prov->root, enc, backup_dir);
                    backup_made = 1;
                    if (gs_cloud_download_container(prov, remotes[i].name, remotes[i].display, remotes[i].time))
                        dl_count++;
                } else {
                    if (gs_cloud_upload_container(prov, nw, ldir, 0)) ul_count++;
                }
            }
        }

        gs_cloud_meta_free(&lm);
        free(nw);
    }

    /* Reconcile local-only containers */
    if (prov->sync_mode != GS_SYNC_DOWNLOAD) {
        h = FindFirstFileW(pat, &fd);
        if (h != INVALID_HANDLE_VALUE) {
            do {
                WCHAR decoded[260], ldir[MAX_PATH * 2];
                char *nu8;
                int in_remote = 0;
                gs_cloud_meta lm;

                if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
                if (fd.cFileName[0] == L'.') continue;
                if (gs_has_suffix(fd.cFileName, GS_STAGE_SUFFIX) ||
                    gs_has_suffix(fd.cFileName, GS_OLD_SUFFIX))
                    continue;
                gs_decode_name(fd.cFileName, decoded, sizeof(decoded) / sizeof(decoded[0]));
                nu8 = gs_w2u8(decoded);
                if (!nu8) continue;
                for (i = 0; i < rcount; i++) {
                    if (strcmp(remotes[i].name, nu8) == 0) { in_remote = 1; break; }
                }
                free(nu8);
                if (in_remote) continue;

                _snwprintf(ldir, sizeof(ldir) / sizeof(ldir[0]), L"%ls\\%ls", prov->root, fd.cFileName);
                ldir[sizeof(ldir) / sizeof(ldir[0]) - 1] = 0;
                gs_cloud_meta_load(ldir, &lm);
                if (prov->sync_mode == GS_SYNC_UPLOAD) {
                    if (gs_cloud_upload_container(prov, decoded, ldir, 1)) ul_count++;
                } else if (lm.dirty || !lm.remote[0]) {
                    if (gs_cloud_upload_container(prov, decoded, ldir, 0)) ul_count++;
                } else {
                    /* Previously synced container deleted remotely -> remove local copy */
                    gs_delete_tree(ldir);
                }
                gs_cloud_meta_free(&lm);
            } while (FindNextFileW(h, &fd));
            FindClose(h);
        }
    }

    /* Start background upload thread for SubmitUpdatesAsync */
    if (prov->cloud_enabled && prov->sync_mode != GS_SYNC_DOWNLOAD) {
        prov->upload_ev = CreateEventW(NULL, FALSE, FALSE, NULL);
        if (prov->upload_ev) {
            prov->upload_thread = CreateThread(NULL, 0, gs_cloud_upload_thread, prov, 0, NULL);
            if (!prov->upload_thread) {
                CloseHandle(prov->upload_ev);
                prov->upload_ev = NULL;
            }
        }
    }

    gs_log("cloud sync complete: downloaded=%d uploaded=%d up_to_date=%d backup=%d",
           dl_count, ul_count, skip_count, backup_made);
    goto cleanup;

fail_list:
    prov->cloud_enabled = 0;
cleanup:
    free(cont_token);
    for (i = 0; i < rcount; i++) {
        free(remotes[i].name);
        free(remotes[i].display);
        free(remotes[i].time);
    }
    free(remotes);
}

typedef struct {
    gs_provider *prov;
    gs_async *op;
} gs_open_ctx;

static DWORD WINAPI gs_open_worker(LPVOID arg)
{
    gs_open_ctx *ctx = (gs_open_ctx *)arg;
    gs_result *res;
    HRESULT hr_com = CoInitializeEx(NULL, COINIT_MULTITHREADED);

    gs_cloud_sync(ctx->prov);
    res = gs_res_new(&gs_iid_provider_get,
                     "Windows.Gaming.XboxLive.Storage.GameSaveProviderGetResult",
                     GS_OK, ctx->prov);
    gs_async_complete_obj(ctx->op, res);
    gs_res_Release(res);
    gs_prov_Release(ctx->prov);
    gs_async_Release(ctx->op);
    free(ctx);

    if (SUCCEEDED(hr_com)) CoUninitialize();
    return 0;
}

static HRESULT gs_open_provider(void *user, hstring_t scid, int sync_on_demand, void **out)
{
    const WCHAR *sc = gs_hstr_raw(scid);
    WCHAR local_state[MAX_PATH], enc_sc[128], root[MAX_PATH * 2];
    gs_provider *prov;
    gs_result *res;
    int root_scoped = 0;

    if (!out) return E_POINTER;
    local_state[0] = 0;
    if (!uwp_local_state_dir(local_state, sizeof(local_state) / sizeof(local_state[0])) ||
        !gs_encode_name((sc && sc[0]) ? sc : L"default", enc_sc, sizeof(enc_sc) / sizeof(enc_sc[0]))) {
        res = gs_res_new(&gs_iid_provider_get,
                         "Windows.Gaming.XboxLive.Storage.GameSaveProviderGetResult",
                         GS_OUT_OF_LOCAL_STORAGE, NULL);
        *out = gs_async_new_obj(&gs_iid_async_provider_get,
                                "Windows.Foundation.IAsyncOperation`1<GameSaveProviderGetResult>", res);
        gs_res_Release(res);
        return *out ? S_OK : E_OUTOFMEMORY;
    }
    {
        char cached_xuid[64];
        cached_xuid[0] = '\0';
        uwp_auth_get_cached_xuid(cached_xuid, sizeof cached_xuid);
        if (!gs_build_root(local_state, cached_xuid, enc_sc,
                           root, sizeof(root) / sizeof(root[0]), &root_scoped) ||
            !gs_ensure_dir(root)) {
            res = gs_res_new(&gs_iid_provider_get,
                             "Windows.Gaming.XboxLive.Storage.GameSaveProviderGetResult",
                             GS_OUT_OF_LOCAL_STORAGE, NULL);
            *out = gs_async_new_obj(&gs_iid_async_provider_get,
                                    "Windows.Foundation.IAsyncOperation`1<GameSaveProviderGetResult>", res);
            gs_res_Release(res);
            return *out ? S_OK : E_OUTOFMEMORY;
        }
        if (root_scoped)
            gs_maybe_migrate_legacy_root(local_state, enc_sc, root);
    }
    gs_recover_root(root);

    prov = (gs_provider *)calloc(1, sizeof(gs_provider));
    if (!prov) return E_OUTOFMEMORY;
    prov->vtbl = gs_prov_vtable;
    prov->refs = 1;
    prov->user = user;
    gs_unk_addref(user);
    prov->root = gs_wcsdup(root);
    wcsncpy(prov->local_state, local_state, sizeof(prov->local_state) / sizeof(prov->local_state[0]) - 1);
    wcsncpy(prov->enc_sc, enc_sc, sizeof(prov->enc_sc) / sizeof(prov->enc_sc[0]) - 1);
    prov->root_scoped_xuid = root_scoped;
    prov->sync_mode = gs_get_sync_mode();
    prov->quota = 256LL * 1024 * 1024;
    InitializeCriticalSection(&prov->cloud_cs);
    InitializeCriticalSection(&prov->upload_cs);

    if (sc && sc[0]) {
        char *sc_u8 = gs_w2u8(sc);
        if (sc_u8) {
            const char *s = sc_u8;
            size_t slen = strlen(s);
            if (slen >= 2 && s[0] == '{' && s[slen - 1] == '}') {
                s++;
                slen -= 2;
            }
            if (slen < sizeof prov->scid) {
                memcpy(prov->scid, s, slen);
                prov->scid[slen] = '\0';
            }
            free(sc_u8);
        }
    }

    gs_log("GameSaveProvider::%s(scid=%s) -> root=%ls sync_mode=%d",
           sync_on_demand ? "GetSyncOnDemandForUserAsync" : "GetForUserAsync",
           prov->scid[0] ? prov->scid : "<empty>", root, (int)prov->sync_mode);

    if (prov->sync_mode != GS_SYNC_NONE && prov->scid[0]) {
        gs_async *op = gs_async_new_pending(
            &gs_iid_async_provider_get,
            "Windows.Foundation.IAsyncOperation`1<GameSaveProviderGetResult>");
        gs_open_ctx *ctx = (gs_open_ctx *)calloc(1, sizeof *ctx);
        if (op && ctx) {
            HANDLE th;
            ctx->prov = prov;
            ctx->op = op;
            gs_async_AddRef(op); /* worker holds one ref; caller gets initial ref */
            th = CreateThread(NULL, 0, gs_open_worker, ctx, 0, NULL);
            if (th) {
                CloseHandle(th);
                *out = op;
                return S_OK;
            }
            gs_async_Release(op);
            gs_async_Release(op);
            free(ctx);
        } else {
            if (op) gs_async_Release(op);
            free(ctx);
        }
    }

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
