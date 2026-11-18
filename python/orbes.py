import atexit
import math
import os
import random
import sys
import tempfile
import time
import uuid

import numpy as np
from PySide6.QtCore import QLineF, QPoint, QPointF, QRectF, Qt, QTimer
from PySide6.QtGui import (QBrush, QColor, QFont, QImage, QLinearGradient,
                           QPainter, QPainterPath, QPen, QRadialGradient)
from PySide6.QtWidgets import QApplication, QWidget

PALETTE = [(40, 160, 255), (40, 255, 150), (255, 70, 200), (255, 160, 40), (160, 100, 255), (255, 235, 80)]
NAMES = ["Bleu", "Vert", "Magenta", "Orange", "Violet", "Jaune"]
DIR = os.path.join(tempfile.gettempdir(), "orbes")
STALE = 3000          # ms sans signal = fenêtre fermée
N_POINTS = 700

# Fibonacci's Sphere
IDX = np.arange(N_POINTS)
_y = 1 - IDX / (N_POINTS - 1) * 2
_r = np.sqrt(1 - _y * _y)
_th = IDX * 2.399963
PX, PY, PZ = np.cos(_th) * _r, _y, np.sin(_th) * _r

RING_J = np.array([j for j in range(180) if j % 22 >= 3])

MY_IDS = set()


def now_ms():
    return int(time.time() * 1000)


def clamp(v, a, b):
    return a if v < a else b if v > b else v


def mix(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)


def qc(c, a):
    return QColor(int(clamp(c[0], 0, 255)), int(clamp(c[1], 0, 255)), int(clamp(c[2], 0, 255)), int(clamp(a * 255, 0, 255)))


def hsh(a, b):
    n = math.sin(a * 127.1 + b * 311.7) * 43758.5453
    return n - math.floor(n)


class Node:
    __slots__ = ("id", "c", "born", "ts", "x", "y", "left", "top", "r", "lx", "ly")

    def serialize(self):
        return f"{self.id};{self.c};{self.born};{self.ts};{float(self.x)};{float(self.y)};{float(self.left)};{float(self.top)};{float(self.r)}"

    @staticmethod
    def parse(s):
        p = s.strip().split(";")
        if len(p) < 9:
            return None
        n = Node()
        n.id, n.c, n.born, n.ts = p[0], int(p[1]), int(p[2]), int(p[3])
        n.x, n.y, n.left, n.top, n.r = map(float, p[4:9])
        return n


def publish(n):
    try:
        os.makedirs(DIR, exist_ok=True)
        tmp, dst = os.path.join(DIR, n.id + ".tmp"), os.path.join(DIR, n.id + ".orb")
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(n.serialize())
        os.replace(tmp, dst)   # remplacement atomique : personne ne lit un fichier à moitié écrit
    except OSError:
        pass


def remove(node_id):
    for ext in (".orb", ".tmp"):
        try:
            os.remove(os.path.join(DIR, node_id + ext))
        except OSError:
            pass


def read_others(self_id):
    out = []
    now = now_ms()
    try:
        names = os.listdir(DIR)
    except OSError:
        return out
    for name in names:
        if not name.endswith(".orb"):
            continue
        path = os.path.join(DIR, name)
        try:
            with open(path, encoding="utf-8") as f:
                n = Node.parse(f.read())
        except (OSError, ValueError):
            continue
        if n is None or n.id == self_id:
            continue
        if now - n.ts > STALE:
            if now - n.ts > STALE * 5:   # fenêtre plantée
                try:
                    os.remove(path)
                except OSError:
                    pass
            continue
        out.append(n)
    return out


@atexit.register
def _cleanup():
    for i in list(MY_IDS):
        remove(i)

class OrbWindow(QWidget):
    windows = []

    def __init__(self, x, y, w, h):
        super().__init__()
        self.setWindowTitle("Orbes multi-fenêtres")
        self.setAttribute(Qt.WA_DeleteOnClose)
        self.resize(w, h)
        self.move(x, y)

        me = self.me = Node()
        me.id = uuid.uuid4().hex[:6]
        me.born = now_ms()
        me.ts = me.born
        me.x = me.y = me.left = me.top = 0.0
        me.r = 100.0
        MY_IDS.add(me.id)

        used = {n.c for n in read_others(me.id)}
        free = [k for k in range(len(PALETTE)) if k not in used]
        me.c = free[0] if free else random.randrange(len(PALETTE))

        self.img = None
        self.W, self.H = w, h
        self.others = []
        self.last_write = 0
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.tick)
        self.timer.start(16)
        OrbWindow.windows.append(self)
        self.show()

    def tick(self):
        w, h = max(1, self.width()), max(1, self.height())
        dpr = self.devicePixelRatioF()
        if self.img is None or self.img.width() != int(w * dpr) or self.img.height() != int(h * dpr):
            self.img = QImage(int(w * dpr), int(h * dpr), QImage.Format_RGB32)
            self.img.setDevicePixelRatio(dpr)
            self.img.fill(QColor(2, 4, 10))
        self.W, self.H = w, h

        p = self.mapToGlobal(QPoint(0, 0))   # coin haut-gauche du contenu, sur l'écran
        me, t_ms = self.me, now_ms()
        me.left, me.top = float(p.x()), float(p.y())
        me.x, me.y = me.left + w / 2, me.top + h / 2
        me.r = clamp(min(w, h) * 0.2, 50, 170)
        me.ts = t_ms
        if t_ms - self.last_write > 50:
            publish(me)
            self.last_write = t_ms
        self.others = read_others(me.id)
        self.render(t_ms / 1000)   # horloge commune à toutes les fenêtres
        self.update()

    def paintEvent(self, _):
        if self.img is not None:
            p = QPainter(self)
            p.drawImage(0, 0, self.img)
            p.end()

    def keyPressEvent(self, e):
        if e.key() == Qt.Key_N:
            OrbWindow(self.x() + 80, self.y() + 80, max(400, int(self.W * 0.8)), max(300, int(self.H * 0.8)))
        elif e.key() == Qt.Key_Escape:
            self.close()

    def closeEvent(self, e):
        self.timer.stop()
        remove(self.me.id)
        MY_IDS.discard(self.me.id)
        if self in OrbWindow.windows:
            OrbWindow.windows.remove(self)
        super().closeEvent(e)

    def render(self, t):
        me = self.me
        orbs = sorted(self.others + [me], key=lambda n: n.born)
        for o in orbs:
            o.lx, o.ly = o.x - me.left, o.y - me.top

        # Fusion des couleurs selon la proximité
        base = [PALETTE[o.c % len(PALETTE)] for o in orbs]
        cols = []
        for i, o in enumerate(orbs):
            c = base[i]
            for j, q in enumerate(orbs):
                if i != j:
                    w = clamp(1 - math.hypot(q.x - o.x, q.y - o.y) / ((o.r + q.r) * 1.8), 0, 1)
                    c = mix(c, base[j], w * 0.5)
            cols.append(c)

        p = QPainter(self.img)
        # fond avec légère traînée
        p.setCompositionMode(QPainter.CompositionMode_SourceOver)
        p.fillRect(QRectF(0, 0, self.W, self.H), QColor(2, 4, 10, 97))
        self.draw_stars(p)

        # les couleurs s'additionnent = fusion
        p.setCompositionMode(QPainter.CompositionMode_Plus)
        for i in range(len(orbs)):
            for j in range(i + 1, len(orbs)):
                self.draw_link(p, orbs[i], orbs[j], cols[i], cols[j], t, (orbs[i].born + orbs[j].born) % 97)

        for i, o in enumerate(orbs):
            pulls = []
            for j, q in enumerate(orbs):
                if i == j:
                    continue
                dx, dy = q.x - o.x, q.y - o.y
                D = max(1.0, math.hypot(dx, dy))
                k = clamp(1 - D / ((o.r + q.r) * 2.4), 0, 1)
                if k > 0:
                    pulls.append((dx / D, dy / D, k))
            self.draw_orb(p, o, cols[i], t, pulls)

        p.setCompositionMode(QPainter.CompositionMode_SourceOver)
        self.draw_hud(p, orbs)
        p.end()

    def draw_stars(self, p):
        # étoiles fixées à l'écran : elles ne bougent pas quand on déplace la fenêtre
        me, cell = self.me, 110
        x0, y0 = math.floor(me.left / cell), math.floor(me.top / cell)
        x1, y1 = math.ceil((me.left + self.W) / cell), math.ceil((me.top + self.H) / cell)
        for gx in range(x0, x1 + 1):
            for gy in range(y0, y1 + 1):
                sx = (gx + hsh(gx, gy)) * cell - me.left
                sy = (gy + hsh(gy, gx)) * cell - me.top
                p.fillRect(QRectF(sx, sy, 1.2, 1.2), qc((156, 200, 255), 0.15 + hsh(gx * 3, gy * 7) * 0.35))

    # ----- Jarvis sphere -----
    def draw_orb(self, p, o, col, t, pulls):
        R, ph, cx, cy = o.r, o.c * 1.7, o.lx, o.ly

        # halo
        g = QRadialGradient(QPointF(cx, cy), R * 1.7)
        g.setColorAt(0, qc(col, .28))
        g.setColorAt(.35, qc(col, .09))
        g.setColorAt(1, qc(col, 0))
        p.fillRect(QRectF(cx - R * 1.7, cy - R * 1.7, R * 3.4, R * 3.4), QBrush(g))

        # 3D Points (Vectorial cal with NumPy)
        ay, ax = t * 0.35 + ph, 0.45 + math.sin(t * 0.23 + ph) * 0.35
        cY, sY, cX, sX = math.cos(ay), math.sin(ay), math.cos(ax), math.sin(ax)
        r = 1 + 0.05 * np.sin(t * 2.1 + IDX * 0.37) + 0.03 * np.sin(t * 3.7 + IDX * 1.3)
        x, y, z = PX * r, PY * r, PZ * r
        x1, z1 = x * cY - z * sY, x * sY + z * cY
        y1, z2 = y * cX - z1 * sX, y * sX + z1 * cX
        s = 2.4 / (2.4 + z2)
        px, py = x1 * R * s, y1 * R * s
        for ux, uy, k in pulls:   # les points s'étirent vers les autres sphères
            d = (px * ux + py * uy) / R
            f = np.where(d > 0, k * d ** 3 * R * 0.7, 0.0)
            px, py = px + ux * f, py + uy * f
        front = 1 - (z2 + 1) / 2
        size = 0.7 + 1.7 * front
        X, Y = cx + px - size / 2, cy + py - size / 2
        bucket = np.minimum((front * 6).astype(int), 5)   # 6 tranches de profondeur
        p.setPen(Qt.NoPen)
        for b in range(6):
            m = bucket == b
            if not m.any():
                continue
            p.setBrush(qc(col, 0.18 + 0.8 * (b + 0.5) / 6))
            p.drawRects([QRectF(a, c, w, w) for a, c, w in zip(X[m].tolist(), Y[m].tolist(), size[m].tolist())])

        # anneaux pointillés en orbite
        for k in range(2):
            rr = R * (1.35 + 0.28 * k)
            tilt, flat = (-0.5, 0.32) if k else (0.35, 0.22)
            spin = t * (-0.55 if k else 0.45) + ph
            ct, st = math.cos(tilt), math.sin(tilt)
            a = RING_J / 180 * math.pi * 2 + spin
            ex, ey = np.cos(a) * rr, np.sin(a) * rr * flat
            rx, ry = cx + ex * ct - ey * st, cy + ex * st + ey * ct
            lvl = np.minimum(((np.sin(a) * 0.5 + 0.5) * 3).astype(int), 2)
            for b in range(3):
                m = lvl == b
                p.setBrush(qc(col, 0.25 + 0.45 * (b + 0.5) / 3))
                p.drawRects([QRectF(a_ - .7, c_ - .7, 1.4, 1.4) for a_, c_ in zip(rx[m].tolist(), ry[m].tolist())])
        p.setBrush(Qt.NoBrush)

        p.setRenderHint(QPainter.Antialiasing, True)
        self.arc(p, cx, cy, R * 1.95, t * .3 + ph, 1.1, qc(col, .45))
        self.arc(p, cx, cy, R * 1.95, t * .3 + ph + math.pi, .6, qc(col, .45))
        self.arc(p, cx, cy, R * 2.12, -t * .18, 2.2, qc(col, .3))
        lines = []
        for j in range(72):
            a, l = j / 72 * math.pi * 2 - t * 0.08, (10 if j % 6 == 0 else 4)
            ca, sa = math.cos(a), math.sin(a)
            lines.append(QLineF(cx + ca * R * 2.25, cy + sa * R * 2.25, cx + ca * (R * 2.25 + l), cy + sa * (R * 2.25 + l)))
        p.setPen(QPen(qc(col, .22), 1))
        p.drawLines(lines)
        p.setRenderHint(QPainter.Antialiasing, False)

    @staticmethod
    def arc(p, cx, cy, r, start, length, color):
        p.setPen(QPen(color, 1))
        # Qt compte les angles en 1/16 de degré, dans le sens inverse des aiguilles d'une montre
        start %= 2 * math.pi   # garde l'angle petit (l'horloge commune donne de très grands nombres)
        p.drawArc(QRectF(cx - r, cy - r, 2 * r, 2 * r), int(-math.degrees(start) * 16), int(-math.degrees(length) * 16))

    # ----- Link between two windows -----
    def draw_link(self, p, A, B, cA, cB, t, seed):
        dx, dy = B.lx - A.lx, B.ly - A.ly
        D = math.hypot(dx, dy)
        if D < 2:
            return
        nx, ny = -dy / D, dx / D
        mx, my = (A.lx + B.lx) / 2, (A.ly + B.ly) / 2

        def curve(u, off):
            qx, qy, v = mx + nx * off, my + ny * off, 1 - u
            return (v * v * A.lx + 2 * v * u * qx + u * u * B.lx, v * v * A.ly + 2 * v * u * qy + u * u * B.ly)

        def pen(a, width):
            g = QLinearGradient(QPointF(A.lx, A.ly), QPointF(B.lx, B.ly))
            g.setColorAt(0, qc(cA, a))
            g.setColorAt(1, qc(cB, a))
            pn = QPen(QBrush(g), width)
            pn.setCapStyle(Qt.RoundCap)
            return pn

        p.setRenderHint(QPainter.Antialiasing, True)
        p.setBrush(Qt.NoBrush)
        for k in range(4):
            off = math.sin(t * (0.9 + k * 0.37) + seed + k * 2.1) * D * (0.04 + k * 0.035)
            path = QPainterPath()
            for s in range(41):
                x, y = curve(s / 40, off)
                if s == 0:
                    path.moveTo(x, y)
                else:
                    path.lineTo(x, y)
            if k == 0:
                p.strokePath(path, pen(.07, 9))
                p.strokePath(path, pen(.75, 1.6))
            else:
                p.strokePath(path, pen(.28, .8))
        p.setRenderHint(QPainter.Antialiasing, False)

        # Double way of particles
        off0 = math.sin(t * 0.9 + seed) * D * 0.04
        for m in range(46):
            u = (t * 0.22 + m / 46 + hsh(m, seed) * 0.1) % 1
            if m % 2:
                u = 1 - u
            x, y = curve(u, off0)
            w = math.sin(u * math.pi * 7 + t * 4 + m) * 7
            p.fillRect(QRectF(x + nx * w - 1.2, y + ny * w - 1.2, 2.4, 2.4), qc(mix(cA, cB, u), math.sin(u * math.pi) * 0.9))

    # ----- Information text-----
    def draw_hud(self, p, orbs):
        me = self.me
        c = PALETTE[me.c % len(PALETTE)]
        p.setRenderHint(QPainter.Antialiasing, True)
        font = QFont("monospace")
        font.setStyleHint(QFont.Monospace)
        font.setPixelSize(14)
        p.setFont(font)
        p.setPen(Qt.NoPen)
        p.setBrush(qc(c, 1))
        p.drawEllipse(QRectF(16, 16, 9, 9))
        p.setPen(qc(c, 1))
        p.drawText(QPointF(32, 25), f"Nœud {NAMES[me.c % len(NAMES)]} · {me.id}")
        font.setPixelSize(13)
        p.setFont(font)
        p.setPen(QColor(191, 230, 255, 220))
        p.drawText(QPointF(16, 46), f"Fenêtres liées : {len(orbs)}")
        dists = [math.hypot(q.x - me.x, q.y - me.y) for q in self.others]
        p.drawText(QPointF(16, 66), f"Distance au plus proche : {round(min(dists))} px" if dists else "En attente d'une autre fenêtre…")
        p.setPen(QColor(191, 230, 255, 150))
        p.drawText(QPointF(16, self.H - 16), "N : nouvelle fenêtre   ·   Échap : fermer")
        p.setRenderHint(QPainter.Antialiasing, False)


def main():
    app = QApplication(sys.argv)
    geo = app.primaryScreen().availableGeometry()
    w, h = 900, 620
    x = geo.x() + random.randint(0, max(0, geo.width() - w))
    y = geo.y() + random.randint(0, max(0, geo.height() - h))
    OrbWindow(x, y, w, h)
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
