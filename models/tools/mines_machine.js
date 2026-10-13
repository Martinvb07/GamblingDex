// Construye mines_machine.bbmodel (máquina de Mines) con la API de Blockbench
// (formato Generic / ModelEngine). Se ejecuta dentro de Blockbench (ver render.mjs).
// Frente del modelo = -Z (north). Animaciones: idle, start, boom, cashout,
// tile_N_gem y tile_N_bomb (N = 0..24, fila*5 + columna, la fila 0 arriba y la columna 0
// a la izquierda de quien mira la máquina: igual que el tablero del menú).
(function () {
  newProject(Formats.free);
  Project.name = 'mines_machine';
  Project.texture_width = 128; Project.texture_height = 64;

  // ---------- Textura 128x64 (pixel art) ----------
  const cv = document.createElement('canvas'); cv.width = 128; cv.height = 64;
  const g = cv.getContext('2d');
  const px = (x, y, c) => { g.fillStyle = c; g.fillRect(x, y, 1, 1); };
  const rect = (x, y, w, h, c) => { g.fillStyle = c; g.fillRect(x, y, w, h); };
  let seed = 23; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const noise = (x0, y0, w, h, cols) => { for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) px(x, y, cols[Math.floor(rnd() * cols.length)]); };
  const art = (x0, y0, rows, pal) => rows.forEach((r, y) => [...r].forEach((ch, x) => { if (pal[ch]) px(x0 + x, y0 + y, pal[ch]); }));

  noise(0, 0, 16, 16, ['#1c1c26', '#20202c', '#181822', '#242430']);       // R0 cuerpo negro
  noise(16, 0, 16, 16, ['#c41c28', '#b81824', '#d42430', '#ac1420']);      // R1 rojo
  noise(32, 0, 16, 16, ['#0c0c12', '#101018', '#0a0a0e']);                  // R2 interior
  noise(48, 0, 16, 16, ['#c8ccd4', '#b4b8c2', '#dfe2e8', '#a8adb8']);      // R3 cromo
  rect(0, 16, 16, 16, '#fff0e0'); rect(2, 18, 12, 12, '#ff5a4a');          // R4 bombillo rojo
  noise(16, 16, 16, 16, ['#5a0f18', '#64121c', '#520c15']);                 // R5 fieltro de la bandeja
  noise(32, 16, 16, 16, ['#2a2a34', '#30303c', '#26262e']);                 // R6 base oscura
  noise(48, 16, 16, 16, ['#0d1626', '#0f1a2c', '#0b1322']);                 // R7 fondo de la pantalla

  // Casilla tapada 16x16 en (64,0): pizarra azulada con relieve
  rect(64, 0, 16, 16, '#3a4660'); rect(65, 1, 14, 14, '#4c5a78');
  rect(65, 1, 14, 1, '#7084a8'); rect(65, 1, 1, 14, '#64789c');
  rect(65, 14, 14, 1, '#2c3548'); rect(78, 1, 1, 14, '#2c3548');
  art(70, 5, ['..##..', '.#..#.', '....#.', '...#..', '......', '...#..'], { '#': '#8496ba' }); // ?

  // Gema 16x16 en (80,0): casilla verde oscura con una esmeralda
  rect(80, 0, 16, 16, '#0c3a22'); rect(81, 1, 14, 14, '#10502e');
  art(81, 1, [
    '..............', '.....gggg.....', '....gGGGGg....', '...gGLLGGGg...', '..gGLLGGGGGg..', '.gGGGGGGGGGGg.',
    '.gDGGGGGGGGDg.', '..gDGGGGGGDg..', '...gDGGGGDg...', '....gDGGDg....', '.....gDDg.....', '......gg......',
    '..............', '..............',
  ], { g: '#0b6b2a', G: '#2fd16a', L: '#d8ffe8', D: '#1a9a48' });

  // Bomba 16x16 en (96,0): casilla roja con una bomba y su mecha
  rect(96, 0, 16, 16, '#5a0a10'); rect(97, 1, 14, 14, '#8a121c');
  art(97, 1, [
    '.........yY...', '........Yo....', '.......o......', '......o.......', '....kkkk......', '...kKKKKk.....',
    '..kKWKKKKk....', '..kKKKKKKk....', '..kKKKKKKk....', '..kKKKKKKk....', '...kKKKKk.....', '....kkkk......',
    '..............', '..............',
  ], { k: '#050508', K: '#22222c', W: '#9a9aae', o: '#b08040', y: '#ffe040', Y: '#ff8020' });

  // Diana (como el bloque de la estación) 16x16 en (112,0)
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const d = Math.max(Math.abs(x - 7.5), Math.abs(y - 7.5));
    px(112 + x, y, Math.floor((d + 0.5) / 2) % 2 === 0 ? '#c41c28' : '#efe6d8');
  }

  rect(64, 16, 8, 8, '#4cff7a');   // destello verde (cobrar)
  rect(72, 16, 8, 8, '#ff3030');   // destello rojo (boom)
  // Moneda: cara (80,16) y canto (88,16)
  art(80, 16, ['.oYYYYo.', 'oYWYYYYo', 'YWYOOYYY', 'YYOYYOYY', 'YYOYYOYY', 'YYYOOYYY', 'oYYYYYYo', '.oYYYYo.'],
    { o: '#9a6a00', Y: '#ffd83d', W: '#fff4b0', O: '#d9a514' });
  rect(88, 16, 8, 8, '#d9a514'); for (let x = 88; x < 96; x += 2) rect(x, 17, 1, 6, '#9a6a00');
  rect(96, 16, 8, 8, '#801010'); rect(97, 17, 6, 6, '#e83030');    // botón rojo
  rect(104, 16, 8, 8, '#1e7a2e'); rect(105, 17, 6, 6, '#3fd060');  // botón verde
  rect(112, 16, 8, 8, '#a07a08'); rect(113, 17, 6, 6, '#ffd040');  // botón dorado
  // Tecla grande de cobrar 16x8 en (64,24)
  rect(64, 24, 16, 8, '#145a22'); rect(65, 25, 14, 6, '#2fb046'); rect(65, 25, 14, 1, '#8cf0a0');
  art(70, 26, ['.##.', '##..', '.##.', '..##', '.##.'], { '#': '#e8ffe8' }); // $

  // Cartel "MINES" 30x10 en (0,48): letras rojas sobre negro con bombillos
  rect(0, 48, 30, 10, '#0a0a10');
  for (let x = 0; x < 30; x += 2) { px(x, 48, '#ff5a4a'); px(x + 1, 57, '#ff5a4a'); }
  const MINES = [
    ['R...R', 'RRR', 'R..R', 'RRRR', '.RRR'], ['RR.RR', '.R.', 'RR.R', 'R...', 'R...'], ['R.R.R', '.R.', 'R.RR', 'RRR.', '.RR.'],
    ['R...R', '.R.', 'R..R', 'R...', '...R'], ['R...R', 'RRR', 'R..R', 'RRRR', 'RRR.'],
  ].map(r => r.join('.'));
  art(3, 50, MINES, { R: '#ff3a3a' });
  // Rejilla de la pantalla 16x16 en (32,48): lineas finas
  rect(32, 48, 16, 16, '#0d1626');
  for (let i = 0; i < 16; i += 4) { rect(32 + i, 48, 1, 16, '#14223a'); rect(32, 48 + i, 16, 1, '#14223a'); }

  const tex = new Texture({ name: 'mines_machine.png' }).fromDataURL(cv.toDataURL()).add(false);

  // ---------- Helpers ----------
  const R = { body: [0, 0], red: [16, 0], dark: [32, 0], chrome: [48, 0], bulb: [0, 16], felt: [16, 16], base: [32, 16], screen: [48, 16] };
  const uvOf = (k) => { const r = R[k]; return [r[0] + 1, r[1] + 1, r[0] + 15, r[1] + 15]; };
  const T = {
    cover: [64, 0, 80, 16], gem: [80, 0, 96, 16], bomb: [96, 0, 112, 16], target: [112, 0, 128, 16],
    green: [65, 17, 71, 23], redglow: [73, 17, 79, 23], coin: [80, 16, 88, 24], coinEdge: [88, 16, 96, 24],
    btnRed: [96, 16, 104, 24], btnGreen: [104, 16, 112, 24], btnGold: [112, 16, 120, 24], cashKey: [64, 24, 80, 32],
    sign: [0, 48, 30, 58], grid: [32, 48, 48, 64],
  };
  const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
  function cube(name, from, to, parent, mat, over = {}) {
    const faces = {};
    FACES.forEach(f => {
      const m = over[f] || mat;
      faces[f] = { uv: Array.isArray(m) ? m : (T[m] || uvOf(m)), texture: tex.uuid };
    });
    const c = new Cube({ name, from, to, faces });
    c.addTo(parent).init();
    return c;
  }
  function group(name, origin, parent) { const gr = new Group({ name, origin }); gr.addTo(parent || undefined).init(); return gr; }
  // Moneda: 2 cubos en cruz (silueta redondeada) acostada
  const coinCube = (name, x, z, y, parent, rot = 0) => {
    const D = 1.0, d = 0.6, H = 0.4;
    [[D, d], [d, D]].forEach(([hx, hz], k) => {
      const c = cube(name + (k ? 'b' : 'a'), [x - hx, y, z - hz], [x + hx, y + H, z + hz], parent, 'coinEdge', { up: 'coin', down: 'coin' });
      if (rot) { c.rotation = [0, rot, 0]; c.origin = [x, y, z]; }
    });
  };

  // ---------- Mueble ----------
  const body = group('body', [0, 0, 0]);
  cube('base', [-7.5, 0, -5.5], [7.5, 2, 5.5], body, 'base');
  cube('lower', [-7, 2, -5], [7, 3.5, 5], body, 'body');
  // Bandeja de monedas
  cube('tray_left', [-7, 3.5, -5], [-5, 7.5, 5], body, 'body');
  cube('tray_right', [5, 3.5, -5], [7, 7.5, 5], body, 'body');
  cube('tray_back', [-5, 3.5, -1], [5, 7.5, 5], body, 'dark');
  cube('tray_floor', [-5, 3.5, -5.3], [5, 4, -1], body, 'felt', { north: 'red' });
  cube('tray_roof', [-7, 7.5, -5.2], [7, 8.5, 5], body, 'red');
  // Pupitre con los botones
  cube('desk', [-7, 8.5, -5], [7, 11, 5], body, 'body');
  cube('desk_shelf', [-7.25, 10.5, -6.8], [7.25, 11.5, -4.5], body, 'body', { up: 'body', north: 'red' });
  cube('btn_minus', [4.8, 11.5, -6.3], [6.2, 12, -5], body, 'btnRed');
  cube('btn_plus', [2.8, 11.5, -6.3], [4.2, 12, -5], body, 'btnGold');
  cube('btn_cash', [-6.2, 11.5, -6.4], [-0.6, 12.1, -4.9], body, 'btnGreen', { up: 'cashKey' });
  // Cabina de arriba con la pantalla
  cube('upper', [-7.25, 11.5, -4.5], [7.25, 27, 5], body, 'body');
  cube('edge_l', [-7.5, 2, -5.2], [-7, 27, -4.7], body, 'red');
  cube('edge_r', [7, 2, -5.2], [7.5, 27, -4.7], body, 'red');
  cube('side_logo_l', [-7.35, 15, -2.5], [-7.25, 22, 2.5], body, 'red', { west: 'target' });
  cube('side_logo_r', [7.25, 15, -2.5], [7.35, 22, 2.5], body, 'red', { east: 'target' });
  cube('side_stripe_l', [-7.35, 2, 3.5], [-7.25, 27, 4.2], body, 'red');
  cube('side_stripe_r', [7.25, 2, 3.5], [7.35, 27, 4.2], body, 'red');

  const screen = group('screen', [0, 19.4, -5], body);
  cube('screen_frame', [-6.6, 12.5, -4.9], [6.6, 26.3, -4.5], screen, 'red');
  cube('screen_bg', [-6.1, 13, -5], [6.1, 25.8, -4.9], screen, 'screen', { north: 'grid' });
  // Destellos (en reposo escondidos dentro del fondo; se ven por las rendijas entre casillas)
  const flashG = group('flash_green', [0, 19.4, -5], screen);
  cube('flash_g', [-6.1, 13, -4.97], [6.1, 25.8, -4.92], flashG, 'green');
  const flashR = group('flash_red', [0, 19.4, -5], screen);
  cube('flash_r', [-6.1, 13, -4.97], [6.1, 25.8, -4.92], flashR, 'redglow');

  // Tablero 5x5: cada casilla es un grupo con la tapa, la gema y la bomba (las dos últimas
  // escondidas detrás de la tapa hasta que se abre la casilla).
  const PITCH = 2.4, HALF = 1.0, CY = 19.4;
  const tiles = [];
  for (let r = 0; r < 5; r++) for (let c = 0; c < 5; c++) {
    const n = r * 5 + c, x = (2 - c) * PITCH, y = CY + (2 - r) * PITCH; // columna 0 = izquierda de quien mira (+X)
    const tg = group('tile_' + n, [x, y, -5], screen);
    const cover = group('cover_' + n, [x, y, -5], tg);
    cube('cover_' + n, [x - HALF, y - HALF, -5.35], [x + HALF, y + HALF, -5.0], cover, 'base', { north: 'cover' });
    const gem = group('gem_' + n, [x, y, -5], tg);
    cube('gem_' + n, [x - HALF, y - HALF, -4.98], [x + HALF, y + HALF, -4.93], gem, 'dark', { north: 'gem' });
    const bomb = group('bomb_' + n, [x, y, -5], tg);
    cube('bomb_' + n, [x - HALF, y - HALF, -4.98], [x + HALF, y + HALF, -4.93], bomb, 'dark', { north: 'bomb' });
    tiles.push({ n, r, c, cover, gem, bomb });
  }

  // Monedas que caen al cobrar: en reposo están escondidas dentro del pupitre
  const coins = group('coins', [0, 9, -3.4], body);
  [[-1.6, -3.0, 0], [0.4, -3.6, 25], [2.2, -2.8, 50], [-0.4, -2.4, 70], [1.2, -3.2, 15]].forEach(([x, z, rot], k) =>
    coinCube('coin_' + k, x, z, 9 + k * 0.42, coins, rot));

  // Cartel
  const sign = group('sign', [0, 27, 0]);
  cube('crown', [-7.5, 27, -5.5], [7.5, 32, 5.5], sign, 'body', { north: 'sign', south: 'sign' });
  cube('crown_trim', [-7.7, 26.6, -5.7], [7.7, 27.4, 5.7], sign, 'red');
  cube('crown_top', [-7.7, 32, -5.7], [7.7, 32.8, 5.7], sign, 'red');
  const la = group('lights_a', [0, 33, 0], sign), lb = group('lights_b', [0, 33, 0], sign);
  [-6, -3, 0, 3, 6].forEach((x, i) => cube('bulb_top_' + i, [x - 0.6, 32.8, -0.6], [x + 0.6, 34, 0.6], i % 2 ? lb : la, 'bulb'));
  // Bombillos a los lados de la pantalla
  for (let i = 0; i < 6; i++) {
    const y = 13.6 + i * 2.3;
    cube('bulb_l_' + i, [6.6, y - 0.35, -5.3], [7.2, y + 0.35, -4.7], i % 2 ? la : lb, 'bulb');
    cube('bulb_r_' + i, [-7.2, y - 0.35, -5.3], [-6.6, y + 0.35, -4.7], i % 2 ? lb : la, 'bulb');
  }

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

  // start: ola por las casillas (en diagonal) y luces rápidas
  const start = new Blockbench.Animation({ name: 'start', loop: 'once', length: 1.2 }).add();
  tiles.forEach(t => {
    const d = (t.r + t.c) * 0.06;
    kf(start, t.cover, 'scale', [[0, 1, 1, 1], [d, 1, 1, 1], [d + 0.1, 1.2, 1.2, 1], [d + 0.22, 1, 1, 1]]);
  });
  const bs = blink(1.2, 0.1, 1.4);
  kf(start, la, 'scale', bs, 'step'); kf(start, lb, 'scale', invert(bs, 1.4), 'step');

  // tile_N_gem / tile_N_bomb: la tapa se da vuelta y aparece lo que había debajo (se queda así)
  tiles.forEach(t => {
    [['gem', t.gem], ['bomb', t.bomb]].forEach(([kind, grp]) => {
      const an = new Blockbench.Animation({ name: 'tile_' + t.n + '_' + kind, loop: 'hold', length: 0.45 }).add();
      kf(an, t.cover, 'scale', [[0, 1, 1, 1], [0.12, 0.01, 1, 1], [0.13, 0.01, 0.01, 0.01], [0.45, 0.01, 0.01, 0.01]]);
      kf(an, grp, 'position', [[0, 0, 0, 0], [0.12, 0, 0, -0.5], [0.45, 0, 0, -0.5]], 'step');
      const pop = kind === 'bomb' ? 1.4 : 1.2;
      kf(an, grp, 'scale', [[0, 0.01, 1, 1], [0.12, 0.01, 1, 1], [0.26, pop, pop, 1], [0.38, 1, 1, 1], [0.45, 1, 1, 1]]);
    });
  });

  // boom: la máquina tiembla y la pantalla destella en rojo
  const boom = new Blockbench.Animation({ name: 'boom', loop: 'once', length: 1.6 }).add();
  kf(boom, body, 'position', [[0, 0, 0, 0], [0.05, 0.6, 0, 0], [0.1, -0.6, 0.2, 0], [0.15, 0.5, 0, 0], [0.2, -0.4, 0.1, 0],
    [0.25, 0.3, 0, 0], [0.3, -0.2, 0, 0], [0.35, 0.1, 0, 0], [0.4, 0, 0, 0]]);
  const fr = []; for (let i = 0; i <= 14; i++) fr.push([+(i * 0.1).toFixed(2), 0, 0, i % 2 === 0 && i < 13 ? -0.15 : 0]);
  kf(boom, flashR, 'position', fr, 'step');
  const bb = blink(1.6, 0.2, 0.6);
  kf(boom, la, 'scale', bb, 'step'); kf(boom, lb, 'scale', bb, 'step');

  // cashout: caen monedas a la bandeja, destello verde y luces a tope
  const cash = new Blockbench.Animation({ name: 'cashout', loop: 'once', length: 2.6 }).add();
  kf(cash, coins, 'position', [[0, 0, 0, 0], [0.15, 0, 0, 0], [0.55, 0, -5, 0], [0.65, 0, -4.7, 0], [0.75, 0, -5, 0], [2.2, 0, -5, 0]]);
  kf(cash, coins, 'scale', [[0, 1, 1, 1], [2.2, 1, 1, 1], [2.5, 0.01, 0.01, 0.01]]);
  const fg = []; for (let i = 0; i <= 10; i++) fg.push([+(i * 0.12).toFixed(2), 0, 0, i % 2 === 0 && i < 9 ? -0.15 : 0]);
  kf(cash, flashG, 'position', fg, 'step');
  const bc = blink(2.6, 0.08, 1.45);
  kf(cash, la, 'scale', bc, 'step'); kf(cash, lb, 'scale', invert(bc, 1.45), 'step');

  Canvas.updateAll();
  return 'ok';
})();
