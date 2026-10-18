#if !defined(__x86_64__)
#error webkitinput is built for x86_64 guests only
#endif

typedef struct _WebKitSettings WebKitSettings;
typedef struct _WebKitUserContentManager WebKitUserContentManager;
typedef struct _WebKitUserScript WebKitUserScript;

extern void g_object_set(void *object, const char *first_property, ...) __attribute__((weak));
extern void *g_object_get_data(void *object, const char *key) __attribute__((weak));
extern void g_object_set_data(void *object, const char *key, void *data) __attribute__((weak));
extern void *dlsym(void *handle, const char *symbol) __attribute__((weak));
extern unsigned gtk_key_snooper_install(int (*snooper)(void *, void *, void *), void *data) __attribute__((weak));
extern void *gtk_widget_get_toplevel(void *widget) __attribute__((weak));
extern void gtk_container_forall(void *container, void (*callback)(void *, void *), void *data) __attribute__((weak));
extern unsigned long gtk_container_get_type(void) __attribute__((weak));
extern unsigned long webkit_web_view_get_type(void) __attribute__((weak));
extern int g_type_check_instance_is_a(void *instance, unsigned long type) __attribute__((weak));
extern const char *webkit_web_view_get_uri(void *view) __attribute__((weak));
extern void webkit_web_view_run_javascript(void *view, const char *script, void *cancellable, void *callback,
                                           void *data) __attribute__((weak));
extern WebKitUserScript *webkit_user_script_new(const char *source, int frames, int time,
                                                const char *const *allow, const char *const *block) __attribute__((weak));
extern void webkit_user_script_unref(WebKitUserScript *script) __attribute__((weak));

static const char NAV_SCRIPT[] =
    "(()=>{let open=-1e9,arrowAt=-1e9,ringed=null,ringOutline='',ringOffset='',settleTimer=0,settling=false,redirecting=false;"
    "const shown=()=>[...document.querySelectorAll('[role=dialog],[role=alertdialog],[aria-modal=true],[role=menu]')]"
    ".some(e=>e.getClientRects().length);"
    "const hidden=e=>{const r=e.getBoundingClientRect();if(!r.width||!r.height)return true;"
    "for(let n=e;n&&n.nodeType===1;n=n.parentElement){const s=getComputedStyle(n);"
    "if(s.visibility==='hidden'||+s.opacity<0.1)return true;}return false;};"
    "const windowButton=e=>/^(Minimize|Maximize|Restore|Close) window$/.test(e.getAttribute('aria-label')||'');"
    "const editing=e=>!!e&&e.nodeType===1&&(e.isContentEditable||"
    "e.matches('input:not([type=checkbox]):not([type=radio]):not([type=button]):not([type=submit]),textarea,select'));"
    "const mainNav=()=>[...document.querySelectorAll('nav')].filter(n=>n.querySelectorAll('a[href]').length>1&&!hidden(n))"
    ".sort((a,b)=>a.getBoundingClientRect().y-b.getBoundingClientRect().y)[0];"
    "const first=nav=>{const below=nav?nav.getBoundingClientRect().bottom:0,all=[];"
    "for(const e of document.querySelectorAll('a[href],button,input,[role=button],[tabindex]')){"
    "if(e.tabIndex<0||(nav&&nav.contains(e)))continue;const r=e.getBoundingClientRect();"
    "if(r.top<below||r.bottom>innerHeight||windowButton(e)||hidden(e))continue;all.push({e,r});}"
    "const big=all.filter(c=>c.r.width*c.r.height>=12000),list=big.length?big:all;"
    "const key=c=>Math.round(c.r.top/8)*1e5+c.r.left;"
    "list.sort((a,b)=>key(a)-key(b));return list.length?list[0].e:null;};"
    "const big=e=>{const r=e.getBoundingClientRect();return r.width*r.height>=12000;};"
    "const toward=(from,key,nav,small)=>{const r=from.getBoundingClientRect(),cx=r.left+r.width/2,cy=r.top+r.height/2;let best=null,score=1e9;"
    "for(const c of document.querySelectorAll('a[href],button,[role=button],[tabindex]')){if(c===from||c.tabIndex<0||(nav&&nav.contains(c))||from.contains(c)||c.contains(from))continue;"
    "const q=c.getBoundingClientRect();if(!small&&q.width*q.height<12000)continue;const qx=q.left+q.width/2,qy=q.top+q.height/2;let along,across;"
    "if(key==='ArrowDown'){along=q.top-r.bottom;across=qx-cx;}else if(key==='ArrowUp'){along=r.top-q.bottom;across=qx-cx;}"
    "else if(key==='ArrowRight'){along=q.left-r.right;across=qy-cy;}else{along=r.left-q.right;across=qy-cy;}"
    "if(along<-4)continue;const d=Math.max(along,0)+2*Math.abs(across);if(d<score&&!windowButton(c)&&!hidden(c)){score=d;best=c;}}return best;};"
    "const settle=()=>{const a=document.activeElement;if(editing(a)||shown())return;const nav=mainNav(),list=[];"
    "for(const c of document.querySelectorAll('a[href],button,[role=button],[tabindex]')){if(c.tabIndex<0||(nav&&nav.contains(c)))continue;"
    "const r=c.getBoundingClientRect();if(r.width*r.height>=12000&&r.top>=0&&r.left>=0&&r.bottom<=innerHeight&&r.right<=innerWidth)list.push({c,r});}"
    "const key=x=>Math.round(x.r.top/8)*1e5+x.r.left;list.sort((x,y)=>key(x)-key(y));"
    "for(const {c,r} of list){const hit=document.elementFromPoint(r.left+r.width/2,r.top+8);"
    "if(hit&&(hit===c||c.contains(hit))&&!hidden(c)){arrowAt=performance.now();c.focus({preventScroll:true});return;}}};"
    "const arm=()=>{clearTimeout(settleTimer);settleTimer=setTimeout(()=>{settling=false;settle();},200);};"
    "addEventListener('scroll',()=>{if(settling)arm();},true);"
    "const unring=()=>{if(ringed){ringed.style.outline=ringOutline;ringed.style.outlineOffset=ringOffset;ringed=null;}};"
    "addEventListener('keydown',e=>{const k=e.key||'';if(k.startsWith('Arrow')){arrowAt=e.timeStamp;const a=document.activeElement;"
    "if(!editing(a)&&!shown()){const nav=mainNav();"
    "if(!a||a===document.body||hidden(a)||(k==='ArrowDown'&&nav&&nav.contains(a))){const f=first(nav);"
    "if(f){e.preventDefault();e.stopImmediatePropagation();f.focus();f.scrollIntoView({block:'nearest'});}}"
    "else if(big(a)&&!e.ctrlKey&&!e.altKey){let t=toward(a,k,nav);"
    "if(!t&&(k==='ArrowUp'||k==='ArrowDown'))t=toward(a,k,nav,true);"
    "if(t){e.preventDefault();e.stopImmediatePropagation();t.focus({preventScroll:true});t.scrollIntoView({block:'nearest',inline:'nearest'});}}}}"
    "if(k==='Escape')open=shown()?e.timeStamp:-1e9;"
    "if(e.code==='F13'){e.preventDefault();e.stopImmediatePropagation();settling=true;arm();}},true);"
    "addEventListener('keydown',e=>{if(e.key!=='BrowserBack')return;e.preventDefault();e.stopImmediatePropagation();"
    "const a=document.activeElement;if(editing(a)){a.blur();return;}"
    "if(e.timeStamp-open>500)history.back();},true);"
    "window.droiddeckTab=step=>{"
    "const nav=mainNav();if(!nav)return;"
    "const tabs=[...nav.querySelectorAll('a[href]')].filter(a=>!hidden(a)),part=p=>p.split('/')[1]||'';"
    "const at=tabs.findIndex(a=>part(new URL(a.href).pathname)===part(location.pathname));"
    "const to=tabs[Math.min(tabs.length-1,Math.max(0,at+step))];"
    "if(at>=0&&to!==tabs[at]){to.click();to.blur();}};"
    "const redirect=n=>{redirecting=true;try{n.focus();}finally{redirecting=false;}};"
    "addEventListener('focusout',unring,true);"
    "addEventListener('focusin',e=>{const t=e.target;if(!(t instanceof Element)||e.timeStamp-arrowAt>1000)return;"
    "const back=e.relatedTarget instanceof Element&&e.relatedTarget.focus?e.relatedTarget:null;"
    "if(!redirecting&&windowButton(t)){if(back)redirect(back);return;}"
    "if(!redirecting&&hidden(t)){let a=t.parentElement;"
    "while(a&&!(a.matches('a[href],button,[role=button],[tabindex]')&&!hidden(a)))a=a.parentElement;"
    "if(a)redirect(a);else if(back)redirect(back);return;}"
    "unring();ringed=(t.matches('input,textarea')&&t.closest('label'))||t;"
    "ringOutline=ringed.style.outline;ringOffset=ringed.style.outlineOffset;"
    "ringed.style.outline='3px solid #fff';ringed.style.outlineOffset='2px';},true);})();";

typedef struct {
    int type;
    void *window;
    signed char send_event;
    unsigned time;
    unsigned state;
    unsigned keyval;
} KeyEvent;

static void *launcher_view;

static void find_launcher_view(void *widget, void *data)
{
    (void)data;
    if (launcher_view)
        return;
    if (g_type_check_instance_is_a(widget, webkit_web_view_get_type())) {
        const char *uri = webkit_web_view_get_uri(widget);
        const char *prefix = "tauri:";
        while (uri && *prefix && *uri == *prefix) {
            uri++;
            prefix++;
        }
        if (uri && !*prefix)
            launcher_view = widget;
    } else if (g_type_check_instance_is_a(widget, gtk_container_get_type())) {
        gtk_container_forall(widget, find_launcher_view, 0);
    }
}

static int switch_tab(void *grab, void *event_data, void *data)
{
    KeyEvent *event = event_data;
    (void)data;
    if (!grab || event->type != 8 || !(event->state & 4) || (event->keyval != 0xff55 && event->keyval != 0xff56))
        return 0;
    launcher_view = 0;
    find_launcher_view(gtk_widget_get_toplevel(grab), 0);
    if (!launcher_view)
        return 0;
    webkit_web_view_run_javascript(launcher_view, event->keyval == 0xff56 ? "window.droiddeckTab&&droiddeckTab(1)"
                                                                            : "window.droiddeckTab&&droiddeckTab(-1)",
                                   0, 0, 0);
    return 1;
}

static void watch_tab_keys(void)
{
    static int watching;
    if (watching || !gtk_key_snooper_install || !gtk_widget_get_toplevel || !gtk_container_forall ||
        !gtk_container_get_type || !webkit_web_view_get_type || !g_type_check_instance_is_a ||
        !webkit_web_view_get_uri || !webkit_web_view_run_javascript)
        return;
    watching = 1;
    gtk_key_snooper_install(switch_tab, 0);
}

static void set(WebKitSettings *settings, const char *setter, const char *property, int value)
{
    static int setting;
    if (setting) {
        void (*next)(WebKitSettings *, int) = dlsym ? (void (*)(WebKitSettings *, int))dlsym((void *)-1l, setter) : 0;
        if (next)
            next(settings, value);
        return;
    }
    if (!g_object_set)
        return;
    setting = 1;
    g_object_set(settings, property, value, "enable-smooth-scrolling", 0, "enable-spatial-navigation", 1, (const char *)0);
    setting = 0;
}

#define SETTER(name, property) \
    __attribute__((visibility("default"))) void webkit_settings_set_##name(WebKitSettings *settings, int value) \
    { \
        set(settings, "webkit_settings_set_" #name, property, value); \
    }

SETTER(enable_javascript, "enable-javascript")
SETTER(enable_developer_extras, "enable-developer-extras")
SETTER(enable_webgl, "enable-webgl")
SETTER(enable_webaudio, "enable-webaudio")
SETTER(enable_page_cache, "enable-page-cache")
SETTER(javascript_can_access_clipboard, "javascript-can-access-clipboard")
SETTER(javascript_can_open_windows_automatically, "javascript-can-open-windows-automatically")
SETTER(enable_back_forward_navigation_gestures, "enable-back-forward-navigation-gestures")

__attribute__((visibility("default"))) void webkit_user_content_manager_add_script(WebKitUserContentManager *manager,
                                                                                   WebKitUserScript *script)
{
    static void (*next)(WebKitUserContentManager *, WebKitUserScript *);
    if (!next && dlsym)
        next = (void (*)(WebKitUserContentManager *, WebKitUserScript *))dlsym((void *)-1l, "webkit_user_content_manager_add_script");
    if (!next)
        return;
    watch_tab_keys();
    if (g_object_get_data && g_object_set_data && webkit_user_script_new && webkit_user_script_unref &&
        !g_object_get_data(manager, "droiddeck-nav")) {
        g_object_set_data(manager, "droiddeck-nav", (void *)1);
        WebKitUserScript *nav = webkit_user_script_new(NAV_SCRIPT, 1, 0, 0, 0);
        if (nav) {
            next(manager, nav);
            webkit_user_script_unref(nav);
        }
    }
    next(manager, script);
}
