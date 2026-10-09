// Carga assets/gamblingdex/models/item/chip.json en Blockbench con cada textura,
// saca una captura de cada ficha y guarda chip.bbmodel (para editarla en Blockbench).
// Uso: node tools/render_chips.mjs <carpeta_capturas>   (Blockbench web en :8765)
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import fs from 'fs';
const out = process.argv[2];
const PACK = '../src/main/resources/pack/assets/gamblingdex';
const model = JSON.parse(fs.readFileSync(PACK + '/models/item/chip.json', 'utf8'));
const names = ['yellow', 'red', 'blue', 'green', 'purple', 'orange', 'pink', 'black', 'gray', 'white'];
const tex = Object.fromEntries(names.map(n => [n, 'data:image/png;base64,' +
  fs.readFileSync(`${PACK}/textures/item/chip_${n}.png`).toString('base64')]));
fs.mkdirSync(out, { recursive: true });
const b = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader'] });
const p = await b.newPage({ viewport: { width: 1400, height: 900 } });
await p.goto('http://localhost:8765/index.html');
await p.waitForFunction(() => typeof newProject === 'function', null, { timeout: 60000 });
await p.waitForTimeout(1500);
await p.evaluate(([model, data]) => {
  newProject(Formats.java_block);
  Codecs.java_block.parse(JSON.parse(JSON.stringify(model)), '');
  Texture.all.slice().forEach(t => t.remove(true));
  const t = new Texture({ name: 'chip_red' }).fromDataURL(data).add(false);
  Cube.all.forEach(c => Object.values(c.faces).forEach(f => { if (f.texture !== null && f.texture !== undefined) f.texture = t.uuid; }));
  Canvas.updateAll();
  Project.name = 'chip';
}, [model, tex.red]);
fs.writeFileSync('chip.bbmodel', await p.evaluate(() => Codecs.project.compile({ compressed: false })));
const vp = await p.evaluateHandle(() => Preview.selected.canvas);
for (const n of names) {
  await p.evaluate((data) => {
    Texture.all[0].fromDataURL(data); Canvas.updateAll();
    const pr = Preview.selected; pr.camera.position.set(-10, 14, -30); pr.controls.target.set(0, 8, 0); pr.controls.update(); pr.render();
  }, tex[n]);
  await p.waitForTimeout(400);
  await vp.screenshot({ path: `${out}/chip_${n}.png` });
}
await b.close();
