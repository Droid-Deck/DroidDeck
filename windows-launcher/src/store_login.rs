use crate::store_api::{self, LoginCapture};
use serde_json::{Value, json};
use std::{sync::mpsc, time::Instant};
use winit::{
    application::ApplicationHandler,
    event::WindowEvent,
    event_loop::{ActiveEventLoop, ControlFlow, EventLoop},
    window::{Window, WindowId},
};
use wry::{WebView, WebViewBuilder};

struct LoginApp {
    store: String,
    start_url: String,
    extra: String,
    window: Option<Window>,
    webview: Option<WebView>,
    rx: mpsc::Receiver<String>,
    tx: mpsc::Sender<String>,
    result: Option<String>,
    error: Option<String>,
}

impl ApplicationHandler for LoginApp {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            return;
        }
        let window = match event_loop.create_window(
            Window::default_attributes()
                .with_title(format!(
                    "Conectar {} con DroidDeck Launcher",
                    crate::accounts::stores()
                        .iter()
                        .find(|(id, _, _)| *id == self.store)
                        .map(|(_, name, _)| *name)
                        .unwrap_or("tienda")
                ))
                .with_inner_size(winit::dpi::LogicalSize::new(1100.0, 780.0)),
        ) {
            Ok(window) => window,
            Err(error) => {
                self.error = Some(format!(
                    "No se pudo abrir la ventana de inicio de sesión: {error}"
                ));
                event_loop.exit();
                return;
            }
        };
        let tx_for_ipc = self.tx.clone();
        let tx_for_nav = self.tx.clone();
        let store = self.store.clone();
        let ipc_store = self.store.clone();
        let script = bridge_script(&self.store, &self.extra);
        let builder = WebViewBuilder::new()
            .with_url(&self.start_url)
            .with_initialization_script_for_main_only(script, true)
            .with_ipc_handler(move |request| {
                let body = request.body().to_owned();
                let sender = request.uri().to_string();
                if store_ipc_origin_allowed(&ipc_store, &sender)
                    && serde_json::from_str::<Value>(&body).is_ok()
                {
                    let _ = tx_for_ipc.send(body);
                }
            })
            .with_navigation_handler(move |url| {
                if callback_for(&store, &url) {
                    let _ = tx_for_nav.send(
                        json!({"store":store,"url":url,"body":"","storage":"","data":{}})
                            .to_string(),
                    );
                    false
                } else {
                    true
                }
            });
        match builder.build(&window) {
            Ok(webview) => {
                self.window = Some(window);
                self.webview = Some(webview);
            }
            Err(error) => {
                self.error = Some(format!(
                    "No se pudo abrir el navegador integrado de Windows: {error}"
                ));
                event_loop.exit();
            }
        }
    }

    fn window_event(&mut self, event_loop: &ActiveEventLoop, _: WindowId, event: WindowEvent) {
        if matches!(event, WindowEvent::CloseRequested) {
            event_loop.exit();
        }
    }

    fn about_to_wait(&mut self, event_loop: &ActiveEventLoop) {
        if let Ok(message) = self.rx.try_recv() {
            self.result = Some(message);
            event_loop.exit();
        } else {
            event_loop.set_control_flow(ControlFlow::WaitUntil(
                Instant::now() + std::time::Duration::from_millis(60),
            ));
        }
    }
}

pub fn run(store: &str) -> Result<(), String> {
    let (start_url, extra) = start_url(store)?;
    let profile = prepare_webview_profile(store)?;
    unsafe {
        std::env::set_var("WEBVIEW2_USER_DATA_FOLDER", profile);
    }
    let (tx, rx) = mpsc::channel();
    let mut app = LoginApp {
        store: store.to_owned(),
        start_url,
        extra,
        window: None,
        webview: None,
        rx,
        tx,
        result: None,
        error: None,
    };
    let event_loop = EventLoop::new().map_err(|e| format!("No se pudo iniciar la ventana: {e}"))?;
    event_loop
        .run_app(&mut app)
        .map_err(|e| format!("Falló la ventana de inicio de sesión: {e}"))?;
    let extra = app.extra.clone();
    drop(app.webview.take());
    drop(app.window.take());
    if let Some(error) = app.error {
        return Err(error);
    }
    let Some(raw) = app.result else {
        return Err("Se cerró la ventana antes de completar el inicio de sesión.".into());
    };
    let mut capture: LoginCapture = serde_json::from_str(&raw)
        .map_err(|_| "La tienda devolvió datos de inicio de sesión ilegibles".to_owned())?;
    capture.store = store.to_owned();
    let result = store_api::connect(capture, &extra)?;
    let output = serde_json::to_string(&result).map_err(|e| e.to_string())?;
    println!("{output}");
    Ok(())
}

pub fn clear_webview_profile(store: &str) -> Result<(), String> {
    if !matches!(
        store,
        "steam" | "epic" | "ea" | "gog" | "ubisoft" | "amazon" | "gamejolt" | "rockstar"
    ) {
        return Ok(());
    }
    let (root, target, marker) = profile_paths(store)?;
    std::fs::create_dir_all(&root)
        .map_err(|e| format!("No se pudo preparar la limpieza del perfil web: {e}"))?;
    std::fs::write(&marker, b"pending")
        .map_err(|e| format!("No se pudo registrar la limpieza pendiente del perfil web: {e}"))?;
    if !target.exists() {
        std::fs::remove_file(marker)
            .map_err(|e| format!("No se pudo cerrar la limpieza del perfil web: {e}"))?;
        return Ok(());
    }
    let root = root
        .canonicalize()
        .map_err(|e| format!("No se pudo validar el perfil web: {e}"))?;
    let target = target
        .canonicalize()
        .map_err(|e| format!("No se pudo validar el perfil web: {e}"))?;
    if !target.starts_with(&root) || target == root {
        return Err(
            "El perfil de tienda resuelve fuera de la carpeta aislada de DroidDeck Launcher".into(),
        );
    }
    std::fs::remove_dir_all(target)
        .map_err(|e| format!("No se pudo borrar la sesión web de {store}: {e}"))?;
    std::fs::remove_file(marker)
        .map_err(|e| format!("No se pudo confirmar la limpieza del perfil web: {e}"))
}

pub fn webview_cleanup_pending(store: &str) -> bool {
    profile_paths(store).is_ok_and(|(_, _, marker)| marker.exists())
}

fn profile_paths(
    store: &str,
) -> Result<(std::path::PathBuf, std::path::PathBuf, std::path::PathBuf), String> {
    let local = dirs::data_local_dir().ok_or("No se encontró la carpeta de datos de Windows")?;
    let root = local.join("DroidDeckLauncher").join("webview2");
    let target = root.join(store);
    let marker = root.join(format!(".{store}.cleanup-pending"));
    Ok((root, target, marker))
}

fn prepare_webview_profile(store: &str) -> Result<std::path::PathBuf, String> {
    let (root, target, marker) = profile_paths(store)?;
    std::fs::create_dir_all(&root)
        .map_err(|e| format!("No se pudo preparar el perfil aislado de {store}: {e}"))?;
    if marker.exists() {
        clear_webview_profile(store)?;
    }
    std::fs::create_dir_all(&target)
        .map_err(|e| format!("No se pudo crear el perfil aislado de {store}: {e}"))?;
    Ok(target)
}

fn start_url(store: &str) -> Result<(String, String), String> {
    let mut random = [0u8; 24];
    getrandom::fill(&mut random).map_err(|e| format!("No se pudo crear una sesión segura: {e}"))?;
    let state = base64::Engine::encode(&base64::engine::general_purpose::URL_SAFE_NO_PAD, random);
    match store {
        "steam" => Ok(("https://store.steampowered.com/login/".into(), String::new())),
        "epic" => Ok(("https://www.epicgames.com/id/login?redirectUrl=https%3A%2F%2Fwww.epicgames.com%2Fid%2Fapi%2Fredirect%3FclientId%3D34a02cf8f4414e29b15921876da36f9a%26responseType%3Dcode".into(), String::new())),
        "gog" => Ok((format!("https://auth.gog.com/auth?client_id=46899977096215655&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient&response_type=token&layout=client2&state={state}"), json!({"state":state}).to_string())),
        "amazon" => {
            let attempt = crate::store_api::amazon::auth::Attempt::new()?;
            let extra = json!({"serial":attempt.serial,"clientId":attempt.client_id,"verifier":attempt.verifier}).to_string();
            Ok((attempt.start_url(), extra))
        }
        "ea" => {
            let (url, verifier) = store_api::ea_auth_start()?;
            Ok((format!("{url}&state={state}"), json!({"state":state,"verifier":verifier}).to_string()))
        }
        "ubisoft" => Ok(("https://connect.ubisoft.com/login?appId=f35adcb5-1911-440c-b1c9-48fdc1701c68&genomeId=5b36b900-65d8-47f3-93c8-86bdaa48ab50&lang=en-US&nextUrl=https%3A%2F%2Fconnect.ubisoft.com%2Flogged-in.html%3FappId%3Df35adcb5-1911-440c-b1c9-48fdc1701c68%26lang%3Den-US%26genomeId%3D5b36b900-65d8-47f3-93c8-86bdaa48ab50".into(), String::new())),
        "gamejolt" => Ok(("https://gamejolt.com/login".into(), String::new())),
        "rockstar" => {
            let mut fp = [0u8; 12];
            getrandom::fill(&mut fp).map_err(|e| format!("No se pudo crear la huella de Rockstar: {e}"))?;
            let device = "DROIDDECK-WINDOWS";
            let fingerprint = json!({
                "device_name":device,"machine_name":device,"os_version":"10.0.19045",
                "volume_serial":fp[..4].iter().map(|b| format!("{b:02x}")).collect::<String>(),
                "cpu_info":fp[4..].iter().map(|b| format!("{b:02x}")).collect::<String>()
            });
            Ok(("https://signin.rockstargames.com/robots.txt".into(), fingerprint.to_string()))
        }
        _ => Err(format!("El inicio de sesión de {store} aún no está disponible en Windows.")),
    }
}

fn callback_for(store: &str, url: &str) -> bool {
    match store {
        "gog" => url.starts_with("https://embed.gog.com/on_login_success"),
        "amazon" => url::Url::parse(url).is_ok_and(|parsed| {
            parsed.scheme() == "https"
                && parsed.host_str() == Some("www.amazon.com")
                && crate::store_api::amazon::auth::code_from_url(url).is_some()
        }),
        "ea" => {
            url.starts_with("qrc:///html/login_successful.html")
                || url.starts_with("qrc:/html/login_successful.html")
        }
        "rockstar" => url.starts_with("https://signin.rockstargames.com/droiddeck/auth-code"),
        _ => false,
    }
}

fn store_ipc_origin_allowed(store: &str, sender: &str) -> bool {
    let Ok(uri) = url::Url::parse(sender) else {
        return false;
    };
    if uri.scheme() != "https" {
        return false;
    }
    let Some(host) = uri.host_str() else {
        return false;
    };
    let allowed: &[&str] = match store {
        "steam" => &["store.steampowered.com"],
        "epic" => &["www.epicgames.com", "accounts.epicgames.com"],
        "ea" => &["signin.ea.com", "accounts.ea.com", "www.ea.com"],
        "gog" => &["auth.gog.com", "embed.gog.com"],
        "ubisoft" => &["connect.ubisoft.com", "signin.ubisoft.com"],
        "amazon" => &["www.amazon.com"],
        "rockstar" => &["signin.rockstargames.com", "rgl.rockstargames.com"],
        "gamejolt" => &["gamejolt.com", "www.gamejolt.com"],
        _ => &[],
    };
    allowed.contains(&host)
}

fn bridge_script(store: &str, extra: &str) -> String {
    if store == "rockstar" {
        return rockstar_bridge_script(extra);
    }
    let store_json = serde_json::to_string(store).unwrap_or_else(|_| "\"\"".into());
    format!(
        r##"
(() => {{
  const store = {store_json};
  const send = async () => {{
    const payload = {{ store, url: location.href, body: document.body?.innerText || "", storage: "", data: {{}} }};
    try {{ payload.storage = JSON.stringify(Object.fromEntries(Array.from({{ length: localStorage.length }}, (_, i) => localStorage.key(i)).filter(k => k && /loginData|rememberMe|lastProfile/i.test(k)).map(k => [k, localStorage.getItem(k)]))); }} catch (_) {{}}
    if (store === "steam") {{
      const [ownedResponse, profileResponse] = await Promise.all([fetch("https://store.steampowered.com/dynamicstore/userdata/?v=1", {{ credentials: "include" }}), fetch("https://store.steampowered.com/my/", {{ credentials: "include" }})]);
      if (!ownedResponse.ok || !profileResponse.ok) throw new Error("No se pudo leer la cuenta de Steam. Revisá la conexión e intentá de nuevo.");
      const owned = await ownedResponse.json(); const html = await profileResponse.text();
      const steamId = html.match(/g_steamID\s*=\s*["'](\d+)["']/)?.[1] || html.match(/data-miniprofile=["'](\d+)["']/)?.[1] || document.querySelector('a[href*="/profiles/"]')?.href.match(/\/profiles\/(\d+)/)?.[1] || "";
      const list = owned.rgOwnedApps;
      const ids = Array.isArray(list) ? list.map(Number).filter(Number.isFinite) : list && typeof list === "object" ? Object.keys(list).map(Number).filter(Number.isFinite) : null;
      if (!steamId || !/^\d+$/.test(steamId)) throw new Error("Iniciá sesión en Steam o escaneá el QR de la página y después sincronizá la biblioteca.");
      if (!ids) throw new Error("Steam confirmó la sesión, pero no devolvió la biblioteca. Volvé a iniciar sesión y probá otra vez.");
      payload.data = {{ steamId, displayName: document.querySelector("#account_pulldown")?.textContent?.trim() || steamId, ids }};
    }} else if (store === "gamejolt") {{
      const search = await fetch("/site-api/web/search/games?q=a", {{ credentials: "include" }}).then(r => r.json());
      const user = search.user; const username = user?.username;
      if (!username) throw new Error("Game Jolt todavía no confirmó que iniciaste sesión.");
      const games = []; let total = 0;
      for (let page = 1; page <= 20; page++) {{
        const path = "/site-api/web/library/games/owned/@" + encodeURIComponent(username) + (page > 1 ? "?page=" + page : "");
        const library = await fetch(path, {{ credentials: "include" }}).then(r => r.json());
        const batch = library.payload?.games || []; games.push(...batch); total = library.payload?.totalGamesCount || total;
        if (batch.length === 0 || (total > 0 && games.length >= total)) break;
      }}
      payload.data = {{ user, games }};
    }}
    window.ipc.postMessage(JSON.stringify(payload));
  }};
  const buttonId = "droiddeck-store-connect";
  const add = () => {{
    if (!document.body || window.top !== window.self || document.getElementById(buttonId)) return;
    if (store === "amazon") return;
    const button = document.createElement("button");
    button.id = buttonId;
    const actionLabel = store === "steam" || store === "gamejolt" ? "Sincronizar biblioteca con DroidDeck" : "Ya inicié sesión · Conectar";
    button.textContent = actionLabel;
    button.title = store === "steam" ? "Si Steam ya muestra tu usuario, sincronizá directamente. Si no, iniciá sesión o escaneá el QR de Steam y volvé a pulsar." : "Completá el inicio de sesión y luego conectá la cuenta.";
    Object.assign(button.style, {{ position: "fixed", zIndex: "2147483647", top: "12px", right: "16px", padding: "12px 18px", border: "0", borderRadius: "9px", background: "#20a4f3", color: "#07111a", font: "600 14px Segoe UI, sans-serif", boxShadow: "0 5px 18px #0009", cursor: "pointer" }});
    button.onclick = async () => {{ button.disabled = true; button.textContent = "Verificando cuenta…"; button.title = ""; try {{ await send(); }} catch (e) {{ const message = e?.message || "No se pudo validar la cuenta. Intentá otra vez."; button.disabled = false; button.textContent = store === "steam" && message.startsWith("Iniciá sesión") ? "Iniciá sesión o escaneá el QR y reintentá" : "No se pudo validar · Reintentar"; button.title = message; }} }};
    document.body.appendChild(button);
  }};
  const observe = () => {{
    new MutationObserver(add).observe(document.documentElement || document, {{ childList: true, subtree: true }});
    add();
  }};
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", observe, {{ once: true }});
  else observe();
  const retry = setInterval(add, 250);
  setTimeout(() => clearInterval(retry), 10000);
}})();
"##
    )
}

fn rockstar_bridge_script(extra: &str) -> String {
    let fingerprint = serde_json::from_str::<Value>(extra).unwrap_or_else(|_| json!({}));
    let fingerprint = serde_json::to_string(&fingerprint).unwrap_or_else(|_| "{}".into());
    format!(
        r##"
(() => {{
  if (window.__droiddeckRockstarBridge) return;
  window.__droiddeckRockstarBridge = true;
  const fp = {fingerprint};
  if (location.hostname === "rgl.rockstargames.com" && location.pathname === "/api/connect/gateway") {{
    let captured = false;
    const captureGateway = () => {{
      if (captured) return;
      try {{
        const gateway = JSON.parse(document.body?.innerText?.trim() || "null");
        if (!gateway || typeof gateway !== "object") return;
        captured = true;
        window.ipc.postMessage(JSON.stringify({{
          store: "rockstar", url: "https://signin.rockstargames.com/droiddeck/auth-code?code=webview",
          body: "", storage: "", data: {{ cookies: document.cookie, fingerprint: fp, gateway }}
        }}));
      }} catch (_) {{}}
    }};
    setInterval(captureGateway, 100);
    return;
  }}
  if (location.hostname !== "signin.rockstargames.com") return;
  const answers = {{
    GetWindowConfig: {{ serviceInfo: {{ rosPlatform: "pc" }}, platformInfo: {{}} }},
    GetDeviceId: {{ fingerprintKvp: fp }},
    GetVersionInfo: {{ isDebugSdk: false }},
    GetProfileList: {{ profiles: [] }},
    GetCommandLineArguments: {{ arguments: [] }}
  }};
  let seq = 0;
  const later = (cb, value) => setTimeout(() => {{ try {{ cb?.(value); }} catch (_) {{}} }}, 0);
  const post = (code, cancel = false, gateway = null) => window.ipc.postMessage(JSON.stringify({{
    store: "rockstar",
    url: "https://signin.rockstargames.com/droiddeck/auth-code?" + (cancel ? "cancel=1" : "code=" + encodeURIComponent(code || "")),
    body: "", storage: "", data: {{ cookies: document.cookie, fingerprint: fp, gateway }}
  }}));
  const finish = async (code) => {{
    const endpoint = new URL("https://rgl.rockstargames.com/api/connect/gateway");
    endpoint.searchParams.set("code", code);
    endpoint.searchParams.set("fingerprint", JSON.stringify({{ fp }}));
    try {{
      const response = await fetch(endpoint, {{ credentials: "include", headers: {{ Accept: "application/json", "X-Requested-With": "XMLHttpRequest" }} }});
      const gateway = await response.json();
      if (response.ok && gateway?.ScAuthToken) {{ post("webview", false, gateway); return; }}
    }} catch (_) {{}}
    location.replace(endpoint.href);
  }};
  window.rgscQueryCancel = () => {{}};
  window.rgscQuery = (q) => {{
    q = q || {{}}; const p = q.params || {{}};
    if (q.method === "CallAuthResult" && p.authCode) {{ later(q.onSuccess, {{}}); finish(p.authCode); }}
    else if ((q.method === "CallAuthResult" && p.result === 3) || (q.method === "CallSetWindowState" && p.state === 3)) {{ later(q.onSuccess, {{}}); post("", true); }}
    else if (q.method === "CallLaunchExternalBrowser" && p.url) {{ window.open(p.url, "_self"); later(q.onSuccess, {{}}); }}
    else later(q.onSuccess, Object.prototype.hasOwnProperty.call(answers, q.method) ? answers[q.method] : {{}});
    return ++seq;
  }};
  window.rgscAddSubscription = (s) => {{
    s = s || {{}};
    if (s.method === "OnStartAuth") later(s.onSuccess, {{ launchPlatform: 0, titleLaunchInfo: {{ activeTitleName: "launcher", externalPlatformInfo: {{}} }}, profile: null, errorMessage: null }});
    return ++seq;
  }};
  window.open = (url) => {{ if (url) location.href = url; return window; }};
  const form = "/signin/user-form?cid=launcher&lang=en-US";
  fetch(form, {{ credentials: "include" }}).then(r => r.text()).then(html => {{
    history.replaceState(null, "", form); document.open(); document.write(html); document.close();
    const css = document.createElement("style"); css.textContent = '[data-ui-name="launcherSettings"]{{display:none!important}}';
    (document.head || document.documentElement).appendChild(css);
  }});
}})();
"##
    )
}

#[cfg(test)]
mod tests {
    use super::{bridge_script, callback_for, store_ipc_origin_allowed};

    #[test]
    fn steam_bridge_mounts_after_dom_ready_and_explains_qr_login() {
        let script = bridge_script("steam", "");

        assert!(script.contains("DOMContentLoaded"));
        assert!(script.contains("document.documentElement || document"));
        assert!(script.contains("droiddeck-store-connect"));
        assert!(script.contains("Sincronizar biblioteca con DroidDeck"));
        assert!(script.contains("escaneá el QR de la página"));
    }

    #[test]
    fn amazon_callback_requires_amazon_https_and_a_nonempty_code() {
        assert!(callback_for(
            "amazon",
            "https://www.amazon.com/?openid.oa2.authorization_code=abc"
        ));
        assert!(!callback_for(
            "amazon",
            "http://www.amazon.com/?openid.oa2.authorization_code=abc"
        ));
        assert!(!callback_for(
            "amazon",
            "https://attacker.example/?next=https://www.amazon.com/?openid.oa2.authorization_code=abc"
        ));
        assert!(!callback_for(
            "amazon",
            "https://www.amazon.com/?openid.oa2.authorization_code="
        ));
    }

    #[test]
    fn store_ipc_accepts_only_the_expected_https_origin() {
        assert!(store_ipc_origin_allowed(
            "steam",
            "https://store.steampowered.com/login/"
        ));
        assert!(!store_ipc_origin_allowed(
            "steam",
            "https://store.steampowered.com.attacker.example/"
        ));
        assert!(!store_ipc_origin_allowed(
            "steam",
            "http://store.steampowered.com/"
        ));
        assert!(!store_ipc_origin_allowed(
            "epic",
            "https://store.steampowered.com/"
        ));
    }

    #[test]
    fn amazon_login_has_no_manual_capture_button() {
        assert!(bridge_script("amazon", "").contains("if (store === \"amazon\") return;"));
    }
}
