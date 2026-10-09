# Modelos 3D (ModelEngine)

Modelos `.bbmodel` para usar con **ModelEngine R4**. Se abren y editan en Blockbench.

| Modelo | Animaciones |
|---|---|
| `slot_machine.bbmodel` | `idle` (luces, en bucle), `spin` (palanca + 3 rodillos que paran escalonados, 3 s), `win` (luces parpadean y el cartel rebota) |

![slot_machine](previews/slot_machine.png)

Los símbolos de los rodillos son los mismos ítems del módulo de slots
(diamante, esmeralda, lingote de oro, lingote de hierro, amatista y estrella del Nether).

## Instalar en el servidor

1. Copia el `.bbmodel` a `plugins/ModelEngine/blueprints/`.
2. Ejecuta `/meg reload` (genera el resource pack).
3. Prueba: `/meg summon slot_machine` y `/meg animation ...` o desde el menú de ModelEngine.

El hueso `hitbox` define el hitbox del modelo (1x2 bloques); ModelEngine no lo dibuja.

## Regenerar el modelo

El modelo se construye con un script (`tools/slot_machine.js`) que corre dentro de Blockbench:

```bash
# Blockbench web compilado desde su código fuente y servido en :8765
git clone --depth 1 https://github.com/JannisX11/blockbench.git && cd blockbench
npm install --ignore-scripts && npm run build-web && python3 -m http.server 8765 &
# Construir + exportar + capturas
cd models && VIEWS='[["front",[-22,24,-50]]]' node tools/render.mjs tools/slot_machine.js slot_machine.bbmodel previews/
```
