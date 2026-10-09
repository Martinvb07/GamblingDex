# Modelos 3D, menús y sonidos del casino

Todo es **opcional**: sin ModelEngine ni resource pack las slots funcionan igual que siempre.

![slot_machine](previews/slot_machine.png)

## Máquina de slots en 3D (ModelEngine R4)

`slot_machine.bbmodel` (se abre y edita en Blockbench):

| Animación | Qué hace |
|---|---|
| `idle` | Luces titilando (en bucle) |
| `spin` | Baja la palanca y parpadean las luces |
| `reelR_K` | Rodillo R (1 = izquierda) gira y se queda con la cara K al frente. El plugin elige K para que paren **en los símbolos que tocaron** |
| `win` | Luces a tope y el cartel rebota |

Los símbolos son los ítems del módulo de slots (diamante, esmeralda, oro, hierro, amatista
y estrella del Nether). Si un tema usa otros ítems, la máquina muestra unos equivalentes
respetando parejas y tríos.

**Instalar**
1. Instala ModelEngine. Al arrancar, GamblingDex copia el modelo a
   `plugins/ModelEngine/blueprints/gamblingdex/` (si no estaba).
2. `/meg reload`. El modelo aparece encima de cada estación de slots (se puede clickear).
3. `/gdx station rotate` mirando la estación para girarla 90°.
   A quien ve el modelo se le esconden la mesa de encantamientos y el holograma (solo en su
   pantalla) y la máquina queda en el suelo (`slots.yml → model.hide_station`).
4. Opciones en `slots.yml → model` (altura, hitbox, radio del sonido, desactivar).

## Cajero de fichas en 3D (ModelEngine R4)

![cajero](previews/exchange_machine.png)

`exchange_machine.bbmodel`: mueble verde y dorado con cartel "CAMBIO", pantalla, ranura de
billetes y una bandeja con montones de fichas.

| Animación | Qué hace |
|---|---|
| `idle` | Bombillos del cartel titilando (en bucle) |
| `buy` | La pantalla destella y cae un montón de fichas a la bandeja |
| `sell` | Entra un billete por la ranura y la pantalla destella |

Se instala igual que la tragamonedas (se copia solo a `plugins/ModelEngine/blueprints/gamblingdex/`,
luego `/meg reload` y `/gdx pack`). Opciones en `config.yml → exchange.model`.
Con el pack, el menú de cambio también tiene fondo propio y suena una lluvia de fichas al
comprar y una caja registradora al vender.

![menú de cambio](previews/exchange_gui.png)

> **Todas las máquinas**: las animaciones y los sonidos salen del modelo, así que los ven y oyen
> todos los que estén cerca (no solo el que juega). Para añadir una máquina nueva basta con
> sumarla a `StationModels.Kind`.

## Fichas en 3D (resource pack)

![fichas](previews/chips.png)

Cada ficha (amarilla 1, roja 10, azul 50, verde 100, morada 500, naranja 1.000, rosa 5.000,
negra 10.000, gris 50.000, blanca 100.000) es una ficha de casino fina, con el aro más alto que
el centro (hundido, con el valor impreso) y 8 marcas en relieve. Acostada en el suelo, en la mano
y en el inventario, y de frente en los marcos. Las de 10.000 o más llevan marcas doradas.

Se activa con `resource_pack.custom_chips: true` (usa `custom_model_data` 7701 sobre los tintes;
si ya tienes tu propio modelo en `currency.token.custom-model-data`, se respeta el tuyo).
Las fichas que ya tengan los jugadores se actualizan al entrar o con `/gdx reload`.
Modelo editable: `chip.bbmodel` (Blockbench, formato Java Block/Item).

## Menú con fondo propio y sonidos (resource pack)

![menú](previews/slots_gui.png)

*(vista previa: en el juego los ítems se ven con su textura normal de Minecraft)*

Sonidos nuevos: palanca, tic de rodillos, parada de rodillo, premio, jackpot, sin premio y monedas.
Los que están cerca de la máquina también la oyen.

1. `/gdx pack` → crea `plugins/GamblingDex/GamblingDex-pack.zip` y, si encuentra el pack de
   ModelEngine, `GamblingDex-merged.zip` (los dos juntos: **sube ese**).
2. Sube el zip a una web con enlace directo (p. ej. mc-packs.net) y pon la URL y el SHA-1 en
   `config.yml → resource_pack.send` (o en `server.properties`).
3. Activa `resource_pack.custom_gui` y `resource_pack.custom_sounds`.

> Quien no tenga el pack no ve el modelo 3D y vería cuadros en el título del menú, por eso
> conviene `resource_pack.send.required: true`. Con `custom_gui`/`custom_sounds` en `false`
> todo se ve como siempre.

## Regenerar

```bash
# Pack (fondo del menú, sonidos .ogg, pack.mcmeta) -> src/main/resources/pack/
python3 models/tools/build_pack.py        # requiere numpy, Pillow y ffmpeg con libvorbis
cd models && node tools/render_chips.mjs previews/chips   # capturas + chip.bbmodel

# Modelo: corre models/tools/slot_machine.js dentro de Blockbench (compilado desde su código)
git clone --depth 1 https://github.com/JannisX11/blockbench.git && cd blockbench
npm install --ignore-scripts && npm run build-web && python3 -m http.server 8765 &
cd models && VIEWS='[["front",[-22,24,-50]]]' node tools/render.mjs tools/slot_machine.js slot_machine.bbmodel previews/
VIEWS='[["front",[-22,24,-50]]]' node tools/render.mjs tools/exchange_machine.js exchange_machine.bbmodel previews/
cp slot_machine.bbmodel exchange_machine.bbmodel ../src/main/resources/models/
```

Si cambias el orden de los símbolos en `slot_machine.js`, cambia también `ORDER` en
`StationModels.java`.
