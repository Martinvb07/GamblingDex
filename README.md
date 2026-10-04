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
| ♠️ **Póker Texas Hold'em** | No-limit, hasta 9 asientos, ciegas configurables, comisión de la casa (*rake*) y **torneos sit & go** con ciegas crecientes y reparto de premios. Las cartas de cada jugador solo las ve él. |
| 🔴 **Ruleta americana** | Ruleta construida con bloques (con 0 y 00), con **jackpot** acumulado que cae en un número al azar. Menú ordenado como la mesa, apuestas sin tope, botón de **repetir apuesta** y una luz que gira rápido y frena hasta caer en el ganador. |
| 🎡 **Rueda de la Fortuna** | Se construye sola alrededor de un faro: pared de lámparas de redstone que se encienden en cadena y una fila de concreto que se desplaza al girar. Gana el color que queda encima del faro (x1, x2, x5, x10, x20 o x40). |
| 🐎 **Carrera de caballos** | La pista se construye sola (vallas, puertas de salida y meta) con un comando. Caballos reales y apuestas mutuas tipo hipódromo: el pozo se reparte entre quienes acertaron. |
| 📈 **Crash** | Mesa con el multiplicador en vivo encima: sube hasta que explota. Click derecho para apostar, **shift + click derecho** para retirarte. Tu apuesta y lo que cobras salen sobre la barra de experiencia. |
| 🎰 **Tragamonedas** | Estaciones de slots con probabilidades por símbolo configurables. |

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
| `/gdx top [semana] [n]` | Ranking de ganancias (todos los juegos) |
| `/gdx coinflip` · `crear <monto> [cara\|sello]` · `cancelar` | Coinflip |
| `/gdx rasca` · `comprar <tipo> [cantidad]` | Rasca y Gana |
| `/gdx bingo` · `comprar <n>` | Bingo |
| `/gdx loteria` · `comprar <n>` | Lotería |

En las mesas físicas (blackjack, póker, ruleta, rueda, carrera, crash, slots) se juega con **click derecho** o **parándose en un asiento**. Los menús se abren solos.

### Administradores

| Comando | Descripción |
|---|---|
| `/gdx reload` | Recarga configuración y mensajes |
| `/gdx station <set\|remove\|list> [slots\|exchange]` | Estaciones de slots y cambio (mirando el bloque) |
| `/gdx station set crash` | Mesa de Crash (mirando cualquier bloque) |
| `/gdx station set rueda` | Construir la rueda física (mirando un faro) |
| `/gdx station set carrera <distancia> <carriles> [nombre]` | Construir una pista de carreras completa |
| `/gdx roulette <build\|remove\|list> [radio] [yOffset]` | Ruletas físicas |
| `/gdx blackjack <create\|remove\|list> <nombre>` | Mesas de blackjack |
| `/gdx blackjack seat <add\|remove\|list\|clear> <nombre>` | Asientos de blackjack |
| `/gdx poker create <nombre> [chica] [grande]` | Crear mesa de póker |
| `/gdx poker <remove\|list\|stakes\|rake> ...` | Gestionar mesas de póker |
| `/gdx poker seat <add\|remove\|list\|clear> <nombre>` | Asientos de póker |
| `/gdx poker torneo <mesa> <inscripción> [fichas] [minutos]` | Crear un torneo |
| `/gdx poker torneo <empezar\|cancelar> <mesa>` | Empezar o cancelar un torneo |
| `/gdx rueda <crear\|borrar\|lista> [nombre]` | Ruedas de la fortuna |
| `/gdx carrera <crear\|borrar\|lista\|iniciar> ...` | Pistas de carrera manuales |
| `/gdx bingo iniciar` | Abrir la venta de bingo ya |
| `/gdx loteria sortear` | Sortear la lotería ya |
| `/gdx item <roulette\|slots> [cantidad]` | Ítems que abren menús |
| `/gdx token <color\|valor> <cantidad>` | Crear fichas (ej. para Shopkeepers) |

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
<summary><b>♠️ Mesa de Póker</b></summary>

1. Mira el bloque central de la mesa: `/gdx poker create Mesa1 5 10` (ciegas 5/10)
2. Párate en cada asiento, en sentido horario: `/gdx poker seat add Mesa1`
3. Los jugadores se sientan, compran fichas de mesa y al levantarse se les devuelven.
4. Torneo: `/gdx poker torneo Mesa1 1000` y luego `/gdx poker torneo empezar Mesa1`

</details>

<details>
<summary><b>🔴 Ruleta</b></summary>

1. Mira el bloque donde irá el centro: `/gdx roulette build [radio] [yOffset]`
2. Las rondas arrancan solas cada `roulette_world.auto_cycle.interval_seconds`.

</details>

<details>
<summary><b>🎡 Rueda de la Fortuna</b></summary>

Coloca un **faro**, míralo y usa `/gdx station set rueda`. Alrededor del faro se construye la pared de lámparas con los 7 colores encima; arriba sale un holograma con lo que paga cada color. Los jugadores apuestan con click derecho al faro. Para quitarla: `/gdx station remove` mirando el faro.

</details>

<details>
<summary><b>🐎 Carrera de caballos</b></summary>

Párate en terreno plano, mira el bloque que será la **mesa de apuestas** y usa:

```
/gdx station set carrera 30 6
```

Detrás de ese bloque, en la dirección en que miras, se construye la pista: 6 carriles de 30 bloques con vallas, puertas de salida y meta a cuadros. Los jugadores apuestan con click derecho a la mesa. Para quitarla, mira la mesa y usa `/gdx station remove`: los bloques vuelven a como estaban.

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
    ├── crash.yml
    ├── carrera.yml
    ├── rueda.yml
    ├── coinflip.yml
    ├── rasca.yml
    ├── bingo.yml
    └── loteria.yml
```

Si vienes de una versión anterior, tus valores de blackjack, póker, ruleta y slots se copian solos de `config.yml` a sus archivos la primera vez.

Los colores usan `&` (ej. `&a`, `&6&l`) y también hexadecimales `&#RRGGBB` (ej. `&#FF8800`). Después de editar, usa `/gdx reload`.

**Nombres de las mesas:** cada mesa de blackjack y póker que creas aparece sola en `table_names` de `modules/blackjack.yml` / `modules/poker.yml`. El identificador es el nombre con el que la creaste; ahí le pones el título que quieras, con colores y símbolos:

```yaml
table_names:
  MESA-VIP1: "&6&l✦ &#FFD700&lMESA VIP &6&l✦"
```

---

## 🏷️ Placeholders (PlaceholderAPI)

Si tienes [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/), GamblingDex registra estos placeholders para hologramas (DecentHolograms), scoreboards, TAB, etc. Cuentan **todos los juegos** menos el póker.

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

En los `top_...` puedes cambiar `profit` por `wagered`, `paid` o `biggest`, y el número por el puesto (1, 2, 3...). El ranking semanal se reinicia el lunes (`stats.timezone` en `config.yml`).

**Ejemplo de holograma con DecentHolograms:**

```
/dh create topcasino &6&l★ TOP GANANCIAS DE LA SEMANA ★
/dh line add topcasino &e1. &f%gamblingdex_topweek_profit_1_name% &7- &a%gamblingdex_topweek_profit_1_value%
/dh line add topcasino &72. &f%gamblingdex_topweek_profit_2_name% &7- &a%gamblingdex_topweek_profit_2_value%
/dh line add topcasino &63. &f%gamblingdex_topweek_profit_3_name% &7- &a%gamblingdex_topweek_profit_3_value%
```

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
