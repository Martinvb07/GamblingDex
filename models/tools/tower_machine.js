// Construye tower_machine.bbmodel (máquina de Tower) con la API de Blockbench
// (formato Generic / ModelEngine). Se ejecuta dentro de Blockbench (ver render.mjs).
// Frente del modelo = -Z (north).
//
// Torre de castillo con almenas y bandera. En la pantalla, 8 pisos (0 = abajo) con sus puertas y,
// a la izquierda de quien mira, el riel por el que sube la ficha del jugador.
// Animaciones: idle, start, floor_K_ok / floor_K_trap (el piso K se abre en verde o en rojo, y se
// queda así), climb_K (la ficha sube del piso K-1 al K; K = 8 = arriba del todo), fall_K (la ficha
// se cae desde el piso K), boom, cashout y top (estrella sobre la torre).
(function () {
  newProject(Formats.free);
  Project.name = 'tower_machine';
  Project.texture_width = 128; Project.texture_height = 64;

  // ---------- Textura 128x64 (pixel art) ----------
  const cv = document.createElement('canvas'); cv.width = 128; cv.height = 64;
  const g = cv.getContext('2d');
  const px = (x, y, c) => { g.fillStyle = c; g.fillRect(x, y, 1, 1); };
  const rect = (x, y, w, h, c) => { g.fillStyle = c; g.fillRect(x, y, w, h); };
  let seed = 53; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const noise = (x0, y0, w, h, cols) => { for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) px(x, y, cols[Math.floor(rnd() * cols.length)]); };
  const art = (x0, y0, rows, pal) => rows.forEach((r, y) => [...r].forEach((ch, x) => { if (pal[ch]) px(x0 + x, y0 + y, pal[ch]); }));

  // R0 piedra de la torre (ladrillos)
  noise(0, 0, 16, 16, ['#4a5468', '#525c72', '#465064', '#56607a']);
  for (let y = 0; y < 16; y += 4) { rect(0, y + 3, 16, 1, '#323a4a'); for (let x = (y / 4) % 2 ? 4 : 0; x < 16; x += 8) rect(x, y, 1, 3, '#323a4a'); }
  noise(16, 0, 16, 16, ['#2ec8e0', '#22b8d0', '#40d8f0', '#1aa8c0']);      // R1 cian
  noise(32, 0, 16, 16, ['#0c0e16', '#10121c', '#0a0c12']);                  // R2 interior
  noise(48, 0, 16, 16, ['#c8ccd4', '#b4b8c2', '#dfe2e8', '#a8adb8']);      // R3 cromo
  rect(0, 16, 16, 16, '#e8fcff'); rect(2, 18, 12, 12, '#60e8ff');          // R4 bombillo cian
  noise(16, 16, 16, 16, ['#6a4020', '#74482a', '#5e381a']);                 // R5 madera
  noise(32, 16, 16, 16, ['#2a2a34', '#30303c', '#26262e']);                 // R6 base oscura
  noise(48, 16, 16, 16, ['#0a1430', '#0c1838', '#08102a']);                 // R7 cielo de la pantalla

  // Pisos 32x8: muro con 3 puertas en arco. (64,0) cerradas, (64,8) abiertas en verde, (64,16) trampa
  const floorBand = (y0, door, glow) => {
    noise(64, y0, 32, 8, ['#3c4658', '#424c60', '#384254']);
    rect(64, y0 + 7, 32, 1, '#6a4020');
    [4, 14, 24].forEach(x => {
      art(64 + x, y0 + 1, ['.###.', '#ddd#', '#ddd#', '#ddd#', '#ddd#', '#ddd#'], { '#': '#222838', d: door });
      if (glow) { px(64 + x + 2, y0 + 3, glow); px(64 + x + 1, y0 + 4, glow); px(64 + x + 3, y0 + 4, glow); }
    });
  };
  floorBand(0, '#6a4020', '#b07840');
  floorBand(8, '#50ff80', '#e0ffe8');
  floorBand(16, '#ff3030', '#ffe040');
  // Ficha que sube (96,0) 8x12: peón dorado
  art(96, 0, ['..oo....', '.oYYo...', '.oYWo...', '..oo....', '.oYYo...', '..oYo...', '..oYo...', '.oYYYo..', 'oYYYYYo.', 'oOOOOOo.', '........', '........'],
    { o: '#8a5a00', Y: '#ffd83d', W: '#fff4b0', O: '#d9a514' });
  // Estrella (104,0) 8x8
  art(104, 0, ['...Y....', '...Y....', '..YWY...', 'YYYWYYY.', '.YYWYY..', '..YYY...', '.YY.YY..', 'Y.....Y.'], { Y: '#ffd83d', W: '#fff8c0' });
  // Moneda (112,0) cara y (120,0) canto
  art(112, 0, ['.oYYYYo.', 'oYWYYYYo', 'YWYOOYYY', 'YYOYYOYY', 'YYOYYOYY', 'YYYOOYYY', 'oYYYYYYo', '.oYYYYo.'],
    { o: '#9a6a00', Y: '#ffd83d', W: '#fff4b0', O: '#d9a514' });
  rect(120, 0, 8, 8, '#d9a514'); for (let x = 120; x < 128; x += 2) rect(x, 1, 1, 6, '#9a6a00');
  rect(96, 16, 8, 8, '#50ff80');   // destello verde
  rect(104, 16, 8, 8, '#ff3030');  // destello rojo
  rect(112, 16, 8, 8, '#ffd040');  // destello dorado
  // Botones
  rect(96, 24, 8, 8, '#801010'); rect(97, 25, 6, 6, '#e83030');
  rect(104, 24, 8, 8, '#1e7a2e'); rect(105, 25, 6, 6, '#3fd060');
  rect(64, 24, 16, 8, '#0e6a7a'); rect(65, 25, 14, 6, '#2ec8e0'); rect(65, 25, 14, 1, '#b0f4ff');
  art(70, 26, ['.##.', '####', '.##.', '.##.'], { '#': '#ffffff' }); // flecha arriba

  // Cartel "TOWER" 30x10 en (0,48)
  rect(0, 48, 30, 10, '#0a0c14');
  for (let x = 0; x < 30; x += 2) { px(x, 48, '#60e8ff'); px(x + 1, 57, '#60e8ff'); }
  const TOWER = [
    ['RRR', '.RR.', 'R...R', 'RRR', 'RRR.'], ['.R.', 'R..R', 'R...R', 'R..', 'R..R'], ['.R.', 'R..R', 'R.R.R', 'RR.', 'RRR.'],
    ['.R.', 'R..R', 'RR.RR', 'R..', 'R.R.'], ['.R.', '.RR.', 'R...R', 'RRR', 'R..R'],
  ].map(r => r.join('.'));
  art(3, 50, TOWER, { R: '#60e8ff' });
  // Bandera (32,48) 12x8
  rect(32, 48, 12, 8, '#1aa8c0'); rect(32, 48, 12, 1, '#60e8ff');
  art(35, 50, ['.YY.', 'YYYY', '.YY.'], { Y: '#ffd83d' });

  const tex = new Texture({ name: 'tower_machine.png' }).fromDataURL(cv.toDataURL()).add(false);

  // ---------- Helpers ----------
  const R = { stone: [0, 0], cyan: [16, 0], dark: [32, 0], chrome: [48, 0], bulb: [0, 16], wood: [16, 16], base: [32, 16], sky: [48, 16] };
  const uvOf = (k) => { const r = R[k]; return [r[0] + 1, r[1] + 1, r[0] + 15, r[1] + 15]; };
  const T = {
    floor: [64, 0, 96, 8], floorOk: [64, 8, 96, 16], floorTrap: [64, 16, 96, 24], pawn: [96, 0, 104, 10], star: [104, 0, 112, 8],
    coin: [112, 0, 120, 8], coinEdge: [120, 0, 128, 8], green: [97, 17, 103, 23], redglow: [105, 17, 111, 23], gold: [113, 17, 119, 23],
    btnRed: [96, 24, 104, 32], btnGreen: [104, 24, 112, 32], climbKey: [64, 24, 80, 32], sign: [0, 48, 30, 58], flag: [32, 48, 44, 56],
    stoneFull: [0, 0, 16, 16],
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
  const coinCube = (name, x, z, y, parent, rot = 0) => {
    const D = 1.0, d = 0.6, H = 0.4;
    [[D, d], [d, D]].forEach(([hx, hz], k) => {
      const c = cube(name + (k ? 'b' : 'a'), [x - hx, y, z - hz], [x + hx, y + H, z + hz], parent, 'coinEdge', { up: 'coin', down: 'coin' });
      if (rot) { c.rotation = [0, rot, 0]; c.origin = [x, y, z]; }
    });
  };

  // ---------- Torre ----------
  const body = group('body', [0, 0, 0]);
  cube('base', [-7.5, 0, -5.5], [7.5, 2, 5.5], body, 'base');
  cube('lower', [-7, 2, -5], [7, 3.5, 5], body, 'stoneFull');
  cube('tray_left', [-7, 3.5, -5], [-5, 7.5, 5], body, 'stoneFull');
  cube('tray_right', [5, 3.5, -5], [7, 7.5, 5], body, 'stoneFull');
  cube('tray_back', [-5, 3.5, -1], [5, 7.5, 5], body, 'dark');
  cube('tray_floor', [-5, 3.5, -5.3], [5, 4, -1], body, 'wood', { north: 'cyan' });
  cube('tray_roof', [-7, 7.5, -5.2], [7, 8.5, 5], body, 'cyan');
  cube('desk', [-7, 8.5, -5], [7, 11, 5], body, 'stoneFull');
  cube('desk_shelf', [-7.25, 10.5, -6.8], [7.25, 11.5, -4.5], body, 'wood', { north: 'cyan' });
  cube('btn_climb', [-6.2, 11.5, -6.4], [-0.6, 12.1, -4.9], body, 'btnGreen', { up: 'climbKey' });
  cube('btn_minus', [4.8, 11.5, -6.3], [6.2, 12, -5], body, 'btnRed');
  cube('btn_plus', [2.8, 11.5, -6.3], [4.2, 12, -5], body, 'btnGreen');
  cube('upper', [-7.25, 11.5, -4.5], [7.25, 29.5, 5], body, 'stoneFull');
  cube('edge_l', [-7.5, 2, -5.2], [-7, 29.5, -4.7], body, 'cyan');
  cube('edge_r', [7, 2, -5.2], [7.5, 29.5, -4.7], body, 'cyan');
  // Ventanitas en arco a los costados
  [[-7.35, 'west'], [7.25, 'east']].forEach(([x, f], i) => [16, 22].forEach((y, j) =>
    cube('side_window_' + i + j, [x, y, -1], [x + 0.1, y + 3, 1], body, 'dark', { [f]: [64 + 4, 1, 64 + 9, 7] })));

  const screen = group('screen', [0, 20, -5], body);
  cube('screen_frame', [-6.8, 12.3, -4.9], [6.8, 28.9, -4.5], screen, 'cyan');
  cube('screen_bg', [-6.4, 12.7, -5], [6.4, 28.5, -4.9], screen, 'sky');
  const flashG = group('flash_green', [0, 20, -5], screen);
  cube('flash_g', [-6.4, 12.7, -4.97], [6.4, 28.5, -4.92], flashG, 'green');
  const flashR = group('flash_red', [0, 20, -5], screen);
  cube('flash_r', [-6.4, 12.7, -4.97], [6.4, 28.5, -4.92], flashR, 'redglow');
  const flashY = group('flash_gold', [0, 20, -5], screen);
  cube('flash_y', [-6.4, 12.7, -4.97], [6.4, 28.5, -4.92], flashY, 'gold');

  // Pisos: K = 0 abajo. La banda tapada delante; la abierta (verde) y la trampa (roja) detrás.
  const FLOOR_Y0 = 13.6, FLOOR_H = 1.95, BAND_H = 0.8, X1 = -6.1, X2 = 4.4;
  const floorY = (k) => FLOOR_Y0 + k * FLOOR_H;
  const floors = [];
  for (let k = 0; k < 8; k++) {
    const y = floorY(k), cx = (X1 + X2) / 2;
    const fg = group('floor_' + k, [cx, y, -5], screen);
    cube('floor_' + k, [X1, y - BAND_H, -5.3], [X2, y + BAND_H, -5.0], fg, 'stone', { north: 'floor' });
    const ok = group('floor_ok_' + k, [cx, y, -5], fg);
    cube('floor_ok_' + k, [X1, y - BAND_H, -4.98], [X2, y + BAND_H, -4.93], ok, 'dark', { north: 'floorOk' });
    const trap = group('floor_trap_' + k, [cx, y, -5], fg);
    cube('floor_trap_' + k, [X1, y - BAND_H, -4.98], [X2, y + BAND_H, -4.93], trap, 'dark', { north: 'floorTrap' });
    floors.push({ fg, ok, trap });
  }
  // Riel y ficha (a la izquierda de quien mira = +X)
  const RAIL_X = 5.4;
  cube('rail', [RAIL_X - 0.12, 12.7, -5.1], [RAIL_X + 0.12, 28.5, -5.0], screen, 'chrome');
  const pawnY = (k) => floorY(k) - BAND_H; // la ficha se para en el piso K (K = 8: arriba de la pantalla)
  const pawn = group('pawn', [RAIL_X, pawnY(0), -5.3], screen);
  cube('pawn', [RAIL_X - 0.55, pawnY(0), -5.5], [RAIL_X + 0.55, pawnY(0) + 1.4, -5.15], pawn, 'chrome', { north: 'pawn', south: 'pawn' });

  // Monedas que caen al cobrar (escondidas dentro del pupitre)
  const coins = group('coins', [0, 9, -3.4], body);
  [[-1.6, -3.0, 0], [0.4, -3.6, 25], [2.2, -2.8, 50], [-0.4, -2.4, 70], [1.2, -3.2, 15]].forEach(([x, z, rot], k) =>
    coinCube('coin_' + k, x, z, 9 + k * 0.42, coins, rot));

  // Corona con almenas, cartel y bandera
  const crown = group('crown', [0, 29.5, 0]);
  cube('crown', [-7.8, 29.5, -5.8], [7.8, 33.5, 5.8], crown, 'stoneFull', { north: 'sign', south: 'sign' });
  cube('crown_trim', [-8, 29.2, -6], [8, 29.8, 6], crown, 'cyan');
  [[-7.8, -5.8], [-1.2, -5.8], [5.4, -5.8], [-7.8, 3.4], [-1.2, 3.4], [5.4, 3.4]].forEach(([x, z], i) =>
    cube('merlon_' + i, [x, 33.5, z], [x + 2.4, 35.3, z + 2.4], crown, 'stoneFull'));
  [[-7.8, -1.2], [5.4, -1.2]].forEach(([x, z], i) => cube('merlon_side_' + i, [x, 33.5, z], [x + 2.4, 35.3, z + 2.4], crown, 'stoneFull'));
  const flagG = group('flag', [0.2, 35.3, 1], crown);
  cube('pole', [0, 33.5, 0.8], [0.4, 40, 1.2], flagG, 'chrome');
  cube('flag', [0.4, 37.4, 1], [4.6, 39.8, 1.05], flagG, 'cyan', { north: 'flag', south: 'flag' });
  const star = group('star', [0, 33, -1], crown);
  cube('star', [-1.2, 32, -1.05], [1.2, 34.4, -0.95], star, 'gold', { north: 'star', south: 'star' });

  const la = group('lights_a', [0, 30, 0], crown), lb = group('lights_b', [0, 30, 0], crown);
  for (let i = 0; i < 7; i++) {
    const y = 13.4 + i * 2.4;
    cube('bulb_l_' + i, [6.85, y - 0.35, -5.3], [7.4, y + 0.35, -4.7], i % 2 ? la : lb, 'bulb');
    cube('bulb_r_' + i, [-7.4, y - 0.35, -5.3], [-6.85, y + 0.35, -4.7], i % 2 ? lb : la, 'bulb');
  }

  const hitbox = group('hitbox', [0, 0, 0]);
  cube('hitbox', [-8, 0, -8], [8, 32, 8], hitbox, 'dark');

  // ---------- Animaciones ----------
  const kf = (anim, grp, channel, list, interp = 'linear') => {
    const a = anim.getBoneAnimator(grp);
    list.forEach(([t, x, y, z]) => a.addKeyframe({ channel, time: +t.toFixed(3), interpolation: interp, data_points: [{ x, y, z }] }));
  };
  const blink = (len, step, on) => { const o = []; for (let t = 0; t <= len + 0.0001; t += step) o.push([+t.toFixed(2), ...(Math.round(t / step) % 2 ? [on, on, on] : [1, 1, 1])]); return o; };
  const invert = (list, on) => list.map(([t, x]) => [t, ...(x > 1 ? [1, 1, 1] : [on, on, on])]);
  const flashKeys = (n, step) => { const o = []; for (let i = 0; i <= n; i++) o.push([i * step, 0, 0, i % 2 === 0 && i < n - 1 ? -0.15 : 0]); return o; };

  const idle = new Blockbench.Animation({ name: 'idle', loop: 'loop', length: 1.2 }).add();
  kf(idle, la, 'scale', [[0, 1, 1, 1], [0.6, 1.25, 1.25, 1.25], [1.2, 1, 1, 1]], 'catmullrom');
  kf(idle, lb, 'scale', [[0, 1.25, 1.25, 1.25], [0.6, 1, 1, 1], [1.2, 1.25, 1.25, 1.25]], 'catmullrom');
  kf(idle, flagG, 'rotation', [[0, 0, -8, 0], [0.6, 0, 8, 0], [1.2, 0, -8, 0]], 'catmullrom');

  // start: los pisos se iluminan de abajo arriba (ola)
  const start = new Blockbench.Animation({ name: 'start', loop: 'once', length: 1.3 }).add();
  floors.forEach(({ fg }, k) => {
    const d = k * 0.08;
    kf(start, fg, 'scale', [[0, 1, 1, 1], [d, 1, 1, 1], [d + 0.1, 1.06, 1.3, 1], [d + 0.22, 1, 1, 1]]);
  });
  const bs = blink(1.3, 0.1, 1.4);
  kf(start, la, 'scale', bs, 'step'); kf(start, lb, 'scale', invert(bs, 1.4), 'step');

  // floor_K_ok / floor_K_trap: la banda tapada se achica y aparece la abierta o la trampa (se queda)
  floors.forEach(({ fg, ok, trap }, k) => {
    [['ok', ok], ['trap', trap]].forEach(([kind, grp]) => {
      const an = new Blockbench.Animation({ name: 'floor_' + k + '_' + kind, loop: 'hold', length: 0.5 }).add();
      kf(an, grp, 'position', [[0, 0, 0, 0], [0.01, 0, 0, -0.4], [0.5, 0, 0, -0.4]], 'step');
      kf(an, grp, 'scale', [[0, 1, 0.01, 1], [0.14, 1, 1.35, 1], [0.26, 1, 1, 1], [0.5, 1, 1, 1]]);
    });
  });

  // climb_K: la ficha salta del piso K-1 al K
  for (let k = 1; k <= 8; k++) {
    const an = new Blockbench.Animation({ name: 'climb_' + k, loop: 'hold', length: 0.5 }).add();
    const a = pawnY(k - 1) - pawnY(0), b = pawnY(k) - pawnY(0);
    kf(an, pawn, 'position', [[0, 0, a, 0], [0.18, 0, b + 0.7, -0.2], [0.32, 0, b, 0], [0.5, 0, b, 0]], 'catmullrom');
    kf(an, pawn, 'scale', [[0, 1, 1, 1], [0.3, 1, 1, 1], [0.36, 1.25, 0.8, 1], [0.46, 1, 1, 1]]);
  }
  // fall_K: la ficha se cae desde el piso K girando y queda tirada abajo
  for (let k = 0; k < 8; k++) {
    const an = new Blockbench.Animation({ name: 'fall_' + k, loop: 'hold', length: 1.0 }).add();
    const a = pawnY(k) - pawnY(0);
    kf(an, pawn, 'position', [[0, 0, a, 0], [0.12, -0.4, a + 0.6, -0.3], [0.7, -0.8, -0.4, -0.3], [1.0, -0.8, -0.4, -0.3]]);
    kf(an, pawn, 'rotation', [[0, 0, 0, 0], [0.7, 0, 0, 450], [1.0, 0, 0, 450]]);
  }

  // boom: la torre tiembla y la pantalla destella en rojo
  const boom = new Blockbench.Animation({ name: 'boom', loop: 'once', length: 1.6 }).add();
  kf(boom, body, 'position', [[0, 0, 0, 0], [0.05, 0.6, 0, 0], [0.1, -0.6, 0.2, 0], [0.15, 0.5, 0, 0], [0.2, -0.4, 0.1, 0],
    [0.25, 0.3, 0, 0], [0.3, -0.2, 0, 0], [0.35, 0.1, 0, 0], [0.4, 0, 0, 0]]);
  kf(boom, flashR, 'position', flashKeys(14, 0.1), 'step');
  const bb = blink(1.6, 0.2, 0.6);
  kf(boom, la, 'scale', bb, 'step'); kf(boom, lb, 'scale', bb, 'step');

  // cashout: caen monedas a la bandeja y destello verde
  const cash = new Blockbench.Animation({ name: 'cashout', loop: 'once', length: 2.6 }).add();
  kf(cash, coins, 'position', [[0, 0, 0, 0], [0.15, 0, 0, 0], [0.55, 0, -5, 0], [0.65, 0, -4.7, 0], [0.75, 0, -5, 0], [2.2, 0, -5, 0]]);
  kf(cash, coins, 'scale', [[0, 1, 1, 1], [2.2, 1, 1, 1], [2.5, 0.01, 0.01, 0.01]]);
  kf(cash, flashG, 'position', flashKeys(10, 0.12), 'step');
  const bc = blink(2.6, 0.08, 1.45);
  kf(cash, la, 'scale', bc, 'step'); kf(cash, lb, 'scale', invert(bc, 1.45), 'step');

  // top: sale una estrella girando sobre la torre, destello dorado y luces a tope
  const top = new Blockbench.Animation({ name: 'top', loop: 'once', length: 3.0 }).add();
  kf(top, star, 'position', [[0, 0, 0, 0], [0.5, 0, 8, -5], [2.6, 0, 8.5, -5], [3.0, 0, 0, 0]], 'catmullrom');
  kf(top, star, 'rotation', [[0, 0, 0, 0], [3.0, 0, 1080, 0]]);
  kf(top, star, 'scale', [[0, 0.5, 0.5, 0.5], [0.5, 1.8, 1.8, 1.8], [2.6, 1.8, 1.8, 1.8], [3.0, 0.5, 0.5, 0.5]]);
  kf(top, flashY, 'position', flashKeys(14, 0.12), 'step');
  const bt = blink(3.0, 0.08, 1.5);
  kf(top, la, 'scale', bt, 'step'); kf(top, lb, 'scale', invert(bt, 1.5), 'step');

  Canvas.updateAll();
  return 'ok';
})();
