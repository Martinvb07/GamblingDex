// Uso: node render.mjs <builder.js> <salida.bbmodel> <carpeta_capturas>
// Requiere Blockbench web servido en http://localhost:8765 (build local).
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import fs from 'fs';
const [builder, out, shotDir] = process.argv.slice(2);
fs.mkdirSync(shotDir, { recursive: true });
const b = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader'] });
const p = await b.newPage({ viewport: { width: 1400, height: 900 } });
p.on('pageerror', e => console.log('PAGEERR', e.message || e));
await p.goto('http://localhost:8765/index.html');
await p.waitForFunction(() => typeof newProject === 'function' && typeof Formats !== 'undefined', null, { timeout: 60000 });
await p.waitForTimeout(1500);
console.log(await p.evaluate(fs.readFileSync(builder, 'utf8')));
fs.writeFileSync(out, await p.evaluate(() => Codecs.project.compile({ compressed: false })));
await p.evaluate(() => { Group.all.find(g => g.name === 'hitbox').children.forEach(c => { c.visibility = false; }); Canvas.updateVisibility(); });
const vp = await p.evaluateHandle(() => Preview.selected.canvas);
async function shot(name, pos, target = [0, 16, 0], anim = null, t = 0) {
  await p.evaluate(([pos, target, anim, t]) => {
    const pr = Preview.selected;
    if (anim) { const a = Animation.all.find(a => a.name === anim); a.select(); Timeline.setTime(t); Animator.preview(); }
    else if (Animation.selected) { Timeline.setTime(0); Animator.preview(); }
    pr.camera.position.set(...pos); pr.controls.target.set(...target); pr.controls.update(); pr.render();
  }, [pos, target, anim, t]);
  await p.waitForTimeout(300);
  await vp.screenshot({ path: `${shotDir}/${name}.png` });
}
const views = JSON.parse(process.env.VIEWS || '[]');
for (const v of views) await shot(...v);
await b.close();
