/* FreeThreadedXMLHTTP60 (IXMLHTTPRequest2) implementation backed by WinHTTP.
 *
 * Packaged UWP titles using cpprestsdk / Microsoft.Xbox.Services (XSAPI)
 * perform all Xbox Live REST calls via CoCreateInstanceFromApp on
 * CLSID_FreeThreadedXMLHTTP60 ({88d96a09-f192-11d4-a65f-0040963251e5}) and
 * call IXMLHTTPRequest2::SetCustomResponseStream. Wine's msxml6.dll returns
 * E_NOTIMPL for SetCustomResponseStream, causing every Xbox Live REST call
 * (MPSD sessions, Thunderhead/gameserverds QoS deployments, profiles, clubs,
 * achievements) to abort with 0x80004001 before leaving the process.
 *
 * Hosting CLSID_FreeThreadedXMLHTTP60 directly in uwp-host.dll via
 * DllGetClassObject and WinHTTP gives UWP titles full async HTTP/HTTPS
 * support including custom ISequentialStream response streaming.
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <stdarg.h>

/* --------------------------------------------------------------- tracing */

static int xhr2_trace_enabled(void)
{
    static int checked = 0;
    static int enabled = 0;
    if (!checked) {
        char buf[8];
        DWORD len = GetEnvironmentVariableA("DROIDDECK_UWP_TRACE", buf, sizeof buf);
        enabled = (len > 0 && len < sizeof buf && buf[0] != '0');
        checked = 1;
    }
    return enabled;
}

static void xhr2_tracef(const char *fmt, ...)
{
    char msg[512];
    char line[600];
    va_list ap;
    HANDLE f;
    DWORD written;
    int n;

    if (!xhr2_trace_enabled()) return;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    n = snprintf(line, sizeof line, "%04lx uwp-xhr2: %s\r\n",
                 (unsigned long)GetCurrentThreadId(), msg);
    if (n <= 0) return;
    if ((size_t)n >= sizeof line) n = (int)(sizeof line - 1);
    f = CreateFileA("C:\\uwp-host-trace.log", FILE_APPEND_DATA,
                    FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS,
                    FILE_ATTRIBUTE_NORMAL, NULL);
    if (f != INVALID_HANDLE_VALUE) {
        WriteFile(f, line, (DWORD)n, &written, NULL);
        CloseHandle(f);
    }
}

/* --------------------------------------------------------------- GUIDs */

static const GUID XHR2_CLSID_FreeThreadedXMLHTTP60 = {
    0x88d96a09, 0xf192, 0x11d4, {0xa6, 0x5f, 0x00, 0x40, 0x96, 0x32, 0x51, 0xe5}};
static const GUID XHR2_IID_IUnknown = {
    0x00000000, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const GUID XHR2_IID_IClassFactory = {
    0x00000001, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const GUID XHR2_IID_IAgileObject = {
    0x94ea2b94, 0xe9cc, 0x49e0, {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};
static const GUID XHR2_IID_IXMLHTTPRequest2 = {
    0xe5d37dc0, 0x552a, 0x4d52, {0x9c, 0xc0, 0xa1, 0x4d, 0x54, 0x6f, 0xbd, 0x04}};
static const GUID XHR2_IID_IXMLHTTPRequest2Callback = {
    0xa44a9299, 0xe321, 0x40de, {0x88, 0x66, 0x34, 0x1b, 0x41, 0x66, 0x91, 0x62}};
static const GUID XHR2_IID_ISequentialStream = {
    0x0c733a30, 0x2a1c, 0x11ce, {0xad, 0xe5, 0x00, 0xaa, 0x00, 0x44, 0x77, 0x3d}};

/* MSXML6 XHR_PROPERTY enum values (Windows SDK msxml6.h). */
enum {
    XHR2_PROP_NO_CRED_PROMPT = 0,
    XHR2_PROP_NO_AUTH = 1,
    XHR2_PROP_TIMEOUT = 2,
    XHR2_PROP_NO_DEFAULT_HEADERS = 3,
    XHR2_PROP_REPORT_REDIRECT_STATUS = 4,
};

static const WCHAR *xhr2_trace_url(const WCHAR *url, WCHAR *buf, size_t cap)
{
    const WCHAR *end;
    size_t len;
    if (!url || !cap) return L"";
    end = wcspbrk(url, L"?#");
    len = end ? (size_t)(end - url) : wcslen(url);
    if (len >= cap) len = cap - 1;
    memcpy(buf, url, len * sizeof(WCHAR));
    buf[len] = 0;
    return buf;
}

typedef HRESULT (WINAPI *xhr2_pfn_CoGetApartmentType)(int *apt_type, int *apt_qual);

static xhr2_pfn_CoGetApartmentType g_xhr2_get_apt_fn = NULL;
static INIT_ONCE g_xhr2_get_apt_once = INIT_ONCE_STATIC_INIT;

static BOOL CALLBACK xhr2_get_apt_init_cb(PINIT_ONCE once, PVOID param, PVOID *ctx)
{
    HMODULE m = GetModuleHandleW(L"combase.dll");
    (void)once; (void)param; (void)ctx;
    if (!m) m = GetModuleHandleW(L"ole32.dll");
    if (!m) m = LoadLibraryW(L"ole32.dll");
    if (m) g_xhr2_get_apt_fn = (xhr2_pfn_CoGetApartmentType)(void *)GetProcAddress(m, "CoGetApartmentType");
    return TRUE;
}

static int xhr2_caller_is_mta(void)
{
    int apt_type = -1, apt_qual = 0;
    InitOnceExecuteOnce(&g_xhr2_get_apt_once, xhr2_get_apt_init_cb, NULL, NULL);
    if (g_xhr2_get_apt_fn && SUCCEEDED(g_xhr2_get_apt_fn(&apt_type, &apt_qual))) {
        /* APTTYPE_MTA (1) or APTTYPE_NA (2): shares the worker's MTA directly. */
        return (apt_type == 1 || apt_type == 2);
    }
    return 0;
}

static IStream *xhr2_marshal_if(void *iface, const GUID *iid)
{
    IUnknown *unk = (IUnknown *)iface;
    void *agile = NULL;
    IStream *stm = NULL;
    if (!unk) return NULL;
    /* Same-apartment MTA callers and agile objects can be invoked directly by the MTA worker. */
    if (xhr2_caller_is_mta()) return NULL;
    if (SUCCEEDED(unk->lpVtbl->QueryInterface(unk, &XHR2_IID_IAgileObject, &agile)) && agile) {
        ((IUnknown *)agile)->lpVtbl->Release((IUnknown *)agile);
        return NULL;
    }
    if (SUCCEEDED(CoMarshalInterThreadInterfaceInStream(iid, unk, &stm))) return stm;
    return NULL;
}

static void xhr2_release_stream(IStream **pstm)
{
    if (*pstm) {
        (*pstm)->lpVtbl->Release(*pstm);
        *pstm = NULL;
    }
}

/* --------------------------------------------------------------- WinINet loader */

typedef void *xhr_hinet;
typedef xhr_hinet (WINAPI *pfn_InternetOpenW)(const WCHAR *, DWORD, const WCHAR *, const WCHAR *, DWORD);
typedef xhr_hinet (WINAPI *pfn_InternetConnectW)(xhr_hinet, const WCHAR *, WORD, const WCHAR *, const WCHAR *, DWORD, DWORD, DWORD_PTR);
typedef xhr_hinet (WINAPI *pfn_HttpOpenRequestW)(xhr_hinet, const WCHAR *, const WCHAR *, const WCHAR *, const WCHAR *, const WCHAR **, DWORD, DWORD_PTR);
typedef BOOL (WINAPI *pfn_InternetSetOptionW)(xhr_hinet, DWORD, void *, DWORD);
typedef BOOL (WINAPI *pfn_HttpAddRequestHeadersW)(xhr_hinet, const WCHAR *, DWORD, DWORD);
typedef BOOL (WINAPI *pfn_HttpSendRequestW)(xhr_hinet, const WCHAR *, DWORD, void *, DWORD);
typedef BOOL (WINAPI *pfn_HttpQueryInfoW)(xhr_hinet, DWORD, void *, DWORD *, DWORD *);
typedef BOOL (WINAPI *pfn_InternetReadFile)(xhr_hinet, void *, DWORD, DWORD *);
typedef BOOL (WINAPI *pfn_InternetCloseHandle)(xhr_hinet);

static struct {
    INIT_ONCE once;
    int ready;
    pfn_InternetOpenW open;
    pfn_InternetConnectW connect;
    pfn_HttpOpenRequestW open_req;
    pfn_InternetSetOptionW set_opt;
    pfn_HttpAddRequestHeadersW add_hdrs;
    pfn_HttpSendRequestW send_req;
    pfn_HttpQueryInfoW query_info;
    pfn_InternetReadFile read_file;
    pfn_InternetCloseHandle close_handle;
} g_in = {INIT_ONCE_STATIC_INIT, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL};

static BOOL CALLBACK xhr2_in_init_cb(PINIT_ONCE once, PVOID param, PVOID *ctx)
{
    HMODULE m;
    (void)once; (void)param; (void)ctx;
    m = GetModuleHandleW(L"wininet.dll");
    if (!m) m = LoadLibraryW(L"wininet.dll");
    if (!m) {
        xhr2_tracef("LoadLibraryW(wininet.dll) failed err=%lu", (unsigned long)GetLastError());
        return TRUE;
    }
    g_in.open = (pfn_InternetOpenW)(void *)GetProcAddress(m, "InternetOpenW");
    g_in.connect = (pfn_InternetConnectW)(void *)GetProcAddress(m, "InternetConnectW");
    g_in.open_req = (pfn_HttpOpenRequestW)(void *)GetProcAddress(m, "HttpOpenRequestW");
    g_in.set_opt = (pfn_InternetSetOptionW)(void *)GetProcAddress(m, "InternetSetOptionW");
    g_in.add_hdrs = (pfn_HttpAddRequestHeadersW)(void *)GetProcAddress(m, "HttpAddRequestHeadersW");
    g_in.send_req = (pfn_HttpSendRequestW)(void *)GetProcAddress(m, "HttpSendRequestW");
    g_in.query_info = (pfn_HttpQueryInfoW)(void *)GetProcAddress(m, "HttpQueryInfoW");
    g_in.read_file = (pfn_InternetReadFile)(void *)GetProcAddress(m, "InternetReadFile");
    g_in.close_handle = (pfn_InternetCloseHandle)(void *)GetProcAddress(m, "InternetCloseHandle");
    g_in.ready = (g_in.open && g_in.connect && g_in.open_req && g_in.add_hdrs &&
                  g_in.send_req && g_in.query_info && g_in.read_file && g_in.close_handle);
    xhr2_tracef("wininet init ready=%d", g_in.ready);
    return TRUE;
}

static int xhr2_in_ensure(void)
{
    InitOnceExecuteOnce(&g_in.once, xhr2_in_init_cb, NULL, NULL);
    return g_in.ready;
}

/* --------------------------------------------------------------- Callback & Stream COM callers */

typedef struct {
    HRESULT (__stdcall *QueryInterface)(void *self, const GUID *riid, void **out);
    ULONG (__stdcall *AddRef)(void *self);
    ULONG (__stdcall *Release)(void *self);
    HRESULT (__stdcall *Read)(void *self, void *pv, ULONG cb, ULONG *pcbRead);
    HRESULT (__stdcall *Write)(void *self, const void *pv, ULONG cb, ULONG *pcbWritten);
} xhr2_seq_stream_vtbl;

typedef struct {
    const xhr2_seq_stream_vtbl *lpVtbl;
} xhr2_seq_stream_if;

typedef struct {
    HRESULT (__stdcall *QueryInterface)(void *self, const GUID *riid, void **out);
    ULONG (__stdcall *AddRef)(void *self);
    ULONG (__stdcall *Release)(void *self);
    HRESULT (__stdcall *OnRedirect)(void *self, void *xhr, const WCHAR *url);
    HRESULT (__stdcall *OnHeadersAvailable)(void *self, void *xhr, DWORD status, const WCHAR *status_text);
    HRESULT (__stdcall *OnDataAvailable)(void *self, void *xhr, void *stream);
    HRESULT (__stdcall *OnResponseReceived)(void *self, void *xhr, void *stream);
    HRESULT (__stdcall *OnError)(void *self, void *xhr, HRESULT hr);
} xhr2_cb_vtbl;

typedef struct {
    const xhr2_cb_vtbl *lpVtbl;
} xhr2_cb_if;

/* --------------------------------------------------------------- Fallback in-memory ISequentialStream */

typedef struct {
    const void **vtbl;
    LONG refs;
    CRITICAL_SECTION lock;
    uint8_t *data;
    size_t len;
    size_t cap;
    size_t pos;
} xhr2_mem_stream;

static HRESULT __stdcall mems_QI(xhr2_mem_stream *self, const GUID *riid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(riid, &XHR2_IID_IUnknown) || IsEqualIID(riid, &XHR2_IID_ISequentialStream) ||
        IsEqualIID(riid, &XHR2_IID_IAgileObject)) {
        InterlockedIncrement(&self->refs);
        *out = self;
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG __stdcall mems_AddRef(xhr2_mem_stream *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}

static ULONG __stdcall mems_Release(xhr2_mem_stream *self)
{
    LONG left = InterlockedDecrement(&self->refs);
    if (left == 0) {
        DeleteCriticalSection(&self->lock);
        free(self->data);
        free(self);
    }
    return (ULONG)left;
}

static HRESULT __stdcall mems_Read(xhr2_mem_stream *self, void *pv, ULONG cb, ULONG *pcbRead)
{
    size_t avail, take;
    if (pcbRead) *pcbRead = 0;
    if (!pv && cb > 0) return STG_E_INVALIDPOINTER;
    EnterCriticalSection(&self->lock);
    avail = (self->pos < self->len) ? (self->len - self->pos) : 0;
    take = (cb < avail) ? (size_t)cb : avail;
    if (take > 0) {
        memcpy(pv, self->data + self->pos, take);
        self->pos += take;
    }
    LeaveCriticalSection(&self->lock);
    if (pcbRead) *pcbRead = (ULONG)take;
    return (take < cb) ? S_FALSE : S_OK;
}

static HRESULT __stdcall mems_Write(xhr2_mem_stream *self, const void *pv, ULONG cb, ULONG *pcbWritten)
{
    if (pcbWritten) *pcbWritten = 0;
    if (!pv && cb > 0) return STG_E_INVALIDPOINTER;
    EnterCriticalSection(&self->lock);
    if (cb > 0) {
        if (self->len + cb > self->cap) {
            size_t ncap = self->cap ? self->cap * 2 : 4096;
            uint8_t *nb;
            while (ncap < self->len + cb) ncap *= 2;
            nb = (uint8_t *)realloc(self->data, ncap);
            if (!nb) {
                LeaveCriticalSection(&self->lock);
                return E_OUTOFMEMORY;
            }
            self->data = nb;
            self->cap = ncap;
        }
        memcpy(self->data + self->len, pv, cb);
        self->len += cb;
    }
    LeaveCriticalSection(&self->lock);
    if (pcbWritten) *pcbWritten = cb;
    return S_OK;
}

static const void *xhr2_mem_stream_vtbl[] = {
    (const void *)mems_QI,
    (const void *)mems_AddRef,
    (const void *)mems_Release,
    (const void *)mems_Read,
    (const void *)mems_Write,
};

static xhr2_mem_stream *mems_new(void)
{
    xhr2_mem_stream *s = (xhr2_mem_stream *)calloc(1, sizeof *s);
    if (!s) return NULL;
    s->vtbl = xhr2_mem_stream_vtbl;
    s->refs = 1;
    InitializeCriticalSection(&s->lock);
    return s;
}

/* --------------------------------------------------------------- IXMLHTTPRequest2 object */

typedef struct {
    WCHAR *name;
    WCHAR *value;
} xhr2_header;

typedef struct {
    const void **vtbl;
    LONG refs;
    CRITICAL_SECTION lock;
    volatile LONG aborted;
    WCHAR *method;
    WCHAR *url;
    xhr2_cb_if *cb;
    IStream *cb_stream;
    xhr2_seq_stream_if *custom_stream;
    IStream *custom_stream_m;
    xhr2_seq_stream_if *req_body;
    IStream *req_body_stream;
    ULONGLONG req_body_len;
    xhr2_header *req_headers;
    size_t req_header_count;
    size_t req_header_cap;
    WCHAR *resp_headers;
    DWORD timeout_ms;
    int report_redirect;
} xhr2_obj;

static WCHAR *xhr2_wcsdup(const WCHAR *s)
{
    size_t n;
    WCHAR *d;
    if (!s) return NULL;
    n = wcslen(s) + 1;
    d = (WCHAR *)malloc(n * sizeof(WCHAR));
    if (d) memcpy(d, s, n * sizeof(WCHAR));
    return d;
}

static void xhr2_clear_req_headers_locked(xhr2_obj *self)
{
    size_t i;
    for (i = 0; i < self->req_header_count; i++) {
        free(self->req_headers[i].name);
        free(self->req_headers[i].value);
    }
    self->req_header_count = 0;
}

static HRESULT __stdcall xhr2_QI(xhr2_obj *self, const GUID *riid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(riid, &XHR2_IID_IUnknown) || IsEqualIID(riid, &XHR2_IID_IXMLHTTPRequest2) ||
        IsEqualIID(riid, &XHR2_IID_IAgileObject)) {
        InterlockedIncrement(&self->refs);
        *out = self;
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG __stdcall xhr2_AddRef(xhr2_obj *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}

static ULONG __stdcall xhr2_Release(xhr2_obj *self)
{
    LONG left = InterlockedDecrement(&self->refs);
    if (left == 0) {
        xhr2_release_stream(&self->cb_stream);
        xhr2_release_stream(&self->custom_stream_m);
        xhr2_release_stream(&self->req_body_stream);
        if (self->cb) self->cb->lpVtbl->Release(self->cb);
        if (self->custom_stream) self->custom_stream->lpVtbl->Release(self->custom_stream);
        if (self->req_body) self->req_body->lpVtbl->Release(self->req_body);
        xhr2_clear_req_headers_locked(self);
        free(self->req_headers);
        free(self->method);
        free(self->url);
        free(self->resp_headers);
        DeleteCriticalSection(&self->lock);
        free(self);
    }
    return (ULONG)left;
}

static HRESULT __stdcall xhr2_Open(xhr2_obj *self, const WCHAR *method, const WCHAR *url,
                                   void *callback, const WCHAR *user, const WCHAR *pass,
                                   const WCHAR *proxy_user, const WCHAR *proxy_pass)
{
    IStream *cb_stm;
    xhr2_cb_if *old_cb;
    IStream *old_stm;
    (void)user; (void)pass; (void)proxy_user; (void)proxy_pass;
    if (!method || !url || !callback) return E_INVALIDARG;
    cb_stm = xhr2_marshal_if(callback, &XHR2_IID_IXMLHTTPRequest2Callback);
    EnterCriticalSection(&self->lock);
    InterlockedExchange(&self->aborted, 0);
    free(self->method);
    free(self->url);
    free(self->resp_headers);
    self->method = xhr2_wcsdup(method);
    self->url = xhr2_wcsdup(url);
    self->resp_headers = NULL;
    xhr2_clear_req_headers_locked(self);
    old_cb = self->cb;
    old_stm = self->cb_stream;
    self->cb = (xhr2_cb_if *)callback;
    self->cb->lpVtbl->AddRef(self->cb);
    self->cb_stream = cb_stm;
    LeaveCriticalSection(&self->lock);
    if (old_stm) old_stm->lpVtbl->Release(old_stm);
    if (old_cb) old_cb->lpVtbl->Release(old_cb);
    if (!self->method || !self->url) return E_OUTOFMEMORY;
    return S_OK;
}

static int parse_http_url(const WCHAR *url, int *is_https, WCHAR *host, size_t host_cap,
                          WORD *port, WCHAR *path, size_t path_cap)
{
    const WCHAR *p = url;
    const WCHAR *host_start, *host_end, *path_start;
    size_t hlen, plen;

    if (!url) return 0;
    if (_wcsnicmp(p, L"https://", 8) == 0) {
        *is_https = 1;
        *port = 443;
        p += 8;
    } else if (_wcsnicmp(p, L"http://", 7) == 0) {
        *is_https = 0;
        *port = 80;
        p += 7;
    } else {
        return 0;
    }

    if (*p == L'[') {
        host_start = p + 1;
        host_end = wcschr(host_start, L']');
        if (!host_end) return 0;
        p = host_end + 1;
    } else {
        host_start = p;
        while (*p && *p != L':' && *p != L'/' && *p != L'?') p++;
        host_end = p;
    }

    hlen = (size_t)(host_end - host_start);
    if (hlen == 0 || hlen >= host_cap) return 0;
    memcpy(host, host_start, hlen * sizeof(WCHAR));
    host[hlen] = 0;

    if (*p == L':') {
        const WCHAR *endp = NULL;
        unsigned long pv = wcstoul(p + 1, (WCHAR **)&endp, 10);
        if (endp == p + 1 || pv == 0 || pv > 65535) return 0;
        p = endp;
        *port = (WORD)pv;
    }

    if (*p != 0 && *p != L'/' && *p != L'?' && *p != L'#') return 0;

    if (*p == L'/') {
        path_start = p;
        plen = wcslen(path_start);
        if (plen >= path_cap) return 0;
        memcpy(path, path_start, (plen + 1) * sizeof(WCHAR));
    } else if (*p == L'?') {
        plen = wcslen(p);
        if (plen + 1 >= path_cap) return 0;
        path[0] = L'/';
        memcpy(path + 1, p, (plen + 1) * sizeof(WCHAR));
    } else {
        path[0] = L'/';
        path[1] = 0;
    }
    return 1;
}

static DWORD WINAPI xhr2_worker(LPVOID param)
{
    xhr2_obj *self = (xhr2_obj *)param;
    WCHAR *method = NULL, *url = NULL;
    WCHAR safe_url[512];
    xhr2_cb_if *cb = NULL;
    IStream *cb_stm = NULL;
    xhr2_seq_stream_if *custom_stream = NULL;
    IStream *custom_stm = NULL;
    xhr2_seq_stream_if *req_body = NULL;
    IStream *req_body_stm = NULL;
    xhr2_mem_stream *mem_stream = NULL;
    ULONGLONG req_body_len = 0;
    xhr2_header *hdrs = NULL;
    size_t hdr_count = 0, i;
    DWORD timeout_ms = 30000;
    int report_redirect = 0;
    uint8_t *body_buf = NULL;
    size_t body_len = 0;
    int is_https = 1;
    WORD port = 443;
    WCHAR host[256];
    WCHAR path[2048];
    xhr_hinet h_sess = NULL, h_conn = NULL, h_req = NULL;
    DWORD status_code = 0, status_sz = sizeof status_code;
    WCHAR status_text[128];
    DWORD status_text_sz = sizeof status_text;
    DWORD raw_hdr_sz = 0;
    WCHAR *raw_hdrs = NULL;
    int decomp_ok = 0;
    HRESULT err_hr = S_OK;
    int com_inited;

    safe_url[0] = 0;
    com_inited = SUCCEEDED(CoInitializeEx(NULL, COINIT_MULTITHREADED));

    EnterCriticalSection(&self->lock);
    method = xhr2_wcsdup(self->method);
    url = xhr2_wcsdup(self->url);
    cb_stm = self->cb_stream;
    self->cb_stream = NULL;
    cb = self->cb;
    if (cb) cb->lpVtbl->AddRef(cb);
    custom_stm = self->custom_stream_m;
    self->custom_stream_m = NULL;
    custom_stream = self->custom_stream;
    if (custom_stream) custom_stream->lpVtbl->AddRef(custom_stream);
    req_body_stm = self->req_body_stream;
    self->req_body_stream = NULL;
    req_body = self->req_body;
    self->req_body = NULL;
    req_body_len = self->req_body_len;
    self->req_body_len = 0;
    timeout_ms = self->timeout_ms ? self->timeout_ms : 30000;
    report_redirect = self->report_redirect;
    hdr_count = self->req_header_count;
    if (hdr_count > 0) {
        hdrs = (xhr2_header *)calloc(hdr_count, sizeof(xhr2_header));
        if (hdrs) {
            for (i = 0; i < hdr_count; i++) {
                hdrs[i].name = xhr2_wcsdup(self->req_headers[i].name);
                hdrs[i].value = xhr2_wcsdup(self->req_headers[i].value);
            }
        } else {
            hdr_count = 0;
        }
    }
    LeaveCriticalSection(&self->lock);

    if (cb_stm) {
        void *p = NULL;
        if (SUCCEEDED(CoGetInterfaceAndReleaseStream(cb_stm, &XHR2_IID_IXMLHTTPRequest2Callback, &p)) && p) {
            if (cb) cb->lpVtbl->Release(cb);
            cb = (xhr2_cb_if *)p;
        }
        cb_stm = NULL;
    }
    if (custom_stm) {
        void *p = NULL;
        if (SUCCEEDED(CoGetInterfaceAndReleaseStream(custom_stm, &XHR2_IID_ISequentialStream, &p)) && p) {
            if (custom_stream) custom_stream->lpVtbl->Release(custom_stream);
            custom_stream = (xhr2_seq_stream_if *)p;
        }
        custom_stm = NULL;
    }
    if (req_body_stm) {
        void *p = NULL;
        if (SUCCEEDED(CoGetInterfaceAndReleaseStream(req_body_stm, &XHR2_IID_ISequentialStream, &p)) && p) {
            if (req_body) req_body->lpVtbl->Release(req_body);
            req_body = (xhr2_seq_stream_if *)p;
        }
        req_body_stm = NULL;
    }

    if (url) xhr2_trace_url(url, safe_url, sizeof(safe_url) / sizeof(safe_url[0]));

    if (!method || !url || !cb) {
        xhr2_tracef("worker invalid args m=%p u=%p cb=%p", (void *)method, (void *)url, (void *)cb);
        err_hr = E_INVALIDARG;
        goto fail;
    }
    if (!xhr2_in_ensure()) {
        err_hr = E_FAIL;
        goto fail;
    }
    xhr2_tracef("start %ls %ls body=%p len=%llu hdrs=%zu custom=%p",
                method, safe_url, (void *)req_body, (unsigned long long)req_body_len,
                hdr_count, (void *)custom_stream);

    /* Read request body from ISequentialStream if provided. */
    if (req_body) {
        size_t cap = (req_body_len > 0 && req_body_len <= 16u * 1024u * 1024u)
                         ? (size_t)req_body_len
                         : 4096;
        body_buf = (uint8_t *)malloc(cap ? cap : 1);
        if (!body_buf) {
            err_hr = E_OUTOFMEMORY;
            goto fail;
        }
        for (;;) {
            ULONG want = 65536;
            ULONG got = 0;
            HRESULT hr;
            if (req_body_len > 0) {
                if (body_len >= (size_t)req_body_len) break;
                if ((size_t)req_body_len - body_len < want)
                    want = (ULONG)((size_t)req_body_len - body_len);
            }
            if (body_len + want > cap) {
                size_t ncap = cap ? cap * 2 : 4096;
                uint8_t *nb;
                while (ncap < body_len + want) ncap *= 2;
                if (ncap > 32u * 1024u * 1024u) {
                    err_hr = E_OUTOFMEMORY;
                    goto fail;
                }
                nb = (uint8_t *)realloc(body_buf, ncap);
                if (!nb) {
                    err_hr = E_OUTOFMEMORY;
                    goto fail;
                }
                body_buf = nb;
                cap = ncap;
            }
            hr = req_body->lpVtbl->Read(req_body, body_buf + body_len, want, &got);
            body_len += got;
            if (FAILED(hr)) {
                err_hr = hr;
                goto fail;
            }
            if (got == 0 || hr == S_FALSE) break;
        }
        req_body->lpVtbl->Release(req_body);
        req_body = NULL;
    }

    if (self->aborted) {
        err_hr = E_ABORT;
        goto fail;
    }

    if (!parse_http_url(url, &is_https, host, sizeof(host) / sizeof(host[0]),
                        &port, path, sizeof(path) / sizeof(path[0]))) {
        err_hr = E_INVALIDARG;
        goto fail;
    }

    h_sess = g_in.open(L"XboxServicesAPI/2.0", 0 /* INTERNET_OPEN_TYPE_PRECONFIG */,
                       NULL, NULL, 0);
    if (!h_sess) {
        DWORD e = GetLastError();
        err_hr = HRESULT_FROM_WIN32(e ? e : ERROR_GEN_FAILURE);
        goto fail;
    }
    if (g_in.set_opt) {
        DWORD t = timeout_ms ? timeout_ms : 30000;
        g_in.set_opt(h_sess, 2 /* INTERNET_OPTION_CONNECT_TIMEOUT */, &t, sizeof t);
        g_in.set_opt(h_sess, 5 /* INTERNET_OPTION_SEND_TIMEOUT */, &t, sizeof t);
        g_in.set_opt(h_sess, 6 /* INTERNET_OPTION_RECEIVE_TIMEOUT */, &t, sizeof t);
    }
    h_conn = g_in.connect(h_sess, host, port, NULL, NULL,
                          3 /* INTERNET_SERVICE_HTTP */, 0, 0);
    if (!h_conn) {
        DWORD e = GetLastError();
        err_hr = HRESULT_FROM_WIN32(e ? e : ERROR_GEN_FAILURE);
        goto fail;
    }
    {
        static const WCHAR *accept_types[] = {L"*/*", NULL};
        DWORD req_flags = 0x80000000 /* INTERNET_FLAG_RELOAD */
                        | 0x04000000 /* INTERNET_FLAG_NO_CACHE_WRITE */
                        | 0x00080000 /* INTERNET_FLAG_NO_COOKIES */
                        | 0x00000200 /* INTERNET_FLAG_NO_UI */;
        if (is_https) req_flags |= 0x00800000 /* INTERNET_FLAG_SECURE */;
        if (report_redirect) req_flags |= 0x00200000 /* INTERNET_FLAG_NO_AUTO_REDIRECT */;
        h_req = g_in.open_req(h_conn, method, path, NULL, NULL, accept_types, req_flags, 0);
    }
    if (!h_req) {
        DWORD e = GetLastError();
        err_hr = HRESULT_FROM_WIN32(e ? e : ERROR_GEN_FAILURE);
        goto fail;
    }

    if (InterlockedCompareExchange(&self->aborted, 0, 0)) {
        err_hr = E_ABORT;
        goto fail;
    }

    if (g_in.set_opt) {
        BOOL dec = TRUE;
        decomp_ok = g_in.set_opt(h_req, 65 /* INTERNET_OPTION_HTTP_DECODING */, &dec, sizeof dec) ? 1 : 0;
    }

    for (i = 0; i < hdr_count; i++) {
        WCHAR *line;
        size_t n;
        if (!hdrs[i].name || !hdrs[i].value) continue;
        if (_wcsicmp(hdrs[i].name, L"Content-Length") == 0 ||
            _wcsicmp(hdrs[i].name, L"Host") == 0)
            continue;
        if (!decomp_ok && _wcsicmp(hdrs[i].name, L"Accept-Encoding") == 0)
            continue;
        n = wcslen(hdrs[i].name) + wcslen(hdrs[i].value) + 6;
        line = (WCHAR *)malloc(n * sizeof(WCHAR));
        if (line) {
            swprintf(line, n, L"%ls: %ls\r\n", hdrs[i].name, hdrs[i].value);
            g_in.add_hdrs(h_req, line, (DWORD)-1L,
                          0x20000000 | 0x80000000 /* HTTP_ADDREQ_FLAG_ADD | REPLACE */);
            free(line);
        }
    }

    if (InterlockedCompareExchange(&self->aborted, 0, 0)) {
        err_hr = E_ABORT;
        goto fail;
    }

    if (!g_in.send_req(h_req, NULL, 0,
                       body_len ? body_buf : NULL,
                       (DWORD)body_len)) {
        DWORD e = GetLastError();
        xhr2_tracef("HttpSendRequestW failed %ls %ls err=%lu", method, safe_url, (unsigned long)e);
        err_hr = HRESULT_FROM_WIN32(e ? e : ERROR_GEN_FAILURE);
        goto fail;
    }

    status_text[0] = 0;
    g_in.query_info(h_req, 19 | 0x20000000 /* HTTP_QUERY_STATUS_CODE | FLAG_NUMBER */,
                    &status_code, &status_sz, NULL);
    g_in.query_info(h_req, 20 /* HTTP_QUERY_STATUS_TEXT */, status_text, &status_text_sz, NULL);

    raw_hdr_sz = 0;
    g_in.query_info(h_req, 22 /* HTTP_QUERY_RAW_HEADERS_CRLF */, NULL, &raw_hdr_sz, NULL);
    if (raw_hdr_sz > 0) {
        raw_hdrs = (WCHAR *)calloc(1, raw_hdr_sz + 4);
        if (raw_hdrs && g_in.query_info(h_req, 22, raw_hdrs, &raw_hdr_sz, NULL)) {
            /* Strip the initial HTTP/1.1 status line; GetAllResponseHeaders omits it. */
            const WCHAR *first_crlf = wcsstr(raw_hdrs, L"\r\n");
            const WCHAR *body_hdrs = first_crlf ? (first_crlf + 2) : raw_hdrs;
            WCHAR *copy = xhr2_wcsdup(body_hdrs);
            EnterCriticalSection(&self->lock);
            free(self->resp_headers);
            self->resp_headers = copy;
            LeaveCriticalSection(&self->lock);
        }
        free(raw_hdrs);
        raw_hdrs = NULL;
    }

    xhr2_tracef("%ls %ls -> HTTP %lu (req_body=%zu)", method, safe_url, (unsigned long)status_code, body_len);

    if (InterlockedCompareExchange(&self->aborted, 0, 0)) {
        err_hr = E_ABORT;
        goto fail;
    }

    cb->lpVtbl->OnHeadersAvailable(cb, self, status_code, status_text[0] ? status_text : L"OK");

    if (!custom_stream) {
        mem_stream = mems_new();
        if (!mem_stream) {
            err_hr = E_OUTOFMEMORY;
            goto fail;
        }
    }

    for (;;) {
        uint8_t chunk[16384];
        DWORD got = 0;
        if (InterlockedCompareExchange(&self->aborted, 0, 0)) {
            err_hr = E_ABORT;
            goto fail;
        }
        if (!g_in.read_file(h_req, chunk, sizeof chunk, &got)) {
            DWORD e = GetLastError();
            err_hr = HRESULT_FROM_WIN32(e ? e : ERROR_GEN_FAILURE);
            goto fail;
        }
        if (got == 0) break;
        if (custom_stream) {
            DWORD off = 0;
            while (off < got) {
                ULONG written = 0;
                HRESULT whr = custom_stream->lpVtbl->Write(custom_stream, chunk + off, got - off, &written);
                if (FAILED(whr)) {
                    err_hr = whr;
                    goto fail;
                }
                if (written == 0) {
                    err_hr = STG_E_WRITEFAULT;
                    goto fail;
                }
                off += written;
            }
            cb->lpVtbl->OnDataAvailable(cb, self, custom_stream);
        } else {
            ULONG written = 0;
            HRESULT whr = mems_Write(mem_stream, chunk, got, &written);
            if (FAILED(whr)) {
                err_hr = whr;
                goto fail;
            }
            cb->lpVtbl->OnDataAvailable(cb, self, mem_stream);
        }
    }

    if (InterlockedCompareExchange(&self->aborted, 0, 0)) {
        err_hr = E_ABORT;
        goto fail;
    }

    cb->lpVtbl->OnResponseReceived(cb, self, custom_stream ? (void *)custom_stream : (void *)mem_stream);
    xhr2_tracef("done %ls %ls -> %lu", method, safe_url, (unsigned long)status_code);
    goto cleanup;

fail:
    xhr2_tracef("fail %ls %ls hr=0x%08lx", method ? method : L"", safe_url, (unsigned long)err_hr);
    if (cb) cb->lpVtbl->OnError(cb, self, err_hr);

cleanup:
    if (h_req) g_in.close_handle(h_req);
    if (h_conn) g_in.close_handle(h_conn);
    if (h_sess) g_in.close_handle(h_sess);
    if (mem_stream) mems_Release(mem_stream);
    if (custom_stream) custom_stream->lpVtbl->Release(custom_stream);
    if (req_body) req_body->lpVtbl->Release(req_body);
    if (cb) cb->lpVtbl->Release(cb);
    for (i = 0; i < hdr_count; i++) {
        free(hdrs[i].name);
        free(hdrs[i].value);
    }
    free(hdrs);
    free(body_buf);
    free(method);
    free(url);
    if (com_inited) CoUninitialize();
    xhr2_Release(self);
    return 0;
}

static HRESULT __stdcall xhr2_Send(xhr2_obj *self, void *body, ULONGLONG cb_body)
{
    HANDLE th;
    IStream *body_stm;
    xhr2_seq_stream_if *old_body;
    IStream *old_body_stm;

    body_stm = xhr2_marshal_if(body, &XHR2_IID_ISequentialStream);
    EnterCriticalSection(&self->lock);
    if (!self->method || !self->url || !self->cb) {
        LeaveCriticalSection(&self->lock);
        if (body_stm) body_stm->lpVtbl->Release(body_stm);
        return E_UNEXPECTED;
    }
    old_body = self->req_body;
    old_body_stm = self->req_body_stream;
    self->req_body = (xhr2_seq_stream_if *)body;
    if (self->req_body) self->req_body->lpVtbl->AddRef(self->req_body);
    self->req_body_stream = body_stm;
    self->req_body_len = cb_body;
    InterlockedExchange(&self->aborted, 0);
    LeaveCriticalSection(&self->lock);
    if (old_body_stm) old_body_stm->lpVtbl->Release(old_body_stm);
    if (old_body) old_body->lpVtbl->Release(old_body);

    xhr2_AddRef(self);
    th = CreateThread(NULL, 0, xhr2_worker, self, 0, NULL);
    if (!th) {
        xhr2_Release(self);
        return E_OUTOFMEMORY;
    }
    CloseHandle(th);
    return S_OK;
}

static HRESULT __stdcall xhr2_Abort(xhr2_obj *self)
{
    InterlockedExchange(&self->aborted, 1);
    return S_OK;
}

static HRESULT __stdcall xhr2_SetCookie(xhr2_obj *self, const void *cookie, DWORD *state)
{
    (void)self; (void)cookie;
    if (state) *state = 5 /* XHR_COOKIE_STATE_REJECTED */;
    return S_OK;
}

static HRESULT __stdcall xhr2_SetCustomResponseStream(xhr2_obj *self, void *stream)
{
    IStream *stm = xhr2_marshal_if(stream, &XHR2_IID_ISequentialStream);
    xhr2_seq_stream_if *old_stream;
    IStream *old_stm;
    EnterCriticalSection(&self->lock);
    old_stream = self->custom_stream;
    old_stm = self->custom_stream_m;
    self->custom_stream = (xhr2_seq_stream_if *)stream;
    if (self->custom_stream) self->custom_stream->lpVtbl->AddRef(self->custom_stream);
    self->custom_stream_m = stm;
    LeaveCriticalSection(&self->lock);
    if (old_stm) old_stm->lpVtbl->Release(old_stm);
    if (old_stream) old_stream->lpVtbl->Release(old_stream);
    return S_OK;
}

static HRESULT __stdcall xhr2_SetProperty(xhr2_obj *self, DWORD prop, ULONGLONG val)
{
    EnterCriticalSection(&self->lock);
    if (prop == XHR2_PROP_TIMEOUT) {
        self->timeout_ms = (DWORD)val;
    } else if (prop == XHR2_PROP_REPORT_REDIRECT_STATUS) {
        self->report_redirect = (val != 0);
    }
    LeaveCriticalSection(&self->lock);
    return S_OK;
}

static HRESULT __stdcall xhr2_SetRequestHeader(xhr2_obj *self, const WCHAR *header, const WCHAR *value)
{
    size_t i;
    if (!header || !value) return E_INVALIDARG;
    EnterCriticalSection(&self->lock);
    for (i = 0; i < self->req_header_count; i++) {
        if (self->req_headers[i].name && _wcsicmp(self->req_headers[i].name, header) == 0) {
            WCHAR *nv = xhr2_wcsdup(value);
            if (!nv) {
                LeaveCriticalSection(&self->lock);
                return E_OUTOFMEMORY;
            }
            free(self->req_headers[i].value);
            self->req_headers[i].value = nv;
            LeaveCriticalSection(&self->lock);
            return S_OK;
        }
    }
    if (self->req_header_count == self->req_header_cap) {
        size_t ncap = self->req_header_cap ? self->req_header_cap * 2 : 16;
        xhr2_header *nh = (xhr2_header *)realloc(self->req_headers, ncap * sizeof(xhr2_header));
        if (!nh) {
            LeaveCriticalSection(&self->lock);
            return E_OUTOFMEMORY;
        }
        self->req_headers = nh;
        self->req_header_cap = ncap;
    }
    self->req_headers[self->req_header_count].name = xhr2_wcsdup(header);
    self->req_headers[self->req_header_count].value = xhr2_wcsdup(value);
    if (!self->req_headers[self->req_header_count].name ||
        !self->req_headers[self->req_header_count].value) {
        free(self->req_headers[self->req_header_count].name);
        free(self->req_headers[self->req_header_count].value);
        LeaveCriticalSection(&self->lock);
        return E_OUTOFMEMORY;
    }
    self->req_header_count++;
    LeaveCriticalSection(&self->lock);
    return S_OK;
}

static HRESULT __stdcall xhr2_GetAllResponseHeaders(xhr2_obj *self, WCHAR **out)
{
    const WCHAR *src;
    size_t bytes;
    WCHAR *copy;
    if (!out) return E_POINTER;
    *out = NULL;
    EnterCriticalSection(&self->lock);
    src = self->resp_headers ? self->resp_headers : L"";
    bytes = (wcslen(src) + 1) * sizeof(WCHAR);
    copy = (WCHAR *)CoTaskMemAlloc(bytes);
    if (copy) memcpy(copy, src, bytes);
    LeaveCriticalSection(&self->lock);
    if (!copy) return E_OUTOFMEMORY;
    *out = copy;
    return S_OK;
}

static HRESULT __stdcall xhr2_GetCookie(xhr2_obj *self, const WCHAR *url, const WCHAR *name,
                                        DWORD flags, ULONG *count, void **cookies)
{
    (void)self; (void)url; (void)name; (void)flags;
    if (count) *count = 0;
    if (cookies) *cookies = NULL;
    return S_OK;
}

static HRESULT __stdcall xhr2_GetResponseHeader(xhr2_obj *self, const WCHAR *header, WCHAR **out)
{
    const WCHAR *p;
    size_t nlen;
    if (!header || !out) return E_INVALIDARG;
    *out = NULL;
    nlen = wcslen(header);
    EnterCriticalSection(&self->lock);
    p = self->resp_headers;
    while (p && *p) {
        const WCHAR *eol = wcsstr(p, L"\r\n");
        const WCHAR *colon = wcschr(p, L':');
        if (colon && (!eol || colon < eol)) {
            if ((size_t)(colon - p) == nlen && _wcsnicmp(p, header, nlen) == 0) {
                const WCHAR *vs = colon + 1;
                const WCHAR *ve = eol ? eol : (p + wcslen(p));
                size_t vlen;
                WCHAR *copy;
                while (vs < ve && (*vs == L' ' || *vs == L'\t')) vs++;
                vlen = (size_t)(ve - vs);
                copy = (WCHAR *)CoTaskMemAlloc((vlen + 1) * sizeof(WCHAR));
                if (!copy) {
                    LeaveCriticalSection(&self->lock);
                    return E_OUTOFMEMORY;
                }
                memcpy(copy, vs, vlen * sizeof(WCHAR));
                copy[vlen] = 0;
                LeaveCriticalSection(&self->lock);
                *out = copy;
                return S_OK;
            }
        }
        if (!eol) break;
        p = eol + 2;
    }
    LeaveCriticalSection(&self->lock);
    return HRESULT_FROM_WIN32(ERROR_NOT_FOUND);
}

static const void *xhr2_vtbl[] = {
    (const void *)xhr2_QI,
    (const void *)xhr2_AddRef,
    (const void *)xhr2_Release,
    (const void *)xhr2_Open,
    (const void *)xhr2_Send,
    (const void *)xhr2_Abort,
    (const void *)xhr2_SetCookie,
    (const void *)xhr2_SetCustomResponseStream,
    (const void *)xhr2_SetProperty,
    (const void *)xhr2_SetRequestHeader,
    (const void *)xhr2_GetAllResponseHeaders,
    (const void *)xhr2_GetCookie,
    (const void *)xhr2_GetResponseHeader,
};

/* --------------------------------------------------------------- IClassFactory */

typedef struct {
    const void **vtbl;
} xhr2_factory;

static HRESULT __stdcall xhr2_cf_QI(xhr2_factory *self, const GUID *riid, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(riid, &XHR2_IID_IUnknown) || IsEqualIID(riid, &XHR2_IID_IClassFactory) ||
        IsEqualIID(riid, &XHR2_IID_IAgileObject)) {
        *out = self;
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG __stdcall xhr2_cf_AddRef(xhr2_factory *self) { (void)self; return 2; }
static ULONG __stdcall xhr2_cf_Release(xhr2_factory *self) { (void)self; return 1; }

static HRESULT __stdcall xhr2_cf_CreateInstance(xhr2_factory *self, void *outer,
                                                const GUID *riid, void **out)
{
    xhr2_obj *obj;
    HRESULT hr;
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    if (outer) return CLASS_E_NOAGGREGATION;
    obj = (xhr2_obj *)calloc(1, sizeof *obj);
    if (!obj) return E_OUTOFMEMORY;
    obj->vtbl = xhr2_vtbl;
    obj->refs = 1;
    obj->timeout_ms = 30000;
    InitializeCriticalSection(&obj->lock);
    hr = xhr2_QI(obj, riid, out);
    xhr2_Release(obj);
    return hr;
}

static HRESULT __stdcall xhr2_cf_LockServer(xhr2_factory *self, BOOL lock)
{
    (void)self; (void)lock;
    return S_OK;
}

static const void *xhr2_cf_vtbl[] = {
    (const void *)xhr2_cf_QI,
    (const void *)xhr2_cf_AddRef,
    (const void *)xhr2_cf_Release,
    (const void *)xhr2_cf_CreateInstance,
    (const void *)xhr2_cf_LockServer,
};

static xhr2_factory g_xhr2_factory = {xhr2_cf_vtbl};

HRESULT uwp_xhr2_get_class_object(REFCLSID clsid, REFIID riid, void **out)
{
    if (!out) return E_POINTER;
    *out = NULL;
    if (!IsEqualCLSID(clsid, &XHR2_CLSID_FreeThreadedXMLHTTP60))
        return CLASS_E_CLASSNOTAVAILABLE;
    return xhr2_cf_QI(&g_xhr2_factory, riid, out);
}
