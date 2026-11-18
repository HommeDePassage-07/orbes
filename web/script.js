{
// ---------- Réglages ----------
const PALETTE = [[40,160,255],[40,255,150],[255,70,200],[255,160,40],[160,100,255],[255,235,80]];
const NAMES   = ['Bleu','Vert','Magenta','Orange','Violet','Jaune'];
const KEY = 'orbe:';          // préfixe localStorage
const STALE = 3000;           // ms sans signal = fenêtre fermée
const N_POINTS = 700;

const ID = Math.random().toString(36).slice(2, 8);
const BORN = Date.now();
const cv = document.getElementById('c');
const ctx = cv.getContext('2d');
let W = 0, H = 0, DPR = 1;
let colorIdx = 0;
const others = new Map();   


const PTS = [];
for (let i = 0; i < N_POINTS; i++) {
  const y = 1 - (i / (N_POINTS - 1)) * 2, r = Math.sqrt(1 - y * y), th = i * 2.399963;
  PTS.push([Math.cos(th) * r, y, Math.sin(th) * r]);
}

// ---------- Communication entre fenêtres ----------
let bc = null;
try {
  bc = new BroadcastChannel('orbes');
  bc.onmessage = e => {
    const s = e.data;
    if (!s || s.id === ID) return;
    if (s.bye) others.delete(s.id);
    else if (s.ping) publish(true);
    else others.set(s.id, s);
  };
} catch (e) {}

function readStorage() {
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (!k || !k.startsWith(KEY)) continue;
      const s = JSON.parse(localStorage.getItem(k));
      if (!s || s.id === ID) continue;
      if (Date.now() - s.ts > STALE) { localStorage.removeItem(k); continue; }
      const cur = others.get(s.id);
      if (!cur || cur.ts < s.ts) others.set(s.id, s);
    }
  } catch (e) {}
}

// Position du contenu de la fenêtre sur l'écran physique
function me() {
  const bx = Math.max(0, (outerWidth - innerWidth) / 2);
  const left = screenX + bx;
  const top = screenY + Math.max(0, outerHeight - innerHeight - bx);
  return {
    id: ID, c: colorIdx, born: BORN, ts: Date.now(),
    left, top,
    x: left + innerWidth / 2, y: top + innerHeight / 2,
    r: Math.max(50, Math.min(170, Math.min(innerWidth, innerHeight) * 0.2))
  };
}

let lastStore = 0;
function publish(force) {
  const s = me();
  try { bc && bc.postMessage(s); } catch (e) {}
  if (force || s.ts - lastStore > 60) {
    lastStore = s.ts;
    try { localStorage.setItem(KEY + ID, JSON.stringify(s)); } catch (e) {}
  }
  return s;
}

function prune() {
  const now = Date.now();
  for (const [id, s] of others) if (now - s.ts > STALE) others.delete(id);
}

addEventListener('pagehide', () => {
  try { localStorage.removeItem(KEY + ID); } catch (e) {}
  try { bc && bc.postMessage({ id: ID, bye: true }); } catch (e) {}
});
setInterval(() => publish(true), 300); // battement de cœur, même si l'animation est ralentie

// ---------- Outils ----------
const clamp = (v, a, b) => v < a ? a : v > b ? b : v;
const mix = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
const rgba = (c, a) => `rgba(${c[0] | 0},${c[1] | 0},${c[2] | 0},${a})`;
const hash = (a, b) => { const n = Math.sin(a * 127.1 + b * 311.7) * 43758.5453; return n - Math.floor(n); };

function resize() {
  DPR = Math.min(2, devicePixelRatio || 1);
  W = innerWidth; H = innerHeight;
  cv.width = W * DPR; cv.height = H * DPR;
  ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
}
addEventListener('resize', resize);

// ---------- Étoiles fixées à l'écran (elles ne bougent pas quand on déplace la fenêtre) ----------
function drawStars(s) {
  const cell = 110;
  const x0 = Math.floor(s.left / cell), y0 = Math.floor(s.top / cell);
  const x1 = Math.ceil((s.left + W) / cell), y1 = Math.ceil((s.top + H) / cell);
  ctx.fillStyle = '#9cc8ff';
  for (let gx = x0; gx <= x1; gx++) for (let gy = y0; gy <= y1; gy++) {
    const px = (gx + hash(gx, gy)) * cell - s.left, py = (gy + hash(gy, gx)) * cell - s.top;
    ctx.globalAlpha = 0.15 + hash(gx * 3, gy * 7) * 0.35;
    ctx.fillRect(px, py, 1.2, 1.2);
  }
  ctx.globalAlpha = 1;
}

// ---------- Sphère façon Jarvis ----------
function drawOrb(o, cx, cy, col, t, pulls) {
  const R = o.r, ph = o.c * 1.7;

  // halo
  const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, R * 1.7);
  g.addColorStop(0, rgba(col, .28)); g.addColorStop(.35, rgba(col, .09)); g.addColorStop(1, rgba(col, 0));
  ctx.fillStyle = g; ctx.fillRect(cx - R * 1.7, cy - R * 1.7, R * 3.4, R * 3.4);

  // nuage de points en 3D
  const ay = t * 0.35 + ph, ax = 0.45 + Math.sin(t * 0.23 + ph) * 0.35;
  const cY = Math.cos(ay), sY = Math.sin(ay), cX = Math.cos(ax), sX = Math.sin(ax);
  ctx.fillStyle = rgba(col, 1);
  for (let i = 0; i < N_POINTS; i++) {
    const p = PTS[i];
    const r = 1 + 0.05 * Math.sin(t * 2.1 + i * 0.37) + 0.03 * Math.sin(t * 3.7 + i * 1.3);
    const x = p[0] * r, y = p[1] * r, z = p[2] * r;
    const x1 = x * cY - z * sY, z1 = x * sY + z * cY;
    const y1 = y * cX - z1 * sX, z2 = y * sX + z1 * cX;
    const s = 2.4 / (2.4 + z2);
    let px = x1 * R * s, py = y1 * R * s;
    // attirance vers les autres sphères : les points s'étirent comme des filaments
    for (const q of pulls) {
      const d = (px * q.ux + py * q.uy) / R;
      if (d > 0) { const f = q.k * d * d * d * R * 0.7; px += q.ux * f; py += q.uy * f; }
    }
    const front = 1 - (z2 + 1) / 2;
    ctx.globalAlpha = 0.18 + 0.8 * front;
    const sz = 0.7 + 1.7 * front;
    ctx.fillRect(cx + px - sz / 2, cy + py - sz / 2, sz, sz);
  }

  // anneaux pointillés en orbite
  for (let k = 0; k < 2; k++) {
    const rr = R * (1.35 + 0.28 * k), tilt = k ? -0.5 : 0.35, flat = k ? 0.32 : 0.22;
    const spin = t * (k ? -0.55 : 0.45) + ph, ct = Math.cos(tilt), st = Math.sin(tilt);
    for (let j = 0; j < 180; j++) {
      if (j % 22 < 3) continue;
      const a = j / 180 * Math.PI * 2 + spin;
      const ex = Math.cos(a) * rr, ey = Math.sin(a) * rr * flat;
      ctx.globalAlpha = 0.25 + 0.45 * (Math.sin(a) * 0.5 + 0.5);
      ctx.fillRect(cx + ex * ct - ey * st, cy + ex * st + ey * ct, 1.4, 1.4);
    }
  }

  // arcs et graduations du HUD
  ctx.strokeStyle = rgba(col, 1); ctx.lineWidth = 1;
  ctx.globalAlpha = .45;
  ctx.beginPath(); ctx.arc(cx, cy, R * 1.95, t * .3 + ph, t * .3 + ph + 1.1); ctx.stroke();
  ctx.beginPath(); ctx.arc(cx, cy, R * 1.95, t * .3 + ph + Math.PI, t * .3 + ph + Math.PI + .6); ctx.stroke();
  ctx.globalAlpha = .3;
  ctx.beginPath(); ctx.arc(cx, cy, R * 2.12, -t * .18, -t * .18 + 2.2); ctx.stroke();
  ctx.beginPath();
  for (let j = 0; j < 72; j++) {
    const a = j / 72 * Math.PI * 2 - t * 0.08, l = j % 6 ? 4 : 10;
    ctx.moveTo(cx + Math.cos(a) * R * 2.25, cy + Math.sin(a) * R * 2.25);
    ctx.lineTo(cx + Math.cos(a) * (R * 2.25 + l), cy + Math.sin(a) * (R * 2.25 + l));
  }
  ctx.globalAlpha = .22; ctx.stroke();
  ctx.globalAlpha = 1;
}

// ---------- Lien entre deux fenêtres ----------
function drawLink(A, B, cA, cB, t, seed) {
  const dx = B.lx - A.lx, dy = B.ly - A.ly, D = Math.hypot(dx, dy);
  if (D < 2) return;
  const nx = -dy / D, ny = dx / D, mx = (A.lx + B.lx) / 2, my = (A.ly + B.ly) / 2;
  const curve = (u, off) => {
    const qx = mx + nx * off, qy = my + ny * off, v = 1 - u;
    return [v * v * A.lx + 2 * v * u * qx + u * u * B.lx, v * v * A.ly + 2 * v * u * qy + u * u * B.ly];
  };
  const grad = a => { const g = ctx.createLinearGradient(A.lx, A.ly, B.lx, B.ly); g.addColorStop(0, rgba(cA, a)); g.addColorStop(1, rgba(cB, a)); return g; };

  // plusieurs brins qui ondulent comme un arc électrique
  for (let k = 0; k < 4; k++) {
    const off = Math.sin(t * (0.9 + k * 0.37) + seed + k * 2.1) * D * (0.04 + k * 0.035);
    ctx.beginPath();
    for (let s = 0; s <= 40; s++) { const p = curve(s / 40, off); s ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1]); }
    if (k === 0) { ctx.lineWidth = 9; ctx.strokeStyle = grad(.07); ctx.stroke(); ctx.lineWidth = 1.6; ctx.strokeStyle = grad(.75); }
    else { ctx.lineWidth = .8; ctx.strokeStyle = grad(.28); }
    ctx.stroke();
  }

  // particules qui voyagent dans les deux sens
  const off0 = Math.sin(t * 0.9 + seed) * D * 0.04;
  for (let m = 0; m < 46; m++) {
    let u = (t * 0.22 + m / 46 + hash(m, seed) * 0.1) % 1;
    if (m % 2) u = 1 - u;
    const p = curve(u, off0), w = Math.sin(u * Math.PI * 7 + t * 4 + m) * 7;
    ctx.globalAlpha = Math.sin(u * Math.PI) * 0.9;
    ctx.fillStyle = rgba(mix(cA, cB, u), 1);
    ctx.fillRect(p[0] + nx * w - 1.2, p[1] + ny * w - 1.2, 2.4, 2.4);
  }
  ctx.globalAlpha = 1;
}

// ---------- Boucle principale ----------
const nameEl = document.getElementById('name'), countEl = document.getElementById('count'), distEl = document.getElementById('dist');
let frameNo = 0;

function frame() {
  const t = Date.now() / 1000;      // horloge commune à toutes les fenêtres
  const s = publish(false);
  readStorage(); prune();

  const orbs = [s, ...others.values()].sort((a, b) => a.born - b.born);
  for (const o of orbs) { o.lx = o.x - s.left; o.ly = o.y - s.top; }

  // fusion des couleurs selon la proximité
  const base = orbs.map(o => PALETTE[o.c % PALETTE.length]);
  const cols = orbs.map((o, i) => {
    let c = base[i];
    orbs.forEach((q, j) => {
      if (i === j) return;
      const w = clamp(1 - Math.hypot(q.x - o.x, q.y - o.y) / ((o.r + q.r) * 1.8), 0, 1);
      c = mix(c, base[j], w * 0.5);
    });
    return c;
  });

  // fond avec légère traînée
  ctx.globalCompositeOperation = 'source-over';
  ctx.fillStyle = 'rgba(2,4,10,0.38)';
  ctx.fillRect(0, 0, W, H);
  drawStars(s);

  ctx.globalCompositeOperation = 'lighter'; // les couleurs s'additionnent = fusion
  for (let i = 0; i < orbs.length; i++)
    for (let j = i + 1; j < orbs.length; j++)
      drawLink(orbs[i], orbs[j], cols[i], cols[j], t, (orbs[i].born + orbs[j].born) % 97);

  orbs.forEach((o, i) => {
    const pulls = [];
    orbs.forEach((q, j) => {
      if (i === j) return;
      const dx = q.x - o.x, dy = q.y - o.y, D = Math.hypot(dx, dy) || 1;
      const k = clamp(1 - D / ((o.r + q.r) * 2.4), 0, 1);
      if (k > 0) pulls.push({ ux: dx / D, uy: dy / D, k });
    });
    drawOrb(o, o.lx, o.ly, cols[i], t, pulls);
  });

  // HUD
  if (frameNo++ % 10 === 0) {
    const c = PALETTE[colorIdx % PALETTE.length];
    document.documentElement.style.setProperty('--c', rgba(c, 1));
    nameEl.textContent = `Nœud ${NAMES[colorIdx % NAMES.length]} · ${ID}`;
    countEl.textContent = `Fenêtres liées : ${orbs.length}`;
    let best = Infinity;
    for (const q of others.values()) best = Math.min(best, Math.hypot(q.x - s.x, q.y - s.y));
    distEl.textContent = isFinite(best) ? `Distance au plus proche : ${Math.round(best)} px` : 'En attente d’une autre fenêtre…';
  }
  requestAnimationFrame(frame);
}

// ---------- Bouton nouvelle fenêtre ----------
document.getElementById('open').addEventListener('click', () => {
  const w = Math.round(innerWidth * 0.7), h = Math.round(innerHeight * 0.7);
  let win = null;
  try { win = window.open(location.href, '_blank', `popup,width=${w},height=${h},left=${screenX + 80},top=${screenY + 80}`); } catch (e) {}
  if (!win) document.getElementById('tip').textContent = 'Popup bloquée : copie l’adresse de cette page et ouvre-la dans une nouvelle fenêtre (Ctrl+N / Cmd+N).';
});

// ---------- Démarrage : on écoute qui est déjà là, puis on choisit une couleur libre ----------
resize();
readStorage();
try { bc && bc.postMessage({ id: ID, ping: true }); } catch (e) {}
setTimeout(() => {
  readStorage(); prune();
  const used = new Set([...others.values()].map(o => o.c));
  colorIdx = PALETTE.findIndex((_, i) => !used.has(i));
  if (colorIdx < 0) colorIdx = Math.floor(Math.random() * PALETTE.length);
  publish(true);
  requestAnimationFrame(frame);
}, 180);
}
