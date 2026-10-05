<div align="center">

# 🎰 GamblingDex

**Casino completo para servidores de Minecraft Paper**

Mesas físicas en el mundo, juegos por menú, fichas con Vault y estadísticas, todo configurable y en español.

[![Release](https://img.shields.io/github/v/release/Martinvb07/GamblingDex?label=versi%C3%B3n&color=gold)](https://github.com/Martinvb07/GamblingDex/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/Martinvb07/GamblingDex/build.yml?branch=main&label=build)](https://github.com/Martinvb07/GamblingDex/actions/workflows/build.yml)
![Paper](https://img.shields.io/badge/Paper-1.20.4%20--%201.21.x-blue)
![Java](https://img.shields.io/badge/Java-17%2B-orange)
![Vault](https://img.shields.io/badge/requiere-Vault-lightgrey)

[**⬇️ Descargar la última versión**](https://github.com/Martinvb07/GamblingDex/releases/latest)

</div>

---

## 📋 Índice

- [Juegos](#-juegos)
- [Características](#-características)
- [Requisitos](#-requisitos)
- [Instalación](#-instalación)
- [Cómo funcionan las fichas](#-cómo-funcionan-las-fichas)
- [Comandos](#-comandos)
- [Permisos](#-permisos)
- [Montar el casino (admins)](#-montar-el-casino-admins)
- [Configuración](#-configuración)
- [Actualizar el plugin](#-actualizar-el-plugin)
- [Compilar desde el código](#-compilar-desde-el-código)

---

## 🎲 Juegos

### Mesas físicas (se construyen en el mundo)

| Juego | Descripción |
|---|---|
| 🃏 **Blackjack** | Mesa con dealer y asientos reales (de 1 a 7 jugadores: se puede jugar solo). Zapato de 6 barajas, *split*, doblar, pago 3:2 y apuestas laterales **Perfect Pairs** y **21+3**. |
| 🃏 **Baccarat** | Mesa con dealer y asientos como el blackjack. Apuestas a Jugador, Banca o Empate (y parejas), reglas reales de tercera carta. |
| ♠️ **Póker Texas Hold'em** | No-limit, hasta 9 asientos, ciegas configurables, comisión de la casa (*rake*) y **torneos sit & go** con ciegas crecientes y reparto de premios. Las cartas de cada jugador solo las ve él. |
| 🔴 **Ruleta americana** | Ruleta construida con bloques (con 0 y 00), con **jackpot** acumulado que cae en un número al azar. Menú ordenado como la mesa, apuestas sin tope, botón de **repetir apuesta** y una luz que gira rápido y frena hasta caer en el ganador. |
| 🎡 **Rueda de la Fortuna** | Se construye sola alrededor de un faro: pared de lámparas de redstone que se encienden en cadena y una fila de concreto que se desplaza al girar. Gana el color que queda encima del faro (x1, x2, x5, x10, x20 o x40). |
| 🐎 **Carrera de caballos** | La pista se construye sola (vallas, puertas de salida y meta) con un comando. Caballos reales y apuestas mutuas tipo hipódromo: el pozo se reparte entre quienes acertaron. |
| 📈 **Crash** | Cada mesa tiene su propia ronda, con el multiplicador en vivo encima: sube hasta que explota. Click derecho para apostar, **shift + click derecho** para retirarte. Tu apuesta y lo que cobras salen sobre la barra de experiencia. |
| 🎰 **Tragamonedas** | Estaciones de slots con probabilidades por símbolo configurables y **jackpot progresivo**: cada tirada aporta un % al pozo y tres estrellas del Nether se lo llevan todo (con anuncio a todo el servidor). |
| 💣 **Mines** | **Estación con menú**: tablero de 5×5 en un cofre, eliges apuesta y minas, cada casilla segura sube el multiplicador y te retiras cuando quieras (juegan varios a la vez). También se puede construir como **pared física** de 5×5. |
| 🗼 **Tower** | **Estación con menú**: sube una torre eligiendo puertas en cada piso; detrás de alguna hay una trampa. Cuatro dificultades, el premio crece con cada piso y te retiras cuando quieras. |
| 🎯 **Plinko** | **Estación con menú**: la bola baja animada entre los clavos y cae en una casilla con multiplicador (shift = 5 bolas). También como **pared física** con clavos; las orillas pagan hasta x10. |
| 🎁 **Bonos** | Estación con menú: bono **diario** (24 h, con racha), **semanal** y **mensual**, en fichas. Las fichas de bono no se pueden vender por dinero hasta apostar x3 lo reclamado (configurable). |

### Juegos por menú / comando

| Juego | Descripción |
|---|---|
| 🪙 **Coinflip** | Cara o sello 1 vs 1 entre jugadores, 50/50. |
| 🎟️ **Rasca y Gana** | Boletos Bronce, Plata y Oro. El resultado se sortea al comprar, así que no se puede hacer trampa. |
| 🎱 **Bingo** | 75 bolas, premios por **línea** y **bingo**. Partidas a horas fijas (por defecto 2:00 p. m. y 8:00 p. m., hora de Colombia) o cuando un admin la inicie. |
| 🍀 **Lotería** | Sorteos a horas fijas (con zona horaria). Más boletos, más probabilidad de ganar. |

---

## ✨ Características

- **Fichas físicas**: ítems con valor que se compran y venden con el dinero del servidor (Vault).
- **Hologramas** con `TextDisplay` sobre las mesas, las cartas y los jugadores.
- **Estadísticas y ranking**: total apostado, ganado, perdido y mejor premio por jugador.
- **Todo editable**: mensajes en `messages/*.yml`, cada juego en `modules/*.yml`, y recarga en caliente con `/gdx reload`.
- **Comisión de la casa** configurable en cada juego.
- **Seguro ante reinicios**: las mesas se guardan en disco y al apagar el servidor las rondas en curso se reembolsan y los boletos de lotería se guardan.
- **Módulos activables**: cada juego se puede apagar con `enabled: false`.
- **Modo mantenimiento**: `/gdx disable crash` cierra un juego al momento (sin reiniciar) y devuelve las apuestas en curso.
- **Horario del casino** (`/gdx schedule`, `schedule.yml`): abre y cierra solo a las horas que elijas (hora de Colombia por defecto), con avisos antes de cerrar. Al cerrar se devuelven las apuestas en curso.
- **Crupier que habla**: el dealer de blackjack dice frases al repartir, con blackjack, al pasarse, al ganar... en el chat de los de cerca y en un globo sobre su cabeza (`blackjack.yml` → `dealer_talk`).
- **Campana del jackpot**: cuando el pozo de slots o de la ruleta pasa de un mínimo, suena una campana y sale un aviso solo cerca de las estaciones o mesas (`jackpot.bell`).
- **Propinas en el póker**: botón "Dar propina" en el menú de la mesa; el crupier da las gracias (`poker.yml` → `tips`).
- **Inspeccionar jugadores** (`/gdx inspect <player>`): fichas, bono bloqueado, ganancias, juego favorito, póker, logros y últimas apuestas en un menú.
- **Logros del casino** (`achievements.yml`): primer blackjack, jackpot, retirarse en x10 en Crash... con recompensas en fichas o comandos y su menú (`/gdx achievements`).
- **Historial personal** (`/gdx history`), últimos premios y récord del casino para hologramas.

---

## 📦 Requisitos

| | |
|---|---|
| **Servidor** | [Paper](https://papermc.io) 1.20.4 – 1.21.x |
| **Java** | 17 o superior |
| **Dependencias** | [Vault](https://www.spigotmc.org/resources/vault.34315/) + un plugin de economía (EssentialsX, CMI, etc.) |

---

## 🚀 Instalación

1. Descarga `GamblingDex-X.Y.Z.jar` desde [Releases](https://github.com/Martinvb07/GamblingDex/releases/latest).
2. Asegúrate de tener **Vault** y un plugin de economía instalados.
3. Copia el `.jar` en la carpeta `plugins/` de tu servidor.
4. Inicia el servidor. Se crea `plugins/GamblingDex/` con toda la configuración.
5. Ajusta los archivos a tu gusto y usa `/gdx reload`.

---

## 💰 Cómo funcionan las fichas

Todos los juegos usan **fichas** (tokens), que son ítems físicos con un valor.

```
Dinero del servidor  ──(Cambio)──▶  Fichas  ──▶  Juegos  ──▶  Fichas  ──(Cambio)──▶  Dinero
```

- Los jugadores compran y venden fichas en una **estación de Cambio** (`/gdx station set exchange`).
- `exchange.money_per_unit` define cuánto dinero vale cada ficha.
- `/gdx balance` muestra el saldo de la moneda interna del plugin.

---

## ⌨️ Comandos

Comando principal: `/gdx` (alias: `/gamblingdex`, `/gambledex`)

### Jugadores

| Comando | Descripción |
|---|---|
| `/gdx help` | Muestra la ayuda |
| `/gdx balance` | Tu saldo interno |
| `/gdx stats [jugador]` | Estadísticas propias o de otro jugador |
| `/gdx top [week] [n]` | Ranking de ganancias (todos los juegos) |
| `/gdx history` | Tus últimas 10 apuestas |
| `/gdx achievements` | Tus logros del casino (menú) |
| `/gdx coinflip` · `create <amount> [cara\|sello]` · `cancel` | Coinflip |
| `/gdx scratch` · `buy <tipo> [amount]` | Rasca y Gana |
| `/gdx bingo` · `buy <n>` | Bingo |
| `/gdx lottery` · `buy <n>` | Lotería |

En las mesas y estaciones (blackjack, póker, baccarat, ruleta, rueda, carrera, crash, mines, plinko, tower, slots, bono diario) se juega con **click derecho** o **parándose en un asiento**. Los menús se abren solos.

### Administradores

| Comando | Descripción |
|---|---|
| `/gdx reload` | Recarga configuración y mensajes |
| `/gdx station <set\|remove\|list> [slots\|exchange]` | Estaciones de slots y cambio (mirando el bloque) |
| `/gdx station set crash` | Mesa de Crash (mirando cualquier bloque) |
| `/gdx station set wheel` | Construir la rueda física (mirando un faro) |
| `/gdx station set mines` | Estación de Mines con menú (mirando cualquier bloque) |
| `/gdx station set mines wall` | Construir la pared 5×5 de Mines (mirando el bloque de la mesa) |
| `/gdx station set plinko` | Estación de Plinko con menú (mirando cualquier bloque) |
| `/gdx station set tower` | Estación de Tower con menú (mirando cualquier bloque) |
| `/gdx station set plinko wall [rows]` | Construir la pared de Plinko (mirando el bloque de la mesa) |
| `/gdx station set daily` | Estación de bonos (diario, semanal, mensual) |
| `/gdx station set baccarat [name]` | Crear mesa de baccarat (mirando el bloque de la mesa) |
| `/gdx baccarat seat <add\|remove\|list\|clear> <table>` | Asientos de baccarat (parado encima) |
| `/gdx station set race <distance> <lanes> [name]` | Construir una pista de carreras completa |
| `/gdx roulette <build\|remove\|list> [radius] [yOffset]` | Ruletas físicas |
| `/gdx blackjack <create\|remove\|list> <name>` | Mesas de blackjack |
| `/gdx blackjack seat <add\|remove\|list\|clear> <name>` | Asientos de blackjack |
| `/gdx blackjack create <name> [min] [max]` · `limits <name> <min> [max]` | Apuesta mínima y máxima de cada mesa (ej. `create Blackjack2 25000 100000`); las laterales 21+3 y pares tienen mínimo 1/5 (5.000) y no tienen tope, y solo se aceptan fichas de 5.000 o más |
| `/gdx blackjack rename <table> <name...>` · `poker rename` · `baccarat rename` | Renombrar la mesa desde el juego, con colores y símbolos (ej. `&6&l♠ BLACKJACK &8\| &eVIP`); `-` vuelve al nombre interno |
| `/gdx blackjack face <name>` | El dealer mira hacia donde estás (al crear la mesa ya mira hacia ti) |
| `/gdx poker create <name> [small] [big]` | Crear mesa de póker |
| `/gdx poker <remove\|list\|stakes\|rake> ...` | Gestionar mesas de póker |
| `/gdx poker seat <add\|remove\|list\|clear> <name>` | Asientos de póker |
| `/gdx poker tournament <table> <fee> [chips] [minutes]` | Crear un torneo |
| `/gdx poker tournament <start\|cancel> <table>` | Empezar o cancelar un torneo |
| `/gdx wheel <create\|remove\|list> [name]` | Ruedas de la fortuna |
| `/gdx race <create\|remove\|list\|start> ...` | Pistas de carrera manuales |
| `/gdx bingo start` | Abrir la venta de bingo ya |
| `/gdx lottery draw` | Sortear la lotería ya |
| `/gdx item <roulette\|slots> [amount]` | Ítems que abren menús |
| `/gdx token <color\|valor> <amount>` | Crear fichas (ej. para Shopkeepers) |
| `/gdx disable <game>` | **Mantenimiento**: cierra un juego al instante y devuelve las apuestas en curso |
| `/gdx enable <game>` | Vuelve a abrir el juego |
| `/gdx maintenance` | Juegos en mantenimiento |
| `/gdx config check` | Revisa los valores de la config: materiales o sonidos que no existen, símbolo del jackpot que no está en `symbol_weights`, pesos en 0, mínima mayor que la máxima, horario mal escrito... |
| `/gdx sign add <type> ...` | **Carteles que se actualizan solos** (mirando un cartel): ganadores, récord, jackpots, tops (ganancias, semana, mayor premio, más apostado, póker), horario, jugadores ahora, estado de un juego, info de una mesa, últimos números de la ruleta y de Crash, lotería, bingo y precio de las fichas. Todos los tipos: `/gdx help signs`. Textos en `config.yml` → `signs` |
| `/gdx schedule` | **Horario del casino** en un menú: activar, hora de apertura y cierre, días |
| `/gdx inspect <player>` | Menú con las fichas, bono, ganancias y últimas apuestas de un jugador |

---

## 🔐 Permisos

| Permiso | Descripción | Por defecto |
|---|---|---|
| `gamblingdex.use` | Usar el plugin y sus juegos | Todos |
| `gamblingdex.stats.self` | Ver tus estadísticas | Todos |
| `gamblingdex.top` | Ver el ranking | Todos |
| `gamblingdex.stats.others` | Ver estadísticas de otros | OP |
| `gamblingdex.admin` | Comandos de administración | OP |

Ejemplo con LuckPerms:

```
/lp group admin permission set gamblingdex.admin true
```

---

## 🏗️ Montar el casino (admins)

<details>
<summary><b>🃏 Mesa de Blackjack</b></summary>

1. Mira el bloque de la mesa: `/gdx blackjack create Mesa1`
2. Párate en cada asiento y usa: `/gdx blackjack seat add Mesa1`
3. Los jugadores entran parándose en un asiento.

</details>

<details>
<summary><b>🃏 Mesa de Baccarat</b></summary>

1. Mira el bloque de la mesa: `/gdx station set baccarat Mesa1` (el dealer aparece detrás, mirando hacia ti).
2. Párate en cada asiento y usa: `/gdx baccarat seat add Mesa1`
3. Los jugadores se sientan y el menú de apuestas se abre solo. El título se cambia en `modules/baccarat.yml` → `table_names`.

</details>

<details>
<summary><b>♠️ Mesa de Póker</b></summary>

1. Mira el bloque central de la mesa: `/gdx poker create Mesa1 5 10` (ciegas 5/10)
2. Párate en cada asiento, en sentido horario: `/gdx poker seat add Mesa1`
3. Los jugadores se sientan, compran fichas de mesa y al levantarse se les devuelven.
4. Torneo: `/gdx poker tournament Mesa1 1000` y luego `/gdx poker tournament start Mesa1`

</details>

<details>
<summary><b>🔴 Ruleta</b></summary>

1. Mira el bloque donde irá el centro: `/gdx roulette build [radius] [yOffset]`
2. Las rondas arrancan solas cada `roulette_world.auto_cycle.interval_seconds`.

</details>

<details>
<summary><b>🎡 Rueda de la Fortuna</b></summary>

Coloca un **faro**, míralo y usa `/gdx station set wheel`. Alrededor del faro se construye la pared de lámparas con los 7 colores encima; arriba sale un holograma con lo que paga cada color. Los jugadores apuestan con click derecho al faro. Para quitarla: `/gdx station remove` mirando el faro.

</details>

<details>
<summary><b>🐎 Carrera de caballos</b></summary>

Párate en terreno plano, mira el bloque que será la **mesa de apuestas** y usa:

```
/gdx station set race 30 6
```

Detrás de ese bloque, en la dirección en que miras, se construye la pista: 6 carriles de 30 bloques con vallas, puertas de salida y meta a cuadros. Los jugadores apuestan con click derecho a la mesa. Para quitarla, mira la mesa y usa `/gdx station remove`: los bloques vuelven a como estaban.

</details>

<details>
<summary><b>🎯 Plinko</b></summary>

Mira el bloque que será la mesa y usa `/gdx station set plinko [6|8|10|12]` (filas; por defecto 8). Detrás se construye la pared con clavos y las casillas con su multiplicador. Necesita espacio: 8 filas = 19 × 19 bloques. Los jugadores sueltan bolas con click derecho a la mesa; shift + click derecho repite la apuesta.

</details>

<details>
<summary><b>💣 Mines</b></summary>

Mira el bloque que será la mesa y usa `/gdx station set mines`. Detrás se construye una pared de 5×5 casillas con marco. Los jugadores empiezan con click derecho a la mesa (eligen minas y apuesta), abren casillas con click derecho y se retiran con shift + click derecho.

</details>

<details>
<summary><b>🗼 Tower</b></summary>

Mira cualquier bloque y usa `/gdx station set tower`. Click derecho abre la torre en un menú: el jugador elige apuesta y dificultad (fácil, normal, difícil o experto), y en cada piso abre una puerta. Si es segura sube un piso y el premio crece; si es una trampa pierde la apuesta. Se retira cuando quiera (cerrar el menú también lo retira). Pisos, dificultades y ventaja de la casa en `modules/tower.yml`.

</details>

<details>
<summary><b>📈 Crash</b></summary>

Mira cualquier bloque: `/gdx station set crash`. Encima aparece un holograma con el multiplicador en vivo.

- **Click derecho** a la mesa: poner la apuesta.
- **Shift + click derecho**: retirarse y cobrar.
- Cada jugador ve sobre la barra de experiencia su apuesta, el multiplicador y lo que cobraría.

</details>

<details>
<summary><b>🎰 Slots y 💱 Cambio</b></summary>

Mira el bloque y usa `/gdx station set slots` o `/gdx station set exchange`.

</details>

---

## ⚙️ Configuración

```
plugins/GamblingDex/
├── config.yml            # Solo lo general: fichas, cambio (Vault) y estaciones
├── messages/             # Todos los textos (editables)
│   ├── gdx.yml
│   ├── blackjack.yml
│   ├── poker.yml
│   └── ...
└── modules/              # Un archivo por juego
    ├── blackjack.yml
    ├── poker.yml
    ├── ruleta.yml
    ├── slots.yml
    ├── mines.yml
    ├── plinko.yml
    ├── tower.yml
    ├── baccarat.yml
    ├── crash.yml
    ├── carrera.yml
    ├── rueda.yml
    ├── coinflip.yml
    ├── rasca.yml
    ├── bingo.yml
    └── loteria.yml
```

Si vienes de una versión anterior, tus valores de blackjack, póker, ruleta y slots se copian solos de `config.yml` a sus archivos la primera vez.

**Al actualizar el plugin**, las opciones nuevas de cada versión (y sus comentarios) se añaden solas a tus archivos, sin tocar los valores que ya cambiaste. Lo que borres a propósito (una mesa, un logro, un símbolo...) no vuelve a aparecer: el plugin recuerda en `config_keys.yml` qué opciones ya te ofreció. En la consola sale qué se añadió en cada archivo.

Los colores usan `&` (ej. `&a`, `&6&l`) y también hexadecimales `&#RRGGBB` (ej. `&#FF8800`). Después de editar, usa `/gdx reload`.

**Nombres de las mesas:** cada mesa de blackjack y póker que creas aparece sola en `table_names` de `modules/blackjack.yml` / `modules/poker.yml`. El identificador es el nombre con el que la creaste; ahí le pones el título que quieras, con colores y símbolos:

```yaml
table_names:
  MESA-VIP1: "&6&l✦ &#FFD700&lMESA VIP &6&l✦"
```

---

## 🏷️ Placeholders (PlaceholderAPI)

Si tienes [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/), GamblingDex registra estos placeholders para hologramas (DecentHolograms), scoreboards, TAB, etc. Los de ganancias cuentan **todos los juegos** menos el póker, que tiene los suyos (`poker_...`).

| Placeholder | Qué muestra |
|---|---|
| `%gamblingdex_balance%` | Fichas que tiene el jugador |
| `%gamblingdex_profit%` | Ganancia neta (cobrado − apostado) |
| `%gamblingdex_wagered%` | Total apostado |
| `%gamblingdex_paid%` | Total cobrado |
| `%gamblingdex_biggest%` | Mejor premio de una sola apuesta |
| `%gamblingdex_rounds%` | Apuestas jugadas |
| `%gamblingdex_rank%` | Puesto en el ranking de ganancias |
| `%gamblingdex_weekly_profit%` | Igual que los de arriba, pero de esta semana (`weekly_...`) |
| `%gamblingdex_top_profit_1_name%` | Nombre del 1° en ganancias |
| `%gamblingdex_top_profit_1_value%` | Ganancia del 1° |
| `%gamblingdex_topweek_profit_1_name%` | Igual, pero del ranking semanal |
| `%gamblingdex_jackpot_ruleta%` | Pozo del jackpot de la ruleta |
| `%gamblingdex_jackpot_slots%` | Pozo del jackpot progresivo de los slots |
| `%gamblingdex_top_blackjack_profit_1_name%` · `_value` | Top de un juego (`topweek_...` = semana). Juegos: `blackjack`, `roulette`, `crash`, `slots`, `baccarat`, `mines`, `plinko`, `tower`, `wheel`, `race`, `coinflip`, `scratch`, `bingo`, `lottery` |
| `%gamblingdex_blackjack_profit%` · `_wagered` · `_biggest` · `_rounds` · `_rank` | Stats del jugador en un juego (`weekly_blackjack_...` = semana) |
| `%gamblingdex_last_win_1%` | Último premio: "Martin +400.000 en Blackjack" (1 a 10; también `_name`, `_value`, `_game`) |
| `%gamblingdex_record_win_name%` · `_value` · `_game` | Récord del casino (el premio más grande de la historia) |
| `%gamblingdex_players_playing%` | Jugadores que jugaron en los últimos 5 minutos |
| `%gamblingdex_crash_multiplier%` | Multiplicador del Crash en vivo (también `_state`, `_seconds`, `_players`) |
| `%gamblingdex_bingo_next%` · `%gamblingdex_bingo_pot%` | Próxima partida de bingo y su pozo |
| `%gamblingdex_lottery_pot%` · `%gamblingdex_lottery_next%` | Pozo de la lotería y tiempo al sorteo |
| `%gamblingdex_achievements%` · `_total` | Logros del jugador / cuántos hay |
| `%gamblingdex_poker_hands%` · `_hands_won` · `_profit` · `_biggest_pot` · `_tournaments` | Estadísticas de póker (`weekly_poker_...` = semana) |
| `%gamblingdex_top_poker_profit_1_name%` · `_value` | Top de póker (`topweek_poker_...` = semana; métricas `profit`, `hands`, `hands_won`, `biggest_pot`, `tournaments`) |

En los `top_...` puedes cambiar `profit` por `wagered`, `paid` o `biggest`, y el número por el puesto (1, 2, 3...). El ranking semanal se reinicia el lunes (`stats.timezone` en `config.yml`).

Todos estos placeholders funcionan desde la versión **1.1.0**. Si alguno sale como texto literal, actualiza el plugin.

### Tableros para hologramas (DecentHolograms)

Párate donde quieras el tablero y pega sus comandos. El `1` después del nombre es la página del holograma.

**💰 Mejores premios de la semana** (siempre positivo)
```
/dh create topbiggest &6&l✦ MEJORES PREMIOS DE LA SEMANA ✦
/dh line add topbiggest 1 &8&m                              
/dh line add topbiggest 1 &e&l1. &f%gamblingdex_topweek_biggest_1_name% &8- &a%gamblingdex_topweek_biggest_1_value%
/dh line add topbiggest 1 &7&l2. &f%gamblingdex_topweek_biggest_2_name% &8- &a%gamblingdex_topweek_biggest_2_value%
/dh line add topbiggest 1 &6&l3. &f%gamblingdex_topweek_biggest_3_name% &8- &a%gamblingdex_topweek_biggest_3_value%
/dh line add topbiggest 1 &8&l4. &7%gamblingdex_topweek_biggest_4_name% &8- &a%gamblingdex_topweek_biggest_4_value%
/dh line add topbiggest 1 &8&l5. &7%gamblingdex_topweek_biggest_5_name% &8- &a%gamblingdex_topweek_biggest_5_value%
/dh line add topbiggest 1 &8&m                              
/dh line add topbiggest 1 &7Tu mejor premio: &a%gamblingdex_weekly_biggest%
```

**🎲 Top apostadores de la semana**
```
/dh create topwagered &b&l✦ TOP APOSTADORES DE LA SEMANA ✦
/dh line add topwagered 1 &8&m                              
/dh line add topwagered 1 &e&l1. &f%gamblingdex_topweek_wagered_1_name% &8- &b%gamblingdex_topweek_wagered_1_value%
/dh line add topwagered 1 &7&l2. &f%gamblingdex_topweek_wagered_2_name% &8- &b%gamblingdex_topweek_wagered_2_value%
/dh line add topwagered 1 &6&l3. &f%gamblingdex_topweek_wagered_3_name% &8- &b%gamblingdex_topweek_wagered_3_value%
/dh line add topwagered 1 &8&l4. &7%gamblingdex_topweek_wagered_4_name% &8- &b%gamblingdex_topweek_wagered_4_value%
/dh line add topwagered 1 &8&l5. &7%gamblingdex_topweek_wagered_5_name% &8- &b%gamblingdex_topweek_wagered_5_value%
/dh line add topwagered 1 &8&m                              
/dh line add topwagered 1 &7Tú apostaste: &b%gamblingdex_weekly_wagered%
```

**🏆 Leyendas del casino** (ganancia de siempre)
```
/dh create topalltime &d&l✦ LEYENDAS DEL CASINO ✦
/dh line add topalltime 1 &8&m                              
/dh line add topalltime 1 &e&l1. &f%gamblingdex_top_profit_1_name% &8- &a%gamblingdex_top_profit_1_value%
/dh line add topalltime 1 &7&l2. &f%gamblingdex_top_profit_2_name% &8- &a%gamblingdex_top_profit_2_value%
/dh line add topalltime 1 &6&l3. &f%gamblingdex_top_profit_3_name% &8- &a%gamblingdex_top_profit_3_value%
/dh line add topalltime 1 &8&l4. &7%gamblingdex_top_profit_4_name% &8- &a%gamblingdex_top_profit_4_value%
/dh line add topalltime 1 &8&l5. &7%gamblingdex_top_profit_5_name% &8- &a%gamblingdex_top_profit_5_value%
/dh line add topalltime 1 &8&m                              
/dh line add topalltime 1 &7Tu puesto: &e%gamblingdex_rank% &8| &7Tu ganancia: &a%gamblingdex_profit%
```

**🔥 Últimos ganadores y récord**
```
/dh create ultimos &a&l✦ ÚLTIMOS GANADORES ✦
/dh line add ultimos 1 &8&m                              
/dh line add ultimos 1 &f%gamblingdex_last_win_1%
/dh line add ultimos 1 &7%gamblingdex_last_win_2%
/dh line add ultimos 1 &7%gamblingdex_last_win_3%
/dh line add ultimos 1 &8%gamblingdex_last_win_4%
/dh line add ultimos 1 &8%gamblingdex_last_win_5%
/dh line add ultimos 1 &8&m                              
/dh line add ultimos 1 &6&lRÉCORD: &e%gamblingdex_record_win_name% &a+%gamblingdex_record_win_value% &7en &f%gamblingdex_record_win_game%
```

**🃏 Top blackjack de la semana**
```
/dh create topblackjack &2&l✦ TOP BLACKJACK DE LA SEMANA ✦
/dh line add topblackjack 1 &8&m                              
/dh line add topblackjack 1 &e&l1. &f%gamblingdex_topweek_blackjack_profit_1_name% &8- &a%gamblingdex_topweek_blackjack_profit_1_value%
/dh line add topblackjack 1 &7&l2. &f%gamblingdex_topweek_blackjack_profit_2_name% &8- &a%gamblingdex_topweek_blackjack_profit_2_value%
/dh line add topblackjack 1 &6&l3. &f%gamblingdex_topweek_blackjack_profit_3_name% &8- &a%gamblingdex_topweek_blackjack_profit_3_value%
/dh line add topblackjack 1 &8&l4. &7%gamblingdex_topweek_blackjack_profit_4_name% &8- &a%gamblingdex_topweek_blackjack_profit_4_value%
/dh line add topblackjack 1 &8&l5. &7%gamblingdex_topweek_blackjack_profit_5_name% &8- &a%gamblingdex_topweek_blackjack_profit_5_value%
/dh line add topblackjack 1 &8&m                              
/dh line add topblackjack 1 &7Tu puesto: &e%gamblingdex_weekly_blackjack_rank% &8| &7Tu ganancia: &a%gamblingdex_weekly_blackjack_profit%
```
Sirve igual para cualquier juego cambiando `blackjack` por `roulette`, `crash`, `slots`, `baccarat`, `mines`, `plinko`, `tower`, `wheel`, `race`, `coinflip`, `scratch`, `bingo` o `lottery`.

**♠ Top póker de la semana**
```
/dh create toppoker &5&l✦ TOP PÓKER DE LA SEMANA ✦
/dh line add toppoker 1 &8&m                              
/dh line add toppoker 1 &e&l1. &f%gamblingdex_topweek_poker_profit_1_name% &8- &a%gamblingdex_topweek_poker_profit_1_value%
/dh line add toppoker 1 &7&l2. &f%gamblingdex_topweek_poker_profit_2_name% &8- &a%gamblingdex_topweek_poker_profit_2_value%
/dh line add toppoker 1 &6&l3. &f%gamblingdex_topweek_poker_profit_3_name% &8- &a%gamblingdex_topweek_poker_profit_3_value%
/dh line add toppoker 1 &8&m                              
/dh line add toppoker 1 &7Tus manos: &f%gamblingdex_weekly_poker_hands% &8| &7Ganadas: &a%gamblingdex_weekly_poker_hands_won%
```

Los tops `topweek_...` se reinician cada lunes; los `top_...` son de siempre. Para más puestos, copia una línea y cambia el número (6, 7...). Mover: `/dh movehere <nombre>` · borrar: `/dh delete <nombre>`.

---

## 🔄 Actualizar el plugin

1. **Apaga** el servidor. No uses `/reload` ni PlugMan.
2. Haz una copia de `plugins/GamblingDex/` por seguridad.
3. Reemplaza el `.jar` viejo por el nuevo.
4. Enciende el servidor.

Las mesas, saldos y estadísticas viven en `plugins/GamblingDex/`, no en el `.jar`, así que se conservan.

---

## 🛠️ Compilar desde el código

Requiere **JDK 17+** y **Maven**.

```bash
git clone https://github.com/Martinvb07/GamblingDex.git
cd GamblingDex
mvn clean package
```

El `.jar` queda en `target/GamblingDex.jar`.

> Cada push a `main` compila el plugin con GitHub Actions y publica una nueva versión (`1.0.1`, `1.0.2`, …) en [Releases](https://github.com/Martinvb07/GamblingDex/releases).

---

<div align="center">

Hecho por **[Martinvb07](https://github.com/Martinvb07)**

</div>
