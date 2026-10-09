// Construye exchange_machine.bbmodel (cajero de fichas) con la API de Blockbench
// (formato Generic / ModelEngine). Se ejecuta dentro de Blockbench (ver render.mjs).
// Frente del modelo = -Z (north). Animaciones: idle, buy, sell.
(function () {
  newProject(Formats.free);
  Project.name = 'exchange_machine';
  Project.texture_width = 128; Project.texture_height = 64;

  // ---------- Textura 128x64 (pixel art) ----------
  const cv = document.createElement('canvas'); cv.width = 128; cv.height = 64;
  const g = cv.getContext('2d');
  const px = (x, y, c) => { g.fillStyle = c; g.fillRect(x, y, 1, 1); };
  const rect = (x, y, w, h, c) => { g.fillStyle = c; g.fillRect(x, y, w, h); };
  let seed = 11; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const noise = (x0, y0, w, h, cols) => { for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) px(x, y, cols[Math.floor(rnd() * cols.length)]); };
  const art = (x0, y0, rows, pal) => rows.forEach((r, y) => [...r].forEach((ch, x) => { if (pal[ch]) px(x0 + x, y0 + y, pal[ch]); }));

  noise(0, 0, 16, 16, ['#0e5230', '#115a36', '#0c4a2b', '#14633c']);       // R0 cuerpo verde
  noise(16, 0, 16, 16, ['#f2c230', '#e8b420', '#ffd84a', '#d9a514']);      // R1 dorado
  noise(32, 0, 16, 16, ['#14141c', '#1a1a24', '#101018']);                  // R2 negro
  noise(48, 0, 16, 16, ['#c8ccd4', '#b4b8c2', '#dfe2e8', '#a8adb8']);      // R3 cromo
  rect(0, 16, 16, 16, '#d8ffd8'); rect(2, 18, 12, 12, '#5cff7a');          // R4 bombillo verde
  noise(16, 16, 16, 16, ['#5a0f18', '#64121c', '#520c15']);                 // R5 fieltro de la bandeja
  noise(32, 16, 16, 16, ['#2a2a34', '#30303c', '#26262e']);                 // R6 base oscura
  rect(48, 16, 16, 16, '#9dffb0');                                          // R7 brillo de la pantalla

  // Fichas (canto con marcas) 8x8 en (64..128, 0..8)
  const chipCols = ['#c41c28', '#244ec4', '#22903a', '#1e1e22', '#e8be1e', '#6c28a0', '#eeeeec', '#e26e10'];
  const shade = (hex, f) => '#' + [1, 3, 5].map(i => Math.max(0, Math.min(255, Math.round(parseInt(hex.substr(i, 2), 16) * f))).toString(16).padStart(2, '0')).join('');
  chipCols.forEach((c, i) => {
    const x0 = 64 + i * 8, mark = i === 3 || i === 6 ? '#e8b434' : '#ffffff';
    // canto (filas 0-7): color con marcas blancas, bordes oscuros
    rect(x0, 0, 8, 8, c); rect(x0, 0, 8, 1, shade(c, 0.6)); rect(x0, 7, 8, 1, shade(c, 0.6));
    for (let x = 1; x < 8; x += 4) rect(x0 + x, 1, 2, 6, mark);
    // cara (filas 8-15): ficha redonda vista desde arriba
    art(x0, 8, ['.oMooMo.', 'oCCCCCCo', 'MCLLLLCM', 'oCLLLLCo', 'oCLLLLCo', 'MCLLLLCM', 'oCCCCCCo', '.oMooMo.'],
      { o: shade(c, 0.75), C: c, M: mark, L: i === 6 ? '#f6e8c0' : shade(c, 1.35) });
  });

  // Pantalla 40x26 en (64,16): moneda -> fichas
  rect(64, 16, 40, 26, '#06301a'); rect(65, 17, 38, 24, '#0a4a28');
  for (let y = 17; y < 41; y += 2) rect(65, y, 38, 1, '#0c5230'); // lineas de la pantalla
  art(68, 21, [
    '..YYYY..', '.YOOOOY.', 'YO.YY.OY', 'YO.Y..OY', 'YO.YY.OY', 'YO..Y.OY', 'YO.YY.OY', '.YOOOOY.', '..YYYY..',
  ], { Y: '#ffd84a', O: '#d9a514' });                     // moneda con $
  art(79, 24, ['G...', 'GGG.', 'GGGG', 'GGG.', 'G...'], { G: '#5cff7a' }); // flecha
  art(86, 20, [
    '..RRRR..', '.RWRRWR.', 'RRRRRRRR', 'RWR..RWR', 'RRR..RRR', 'RWRRRRWR', '.RRWRRR.', '..RRRR..',
  ], { R: '#e02434', W: '#ffffff' });                     // ficha
  art(73, 34, [                                           // "FICHAS"
    'GGG.GGG.GGG.G.G.GGG.GGG',
    'G....G..G...G.G.G.G.G..',
    'GG...G..G...GGG.GGG.GGG',
    'G....G..G...G.G.G.G...G',
    'G...GGG.GGG.G.G.G.G.GGG',
  ], { G: '#5cff7a' });

  // Cartel "CAMBIO" 30x10 en (0,48)
  rect(0, 48, 30, 10, '#06200f');
  for (let x = 0; x < 30; x += 2) { px(x, 48, '#5cff7a'); px(x + 1, 57, '#5cff7a'); }
  art(3, 50, [
    'YYY.YYY.Y...Y.YY..YYY.YYY',
    'Y...Y.Y.YY.YY.Y.Y..Y..Y.Y',
    'Y...YYY.Y.Y.Y.YY...Y..Y.Y',
    'Y...Y.Y.Y...Y.Y.Y..Y..Y.Y',
    'YYY.Y.Y.Y...Y.YY..YYY.YYY',
  ], { Y: '#ffd030' });
  // Billete 16x8 en (32,48)
  rect(32, 48, 16, 8, '#2f8a3c'); rect(33, 49, 14, 6, '#5fbf5a'); rect(38, 50, 4, 4, '#2f8a3c'); rect(39, 51, 2, 2, '#bff0b0');
  // Botones (48,48)
  rect(48, 48, 8, 8, '#a01010'); rect(49, 49, 6, 6, '#e83030');
  rect(56, 48, 8, 8, '#e0b010'); rect(57, 49, 6, 6, '#ffe040');
  rect(64, 48, 8, 8, '#1e7a2e'); rect(65, 49, 6, 6, '#2fb046');

  const tex = new Texture({ name: 'exchange_machine.png' }).fromDataURL(cv.toDataURL()).add(false);

  // ---------- Helpers ----------
  const R = { body: [0, 0], gold: [16, 0], dark: [32, 0], chrome: [48, 0], bulb: [0, 16], felt: [16, 16], base: [32, 16], glow: [48, 16] };
  const uvOf = (k) => { const r = R[k]; return [r[0] + 1, r[1] + 1, r[0] + 15, r[1] + 15]; };
  const chipUV = (i) => [64 + i * 8, 0, 64 + i * 8 + 8, 8];
  const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
  function cube(name, from, to, parent, mat, over = {}) {
    const faces = {};
    FACES.forEach(f => { const m = over[f] || mat; faces[f] = { uv: Array.isArray(m) ? m : uvOf(m), texture: tex.uuid }; });
    const c = new Cube({ name, from, to, faces });
    c.addTo(parent).init();
    return c;
  }
  function group(name, origin, parent) { const gr = new Group({ name, origin }); gr.addTo(parent || undefined).init(); return gr; }
  // Ficha: 2 cubos en cruz (silueta redondeada) de 2.2 de diametro; arriba la cara de ficha
  // (la textura se mapea igual en los dos cubos), al costado el canto con marcas.
  const CH = 0.45, D = 1.1, d = 0.65;
  const faceUV = (ci, fx1, fz1, fx2, fz2) => [64 + ci * 8 + fx1 * 8, 8 + fz1 * 8, 64 + ci * 8 + fx2 * 8, 8 + fz2 * 8];
  const chipCube = (name, x, z, y, ci, parent, rot = 0) => {
    const side = [64 + ci * 8, 0, 64 + ci * 8 + 8, 8];
    const t = (D - d) / (2 * D), u = 1 - t;
    [[D, d, faceUV(ci, 0, t, 1, u)], [d, D, faceUV(ci, t, 0, u, 1)]].forEach(([hx, hz, top], k) => {
      const c = cube(name + (k ? 'b' : 'a'), [x - hx, y, z - hz], [x + hx, y + CH, z + hz], parent, side, { up: top, down: top });
      if (rot) { c.rotation = [0, rot, 0]; c.origin = [x, y, z]; }
    });
  };

  // ---------- Modelo ----------
  const body = group('body', [0, 0, 0]);
  cube('base', [-7.5, 0, -5.5], [7.5, 2, 5.5], body, 'base');
  cube('lower', [-7, 2, -5], [7, 6, 5], body, 'body');
  cube('tray_left', [-7, 6, -5], [-5, 10, 5], body, 'body');
  cube('tray_right', [5, 6, -5], [7, 10, 5], body, 'body');
  cube('tray_back', [-5, 6, -1], [5, 10, 5], body, 'dark');
  cube('tray_floor', [-5, 6, -5.3], [5, 6.5, -1], body, 'felt', { north: 'gold' });
  cube('tray_roof', [-7, 10, -5.2], [7, 11, 5], body, 'gold');
  cube('upper', [-7, 11, -5], [7, 26, 5], body, 'body');
  cube('edge_l', [-7.25, 2, -5.25], [-6.75, 26, -4.75], body, 'gold');
  cube('edge_r', [6.75, 2, -5.25], [7.25, 26, -4.75], body, 'gold');
  cube('screen_frame', [-5.6, 15, -5.4], [5.6, 23.4, -5], body, 'dark');
  cube('screen', [-4.9, 15.6, -5.5], [4.9, 22.8, -5.4], body, 'dark', { north: [64, 16, 104, 42] });
  cube('slot_frame', [-3.2, 12.2, -5.3], [3.2, 13.8, -5], body, 'gold');
  cube('slot', [-2.5, 12.75, -5.35], [2.5, 13.25, -5.3], body, 'dark');
  cube('btn_1', [-5.2, 11.4, -5.6], [-3.6, 12.2, -5], body, [48, 48, 56, 56]);
  cube('btn_2', [3.6, 11.4, -5.6], [5.2, 12.2, -5], body, [64, 48, 72, 56]);
  cube('side_logo_l', [-7.1, 15, -2], [-7, 23, 2], body, 'gold', { west: [56, 48, 64, 56] });
  cube('side_logo_r', [7, 15, -2], [7.1, 23, 2], body, 'gold', { east: [56, 48, 64, 56] });

  // Fichas de adorno en la bandeja
  const tray = group('tray_chips', [0, 6.5, -3], body);
  [[-3.6, -2.2, 6, 0], [-1.5, -1.9, 4, 2], [2.7, -1.9, 7, 3], [3.5, -4.1, 2, 4], [-3.4, -4.3, 1, 5]].forEach(([x, z, n, ci], i) => {
    for (let k = 0; k < n; k++) chipCube('stack' + i + '_' + k, x, z, 6.5 + k * CH, (ci + (k % 2 ? 0 : 0)) % 8, tray, k % 2 ? 22.5 : 0);
  });

  // Fichas que caen al comprar: en reposo estan escondidas dentro del mueble
  const drop = group('drop', [0.4, 12, -3.6], body);
  [0, 1, 2, 3].forEach(k => chipCube('drop_' + k, 0.4, -3.6, 11.4 + k * CH, [1, 1, 1, 1][k], drop, k % 2 ? 22.5 : 0));

  // Billete que entra al vender: en reposo esta dentro del mueble
  const bill = group('bill', [0, 13, -2], body);
  cube('bill', [-2, 12.85, -4.6], [2, 13.15, -0.6], bill, 'body', { up: [32, 48, 48, 56], down: [32, 48, 48, 56] });

  // Brillo de la pantalla: en reposo detras de la pantalla
  const glow = group('screen_glow', [0, 19, -5.45], body);
  cube('glow', [-4.9, 15.6, -5.4], [4.9, 22.8, -5.38], glow, 'glow');

  const sign = group('sign', [0, 26, 0]);
  cube('crown', [-7.5, 26, -5.5], [7.5, 31, 5.5], sign, 'body', { north: [0, 48, 30, 58], south: [0, 48, 30, 58] });
  cube('crown_trim', [-7.7, 25.6, -5.7], [7.7, 26.4, 5.7], sign, 'gold');
  cube('crown_top', [-7.7, 31, -5.7], [7.7, 31.8, 5.7], sign, 'gold');
  const la = group('lights_a', [0, 32, 0], sign), lb = group('lights_b', [0, 32, 0], sign);
  [-6, -3, 0, 3, 6].forEach((x, i) => cube('bulb_' + i, [x - 0.6, 31.8, -0.6], [x + 0.6, 33, 0.6], i % 2 ? lb : la, 'bulb'));

  const hitbox = group('hitbox', [0, 0, 0]);
  cube('hitbox', [-8, 0, -8], [8, 32, 8], hitbox, 'dark');

  // ---------- Animaciones ----------
  const kf = (anim, grp, channel, list, interp = 'linear') => {
    const a = anim.getBoneAnimator(grp);
    list.forEach(([t, x, y, z]) => a.addKeyframe({ channel, time: t, interpolation: interp, data_points: [{ x, y, z }] }));
  };
  const blink = (len, step, on) => { const o = []; for (let t = 0; t <= len + 0.0001; t += step) o.push([+t.toFixed(2), ...(Math.round(t / step) % 2 ? [on, on, on] : [1, 1, 1])]); return o; };
  const invert = (list, on) => list.map(([t, x]) => [t, ...(x > 1 ? [1, 1, 1] : [on, on, on])]);

  const idle = new Blockbench.Animation({ name: 'idle', loop: 'loop', length: 1.2 }).add();
  kf(idle, la, 'scale', [[0, 1, 1, 1], [0.6, 1.25, 1.25, 1.25], [1.2, 1, 1, 1]], 'catmullrom');
  kf(idle, lb, 'scale', [[0, 1.25, 1.25, 1.25], [0.6, 1, 1, 1], [1.2, 1.25, 1.25, 1.25]], 'catmullrom');

  // buy: la pantalla brilla y caen fichas a la bandeja (luego "las coges" y desaparecen)
  const buy = new Blockbench.Animation({ name: 'buy', loop: 'once', length: 2 }).add();
  kf(buy, glow, 'position', [[0, 0, 0, -0.12], [0.15, 0, 0, -0.12], [0.16, 0, 0, 0], [0.3, 0, 0, 0], [0.31, 0, 0, -0.12], [0.45, 0, 0, -0.12], [0.46, 0, 0, 0]], 'step');
  kf(buy, drop, 'position', [[0, 0, 0, 0], [0.3, 0, 0, 0], [0.75, 0, -4.9, 0], [0.85, 0, -4.6, 0], [0.95, 0, -4.9, 0], [1.6, 0, -4.9, 0]]);
  kf(buy, drop, 'scale', [[0, 1, 1, 1], [1.6, 1, 1, 1], [1.9, 0.01, 0.01, 0.01]]);
  const bl = blink(2, 0.1, 1.4);
  kf(buy, la, 'scale', bl, 'step'); kf(buy, lb, 'scale', invert(bl, 1.4), 'step');

  // sell: un billete sale de la ranura... no: entra por la ranura, y la pantalla brilla
  const sell = new Blockbench.Animation({ name: 'sell', loop: 'once', length: 2 }).add();
  kf(sell, bill, 'position', [[0, 0, 0, -4.2], [0.25, 0, 0, -4.2], [1.0, 0, 0, 0]], 'catmullrom');
  kf(sell, glow, 'position', [[0, 0, 0, 0], [1.0, 0, 0, 0], [1.01, 0, 0, -0.12], [1.2, 0, 0, -0.12], [1.21, 0, 0, 0], [1.35, 0, 0, 0], [1.36, 0, 0, -0.12], [1.6, 0, 0, -0.12], [1.61, 0, 0, 0]], 'step');
  kf(sell, la, 'scale', bl, 'step'); kf(sell, lb, 'scale', invert(bl, 1.4), 'step');

  Canvas.updateAll();
  return 'ok';
})();
