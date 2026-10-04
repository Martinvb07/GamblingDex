#!/usr/bin/env python3
"""
Crea los tableros de GamblingDex para DecentHolograms (sin pegar comandos).

Uso:
  1. Copia este archivo a la carpeta del servidor (donde está la carpeta "plugins").
  2. Ejecuta:  python crear_hologramas.py
  3. Responde: mundo y coordenadas del primer tablero (los demás se ponen en fila).
  4. En el juego: /dh reload
  5. Para acomodar cada uno: párate donde lo quieras y usa /dh movehere <nombre>

También sin preguntas:
  python crear_hologramas.py --world world --x 0 --y 65 --z 0 --gap 5 --axis x
"""

import argparse
import os
import sys

LINE = "&8&m                              "


def top(title, color, metric, n=5, footer=None):
    """Tablero de ranking: título, separador, puestos y una línea del jugador."""
    medal = ["&e&l1.", "&7&l2.", "&6&l3."] + [f"&8&l{i}." for i in range(4, n + 1)]
    lines = [title, LINE]
    for i in range(1, n + 1):
        name_color = "&f" if i <= 3 else "&7"
        lines.append(
            f"{medal[i - 1]} {name_color}%gamblingdex_{metric}_{i}_name% &8- {color}%gamblingdex_{metric}_{i}_value%"
        )
    if footer:
        lines += [LINE, footer]
    return lines


BOARDS = {
    "topbiggest": top(
        "&6&l✦ MEJORES PREMIOS DE LA SEMANA ✦", "&a", "topweek_biggest",
        footer="&7Tu mejor premio: &a%gamblingdex_weekly_biggest%"),
    "topwagered": top(
        "&b&l✦ TOP APOSTADORES DE LA SEMANA ✦", "&b", "topweek_wagered",
        footer="&7Tú apostaste: &b%gamblingdex_weekly_wagered%"),
    "topalltime": top(
        "&d&l✦ LEYENDAS DEL CASINO ✦", "&a", "top_profit",
        footer="&7Tu puesto: &e%gamblingdex_rank% &8| &7Tu ganancia: &a%gamblingdex_profit%"),
    "ultimos": [
        "&a&l✦ ÚLTIMOS GANADORES ✦",
        LINE,
        "&f%gamblingdex_last_win_1%",
        "&7%gamblingdex_last_win_2%",
        "&7%gamblingdex_last_win_3%",
        "&8%gamblingdex_last_win_4%",
        "&8%gamblingdex_last_win_5%",
        LINE,
        "&6&lRÉCORD: &e%gamblingdex_record_win_name% &a+%gamblingdex_record_win_value% &7en &f%gamblingdex_record_win_game%",
    ],
    "topblackjack": top(
        "&2&l✦ TOP BLACKJACK DE LA SEMANA ✦", "&a", "topweek_blackjack_profit",
        footer="&7Tu puesto: &e%gamblingdex_weekly_blackjack_rank% &8| &7Tu ganancia: &a%gamblingdex_weekly_blackjack_profit%"),
    "toppoker": top(
        "&5&l✦ TOP PÓKER DE LA SEMANA ✦", "&a", "topweek_poker_profit", n=3,
        footer="&7Tus manos: &f%gamblingdex_weekly_poker_hands% &8| &7Ganadas: &a%gamblingdex_weekly_poker_hands_won%"),
}


def yaml_str(s):
    """Texto entre comillas simples para YAML (las ' se duplican)."""
    return "'" + s.replace("'", "''") + "'"


def hologram_yaml(world, x, y, z, lines):
    out = [
        f"location: {world}:{x:.3f}:{y:.3f}:{z:.3f}",
        "enabled: true",
        "display-range: 48",
        "update-range: 48",
        "update-interval: 20",
        "facing: 0.0",
        "down-origin: false",
        "pages:",
        "- lines:",
    ]
    for line in lines:
        out.append(f"  - content: {yaml_str(line)}")
        out.append("    height: 0.3")
    out.append("  actions: {}")
    return "\n".join(out) + "\n"


def ask(prompt, default):
    v = input(f"{prompt} [{default}]: ").strip()
    return v or str(default)


def main():
    p = argparse.ArgumentParser(description="Crea los tableros de GamblingDex para DecentHolograms.")
    p.add_argument("--folder", default=os.path.join("plugins", "DecentHolograms", "holograms"),
                   help="Carpeta de hologramas de DecentHolograms")
    p.add_argument("--world")
    p.add_argument("--x", type=float)
    p.add_argument("--y", type=float)
    p.add_argument("--z", type=float)
    p.add_argument("--gap", type=float, help="Bloques entre un tablero y el siguiente")
    p.add_argument("--axis", choices=["x", "z"], help="Eje en el que se ponen en fila")
    p.add_argument("--force", action="store_true", help="Sobrescribir si ya existen")
    a = p.parse_args()

    print("=== Tableros de GamblingDex para DecentHolograms ===")
    print("Tableros:", ", ".join(BOARDS))
    world = a.world or ask("Mundo", "world")
    x = a.x if a.x is not None else float(ask("X del primer tablero", 0))
    y = a.y if a.y is not None else float(ask("Y (altura)", 66))
    z = a.z if a.z is not None else float(ask("Z del primer tablero", 0))
    gap = a.gap if a.gap is not None else float(ask("Separación entre tableros (bloques)", 5))
    axis = a.axis or ask("¿En fila sobre el eje x o z?", "x").lower()
    if axis not in ("x", "z"):
        sys.exit("El eje debe ser x o z.")

    folder = a.folder
    if not os.path.isdir(folder):
        create = ask(f"No existe '{folder}'. ¿Crearla? (s/n)", "s").lower()
        if create != "s":
            sys.exit("Cancelado. Usa --folder con la ruta de plugins/DecentHolograms/holograms.")
        os.makedirs(folder, exist_ok=True)

    created = []
    for i, (name, lines) in enumerate(BOARDS.items()):
        path = os.path.join(folder, f"{name}.yml")
        if os.path.exists(path) and not a.force:
            print(f"  - {name}: ya existe, se deja como está (usa --force para sobrescribir)")
            continue
        hx = x + (i * gap if axis == "x" else 0) + 0.5
        hz = z + (i * gap if axis == "z" else 0) + 0.5
        with open(path, "w", encoding="utf-8") as f:
            f.write(hologram_yaml(world, hx, y, hz, lines))
        created.append(name)
        print(f"  + {name}: {world} {hx:.1f} {y:.1f} {hz:.1f}")

    print()
    if created:
        print(f"Listo: {len(created)} tablero(s) creados en {folder}")
        print("En el juego usa:  /dh reload")
        print("Para moverlos:    /dh movehere <nombre>   (ej. /dh movehere topblackjack)")
    else:
        print("No se creó nada nuevo.")


if __name__ == "__main__":
    main()
