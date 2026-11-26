const { app, BrowserWindow, dialog, ipcMain, shell } = require('electron');
const { execFile, execFileSync } = require('node:child_process');
const { promisify } = require('node:util');
const fs = require('node:fs');
const path = require('node:path');
const execFileAsync = promisify(execFile);

const STORE_NAMES = {
  steam: 'Steam', epic: 'Epic Games', ea: 'EA app', xbox: 'Xbox', gog: 'GOG',
  ubisoft: 'Ubisoft Connect', amazon: 'Amazon Games', battlenet: 'Battle.net',
  rockstar: 'Rockstar Games', itch: 'itch.io', gamejolt: 'Game Jolt', other: 'Otro',
};

function databasePath() {
  return path.join(app.getPath('userData'), 'library.json');
}

function readDatabase() {
  try {
    const parsed = JSON.parse(fs.readFileSync(databasePath(), 'utf8'));
    return { games: Array.isArray(parsed.games) ? parsed.games : [] };
  } catch {
    return { games: [] };
  }
}

function writeDatabase(games) {
  fs.mkdirSync(path.dirname(databasePath()), { recursive: true });
  const temp = `${databasePath()}.tmp`;
  fs.writeFileSync(temp, JSON.stringify({ schemaVersion: 1, games }, null, 2), 'utf8');
  fs.renameSync(temp, databasePath());
}

function normalizeStore(value) {
  const id = String(value || 'other').trim().toLowerCase();
  return STORE_NAMES[id] ? id : 'other';
}

function cleanText(value, max = 500) {
  return String(value || '').trim().slice(0, max);
}

function safeExternalUrl(value) {
  try {
    const url = new URL(cleanText(value, 2000));
    return url.protocol === 'https:' ? url.toString() : '';
  } catch {
    return '';
  }
}

function stableKey(game) {
  return `${normalizeStore(game.store)}:${cleanText(game.storeId || game.appId || game.title, 180).toLowerCase()}`;
}

function normalizeTitle(value) {
  return cleanText(value, 300).normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '');
}

function decodeVdf(value) {
  return value.replace(/\\\\/g, '\\').replace(/\\"/g, '"');
}

function vdfValue(source, key) {
  const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = new RegExp(`"${escaped}"\\s*"((?:\\\\.|[^"])*)"`, 'i').exec(source);
  return match ? decodeVdf(match[1]) : '';
}

function steamInstallPath() {
  const keys = ['HKCU\\Software\\Valve\\Steam', 'HKLM\\SOFTWARE\\WOW6432Node\\Valve\\Steam', 'HKLM\\SOFTWARE\\Valve\\Steam'];
  for (const key of keys) {
    try {
      const output = execFileSync('reg.exe', ['query', key, '/v', 'SteamPath'], { encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] });
      const value = /SteamPath\s+REG_\w+\s+(.+)/i.exec(output)?.[1]?.trim();
      if (value && fs.existsSync(path.join(value, 'steamapps'))) return path.resolve(value.replaceAll('/', '\\'));
    } catch {}
  }
  const candidates = [
    path.join(process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)', 'Steam'),
    path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Steam'),
    'D:\\Steam',
  ];
  return candidates.find((candidate) => fs.existsSync(path.join(candidate, 'steamapps'))) || null;
}

function steamLibraries(root) {
  const folders = new Set(root ? [root] : []);
  if (!root) return [];
  try {
    const vdf = fs.readFileSync(path.join(root, 'steamapps', 'libraryfolders.vdf'), 'utf8');
    for (const match of vdf.matchAll(/"path"\s*"((?:\\\\.|[^"])*)"/gi)) {
      const candidate = path.resolve(decodeVdf(match[1]).replaceAll('/', '\\'));
      if (fs.existsSync(path.join(candidate, 'steamapps'))) folders.add(candidate);
    }
  } catch {}
  return [...folders];
}

function scanSteam() {
  const root = steamInstallPath();
  const result = [];
  for (const library of steamLibraries(root)) {
    const dir = path.join(library, 'steamapps');
    let files = [];
    try { files = fs.readdirSync(dir); } catch { continue; }
    for (const name of files) {
      const match = /^appmanifest_(\d+)\.acf$/i.exec(name);
      if (!match) continue;
      try {
        const text = fs.readFileSync(path.join(dir, name), 'utf8');
        const appId = match[1];
        const title = vdfValue(text, 'name');
        if (!title) continue;
        result.push({
          store: 'steam', storeName: STORE_NAMES.steam, storeId: appId, appId,
          title, owned: false, installed: true,
          localTarget: `steam://run/${appId}`,
          storeUrl: `https://store.steampowered.com/app/${appId}/`,
          coverUrl: `https://cdn.cloudflare.steamstatic.com/steam/apps/${appId}/library_600x900.jpg`,
          heroUrl: `https://cdn.cloudflare.steamstatic.com/steam/apps/${appId}/library_hero.jpg`,
        });
      } catch {}
    }
  }
  return result;
}

function scanEpic() {
  const base = process.env.ProgramData || 'C:\\ProgramData';
  const manifests = path.join(base, 'Epic', 'EpicGamesLauncher', 'Data', 'Manifests');
  let files = [];
  try { files = fs.readdirSync(manifests).filter((file) => file.toLowerCase().endsWith('.item')); } catch { return []; }
  const games = [];
  for (const file of files) {
    try {
      const item = JSON.parse(fs.readFileSync(path.join(manifests, file), 'utf8'));
      if (item.bIsIncompleteInstall || item.bIsApplication === false) continue;
      const appName = cleanText(item.AppName, 200);
      const title = cleanText(item.DisplayName || item.AppName, 300);
      if (!appName || !title) continue;
      const id = cleanText(item.CatalogItemId || appName, 200);
      games.push({
        store: 'epic', storeName: STORE_NAMES.epic, storeId: id, appName,
        title, owned: false, installed: true,
        localTarget: `com.epicgames.launcher://apps/${encodeURIComponent(appName)}?action=launch&silent=true`,
        storeUrl: cleanText(item.LaunchCommand, 1000).startsWith('https://') ? item.LaunchCommand : `https://store.epicgames.com/en-US/browse?q=${encodeURIComponent(title)}`,
        coverUrl: `https://cdn1.epicgames.com/${encodeURIComponent(item.CatalogNamespace || '')}/${encodeURIComponent(item.CatalogItemId || '')}/product?resize=600w`,
      });
    } catch {}
  }
  return games;
}

async function scanStartMenu() {
  const script = String.raw`
$ErrorActionPreference = 'SilentlyContinue'
$roots = @("$env:APPDATA\Microsoft\Windows\Start Menu\Programs", "$env:ProgramData\Microsoft\Windows\Start Menu\Programs")
$links = foreach ($root in $roots) { if (Test-Path -LiteralPath $root) { Get-ChildItem -LiteralPath $root -Filter *.lnk -File -Recurse } }
$shell = New-Object -ComObject WScript.Shell
$rows = foreach ($link in $links) {
  $shortcut = $shell.CreateShortcut($link.FullName)
  if ($shortcut.TargetPath) { [PSCustomObject]@{ name = $link.BaseName; path = $link.FullName; target = $shortcut.TargetPath; arguments = $shortcut.Arguments } }
}
ConvertTo-Json -InputObject @($rows) -Compress -Depth 3
`;
  try {
    const encoded = Buffer.from(script, 'utf16le').toString('base64');
    const { stdout } = await execFileAsync('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', encoded], {
      windowsHide: true, timeout: 25_000, maxBuffer: 8 * 1024 * 1024,
    });
    const parsed = JSON.parse(stdout.trim() || '[]');
    return (Array.isArray(parsed) ? parsed : [parsed]).filter((row) => row && row.name && row.path && row.target);
  } catch {
    return [];
  }
}

function catalogGame(input) {
  const store = normalizeStore(input.store);
  const title = cleanText(input.title, 300);
  const storeId = cleanText(input.storeId || input.appId, 200);
  if (!title) return null;
  const appId = store === 'steam' ? (storeId.match(/^\d+$/) ? storeId : '') : '';
  const key = storeId ? `${store}:${storeId.toLowerCase()}` : `${store}:title:${normalizeTitle(title)}`;
  return {
    key, store, storeName: cleanText(input.storeName, 100) || STORE_NAMES[store], storeId,
    appId, title, owned: input.owned !== false, subscription: Boolean(input.subscription),
    installed: Boolean(input.installed), storeUrl: safeExternalUrl(input.storeUrl),
    coverUrl: safeImageUrl(input.coverUrl), heroUrl: safeImageUrl(input.heroUrl),
    launchTarget: cleanText(input.launchTarget, 2000), localTarget: '',
    importedFrom: cleanText(input.importedFrom, 100) || 'manual',
  };
}

function safeImageUrl(value) {
  const url = cleanText(value, 2000);
  return /^https:\/\//i.test(url) ? url : '';
}

function storeUrl(game) {
  if (game.storeUrl) return game.storeUrl;
  if (game.store === 'steam' && /^\d+$/.test(game.storeId)) return `https://store.steampowered.com/app/${game.storeId}/`;
  const query = encodeURIComponent(game.title);
  const base = {
    epic: `https://store.epicgames.com/en-US/browse?q=${query}`,
    ea: 'https://www.ea.com/games', xbox: 'https://www.xbox.com/games',
    gog: `https://www.gog.com/en/games?query=${query}`,
    ubisoft: 'https://store.ubisoft.com/', amazon: 'https://gaming.amazon.com/',
    battlenet: 'https://shop.battle.net/', rockstar: 'https://store.rockstargames.com/',
    itch: `https://itch.io/search?q=${query}`, gamejolt: `https://gamejolt.com/games?q=${query}`,
  };
  return base[game.store] || `https://www.google.com/search?q=${query}`;
}

function mergeLibrary(saved, detected, links) {
  const detectedRows = [...detected];
  const shortcuts = links.map((link) => ({ ...link, normalized: normalizeTitle(link.name) }));
  const imported = saved.map((game) => {
    const key = stableKey(game);
    let local = detectedRows.find((row) => stableKey(row) === key);
    if (!local && game.store === 'epic') local = detectedRows.find((row) => row.store === 'epic' && normalizeTitle(row.title) === normalizeTitle(game.title));
    const shortcut = shortcuts.find((row) => row.normalized === normalizeTitle(game.title));
    const launchTargetExists = game.launchTarget && !/^([a-z][a-z0-9+.-]*:)?\/\//i.test(game.launchTarget) && fs.existsSync(game.launchTarget);
    const installed = Boolean(local || shortcut || launchTargetExists);
    return {
      ...game,
      installed,
      localTarget: local?.localTarget || shortcut?.path || (launchTargetExists ? game.launchTarget : ''),
      storeUrl: game.storeUrl || local?.storeUrl || '',
      coverUrl: game.coverUrl || local?.coverUrl || '',
      heroUrl: game.heroUrl || local?.heroUrl || '',
    };
  });
  const known = new Set(imported.map(stableKey));
  for (const row of detectedRows) if (!known.has(stableKey(row))) {
    imported.push({ ...catalogGame(row), installed: true, localTarget: row.localTarget, owned: false });
    known.add(stableKey(row));
  }
  return imported.sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' }));
}

async function currentLibrary() {
  const saved = readDatabase().games.map(catalogGame).filter(Boolean);
  const detected = process.platform === 'win32' ? [...scanSteam(), ...scanEpic()] : [];
  const links = process.platform === 'win32' ? await scanStartMenu() : [];
  return mergeLibrary(saved, detected, links);
}

async function importLibrary() {
  const picked = await dialog.showOpenDialog({
    title: 'Importar biblioteca de DroidDeck',
    properties: ['openFile'],
    filters: [{ name: 'Biblioteca DroidDeck', extensions: ['json'] }],
  });
  if (picked.canceled || !picked.filePaths[0]) return { imported: 0, canceled: true };
  const data = JSON.parse(fs.readFileSync(picked.filePaths[0], 'utf8'));
  if (data.schemaVersion !== 1 || !Array.isArray(data.games)) throw new Error('El archivo no es una exportación compatible de DroidDeck.');
  const old = readDatabase().games.map(catalogGame).filter(Boolean);
  const byKey = new Map(old.map((game) => [stableKey(game), game]));
  let imported = 0;
  for (const raw of data.games) {
    const game = catalogGame({ ...raw, importedFrom: 'DroidDeck' });
    if (!game) continue;
    const previous = byKey.get(stableKey(game));
    byKey.set(stableKey(game), { ...previous, ...game, launchTarget: previous?.launchTarget || game.launchTarget || '' });
    imported++;
  }
  const games = [...byKey.values()];
  writeDatabase(games);
  return { imported, games: await currentLibrary() };
}

async function addGame(input) {
  const game = catalogGame(input);
  if (!game) throw new Error('Ingresá el nombre del juego.');
  const old = readDatabase().games.map(catalogGame).filter(Boolean);
  const index = old.findIndex((row) => stableKey(row) === stableKey(game));
  if (index >= 0) old[index] = { ...old[index], ...game };
  else old.push(game);
  writeDatabase(old);
  return currentLibrary();
}

async function removeGame(key) {
  const games = readDatabase().games.map(catalogGame).filter(Boolean).filter((game) => game.key !== key);
  writeDatabase(games);
  return currentLibrary();
}

async function launchGame(key) {
  const game = (await currentLibrary()).find((row) => row.key === key);
  if (!game) throw new Error('No se encontró el juego.');
  if (game.store === 'steam' && game.storeId) {
    await shell.openExternal(`${game.installed ? 'steam://run/' : 'steam://install/'}${game.storeId}`);
    return game.installed ? 'started' : 'installing';
  }
  if (game.store === 'epic' && game.installed && game.appName) {
    await shell.openExternal(`com.epicgames.launcher://apps/${encodeURIComponent(game.appName)}?action=launch&silent=true`);
    return 'started';
  }
  if (game.installed && game.localTarget) {
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(game.localTarget)) await shell.openExternal(game.localTarget);
    else {
      const error = await shell.openPath(game.localTarget);
      if (error) throw new Error(error);
    }
    return 'started';
  }
  const url = storeUrl(game);
  await shell.openExternal(url);
  return 'store';
}

async function chooseLaunchTarget() {
  const picked = await dialog.showOpenDialog({
    title: 'Elegir juego o acceso directo',
    properties: ['openFile'],
    filters: [{ name: 'Aplicaciones y accesos directos', extensions: ['exe', 'lnk', 'url', 'bat', 'cmd'] }, { name: 'Todos los archivos', extensions: ['*'] }],
  });
  return picked.canceled ? '' : picked.filePaths[0];
}

function createWindow() {
  const window = new BrowserWindow({
    width: 1440, height: 940, minWidth: 960, minHeight: 680,
    backgroundColor: '#080c12', title: 'DroidDeck Launcher',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, nodeIntegration: false, sandbox: true,
    },
  });
  window.loadFile(path.join(__dirname, 'index.html'));
}

ipcMain.handle('library:get', () => currentLibrary());
ipcMain.handle('library:refresh', () => currentLibrary());
ipcMain.handle('library:import', () => importLibrary());
ipcMain.handle('library:add', (_event, input) => addGame(input));
ipcMain.handle('library:remove', (_event, key) => removeGame(key));
ipcMain.handle('library:launch', (_event, key) => launchGame(key));
ipcMain.handle('library:choose-target', () => chooseLaunchTarget());

app.whenReady().then(() => {
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
