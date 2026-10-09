// Carga assets/gamblingdex/models/item/chip.json en Blockbench, saca capturas de las
// fichas (3D y en cada sitio: inventario, mano, suelo, marco) y guarda chip.bbmodel.
// Uso: node tools/render_chips.mjs <carpeta_capturas>   (Blockbench web en :8765)
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import fs from 'fs';
const out = process.argv[2];
const PACK = '../src/main/resources/pack/assets/gamblingdex';
const model = JSON.parse(fs.readFileSync(PACK + '/models/item/chip.json', 'utf8'));
Object.assign(model.display, JSON.parse(process.env.DISPLAY_OVERRIDE || '{}')); // para probar ajustes
const names = ['yellow', 'red', 'blue', 'green', 'purple', 'orange', 'pink', 'black', 'gray', 'white'];
const tex = Object.fromEntries(names.map(n => [n, 'data:image/png;base64,' +
  fs.readFileSync(`${PACK}/textures/item/chip_${n}.png`).toString('base64')]));
fs.mkdirSync(out, { recursive: true });
const b = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader'] });
const p = await b.newPage({ viewport: { width: 1400, height: 900 } });
p.on('pageerror', e => console.log('PAGEERR', e.message || e));
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
    const pr = Preview.selected; pr.camera.position.set(5, 15, 14); pr.controls.target.set(0, 0.6, 0); pr.controls.update(); pr.render();
  }, tex[n]);
  await p.waitForTimeout(400);
  await vp.screenshot({ path: `${out}/chip_${n}.png` });
}
// Vistas de cada sitio con el modo Display de Blockbench
await p.evaluate((data) => { Texture.all[0].fromDataURL(data); Canvas.updateAll(); Modes.options.display.select(); }, tex.red);
await p.waitForTimeout(800);
for (const [slot, fn] of [['gui', 'loadGUI'], ['ground', 'loadGround'], ['firstperson', 'loadFirstRight'], ['thirdperson', 'loadThirdRight'], ['fixed', 'loadFixed']]) {
  const ok = await p.evaluate((fn) => { if (typeof DisplayMode[fn] !== 'function') return Object.keys(DisplayMode).filter(k => k.startsWith('load')).join(','); DisplayMode[fn](); return 'ok'; }, fn);
  if (ok !== 'ok') { console.log('no', fn, ok); continue; }
  await p.waitForTimeout(700);
  await p.screenshot({ path: `${out}/display_${slot}.png` });
}
await b.close();
