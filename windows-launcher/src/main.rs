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
    collections::{HashMap, HashSet, VecDeque},
    fs,
    path::PathBuf,
    sync::{
        Arc,
        mpsc::{self, Receiver, Sender},
    },
    time::Duration,
};
use threadpool::ThreadPool;

const BG: Color32 = Color32::from_rgb(7, 11, 17);
const PANEL: Color32 = Color32::from_rgb(11, 16, 24);
const SURFACE: Color32 = Color32::from_rgb(15, 22, 32);
const CARD: Color32 = Color32::from_rgb(17, 25, 36);
const CARD_HOVER: Color32 = Color32::from_rgb(22, 32, 45);
const LINE: Color32 = Color32::from_rgb(35, 49, 66);
const MUTED: Color32 = Color32::from_rgb(143, 162, 183);
const BLUE: Color32 = Color32::from_rgb(26, 159, 255);
const BLUE_LIGHT: Color32 = Color32::from_rgb(87, 194, 255);
const GREEN: Color32 = Color32::from_rgb(113, 203, 149);
const CARD_W: f32 = 184.0;
const CARD_H: f32 = 326.0;
const MAX_CACHED_COVER_TEXTURES: usize = 72;

#[derive(Clone, Copy)]
enum UiIcon {
    Library,
    Download,
    Play,
    Search,
    Refresh,
    Add,
    Store,
    Accounts,
    Gamepad,
    Monitor,
    Grid,
    Check,
}

fn paint_icon(painter: &egui::Painter, rect: egui::Rect, icon: UiIcon, color: Color32) {
    let center = rect.center();
    let size = rect.width().min(rect.height());
    let stroke = Stroke::new((size * 0.09).clamp(1.4, 2.2), color);
    match icon {
        UiIcon::Grid => {
            let gap = size * 0.12;
            let cell = (size - gap) / 2.0;
            for y in 0..2 {
                for x in 0..2 {
                    let cell_center = egui::pos2(
                        rect.left() + cell / 2.0 + x as f32 * (cell + gap),
                        rect.top() + cell / 2.0 + y as f32 * (cell + gap),
                    );
                    painter.rect_stroke(
                        egui::Rect::from_center_size(cell_center, Vec2::splat(cell)),
                        2.5,
                        stroke,
                        egui::StrokeKind::Inside,
                    );
                }
            }
        }
        UiIcon::Library => {
            let book_w = size * 0.22;
            let book_h = size * 0.62;
            for x in [-0.27, 0.0, 0.27] {
                let book = egui::Rect::from_center_size(
                    center + Vec2::new(size * x, size * 0.02),
                    Vec2::new(book_w, book_h),
                );
                painter.rect_stroke(book, 2.0, stroke, egui::StrokeKind::Inside);
                painter.line_segment(
                    [
                        egui::pos2(book.left() + 3.0, book.top() + 4.0),
                        egui::pos2(book.left() + 3.0, book.bottom() - 4.0),
                    ],
                    stroke,
                );
            }
        }
        UiIcon::Download => {
            painter.line_segment(
                [
                    egui::pos2(center.x, rect.top() + size * 0.1),
                    egui::pos2(center.x, center.y + size * 0.12),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x - size * 0.18, center.y),
                    egui::pos2(center.x, center.y + size * 0.18),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x + size * 0.18, center.y),
                    egui::pos2(center.x, center.y + size * 0.18),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(rect.left() + size * 0.12, rect.bottom() - size * 0.18),
                    egui::pos2(rect.right() - size * 0.12, rect.bottom() - size * 0.18),
                ],
                stroke,
            );
        }
        UiIcon::Play => {
            painter.add(egui::Shape::convex_polygon(
                vec![
                    egui::pos2(rect.left() + size * 0.3, rect.top() + size * 0.18),
                    egui::pos2(rect.right() - size * 0.16, center.y),
                    egui::pos2(rect.left() + size * 0.3, rect.bottom() - size * 0.18),
                ],
                color,
                Stroke::NONE,
            ));
        }
        UiIcon::Search => {
            painter.circle_stroke(center - Vec2::splat(size * 0.06), size * 0.27, stroke);
            painter.line_segment(
                [
                    center + Vec2::splat(size * 0.16),
                    center + Vec2::splat(size * 0.39),
                ],
                stroke,
            );
        }
        UiIcon::Refresh => {
            painter.circle_stroke(center, size * 0.32, stroke);
            painter.line_segment(
                [
                    egui::pos2(center.x + size * 0.12, rect.top() + size * 0.12),
                    egui::pos2(rect.right() - size * 0.08, rect.top() + size * 0.12),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(rect.right() - size * 0.08, rect.top() + size * 0.12),
                    egui::pos2(rect.right() - size * 0.08, center.y + size * 0.02),
                ],
                stroke,
            );
        }
        UiIcon::Add => {
            painter.line_segment(
                [
                    egui::pos2(center.x, rect.top() + size * 0.16),
                    egui::pos2(center.x, rect.bottom() - size * 0.16),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(rect.left() + size * 0.16, center.y),
                    egui::pos2(rect.right() - size * 0.16, center.y),
                ],
                stroke,
            );
        }
        UiIcon::Store => {
            let body = egui::Rect::from_min_max(
                egui::pos2(rect.left() + size * 0.15, center.y - size * 0.03),
                egui::pos2(rect.right() - size * 0.15, rect.bottom() - size * 0.12),
            );
            painter.rect_stroke(body, 3.0, stroke, egui::StrokeKind::Inside);
            painter.line_segment(
                [
                    egui::pos2(body.left() - size * 0.03, center.y - size * 0.08),
                    egui::pos2(center.x, rect.top() + size * 0.12),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x, rect.top() + size * 0.12),
                    egui::pos2(body.right() + size * 0.03, center.y - size * 0.08),
                ],
                stroke,
            );
            painter.rect_stroke(
                egui::Rect::from_min_max(
                    egui::pos2(center.x - size * 0.1, center.y + size * 0.08),
                    egui::pos2(center.x + size * 0.1, body.bottom()),
                ),
                1.5,
                stroke,
                egui::StrokeKind::Inside,
            );
        }
        UiIcon::Accounts => {
            painter.circle_stroke(
                center - Vec2::new(size * 0.14, size * 0.16),
                size * 0.16,
                stroke,
            );
            painter.circle_stroke(
                center + Vec2::new(size * 0.18, -size * 0.1),
                size * 0.13,
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(rect.left() + size * 0.08, rect.bottom() - size * 0.17),
                    egui::pos2(center.x + size * 0.06, rect.bottom() - size * 0.17),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x + size * 0.1, rect.bottom() - size * 0.17),
                    egui::pos2(rect.right() - size * 0.06, rect.bottom() - size * 0.17),
                ],
                stroke,
            );
        }
        UiIcon::Gamepad => {
            let body = egui::Rect::from_center_size(center, Vec2::new(size * 0.78, size * 0.48));
            painter.rect_stroke(body, size * 0.2, stroke, egui::StrokeKind::Inside);
            painter.line_segment(
                [
                    egui::pos2(center.x - size * 0.22, center.y),
                    egui::pos2(center.x - size * 0.08, center.y),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x - size * 0.15, center.y - size * 0.07),
                    egui::pos2(center.x - size * 0.15, center.y + size * 0.07),
                ],
                stroke,
            );
            painter.circle_filled(
                center + Vec2::new(size * 0.18, -size * 0.04),
                size * 0.035,
                color,
            );
            painter.circle_filled(
                center + Vec2::new(size * 0.28, size * 0.06),
                size * 0.035,
                color,
            );
        }
        UiIcon::Monitor => {
            let screen = egui::Rect::from_min_max(
                egui::pos2(rect.left() + size * 0.1, rect.top() + size * 0.12),
                egui::pos2(rect.right() - size * 0.1, center.y + size * 0.1),
            );
            painter.rect_stroke(screen, 3.0, stroke, egui::StrokeKind::Inside);
            painter.line_segment(
                [
                    egui::pos2(center.x, screen.bottom()),
                    egui::pos2(center.x, rect.bottom() - size * 0.12),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x - size * 0.18, rect.bottom() - size * 0.12),
                    egui::pos2(center.x + size * 0.18, rect.bottom() - size * 0.12),
                ],
                stroke,
            );
        }
        UiIcon::Check => {
            painter.line_segment(
                [
                    center + Vec2::new(-size * 0.3, 0.0),
                    center + Vec2::new(-size * 0.08, size * 0.21),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    center + Vec2::new(-size * 0.08, size * 0.21),
                    center + Vec2::new(size * 0.32, -size * 0.23),
                ],
                stroke,
            );
        }
    }
}

fn icon_label_button(
    ui: &mut egui::Ui,
    label: &str,
    icon: UiIcon,
    width: f32,
    primary: bool,
    enabled: bool,
) -> egui::Response {
    let sense = if enabled {
        Sense::click()
    } else {
        Sense::hover()
    };
    let (rect, response) = ui.allocate_exact_size(Vec2::new(width, 40.0), sense);
    let fill = if !enabled {
        SURFACE
    } else if primary {
        if response.hovered() { BLUE_LIGHT } else { BLUE }
    } else if response.hovered() {
        CARD_HOVER
    } else {
        SURFACE
    };
    let text_color = if !enabled {
        MUTED
    } else if primary {
        BG
    } else {
        Color32::from_rgb(222, 232, 242)
    };
    ui.painter().rect_filled(rect, 10.0, fill);
    ui.painter().rect_stroke(
        rect,
        10.0,
        Stroke::new(1.0, if primary { BLUE_LIGHT } else { LINE }),
        egui::StrokeKind::Inside,
    );
    let icon_rect = egui::Rect::from_center_size(
        egui::pos2(rect.left() + 22.0, rect.center().y),
        Vec2::splat(17.0),
    );
    paint_icon(ui.painter(), icon_rect, icon, text_color);
    ui.painter().text(
        egui::pos2(rect.center().x + 7.0, rect.center().y),
        egui::Align2::CENTER_CENTER,
        label,
        FontId::proportional(13.0),
        text_color,
    );
    response
}

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
    let window_icon = image::load_from_memory(include_bytes!(
        "../../app/src/main/res/drawable-nodpi/logo.png"
    ))
    .ok()
    .map(|decoded| {
        let rgba = decoded.to_rgba8();
        let width = rgba.width();
        let height = rgba.height();
        Arc::new(egui::IconData {
            rgba: rgba.into_raw(),
            width,
            height,
        })
    });
    let mut viewport = egui::ViewportBuilder::default()
        .with_title("DroidDeck Launcher")
        .with_inner_size([1420.0, 940.0])
        .with_min_inner_size([980.0, 680.0]);
    if let Some(icon) = window_icon {
        viewport = viewport.with_icon(icon);
    }
    let options = eframe::NativeOptions {
        viewport,
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
    texture_order: VecDeque<String>,
    logo_texture: Option<TextureHandle>,
    requested_covers: HashSet<String>,
    cover_retry_after: HashMap<String, f64>,
    cover_tx: Sender<(String, Option<egui::ColorImage>)>,
    cover_rx: Receiver<(String, Option<egui::ColorImage>)>,
    workers: ThreadPool,
    cover_workers: ThreadPool,
    catalog_blocked: Option<String>,
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
        let logo_texture = image::load_from_memory(include_bytes!(
            "../../app/src/main/res/drawable-nodpi/logo.png"
        ))
        .ok()
        .map(|decoded| {
            let rgba = decoded.to_rgba8();
            cc.egui_ctx.load_texture(
                "droiddeck-logo",
                egui::ColorImage::from_rgba_unmultiplied(
                    [rgba.width() as usize, rgba.height() as usize],
                    rgba.as_raw(),
                ),
                TextureOptions::LINEAR,
            )
        });
        let paths = Paths::new();
        let (loaded, catalog_blocked) = match load_database(&paths.db) {
            Ok(games) => (Some(games), None),
            Err(error) => (None, Some(error)),
        };
        let scan = loaded
            .map(scanner::merge_local_with_report)
            .unwrap_or_default();
        let startup_notice = catalog_blocked
            .as_ref()
            .map(|error| format!("No se pudo cargar tu biblioteca. La sincronización automática está pausada: {error}"))
            .or_else(|| scan.issues.first().map(|issue| format!("La exploración local fue parcial: {issue}")));
        let (cover_tx, cover_rx) = mpsc::channel();
        let (account_tx, account_rx) = mpsc::channel();
        Self {
            games: scan.games,
            query: String::new(),
            filter: LibraryFilter::All,
            store_filter: "all".to_owned(),
            paths,
            textures: HashMap::new(),
            texture_order: VecDeque::new(),
            logo_texture,
            requested_covers: HashSet::new(),
            cover_retry_after: HashMap::new(),
            cover_tx,
            cover_rx,
            workers: ThreadPool::new(4),
            cover_workers: ThreadPool::new(4),
            catalog_blocked,
            toast: None,
            notice: startup_notice,
            page: Page::Library,
            accounts: accounts::list(),
            account_tx,
            account_rx,
            account_busy: HashSet::new(),
            itch_key: String::new(),
        }
    }

    fn refresh(&mut self) {
        self.cover_retry_after.clear();
        let saved = match load_database(&self.paths.db) {
            Ok(saved) => saved,
            Err(error) => {
                self.catalog_blocked = Some(error.clone());
                self.notice = Some(format!(
                    "No se pudo leer la biblioteca; no se importaron juegos: {error}"
                ));
                return;
            }
        };
        let scan = scanner::merge_local_with_report(saved);
        self.games = scan.games;
        self.catalog_blocked = None;
        if let Err(error) = self.persist() {
            self.catalog_blocked = Some(error.clone());
            self.notice = Some(format!(
                "No se pudo guardar la biblioteca; la sincronización está pausada: {error}"
            ));
            return;
        }
        self.sync_connected();
        self.notice = scan
            .issues
            .first()
            .map(|issue| format!("La exploración local fue parcial: {issue}"))
            .or_else(|| Some("Buscando cambios en las tiendas conectadas…".into()));
    }

    fn catalog_available(&mut self) -> bool {
        if let Some(error) = &self.catalog_blocked {
            self.notice = Some(format!(
                "La biblioteca no está disponible para cambios: {error}. Usá Actualizar para reintentar."
            ));
            false
        } else {
            true
        }
    }

    fn connect_store(&mut self, store: &str) {
        if !self.catalog_available() {
            return;
        }
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
        if !self.catalog_available() {
            return;
        }
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
        if !self.catalog_available() {
            return;
        }
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
        if !self.catalog_available() {
            return;
        }
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
                if let Err(error) = self.persist() {
                    self.catalog_blocked = Some(error.clone());
                    self.notice = Some(format!(
                        "Se desconectó la cuenta, pero no se pudo guardar la biblioteca: {error}"
                    ));
                }
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

    #[cfg(windows)]
    fn retry_webview_cleanup(&mut self, store: &str) {
        match store_login::clear_webview_profile(store) {
            Ok(()) => {
                self.notice = Some(format!(
                    "Se limpió la sesión web de {}.",
                    model::store_label(store)
                ))
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
                    if let Err(error) = accounts::save(&connected.account) {
                        self.notice = Some(format!(
                            "Se leyó la biblioteca, pero no se pudo guardar la cuenta: {error}"
                        ));
                        continue;
                    }
                    self.games.retain(|game| {
                        !(game.store == store && game.imported_from == "connected-store")
                    });
                    self.games.extend(connected.games);
                    self.games =
                        scanner::merge_local(model::merge_games(std::mem::take(&mut self.games)));
                    self.accounts = accounts::list();
                    let persisted = self.persist();
                    self.notice = Some(match persisted {
                        Ok(()) if display.is_empty() => format!(
                            "{}: {count} juegos sincronizados.",
                            model::store_label(&store)
                        ),
                        Ok(()) => format!(
                            "{} conectado como {display}: {count} juegos sincronizados.",
                            model::store_label(&store)
                        ),
                        Err(error) => {
                            self.catalog_blocked = Some(error.clone());
                            format!(
                                "La cuenta quedó guardada, pero no se pudo persistir la biblioteca: {error}"
                            )
                        }
                    });
                }
                Err(error) => self.notice = Some(error),
            }
        }
        if !self.account_busy.is_empty() {
            ctx.request_repaint_after(Duration::from_millis(120));
        }
    }

    fn persist(&self) -> Result<(), String> {
        let parent = self.paths.db.parent().unwrap_or(&self.paths.db);
        fs::create_dir_all(parent)
            .map_err(|error| format!("No se pudo preparar la carpeta de la biblioteca: {error}"))?;
        let temp = self.paths.db.with_extension("json.tmp");
        let contents = serde_json::to_vec_pretty(&model::Catalog {
            schema_version: 1,
            games: self.games.clone(),
        })
        .map_err(|error| format!("No se pudo serializar la biblioteca: {error}"))?;
        fs::write(&temp, contents)
            .map_err(|error| format!("No se pudo guardar la biblioteca temporal: {error}"))?;
        fs::rename(&temp, &self.paths.db).map_err(|error| {
            let _ = fs::remove_file(&temp);
            format!("No se pudo reemplazar el archivo de biblioteca: {error}")
        })
    }

    fn add_game(&mut self) {
        if !self.catalog_available() {
            return;
        }
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
        match self.persist() {
            Ok(()) => self.notice = Some("Juego agregado".into()),
            Err(error) => {
                self.notice = Some(format!(
                    "El juego quedó en la biblioteca actual, pero no se pudo guardar: {error}"
                ));
            }
        }
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
        let now = ctx.input(|input| input.time);
        if let Some(retry_at) = self.cover_retry_after.get(&game.key).copied() {
            if retry_at > now {
                ctx.request_repaint_after(Duration::from_secs_f64(retry_at - now));
                return;
            }
        }
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
        self.cover_workers.execute(move || {
            let cached = fs::read(&cache)
                .ok()
                .and_then(|bytes| image::load_from_memory(&bytes).ok());
            let decoded = cached.or_else(|| {
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
                let decoded = image::load_from_memory(&data).ok()?;
                let _ = fs::write(cache, &data);
                Some(decoded)
            });
            let image = decoded.map(|decoded| {
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
            self.requested_covers.remove(&key);
            if let Some(image) = image {
                self.cover_retry_after.remove(&key);
                self.texture_order.retain(|cached| cached != &key);
                self.textures.insert(
                    key.clone(),
                    ctx.load_texture(key.clone(), image, TextureOptions::LINEAR),
                );
                self.texture_order.push_back(key);
                while self.texture_order.len() > MAX_CACHED_COVER_TEXTURES {
                    if let Some(expired) = self.texture_order.pop_front() {
                        self.textures.remove(&expired);
                    }
                }
                ctx.request_repaint();
            } else {
                let retry_at = ctx.input(|input| input.time) + 60.0;
                self.cover_retry_after.insert(key, retry_at);
                ctx.request_repaint_after(Duration::from_secs(60));
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
                Frame::new()
                    .fill(SURFACE)
                    .stroke(Stroke::new(1.0, LINE))
                    .corner_radius(11.0)
                    .inner_margin(Margin::symmetric(12, 4))
                    .show(ui, |ui| {
                        ui.set_width(310.0);
                        ui.horizontal(|ui| {
                            let (icon_rect, _) =
                                ui.allocate_exact_size(Vec2::splat(18.0), Sense::hover());
                            paint_icon(ui.painter(), icon_rect, UiIcon::Search, MUTED);
                            ui.add_sized(
                                [270.0, 30.0],
                                egui::TextEdit::singleline(&mut self.query)
                                    .hint_text("Buscar en tu biblioteca")
                                    .frame(egui::Frame::NONE),
                            );
                        });
                    });
            } else {
                ui.vertical(|ui| {
                    ui.label(
                        RichText::new("CUENTAS DE TIENDAS")
                            .size(10.0)
                            .strong()
                            .color(BLUE_LIGHT),
                    );
                    ui.label(
                        RichText::new("Conectá tus bibliotecas")
                            .size(17.0)
                            .strong()
                            .color(Color32::WHITE),
                    );
                });
            }
            ui.add_space((ui.available_width() - 430.0).max(10.0));
            if icon_label_button(ui, "Actualizar", UiIcon::Refresh, 124.0, false, true).clicked() {
                if self.page == Page::Library {
                    self.refresh();
                } else {
                    self.sync_connected();
                }
            }
            if icon_label_button(
                ui,
                if self.page == Page::Library {
                    "Tiendas"
                } else {
                    "Biblioteca"
                },
                if self.page == Page::Library {
                    UiIcon::Accounts
                } else {
                    UiIcon::Library
                },
                116.0,
                false,
                true,
            )
            .clicked()
            {
                self.page = if self.page == Page::Library {
                    Page::Stores
                } else {
                    Page::Library
                };
            }
            if self.page == Page::Library
                && icon_label_button(ui, "Agregar juego", UiIcon::Add, 140.0, true, true).clicked()
            {
                self.add_game();
            }
        });
    }

    fn draw_hero(&mut self, ui: &mut egui::Ui, featured: Option<Game>) {
        let ctx = ui.ctx().clone();
        let tile_count = ((ui.available_width() / 142.0).ceil() as usize).clamp(7, 14);
        let mut mosaic_games = Vec::with_capacity(tile_count);
        if let Some(game) = &featured {
            mosaic_games.push(game.clone());
        }
        for game in &self.games {
            if mosaic_games.len() >= tile_count {
                break;
            }
            if featured
                .as_ref()
                .is_none_or(|selected| selected.key != game.key)
            {
                mosaic_games.push(game.clone());
            }
        }
        for game in &mosaic_games {
            self.queue_cover(game, &ctx);
        }
        let logo_texture = self.logo_texture.clone();
        let (rect, _) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 228.0), Sense::hover());
        let painter = ui.painter().clone();
        painter.rect_filled(rect, 18.0, Color32::from_rgb(8, 15, 24));
        let tile_width = rect.width() / tile_count as f32;
        for index in 0..tile_count {
            let tile = egui::Rect::from_min_max(
                egui::pos2(rect.left() + tile_width * index as f32, rect.top()),
                egui::pos2(
                    rect.left() + tile_width * (index + 1) as f32 + 1.0,
                    rect.bottom(),
                ),
            );
            if let Some(game) = mosaic_games.get(index) {
                if let Some(texture) = self.textures.get(&game.key) {
                    painter.with_clip_rect(tile).image(
                        texture.id(),
                        tile,
                        image_uv_for_fill(texture, tile.size()),
                        Color32::WHITE,
                    );
                } else {
                    let color = game.store_color();
                    painter.rect_filled(
                        tile,
                        0.0,
                        Color32::from_rgb(
                            color.r().saturating_div(3),
                            color.g().saturating_div(3),
                            color.b().saturating_div(3),
                        ),
                    );
                    painter.text(
                        tile.center(),
                        egui::Align2::CENTER_CENTER,
                        game.title.chars().next().unwrap_or('D').to_string(),
                        FontId::proportional(46.0),
                        Color32::from_rgba_unmultiplied(220, 238, 255, 130),
                    );
                }
            }
            painter.line_segment(
                [tile.left_top(), tile.left_bottom()],
                Stroke::new(1.0, Color32::from_rgba_unmultiplied(210, 228, 244, 28)),
            );
        }
        painter.rect_filled(rect, 18.0, Color32::from_rgba_unmultiplied(3, 8, 14, 118));
        painter.rect_stroke(
            rect,
            18.0,
            Stroke::new(1.0, Color32::from_rgb(38, 70, 98)),
            egui::StrokeKind::Inside,
        );
        let gradient_width = rect.width() * 0.78;
        for step in 0..32 {
            let progress = step as f32 / 31.0;
            let alpha = (218.0 - progress * 156.0) as u8;
            let strip_width = gradient_width / 32.0 + 1.0;
            let strip = egui::Rect::from_min_size(
                egui::pos2(rect.left() + strip_width * step as f32, rect.top()),
                Vec2::new(strip_width, rect.height()),
            );
            painter.rect_filled(
                strip,
                0.0,
                Color32::from_rgba_unmultiplied(5, 10, 16, alpha),
            );
        }
        let left_rect = egui::Rect::from_min_max(
            rect.left_top() + Vec2::new(26.0, 18.0),
            egui::pos2(rect.right() - 335.0, rect.bottom() - 15.0),
        );
        ui.scope_builder(
            UiBuilder::new()
                .max_rect(left_rect)
                .layout(Layout::top_down(Align::Min)),
            |ui| {
                ui.horizontal(|ui| {
                    let (logo_rect, _) = ui.allocate_exact_size(Vec2::splat(34.0), Sense::hover());
                    if let Some(texture) = &logo_texture {
                        ui.painter().image(
                            texture.id(),
                            logo_rect,
                            egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)),
                            Color32::WHITE,
                        );
                    } else {
                        ui.label(RichText::new("D").strong().size(24.0).color(BLUE));
                    }
                    ui.vertical(|ui| {
                        ui.label(
                            RichText::new("DroidDeck")
                                .strong()
                                .size(19.0)
                                .color(Color32::WHITE),
                        );
                        ui.label(
                            RichText::new("PC GAME LIBRARY")
                                .size(8.0)
                                .strong()
                                .color(Color32::from_rgb(180, 203, 222)),
                        );
                    });
                });
                ui.add_space(8.0);
                ui.label(
                    RichText::new("TU COLECCIÓN, EN UN SOLO LUGAR")
                        .size(9.0)
                        .strong()
                        .color(BLUE_LIGHT),
                );
                ui.add_space(2.0);
                if let Some(game) = &featured {
                    ui.label(
                        RichText::new(&game.title)
                            .size(27.0)
                            .strong()
                            .color(Color32::WHITE),
                    );
                    ui.add_space(2.0);
                    ui.label(
                        RichText::new(format!(
                            "{}  ·  {}",
                            game.store_name,
                            if game.installed {
                                "Listo para jugar"
                            } else {
                                "En tu colección"
                            }
                        ))
                        .size(11.0)
                        .color(Color32::from_rgb(192, 211, 228)),
                    );
                    ui.add_space(9.0);
                    if icon_label_button(
                        ui,
                        if game.installed {
                            "Jugar ahora"
                        } else {
                            "Ver en tienda"
                        },
                        if game.installed {
                            UiIcon::Play
                        } else {
                            UiIcon::Store
                        },
                        144.0,
                        true,
                        true,
                    )
                    .clicked()
                    {
                        self.launch(game);
                    }
                } else {
                    ui.label(
                        RichText::new("Tu biblioteca empieza acá")
                            .size(25.0)
                            .strong()
                            .color(Color32::WHITE),
                    );
                    ui.add_space(5.0);
                    ui.label(
                        RichText::new("Conectá tus tiendas y reuní todos tus juegos.")
                            .size(12.0)
                            .color(Color32::from_rgb(192, 211, 228)),
                    );
                    ui.add_space(13.0);
                    if icon_label_button(
                        ui,
                        "Conectar tiendas",
                        UiIcon::Accounts,
                        160.0,
                        true,
                        true,
                    )
                    .clicked()
                    {
                        self.page = Page::Stores;
                    }
                }
            },
        );
        let total = self.games.len();
        let installed = self.games.iter().filter(|game| game.installed).count();
        let count_rect = egui::Rect::from_min_size(
            egui::pos2(rect.right() - 154.0, rect.bottom() - 69.0),
            Vec2::new(128.0, 46.0),
        );
        painter.rect_filled(
            count_rect.expand2(Vec2::new(8.0, 6.0)),
            10.0,
            Color32::from_rgba_unmultiplied(5, 11, 18, 155),
        );
        painter.text(
            egui::pos2(count_rect.center().x, count_rect.top()),
            egui::Align2::CENTER_TOP,
            total.to_string(),
            FontId::proportional(30.0),
            Color32::WHITE,
        );
        painter.text(
            egui::pos2(count_rect.center().x, count_rect.bottom() - 2.0),
            egui::Align2::CENTER_BOTTOM,
            format!("{total} JUEGOS  ·  {installed} INSTALADOS"),
            FontId::proportional(8.0),
            Color32::from_rgb(172, 197, 218),
        );
    }

    fn accounts_panel(&mut self, ui: &mut egui::Ui) {
        ui.add_space(20.0);
        let connected_count = self
            .accounts
            .iter()
            .filter(|account| account.connected)
            .count();
        ui.horizontal(|ui| {
            ui.vertical(|ui| {
                ui.label(
                    RichText::new("TU COLECCIÓN, EN UN SOLO LUGAR")
                        .size(10.0)
                        .strong()
                        .color(BLUE_LIGHT),
                );
                ui.add_space(4.0);
                ui.heading(RichText::new("Conectá tus tiendas").size(30.0).strong());
                ui.add_space(4.0);
                ui.label(
                    RichText::new(
                        "Vinculá tus cuentas para reunir juegos instalados y por instalar.",
                    )
                    .size(13.0)
                    .color(MUTED),
                );
            });
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                Frame::new()
                    .fill(SURFACE)
                    .stroke(Stroke::new(1.0, LINE))
                    .corner_radius(12.0)
                    .inner_margin(Margin::symmetric(16, 11))
                    .show(ui, |ui| {
                        ui.horizontal(|ui| {
                            let (icon_rect, _) =
                                ui.allocate_exact_size(Vec2::splat(24.0), Sense::hover());
                            paint_icon(ui.painter(), icon_rect, UiIcon::Accounts, BLUE_LIGHT);
                            ui.vertical(|ui| {
                                ui.label(
                                    RichText::new(format!("{connected_count} de 11"))
                                        .size(17.0)
                                        .strong(),
                                );
                                ui.label(
                                    RichText::new("tiendas conectadas").size(10.0).color(MUTED),
                                );
                            });
                        });
                    });
            });
        });
        ui.add_space(22.0);
        let rows = accounts::stores().to_vec();
        egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
            let card_width = ((ui.available_width() - 12.0) / 2.0).max(260.0);
            for row in rows.chunks(2) {
                ui.horizontal(|ui| {
                    ui.spacing_mut().item_spacing.x = 12.0;
                    for (id, label, description) in row {
                        self.account_card(ui, id, label, description, card_width);
                    }
                });
                ui.add_space(12.0);
            }
            ui.add_space(10.0);
            Frame::new()
                .fill(SURFACE)
                .stroke(Stroke::new(1.0, LINE))
                .corner_radius(11.0)
                .inner_margin(Margin::symmetric(14, 11))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        let (icon_rect, _) =
                            ui.allocate_exact_size(Vec2::splat(17.0), Sense::hover());
                        paint_icon(ui.painter(), icon_rect, UiIcon::Monitor, MUTED);
                        ui.label(
                            RichText::new("Xbox y Battle.net todavía no ofrecen biblioteca completa en este launcher.")
                                .size(11.0)
                                .color(MUTED),
                        );
                    });
                });
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
        #[cfg(windows)]
        let cleanup_pending = store_login::webview_cleanup_pending(id);
        #[cfg(not(windows))]
        let cleanup_pending = false;
        Frame::new()
            .fill(CARD)
            .stroke(Stroke::new(1.0, LINE))
            .corner_radius(15.0)
            .inner_margin(Margin::same(14))
            .show(ui, |ui| {
                ui.set_min_width((card_width - 28.0).max(180.0));
                ui.set_max_width((card_width - 28.0).max(180.0));
                ui.horizontal(|ui| {
                    let store = model::STORES.iter().find(|store| store.id == id);
                    let color = store.map(|store| store.color).unwrap_or(BLUE);
                    Frame::new()
                        .fill(Color32::from_rgba_unmultiplied(
                            color.r(),
                            color.g(),
                            color.b(),
                            28,
                        ))
                        .stroke(Stroke::new(
                            1.0,
                            Color32::from_rgba_unmultiplied(color.r(), color.g(), color.b(), 90),
                        ))
                        .corner_radius(10.0)
                        .inner_margin(Margin::same(9))
                        .show(ui, |ui| {
                            ui.label(
                                RichText::new(store_initials(id))
                                    .size(14.0)
                                    .strong()
                                    .color(color),
                            );
                        });
                    ui.add_space(3.0);
                    ui.vertical(|ui| {
                        ui.label(RichText::new(label).strong().size(16.0));
                        ui.label(RichText::new(description).size(11.0).color(MUTED));
                    });
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        let state = if busy {
                            ("CONECTANDO", BLUE_LIGHT)
                        } else if connected.connected {
                            ("CONECTADA", GREEN)
                        } else if ready {
                            ("DISPONIBLE", MUTED)
                        } else {
                            ("PRÓXIMAMENTE", MUTED)
                        };
                        Frame::new()
                            .fill(Color32::from_rgba_unmultiplied(
                                state.1.r(),
                                state.1.g(),
                                state.1.b(),
                                26,
                            ))
                            .corner_radius(8.0)
                            .inner_margin(Margin::symmetric(8, 5))
                            .show(ui, |ui| {
                                ui.label(RichText::new(state.0).size(9.0).strong().color(state.1));
                            });
                    });
                });
                if connected.connected {
                    ui.add_space(10.0);
                    ui.horizontal(|ui| {
                        let (icon_rect, _) =
                            ui.allocate_exact_size(Vec2::splat(14.0), Sense::hover());
                        paint_icon(ui.painter(), icon_rect, UiIcon::Check, GREEN);
                        ui.label(
                            RichText::new(format!("Sesión activa · {}", connected.display_name))
                                .size(11.0)
                                .color(GREEN),
                        );
                    });
                }
                if id == "itch" && !connected.connected {
                    ui.add_space(10.0);
                    ui.add(
                        egui::TextEdit::singleline(&mut self.itch_key)
                            .password(true)
                            .hint_text("Clave API de itch.io")
                            .desired_width(ui.available_width()),
                    );
                }
                ui.add_space(12.0);
                ui.horizontal(|ui| {
                    if connected.connected {
                        if icon_label_button(ui, "Sincronizar", UiIcon::Refresh, 132.0, true, !busy)
                            .clicked()
                        {
                            self.sync_store(id);
                        }
                        if ui
                            .add_enabled(
                                !busy,
                                egui::Button::new("Desconectar")
                                    .fill(SURFACE)
                                    .stroke(Stroke::new(1.0, LINE))
                                    .corner_radius(9.0),
                            )
                            .clicked()
                        {
                            self.disconnect_store(id);
                        }
                    } else if ready {
                        if icon_label_button(
                            ui,
                            if busy {
                                "Conectando…"
                            } else {
                                "Conectar cuenta"
                            },
                            UiIcon::Accounts,
                            158.0,
                            true,
                            !busy,
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
                if cleanup_pending {
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        ui.label(
                            RichText::new("La sesión web anterior no se pudo borrar.")
                                .size(10.0)
                                .color(MUTED),
                        );
                        if ui
                            .add_enabled(!busy, egui::Button::new("Reintentar limpieza"))
                            .clicked()
                        {
                            #[cfg(windows)]
                            self.retry_webview_cleanup(id);
                        }
                    });
                }
            });
    }

    fn draw_card(&mut self, ui: &mut egui::Ui, game: &Game) {
        let ctx = ui.ctx().clone();
        let (rect, response) = ui.allocate_exact_size(Vec2::new(CARD_W, CARD_H), Sense::hover());
        let cover_rect = egui::Rect::from_min_size(
            rect.left_top() + Vec2::splat(1.0),
            Vec2::new(CARD_W - 2.0, 204.0),
        );
        if ui.clip_rect().intersects(cover_rect) {
            self.queue_cover(game, &ctx);
        }
        ui.painter().rect_filled(
            rect,
            15.0,
            if response.hovered() { CARD_HOVER } else { CARD },
        );
        ui.painter().rect_stroke(
            rect,
            15.0,
            Stroke::new(1.0, if response.hovered() { BLUE } else { LINE }),
            egui::StrokeKind::Inside,
        );
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
                    Color32::from_rgb(22, 34, 49),
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
                        Color32::from_rgba_unmultiplied(3, 8, 15, 46),
                    );
                } else {
                    ui.painter().text(
                        cover.center(),
                        egui::Align2::CENTER_CENTER,
                        game.title.chars().next().unwrap_or('D').to_string(),
                        FontId::proportional(62.0),
                        Color32::from_rgb(75, 128, 171),
                    );
                    ui.painter().circle_stroke(
                        cover.center(),
                        54.0,
                        Stroke::new(1.0, Color32::from_rgba_unmultiplied(75, 177, 242, 100)),
                    );
                }
                let badge_width = ((game.store_name.chars().count() as f32 * 6.4) + 31.0)
                    .clamp(70.0, CARD_W - 22.0);
                let badge = egui::Rect::from_min_size(
                    cover.left_top() + Vec2::splat(9.0),
                    Vec2::new(badge_width, 25.0),
                );
                ui.painter().rect_filled(
                    badge,
                    9.0,
                    Color32::from_rgba_unmultiplied(8, 13, 20, 232),
                );
                ui.painter().circle_filled(
                    egui::pos2(badge.left() + 11.0, badge.center().y),
                    3.0,
                    game.store_color(),
                );
                ui.painter().text(
                    egui::pos2(badge.left() + 21.0, badge.center().y),
                    egui::Align2::LEFT_CENTER,
                    game.store_name.as_str(),
                    FontId::proportional(11.0),
                    game.store_color(),
                );
                let status_color = if game.installed { GREEN } else { BLUE_LIGHT };
                let status_badge = egui::Rect::from_min_size(
                    egui::pos2(cover.left() + 9.0, cover.bottom() - 33.0),
                    Vec2::new(if game.installed { 80.0 } else { 110.0 }, 23.0),
                );
                ui.painter().rect_filled(
                    status_badge,
                    8.0,
                    Color32::from_rgba_unmultiplied(8, 13, 20, 220),
                );
                ui.painter().circle_filled(
                    egui::pos2(status_badge.left() + 10.0, status_badge.center().y),
                    3.0,
                    status_color,
                );
                ui.painter().text(
                    egui::pos2(status_badge.left() + 19.0, status_badge.center().y),
                    egui::Align2::LEFT_CENTER,
                    if game.installed {
                        "INSTALADO"
                    } else {
                        "NO INSTALADO"
                    },
                    FontId::proportional(9.0),
                    status_color,
                );
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    ui.add_space(10.0);
                    ui.add_sized(
                        [CARD_W - 22.0, 38.0],
                        egui::Label::new(RichText::new(&game.title).strong().size(14.0)).truncate(),
                    );
                });
                ui.add_space(5.0);
                ui.horizontal(|ui| {
                    ui.add_space(9.0);
                    if icon_label_button(
                        ui,
                        if game.installed { "Jugar" } else { "Instalar" },
                        if game.installed {
                            UiIcon::Play
                        } else {
                            UiIcon::Download
                        },
                        CARD_W - 20.0,
                        true,
                        true,
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
            .exact_size(244.0)
            .resizable(false)
            .frame(
                Frame::new()
                    .fill(PANEL)
                    .inner_margin(Margin::same(18))
                    .stroke(Stroke::new(1.0, LINE)),
            )
            .show(ui, |ui| {
                ui.add_space(7.0);
                ui.horizontal(|ui| {
                    let (logo_rect, _) = ui.allocate_exact_size(Vec2::splat(42.0), Sense::hover());
                    if let Some(texture) = &self.logo_texture {
                        ui.painter().image(
                            texture.id(),
                            logo_rect,
                            egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)),
                            Color32::WHITE,
                        );
                    } else {
                        Frame::new()
                            .fill(BLUE)
                            .corner_radius(11.0)
                            .inner_margin(Margin::symmetric(9, 7))
                            .show(ui, |ui| {
                                ui.label(
                                    RichText::new("D").strong().size(22.0).color(Color32::WHITE),
                                );
                            });
                    }
                    ui.vertical(|ui| {
                        ui.label(
                            RichText::new("DroidDeck")
                                .strong()
                                .size(20.0)
                                .color(Color32::WHITE),
                        );
                        ui.label(
                            RichText::new("GAME LAUNCHER")
                                .size(8.0)
                                .strong()
                                .color(MUTED),
                        );
                    });
                });
                ui.add_space(31.0);
                ui.label(RichText::new("BIBLIOTECA").size(10.0).strong().color(MUTED));
                ui.add_space(9.0);
                self.nav_item(
                    ui,
                    "Todos los juegos",
                    all,
                    LibraryFilter::All,
                    UiIcon::Grid,
                );
                self.nav_item(
                    ui,
                    "Instalados",
                    installed,
                    LibraryFilter::Installed,
                    UiIcon::Check,
                );
                self.nav_item(
                    ui,
                    "No instalados",
                    uninstalled,
                    LibraryFilter::Uninstalled,
                    UiIcon::Download,
                );
                ui.add_space(8.0);
                let connected = self
                    .accounts
                    .iter()
                    .filter(|account| account.connected)
                    .count();
                if sidebar_row(
                    ui,
                    "Cuentas de tiendas",
                    &format!("{connected}/11"),
                    UiIcon::Accounts,
                    BLUE_LIGHT,
                    self.page == Page::Stores,
                )
                .clicked()
                {
                    self.page = Page::Stores;
                }
                ui.add_space(26.0);
                ui.label(
                    RichText::new("TUS TIENDAS")
                        .size(10.0)
                        .strong()
                        .color(MUTED),
                );
                ui.add_space(9.0);
                let stores = model::available_stores(&self.games);
                self.store_nav(ui, "all", "Todas las tiendas", all);
                for (id, label, count) in stores {
                    self.store_nav(ui, &id, &label, count);
                }
                ui.with_layout(Layout::bottom_up(Align::Min), |ui| {
                    Frame::new()
                        .fill(SURFACE)
                        .stroke(Stroke::new(1.0, LINE))
                        .corner_radius(11.0)
                        .inner_margin(Margin::symmetric(11, 9))
                        .show(ui, |ui| {
                            ui.horizontal(|ui| {
                                let (icon_rect, _) =
                                    ui.allocate_exact_size(Vec2::splat(24.0), Sense::hover());
                                paint_icon(ui.painter(), icon_rect, UiIcon::Gamepad, BLUE_LIGHT);
                                ui.vertical(|ui| {
                                    ui.label(
                                        RichText::new("Windows launcher")
                                            .size(10.0)
                                            .strong()
                                            .color(Color32::WHITE),
                                    );
                                    ui.label(
                                        RichText::new("Solo PC · Sin emulación")
                                            .size(9.0)
                                            .color(MUTED),
                                    );
                                });
                            });
                        });
                });
            });
    }

    fn nav_item(
        &mut self,
        ui: &mut egui::Ui,
        label: &str,
        count: usize,
        filter: LibraryFilter,
        icon: UiIcon,
    ) {
        let selected =
            self.page == Page::Library && self.filter == filter && self.store_filter == "all";
        if sidebar_row(ui, label, &count.to_string(), icon, BLUE_LIGHT, selected).clicked() {
            self.filter = filter;
            self.store_filter = "all".into();
            self.page = Page::Library;
        }
        ui.add_space(4.0);
    }

    fn store_nav(&mut self, ui: &mut egui::Ui, id: &str, label: &str, count: usize) {
        let selected = self.page == Page::Library && self.store_filter == id;
        let color = if id == "all" {
            BLUE_LIGHT
        } else {
            model::STORES
                .iter()
                .find(|store| store.id == id)
                .map(|store| store.color)
                .unwrap_or(MUTED)
        };
        if sidebar_row(
            ui,
            label,
            &count.to_string(),
            UiIcon::Store,
            color,
            selected,
        )
        .clicked()
        {
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
        egui::CentralPanel::default()
            .frame(Frame::new().fill(BG).inner_margin(Margin::same(24)))
            .show(ui, |ui| {
            self.header(ui);
            if let Some(message) = self.notice.take() {
                self.toast = Some((message, ctx.input(|i| i.time) + 3.5));
            }
            if let Some((message, until)) = &self.toast {
                if ctx.input(|i| i.time) < *until {
                    Frame::new()
                        .fill(Color32::from_rgb(14, 39, 56))
                        .stroke(Stroke::new(1.0, Color32::from_rgb(35, 91, 126)))
                        .corner_radius(10.0)
                        .inner_margin(Margin::symmetric(12, 8))
                        .show(ui, |ui| {
                            ui.horizontal(|ui| {
                                let (icon_rect, _) =
                                    ui.allocate_exact_size(Vec2::splat(16.0), Sense::hover());
                                paint_icon(ui.painter(), icon_rect, UiIcon::Check, BLUE_LIGHT);
                                ui.label(RichText::new(message).color(Color32::WHITE));
                            });
                        });
                    ui.add_space(8.0);
                } else {
                    self.toast = None;
                }
            }
            if self.page == Page::Stores {
                ui.add_space(4.0);
                self.accounts_panel(ui);
            } else {
                ui.add_space(17.0);
                let featured = self
                    .games
                    .iter()
                    .find(|game| game.installed)
                    .or_else(|| self.games.first())
                    .cloned();
                self.draw_hero(ui, featured);
                ui.add_space(22.0);
                let installed = self.games.iter().filter(|game| game.installed).count();
                let uninstalled = self.games.len().saturating_sub(installed);
                ui.horizontal(|ui| {
                    ui.vertical(|ui| {
                        ui.heading(RichText::new("Tu biblioteca").size(22.0).strong());
                        ui.label(
                            RichText::new("Tus juegos, ordenados por disponibilidad")
                                .size(11.0)
                                .color(MUTED),
                        );
                    });
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        Frame::new()
                            .fill(SURFACE)
                            .stroke(Stroke::new(1.0, LINE))
                            .corner_radius(10.0)
                            .inner_margin(Margin::symmetric(12, 8))
                            .show(ui, |ui| {
                                ui.horizontal(|ui| {
                                    ui.label(
                                        RichText::new(format!("{} juegos", self.games.len()))
                                            .size(11.0)
                                            .strong()
                                            .color(Color32::WHITE),
                                    );
                                    ui.separator();
                                    ui.label(RichText::new(format!("{installed} instalados")).size(10.0).color(GREEN));
                                    ui.label(RichText::new(format!("{uninstalled} por instalar")).size(10.0).color(MUTED));
                                });
                            });
                    });
                });
                ui.add_space(16.0);
                let visible = self.visible_games();
                if visible.is_empty() {
                    Frame::new()
                        .fill(CARD)
                        .stroke(Stroke::new(1.0, LINE))
                        .corner_radius(15.0)
                        .inner_margin(Margin::same(25))
                        .show(ui, |ui| {
                            ui.vertical_centered(|ui| {
                                let (icon_rect, _) =
                                    ui.allocate_exact_size(Vec2::splat(36.0), Sense::hover());
                                paint_icon(
                                    ui.painter(),
                                    icon_rect,
                                    if self.games.is_empty() { UiIcon::Library } else { UiIcon::Search },
                                    BLUE_LIGHT,
                                );
                                ui.add_space(10.0);
                                ui.label(
                                    RichText::new(if self.games.is_empty() {
                                        "Tu biblioteca está lista para crecer"
                                    } else {
                                        "No hay juegos que coincidan"
                                    })
                                    .size(20.0)
                                    .strong(),
                                );
                                ui.add_space(6.0);
                                ui.label(
                                    RichText::new(if self.games.is_empty() {
                                        "Conectá Steam y tus otras tiendas para cargar juegos instalados y no instalados."
                                    } else {
                                        "Probá otro nombre o elegí un filtro distinto."
                                    })
                                    .size(12.0)
                                    .color(MUTED),
                                );
                                if self.games.is_empty() {
                                    ui.add_space(15.0);
                                    if icon_label_button(ui, "Conectar tiendas", UiIcon::Accounts, 170.0, true, true).clicked() {
                                        self.page = Page::Stores;
                                    }
                                }
                            });
                        });
                } else {
                    let no_installed: Vec<_> = visible.iter().filter(|game| !game.installed).cloned().collect();
                    let installed_games: Vec<_> = visible.iter().filter(|game| game.installed).cloned().collect();
                    egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
                        if !no_installed.is_empty() {
                            section_header(ui, "NO INSTALADOS", no_installed.len(), BLUE_LIGHT);
                            ui.add_space(12.0);
                            self.draw_grid(ui, &no_installed);
                            ui.add_space(18.0);
                        }
                        if !installed_games.is_empty() {
                            section_header(ui, "INSTALADOS", installed_games.len(), GREEN);
                            ui.add_space(12.0);
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

fn image_uv_for_fill(texture: &TextureHandle, target_size: Vec2) -> egui::Rect {
    let [width, height] = texture.size();
    let source_aspect = width as f32 / height.max(1) as f32;
    let target_aspect = target_size.x / target_size.y.max(1.0);
    if source_aspect > target_aspect {
        let visible_width = target_aspect / source_aspect;
        let inset = (1.0 - visible_width) * 0.5;
        egui::Rect::from_min_max(egui::pos2(inset, 0.0), egui::pos2(1.0 - inset, 1.0))
    } else {
        let visible_height = source_aspect / target_aspect.max(0.001);
        let inset = (1.0 - visible_height) * 0.5;
        egui::Rect::from_min_max(egui::pos2(0.0, inset), egui::pos2(1.0, 1.0 - inset))
    }
}

fn sidebar_row(
    ui: &mut egui::Ui,
    label: &str,
    count: &str,
    icon: UiIcon,
    icon_color: Color32,
    selected: bool,
) -> egui::Response {
    let (rect, response) =
        ui.allocate_exact_size(Vec2::new(ui.available_width(), 36.0), Sense::click());
    let fill = if selected {
        Color32::from_rgb(17, 45, 66)
    } else if response.hovered() {
        CARD_HOVER
    } else {
        Color32::TRANSPARENT
    };
    ui.painter().rect_filled(rect, 9.0, fill);
    if selected {
        ui.painter().rect_stroke(
            rect,
            9.0,
            Stroke::new(1.0, Color32::from_rgb(33, 99, 140)),
            egui::StrokeKind::Inside,
        );
        ui.painter().rect_filled(
            egui::Rect::from_min_size(rect.left_top(), Vec2::new(3.0, rect.height())),
            2.0,
            BLUE,
        );
    }
    let icon_rect = egui::Rect::from_center_size(
        egui::pos2(rect.left() + 23.0, rect.center().y),
        Vec2::splat(16.0),
    );
    paint_icon(
        ui.painter(),
        icon_rect,
        icon,
        if selected { BLUE_LIGHT } else { icon_color },
    );
    ui.painter().text(
        egui::pos2(rect.left() + 43.0, rect.center().y),
        egui::Align2::LEFT_CENTER,
        label,
        FontId::proportional(12.5),
        if selected { Color32::WHITE } else { MUTED },
    );
    ui.painter().text(
        egui::pos2(rect.right() - 11.0, rect.center().y),
        egui::Align2::RIGHT_CENTER,
        count,
        FontId::proportional(10.5),
        if selected {
            Color32::from_rgb(190, 216, 236)
        } else {
            MUTED
        },
    );
    response
}

fn store_initials(id: &str) -> &'static str {
    match id {
        "steam" => "S",
        "epic" => "EP",
        "ea" => "EA",
        "gog" => "GOG",
        "ubisoft" => "U",
        "amazon" => "A",
        "rockstar" => "R*",
        "itch" => "itch",
        "gamejolt" => "GJ",
        "xbox" => "X",
        "battlenet" => "B",
        _ => "PC",
    }
}

fn set_theme(ctx: &egui::Context) {
    let mut visuals = egui::Visuals::dark();
    visuals.panel_fill = BG;
    visuals.window_fill = PANEL;
    visuals.extreme_bg_color = Color32::from_rgb(6, 10, 15);
    visuals.faint_bg_color = Color32::from_rgb(20, 29, 40);
    visuals.selection.bg_fill = Color32::from_rgb(20, 95, 141);
    visuals.widgets.noninteractive.bg_fill = SURFACE;
    visuals.widgets.inactive.bg_fill = CARD;
    visuals.widgets.hovered.bg_fill = CARD_HOVER;
    visuals.widgets.active.bg_fill = Color32::from_rgb(25, 133, 198);
    visuals.widgets.noninteractive.fg_stroke.color = Color32::from_rgb(226, 234, 243);
    visuals.widgets.inactive.fg_stroke.color = Color32::from_rgb(202, 216, 230);
    visuals.widgets.hovered.fg_stroke.color = Color32::WHITE;
    visuals.widgets.active.fg_stroke.color = Color32::WHITE;
    ctx.set_visuals(visuals);
    ctx.all_styles_mut(|style| {
        style.spacing.item_spacing = Vec2::new(10.0, 9.0);
        style.spacing.button_padding = Vec2::new(12.0, 8.0);
    });
}

fn read_database(path: &std::path::Path) -> Result<Option<Vec<Game>>, String> {
    let data = match fs::read_to_string(path) {
        Ok(data) => data,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(format!("No se pudo leer {}: {error}", path.display())),
    };
    let catalog: model::Catalog = serde_json::from_str(&data).map_err(|error| {
        format!(
            "La biblioteca {} no contiene JSON válido: {error}",
            path.display()
        )
    })?;
    Ok(Some(catalog.games))
}

fn load_database(path: &std::path::Path) -> Result<Vec<Game>, String> {
    match read_database(path)? {
        Some(games) => Ok(games),
        None => load_legacy_database(),
    }
}

fn load_legacy_database() -> Result<Vec<Game>, String> {
    let Some(appdata) = std::env::var_os("APPDATA").map(PathBuf::from) else {
        return Ok(Vec::new());
    };
    let candidates = [
        appdata.join("droideck-windows-launcher/library.json"),
        appdata.join("DroidDeck Launcher/library.json"),
        appdata.join("DroidDeckLauncher/library.json"),
    ];
    for candidate in &candidates {
        if let Some(games) = read_database(candidate)? {
            return Ok(games);
        }
    }
    Ok(Vec::new())
}

fn safe_cache_name(key: &str) -> String {
    let safe: String = key
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .take(120)
        .collect();
    let hash = key.bytes().fold(0xcbf29ce484222325u64, |hash, byte| {
        (hash ^ u64::from(byte)).wrapping_mul(0x100000001b3)
    });
    format!("{safe}_{hash:016x}")
}

#[cfg(test)]
mod tests {
    use super::safe_cache_name;

    #[test]
    fn cache_names_distinguish_keys_with_same_sanitized_form() {
        assert_ne!(safe_cache_name("epic:a-b"), safe_cache_name("epic:a_b"));
        assert_eq!(safe_cache_name("epic:a-b"), safe_cache_name("epic:a-b"));
    }
}
