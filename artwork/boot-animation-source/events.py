import json
from pathlib import Path
from playwright.sync_api import sync_playwright
JS = """(() => {
  const d = DD.drops, c = (m, q) => ({x: m[0]*q.x + m[2]*q.y + m[4], y: m[1]*q.x + m[3]*q.y + m[5]});
  const ev = {DUR: DD.DUR, T_L: DD.T_L, LOCK: DD.BEATS[3][1], HOP: DD.HOP,
    drops: Object.fromEntries(['archDrop','ballDrop','domeDrop'].map(k => [k, {t0: d[k].o.t0, impacts: d[k].impacts}]))};
  const tr = {t:[], phi:[], u:[], psi:[], ax:[], ay:[], bx:[], by:[], dx:[], dy:[]}, dt = 1/1200;
  for (let i = 0; i*dt <= DD.DUR + 1e-9; i++) { const t = i*dt, p = DD.pose(t);
    const a = c(p.arch, DD.CEN.arch), b = c(p.ball, {x: 63.98, y: 111.86}), q = c(p.dome, DD.CEN.dome);
    tr.t.push(t); tr.phi.push(DD.sig('phi', t)); tr.u.push(DD.sig('u', t)); tr.psi.push(DD.sig('psi', t));
    tr.ax.push(a.x); tr.ay.push(a.y); tr.bx.push(b.x); tr.by.push(b.y); tr.dx.push(q.x); tr.dy.push(q.y); }
  ev.tr = tr; return ev; })()"""
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page()
    pg.goto(Path(__file__).with_name('droiddeck-boot.html').resolve().as_uri() + '?render=1&w=320&h=200')
    pg.wait_for_function("typeof DD !== 'undefined'", timeout=15000)
    ev = pg.evaluate(JS); b.close()
json.dump(ev, open('events.json', 'w'))
print({k: v for k, v in ev.items() if k != 'tr'})
