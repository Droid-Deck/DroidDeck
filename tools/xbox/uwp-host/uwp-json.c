#define E_ILLEGAL_METHOD_CALL ((HRESULT)0x8000000EL)
#define E_BOUNDS              ((HRESULT)0x8000000BL)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <stddef.h>

static int my_memcmp(const void *a, const void *b, size_t n) {
    const unsigned char *p = (const unsigned char *)a, *q = (const unsigned char *)b;
    for (size_t i = 0; i < n; i++) if (p[i] != q[i]) return (int)p[i] - (int)q[i];
    return 0;
}
static void *my_memcpy(void *dst, const void *src, size_t n) {
    unsigned char *d = (unsigned char *)dst;
    const unsigned char *s = (const unsigned char *)src;
    for (size_t i = 0; i < n; i++) d[i] = s[i];
    return dst;
}
static size_t my_strlen(const char *s) { size_t n = 0; while (s[n]) n++; return n; }
static int my_wcscmp(const WCHAR *a, const WCHAR *b) {
    while (*a && *a == *b) { a++; b++; }
    return (int)*a - (int)*b;
}
static char *append_str(char *d, char *end, const char *s) {
    while (*s && d < end) *d++ = *s++;
    return d;
}
static char *append_hex(char *d, char *end, unsigned long v, int digits) {
    static const char hx[] = "0123456789abcdef";
    for (int i = digits - 1; i >= 0 && d < end; i--) *d++ = hx[(v >> (i * 4)) & 0xf];
    return d;
}

typedef void *hstring_t;
typedef uint8_t boolean_t;

typedef HRESULT (__stdcall *pfn_DllGetActivationFactory)(hstring_t, void **);
typedef const WCHAR *(__stdcall *pfn_WindowsGetStringRawBuffer)(hstring_t, UINT32 *);
typedef HRESULT (__stdcall *pfn_WindowsCreateString)(const WCHAR *, UINT32, hstring_t *);
typedef HRESULT (__stdcall *pfn_WindowsDuplicateString)(hstring_t, hstring_t *);
typedef HRESULT (__stdcall *pfn_WindowsDeleteString)(hstring_t);

static const GUID IID_IUnknown_           = {0x00000000,0x0000,0x0000,{0xc0,0x00,0x00,0x00,0x00,0x00,0x00,0x46}};
static const GUID IID_IInspectable_       = {0xaf86e2e0,0xb12d,0x4c6a,{0x9c,0x5a,0xd7,0xaa,0x65,0x10,0x1e,0x90}};
static const GUID IID_IAgileObject_       = {0x94ea2b94,0xe9cc,0x49e0,{0xc0,0xff,0xee,0x64,0xca,0x8f,0x5b,0x90}};
static const GUID IID_IActivationFactory_ = {0x00000035,0x0000,0x0000,{0xc0,0x00,0x00,0x00,0x00,0x00,0x00,0x46}};
static const GUID IID_IStringable_        = {0x96369f54,0x8eb6,0x48f0,{0xab,0xce,0xc1,0xb2,0x11,0xe6,0x27,0xc3}};

static const GUID IID_IJsonValue_         = {0xa3219ecb,0xf0b3,0x4dcd,{0xbe,0xee,0x19,0xd4,0x8c,0xd3,0xed,0x1e}};
static const GUID IID_IJsonValueStatics_  = {0x5f6b544a,0x2f53,0x48e1,{0x91,0xa3,0xf7,0x8b,0x50,0xa6,0x34,0x5c}};
static const GUID IID_IJsonValueStatics2_ = {0x1d9ecbe4,0x3fe8,0x4335,{0x83,0x92,0x93,0xd8,0xe3,0x68,0x65,0xf0}};
static const GUID IID_IJsonObject_        = {0x064e24dd,0x29c2,0x4f83,{0x9a,0xc1,0x9e,0xe1,0x15,0x78,0xbe,0xb3}};
static const GUID IID_IJsonObjectStatics_ = {0x2289f159,0x54de,0x45d8,{0xab,0xcc,0x22,0x60,0x3f,0xa0,0x66,0xa0}};
static const GUID IID_IJsonObjectWithDef_ = {0xd960d2a2,0xb7f0,0x4f00,{0x8e,0x44,0xd8,0x2c,0xf4,0x15,0xea,0x13}};
static const GUID IID_IJsonArrayStatics_  = {0xdb1434a9,0xe164,0x499f,{0x93,0xe2,0x8a,0x8f,0x49,0xbb,0x90,0xba}};

/* Parameterized collection IIDs for JsonObject (HSTRING -> IJsonValue) */
static const GUID IID_IMap_JsonValue_     = {0xc9d9a725,0x786b,0x5113,{0xb4,0xb7,0x9b,0x61,0x76,0x4c,0x22,0x0b}};
static const GUID IID_IMapView_JsonValue_ = {0xeecd690c,0x1ff3,0x529f,{0x92,0x3f,0x9b,0x1c,0x31,0xfd,0x3d,0x0f}};
static const GUID IID_IIterable_KVP_      = {0xdfabb6e1,0x0411,0x5a8f,{0xaa,0x87,0x35,0x4e,0x71,0x10,0xf0,0x99}};
static const GUID IID_IIterator_KVP_      = {0xf948eac5,0x33eb,0x50f5,{0xb5,0xaf,0xe7,0xce,0xcf,0x0e,0x45,0x01}};
static const GUID IID_IKVP_JsonValue_     = {0x4deecc89,0xb0b8,0x5ee8,{0xa5,0x1d,0x1c,0x25,0xad,0x9a,0x5b,0x01}};

static int eq_guid(const GUID *a, const GUID *b) {
    return a && b && my_memcmp(a, b, sizeof(GUID)) == 0;
}

static int g_trace_on = 0;

static void web_log(const char *msg) {
    if (!g_trace_on) return;
    HANDLE f = CreateFileA("C:\\uwp-host-trace.log", FILE_APPEND_DATA,
        FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return;
    DWORD w = 0;
    WriteFile(f, msg, (DWORD)my_strlen(msg), &w, NULL);
    WriteFile(f, "\r\n", 2, &w, NULL);
    CloseHandle(f);
}

static void web_log_guid(const char *tag, const GUID *g) {
    if (!g) return;
    if (g->Data1 == 0x00000003 || g->Data1 == 0x00000018 || g->Data1 == 0x00000020 ||
        g->Data1 == 0x0000001b || g->Data1 == 0x0000001c || g->Data1 == 0x00000038) return;
    char buf[160]; char *p = buf, *e = buf + sizeof(buf) - 1;
    p = append_hex(p, e, GetCurrentThreadId(), 4);
    p = append_str(p, e, " "); p = append_str(p, e, tag); p = append_str(p, e, " ");
    p = append_hex(p, e, g->Data1, 8); p = append_str(p, e, "-");
    p = append_hex(p, e, g->Data2, 4); p = append_str(p, e, "-");
    p = append_hex(p, e, g->Data3, 4); p = append_str(p, e, "-");
    p = append_hex(p, e, g->Data4[0], 2); p = append_hex(p, e, g->Data4[1], 2); p = append_str(p, e, "-");
    for (int i = 2; i < 8; i++) p = append_hex(p, e, g->Data4[i], 2);
    *p = 0;
    web_log(buf);
}

/* Raw COM vtable definitions for calling orig */
typedef struct {
    HRESULT (__stdcall *QI)(void *, const GUID *, void **);
    ULONG   (__stdcall *AddRef)(void *);
    ULONG   (__stdcall *Release)(void *);
    HRESULT (__stdcall *GetIids)(void *, ULONG *, GUID **);
    HRESULT (__stdcall *GetRuntimeClassName)(void *, hstring_t *);
    HRESULT (__stdcall *GetTrustLevel)(void *, int *);
    HRESULT (__stdcall *ActivateInstance)(void *, void **);
} raw_af_vtbl;

typedef struct {
    HRESULT (__stdcall *QI)(void *, const GUID *, void **);
    ULONG   (__stdcall *AddRef)(void *);
    ULONG   (__stdcall *Release)(void *);
    HRESULT (__stdcall *GetIids)(void *, ULONG *, GUID **);
    HRESULT (__stdcall *GetRuntimeClassName)(void *, hstring_t *);
    HRESULT (__stdcall *GetTrustLevel)(void *, int *);
    HRESULT (__stdcall *Parse)(void *, hstring_t, void **);
    HRESULT (__stdcall *TryParse)(void *, hstring_t, void **, boolean_t *);
    HRESULT (__stdcall *CreateBooleanValue)(void *, boolean_t, void **);
    HRESULT (__stdcall *CreateNumberValue)(void *, double, void **);
    HRESULT (__stdcall *CreateStringValue)(void *, hstring_t, void **);
} raw_jvs_vtbl;

typedef struct {
    HRESULT (__stdcall *QI)(void *, const GUID *, void **);
    ULONG   (__stdcall *AddRef)(void *);
    ULONG   (__stdcall *Release)(void *);
    HRESULT (__stdcall *GetIids)(void *, ULONG *, GUID **);
    HRESULT (__stdcall *GetRuntimeClassName)(void *, hstring_t *);
    HRESULT (__stdcall *GetTrustLevel)(void *, int *);
    HRESULT (__stdcall *get_ValueType)(void *, int *);
    HRESULT (__stdcall *Stringify)(void *, hstring_t *);
    HRESULT (__stdcall *GetString)(void *, hstring_t *);
    HRESULT (__stdcall *GetNumber)(void *, double *);
    HRESULT (__stdcall *GetBoolean)(void *, boolean_t *);
    HRESULT (__stdcall *GetArray)(void *, void **);
    HRESULT (__stdcall *GetObject)(void *, void **);
} raw_jv_vtbl;

typedef struct {
    HRESULT (__stdcall *QI)(void *, const GUID *, void **);
    ULONG   (__stdcall *AddRef)(void *);
    ULONG   (__stdcall *Release)(void *);
    HRESULT (__stdcall *GetIids)(void *, ULONG *, GUID **);
    HRESULT (__stdcall *GetRuntimeClassName)(void *, hstring_t *);
    HRESULT (__stdcall *GetTrustLevel)(void *, int *);
    HRESULT (__stdcall *GetNamedValue)(void *, hstring_t, void **);
    HRESULT (__stdcall *SetNamedValue)(void *, hstring_t, void *);
    HRESULT (__stdcall *GetNamedObject)(void *, hstring_t, void **);
    HRESULT (__stdcall *GetNamedArray)(void *, hstring_t, void **);
    HRESULT (__stdcall *GetNamedString)(void *, hstring_t, hstring_t *);
    HRESULT (__stdcall *GetNamedNumber)(void *, hstring_t, double *);
    HRESULT (__stdcall *GetNamedBoolean)(void *, hstring_t, boolean_t *);
} raw_jo_vtbl;

static HMODULE g_orig = NULL;
static pfn_DllGetActivationFactory g_orig_get_af = NULL;
static pfn_WindowsGetStringRawBuffer g_get_raw = NULL;
static pfn_WindowsCreateString g_create_str = NULL;
static pfn_WindowsDuplicateString g_dup_str = NULL;
static pfn_WindowsDeleteString g_del_str = NULL;
static void *g_jv_statics = NULL;

static INIT_ONCE g_json_once = INIT_ONCE_STATIC_INIT;

static BOOL CALLBACK json_init_once(PINIT_ONCE once, PVOID param, PVOID *ctx) {
    (void)once; (void)param; (void)ctx;
    char tb[8];
    DWORD tl = GetEnvironmentVariableA("DROIDDECK_UWP_TRACE", tb, sizeof(tb));
    if (tl > 0 && tl < sizeof(tb) && tb[0] != '0') g_trace_on = 1;
    HMODULE cb = LoadLibraryA("combase.dll");
    if (cb) {
        g_get_raw = (pfn_WindowsGetStringRawBuffer)(void *)GetProcAddress(cb, "WindowsGetStringRawBuffer");
        g_create_str = (pfn_WindowsCreateString)(void *)GetProcAddress(cb, "WindowsCreateString");
        g_dup_str = (pfn_WindowsDuplicateString)(void *)GetProcAddress(cb, "WindowsDuplicateString");
        g_del_str = (pfn_WindowsDeleteString)(void *)GetProcAddress(cb, "WindowsDeleteString");
    }
    g_orig = LoadLibraryA("windows.web.dll");
    if (g_orig) {
        g_orig_get_af = (pfn_DllGetActivationFactory)(void *)GetProcAddress(g_orig, "DllGetActivationFactory");
    }
    if (g_orig_get_af && g_create_str) {
        static const WCHAR cls[] = L"Windows.Data.Json.JsonValue";
        hstring_t hs = NULL;
        if (SUCCEEDED(g_create_str(cls, (UINT32)(sizeof(cls)/sizeof(WCHAR) - 1), &hs))) {
            void *af = NULL;
            if (SUCCEEDED(g_orig_get_af(hs, &af)) && af) {
                raw_af_vtbl *v = *(raw_af_vtbl **)af;
                void *st = NULL;
                if (SUCCEEDED(v->QI(af, &IID_IJsonValueStatics_, &st)) && st) {
                    g_jv_statics = st;
                }
                v->Release(af);
            }
            if (g_del_str) g_del_str(hs);
        }
    }
    return TRUE;
}

static void ensure_init(void) {
    InitOnceExecuteOnce(&g_json_once, json_init_once, NULL, NULL);
}

static int hstr_eq(hstring_t a, hstring_t b) {
    UINT32 la = 0, lb = 0;
    const WCHAR *sa = (g_get_raw && a) ? g_get_raw(a, &la) : L"";
    const WCHAR *sb = (g_get_raw && b) ? g_get_raw(b, &lb) : L"";
    if (la != lb) return 0;
    return my_memcmp(sa, sb, la * sizeof(WCHAR)) == 0;
}

/* ---------- Key-Value Pair object for IMap iteration ---------- */
typedef struct kvp_entry {
    hstring_t key;
    void *val; /* IJsonValue* */
} kvp_entry;

typedef struct kvp_obj {
    const void *vtbl;
    LONG refs;
    hstring_t key;
    void *val;
} kvp_obj;

static HRESULT __stdcall kvp_QI(void *s, const GUID *iid, void **out) {
    kvp_obj *o = (kvp_obj *)s;
    if (!out) return E_POINTER;
    if (eq_guid(iid, &IID_IUnknown_) || eq_guid(iid, &IID_IInspectable_) ||
        eq_guid(iid, &IID_IAgileObject_) || eq_guid(iid, &IID_IKVP_JsonValue_)) {
        *out = &o->vtbl;
        InterlockedIncrement(&o->refs);
        return S_OK;
    }
    web_log_guid("KVP QI_MISS", iid);
    *out = NULL;
    return E_NOINTERFACE;
}
static ULONG __stdcall kvp_AddRef(void *s) { return (ULONG)InterlockedIncrement(&((kvp_obj *)s)->refs); }
static ULONG __stdcall kvp_Release(void *s) {
    kvp_obj *o = (kvp_obj *)s;
    LONG r = InterlockedDecrement(&o->refs);
    if (r == 0) {
        if (o->key && g_del_str) g_del_str(o->key);
        if (o->val) (*(raw_jv_vtbl **)o->val)->Release(o->val);
        HeapFree(GetProcessHeap(), 0, o);
    }
    return (ULONG)r;
}
static HRESULT __stdcall kvp_GetIids(void *s, ULONG *c, GUID **i) { (void)s; if (c) *c = 0; if (i) *i = NULL; return S_OK; }
static HRESULT __stdcall kvp_GetRCN(void *s, hstring_t *n) { (void)s; if (n) *n = NULL; return S_OK; }
static HRESULT __stdcall kvp_GetTL(void *s, int *t) { (void)s; if (t) *t = 0; return S_OK; }
static HRESULT __stdcall kvp_get_Key(void *s, hstring_t *out) {
    kvp_obj *o = (kvp_obj *)s;
    if (!out) return E_POINTER;
    if (g_dup_str && o->key) return g_dup_str(o->key, out);
    *out = NULL;
    return S_OK;
}
static HRESULT __stdcall kvp_get_Value(void *s, void **out) {
    kvp_obj *o = (kvp_obj *)s;
    if (!out) return E_POINTER;
    *out = o->val;
    if (o->val) (*(raw_jv_vtbl **)o->val)->AddRef(o->val);
    return S_OK;
}
static const void *g_kvp_vtbl[] = {
    kvp_QI, kvp_AddRef, kvp_Release, kvp_GetIids, kvp_GetRCN, kvp_GetTL,
    kvp_get_Key, kvp_get_Value
};
static kvp_obj *make_kvp(hstring_t k, void *v) {
    kvp_obj *o = (kvp_obj *)HeapAlloc(GetProcessHeap(), 0, sizeof(kvp_obj));
    if (!o) return NULL;
    o->vtbl = g_kvp_vtbl;
    o->refs = 1;
    o->key = NULL;
    if (g_dup_str && k) g_dup_str(k, &o->key);
    o->val = v;
    if (v) (*(raw_jv_vtbl **)v)->AddRef(v);
    return o;
}

/* ---------- Wrapped JsonObject (IJsonObject + IJsonValue + IJsonObjectWithDefaultValues + IStringable + IMap + IMapView + IIterable) ---------- */
typedef struct wrapped_jo {
    const void *jo_vtbl;
    const void *jv_vtbl;
    const void *def_vtbl;
    const void *str_vtbl;
    const void *map_vtbl;
    const void *mv_vtbl;
    const void *iter_vtbl;
    LONG refs;
    void *inner; /* orig IJsonObject* */
    kvp_entry *items;
    UINT32 count;
    UINT32 cap;
} wrapped_jo;

static wrapped_jo *wrap_jo(void *inner);

/* Iterator over wrapped_jo */
typedef struct jo_iter {
    const void *vtbl;
    LONG refs;
    wrapped_jo *owner;
    UINT32 idx;
} jo_iter;

static ULONG __stdcall wjo_AddRef(void *self);
static ULONG __stdcall wjo_Release(void *self);

static HRESULT __stdcall it_QI(void *s, const GUID *iid, void **out) {
    jo_iter *o = (jo_iter *)s;
    if (!out) return E_POINTER;
    if (eq_guid(iid, &IID_IUnknown_) || eq_guid(iid, &IID_IInspectable_) ||
        eq_guid(iid, &IID_IAgileObject_) || eq_guid(iid, &IID_IIterator_KVP_)) {
        *out = &o->vtbl;
        InterlockedIncrement(&o->refs);
        return S_OK;
    }
    web_log_guid("JsonIter QI_MISS", iid);
    *out = NULL;
    return E_NOINTERFACE;
}
static ULONG __stdcall it_AddRef(void *s) { return (ULONG)InterlockedIncrement(&((jo_iter *)s)->refs); }
static ULONG __stdcall it_Release(void *s) {
    jo_iter *o = (jo_iter *)s;
    LONG r = InterlockedDecrement(&o->refs);
    if (r == 0) {
        if (o->owner) wjo_Release(o->owner);
        HeapFree(GetProcessHeap(), 0, o);
    }
    return (ULONG)r;
}
static HRESULT __stdcall it_GetIids(void *s, ULONG *c, GUID **i) { (void)s; if (c) *c = 0; if (i) *i = NULL; return S_OK; }
static HRESULT __stdcall it_GetRCN(void *s, hstring_t *n) { (void)s; if (n) *n = NULL; return S_OK; }
static HRESULT __stdcall it_GetTL(void *s, int *t) { (void)s; if (t) *t = 0; return S_OK; }
static HRESULT __stdcall it_get_Current(void *s, void **out) {
    jo_iter *o = (jo_iter *)s;
    if (!out) return E_POINTER;
    if (!o->owner || o->idx >= o->owner->count) { *out = NULL; return E_BOUNDS; }
    kvp_obj *k = make_kvp(o->owner->items[o->idx].key, o->owner->items[o->idx].val);
    if (!k) { *out = NULL; return E_OUTOFMEMORY; }
    *out = &k->vtbl;
    return S_OK;
}
static HRESULT __stdcall it_get_HasCurrent(void *s, boolean_t *out) {
    jo_iter *o = (jo_iter *)s;
    if (!out) return E_POINTER;
    *out = (o->owner && o->idx < o->owner->count) ? 1 : 0;
    return S_OK;
}
static HRESULT __stdcall it_MoveNext(void *s, boolean_t *out) {
    jo_iter *o = (jo_iter *)s;
    if (!out) return E_POINTER;
    if (o->owner && o->idx < o->owner->count) o->idx++;
    *out = (o->owner && o->idx < o->owner->count) ? 1 : 0;
    return S_OK;
}
static HRESULT __stdcall it_GetMany(void *s, UINT32 cap, void **items, UINT32 *actual) {
    jo_iter *o = (jo_iter *)s;
    if (!actual) return E_POINTER;
    UINT32 n = 0;
    while (n < cap && o->owner && o->idx < o->owner->count) {
        kvp_obj *k = make_kvp(o->owner->items[o->idx].key, o->owner->items[o->idx].val);
        if (!k) break;
        items[n++] = &k->vtbl;
        o->idx++;
    }
    *actual = n;
    return S_OK;
}
static const void *g_it_vtbl[] = {
    it_QI, it_AddRef, it_Release, it_GetIids, it_GetRCN, it_GetTL,
    it_get_Current, it_get_HasCurrent, it_MoveNext, it_GetMany
};

/* Internal map helpers on wrapped_jo */
static int wjo_find(wrapped_jo *o, hstring_t key) {
    for (UINT32 i = 0; i < o->count; i++) {
        if (hstr_eq(o->items[i].key, key)) return (int)i;
    }
    return -1;
}

static HRESULT wjo_put(wrapped_jo *o, hstring_t key, void *val, boolean_t *replaced) {
    if (replaced) *replaced = 0;
    void *jv = NULL;
    if (val) {
        if (FAILED((*(raw_jv_vtbl **)val)->QI(val, &IID_IJsonValue_, &jv))) {
            jv = val;
            (*(raw_jv_vtbl **)jv)->AddRef(jv);
        }
    }
    int idx = wjo_find(o, key);
    if (idx >= 0) {
        if (o->items[idx].val) (*(raw_jv_vtbl **)o->items[idx].val)->Release(o->items[idx].val);
        o->items[idx].val = jv;
        if (replaced) *replaced = 1;
        return S_OK;
    }
    if (o->count >= o->cap) {
        UINT32 nc = o->cap ? o->cap * 2 : 8;
        kvp_entry *ni = (kvp_entry *)HeapAlloc(GetProcessHeap(), 0, nc * sizeof(kvp_entry));
        if (!ni) { if (jv) (*(raw_jv_vtbl **)jv)->Release(jv); return E_OUTOFMEMORY; }
        if (o->items) {
            my_memcpy(ni, o->items, o->count * sizeof(kvp_entry));
            HeapFree(GetProcessHeap(), 0, o->items);
        }
        o->items = ni;
        o->cap = nc;
    }
    hstring_t kdup = NULL;
    if (g_dup_str && key) g_dup_str(key, &kdup);
    o->items[o->count].key = kdup;
    o->items[o->count].val = jv;
    o->count++;
    return S_OK;
}

static void wjo_clear_items(wrapped_jo *o) {
    for (UINT32 i = 0; i < o->count; i++) {
        if (o->items[i].key && g_del_str) g_del_str(o->items[i].key);
        if (o->items[i].val) (*(raw_jv_vtbl **)o->items[i].val)->Release(o->items[i].val);
    }
    o->count = 0;
}

/* Dynamic WCHAR buffer for JSON Stringify */
typedef struct wbuf {
    WCHAR *p;
    UINT32 len;
    UINT32 cap;
} wbuf;

static void wb_push(wbuf *b, WCHAR c) {
    if (b->len + 1 >= b->cap) {
        UINT32 nc = b->cap ? b->cap * 2 : 128;
        WCHAR *np = (WCHAR *)HeapAlloc(GetProcessHeap(), 0, nc * sizeof(WCHAR));
        if (!np) return;
        if (b->p) { my_memcpy(np, b->p, b->len * sizeof(WCHAR)); HeapFree(GetProcessHeap(), 0, b->p); }
        b->p = np; b->cap = nc;
    }
    b->p[b->len++] = c;
}
static void wb_push_str(wbuf *b, const WCHAR *s, UINT32 n) {
    for (UINT32 i = 0; i < n; i++) wb_push(b, s[i]);
}
static void wb_push_escaped(wbuf *b, const WCHAR *s, UINT32 n) {
    wb_push(b, L'"');
    for (UINT32 i = 0; i < n; i++) {
        WCHAR c = s[i];
        if (c == L'"' || c == L'\\') { wb_push(b, L'\\'); wb_push(b, c); }
        else if (c == L'\n') { wb_push(b, L'\\'); wb_push(b, L'n'); }
        else if (c == L'\r') { wb_push(b, L'\\'); wb_push(b, L'r'); }
        else if (c == L'\t') { wb_push(b, L'\\'); wb_push(b, L't'); }
        else wb_push(b, c);
    }
    wb_push(b, L'"');
}

/* ---------- wrapped_jo IJsonObject + QI ---------- */
static HRESULT __stdcall wjo_QI(void *self, const GUID *iid, void **out) {
    wrapped_jo *o = (wrapped_jo *)self;
    if (!out) return E_POINTER;
    if (eq_guid(iid, &IID_IUnknown_) || eq_guid(iid, &IID_IInspectable_) ||
        eq_guid(iid, &IID_IAgileObject_) || eq_guid(iid, &IID_IJsonObject_)) {
        *out = &o->jo_vtbl;
        InterlockedIncrement(&o->refs);
        return S_OK;
    }
    if (eq_guid(iid, &IID_IJsonValue_)) {
        *out = &o->jv_vtbl;
        InterlockedIncrement(&o->refs);
        return S_OK;
    }
    if (eq_guid(iid, &IID_IJsonObjectWithDef_)) {
        *out = &o->def_vtbl;
        InterlockedIncrement(&o->refs);
        return S_OK;
    }
    if (eq_guid(iid, &IID_IStringable_)) {
        *out = &o->str_vtbl;
        InterlockedIncrement(&o->refs);
        return S_OK;
    }
    if (eq_guid(iid, &IID_IMap_JsonValue_)) {
        *out = &o->map_vtbl;
        InterlockedIncrement(&o->refs);
        return S_OK;
    }
    if (eq_guid(iid, &IID_IMapView_JsonValue_)) {
        *out = &o->mv_vtbl;
        InterlockedIncrement(&o->refs);
        return S_OK;
    }
    if (eq_guid(iid, &IID_IIterable_KVP_)) {
        *out = &o->iter_vtbl;
        InterlockedIncrement(&o->refs);
        return S_OK;
    }
    web_log_guid("JsonObject QI_MISS", iid);
    *out = NULL;
    return E_NOINTERFACE;
}
static ULONG __stdcall wjo_AddRef(void *self) {
    return (ULONG)InterlockedIncrement(&((wrapped_jo *)self)->refs);
}
static ULONG __stdcall wjo_Release(void *self) {
    wrapped_jo *o = (wrapped_jo *)self;
    LONG r = InterlockedDecrement(&o->refs);
    if (r == 0) {
        wjo_clear_items(o);
        if (o->items) HeapFree(GetProcessHeap(), 0, o->items);
        if (o->inner) (*(raw_jo_vtbl **)o->inner)->Release(o->inner);
        HeapFree(GetProcessHeap(), 0, o);
    }
    return (ULONG)r;
}
static HRESULT __stdcall wjo_GetIids(void *s, ULONG *c, GUID **i) {
    wrapped_jo *o = (wrapped_jo *)s;
    if (o->inner) return (*(raw_jo_vtbl **)o->inner)->GetIids(o->inner, c, i);
    if (c) *c = 0;
    if (i) *i = NULL;
    return S_OK;
}
static HRESULT __stdcall wjo_GetRCN(void *s, hstring_t *n) {
    wrapped_jo *o = (wrapped_jo *)s;
    if (o->inner) return (*(raw_jo_vtbl **)o->inner)->GetRuntimeClassName(o->inner, n);
    if (g_create_str && n) return g_create_str(L"Windows.Data.Json.JsonObject", 28, n);
    if (n) *n = NULL;
    return S_OK;
}
static HRESULT __stdcall wjo_GetTL(void *s, int *t) { (void)s; if (t) *t = 0; return S_OK; }

static HRESULT __stdcall wjo_GetNamedValue(void *s, hstring_t name, void **ret) {
    wrapped_jo *o = (wrapped_jo *)s;
    if (!ret) return E_POINTER;
    int idx = wjo_find(o, name);
    if (idx >= 0) {
        *ret = o->items[idx].val;
        if (*ret) (*(raw_jv_vtbl **)(*ret))->AddRef(*ret);
        return S_OK;
    }
    if (o->inner) return (*(raw_jo_vtbl **)o->inner)->GetNamedValue(o->inner, name, ret);
    *ret = NULL;
    return E_BOUNDS;
}
static HRESULT __stdcall wjo_SetNamedValue(void *s, hstring_t name, void *val) {
    wrapped_jo *o = (wrapped_jo *)s;
    wjo_put(o, name, val, NULL);
    if (o->inner) (*(raw_jo_vtbl **)o->inner)->SetNamedValue(o->inner, name, val);
    return S_OK;
}
static HRESULT __stdcall wjo_GetNamedObject(void *s, hstring_t name, void **ret) {
    wrapped_jo *o = (wrapped_jo *)s;
    if (!ret) return E_POINTER;
    int idx = wjo_find(o, name);
    if (idx >= 0 && o->items[idx].val) {
        return (*(raw_jv_vtbl **)o->items[idx].val)->GetObject(o->items[idx].val, ret);
    }
    if (o->inner) {
        void *sub = NULL;
        HRESULT hr = (*(raw_jo_vtbl **)o->inner)->GetNamedObject(o->inner, name, &sub);
        if (SUCCEEDED(hr) && sub) {
            *ret = wrap_jo(sub);
            if (!*ret) { (*(raw_jo_vtbl **)sub)->Release(sub); return E_OUTOFMEMORY; }
            return S_OK;
        }
        *ret = NULL;
        return hr;
    }
    *ret = NULL;
    return E_BOUNDS;
}
static HRESULT __stdcall wjo_GetNamedArray(void *s, hstring_t name, void **ret) {
    wrapped_jo *o = (wrapped_jo *)s;
    if (!ret) return E_POINTER;
    int idx = wjo_find(o, name);
    if (idx >= 0 && o->items[idx].val) {
        return (*(raw_jv_vtbl **)o->items[idx].val)->GetArray(o->items[idx].val, ret);
    }
    if (o->inner) return (*(raw_jo_vtbl **)o->inner)->GetNamedArray(o->inner, name, ret);
    *ret = NULL;
    return E_BOUNDS;
}
static HRESULT __stdcall wjo_GetNamedString(void *s, hstring_t name, hstring_t *ret) {
    wrapped_jo *o = (wrapped_jo *)s;
    if (!ret) return E_POINTER;
    int idx = wjo_find(o, name);
    if (idx >= 0 && o->items[idx].val) {
        return (*(raw_jv_vtbl **)o->items[idx].val)->GetString(o->items[idx].val, ret);
    }
    if (o->inner) return (*(raw_jo_vtbl **)o->inner)->GetNamedString(o->inner, name, ret);
    *ret = NULL;
    return E_BOUNDS;
}
static HRESULT __stdcall wjo_GetNamedNumber(void *s, hstring_t name, double *ret) {
    wrapped_jo *o = (wrapped_jo *)s;
    if (!ret) return E_POINTER;
    int idx = wjo_find(o, name);
    if (idx >= 0 && o->items[idx].val) {
        return (*(raw_jv_vtbl **)o->items[idx].val)->GetNumber(o->items[idx].val, ret);
    }
    if (o->inner) return (*(raw_jo_vtbl **)o->inner)->GetNamedNumber(o->inner, name, ret);
    *ret = 0;
    return E_BOUNDS;
}
static HRESULT __stdcall wjo_GetNamedBoolean(void *s, hstring_t name, boolean_t *ret) {
    wrapped_jo *o = (wrapped_jo *)s;
    if (!ret) return E_POINTER;
    int idx = wjo_find(o, name);
    if (idx >= 0 && o->items[idx].val) {
        return (*(raw_jv_vtbl **)o->items[idx].val)->GetBoolean(o->items[idx].val, ret);
    }
    if (o->inner) return (*(raw_jo_vtbl **)o->inner)->GetNamedBoolean(o->inner, name, ret);
    *ret = 0;
    return E_BOUNDS;
}

/* IJsonValue view on wrapped_jo */
static wrapped_jo *from_jv(void *p) { return (wrapped_jo *)((char *)p - offsetof(wrapped_jo, jv_vtbl)); }
static HRESULT __stdcall wjo_jv_QI(void *s, const GUID *i, void **o) { return wjo_QI(from_jv(s), i, o); }
static ULONG   __stdcall wjo_jv_AddRef(void *s) { return wjo_AddRef(from_jv(s)); }
static ULONG   __stdcall wjo_jv_Release(void *s) { return wjo_Release(from_jv(s)); }
static HRESULT __stdcall wjo_jv_GetIids(void *s, ULONG *c, GUID **i) { return wjo_GetIids(from_jv(s), c, i); }
static HRESULT __stdcall wjo_jv_GetRCN(void *s, hstring_t *n) { return wjo_GetRCN(from_jv(s), n); }
static HRESULT __stdcall wjo_jv_GetTL(void *s, int *t) { return wjo_GetTL(from_jv(s), t); }
static HRESULT __stdcall wjo_jv_get_ValueType(void *s, int *t) { (void)s; if (!t) return E_POINTER; *t = 5; return S_OK; }
static HRESULT __stdcall wjo_jv_Stringify(void *s, hstring_t *out) {
    wrapped_jo *o = from_jv(s);
    if (!out) return E_POINTER;
    if (!g_create_str) { *out = NULL; return S_OK; }
    wbuf b = {NULL, 0, 0};
    wb_push(&b, L'{');
    for (UINT32 i = 0; i < o->count; i++) {
        if (i > 0) wb_push(&b, L',');
        UINT32 kl = 0;
        const WCHAR *ks = (g_get_raw && o->items[i].key) ? g_get_raw(o->items[i].key, &kl) : L"";
        wb_push_escaped(&b, ks, kl);
        wb_push(&b, L':');
        hstring_t vs = NULL;
        if (o->items[i].val && SUCCEEDED((*(raw_jv_vtbl **)o->items[i].val)->Stringify(o->items[i].val, &vs)) && vs) {
            UINT32 vl = 0;
            const WCHAR *vraw = g_get_raw ? g_get_raw(vs, &vl) : L"null";
            wb_push_str(&b, vraw, vl);
            if (g_del_str) g_del_str(vs);
        } else {
            wb_push_str(&b, L"null", 4);
        }
    }
    wb_push(&b, L'}');
    HRESULT hr = g_create_str(b.p ? b.p : L"{}", b.p ? b.len : 2, out);
    if (b.p) HeapFree(GetProcessHeap(), 0, b.p);
    return hr;
}
static HRESULT __stdcall wjo_jv_GetString(void *s, hstring_t *out) { (void)s; if (out) *out = NULL; return E_ILLEGAL_METHOD_CALL; }
static HRESULT __stdcall wjo_jv_GetNumber(void *s, double *out) { (void)s; if (out) *out = 0; return E_ILLEGAL_METHOD_CALL; }
static HRESULT __stdcall wjo_jv_GetBoolean(void *s, boolean_t *out) { (void)s; if (out) *out = 0; return E_ILLEGAL_METHOD_CALL; }
static HRESULT __stdcall wjo_jv_GetArray(void *s, void **out) { (void)s; if (out) *out = NULL; return E_ILLEGAL_METHOD_CALL; }
static HRESULT __stdcall wjo_jv_GetObject(void *s, void **out) {
    wrapped_jo *o = from_jv(s);
    if (!out) return E_POINTER;
    *out = &o->jo_vtbl;
    InterlockedIncrement(&o->refs);
    return S_OK;
}

/* IJsonObjectWithDefaultValues view */
static wrapped_jo *from_def(void *p) { return (wrapped_jo *)((char *)p - offsetof(wrapped_jo, def_vtbl)); }
static HRESULT __stdcall wjo_def_QI(void *s, const GUID *i, void **o) { return wjo_QI(from_def(s), i, o); }
static ULONG   __stdcall wjo_def_AddRef(void *s) { return wjo_AddRef(from_def(s)); }
static ULONG   __stdcall wjo_def_Release(void *s) { return wjo_Release(from_def(s)); }
static HRESULT __stdcall wjo_def_GetIids(void *s, ULONG *c, GUID **i) { return wjo_GetIids(from_def(s), c, i); }
static HRESULT __stdcall wjo_def_GetRCN(void *s, hstring_t *n) { return wjo_GetRCN(from_def(s), n); }
static HRESULT __stdcall wjo_def_GetTL(void *s, int *t) { return wjo_GetTL(from_def(s), t); }
static HRESULT __stdcall wjo_def_GetNamedValueOrDef(void *s, hstring_t name, void *defv, void **ret) {
    HRESULT hr = wjo_GetNamedValue(from_def(s), name, ret);
    if (FAILED(hr)) { *ret = defv; if (defv) (*(raw_jv_vtbl **)defv)->AddRef(defv); return S_OK; }
    return S_OK;
}
static HRESULT __stdcall wjo_def_GetNamedObjectOrDef(void *s, hstring_t name, void *defv, void **ret) {
    HRESULT hr = wjo_GetNamedObject(from_def(s), name, ret);
    if (FAILED(hr)) { *ret = defv; if (defv) (*(raw_jo_vtbl **)defv)->AddRef(defv); return S_OK; }
    return S_OK;
}
static HRESULT __stdcall wjo_def_GetNamedStringOrDef(void *s, hstring_t name, hstring_t defv, hstring_t *ret) {
    HRESULT hr = wjo_GetNamedString(from_def(s), name, ret);
    if (FAILED(hr)) { if (g_dup_str) return g_dup_str(defv, ret); *ret = NULL; return S_OK; }
    return S_OK;
}
static HRESULT __stdcall wjo_def_GetNamedArrayOrDef(void *s, hstring_t name, void *defv, void **ret) {
    HRESULT hr = wjo_GetNamedArray(from_def(s), name, ret);
    if (FAILED(hr)) { *ret = defv; if (defv) (*(raw_jo_vtbl **)defv)->AddRef(defv); return S_OK; }
    return S_OK;
}
static HRESULT __stdcall wjo_def_GetNamedNumberOrDef(void *s, hstring_t name, double defv, double *ret) {
    HRESULT hr = wjo_GetNamedNumber(from_def(s), name, ret);
    if (FAILED(hr)) { *ret = defv; return S_OK; }
    return S_OK;
}
static HRESULT __stdcall wjo_def_GetNamedBooleanOrDef(void *s, hstring_t name, boolean_t defv, boolean_t *ret) {
    HRESULT hr = wjo_GetNamedBoolean(from_def(s), name, ret);
    if (FAILED(hr)) { *ret = defv; return S_OK; }
    return S_OK;
}

/* IStringable view */
static wrapped_jo *from_str(void *p) { return (wrapped_jo *)((char *)p - offsetof(wrapped_jo, str_vtbl)); }
static HRESULT __stdcall wjo_str_QI(void *s, const GUID *i, void **o) { return wjo_QI(from_str(s), i, o); }
static ULONG   __stdcall wjo_str_AddRef(void *s) { return wjo_AddRef(from_str(s)); }
static ULONG   __stdcall wjo_str_Release(void *s) { return wjo_Release(from_str(s)); }
static HRESULT __stdcall wjo_str_GetIids(void *s, ULONG *c, GUID **i) { return wjo_GetIids(from_str(s), c, i); }
static HRESULT __stdcall wjo_str_GetRCN(void *s, hstring_t *n) { return wjo_GetRCN(from_str(s), n); }
static HRESULT __stdcall wjo_str_GetTL(void *s, int *t) { return wjo_GetTL(from_str(s), t); }
static HRESULT __stdcall wjo_str_ToString(void *s, hstring_t *out) { return wjo_jv_Stringify(&from_str(s)->jv_vtbl, out); }

/* IMap<HSTRING, IJsonValue> view */
static wrapped_jo *from_map(void *p) { return (wrapped_jo *)((char *)p - offsetof(wrapped_jo, map_vtbl)); }
static HRESULT __stdcall wjo_map_QI(void *s, const GUID *i, void **o) { return wjo_QI(from_map(s), i, o); }
static ULONG   __stdcall wjo_map_AddRef(void *s) { return wjo_AddRef(from_map(s)); }
static ULONG   __stdcall wjo_map_Release(void *s) { return wjo_Release(from_map(s)); }
static HRESULT __stdcall wjo_map_GetIids(void *s, ULONG *c, GUID **i) { return wjo_GetIids(from_map(s), c, i); }
static HRESULT __stdcall wjo_map_GetRCN(void *s, hstring_t *n) { return wjo_GetRCN(from_map(s), n); }
static HRESULT __stdcall wjo_map_GetTL(void *s, int *t) { return wjo_GetTL(from_map(s), t); }
static HRESULT __stdcall wjo_map_Lookup(void *s, hstring_t key, void **ret) {
    return wjo_GetNamedValue(from_map(s), key, ret);
}
static HRESULT __stdcall wjo_map_get_Size(void *s, UINT32 *size) {
    wrapped_jo *o = from_map(s);
    if (!size) return E_POINTER;
    *size = o->count;
    return S_OK;
}
static HRESULT __stdcall wjo_map_HasKey(void *s, hstring_t key, boolean_t *found) {
    wrapped_jo *o = from_map(s);
    if (!found) return E_POINTER;
    if (wjo_find(o, key) >= 0) { *found = 1; return S_OK; }
    if (o->inner) {
        void *tmp = NULL;
        if (SUCCEEDED((*(raw_jo_vtbl **)o->inner)->GetNamedValue(o->inner, key, &tmp)) && tmp) {
            (*(raw_jv_vtbl **)tmp)->Release(tmp);
            *found = 1;
            return S_OK;
        }
    }
    *found = 0;
    return S_OK;
}
static HRESULT __stdcall wjo_map_GetView(void *s, void **view) {
    wrapped_jo *o = from_map(s);
    if (!view) return E_POINTER;
    *view = &o->mv_vtbl;
    InterlockedIncrement(&o->refs);
    return S_OK;
}
static HRESULT __stdcall wjo_map_Insert(void *s, hstring_t key, void *val, boolean_t *replaced) {
    wrapped_jo *o = from_map(s);
    HRESULT hr = wjo_put(o, key, val, replaced);
    if (o->inner) (*(raw_jo_vtbl **)o->inner)->SetNamedValue(o->inner, key, val);
    return hr;
}
static HRESULT __stdcall wjo_map_Remove(void *s, hstring_t key) {
    wrapped_jo *o = from_map(s);
    int idx = wjo_find(o, key);
    if (idx < 0) return E_BOUNDS;
    if (o->items[idx].key && g_del_str) g_del_str(o->items[idx].key);
    if (o->items[idx].val) (*(raw_jv_vtbl **)o->items[idx].val)->Release(o->items[idx].val);
    for (UINT32 i = (UINT32)idx; i + 1 < o->count; i++) o->items[i] = o->items[i + 1];
    o->count--;
    return S_OK;
}
static HRESULT __stdcall wjo_map_Clear(void *s) {
    wjo_clear_items(from_map(s));
    return S_OK;
}

/* IMapView<HSTRING, IJsonValue> view */
static wrapped_jo *from_mv(void *p) { return (wrapped_jo *)((char *)p - offsetof(wrapped_jo, mv_vtbl)); }
static HRESULT __stdcall wjo_mv_QI(void *s, const GUID *i, void **o) { return wjo_QI(from_mv(s), i, o); }
static ULONG   __stdcall wjo_mv_AddRef(void *s) { return wjo_AddRef(from_mv(s)); }
static ULONG   __stdcall wjo_mv_Release(void *s) { return wjo_Release(from_mv(s)); }
static HRESULT __stdcall wjo_mv_GetIids(void *s, ULONG *c, GUID **i) { return wjo_GetIids(from_mv(s), c, i); }
static HRESULT __stdcall wjo_mv_GetRCN(void *s, hstring_t *n) { return wjo_GetRCN(from_mv(s), n); }
static HRESULT __stdcall wjo_mv_GetTL(void *s, int *t) { return wjo_GetTL(from_mv(s), t); }
static HRESULT __stdcall wjo_mv_Lookup(void *s, hstring_t key, void **ret) { return wjo_GetNamedValue(from_mv(s), key, ret); }
static HRESULT __stdcall wjo_mv_get_Size(void *s, UINT32 *size) { if (!size) return E_POINTER; *size = from_mv(s)->count; return S_OK; }
static HRESULT __stdcall wjo_mv_HasKey(void *s, hstring_t key, boolean_t *found) { return wjo_map_HasKey(&from_mv(s)->map_vtbl, key, found); }
static HRESULT __stdcall wjo_mv_Split(void *s, void **first, void **second) {
    (void)s;
    if (first) *first = NULL;
    if (second) *second = NULL;
    return S_OK;
}

/* IIterable<IKeyValuePair<HSTRING, IJsonValue>> view */
static wrapped_jo *from_it(void *p) { return (wrapped_jo *)((char *)p - offsetof(wrapped_jo, iter_vtbl)); }
static HRESULT __stdcall wjo_it_QI(void *s, const GUID *i, void **o) { return wjo_QI(from_it(s), i, o); }
static ULONG   __stdcall wjo_it_AddRef(void *s) { return wjo_AddRef(from_it(s)); }
static ULONG   __stdcall wjo_it_Release(void *s) { return wjo_Release(from_it(s)); }
static HRESULT __stdcall wjo_it_GetIids(void *s, ULONG *c, GUID **i) { return wjo_GetIids(from_it(s), c, i); }
static HRESULT __stdcall wjo_it_GetRCN(void *s, hstring_t *n) { return wjo_GetRCN(from_it(s), n); }
static HRESULT __stdcall wjo_it_GetTL(void *s, int *t) { return wjo_GetTL(from_it(s), t); }
static HRESULT __stdcall wjo_it_First(void *s, void **out) {
    wrapped_jo *o = from_it(s);
    if (!out) return E_POINTER;
    jo_iter *it = (jo_iter *)HeapAlloc(GetProcessHeap(), 0, sizeof(jo_iter));
    if (!it) { *out = NULL; return E_OUTOFMEMORY; }
    it->vtbl = g_it_vtbl;
    it->refs = 1;
    it->owner = o;
    it->idx = 0;
    InterlockedIncrement(&o->refs);
    *out = &it->vtbl;
    return S_OK;
}

static const void *g_wjo_vtbl[] = {
    wjo_QI, wjo_AddRef, wjo_Release, wjo_GetIids, wjo_GetRCN, wjo_GetTL,
    wjo_GetNamedValue, wjo_SetNamedValue, wjo_GetNamedObject, wjo_GetNamedArray,
    wjo_GetNamedString, wjo_GetNamedNumber, wjo_GetNamedBoolean
};
static const void *g_wjo_jv_vtbl[] = {
    wjo_jv_QI, wjo_jv_AddRef, wjo_jv_Release, wjo_jv_GetIids, wjo_jv_GetRCN, wjo_jv_GetTL,
    wjo_jv_get_ValueType, wjo_jv_Stringify, wjo_jv_GetString, wjo_jv_GetNumber,
    wjo_jv_GetBoolean, wjo_jv_GetArray, wjo_jv_GetObject
};
static const void *g_wjo_def_vtbl[] = {
    wjo_def_QI, wjo_def_AddRef, wjo_def_Release, wjo_def_GetIids, wjo_def_GetRCN, wjo_def_GetTL,
    wjo_def_GetNamedValueOrDef, wjo_def_GetNamedObjectOrDef, wjo_def_GetNamedStringOrDef,
    wjo_def_GetNamedArrayOrDef, wjo_def_GetNamedNumberOrDef, wjo_def_GetNamedBooleanOrDef
};
static const void *g_wjo_str_vtbl[] = {
    wjo_str_QI, wjo_str_AddRef, wjo_str_Release, wjo_str_GetIids, wjo_str_GetRCN, wjo_str_GetTL,
    wjo_str_ToString
};
static const void *g_wjo_map_vtbl[] = {
    wjo_map_QI, wjo_map_AddRef, wjo_map_Release, wjo_map_GetIids, wjo_map_GetRCN, wjo_map_GetTL,
    wjo_map_Lookup, wjo_map_get_Size, wjo_map_HasKey, wjo_map_GetView,
    wjo_map_Insert, wjo_map_Remove, wjo_map_Clear
};
static const void *g_wjo_mv_vtbl[] = {
    wjo_mv_QI, wjo_mv_AddRef, wjo_mv_Release, wjo_mv_GetIids, wjo_mv_GetRCN, wjo_mv_GetTL,
    wjo_mv_Lookup, wjo_mv_get_Size, wjo_mv_HasKey, wjo_mv_Split
};
static const void *g_wjo_it_vtbl[] = {
    wjo_it_QI, wjo_it_AddRef, wjo_it_Release, wjo_it_GetIids, wjo_it_GetRCN, wjo_it_GetTL,
    wjo_it_First
};

static wrapped_jo *wrap_jo(void *inner) {
    wrapped_jo *o = (wrapped_jo *)HeapAlloc(GetProcessHeap(), 0, sizeof(wrapped_jo));
    if (!o) return NULL;
    o->jo_vtbl = g_wjo_vtbl;
    o->jv_vtbl = g_wjo_jv_vtbl;
    o->def_vtbl = g_wjo_def_vtbl;
    o->str_vtbl = g_wjo_str_vtbl;
    o->map_vtbl = g_wjo_map_vtbl;
    o->mv_vtbl = g_wjo_mv_vtbl;
    o->iter_vtbl = g_wjo_it_vtbl;
    o->refs = 1;
    o->inner = inner;
    o->items = NULL;
    o->count = 0;
    o->cap = 0;
    return o;
}

/* ---------- Wrapped JsonObject Factory (IActivationFactory + IJsonObjectStatics) ---------- */
typedef struct jo_factory {
    const void *af_vtbl;
    const void *st_vtbl;
    void *orig_af;
} jo_factory;
static jo_factory g_jo_factory;

static HRESULT __stdcall jof_QI(void *s, const GUID *iid, void **out) {
    (void)s;
    if (!out) return E_POINTER;
    if (eq_guid(iid, &IID_IUnknown_) || eq_guid(iid, &IID_IInspectable_) ||
        eq_guid(iid, &IID_IAgileObject_) || eq_guid(iid, &IID_IActivationFactory_)) {
        *out = &g_jo_factory.af_vtbl;
        return S_OK;
    }
    if (eq_guid(iid, &IID_IJsonObjectStatics_)) {
        *out = &g_jo_factory.st_vtbl;
        return S_OK;
    }
    web_log_guid("JsonObjectFactory QI_MISS", iid);
    *out = NULL;
    return E_NOINTERFACE;
}
static ULONG __stdcall jof_AddRef(void *s) { (void)s; return 2; }
static ULONG __stdcall jof_Release(void *s) { (void)s; return 1; }
static HRESULT __stdcall jof_GetIids(void *s, ULONG *c, GUID **i) { (void)s; if (c) *c = 0; if (i) *i = NULL; return S_OK; }
static HRESULT __stdcall jof_GetRCN(void *s, hstring_t *n) { (void)s; if (n) *n = NULL; return E_ILLEGAL_METHOD_CALL; }
static HRESULT __stdcall jof_GetTL(void *s, int *t) { (void)s; if (t) *t = 0; return S_OK; }
static HRESULT __stdcall jof_ActivateInstance(void *s, void **out) {
    (void)s;
    if (!out) return E_POINTER;
    *out = NULL;
    void *inner = NULL;
    if (g_jo_factory.orig_af) {
        (*(raw_af_vtbl **)g_jo_factory.orig_af)->ActivateInstance(g_jo_factory.orig_af, &inner);
    }
    wrapped_jo *w = wrap_jo(inner);
    if (!w) { if (inner) (*(raw_jo_vtbl **)inner)->Release(inner); return E_OUTOFMEMORY; }
    *out = &w->jo_vtbl;
    return S_OK;
}
static HRESULT __stdcall jos_QI(void *s, const GUID *i, void **o) { return jof_QI(s, i, o); }
static HRESULT __stdcall jos_Parse(void *s, hstring_t input, void **result) {
    (void)s;
    if (!result) return E_POINTER;
    *result = NULL;
    ensure_init();
    if (!g_jv_statics) return E_FAIL;
    void *jv = NULL;
    HRESULT hr = (*(raw_jvs_vtbl **)g_jv_statics)->Parse(g_jv_statics, input, &jv);
    if (FAILED(hr) || !jv) return hr;
    void *jo = NULL;
    hr = (*(raw_jv_vtbl **)jv)->GetObject(jv, &jo);
    (*(raw_jv_vtbl **)jv)->Release(jv);
    if (SUCCEEDED(hr) && jo) {
        wrapped_jo *w = wrap_jo(jo);
        if (!w) { (*(raw_jo_vtbl **)jo)->Release(jo); return E_OUTOFMEMORY; }
        *result = &w->jo_vtbl;
    }
    return hr;
}
static HRESULT __stdcall jos_TryParse(void *s, hstring_t input, void **result, boolean_t *succeeded) {
    (void)s;
    if (result) *result = NULL;
    if (succeeded) *succeeded = 0;
    if (!result || !succeeded) return E_POINTER;
    ensure_init();
    if (!g_jv_statics) return S_OK;
    void *jv = NULL;
    boolean_t ok = 0;
    HRESULT hr = (*(raw_jvs_vtbl **)g_jv_statics)->TryParse(g_jv_statics, input, &jv, &ok);
    if (FAILED(hr) || !ok || !jv) return S_OK;
    int vt = 0;
    if (SUCCEEDED((*(raw_jv_vtbl **)jv)->get_ValueType(jv, &vt)) && vt == 5) {
        void *jo = NULL;
        if (SUCCEEDED((*(raw_jv_vtbl **)jv)->GetObject(jv, &jo)) && jo) {
            wrapped_jo *w = wrap_jo(jo);
            if (w) { *result = &w->jo_vtbl; *succeeded = 1; }
            else (*(raw_jo_vtbl **)jo)->Release(jo);
        }
    }
    (*(raw_jv_vtbl **)jv)->Release(jv);
    return S_OK;
}
static const void *g_jof_vtbl[] = {
    jof_QI, jof_AddRef, jof_Release, jof_GetIids, jof_GetRCN, jof_GetTL, jof_ActivateInstance
};
static const void *g_jos_vtbl[] = {
    jos_QI, jof_AddRef, jof_Release, jof_GetIids, jof_GetRCN, jof_GetTL, jos_Parse, jos_TryParse
};

/* ---------- Wrapped JsonArray Factory (IActivationFactory + IJsonArrayStatics) ---------- */
typedef struct ja_factory {
    const void *af_vtbl;
    const void *st_vtbl;
    void *orig_af;
} ja_factory;
static ja_factory g_ja_factory;

static HRESULT __stdcall jaf_QI(void *s, const GUID *iid, void **out) {
    (void)s;
    if (!out) return E_POINTER;
    if (eq_guid(iid, &IID_IUnknown_) || eq_guid(iid, &IID_IInspectable_) ||
        eq_guid(iid, &IID_IAgileObject_) || eq_guid(iid, &IID_IActivationFactory_)) {
        *out = &g_ja_factory.af_vtbl;
        return S_OK;
    }
    if (eq_guid(iid, &IID_IJsonArrayStatics_)) {
        *out = &g_ja_factory.st_vtbl;
        return S_OK;
    }
    web_log_guid("JsonArrayFactory QI_MISS", iid);
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall jaf_ActivateInstance(void *s, void **out) {
    (void)s;
    if (!g_ja_factory.orig_af) return E_FAIL;
    return (*(raw_af_vtbl **)g_ja_factory.orig_af)->ActivateInstance(g_ja_factory.orig_af, out);
}
static HRESULT __stdcall jas_QI(void *s, const GUID *i, void **o) { return jaf_QI(s, i, o); }
static HRESULT __stdcall jas_Parse(void *s, hstring_t input, void **result) {
    (void)s;
    if (!result) return E_POINTER;
    *result = NULL;
    ensure_init();
    if (!g_jv_statics) return E_FAIL;
    void *jv = NULL;
    HRESULT hr = (*(raw_jvs_vtbl **)g_jv_statics)->Parse(g_jv_statics, input, &jv);
    if (FAILED(hr) || !jv) return hr;
    hr = (*(raw_jv_vtbl **)jv)->GetArray(jv, result);
    (*(raw_jv_vtbl **)jv)->Release(jv);
    return hr;
}
static HRESULT __stdcall jas_TryParse(void *s, hstring_t input, void **result, boolean_t *succeeded) {
    (void)s;
    if (result) *result = NULL;
    if (succeeded) *succeeded = 0;
    if (!result || !succeeded) return E_POINTER;
    ensure_init();
    if (!g_jv_statics) return S_OK;
    void *jv = NULL;
    boolean_t ok = 0;
    HRESULT hr = (*(raw_jvs_vtbl **)g_jv_statics)->TryParse(g_jv_statics, input, &jv, &ok);
    if (FAILED(hr) || !ok || !jv) return S_OK;
    int vt = 0;
    if (SUCCEEDED((*(raw_jv_vtbl **)jv)->get_ValueType(jv, &vt)) && vt == 4) {
        if (SUCCEEDED((*(raw_jv_vtbl **)jv)->GetArray(jv, result)) && *result) *succeeded = 1;
    }
    (*(raw_jv_vtbl **)jv)->Release(jv);
    return S_OK;
}
static const void *g_jaf_vtbl[] = {
    jaf_QI, jof_AddRef, jof_Release, jof_GetIids, jof_GetRCN, jof_GetTL, jaf_ActivateInstance
};
static const void *g_jas_vtbl[] = {
    jas_QI, jof_AddRef, jof_Release, jof_GetIids, jof_GetRCN, jof_GetTL, jas_Parse, jas_TryParse
};

/* ---------- NullJsonValue singleton ---------- */
typedef struct null_jv {
    const void *jv_vtbl;
    const void *str_vtbl;
} null_jv;
static null_jv g_null_jv;

static HRESULT __stdcall njv_QI(void *s, const GUID *iid, void **out) {
    (void)s;
    if (!out) return E_POINTER;
    if (eq_guid(iid, &IID_IUnknown_) || eq_guid(iid, &IID_IInspectable_) ||
        eq_guid(iid, &IID_IAgileObject_) || eq_guid(iid, &IID_IJsonValue_)) {
        *out = &g_null_jv.jv_vtbl;
        return S_OK;
    }
    if (eq_guid(iid, &IID_IStringable_)) {
        *out = &g_null_jv.str_vtbl;
        return S_OK;
    }
    web_log_guid("NullJsonValue QI_MISS", iid);
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall njv_GetRCN(void *s, hstring_t *n) {
    (void)s;
    if (!n) return E_POINTER;
    if (g_create_str) return g_create_str(L"Windows.Data.Json.JsonValue", 27, n);
    *n = NULL; return S_OK;
}
static HRESULT __stdcall njv_get_ValueType(void *s, int *t) { (void)s; if (!t) return E_POINTER; *t = 0; return S_OK; }
static HRESULT __stdcall njv_Stringify(void *s, hstring_t *out) {
    (void)s;
    if (!out) return E_POINTER;
    if (g_create_str) return g_create_str(L"null", 4, out);
    *out = NULL; return S_OK;
}
static HRESULT __stdcall njv_GetString(void *s, hstring_t *out) { (void)s; if (out) *out = NULL; return E_ILLEGAL_METHOD_CALL; }
static HRESULT __stdcall njv_GetNumber(void *s, double *out) { (void)s; if (out) *out = 0; return E_ILLEGAL_METHOD_CALL; }
static HRESULT __stdcall njv_GetBoolean(void *s, boolean_t *out) { (void)s; if (out) *out = 0; return E_ILLEGAL_METHOD_CALL; }
static HRESULT __stdcall njv_GetArray(void *s, void **out) { (void)s; if (out) *out = NULL; return E_ILLEGAL_METHOD_CALL; }
static HRESULT __stdcall njv_GetObject(void *s, void **out) { (void)s; if (out) *out = NULL; return E_ILLEGAL_METHOD_CALL; }
static const void *g_njv_vtbl[] = {
    njv_QI, jof_AddRef, jof_Release, jof_GetIids, njv_GetRCN, jof_GetTL,
    njv_get_ValueType, njv_Stringify, njv_GetString, njv_GetNumber,
    njv_GetBoolean, njv_GetArray, njv_GetObject
};
static const void *g_njv_str_vtbl[] = {
    njv_QI, jof_AddRef, jof_Release, jof_GetIids, njv_GetRCN, jof_GetTL,
    njv_Stringify
};

/* ---------- Wrapped JsonValue Factory (IActivationFactory + IJsonValueStatics + IJsonValueStatics2) ---------- */
typedef struct jv_factory {
    const void *af_vtbl;
    const void *st1_vtbl;
    const void *st2_vtbl;
    void *orig_af;
} jv_factory;
static jv_factory g_jv_factory;

static HRESULT __stdcall jvf_QI(void *s, const GUID *iid, void **out) {
    (void)s;
    if (!out) return E_POINTER;
    if (eq_guid(iid, &IID_IUnknown_) || eq_guid(iid, &IID_IInspectable_) ||
        eq_guid(iid, &IID_IAgileObject_) || eq_guid(iid, &IID_IActivationFactory_)) {
        *out = &g_jv_factory.af_vtbl;
        return S_OK;
    }
    if (eq_guid(iid, &IID_IJsonValueStatics_)) {
        *out = &g_jv_factory.st1_vtbl;
        return S_OK;
    }
    if (eq_guid(iid, &IID_IJsonValueStatics2_)) {
        *out = &g_jv_factory.st2_vtbl;
        return S_OK;
    }
    web_log_guid("JsonValueFactory QI_MISS", iid);
    *out = NULL;
    return E_NOINTERFACE;
}
static HRESULT __stdcall jvf_ActivateInstance(void *s, void **out) {
    (void)s;
    if (!g_jv_factory.orig_af) return E_FAIL;
    return (*(raw_af_vtbl **)g_jv_factory.orig_af)->ActivateInstance(g_jv_factory.orig_af, out);
}
static HRESULT __stdcall jvs1_QI(void *s, const GUID *i, void **o) { return jvf_QI(s, i, o); }
static HRESULT __stdcall jvs1_Parse(void *s, hstring_t in, void **out) {
    (void)s; ensure_init(); if (!g_jv_statics) return E_FAIL;
    return (*(raw_jvs_vtbl **)g_jv_statics)->Parse(g_jv_statics, in, out);
}
static HRESULT __stdcall jvs1_TryParse(void *s, hstring_t in, void **out, boolean_t *ok) {
    (void)s; ensure_init(); if (!g_jv_statics) return E_FAIL;
    return (*(raw_jvs_vtbl **)g_jv_statics)->TryParse(g_jv_statics, in, out, ok);
}
static HRESULT __stdcall jvs1_CreateBool(void *s, boolean_t v, void **out) {
    (void)s; ensure_init(); if (!g_jv_statics) return E_FAIL;
    return (*(raw_jvs_vtbl **)g_jv_statics)->CreateBooleanValue(g_jv_statics, v, out);
}
static HRESULT __stdcall jvs1_CreateNum(void *s, double v, void **out) {
    (void)s; ensure_init(); if (!g_jv_statics) return E_FAIL;
    return (*(raw_jvs_vtbl **)g_jv_statics)->CreateNumberValue(g_jv_statics, v, out);
}
static HRESULT __stdcall jvs1_CreateStr(void *s, hstring_t v, void **out) {
    (void)s; ensure_init(); if (!g_jv_statics) return E_FAIL;
    return (*(raw_jvs_vtbl **)g_jv_statics)->CreateStringValue(g_jv_statics, v, out);
}
static HRESULT __stdcall jvs2_QI(void *s, const GUID *i, void **o) { return jvf_QI(s, i, o); }
static HRESULT __stdcall jvs2_CreateNullValue(void *s, void **result) {
    (void)s;
    if (!result) return E_POINTER;
    *result = NULL;
    ensure_init();
    if (g_jv_statics && g_create_str) {
        hstring_t hs = NULL;
        if (SUCCEEDED(g_create_str(L"null", 4, &hs)) && hs) {
            HRESULT hr = (*(raw_jvs_vtbl **)g_jv_statics)->Parse(g_jv_statics, hs, result);
            if (g_del_str) g_del_str(hs);
            if (SUCCEEDED(hr) && *result) return S_OK;
        }
    }
    g_null_jv.jv_vtbl = g_njv_vtbl;
    g_null_jv.str_vtbl = g_njv_str_vtbl;
    *result = &g_null_jv.jv_vtbl;
    return S_OK;
}
static const void *g_jvf_vtbl[] = {
    jvf_QI, jof_AddRef, jof_Release, jof_GetIids, jof_GetRCN, jof_GetTL, jvf_ActivateInstance
};
static const void *g_jvs1_vtbl[] = {
    jvs1_QI, jof_AddRef, jof_Release, jof_GetIids, jof_GetRCN, jof_GetTL,
    jvs1_Parse, jvs1_TryParse, jvs1_CreateBool, jvs1_CreateNum, jvs1_CreateStr
};
static const void *g_jvs2_vtbl[] = {
    jvs2_QI, jof_AddRef, jof_Release, jof_GetIids, jof_GetRCN, jof_GetTL, jvs2_CreateNullValue
};

static INIT_ONCE g_json_factories_once = INIT_ONCE_STATIC_INIT;

static void init_orig_factory(const WCHAR *cls, UINT32 len, void **slot) {
    if (!g_orig_get_af || !g_create_str) return;
    hstring_t hs = NULL;
    if (SUCCEEDED(g_create_str(cls, len, &hs)) && hs) {
        g_orig_get_af(hs, slot);
        if (g_del_str) g_del_str(hs);
    }
}

static BOOL CALLBACK json_factories_init_once(PINIT_ONCE once, PVOID param, PVOID *ctx) {
    (void)once; (void)param; (void)ctx;
    ensure_init();
    g_jo_factory.af_vtbl = g_jof_vtbl;
    g_jo_factory.st_vtbl = g_jos_vtbl;
    init_orig_factory(L"Windows.Data.Json.JsonObject", 28, &g_jo_factory.orig_af);

    g_ja_factory.af_vtbl = g_jaf_vtbl;
    g_ja_factory.st_vtbl = g_jas_vtbl;
    init_orig_factory(L"Windows.Data.Json.JsonArray", 27, &g_ja_factory.orig_af);

    g_jv_factory.af_vtbl = g_jvf_vtbl;
    g_jv_factory.st1_vtbl = g_jvs1_vtbl;
    g_jv_factory.st2_vtbl = g_jvs2_vtbl;
    init_orig_factory(L"Windows.Data.Json.JsonValue", 27, &g_jv_factory.orig_af);
    return TRUE;
}

int uwp_json_lookup(const WCHAR *class_name, void **factory);
int uwp_json_lookup(const WCHAR *class_name, void **factory) {
    if (!class_name || !factory) return 0;
    if (my_wcscmp(class_name, L"Windows.Data.Json.JsonObject") == 0) {
        InitOnceExecuteOnce(&g_json_factories_once, json_factories_init_once, NULL, NULL);
        if (!g_orig_get_af) return 0;
        *factory = &g_jo_factory.af_vtbl;
        return 1;
    }
    if (my_wcscmp(class_name, L"Windows.Data.Json.JsonArray") == 0) {
        InitOnceExecuteOnce(&g_json_factories_once, json_factories_init_once, NULL, NULL);
        if (!g_orig_get_af) return 0;
        *factory = &g_ja_factory.af_vtbl;
        return 1;
    }
    if (my_wcscmp(class_name, L"Windows.Data.Json.JsonValue") == 0) {
        InitOnceExecuteOnce(&g_json_factories_once, json_factories_init_once, NULL, NULL);
        if (!g_orig_get_af) return 0;
        *factory = &g_jv_factory.af_vtbl;
        return 1;
    }
    return 0;
}
