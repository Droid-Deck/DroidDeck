/*
 * Windows Runtime app-model host for packaged titles whose manifest names a UWP
 * entry point.
 *
 * Wine resolves an ActivatableClassId to a DLL and calls that DLL's
 * DllGetActivationFactory, but it ships nothing behind the app-model classes. A
 * title that starts its view through `CoreApplication::Run` therefore has no
 * host to run it: the call has nowhere to go and the title never gets a view.
 *
 * This DLL answers those classes, owns the Win32 window that stands in for the
 * app-model window, and drives the title's own IFrameworkView the way Windows
 * does:
 *
 *     source->CreateView(&view)
 *     view->Initialize(app_view)
 *     view->SetWindow(core_window)
 *     view->Load(L"App")
 *     view->Run()
 *     view->Uninitialize()
 *
 * It supplies nothing else: rendering, input and presentation stay with the
 * title and with the graphics stack the runtime already ships. A class the host
 * does not answer is reported once on stderr and the activation fails the way
 * it would on a machine without the app model, so a missing piece is visible
 * rather than silently stubbed.
 *
 * Set DROIDDECK_UWP_TRACE=1 to trace the lifecycle on stderr; the host is
 * otherwise silent.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

/* --------------------------------------------------------------- COM basics */

typedef struct {
    const void **vtbl;
    LONG refs;
    const char *name;
    int permanent;
    /* Set on a factory object: the class entry whose statics it forwards to. */
    void *owner;
} host_object;

static const GUID iid_unknown = {0x00000000, 0x0000, 0x0000,
                                 {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const GUID iid_agile_object = {0x94ea2b94, 0xe9cc, 0x49e0,
                                      {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};
static const GUID iid_inspectable = {0xaf86e2e0, 0xb12d, 0x4c6a,
                                     {0x9c, 0x5a, 0xd7, 0xaa, 0x65, 0x10, 0x1e, 0x90}};
static const GUID iid_activation_factory = {0x00000035, 0x0000, 0x0000,
                                             {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};

/* Windows.ApplicationModel.Core.ICoreApplication */
static const GUID iid_core_application = {0x0aacf7a4, 0x5e1d, 0x49df,
                                          {0x80, 0x34, 0xfb, 0x6a, 0x68, 0xbc, 0x5e, 0xd1}};
/* Windows.ApplicationModel.Core.ICoreApplicationUseCount */
static const GUID iid_core_application_use_count = {0x518dc408, 0xc077, 0x475b,
                                                    {0x80, 0x9e, 0x0b, 0xc0, 0xc5, 0x7e, 0x4b, 0x74}};
/* Windows.ApplicationModel.Core.ICoreApplicationExit */
static const GUID iid_core_application_exit = {0xcf86461d, 0x261e, 0x4b72,
                                               {0x9a, 0xcd, 0x44, 0xed, 0x2a, 0xce, 0x6a, 0x29}};
/* Windows.ApplicationModel.Core.ICoreApplicationUnhandledError */
static const GUID iid_core_application_unhandled = {0xf0e24ab0, 0xdd09, 0x42e1,
                                                    {0xb0, 0xbc, 0xe0, 0xe1, 0x31, 0xf7, 0x8d, 0x7e}};
/* Windows.ApplicationModel.Core.ICoreApplicationView */
static const GUID iid_core_application_view = {0x638bb2db, 0x451d, 0x4661,
                                               {0xb0, 0x99, 0x41, 0x4f, 0x34, 0xff, 0xb9, 0xf1}};
/* Windows.UI.Core.ICoreWindow */
static const GUID iid_core_window = {0x79b9d5f2, 0x879e, 0x4b89,
                                     {0xb7, 0x98, 0x79, 0xe4, 0x75, 0x98, 0x03, 0x0c}};
/* Windows.UI.Core.ICoreWindowStatic */
static const GUID iid_core_window_static = {0x4d239005, 0x3c2a, 0x41b1,
                                            {0x90, 0x22, 0x53, 0x6b, 0xb9, 0xcf, 0x93, 0xb1}};
/* ICoreWindowInterop (corewindow.h) is a classic COM interface, not a WinRT
   one: IUnknown plus two methods, so the caller's get_WindowHandle would land
   on GetIids behind an IInspectable layout. */
static const GUID iid_core_window_interop = {0x45d64a29, 0xa63e, 0x4cb6,
                                             {0xb4, 0x98, 0x57, 0x81, 0xd2, 0x98, 0xcb, 0x4f}};
/* Windows.UI.Core.ICoreDispatcher */
static const GUID iid_core_dispatcher = {0x60db2fa8, 0xb705, 0x4fde,
                                         {0xa7, 0xd6, 0xeb, 0xbb, 0x18, 0x91, 0xd3, 0x9e}};

typedef void *hstring_t;

static int trace_on = -1;
static HINSTANCE host_module;

/* A packaged title's stderr is not reachable from a loaded DLL in this runtime,
   so the lifecycle trace is appended to a file with the kernel32 calls only: no
   CRT file table, no stack walk, nothing that takes the loader lock the
   activation already holds. Four candidates, because a title can run against a
   prefix whose drive root refuses the earlier ones: the root of C:, the
   directory this DLL was loaded from, the process working directory, and the
   user's temp directory. */
static const char *trace_path(void)
{
    static char beside[1024];
    static const char suffix[] = "uwp-host-trace.log";
    DWORD got;
    if (beside[0]) return beside;
    got = GetModuleFileNameA(host_module, beside, sizeof beside);
    if (got == 0 || got >= sizeof beside) {
        beside[0] = 0;
        return "C:\\uwp-host-trace.log";
    }
    {
        size_t at = got;
        while (at > 0 && beside[at - 1] != '\\') at--;
        if (at + sizeof(suffix) > sizeof(beside)) {
            beside[0] = 0;
            return "C:\\uwp-host-trace.log";
        }
        memcpy(beside + at, suffix, sizeof(suffix));
    }
    return beside;
}

static const char *temp_path(void)
{
    static char in_temp[MAX_PATH + 32];
    static const char suffix[] = "uwp-host-trace.log";
    DWORD length = GetTempPathA(MAX_PATH, in_temp);
    if (!length || length >= MAX_PATH || (size_t)length + sizeof(suffix) > sizeof(in_temp))
        return NULL;
    memcpy(in_temp + length, suffix, sizeof(suffix));
    return in_temp;
}

/* One log per process, not one log per prefix. A trace that appends leaves the
   start of the run buried under the previous run, and the first thing a title
   does is exactly what a stall has to be read from. */
static void trace_begin_run(void)
{
    char setting[8];
    DWORD length = GetEnvironmentVariableA("DROIDDECK_UWP_TRACE", setting, sizeof setting);
    if (!(length > 0 && length < sizeof setting)) return;
    DeleteFileA("C:\\uwp-host-trace.log");
    DeleteFileA(trace_path());
    DeleteFileA("uwp-host-trace.log");
    if (temp_path()) DeleteFileA(temp_path());
}

/* Formatting without the C runtime: the loader entry below does not run the
   CRT startup, so no part of the trace may depend on it having initialized. */
static char *append_text(char *at, const char *text)
{
    while (*text) *at++ = *text++;
    return at;
}

static char *append_hex(char *at, unsigned value, int digits)
{
    static const char table[] = "0123456789abcdef";
    int shift;
    for (shift = (digits - 1) * 4; shift >= 0; shift -= 4)
        *at++ = table[(value >> shift) & 0xf];
    return at;
}

static int trace_enabled(void)
{
    if (trace_on < 0) {
        char setting[8];
        DWORD length = GetEnvironmentVariableA("DROIDDECK_UWP_TRACE", setting, sizeof setting);
        trace_on = length > 0 && length < sizeof setting;
    }
    return trace_on;
}

static void trace_sink(const char *line)
{
    static const char prefix[] = "uwp-host: ";
    const char *candidates[4];
    HANDLE file = INVALID_HANDLE_VALUE;
    DWORD written;
    int at;

    if (!trace_enabled()) return;

    /* Record on disk before touching the standard handles: a title that is
       activating a class still holds the loader lock, and a stderr write that
       waits on that lock would swallow the only evidence that this code ran at
       all. */
    candidates[0] = "C:\\uwp-host-trace.log";
    candidates[1] = trace_path();
    candidates[2] = "uwp-host-trace.log";
    candidates[3] = temp_path();
    for (at = 0; at < 4 && file == INVALID_HANDLE_VALUE; at++) {
        if (!candidates[at]) continue;
        file = CreateFileA(candidates[at], FILE_APPEND_DATA,
                           FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, NULL);
    }
    if (file != INVALID_HANDLE_VALUE) {
        WriteFile(file, line, (DWORD)strlen(line), &written, NULL);
        WriteFile(file, "\r\n", 2, &written, NULL);
        CloseHandle(file);
    }

    WriteFile(GetStdHandle(STD_ERROR_HANDLE), prefix, sizeof prefix - 1, &written, NULL);
    WriteFile(GetStdHandle(STD_ERROR_HANDLE), line, (DWORD)strlen(line), &written, NULL);
    WriteFile(GetStdHandle(STD_ERROR_HANDLE), "\r\n", 2, &written, NULL);
}

/* A packaged title's stderr is not reachable from a loaded DLL in this runtime,
   so the lifecycle trace is appended to a file with the kernel32 calls only: no
   CRT file table, no stack walk, nothing that takes the loader lock the
   activation already holds. Four candidates, because a title can run against a
   prefix whose drive root refuses the earlier ones: the root of C:, the
   directory this DLL was loaded from, the process working directory, and the
   user's temp directory. */

/* Nothing here may allocate a lock the loader holds: activation already runs
   under it, so a second acquisition from another thread stalls the title. */
static void trace(const char *text)
{
    char line[1200];
    char *at;
    if (!trace_enabled()) return;
    at = append_hex(line, (unsigned)GetCurrentProcessId(), 4);
    *at++ = ':';
    at = append_hex(at, (unsigned)GetCurrentThreadId(), 4);
    *at++ = ' ';
    /* Elapsed milliseconds, so a two-process trace still reads in order. */
    at = append_hex(at, (unsigned)GetTickCount(), 8);
    *at++ = ' ';
    at = append_text(at, text);
    *at = 0;
    trace_sink(line);
}

static void trace_text(const char *tag, const WCHAR *text)
{
    char line[320];
    int i;
    char *at;
    if (trace_on < 0) trace("");
    if (!trace_on) return;
    if (!text) {
        trace(tag);
        return;
    }
    at = append_text(line, tag);
    *at++ = ' ';
    for (i = 0; i < 250 && text[i]; i++) *at++ = (char)text[i];
    *at = 0;
    trace(line);
}

static void trace_guid(const char *tag, const GUID *id)
{
    char text[112];
    char *at;
    if (trace_on < 0) trace("");
    if (!trace_on || !id) return;
    at = append_text(text, tag);
    *at++ = ' ';
    at = append_hex(at, (unsigned)id->Data1, 8);
    *at++ = '-';
    at = append_hex(at, id->Data2, 4);
    *at++ = '-';
    at = append_hex(at, id->Data3, 4);
    *at++ = '-';
    at = append_hex(at, id->Data4[0], 2);
    at = append_hex(at, id->Data4[1], 2);
    *at++ = '-';
    at = append_hex(at, id->Data4[2], 2);
    at = append_hex(at, id->Data4[3], 2);
    at = append_hex(at, id->Data4[4], 2);
    at = append_hex(at, id->Data4[5], 2);
    at = append_hex(at, id->Data4[6], 2);
    at = append_hex(at, id->Data4[7], 2);
    *at = 0;
    trace(text);
}

/* --------------------------------------------------------------- HSTRING API */

typedef HRESULT(WINAPI *create_hstring_fn)(const WCHAR *, UINT32, hstring_t *);
typedef HRESULT(WINAPI *delete_hstring_fn)(hstring_t);
typedef const WCHAR *(WINAPI *hstring_buffer_fn)(hstring_t, UINT32 *);

static create_hstring_fn p_create_hstring;
static delete_hstring_fn p_delete_hstring;
static hstring_buffer_fn p_hstring_buffer;

static void load_hstring_api(void)
{
    HMODULE combase;
    if (p_create_hstring) return;
    combase = GetModuleHandleA("combase.dll");
    if (!combase) combase = LoadLibraryA("combase.dll");
    if (!combase) return;
    p_create_hstring = (create_hstring_fn)(void *)GetProcAddress(combase, "WindowsCreateString");
    p_delete_hstring = (delete_hstring_fn)(void *)GetProcAddress(combase, "WindowsDeleteString");
    p_hstring_buffer = (hstring_buffer_fn)(void *)GetProcAddress(combase, "WindowsGetStringRawBuffer");
}

static hstring_t make_hstring(const WCHAR *text)
{
    hstring_t out = NULL;
    load_hstring_api();
    if (!p_create_hstring || !text) return NULL;
    p_create_hstring(text, (UINT32)wcslen(text), &out);
    return out;
}

static const WCHAR *hstring_text(hstring_t text)
{
    load_hstring_api();
    if (!p_hstring_buffer || !text) return NULL;
    return p_hstring_buffer(text, NULL);
}

/* ------------------------------------------------------------ object helpers */

static HRESULT host_query_interface(host_object *self, const GUID *id, const GUID *own, void **out)
{
    trace_guid("host QI", id);
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &iid_unknown) || IsEqualIID(id, &iid_inspectable) || IsEqualIID(id, own)) {
        *out = self;
        self->refs++;
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG host_add_ref(host_object *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}

static ULONG host_release(host_object *self)
{
    LONG left = InterlockedDecrement(&self->refs);
    if (left == 0 && !self->permanent) free(self);
    return (ULONG)left;
}

/* IInspectable slots 3..5. A class name is what a title sees when it asks, so
   each object carries the name it was registered under. */
static HRESULT host_get_iids(host_object *self, ULONG *count, GUID **ids)
{
    (void)self;
    if (count) *count = 0;
    if (ids) *ids = NULL;
    return S_OK;
}

static HRESULT host_get_runtime_class_name(host_object *self, hstring_t *out)
{
    WCHAR name[96];
    int i;
    if (!out) return E_POINTER;
    for (i = 0; i < 95 && self->name && self->name[i]; i++) name[i] = (WCHAR)self->name[i];
    name[i] = 0;
    *out = make_hstring(name);
    return S_OK;
}

static HRESULT host_get_trust_level(host_object *self, int *out)
{
    (void)self;
    if (out) *out = 2; /* BaseTrust */
    return S_OK;
}

/* A slot the host answers only so the title can keep going: the out parameter
   is zeroed and success says "nothing to report". */
/* A delegate handed in by the title is its own COM object, so its reference
   count is reached through its vtable rather than through ours. */
typedef unsigned long(__stdcall *com_ref_fn)(void *);

static void com_add_ref(void *object)
{
    if (object && *(void ***)object && (*(void ***)object)[1])
        ((com_ref_fn)(*(void ***)object)[1])(object);
}

static void com_release(void *object)
{
    if (object && *(void ***)object && (*(void ***)object)[2])
        ((com_ref_fn)(*(void ***)object)[2])(object);
}

/* A slot that takes no out-parameter and needs to do nothing. Writing through
   the first argument here would land in a handler or a scalar argument, so it
   only reports success. */
static HRESULT host_noop(void *self, void *a, void *b, void *c, void *d)
{
    (void)self;
    (void)a;
    (void)b;
    (void)c;
    (void)d;
    return S_OK;
}

/* A property getter that has nothing to report hands back null. */
static HRESULT host_null_out(void *self, void *out)
{
    (void)self;
    if (out) *(void **)out = NULL;
    return S_OK;
}

/* ------------------------------------------------------------------- window */

static HWND host_window;
static int host_quit;
static void run_dispatched(void);

static LRESULT CALLBACK host_wndproc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam)
{
    switch (message) {
    case WM_NULL:
        run_dispatched();
        return 0;
    case WM_CLOSE:
        DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        host_quit = 1;
        PostQuitMessage(0);
        return 0;
    case WM_ERASEBKGND:
        /* The title's swapchain owns the client area; a background fill here
           would paint over frames the compositor already has. */
        return 1;
    default:
        break;
    }
    return DefWindowProcW(hwnd, message, wparam, lparam);
}

/* The app-model window is a full-display popup: on the console it covers the
   desktop shell, which is what a UWP view gets on Windows. */
static HWND create_host_window(void)
{
    WNDCLASSEXW wc;
    int width = GetSystemMetrics(SM_CXSCREEN);
    int height = GetSystemMetrics(SM_CYSCREEN);

    memset(&wc, 0, sizeof(wc));
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = host_wndproc;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
    wc.hbrBackground = NULL;
    wc.lpszClassName = L"DroidDeckAppModelWindow";
    if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        trace("RegisterClassEx failed");
        return NULL;
    }
    if (width <= 0 || height <= 0) {
        width = 1280;
        height = 720;
    }
    host_window = CreateWindowExW(WS_EX_TOPMOST, wc.lpszClassName, L"DroidDeck", WS_POPUP | WS_VISIBLE,
                                  0, 0, width, height, NULL, NULL, wc.hInstance, NULL);
    if (!host_window) {
        trace("CreateWindowEx failed");
        return NULL;
    }
    ShowWindow(host_window, SW_SHOW);
    UpdateWindow(host_window);
    SetForegroundWindow(host_window);
    SetActiveWindow(host_window);
    SetFocus(host_window);
    return host_window;
}

/* Windows delivers the first activation on the view thread once Load has
   returned, before the title's Run is entered. A title that subscribes during
   Initialize (the usual place) is already listening; a title that polls its
   view from inside Run sees the same raised state Windows would have given it. */
static void raise_activation(void);
static int activation_pending;

static void deliver_activation(void)
{
    if (!activation_pending) return;
    activation_pending = 0;
    raise_activation();
}

/* Work handed to CoreDispatcher::RunAsync. Windows never runs it inline: it
   runs on the view thread the next time that thread processes events, and a
   title that dispatches from a worker waits for the handler to have run there.
   Forza Horizon 4's game thread dispatches to its view and sleeps until the
   handler has set its flags. */
#define HOST_RO_E_CLOSED ((HRESULT)0x80000013L)

typedef struct dispatched_work {
    struct dispatched_work *next;
    void *callback;
    void *action;
    int is_idle;
} dispatched_work;

static SRWLOCK dispatch_lock = SRWLOCK_INIT;
static dispatched_work *dispatch_head;
static dispatched_work *dispatch_tail;
static int dispatch_closed;
static DWORD view_thread;

void *svc_action_started(void);
void svc_action_finish(void *action, HRESULT error);

/* IIdleDispatchedHandlerArgs {98bb6a24-dc1c-43cb-b4ed-d1c0eb2391f3}: slot 6 is get_IsDispatcherIdle. */
static const GUID iid_idle_args = {0x98bb6a24, 0xdc1c, 0x43cb,
                                   {0xb4, 0xed, 0xd1, 0xc0, 0xeb, 0x23, 0x91, 0xf3}};

static HRESULT idle_args_qi(host_object *self, const GUID *id, void **out)
{
    return host_query_interface(self, id, &iid_idle_args, out);
}

static HRESULT idle_args_IsDispatcherIdle(host_object *self, unsigned char *out)
{
    int idle;
    (void)self;
    if (!out) return E_POINTER;
    AcquireSRWLockExclusive(&dispatch_lock);
    idle = (dispatch_head == NULL);
    ReleaseSRWLockExclusive(&dispatch_lock);
    if (idle && HIWORD(GetQueueStatus(QS_ALLINPUT)) != 0) idle = 0;
    *out = (unsigned char)(idle ? 1 : 0);
    return S_OK;
}

static const void *idle_args_vtable[] = {
    (const void *)idle_args_qi, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)idle_args_IsDispatcherIdle,
};

static host_object idle_args_singleton = {
    idle_args_vtable, 1000, "Windows.UI.Core.IdleDispatchedHandlerArgs", 1, NULL,
};

/* DispatchedHandler::Invoke(self) and IdleDispatchedHandler::Invoke(self, args) are slot 3. */
typedef HRESULT(__stdcall *dispatched_invoke_fn)(void *);
typedef HRESULT(__stdcall *idle_dispatched_invoke_fn)(void *, void *);

static void run_dispatched(void)
{
    if (GetCurrentThreadId() != view_thread) return;
    for (;;) {
        dispatched_work *work;
        HRESULT hr;
        AcquireSRWLockExclusive(&dispatch_lock);
        work = dispatch_head;
        if (work) {
            dispatch_head = work->next;
            if (!dispatch_head) dispatch_tail = NULL;
        }
        ReleaseSRWLockExclusive(&dispatch_lock);
        if (!work) return;
        if (work->is_idle) {
            hr = ((idle_dispatched_invoke_fn)(*(void ***)work->callback)[3])(work->callback,
                                                                             &idle_args_singleton);
        } else {
            hr = ((dispatched_invoke_fn)(*(void ***)work->callback)[3])(work->callback);
        }
        if (FAILED(hr)) {
            char line[64];
            snprintf(line, sizeof line, "dispatched handler failed 0x%08lx", (unsigned long)hr);
            trace(line);
        }
        svc_action_finish(work->action, hr);
        com_release(work->action);
        com_release(work->callback);
        free(work);
    }
}

static void close_dispatcher_queue(void)
{
    dispatched_work *work, *next;
    AcquireSRWLockExclusive(&dispatch_lock);
    dispatch_closed = 1;
    work = dispatch_head;
    dispatch_head = NULL;
    dispatch_tail = NULL;
    ReleaseSRWLockExclusive(&dispatch_lock);
    while (work) {
        next = work->next;
        svc_action_finish(work->action, HOST_RO_E_CLOSED);
        com_release(work->action);
        com_release(work->callback);
        free(work);
        work = next;
    }
}

static void pump_messages(int wait, int single)
{
    MSG msg;
    int pumped = 0;

    deliver_activation();
    run_dispatched();

    while (!host_quit) {
        if (wait && !pumped) {
            /* Nothing posts to this queue unless the device is touched, so the
               wait is bounded to keep the title's frame loop turning. */
            MsgWaitForMultipleObjects(0, NULL, FALSE, 16, QS_ALLINPUT);
            run_dispatched();
            if (!PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) return;
        } else if (!PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) {
            return;
        }
        if (msg.message == WM_QUIT) {
            host_quit = 1;
            return;
        }
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
        pumped++;
        if (single) return;
    }
}

static void pump_once(int wait)
{
    pump_messages(wait, 0);
}

/* ------------------------------------------------------------- CoreWindow */

typedef struct {
    host_object base;
} core_window;

static core_window window;
static int window_size_w = 1280;
static int window_size_h = 720;

/* A class registered here can also be reached through its static interface, so
   a title that asks `CoreWindow::GetForCurrentThread` gets the window the host
   made instead of E_NOINTERFACE. */
typedef struct {
    host_object base;
} core_window_static_obj;

static core_window_static_obj window_static;

static HRESULT cws_query_interface(core_window_static_obj *self, const GUID *id, void **out)
{
    return host_query_interface(&self->base, id, &iid_core_window_static, out);
}

static HRESULT cws_GetForCurrentThread(core_window_static_obj *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &window;
    window.base.refs++;
    return S_OK;
}

static const void *cws_vtable[] = {
    (const void *)cws_query_interface, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)cws_GetForCurrentThread, /* ICoreWindowStatic */
};

/* Windows.UI.Core.ICoreWindowInterop: how a title, and the dxgi under it,
   reaches the app-model window's handle. `CreateSwapChainForCoreWindow` asks
   the core window for this interface and binds the swapchain to the handle it
   returns, so a view that answers CoreWindow but not the interop gets a device
   and never a frame. The vtable is IUnknown's three slots followed by the
   interface's own two: it is not IInspectable, so there is no GetIids,
   GetRuntimeClassName or GetTrustLevel in front of get_WindowHandle. */
typedef struct {
    const void **vtbl;
    LONG refs;
} core_window_interop;

static core_window_interop window_interop;

static HRESULT cw_query_interface(core_window *self, const GUID *id, void **out);

/* The interop is a face of the core window, so it answers with the window's
   identity. */
static HRESULT cwi_query_interface(core_window_interop *self, const GUID *id, void **out)
{
    (void)self;
    return cw_query_interface(&window, id, out);
}

static ULONG cwi_add_ref(core_window_interop *self)
{
    return (ULONG)InterlockedIncrement(&self->refs);
}

static ULONG cwi_release(core_window_interop *self)
{
    return (ULONG)InterlockedDecrement(&self->refs);
}

static HRESULT cwi_WindowHandle(core_window_interop *self, HWND *out)
{
    (void)self;
    trace("window interop handle");
    if (!out) return E_POINTER;
    *out = host_window;
    return S_OK;
}

/* The host already dispatches the window's messages, so nothing needs marking
   as handled; the setter exists because the interface has it. */
static HRESULT cwi_SetMessageHandled(core_window_interop *self, unsigned char handled)
{
    (void)self;
    (void)handled;
    return S_OK;
}

static const void *cwi_vtable[] = {
    (const void *)cwi_query_interface, (const void *)cwi_add_ref, (const void *)cwi_release,
    (const void *)cwi_WindowHandle,      /* get_WindowHandle */
    (const void *)cwi_SetMessageHandled, /* put_MessageHandled */
};

static HRESULT cw_query_interface(core_window *self, const GUID *id, void **out)
{
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &iid_core_window_static)) {
        *out = &window_static;
        window_static.base.refs++;
        return S_OK;
    }
    if (IsEqualIID(id, &iid_core_window_interop)) {
        *out = &window_interop;
        InterlockedIncrement(&window_interop.refs);
        return S_OK;
    }
    return host_query_interface(&self->base, id, &iid_core_window, out);
}

static HRESULT cw_Bounds(core_window *self, float *out)
{
    (void)self;
    trace("window Bounds");
    if (!out) return E_POINTER;
    out[0] = 0.0f;
    out[1] = 0.0f;
    out[2] = (float)window_size_w;
    out[3] = (float)window_size_h;
    return S_OK;
}

static HRESULT cw_Visible(core_window *self, unsigned char *out)
{
    (void)self;
    trace("window Visible");
    if (out) *out = 1;
    return S_OK;
}

/* CoreWindowFlowDirection::LeftToRight. */
static HRESULT cw_FlowDirection(core_window *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

static HRESULT cw_IsInputEnabled(core_window *self, unsigned char *out)
{
    (void)self;
    trace("window IsInputEnabled");
    if (!out) return E_POINTER;
    *out = 1;
    return S_OK;
}

static HRESULT cw_PointerPosition(core_window *self, float *out)
{
    (void)self;
    trace("window PointerPosition");
    if (!out) return E_POINTER;
    out[0] = 0.0f;
    out[1] = 0.0f;
    return S_OK;
}

static HRESULT cw_Activate(core_window *self)
{
    (void)self;
    trace("window Activate");
    if (host_window) SetForegroundWindow(host_window);
    return S_OK;
}

static HRESULT cw_Close(core_window *self)
{
    (void)self;
    trace("window Close");
    if (host_window) {
        if (!PostMessageW(host_window, WM_CLOSE, 0, 0)) host_quit = 1;
    } else {
        host_quit = 1;
    }
    return S_OK;
}

/* Windows answers these from the input queue of the window the view draws in,
   so a title that waits for a key sees the keys the handheld actually sends
   instead of a keyboard that is permanently idle. */
static HRESULT cw_GetKeyState(core_window *self, int key, unsigned char *out)
{
    SHORT state;
    (void)self;
    state = GetKeyState(key);
    if (out) *out = (unsigned char)((state & 0x8000) != 0);
    return S_OK;
}

static HRESULT cw_GetAsyncKeyState(core_window *self, int key, unsigned char *out)
{
    (void)self;
    if (out) *out = (unsigned char)((GetAsyncKeyState(key) & 0x8000) != 0);
    return S_OK;
}

typedef struct {
    host_object base;
} core_dispatcher;

static core_dispatcher dispatcher;

static HRESULT cd_query_interface(core_dispatcher *self, const GUID *id, void **out)
{
    return host_query_interface(&self->base, id, &iid_core_dispatcher, out);
}

static HRESULT cd_HasThreadAccess(core_dispatcher *self, unsigned char *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = GetCurrentThreadId() == view_thread;
    return S_OK;
}

static HRESULT cd_ProcessEvents(core_dispatcher *self, int option)
{
    (void)self;
    switch (option) {
    case 0: /* CoreProcessEventsOption::ProcessOneAndAllPending */
        pump_messages(1, 0);
        break;
    case 1: /* CoreProcessEventsOption::ProcessOneIfPresent */
        pump_messages(0, 1);
        break;
    case 2: /* CoreProcessEventsOption::ProcessUntilQuit */
        while (!host_quit) pump_messages(1, 0);
        break;
    case 3: /* CoreProcessEventsOption::ProcessAllIfPresent */
    default:
        pump_messages(0, 0);
        break;
    }
    return S_OK;
}

static HRESULT cd_enqueue(void *callback, int is_idle, void **operation)
{
    dispatched_work *work;
    int closed;

    if (operation) *operation = NULL;
    if (!callback) return E_INVALIDARG;
    work = (dispatched_work *)calloc(1, sizeof *work);
    if (!work) return E_OUTOFMEMORY;
    work->action = svc_action_started();
    if (!work->action) {
        free(work);
        return E_OUTOFMEMORY;
    }
    work->is_idle = is_idle;
    com_add_ref(callback);
    work->callback = callback;

    AcquireSRWLockExclusive(&dispatch_lock);
    closed = dispatch_closed;
    if (!closed) {
        if (dispatch_tail) dispatch_tail->next = work;
        else dispatch_head = work;
        dispatch_tail = work;
    }
    ReleaseSRWLockExclusive(&dispatch_lock);

    if (closed) {
        svc_action_finish(work->action, HOST_RO_E_CLOSED);
        com_release(work->action);
        com_release(work->callback);
        free(work);
        return HOST_RO_E_CLOSED;
    }

    if (operation) {
        com_add_ref(work->action);
        *operation = work->action;
    }
    if (host_window) PostMessageW(host_window, WM_NULL, 0, 0);
    return S_OK;
}

static HRESULT cd_RunAsync(core_dispatcher *self, int priority, void *callback, void **operation)
{
    (void)self;
    (void)priority;
    return cd_enqueue(callback, 0, operation);
}

static HRESULT cd_RunIdleAsync(core_dispatcher *self, void *callback, void **operation)
{
    (void)self;
    return cd_enqueue(callback, 1, operation);
}

/* Order follows Windows.UI.Core.ICoreDispatcher: HasThreadAccess, ProcessEvents,
   RunAsync, RunIdleAsync. */
static const void *cd_vtable[] = {
    (const void *)cd_query_interface, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)cd_HasThreadAccess,   /* HasThreadAccess */
    (const void *)cd_ProcessEvents,     /* ProcessEvents */
    (const void *)cd_RunAsync,          /* RunAsync */
    (const void *)cd_RunIdleAsync,      /* RunIdleAsync */
};

static HRESULT cw_Dispatcher(core_window *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &dispatcher;
    dispatcher.base.refs++;
    return S_OK;
}

/* Every CoreWindow event keeps the delegate it was handed and hands back a live
   token, so a title that subscribes and then drops its own reference still has
   a valid subscription. */
enum {
    CW_EV_ACTIVATED,
    CW_EV_AUTOMATION_PROVIDER_REQUESTED,
    CW_EV_CHARACTER_RECEIVED,
    CW_EV_CLOSED,
    CW_EV_INPUT_ENABLED,
    CW_EV_KEY_DOWN,
    CW_EV_KEY_UP,
    CW_EV_POINTER_CAPTURE_LOST,
    CW_EV_POINTER_ENTERED,
    CW_EV_POINTER_EXITED,
    CW_EV_POINTER_MOVED,
    CW_EV_POINTER_PRESSED,
    CW_EV_POINTER_RELEASED,
    CW_EV_TOUCH_HIT_TESTING,
    CW_EV_POINTER_WHEEL_CHANGED,
    CW_EV_SIZE_CHANGED,
    CW_EV_VISIBILITY_CHANGED,
    CW_EV_COUNT
};

static void *cw_event_handler[CW_EV_COUNT];
static long long cw_event_token[CW_EV_COUNT];

/* Named so a run says which events the title wanted, instead of only how many
   handlers it handed over. */
static const char *cw_event_name[CW_EV_COUNT] = {
    "Activated", "AutomationProviderRequested", "CharacterReceived", "Closed",
    "InputEnabled", "KeyDown", "KeyUp", "PointerCaptureLost", "PointerEntered",
    "PointerExited", "PointerMoved", "PointerPressed", "PointerReleased",
    "TouchHitTesting", "PointerWheelChanged", "SizeChanged", "VisibilityChanged",
};

static HRESULT cw_event_add(core_window *self, int slot, void *handler, long long *token)
{
    (void)self;
    char line[64];
    if (!token) return E_POINTER;
    snprintf(line, sizeof line, "window event +%s", cw_event_name[slot]);
    trace(line);
    com_release(cw_event_handler[slot]);
    cw_event_handler[slot] = handler;
    com_add_ref(handler);
    cw_event_token[slot]++;
    *token = cw_event_token[slot];
    return S_OK;
}

static HRESULT cw_event_remove(core_window *self, int slot, long long token)
{
    (void)self;
    if (token != cw_event_token[slot]) return S_OK;
    com_release(cw_event_handler[slot]);
    cw_event_handler[slot] = NULL;
    return S_OK;
}

#define CW_EVENT(name, index)                                                   \
    static HRESULT cw_##name##_add(core_window *self, void *handler,            \
                                   long long *token)                            \
    {                                                                           \
        return cw_event_add(self, index, handler, token);                       \
    }                                                                           \
    static HRESULT cw_##name##_remove(core_window *self, long long token)       \
    {                                                                           \
        return cw_event_remove(self, index, token);                             \
    }

CW_EVENT(activated, CW_EV_ACTIVATED)
CW_EVENT(automation_provider_requested, CW_EV_AUTOMATION_PROVIDER_REQUESTED)
CW_EVENT(character_received, CW_EV_CHARACTER_RECEIVED)
CW_EVENT(closed, CW_EV_CLOSED)
CW_EVENT(input_enabled, CW_EV_INPUT_ENABLED)
CW_EVENT(key_down, CW_EV_KEY_DOWN)
CW_EVENT(key_up, CW_EV_KEY_UP)
CW_EVENT(pointer_capture_lost, CW_EV_POINTER_CAPTURE_LOST)
CW_EVENT(pointer_entered, CW_EV_POINTER_ENTERED)
CW_EVENT(pointer_exited, CW_EV_POINTER_EXITED)
CW_EVENT(pointer_moved, CW_EV_POINTER_MOVED)
CW_EVENT(pointer_pressed, CW_EV_POINTER_PRESSED)
CW_EVENT(pointer_released, CW_EV_POINTER_RELEASED)
CW_EVENT(touch_hit_testing, CW_EV_TOUCH_HIT_TESTING)
CW_EVENT(pointer_wheel_changed, CW_EV_POINTER_WHEEL_CHANGED)
CW_EVENT(size_changed, CW_EV_SIZE_CHANGED)
CW_EVENT(visibility_changed, CW_EV_VISIBILITY_CHANGED)

/* Order follows Windows.UI.Core.ICoreWindow, so a title calling any slot by
   index reaches the right behaviour instead of a neighbouring stub. */
static const void *cw_vtable[] = {
    (const void *)cw_query_interface, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)host_null_out,         /* AutomationHostProvider */
    (const void *)cw_Bounds,             /* Bounds */
    (const void *)host_null_out,         /* CustomProperties */
    (const void *)cw_Dispatcher,         /* Dispatcher */
    (const void *)cw_FlowDirection,      /* FlowDirection */
    (const void *)host_noop,             /* SetFlowDirection */
    (const void *)cw_IsInputEnabled,     /* IsInputEnabled */
    (const void *)host_noop,             /* SetIsInputEnabled */
    (const void *)host_null_out,         /* PointerCursor */
    (const void *)host_noop,             /* SetPointerCursor */
    (const void *)cw_PointerPosition,    /* PointerPosition */
    (const void *)cw_Visible,            /* Visible */
    (const void *)cw_Activate,           /* Activate */
    (const void *)cw_Close,              /* Close */
    (const void *)cw_GetAsyncKeyState,   /* GetAsyncKeyState */
    (const void *)cw_GetKeyState,        /* GetKeyState */
    (const void *)host_noop,             /* ReleasePointerCapture */
    (const void *)host_noop,             /* SetPointerCapture */
    (const void *)cw_activated_add, (const void *)cw_activated_remove,
    (const void *)cw_automation_provider_requested_add,
    (const void *)cw_automation_provider_requested_remove,
    (const void *)cw_character_received_add, (const void *)cw_character_received_remove,
    (const void *)cw_closed_add, (const void *)cw_closed_remove,
    (const void *)cw_input_enabled_add, (const void *)cw_input_enabled_remove,
    (const void *)cw_key_down_add, (const void *)cw_key_down_remove,
    (const void *)cw_key_up_add, (const void *)cw_key_up_remove,
    (const void *)cw_pointer_capture_lost_add, (const void *)cw_pointer_capture_lost_remove,
    (const void *)cw_pointer_entered_add, (const void *)cw_pointer_entered_remove,
    (const void *)cw_pointer_exited_add, (const void *)cw_pointer_exited_remove,
    (const void *)cw_pointer_moved_add, (const void *)cw_pointer_moved_remove,
    (const void *)cw_pointer_pressed_add, (const void *)cw_pointer_pressed_remove,
    (const void *)cw_pointer_released_add, (const void *)cw_pointer_released_remove,
    (const void *)cw_touch_hit_testing_add, (const void *)cw_touch_hit_testing_remove,
    (const void *)cw_pointer_wheel_changed_add, (const void *)cw_pointer_wheel_changed_remove,
    (const void *)cw_size_changed_add, (const void *)cw_size_changed_remove,
    (const void *)cw_visibility_changed_add, (const void *)cw_visibility_changed_remove,
};

/* --------------------------------------------------- CoreApplicationView */

typedef struct {
    host_object base;
} core_application_view;

static core_application_view app_view;

static HRESULT cav_query_interface(core_application_view *self, const GUID *id, void **out)
{
    trace_guid("view QI", id);
    return host_query_interface(&self->base, id, &iid_core_application_view, out);
}

static HRESULT cav_CoreWindow(core_application_view *self, void **out)
{
    (void)self;
    trace("view CoreWindow");
    if (!out) return E_POINTER;
    *out = &window;
    window.base.refs++;
    return S_OK;
}

/* ICoreApplicationView::Activated. The title hands over a delegate and expects
   a live token back; it drops its own reference straight after the call, so the
   subscription has to hold one. */
#define MAX_VIEW_ACTIVATED_SUBS 16

typedef struct {
    long long token;
    void *handler;
} view_activated_sub;

static SRWLOCK view_activated_lock = SRWLOCK_INIT;
static view_activated_sub view_activated_subs[MAX_VIEW_ACTIVATED_SUBS];
static long long view_activated_next_token;

static HRESULT cav_activated_add(core_application_view *self, void *handler, long long *token)
{
    size_t i;
    (void)self;
    trace("view add Activated");
    if (!token) return E_POINTER;
    *token = 0;
    if (!handler) return E_INVALIDARG;
    AcquireSRWLockExclusive(&view_activated_lock);
    for (i = 0; i < MAX_VIEW_ACTIVATED_SUBS; i++) {
        if (!view_activated_subs[i].handler) {
            com_add_ref(handler);
            view_activated_subs[i].handler = handler;
            view_activated_subs[i].token = ++view_activated_next_token;
            *token = view_activated_subs[i].token;
            ReleaseSRWLockExclusive(&view_activated_lock);
            return S_OK;
        }
    }
    ReleaseSRWLockExclusive(&view_activated_lock);
    return E_OUTOFMEMORY;
}

static HRESULT cav_activated_remove(core_application_view *self, long long token)
{
    size_t i;
    void *to_release = NULL;
    (void)self;
    trace("view remove Activated");
    AcquireSRWLockExclusive(&view_activated_lock);
    for (i = 0; i < MAX_VIEW_ACTIVATED_SUBS; i++) {
        if (view_activated_subs[i].handler && view_activated_subs[i].token == token) {
            to_release = view_activated_subs[i].handler;
            view_activated_subs[i].handler = NULL;
            view_activated_subs[i].token = 0;
            break;
        }
    }
    ReleaseSRWLockExclusive(&view_activated_lock);
    if (to_release) com_release(to_release);
    return S_OK;
}

static HRESULT cav_IsMain(core_application_view *self, unsigned char *out)
{
    (void)self;
    trace("view IsMain");
    if (!out) return E_POINTER;
    *out = 1;
    return S_OK;
}

/* A view hosted inside another application's frame reports true; this view is
   the application's own. */
static HRESULT cav_IsHosted(core_application_view *self, unsigned char *out)
{
    (void)self;
    trace("view IsHosted");
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

static const void *cav_vtable[] = {
    (const void *)cav_query_interface, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)cav_CoreWindow,         /* CoreWindow */
    (const void *)cav_activated_add,      /* Activated */
    (const void *)cav_activated_remove,   /* RemoveActivated */
    (const void *)cav_IsMain,             /* IsMain */
    (const void *)cav_IsHosted,           /* IsHosted */
};

/* ----------------------------------------------- CoreWindow event arguments */

/* Windows.UI.Core.ICoreWindowEventArgs */
static const GUID iid_core_window_args = {0x272b1ef3, 0xc633, 0x4da5,
                                          {0xa2, 0x6c, 0xc6, 0xd0, 0xf5, 0x6b, 0x29, 0xda}};
/* Windows.UI.Core.IWindowActivatedEventArgs */
static const GUID iid_window_activated_args = {0x179d65e7, 0x4658, 0x4cb6,
                                               {0xaa, 0x13, 0x41, 0xd0, 0x94, 0xea, 0x25, 0x5e}};
/* Windows.UI.Core.IWindowSizeChangedEventArgs */
static const GUID iid_window_size_args = {0x5a200ec7, 0x0426, 0x47dc,
                                          {0xb8, 0x6c, 0x6f, 0x47, 0x59, 0x15, 0xe4, 0x51}};
/* Windows.UI.Core.IVisibilityChangedEventArgs */
static const GUID iid_visibility_args = {0xbf9918ea, 0xd801, 0x4564,
                                         {0xa4, 0x95, 0xb1, 0xe8, 0x4f, 0x8a, 0xd0, 0x85}};

/* What a handler reads out of an event's arguments. Windows raises Activated
   when the view becomes the active one, SizeChanged with the client area the
   title is given, and VisibilityChanged when the window is shown; a title that
   waits for them before drawing never draws if none of them arrive. */
typedef struct {
    int activation_state; /* Windows.UI.Core.CoreWindowActivationState */
    float width;
    float height;
    unsigned char visible;
    unsigned char handled;
} window_event_state;

typedef struct {
    host_object base;    /* the event's own interface */
    host_object handled; /* the same event seen as ICoreWindowEventArgs */
    window_event_state state;
    const GUID *iid;
} window_event_args;

static window_event_args *args_of_handled(host_object *self)
{
    return (window_event_args *)((char *)self - offsetof(window_event_args, handled));
}

static HRESULT wea_query_interface(window_event_args *self, const GUID *id, void **out)
{
    trace_guid("args QI", id);
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &iid_unknown) || IsEqualIID(id, &iid_inspectable) ||
        (self->iid && IsEqualIID(id, self->iid))) {
        *out = &self->base;
        self->base.refs++;
        return S_OK;
    }
    if (IsEqualIID(id, &iid_core_window_args)) {
        *out = &self->handled;
        self->handled.refs++;
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT wea_handled_query_interface(host_object *self, const GUID *id, void **out)
{
    return wea_query_interface(args_of_handled(self), id, out);
}

static HRESULT wea_get_activation_state(void *self, int *out)
{
    window_event_args *args = (window_event_args *)self;
    if (!out) return E_POINTER;
    *out = args->state.activation_state;
    return S_OK;
}

/* Windows.Foundation.Size is two floats handed back through the out pointer. */
static HRESULT wea_get_size(void *self, float *out)
{
    window_event_args *args = (window_event_args *)self;
    if (!out) return E_POINTER;
    out[0] = args->state.width;
    out[1] = args->state.height;
    return S_OK;
}

static HRESULT wea_get_visible(void *self, unsigned char *out)
{
    window_event_args *args = (window_event_args *)self;
    if (!out) return E_POINTER;
    *out = args->state.visible;
    return S_OK;
}

static HRESULT wea_get_handled(host_object *self, unsigned char *out)
{
    window_event_args *args = args_of_handled(self);
    if (!out) return E_POINTER;
    *out = args->state.handled;
    return S_OK;
}

static HRESULT wea_put_handled(host_object *self, unsigned char value)
{
    args_of_handled(self)->state.handled = value;
    return S_OK;
}

/* WinRT interfaces do not inherit each other's vtable slots; ICoreWindowEventArgs
   is reached via QueryInterface (handled_face), so each event interface places
   its own property at slot 6 immediately after IInspectable. */
static const void *activated_args_vtable[] = {
    (const void *)wea_query_interface, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)wea_get_activation_state, /* WindowActivationState */
};

static const void *size_args_vtable[] = {
    (const void *)wea_query_interface, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)wea_get_size, /* Size */
};

static const void *visibility_args_vtable[] = {
    (const void *)wea_query_interface, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)wea_get_visible, /* Visible */
};

static const void *handled_args_vtable[] = {
    (const void *)wea_handled_query_interface, (const void *)host_add_ref,
    (const void *)host_release, (const void *)host_get_iids,
    (const void *)host_get_runtime_class_name, (const void *)host_get_trust_level,
    (const void *)wea_get_handled, /* Handled */
    (const void *)wea_put_handled, /* SetHandled */
};

/* One static set per event: the arguments of a view activation are the same
   object every time, exactly as Windows hands the same args instance to every
   handler of the event. */
static window_event_args activated_args = {
    {activated_args_vtable, 1, "Windows.UI.Core.WindowActivatedEventArgs", 1, NULL},
    {handled_args_vtable, 1, "Windows.UI.Core.WindowActivatedEventArgs", 1, NULL},
    {0 /* CoreWindowActivationState::CodeActivated */, 0, 0, 0, 0},
    &iid_window_activated_args,
};

static window_event_args size_args = {
    {size_args_vtable, 1, "Windows.UI.Core.WindowSizeChangedEventArgs", 1, NULL},
    {handled_args_vtable, 1, "Windows.UI.Core.WindowSizeChangedEventArgs", 1, NULL},
    {0, 0, 0, 0, 0},
    &iid_window_size_args,
};

static window_event_args visibility_args = {
    {visibility_args_vtable, 1, "Windows.UI.Core.VisibilityChangedEventArgs", 1, NULL},
    {handled_args_vtable, 1, "Windows.UI.Core.VisibilityChangedEventArgs", 1, NULL},
    {0, 0, 0, 1, 0},
    &iid_visibility_args,
};

/* CoreApplicationView::Activated hands a title IActivatedEventArgs, and for a
   launch that object also offers ILaunchActivatedEventArgs. A handler that
   casts to it and finds nothing treats the activation as one it cannot use,
   which leaves the title's own "the view is up" flag clear. */
static const GUID iid_activated_args = {0xcf651713, 0xcd08, 0x4fd8,
                                        {0xb6, 0x97, 0xa2, 0x81, 0xb6, 0x54, 0x4e, 0x2e}};
static const GUID iid_launch_activated_args = {0xfbc93e26, 0xa14a, 0x4b4f,
                                               {0x82, 0xb0, 0x33, 0xbe, 0xd9, 0x20, 0xaf, 0x52}};

static const GUID iid_launch_activated_args2 = {0x0fd37ebc, 0x9dc9, 0x46b5,
                                                {0x9a, 0xce, 0xbd, 0x95, 0xd4, 0x56, 0x53, 0x45}};
static const GUID iid_prelaunch_activated_args = {0x0c44717b, 0x19f7, 0x48d6,
                                                  {0xb0, 0x46, 0xcf, 0x22, 0x82, 0x6e, 0xaa, 0x74}};
static const GUID iid_view_activated_args = {0x930cef4b, 0xb829, 0x40fc,
                                             {0x88, 0xf4, 0x85, 0x13, 0xe8, 0xa6, 0x47, 0x38}};
static const GUID iid_activated_args_with_user = {0x1cf09b9e, 0x9962, 0x4936,
                                                  {0x80, 0xff, 0xaf, 0xc8, 0xe8, 0xae, 0x5c, 0x8c}};

/* Each interface LaunchActivatedEventArgs offers has its own pointer, as on
   Windows: the base is IActivatedEventArgs and the object's identity. */
enum { LA_LAUNCH, LA_LAUNCH2, LA_PRELAUNCH, LA_VIEW, LA_USER, LA_FACES };

typedef struct {
    host_object base;
    const void **face[LA_FACES];
} launch_activated_args;

static const void *launch_args_vtable[];
static const void *launch_face_vtables[LA_FACES][8];

static launch_activated_args launch_args = {
    {launch_args_vtable, 1000, "Windows.ApplicationModel.Activation.LaunchActivatedEventArgs", 1,
     NULL},
    {launch_face_vtables[LA_LAUNCH], launch_face_vtables[LA_LAUNCH2],
     launch_face_vtables[LA_PRELAUNCH], launch_face_vtables[LA_VIEW], launch_face_vtables[LA_USER]},
};

static HRESULT la_query_interface(launch_activated_args *self, const GUID *id, void **out)
{
    static const GUID *const faces[LA_FACES] = {
        &iid_launch_activated_args, &iid_launch_activated_args2, &iid_prelaunch_activated_args,
        &iid_view_activated_args, &iid_activated_args_with_user,
    };
    int i;
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    if (IsEqualIID(id, &iid_unknown) || IsEqualIID(id, &iid_inspectable) ||
        IsEqualIID(id, &iid_activated_args))
        *out = &launch_args.base;
    for (i = 0; i < LA_FACES && !*out; i++)
        if (IsEqualIID(id, faces[i])) *out = &launch_args.face[i];
    if (!*out) return E_NOINTERFACE;
    host_add_ref(&launch_args.base);
    return S_OK;
}

static HRESULT la_face_query_interface(void *face, const GUID *id, void **out)
{
    (void)face;
    return la_query_interface(&launch_args, id, out);
}

static ULONG la_face_add_ref(void *face)
{
    (void)face;
    return host_add_ref(&launch_args.base);
}

static ULONG la_face_release(void *face)
{
    (void)face;
    return host_release(&launch_args.base);
}

static HRESULT la_face_get_iids(void *face, ULONG *count, GUID **ids)
{
    (void)face;
    return host_get_iids(&launch_args.base, count, ids);
}

static HRESULT la_face_class_name(void *face, hstring_t *out)
{
    (void)face;
    return host_get_runtime_class_name(&launch_args.base, out);
}

static HRESULT la_face_trust_level(void *face, int *out)
{
    (void)face;
    return host_get_trust_level(&launch_args.base, out);
}

/* PrelaunchActivated: the player started this launch. */
static HRESULT la_PrelaunchActivated(void *self, unsigned char *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

/* CurrentlyShownApplicationViewId: the main view, as ApplicationView.Id reports it. */
static HRESULT la_CurrentlyShownApplicationViewId(void *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 1;
    return S_OK;
}

/* TileActivatedInfo and User: a shell launch has no tile, and the host has no
   Windows.System.User object to hand over. */
static HRESULT la_no_object(void *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    return S_OK;
}

/* ActivationKind::Launch. */
static HRESULT la_Kind(void *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

/* ApplicationExecutionState::NotRunning. */
static HRESULT la_PreviousExecutionState(void *self, int *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = 0;
    return S_OK;
}

/* A launch through the shell has no splash to hand over. */
static HRESULT la_SplashScreen(void *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = NULL;
    return S_OK;
}

static HRESULT la_Arguments(void *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = make_hstring(L"");
    return S_OK;
}

static HRESULT la_TileId(void *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = make_hstring(L"");
    return S_OK;
}

static const void *launch_args_vtable[] = {
    (const void *)la_query_interface, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)la_Kind, (const void *)la_PreviousExecutionState, (const void *)la_SplashScreen,
};

#define LA_FACE(...) \
    {(const void *)la_face_query_interface, (const void *)la_face_add_ref, \
     (const void *)la_face_release, (const void *)la_face_get_iids, \
     (const void *)la_face_class_name, (const void *)la_face_trust_level, __VA_ARGS__}

static const void *launch_face_vtables[LA_FACES][8] = {
    [LA_LAUNCH] = LA_FACE((const void *)la_Arguments, (const void *)la_TileId),
    [LA_LAUNCH2] = LA_FACE((const void *)la_no_object),
    [LA_PRELAUNCH] = LA_FACE((const void *)la_PrelaunchActivated),
    [LA_VIEW] = LA_FACE((const void *)la_CurrentlyShownApplicationViewId),
    [LA_USER] = LA_FACE((const void *)la_no_object),
};

/* A WinRT delegate derives from IUnknown, not IInspectable: Invoke is slot 3. */
typedef HRESULT(__stdcall *delegate_invoke_fn)(void *, void *, void *);

static void fire_delegate(void *handler, void *sender, void *args)
{
    const void **vtbl;
    if (!handler) return;
    if (IsBadReadPtr(handler, sizeof(void *))) return;
    vtbl = *(const void ***)handler;
    if (!vtbl || IsBadReadPtr(vtbl, 4 * sizeof(void *)) || !vtbl[3]) return;
    trace("fire event");
    ((delegate_invoke_fn)vtbl[3])(handler, sender, args);
}

/* Windows activates a view after its Load has returned and before its Run is
   called, and delivers visibility, the first client size and the activation
   itself in that order. */
static void raise_activation(void)
{
    RECT client;

    if (host_window && GetClientRect(host_window, &client)) {
        window_size_w = client.right - client.left;
        window_size_h = client.bottom - client.top;
    }
    {
        char line[64];
        snprintf(line, sizeof line, "activation size %dx%d", window_size_w, window_size_h);
        trace(line);
    }
    size_args.state.width = (float)window_size_w;
    size_args.state.height = (float)window_size_h;

    fire_delegate(cw_event_handler[CW_EV_VISIBILITY_CHANGED], &window, &visibility_args);
    fire_delegate(cw_event_handler[CW_EV_SIZE_CHANGED], &window, &size_args);
    fire_delegate(cw_event_handler[CW_EV_ACTIVATED], &window, &activated_args);
    {
        void *snapshot[MAX_VIEW_ACTIVATED_SUBS];
        size_t count = 0, i;
        AcquireSRWLockExclusive(&view_activated_lock);
        for (i = 0; i < MAX_VIEW_ACTIVATED_SUBS; i++) {
            if (view_activated_subs[i].handler) {
                com_add_ref(view_activated_subs[i].handler);
                snapshot[count++] = view_activated_subs[i].handler;
            }
        }
        ReleaseSRWLockExclusive(&view_activated_lock);
        for (i = 0; i < count; i++) {
            fire_delegate(snapshot[i], &app_view, &launch_args);
            com_release(snapshot[i]);
        }
    }
}

/* --------------------------------------------------------- CoreApplication */

typedef struct {
    host_object base;
} core_application;

static core_application application;
static int app_running;

static HRESULT ca_query_interface(core_application *self, const GUID *id, void **out);

static HRESULT ca_face_qi(host_object *self, const GUID *id, void **out)
{
    (void)self;
    return ca_query_interface(&application, id, out);
}

static HRESULT ca_exit_Exit(host_object *self)
{
    (void)self;
    trace("CoreApplication::Exit");
    return cw_Close(&window);
}

static HRESULT ca_exit_add_Exiting(host_object *self, void *handler, long long *token)
{
    (void)self;
    (void)handler;
    if (!token) return E_POINTER;
    *token = 1;
    return S_OK;
}

static HRESULT ca_exit_remove_Exiting(host_object *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

static const void *ca_exit_vtable[] = {
    (const void *)ca_face_qi, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)ca_exit_Exit,           /* Exit */
    (const void *)ca_exit_add_Exiting,    /* add_Exiting */
    (const void *)ca_exit_remove_Exiting, /* remove_Exiting */
};

static host_object ca_exit_face = {
    ca_exit_vtable, 1000, "Windows.ApplicationModel.Core.CoreApplication", 1, NULL,
};

static HRESULT ca_use_count_noop(host_object *self)
{
    (void)self;
    return S_OK;
}

static const void *ca_use_count_vtable[] = {
    (const void *)ca_face_qi, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)ca_use_count_noop, /* IncrementApplicationUseCount */
    (const void *)ca_use_count_noop, /* DecrementApplicationUseCount */
};

static host_object ca_use_count_face = {
    ca_use_count_vtable, 1000, "Windows.ApplicationModel.Core.CoreApplication", 1, NULL,
};

static HRESULT ca_unhandled_add(host_object *self, void *handler, long long *token)
{
    (void)self;
    (void)handler;
    if (!token) return E_POINTER;
    *token = 1;
    return S_OK;
}

static HRESULT ca_unhandled_remove(host_object *self, long long token)
{
    (void)self;
    (void)token;
    return S_OK;
}

static const void *ca_unhandled_vtable[] = {
    (const void *)ca_face_qi, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)ca_unhandled_add,    /* add_UnhandledErrorDetected */
    (const void *)ca_unhandled_remove, /* remove_UnhandledErrorDetected */
};

static host_object ca_unhandled_face = {
    ca_unhandled_vtable, 1000, "Windows.ApplicationModel.Core.CoreApplication", 1, NULL,
};

static HRESULT ca_query_interface(core_application *self, const GUID *id, void **out)
{
    trace_guid("application QI", id);
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &iid_core_application_exit)) {
        *out = &ca_exit_face;
        host_add_ref(&ca_exit_face);
        return S_OK;
    }
    if (IsEqualIID(id, &iid_core_application_use_count)) {
        *out = &ca_use_count_face;
        host_add_ref(&ca_use_count_face);
        return S_OK;
    }
    if (IsEqualIID(id, &iid_core_application_unhandled)) {
        *out = &ca_unhandled_face;
        host_add_ref(&ca_unhandled_face);
        return S_OK;
    }
    return host_query_interface(&self->base, id, &iid_core_application, out);
}

static HRESULT ca_Id(core_application *self, hstring_t *out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = make_hstring(L"DroidDeck.AppModel");
    return S_OK;
}

static HRESULT ca_Properties(core_application *self, void **out)
{
    (void)self;
    if (out) *out = NULL;
    return S_OK;
}

static HRESULT ca_GetCurrentView(core_application *self, void **out)
{
    (void)self;
    if (!out) return E_POINTER;
    *out = &app_view;
    app_view.base.refs++;
    return S_OK;
}

/* LoadPackagedLibrary loads a DLL by a path relative to the package root and
   refuses absolute paths and paths that climb out of it. Wine has no package
   graph and fails every call with APPMODEL_ERROR_NO_PACKAGE, so the host
   answers it for the package it is running. */
int uwp_package_root(WCHAR *out, size_t cap);
int uwp_package_root(WCHAR *out, size_t cap)
{
    WCHAR probe[MAX_PATH];
    DWORD length;
    WCHAR *slash;

    if (!out || cap == 0) return 0;
    out[0] = 0;

    length = GetEnvironmentVariableW(L"DROIDDECK_XBOX_PACKAGE_ROOT", out, (DWORD)cap);
    if (length > 0 && length < cap) {
        while (length > 0 && (out[length - 1] == L'\\' || out[length - 1] == L'/'))
            out[--length] = 0;
        if (length > 0) return 1;
    }

    length = GetModuleFileNameW(NULL, probe, MAX_PATH);
    if (!length || length >= MAX_PATH) return 0;
    slash = wcsrchr(probe, L'\\');
    if (!slash) return 0;
    *slash = 0;

    /* Walk up from the executable directory to find AppxManifest.xml for nested EXEs. */
    {
        WCHAR walk[MAX_PATH];
        wcsncpy(walk, probe, MAX_PATH - 1);
        walk[MAX_PATH - 1] = 0;
        for (;;) {
            WCHAR manifest[MAX_PATH];
            int n = _snwprintf(manifest, MAX_PATH, L"%ls\\AppxManifest.xml", walk);
            if (n > 0 && n < MAX_PATH && GetFileAttributesW(manifest) != INVALID_FILE_ATTRIBUTES) {
                if (wcslen(walk) >= cap) return 0;
                wcscpy(out, walk);
                return 1;
            }
            slash = wcsrchr(walk, L'\\');
            if (!slash || slash <= walk + 2) break;
            *slash = 0;
        }
    }

    if (wcslen(probe) >= cap) return 0;
    wcscpy(out, probe);
    return 1;
}

static HMODULE WINAPI host_LoadPackagedLibrary(LPCWSTR name, DWORD reserved)
{
    WCHAR root[MAX_PATH];
    WCHAR path[MAX_PATH];
    int n;

    if (reserved || !name || !name[0] || name[0] == L'\\' || name[0] == L'/' ||
        wcschr(name, L':') || wcsstr(name, L"..")) {
        SetLastError(ERROR_INVALID_PARAMETER);
        return NULL;
    }
    if (!uwp_package_root(root, MAX_PATH)) {
        SetLastError(ERROR_FILENAME_EXCED_RANGE);
        return NULL;
    }
    n = _snwprintf(path, MAX_PATH, L"%ls\\%ls", root, name);
    if (n < 0 || n >= MAX_PATH) {
        SetLastError(ERROR_FILENAME_EXCED_RANGE);
        return NULL;
    }
    {
        HMODULE module = LoadLibraryExW(path, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
        DWORD error = GetLastError();
        char line[320];
        snprintf(line, sizeof line, "packaged library %ls -> %p (%lu)", name, (void *)module,
                 module ? 0ul : (unsigned long)error);
        trace(line);
        SetLastError(error);
        return module;
    }
}

/* Points a system export at the host and returns what it pointed at. The
   title checks what its own imports point at, so its table is left alone:
   these exports are thunks that jump through their DLL's import of
   kernelbase, and that slot is answered. */
static void *answer_export_store(const char *dll, const char *name, void *answer, void **store_next)
{
    const BYTE *thunk = (const BYTE *)GetProcAddress(GetModuleHandleA(dll), name);
    ULONG_PTR *slot, previous;
    DWORD old;

    if (!thunk) return NULL;
    if (thunk[0] == 0x48 && thunk[1] == 0x8d && thunk[2] == 0xa4 && thunk[3] == 0x24) thunk += 8;
    if (thunk[0] != 0xff || thunk[1] != 0x25) {
        trace("export is not an import thunk");
        return NULL;
    }
    slot = (ULONG_PTR *)(thunk + 6 + *(const int *)(thunk + 2));
    previous = *slot;
    if ((void *)previous == answer) return store_next ? *store_next : NULL;
    if (store_next) *store_next = (void *)previous;
    MemoryBarrier();
    if (!VirtualProtect(slot, sizeof(*slot), PAGE_READWRITE, &old)) return NULL;
    *slot = (ULONG_PTR)answer;
    VirtualProtect(slot, sizeof(*slot), old, &old);
    return (void *)previous;
}

static void *answer_export(const char *dll, const char *name, void *answer)
{
    return answer_export_store(dll, name, answer, NULL);
}

typedef BOOL(WINAPI *token_information_fn)(HANDLE, TOKEN_INFORMATION_CLASS, void *, DWORD, DWORD *);
static token_information_fn token_information_next;
static LUID hosted_token;

extern DWORD uwp_package_sid(BYTE *out, DWORD capacity);

/* A packaged app runs in an AppContainer named for its package family, and a
   title reads that back from its own token. Wine's tokens carry no
   AppContainer, so the host answers those classes for the hosted process's
   token and passes every other query through. */
static BOOL WINAPI host_GetTokenInformation(HANDLE token, TOKEN_INFORMATION_CLASS cls, void *info, DWORD size,
                                            DWORD *needed)
{
    TOKEN_STATISTICS statistics;
    DWORD length, required;
    BYTE sid[64];

    if ((cls != TokenIsAppContainer && cls != TokenAppContainerNumber && cls != TokenAppContainerSid) ||
        !token_information_next(token, TokenStatistics, &statistics, sizeof statistics, &length) ||
        memcmp(&statistics.TokenId, &hosted_token, sizeof hosted_token))
        return token_information_next(token, cls, info, size, needed);

    required = cls == TokenAppContainerSid ? sizeof(TOKEN_APPCONTAINER_INFORMATION) + uwp_package_sid(sid, sizeof sid)
                                           : sizeof(DWORD);
    if (needed) *needed = required;
    if (!info || size < required) {
        SetLastError(ERROR_INSUFFICIENT_BUFFER);
        return FALSE;
    }
    if (cls == TokenAppContainerSid) {
        TOKEN_APPCONTAINER_INFORMATION *container = (TOKEN_APPCONTAINER_INFORMATION *)info;
        memcpy(container + 1, sid, required - sizeof(*container));
        container->TokenAppContainer = (PSID)(container + 1);
    } else {
        *(DWORD *)info = 1;
    }
    return TRUE;
}

static void install_app_container(void)
{
    TOKEN_STATISTICS statistics;
    HANDLE token;
    DWORD length;

    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return;
    if (GetTokenInformation(token, TokenStatistics, &statistics, sizeof statistics, &length)) {
        void *prev = NULL;
        hosted_token = statistics.TokenId;
        MemoryBarrier();
        prev = answer_export_store("advapi32.dll", "GetTokenInformation",
                                   (void *)host_GetTokenInformation,
                                   (void **)&token_information_next);
        if (prev) trace("app container installed");
    }
    CloseHandle(token);
}

static void install_package_loader(void)
{
    if (answer_export("kernel32.dll", "LoadPackagedLibrary", (void *)host_LoadPackagedLibrary))
        trace("package loader installed");
}

typedef HRESULT(__stdcall *create_view_fn)(void *, void **);
typedef HRESULT(__stdcall *view_lifecycle_fn)(void *, void *);

/* Drives the title's own view. The indices are IFrameworkViewSource and
   IFrameworkView: six IUnknown/IInspectable slots first, then CreateView, then
   Initialize, SetWindow, Load, Run, Uninitialize. */
static HRESULT drive_view(void *source)
{
    const void **source_vtbl;
    const void **view_vtbl = NULL;
    void *view = NULL;
    hstring_t entry;
    HRESULT hr = S_OK;
    int initialized = 0;
    int owns_window = 0;

    if (!source) return E_INVALIDARG;
    host_quit = 0;
    AcquireSRWLockExclusive(&dispatch_lock);
    dispatch_closed = 0;
    ReleaseSRWLockExclusive(&dispatch_lock);

    if (!host_window) {
        if (!create_host_window()) {
            close_dispatcher_queue();
            return E_FAIL;
        }
        owns_window = 1;
    }
    view_thread = GetCurrentThreadId();
    install_package_loader();

    source_vtbl = *(const void ***)source;
    if (!source_vtbl || !source_vtbl[6]) {
        trace("view source has no CreateView");
        hr = E_NOINTERFACE;
        goto cleanup;
    }
    hr = ((create_view_fn)(void *)source_vtbl[6])(source, &view);
    if (FAILED(hr) || !view) {
        char line[96];
        snprintf(line, sizeof line, "CreateView failed 0x%08lx view %p", (unsigned long)hr, view);
        trace(line);
        hr = FAILED(hr) ? hr : E_FAIL;
        goto cleanup;
    }
    trace("CreateView ok");

    view_vtbl = *(const void ***)view;
    if (!view_vtbl || !view_vtbl[6]) {
        trace("view has no lifecycle slots");
        hr = E_NOINTERFACE;
        goto cleanup;
    }
    {
        char line[160];
        snprintf(line, sizeof line, "view %p slots %p %p %p %p %p", view, view_vtbl[6],
                 view_vtbl[7], view_vtbl[8], view_vtbl[9], view_vtbl[10]);
        trace(line);
    }
    hr = ((view_lifecycle_fn)(void *)view_vtbl[6])(view, &app_view);
    trace(hr ? "Initialize failed" : "Initialize ok");
    if (FAILED(hr)) goto cleanup;
    initialized = 1;

    trace("SetWindow enter");
    hr = ((view_lifecycle_fn)(void *)view_vtbl[7])(view, &window);
    trace(hr ? "SetWindow failed" : "SetWindow ok");
    if (FAILED(hr)) goto cleanup;

    entry = make_hstring(L"App");
    trace("Load enter");
    hr = ((view_lifecycle_fn)(void *)view_vtbl[8])(view, entry);
    if (p_delete_hstring) p_delete_hstring(entry);
    trace(hr ? "Load failed" : "Load ok");
    if (FAILED(hr)) goto cleanup;

    /* Windows activates the view after Load has returned and before Run is
       called, so the handler runs on the same thread that is about to enter
       the title's loop. It is not held back for ProcessEvents: Forza Horizon 4
       never calls ProcessEvents and instead polls the view from its own loop,
       so a deferred activation is never delivered at all. */
    activation_pending = 1;
    deliver_activation();
    trace("activation raised");

    app_running = 1;
    {
        char line[96];
        snprintf(line, sizeof line, "Run enter %p", (void *)view_vtbl[9]);
        trace(line);
    }
    hr = ((view_lifecycle_fn)(void *)view_vtbl[9])(view, NULL);
    {
        char line[96];
        snprintf(line, sizeof line, "Run returned %s 0x%08lx",
                 SUCCEEDED(hr) ? "S_OK" : "", (unsigned long)hr);
        trace(line);
    }
    app_running = 0;

    /* A title whose Run returns immediately with S_OK leaves the window up;
       pumping until the window closes keeps the model honest. On failure, skip
       the fallback pump and return the original Run HRESULT after cleanup. */
    if (SUCCEEDED(hr)) {
        while (!host_quit) pump_once(1);
    }

cleanup:
    close_dispatcher_queue();
    if (initialized && view && view_vtbl && view_vtbl[10]) {
        HRESULT uninit_hr = ((view_lifecycle_fn)(void *)view_vtbl[10])(view, NULL);
        trace(uninit_hr ? "Uninitialize failed" : "Uninitialize ok");
    }
    if (view) com_release(view);
    if (owns_window && host_window) {
        if (IsWindow(host_window)) DestroyWindow(host_window);
        host_window = NULL;
    }
    return hr;
}

static HRESULT ca_Run(core_application *self, void *source)
{
    (void)self;
    trace("CoreApplication::Run");
    return drive_view(source);
}

/* A title that supplies activation factories instead of a view source is not
   hosted: there is no view to drive, and pretending otherwise would report
   success for a title that never appears. */
static HRESULT ca_RunWithActivationFactories(core_application *self, void *source)
{
    (void)self;
    (void)source;
    trace("CoreApplication::RunWithActivationFactories is not hosted");
    return E_NOTIMPL;
}

static const void *ca_vtable[] = {
    (const void *)ca_query_interface, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)host_get_runtime_class_name,
    (const void *)host_get_trust_level,
    (const void *)ca_Id,                 /* Id */
    (const void *)host_noop,             /* add_Suspending */
    (const void *)host_noop,             /* remove_Suspending */
    (const void *)host_noop,             /* add_Resuming */
    (const void *)host_noop,             /* remove_Resuming */
    (const void *)ca_Properties,         /* Properties */
    (const void *)ca_GetCurrentView,     /* GetCurrentView */
    (const void *)ca_Run,                /* Run */
    (const void *)ca_RunWithActivationFactories, /* RunWithActivationFactories */
};

/* --------------------------------------------------------------- factories */

/* Every class the host answers. A static class hands back its own statics
   object; CoreWindow's factory activates the single window it owns. */
typedef struct {
    const WCHAR *class_name;
    host_object *object;
    int activatable;
} host_class;

static host_class classes[] = {
    {L"Windows.ApplicationModel.Core.CoreApplication", &application.base, 0},
    {L"Windows.UI.Core.CoreWindow", &window.base, 1},
};

static host_class *factory_entry_of(host_object *self)
{
    return (host_class *)self->owner;
}

static HRESULT factory_activate(host_object *self, void **out)
{
    host_class *entry = factory_entry_of(self);
    if (!out) return E_POINTER;
    if (!entry || !entry->activatable) {
        if (entry) trace_text("activate refused for", entry->class_name);
        *out = NULL;
        return E_NOTIMPL;
    }
    *out = entry->object;
    entry->object->refs++;
    return S_OK;
}

static HRESULT factory_query_interface(host_object *self, const GUID *id, void **out)
{
    host_class *entry = factory_entry_of(self);
    if (!out) return E_POINTER;
    if (IsEqualIID(id, &iid_activation_factory) || IsEqualIID(id, &iid_unknown) ||
        IsEqualIID(id, &iid_inspectable) || IsEqualIID(id, &iid_agile_object)) {
        *out = self;
        self->refs++;
        return S_OK;
    }
    /* Reaching the statics interface of the object behind this class is how a
       title calls a static: the factory is the way in. */
    if (entry && entry->object->vtbl && entry->object->vtbl[0]) {
        return ((HRESULT(__stdcall *)(void *, const GUID *, void **))entry->object->vtbl[0])(
            entry->object, id, out);
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static HRESULT factory_activate_instance(host_object *self, void **out)
{
    return factory_activate(self, out);
}

static HRESULT factory_class_name(host_object *self, hstring_t *out)
{
    host_class *entry = factory_entry_of(self);
    if (!out) return E_POINTER;
    *out = entry ? make_hstring(entry->class_name) : NULL;
    return S_OK;
}

static HRESULT factory_trust(host_object *self, int *out)
{
    (void)self;
    if (out) *out = 2;
    return S_OK;
}

static const void *factory_vtable[] = {
    (const void *)factory_query_interface, (const void *)host_add_ref, (const void *)host_release,
    (const void *)host_get_iids, (const void *)factory_class_name,
    (const void *)factory_trust,
    (const void *)factory_activate_instance, /* IActivationFactory::ActivateInstance */
};

static void install_objects(void)
{
    window.base.vtbl = cw_vtable;
    window.base.refs = 1000;
    window.base.permanent = 1;
    window.base.name = "Windows.UI.Core.CoreWindow";

    window_static.base.vtbl = cws_vtable;
    window_static.base.refs = 1000;
    window_static.base.permanent = 1;
    window_static.base.name = "Windows.UI.Core.CoreWindow";

    window_interop.vtbl = cwi_vtable;
    window_interop.refs = 1000;

    dispatcher.base.vtbl = cd_vtable;
    dispatcher.base.refs = 1000;
    dispatcher.base.permanent = 1;
    dispatcher.base.name = "Windows.UI.Core.CoreDispatcher";

    app_view.base.vtbl = cav_vtable;
    app_view.base.refs = 1000;
    app_view.base.permanent = 1;
    app_view.base.name = "Windows.ApplicationModel.Core.CoreApplicationView";

    application.base.vtbl = ca_vtable;
    application.base.refs = 1000;
    application.base.permanent = 1;
    application.base.name = "Windows.ApplicationModel.Core.CoreApplication";
}

/* --------------------------------------------------------------- exports */

/* The operating-system services a packaged title reaches for on the way up
   live in their own unit: storage, package identity, cryptography, display,
   media and input. They register here so the class table stays one list. */
extern void uwp_services_install(void);
extern int uwp_services_lookup(const WCHAR *class_name, void **factory);

/* Xbox Live single sign-on lives in its own unit too. A packaged title that
   starts through WebAuthenticationCoreManager needs these registered next to
   the app-model and service classes, or activation fails as "not registered". */
extern void uwp_auth_install(void);
extern int uwp_auth_lookup(const WCHAR *class_name, void **factory);
extern void uwp_gamesave_install(void);
extern int uwp_gamesave_lookup(const WCHAR *class_name, void **factory);

static host_object factories[sizeof(classes) / sizeof(classes[0])];

static INIT_ONCE host_install_once = INIT_ONCE_STATIC_INIT;

static BOOL CALLBACK host_install_cb(PINIT_ONCE once, PVOID param, PVOID *ctx)
{
    size_t i;
    (void)once;
    (void)param;
    (void)ctx;
    install_objects();
    uwp_services_install();
    uwp_auth_install();
    uwp_gamesave_install();
    install_app_container();
    for (i = 0; i < sizeof(classes) / sizeof(classes[0]); i++) {
        factories[i].vtbl = factory_vtable;
        factories[i].refs = 1000;
        factories[i].permanent = 1;
        factories[i].name = classes[i].object->name;
        factories[i].owner = &classes[i];
    }
    return TRUE;
}

static void ensure_installed(void)
{
    InitOnceExecuteOnce(&host_install_once, host_install_cb, NULL, NULL);
}



__declspec(dllexport) BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved)
{
    (void)reserved;
    if (reason == 1 /* DLL_PROCESS_ATTACH */) {
        char image[MAX_PATH];
        host_module = instance;
        trace_begin_run();
        trace_sink("dll attached");
        /* Which program loaded the host. A prefix hosts several processes and
           they all land in this one file, so every run names its title. */
        {
            char title[MAX_PATH];
            DWORD got = GetModuleFileNameA(NULL, title, sizeof title);
            if (got > 0 && got < sizeof title) trace(title);
        }
        if (GetModuleFileNameA(instance, image, sizeof image)) trace(image);
    }
    return TRUE;
}

/* The C runtime's startup is not linked in (see _DllMainCRTStartup), and its
   pseudo-relocation pass is the one piece the linker still names. Nothing here
   imports data from another image, so there is nothing for it to fix up. */
void _pei386_runtime_relocator(void)
{
}

__declspec(dllexport) BOOL WINAPI _DllMainCRTStartup(HINSTANCE instance, DWORD reason, LPVOID reserved)
{
    /* The loader's entry for the image. A packaged title runs x86-64 code
       through FEX on ARM64EC, and the C runtime's own startup walks a TLS
       callback table, an atexit table and a security cookie before it reaches
       DllMain; under the translator that startup is where the load faults. The
       host needs none of it: it is C, it has no constructors, and its first
       work is a kernel32 call. Linking without the C runtime startup and
       entering here is the whole initialization it requires. */
    return DllMain(instance, reason, reserved);
}

__declspec(dllexport) HRESULT __stdcall DllGetActivationFactory(hstring_t class_id, void **factory)
{
    const WCHAR *name;
    size_t i;

    trace_sink("activation factory entered");
    {
        char stub[8];
        DWORD length = GetEnvironmentVariableA("DROIDDECK_UWP_STUB", stub, sizeof stub);
        if (length > 0 && length < sizeof stub) {
            trace_sink("stub: class not available");
            return CLASS_E_CLASSNOTAVAILABLE;
        }
    }
    ensure_installed();
    trace("activation request");
    if (!factory) return E_POINTER;
    *factory = NULL;
    name = hstring_text(class_id);
    if (!name) return E_INVALIDARG;
    {
        char line[300];
        char narrow[256];
        int at;
        for (at = 0; at < 255 && name[at]; at++) narrow[at] = (char)name[at];
        narrow[at] = 0;
        snprintf(line, sizeof line, "class: %s", narrow);
        trace_sink(line);
    }
    trace_text("activate", name);
    for (i = 0; i < sizeof(classes) / sizeof(classes[0]); i++) {
        if (wcscmp(name, classes[i].class_name) == 0) {
            *factory = &factories[i];
            return S_OK;
        }
    }
    if (uwp_services_lookup(name, factory)) return S_OK;
    if (uwp_auth_lookup(name, factory)) return S_OK;
    if (uwp_gamesave_lookup(name, factory)) return S_OK;
    trace_text("no host for class", name);
    return CLASS_E_CLASSNOTAVAILABLE;
}

__declspec(dllexport) HRESULT __stdcall DllCanUnloadNow(void)
{
    return S_FALSE;
}
