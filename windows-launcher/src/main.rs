#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod model;
mod scanner;

use eframe::egui::{
    self, Align, Color32, FontId, Frame, Layout, Margin, RichText, Sense, Stroke, TextureHandle,
    TextureOptions, UiBuilder, Vec2,
};
use model::{Game, normalize_title};
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

fn main() -> eframe::Result<()> {
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
        Box::new(|cc| Ok(Box::new(Launcher::new(cc)))),
    )
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum LibraryFilter {
    All,
    Installed,
    Uninstalled,
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
}

impl Launcher {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        set_theme(&cc.egui_ctx);
        let paths = Paths::new();
        let games =
            scanner::merge_local(read_database(&paths.db).unwrap_or_else(load_legacy_database));
        let (cover_tx, cover_rx) = mpsc::channel();
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
        }
    }

    fn refresh(&mut self) {
        let saved = read_database(&self.paths.db).unwrap_or_else(load_legacy_database);
        self.games = scanner::merge_local(saved);
        self.notice = Some("Biblioteca actualizada".into());
        self.persist();
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

    fn import(&mut self) {
        let Some(path) = rfd::FileDialog::new()
            .set_title("Importar biblioteca de DroidDeck")
            .add_filter("Biblioteca DroidDeck", &["json"])
            .pick_file()
        else {
            return;
        };
        match fs::read_to_string(&path)
            .ok()
            .and_then(|json| serde_json::from_str::<model::Catalog>(&json).ok())
        {
            Some(catalog) if catalog.schema_version == 1 => {
                let existing: HashMap<_, _> = self
                    .games
                    .drain(..)
                    .map(|game| (game.key.clone(), game))
                    .collect();
                let mut merged = existing;
                let count = catalog.games.len();
                for mut game in catalog.games {
                    game.normalize();
                    if let Some(old) = merged.get(&game.key) {
                        if game.launch_target.is_empty() {
                            game.launch_target.clone_from(&old.launch_target);
                        }
                        if game.cover_url.is_empty() {
                            game.cover_url.clone_from(&old.cover_url);
                        }
                    }
                    merged.insert(game.key.clone(), game);
                }
                self.games = scanner::merge_local(merged.into_values().collect());
                self.persist();
                self.notice = Some(format!("Se importaron {count} juegos de DroidDeck"));
            }
            _ => self.notice = Some("El JSON no es una exportación compatible de DroidDeck".into()),
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
            ui.add_sized(
                [320.0, 38.0],
                egui::TextEdit::singleline(&mut self.query).hint_text("Buscar en tu biblioteca"),
            );
            ui.add_space((ui.available_width() - 390.0).max(12.0));
            if ui.button("Actualizar").clicked() {
                self.refresh();
            }
            if ui.button("Importar DroidDeck").clicked() {
                self.import();
            }
            if ui
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
        }
    }
}

impl eframe::App for Launcher {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        self.poll_covers(&ctx);
        self.sidebar(ui);
        egui::CentralPanel::default().frame(Frame::new().fill(BG).inner_margin(Margin::same(22))).show(ui, |ui| {
            self.header(ui);
            ui.add_space(20.0);
            let installed = self.games.iter().filter(|game| game.installed).count();
            let uninstalled = self.games.len().saturating_sub(installed);
            ui.horizontal(|ui| {
                ui.vertical(|ui| {
                    ui.label(RichText::new("TU COLECCIÓN, EN UN SOLO LUGAR").size(10.0).strong().color(BLUE));
                    ui.add_space(4.0);
                    ui.heading(RichText::new("Biblioteca").size(27.0).strong());
                });
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    ui.label(RichText::new(format!("{} juegos   ·   {} instalados   ·   {} por instalar", self.games.len(), installed, uninstalled)).size(12.0).color(MUTED));
                });
            });
            ui.add_space(14.0);
            if let Some(message) = self.notice.take() { self.toast = Some((message, ctx.input(|i| i.time) + 3.5)); }
            if let Some((message, until)) = &self.toast {
                    if ctx.input(|i| i.time) < *until {
                    Frame::new().fill(Color32::from_rgb(14, 42, 61)).stroke(Stroke::new(1.0, Color32::from_rgb(28, 96, 137))).corner_radius(8.0).inner_margin(Margin::symmetric(12, 8)).show(ui, |ui| { ui.label(RichText::new(message).color(Color32::WHITE)); });
                    ui.add_space(8.0);
                } else { self.toast = None; }
            }
            ui.separator();
            let visible = self.visible_games();
            if visible.is_empty() {
                ui.add_space(70.0);
                ui.vertical_centered(|ui| {
                    ui.label(RichText::new(if self.games.is_empty() { "Tu biblioteca está vacía" } else { "No hay juegos que coincidan" }).size(24.0).strong());
                    ui.add_space(8.0);
                    ui.label(RichText::new(if self.games.is_empty() { "En DroidDeck para Android, abrí Biblioteca → Compartir y exportá todas las tiendas. Ese catálogo incluye juegos de Steam aunque todavía no estén instalados." } else { "Probá otro nombre o filtro." }).color(MUTED));
                    ui.add_space(18.0);
                    if ui.add(egui::Button::new("Importar biblioteca DroidDeck").fill(BLUE).corner_radius(8.0)).clicked() { self.import(); }
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
                    if no_installed.is_empty() && self.filter != LibraryFilter::Installed && !self.games.is_empty() {
                        ui.add_space(10.0);
                        ui.label(RichText::new("Todavía no hay juegos sin instalar en este catálogo. Volvé a exportar la biblioteca desde DroidDeck en Android para incluir los juegos de Steam de tu cuenta.").color(MUTED));
                    }
                });
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
