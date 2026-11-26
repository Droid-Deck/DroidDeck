const state = { games: [], status: 'all', store: 'all', query: '', heroKey: '' };
const storeNames = {
  steam: 'Steam', epic: 'Epic Games', ea: 'EA app', xbox: 'Xbox', gog: 'GOG',
  ubisoft: 'Ubisoft Connect', amazon: 'Amazon Games', battlenet: 'Battle.net',
  rockstar: 'Rockstar Games', itch: 'itch.io', gamejolt: 'Game Jolt', other: 'Otro',
};
const storeColors = {
  steam: '#55aaff', epic: '#dedede', ea: '#ff5964', xbox: '#78d96b', gog: '#b86eff',
  ubisoft: '#5fc9ff', amazon: '#e9a24c', battlenet: '#63a9ff', rockstar: '#f5d258',
  itch: '#fa6b7b', gamejolt: '#8ad775', other: '#a6b4c5', all: '#36aaff',
};

const byId = (id) => document.getElementById(id);
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const normalize = (value) => String(value || '').normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase();

function hue(title) {
  let hash = 0;
  for (const char of String(title || '')) hash = ((hash << 5) - hash + char.charCodeAt(0)) | 0;
  return Math.abs(hash) % 360;
}

function showToast(message) {
  const toast = byId('toast');
  toast.textContent = message;
  toast.classList.add('show');
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove('show'), 2600);
}

async function reloadLibrary() {
  byId('footer-status').textContent = 'Actualizando instalaciones locales…';
  try {
    state.games = await window.droiddeck.refreshLibrary();
    render();
    byId('footer-status').textContent = 'Biblioteca actualizada';
  } catch (error) {
    byId('footer-status').textContent = 'No se pudo leer la biblioteca';
    showToast(error.message || 'No se pudo actualizar la biblioteca.');
  }
}

function visibleGames() {
  const query = normalize(state.query.trim());
  return state.games.filter((game) => {
    const matchesStatus = state.status === 'all' || (state.status === 'installed' ? game.installed : !game.installed);
    const matchesStore = state.store === 'all' || game.store === state.store;
    const matchesQuery = !query || normalize(game.title).includes(query) || normalize(game.storeName).includes(query);
    return matchesStatus && matchesStore && matchesQuery;
  });
}

function storeBadge(store) {
  const id = storeNames[store] ? store : 'other';
  return `<span class="store-dot" style="--dot:${storeColors[id]}"></span>${escapeHtml(storeNames[id])}`;
}

function card(game) {
  const cover = game.coverUrl && /^https:\/\//i.test(game.coverUrl)
    ? `<img class="cover-img" src="${escapeHtml(game.coverUrl)}" alt="" loading="lazy">` : '';
  const app = game.installed ? '▶' : '↓';
  const button = game.installed ? 'Jugar' : 'Instalar';
  const stateText = game.installed ? 'Instalado en este equipo' : (game.subscription ? 'Incluido con tu suscripción' : 'No instalada');
  const owned = game.owned ? '<span class="card-owned">EN TU CUENTA</span>' : '';
  return `<article class="game-card" style="--hue:${hue(game.title)}" data-key="${escapeHtml(game.key)}">
    <div class="cover">
      ${cover}
      <span class="store-badge">${storeBadge(game.store)}</span>
      ${owned}
      <div class="card-info"><h3 class="card-title" title="${escapeHtml(game.title)}">${escapeHtml(game.title)}</h3><div class="card-state">${escapeHtml(stateText)}</div></div>
    </div>
    <div class="card-actions"><button class="card-action ${game.installed ? 'play' : ''}" data-action="launch" data-key="${escapeHtml(game.key)}"><span>${app}</span>${button}</button></div>
    <button class="card-more" title="Quitar de esta biblioteca" aria-label="Quitar de esta biblioteca" data-action="remove" data-key="${escapeHtml(game.key)}">⋯</button>
  </article>`;
}

function section(label, games) {
  if (!games.length) return '';
  return `<section class="game-section"><div class="section-heading">${label}<small>· ${games.length}</small></div><div class="game-grid">${games.map(card).join('')}</div></section>`;
}

function renderStores() {
  const available = [...new Set(state.games.map((game) => game.store))].sort((a, b) => (storeNames[a] || a).localeCompare(storeNames[b] || b));
  const rows = ['all', ...available];
  byId('store-tabs').innerHTML = rows.map((store) => {
    const count = store === 'all' ? state.games.length : state.games.filter((game) => game.store === store).length;
    const label = store === 'all' ? 'Todas las tiendas' : storeNames[store] || store;
    return `<button class="store-chip ${state.store === store ? 'selected' : ''}" data-store="${escapeHtml(store)}"><span class="store-dot" style="--dot:${storeColors[store] || storeColors.other}"></span>${escapeHtml(label)}<span>${count}</span></button>`;
  }).join('');
}

function renderHero() {
  const game = state.games.find((item) => item.installed) || state.games[0];
  state.heroKey = game?.key || '';
  byId('hero-total').textContent = String(state.games.length);
  if (!game) {
    byId('hero-title').textContent = 'Tu biblioteca te espera';
    byId('hero-subtitle').textContent = 'Importá tu biblioteca DroidDeck o agregá tus juegos.';
    byId('hero-play').hidden = true;
    byId('hero-backdrop').style.backgroundImage = '';
    return;
  }
  byId('hero-title').textContent = game.title;
  byId('hero-subtitle').textContent = `${game.storeName || storeNames[game.store] || game.store} · ${game.installed ? 'Listo para jugar' : 'En tu biblioteca'}`;
  byId('hero-play').hidden = false;
  byId('hero-play-label').textContent = game.installed ? 'Jugar ahora' : 'Abrir tienda';
  byId('hero-play').dataset.key = game.key;
  const image = game.heroUrl || game.coverUrl;
  byId('hero-backdrop').style.backgroundImage = image && /^https:\/\//i.test(image) ? `url(${JSON.stringify(image)})` : '';
}

function render() {
  const installed = state.games.filter((game) => game.installed).length;
  const uninstalled = state.games.length - installed;
  byId('count-all').textContent = String(state.games.length);
  byId('count-installed').textContent = String(installed);
  byId('count-uninstalled').textContent = String(uninstalled);
  byId('library-meta').textContent = state.games.length ? `${state.games.length} juegos · ${installed} instalados en Windows` : 'Importá los juegos que ya tenés';
  renderStores();
  renderHero();

  const shown = visibleGames();
  const on = shown.filter((game) => game.installed);
  const off = shown.filter((game) => !game.installed);
  const sections = state.status === 'installed' ? section('INSTALADOS', on)
    : state.status === 'uninstalled' ? section('NO INSTALADOS', off)
      : section('INSTALADOS', on) + section('NO INSTALADOS', off);
  byId('library-list').innerHTML = sections;
  const empty = shown.length === 0;
  byId('empty-state').hidden = !empty;
  byId('library-list').hidden = empty;
  if (empty && state.games.length) {
    byId('empty-state').querySelector('h2').textContent = 'No hay juegos que coincidan';
    byId('empty-state').querySelector('p').textContent = 'Probá con otro nombre o tienda.';
    byId('empty-state').querySelector('.empty-actions').hidden = true;
  } else if (empty) {
    byId('empty-state').querySelector('h2').textContent = 'Tu biblioteca está vacía';
    byId('empty-state').querySelector('p').textContent = 'Exportá el catálogo de DroidDeck en Android y abrilo acá. El archivo contiene títulos y tiendas, nunca contraseñas ni sesiones.';
    byId('empty-state').querySelector('.empty-actions').hidden = false;
  }
}

async function importLibrary() {
  try {
    const result = await window.droiddeck.importLibrary();
    if (result.canceled) return;
    state.games = result.games || await window.droiddeck.getLibrary();
    render();
    showToast(`${result.imported} juegos importados desde DroidDeck.`);
    byId('footer-status').textContent = 'Catálogo importado';
  } catch (error) {
    showToast(error.message || 'No se pudo importar el catálogo.');
  }
}

function openGameDialog() {
  byId('game-form').reset();
  byId('game-dialog').showModal();
}

document.addEventListener('click', async (event) => {
  const close = event.target.closest('[data-close]');
  if (close) { byId(close.dataset.close).close(); return; }
  const status = event.target.closest('[data-status]');
  if (status) {
    state.status = status.dataset.status;
    document.querySelectorAll('.rail-link').forEach((button) => button.classList.toggle('selected', button === status));
    render();
    return;
  }
  const store = event.target.closest('[data-store]');
  if (store) { state.store = store.dataset.store; render(); return; }
  const action = event.target.closest('[data-action]');
  if (action) {
    const game = state.games.find((item) => item.key === action.dataset.key);
    if (!game) return;
    if (action.dataset.action === 'launch') {
      try {
        const result = await window.droiddeck.launchGame(game.key);
        if (result === 'installing') showToast('Steam abrió la instalación del juego.');
        else if (result === 'store') showToast(`Abriendo ${game.storeName || storeNames[game.store]}…`);
        else showToast(`Iniciando ${game.title}…`);
      } catch (error) { showToast(error.message || 'No se pudo abrir el juego.'); }
    } else if (action.dataset.action === 'remove' && window.confirm(`¿Quitar “${game.title}” de DroidDeck Launcher?`)) {
      state.games = await window.droiddeck.removeGame(game.key);
      render();
    }
    return;
  }
});

byId('search').addEventListener('input', (event) => { state.query = event.target.value; render(); });
byId('refresh-button').addEventListener('click', reloadLibrary);
byId('import-button').addEventListener('click', importLibrary);
byId('empty-import').addEventListener('click', importLibrary);
byId('add-button').addEventListener('click', openGameDialog);
byId('empty-add').addEventListener('click', openGameDialog);
byId('hero-play').addEventListener('click', async (event) => {
  const key = event.currentTarget.dataset.key;
  if (!key) return;
  try {
    const result = await window.droiddeck.launchGame(key);
    if (result === 'installing') showToast('Steam abrió la instalación del juego.');
    else if (result === 'store') showToast('Abriendo la tienda…');
    else showToast('Iniciando juego…');
  } catch (error) { showToast(error.message || 'No se pudo abrir el juego.'); }
});

byId('browse-target').addEventListener('click', async () => {
  const target = await window.droiddeck.chooseLaunchTarget();
  if (target) byId('launch-target').value = target;
});

byId('game-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  const game = Object.fromEntries(form.entries());
  try {
    state.games = await window.droiddeck.addGame(game);
    byId('game-dialog').close();
    render();
    showToast(`${game.title} agregado a la biblioteca.`);
  } catch (error) { showToast(error.message || 'No se pudo guardar el juego.'); }
});

document.addEventListener('keydown', (event) => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'f') {
    event.preventDefault();
    byId('search').focus();
  }
  if (event.key === 'Escape') document.querySelectorAll('dialog[open]').forEach((dialog) => dialog.close());
});

window.droiddeck.getLibrary().then((games) => {
  state.games = games;
  render();
  byId('footer-status').textContent = 'Listo';
}).catch(() => {
  render();
  byId('footer-status').textContent = 'Importá tu biblioteca para comenzar';
});
