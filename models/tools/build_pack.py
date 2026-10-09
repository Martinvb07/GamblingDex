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
    font = {'providers': [
        {'type': 'space', 'advances': {CH_BACK_8: -8, CH_BACK_169: -169}},
        {'type': 'bitmap', 'file': 'gamblingdex:font/slots_gui.png', 'height': bg.height, 'ascent': 13,
         'chars': [CH_SLOTS_BG]},
    ]}
    mkdir(os.path.join(NS, 'font'))
    with open(os.path.join(NS, 'font', 'gui.json'), 'w', encoding='utf-8') as f:
        json.dump(font, f, indent=2)

    # Sonidos
    sj = {}
    for name, sig in sounds().items():
        write_ogg(os.path.join(NS, 'sounds', 'slots', name + '.ogg'), sig)
        sj['slots.' + name] = {'sounds': ['gamblingdex:slots/' + name], 'subtitle': 'subtitles.gamblingdex.slots.' + name}
    with open(os.path.join(NS, 'sounds.json'), 'w', encoding='utf-8') as f:
        json.dump(sj, f, indent=2)
    mkdir(os.path.join(NS, 'lang'))
    lang = {'subtitles.gamblingdex.slots.' + k: v for k, v in SUBTITLES.items()}
    for code in ('es_es', 'es_mx', 'es_ar', 'en_us'):
        with open(os.path.join(NS, 'lang', code + '.json'), 'w', encoding='utf-8') as f:
            json.dump(lang, f, indent=2, ensure_ascii=False)

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
