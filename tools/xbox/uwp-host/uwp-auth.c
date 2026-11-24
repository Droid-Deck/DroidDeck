/*
 * Windows.System.User and Web Account Manager (WebAuthenticationCoreManager)
 * backed by DroidDeck's live xodus-service Xbox Live session.
 *
 * UWP Xbox Live titles (XSAPI / Microsoft.Xbox.Services.dll) discover the
 * signed-in Windows user via Windows.System.User::FindAllAsync and acquire
 * XSTS tokens via Windows.Security.Authentication.Web.Core.WebAuthenticationCoreManager
 * against the https://xsts.auth.xboxlive.com provider.
 *
 * This module implements those WinRT interfaces and exchanges the live MSA RPS
 * ticket from XODUS_BRIDGE_PORT / XODUS_BRIDGE_KEY with Microsoft's
 * user.auth.xboxlive.com and xsts.auth.xboxlive.com endpoints via WinHTTP.
 */
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <wininet.h>
#include <objbase.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <wchar.h>

typedef void *hstring_t;

typedef struct {
    const void **vtbl;
    LONG refs;
    const char *name;
    int permanent;
} auth_obj;

#ifndef E_ILLEGAL_DELEGATE_ASSIGNMENT
#define E_ILLEGAL_DELEGATE_ASSIGNMENT ((HRESULT)0x80000018L)
#endif
#ifndef E_BOUNDS
#define E_BOUNDS ((HRESULT)0x8000000BL)
#endif

/* AsyncStatus */
#define AUTH_ASYNC_STARTED   0
#define AUTH_ASYNC_COMPLETED 1
#define AUTH_ASYNC_CANCELED  2
#define AUTH_ASYNC_ERROR     3

/* Windows.System.UserType */
#define USER_TYPE_LOCAL  0
/* Windows.System.UserAuthenticationStatus */
#define USER_AUTH_LOCAL  1
/* Windows.System.UserWatcherStatus */
#define WATCHER_CREATED  0
#define WATCHER_STARTED  1
#define WATCHER_ENUMDONE 2
#define WATCHER_STOPPED  4
/* Windows.Security.Credentials.WebAccountState */
#define WEB_ACCOUNT_CONNECTED 1
/* Windows.Security.Authentication.Web.Core.WebTokenRequestStatus */
#define WTR_STATUS_SUCCESS        0
#define WTR_STATUS_PROVIDER_ERROR 5
/* Windows.Foundation.PropertyType */
#define PROP_TYPE_STRING 12

/* xodus-service XML wire framing */
#define XODUS_XML_MAGIC        0x58445358U
#define XODUS_MSA_TOKEN_REQ    3U
#define XODUS_MSA_TOKEN_RESP   4U
#define XODUS_XBOX_CLIENT_ID   "000000004424da1f"

/* --------------------------------------------------------------- logging */

static int auth_trace_on = -1;

static void auth_sink(const char *line)
{
    HANDLE file;
    DWORD written;
    if (auth_trace_on < 0) {
        char setting[8];
        DWORD len = GetEnvironmentVariableA("DROIDDECK_UWP_TRACE", setting, sizeof setting);
        auth_trace_on = len > 0 && len < sizeof setting;
    }
    if (!auth_trace_on) return;
    file = CreateFileA("C:\\uwp-host-trace.log", FILE_APPEND_DATA,
                       FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS,
                       FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return;
    WriteFile(file, line, (DWORD)strlen(line), &written, NULL);
    WriteFile(file, "\r\n", 2, &written, NULL);
    CloseHandle(file);
}

static void auth_log(const char *fmt, ...)
{
    char text[384];
    char line[440];
    va_list args;
    va_start(args, fmt);
    vsnprintf(text, sizeof text, fmt, args);
    va_end(args);
    snprintf(line, sizeof line, "%04x uwp-auth: %s", (unsigned)GetCurrentThreadId(), text);
    auth_sink(line);
    OutputDebugStringA(line);
    OutputDebugStringA("\n");
}

static void auth_tracef(const char *fmt, ...)
{
    char text[384];
    char line[440];
    va_list args;
    if (auth_trace_on < 0) {
        char setting[8];
        DWORD len = GetEnvironmentVariableA("DROIDDECK_UWP_TRACE", setting, sizeof setting);
        auth_trace_on = len > 0 && len < sizeof setting;
    }
    if (!auth_trace_on) return;
    va_start(args, fmt);
    vsnprintf(text, sizeof text, fmt, args);
    va_end(args);
    snprintf(line, sizeof line, "%04x uwp-auth: %s", (unsigned)GetCurrentThreadId(), text);
    auth_sink(line);
}

/* --------------------------------------------------------------- HSTRING */

typedef HRESULT(WINAPI *win_create_hstring_fn)(const WCHAR *, UINT32, hstring_t *);
typedef const WCHAR *(WINAPI *win_hstring_buf_fn)(hstring_t, UINT32 *);
typedef HRESULT(WINAPI *win_delete_hstring_fn)(hstring_t);
typedef HRESULT(WINAPI *win_dup_hstring_fn)(hstring_t, hstring_t *);

static win_create_hstring_fn fn_create_hstring;
static win_hstring_buf_fn fn_hstring_buf;
static win_delete_hstring_fn fn_delete_hstring;
static win_dup_hstring_fn fn_dup_hstring;

static void load_hstring_api(void)
{
    HMODULE mod;
    if (fn_create_hstring) return;
    mod = GetModuleHandleA("combase.dll");
    if (!mod) mod = LoadLibraryA("combase.dll");
    if (!mod) return;
    fn_create_hstring = (win_create_hstring_fn)(void *)GetProcAddress(mod, "WindowsCreateString");
    fn_hstring_buf = (win_hstring_buf_fn)(void *)GetProcAddress(mod, "WindowsGetStringRawBuffer");
    fn_delete_hstring = (win_delete_hstring_fn)(void *)GetProcAddress(mod, "WindowsDeleteString");
    fn_dup_hstring = (win_dup_hstring_fn)(void *)GetProcAddress(mod, "WindowsDuplicateString");
}

static hstring_t hstr_from_w(const WCHAR *w)
{
    hstring_t out = NULL;
    load_hstring_api();
    if (!fn_create_hstring || !w || !w[0]) return NULL;
    fn_create_hstring(w, (UINT32)wcslen(w), &out);
    return out;
}

static hstring_t hstr_from_utf8(const char *s)
{
    int n;
    WCHAR *w;
    hstring_t out;
    if (!s || !s[0]) return NULL;
    n = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
    if (n <= 1) return NULL;
    w = (WCHAR *)malloc((size_t)n * sizeof(WCHAR));
    if (!w) return NULL;
    MultiByteToWideChar(CP_UTF8, 0, s, -1, w, n);
    out = hstr_from_w(w);
    free(w);
    return out;
}

static const WCHAR *hstr_raw(hstring_t h, UINT32 *len_out)
{
    UINT32 len = 0;
    const WCHAR *w;
    load_hstring_api();
    if (!h || !fn_hstring_buf) {
        if (len_out) *len_out = 0;
        return L"";
    }
    w = fn_hstring_buf(h, &len);
    if (len_out) *len_out = w ? len : 0;
    return w ? w : L"";
}

static char *hstr_to_utf8(hstring_t h)
{
    UINT32 wlen = 0;
    const WCHAR *w = hstr_raw(h, &wlen);
    int n;
    char *s;
    if (!w || !wlen) return strdup("");
    n = WideCharToMultiByte(CP_UTF8, 0, w, (int)wlen, NULL, 0, NULL, NULL);
    if (n <= 0) return strdup("");
    s = (char *)malloc((size_t)n + 1);
    if (!s) return NULL;
    WideCharToMultiByte(CP_UTF8, 0, w, (int)wlen, s, n, NULL, NULL);
    s[n] = '\0';
    return s;
}

/* --------------------------------------------------------------- COM helpers */

static void com_addref(void *p)
{
    if (!p) return;
    ((ULONG(__stdcall *)(void *))(*(void ***)p)[1])(p);
}

static void com_release(void *p)
{
    if (!p) return;
    ((ULONG(__stdcall *)(void *))(*(void ***)p)[2])(p);
}

static ULONG __stdcall obj_AddRef(void *self)
{
    auth_obj *o = (auth_obj *)self;
    return (ULONG)InterlockedIncrement(&o->refs);
}

static ULONG __stdcall obj_Release(void *self)
{
    auth_obj *o = (auth_obj *)self;
    LONG left = InterlockedDecrement(&o->refs);
    if (left <= 0 && !o->permanent) free(o);
    return (ULONG)left;
}

static HRESULT __stdcall obj_GetIids(void *self, ULONG *count, GUID **ids)
{
    (void)self;
    if (count) *count = 0;
    if (ids) *ids = NULL;
    return S_OK;
}

static HRESULT __stdcall obj_GetClassName(void *self, hstring_t *out)
{
    auth_obj *o = (auth_obj *)self;
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(o && o->name ? o->name : "");
    return S_OK;
}

static HRESULT __stdcall obj_GetTrustLevel(void *self, int *out)
{
    (void)self;
    if (out) *out = 0;
    return S_OK;
}

/* --------------------------------------------------------------- GUIDs */

static const GUID IID_IUnknown_a      = {0x00000000,0x0000,0x0000,{0xc0,0x00,0x00,0x00,0x00,0x00,0x00,0x46}};
static const GUID IID_IInspectable_a  = {0xaf86e2e0,0xb12d,0x4c6a,{0x9c,0x5a,0xd7,0xaa,0x65,0x10,0x1e,0x90}};
static const GUID IID_IFactory_a      = {0x00000035,0x0000,0x0000,{0xc0,0x00,0x00,0x00,0x00,0x00,0x00,0x46}};
static const GUID IID_IAsyncInfo_a    = {0x00000036,0x0000,0x0000,{0xc0,0x00,0x00,0x00,0x00,0x00,0x00,0x46}};
static const GUID IID_IPropertyValue_a= {0x4bd682dd,0x7554,0x40e9,{0x9a,0x9b,0x82,0x65,0x4e,0xde,0x7e,0x62}};

/* Windows.System.User */
static const GUID IID_IUserStatics    = {0x155eb23b,0x242a,0x45e0,{0xa2,0xe9,0x31,0x71,0xfc,0x6a,0x7f,0xdd}};
static const GUID IID_IUserStatics2   = {0x74a37e11,0x2eb5,0x4487,{0xb0,0xd5,0x2c,0x67,0x90,0xe0,0x13,0xe9}};
static const GUID IID_IUser           = {0xdf9a26c6,0xe746,0x4bcd,{0xb5,0xd4,0x12,0x01,0x03,0xc4,0x20,0x9b}};
static const GUID IID_IUser2          = {0x98ba5628,0xa6e3,0x518e,{0x89,0xd9,0xd3,0xb2,0xb1,0x99,0x1a,0x10}};
static const GUID IID_IUserWatcher    = {0x155eb23b,0x242a,0x45e0,{0xa2,0xe9,0x31,0x71,0xfc,0x6a,0x7f,0xbb}};
static const GUID IID_IUserChangedEA  = {0x086459dc,0x18c6,0x48db,{0xbc,0x99,0x72,0x4f,0xb9,0x20,0x3c,0xcc}};
static const GUID IID_IKnownUserProps = {0x7755911a,0x70c5,0x48e5,{0xb6,0x37,0x5b,0xa3,0x44,0x1e,0x4e,0xe4}};
static const GUID IID_IUserPickerStat = {0xde3290dc,0x7e73,0x4df6,{0xa1,0xae,0x4d,0x7e,0xca,0x82,0xb4,0x0d}};
static const GUID IID_IUserPicker     = {0x7d548008,0xf1e3,0x4a6c,{0x8d,0xdc,0xa9,0xbb,0x0f,0x48,0x8a,0xed}};

/* WebAuthenticationCoreManager & WebTokenRequest/Response */
static const GUID IID_IWacmStatics    = {0x6aca7c92,0xa581,0x4479,{0x9c,0x10,0x75,0x2e,0xff,0x44,0xfd,0x34}};
static const GUID IID_IWacmStatics2   = {0xf584184a,0x8b57,0x4820,{0xb6,0xa4,0x70,0xa5,0xb6,0xfc,0xf4,0x4a}};
static const GUID IID_IWacmStatics3   = {0x2404eeb2,0x8924,0x4d93,{0xab,0x3a,0x99,0x68,0x8b,0x41,0x9d,0x56}};
static const GUID IID_IWacmStatics4   = {0x54e633fe,0x96e0,0x41e8,{0x98,0x32,0x12,0x98,0x89,0x7c,0x2a,0xaf}};
static const GUID IID_IWtrFactory     = {0x6cf2141c,0x0ff0,0x4c67,{0xb8,0x4f,0x99,0xdd,0xbe,0x4a,0x72,0xc9}};
static const GUID IID_IWtr            = {0xb77b4d68,0xadcb,0x4673,{0xb3,0x64,0x0c,0xf7,0xb3,0x5c,0xaf,0x97}};
static const GUID IID_IWtr2           = {0xd700c079,0x30c8,0x4397,{0x96,0x54,0x96,0x1c,0x3b,0xe8,0xb8,0x55}};
static const GUID IID_IWtr3           = {0x5a755b51,0x3bb1,0x41a5,{0xa6,0x3d,0x90,0xbc,0x32,0xc7,0xdb,0x9a}};
static const GUID IID_IWtrResult      = {0xc12a8305,0xd1f8,0x4483,{0x8d,0x54,0x38,0xfe,0x29,0x27,0x84,0xff}};
static const GUID IID_IWtrResponse    = {0x67a7c5ca,0x83f6,0x44c6,{0xa3,0xb1,0x0e,0xb6,0x9e,0x41,0xfa,0x8a}};
static const GUID IID_IWebProvError   = {0xdb191bb1,0x50c5,0x4809,{0x8d,0xca,0x09,0xc9,0x94,0x10,0x24,0x5c}};

/* Windows.Security.Credentials */
static const GUID IID_IWebAccount     = {0x69473eb2,0x8031,0x49be,{0x80,0xbb,0x96,0xcb,0x46,0xd9,0x9a,0xba}};
static const GUID IID_IWebAccount2    = {0x7b56d6f8,0x990b,0x4eb5,{0x94,0xa7,0x56,0x21,0xf3,0xa8,0xb8,0x24}};
static const GUID IID_IWebAccProv     = {0x29dcc8c3,0x7ab9,0x4a7c,{0xa3,0x36,0xb9,0x42,0xf9,0xdb,0xf7,0xc7}};
static const GUID IID_IWebAccProv2    = {0x4a01eb05,0x4e42,0x41d4,{0xb5,0x18,0xe0,0x08,0xa5,0x16,0x36,0x14}};
static const GUID IID_IWebAccProv3    = {0xda1c518b,0x970d,0x4d49,{0x82,0x5c,0xf2,0x70,0x6f,0x8c,0xa7,0xfe}};
static const GUID IID_IWebAccProv4    = {0x718fd8db,0xe796,0x4210,{0xb7,0x4e,0x84,0xd2,0x98,0x94,0xb0,0x80}};

/* Parameterized collection IIDs (verified against Windows WinMD & XSAPI) */
static const GUID IID_IVecViewUser    = {0x8cbd762a,0x1222,0x5ee5,{0xb7,0x45,0x48,0x9e,0x7a,0x42,0xc6,0xec}};
static const GUID IID_IIterableUser   = {0xd1bacd1f,0x0376,0x5823,{0x8c,0x29,0x1d,0x45,0xb9,0xf4,0xc1,0x91}};
static const GUID IID_IIteratorUser   = {0x326fe162,0x582b,0x5659,{0xb8,0xa4,0x68,0xff,0x0f,0x52,0x57,0x45}};

static const GUID IID_IVecViewWtrResp = {0x199e065c,0x8195,0x55da,{0x9c,0x10,0x8a,0xea,0xf9,0xac,0x10,0x62}};
static const GUID IID_IIterableWtrRsp = {0x7e5bb7ec,0xbbd7,0x5575,{0x9a,0x61,0xf5,0x81,0x5f,0xa2,0x2a,0x0e}};
static const GUID IID_IIteratorWtrRsp = {0xf080b0c9,0xa095,0x5b3a,{0xa1,0xdc,0xd1,0x7e,0x7d,0x29,0x82,0xc7}};

static const GUID IID_IMapStrStr      = {0xf6d1f700,0x49c2,0x52ae,{0x81,0x54,0x82,0x6f,0x99,0x08,0x77,0x3c}};
static const GUID IID_IMapViewStrStr  = {0xac7f26f2,0xfeb7,0x5b2a,{0x8a,0xc4,0x34,0x5b,0xc6,0x2c,0xae,0xde}};
static const GUID IID_IKvpStrStr      = {0x60310303,0x49c5,0x52e6,{0xab,0xc6,0xa9,0xb3,0x6e,0xcc,0xc7,0x16}};
static const GUID IID_IIterableKvpSS  = {0xe9bdaaf0,0xcbf6,0x5c72,{0xbe,0x90,0x29,0xcb,0xf3,0xa1,0x31,0x9b}};
static const GUID IID_IIteratorKvpSS  = {0x05eb86f1,0x7140,0x5517,{0xb8,0x8d,0xcb,0xae,0xbe,0x57,0xe6,0xb1}};

static const GUID IID_IAsyncAction_a      = {0x5a648006,0x843a,0x4da9,{0x86,0x5b,0x9d,0x26,0xe5,0xdf,0xad,0x7b}};
static const GUID IID_IAgileObject_a      = {0x94ea2b94,0xe9cc,0x49e0,{0xc0,0xff,0xee,0x64,0xca,0x8f,0x5b,0x90}};
static const GUID IID_IAsyncOpObj         = {0xabf53c57,0xee50,0x5342,{0xb5,0x2a,0x26,0xe3,0xb8,0xcc,0x02,0x4f}};
static const GUID IID_IAsyncOpStreamRef   = {0x65178d50,0xe6a2,0x5d16,{0xb2,0x44,0x65,0xe9,0x72,0x5e,0x5a,0x0c}};
static const GUID IID_IAsyncOpStream      = {0x430ecece,0x1418,0x5d19,{0x81,0xb2,0x5d,0xdb,0x38,0x16,0x03,0xcc}};
static const GUID IID_IAsyncOpVecUser     = {0xe44ea1df,0xbb85,0x5a8c,{0xbd,0xdc,0xc8,0xe9,0x60,0xc3,0x55,0xc9}};
static const GUID IID_IAsyncOpUser        = {0xa895d2f9,0x2399,0x5104,{0xa5,0x32,0x5b,0xa4,0x4a,0xb4,0xb1,0x65}};
static const GUID IID_IAsyncOpWtrResult   = {0x0a815852,0x7c44,0x5674,{0xb3,0xd2,0xfa,0x2e,0x4c,0x1e,0x46,0xc9}};
static const GUID IID_IAsyncOpWebAccount  = {0xacd76b54,0x297f,0x5a18,{0x91,0x43,0x20,0xa3,0x09,0xe2,0xdf,0xd3}};
static const GUID IID_IAsyncOpWebProvider = {0x88c66009,0x12f7,0x58e2,{0x8d,0xbe,0x6e,0xfc,0x62,0x0c,0x85,0xba}};

typedef HRESULT(WINAPI *pfn_CoInitializeEx)(LPVOID, DWORD);
typedef void(WINAPI *pfn_CoUninitialize)(void);
typedef HRESULT(WINAPI *pfn_CoMarshalInterThreadInterfaceInStream)(REFIID, LPUNKNOWN, LPSTREAM *);
typedef HRESULT(WINAPI *pfn_CoGetInterfaceAndReleaseStream)(LPSTREAM, REFIID, LPVOID *);

static pfn_CoInitializeEx fn_CoInitializeEx;
static pfn_CoUninitialize fn_CoUninitialize;
static pfn_CoMarshalInterThreadInterfaceInStream fn_CoMarshalInterThreadInterfaceInStream;
static pfn_CoGetInterfaceAndReleaseStream fn_CoGetInterfaceAndReleaseStream;
static INIT_ONCE g_ole_once = INIT_ONCE_STATIC_INIT;

static BOOL CALLBACK init_ole_cb(PINIT_ONCE once, PVOID param, PVOID *ctx)
{
    HMODULE ole = GetModuleHandleA("ole32.dll");
    (void)once; (void)param; (void)ctx;
    if (!ole) ole = LoadLibraryA("ole32.dll");
    if (ole) {
        fn_CoInitializeEx = (pfn_CoInitializeEx)(void *)GetProcAddress(ole, "CoInitializeEx");
        fn_CoUninitialize = (pfn_CoUninitialize)(void *)GetProcAddress(ole, "CoUninitialize");
        fn_CoMarshalInterThreadInterfaceInStream =
            (pfn_CoMarshalInterThreadInterfaceInStream)(void *)GetProcAddress(
                ole, "CoMarshalInterThreadInterfaceInStream");
        fn_CoGetInterfaceAndReleaseStream =
            (pfn_CoGetInterfaceAndReleaseStream)(void *)GetProcAddress(
                ole, "CoGetInterfaceAndReleaseStream");
    }
    return TRUE;
}

static void auth_ole_init(void)
{
    InitOnceExecuteOnce(&g_ole_once, init_ole_cb, NULL, NULL);
}

static int handler_is_agile(void *handler)
{
    typedef HRESULT(__stdcall *qi_fn)(void *, const GUID *, void **);
    void *agile = NULL;
    qi_fn *slots;
    if (!handler) return 1;
    slots = *(qi_fn **)handler;
    if (slots && slots[0] && SUCCEEDED(slots[0](handler, &IID_IAgileObject_a, &agile)) && agile) {
        com_release(agile);
        return 1;
    }
    return 0;
}

/* --------------------------------------------------------------- AsyncOp */

typedef struct {
    auth_obj base;
    auth_obj info;
    CRITICAL_SECTION lock;
    const GUID *op_iid;
    void *result;
    void *handler;
    void *handler_stream;
    DWORD handler_tid;
    HRESULT error;
    int status;
    int fired;
    int is_operation;
} auth_async;

static const void *auth_async_info_vtbl[];
static const void *auth_async_action_vtbl[];
static const void *auth_async_op_vtbl[];

static auth_async *auth_async_from(void *self)
{
    auth_obj *h = (auth_obj *)self;
    if (h && h->vtbl == auth_async_info_vtbl)
        return (auth_async *)((char *)self - offsetof(auth_async, info));
    return (auth_async *)self;
}

static ULONG __stdcall auth_async_AddRef(void *self)
{
    return (ULONG)InterlockedIncrement(&auth_async_from(self)->base.refs);
}

static ULONG __stdcall auth_async_Release(void *self)
{
    auth_async *op = auth_async_from(self);
    LONG left = InterlockedDecrement(&op->base.refs);
    if (left <= 0) {
        if (op->result) com_release(op->result);
        if (op->handler_stream) com_release(op->handler_stream);
        if (op->handler) com_release(op->handler);
        DeleteCriticalSection(&op->lock);
        free(op);
    }
    return (ULONG)left;
}

static HRESULT __stdcall auth_async_QI(void *self, const GUID *id, void **out)
{
    auth_async *op = auth_async_from(self);
    if (!out) return E_POINTER;
    *out = NULL;
    if (!id) return E_INVALIDARG;
    if (IsEqualIID(id, &IID_IAsyncInfo_a)) {
        *out = &op->info;
    } else if (IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
               IsEqualIID(id, &IID_IAgileObject_a) ||
               (op->op_iid && IsEqualIID(id, op->op_iid))) {
        *out = op;
    } else {
        return E_NOINTERFACE;
    }
    auth_async_AddRef(op);
    return S_OK;
}

static HRESULT __stdcall auth_async_get_Id(void *self, UINT32 *out)
{
    (void)self;
    if (out) *out = 1;
    return S_OK;
}

static HRESULT __stdcall auth_async_get_Status(void *self, int *out)
{
    auth_async *op = auth_async_from(self);
    EnterCriticalSection(&op->lock);
    if (out) *out = op->status;
    LeaveCriticalSection(&op->lock);
    return S_OK;
}

static HRESULT __stdcall auth_async_get_ErrorCode(void *self, HRESULT *out)
{
    auth_async *op = auth_async_from(self);
    EnterCriticalSection(&op->lock);
    if (out) *out = op->error;
    LeaveCriticalSection(&op->lock);
    return S_OK;
}

static void auth_async_invoke(auth_async *op, void *handler, void *stream, DWORD handler_tid,
                              int status)
{
    typedef HRESULT(__stdcall *invoke_fn)(void *, void *, int);
    void *target = handler;
    void *unmarshaled = NULL;
    invoke_fn *slots;
    if (!handler && !stream) return;
    auth_async_AddRef(op);
    if (stream) {
        auth_ole_init();
        if (GetCurrentThreadId() != handler_tid && fn_CoGetInterfaceAndReleaseStream) {
            if (SUCCEEDED(fn_CoGetInterfaceAndReleaseStream((LPSTREAM)stream, &IID_IUnknown_a,
                                                            &unmarshaled)) &&
                unmarshaled) {
                target = unmarshaled;
            } else {
                target = handler;
            }
            /* CoGetInterfaceAndReleaseStream always releases the stream. */
            stream = NULL;
        } else {
            com_release(stream);
            stream = NULL;
        }
    }
    if (target) {
        slots = *(invoke_fn **)target;
        if (slots && slots[3]) slots[3](target, op, status);
    }
    if (unmarshaled) com_release(unmarshaled);
    if (handler) com_release(handler);
    auth_async_Release(op);
}

static HRESULT __stdcall auth_async_Cancel(void *self)
{
    auth_async *op = auth_async_from(self);
    void *to_fire = NULL;
    void *to_stream = NULL;
    DWORD tid = 0;
    EnterCriticalSection(&op->lock);
    if (op->status == AUTH_ASYNC_STARTED) {
        op->status = AUTH_ASYNC_CANCELED;
        op->error = E_ABORT;
        if (op->handler && !op->fired) {
            op->fired = 1;
            to_fire = op->handler;
            com_addref(to_fire);
            to_stream = op->handler_stream;
            op->handler_stream = NULL;
            tid = op->handler_tid;
        }
    }
    LeaveCriticalSection(&op->lock);
    if (to_fire) auth_async_invoke(op, to_fire, to_stream, tid, AUTH_ASYNC_CANCELED);
    return S_OK;
}

static HRESULT __stdcall auth_async_Close(void *self)  { (void)self; return S_OK; }

static HRESULT __stdcall auth_async_put_Completed(void *self, void *handler)
{
    auth_async *op = auth_async_from(self);
    void *to_fire = NULL;
    void *stream = NULL;
    int status = AUTH_ASYNC_STARTED;
    DWORD tid = GetCurrentThreadId();
    if (!handler) return E_INVALIDARG;
    if (!handler_is_agile(handler)) {
        auth_ole_init();
        if (fn_CoMarshalInterThreadInterfaceInStream) {
            LPSTREAM st = NULL;
            if (SUCCEEDED(fn_CoMarshalInterThreadInterfaceInStream(&IID_IUnknown_a,
                                                                   (LPUNKNOWN)handler, &st))) {
                stream = st;
            }
        }
    }
    EnterCriticalSection(&op->lock);
    if (op->handler) {
        LeaveCriticalSection(&op->lock);
        if (stream) com_release(stream);
        return E_ILLEGAL_DELEGATE_ASSIGNMENT;
    }
    com_addref(handler);
    op->handler = handler;
    op->handler_tid = tid;
    if (op->status != AUTH_ASYNC_STARTED && !op->fired) {
        op->fired = 1;
        status = op->status;
        to_fire = handler;
        com_addref(to_fire);
    } else {
        op->handler_stream = stream;
        stream = NULL;
    }
    LeaveCriticalSection(&op->lock);
    if (stream) com_release(stream);
    if (to_fire) auth_async_invoke(op, to_fire, NULL, tid, status);
    return S_OK;
}

static HRESULT __stdcall auth_async_get_Completed(void *self, void **out)
{
    auth_async *op = auth_async_from(self);
    if (!out) return E_POINTER;
    EnterCriticalSection(&op->lock);
    *out = op->handler;
    if (op->handler) com_addref(op->handler);
    LeaveCriticalSection(&op->lock);
    return S_OK;
}

static HRESULT __stdcall auth_async_GetResults(void *self, void **out)
{
    auth_async *op = auth_async_from(self);
    HRESULT hr;
    if (!out) return E_POINTER;
    EnterCriticalSection(&op->lock);
    *out = op->result;
    if (op->result) com_addref(op->result);
    hr = op->error;
    LeaveCriticalSection(&op->lock);
    return hr;
}

static HRESULT __stdcall auth_async_action_GetResults(void *self)
{
    auth_async *op = auth_async_from(self);
    HRESULT hr;
    EnterCriticalSection(&op->lock);
    hr = op->error;
    LeaveCriticalSection(&op->lock);
    return hr;
}

static const void *auth_async_info_vtbl[] = {
    (const void *)auth_async_QI, (const void *)auth_async_AddRef, (const void *)auth_async_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)auth_async_get_Id, (const void *)auth_async_get_Status,
    (const void *)auth_async_get_ErrorCode, (const void *)auth_async_Cancel, (const void *)auth_async_Close,
};

static const void *auth_async_action_vtbl[] = {
    (const void *)auth_async_QI, (const void *)auth_async_AddRef, (const void *)auth_async_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)auth_async_put_Completed, (const void *)auth_async_get_Completed,
    (const void *)auth_async_action_GetResults,
};

static const void *auth_async_op_vtbl[] = {
    (const void *)auth_async_QI, (const void *)auth_async_AddRef, (const void *)auth_async_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)auth_async_put_Completed, (const void *)auth_async_get_Completed,
    (const void *)auth_async_GetResults,
};

static auth_async *auth_async_create_iid(int is_op, const GUID *op_iid, int started, HRESULT error,
                                         void *result)
{
    auth_async *op = (auth_async *)calloc(1, sizeof(auth_async));
    if (!op) return NULL;
    InitializeCriticalSection(&op->lock);
    op->base.vtbl = is_op ? auth_async_op_vtbl : auth_async_action_vtbl;
    op->base.refs = 1;
    op->base.name = is_op ? "Windows.Foundation.IAsyncOperation" : "Windows.Foundation.IAsyncAction";
    op->info.vtbl = auth_async_info_vtbl;
    op->info.refs = 1;
    op->info.permanent = 1;
    op->info.name = "Windows.Foundation.IAsyncInfo";
    op->op_iid = is_op ? op_iid : &IID_IAsyncAction_a;
    op->is_operation = is_op;
    op->result = result; /* adopts caller's ref */
    op->error = error;
    op->status = started ? AUTH_ASYNC_STARTED : (FAILED(error) ? AUTH_ASYNC_ERROR : AUTH_ASYNC_COMPLETED);
    return op;
}

static void auth_async_complete(auth_async *op, HRESULT hr, void *result)
{
    void *to_fire = NULL;
    void *to_stream = NULL;
    DWORD tid = 0;
    int status = FAILED(hr) ? AUTH_ASYNC_ERROR : AUTH_ASYNC_COMPLETED;
    int drop_result = 0;
    EnterCriticalSection(&op->lock);
    if (op->status != AUTH_ASYNC_STARTED) {
        drop_result = 1;
    } else {
        op->result = result; /* adopts ref */
        op->error = hr;
        op->status = status;
        if (op->handler && !op->fired) {
            op->fired = 1;
            to_fire = op->handler;
            com_addref(to_fire);
            to_stream = op->handler_stream;
            op->handler_stream = NULL;
            tid = op->handler_tid;
        }
    }
    LeaveCriticalSection(&op->lock);
    if (drop_result) {
        if (result) com_release(result);
        return;
    }
    if (to_fire) auth_async_invoke(op, to_fire, to_stream, tid, status);
}

static HRESULT auth_async_done_iid(const GUID *op_iid, HRESULT hr, void *result, void **out)
{
    auth_async *op;
    if (!out) {
        if (result) com_release(result);
        return E_POINTER;
    }
    op = auth_async_create_iid(1, op_iid, 0, hr, result);
    if (!op) {
        if (result) com_release(result);
        return E_OUTOFMEMORY;
    }
    *out = op;
    return S_OK;
}

/* --------------------------------------------------------------- String Map (IMap/IMapView/IIterable<KVP>) */

typedef struct {
    char *key;
    char *val;
} str_pair;

typedef struct {
    auth_obj base;     /* IMap<HSTRING, HSTRING> */
    auth_obj view;     /* IMapView<HSTRING, HSTRING> */
    auth_obj iterable; /* IIterable<IKeyValuePair<HSTRING, HSTRING>> */
    CRITICAL_SECTION lock;
    str_pair *items;
    UINT32 count;
    UINT32 cap;
} str_map;

static const void *str_map_vtbl[];
static const void *str_map_view_vtbl[];
static const void *str_map_iter_able_vtbl[];

static str_map *str_map_from(void *self)
{
    auth_obj *h = (auth_obj *)self;
    if (h && h->vtbl == str_map_view_vtbl)
        return (str_map *)((char *)self - offsetof(str_map, view));
    if (h && h->vtbl == str_map_iter_able_vtbl)
        return (str_map *)((char *)self - offsetof(str_map, iterable));
    return (str_map *)self;
}

static ULONG __stdcall str_map_AddRef(void *self)
{
    return (ULONG)InterlockedIncrement(&str_map_from(self)->base.refs);
}

static ULONG __stdcall str_map_Release(void *self)
{
    str_map *m = str_map_from(self);
    LONG left = InterlockedDecrement(&m->base.refs);
    if (left <= 0) {
        UINT32 i;
        for (i = 0; i < m->count; i++) {
            free(m->items[i].key);
            free(m->items[i].val);
        }
        free(m->items);
        DeleteCriticalSection(&m->lock);
        free(m);
    }
    return (ULONG)left;
}

static HRESULT __stdcall str_map_QI(void *self, const GUID *id, void **out)
{
    str_map *m = str_map_from(self);
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IMapStrStr)) {
        *out = &m->base;
        str_map_AddRef(m);
        return S_OK;
    }
    if (IsEqualIID(id, &IID_IMapViewStrStr)) {
        *out = &m->view;
        str_map_AddRef(m);
        return S_OK;
    }
    if (IsEqualIID(id, &IID_IIterableKvpSS)) {
        *out = &m->iterable;
        str_map_AddRef(m);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

/* IKeyValuePair<HSTRING, HSTRING> */
typedef struct {
    auth_obj base;
    char *key;
    char *val;
} str_kvp;

static ULONG __stdcall str_kvp_Release(void *self)
{
    str_kvp *p = (str_kvp *)self;
    LONG left = InterlockedDecrement(&p->base.refs);
    if (left <= 0) {
        free(p->key);
        free(p->val);
        free(p);
    }
    return (ULONG)left;
}

static HRESULT __stdcall str_kvp_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IKvpStrStr)) {
        *out = self;
        obj_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall str_kvp_get_Key(void *self, hstring_t *out)
{
    str_kvp *p = (str_kvp *)self;
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(p->key);
    return S_OK;
}

static HRESULT __stdcall str_kvp_get_Value(void *self, hstring_t *out)
{
    str_kvp *p = (str_kvp *)self;
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(p->val);
    return S_OK;
}

static const void *str_kvp_vtbl[] = {
    (const void *)str_kvp_QI, (const void *)obj_AddRef, (const void *)str_kvp_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)str_kvp_get_Key, (const void *)str_kvp_get_Value,
};

static str_kvp *str_kvp_new(const char *k, const char *v)
{
    str_kvp *p = (str_kvp *)calloc(1, sizeof(str_kvp));
    if (!p) return NULL;
    p->base.vtbl = str_kvp_vtbl;
    p->base.refs = 1;
    p->base.name = "Windows.Foundation.Collections.IKeyValuePair";
    p->key = strdup(k ? k : "");
    p->val = strdup(v ? v : "");
    return p;
}

/* IIterator<IKeyValuePair<HSTRING, HSTRING>> */
typedef struct {
    auth_obj base;
    str_map *map;
    UINT32 index;
} str_map_iter;

static ULONG __stdcall str_map_iter_Release(void *self)
{
    str_map_iter *it = (str_map_iter *)self;
    LONG left = InterlockedDecrement(&it->base.refs);
    if (left <= 0) {
        if (it->map) str_map_Release(it->map);
        free(it);
    }
    return (ULONG)left;
}

static HRESULT __stdcall str_map_iter_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IIteratorKvpSS)) {
        *out = self;
        obj_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall str_map_iter_get_Current(void *self, void **out)
{
    str_map_iter *it = (str_map_iter *)self;
    str_kvp *kvp = NULL;
    if (!out) return E_POINTER;
    EnterCriticalSection(&it->map->lock);
    if (it->index < it->map->count)
        kvp = str_kvp_new(it->map->items[it->index].key, it->map->items[it->index].val);
    LeaveCriticalSection(&it->map->lock);
    if (!kvp) { *out = NULL; return E_BOUNDS; }
    *out = kvp;
    return S_OK;
}

static HRESULT __stdcall str_map_iter_get_HasCurrent(void *self, BOOLEAN *out)
{
    str_map_iter *it = (str_map_iter *)self;
    if (!out) return E_POINTER;
    EnterCriticalSection(&it->map->lock);
    *out = (it->index < it->map->count) ? TRUE : FALSE;
    LeaveCriticalSection(&it->map->lock);
    return S_OK;
}

static HRESULT __stdcall str_map_iter_MoveNext(void *self, BOOLEAN *out)
{
    str_map_iter *it = (str_map_iter *)self;
    EnterCriticalSection(&it->map->lock);
    if (it->index < it->map->count) it->index++;
    if (out) *out = (it->index < it->map->count) ? TRUE : FALSE;
    LeaveCriticalSection(&it->map->lock);
    return S_OK;
}

static HRESULT __stdcall str_map_iter_GetMany(void *self, UINT32 cap, void **items, UINT32 *actual)
{
    str_map_iter *it = (str_map_iter *)self;
    UINT32 n = 0;
    EnterCriticalSection(&it->map->lock);
    while (n < cap && it->index < it->map->count) {
        items[n++] = str_kvp_new(it->map->items[it->index].key, it->map->items[it->index].val);
        it->index++;
    }
    LeaveCriticalSection(&it->map->lock);
    if (actual) *actual = n;
    return S_OK;
}

static const void *str_map_iter_vtbl[] = {
    (const void *)str_map_iter_QI, (const void *)obj_AddRef, (const void *)str_map_iter_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)str_map_iter_get_Current, (const void *)str_map_iter_get_HasCurrent,
    (const void *)str_map_iter_MoveNext, (const void *)str_map_iter_GetMany,
};

/* Returns 1 if an existing entry was replaced, 0 if inserted, or -1 on OOM. */
static int str_map_set_utf8(str_map *m, const char *k, const char *v)
{
    UINT32 i;
    int res = 0;
    if (!m || !k) return -1;
    if (!v) v = "";
    EnterCriticalSection(&m->lock);
    for (i = 0; i < m->count; i++) {
        if (strcmp(m->items[i].key, k) == 0) {
            char *nv = strdup(v);
            if (!nv) {
                LeaveCriticalSection(&m->lock);
                return -1;
            }
            free(m->items[i].val);
            m->items[i].val = nv;
            LeaveCriticalSection(&m->lock);
            return 1;
        }
    }
    {
        char *nk = strdup(k);
        char *nv = strdup(v);
        if (!nk || !nv) {
            free(nk);
            free(nv);
            LeaveCriticalSection(&m->lock);
            return -1;
        }
        if (m->count == m->cap) {
            UINT32 ncap = m->cap ? m->cap * 2 : 16;
            str_pair *ni = (str_pair *)realloc(m->items, ncap * sizeof(str_pair));
            if (!ni) {
                free(nk);
                free(nv);
                LeaveCriticalSection(&m->lock);
                return -1;
            }
            m->items = ni;
            m->cap = ncap;
        }
        m->items[m->count].key = nk;
        m->items[m->count].val = nv;
        m->count++;
    }
    LeaveCriticalSection(&m->lock);
    return res;
}

static char *str_map_get_utf8(str_map *m, const char *k)
{
    UINT32 i;
    char *out = NULL;
    if (!m || !k) return NULL;
    EnterCriticalSection(&m->lock);
    for (i = 0; i < m->count; i++) {
        if (_stricmp(m->items[i].key, k) == 0) {
            out = strdup(m->items[i].val);
            break;
        }
    }
    LeaveCriticalSection(&m->lock);
    return out;
}

static HRESULT __stdcall str_map_Lookup(void *self, hstring_t key, hstring_t *out)
{
    str_map *m = str_map_from(self);
    char *k;
    UINT32 i;
    if (!out) return E_POINTER;
    *out = NULL;
    k = hstr_to_utf8(key);
    if (!k) return E_OUTOFMEMORY;
    EnterCriticalSection(&m->lock);
    for (i = 0; i < m->count; i++) {
        if (strcmp(m->items[i].key, k) == 0) {
            *out = hstr_from_utf8(m->items[i].val);
            LeaveCriticalSection(&m->lock);
            free(k);
            return S_OK;
        }
    }
    LeaveCriticalSection(&m->lock);
    free(k);
    return E_BOUNDS;
}

static HRESULT __stdcall str_map_get_Size(void *self, UINT32 *out)
{
    str_map *m = str_map_from(self);
    if (!out) return E_POINTER;
    EnterCriticalSection(&m->lock);
    *out = m->count;
    LeaveCriticalSection(&m->lock);
    return S_OK;
}

static HRESULT __stdcall str_map_HasKey(void *self, hstring_t key, BOOLEAN *out)
{
    str_map *m = str_map_from(self);
    char *k;
    UINT32 i;
    BOOLEAN found = FALSE;
    if (!out) return E_POINTER;
    k = hstr_to_utf8(key);
    if (!k) return E_OUTOFMEMORY;
    EnterCriticalSection(&m->lock);
    for (i = 0; i < m->count; i++) {
        if (strcmp(m->items[i].key, k) == 0) { found = TRUE; break; }
    }
    LeaveCriticalSection(&m->lock);
    free(k);
    *out = found;
    return S_OK;
}

static HRESULT __stdcall str_map_GetView(void *self, void **out)
{
    str_map *m = str_map_from(self);
    if (!out) return E_POINTER;
    *out = &m->view;
    str_map_AddRef(m);
    return S_OK;
}

static HRESULT __stdcall str_map_Insert(void *self, hstring_t key, hstring_t val, BOOLEAN *replaced)
{
    str_map *m = str_map_from(self);
    char *k = hstr_to_utf8(key);
    char *v = hstr_to_utf8(val);
    int rep;
    if (!k || !v) {
        free(k);
        free(v);
        return E_OUTOFMEMORY;
    }
    rep = str_map_set_utf8(m, k, v);
    free(k);
    free(v);
    if (rep < 0) return E_OUTOFMEMORY;
    if (replaced) *replaced = rep ? TRUE : FALSE;
    return S_OK;
}

static HRESULT __stdcall str_map_Remove(void *self, hstring_t key)
{
    str_map *m = str_map_from(self);
    char *k = hstr_to_utf8(key);
    UINT32 i;
    HRESULT hr = E_BOUNDS;
    EnterCriticalSection(&m->lock);
    for (i = 0; i < m->count; i++) {
        if (k && strcmp(m->items[i].key, k) == 0) {
            free(m->items[i].key);
            free(m->items[i].val);
            if (i + 1 < m->count)
                memmove(&m->items[i], &m->items[i + 1], (m->count - i - 1) * sizeof(str_pair));
            m->count--;
            hr = S_OK;
            break;
        }
    }
    LeaveCriticalSection(&m->lock);
    free(k);
    return hr;
}

static HRESULT __stdcall str_map_Clear(void *self)
{
    str_map *m = str_map_from(self);
    UINT32 i;
    EnterCriticalSection(&m->lock);
    for (i = 0; i < m->count; i++) {
        free(m->items[i].key);
        free(m->items[i].val);
    }
    m->count = 0;
    LeaveCriticalSection(&m->lock);
    return S_OK;
}

static HRESULT __stdcall str_map_Split(void *self, void **first, void **second)
{
    (void)self;
    if (first) *first = NULL;
    if (second) *second = NULL;
    return S_OK;
}

static HRESULT __stdcall str_map_First(void *self, void **out)
{
    str_map *m = str_map_from(self);
    str_map_iter *it;
    if (!out) return E_POINTER;
    it = (str_map_iter *)calloc(1, sizeof(str_map_iter));
    if (!it) return E_OUTOFMEMORY;
    it->base.vtbl = str_map_iter_vtbl;
    it->base.refs = 1;
    it->base.name = "Windows.Foundation.Collections.IIterator";
    it->map = m;
    str_map_AddRef(m);
    *out = it;
    return S_OK;
}

static const void *str_map_vtbl[] = {
    (const void *)str_map_QI, (const void *)str_map_AddRef, (const void *)str_map_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)str_map_Lookup, (const void *)str_map_get_Size, (const void *)str_map_HasKey,
    (const void *)str_map_GetView, (const void *)str_map_Insert, (const void *)str_map_Remove,
    (const void *)str_map_Clear,
};

static const void *str_map_view_vtbl[] = {
    (const void *)str_map_QI, (const void *)str_map_AddRef, (const void *)str_map_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)str_map_Lookup, (const void *)str_map_get_Size, (const void *)str_map_HasKey,
    (const void *)str_map_Split,
};

static const void *str_map_iter_able_vtbl[] = {
    (const void *)str_map_QI, (const void *)str_map_AddRef, (const void *)str_map_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)str_map_First,
};

static str_map *str_map_new(void)
{
    str_map *m = (str_map *)calloc(1, sizeof(str_map));
    if (!m) return NULL;
    InitializeCriticalSection(&m->lock);
    m->base.vtbl = str_map_vtbl;
    m->base.refs = 1;
    m->base.name = "Windows.Foundation.Collections.IMap";
    m->view.vtbl = str_map_view_vtbl;
    m->view.refs = 1;
    m->view.permanent = 1;
    m->view.name = "Windows.Foundation.Collections.IMapView";
    m->iterable.vtbl = str_map_iter_able_vtbl;
    m->iterable.refs = 1;
    m->iterable.permanent = 1;
    m->iterable.name = "Windows.Foundation.Collections.IIterable";
    return m;
}

/* --------------------------------------------------------------- Generic IVectorView<T> */

typedef struct {
    auth_obj base;
    auth_obj iterable;
    const GUID *iid_vec;
    const GUID *iid_able;
    const GUID *iid_iter;
    void **items;
    UINT32 count;
} vec_view;

typedef struct {
    auth_obj base;
    vec_view *vec;
    UINT32 index;
} vec_iter;

static const void *vec_view_vtbl[];
static const void *vec_able_vtbl[];

static vec_view *vec_from(void *self)
{
    auth_obj *h = (auth_obj *)self;
    if (h && h->vtbl == vec_able_vtbl)
        return (vec_view *)((char *)self - offsetof(vec_view, iterable));
    return (vec_view *)self;
}

static ULONG __stdcall vec_AddRef(void *self)
{
    return (ULONG)InterlockedIncrement(&vec_from(self)->base.refs);
}

static ULONG __stdcall vec_Release(void *self)
{
    vec_view *v = vec_from(self);
    LONG left = InterlockedDecrement(&v->base.refs);
    if (left <= 0) {
        UINT32 i;
        for (i = 0; i < v->count; i++) com_release(v->items[i]);
        free(v->items);
        free(v);
    }
    return (ULONG)left;
}

static HRESULT __stdcall vec_QI(void *self, const GUID *id, void **out)
{
    vec_view *v = vec_from(self);
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, v->iid_vec)) {
        *out = &v->base;
        vec_AddRef(v);
        return S_OK;
    }
    if (IsEqualIID(id, v->iid_able)) {
        *out = &v->iterable;
        vec_AddRef(v);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG __stdcall vec_iter_Release(void *self)
{
    vec_iter *it = (vec_iter *)self;
    LONG left = InterlockedDecrement(&it->base.refs);
    if (left <= 0) {
        vec_Release(it->vec);
        free(it);
    }
    return (ULONG)left;
}

static HRESULT __stdcall vec_iter_QI(void *self, const GUID *id, void **out)
{
    vec_iter *it = (vec_iter *)self;
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, it->vec->iid_iter)) {
        *out = it;
        obj_AddRef(it);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall vec_iter_get_Current(void *self, void **out)
{
    vec_iter *it = (vec_iter *)self;
    if (!out) return E_POINTER;
    if (it->index >= it->vec->count) { *out = NULL; return E_BOUNDS; }
    *out = it->vec->items[it->index];
    com_addref(*out);
    return S_OK;
}

static HRESULT __stdcall vec_iter_get_HasCurrent(void *self, BOOLEAN *out)
{
    vec_iter *it = (vec_iter *)self;
    if (!out) return E_POINTER;
    *out = (it->index < it->vec->count) ? TRUE : FALSE;
    return S_OK;
}

static HRESULT __stdcall vec_iter_MoveNext(void *self, BOOLEAN *out)
{
    vec_iter *it = (vec_iter *)self;
    if (it->index < it->vec->count) it->index++;
    if (out) *out = (it->index < it->vec->count) ? TRUE : FALSE;
    return S_OK;
}

static HRESULT __stdcall vec_iter_GetMany(void *self, UINT32 cap, void **items, UINT32 *actual)
{
    vec_iter *it = (vec_iter *)self;
    UINT32 n = 0;
    while (n < cap && it->index < it->vec->count) {
        items[n] = it->vec->items[it->index++];
        com_addref(items[n]);
        n++;
    }
    if (actual) *actual = n;
    return S_OK;
}

static const void *vec_iter_vtbl[] = {
    (const void *)vec_iter_QI, (const void *)obj_AddRef, (const void *)vec_iter_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)vec_iter_get_Current, (const void *)vec_iter_get_HasCurrent,
    (const void *)vec_iter_MoveNext, (const void *)vec_iter_GetMany,
};

static HRESULT __stdcall vec_GetAt(void *self, UINT32 index, void **out)
{
    vec_view *v = vec_from(self);
    if (!out) return E_POINTER;
    if (index >= v->count) { *out = NULL; return E_BOUNDS; }
    *out = v->items[index];
    com_addref(*out);
    return S_OK;
}

static HRESULT __stdcall vec_get_Size(void *self, UINT32 *out)
{
    if (!out) return E_POINTER;
    *out = vec_from(self)->count;
    return S_OK;
}

static HRESULT __stdcall vec_IndexOf(void *self, void *item, UINT32 *index, BOOLEAN *found)
{
    vec_view *v = vec_from(self);
    UINT32 i;
    for (i = 0; i < v->count; i++) {
        if (v->items[i] == item) {
            if (index) *index = i;
            if (found) *found = TRUE;
            return S_OK;
        }
    }
    if (index) *index = 0;
    if (found) *found = FALSE;
    return S_OK;
}

static HRESULT __stdcall vec_GetMany(void *self, UINT32 start, UINT32 cap, void **items, UINT32 *actual)
{
    vec_view *v = vec_from(self);
    UINT32 n = 0;
    while (n < cap && start + n < v->count) {
        items[n] = v->items[start + n];
        com_addref(items[n]);
        n++;
    }
    if (actual) *actual = n;
    return S_OK;
}

static HRESULT __stdcall vec_First(void *self, void **out)
{
    vec_view *v = vec_from(self);
    vec_iter *it;
    if (!out) return E_POINTER;
    it = (vec_iter *)calloc(1, sizeof(vec_iter));
    if (!it) return E_OUTOFMEMORY;
    it->base.vtbl = vec_iter_vtbl;
    it->base.refs = 1;
    it->base.name = "Windows.Foundation.Collections.IIterator";
    it->vec = v;
    vec_AddRef(v);
    *out = it;
    return S_OK;
}

static const void *vec_view_vtbl[] = {
    (const void *)vec_QI, (const void *)vec_AddRef, (const void *)vec_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)vec_GetAt, (const void *)vec_get_Size,
    (const void *)vec_IndexOf, (const void *)vec_GetMany,
};

static const void *vec_able_vtbl[] = {
    (const void *)vec_QI, (const void *)vec_AddRef, (const void *)vec_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)vec_First,
};

static vec_view *vec_view_new(const GUID *iid_vec, const GUID *iid_able, const GUID *iid_iter,
                              void *single_item)
{
    vec_view *v = (vec_view *)calloc(1, sizeof(vec_view));
    if (!v) return NULL;
    v->base.vtbl = vec_view_vtbl;
    v->base.refs = 1;
    v->base.name = "Windows.Foundation.Collections.IVectorView";
    v->iterable.vtbl = vec_able_vtbl;
    v->iterable.refs = 1;
    v->iterable.permanent = 1;
    v->iterable.name = "Windows.Foundation.Collections.IIterable";
    v->iid_vec = iid_vec;
    v->iid_able = iid_able;
    v->iid_iter = iid_iter;
    if (single_item) {
        v->items = (void **)malloc(sizeof(void *));
        if (!v->items) {
            free(v);
            return NULL;
        }
        v->items[0] = single_item;
        com_addref(single_item);
        v->count = 1;
    }
    return v;
}

/* --------------------------------------------------------------- Boxed String IPropertyValue */

typedef struct {
    auth_obj base;
    char *value;
} prop_str;

static ULONG __stdcall prop_str_Release(void *self)
{
    prop_str *p = (prop_str *)self;
    LONG left = InterlockedDecrement(&p->base.refs);
    if (left <= 0) {
        free(p->value);
        free(p);
    }
    return (ULONG)left;
}

static HRESULT __stdcall prop_str_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IPropertyValue_a)) {
        *out = self;
        obj_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall prop_str_get_Type(void *self, int *out)
{
    (void)self;
    if (out) *out = PROP_TYPE_STRING;
    return S_OK;
}

static HRESULT __stdcall prop_str_get_IsNumericScalar(void *self, BOOLEAN *out)
{
    (void)self;
    if (out) *out = FALSE;
    return S_OK;
}

static HRESULT __stdcall prop_str_GetString(void *self, hstring_t *out)
{
    prop_str *p = (prop_str *)self;
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(p->value);
    return S_OK;
}

#ifndef TYPE_E_TYPEMISMATCH
#define TYPE_E_TYPEMISMATCH ((HRESULT)0x80028CA0L)
#endif

static HRESULT __stdcall prop_str_TypeMismatch1(void *self, void *out)
{
    (void)self; (void)out;
    return TYPE_E_TYPEMISMATCH;
}

static HRESULT __stdcall prop_str_TypeMismatchArr(void *self, UINT32 *len, void **out)
{
    (void)self;
    if (len) *len = 0;
    if (out) *out = NULL;
    return TYPE_E_TYPEMISMATCH;
}

static const void *prop_str_vtbl[] = {
    (const void *)prop_str_QI, (const void *)obj_AddRef, (const void *)prop_str_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)prop_str_get_Type,          /* 6  get_Type */
    (const void *)prop_str_get_IsNumericScalar,/* 7 get_IsNumericScalar */
    (const void *)prop_str_TypeMismatch1,     /* 8  GetUInt8 */
    (const void *)prop_str_TypeMismatch1,     /* 9  GetInt16 */
    (const void *)prop_str_TypeMismatch1,     /* 10 GetUInt16 */
    (const void *)prop_str_TypeMismatch1,     /* 11 GetInt32 */
    (const void *)prop_str_TypeMismatch1,     /* 12 GetUInt32 */
    (const void *)prop_str_TypeMismatch1,     /* 13 GetInt64 */
    (const void *)prop_str_TypeMismatch1,     /* 14 GetUInt64 */
    (const void *)prop_str_TypeMismatch1,     /* 15 GetSingle */
    (const void *)prop_str_TypeMismatch1,     /* 16 GetDouble */
    (const void *)prop_str_TypeMismatch1,     /* 17 GetChar16 */
    (const void *)prop_str_TypeMismatch1,     /* 18 GetBoolean */
    (const void *)prop_str_GetString,         /* 19 GetString */
    (const void *)prop_str_TypeMismatch1,     /* 20 GetGuid */
    (const void *)prop_str_TypeMismatch1,     /* 21 GetDateTime */
    (const void *)prop_str_TypeMismatch1,     /* 22 GetTimeSpan */
    (const void *)prop_str_TypeMismatch1,     /* 23 GetPoint */
    (const void *)prop_str_TypeMismatch1,     /* 24 GetSize */
    (const void *)prop_str_TypeMismatch1,     /* 25 GetRect */
    (const void *)prop_str_TypeMismatchArr,   /* 26 GetUInt8Array */
    (const void *)prop_str_TypeMismatchArr,   /* 27 GetInt16Array */
    (const void *)prop_str_TypeMismatchArr,   /* 28 GetUInt16Array */
    (const void *)prop_str_TypeMismatchArr,   /* 29 GetInt32Array */
    (const void *)prop_str_TypeMismatchArr,   /* 30 GetUInt32Array */
    (const void *)prop_str_TypeMismatchArr,   /* 31 GetInt64Array */
    (const void *)prop_str_TypeMismatchArr,   /* 32 GetUInt64Array */
    (const void *)prop_str_TypeMismatchArr,   /* 33 GetSingleArray */
    (const void *)prop_str_TypeMismatchArr,   /* 34 GetDoubleArray */
    (const void *)prop_str_TypeMismatchArr,   /* 35 GetChar16Array */
    (const void *)prop_str_TypeMismatchArr,   /* 36 GetBooleanArray */
    (const void *)prop_str_TypeMismatchArr,   /* 37 GetStringArray */
    (const void *)prop_str_TypeMismatchArr,   /* 38 GetInspectableArray */
    (const void *)prop_str_TypeMismatchArr,   /* 39 GetGuidArray */
    (const void *)prop_str_TypeMismatchArr,   /* 40 GetDateTimeArray */
    (const void *)prop_str_TypeMismatchArr,   /* 41 GetTimeSpanArray */
    (const void *)prop_str_TypeMismatchArr,   /* 42 GetPointArray */
    (const void *)prop_str_TypeMismatchArr,   /* 43 GetSizeArray */
    (const void *)prop_str_TypeMismatchArr,   /* 44 GetRectArray */
};

static prop_str *prop_str_new(const char *val)
{
    prop_str *p = (prop_str *)calloc(1, sizeof(prop_str));
    if (!p) return NULL;
    p->base.vtbl = prop_str_vtbl;
    p->base.refs = 1;
    p->base.name = "Windows.Foundation.IPropertyValue";
    p->value = strdup(val ? val : "");
    if (!p->value) {
        free(p);
        return NULL;
    }
    return p;
}

/* --------------------------------------------------------------- Xodus Bridge & Xbox Live XSTS */

typedef struct {
    char rp[256];
    char token[8192];
    char uhs[128];
    char xuid[64];
    char gamertag[128];
    char age_group[64];
    char privileges[512];
    time_t expires;
} xsts_entry;

static INIT_ONCE g_auth_once = INIT_ONCE_STATIC_INIT;
static CRITICAL_SECTION g_auth_lock;
static int g_wsa_ready = 0;

static char g_msa_ticket[8192];
static char g_device_rps[8192];
static time_t g_msa_expiry = 0;
static char g_msa_account_id[128];

static char g_xbl_device_token[8192];
static char g_xbl_title_token[8192];
static char g_xbl_user_token[8192];
static time_t g_xbl_user_expiry = 0;

/* Ephemeral ECDSA P-256 ProofKey for Xbox Live device/SISU/XSTS request signing. */
typedef LONG NTSTATUS_A;
typedef void *BCRYPT_HANDLE_A;
typedef NTSTATUS_A (WINAPI *pfn_BCryptOpenAlgorithmProvider)(BCRYPT_HANDLE_A *, LPCWSTR, LPCWSTR, ULONG);
typedef NTSTATUS_A (WINAPI *pfn_BCryptCloseAlgorithmProvider)(BCRYPT_HANDLE_A, ULONG);
typedef NTSTATUS_A (WINAPI *pfn_BCryptGenerateKeyPair)(BCRYPT_HANDLE_A, BCRYPT_HANDLE_A *, ULONG, ULONG);
typedef NTSTATUS_A (WINAPI *pfn_BCryptFinalizeKeyPair)(BCRYPT_HANDLE_A, ULONG);
typedef NTSTATUS_A (WINAPI *pfn_BCryptExportKey)(BCRYPT_HANDLE_A, BCRYPT_HANDLE_A, LPCWSTR, PUCHAR, ULONG, ULONG *, ULONG);
typedef NTSTATUS_A (WINAPI *pfn_BCryptDestroyKey)(BCRYPT_HANDLE_A);
typedef NTSTATUS_A (WINAPI *pfn_BCryptCreateHash)(BCRYPT_HANDLE_A, BCRYPT_HANDLE_A *, PUCHAR, ULONG, PUCHAR, ULONG, ULONG);
typedef NTSTATUS_A (WINAPI *pfn_BCryptHashData)(BCRYPT_HANDLE_A, PUCHAR, ULONG, ULONG);
typedef NTSTATUS_A (WINAPI *pfn_BCryptFinishHash)(BCRYPT_HANDLE_A, PUCHAR, ULONG, ULONG);
typedef NTSTATUS_A (WINAPI *pfn_BCryptDestroyHash)(BCRYPT_HANDLE_A);
typedef NTSTATUS_A (WINAPI *pfn_BCryptSignHash)(BCRYPT_HANDLE_A, void *, PUCHAR, ULONG, PUCHAR, ULONG, ULONG *, ULONG);

static pfn_BCryptOpenAlgorithmProvider  fn_BCryptOpenAlgorithmProvider;
static pfn_BCryptCloseAlgorithmProvider fn_BCryptCloseAlgorithmProvider;
static pfn_BCryptGenerateKeyPair        fn_BCryptGenerateKeyPair;
static pfn_BCryptFinalizeKeyPair        fn_BCryptFinalizeKeyPair;
static pfn_BCryptExportKey              fn_BCryptExportKey;
static pfn_BCryptDestroyKey             fn_BCryptDestroyKey;
static pfn_BCryptCreateHash             fn_BCryptCreateHash;
static pfn_BCryptHashData               fn_BCryptHashData;
static pfn_BCryptFinishHash             fn_BCryptFinishHash;
static pfn_BCryptDestroyHash            fn_BCryptDestroyHash;
static pfn_BCryptSignHash               fn_BCryptSignHash;
static int g_bcrypt_ready = 0;

static BCRYPT_HANDLE_A g_ecdsa_alg = NULL;
static BCRYPT_HANDLE_A g_sha256_alg = NULL;
static BCRYPT_HANDLE_A g_proof_key = NULL;
static char g_proof_key_json[256];

static xsts_entry g_xsts_cache[16];
static int g_xsts_count = 0;

/* Cached identity strings (populated once any XSTS token is fetched) */
static char g_cached_xuid[64];
static char g_cached_gamertag[128];
static char g_cached_non_roamable_id[128];

/* Lazy-resolved ws2_32.dll and wininet.dll exports (keeps uwp-host.dll static imports minimal). */
typedef int (WSAAPI *pfn_WSAStartup)(WORD, LPWSADATA);
typedef int (WSAAPI *pfn_WSAGetLastError)(void);
typedef SOCKET (WSAAPI *pfn_socket)(int, int, int);
typedef int (WSAAPI *pfn_setsockopt)(SOCKET, int, int, const char *, int);
typedef int (WSAAPI *pfn_connect)(SOCKET, const struct sockaddr *, int);
typedef int (WSAAPI *pfn_send)(SOCKET, const char *, int, int);
typedef int (WSAAPI *pfn_recv)(SOCKET, char *, int, int);
typedef int (WSAAPI *pfn_closesocket)(SOCKET);

typedef HINTERNET (WINAPI *pfn_InternetOpenW)(LPCWSTR, DWORD, LPCWSTR, LPCWSTR, DWORD);
typedef HINTERNET (WINAPI *pfn_InternetConnectW)(HINTERNET, LPCWSTR, INTERNET_PORT, LPCWSTR, LPCWSTR, DWORD, DWORD, DWORD_PTR);
typedef HINTERNET (WINAPI *pfn_HttpOpenRequestW)(HINTERNET, LPCWSTR, LPCWSTR, LPCWSTR, LPCWSTR, LPCWSTR *, DWORD, DWORD_PTR);
typedef BOOL (WINAPI *pfn_HttpSendRequestW)(HINTERNET, LPCWSTR, DWORD, LPVOID, DWORD);
typedef BOOL (WINAPI *pfn_HttpQueryInfoW)(HINTERNET, DWORD, LPVOID, LPDWORD, LPDWORD);
typedef BOOL (WINAPI *pfn_InternetReadFile)(HINTERNET, LPVOID, DWORD, LPDWORD);
typedef BOOL (WINAPI *pfn_InternetCloseHandle)(HINTERNET);
typedef BOOL (WINAPI *pfn_InternetSetOptionW)(HINTERNET, DWORD, LPVOID, DWORD);

static pfn_WSAStartup       fn_WSAStartup;
static pfn_WSAGetLastError  fn_WSAGetLastError;
static pfn_socket           fn_socket;
static pfn_setsockopt       fn_setsockopt;
static pfn_connect          fn_connect;
static pfn_send             fn_send;
static pfn_recv             fn_recv;
static pfn_closesocket      fn_closesocket;

static pfn_InternetOpenW        fn_InternetOpenW;
static pfn_InternetConnectW     fn_InternetConnectW;
static pfn_HttpOpenRequestW     fn_HttpOpenRequestW;
static pfn_HttpSendRequestW     fn_HttpSendRequestW;
static pfn_HttpQueryInfoW       fn_HttpQueryInfoW;
static pfn_InternetReadFile     fn_InternetReadFile;
static pfn_InternetCloseHandle  fn_InternetCloseHandle;
static pfn_InternetSetOptionW   fn_InternetSetOptionW;
static int g_inet_ready = 0;

static BOOL CALLBACK init_auth_lock_cb(PINIT_ONCE once, PVOID param, PVOID *ctx)
{
    HMODULE ws2, inet;
    WSADATA wsa;
    (void)once; (void)param; (void)ctx;
    InitializeCriticalSection(&g_auth_lock);
    ws2 = GetModuleHandleA("ws2_32.dll");
    if (!ws2) ws2 = LoadLibraryA("ws2_32.dll");
    if (ws2) {
        fn_WSAStartup      = (pfn_WSAStartup)(void *)GetProcAddress(ws2, "WSAStartup");
        fn_WSAGetLastError = (pfn_WSAGetLastError)(void *)GetProcAddress(ws2, "WSAGetLastError");
        fn_socket          = (pfn_socket)(void *)GetProcAddress(ws2, "socket");
        fn_setsockopt      = (pfn_setsockopt)(void *)GetProcAddress(ws2, "setsockopt");
        fn_connect         = (pfn_connect)(void *)GetProcAddress(ws2, "connect");
        fn_send            = (pfn_send)(void *)GetProcAddress(ws2, "send");
        fn_recv            = (pfn_recv)(void *)GetProcAddress(ws2, "recv");
        fn_closesocket     = (pfn_closesocket)(void *)GetProcAddress(ws2, "closesocket");
        if (fn_WSAStartup && fn_socket && fn_connect && fn_send && fn_recv && fn_closesocket) {
            if (fn_WSAStartup(MAKEWORD(2, 2), &wsa) == 0) g_wsa_ready = 1;
        }
    }
    inet = GetModuleHandleA("wininet.dll");
    if (!inet) inet = LoadLibraryA("wininet.dll");
    if (inet) {
        fn_InternetOpenW       = (pfn_InternetOpenW)(void *)GetProcAddress(inet, "InternetOpenW");
        fn_InternetConnectW    = (pfn_InternetConnectW)(void *)GetProcAddress(inet, "InternetConnectW");
        fn_HttpOpenRequestW    = (pfn_HttpOpenRequestW)(void *)GetProcAddress(inet, "HttpOpenRequestW");
        fn_HttpSendRequestW    = (pfn_HttpSendRequestW)(void *)GetProcAddress(inet, "HttpSendRequestW");
        fn_HttpQueryInfoW      = (pfn_HttpQueryInfoW)(void *)GetProcAddress(inet, "HttpQueryInfoW");
        fn_InternetReadFile    = (pfn_InternetReadFile)(void *)GetProcAddress(inet, "InternetReadFile");
        fn_InternetCloseHandle = (pfn_InternetCloseHandle)(void *)GetProcAddress(inet, "InternetCloseHandle");
        fn_InternetSetOptionW  = (pfn_InternetSetOptionW)(void *)GetProcAddress(inet, "InternetSetOptionW");
        if (fn_InternetOpenW && fn_InternetConnectW && fn_HttpOpenRequestW &&
            fn_HttpSendRequestW && fn_HttpQueryInfoW && fn_InternetReadFile && fn_InternetCloseHandle)
            g_inet_ready = 1;
    }
    {
        HMODULE bc = GetModuleHandleA("bcrypt.dll");
        if (!bc) bc = LoadLibraryA("bcrypt.dll");
        if (bc) {
            fn_BCryptOpenAlgorithmProvider  = (pfn_BCryptOpenAlgorithmProvider)(void *)GetProcAddress(bc, "BCryptOpenAlgorithmProvider");
            fn_BCryptCloseAlgorithmProvider = (pfn_BCryptCloseAlgorithmProvider)(void *)GetProcAddress(bc, "BCryptCloseAlgorithmProvider");
            fn_BCryptGenerateKeyPair        = (pfn_BCryptGenerateKeyPair)(void *)GetProcAddress(bc, "BCryptGenerateKeyPair");
            fn_BCryptFinalizeKeyPair        = (pfn_BCryptFinalizeKeyPair)(void *)GetProcAddress(bc, "BCryptFinalizeKeyPair");
            fn_BCryptExportKey              = (pfn_BCryptExportKey)(void *)GetProcAddress(bc, "BCryptExportKey");
            fn_BCryptDestroyKey             = (pfn_BCryptDestroyKey)(void *)GetProcAddress(bc, "BCryptDestroyKey");
            fn_BCryptCreateHash             = (pfn_BCryptCreateHash)(void *)GetProcAddress(bc, "BCryptCreateHash");
            fn_BCryptHashData               = (pfn_BCryptHashData)(void *)GetProcAddress(bc, "BCryptHashData");
            fn_BCryptFinishHash             = (pfn_BCryptFinishHash)(void *)GetProcAddress(bc, "BCryptFinishHash");
            fn_BCryptDestroyHash            = (pfn_BCryptDestroyHash)(void *)GetProcAddress(bc, "BCryptDestroyHash");
            fn_BCryptSignHash               = (pfn_BCryptSignHash)(void *)GetProcAddress(bc, "BCryptSignHash");
            if (fn_BCryptOpenAlgorithmProvider && fn_BCryptGenerateKeyPair && fn_BCryptFinalizeKeyPair &&
                fn_BCryptExportKey && fn_BCryptCreateHash && fn_BCryptHashData &&
                fn_BCryptFinishHash && fn_BCryptDestroyHash && fn_BCryptSignHash)
                g_bcrypt_ready = 1;
        }
    }
    auth_log("auth init wsa=%d wininet=%d bcrypt=%d", g_wsa_ready, g_inet_ready, g_bcrypt_ready);
    return TRUE;
}

static void auth_init_once(void)
{
    InitOnceExecuteOnce(&g_auth_once, init_auth_lock_cb, NULL, NULL);
}

static int sock_send_all(SOCKET s, const void *buf, size_t len)
{
    const char *p = (const char *)buf;
    size_t sent = 0;
    while (sent < len) {
        int n = fn_send(s, p + sent, (int)(len - sent), 0);
        if (n <= 0) return 0;
        sent += (size_t)n;
    }
    return 1;
}

static int sock_recv_all(SOCKET s, void *buf, size_t len)
{
    char *p = (char *)buf;
    size_t got = 0;
    while (got < len) {
        int n = fn_recv(s, p + got, (int)(len - got), 0);
        if (n <= 0) return 0;
        got += (size_t)n;
    }
    return 1;
}

/* Extract <Tag>...</Tag> from XML response with basic entity unescaping. */
static int xml_extract(const char *xml, const char *tag, char *out, size_t cap)
{
    char open_t[96], close_t[96];
    const char *start, *end, *p;
    size_t w = 0;
    if (!xml || !tag || !out || cap == 0) return 0;
    out[0] = '\0';
    snprintf(open_t, sizeof open_t, "<%s>", tag);
    snprintf(close_t, sizeof close_t, "</%s>", tag);
    start = strstr(xml, open_t);
    if (!start) return 0;
    start += strlen(open_t);
    end = strstr(start, close_t);
    if (!end) return 0;
    for (p = start; p < end && w + 1 < cap; ) {
        if (*p == '&') {
            if (strncmp(p, "&amp;", 5) == 0)  { out[w++] = '&'; p += 5; continue; }
            if (strncmp(p, "&lt;", 4) == 0)   { out[w++] = '<'; p += 4; continue; }
            if (strncmp(p, "&gt;", 4) == 0)   { out[w++] = '>'; p += 4; continue; }
            if (strncmp(p, "&quot;", 6) == 0) { out[w++] = '"'; p += 6; continue; }
            if (strncmp(p, "&apos;", 6) == 0) { out[w++] = '\''; p += 6; continue; }
        }
        out[w++] = *p++;
    }
    out[w] = '\0';
    return 1;
}

/* Match xodus::models::secrets::xbox_ticket: strip "d=" only for 5-part compact JWE. */
static const char *normalize_xbox_rps_ticket(const char *ticket)
{
    if (strncmp(ticket, "d=", 2) == 0) {
        const char *p = ticket + 2;
        int dots = 0;
        while (*p) {
            if (*p == '.') dots++;
            p++;
        }
        if (dots == 4) return ticket + 2;
    }
    return ticket;
}

/* Fetch MSA RPS ticket from DroidDeck's live xodus-service via XODUS_BRIDGE_PORT/KEY. */
static int fetch_msa_ticket_locked(int force_refresh)
{
    char port_str[32], key[256];
    DWORD port_len, key_len;
    int port;
    SOCKET s = INVALID_SOCKET;
    struct sockaddr_in addr;
    DWORD timeout_ms = 20000;
    const char *req_xml =
        "<MSATokenRequest><ClientId>" XODUS_XBOX_CLIENT_ID "</ClientId>"
        "<AllowUi>false</AllowUi><MSAFullTrust>false</MSAFullTrust></MSATokenRequest>";
    uint8_t hdr[8];
    uint32_t magic;
    uint16_t mtype, msize;
    char *resp = NULL;
    char exp_str[64];
    time_t now = time(NULL);

    if (!force_refresh && g_msa_ticket[0] && now + 120 < g_msa_expiry)
        return 1;

    port_len = GetEnvironmentVariableA("XODUS_BRIDGE_PORT", port_str, sizeof port_str);
    key_len = GetEnvironmentVariableA("XODUS_BRIDGE_KEY", key, sizeof key);
    if (!port_len || port_len >= sizeof port_str || !key_len || key_len >= sizeof key) {
        auth_log("XODUS_BRIDGE_PORT/KEY not set in environment");
        return 0;
    }
    port = atoi(port_str);
    if (port <= 0 || port > 65535 || !g_wsa_ready) {
        auth_log("invalid bridge port=%d wsa=%d", port, g_wsa_ready);
        return 0;
    }

    s = fn_socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) {
        auth_log("socket() failed err=%d", fn_WSAGetLastError ? fn_WSAGetLastError() : -1);
        return 0;
    }
    if (fn_setsockopt) {
        fn_setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char *)&timeout_ms, sizeof timeout_ms);
        fn_setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, (const char *)&timeout_ms, sizeof timeout_ms);
    }

    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_port = (u_short)(((port & 0xff) << 8) | ((port >> 8) & 0xff));
    addr.sin_addr.s_addr = 0x0100007fU; /* 127.0.0.1 in network byte order */
    if (fn_connect(s, (struct sockaddr *)&addr, sizeof addr) != 0) {
        auth_log("connect(127.0.0.1:%d) failed err=%d", port, fn_WSAGetLastError ? fn_WSAGetLastError() : -1);
        fn_closesocket(s);
        return 0;
    }

    /* Bridge handshake: raw ASCII key */
    if (!sock_send_all(s, key, key_len)) {
        auth_log("bridge key send failed");
        fn_closesocket(s);
        return 0;
    }

    /* Frame header: magic(u32 LE) + type(u16 LE) + size(u16 LE) */
    msize = (uint16_t)strlen(req_xml);
    hdr[0] = (uint8_t)(XODUS_XML_MAGIC & 0xff);
    hdr[1] = (uint8_t)((XODUS_XML_MAGIC >> 8) & 0xff);
    hdr[2] = (uint8_t)((XODUS_XML_MAGIC >> 16) & 0xff);
    hdr[3] = (uint8_t)((XODUS_XML_MAGIC >> 24) & 0xff);
    hdr[4] = (uint8_t)(XODUS_MSA_TOKEN_REQ & 0xff);
    hdr[5] = (uint8_t)((XODUS_MSA_TOKEN_REQ >> 8) & 0xff);
    hdr[6] = (uint8_t)(msize & 0xff);
    hdr[7] = (uint8_t)((msize >> 8) & 0xff);

    if (!sock_send_all(s, hdr, 8) || !sock_send_all(s, req_xml, msize)) {
        auth_log("MsaTokenRequest send failed");
        fn_closesocket(s);
        return 0;
    }

    if (!sock_recv_all(s, hdr, 8)) {
        auth_log("MsaTokenResponse header recv failed");
        fn_closesocket(s);
        return 0;
    }
    magic = (uint32_t)hdr[0] | ((uint32_t)hdr[1] << 8) | ((uint32_t)hdr[2] << 16) | ((uint32_t)hdr[3] << 24);
    mtype = (uint16_t)hdr[4] | ((uint16_t)hdr[5] << 8);
    msize = (uint16_t)hdr[6] | ((uint16_t)hdr[7] << 8);
    if (magic != XODUS_XML_MAGIC || mtype != XODUS_MSA_TOKEN_RESP || msize == 0) {
        auth_log("MsaTokenResponse bad header magic=0x%08x type=%u size=%u", magic, mtype, msize);
        fn_closesocket(s);
        return 0;
    }

    resp = (char *)malloc((size_t)msize + 1);
    if (!resp) { fn_closesocket(s); return 0; }
    if (!sock_recv_all(s, resp, msize)) {
        auth_log("MsaTokenResponse body recv failed");
        free(resp);
        fn_closesocket(s);
        return 0;
    }
    resp[msize] = '\0';
    fn_closesocket(s);

    if (!xml_extract(resp, "Token", g_msa_ticket, sizeof g_msa_ticket) || !g_msa_ticket[0]) {
        auth_log("MsaTokenResponse missing <Token>");
        free(resp);
        return 0;
    }
    g_device_rps[0] = '\0';
    xml_extract(resp, "DeviceRps", g_device_rps, sizeof g_device_rps);
    if (xml_extract(resp, "Expiry", exp_str, sizeof exp_str) && exp_str[0])
        g_msa_expiry = (time_t)_strtoi64(exp_str, NULL, 10);
    else
        g_msa_expiry = now + 3600;
    xml_extract(resp, "XboxMsaAccountId", g_msa_account_id, sizeof g_msa_account_id);
    if (g_msa_account_id[0] && !g_cached_non_roamable_id[0])
        snprintf(g_cached_non_roamable_id, sizeof g_cached_non_roamable_id, "%s", g_msa_account_id);

    free(resp);
    auth_log("acquired MSA ticket from xodus-service (len=%u device_rps=%u exp=%lld)",
             (unsigned)strlen(g_msa_ticket), (unsigned)strlen(g_device_rps), (long long)g_msa_expiry);
    return 1;
}

/* Minimal JSON string helper: appends JSON-escaped src into dst. */
static size_t json_escape(char *dst, size_t cap, const char *src)
{
    size_t w = 0;
    const char *p;
    for (p = src ? src : ""; *p && w + 6 < cap; p++) {
        unsigned char c = (unsigned char)*p;
        if (c == '"' || c == '\\') { dst[w++] = '\\'; dst[w++] = (char)c; }
        else if (c == '\n')        { dst[w++] = '\\'; dst[w++] = 'n'; }
        else if (c == '\r')        { dst[w++] = '\\'; dst[w++] = 'r'; }
        else if (c == '\t')        { dst[w++] = '\\'; dst[w++] = 't'; }
        else if (c >= 0x20)        { dst[w++] = (char)c; }
    }
    if (cap) dst[w] = '\0';
    return w;
}

/* Minimal JSON string field extractor: finds `"key" : "value"`. */
static int json_extract_str(const char *json, const char *key, char *out, size_t cap)
{
    char needle[96];
    const char *p = json;
    if (!json || !key || !out || cap == 0) return 0;
    out[0] = '\0';
    snprintf(needle, sizeof needle, "\"%s\"", key);
    while ((p = strstr(p, needle)) != NULL) {
        const char *q = p + strlen(needle);
        while (*q == ' ' || *q == '\t' || *q == '\r' || *q == '\n') q++;
        if (*q != ':') { p = q; continue; }
        q++;
        while (*q == ' ' || *q == '\t' || *q == '\r' || *q == '\n') q++;
        if (*q != '"') { p = q; continue; }
        q++;
        {
            size_t w = 0;
            while (*q && *q != '"' && w + 1 < cap) {
                if (*q == '\\' && q[1]) {
                    q++;
                    if (*q == 'n') out[w++] = '\n';
                    else if (*q == 'r') out[w++] = '\r';
                    else if (*q == 't') out[w++] = '\t';
                    else out[w++] = *q;
                    q++;
                } else {
                    out[w++] = *q++;
                }
            }
            out[w] = '\0';
            return 1;
        }
    }
    return 0;
}

/* Extract a string field from inside a named JSON object child `"obj_key": { ... "field_key": "val" ... }`. */
static int json_extract_nested_str(const char *json, const char *obj_key, const char *field_key,
                                   char *out, size_t cap)
{
    char needle[96];
    const char *p = json;
    if (!json || !obj_key || !field_key || !out || cap == 0) return 0;
    out[0] = '\0';
    snprintf(needle, sizeof needle, "\"%s\"", obj_key);
    while ((p = strstr(p, needle)) != NULL) {
        const char *q = p + strlen(needle);
        const char *start, *cur;
        int depth = 0, in_str = 0;
        while (*q == ' ' || *q == '\t' || *q == '\r' || *q == '\n') q++;
        if (*q != ':') { p = q; continue; }
        q++;
        while (*q == ' ' || *q == '\t' || *q == '\r' || *q == '\n') q++;
        if (*q != '{') { p = q; continue; }
        start = q;
        for (cur = start; *cur; cur++) {
            if (in_str) {
                if (*cur == '\\' && cur[1]) { cur++; continue; }
                if (*cur == '"') in_str = 0;
            } else {
                if (*cur == '"') in_str = 1;
                else if (*cur == '{') depth++;
                else if (*cur == '}') {
                    depth--;
                    if (depth == 0) { cur++; break; }
                }
            }
        }
        if (depth == 0 && cur > start) {
            size_t sub_len = (size_t)(cur - start);
            char *sub = (char *)malloc(sub_len + 1);
            int ok;
            if (!sub) return 0;
            memcpy(sub, start, sub_len);
            sub[sub_len] = '\0';
            ok = json_extract_str(sub, field_key, out, cap);
            free(sub);
            return ok;
        }
        p = q + 1;
    }
    return 0;
}

static size_t encode_b64(const uint8_t *in, size_t len, char *out, size_t cap, int url_safe, int pad)
{
    static const char std_tbl[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    static const char url_tbl[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    const char *tbl = url_safe ? url_tbl : std_tbl;
    size_t i = 0, w = 0;
    while (i < len) {
        size_t rem = len - i;
        uint32_t v = ((uint32_t)in[i] << 16) |
                     (rem > 1 ? ((uint32_t)in[i + 1] << 8) : 0) |
                     (rem > 2 ? (uint32_t)in[i + 2] : 0);
        if (w + 4 >= cap) break;
        out[w++] = tbl[(v >> 18) & 0x3f];
        out[w++] = tbl[(v >> 12) & 0x3f];
        if (rem > 1) out[w++] = tbl[(v >> 6) & 0x3f];
        else if (pad) out[w++] = '=';
        if (rem > 2) out[w++] = tbl[v & 0x3f];
        else if (pad) out[w++] = '=';
        i += (rem >= 3) ? 3 : rem;
    }
    if (cap) out[w] = '\0';
    return w;
}

static int ensure_proof_key_locked(void)
{
    uint8_t blob[8 + 64];
    ULONG out_len = 0;
    char x_b64[64], y_b64[64];
    NTSTATUS_A st;

    if (g_proof_key && g_proof_key_json[0]) return 1;
    if (!g_bcrypt_ready) return 0;

    if (!g_ecdsa_alg) {
        st = fn_BCryptOpenAlgorithmProvider(&g_ecdsa_alg, L"ECDSA_P256", NULL, 0);
        if (st < 0) { auth_log("BCryptOpenAlgorithmProvider(ECDSA_P256) failed 0x%08lx", (unsigned long)st); return 0; }
    }
    if (!g_sha256_alg) {
        st = fn_BCryptOpenAlgorithmProvider(&g_sha256_alg, L"SHA256", NULL, 0);
        if (st < 0) { auth_log("BCryptOpenAlgorithmProvider(SHA256) failed 0x%08lx", (unsigned long)st); return 0; }
    }
    st = fn_BCryptGenerateKeyPair(g_ecdsa_alg, &g_proof_key, 256, 0);
    if (st < 0) { auth_log("BCryptGenerateKeyPair failed 0x%08lx", (unsigned long)st); return 0; }
    st = fn_BCryptFinalizeKeyPair(g_proof_key, 0);
    if (st < 0) { auth_log("BCryptFinalizeKeyPair failed 0x%08lx", (unsigned long)st); return 0; }
    st = fn_BCryptExportKey(g_proof_key, NULL, L"ECCPUBLICBLOB", blob, sizeof blob, &out_len, 0);
    if (st < 0 || out_len < 72) {
        auth_log("BCryptExportKey failed 0x%08lx len=%lu", (unsigned long)st, (unsigned long)out_len);
        return 0;
    }
    encode_b64(blob + 8, 32, x_b64, sizeof x_b64, 1, 0);
    encode_b64(blob + 40, 32, y_b64, sizeof y_b64, 1, 0);
    snprintf(g_proof_key_json, sizeof g_proof_key_json,
             "{\"alg\":\"ES256\",\"crv\":\"P-256\",\"kty\":\"EC\",\"use\":\"sig\",\"x\":\"%s\",\"y\":\"%s\"}",
             x_b64, y_b64);
    auth_log("generated ECDSA P-256 ProofKey");
    return 1;
}

static int sign_xbox_request_locked(uint32_t version, const char *method, const char *path_and_query,
                                    const char *auth_hdr, const void *body, size_t body_len,
                                    char sig_out[128])
{
    uint8_t raw[76];
    uint8_t hash[32];
    FILETIME ft;
    size_t mlen, plen, alen, total;
    uint8_t *buf = NULL, *p;
    BCRYPT_HANDLE_A hhash = NULL;
    ULONG sig_len = 0;
    NTSTATUS_A st;
    int ok = 0;

    sig_out[0] = '\0';
    if (!ensure_proof_key_locked()) return 0;
    if (!method) method = "POST";
    if (!path_and_query || !path_and_query[0]) path_and_query = "/";
    if (!auth_hdr) auth_hdr = "";

    GetSystemTimeAsFileTime(&ft);
    raw[0]  = (uint8_t)((version >> 24) & 0xff);
    raw[1]  = (uint8_t)((version >> 16) & 0xff);
    raw[2]  = (uint8_t)((version >> 8) & 0xff);
    raw[3]  = (uint8_t)(version & 0xff);
    raw[4]  = (uint8_t)((ft.dwHighDateTime >> 24) & 0xff);
    raw[5]  = (uint8_t)((ft.dwHighDateTime >> 16) & 0xff);
    raw[6]  = (uint8_t)((ft.dwHighDateTime >> 8) & 0xff);
    raw[7]  = (uint8_t)(ft.dwHighDateTime & 0xff);
    raw[8]  = (uint8_t)((ft.dwLowDateTime >> 24) & 0xff);
    raw[9]  = (uint8_t)((ft.dwLowDateTime >> 16) & 0xff);
    raw[10] = (uint8_t)((ft.dwLowDateTime >> 8) & 0xff);
    raw[11] = (uint8_t)(ft.dwLowDateTime & 0xff);

    mlen = strlen(method);
    plen = strlen(path_and_query);
    alen = strlen(auth_hdr);
    total = 4 + 1 + 8 + 1 + mlen + 1 + plen + 1 + alen + 1 + body_len + 1;
    buf = (uint8_t *)calloc(1, total);
    if (!buf) return 0;

    p = buf;
    memcpy(p, raw, 4); p += 4; *p++ = 0;
    memcpy(p, raw + 4, 8); p += 8; *p++ = 0;
    {
        size_t i;
        for (i = 0; i < mlen; i++) {
            char c = method[i];
            if (c >= 'a' && c <= 'z') c = (char)(c - 'a' + 'A');
            *p++ = (uint8_t)c;
        }
    }
    *p++ = 0;
    memcpy(p, path_and_query, plen); p += plen; *p++ = 0;
    memcpy(p, auth_hdr, alen); p += alen; *p++ = 0;
    if (body && body_len) { memcpy(p, body, body_len); p += body_len; }
    *p++ = 0;

    st = fn_BCryptCreateHash(g_sha256_alg, &hhash, NULL, 0, NULL, 0, 0);
    if (st < 0) goto done;
    st = fn_BCryptHashData(hhash, buf, (ULONG)total, 0);
    if (st < 0) goto done;
    st = fn_BCryptFinishHash(hhash, hash, sizeof hash, 0);
    if (st < 0) goto done;
    st = fn_BCryptSignHash(g_proof_key, NULL, hash, sizeof hash, raw + 12, 64, &sig_len, 0);
    if (st < 0 || sig_len != 64) {
        auth_log("BCryptSignHash failed 0x%08lx len=%lu", (unsigned long)st, (unsigned long)sig_len);
        goto done;
    }

    encode_b64(raw, 76, sig_out, 128, 0, 1);
    ok = 1;

done:
    if (hhash) fn_BCryptDestroyHash(hhash);
    free(buf);
    return ok;
}

static int https_post_json_ex(const WCHAR *host, const WCHAR *path, const char *body,
                              int contract_ver, int sign_it, DWORD *status_out, char **resp_out)
{
    HINTERNET hSession = NULL, hConnect = NULL, hReq = NULL;
    DWORD status = 0, status_sz = sizeof status;
    DWORD timeout_ms = 20000;
    char *buf = NULL;
    size_t total = 0, cap = 0;
    int ok = 0;
    const WCHAR *accept_types[] = { L"application/json", NULL };
    WCHAR headers[512];
    char sig[128];

    *status_out = 0;
    *resp_out = NULL;
    if (!g_inet_ready) { auth_log("wininet not available"); return 0; }

    sig[0] = '\0';
    if (sign_it) {
        char path_a[256];
        size_t i;
        for (i = 0; i + 1 < sizeof path_a && path[i]; i++) path_a[i] = (char)path[i];
        path_a[i] = '\0';
        sign_xbox_request_locked(1, "POST", path_a, "", body, strlen(body), sig);
    }

    if (contract_ver > 0 && sig[0]) {
        WCHAR sig_w[128];
        size_t i;
        for (i = 0; i + 1 < 128 && sig[i]; i++) sig_w[i] = (WCHAR)(unsigned char)sig[i];
        sig_w[i] = 0;
        swprintf(headers, 512,
                 L"Content-Type: application/json\r\nAccept: application/json\r\n"
                 L"x-xbl-contract-version: %d\r\nSignature: %ls\r\n", contract_ver, sig_w);
    } else if (sig[0]) {
        WCHAR sig_w[128];
        size_t i;
        for (i = 0; i + 1 < 128 && sig[i]; i++) sig_w[i] = (WCHAR)(unsigned char)sig[i];
        sig_w[i] = 0;
        swprintf(headers, 512,
                 L"Content-Type: application/json\r\nAccept: application/json\r\nSignature: %ls\r\n", sig_w);
    } else {
        swprintf(headers, 512,
                 L"Content-Type: application/json\r\nAccept: application/json\r\n"
                 L"x-xbl-contract-version: %d\r\n", contract_ver > 0 ? contract_ver : 1);
    }

    hSession = fn_InternetOpenW(L"XboxLiveUwp/1.0", INTERNET_OPEN_TYPE_PRECONFIG, NULL, NULL, 0);
    if (!hSession) { auth_log("InternetOpenW failed err=%lu", GetLastError()); goto done; }
    if (fn_InternetSetOptionW) {
        fn_InternetSetOptionW(hSession, INTERNET_OPTION_CONNECT_TIMEOUT, &timeout_ms, sizeof timeout_ms);
        fn_InternetSetOptionW(hSession, INTERNET_OPTION_SEND_TIMEOUT, &timeout_ms, sizeof timeout_ms);
        fn_InternetSetOptionW(hSession, INTERNET_OPTION_RECEIVE_TIMEOUT, &timeout_ms, sizeof timeout_ms);
    }

    hConnect = fn_InternetConnectW(hSession, host, INTERNET_DEFAULT_HTTPS_PORT,
                                   NULL, NULL, INTERNET_SERVICE_HTTP, 0, 0);
    if (!hConnect) { auth_log("InternetConnectW failed err=%lu", GetLastError()); goto done; }

    hReq = fn_HttpOpenRequestW(hConnect, L"POST", path, NULL, NULL, (LPCWSTR *)accept_types,
                               INTERNET_FLAG_SECURE | INTERNET_FLAG_RELOAD |
                               INTERNET_FLAG_NO_CACHE_WRITE | INTERNET_FLAG_NO_COOKIES, 0);
    if (!hReq) { auth_log("HttpOpenRequestW failed err=%lu", GetLastError()); goto done; }

    if (!fn_HttpSendRequestW(hReq, headers, (DWORD)-1L, (LPVOID)body, (DWORD)strlen(body))) {
        auth_log("HttpSendRequestW failed err=%lu", GetLastError());
        goto done;
    }

    fn_HttpQueryInfoW(hReq, HTTP_QUERY_STATUS_CODE | HTTP_QUERY_FLAG_NUMBER, &status, &status_sz, NULL);
    *status_out = status;

    for (;;) {
        char chunk[4096];
        DWORD got = 0;
        if (!fn_InternetReadFile(hReq, chunk, sizeof chunk, &got)) {
            auth_log("InternetReadFile failed err=%lu", GetLastError());
            goto done;
        }
        if (got == 0) break;
        if (total + got + 1 > cap) {
            size_t ncap = (total + got + 1) * 2;
            char *nb = (char *)realloc(buf, ncap);
            if (!nb) goto done;
            buf = nb;
            cap = ncap;
        }
        memcpy(buf + total, chunk, got);
        total += got;
    }
    if (!buf) buf = (char *)calloc(1, 1);
    else buf[total] = '\0';
    *resp_out = buf;
    buf = NULL;
    ok = 1;

done:
    free(buf);
    if (hReq) fn_InternetCloseHandle(hReq);
    if (hConnect) fn_InternetCloseHandle(hConnect);
    if (hSession) fn_InternetCloseHandle(hSession);
    return ok;
}

static int https_post_json(const WCHAR *host, const WCHAR *path, const char *body,
                           DWORD *status_out, char **resp_out)
{
    HINTERNET hSession = NULL, hConnect = NULL, hReq = NULL;
    DWORD status = 0, status_sz = sizeof status;
    DWORD timeout_ms = 20000;
    char *buf = NULL;
    size_t total = 0, cap = 0;
    int ok = 0;
    const WCHAR *accept_types[] = { L"application/json", NULL };
    const WCHAR *headers =
        L"Content-Type: application/json\r\n"
        L"Accept: application/json\r\n"
        L"x-xbl-contract-version: 1\r\n";

    *status_out = 0;
    *resp_out = NULL;
    if (!g_inet_ready) { auth_log("wininet not available"); return 0; }

    hSession = fn_InternetOpenW(L"XboxLiveUwp/1.0", INTERNET_OPEN_TYPE_PRECONFIG, NULL, NULL, 0);
    if (!hSession) { auth_log("InternetOpenW failed err=%lu", GetLastError()); goto done; }
    if (fn_InternetSetOptionW) {
        fn_InternetSetOptionW(hSession, INTERNET_OPTION_CONNECT_TIMEOUT, &timeout_ms, sizeof timeout_ms);
        fn_InternetSetOptionW(hSession, INTERNET_OPTION_SEND_TIMEOUT, &timeout_ms, sizeof timeout_ms);
        fn_InternetSetOptionW(hSession, INTERNET_OPTION_RECEIVE_TIMEOUT, &timeout_ms, sizeof timeout_ms);
    }

    hConnect = fn_InternetConnectW(hSession, host, INTERNET_DEFAULT_HTTPS_PORT,
                                   NULL, NULL, INTERNET_SERVICE_HTTP, 0, 0);
    if (!hConnect) { auth_log("InternetConnectW failed err=%lu", GetLastError()); goto done; }

    hReq = fn_HttpOpenRequestW(hConnect, L"POST", path, NULL, NULL, (LPCWSTR *)accept_types,
                               INTERNET_FLAG_SECURE | INTERNET_FLAG_RELOAD |
                               INTERNET_FLAG_NO_CACHE_WRITE | INTERNET_FLAG_NO_COOKIES, 0);
    if (!hReq) { auth_log("HttpOpenRequestW failed err=%lu", GetLastError()); goto done; }

    if (!fn_HttpSendRequestW(hReq, headers, (DWORD)-1L, (LPVOID)body, (DWORD)strlen(body))) {
        auth_log("HttpSendRequestW failed err=%lu", GetLastError());
        goto done;
    }

    fn_HttpQueryInfoW(hReq, HTTP_QUERY_STATUS_CODE | HTTP_QUERY_FLAG_NUMBER, &status, &status_sz, NULL);
    *status_out = status;

    for (;;) {
        char chunk[4096];
        DWORD got = 0;
        if (!fn_InternetReadFile(hReq, chunk, sizeof chunk, &got)) {
            auth_log("InternetReadFile failed err=%lu", GetLastError());
            goto done;
        }
        if (got == 0) break;
        if (total + got + 1 > cap) {
            size_t ncap = (total + got + 1) * 2;
            char *nb = (char *)realloc(buf, ncap);
            if (!nb) goto done;
            buf = nb;
            cap = ncap;
        }
        memcpy(buf + total, chunk, got);
        total += got;
    }
    if (!buf) buf = (char *)calloc(1, 1);
    else buf[total] = '\0';
    *resp_out = buf;
    buf = NULL;
    ok = 1;

done:
    free(buf);
    if (hReq) fn_InternetCloseHandle(hReq);
    if (hConnect) fn_InternetCloseHandle(hConnect);
    if (hSession) fn_InternetCloseHandle(hSession);
    return ok;
}

static int fetch_xbl_user_token_locked(int force_refresh)
{
    const char *ticket;
    char *esc_ticket = NULL;
    char *esc_dev = NULL;
    char *body = NULL;
    DWORD status = 0;
    char *resp = NULL;
    time_t now = time(NULL);
    int ok = 0;

    if (!force_refresh && g_xbl_user_token[0] && now + 120 < g_xbl_user_expiry)
        return 1;

    if (!fetch_msa_ticket_locked(force_refresh))
        return 0;

    esc_ticket = (char *)malloc(10240);
    esc_dev = (char *)malloc(10240);
    body = (char *)malloc(24576);
    if (!esc_ticket || !esc_dev || !body) goto done;

    g_xbl_device_token[0] = '\0';
    g_xbl_title_token[0] = '\0';

    /* Step 1 + 2: Genuine DeviceToken + SISU authorization (produces DeviceToken + TitleToken + UserToken
     * so title-scoped endpoints like userpresence.xboxlive.com/devices/current/titles/current succeed). */
    if (g_device_rps[0] && ensure_proof_key_locked()) {
        json_escape(esc_dev, 10240, g_device_rps);
        snprintf(body, 24576,
                 "{\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\","
                 "\"Properties\":{\"AuthMethod\":\"RPS\",\"RpsTicket\":\"%s\","
                 "\"SiteName\":\"user.auth.xboxlive.com\",\"Version\":\"10.0.22621.0\","
                 "\"ProofKey\":%s}}",
                 esc_dev, g_proof_key_json);
        if (https_post_json_ex(L"device.auth.xboxlive.com", L"/device/authenticate", body, 1, 1, &status, &resp) &&
            status == 200 && json_extract_str(resp, "Token", g_xbl_device_token, sizeof g_xbl_device_token) &&
            g_xbl_device_token[0]) {
            auth_log("acquired Xbox Live DeviceToken (len=%u)", (unsigned)strlen(g_xbl_device_token));
        } else {
            auth_log("device/authenticate failed HTTP %lu", (unsigned long)status);
            g_xbl_device_token[0] = '\0';
        }
        free(resp);
        resp = NULL;

        if (g_xbl_device_token[0]) {
            json_escape(esc_ticket, 10240, g_msa_ticket);
            json_escape(esc_dev, 10240, g_xbl_device_token);
            snprintf(body, 24576,
                     "{\"AccessToken\":\"%s\",\"AppId\":\"" XODUS_XBOX_CLIENT_ID "\","
                     "\"DeviceToken\":\"%s\",\"Sandbox\":\"RETAIL\","
                     "\"SiteName\":\"user.auth.xboxlive.com\",\"UseModernGamertag\":true,"
                     "\"ProofKey\":%s}",
                     esc_ticket, esc_dev, g_proof_key_json);
            if (https_post_json_ex(L"sisu.xboxlive.com", L"/authorize", body, 0, 1, &status, &resp) &&
                status == 200 &&
                json_extract_nested_str(resp, "UserToken", "Token", g_xbl_user_token, sizeof g_xbl_user_token) &&
                g_xbl_user_token[0]) {
                json_extract_nested_str(resp, "TitleToken", "Token", g_xbl_title_token, sizeof g_xbl_title_token);
                g_xbl_user_expiry = now + 36000;
                auth_log("SISU authorized: user_len=%u title_len=%u device_len=%u",
                         (unsigned)strlen(g_xbl_user_token),
                         (unsigned)strlen(g_xbl_title_token),
                         (unsigned)strlen(g_xbl_device_token));
                ok = 1;
                goto done;
            } else {
                auth_log("sisu/authorize failed HTTP %lu", (unsigned long)status);
            }
            free(resp);
            resp = NULL;
        }
    }

    /* Fallback: direct user.auth.xboxlive.com/user/authenticate (without Device/Title tokens). */
    ticket = normalize_xbox_rps_ticket(g_msa_ticket);
    json_escape(esc_ticket, 10240, ticket);
    snprintf(body, 24576,
             "{\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\","
             "\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"%s\"}}",
             esc_ticket);

    if (!https_post_json(L"user.auth.xboxlive.com", L"/user/authenticate", body, &status, &resp))
        goto done;
    if (status != 200 && strncmp(ticket, "d=", 2) != 0 && strncmp(ticket, "t=", 2) != 0) {
        free(resp);
        resp = NULL;
        snprintf(body, 24576,
                 "{\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\","
                 "\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"d=%s\"}}",
                 esc_ticket);
        if (!https_post_json(L"user.auth.xboxlive.com", L"/user/authenticate", body, &status, &resp))
            goto done;
    }
    if (status != 200) {
        auth_log("user/authenticate HTTP %lu", (unsigned long)status);
        goto done;
    }
    if (!json_extract_str(resp, "Token", g_xbl_user_token, sizeof g_xbl_user_token) || !g_xbl_user_token[0]) {
        auth_log("user/authenticate response missing Token");
        goto done;
    }
    g_xbl_user_expiry = now + 36000;
    auth_log("acquired Xbox Live user token (fallback, len=%u)", (unsigned)strlen(g_xbl_user_token));
    ok = 1;

done:
    free(resp);
    free(body);
    free(esc_dev);
    free(esc_ticket);
    return ok;
}

/* Normalize a requested target/URL into an XSTS RelyingParty. */
static void normalize_relying_party(const char *raw_target, const char *raw_url, char *rp, size_t cap)
{
    const char *src = (raw_target && raw_target[0]) ? raw_target : raw_url;
    if (!src || !src[0]) {
        snprintf(rp, cap, "http://xboxlive.com");
        return;
    }
    /* All *.xboxlive.com endpoints use the canonical http://xboxlive.com relying party. */
    if (strstr(src, ".xboxlive.com") != NULL || strstr(src, "//xboxlive.com") != NULL) {
        snprintf(rp, cap, "http://xboxlive.com");
        return;
    }
    snprintf(rp, cap, "%s", src);
}

/* Strip URL path to origin ("https://host/") for XSTS fallback when full URL is rejected. */
static int origin_only(const char *url, char *out, size_t cap)
{
    const char *scheme = strstr(url, "://");
    const char *slash;
    size_t len;
    if (!scheme) return 0;
    slash = strchr(scheme + 3, '/');
    if (!slash || slash[1] == '\0') return 0;
    len = (size_t)(slash - url) + 1;
    if (len + 1 >= cap) return 0;
    memcpy(out, url, len);
    out[len] = '\0';
    return 1;
}

static int parse_iso8601_utc(const char *str, time_t *out)
{
    int y = 0, mo = 0, d = 0, h = 0, mi = 0, s = 0;
    SYSTEMTIME st;
    FILETIME ft;
    ULARGE_INTEGER ui;
    if (!str || !out) return 0;
    if (sscanf(str, "%4d-%2d-%2dT%2d:%2d:%2d", &y, &mo, &d, &h, &mi, &s) != 6) return 0;
    if (y < 1970 || mo < 1 || mo > 12 || d < 1 || d > 31 || h < 0 || h > 23 || mi < 0 ||
        mi > 59 || s < 0 || s > 60)
        return 0;
    memset(&st, 0, sizeof st);
    st.wYear = (WORD)y;
    st.wMonth = (WORD)mo;
    st.wDay = (WORD)d;
    st.wHour = (WORD)h;
    st.wMinute = (WORD)mi;
    st.wSecond = (WORD)(s == 60 ? 59 : s);
    if (!SystemTimeToFileTime(&st, &ft)) return 0;
    ui.LowPart = ft.dwLowDateTime;
    ui.HighPart = ft.dwHighDateTime;
    if (ui.QuadPart < 116444736000000000ULL) return 0;
    *out = (time_t)((ui.QuadPart - 116444736000000000ULL) / 10000000ULL);
    return 1;
}

static int rp_is_xboxlive_equiv(const char *rp)
{
    if (!rp || !rp[0]) return 1;
    if (_stricmp(rp, "http://xboxlive.com") == 0 || _stricmp(rp, "https://xboxlive.com") == 0)
        return 1;
    return strstr(rp, ".xboxlive.com") != NULL || strstr(rp, "//xboxlive.com") != NULL;
}

static int acquire_xsts_entry(const char *rp_in, int force_refresh, xsts_entry *out)
{
    int i, slot = -1, ok = 0;
    time_t now;
    char rp[256];
    char actual_rp[256];
    char not_after[64] = "";
    char esc_rp[512];
    char *esc_ut = NULL;
    char *body = NULL;
    DWORD status = 0;
    char *resp = NULL;
    xsts_entry *entry = NULL;

    auth_init_once();
    snprintf(rp, sizeof rp, "%s", (rp_in && rp_in[0]) ? rp_in : "http://xboxlive.com");
    snprintf(actual_rp, sizeof actual_rp, "%s", rp);

    EnterCriticalSection(&g_auth_lock);
    now = time(NULL);
    if (!force_refresh) {
        for (i = 0; i < g_xsts_count; i++) {
            if (_stricmp(g_xsts_cache[i].rp, rp) == 0 && g_xsts_cache[i].expires > 0 &&
                now + 120 < g_xsts_cache[i].expires) {
                *out = g_xsts_cache[i];
                LeaveCriticalSection(&g_auth_lock);
                return 1;
            }
        }
    }

    if (!fetch_xbl_user_token_locked(force_refresh)) goto unlock;

    esc_ut = (char *)malloc(10240);
    body = (char *)malloc(32768);
    entry = (xsts_entry *)calloc(1, sizeof(xsts_entry));
    if (!esc_ut || !body || !entry) goto unlock;

    json_escape(esc_ut, 10240, g_xbl_user_token);
    json_escape(esc_rp, sizeof esc_rp, rp);
    {
        char *esc_dt = (char *)malloc(10240);
        char *esc_tt = (char *)malloc(10240);
        int has_dt = (esc_dt && g_xbl_device_token[0]);
        int has_tt = (esc_tt && g_xbl_title_token[0]);
        int use_sig = (has_dt && g_proof_key != NULL);
        if (has_dt) json_escape(esc_dt, 10240, g_xbl_device_token);
        if (has_tt) json_escape(esc_tt, 10240, g_xbl_title_token);

        if (has_dt && has_tt) {
            snprintf(body, 32768,
                     "{\"RelyingParty\":\"%s\",\"TokenType\":\"JWT\","
                     "\"Properties\":{\"SandboxId\":\"RETAIL\",\"DeviceToken\":\"%s\","
                     "\"TitleToken\":\"%s\",\"UserTokens\":[\"%s\"]}}",
                     esc_rp, esc_dt, esc_tt, esc_ut);
        } else if (has_dt) {
            snprintf(body, 32768,
                     "{\"RelyingParty\":\"%s\",\"TokenType\":\"JWT\","
                     "\"Properties\":{\"SandboxId\":\"RETAIL\",\"DeviceToken\":\"%s\","
                     "\"UserTokens\":[\"%s\"]}}",
                     esc_rp, esc_dt, esc_ut);
        } else {
            snprintf(body, 32768,
                     "{\"RelyingParty\":\"%s\",\"TokenType\":\"JWT\","
                     "\"Properties\":{\"UserTokens\":[\"%s\"],\"SandboxId\":\"RETAIL\"}}",
                     esc_rp, esc_ut);
        }

        if (!https_post_json_ex(L"xsts.auth.xboxlive.com", L"/xsts/authorize", body, 1, use_sig, &status, &resp)) {
            free(esc_dt); free(esc_tt);
            goto unlock;
        }

        if (status != 200) {
            char origin[256];
            auth_log("xsts/authorize failed HTTP %lu for rp=%s dt=%d tt=%d",
                     (unsigned long)status, rp, has_dt, has_tt);
            free(resp);
            resp = NULL;
            if (has_tt && _stricmp(rp, "http://xboxlive.com") == 0) {
                snprintf(body, 32768,
                         "{\"RelyingParty\":\"%s\",\"TokenType\":\"JWT\","
                         "\"Properties\":{\"SandboxId\":\"RETAIL\",\"DeviceToken\":\"%s\","
                         "\"UserTokens\":[\"%s\"]}}",
                         esc_rp, esc_dt, esc_ut);
                if (!https_post_json_ex(L"xsts.auth.xboxlive.com", L"/xsts/authorize", body, 1, use_sig, &status, &resp) ||
                    status != 200) {
                    free(resp); resp = NULL;
                }
            }
            if (!resp && origin_only(rp, origin, sizeof origin)) {
                json_escape(esc_rp, sizeof esc_rp, origin);
                snprintf(body, 32768,
                         "{\"RelyingParty\":\"%s\",\"TokenType\":\"JWT\","
                         "\"Properties\":{\"UserTokens\":[\"%s\"],\"SandboxId\":\"RETAIL\"}}",
                         esc_rp, esc_ut);
                if (!https_post_json_ex(L"xsts.auth.xboxlive.com", L"/xsts/authorize", body, 1, 0, &status, &resp) ||
                    status != 200) {
                    auth_log("xsts/authorize origin retry failed HTTP %lu for origin=%s", (unsigned long)status, origin);
                    free(resp);
                    resp = NULL;
                } else {
                    snprintf(actual_rp, sizeof actual_rp, "%s", origin);
                }
            }
            if (!resp && _stricmp(rp, "http://xboxlive.com") != 0 && rp_is_xboxlive_equiv(rp)) {
                snprintf(body, 32768,
                         "{\"RelyingParty\":\"http://xboxlive.com\",\"TokenType\":\"JWT\","
                         "\"Properties\":{\"UserTokens\":[\"%s\"],\"SandboxId\":\"RETAIL\"}}",
                         esc_ut);
                if (!https_post_json_ex(L"xsts.auth.xboxlive.com", L"/xsts/authorize", body, 1, 0, &status, &resp) ||
                    status != 200) {
                    auth_log("xsts/authorize fallback failed HTTP %lu", (unsigned long)status);
                    free(esc_dt); free(esc_tt);
                    goto unlock;
                }
                snprintf(actual_rp, sizeof actual_rp, "http://xboxlive.com");
            }
            if (!resp) { free(esc_dt); free(esc_tt); goto unlock; }
        }
        free(esc_dt);
        free(esc_tt);
    }

    snprintf(entry->rp, sizeof entry->rp, "%s", actual_rp);
    if (!json_extract_str(resp, "Token", entry->token, sizeof entry->token) || !entry->token[0]) {
        auth_log("xsts/authorize response missing Token");
        goto unlock;
    }
    json_extract_str(resp, "uhs", entry->uhs, sizeof entry->uhs);
    json_extract_str(resp, "xid", entry->xuid, sizeof entry->xuid);
    json_extract_str(resp, "gtg", entry->gamertag, sizeof entry->gamertag);
    json_extract_str(resp, "agg", entry->age_group, sizeof entry->age_group);
    json_extract_str(resp, "prv", entry->privileges, sizeof entry->privileges);
    now = time(NULL);
    entry->expires = 0;
    if (json_extract_str(resp, "NotAfter", not_after, sizeof not_after) && not_after[0]) {
        time_t parsed_exp = 0;
        if (parse_iso8601_utc(not_after, &parsed_exp) && parsed_exp > now)
            entry->expires = parsed_exp;
    }

    if (entry->xuid[0]) snprintf(g_cached_xuid, sizeof g_cached_xuid, "%s", entry->xuid);
    if (entry->gamertag[0]) snprintf(g_cached_gamertag, sizeof g_cached_gamertag, "%s", entry->gamertag);
    if (!g_cached_non_roamable_id[0] && entry->xuid[0])
        snprintf(g_cached_non_roamable_id, sizeof g_cached_non_roamable_id, "%s", entry->xuid);

    if (!entry->xuid[0] && g_cached_xuid[0])
        snprintf(entry->xuid, sizeof entry->xuid, "%s", g_cached_xuid);
    if (!entry->gamertag[0] && g_cached_gamertag[0])
        snprintf(entry->gamertag, sizeof entry->gamertag, "%s", g_cached_gamertag);

    if (entry->expires > now + 120) {
        for (i = 0; i < g_xsts_count; i++) {
            if (_stricmp(g_xsts_cache[i].rp, entry->rp) == 0) { slot = i; break; }
        }
        if (slot < 0) {
            if (g_xsts_count < 16) slot = g_xsts_count++;
            else slot = 0;
        }
        g_xsts_cache[slot] = *entry;
    }
    *out = *entry;
    ok = 1;

unlock:
    LeaveCriticalSection(&g_auth_lock);
    if (ok) {
        auth_log("acquired XSTS token rp=%s has_xuid=%d has_gt=%d has_age=%d has_uhs=%d has_prv=%d token_len=%u",
                 entry->rp, entry->xuid[0] != 0, entry->gamertag[0] != 0,
                 entry->age_group[0] != 0, entry->uhs[0] != 0, entry->privileges[0] != 0,
                 (unsigned)strlen(entry->token));
    }
    free(resp);
    free(body);
    free(esc_ut);
    free(entry);
    return ok;
}

static void ensure_identity_warm(void)
{
    xsts_entry *tmp;
    auth_init_once();
    EnterCriticalSection(&g_auth_lock);
    if (g_cached_xuid[0]) {
        LeaveCriticalSection(&g_auth_lock);
        return;
    }
    LeaveCriticalSection(&g_auth_lock);
    tmp = (xsts_entry *)calloc(1, sizeof(xsts_entry));
    if (tmp) {
        acquire_xsts_entry("http://xboxlive.com", 0, tmp);
        free(tmp);
    }
}

static void get_user_id_str(char *out, size_t cap)
{
    auth_init_once();
    EnterCriticalSection(&g_auth_lock);
    if (g_cached_non_roamable_id[0])
        snprintf(out, cap, "%s", g_cached_non_roamable_id);
    else if (g_cached_xuid[0])
        snprintf(out, cap, "%s", g_cached_xuid);
    else
        snprintf(out, cap, "S-1-5-21-0-0-0-1001");
    LeaveCriticalSection(&g_auth_lock);
}

static void get_gamertag_str(char *out, size_t cap)
{
    auth_init_once();
    EnterCriticalSection(&g_auth_lock);
    snprintf(out, cap, "%s", g_cached_gamertag[0] ? g_cached_gamertag : "Player");
    LeaveCriticalSection(&g_auth_lock);
}

/* --------------------------------------------------------------- Windows.System.User */

typedef struct {
    auth_obj base;  /* IUser */
    auth_obj user2; /* IUser2 */
} winrt_user;

static winrt_user g_user_obj;
static const void *user_vtbl[];
static const void *user2_vtbl[];

static winrt_user *user_from(void *self)
{
    auth_obj *h = (auth_obj *)self;
    if (h && h->vtbl == user2_vtbl)
        return (winrt_user *)((char *)self - offsetof(winrt_user, user2));
    return (winrt_user *)self;
}

static ULONG __stdcall user_AddRef(void *self)  { return obj_AddRef(&user_from(self)->base); }
static ULONG __stdcall user_Release(void *self) { return obj_Release(&user_from(self)->base); }

static HRESULT __stdcall user_QI(void *self, const GUID *id, void **out)
{
    winrt_user *u = user_from(self);
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IUser)) {
        *out = &u->base;
        user_AddRef(u);
        return S_OK;
    }
    if (IsEqualIID(id, &IID_IUser2)) {
        *out = &u->user2;
        user_AddRef(u);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall user_get_NonRoamableId(void *self, hstring_t *out)
{
    char id[128];
    (void)self;
    if (!out) return E_POINTER;
    get_user_id_str(id, sizeof id);
    auth_tracef("User::get_NonRoamableId (present=%d)", id[0] != 0);
    *out = hstr_from_utf8(id);
    return S_OK;
}

static HRESULT __stdcall user_get_AuthenticationStatus(void *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = USER_AUTH_LOCAL;
    return S_OK;
}

static HRESULT __stdcall user_get_Type(void *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = USER_TYPE_LOCAL;
    return S_OK;
}

static HRESULT __stdcall user_GetPropertyAsync(void *self, hstring_t prop_name, void **out)
{
    char *name = hstr_to_utf8(prop_name);
    char val[128] = "";
    (void)self;
    if (name && (_stricmp(name, "DisplayName") == 0 || _stricmp(name, "AccountName") == 0 ||
                 _stricmp(name, "FirstName") == 0)) {
        get_gamertag_str(val, sizeof val);
    }
    auth_tracef("User::GetPropertyAsync(%s) (present=%d)", name ? name : "", val[0] != 0);
    free(name);
    {
        prop_str *pv = prop_str_new(val);
        if (!pv) return E_OUTOFMEMORY;
        return auth_async_done_iid(&IID_IAsyncOpObj, S_OK, pv, out);
    }
}

static HRESULT __stdcall user_GetPropertiesAsync(void *self, void *values, void **out)
{
    (void)self; (void)values;
    if (out) *out = NULL;
    return E_NOTIMPL;
}

static HRESULT __stdcall user_GetPictureAsync(void *self, int size, void **out)
{
    (void)self; (void)size;
    return auth_async_done_iid(&IID_IAsyncOpStreamRef, S_OK, NULL, out);
}

static HRESULT __stdcall user2_CheckUserAgeConsentGroupAsync(void *self, int group, void **out)
{
    (void)self; (void)group;
    if (out) *out = NULL;
    return E_NOTIMPL;
}

static const void *user_vtbl[] = {
    (const void *)user_QI, (const void *)user_AddRef, (const void *)user_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)user_get_NonRoamableId,
    (const void *)user_get_AuthenticationStatus,
    (const void *)user_get_Type,
    (const void *)user_GetPropertyAsync,
    (const void *)user_GetPropertiesAsync,
    (const void *)user_GetPictureAsync,
};

static const void *user2_vtbl[] = {
    (const void *)user_QI, (const void *)user_AddRef, (const void *)user_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)user2_CheckUserAgeConsentGroupAsync,
};

/* UserChangedEventArgs */
typedef struct {
    auth_obj base;
    void *user;
} user_changed_args;

static ULONG __stdcall ucea_Release(void *self)
{
    user_changed_args *a = (user_changed_args *)self;
    LONG left = InterlockedDecrement(&a->base.refs);
    if (left <= 0) {
        if (a->user) com_release(a->user);
        free(a);
    }
    return (ULONG)left;
}

static HRESULT __stdcall ucea_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IUserChangedEA)) {
        *out = self;
        obj_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall ucea_get_User(void *self, void **out)
{
    user_changed_args *a = (user_changed_args *)self;
    if (!out) return E_POINTER;
    *out = a->user;
    if (a->user) com_addref(a->user);
    return S_OK;
}

static const void *ucea_vtbl[] = {
    (const void *)ucea_QI, (const void *)obj_AddRef, (const void *)ucea_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)ucea_get_User,
};

/* UserWatcher */
#define UW_MAX_HANDLERS 16

typedef struct {
    INT64 token;
    void *handler;
} uw_sub;

typedef struct {
    auth_obj base;
    CRITICAL_SECTION lock;
    int status;
    UINT32 generation;
    INT64 next_token;
    uw_sub added[UW_MAX_HANDLERS];
    uw_sub enum_done[UW_MAX_HANDLERS];
    uw_sub stopped[UW_MAX_HANDLERS];
} user_watcher;

static void uw_subs_clear(uw_sub *subs)
{
    int i;
    for (i = 0; i < UW_MAX_HANDLERS; i++) {
        if (subs[i].handler) {
            com_release(subs[i].handler);
            subs[i].handler = NULL;
            subs[i].token = 0;
        }
    }
}

static HRESULT uw_subs_add(user_watcher *w, uw_sub *subs, void *h, INT64 *tok)
{
    int i;
    if (!h) return E_INVALIDARG;
    EnterCriticalSection(&w->lock);
    for (i = 0; i < UW_MAX_HANDLERS; i++) {
        if (!subs[i].handler) {
            INT64 id = ++w->next_token;
            com_addref(h);
            subs[i].token = id;
            subs[i].handler = h;
            LeaveCriticalSection(&w->lock);
            if (tok) *tok = id;
            return S_OK;
        }
    }
    LeaveCriticalSection(&w->lock);
    return E_OUTOFMEMORY;
}

static HRESULT uw_subs_rem(user_watcher *w, uw_sub *subs, INT64 tok)
{
    int i;
    void *victim = NULL;
    EnterCriticalSection(&w->lock);
    for (i = 0; i < UW_MAX_HANDLERS; i++) {
        if (subs[i].handler && subs[i].token == tok) {
            victim = subs[i].handler;
            subs[i].handler = NULL;
            subs[i].token = 0;
            break;
        }
    }
    LeaveCriticalSection(&w->lock);
    if (victim) com_release(victim);
    return S_OK;
}

static int uw_subs_snapshot(user_watcher *w, uw_sub *subs, void **out_arr)
{
    int i, n = 0;
    for (i = 0; i < UW_MAX_HANDLERS; i++) {
        if (subs[i].handler) {
            com_addref(subs[i].handler);
            out_arr[n++] = subs[i].handler;
        }
    }
    (void)w;
    return n;
}

static ULONG __stdcall uw_Release(void *self)
{
    user_watcher *w = (user_watcher *)self;
    LONG left = InterlockedDecrement(&w->base.refs);
    if (left <= 0) {
        uw_subs_clear(w->added);
        uw_subs_clear(w->enum_done);
        uw_subs_clear(w->stopped);
        DeleteCriticalSection(&w->lock);
        free(w);
    }
    return (ULONG)left;
}

static HRESULT __stdcall uw_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IUserWatcher)) {
        *out = self;
        obj_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall uw_get_Status(void *self, int *out)
{
    user_watcher *w = (user_watcher *)self;
    if (!out) return E_POINTER;
    EnterCriticalSection(&w->lock);
    *out = w->status;
    LeaveCriticalSection(&w->lock);
    return S_OK;
}

static HRESULT __stdcall uw_Start(void *self)
{
    typedef HRESULT(__stdcall *ev_fn)(void *, void *, void *);
    user_watcher *w = (user_watcher *)self;
    void *snap[UW_MAX_HANDLERS];
    int i, n;
    UINT32 gen;

    obj_AddRef(w);
    EnterCriticalSection(&w->lock);
    w->status = WATCHER_STARTED;
    gen = ++w->generation;
    n = uw_subs_snapshot(w, w->added, snap);
    LeaveCriticalSection(&w->lock);

    if (n > 0) {
        user_changed_args *args = (user_changed_args *)calloc(1, sizeof(user_changed_args));
        if (args) {
            args->base.vtbl = ucea_vtbl;
            args->base.refs = 1;
            args->base.name = "Windows.System.UserChangedEventArgs";
            args->user = &g_user_obj.base;
            com_addref(args->user);
            for (i = 0; i < n; i++) {
                int still_active;
                EnterCriticalSection(&w->lock);
                still_active = (w->status == WATCHER_STARTED && w->generation == gen);
                LeaveCriticalSection(&w->lock);
                if (still_active) {
                    ev_fn *slots = *(ev_fn **)snap[i];
                    if (slots && slots[3]) slots[3](snap[i], w, args);
                }
                com_release(snap[i]);
            }
            ucea_Release(args);
        } else {
            for (i = 0; i < n; i++) com_release(snap[i]);
        }
    }

    EnterCriticalSection(&w->lock);
    if (w->status == WATCHER_STARTED && w->generation == gen) {
        w->status = WATCHER_ENUMDONE;
        n = uw_subs_snapshot(w, w->enum_done, snap);
    } else {
        n = 0;
    }
    LeaveCriticalSection(&w->lock);

    for (i = 0; i < n; i++) {
        ev_fn *slots = *(ev_fn **)snap[i];
        if (slots && slots[3]) slots[3](snap[i], w, NULL);
        com_release(snap[i]);
    }
    uw_Release(w);
    return S_OK;
}

static HRESULT __stdcall uw_Stop(void *self)
{
    typedef HRESULT(__stdcall *ev_fn)(void *, void *, void *);
    user_watcher *w = (user_watcher *)self;
    void *snap[UW_MAX_HANDLERS];
    int i, n;
    obj_AddRef(w);
    EnterCriticalSection(&w->lock);
    w->status = WATCHER_STOPPED;
    w->generation++;
    n = uw_subs_snapshot(w, w->stopped, snap);
    LeaveCriticalSection(&w->lock);
    for (i = 0; i < n; i++) {
        ev_fn *slots = *(ev_fn **)snap[i];
        if (slots && slots[3]) slots[3](snap[i], w, NULL);
        com_release(snap[i]);
    }
    uw_Release(w);
    return S_OK;
}

static HRESULT __stdcall uw_add_Added(void *self, void *h, INT64 *tok)
{
    return uw_subs_add((user_watcher *)self, ((user_watcher *)self)->added, h, tok);
}

static HRESULT __stdcall uw_rem_Added(void *self, INT64 tok)
{
    return uw_subs_rem((user_watcher *)self, ((user_watcher *)self)->added, tok);
}

static HRESULT __stdcall uw_add_noop(void *self, void *h, INT64 *tok)
{
    user_watcher *w = (user_watcher *)self;
    (void)h;
    EnterCriticalSection(&w->lock);
    if (tok) *tok = ++w->next_token;
    LeaveCriticalSection(&w->lock);
    return S_OK;
}

static HRESULT __stdcall uw_rem_noop(void *self, INT64 tok) { (void)self; (void)tok; return S_OK; }

static HRESULT __stdcall uw_add_EnumDone(void *self, void *h, INT64 *tok)
{
    return uw_subs_add((user_watcher *)self, ((user_watcher *)self)->enum_done, h, tok);
}

static HRESULT __stdcall uw_rem_EnumDone(void *self, INT64 tok)
{
    return uw_subs_rem((user_watcher *)self, ((user_watcher *)self)->enum_done, tok);
}

static HRESULT __stdcall uw_add_Stopped(void *self, void *h, INT64 *tok)
{
    return uw_subs_add((user_watcher *)self, ((user_watcher *)self)->stopped, h, tok);
}

static HRESULT __stdcall uw_rem_Stopped(void *self, INT64 tok)
{
    return uw_subs_rem((user_watcher *)self, ((user_watcher *)self)->stopped, tok);
}

static const void *uw_vtbl[] = {
    (const void *)uw_QI, (const void *)obj_AddRef, (const void *)uw_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)uw_get_Status,       /* 6 */
    (const void *)uw_Start,            /* 7 */
    (const void *)uw_Stop,             /* 8 */
    (const void *)uw_add_Added,        /* 9 */
    (const void *)uw_rem_Added,        /* 10 */
    (const void *)uw_add_noop,         /* 11 Removed */
    (const void *)uw_rem_noop,         /* 12 */
    (const void *)uw_add_noop,         /* 13 Updated */
    (const void *)uw_rem_noop,         /* 14 */
    (const void *)uw_add_noop,         /* 15 AuthStatusChanged */
    (const void *)uw_rem_noop,         /* 16 */
    (const void *)uw_add_noop,         /* 17 AuthStatusChanging */
    (const void *)uw_rem_noop,         /* 18 */
    (const void *)uw_add_EnumDone,     /* 19 EnumerationCompleted */
    (const void *)uw_rem_EnumDone,     /* 20 */
    (const void *)uw_add_Stopped,      /* 21 Stopped */
    (const void *)uw_rem_Stopped,      /* 22 */
};

static auth_obj *auth_class_factory_for_statics(const void *statics);

/* IUserStatics & IUserStatics2 */
static auth_obj g_user_statics;
static auth_obj g_user_statics2;

static HRESULT __stdcall user_statics_QI(void *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IFactory_a)) {
        auth_obj *fac = auth_class_factory_for_statics(&g_user_statics);
        *out = fac ? fac : &g_user_statics;
        obj_AddRef(*out);
        return S_OK;
    }
    if (IsEqualIID(id, &IID_IUserStatics)) {
        *out = &g_user_statics;
        obj_AddRef(&g_user_statics);
        return S_OK;
    }
    if (IsEqualIID(id, &IID_IUserStatics2)) {
        *out = &g_user_statics2;
        obj_AddRef(&g_user_statics2);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall user_CreateWatcher(void *self, void **out)
{
    user_watcher *w;
    (void)self;
    if (!out) return E_POINTER;
    auth_tracef("User::CreateWatcher");
    w = (user_watcher *)calloc(1, sizeof(user_watcher));
    if (!w) return E_OUTOFMEMORY;
    InitializeCriticalSection(&w->lock);
    w->base.vtbl = uw_vtbl;
    w->base.refs = 1;
    w->base.name = "Windows.System.UserWatcher";
    w->status = WATCHER_CREATED;
    *out = w;
    return S_OK;
}

typedef struct {
    auth_async *op;
    int include_user;
} find_users_ctx;

static DWORD WINAPI find_users_thread(LPVOID param)
{
    find_users_ctx *ctx = (find_users_ctx *)param;
    vec_view *vec;
    HRESULT com_hr = E_FAIL;
    auth_ole_init();
    if (fn_CoInitializeEx) com_hr = fn_CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (ctx->include_user) ensure_identity_warm();
    vec = vec_view_new(&IID_IVecViewUser, &IID_IIterableUser, &IID_IIteratorUser,
                       ctx->include_user ? &g_user_obj.base : NULL);
    auth_async_complete(ctx->op, vec ? S_OK : E_OUTOFMEMORY, vec);
    auth_async_Release(ctx->op);
    free(ctx);
    if (SUCCEEDED(com_hr) && fn_CoUninitialize) fn_CoUninitialize();
    return 0;
}

static HRESULT start_find_users(int include_user, void **out)
{
    auth_async *op;
    find_users_ctx *ctx;
    HANDLE th;
    if (!out) return E_POINTER;
    op = auth_async_create_iid(1, &IID_IAsyncOpVecUser, 1, S_OK, NULL);
    if (!op) return E_OUTOFMEMORY;
    ctx = (find_users_ctx *)calloc(1, sizeof(find_users_ctx));
    if (!ctx) { auth_async_Release(op); return E_OUTOFMEMORY; }
    ctx->op = op;
    ctx->include_user = include_user;
    auth_async_AddRef(op);
    th = CreateThread(NULL, 0, find_users_thread, ctx, 0, NULL);
    if (th) {
        CloseHandle(th);
    } else {
        find_users_thread(ctx);
    }
    *out = op;
    return S_OK;
}

static HRESULT __stdcall user_FindAllAsync(void *self, void **out)
{
    (void)self;
    auth_log("User::FindAllAsync()");
    return start_find_users(1, out);
}

static HRESULT __stdcall user_FindAllAsyncByType(void *self, int type, void **out)
{
    (void)self;
    auth_log("User::FindAllAsync(type=%d)", type);
    return start_find_users(type == USER_TYPE_LOCAL, out);
}

static HRESULT __stdcall user_FindAllAsyncByTypeAndStatus(void *self, int type, int status, void **out)
{
    (void)self;
    auth_log("User::FindAllAsync(type=%d, status=%d)", type, status);
    return start_find_users(type == USER_TYPE_LOCAL && status == USER_AUTH_LOCAL, out);
}

static HRESULT __stdcall user_GetFromId(void *self, hstring_t id, void **out)
{
    char expected[128];
    char *req_id;
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    auth_tracef("User::GetFromId");
    req_id = hstr_to_utf8(id);
    if (!req_id) return E_OUTOFMEMORY;
    get_user_id_str(expected, sizeof expected);
    if (expected[0] && _stricmp(req_id, expected) == 0) {
        *out = &g_user_obj.base;
        user_AddRef(&g_user_obj);
    }
    free(req_id);
    return S_OK;
}

static HRESULT __stdcall user_GetDefault(void *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    auth_log("User::GetDefault()");
    ensure_identity_warm();
    *out = &g_user_obj.base;
    user_AddRef(&g_user_obj);
    return S_OK;
}

static const void *user_statics_vtbl[] = {
    (const void *)user_statics_QI, (const void *)obj_AddRef, (const void *)obj_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)user_CreateWatcher,
    (const void *)user_FindAllAsync,
    (const void *)user_FindAllAsyncByType,
    (const void *)user_FindAllAsyncByTypeAndStatus,
    (const void *)user_GetFromId,
};

static const void *user_statics2_vtbl[] = {
    (const void *)user_statics_QI, (const void *)obj_AddRef, (const void *)obj_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)user_GetDefault,
};

/* --------------------------------------------------------------- KnownUserProperties */

static auth_obj g_known_user_props;

static HRESULT __stdcall kup_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IFactory_a)) {
        auth_obj *fac = auth_class_factory_for_statics(&g_known_user_props);
        *out = fac ? fac : self;
        obj_AddRef(*out);
        return S_OK;
    }
    if (IsEqualIID(id, &IID_IKnownUserProps)) {
        *out = self;
        obj_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

#define KUP_GETTER(fn, str) \
    static HRESULT __stdcall fn(void *self, hstring_t *out) { \
        (void)self; if (!out) return E_POINTER; *out = hstr_from_w(str); return S_OK; \
    }

KUP_GETTER(kup_DisplayName, L"DisplayName")
KUP_GETTER(kup_FirstName, L"FirstName")
KUP_GETTER(kup_LastName, L"LastName")
KUP_GETTER(kup_ProviderName, L"ProviderName")
KUP_GETTER(kup_AccountName, L"AccountName")
KUP_GETTER(kup_GuestHost, L"GuestHost")
KUP_GETTER(kup_PrincipalName, L"PrincipalName")
KUP_GETTER(kup_DomainName, L"DomainName")
KUP_GETTER(kup_SipUri, L"SessionInitiationProtocolUri")

static const void *kup_vtbl[] = {
    (const void *)kup_QI, (const void *)obj_AddRef, (const void *)obj_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)kup_DisplayName, (const void *)kup_FirstName, (const void *)kup_LastName,
    (const void *)kup_ProviderName, (const void *)kup_AccountName, (const void *)kup_GuestHost,
    (const void *)kup_PrincipalName, (const void *)kup_DomainName, (const void *)kup_SipUri,
};

/* --------------------------------------------------------------- UserPicker */

static auth_obj g_user_picker_statics;

typedef struct {
    auth_obj base;
    BOOLEAN allow_guest;
    void *suggested;
} user_picker_obj;

static ULONG __stdcall up_Release(void *self)
{
    user_picker_obj *p = (user_picker_obj *)self;
    LONG left = InterlockedDecrement(&p->base.refs);
    if (left <= 0) {
        if (p->suggested) com_release(p->suggested);
        free(p);
    }
    return (ULONG)left;
}

static HRESULT __stdcall up_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IUserPicker)) {
        *out = self;
        obj_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall up_get_AllowGuest(void *self, BOOLEAN *out)
{
    if (out) *out = ((user_picker_obj *)self)->allow_guest;
    return S_OK;
}
static HRESULT __stdcall up_put_AllowGuest(void *self, BOOLEAN v)
{
    ((user_picker_obj *)self)->allow_guest = v;
    return S_OK;
}
static HRESULT __stdcall up_get_Suggested(void *self, void **out)
{
    user_picker_obj *p = (user_picker_obj *)self;
    if (!out) return E_POINTER;
    *out = p->suggested;
    if (p->suggested) com_addref(p->suggested);
    return S_OK;
}
static HRESULT __stdcall up_put_Suggested(void *self, void *u)
{
    user_picker_obj *p = (user_picker_obj *)self;
    if (p->suggested) com_release(p->suggested);
    p->suggested = u;
    if (u) com_addref(u);
    return S_OK;
}
static HRESULT __stdcall up_PickSingleUserAsync(void *self, void **out)
{
    (void)self;
    ensure_identity_warm();
    user_AddRef(&g_user_obj);
    return auth_async_done_iid(&IID_IAsyncOpUser, S_OK, &g_user_obj.base, out);
}

static const void *up_vtbl[] = {
    (const void *)up_QI, (const void *)obj_AddRef, (const void *)up_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)up_get_AllowGuest, (const void *)up_put_AllowGuest,
    (const void *)up_get_Suggested, (const void *)up_put_Suggested,
    (const void *)up_PickSingleUserAsync,
};

static HRESULT __stdcall up_statics_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IFactory_a)) {
        auth_obj *fac = auth_class_factory_for_statics(&g_user_picker_statics);
        *out = fac ? fac : self;
        obj_AddRef(*out);
        return S_OK;
    }
    if (IsEqualIID(id, &IID_IUserPickerStat)) {
        *out = self;
        obj_AddRef(self);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall up_IsSupported(void *self, BOOLEAN *out)
{
    (void)self;
    if (!out) return E_POINTER;
    /* On Windows desktop PC, UserPicker is unsupported so UWP titles pick the single local user directly. */
    *out = FALSE;
    return S_OK;
}

static const void *up_statics_vtbl[] = {
    (const void *)up_statics_QI, (const void *)obj_AddRef, (const void *)obj_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)up_IsSupported,
};

/* --------------------------------------------------------------- WebAccountProvider */

typedef struct {
    auth_obj base;  /* IWebAccountProvider */
    auth_obj prov2; /* IWebAccountProvider2 */
    auth_obj prov3; /* IWebAccountProvider3 */
    auth_obj prov4; /* IWebAccountProvider4 */
    char *id;
    char *display_name;
    char *authority;
    void *user;
} web_provider;

static const void *wap_vtbl[];
static const void *wap2_vtbl[];
static const void *wap3_vtbl[];
static const void *wap4_vtbl[];

static web_provider *wap_from(void *self)
{
    auth_obj *h = (auth_obj *)self;
    if (h && h->vtbl == wap2_vtbl) return (web_provider *)((char *)self - offsetof(web_provider, prov2));
    if (h && h->vtbl == wap3_vtbl) return (web_provider *)((char *)self - offsetof(web_provider, prov3));
    if (h && h->vtbl == wap4_vtbl) return (web_provider *)((char *)self - offsetof(web_provider, prov4));
    return (web_provider *)self;
}

static ULONG __stdcall wap_AddRef(void *self)
{
    return (ULONG)InterlockedIncrement(&wap_from(self)->base.refs);
}

static ULONG __stdcall wap_Release(void *self)
{
    web_provider *p = wap_from(self);
    LONG left = InterlockedDecrement(&p->base.refs);
    if (left <= 0) {
        free(p->id);
        free(p->display_name);
        free(p->authority);
        if (p->user) com_release(p->user);
        free(p);
    }
    return (ULONG)left;
}

static HRESULT __stdcall wap_QI(void *self, const GUID *id, void **out)
{
    web_provider *p = wap_from(self);
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IWebAccProv)) {
        *out = &p->base; wap_AddRef(p); return S_OK;
    }
    if (IsEqualIID(id, &IID_IWebAccProv2)) { *out = &p->prov2; wap_AddRef(p); return S_OK; }
    if (IsEqualIID(id, &IID_IWebAccProv3)) { *out = &p->prov3; wap_AddRef(p); return S_OK; }
    if (IsEqualIID(id, &IID_IWebAccProv4)) { *out = &p->prov4; wap_AddRef(p); return S_OK; }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall wap_get_Id(void *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(wap_from(self)->id);
    return S_OK;
}
static HRESULT __stdcall wap_get_DisplayName(void *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(wap_from(self)->display_name);
    return S_OK;
}
static HRESULT __stdcall wap_get_IconUri(void *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    return S_OK;
}
static HRESULT __stdcall wap2_get_DisplayPurpose(void *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    return S_OK;
}
static HRESULT __stdcall wap2_get_Authority(void *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(wap_from(self)->authority);
    return S_OK;
}
static HRESULT __stdcall wap3_get_User(void *self, void **out)
{
    web_provider *p = wap_from(self);
    if (!out) return E_POINTER;
    *out = p->user;
    if (p->user) com_addref(p->user);
    return S_OK;
}
static HRESULT __stdcall wap4_get_IsSystemProvider(void *self, BOOLEAN *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = TRUE;
    return S_OK;
}

static const void *wap_vtbl[] = {
    (const void *)wap_QI, (const void *)wap_AddRef, (const void *)wap_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wap_get_Id, (const void *)wap_get_DisplayName, (const void *)wap_get_IconUri,
};
static const void *wap2_vtbl[] = {
    (const void *)wap_QI, (const void *)wap_AddRef, (const void *)wap_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wap2_get_DisplayPurpose, (const void *)wap2_get_Authority,
};
static const void *wap3_vtbl[] = {
    (const void *)wap_QI, (const void *)wap_AddRef, (const void *)wap_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wap3_get_User,
};
static const void *wap4_vtbl[] = {
    (const void *)wap_QI, (const void *)wap_AddRef, (const void *)wap_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wap4_get_IsSystemProvider,
};

static web_provider *wap_new(const char *id, const char *authority, void *user)
{
    web_provider *p = (web_provider *)calloc(1, sizeof(web_provider));
    if (!p) return NULL;
    p->base.vtbl = wap_vtbl; p->base.refs = 1; p->base.name = "Windows.Security.Credentials.WebAccountProvider";
    p->prov2.vtbl = wap2_vtbl; p->prov2.refs = 1; p->prov2.permanent = 1; p->prov2.name = p->base.name;
    p->prov3.vtbl = wap3_vtbl; p->prov3.refs = 1; p->prov3.permanent = 1; p->prov3.name = p->base.name;
    p->prov4.vtbl = wap4_vtbl; p->prov4.refs = 1; p->prov4.permanent = 1; p->prov4.name = p->base.name;
    p->id = strdup(id && id[0] ? id : "https://xsts.auth.xboxlive.com");
    p->display_name = strdup("Xbox Live");
    p->authority = strdup(authority && authority[0] ? authority : "consumers");
    p->user = user ? user : &g_user_obj.base;
    com_addref(p->user);
    return p;
}

/* --------------------------------------------------------------- WebAccount */

typedef struct {
    auth_obj base;  /* IWebAccount */
    auth_obj acct2; /* IWebAccount2 */
    web_provider *provider;
    char *id;
    char *username;
    str_map *props;
} web_account;

static const void *wa_vtbl[];
static const void *wa2_vtbl[];

static web_account *wa_from(void *self)
{
    auth_obj *h = (auth_obj *)self;
    if (h && h->vtbl == wa2_vtbl) return (web_account *)((char *)self - offsetof(web_account, acct2));
    return (web_account *)self;
}

static ULONG __stdcall wa_AddRef(void *self)
{
    return (ULONG)InterlockedIncrement(&wa_from(self)->base.refs);
}

static ULONG __stdcall wa_Release(void *self)
{
    web_account *a = wa_from(self);
    LONG left = InterlockedDecrement(&a->base.refs);
    if (left <= 0) {
        if (a->provider) wap_Release(a->provider);
        free(a->id);
        free(a->username);
        if (a->props) str_map_Release(a->props);
        free(a);
    }
    return (ULONG)left;
}

static HRESULT __stdcall wa_QI(void *self, const GUID *id, void **out)
{
    web_account *a = wa_from(self);
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IWebAccount)) {
        *out = &a->base; wa_AddRef(a); return S_OK;
    }
    if (IsEqualIID(id, &IID_IWebAccount2)) { *out = &a->acct2; wa_AddRef(a); return S_OK; }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall wa_get_Provider(void *self, void **out)
{
    web_account *a = wa_from(self);
    if (!out) return E_POINTER;
    *out = a->provider;
    if (a->provider) wap_AddRef(a->provider);
    return S_OK;
}
static HRESULT __stdcall wa_get_UserName(void *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(wa_from(self)->username);
    return S_OK;
}
static HRESULT __stdcall wa_get_State(void *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = WEB_ACCOUNT_CONNECTED;
    return S_OK;
}
static HRESULT __stdcall wa2_get_Id(void *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(wa_from(self)->id);
    return S_OK;
}
static HRESULT __stdcall wa2_get_Properties(void *self, void **out)
{
    web_account *a = wa_from(self);
    if (!out) return E_POINTER;
    *out = &a->props->view;
    str_map_AddRef(a->props);
    return S_OK;
}
static HRESULT __stdcall wa2_GetPictureAsync(void *self, int sz, void **out)
{
    (void)self; (void)sz;
    return auth_async_done_iid(&IID_IAsyncOpStream, S_OK, NULL, out);
}
static HRESULT __stdcall wa2_SignOutAsync(void *self, void **out)
{
    auth_async *op;
    (void)self;
    if (!out) return E_POINTER;
    auth_init_once();
    EnterCriticalSection(&g_auth_lock);
    g_xsts_count = 0;
    memset(g_xsts_cache, 0, sizeof g_xsts_cache);
    g_xbl_user_token[0] = '\0';
    g_xbl_title_token[0] = '\0';
    g_xbl_user_expiry = 0;
    LeaveCriticalSection(&g_auth_lock);
    op = auth_async_create_iid(0, &IID_IAsyncAction_a, 0, S_OK, NULL);
    if (!op) return E_OUTOFMEMORY;
    *out = op;
    return S_OK;
}
static HRESULT __stdcall wa2_SignOutWithClientIdAsync(void *self, hstring_t cid, void **out)
{
    (void)cid;
    return wa2_SignOutAsync(self, out);
}

static const void *wa_vtbl[] = {
    (const void *)wa_QI, (const void *)wa_AddRef, (const void *)wa_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wa_get_Provider, (const void *)wa_get_UserName, (const void *)wa_get_State,
};
static const void *wa2_vtbl[] = {
    (const void *)wa_QI, (const void *)wa_AddRef, (const void *)wa_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wa2_get_Id, (const void *)wa2_get_Properties,
    (const void *)wa2_GetPictureAsync, (const void *)wa2_SignOutAsync,
    (const void *)wa2_SignOutWithClientIdAsync,
};

static web_account *wa_new(web_provider *provider, const char *xuid, const char *gamertag)
{
    web_account *a = (web_account *)calloc(1, sizeof(web_account));
    if (!a) return NULL;
    a->base.vtbl = wa_vtbl; a->base.refs = 1; a->base.name = "Windows.Security.Credentials.WebAccount";
    a->acct2.vtbl = wa2_vtbl; a->acct2.refs = 1; a->acct2.permanent = 1; a->acct2.name = a->base.name;
    a->provider = provider ? provider : wap_new("https://xsts.auth.xboxlive.com", "consumers", NULL);
    if (provider) wap_AddRef(provider);
    a->id = strdup(xuid && xuid[0] ? xuid : "0");
    a->username = strdup(gamertag && gamertag[0] ? gamertag : "Player");
    a->props = str_map_new();
    if (!a->provider || !a->id || !a->username || !a->props) {
        wa_Release(a);
        return NULL;
    }
    if ((xuid && xuid[0] && str_map_set_utf8(a->props, "XboxUserId", xuid) < 0) ||
        (gamertag && gamertag[0] && str_map_set_utf8(a->props, "Gamertag", gamertag) < 0)) {
        wa_Release(a);
        return NULL;
    }
    return a;
}

/* --------------------------------------------------------------- WebTokenRequest */

typedef struct {
    auth_obj base; /* IWebTokenRequest */
    auth_obj req2; /* IWebTokenRequest2 */
    auth_obj req3; /* IWebTokenRequest3 */
    void *provider;
    char *scope;
    char *client_id;
    char *corr_id;
    int prompt_type;
    str_map *props;
    str_map *app_props;
} web_token_req;

static const void *wtr_vtbl[];
static const void *wtr2_vtbl[];
static const void *wtr3_vtbl[];

static web_token_req *wtr_from(void *self)
{
    auth_obj *h = (auth_obj *)self;
    if (h && h->vtbl == wtr2_vtbl) return (web_token_req *)((char *)self - offsetof(web_token_req, req2));
    if (h && h->vtbl == wtr3_vtbl) return (web_token_req *)((char *)self - offsetof(web_token_req, req3));
    return (web_token_req *)self;
}

static ULONG __stdcall wtr_AddRef(void *self)
{
    return (ULONG)InterlockedIncrement(&wtr_from(self)->base.refs);
}

static ULONG __stdcall wtr_Release(void *self)
{
    web_token_req *r = wtr_from(self);
    LONG left = InterlockedDecrement(&r->base.refs);
    if (left <= 0) {
        if (r->provider) com_release(r->provider);
        free(r->scope);
        free(r->client_id);
        free(r->corr_id);
        if (r->props) str_map_Release(r->props);
        if (r->app_props) str_map_Release(r->app_props);
        free(r);
    }
    return (ULONG)left;
}

static HRESULT __stdcall wtr_QI(void *self, const GUID *id, void **out)
{
    web_token_req *r = wtr_from(self);
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IWtr)) {
        *out = &r->base; wtr_AddRef(r); return S_OK;
    }
    if (IsEqualIID(id, &IID_IWtr2)) { *out = &r->req2; wtr_AddRef(r); return S_OK; }
    if (IsEqualIID(id, &IID_IWtr3)) { *out = &r->req3; wtr_AddRef(r); return S_OK; }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall wtr_get_Provider(void *self, void **out)
{
    web_token_req *r = wtr_from(self);
    if (!out) return E_POINTER;
    *out = r->provider;
    if (r->provider) com_addref(r->provider);
    return S_OK;
}
static HRESULT __stdcall wtr_get_Scope(void *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(wtr_from(self)->scope);
    return S_OK;
}
static HRESULT __stdcall wtr_get_ClientId(void *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(wtr_from(self)->client_id);
    return S_OK;
}
static HRESULT __stdcall wtr_get_PromptType(void *self, int *out)
{
    if (!out) return E_POINTER;
    *out = wtr_from(self)->prompt_type;
    return S_OK;
}
static HRESULT __stdcall wtr_get_Properties(void *self, void **out)
{
    web_token_req *r = wtr_from(self);
    if (!out) return E_POINTER;
    *out = &r->props->base;
    str_map_AddRef(r->props);
    return S_OK;
}
static HRESULT __stdcall wtr2_get_AppProperties(void *self, void **out)
{
    web_token_req *r = wtr_from(self);
    if (!out) return E_POINTER;
    *out = &r->app_props->base;
    str_map_AddRef(r->app_props);
    return S_OK;
}
static HRESULT __stdcall wtr3_get_CorrelationId(void *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(wtr_from(self)->corr_id);
    return S_OK;
}
static HRESULT __stdcall wtr3_put_CorrelationId(void *self, hstring_t val)
{
    web_token_req *r = wtr_from(self);
    free(r->corr_id);
    r->corr_id = hstr_to_utf8(val);
    return S_OK;
}

static const void *wtr_vtbl[] = {
    (const void *)wtr_QI, (const void *)wtr_AddRef, (const void *)wtr_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wtr_get_Provider, (const void *)wtr_get_Scope, (const void *)wtr_get_ClientId,
    (const void *)wtr_get_PromptType, (const void *)wtr_get_Properties,
};
static const void *wtr2_vtbl[] = {
    (const void *)wtr_QI, (const void *)wtr_AddRef, (const void *)wtr_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wtr2_get_AppProperties,
};
static const void *wtr3_vtbl[] = {
    (const void *)wtr_QI, (const void *)wtr_AddRef, (const void *)wtr_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wtr3_get_CorrelationId, (const void *)wtr3_put_CorrelationId,
};

static HRESULT wtr_create_internal(void *provider, hstring_t scope, hstring_t client_id,
                                   int prompt_type, void **out)
{
    web_token_req *r;
    if (!out) return E_POINTER;
    r = (web_token_req *)calloc(1, sizeof(web_token_req));
    if (!r) return E_OUTOFMEMORY;
    r->base.vtbl = wtr_vtbl; r->base.refs = 1;
    r->base.name = "Windows.Security.Authentication.Web.Core.WebTokenRequest";
    r->req2.vtbl = wtr2_vtbl; r->req2.refs = 1; r->req2.permanent = 1; r->req2.name = r->base.name;
    r->req3.vtbl = wtr3_vtbl; r->req3.refs = 1; r->req3.permanent = 1; r->req3.name = r->base.name;
    r->provider = provider;
    if (provider) com_addref(provider);
    r->scope = hstr_to_utf8(scope);
    r->client_id = hstr_to_utf8(client_id);
    r->corr_id = strdup("");
    r->prompt_type = prompt_type;
    r->props = str_map_new();
    r->app_props = str_map_new();
    if (!r->scope || !r->client_id || !r->corr_id || !r->props || !r->app_props) {
        wtr_Release(r);
        *out = NULL;
        return E_OUTOFMEMORY;
    }
    *out = &r->base;
    return S_OK;
}

static auth_obj g_wtr_factory;

static HRESULT __stdcall wtr_factory_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IFactory_a)) {
        auth_obj *fac = auth_class_factory_for_statics(&g_wtr_factory);
        *out = fac ? fac : self;
        obj_AddRef(*out);
        return S_OK;
    }
    if (IsEqualIID(id, &IID_IWtrFactory)) {
        *out = self; obj_AddRef(self); return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall wtr_Create(void *self, void *prov, hstring_t scope, hstring_t cid, void **out)
{
    (void)self;
    return wtr_create_internal(prov, scope, cid, 0, out);
}
static HRESULT __stdcall wtr_CreateWithPrompt(void *self, void *prov, hstring_t scope, hstring_t cid,
                                              int prompt, void **out)
{
    (void)self;
    return wtr_create_internal(prov, scope, cid, prompt, out);
}
static HRESULT __stdcall wtr_CreateWithProvider(void *self, void *prov, void **out)
{
    (void)self;
    return wtr_create_internal(prov, NULL, NULL, 0, out);
}
static HRESULT __stdcall wtr_CreateWithScope(void *self, void *prov, hstring_t scope, void **out)
{
    (void)self;
    return wtr_create_internal(prov, scope, NULL, 0, out);
}

static const void *wtr_factory_vtbl[] = {
    (const void *)wtr_factory_QI, (const void *)obj_AddRef, (const void *)obj_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wtr_Create,
    (const void *)wtr_CreateWithPrompt,
    (const void *)wtr_CreateWithProvider,
    (const void *)wtr_CreateWithScope,
};

/* --------------------------------------------------------------- WebProviderError / Response / Result */

typedef struct {
    auth_obj base;
    UINT32 code;
    char *msg;
    str_map *props;
} web_prov_err;

static ULONG __stdcall wpe_Release(void *self)
{
    web_prov_err *e = (web_prov_err *)self;
    LONG left = InterlockedDecrement(&e->base.refs);
    if (left <= 0) {
        free(e->msg);
        if (e->props) str_map_Release(e->props);
        free(e);
    }
    return (ULONG)left;
}

static HRESULT __stdcall wpe_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IWebProvError)) {
        *out = self; obj_AddRef(self); return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall wpe_get_ErrorCode(void *self, UINT32 *out)
{
    if (out) *out = ((web_prov_err *)self)->code;
    return S_OK;
}
static HRESULT __stdcall wpe_get_ErrorMessage(void *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(((web_prov_err *)self)->msg);
    return S_OK;
}
static HRESULT __stdcall wpe_get_Properties(void *self, void **out)
{
    web_prov_err *e = (web_prov_err *)self;
    if (!out) return E_POINTER;
    *out = &e->props->base;
    str_map_AddRef(e->props);
    return S_OK;
}

static const void *wpe_vtbl[] = {
    (const void *)wpe_QI, (const void *)obj_AddRef, (const void *)wpe_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wpe_get_ErrorCode, (const void *)wpe_get_ErrorMessage, (const void *)wpe_get_Properties,
};

static web_prov_err *wpe_new(UINT32 code, const char *msg)
{
    web_prov_err *e = (web_prov_err *)calloc(1, sizeof(web_prov_err));
    if (!e) return NULL;
    e->base.vtbl = wpe_vtbl;
    e->base.refs = 1;
    e->base.name = "Windows.Security.Authentication.Web.Core.WebProviderError";
    e->code = code;
    e->msg = strdup(msg ? msg : "Authentication failed");
    e->props = str_map_new();
    if (!e->msg || !e->props) {
        wpe_Release(e);
        return NULL;
    }
    return e;
}

typedef struct {
    auth_obj base;
    char *token;
    web_prov_err *err;
    web_account *account;
    str_map *props;
} web_token_resp;

static ULONG __stdcall wtresp_Release(void *self)
{
    web_token_resp *r = (web_token_resp *)self;
    LONG left = InterlockedDecrement(&r->base.refs);
    if (left <= 0) {
        free(r->token);
        if (r->err) wpe_Release(r->err);
        if (r->account) wa_Release(r->account);
        if (r->props) str_map_Release(r->props);
        free(r);
    }
    return (ULONG)left;
}

static HRESULT __stdcall wtresp_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IWtrResponse)) {
        *out = self; obj_AddRef(self); return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall wtresp_get_Token(void *self, hstring_t *out)
{
    if (!out) return E_POINTER;
    *out = hstr_from_utf8(((web_token_resp *)self)->token);
    return S_OK;
}
static HRESULT __stdcall wtresp_get_ProviderError(void *self, void **out)
{
    web_token_resp *r = (web_token_resp *)self;
    if (!out) return E_POINTER;
    *out = r->err;
    if (r->err) obj_AddRef(r->err);
    return S_OK;
}
static HRESULT __stdcall wtresp_get_WebAccount(void *self, void **out)
{
    web_token_resp *r = (web_token_resp *)self;
    if (!out) return E_POINTER;
    *out = r->account;
    if (r->account) wa_AddRef(r->account);
    return S_OK;
}
static HRESULT __stdcall wtresp_get_Properties(void *self, void **out)
{
    web_token_resp *r = (web_token_resp *)self;
    if (!out) return E_POINTER;
    *out = &r->props->base;
    str_map_AddRef(r->props);
    return S_OK;
}

static const void *wtresp_vtbl[] = {
    (const void *)wtresp_QI, (const void *)obj_AddRef, (const void *)wtresp_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wtresp_get_Token, (const void *)wtresp_get_ProviderError,
    (const void *)wtresp_get_WebAccount, (const void *)wtresp_get_Properties,
};

typedef struct {
    auth_obj base;
    vec_view *data;
    int status;
    web_prov_err *err;
} web_token_result;

static ULONG __stdcall wtres_Release(void *self)
{
    web_token_result *r = (web_token_result *)self;
    LONG left = InterlockedDecrement(&r->base.refs);
    if (left <= 0) {
        if (r->data) vec_Release(r->data);
        if (r->err) wpe_Release(r->err);
        free(r);
    }
    return (ULONG)left;
}

static HRESULT __stdcall wtres_QI(void *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IWtrResult)) {
        *out = self; obj_AddRef(self); return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall wtres_get_ResponseData(void *self, void **out)
{
    web_token_result *r = (web_token_result *)self;
    if (!out) return E_POINTER;
    *out = r->data;
    if (r->data) vec_AddRef(r->data);
    return S_OK;
}
static HRESULT __stdcall wtres_get_ResponseStatus(void *self, int *out)
{
    if (!out) return E_POINTER;
    *out = ((web_token_result *)self)->status;
    return S_OK;
}
static HRESULT __stdcall wtres_get_ResponseError(void *self, void **out)
{
    web_token_result *r = (web_token_result *)self;
    if (!out) return E_POINTER;
    *out = r->err;
    if (r->err) obj_AddRef(r->err);
    return S_OK;
}
static HRESULT __stdcall wtres_InvalidateCacheAsync(void *self, void **out)
{
    auth_async *op;
    (void)self;
    if (!out) return E_POINTER;
    auth_init_once();
    EnterCriticalSection(&g_auth_lock);
    g_xsts_count = 0;
    LeaveCriticalSection(&g_auth_lock);
    op = auth_async_create_iid(0, &IID_IAsyncAction_a, 0, S_OK, NULL);
    if (!op) return E_OUTOFMEMORY;
    *out = op;
    return S_OK;
}

static const void *wtres_vtbl[] = {
    (const void *)wtres_QI, (const void *)obj_AddRef, (const void *)wtres_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wtres_get_ResponseData, (const void *)wtres_get_ResponseStatus,
    (const void *)wtres_get_ResponseError, (const void *)wtres_InvalidateCacheAsync,
};

/* --------------------------------------------------------------- WebAuthenticationCoreManager */

typedef struct {
    auth_async *op;
    web_token_req *req;
} token_work_ctx;

static DWORD WINAPI token_worker_thread(LPVOID param)
{
    token_work_ctx *ctx = (token_work_ctx *)param;
    web_token_req *req = ctx->req;
    char *prop_target = req ? str_map_get_utf8(req->props, "Target") : NULL;
    char *prop_url = req ? str_map_get_utf8(req->props, "Url") : NULL;
    char *prop_refresh = req ? str_map_get_utf8(req->props, "ForceRefresh") : NULL;
    int force_refresh = (prop_refresh && _stricmp(prop_refresh, "true") == 0);
    char rp[256];
    xsts_entry *xsts = (xsts_entry *)calloc(1, sizeof(xsts_entry));
    web_token_result *res = (web_token_result *)calloc(1, sizeof(web_token_result));
    web_provider *prov = NULL;
    int is_msa_provider = 0;
    HRESULT com_hr = E_FAIL;

    auth_ole_init();
    if (fn_CoInitializeEx) com_hr = fn_CoInitializeEx(NULL, COINIT_MULTITHREADED);

    if (req && req->provider && ((auth_obj *)req->provider)->vtbl == wap_vtbl) {
        prov = wap_from(req->provider);
        if (prov->id && strstr(prov->id, "login.microsoft") != NULL)
            is_msa_provider = 1;
    }

    normalize_relying_party(prop_target, prop_url, rp, sizeof rp);
    auth_log("WebAuthenticationCoreManager token request rp=%s refresh=%d msa=%d",
             rp, force_refresh, is_msa_provider);

    if (res) {
        res->base.vtbl = wtres_vtbl;
        res->base.refs = 1;
        res->base.name = "Windows.Security.Authentication.Web.Core.WebTokenRequestResult";

        if (is_msa_provider) {
            char *msa_copy = (char *)calloc(1, 8192);
            int ok;
            auth_init_once();
            EnterCriticalSection(&g_auth_lock);
            ok = fetch_msa_ticket_locked(force_refresh);
            if (ok && msa_copy) snprintf(msa_copy, 8192, "%s", g_msa_ticket);
            LeaveCriticalSection(&g_auth_lock);
            if (ok && msa_copy && msa_copy[0]) {
                web_token_resp *resp = (web_token_resp *)calloc(1, sizeof(web_token_resp));
                char xuid[64], gt[128];
                if (!resp) {
                    wtres_Release(res);
                    res = NULL;
                } else {
                    get_user_id_str(xuid, sizeof xuid);
                    get_gamertag_str(gt, sizeof gt);
                    resp->base.vtbl = wtresp_vtbl;
                    resp->base.refs = 1;
                    resp->base.name = "Windows.Security.Authentication.Web.Core.WebTokenResponse";
                    resp->token = strdup(msa_copy);
                    resp->account = wa_new(prov, xuid, gt);
                    resp->props = str_map_new();
                    if (!resp->token || !resp->account || !resp->props) {
                        wtresp_Release(resp);
                        wtres_Release(res);
                        res = NULL;
                    } else {
                        res->status = WTR_STATUS_SUCCESS;
                        res->data = vec_view_new(&IID_IVecViewWtrResp, &IID_IIterableWtrRsp,
                                                 &IID_IIteratorWtrRsp, resp);
                        wtresp_Release(resp);
                        if (!res->data) {
                            wtres_Release(res);
                            res = NULL;
                        }
                    }
                }
            } else {
                res->status = WTR_STATUS_PROVIDER_ERROR;
                res->err = wpe_new(0x80070525U, "MSA token unavailable");
                res->data = vec_view_new(&IID_IVecViewWtrResp, &IID_IIterableWtrRsp,
                                         &IID_IIteratorWtrRsp, NULL);
                if (!res->err || !res->data) {
                    wtres_Release(res);
                    res = NULL;
                }
            }
            free(msa_copy);
        } else if (xsts && acquire_xsts_entry(rp, force_refresh, xsts)) {
            char *header_tok = (char *)malloc(8400);
            web_token_resp *resp = (web_token_resp *)calloc(1, sizeof(web_token_resp));
            if (!header_tok || !resp) {
                free(header_tok);
                free(resp);
                wtres_Release(res);
                res = NULL;
            } else {
                int prop_ok = 1;
                snprintf(header_tok, 8400, "XBL3.0 x=%s;%s", xsts->uhs, xsts->token);
                resp->base.vtbl = wtresp_vtbl;
                resp->base.refs = 1;
                resp->base.name = "Windows.Security.Authentication.Web.Core.WebTokenResponse";
                resp->token = strdup(header_tok);
                free(header_tok);
                resp->account = wa_new(prov, xsts->xuid, xsts->gamertag);
                resp->props = str_map_new();
                if (!resp->token || !resp->account || !resp->props) {
                    prop_ok = 0;
                } else {
                    if (str_map_set_utf8(resp->props, "XboxUserId", xsts->xuid) < 0 ||
                        str_map_set_utf8(resp->props, "Gamertag", xsts->gamertag) < 0 ||
                        (xsts->age_group[0] &&
                         str_map_set_utf8(resp->props, "AgeGroup", xsts->age_group) < 0) ||
                        str_map_set_utf8(resp->props, "Environment", "prod") < 0 ||
                        str_map_set_utf8(resp->props, "Sandbox", "RETAIL") < 0 ||
                        str_map_set_utf8(resp->props, "Privileges", xsts->privileges) < 0 ||
                        str_map_set_utf8(resp->props, "UserHash", xsts->uhs) < 0)
                        prop_ok = 0;
                }
                if (!prop_ok) {
                    wtresp_Release(resp);
                    wtres_Release(res);
                    res = NULL;
                } else {
                    res->status = WTR_STATUS_SUCCESS;
                    res->data = vec_view_new(&IID_IVecViewWtrResp, &IID_IIterableWtrRsp,
                                             &IID_IIteratorWtrRsp, resp);
                    wtresp_Release(resp);
                    if (!res->data) {
                        wtres_Release(res);
                        res = NULL;
                    }
                }
            }
        } else {
            res->status = WTR_STATUS_PROVIDER_ERROR;
            res->err = wpe_new(0x8015dc00U, "Xbox Live XSTS authentication failed");
            res->data = vec_view_new(&IID_IVecViewWtrResp, &IID_IIterableWtrRsp,
                                     &IID_IIteratorWtrRsp, NULL);
            if (!res->err || !res->data) {
                wtres_Release(res);
                res = NULL;
            }
        }
    }

    free(xsts);

    free(prop_target);
    free(prop_url);
    free(prop_refresh);
    if (req) wtr_Release(req);

    auth_async_complete(ctx->op, res ? S_OK : E_OUTOFMEMORY, res);
    auth_async_Release(ctx->op);
    free(ctx);
    if (SUCCEEDED(com_hr) && fn_CoUninitialize) fn_CoUninitialize();
    return 0;
}

static HRESULT start_token_request(void *request, void **out)
{
    auth_async *op;
    token_work_ctx *ctx;
    HANDLE th;
    if (!out) return E_POINTER;
    op = auth_async_create_iid(1, &IID_IAsyncOpWtrResult, 1, S_OK, NULL);
    if (!op) return E_OUTOFMEMORY;
    ctx = (token_work_ctx *)calloc(1, sizeof(token_work_ctx));
    if (!ctx) { auth_async_Release(op); return E_OUTOFMEMORY; }
    ctx->op = op;
    if (request) {
        ctx->req = wtr_from(request);
        wtr_AddRef(ctx->req);
    }
    auth_async_AddRef(op);
    th = CreateThread(NULL, 0, token_worker_thread, ctx, 0, NULL);
    if (th) {
        CloseHandle(th);
    } else {
        token_worker_thread(ctx);
    }
    *out = op;
    return S_OK;
}

static auth_obj g_wacm_statics;
static auth_obj g_wacm_statics2;
static auth_obj g_wacm_statics3;
static auth_obj g_wacm_statics4;

static HRESULT __stdcall wacm_QI(void *self, const GUID *id, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IUnknown_a) || IsEqualIID(id, &IID_IInspectable_a) ||
        IsEqualIID(id, &IID_IAgileObject_a) ||
        IsEqualIID(id, &IID_IFactory_a)) {
        auth_obj *fac = auth_class_factory_for_statics(&g_wacm_statics);
        *out = fac ? fac : &g_wacm_statics;
        obj_AddRef(*out);
        return S_OK;
    }
    if (IsEqualIID(id, &IID_IWacmStatics))  { *out = &g_wacm_statics;  obj_AddRef(&g_wacm_statics);  return S_OK; }
    if (IsEqualIID(id, &IID_IWacmStatics2)) { *out = &g_wacm_statics2; obj_AddRef(&g_wacm_statics2); return S_OK; }
    if (IsEqualIID(id, &IID_IWacmStatics3)) { *out = &g_wacm_statics3; obj_AddRef(&g_wacm_statics3); return S_OK; }
    if (IsEqualIID(id, &IID_IWacmStatics4)) { *out = &g_wacm_statics4; obj_AddRef(&g_wacm_statics4); return S_OK; }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall wacm_GetTokenSilentlyAsync(void *self, void *req, void **out)
{
    (void)self;
    return start_token_request(req, out);
}
static HRESULT __stdcall wacm_GetTokenSilentlyWithAccountAsync(void *self, void *req, void *acct, void **out)
{
    (void)self; (void)acct;
    return start_token_request(req, out);
}
static HRESULT __stdcall wacm_RequestTokenAsync(void *self, void *req, void **out)
{
    (void)self;
    return start_token_request(req, out);
}
static HRESULT __stdcall wacm_RequestTokenWithAccountAsync(void *self, void *req, void *acct, void **out)
{
    (void)self; (void)acct;
    return start_token_request(req, out);
}
static HRESULT __stdcall wacm_FindAccountAsync(void *self, void *prov, hstring_t acct_id, void **out)
{
    char xuid[64], gt[128];
    web_provider *p = (prov && ((auth_obj *)prov)->vtbl == wap_vtbl) ? wap_from(prov) : NULL;
    (void)self; (void)acct_id;
    ensure_identity_warm();
    get_user_id_str(xuid, sizeof xuid);
    get_gamertag_str(gt, sizeof gt);
    {
        web_account *acct = wa_new(p, xuid, gt);
        if (!acct) return E_OUTOFMEMORY;
        return auth_async_done_iid(&IID_IAsyncOpWebAccount, S_OK, acct, out);
    }
}
static HRESULT __stdcall wacm_FindAccountProviderAsync(void *self, hstring_t pid, void **out)
{
    char *id = hstr_to_utf8(pid);
    web_provider *p = wap_new(id, "consumers", &g_user_obj.base);
    (void)self;
    auth_log("FindAccountProviderAsync(%s)", id ? id : "");
    free(id);
    if (!p) return E_OUTOFMEMORY;
    return auth_async_done_iid(&IID_IAsyncOpWebProvider, S_OK, p, out);
}
static HRESULT __stdcall wacm_FindAccountProviderWithAuthAsync(void *self, hstring_t pid, hstring_t auth, void **out)
{
    char *id = hstr_to_utf8(pid);
    char *au = hstr_to_utf8(auth);
    web_provider *p = wap_new(id, au, &g_user_obj.base);
    (void)self;
    auth_log("FindAccountProviderAsync(%s, %s)", id ? id : "", au ? au : "");
    free(id);
    free(au);
    if (!p) return E_OUTOFMEMORY;
    return auth_async_done_iid(&IID_IAsyncOpWebProvider, S_OK, p, out);
}
static HRESULT __stdcall wacm2_FindAccountProviderForUserAsync(void *self, hstring_t pid, hstring_t auth,
                                                               void *user, void **out)
{
    char *id = hstr_to_utf8(pid);
    char *au = hstr_to_utf8(auth);
    web_provider *p = wap_new(id, au, user ? user : &g_user_obj.base);
    (void)self;
    auth_log("FindAccountProviderForUserAsync(%s, %s)", id ? id : "", au ? au : "");
    free(id);
    free(au);
    if (!p) return E_OUTOFMEMORY;
    return auth_async_done_iid(&IID_IAsyncOpWebProvider, S_OK, p, out);
}
static HRESULT __stdcall wacm3_CreateMonitor(void *self, void *accts, void **out)
{
    (void)self; (void)accts;
    if (out) *out = NULL;
    return E_NOTIMPL;
}
static HRESULT __stdcall wacm4_FindAllAccountsAsync(void *self, void *prov, void **out)
{
    (void)self; (void)prov;
    if (out) *out = NULL;
    return E_NOTIMPL;
}
static HRESULT __stdcall wacm4_FindAllAccountsClientAsync(void *self, void *prov, hstring_t cid, void **out)
{
    (void)self; (void)prov; (void)cid;
    if (out) *out = NULL;
    return E_NOTIMPL;
}

static const void *wacm_statics_vtbl[] = {
    (const void *)wacm_QI, (const void *)obj_AddRef, (const void *)obj_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wacm_GetTokenSilentlyAsync,             /* 6 */
    (const void *)wacm_GetTokenSilentlyWithAccountAsync,  /* 7 */
    (const void *)wacm_RequestTokenAsync,                 /* 8 */
    (const void *)wacm_RequestTokenWithAccountAsync,      /* 9 */
    (const void *)wacm_FindAccountAsync,                  /* 10 */
    (const void *)wacm_FindAccountProviderAsync,          /* 11 */
    (const void *)wacm_FindAccountProviderWithAuthAsync,  /* 12 */
};
static const void *wacm_statics2_vtbl[] = {
    (const void *)wacm_QI, (const void *)obj_AddRef, (const void *)obj_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wacm2_FindAccountProviderForUserAsync,  /* 6 */
};
static const void *wacm_statics3_vtbl[] = {
    (const void *)wacm_QI, (const void *)obj_AddRef, (const void *)obj_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wacm3_CreateMonitor,                    /* 6 */
};
static const void *wacm_statics4_vtbl[] = {
    (const void *)wacm_QI, (const void *)obj_AddRef, (const void *)obj_Release,
    (const void *)obj_GetIids, (const void *)obj_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)wacm4_FindAllAccountsAsync,             /* 6 */
    (const void *)wacm4_FindAllAccountsClientAsync,       /* 7 */
    (const void *)wacm_FindAccountProviderAsync,          /* 8 */
    (const void *)wacm_FindAccountProviderWithAuthAsync,  /* 9 */
    (const void *)wacm2_FindAccountProviderForUserAsync,  /* 10 */
};

/* --------------------------------------------------------------- Activation Factory Table */

typedef struct {
    const WCHAR *class_name;
    auth_obj *statics;
    int activatable_picker;
    auth_obj factory;
} auth_class;

static auth_class g_auth_classes[] = {
    {L"Windows.System.User", &g_user_statics, 0, {0}},
    {L"Windows.System.KnownUserProperties", &g_known_user_props, 0, {0}},
    {L"Windows.System.UserPicker", &g_user_picker_statics, 1, {0}},
    {L"Windows.Security.Authentication.Web.Core.WebAuthenticationCoreManager", &g_wacm_statics, 0, {0}},
    {L"Windows.Security.Authentication.Web.Core.WebTokenRequest", &g_wtr_factory, 0, {0}},
};

static auth_obj *auth_class_factory_for_statics(const void *statics)
{
    size_t i;
    for (i = 0; i < sizeof(g_auth_classes) / sizeof(g_auth_classes[0]); i++) {
        if (g_auth_classes[i].statics == statics) return &g_auth_classes[i].factory;
    }
    return NULL;
}

static HRESULT __stdcall auth_factory_QI(void *self, const GUID *id, void **out)
{
    auth_obj *o = (auth_obj *)self;
    auth_class *entry = (auth_class *)o->name;
    if (!out) return E_POINTER;
    if (!id || IsEqualIID(id, &IID_IFactory_a) || IsEqualIID(id, &IID_IUnknown_a) ||
        IsEqualIID(id, &IID_IInspectable_a) || IsEqualIID(id, &IID_IAgileObject_a)) {
        *out = self;
        obj_AddRef(self);
        return S_OK;
    }
    if (entry && entry->statics && entry->statics->vtbl && entry->statics->vtbl[0]) {
        return ((HRESULT(__stdcall *)(void *, const GUID *, void **))entry->statics->vtbl[0])(
            entry->statics, id, out);
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT __stdcall auth_factory_GetClassName(void *self, hstring_t *out)
{
    auth_class *entry = (auth_class *)((auth_obj *)self)->name;
    if (!out) return E_POINTER;
    *out = hstr_from_w(entry->class_name);
    return S_OK;
}

static HRESULT __stdcall auth_factory_Activate(void *self, void **out)
{
    auth_class *entry = (auth_class *)((auth_obj *)self)->name;
    if (!out) return E_POINTER;
    if (entry && entry->activatable_picker) {
        user_picker_obj *p = (user_picker_obj *)calloc(1, sizeof(user_picker_obj));
        if (!p) return E_OUTOFMEMORY;
        p->base.vtbl = up_vtbl;
        p->base.refs = 1;
        p->base.name = "Windows.System.UserPicker";
        *out = p;
        return S_OK;
    }
    *out = NULL;
    return E_NOTIMPL;
}

static const void *auth_factory_vtbl[] = {
    (const void *)auth_factory_QI, (const void *)obj_AddRef, (const void *)obj_Release,
    (const void *)obj_GetIids, (const void *)auth_factory_GetClassName, (const void *)obj_GetTrustLevel,
    (const void *)auth_factory_Activate,
};

void uwp_auth_install(void);
int uwp_auth_lookup(const WCHAR *class_name, void **factory);

void uwp_auth_install(void)
{
    size_t i;
#define INIT_OBJ(obj, table, rname) \
    do { (obj).vtbl = (table); (obj).refs = 1000; (obj).permanent = 1; (obj).name = (rname); } while (0)

    INIT_OBJ(g_user_obj.base, user_vtbl, "Windows.System.User");
    INIT_OBJ(g_user_obj.user2, user2_vtbl, "Windows.System.User");
    INIT_OBJ(g_user_statics, user_statics_vtbl, "Windows.System.User");
    INIT_OBJ(g_user_statics2, user_statics2_vtbl, "Windows.System.User");
    INIT_OBJ(g_known_user_props, kup_vtbl, "Windows.System.KnownUserProperties");
    INIT_OBJ(g_user_picker_statics, up_statics_vtbl, "Windows.System.UserPicker");
    INIT_OBJ(g_wacm_statics, wacm_statics_vtbl,
             "Windows.Security.Authentication.Web.Core.WebAuthenticationCoreManager");
    INIT_OBJ(g_wacm_statics2, wacm_statics2_vtbl,
             "Windows.Security.Authentication.Web.Core.WebAuthenticationCoreManager");
    INIT_OBJ(g_wacm_statics3, wacm_statics3_vtbl,
             "Windows.Security.Authentication.Web.Core.WebAuthenticationCoreManager");
    INIT_OBJ(g_wacm_statics4, wacm_statics4_vtbl,
             "Windows.Security.Authentication.Web.Core.WebAuthenticationCoreManager");
    INIT_OBJ(g_wtr_factory, wtr_factory_vtbl,
             "Windows.Security.Authentication.Web.Core.WebTokenRequest");
#undef INIT_OBJ

    for (i = 0; i < sizeof(g_auth_classes) / sizeof(g_auth_classes[0]); i++) {
        auth_class *e = &g_auth_classes[i];
        e->factory.vtbl = auth_factory_vtbl;
        e->factory.refs = 1000;
        e->factory.permanent = 1;
        e->factory.name = (const char *)e;
    }
}

int uwp_auth_lookup(const WCHAR *class_name, void **factory)
{
    size_t i;
    for (i = 0; i < sizeof(g_auth_classes) / sizeof(g_auth_classes[0]); i++) {
        if (wcscmp(class_name, g_auth_classes[i].class_name) == 0) {
            *factory = &g_auth_classes[i].factory;
            return 1;
        }
    }
    return 0;
}
