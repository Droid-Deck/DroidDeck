#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod accounts;
mod model;
mod scanner;
mod store_api;
#[cfg(windows)]
mod store_login;

mod stores_jni {
    pub const VERSION: &str = "0.7.11";
}

use eframe::egui::{
    self, Align, Color32, FontId, Frame, Layout, Margin, RichText, Sense, Stroke, TextureHandle,
    TextureOptions, UiBuilder, Vec2,
};
use model::{Game, normalize_title};
use std::process::Command;
use std::{
    collections::{HashMap, HashSet},
    fs,
    path::PathBuf,
    sync::mpsc::{self, Receiver, Sender},
    time::Duration,
};
use threadpool::ThreadPool;

const BG: Color32 = Color32::from_rgb(9, 13, 19);
const PANEL: Color32 = Color32::from_rgb(15, 21, 30);
const CARD: Color32 = Color32::from_rgb(18, 25, 35);
const LINE: Color32 = Color32::from_rgb(36, 48, 63);
const MUTED: Color32 = Color32::from_rgb(145, 163, 183);
const BLUE: Color32 = Color32::from_rgb(38, 164, 245);
const CARD_W: f32 = 166.0;
const CARD_H: f32 = 326.0;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() >= 3 && args[1] == "--connect-store" {
        let result = store_login::run(&args[2]);
        if let Err(error) = result {
            println!("{}", serde_json::json!({"error": error}));
            std::process::exit(1);
        }
        return;
    }
    if let Err(error) = run_launcher() {
        eprintln!("DroidDeck Launcher could not start: {error}");
    }
}

fn run_launcher() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("DroidDeck Launcher")
            .with_inner_size([1420.0, 940.0])
            .with_min_inner_size([980.0, 680.0]),
        ..Default::default()
    };
    eframe::run_native(
        "DroidDeck Launcher",
        options,
        Box::new(|cc| {
            let mut launcher = Launcher::new(cc);
            launcher.sync_noninteractive_connected();
            Ok(Box::new(launcher))
        }),
    )
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum LibraryFilter {
    All,
    Installed,
    Uninstalled,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Page {
    Library,
    Stores,
}

struct Paths {
    db: PathBuf,
    cache: PathBuf,
}

impl Paths {
    fn new() -> Self {
        let base = dirs::data_local_dir()
            .unwrap_or_else(std::env::temp_dir)
            .join("DroidDeckLauncher");
        let _ = fs::create_dir_all(&base);
        let cache = base.join("covers");
        let _ = fs::create_dir_all(&cache);
        Self {
            db: base.join("library.json"),
            cache,
        }
    }
}

struct Launcher {
    games: Vec<Game>,
    query: String,
    filter: LibraryFilter,
    store_filter: String,
    paths: Paths,
    textures: HashMap<String, TextureHandle>,
    requested_covers: HashSet<String>,
    cover_tx: Sender<(String, Option<egui::ColorImage>)>,
    cover_rx: Receiver<(String, Option<egui::ColorImage>)>,
    workers: ThreadPool,
    toast: Option<(String, f64)>,
    notice: Option<String>,
    page: Page,
    accounts: Vec<accounts::AccountStatus>,
    account_tx: Sender<(String, Result<store_api::ConnectedStore, String>)>,
    account_rx: Receiver<(String, Result<store_api::ConnectedStore, String>)>,
    account_busy: HashSet<String>,
    itch_key: String,
}

impl Launcher {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        set_theme(&cc.egui_ctx);
        let paths = Paths::new();
        let games =
            scanner::merge_local(read_database(&paths.db).unwrap_or_else(load_legacy_database));
        let (cover_tx, cover_rx) = mpsc::channel();
        let (account_tx, account_rx) = mpsc::channel();
        Self {
            games,
            query: String::new(),
            filter: LibraryFilter::All,
            store_filter: "all".to_owned(),
            paths,
            textures: HashMap::new(),
            requested_covers: HashSet::new(),
            cover_tx,
            cover_rx,
            workers: ThreadPool::new(4),
            toast: None,
            notice: None,
            page: Page::Library,
            accounts: accounts::list(),
            account_tx,
            account_rx,
            account_busy: HashSet::new(),
            itch_key: String::new(),
        }
    }

    fn refresh(&mut self) {
        let saved = read_database(&self.paths.db).unwrap_or_else(load_legacy_database);
        self.games = scanner::merge_local(saved);
        self.sync_connected();
        self.notice = Some("Buscando cambios en las tiendas conectadas…".into());
        self.persist();
    }

    fn connect_store(&mut self, store: &str) {
        if self.account_busy.contains(store) {
            return;
        }
        let ready = matches!(
            store,
            "steam"
                | "epic"
                | "ea"
                | "gog"
                | "ubisoft"
                | "amazon"
                | "rockstar"
                | "itch"
                | "gamejolt"
        );
        if !ready {
            self.notice = Some(format!(
                "El acceso de cuenta de {} todavía no está implementado en Windows.",
                model::store_label(store)
            ));
            return;
        }
        self.account_busy.insert(store.to_owned());
        let tx = self.account_tx.clone();
        if store == "itch" {
            let key = self.itch_key.trim().to_owned();
            if key.is_empty() {
                self.account_busy.remove(store);
                self.notice = Some("Pegá primero tu clave API de itch.io.".into());
                return;
            }
            self.workers.execute(move || {
                let capture = store_api::LoginCapture {
                    store: "itch".into(),
                    ..Default::default()
                };
                let result = store_api::connect(capture, &key);
                let _ = tx.send(("itch".into(), result));
            });
        } else {
            let executable = std::env::current_exe();
            let store_id = store.to_owned();
            self.workers.execute(move || {
                let result = executable
                    .map_err(|e| format!("No se encontró el ejecutable: {e}"))
                    .and_then(|exe| {
                        Command::new(exe)
                            .arg("--connect-store")
                            .arg(&store_id)
                            .output()
                            .map_err(|e| format!("No se pudo abrir el login: {e}"))
                    })
                    .and_then(|output| {
                        if !output.status.success() {
                            let value: serde_json::Value =
                                serde_json::from_slice(&output.stdout).unwrap_or_default();
                            return Err(value
                                .get("error")
                                .and_then(serde_json::Value::as_str)
                                .map(str::to_owned)
                                .unwrap_or_else(|| {
                                    String::from_utf8_lossy(&output.stderr).trim().to_owned()
                                }));
                        }
                        serde_json::from_slice(&output.stdout)
                            .map_err(|e| format!("La tienda devolvió una respuesta ilegible: {e}"))
                    });
                let _ = tx.send((store_id, result));
            });
        }
        self.notice = Some(format!("Conectando {}…", model::store_label(store)));
    }

    fn sync_store(&mut self, store: &str) {
        if self.account_busy.contains(store) {
            return;
        }
        if matches!(store, "steam" | "gamejolt") {
            self.connect_store(store);
            return;
        }
        let saved = match accounts::load(store) {
            Ok(Some(saved)) => saved,
            Ok(None) => {
                self.notice = Some(format!("Conectá {} primero.", model::store_label(store)));
                return;
            }
            Err(error) => {
                self.notice = Some(error);
                return;
            }
        };
        self.account_busy.insert(store.to_owned());
        let tx = self.account_tx.clone();
        let store_id = store.to_owned();
        self.workers.execute(move || {
            let result = store_api::sync(&store_id, &saved);
            let _ = tx.send((store_id, result));
        });
        self.notice = Some(format!("Sincronizando {}…", model::store_label(store)));
    }

    fn sync_connected(&mut self) {
        let stores: Vec<String> = self
            .accounts
            .iter()
            .filter(|account| account.connected)
            .map(|account| account.store.clone())
            .collect();
        for store in stores {
            self.sync_store(&store);
        }
    }

    fn sync_noninteractive_connected(&mut self) {
        let stores: Vec<String> = self
            .accounts
            .iter()
            .filter(|account| {
                account.connected && !matches!(account.store.as_str(), "steam" | "gamejolt")
            })
            .map(|account| account.store.clone())
            .collect();
        for store in stores {
            self.sync_store(&store);
        }
    }

    fn disconnect_store(&mut self, store: &str) {
        match accounts::remove(store) {
            Ok(()) => {
                self.notice = None;
                self.games.retain(|game| {
                    !(game.store == store && game.imported_from == "connected-store")
                });
                self.games =
                    scanner::merge_local(model::merge_games(std::mem::take(&mut self.games)));
                self.accounts = accounts::list();
                self.persist();
                #[cfg(windows)]
                if let Err(error) = store_login::clear_webview_profile(store) {
                    self.notice = Some(error);
                }
                if self.notice.is_none() {
                    self.notice = Some(format!("Se desconectó {}.", model::store_label(store)));
                }
            }
            Err(error) => self.notice = Some(error),
        }
    }

    fn poll_accounts(&mut self, ctx: &egui::Context) {
        while let Ok((store, result)) = self.account_rx.try_recv() {
            self.account_busy.remove(&store);
            match result {
                Ok(connected) => {
                    let count = connected.games.len();
                    let display = connected.account.display_name.clone();
                    let save_result = accounts::save(&connected.account);
                    self.games.retain(|game| {
                        !(game.store == store && game.imported_from == "connected-store")
                    });
                    self.games.extend(connected.games);
                    self.games =
                        scanner::merge_local(model::merge_games(std::mem::take(&mut self.games)));
                    self.accounts = accounts::list();
                    self.persist();
                    self.notice = Some(match save_result {
                        Ok(()) if display.is_empty() => format!(
                            "{}: {count} juegos sincronizados.",
                            model::store_label(&store)
                        ),
                        Ok(()) => format!(
                            "{} conectado como {display}: {count} juegos sincronizados.",
                            model::store_label(&store)
                        ),
                        Err(error) => format!(
                            "Se leyó la biblioteca ({count} juegos), pero no se pudo guardar la cuenta: {error}"
                        ),
                    });
                }
                Err(error) => self.notice = Some(error),
            }
        }
        if !self.account_busy.is_empty() {
            ctx.request_repaint_after(Duration::from_millis(120));
        }
    }

    fn persist(&self) {
        let _ = fs::create_dir_all(self.paths.db.parent().unwrap_or(&self.paths.db));
        let temp = self.paths.db.with_extension("json.tmp");
        if let Ok(contents) = serde_json::to_vec_pretty(&model::Catalog {
            schema_version: 1,
            games: self.games.clone(),
        }) {
            if fs::write(&temp, contents).is_ok() {
                let _ = fs::rename(temp, &self.paths.db);
            }
        }
    }

    fn add_game(&mut self) {
        let Some(path) = rfd::FileDialog::new()
            .set_title("Elegir juego")
            .add_filter(
                "Aplicación o acceso directo",
                &["exe", "lnk", "url", "bat", "cmd"],
            )
            .pick_file()
        else {
            return;
        };
        let mut game = Game::manual(
            path.file_stem().and_then(|v| v.to_str()).unwrap_or("Juego"),
            path.to_string_lossy().as_ref(),
        );
        game.installed = true;
        game.normalize();
        self.games.retain(|existing| existing.key != game.key);
        self.games.push(game);
        self.games
            .sort_by(|a, b| a.title.to_lowercase().cmp(&b.title.to_lowercase()));
        self.persist();
        self.notice = Some("Juego agregado".into());
    }

    fn visible_games(&self) -> Vec<Game> {
        let query = normalize_title(&self.query);
        let mut games: Vec<_> = self
            .games
            .iter()
            .filter(|game| {
                let status_ok = match self.filter {
                    LibraryFilter::All => true,
                    LibraryFilter::Installed => game.installed,
                    LibraryFilter::Uninstalled => !game.installed,
                };
                let store_ok = self.store_filter == "all" || self.store_filter == game.store;
                let query_ok = query.is_empty()
                    || normalize_title(&game.title).contains(&query)
                    || normalize_title(&game.store_name).contains(&query);
                status_ok && store_ok && query_ok
            })
            .cloned()
            .collect();
        games.sort_by(|a, b| {
            a.installed
                .cmp(&b.installed)
                .then_with(|| a.title.to_lowercase().cmp(&b.title.to_lowercase()))
        });
        games
    }

    fn queue_cover(&mut self, game: &Game, ctx: &egui::Context) {
        if game.cover_url.is_empty()
            || self.textures.contains_key(&game.key)
            || !self.requested_covers.insert(game.key.clone())
        {
            return;
        }
        let key = game.key.clone();
        let url = game.cover_url.clone();
        let cache = self
            .paths
            .cache
            .join(format!("{}.img", safe_cache_name(&key)));
        let tx = self.cover_tx.clone();
        let ctx = ctx.clone();
        self.workers.execute(move || {
            let bytes = fs::read(&cache).ok().or_else(|| {
                let response = reqwest::blocking::Client::builder()
                    .timeout(Duration::from_secs(12))
                    .build()
                    .ok()?
                    .get(url)
                    .header("User-Agent", "DroidDeckLauncher/0.2")
                    .send()
                    .ok()?
                    .error_for_status()
                    .ok()?;
                let data = response.bytes().ok()?.to_vec();
                let _ = fs::write(cache, &data);
                Some(data)
            });
            let image = bytes
                .and_then(|data| image::load_from_memory(&data).ok())
                .map(|decoded| {
                    let rgba = decoded.to_rgba8();
                    egui::ColorImage::from_rgba_unmultiplied(
                        [rgba.width() as usize, rgba.height() as usize],
                        rgba.as_raw(),
                    )
                });
            let _ = tx.send((key, image));
            ctx.request_repaint();
        });
    }

    fn poll_covers(&mut self, ctx: &egui::Context) {
        for (key, image) in self.cover_rx.try_iter() {
            if let Some(image) = image {
                self.textures.insert(
                    key.clone(),
                    ctx.load_texture(key, image, TextureOptions::LINEAR),
                );
                ctx.request_repaint();
            }
        }
    }

    fn launch(&mut self, game: &Game) {
        let target = if game.store == "steam" && !game.store_id.is_empty() {
            format!(
                "steam://{}/{}",
                if game.installed { "run" } else { "install" },
                game.store_id
            )
        } else if game.installed && !game.local_target.is_empty() {
            game.local_target.clone()
        } else if game.installed && !game.launch_target.is_empty() {
            game.launch_target.clone()
        } else if !game.store_url.is_empty() {
            game.store_url.clone()
        } else {
            format!(
                "https://www.google.com/search?q={}",
                game.title.replace(' ', "+")
            )
        };
        let result = open::that(&target);
        self.notice = Some(match result {
            Ok(()) if game.installed => format!("Iniciando {}", game.title),
            Ok(()) => format!("Abriendo instalación de {}", game.title),
            Err(_) => format!("No se pudo abrir {}", game.title),
        });
    }

    fn header(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            if self.page == Page::Library {
                ui.add_sized(
                    [320.0, 38.0],
                    egui::TextEdit::singleline(&mut self.query)
                        .hint_text("Buscar en tu biblioteca"),
                );
            } else {
                ui.label(
                    RichText::new("Conectá tus tiendas para sincronizar los juegos que compraste.")
                        .color(MUTED),
                );
            }
            ui.add_space((ui.available_width() - 450.0).max(12.0));
            if ui.button("Actualizar todas").clicked() {
                if self.page == Page::Library {
                    self.refresh();
                } else {
                    self.sync_connected();
                }
            }
            if ui.button("Cuentas").clicked() {
                self.page = Page::Stores;
            }
            if self.page == Page::Library
                && ui
                    .button(
                        RichText::new("+  Agregar juego")
                            .strong()
                            .color(Color32::WHITE),
                    )
                    .clicked()
            {
                self.add_game();
            }
        });
    }

    fn accounts_panel(&mut self, ui: &mut egui::Ui) {
        ui.label(
            RichText::new("TIENDAS COMPATIBLES")
                .size(10.0)
                .strong()
                .color(BLUE),
        );
        ui.add_space(4.0);
        ui.heading(RichText::new("Tus cuentas").size(27.0).strong());
        ui.add_space(4.0);
        ui.label(RichText::new("Conectá cada tienda desde su ventana web. Steam muestra el QR en el formulario cuando no hay una sesión guardada; si ya ves tu usuario, pulsá Sincronizar biblioteca con DroidDeck.").color(MUTED));
        ui.add_space(18.0);
        let rows = accounts::stores().to_vec();
        egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
            let card_width = ((ui.available_width() - 18.0) / 2.0).max(250.0);
            for row in rows.chunks(2) {
                ui.horizontal(|ui| {
                    for (id, label, description) in row {
                        ui.add_space(6.0);
                        self.account_card(ui, id, label, description, card_width);
                    }
                });
                ui.add_space(10.0);
            }
            ui.add_space(10.0);
            ui.label(RichText::new("Steam, Epic, EA, GOG, Ubisoft, Amazon, Rockstar y Game Jolt sincronizan bibliotecas de cuenta; itch.io usa su clave API. Xbox todavía depende del helper Linux de DroidDeck y Battle.net solo expone los juegos instalados en la app Android, por eso esas dos integraciones no figuran como conexión completa en Windows.").size(11.0).color(MUTED));
        });
    }

    fn account_card(
        &mut self,
        ui: &mut egui::Ui,
        id: &str,
        label: &str,
        description: &str,
        card_width: f32,
    ) {
        let connected = self
            .accounts
            .iter()
            .find(|account| account.store == id)
            .cloned()
            .unwrap_or_default();
        let ready = matches!(
            id,
            "steam"
                | "epic"
                | "ea"
                | "gog"
                | "ubisoft"
                | "amazon"
                | "rockstar"
                | "itch"
                | "gamejolt"
        );
        let busy = self.account_busy.contains(id);
        Frame::new()
            .fill(CARD)
            .stroke(Stroke::new(1.0, LINE))
            .corner_radius(12.0)
            .inner_margin(Margin::same(14))
            .show(ui, |ui| {
                ui.set_min_width((card_width - 28.0).max(180.0));
                ui.set_max_width((card_width - 28.0).max(180.0));
                ui.horizontal(|ui| {
                    let store = model::STORES.iter().find(|store| store.id == id);
                    let color = store.map(|store| store.color).unwrap_or(BLUE);
                    let (dot, _) = ui.allocate_exact_size(Vec2::splat(12.0), Sense::hover());
                    ui.painter().circle_filled(dot.center(), 5.0, color);
                    ui.label(RichText::new(label).strong().size(16.0));
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        ui.label(
                            RichText::new(if busy {
                                "CONECTANDO"
                            } else if connected.connected {
                                "CONECTADA"
                            } else if ready {
                                "DISPONIBLE"
                            } else {
                                "EN DESARROLLO"
                            })
                            .size(9.0)
                            .strong()
                            .color(if connected.connected {
                                Color32::from_rgb(113, 203, 149)
                            } else if ready {
                                BLUE
                            } else {
                                MUTED
                            }),
                        );
                    });
                });
                ui.add_space(4.0);
                ui.label(RichText::new(description).size(11.0).color(MUTED));
                if connected.connected {
                    ui.add_space(7.0);
                    ui.label(
                        RichText::new(format!("Sesión: {}", connected.display_name))
                            .size(11.0)
                            .color(Color32::from_rgb(113, 203, 149)),
                    );
                }
                if id == "itch" && !connected.connected {
                    ui.add_space(7.0);
                    ui.add(
                        egui::TextEdit::singleline(&mut self.itch_key)
                            .password(true)
                            .hint_text("Clave API de itch.io")
                            .desired_width(ui.available_width()),
                    );
                }
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    if connected.connected {
                        if ui
                            .add_enabled(
                                !busy,
                                egui::Button::new("Sincronizar")
                                    .fill(BLUE)
                                    .corner_radius(7.0),
                            )
                            .clicked()
                        {
                            self.sync_store(id);
                        }
                        if ui
                            .add_enabled(!busy, egui::Button::new("Desconectar").corner_radius(7.0))
                            .clicked()
                        {
                            self.disconnect_store(id);
                        }
                    } else if ready {
                        if ui
                            .add_enabled(
                                !busy,
                                egui::Button::new(if busy {
                                    "Conectando…"
                                } else {
                                    "Conectar cuenta"
                                })
                                .fill(BLUE)
                                .corner_radius(7.0),
                            )
                            .clicked()
                        {
                            self.connect_store(id);
                        }
                    } else {
                        ui.label(
                            RichText::new(
                                "El login y la biblioteca completa no están listos en Windows.",
                            )
                            .size(10.0)
                            .color(MUTED),
                        );
                    }
                });
            });
    }

    fn draw_card(&mut self, ui: &mut egui::Ui, game: &Game) {
        let ctx = ui.ctx().clone();
        self.queue_cover(game, &ctx);
        let (rect, _) = ui.allocate_exact_size(Vec2::new(CARD_W, CARD_H), Sense::hover());
        ui.painter().rect_filled(rect, 12.0, CARD);
        ui.painter()
            .rect_stroke(rect, 12.0, Stroke::new(1.0, LINE), egui::StrokeKind::Inside);
        ui.scope_builder(
            UiBuilder::new()
                .max_rect(rect.shrink(1.0))
                .layout(Layout::top_down(Align::Min)),
            |ui| {
                ui.spacing_mut().item_spacing = Vec2::ZERO;
                let cover_size = Vec2::new(CARD_W - 2.0, 204.0);
                let (cover, _) = ui.allocate_exact_size(cover_size, Sense::hover());
                ui.painter().rect_filled(
                    cover,
                    egui::CornerRadius {
                        nw: 11,
                        ne: 11,
                        sw: 0,
                        se: 0,
                    },
                    Color32::from_rgb(22, 33, 47),
                );
                if let Some(texture) = self.textures.get(&game.key) {
                    ui.painter().image(
                        texture.id(),
                        cover,
                        egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)),
                        Color32::WHITE,
                    );
                    ui.painter().rect_filled(
                        cover,
                        egui::CornerRadius {
                            nw: 11,
                            ne: 11,
                            sw: 0,
                            se: 0,
                        },
                        Color32::from_rgba_unmultiplied(2, 7, 13, 48),
                    );
                } else {
                    ui.painter().text(
                        cover.center(),
                        egui::Align2::CENTER_CENTER,
                        game.title.chars().next().unwrap_or('D').to_string(),
                        FontId::proportional(62.0),
                        Color32::from_rgb(60, 104, 144),
                    );
                }
                let badge = egui::Rect::from_min_size(
                    cover.left_top() + Vec2::splat(9.0),
                    Vec2::new(80.0, 25.0),
                );
                ui.painter().rect_filled(
                    badge,
                    7.0,
                    Color32::from_rgba_unmultiplied(8, 13, 20, 224),
                );
                ui.painter().text(
                    badge.center(),
                    egui::Align2::CENTER_CENTER,
                    game.store_name.as_str(),
                    FontId::proportional(11.0),
                    game.store_color(),
                );
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    ui.add_space(10.0);
                    ui.add_sized(
                        [CARD_W - 22.0, 40.0],
                        egui::Label::new(RichText::new(&game.title).strong().size(14.0)).truncate(),
                    );
                });
                ui.add_space(2.0);
                let status = if game.installed {
                    "Instalado en este equipo"
                } else if game.owned {
                    "En tu biblioteca · No instalado"
                } else {
                    "Detectado localmente"
                };
                ui.horizontal(|ui| {
                    ui.add_space(10.0);
                    ui.add_sized(
                        [CARD_W - 22.0, 14.0],
                        egui::Label::new(RichText::new(status).size(10.0).color(
                            if game.installed {
                                Color32::from_rgb(113, 203, 149)
                            } else {
                                MUTED
                            },
                        ))
                        .truncate(),
                    );
                });
                ui.add_space(10.0);
                ui.horizontal(|ui| {
                    ui.add_space(9.0);
                    let label = if game.installed { "Jugar" } else { "Instalar" };
                    if ui
                        .add_sized(
                            [CARD_W - 20.0, 34.0],
                            egui::Button::new(RichText::new(label).strong().size(12.0))
                                .fill(BLUE)
                                .corner_radius(8.0),
                        )
                        .clicked()
                    {
                        self.launch(game);
                    }
                });
            },
        );
    }

    fn draw_grid(&mut self, ui: &mut egui::Ui, games: &[Game]) {
        let columns = ((ui.available_width() + 10.0) / (CARD_W + 10.0))
            .floor()
            .max(1.0) as usize;
        for row in games.chunks(columns) {
            ui.with_layout(Layout::left_to_right(Align::Min), |ui| {
                ui.spacing_mut().item_spacing.x = 0.0;
                for (index, game) in row.iter().enumerate() {
                    if index > 0 {
                        ui.add_space(10.0);
                    }
                    self.draw_card(ui, game);
                }
            });
            ui.add_space(14.0);
        }
    }

    fn sidebar(&mut self, ui: &mut egui::Ui) {
        let all = self.games.len();
        let installed = self.games.iter().filter(|game| game.installed).count();
        let uninstalled = all - installed;
        egui::Panel::left("navigation")
            .exact_size(224.0)
            .resizable(false)
            .frame(
                Frame::new()
                    .fill(PANEL)
                    .inner_margin(Margin::same(16))
                    .stroke(Stroke::new(1.0, LINE)),
            )
            .show(ui, |ui| {
                ui.add_space(12.0);
                ui.horizontal(|ui| {
                    Frame::new()
                        .fill(BLUE)
                        .corner_radius(8.0)
                        .inner_margin(Margin::symmetric(8, 4))
                        .show(ui, |ui| {
                            ui.label(RichText::new("D").strong().size(19.0).color(Color32::WHITE));
                        });
                    ui.label(
                        RichText::new("DroidDeck")
                            .strong()
                            .size(21.0)
                            .color(Color32::WHITE),
                    );
                });
                ui.add_space(38.0);
                ui.label(RichText::new("BIBLIOTECA").size(10.0).strong().color(MUTED));
                ui.add_space(8.0);
                self.nav_item(ui, "Todos", all, LibraryFilter::All);
                self.nav_item(ui, "Instalados", installed, LibraryFilter::Installed);
                self.nav_item(ui, "No instalados", uninstalled, LibraryFilter::Uninstalled);
                let connected = self
                    .accounts
                    .iter()
                    .filter(|account| account.connected)
                    .count();
                let selected = self.page == Page::Stores;
                let response = ui.add(
                    egui::Button::new(
                        RichText::new(format!("Cuentas de tiendas    {connected}/11"))
                            .size(12.0)
                            .color(if selected { Color32::WHITE } else { MUTED }),
                    )
                    .fill(if selected {
                        Color32::from_rgb(16, 48, 70)
                    } else {
                        Color32::TRANSPARENT
                    })
                    .stroke(if selected {
                        Stroke::new(1.0, Color32::from_rgb(29, 111, 162))
                    } else {
                        Stroke::NONE
                    })
                    .corner_radius(8.0)
                    .min_size(Vec2::new(ui.available_width(), 34.0)),
                );
                if response.clicked() {
                    self.page = Page::Stores;
                }
                ui.add_space(28.0);
                ui.label(RichText::new("TIENDAS").size(10.0).strong().color(MUTED));
                ui.add_space(8.0);
                let stores = model::available_stores(&self.games);
                self.store_nav(ui, "all", "Todas las tiendas", all);
                for (id, label, count) in stores {
                    self.store_nav(ui, &id, &label, count);
                }
                ui.with_layout(Layout::bottom_up(Align::Min), |ui| {
                    ui.separator();
                    ui.label(
                        RichText::new("Launcher para Windows")
                            .size(12.0)
                            .color(Color32::from_rgb(119, 204, 147)),
                    );
                    ui.label(
                        RichText::new("   Solo juegos · Sin emulación")
                            .size(10.0)
                            .color(MUTED),
                    );
                });
            });
    }

    fn nav_item(&mut self, ui: &mut egui::Ui, label: &str, count: usize, filter: LibraryFilter) {
        let selected = self.filter == filter && self.store_filter == "all";
        let frame = if selected {
            Frame::new()
                .fill(Color32::from_rgb(16, 48, 70))
                .stroke(Stroke::new(1.0, Color32::from_rgb(29, 111, 162)))
                .corner_radius(9.0)
                .inner_margin(Margin::symmetric(10, 9))
        } else {
            Frame::new().inner_margin(Margin::symmetric(10, 9))
        };
        let response = frame.show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                ui.label(RichText::new(label).color(if selected { Color32::WHITE } else { MUTED }));
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    ui.label(RichText::new(count.to_string()).size(11.0).color(MUTED));
                });
            });
        });
        if ui
            .interact(
                response.response.rect,
                ui.make_persistent_id(("library-nav", label)),
                Sense::click(),
            )
            .clicked()
        {
            self.filter = filter;
            self.store_filter = "all".into();
            self.page = Page::Library;
        }
        ui.add_space(3.0);
    }

    fn store_nav(&mut self, ui: &mut egui::Ui, id: &str, label: &str, count: usize) {
        let selected = self.store_filter == id;
        let response = ui.add(
            egui::Button::new(
                RichText::new(format!("{label}    {count}"))
                    .size(12.0)
                    .color(if selected { Color32::WHITE } else { MUTED }),
            )
            .fill(if selected {
                Color32::from_rgb(24, 36, 50)
            } else {
                Color32::TRANSPARENT
            })
            .stroke(Stroke::NONE)
            .corner_radius(8.0)
            .min_size(Vec2::new(ui.available_width(), 34.0)),
        );
        if response.clicked() {
            self.store_filter = id.to_owned();
            self.filter = LibraryFilter::All;
            self.page = Page::Library;
        }
    }
}

impl eframe::App for Launcher {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        self.poll_covers(&ctx);
        self.poll_accounts(&ctx);
        self.sidebar(ui);
        egui::CentralPanel::default().frame(Frame::new().fill(BG).inner_margin(Margin::same(22))).show(ui, |ui| {
            self.header(ui);
            if let Some(message) = self.notice.take() { self.toast = Some((message, ctx.input(|i| i.time) + 3.5)); }
            if let Some((message, until)) = &self.toast {
                    if ctx.input(|i| i.time) < *until {
                    Frame::new().fill(Color32::from_rgb(14, 42, 61)).stroke(Stroke::new(1.0, Color32::from_rgb(28, 96, 137))).corner_radius(8.0).inner_margin(Margin::symmetric(12, 8)).show(ui, |ui| { ui.label(RichText::new(message).color(Color32::WHITE)); });
                    ui.add_space(8.0);
                } else { self.toast = None; }
            }
            ui.separator();
            if self.page == Page::Stores {
                self.accounts_panel(ui);
            } else {
                ui.add_space(16.0);
                let installed = self.games.iter().filter(|game| game.installed).count();
                let uninstalled = self.games.len().saturating_sub(installed);
                ui.horizontal(|ui| {
                    ui.vertical(|ui| {
                        ui.label(RichText::new("TU COLECCIÓN, EN UN SOLO LUGAR").size(10.0).strong().color(BLUE));
                        ui.add_space(4.0);
                        ui.heading(RichText::new("Biblioteca").size(27.0).strong());
                    });
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        ui.label(RichText::new(format!("{} juegos   ·   {} instalados   ·   {} no instalados", self.games.len(), installed, uninstalled)).size(12.0).color(MUTED));
                    });
                });
                ui.add_space(14.0);
                let visible = self.visible_games();
                if visible.is_empty() {
                    ui.add_space(70.0);
                    ui.vertical_centered(|ui| {
                        ui.label(RichText::new(if self.games.is_empty() { "Conectá tus tiendas" } else { "No hay juegos que coincidan" }).size(24.0).strong());
                        ui.add_space(8.0);
                        ui.label(RichText::new(if self.games.is_empty() { "Iniciá sesión en Steam, Epic, EA, GOG, Ubisoft, Amazon, itch.io o Game Jolt para ver tu colección completa." } else { "Probá otro nombre o filtro." }).color(MUTED));
                        ui.add_space(18.0);
                        if self.games.is_empty() && ui.add(egui::Button::new("Conectar tiendas").fill(BLUE).corner_radius(8.0)).clicked() { self.page = Page::Stores; }
                    });
                } else {
                    let no_installed: Vec<_> = visible.iter().filter(|game| !game.installed).cloned().collect();
                    let installed_games: Vec<_> = visible.iter().filter(|game| game.installed).cloned().collect();
                    egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
                        if !no_installed.is_empty() {
                            section_header(ui, "NO INSTALADOS", no_installed.len(), Color32::from_rgb(153, 185, 210));
                            ui.add_space(9.0);
                            self.draw_grid(ui, &no_installed);
                            ui.add_space(22.0);
                        }
                        if !installed_games.is_empty() {
                            section_header(ui, "INSTALADOS", installed_games.len(), Color32::from_rgb(113, 203, 149));
                            ui.add_space(9.0);
                            self.draw_grid(ui, &installed_games);
                        }
                    });
                }
            }
        });
    }
}

fn section_header(ui: &mut egui::Ui, label: &str, count: usize, color: Color32) {
    ui.horizontal(|ui| {
        ui.label(RichText::new(label).size(11.0).strong().color(color));
        ui.label(RichText::new(format!("· {count}")).size(11.0).color(MUTED));
        ui.add_space(8.0);
        let (line, _) = ui.allocate_exact_size(
            Vec2::new(ui.available_width().max(8.0), 1.0),
            Sense::hover(),
        );
        ui.painter().line_segment(
            [line.left_center(), line.right_center()],
            Stroke::new(1.0, LINE),
        );
    });
}

fn set_theme(ctx: &egui::Context) {
    let mut visuals = egui::Visuals::dark();
    visuals.panel_fill = BG;
    visuals.window_fill = PANEL;
    visuals.extreme_bg_color = Color32::from_rgb(8, 12, 18);
    visuals.faint_bg_color = Color32::from_rgb(19, 27, 38);
    visuals.selection.bg_fill = Color32::from_rgb(18, 99, 150);
    visuals.widgets.noninteractive.bg_fill = Color32::from_rgb(15, 21, 30);
    visuals.widgets.inactive.bg_fill = Color32::from_rgb(19, 27, 37);
    visuals.widgets.hovered.bg_fill = Color32::from_rgb(28, 40, 54);
    visuals.widgets.active.bg_fill = Color32::from_rgb(25, 133, 198);
    visuals.widgets.noninteractive.fg_stroke.color = Color32::from_rgb(216, 228, 239);
    visuals.widgets.inactive.fg_stroke.color = Color32::from_rgb(194, 211, 229);
    visuals.widgets.hovered.fg_stroke.color = Color32::WHITE;
    visuals.widgets.active.fg_stroke.color = Color32::WHITE;
    ctx.set_visuals(visuals);
    ctx.all_styles_mut(|style| style.spacing.item_spacing = Vec2::new(8.0, 8.0));
}

fn read_database(path: &PathBuf) -> Option<Vec<Game>> {
    let data = fs::read_to_string(path).ok()?;
    let catalog: model::Catalog = serde_json::from_str(&data).ok()?;
    Some(catalog.games)
}

fn load_legacy_database() -> Vec<Game> {
    let Some(appdata) = std::env::var_os("APPDATA").map(PathBuf::from) else {
        return Vec::new();
    };
    let candidates = [
        appdata.join("droideck-windows-launcher/library.json"),
        appdata.join("DroidDeck Launcher/library.json"),
        appdata.join("DroidDeckLauncher/library.json"),
    ];
    candidates
        .iter()
        .find_map(read_database)
        .unwrap_or_default()
}

fn safe_cache_name(key: &str) -> String {
    key.chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .collect()
}
