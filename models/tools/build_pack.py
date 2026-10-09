#!/usr/bin/env python3
"""Genera el resource pack de GamblingDex (src/main/resources/pack/).

- Fondo del menu de slots (fuente con espaciado negativo + imagen).
- Sonidos propios de los slots (.ogg sintetizados con numpy + ffmpeg/libvorbis).
- pack.mcmeta, pack.png y pack/files.txt (lista que el plugin usa para extraerlo).

Uso: python3 models/tools/build_pack.py
"""
import json
import os
import subprocess
import tempfile
import wave

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
PACK = os.path.join(ROOT, 'src', 'main', 'resources', 'pack')
NS = os.path.join(PACK, 'assets', 'gamblingdex')

# Caracteres de la fuente gamblingdex:gui (los mismos que usa SlotsController)
CH_BACK_8 = ''      # -8 px: del inicio del titulo al borde izquierdo del menu
CH_BACK_169 = ''    # -169 px: tras el fondo (176+1) vuelve a x=8 para el texto
CH_SLOTS_BG = ''    # fondo del menu de slots
CH_EXCHANGE_BG = '\uE101' # fondo del menu de cambio
CH_POKER_ACTION_BG = '\uE102'  # menu de acciones del poker
CH_POKER_BUYIN_BG = '\uE103'   # menu de comprar fichas del poker
CH_BJ_ACTION_BG = '\uE104'     # menu de acciones del blackjack
CH_BJ_BET_BG = '\uE105'        # menu de apuestas del blackjack


def mkdir(p):
    os.makedirs(p, exist_ok=True)


# ----------------------------------------------------------------------------
# Fondo del menu de slots (176 x 130: titulo + 6 filas de casillas)
# Casilla (fila r, col c): borde exterior en x=7+18c, y=17+18r, 18x18.
# ----------------------------------------------------------------------------
RED, RED_D, RED_L = (142, 20, 32), (98, 10, 22), (176, 34, 46)
GOLD, GOLD_D, GOLD_L = (232, 180, 32), (160, 112, 10), (255, 222, 90)
CREAM, CREAM_D = (246, 241, 228), (205, 196, 176)
DARK, DARK_L = (24, 22, 30), (52, 48, 62)
BULB_ON, BULB_OFF = (255, 236, 120), (190, 150, 40)


def slot_box(r, c):
    x, y = 7 + 18 * c, 17 + 18 * r
    return x, y, x + 17, y + 17


def bevel(d, box, light, dark, fill=None):
    x1, y1, x2, y2 = box
    if fill:
        d.rectangle(box, fill=fill)
    d.line([(x1, y1), (x2, y1)], fill=light)
    d.line([(x1, y1), (x1, y2)], fill=light)
    d.line([(x1, y2), (x2, y2)], fill=dark)
    d.line([(x2, y1), (x2, y2)], fill=dark)


def item_slot(d, r, c, frame=GOLD):
    """Casilla oscura con marco (para botones e info)."""
    x1, y1, x2, y2 = slot_box(r, c)
    d.rectangle((x1, y1, x2, y2), fill=frame)
    bevel(d, (x1 + 1, y1 + 1, x2 - 1, y2 - 1), DARK, DARK_L, fill=(40, 36, 48))


def slots_background():
    w, h = 176, 130
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    rng = np.random.default_rng(7)
    # Mueble rojo con textura y borde dorado redondeado
    d.rounded_rectangle((0, 0, w - 1, h - 1), radius=4, fill=GOLD_D)
    d.rounded_rectangle((1, 1, w - 2, h - 2), radius=3, fill=GOLD)
    d.rectangle((3, 3, w - 4, h - 4), fill=RED)
    for _ in range(900):
        x, y = rng.integers(3, w - 3), rng.integers(3, h - 3)
        d.point((int(x), int(y)), fill=[RED_D, RED_L, (128, 16, 28)][rng.integers(0, 3)])
    # Franja del titulo
    d.rectangle((3, 3, w - 4, 15), fill=(26, 10, 16))
    d.line([(3, 16), (w - 4, 16)], fill=GOLD)
    for x in range(5, w - 4, 4):
        d.point((x, 4), fill=BULB_ON if (x // 4) % 2 else BULB_OFF)
        d.point((x + 2, 14), fill=BULB_ON if (x // 4) % 2 == 0 else BULB_OFF)

    # Fila 0: marquesina con bombillos; casilla 4 = jackpot
    for c in range(9):
        if c == 4:
            continue
        x1, y1, x2, y2 = slot_box(0, c)
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        d.ellipse((cx - 4, cy - 4, cx + 4, cy + 4), fill=GOLD_D)
        d.ellipse((cx - 3, cy - 3, cx + 3, cy + 3), fill=BULB_ON if c % 2 else BULB_OFF)
        d.point((cx - 1, cy - 1), fill=(255, 255, 255))
    x1, y1, x2, y2 = slot_box(0, 4)
    d.rectangle((x1 - 2, y1 - 1, x2 + 2, y2 + 1), fill=GOLD_L)
    bevel(d, (x1, y1, x2, y2), DARK, DARK_L, fill=(40, 36, 48))

    # Filas 1-3: ventana de rodillos (columnas 2, 4, 6)
    wx1, wy1 = slot_box(1, 2)[0] - 3, slot_box(1, 2)[1] - 3
    wx2, wy2 = slot_box(3, 6)[2] + 3, slot_box(3, 6)[3] + 3
    d.rectangle((wx1 - 1, wy1 - 1, wx2 + 1, wy2 + 1), fill=GOLD_D)
    d.rectangle((wx1, wy1, wx2, wy2), fill=GOLD)
    d.rectangle((wx1 + 2, wy1 + 2, wx2 - 2, wy2 - 2), fill=DARK)
    for c in (2, 4, 6):
        x1 = slot_box(1, c)[0] - 1
        x2 = slot_box(3, c)[2] + 1
        y1, y2 = slot_box(1, c)[1], slot_box(3, c)[3]
        d.rectangle((x1, y1, x2, y2), fill=CREAM)
        # sombra arriba/abajo: da sensacion de cilindro
        for i, col in enumerate([(150, 144, 130), (180, 172, 156), (205, 196, 176), (226, 219, 202)]):
            d.line([(x1, y1 + i), (x2, y1 + i)], fill=col)
            d.line([(x1, y2 - i), (x2, y2 - i)], fill=col)
    for c in (3, 5):  # separadores dorados entre rodillos
        x1, _, x2, _ = slot_box(1, c)
        d.rectangle((x1 + 2, wy1 + 2, x2 - 2, wy2 - 2), fill=RED_D)
        d.line([((x1 + x2) // 2, wy1 + 2), ((x1 + x2) // 2, wy2 - 2)], fill=GOLD)
    # Linea de pago (fila 2) + flechas en las columnas 1 y 7
    _, py1, _, py2 = slot_box(2, 2)
    py = (py1 + py2) // 2
    for c in (1, 7):
        x1, y1, x2, y2 = slot_box(2, c)
        d.rectangle((x1, y1, x2, y2), fill=RED)
        cx = (x1 + x2) // 2
        if c == 1:
            d.polygon([(cx - 5, py - 6), (cx + 6, py), (cx - 5, py + 6)], fill=GOLD_L, outline=GOLD_D)
        else:
            d.polygon([(cx + 5, py - 6), (cx - 6, py), (cx + 5, py + 6)], fill=GOLD_L, outline=GOLD_D)
    d.line([(wx1 + 2, py), (wx2 - 2, py)], fill=(220, 20, 40))

    # Laterales: tabla de pagos, fichas, ultimo resultado, estadisticas
    for r, c in ((1, 0), (1, 8), (3, 0), (3, 8)):
        item_slot(d, r, c)

    # Fila 4: panel de control con luces
    x1, y1, _, _ = slot_box(4, 0)
    _, _, x2, y2 = slot_box(4, 8)
    d.rectangle((x1, y1 + 2, x2, y2 - 2), fill=DARK)
    d.line([(x1, y1 + 2), (x2, y1 + 2)], fill=GOLD)
    d.line([(x1, y2 - 2), (x2, y2 - 2)], fill=GOLD_D)
    for i, x in enumerate(range(x1 + 5, x2 - 2, 8)):
        cy = (y1 + y2) // 2
        d.rectangle((x - 1, cy - 1, x + 1, cy + 1), fill=[(220, 40, 40), BULB_ON, (60, 200, 90)][i % 3])

    # Fila 5: botones (45..49 y 53) + bandeja de monedas (50..52)
    for c in range(5):
        item_slot(d, 5, c, frame=GOLD if c != 2 else GOLD_L)
    item_slot(d, 5, 8, frame=(60, 200, 90))
    tx1, ty1, _, _ = slot_box(5, 5)
    _, _, tx2, ty2 = slot_box(5, 7)
    d.rectangle((tx1 + 1, ty1 + 3, tx2 - 1, ty2 - 1), fill=GOLD_D)
    d.rectangle((tx1 + 2, ty1 + 4, tx2 - 2, ty2 - 2), fill=(30, 26, 20))
    for i, x in enumerate(range(tx1 + 6, tx2 - 4, 7)):
        y = ty2 - 5 - (i % 2) * 2
        d.ellipse((x - 2, y - 2, x + 2, y + 2), fill=GOLD, outline=GOLD_D)
        d.point((x - 1, y - 1), fill=GOLD_L)
    return img


# ----------------------------------------------------------------------------
# Sonidos (sintetizados): 44.1 kHz mono -> .ogg (libvorbis)
# ----------------------------------------------------------------------------
SR = 44100


def env(n, a=0.005, r=0.1):
    t = np.arange(n) / SR
    e = np.minimum(1, t / max(a, 1e-4)) * np.exp(-t / max(r, 1e-4))
    return e


def tone(freq, dur, kind='sine', a=0.005, r=0.2, vol=0.6):
    n = int(SR * dur)
    t = np.arange(n) / SR
    if kind == 'square':
        w = np.sign(np.sin(2 * np.pi * freq * t)) * 0.5
    elif kind == 'bell':
        w = (np.sin(2 * np.pi * freq * t) + 0.5 * np.sin(2 * np.pi * freq * 2.76 * t)
             + 0.25 * np.sin(2 * np.pi * freq * 5.4 * t)) / 1.75
    else:
        w = np.sin(2 * np.pi * freq * t)
    return w * env(n, a, r) * vol


def noise(dur, r=0.03, vol=0.5, lp=0.0, seed=1):
    n = int(SR * dur)
    x = np.random.default_rng(seed).uniform(-1, 1, n)
    if lp > 0:  # filtro paso bajo simple
        y = np.zeros(n)
        for i in range(1, n):
            y[i] = y[i - 1] + lp * (x[i] - y[i - 1])
        x = y / max(1e-6, np.abs(y).max())
    return x * env(n, 0.001, r) * vol


def mix(total, *parts):
    out = np.zeros(int(SR * total))
    for start, sig in parts:
        i = int(SR * start)
        j = min(len(out), i + len(sig))
        out[i:j] += sig[:j - i]
    peak = np.abs(out).max()
    return out / peak * 0.9 if peak > 0 else out


def write_ogg(path, sig):
    if os.path.exists(path) and not os.environ.get('FORCE_SOUNDS'):
        return  # libvorbis no da el mismo archivo dos veces: solo se regenera con FORCE_SOUNDS=1
    mkdir(os.path.dirname(path))
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
        tmp = f.name
    with wave.open(tmp, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(sig, -1, 1) * 32767).astype('<i2').tobytes())
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', tmp, '-c:a', 'libvorbis', '-q:a', '4', path], check=True)
    os.remove(tmp)


C5, E5, G5, C6, E6, G6 = 523.25, 659.25, 783.99, 1046.5, 1318.5, 1568.0


# ----------------------------------------------------------------------------
# Fondo del menu de cambio (ExchangeMenu): verde y dorado como el cajero 3D
# 0..8 luces (4 = tu info) | 11 COMPRAR, 15 VENDER | 19..25, 28..34 fichas
# 37..43 cantidad (40 = cantidad) | 45 cerrar, 47 todo, 49 CONFIRMAR, 51 vender todas, 53 tasas
# ----------------------------------------------------------------------------
GRN, GRN_D, GRN_L = (16, 88, 52), (10, 60, 34), (28, 112, 68)


def exchange_background():
    w, h = 176, 130
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    rng = np.random.default_rng(13)
    d.rounded_rectangle((0, 0, w - 1, h - 1), radius=4, fill=GOLD_D)
    d.rounded_rectangle((1, 1, w - 2, h - 2), radius=3, fill=GOLD)
    d.rectangle((3, 3, w - 4, h - 4), fill=GRN)
    for _ in range(900):
        x, y = rng.integers(3, w - 3), rng.integers(3, h - 3)
        d.point((int(x), int(y)), fill=[GRN_D, GRN_L, (13, 74, 44)][rng.integers(0, 3)])
    # Franja del titulo con bombillos verdes
    d.rectangle((3, 3, w - 4, 15), fill=(6, 32, 15))
    d.line([(3, 16), (w - 4, 16)], fill=GOLD)
    for x in range(5, w - 4, 4):
        d.point((x, 4), fill=(92, 255, 122) if (x // 4) % 2 else (40, 140, 60))
        d.point((x + 2, 14), fill=(92, 255, 122) if (x // 4) % 2 == 0 else (40, 140, 60))
    # Fila 0: bombillos; 4 = tu info
    for c in range(9):
        x1, y1, x2, y2 = slot_box(0, c)
        if c == 4:
            d.rectangle((x1 - 2, y1 - 1, x2 + 2, y2 + 1), fill=GOLD_L)
            bevel(d, (x1, y1, x2, y2), DARK, DARK_L, fill=(40, 36, 48))
            continue
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        d.ellipse((cx - 4, cy - 4, cx + 4, cy + 4), fill=GOLD_D)
        d.ellipse((cx - 3, cy - 3, cx + 3, cy + 3), fill=(92, 255, 122) if c % 2 else (40, 140, 60))
        d.point((cx - 1, cy - 1), fill=(220, 255, 220))
    # Fila 1: pestañas COMPRAR (verde) y VENDER (naranja)
    for c, frame in ((2, (60, 200, 90)), (6, (240, 150, 40))):
        x1, y1, x2, y2 = slot_box(1, c)
        d.rectangle((x1 - 3, y1 - 1, x2 + 3, y2 + 1), fill=frame)
        bevel(d, (x1, y1, x2, y2), DARK, DARK_L, fill=(40, 36, 48))
    # Filas 2-3: vitrina de fichas (columnas 1..7) con fieltro rojo
    vx1, vy1 = slot_box(2, 1)[0] - 3, slot_box(2, 1)[1] - 3
    vx2, vy2 = slot_box(3, 7)[2] + 3, slot_box(3, 7)[3] + 3
    d.rectangle((vx1 - 1, vy1 - 1, vx2 + 1, vy2 + 1), fill=GOLD_D)
    d.rectangle((vx1, vy1, vx2, vy2), fill=GOLD)
    d.rectangle((vx1 + 2, vy1 + 2, vx2 - 2, vy2 - 2), fill=(90, 15, 24))
    for r in (2, 3):
        for c in range(1, 8):
            x1, y1, x2, y2 = slot_box(r, c)
            bevel(d, (x1, y1, x2, y2), (60, 8, 16), (130, 30, 40))
    # Fila 4: cantidad (37..43), 40 destacada
    x1, y1, _, _ = slot_box(4, 1)
    _, _, x2, y2 = slot_box(4, 7)
    d.rectangle((x1 - 2, y1 - 1, x2 + 2, y2 + 1), fill=(6, 32, 15))
    for c in range(1, 8):
        item_slot(d, 4, c, frame=GOLD_L if c == 4 else GOLD_D)
    # Fila 5: cerrar, todo, CONFIRMAR, vender todas, tasas
    for c, frame in ((0, (200, 50, 50)), (2, GOLD_D), (4, (60, 200, 90)), (6, GOLD_D), (8, GOLD_D)):
        item_slot(d, 5, c, frame=frame)
    x1, y1, x2, y2 = slot_box(5, 4)
    d.rectangle((x1 - 2, y1 - 2, x2 + 2, y2 + 2), outline=GOLD_L)
    return img


# ----------------------------------------------------------------------------
# Fondos de los menus de poker y blackjack: pano verde con borde de madera, botones
# de color segun la accion (mismas casillas que PokerActionMenu, PokerBuyInMenu,
# BlackjackActionMenu y BlackjackBetMenu).
# ----------------------------------------------------------------------------
WOOD, WOOD_D, WOOD_L = (107, 61, 23), (70, 40, 14), (130, 78, 32)
RED_B, GREEN_B, GOLD_B, BLUE_B, ORANGE_B, GREY_B = (210, 40, 46), (60, 190, 80), (240, 190, 50), (60, 130, 220), (240, 120, 30), (130, 130, 140)


def felt_menu(rows):
    """Base: marco de madera, franja del titulo, filete dorado doble y pano verde con vineta."""
    w, h = 176, 17 + rows * 18 + 7
    rng = np.random.default_rng(rows * 7 + 1)
    # Pano: verde con grano fino y mas oscuro hacia los bordes (luz de mesa en el centro)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    dist = np.sqrt(((xx - w / 2) / (w / 2)) ** 2 + ((yy - (h + 17) / 2) / (h / 2)) ** 2)
    light = np.clip(1.08 - 0.38 * dist ** 2, 0.62, 1.08)
    grain = rng.normal(0, 0.035, (h, w))
    base = np.array(GRN, np.float32)
    px = np.clip(base[None, None, :] * (light + grain)[:, :, None], 0, 255).astype(np.uint8)
    img = Image.fromarray(np.dstack([px, np.full((h, w), 255, np.uint8)]), 'RGBA')
    d = ImageDraw.Draw(img)
    # Marco de madera con vetas horizontales
    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=4, fill=255)
    frame = Image.new('RGBA', (w, h), WOOD + (255,))
    fd = ImageDraw.Draw(frame)
    for y in range(h):
        if rng.random() < 0.35:
            fd.line([(0, y), (w, y)], fill=[WOOD_D, WOOD_L][int(rng.integers(0, 2))])
    inner = Image.new('L', (w, h), 255)
    ImageDraw.Draw(inner).rectangle((4, 17, w - 5, h - 5), fill=0)
    img.paste(frame, (0, 0), inner)
    d.rounded_rectangle((0, 0, w - 1, h - 1), radius=4, outline=WOOD_D)
    # Franja del titulo
    d.rectangle((4, 3, w - 5, 14), fill=(14, 12, 10))
    d.line([(4, 3), (w - 5, 3)], fill=(40, 34, 26))
    # Filete dorado doble alrededor del pano
    d.rectangle((4, 16, w - 5, h - 5), outline=GOLD_D)
    d.rectangle((5, 17, w - 6, h - 6), outline=GOLD)
    d.line([(5, 17), (w - 6, 17)], fill=GOLD_L)
    img.putalpha(Image.fromarray(np.minimum(np.array(img)[:, :, 3], np.array(mask))))
    return img, ImageDraw.Draw(img)


# Letras de 3x5 para los rotulos de los botones
FONT = {
    'A': ['010', '101', '111', '101', '101'], 'B': ['110', '101', '110', '101', '110'],
    'C': ['011', '100', '100', '100', '011'], 'D': ['110', '101', '101', '101', '110'],
    'E': ['111', '100', '110', '100', '111'], 'F': ['111', '100', '110', '100', '100'],
    'G': ['011', '100', '101', '101', '011'], 'H': ['101', '101', '111', '101', '101'],
    'I': ['111', '010', '010', '010', '111'], 'J': ['001', '001', '001', '101', '010'],
    'L': ['100', '100', '100', '100', '111'], 'M': ['101', '111', '111', '101', '101'],
    'N': ['110', '101', '101', '101', '101'], 'O': ['010', '101', '101', '101', '010'],
    'P': ['110', '101', '110', '100', '100'], 'Q': ['010', '101', '101', '110', '011'],
    'R': ['110', '101', '110', '101', '101'], 'S': ['011', '100', '010', '001', '110'],
    'T': ['111', '010', '010', '010', '010'], 'U': ['101', '101', '101', '101', '111'],
    'V': ['101', '101', '101', '101', '010'], 'Y': ['101', '101', '010', '010', '010'],
    'Z': ['111', '001', '010', '100', '111'], '-': ['000', '000', '111', '000', '000'],
    '+': ['000', '010', '111', '010', '000'], '1': ['010', '110', '010', '010', '111'],
    '2': ['110', '001', '010', '100', '111'], '3': ['110', '001', '010', '001', '110'],
    ' ': ['00', '00', '00', '00', '00'],
}


def text_width(s):
    return sum(len(FONT[ch][0]) + 1 for ch in s) - 1


def label(d, s, cx, cy, col=CREAM, plate=True):
    """Rotulo centrado en (cx, cy) sobre una plaquita oscura con borde dorado."""
    tw = text_width(s)
    x0, y0 = cx - tw // 2, cy - 2
    if plate:
        d.rounded_rectangle((x0 - 3, y0 - 2, x0 + tw + 2, y0 + 6), radius=2, fill=(10, 30, 18), outline=GOLD_D)
    for ch in s:
        g = FONT[ch]
        for j, row in enumerate(g):
            for i, b in enumerate(row):
                if b == '1':
                    d.point((x0 + i, y0 + j + 1), fill=(0, 0, 0))
                    d.point((x0 + i, y0 + j), fill=col)
        x0 += len(g[0]) + 1


def slot_center(r, c):
    x1, y1, x2, y2 = slot_box(r, c)
    return (x1 + x2 + 1) // 2, (y1 + y2 + 1) // 2


def recess(d, r, c):
    """Hueco de la casilla (donde va el item): oscuro y hundido."""
    x1, y1, x2, y2 = slot_box(r, c)
    bevel(d, (x1 + 1, y1 + 1, x2 - 1, y2 - 1), (14, 14, 18), (70, 66, 80), fill=(34, 32, 40))


def button_slot(d, r, c, color):
    """Boton de color con relieve (2 px alrededor de la casilla)."""
    x1, y1, x2, y2 = slot_box(r, c)
    dark = tuple(int(v * 0.45) for v in color)
    lite = tuple(min(255, int(v * 1.3) + 20) for v in color)
    d.rounded_rectangle((x1 - 1, y1 - 1, x2 + 1, y2 + 1), radius=2, fill=color, outline=dark)
    d.line([(x1 + 1, y1), (x2 - 1, y1)], fill=lite)
    d.line([(x1, y1 + 1), (x1, y2 - 1)], fill=lite)
    recess(d, r, c)


def info_slot(d, r, c, color=GOLD):
    x1, y1, x2, y2 = slot_box(r, c)
    d.rectangle((x1 - 1, y1 - 1, x2 + 1, y2 + 1), fill=GOLD_D)
    d.rectangle((x1, y1, x2, y2), fill=color)
    recess(d, r, c)


def panel(d, r1, c1, r2, c2, fill=(8, 34, 20)):
    """Panel hundido con filete dorado que agrupa casillas."""
    x1, y1 = slot_box(r1, c1)[:2]
    x2, y2 = slot_box(r2, c2)[2:]
    d.rounded_rectangle((x1 - 2, y1 - 2, x2 + 2, y2 + 2), radius=2, fill=fill, outline=GOLD_D)


def vitrine(d, r1, r2, c1, c2):
    """Vitrina de fichas: fieltro rojo con marco dorado, casillas marcadas."""
    x1, y1 = slot_box(r1, c1)[:2]
    x2, y2 = slot_box(r2, c2)[2:]
    d.rounded_rectangle((x1 - 2, y1 - 2, x2 + 2, y2 + 2), radius=2, fill=GOLD_D)
    d.rectangle((x1 - 1, y1 - 1, x2 + 1, y2 + 1), fill=GOLD)
    d.rectangle((x1 - 1, y1 - 1, x2 + 1, y2 + 1), fill=(96, 16, 26))
    for r in range(r1, r2 + 1):
        for c in range(c1, c2 + 1):
            bx1, by1, bx2, by2 = slot_box(r, c)
            bevel(d, (bx1 + 1, by1 + 1, bx2 - 1, by2 - 1), (62, 8, 16), (138, 34, 44), fill=(80, 12, 22))


def suit_icon(d, cx, cy, suit, col):
    shapes = {
        'heart': ['01010', '11111', '11111', '01110', '00100'],
        'diamond': ['00100', '01110', '11111', '01110', '00100'],
        'spade': ['00100', '01110', '11111', '00100', '01110'],
        'club': ['01110', '01110', '11111', '00100', '01110'],
    }
    for j, row in enumerate(shapes[suit]):
        for i, b in enumerate(row):
            if b == '1':
                d.point((cx - 2 + i, cy - 2 + j), fill=col)


def suits_row(d, cx, cy):
    """Los cuatro palos centrados (adorno discreto)."""
    for i, (s, col) in enumerate((('spade', CREAM), ('heart', (214, 40, 50)), ('club', CREAM), ('diamond', (214, 40, 50)))):
        suit_icon(d, cx - 15 + i * 10, cy, s, col)


def poker_action_background():
    img, d = felt_menu(6)
    # fila 0: rotulos de la info; fila 1: tus cartas, mesa, jugadores
    panel(d, 1, 2, 1, 6)
    for c, txt, col in ((2, 'CARTAS', (240, 240, 232)), (4, 'MESA', GOLD), (6, 'JUGADORES', BLUE_B)):
        info_slot(d, 1, c, col)
        label(d, txt, slot_center(0, c)[0], slot_center(0, c)[1] + 2)
    for c in (3, 5):
        suit_icon(d, *slot_center(1, c), 'spade' if c == 3 else 'heart', GOLD_D)
    # fila 3: acciones (28, 30, 32, 34) con su nombre encima (fila 2)
    for c, txt, col in ((1, 'RETIRAR', RED_B), (3, 'IGUALAR', GREEN_B), (5, 'SUBIR', GOLD_B), (7, 'ALL-IN', ORANGE_B)):
        button_slot(d, 3, c, col)
        label(d, txt, slot_center(2, c)[0], slot_center(2, c)[1] + 2)
    # fila 4: ajuste de la subida (36..44), 40 = a cuanto subes
    panel(d, 4, 0, 4, 8, fill=(6, 26, 15))  # 1 px por fuera de las casillas
    for c in range(9):
        col = RED_B if c < 2 else GREEN_B if c > 6 else (200, 200, 196)
        if c == 4:
            continue
        info_slot(d, 4, c, tuple(int(v * 0.8) for v in col))
    button_slot(d, 4, 4, GOLD_B)
    # fila 5: cerrar
    button_slot(d, 5, 4, GREY_B)
    return img


def poker_buyin_background():
    img, d = felt_menu(4)
    label(d, 'ELIGE TUS FICHAS', 88, slot_center(0, 4)[1] + 1)
    vitrine(d, 1, 2, 2, 6)
    info_slot(d, 3, 2, GOLD)      # 29 = tus fichas
    button_slot(d, 3, 4, GREY_B)  # 31 = cerrar
    info_slot(d, 3, 6, BLUE_B)    # 33 = propina
    return img


def bj_action_background():
    img, d = felt_menu(3)
    # fila 0: tu mano (2) contra el dealer (6)
    info_slot(d, 0, 2, (240, 240, 232))
    info_slot(d, 0, 6, (200, 120, 60))
    label(d, 'VS', 88, slot_center(0, 4)[1], col=GOLD_L, plate=False)
    suit_icon(d, slot_center(0, 3)[0], slot_center(0, 3)[1], 'spade', GOLD_D)
    suit_icon(d, slot_center(0, 5)[0], slot_center(0, 5)[1], 'heart', GOLD_D)
    # fila 1: pedir, doblar, dividir, plantarse; fila 2: sus nombres
    for c, txt, col in ((1, 'PEDIR', GREEN_B), (3, 'DOBLAR', GOLD_B), (5, 'DIVIDIR', BLUE_B), (7, 'PLANTAR', RED_B)):
        button_slot(d, 1, c, col)
        label(d, txt, slot_center(2, c)[0], slot_center(2, c)[1] + 1)
    return img


def bj_bet_background():
    img, d = felt_menu(5)
    label(d, 'HAZ TU APUESTA', 88, slot_center(0, 4)[1] + 1)
    vitrine(d, 1, 2, 2, 6)
    info_slot(d, 2, 0, GOLD)              # 18 info
    info_slot(d, 2, 8, (240, 240, 232))   # 26 tabla de pagos
    # fila 3: los 3 circulos de apuesta (principal, parejas, 21+3)
    for c, col in ((2, GOLD_B), (4, (190, 90, 220)), (6, (60, 200, 210))):
        cx, cy = slot_center(3, c)
        d.ellipse((cx - 11, cy - 11, cx + 10, cy + 10), outline=(242, 242, 234))
        d.ellipse((cx - 10, cy - 10, cx + 9, cy + 9), fill=tuple(int(v * 0.35) for v in col), outline=col)
        recess(d, 3, c)
    # fila 4: retirar (37), cerrar (40), repetir (43)
    button_slot(d, 4, 1, RED_B)
    button_slot(d, 4, 4, GREY_B)
    button_slot(d, 4, 7, GREEN_B)
    return img


def sounds():
    s = {}
    # Palanca: chasquido metalico + golpe grave + muelle
    s['lever'] = mix(0.45, (0, noise(0.04, 0.015, 0.8, seed=2)), (0.0, tone(90, 0.25, r=0.06, vol=0.9)),
                     (0.05, tone(380, 0.3, 'sine', r=0.08, vol=0.25)), (0.18, noise(0.05, 0.02, 0.5, 0.3, 3)),
                     (0.2, tone(140, 0.2, r=0.05, vol=0.6)))
    # Tic de los rodillos girando
    s['reel_tick'] = mix(0.06, (0, noise(0.03, 0.006, 0.7, seed=4)), (0, tone(1800, 0.03, 'square', r=0.006, vol=0.25)))
    # Parada de un rodillo: golpe seco + campanita
    s['reel_stop'] = mix(0.4, (0, noise(0.05, 0.02, 0.9, 0.25, 5)), (0, tone(110, 0.2, r=0.05, vol=0.9)),
                         (0.02, tone(G5, 0.35, 'bell', r=0.12, vol=0.35)))
    # Premio: arpegio ascendente
    s['win'] = mix(1.0, *[(i * 0.09, tone(f, 0.5, 'square', r=0.18, vol=0.35)) for i, f in enumerate([C5, E5, G5, C6])],
                   (0.36, tone(C6, 0.6, 'bell', r=0.3, vol=0.5)))
    # Jackpot: fanfarria + lluvia de campanas
    fan = [(0, C5), (0.12, E5), (0.24, G5), (0.36, C6), (0.6, G5), (0.72, C6), (0.84, E6), (1.0, G6)]
    rng = np.random.default_rng(9)
    bells = [(1.1 + i * 0.07, tone(float(rng.choice([C6, E6, G6, 2093.0])), 0.4, 'bell', r=0.15, vol=0.3)) for i in range(18)]
    s['jackpot'] = mix(2.6, *[(t, tone(f, 0.45, 'square', r=0.2, vol=0.35)) for t, f in fan],
                       (1.0, tone(G6, 1.0, 'bell', r=0.5, vol=0.5)), *bells)
    # Sin premio: dos notas que bajan
    s['lose'] = mix(0.7, (0, tone(392.0, 0.3, 'square', r=0.12, vol=0.35)), (0.18, tone(311.1, 0.5, 'square', r=0.2, vol=0.35)))
    # Moneda (al subir/bajar la apuesta)
    s['coin'] = mix(0.35, (0, tone(1975.5, 0.3, 'bell', r=0.08, vol=0.5)), (0.06, tone(2637.0, 0.3, 'bell', r=0.1, vol=0.5)))
    return s


def exchange_sounds():
    s = {}
    # Comprar: fichas cayendo a la bandeja (clics de plastico) + campanita
    rng = np.random.default_rng(21)
    clicks = [(0.05 + i * 0.045 + float(rng.uniform(0, 0.02)),
               mix(0.05, (0, noise(0.02, 0.005, 0.8, 0.5, 30 + i)), (0, tone(float(rng.uniform(2400, 3400)), 0.03, 'bell', r=0.01, vol=0.5))))
              for i in range(9)]
    s['buy'] = mix(0.9, *clicks, (0.5, tone(1568.0, 0.4, 'bell', r=0.15, vol=0.35)))
    # Vender: caja registradora (cajon + "ka-ching")
    s['sell'] = mix(1.1, (0, noise(0.12, 0.05, 0.6, 0.15, 40)), (0.0, tone(180, 0.15, r=0.05, vol=0.6)),
                    (0.12, noise(0.05, 0.02, 0.8, 0.4, 41)),
                    (0.2, tone(2093.0, 0.8, 'bell', r=0.35, vol=0.6)), (0.26, tone(2637.0, 0.8, 'bell', r=0.35, vol=0.5)))
    return s


# ----------------------------------------------------------------------------
# Fichas en 3D: un modelo de item (chip.json) + una textura por valor. Cada tinte
# de ficha (TokenManager.DENOMS) se cambia por su ficha cuando el item tiene
# custom_model_data = CHIP_CMD (1.20.4-1.21.3: overrides; 1.21.4+: items/*.json).
# ----------------------------------------------------------------------------
CHIP_CMD = 7701
# tinte, nombre, valor impreso, color base, color de las marcas del canto
CHIPS = [
    ('yellow_dye', 'yellow', '1', (232, 190, 30), (255, 255, 255)),
    ('red_dye', 'red', '10', (190, 24, 36), (255, 255, 255)),
    ('blue_dye', 'blue', '50', (30, 72, 184), (255, 255, 255)),
    ('lime_dye', 'green', '100', (34, 140, 58), (255, 255, 255)),
    ('purple_dye', 'purple', '500', (108, 40, 160), (255, 255, 255)),
    ('orange_dye', 'orange', '1K', (226, 110, 16), (255, 255, 255)),
    ('pink_dye', 'pink', '5K', (214, 92, 152), (255, 255, 255)),
    ('black_dye', 'black', '10K', (30, 30, 34), (232, 186, 52)),
    ('gray_dye', 'gray', '50K', (118, 120, 132), (232, 186, 52)),
    ('white_dye', 'white', '100K', (238, 238, 236), (206, 150, 24)),
]

# Ficha de casino acostada: 14 de diametro y 2.6 de grueso. Textura 32x32: cara redonda en el centro (px 2..30) y la franja
# del canto en las filas 0-1 (fuera del circulo).
CHIP_R = 7.0      # radio en unidades del modelo (= 14 px de textura)
CHIP_H = 2.6      # grosor (ficha gruesa, el canto a rayas se ve bien)
# Canto: union de rectangulos (medio ancho x, medio ancho z) que siguen el circulo
CHIP_RIM = [(7.0, 2.4), (6.6, 3.6), (6.1, 4.5), (5.3, 5.3), (4.5, 6.1), (3.6, 6.6), (2.4, 7.0)]

DIGITS = {
    '0': ['111', '101', '101', '101', '111'], '1': ['010', '110', '010', '010', '111'],
    '5': ['111', '100', '111', '001', '111'], 'K': ['101', '101', '110', '101', '101'],
}


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def chip_texture(base, mark, label):
    import math
    n = 32
    img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    px = img.load()
    cx = cy = 15.5
    light = shade(base, 1.18) if sum(base) < 600 else (250, 248, 240)
    inlay = tuple(int(v * 0.25 + 255 * 0.75) for v in base)  # centro claro, tenido del color
    for y in range(n):
        for x in range(n):
            dx, dy = x - cx, y - cy
            r = math.hypot(dx, dy)
            if r > 14.2:
                continue
            ang = (math.degrees(math.atan2(dy, dx)) + 360) % 360
            c = base
            if r > 13.2:
                c = shade(base, 0.7)                       # borde
            elif r > 10.6:
                # 8 marcas rectangulares en el aro exterior
                c = mark if ((ang + 7) % 45) < 14 else base
                if ((ang + 7) % 45) < 14 and r > 12.6:
                    c = shade(mark, 0.85)
            elif r > 9.6:
                c = shade(base, 0.82)
            elif r > 8.9:
                c = mark if int(ang // 10) % 2 == 0 else shade(base, 1.1)  # anillo punteado
            elif r > 8.2:
                c = shade(base, 0.85)
            else:
                c = inlay
            px[x, y] = c + (255,)
    # Valor en el centro (fuente 3x5)
    w = len(label) * 4 - 1
    x0, y0 = int(16 - w / 2), 14
    ink = shade(base, 0.6) if sum(base) < 700 else (40, 40, 40)
    if sum(base) < 150:
        ink = (20, 20, 22)
    for i, ch in enumerate(label):
        for ry, row in enumerate(DIGITS[ch]):
            for rx, bit in enumerate(row):
                if bit == '1':
                    px[x0 + i * 4 + rx, y0 + ry] = ink + (255,)
    # Franja del canto (filas 0-1): color base con marcas como el aro
    for x in range(n):
        c = mark if x % 6 in (0, 1) else base
        px[x, 0] = c + (255,)
        px[x, 1] = shade(c, 0.75) + (255,)
    return img


# Ficha fina con el centro hundido: aro octogonal (8 barras) mas alto que el centro y
# 8 marcas en relieve encima del aro (como las fichas de casino de verdad).
CHIP_APO = 7.0       # apotema exterior del aro
CHIP_APO_IN = 4.8    # apotema interior (borde del centro hundido)
CHIP_CENTER_H = 1.0  # altura del centro
CHIP_RIM_H = 1.6     # altura del aro
CHIP_MARK_H = 1.9    # altura de las marcas


def chip_model():
    import math
    c = 8.0
    tex = lambda uv: {'uv': uv, 'texture': '#chip'}
    base_uv, mark_uv = tex([1.5, 0, 2.5, 0.5]), tex([0, 0, 0.5, 0.5])
    side = 2 * CHIP_APO * math.tan(math.pi / 8)  # lado del octogono
    els = []

    def oriented(name, k, radial, tang, y, faces):
        """Pieza en la direccion k*45 grados: radial = (desde, hasta) del centro, tang = medio ancho."""
        rot = 45 if k % 2 else 0
        a = math.radians(k * 45 - rot)
        dx, dz = round(math.cos(a)), round(math.sin(a))
        if dx:
            fx = sorted([c + dx * radial[0], c + dx * radial[1]]); fz = [c - tang, c + tang]
        else:
            fz = sorted([c + dz * radial[0], c + dz * radial[1]]); fx = [c - tang, c + tang]
        el = {'name': name, 'from': [fx[0], y[0], fz[0]], 'to': [fx[1], y[1], fz[1]], 'faces': faces}
        if rot:
            el['rotation'] = {'angle': rot, 'axis': 'y', 'origin': [c, 0, c]}
        els.append(el)

    # Centro hundido con el valor (la textura se mapea igual que la cara: x,z -> u,v)
    h = CHIP_APO_IN + 0.05
    els.append({'name': 'centro', 'from': [c - h, 0, c - h], 'to': [c + h, CHIP_CENTER_H, c + h],
                'faces': {'up': tex([c - h, c - h, c + h, c + h]), 'down': tex([c - h, c - h, c + h, c + h])}})
    all6 = ('north', 'south', 'east', 'west', 'up', 'down')
    for k in range(8):
        oriented('aro_%d' % k, k, (CHIP_APO_IN, CHIP_APO), side / 2 + 0.01, (0, CHIP_RIM_H), {f: base_uv for f in all6})
        oriented('marca_%d' % k, k, (CHIP_APO_IN - 0.05, CHIP_APO + 0.12), 0.85, (-0.05, CHIP_MARK_H), {f: mark_uv for f in all6})
    return {
        'credit': 'GamblingDex',
        'texture_size': [32, 32],
        'textures': {'chip': 'gamblingdex:item/chip_red', 'particle': 'gamblingdex:item/chip_red'},
        'elements': els,
        'display': CHIP_DISPLAY,
    }


# Como se ve en cada sitio (la ficha esta acostada: cara hacia arriba)
CHIP_DISPLAY = {
    'gui': {'rotation': [-55, 0, 0], 'translation': [0, 1, 0], 'scale': [1.15, 1.15, 1.15]},
    'ground': {'translation': [0, 1, 0], 'scale': [0.45, 0.45, 0.45]},
    'fixed': {'rotation': [90, 180, 0], 'scale': [0.9, 0.9, 0.9]},
    'head': {'translation': [0, 14.5, 0], 'scale': [0.7, 0.7, 0.7]},
    'thirdperson_righthand': {'rotation': [50, 0, 0], 'translation': [0, 3, 1.5], 'scale': [0.35, 0.35, 0.35]},
    'thirdperson_lefthand': {'rotation': [50, 0, 0], 'translation': [0, 3, 1.5], 'scale': [0.35, 0.35, 0.35]},
    'firstperson_righthand': {'rotation': [50, -20, 0], 'translation': [-1, 5, 0], 'scale': [0.5, 0.5, 0.5]},
    'firstperson_lefthand': {'rotation': [50, 20, 0], 'translation': [-1, 5, 0], 'scale': [0.5, 0.5, 0.5]},
}

def write_json(path, data):
    mkdir(os.path.dirname(path))
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2)


def chips():
    write_json(os.path.join(NS, 'models', 'item', 'chip.json'), chip_model())
    mc = os.path.join(PACK, 'assets', 'minecraft')
    for dye, name, label, base, mark in CHIPS:
        chip_texture(base, mark, label).save(os.path.join(mkdir_p(os.path.join(NS, 'textures', 'item')), 'chip_%s.png' % name))
        model = 'gamblingdex:item/chip_' + name
        write_json(os.path.join(NS, 'models', 'item', 'chip_%s.json' % name),
                   {'parent': 'gamblingdex:item/chip', 'textures': {'chip': model, 'particle': model}})
        # 1.20.4 - 1.21.3
        write_json(os.path.join(mc, 'models', 'item', dye + '.json'), {
            'parent': 'minecraft:item/generated', 'textures': {'layer0': 'minecraft:item/' + dye},
            'overrides': [{'predicate': {'custom_model_data': CHIP_CMD}, 'model': model}]})
        # 1.21.4+
        write_json(os.path.join(mc, 'items', dye + '.json'), {'model': {
            'type': 'minecraft:range_dispatch', 'property': 'minecraft:custom_model_data', 'index': 0,
            'entries': [{'threshold': CHIP_CMD, 'model': {'type': 'minecraft:model', 'model': model}}],
            'fallback': {'type': 'minecraft:model', 'model': 'minecraft:item/' + dye}}})


def mkdir_p(p):
    mkdir(p)
    return p


def roulette_sounds():
    # La bola: rueda 4.6 s (zumbido que baja de tono y de volumen), golpea los deflectores,
    # rebota en las casillas (5.3, 5.75, 6.3 s) y se queda (7 s). Igual que ball_N del modelo.
    n = int(SR * 7.4)
    t = np.arange(n) / SR
    rng = np.random.default_rng(31)
    roll = rng.uniform(-1, 1, n)
    y = np.zeros(n)
    a = 0.12
    for i in range(1, n):
        y[i] = y[i - 1] + a * (roll[i] - y[i - 1])
    speed = np.clip(1 - t / 5.0, 0, 1)
    rumble = y / np.abs(y).max() * (0.25 + 0.5 * speed) * (t < 5.0) * (0.6 + 0.4 * np.sin(2 * np.pi * (3 + 9 * speed) * t))
    hum = np.sin(2 * np.pi * (180 + 260 * speed) * t) * 0.08 * speed
    out = rumble + hum
    hits = [(1.2, 0.5), (2.6, 0.45), (3.9, 0.5), (4.75, 0.6), (5.3, 1.0), (5.55, 0.5), (5.75, 0.9), (6.0, 0.45),
            (6.3, 0.8), (6.6, 0.4), (7.0, 0.6)]
    parts = [(0, out)]
    for when, vol in hits:
        parts.append((when, mix(0.12, (0, noise(0.03, 0.01, 0.9 * vol, 0.5, int(when * 100))),
                                (0, tone(2600 + when * 80, 0.06, 'bell', r=0.015, vol=0.6 * vol)))))
    return {'spin': mix(7.4, *parts)}


ROULETTE_SUBTITLES = {'spin': 'Bola de la ruleta'}


def poker_sounds():
    s = {}
    # Carta repartida: "fsst" corto (ruido filtrado que cae) + golpecito sobre el pano
    s['card'] = mix(0.25, (0, noise(0.12, 0.04, 0.7, 0.35, 50)), (0.08, noise(0.03, 0.01, 0.6, 0.15, 51)))
    # Fichas: varios clics de plastico
    rng = np.random.default_rng(52)
    s['chips'] = mix(0.5, *[(0.02 + i * 0.05 + float(rng.uniform(0, 0.02)),
                             mix(0.05, (0, noise(0.02, 0.005, 0.8, 0.5, 60 + i)),
                                 (0, tone(float(rng.uniform(2600, 3600)), 0.03, 'bell', r=0.01, vol=0.5)))) for i in range(6)])
    # Carta que se da vuelta: chasquido corto de carton
    s['flip'] = mix(0.18, (0, noise(0.05, 0.012, 0.9, 0.6, 70)), (0.03, noise(0.02, 0.006, 0.5, 0.25, 71)),
                    (0.0, tone(1400, 0.04, 'sine', r=0.01, vol=0.25)))
    # Bote al ganador: fichas arrastrandose por el pano + clics + campanita
    sweep = [(0.05 + i * 0.06 + float(rng.uniform(0, 0.03)),
              mix(0.05, (0, noise(0.02, 0.005, 0.7, 0.5, 80 + i)), (0, tone(float(rng.uniform(2400, 3600)), 0.03, 'bell', r=0.01, vol=0.45))))
             for i in range(12)]
    s['win'] = mix(1.4, (0, noise(0.8, 0.35, 0.35, 0.08, 90)), *sweep,
                   (0.8, tone(1568.0, 0.5, 'bell', r=0.2, vol=0.45)), (0.92, tone(2093.0, 0.5, 'bell', r=0.25, vol=0.4)))
    return s


POKER_SUBTITLES = {'card': 'Carta repartida', 'chips': 'Fichas', 'flip': 'Carta dada vuelta', 'win': 'Bote al ganador'}

EXCHANGE_SUBTITLES = {'buy': 'Fichas cayendo', 'sell': 'Caja registradora'}

# ----------------------------------------------------------------------------
# Baraja (poker): 52 cartas + dorso, como modelo de item sobre el papel.
# custom_model_data = CARD_CMD + palo*13 + valor (palo: treboles, diamantes, corazones,
# picas; valor: A,2..10,J,Q,K, igual que Card.Suit/Card.Rank) y CARD_CMD + 52 = dorso.
# ----------------------------------------------------------------------------
CARD_CMD = 7800
CW, CHH = 40, 56
RANKS = ['A', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'J', 'Q', 'K']
SUITS = ['clubs', 'diamonds', 'hearts', 'spades']
FONT5 = dict(DIGITS)
FONT5.update({'2': ['111', '001', '111', '100', '111'], '3': ['111', '001', '111', '001', '111'],
              '4': ['101', '101', '111', '001', '001'], '6': ['111', '100', '111', '101', '111'],
              '7': ['111', '001', '010', '010', '010'], '8': ['111', '101', '111', '101', '111'],
              '9': ['111', '101', '111', '001', '111'], 'A': ['010', '101', '111', '101', '101'],
              'J': ['001', '001', '001', '101', '111'], 'Q': ['111', '101', '101', '111', '011'],
              'K': ['101', '110', '100', '110', '101']})
SUIT_SMALL = {
    'hearts': ['.X.X.', 'XXXXX', 'XXXXX', '.XXX.', '..X..'],
    'diamonds': ['..X..', '.XXX.', 'XXXXX', '.XXX.', '..X..'],
    'clubs': ['..X..', '.XXX.', 'XXXXX', 'X.X.X', '.XXX.'],
    'spades': ['..X..', '.XXX.', 'XXXXX', 'XXXXX', '.X.X.'],
}
SUIT_BIG = {
    'hearts': ['..XX...XX..', '.XXXX.XXXX.', 'XXXXXXXXXXX', 'XXXXXXXXXXX', 'XXXXXXXXXXX', '.XXXXXXXXX.',
               '..XXXXXXX..', '...XXXXX...', '....XXX....', '.....X.....'],
    'diamonds': ['.....X.....', '....XXX....', '...XXXXX...', '..XXXXXXX..', '.XXXXXXXXX.', 'XXXXXXXXXXX',
                 '.XXXXXXXXX.', '..XXXXXXX..', '...XXXXX...', '....XXX....', '.....X.....'],
    'clubs': ['....XXX....', '...XXXXX...', '...XXXXX...', '.XX.XXX.XX.', 'XXXXXXXXXXX', 'XXXXXXXXXXX',
              '.XX..X..XX.', '.....X.....', '....XXX....', '...XXXXX...'],
    'spades': ['.....X.....', '....XXX....', '...XXXXX...', '..XXXXXXX..', '.XXXXXXXXX.', 'XXXXXXXXXXX',
               'XXXXXXXXXXX', '.XX.XXX.XX.', '.....X.....', '....XXX....'],
}


def card_base():
    img = Image.new('RGBA', (CW, CHH), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, CW - 1, CHH - 1), radius=4, fill=(250, 250, 246), outline=(170, 170, 165))
    return img, d


def draw_glyph(img, rows, x0, y0, col, sc=1, flip=False):
    px = img.load()
    h = len(rows)
    for ry, row in enumerate(rows):
        for rx, ch in enumerate(row):
            if ch in 'X1':
                for a in range(sc):
                    for b in range(sc):
                        if flip:
                            px[x0 - rx * sc - a, y0 - ry * sc - b] = col
                        else:
                            px[x0 + rx * sc + a, y0 + ry * sc + b] = col


def card_face(rank, suit):
    img, d = card_base()
    red = suit in ('hearts', 'diamonds')
    col = (204, 26, 40, 255) if red else (26, 26, 30, 255)
    # esquina arriba-izquierda: valor + palo chico (y la de abajo-derecha girada)
    x = 3
    for ch in rank:
        draw_glyph(img, FONT5[ch], x, 3, col, 2)
        x += 8 if ch != '1' else 7
    draw_glyph(img, SUIT_SMALL[suit], 3, 15, col, 1)
    x = CW - 4
    for ch in rank:
        draw_glyph(img, FONT5[ch], x, CHH - 4, col, 2, flip=True)
        x -= 8 if ch != '1' else 7
    draw_glyph(img, SUIT_SMALL[suit], CW - 4, CHH - 16, col, 1, flip=True)
    big = SUIT_BIG[suit]
    if rank in ('J', 'Q', 'K'):
        frame = {'J': (40, 90, 200), 'Q': (200, 40, 120), 'K': (220, 160, 30)}[rank]
        d.rectangle((9, 12, CW - 10, CHH - 13), outline=frame + (255,), width=2)
        d.rectangle((11, 14, CW - 12, CHH - 15), fill=tuple(int(c * 0.15 + 255 * 0.85) for c in frame) + (255,))
        draw_glyph(img, FONT5[rank], CW // 2 - 4, 18, frame + (255,), 3)
        draw_glyph(img, SUIT_SMALL[suit], CW // 2 - 5, 37, col, 2)
    else:
        sc = 2 if rank == 'A' else 1
        w = len(big[0]) * sc
        draw_glyph(img, big, CW // 2 - w // 2, CHH // 2 - len(big) * sc // 2, col, sc)
    return img


def card_back():
    img, d = card_base()
    d.rounded_rectangle((3, 3, CW - 4, CHH - 4), radius=3, fill=(150, 18, 30))
    px = img.load()
    for y in range(5, CHH - 5):
        for x in range(5, CW - 5):
            if (x + y) % 6 == 0 or (x - y) % 6 == 0:
                px[x, y] = (200, 50, 60, 255)
    d.rectangle((3, 3, CW - 4, CHH - 4), outline=(232, 186, 52), width=1)
    cx, cy = CW // 2, CHH // 2
    d.polygon([(cx, cy - 9), (cx + 7, cy), (cx, cy + 9), (cx - 7, cy)], fill=(232, 186, 52), outline=(120, 80, 10))
    d.polygon([(cx, cy - 5), (cx + 4, cy), (cx, cy + 5), (cx - 4, cy)], fill=(150, 18, 30))
    return img


def card_model():
    # Carta acostada (10 x 14, grosor 0.3): arriba la cara, abajo el dorso
    return {
        'credit': 'GamblingDex', 'texture_size': [CW, CHH],
        'textures': {'front': 'gamblingdex:item/card/back', 'back': 'gamblingdex:item/card/back',
                     'particle': 'gamblingdex:item/card/back'},
        'elements': [{'name': 'carta', 'from': [3, 0, 1], 'to': [13, 0.3, 15], 'faces': {
            'up': {'uv': [0, 0, 16, 16], 'texture': '#front'},
            'down': {'uv': [16, 0, 0, 16], 'texture': '#back'},
            'north': {'uv': [0, 0, 16, 1], 'texture': '#back'}, 'south': {'uv': [0, 0, 16, 1], 'texture': '#back'},
            'east': {'uv': [0, 0, 16, 1], 'texture': '#back'}, 'west': {'uv': [0, 0, 16, 1], 'texture': '#back'}}}],
        'display': {
            'gui': {'rotation': [-75, 0, 0], 'scale': [1.1, 1.1, 1.1]},
            'ground': {'translation': [0, 1, 0], 'scale': [0.5, 0.5, 0.5]},
            'fixed': {'rotation': [90, 180, 0]},
            'firstperson_righthand': {'rotation': [50, -20, 0], 'translation': [-1, 5, 0], 'scale': [0.5, 0.5, 0.5]},
            'thirdperson_righthand': {'rotation': [50, 0, 0], 'translation': [0, 3, 1.5], 'scale': [0.35, 0.35, 0.35]},
        },
    }


def stool():
    """Taburete de casino (silla de las mesas de poker y blackjack): CARD_CMD + 54."""
    texd = mkdir_p(os.path.join(NS, 'textures', 'item', 'card'))
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    rng = np.random.default_rng(11)
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = tuple(int(v) for v in rng.choice([(176, 24, 36), (160, 20, 32), (190, 30, 42)])) + (255,)  # cuero rojo
            px[16 + x, y] = tuple(int(v) for v in rng.choice([(200, 204, 212), (180, 184, 194), (226, 230, 236)])) + (255,)  # cromo
            px[x, 16 + y] = tuple(int(v) for v in rng.choice([(232, 180, 32), (242, 194, 48), (217, 165, 20)])) + (255,)  # dorado
            px[16 + x, 16 + y] = tuple(int(v) for v in rng.choice([(30, 30, 36), (24, 24, 30), (36, 36, 42)])) + (255,)  # base
    for x in range(16):  # botones del cuero
        for y in range(16):
            if x % 5 == 2 and y % 5 == 2:
                px[x, y] = (120, 12, 22, 255)
    img.save(os.path.join(texd, 'stool.png'))
    T = lambda u: {'uv': u, 'texture': '#t'}
    red, chrome, gold, dark = [0, 0, 8, 8], [8, 0, 16, 8], [0, 8, 8, 16], [8, 8, 16, 16]
    def box(name, f, t, uv, top=None):
        return {'name': name, 'from': f, 'to': t, 'faces': {k: T(top if (top and k in ('up', 'down')) else uv)
                                                             for k in ('north', 'south', 'east', 'west', 'up', 'down')}}
    els = [
        box('base_a', [3, 0, 5], [13, 0.8, 11], dark), box('base_b', [5, 0, 3], [11, 0.8, 13], dark),
        box('pole', [7.2, 0.8, 7.2], [8.8, 9.4, 8.8], chrome),
        box('foot_n', [4.5, 4, 4.2], [11.5, 4.6, 4.8], chrome), box('foot_s', [4.5, 4, 11.2], [11.5, 4.6, 11.8], chrome),
        box('foot_w', [4.2, 4, 4.5], [4.8, 4.6, 11.5], chrome), box('foot_e', [11.2, 4, 4.5], [11.8, 4.6, 11.5], chrome),
        box('arm_x', [4.5, 4, 7.6], [11.5, 4.5, 8.4], chrome), box('arm_z', [7.6, 4, 4.5], [8.4, 4.5, 11.5], chrome),
        box('rim_a', [2, 9.4, 4.5], [14, 10, 11.5], gold), box('rim_b', [4.5, 9.4, 2], [11.5, 10, 14], gold),
        box('seat_a', [2.3, 10, 4.7], [13.7, 11.6, 11.3], red), box('seat_b', [4.7, 10, 2.3], [11.3, 11.6, 13.7], red),
        box('seat_c', [3.3, 10, 3.3], [12.7, 11.6, 12.7], red),
        box('cushion', [4, 11.6, 4], [12, 12.1, 12], red),
    ]
    write_json(os.path.join(NS, 'models', 'item', 'card', 'stool.json'), {
        'credit': 'GamblingDex', 'texture_size': [32, 32],
        'textures': {'t': 'gamblingdex:item/card/stool', 'particle': 'gamblingdex:item/card/stool'},
        'elements': els,
        'display': {'gui': {'rotation': [30, 45, 0], 'scale': [0.7, 0.7, 0.7]}, 'fixed': {}, 'ground': {'scale': [0.5, 0.5, 0.5]}}})


def cards():
    texd = mkdir_p(os.path.join(NS, 'textures', 'item', 'card'))
    card_back().save(os.path.join(texd, 'back.png'))
    write_json(os.path.join(NS, 'models', 'item', 'card.json'), card_model())
    entries, overrides = [], []
    names = []
    for si, suit in enumerate(SUITS):
        for ri, rank in enumerate(RANKS):
            name = '%s_%s' % (suit, rank.lower())
            card_face(rank, suit).save(os.path.join(texd, name + '.png'))
            names.append((CARD_CMD + si * 13 + ri, name))
    names.append((CARD_CMD + 52, 'back'))
    for cmd, name in names:
        model = 'gamblingdex:item/card/' + name
        if name in ('dealer', 'stool'):
            continue
        write_json(os.path.join(NS, 'models', 'item', 'card', name + '.json'), {
            'parent': 'gamblingdex:item/card',
            'textures': {'front': model, 'back': 'gamblingdex:item/card/back', 'particle': model}})
    # Boton del dealer: una ficha blanca con una D (mismo modelo que las fichas)
    import math
    btn = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    bp = btn.load()
    for y in range(32):
        for x in range(32):
            r = math.hypot(x - 15.5, y - 15.5)
            if r <= 14.2:
                bp[x, y] = (40, 40, 44, 255) if r > 13.2 else ((232, 186, 52, 255) if r > 11.8 else (248, 248, 244, 255))
    for x in range(32):
        bp[x, 0] = (248, 248, 244, 255) if x % 6 > 1 else (232, 186, 52, 255)
        bp[x, 1] = (210, 210, 205, 255)
    draw_glyph(btn, ['110', '101', '101', '101', '110'], 11, 9, (20, 20, 24, 255), 3)
    btn.save(os.path.join(texd, 'dealer.png'))
    write_json(os.path.join(NS, 'models', 'item', 'card', 'dealer.json'), {
        'parent': 'gamblingdex:item/chip',
        'textures': {'chip': 'gamblingdex:item/card/dealer', 'particle': 'gamblingdex:item/card/dealer'}})
    names.append((CARD_CMD + 53, 'dealer'))
    stool()
    names.append((CARD_CMD + 54, 'stool'))
    for cmd, name in names:
        model = 'gamblingdex:item/card/' + name
        overrides.append({'predicate': {'custom_model_data': cmd}, 'model': model})
        entries.append({'threshold': cmd, 'model': {'type': 'minecraft:model', 'model': model}})
    mc = os.path.join(PACK, 'assets', 'minecraft')
    write_json(os.path.join(mc, 'models', 'item', 'paper.json'), {
        'parent': 'minecraft:item/generated', 'textures': {'layer0': 'minecraft:item/paper'}, 'overrides': overrides})
    write_json(os.path.join(mc, 'items', 'paper.json'), {'model': {
        'type': 'minecraft:range_dispatch', 'property': 'minecraft:custom_model_data', 'index': 0,
        'entries': entries, 'fallback': {'type': 'minecraft:model', 'model': 'minecraft:item/paper'}}})


SUBTITLES = {
    'lever': 'Palanca de la tragamonedas', 'reel_tick': 'Rodillos girando', 'reel_stop': 'Rodillo se detiene',
    'win': 'Premio en la tragamonedas', 'jackpot': '¡Jackpot!', 'lose': 'Sin premio', 'coin': 'Monedas',
}


def main():
    mkdir(NS)
    # pack.mcmeta (1.20.4 = 22; supported_formats / min_format cubren 1.21.x)
    meta = {'pack': {'pack_format': 22, 'supported_formats': {'min_inclusive': 22, 'max_inclusive': 999},
                     'min_format': 22, 'max_format': 999,
                     'description': '§6GamblingDex §7- menús y sonidos del casino'}}
    with open(os.path.join(PACK, 'pack.mcmeta'), 'w', encoding='utf-8') as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)

    # Fondo del menu y fuente
    tex = os.path.join(NS, 'textures', 'font')
    mkdir(tex)
    bg = slots_background()
    bg.save(os.path.join(tex, 'slots_gui.png'))
    ex_bg = exchange_background()
    ex_bg.save(os.path.join(tex, 'exchange_gui.png'))
    font = {'providers': [
        {'type': 'space', 'advances': {CH_BACK_8: -8, CH_BACK_169: -169}},
        {'type': 'bitmap', 'file': 'gamblingdex:font/slots_gui.png', 'height': bg.height, 'ascent': 13,
         'chars': [CH_SLOTS_BG]},
        {'type': 'bitmap', 'file': 'gamblingdex:font/exchange_gui.png', 'height': ex_bg.height, 'ascent': 13,
         'chars': [CH_EXCHANGE_BG]},
    ]}
    for name, fn, ch in (('poker_action_gui', poker_action_background, CH_POKER_ACTION_BG),
                         ('poker_buyin_gui', poker_buyin_background, CH_POKER_BUYIN_BG),
                         ('bj_action_gui', bj_action_background, CH_BJ_ACTION_BG),
                         ('bj_bet_gui', bj_bet_background, CH_BJ_BET_BG)):
        im = fn()
        im.save(os.path.join(tex, name + '.png'))
        font['providers'].append({'type': 'bitmap', 'file': 'gamblingdex:font/' + name + '.png',
                                  'height': im.height, 'ascent': 13, 'chars': [ch]})
    mkdir(os.path.join(NS, 'font'))
    with open(os.path.join(NS, 'font', 'gui.json'), 'w', encoding='utf-8') as f:
        json.dump(font, f, indent=2)

    # Sonidos
    sj = {}
    for name, sig in sounds().items():
        write_ogg(os.path.join(NS, 'sounds', 'slots', name + '.ogg'), sig)
        sj['slots.' + name] = {'sounds': ['gamblingdex:slots/' + name], 'subtitle': 'subtitles.gamblingdex.slots.' + name}
    for name, sig in poker_sounds().items():
        write_ogg(os.path.join(NS, 'sounds', 'poker', name + '.ogg'), sig)
        sj['poker.' + name] = {'sounds': ['gamblingdex:poker/' + name], 'subtitle': 'subtitles.gamblingdex.poker.' + name}
    for name, sig in roulette_sounds().items():
        write_ogg(os.path.join(NS, 'sounds', 'roulette', name + '.ogg'), sig)
        sj['roulette.' + name] = {'sounds': ['gamblingdex:roulette/' + name],
                                  'subtitle': 'subtitles.gamblingdex.roulette.' + name}
    for name, sig in exchange_sounds().items():
        write_ogg(os.path.join(NS, 'sounds', 'exchange', name + '.ogg'), sig)
        sj['exchange.' + name] = {'sounds': ['gamblingdex:exchange/' + name],
                                  'subtitle': 'subtitles.gamblingdex.exchange.' + name}
    with open(os.path.join(NS, 'sounds.json'), 'w', encoding='utf-8') as f:
        json.dump(sj, f, indent=2)
    mkdir(os.path.join(NS, 'lang'))
    lang = {'subtitles.gamblingdex.slots.' + k: v for k, v in SUBTITLES.items()}
    lang.update({'subtitles.gamblingdex.exchange.' + k: v for k, v in EXCHANGE_SUBTITLES.items()})
    lang.update({'subtitles.gamblingdex.roulette.' + k: v for k, v in ROULETTE_SUBTITLES.items()})
    lang.update({'subtitles.gamblingdex.poker.' + k: v for k, v in POKER_SUBTITLES.items()})
    for code in ('es_es', 'es_mx', 'es_ar', 'en_us'):
        with open(os.path.join(NS, 'lang', code + '.json'), 'w', encoding='utf-8') as f:
            json.dump(lang, f, indent=2, ensure_ascii=False)

    chips()
    cards()

    # Icono del pack: el fondo del menu recortado
    icon = Image.new('RGBA', (64, 64), (26, 10, 16, 255))
    icon.paste(bg.crop((36, 17, 136, 117)).resize((60, 60), Image.NEAREST), (2, 2))
    icon.save(os.path.join(PACK, 'pack.png'))

    files = []
    for dp, _, fns in os.walk(PACK):
        for fn in fns:
            rel = os.path.relpath(os.path.join(dp, fn), PACK).replace(os.sep, '/')
            if rel != 'files.txt':
                files.append(rel)
    with open(os.path.join(PACK, 'files.txt'), 'w') as f:
        f.write('\n'.join(sorted(files)) + '\n')
    print('\n'.join(sorted(files)))


if __name__ == '__main__':
    main()
